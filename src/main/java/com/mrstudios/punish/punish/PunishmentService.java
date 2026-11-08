package com.mrstudios.punish.punish;

import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.punish.Requests.IssueResult;
import com.mrstudios.punish.punish.Requests.RemoveResult;
import com.mrstudios.punish.rank.Authority;
import com.mrstudios.punish.rank.OfflineRanks;
import com.mrstudios.punish.storage.ActionRepo;
import com.mrstudios.punish.storage.PunishmentRepo;
import com.mrstudios.punish.storage.Storage;
import com.mrstudios.punish.storage.StorageException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one way punishments are issued and removed (DESIGN.md 7.10, 7.6). Callers are on the main
 * thread; the returned futures complete on the main thread too, after the follow-up (notifications)
 * has run. Nothing is applied unless its transaction committed.
 */
public final class PunishmentService {

    /** How long to wait for LuckPerms to load an offline user before falling back to the cache (X49). */
    static final long LUCKPERMS_TIMEOUT_MS = 3000;

    public static final String REMOVE = "mrpunish.remove";
    public static final String IP = "mrpunish.ip";

    private final Storage storage;
    private final Supplier<PluginState> state;
    private final OfflineRanks offlineRanks;
    private final Executor mainThread;
    private final Notifier notifier;
    private final LongSupplier clock;
    private final Logger log;
    private final InFlight inFlight = new InFlight();
    private final List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Applies a committed change: caches, kicks, messages to the target (main thread, after the commit). */
    public interface Listener {

        default void issued(PluginState snapshot, Requests.Issue request, IssueResult.Committed committed) {
        }

        default void removed(PluginState snapshot, Requests.Remove request, RemoveResult.Removed removed) {
        }
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public PunishmentService(Storage storage, Supplier<PluginState> state, OfflineRanks offlineRanks, Executor mainThread,
            Notifier notifier, LongSupplier clock, Logger log) {
        this.storage = storage;
        this.state = state;
        this.offlineRanks = offlineRanks;
        this.mainThread = mainThread;
        this.notifier = notifier;
        this.clock = clock;
        this.log = log;
    }

    /** The permission a type needs (IP types need {@link #IP} as well). */
    public static String permission(PunishmentType type) {
        return switch (type) {
            case WARN -> "mrpunish.warn";
            case MUTE, IP_MUTE -> "mrpunish.mute";
            case BAN, IP_BAN -> "mrpunish.ban";
        };
    }

    public InFlight inFlight() {
        return inFlight;
    }

    private Optional<String> missingPermission(Requests.Actor actor, PunishmentType type, Reason reason) {
        List<String> needed = new ArrayList<>();
        needed.add(permission(type));
        if (type.isIp()) {
            needed.add(IP);
        }
        if (reason != null && reason.permission() != null) {
            needed.add(reason.permission());
        }
        return needed.stream().filter(p -> !actor.permissions().test(p)).findFirst();
    }

    private Optional<String> unavailable() {
        if (storage.executor().isShutDown()) {
            return Optional.of("the server is stopping");
        }
        if (!storage.health().isUp()) {
            return Optional.of(storage.health().reason());
        }
        return Optional.empty();
    }

    /** What issuing {@code reason} to {@code target} would do now; read-only (DESIGN.md 7.10 step 2). */
    public CompletableFuture<Preview> preview(Requests.Target target, Reason reason) {
        Settings settings = state.get().settings();
        long now = clock.getAsLong();
        PunishmentRepo.Subject subject = PunishmentRepo.Subject.of(reason.type(), target.uuid(), target.ipHash());
        return storage.executor().submit("previewing a punishment for " + target.name(), c -> {
            PunishmentRepo repo = new PunishmentRepo(storage.database());
            List<ReplacementRules.Active> active = new ArrayList<>();
            if (reason.type().hasDuration()) {
                for (Punishment p : repo.active(c, reason.type(), subject, now)) {
                    active.add(new ReplacementRules.Active(p.id(), p.expiresAt()));
                }
            }
            return EscalationCalculator.preview(reason, settings.historyPeriod(),
                    repo.priors(c, reason.type(), reason.key(), subject), active, now);
        });
    }

    /** Issues a punishment (DESIGN.md 7.10). Main thread. */
    public CompletableFuture<IssueResult> issue(Requests.Issue request) {
        PluginState snapshot = state.get();
        Settings settings = snapshot.settings();
        Optional<String> down = unavailable();
        if (down.isPresent()) {
            return CompletableFuture.completedFuture(new IssueResult.Unavailable(down.get()));
        }
        Optional<String> missing = missingPermission(request.actor(), request.type(), request.reason());
        if (missing.isPresent()) {
            return CompletableFuture.completedFuture(new IssueResult.NoPermission(missing.get()));
        }
        Requests.Target target = request.target();
        Authority.Standing actor = request.actor().standing();
        if (target.live() != null) {
            // Fast fail on the main thread; the transaction checks again with what it reads.
            List<Authority.Standing> affected = new ArrayList<>();
            affected.add(target.live());
            affected.addAll(target.affected());
            Authority.Verdict verdict = Authority.check(actor, affected,
                    request.type().isIp() ? Authority.Action.IP_PUNISH : Authority.Action.PUNISH);
            if (verdict != Authority.Verdict.OK) {
                logRefusal(request, verdict, settings.effectiveServerName());
                return CompletableFuture.completedFuture(
                        new IssueResult.Refused(verdict, Authority.refusedCount(actor, affected)));
            }
        }
        if (!inFlight.acquire(actor.uuid(), target.uuid())) {
            return CompletableFuture.completedFuture(new IssueResult.Busy());
        }

        CompletableFuture<Optional<Authority.Standing>> lp = target.live() != null
                ? CompletableFuture.completedFuture(Optional.empty())
                : offlineRank(target.uuid());
        IssueTx.Context context = new IssueTx.Context(settings.historyPeriod(), settings.warningActiveFor(),
                settings.effectiveServerName());
        CompletableFuture<IssueResult> result = lp.thenCompose(fromLuckPerms -> storage.executor()
                .transaction("issuing a " + request.type() + " to " + target.name(),
                        c -> new IssueTx(storage.database()).run(c, request, fromLuckPerms, context, clock.getAsLong())));
        return result
                .handle((r, e) -> {
                    inFlight.release(actor.uuid(), target.uuid());
                    return e == null ? r : issueFailure(e, "issuing a " + request.type() + " to " + target.name());
                })
                .thenApplyAsync(r -> {
                    if (r instanceof IssueResult.Committed committed) {
                        // The punishment is saved whatever happens here: a notification bug mustn't hide that.
                        for (Listener listener : listeners) {
                            try {
                                listener.issued(snapshot, request, committed);
                            } catch (RuntimeException e) {
                                log.log(Level.SEVERE, "Couldn't apply punishment #" + committed.punishment().id()
                                        + " (it is saved and applies at the next login)", e);
                            }
                        }
                        try {
                            notifier.issued(snapshot, request, committed);
                        } catch (RuntimeException e) {
                            log.log(Level.SEVERE, "Couldn't send the notification for punishment #"
                                    + committed.punishment().id(), e);
                        }
                    }
                    return r;
                }, mainThread);
    }

    /** Removes punishments (DESIGN.md 7.6). Main thread. */
    public CompletableFuture<RemoveResult> remove(Requests.Remove request) {
        PluginState snapshot = state.get();
        Optional<String> down = unavailable();
        if (down.isPresent()) {
            return CompletableFuture.completedFuture(new RemoveResult.Unavailable(down.get()));
        }
        if (!request.actor().permissions().test(REMOVE)) {
            return CompletableFuture.completedFuture(new RemoveResult.NoPermission(REMOVE));
        }
        if (request.type().isIp() && !request.actor().permissions().test(IP)) {
            return CompletableFuture.completedFuture(new RemoveResult.NoPermission(IP));
        }
        UUID actor = request.actor().uuid();
        UUID target = request.target().uuid();
        if (!inFlight.acquire(actor, target)) {
            return CompletableFuture.completedFuture(new RemoveResult.Busy());
        }
        String reason = request.reason() == null || request.reason().isBlank()
                ? snapshot.messages().raw("notify.no-reason") : request.reason().strip();
        String server = snapshot.settings().effectiveServerName();
        String what = "removing " + request.type() + " from " + request.target().name();
        return storage.executor()
                .transaction(what, c -> new RemoveTx(storage.database()).run(c, request, reason, server, clock.getAsLong()))
                .handle((r, e) -> {
                    inFlight.release(actor, target);
                    return e == null ? r : removeFailure(e, what);
                })
                .thenApplyAsync(r -> {
                    if (r instanceof RemoveResult.Removed removed) {
                        for (Listener listener : listeners) {
                            try {
                                listener.removed(snapshot, request, removed);
                            } catch (RuntimeException e) {
                                log.log(Level.SEVERE, "Couldn't apply a removal (it is saved)", e);
                            }
                        }
                        try {
                            notifier.removed(snapshot, request, removed);
                        } catch (RuntimeException e) {
                            log.log(Level.SEVERE, "Couldn't send the notification for a removal", e);
                        }
                    }
                    return r;
                }, mainThread);
    }

    private CompletableFuture<Optional<Authority.Standing>> offlineRank(UUID uuid) {
        CompletableFuture<Optional<Authority.Standing>> loading;
        try {
            loading = offlineRanks.load(uuid);
        } catch (RuntimeException e) {
            loading = CompletableFuture.failedFuture(e);
        }
        return loading.orTimeout(LUCKPERMS_TIMEOUT_MS, TimeUnit.MILLISECONDS).exceptionally(e -> {
            log.warning("Couldn't load " + uuid + " from LuckPerms (" + cause(e) + "); using the rank cached at their"
                    + " last visit.");
            return Optional.empty();
        });
    }

    private void logRefusal(Requests.Issue request, Authority.Verdict verdict, String server) {
        Authority.Standing actor = request.actor().standing();
        storage.executor().submit("logging a refused punishment", c -> {
            new ActionRepo(storage.database()).insert(c, new ActionRepo.Action(clock.getAsLong(), server, actor.uuid(),
                    actor.name(), "REFUSED", request.target().uuid(), request.target().name(), null,
                    request.type() + " " + request.reason().key() + " refused: " + verdict));
            return null;
        });
    }

    private IssueResult issueFailure(Throwable e, String what) {
        Throwable cause = unwrap(e);
        if (cause instanceof StorageException se) {
            return se.errorId() == null ? new IssueResult.Unavailable(se.getMessage()) : new IssueResult.Failed(se.errorId());
        }
        return new IssueResult.Failed(logUnexpected(cause, what));
    }

    private RemoveResult removeFailure(Throwable e, String what) {
        Throwable cause = unwrap(e);
        if (cause instanceof StorageException se) {
            return se.errorId() == null ? new RemoveResult.Unavailable(se.getMessage())
                    : new RemoveResult.Failed(se.errorId());
        }
        return new RemoveResult.Failed(logUnexpected(cause, what));
    }

    /** A timeout or a bug outside the transaction: logged with its own error id. */
    private String logUnexpected(Throwable cause, String what) {
        String id = String.format("%08x", java.util.concurrent.ThreadLocalRandom.current().nextInt());
        if (cause instanceof TimeoutException) {
            log.warning("Error " + id + ": " + what + " took longer than " + 10 + " seconds. It may still be saved:"
                    + " check the history before trying again.");
        } else {
            log.log(Level.SEVERE, "Error " + id + " while " + what, cause);
        }
        return id;
    }

    private static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static String cause(Throwable e) {
        Throwable t = unwrap(e);
        return t instanceof TimeoutException ? "timed out" : String.valueOf(t.getMessage());
    }
}
