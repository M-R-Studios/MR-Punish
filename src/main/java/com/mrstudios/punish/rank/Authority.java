package com.mrstudios.punish.rank;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Who may act on whom (DESIGN.md 6.3). Pure: the weights come from {@link Standing}s read by the
 * caller (live, LuckPerms or cached) and are checked again inside each transaction.
 */
public final class Authority {

    /**
     * A player's rank position at the moment of the check.
     *
     * @param uuid   null for console
     * @param weight 0 without a rank
     */
    public record Standing(UUID uuid, String name, int weight, boolean exempt) {

        public static Standing console(String name) {
            return new Standing(null, name, Integer.MAX_VALUE, false);
        }

        public boolean isConsole() {
            return uuid == null;
        }
    }

    /** What the actor is doing; the rules are the same, the name goes into refusal logs. */
    public enum Action {
        PUNISH,
        IP_PUNISH,
        FREEZE,
        VANISH_OTHER,
        FORCE_EXIT
    }

    public enum Verdict {
        OK,
        /** The actor is (one of) the targets. */
        SELF,
        /** A target has {@code mrpunish.exempt}; only console can act on them. */
        EXEMPT,
        /** A target's weight is equal to or higher than the actor's. */
        RANK,
        /** Removal only: a punishment was issued by someone heavier than the remover. */
        ISSUER_RANK
    }

    private Authority() {
    }

    public static Verdict check(Standing actor, Standing target, Action action) {
        return check(actor, List.of(target), action);
    }

    /**
     * For IP actions {@code targets} is every affected account; one refusal refuses the whole action.
     * Order of checks: console, self, exempt, rank.
     */
    public static Verdict check(Standing actor, Collection<Standing> targets, Action action) {
        if (actor.isConsole()) {
            return Verdict.OK;
        }
        if (targets.stream().anyMatch(t -> actor.uuid().equals(t.uuid()))) {
            return Verdict.SELF;
        }
        if (targets.stream().anyMatch(Standing::exempt)) {
            return Verdict.EXEMPT;
        }
        if (targets.stream().anyMatch(t -> t.weight() >= actor.weight())) {
            return Verdict.RANK;
        }
        return Verdict.OK;
    }

    /** How many of {@code targets} the actor can't act on (for the IP-rank message, which gives a count). */
    public static long refusedCount(Standing actor, Collection<Standing> targets) {
        if (actor.isConsole()) {
            return 0;
        }
        return targets.stream().filter(t -> actor.uuid().equals(t.uuid()) || t.exempt() || t.weight() >= actor.weight())
                .count();
    }

    /**
     * Removing punishments of {@code targetUuid} issued with these weights (DESIGN.md 7.6): the
     * remover needs at least each issuer's weight, so an admin can't undo an owner's ban. Nobody
     * removes their own punishment.
     */
    public static Verdict checkRemoval(Standing remover, UUID targetUuid, Collection<Integer> issuerWeights) {
        if (remover.isConsole()) {
            return Verdict.OK;
        }
        if (remover.uuid().equals(targetUuid)) {
            return Verdict.SELF;
        }
        if (issuerWeights.stream().anyMatch(w -> w > remover.weight())) {
            return Verdict.ISSUER_RANK;
        }
        return Verdict.OK;
    }
}
