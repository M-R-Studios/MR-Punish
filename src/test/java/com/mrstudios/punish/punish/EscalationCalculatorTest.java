package com.mrstudios.punish.punish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.config.DurationParser;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.config.SettingsLoader;
import com.mrstudios.punish.config.TimeSpan;
import com.mrstudios.punish.punish.EscalationCalculator.Prior;
import com.mrstudios.punish.punish.Punishment.StoredStatus;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** DESIGN.md 7.3 and examples E1, E2, E3, E7, E13 of 7.5 ("now" is day 100, period 90 days). */
class EscalationCalculatorTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long NOW = 100 * DAY;
    private static final Settings SETTINGS =
            new SettingsLoader(name -> true, List.of(), Set.of(), SettingsLoader.bundledDefaults()).defaults();
    private static final TimeSpan PERIOD = SETTINGS.historyPeriod();

    private static Reason reason(String key) {
        return SETTINGS.reasons().byKey(key).orElseThrow();
    }

    private static Prior prior(long id, String key, PunishmentType type, int day, StoredStatus status) {
        return new Prior(id, type, key, day * DAY, status);
    }

    private static Preview preview(String key, Prior... priors) {
        return EscalationCalculator.preview(reason(key), PERIOD, List.of(priors), List.of(), NOW);
    }

    @Test
    void e1NoHistoryIsTheFirstStep() {
        Preview p = preview("toxicity");
        assertEquals(1, p.offence());
        assertEquals(1, p.step());
        assertEquals(DurationParser.parse("2h"), p.duration());
        assertEquals(NOW + 2 * 60 * 60 * 1000L, p.endsAt());
        assertEquals(List.of(), p.counted());
    }

    @Test
    void e2RemovedNeverCountsExpiredDoes() {
        Preview p = preview("toxicity",
                prior(1, "toxicity", PunishmentType.MUTE, 50, StoredStatus.ACTIVE),   // expired long ago
                prior(2, "toxicity", PunishmentType.MUTE, 70, StoredStatus.REMOVED));
        assertEquals(2, p.offence());
        assertEquals(DurationParser.parse("1d"), p.duration());
        assertEquals(List.of(1L), p.counted());
    }

    @Test
    void e3OlderThanThePeriodDoesntCount() {
        Preview p = preview("toxicity",
                prior(1, "toxicity", PunishmentType.MUTE, 5, StoredStatus.ACTIVE),
                prior(2, "toxicity", PunishmentType.MUTE, 60, StoredStatus.ACTIVE));
        assertEquals(2, p.offence());
        assertEquals(DurationParser.parse("1d"), p.duration());
        assertEquals(List.of(2L), p.counted());
    }

    @Test
    void replacedPunishmentsStillCount() {
        Preview p = preview("spam", prior(1, "spam", PunishmentType.MUTE, 99, StoredStatus.REPLACED));
        assertEquals(2, p.offence());
        assertEquals(DurationParser.parse("30m"), p.duration());
    }

    @Test
    void e7TheLastStepRepeats() {
        Preview p = preview("hacking",
                prior(1, "hacking", PunishmentType.BAN, 20, StoredStatus.REPLACED),
                prior(2, "hacking", PunishmentType.BAN, 40, StoredStatus.REPLACED),
                prior(3, "hacking", PunishmentType.BAN, 60, StoredStatus.ACTIVE));
        assertEquals(4, p.offence());
        assertEquals(3, p.step());
        assertEquals(TimeSpan.PERMANENT, p.duration());
        assertNull(p.endsAt());
    }

    @Test
    void e13WarningsCountButHaveNoDuration() {
        Preview p = preview("spam-warn",
                prior(1, "spam-warn", PunishmentType.WARN, 5, StoredStatus.ACTIVE),
                prior(2, "spam-warn", PunishmentType.WARN, 20, StoredStatus.ACTIVE),
                prior(3, "spam-warn", PunishmentType.WARN, 60, StoredStatus.ACTIVE),
                prior(4, "spam-warn", PunishmentType.WARN, 95, StoredStatus.ACTIVE));
        assertEquals(4, p.offence());
        assertNull(p.duration());
        assertNull(p.endsAt());
        assertFalse(p.decision().blocked());
        assertEquals(List.of(), p.decision().replaces());
    }

    @Test
    void onlyTheSameTypeAndReasonCount() {
        Preview p = preview("spam",
                prior(1, "toxicity", PunishmentType.MUTE, 99, StoredStatus.ACTIVE),
                prior(2, "spam-warn", PunishmentType.WARN, 99, StoredStatus.ACTIVE),
                prior(3, "spam", PunishmentType.BAN, 99, StoredStatus.ACTIVE));
        assertEquals(1, p.offence());
    }

    @Test
    void aReasonsOwnPeriodOverridesTheGlobalOne() {
        Reason base = reason("spam");
        Reason shortPeriod = new Reason(base.key(), base.type(), base.display(), base.description(), base.icon(),
                base.slot(), base.aliases(), base.escalation(), DurationParser.parse("7d"), null, true);
        List<Prior> priors = List.of(prior(1, "spam", PunishmentType.MUTE, 90, StoredStatus.ACTIVE),
                prior(2, "spam", PunishmentType.MUTE, 95, StoredStatus.ACTIVE));
        assertEquals(2, EscalationCalculator.preview(shortPeriod, PERIOD, priors, List.of(), NOW).offence());
        assertEquals(3, EscalationCalculator.preview(base, PERIOD, priors, List.of(), NOW).offence());
    }

    @Test
    void aPermanentPeriodCountsEverything() {
        List<Prior> priors = List.of(prior(1, "spam", PunishmentType.MUTE, 0, StoredStatus.ACTIVE),
                prior(2, "spam", PunishmentType.MUTE, 1, StoredStatus.ACTIVE));
        Preview p = EscalationCalculator.preview(reason("spam"), TimeSpan.PERMANENT, priors, List.of(), NOW);
        assertEquals(3, p.offence());
        assertEquals(DurationParser.parse("2h"), p.duration());
    }

    @Test
    void theBoundaryOfThePeriodIsExclusive() {
        // Exactly 90 days ago is outside "issued after now - 90d".
        Preview p = preview("spam", prior(1, "spam", PunishmentType.MUTE, 10, StoredStatus.ACTIVE));
        assertEquals(1, p.offence());
    }

    @Test
    void sameDecisionIgnoresTheMovingEndTime() {
        Preview a = preview("spam");
        Preview b = EscalationCalculator.preview(reason("spam"), PERIOD, List.of(), List.of(), NOW + 5000);
        assertTrue(a.sameDecision(b));
        Preview c = preview("spam", prior(1, "spam", PunishmentType.MUTE, 99, StoredStatus.ACTIVE));
        assertFalse(a.sameDecision(c));
    }
}
