package com.mrstudios.punish.punish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.punish.ReplacementRules.Active;
import com.mrstudios.punish.punish.ReplacementRules.Decision;
import java.util.List;
import org.junit.jupiter.api.Test;

/** DESIGN.md 7.4 and examples E4, E5, E8, E9 of 7.5. */
class ReplacementRulesTest {

    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;
    private static final long DAY = 24 * HOUR;
    private static final long NOW = 100 * DAY;

    @Test
    void e4ALaterEndReplaces() {
        // Active spam mute with 4 minutes left; a 2h toxicity mute ends later.
        Decision d = ReplacementRules.decide(PunishmentType.MUTE, List.of(new Active(7, NOW + 4 * MIN)), NOW + 2 * HOUR);
        assertFalse(d.blocked());
        assertEquals(List.of(7L), d.replaces());
    }

    @Test
    void e5AnEarlierEndIsRefused() {
        Active toxicity = new Active(12, NOW + 20 * DAY);
        Decision d = ReplacementRules.decide(PunishmentType.MUTE, List.of(toxicity), NOW + 10 * MIN);
        assertTrue(d.blocked());
        assertEquals(toxicity, d.blockedBy());
        assertEquals(List.of(), d.replaces());
    }

    @Test
    void e8NothingEndsLaterThanPermanentButPermanentMayReplacePermanent() {
        Active permanent = new Active(3, null);
        assertTrue(ReplacementRules.decide(PunishmentType.BAN, List.of(permanent), NOW + 30 * DAY).blocked());
        Decision again = ReplacementRules.decide(PunishmentType.BAN, List.of(permanent), null);
        assertFalse(again.blocked());
        assertEquals(List.of(3L), again.replaces());
    }

    @Test
    void e9ExtendingAShortRemainderIsAllowed() {
        Decision d = ReplacementRules.decide(PunishmentType.BAN, List.of(new Active(5, NOW + DAY)), NOW + 3 * DAY);
        assertEquals(List.of(5L), d.replaces());
    }

    @Test
    void anEqualEndReplaces() {
        assertEquals(List.of(1L),
                ReplacementRules.decide(PunishmentType.MUTE, List.of(new Active(1, NOW + DAY)), NOW + DAY).replaces());
    }

    @Test
    void withSeveralActiveTheLatestEndDecidesAndAllAreReplaced() {
        List<Active> active = List.of(new Active(4, NOW + HOUR), new Active(2, NOW + 5 * DAY), new Active(9, NOW + DAY));
        Decision blocked = ReplacementRules.decide(PunishmentType.MUTE, active, NOW + 2 * DAY);
        assertEquals(2, blocked.blockedBy().id());
        Decision replaced = ReplacementRules.decide(PunishmentType.MUTE, active, NOW + 6 * DAY);
        assertEquals(List.of(2L, 4L, 9L), replaced.replaces());
        Decision permanentAmongThem = ReplacementRules.decide(PunishmentType.MUTE,
                List.of(new Active(1, NOW + HOUR), new Active(6, null)), NOW + 99 * DAY);
        assertEquals(6, permanentAmongThem.blockedBy().id());
    }

    @Test
    void warningsNeverReplaceAnything() {
        Decision d = ReplacementRules.decide(PunishmentType.WARN, List.of(new Active(1, NOW + DAY)), NOW);
        assertFalse(d.blocked());
        assertEquals(List.of(), d.replaces());
    }

    @Test
    void anEmptySlotIsNoDecision() {
        assertEquals(Decision.NONE, ReplacementRules.decide(PunishmentType.IP_BAN, List.of(), NOW + DAY));
    }
}
