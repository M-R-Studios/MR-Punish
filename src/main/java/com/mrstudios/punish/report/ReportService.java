package com.mrstudios.punish.report;

import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.report.Report.Status;
import com.mrstudios.punish.storage.ActionRepo;
import com.mrstudios.punish.storage.SyncRepo;
import com.mrstudios.punish.storage.PlayerRepo;
import com.mrstudios.punish.storage.ReportRepo;
import com.mrstudios.punish.storage.Storage;
import com.mrstudios.punish.storage.StorageException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reports (DESIGN.md 13.1, 13.2): making them, and every state change. Callers are on the main
 * thread and the futures complete on the main thread. Every state change re-reads the report in a
 * transaction and is optimistic-locked on the version the caller saw, so two staff acting on the
 * same report at once can't both succeed: the second gets {@link Change.Changed}. Nothing is
 * deleted; notes are append-only.
 */
public final class ReportService {

    public static final String REPORT = "mrpunish.report";
    public static final String REPORTS = "mrpunish.reports";
    public static final String NO_COOLDOWN = "mrpunish.report.nocooldown";
    public static final String ADMIN = "mrpunish.admin";
    public static final String STAFF = "mrpunish.staff";
    /** {@code deliveries.kind} for a reporter's outcome message (ref = report id). */
    public static final String DELIVERY = "REPORT";

    /** Who is acting, read now. @param uuid null for console */
    public record Actor(UUID uuid, String name, Predicate<String> permissions) {

        boolean admin() {
            return permissions.test(ADMIN);
        }
    }

    /** Where the reporter was standing (world null if unknown). */
    public record Where(String world, Integer x, Integer y, Integer z) {

        public static final Where UNKNOWN = new Where(null, null, null, null);
    }

    public sealed interface Created {

        record Sent(Report report) implements Created {
        }

        record Duplicate(long existingId) implements Created {
        }

        record TooMany(int max) implements Created {
        }

        record Cooldown(long remainingMs) implements Created {
        }

        record Self() implements Created {
        }

        record Staff() implements Created {
        }

        record NoPermission() implements Created {
        }

        record Unavailable(String why) implements Created {
        }

        record Failed(String errorId) implements Created {
        }
    }

    public sealed interface Change {

        /** Saved; {@code report} is as stored now. */
        record Done(Report report) implements Change {
        }

        /** Someone else changed it since the caller read it; {@code current} is the latest version. */
        record Changed(Report current) implements Change {
        }

        record NotFound(long id) implements Change {
        }

        record Closed(Report report) implements Change {
        }

        record ClaimedByOther(Report report) implements Change {
        }

        record NoPermission() implements Change {
        }

        record Unavailable(String why) implements Change {
        }

        record Failed(String errorId) implements Change {
        }
    }

    /** What happens after a commit, on the main thread (notifications, the reporter's message). */
    public interface Listener {

        default void created(Report report) {
        }

        default void closed(Report report, Actor by) {
        }

        default void claimExpired(Report report) {
        }
    }

    private final Storage storage;
    private final Supplier<PluginState> state;
    private final Executor mainThread;
    private final LongSupplier clock;
    private final Logger log;
    private final Map<UUID, Long> lastReport = new ConcurrentHashMap<>();
    private volatile Listener listener = new Listener() { };
    private volatile int openCount;

    public ReportService(Storage storage, Supplier<PluginState> state, Executor mainThread, LongSupplier clock,
            Logger log) {
        this.storage = storage;
        this.state = state;
        this.mainThread = mainThread;
        this.clock = clock;
        this.log = log;
    }

    public void listener(Listener listener) {
        this.listener = listener;
    }

    private ReportRepo repo() {
        return new ReportRepo(storage.database());
    }

    private ActionRepo actions() {
        return new ActionRepo(storage.database());
    }

    private String server() {
        return state.get().settings().effectiveServerName();
    }

    private Optional<String> down() {
        if (storage.executor().isShutDown()) {
            return Optional.of("the server is stopping");
        }
        return storage.health().isUp() ? Optional.empty() : Optional.of(storage.health().reason());
    }

    // ---- Making a report ----

    /**
     * Makes a report (main thread).
     *
     * @param targetIsStaff whether the target is staff right now if they're online, else null (the
     *                      cached rank from their last visit decides)
     * @param reasonText    already cleaned; the display of a configured reason or a typed text
     */
    public CompletableFuture<Created> create(Actor reporter, UUID target, String targetName, Boolean targetIsStaff,
            String reasonKey, String reasonText, Where where) {
        var settings = state.get().settings().reports();
        if (!reporter.permissions().test(REPORT)) {
            return done(new Created.NoPermission());
        }
        if (reporter.uuid().equals(target)) {
            return done(new Created.Self());
        }
        if (!settings.allowReportingStaff() && Boolean.TRUE.equals(targetIsStaff)) {
            return done(new Created.Staff());
        }
        long now = clock.getAsLong();
        if (!reporter.permissions().test(NO_COOLDOWN)) {
            Long last = lastReport.get(reporter.uuid());
            long wait = settings.cooldownSeconds() * 1000L;
            if (last != null && now - last < wait) {
                return done(new Created.Cooldown(wait - (now - last)));
            }
        }
        Optional<String> down = down();
        if (down.isPresent()) {
            return done(new Created.Unavailable(down.get()));
        }
        boolean checkOfflineStaff = !settings.allowReportingStaff() && targetIsStaff == null;
        int max = settings.maxOpenPerPlayer();
        Report draft = new Report(0, reporter.uuid(), reporter.name(), target, targetName, reasonKey, reasonText,
                server(), where.world(), where.x(), where.y(), where.z(), now, Status.OPEN, null, null, null, null,
                null, null, null, 0);
        CompletableFuture<Created> work = storage.executor().transaction("saving a report on " + targetName, c -> {
            if (checkOfflineStaff) {
                Optional<PlayerRepo.PlayerRecord> seen = new PlayerRepo(storage.database()).byUuid(c, target);
                if (seen.isPresent() && seen.get().rankKey() != null) {
                    return new Created.Staff();
                }
            }
            ReportRepo repo = repo();
            Optional<Report> duplicate = repo.openAbout(c, reporter.uuid(), target);
            if (duplicate.isPresent()) {
                return new Created.Duplicate(duplicate.get().id());
            }
            if (repo.openBy(c, reporter.uuid()) >= max) {
                return new Created.TooMany(max);
            }
            long id = repo.insert(c, draft);
            actions().insert(c, new ActionRepo.Action(now, draft.server(), reporter.uuid(), reporter.name(),
                    "REPORT_CREATE", target, targetName, id, reasonText));
            // Staff on the other servers are told too (DESIGN.md 9.4).
            new SyncRepo(storage.database()).insert(c, now, draft.server(), SyncRepo.REPORT, target, null, id);
            return new Created.Sent(repo.byId(c, id).orElseThrow());
        });
        return work.handle((r, e) -> e == null ? r : createFailure(e, "saving a report on " + targetName))
                .thenApplyAsync(r -> {
                    if (r instanceof Created.Sent sent) {
                        lastReport.put(reporter.uuid(), now);
                        openCount++;
                        try {
                            listener.created(sent.report());
                        } catch (RuntimeException ex) {
                            log.log(Level.SEVERE, "Couldn't send the notifications for report #" + sent.report().id(),
                                    ex);
                        }
                    }
                    return r;
                }, mainThread);
    }

    private static <T> CompletableFuture<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }

    // ---- State changes ----

    @FunctionalInterface
    private interface Step {

        /** The change on the re-read {@code r}; null means "nothing changed, it was stale". */
        Change apply(Connection c, Report r, long now) throws SQLException;
    }

    private CompletableFuture<Change> change(Actor actor, long id, int version, String what, Step step) {
        if (!actor.permissions().test(REPORTS)) {
            return done(new Change.NoPermission());
        }
        Optional<String> down = down();
        if (down.isPresent()) {
            return done(new Change.Unavailable(down.get()));
        }
        long now = clock.getAsLong();
        return storage.executor().transaction(what + " report #" + id, c -> {
            ReportRepo repo = repo();
            Optional<Report> found = repo.byId(c, id);
            if (found.isEmpty()) {
                return new Change.NotFound(id);
            }
            Report r = found.get();
            if (r.version() != version) {
                return new Change.Changed(r);
            }
            Change result = step.apply(c, r, now);
            return result != null ? result : new Change.Changed(repo.byId(c, id).orElseThrow());
        }).handle((r, e) -> e == null ? r : changeFailure(e, what + " report #" + id));
    }

    private Change done(Connection c, long id) throws SQLException {
        return new Change.Done(repo().byId(c, id).orElseThrow());
    }

    private void action(Connection c, Actor actor, String action, Report r, long now, String details)
            throws SQLException {
        actions().insert(c, new ActionRepo.Action(now, server(), actor.uuid(), actor.name(), action, r.targetUuid(),
                r.targetName(), r.id(), details));
    }

    public CompletableFuture<Change> claim(Actor actor, long id, int version) {
        return change(actor, id, version, "claiming", (c, r, now) -> {
            if (r.status().closed()) {
                return new Change.Closed(r);
            }
            if (r.status() == Status.CLAIMED) {
                return r.claimedBy(actor.uuid()) ? new Change.Done(r) : new Change.ClaimedByOther(r);
            }
            if (!repo().claim(c, r.id(), version, actor.uuid(), actor.name(), now)) {
                return null;
            }
            action(c, actor, "REPORT_CLAIM", r, now, null);
            return done(c, r.id());
        }).thenApplyAsync(x -> x, mainThread);
    }

    /** Releases a claim: your own, or anyone's with {@code mrpunish.admin} (logged as an override). */
    public CompletableFuture<Change> unclaim(Actor actor, long id, int version) {
        return change(actor, id, version, "unclaiming", (c, r, now) -> {
            if (r.status().closed()) {
                return new Change.Closed(r);
            }
            if (r.status() != Status.CLAIMED) {
                return new Change.Done(r);
            }
            boolean own = r.claimedBy(actor.uuid());
            if (!own && !actor.admin()) {
                return new Change.ClaimedByOther(r);
            }
            if (!repo().unclaim(c, r.id(), version)) {
                return null;
            }
            action(c, actor, "REPORT_UNCLAIM", r, now, own ? null : "override: claimed by " + r.claimedByName());
            return done(c, r.id());
        }).thenApplyAsync(x -> x, mainThread);
    }

    /**
     * Resolves or rejects (with or without a claim). A report claimed by someone else needs
     * {@code mrpunish.admin} (logged as an override). The reporter's message is queued in the same
     * transaction and shown now if they're online, else on their next join.
     *
     * @param note the decision note, or null
     */
    public CompletableFuture<Change> close(Actor actor, long id, int version, Status to, String note) {
        if (!to.closed()) {
            throw new IllegalArgumentException(to + " doesn't close a report");
        }
        String what = to == Status.RESOLVED ? "resolving" : "rejecting";
        return change(actor, id, version, what, (c, r, now) -> {
            if (r.status().closed()) {
                return new Change.Closed(r);
            }
            boolean override = r.status() == Status.CLAIMED && !r.claimedBy(actor.uuid());
            if (override && !actor.admin()) {
                return new Change.ClaimedByOther(r);
            }
            if (!repo().close(c, r.id(), version, to, actor.uuid(), actor.name(), now, note)) {
                return null;
            }
            action(c, actor, to == Status.RESOLVED ? "REPORT_RESOLVE" : "REPORT_REJECT", r, now,
                    (override ? "override: claimed by " + r.claimedByName() + "; " : "") + (note == null ? "" : note));
            actions().deliver(c, r.reporterUuid(), DELIVERY, r.id(), now);
            return done(c, r.id());
        }).thenApplyAsync(result -> {
            if (result instanceof Change.Done d) {
                openCount = Math.max(0, openCount - (d.report().status().closed() ? 1 : 0));
                try {
                    listener.closed(d.report(), actor);
                } catch (RuntimeException e) {
                    log.log(Level.SEVERE, "Couldn't send the notifications for report #" + id, e);
                }
            }
            return result;
        }, mainThread);
    }

    /** Adds an internal note, at any state. No version check: notes only append. */
    public CompletableFuture<Change> addNote(Actor actor, long id, String text) {
        if (!actor.permissions().test(REPORTS)) {
            return done(new Change.NoPermission());
        }
        Optional<String> down = down();
        if (down.isPresent()) {
            return done(new Change.Unavailable(down.get()));
        }
        long now = clock.getAsLong();
        return storage.executor().transaction("adding a note to report #" + id, c -> {
            Optional<Report> found = repo().byId(c, id);
            if (found.isEmpty()) {
                return (Change) new Change.NotFound(id);
            }
            repo().addNote(c, id, actor.uuid(), actor.name(), text, now);
            action(c, actor, "REPORT_NOTE", found.get(), now, text);
            return done(c, id);
        }).handle((r, e) -> e == null ? r : changeFailure(e, "adding a note to report #" + id))
                .thenApplyAsync(x -> x, mainThread);
    }

    // ---- Timers and deliveries ----

    /**
     * Releases claims older than {@code reports.claim-timeout-minutes} (called every minute). Each
     * release is version-checked, so a claim acted on meanwhile is left alone.
     */
    public CompletableFuture<List<Report>> expireClaims() {
        int minutes = state.get().settings().reports().claimTimeoutMinutes();
        if (minutes <= 0 || down().isPresent()) {
            return done(List.of());
        }
        long now = clock.getAsLong();
        String server = server();
        return storage.executor().transaction("releasing expired report claims", c -> {
            ReportRepo repo = repo();
            List<Report> released = new java.util.ArrayList<>();
            for (Report r : repo.claimedBefore(c, now - minutes * 60_000L)) {
                if (repo.unclaim(c, r.id(), r.version())) {
                    actions().insert(c, new ActionRepo.Action(now, server, null, "MR-Punish", "REPORT_CLAIM_EXPIRED",
                            r.targetUuid(), r.targetName(), r.id(), "claimed by " + r.claimedByName()));
                    released.add(r);
                }
            }
            return released;
        }).thenApplyAsync(released -> {
            for (Report r : released) {
                try {
                    listener.claimExpired(r);
                } catch (RuntimeException e) {
                    log.log(Level.WARNING, "Couldn't tell " + r.claimedByName() + " their claim expired", e);
                }
            }
            return released;
        }, mainThread);
    }

    /** Refreshes and returns the cached OPEN count (the reminder). */
    public CompletableFuture<Integer> refreshOpenCount() {
        if (down().isPresent()) {
            return done(openCount);
        }
        return storage.executor().submit("counting open reports", c -> repo().countOpen(c))
                .thenApply(n -> {
                    openCount = n;
                    return n;
                });
    }

    public int openCount() {
        return openCount;
    }

    /**
     * The player's undelivered report outcomes, each marked delivered first so it's shown once
     * (also when shown right after the decision, racing a join on another server).
     */
    public CompletableFuture<List<Report>> takeDeliveries(UUID player) {
        if (down().isPresent()) {
            return done(List.of());
        }
        long now = clock.getAsLong();
        return storage.executor().submit("loading report outcomes for " + player, c -> {
            List<Report> out = new java.util.ArrayList<>();
            ActionRepo actions = actions();
            for (ActionRepo.Delivery d : actions.pending(c, player, DELIVERY)) {
                if (actions.markDelivered(c, d.id(), now)) {
                    repo().byId(c, d.refId()).ifPresent(out::add);
                }
            }
            return out;
        }).thenApplyAsync(x -> x, mainThread);
    }

    // ---- Failures ----

    private Created createFailure(Throwable e, String what) {
        Throwable cause = unwrap(e);
        if (cause instanceof StorageException se) {
            return se.errorId() == null ? new Created.Unavailable(se.getMessage()) : new Created.Failed(se.errorId());
        }
        return new Created.Failed(logUnexpected(cause, what));
    }

    private Change changeFailure(Throwable e, String what) {
        Throwable cause = unwrap(e);
        if (cause instanceof StorageException se) {
            return se.errorId() == null ? new Change.Unavailable(se.getMessage()) : new Change.Failed(se.errorId());
        }
        return new Change.Failed(logUnexpected(cause, what));
    }

    private String logUnexpected(Throwable cause, String what) {
        String id = String.format("%08x", ThreadLocalRandom.current().nextInt());
        log.log(Level.SEVERE, "Error " + id + " while " + what, cause);
        return id;
    }

    private static Throwable unwrap(Throwable e) {
        Throwable t = e;
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
