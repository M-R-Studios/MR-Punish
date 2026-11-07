package com.mrstudios.punish.punish;

import com.mrstudios.punish.config.TimeSpan;
import java.util.List;

/**
 * What issuing a reason would do right now (DESIGN.md 7.3): shown to staff before they confirm and
 * recomputed inside the transaction. If the two differ in anything that matters
 * ({@link #sameDecision}), nothing is written (DESIGN.md E12).
 *
 * @param duration  null for warnings
 * @param endsAt    null for permanent and for warnings
 * @param counted   ids of the earlier offences that were counted
 * @param decision  which active punishments it replaces, or which one blocks it
 */
public record Preview(String reasonKey, PunishmentType type, int offence, int step, TimeSpan duration, Long endsAt,
        List<Long> counted, ReplacementRules.Decision decision) {

    public Preview {
        counted = List.copyOf(counted);
    }

    /**
     * Whether {@code other} leads to the same punishment: same reason, offence, step and duration,
     * and the same replacements or block. The end time is left out (it moves with the clock).
     */
    public boolean sameDecision(Preview other) {
        return reasonKey.equals(other.reasonKey) && type == other.type && offence == other.offence
                && step == other.step && java.util.Objects.equals(duration, other.duration)
                && decision.replaces().equals(other.decision.replaces())
                && java.util.Objects.equals(blockedId(), other.blockedId());
    }

    private Long blockedId() {
        return decision.blockedBy() == null ? null : decision.blockedBy().id();
    }
}
