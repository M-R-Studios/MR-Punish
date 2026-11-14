package com.mrstudios.punish.network;

import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.report.Report;
import com.mrstudios.punish.storage.FrozenRepo;
import com.mrstudios.punish.storage.PunishmentRepo;
import com.mrstudios.punish.storage.ReportRepo;
import com.mrstudios.punish.storage.Storage;
import com.mrstudios.punish.storage.SyncRepo;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Applies changes made on other servers sharing the MySQL database (DESIGN.md 9.4). Every
 * {@code network.poll-seconds} it reads the {@code sync_events} written since the last poll (starting
 * after the newest one at startup, so nothing old is replayed), skips this server's own, re-reads
 * the row each event points at (an event is never trusted on its own), and applies it on the main
 * thread: kick for a ban, refresh for a mute, freeze or unfreeze, notify staff of a report.
 */
public final class SyncPoller {

    static final int BATCH = 500;

    /** What to do on this server, read in the poll; applied on the main thread. */
    public interface Target {

        void punishmentIssued(Punishment p);

        void punishmentRemoved(Punishment p);

        void frozen(FrozenRepo.Frozen row);

        void unfrozen(UUID uuid);

        void reportCreated(Report report);
    }

    /** An event with the row it points at, as the database has it now. */
    record Resolved(SyncRepo.Event event, Object row) {
    }

    private final Storage storage;
    private final Target target;
    private final Executor mainThread;
    private final IntSupplier pollSeconds;
    private final LongSupplier clock;
    private final Logger log;
    private volatile long lastId = -1;
    private volatile boolean polling;
    private volatile long lastPoll;
    private volatile boolean warnedFailure;

    public SyncPoller(Storage storage, Target target, Executor mainThread, IntSupplier pollSeconds, LongSupplier clock,
            Logger log) {
        this.storage = storage;
        this.target = target;
        this.mainThread = mainThread;
        this.pollSeconds = pollSeconds;
        this.clock = clock;
        this.log = log;
    }

    /** Only MySQL databases are shared between servers. */
    public boolean active() {
        return new SyncRepo(storage.database()).enabled();
    }

    /** Called every second (main thread); polls when {@code network.poll-seconds} have passed. */
    public void tick() {
        long now = clock.getAsLong();
        if (now - lastPoll < pollSeconds.getAsInt() * 1000L - 50) {
            return;
        }
        lastPoll = now;
        poll();
    }

    /**
     * One poll: reads new events off the main thread, then applies them on it. The future completes
     * once they're applied (at once if a poll is already running or the database is down).
     */
    public CompletableFuture<Void> poll() {
        if (polling || storage.executor().isShutDown() || !storage.health().isUp()) {
            return CompletableFuture.completedFuture(null);
        }
        polling = true;
        CompletableFuture<Void> done = new CompletableFuture<>();
        long from = lastId;
        storage.executor().submit("reading changes from other servers", c -> {
            SyncRepo sync = new SyncRepo(storage.database());
            if (from < 0) {
                return new Read(sync.maxId(c), List.of());
            }
            List<SyncRepo.Event> events = sync.after(c, from, BATCH);
            long last = events.isEmpty() ? from : events.get(events.size() - 1).id();
            PunishmentRepo punishments = new PunishmentRepo(storage.database());
            FrozenRepo frozen = new FrozenRepo(storage.database());
            ReportRepo reports = new ReportRepo(storage.database());
            List<Resolved> resolved = new ArrayList<>();
            for (SyncRepo.Event e : events) {
                if (SyncRepo.fromHere(e.origin())) {
                    continue;
                }
                Object row = switch (e.kind()) {
                    case SyncRepo.PUNISH, SyncRepo.REMOVE -> e.refId() == null ? null
                            : punishments.byId(c, e.refId()).orElse(null);
                    case SyncRepo.FREEZE, SyncRepo.UNFREEZE -> e.targetUuid() == null ? null
                            : frozen.get(c, e.targetUuid());
                    case SyncRepo.REPORT -> e.refId() == null ? null : reports.byId(c, e.refId()).orElse(null);
                    default -> null; // From a newer version: ignored.
                };
                if (row != null) {
                    resolved.add(new Resolved(e, row));
                }
            }
            return new Read(last, resolved);
        }).whenComplete((read, error) -> {
            if (error != null) {
                polling = false;
                if (!warnedFailure) {
                    warnedFailure = true;
                    log.log(Level.WARNING, "Couldn't read changes from other servers; trying again.", error);
                }
                done.complete(null);
                return;
            }
            warnedFailure = false;
            mainThread.execute(() -> {
                try {
                    for (Resolved r : read.resolved()) {
                        apply(r);
                    }
                } finally {
                    lastId = Math.max(lastId, read.last());
                    polling = false;
                    done.complete(null);
                }
            });
        });
        return done;
    }

    record Read(long last, List<Resolved> resolved) {
    }

    /** Main thread. Each event on its own: one that fails doesn't stop the rest. */
    void apply(Resolved r) {
        try {
            switch (r.event().kind()) {
                case SyncRepo.PUNISH -> target.punishmentIssued((Punishment) r.row());
                case SyncRepo.REMOVE -> {
                    Punishment p = (Punishment) r.row();
                    if (p.status() == Punishment.StoredStatus.REMOVED) {
                        target.punishmentRemoved(p);
                    }
                }
                case SyncRepo.FREEZE -> {
                    @SuppressWarnings("unchecked")
                    Optional<FrozenRepo.Frozen> row = (Optional<FrozenRepo.Frozen>) r.row();
                    // Unfrozen again meanwhile: nothing to do.
                    row.ifPresent(target::frozen);
                }
                case SyncRepo.UNFREEZE -> {
                    @SuppressWarnings("unchecked")
                    Optional<FrozenRepo.Frozen> row = (Optional<FrozenRepo.Frozen>) r.row();
                    // Frozen again meanwhile: stays frozen.
                    if (row.isEmpty()) {
                        target.unfrozen(r.event().targetUuid());
                    }
                }
                case SyncRepo.REPORT -> target.reportCreated((Report) r.row());
                default -> { }
            }
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Couldn't apply a change from another server (" + r.event().kind() + " #"
                    + r.event().id() + ")", e);
        }
    }

    /** The last event read, -1 before the first poll. */
    public long lastId() {
        return lastId;
    }
}
