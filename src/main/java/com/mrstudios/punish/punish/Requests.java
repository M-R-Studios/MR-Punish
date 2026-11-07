package com.mrstudios.punish.punish;

import com.mrstudios.punish.rank.Authority;
import com.mrstudios.punish.rank.Authority.Standing;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/** What callers hand to {@link PunishmentService}, and what they get back. */
public final class Requests {

    private Requests() {
    }

    /**
     * Who is acting. Built on the main thread at the moment of the action.
     *
     * @param permissions the actor's permissions right now (console: everything)
     */
    public record Actor(Standing standing, Predicate<String> permissions) {

        public static Actor console(String name) {
            return new Actor(Standing.console(name), permission -> true);
        }

        public UUID uuid() {
            return standing.uuid();
        }

        public String name() {
            return standing.name();
        }
    }

    /**
     * Who is punished.
     *
     * @param live     the target's standing read now if they're online, else null (then it comes from
     *                 LuckPerms or the cached {@code players} row inside the transaction)
     * @param ipHash   IP types only
     * @param affected IP types only: every other account the IP action affects (DESIGN.md 8.4)
     */
    public record Target(UUID uuid, String name, Standing live, String ipHash, List<Standing> affected) {

        public Target {
            affected = affected == null ? List.of() : List.copyOf(affected);
        }

        public static Target online(Standing live) {
            return new Target(live.uuid(), live.name(), live, null, List.of());
        }

        public static Target offline(UUID uuid, String name) {
            return new Target(uuid, name, null, null, List.of());
        }

        public boolean online() {
            return live != null;
        }
    }

    /**
     * @param expected the preview the actor confirmed, or null (console applies directly); if the
     *                 transaction works out anything different, nothing is written ({@link IssueResult.Changed})
     */
    public record Issue(Actor actor, Target target, Reason reason, String note, boolean silent,
            Punishment.Origin origin, Preview expected) {

        public PunishmentType type() {
            return reason.type();
        }
    }

    /**
     * @param id     a single punishment to remove (history menu, {@code /unwarn <id>}); null for "every
     *               active one in the slot" (for warnings: the most recent active one)
     * @param reason the typed removal reason, or null for none
     */
    public record Remove(Actor actor, Target target, PunishmentType type, Long id, String reason) {
    }

    public sealed interface IssueResult {

        /** Saved. {@code replaced} are the ids that became REPLACED. */
        record Committed(Punishment punishment, List<Long> replaced, Preview preview) implements IssueResult {
        }

        /** Someone else changed the target's punishments since the preview; here is the new one. */
        record Changed(Preview current) implements IssueResult {
        }

        /** An active punishment in the slot ends later; nothing was written. */
        record WouldShorten(Punishment blocking) implements IssueResult {
        }

        /** SELF, EXEMPT or RANK. {@code refusedAccounts} counts the affected accounts for IP actions. */
        record Refused(Authority.Verdict verdict, long refusedAccounts) implements IssueResult {
        }

        record NoPermission(String permission) implements IssueResult {
        }

        /** The same actor is already punishing the same target. */
        record Busy() implements IssueResult {
        }

        /** Never seen, so there's nothing to punish (DESIGN.md X1). */
        record UnknownTarget() implements IssueResult {
        }

        /** Storage is down or the server is stopping; nothing ran. */
        record Unavailable(String why) implements IssueResult {
        }

        /** The transaction failed and was rolled back; the error id is in the console. */
        record Failed(String errorId) implements IssueResult {
        }
    }

    public sealed interface RemoveResult {

        /** Every one of {@code removed} is now REMOVED (as stored after the update). */
        record Removed(List<Punishment> removed) implements RemoveResult {
        }

        /** Nothing active of that type. */
        record None() implements RemoveResult {
        }

        /** The given id isn't an active punishment of that type for that target. */
        record NotFound(long id) implements RemoveResult {
        }

        /** {@code id} was issued by someone heavier than the remover; nothing was removed. */
        record IssuerRank(long id) implements RemoveResult {
        }

        /** Removing your own punishment. */
        record Self() implements RemoveResult {
        }

        record NoPermission(String permission) implements RemoveResult {
        }

        record Busy() implements RemoveResult {
        }

        record Unavailable(String why) implements RemoveResult {
        }

        record Failed(String errorId) implements RemoveResult {
        }
    }
}
