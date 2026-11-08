package com.mrstudios.punish.punish;

import java.util.List;

/**
 * The one-punishment-per-slot rule (DESIGN.md 7.4). MUTE and BAN have one slot per account, IP_MUTE
 * and IP_BAN one per IP hash; warnings have none. A new punishment may only replace the active ones
 * in its slot if it ends at the same time or later (permanent is the latest end); otherwise it's
 * refused, so a punishment can never be shortened by issuing another one.
 */
public final class ReplacementRules {

    /** An active punishment in the slot. @param endsAt null = permanent */
    public record Active(long id, Long endsAt) {
    }

    /**
     * @param replaces  ids that become REPLACED (all active ones in the slot); empty if none
     * @param blockedBy the active punishment that ends latest, when the new one would end sooner
     */
    public record Decision(List<Long> replaces, Active blockedBy) {

        public static final Decision NONE = new Decision(List.of(), null);

        public Decision {
            replaces = List.copyOf(replaces);
        }

        public boolean blocked() {
            return blockedBy != null;
        }
    }

    private ReplacementRules() {
    }

    /**
     * @param newEnd when the new punishment would end; null = permanent
     */
    public static Decision decide(PunishmentType type, List<Active> active, Long newEnd) {
        if (!type.hasDuration() || active.isEmpty()) {
            return Decision.NONE;
        }
        Active latest = active.get(0);
        for (Active a : active) {
            if (later(a.endsAt(), latest.endsAt())) {
                latest = a;
            }
        }
        if (later(latest.endsAt(), newEnd)) {
            return new Decision(List.of(), latest);
        }
        return new Decision(active.stream().map(Active::id).sorted().toList(), null);
    }

    /** Whether end {@code a} is strictly later than {@code b} (null = permanent = latest). */
    public static boolean later(Long a, Long b) {
        if (a == null) {
            return b != null;
        }
        return b != null && a > b;
    }
}
