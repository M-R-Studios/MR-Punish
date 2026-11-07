package com.mrstudios.punish.punish;

import com.mrstudios.punish.config.TimeSpan;
import java.util.List;

/**
 * Works out the next punishment for a reason (DESIGN.md 7.3): count the earlier punishments of the
 * same type and reason within the period (removed ones never count; replaced and expired ones do),
 * take that step of the ladder, and repeat the last step once the ladder runs out.
 */
public final class EscalationCalculator {

    /** An earlier punishment of the subject, as read from history. */
    public record Prior(long id, PunishmentType type, String reasonKey, long issuedAt, Punishment.StoredStatus status) {
    }

    private EscalationCalculator() {
    }

    /**
     * @param priors        the subject's punishments (the account's, or for IP types the hash's); any
     *                      type and reason, any status: filtering happens here
     * @param active        the subject's active punishments in the reason type's slot
     * @param globalPeriod  {@code escalation.history-period}
     */
    public static Preview preview(Reason reason, TimeSpan globalPeriod, List<Prior> priors,
            List<ReplacementRules.Active> active, long now) {
        TimeSpan period = reason.period(globalPeriod);
        long since = period.permanent() ? Long.MIN_VALUE : now - period.millis();
        List<Long> counted = priors.stream()
                .filter(p -> p.type() == reason.type())
                .filter(p -> p.reasonKey().equals(reason.key()))
                .filter(p -> p.status() != Punishment.StoredStatus.REMOVED)
                .filter(p -> p.issuedAt() > since)
                .map(Prior::id)
                .sorted()
                .toList();
        int offence = counted.size() + 1;
        if (!reason.type().hasDuration()) {
            return new Preview(reason.key(), reason.type(), offence, 1, null, null, counted, ReplacementRules.Decision.NONE);
        }
        List<TimeSpan> ladder = reason.escalation();
        int step = Math.min(offence, ladder.size());
        TimeSpan duration = ladder.get(step - 1);
        Long endsAt = duration.permanent() ? null : now + duration.millis();
        return new Preview(reason.key(), reason.type(), offence, step, duration, endsAt, counted,
                ReplacementRules.decide(reason.type(), active, endsAt));
    }
}
