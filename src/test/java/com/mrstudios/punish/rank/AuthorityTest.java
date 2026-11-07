package com.mrstudios.punish.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mrstudios.punish.rank.Authority.Action;
import com.mrstudios.punish.rank.Authority.Standing;
import com.mrstudios.punish.rank.Authority.Verdict;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

/** The authority matrix of DESIGN.md 6.3: console/self/exempt/lower/equal/higher x every action. */
class AuthorityTest {

    private static final UUID MOD_ID = UUID.randomUUID();
    private static final Standing MOD = new Standing(MOD_ID, "Mod", 20, false);
    private static final Standing CONSOLE = Standing.console("Console");

    private static Standing player(int weight) {
        return new Standing(UUID.randomUUID(), "P" + weight, weight, false);
    }

    private static Standing exempt(int weight) {
        return new Standing(UUID.randomUUID(), "E" + weight, weight, true);
    }

    @ParameterizedTest
    @EnumSource(Action.class)
    void matrixForEveryAction(Action action) {
        assertEquals(Verdict.OK, Authority.check(MOD, player(0), action), "no rank");
        assertEquals(Verdict.OK, Authority.check(MOD, player(10), action), "lower");
        assertEquals(Verdict.RANK, Authority.check(MOD, player(20), action), "equal is refused");
        assertEquals(Verdict.RANK, Authority.check(MOD, player(30), action), "higher");
        assertEquals(Verdict.EXEMPT, Authority.check(MOD, exempt(0), action), "exempt, even without rank");
        assertEquals(Verdict.SELF, Authority.check(MOD, new Standing(MOD_ID, "Mod", 20, false), action), "self");
        for (Standing target : List.of(player(0), player(1000), exempt(1000))) {
            assertEquals(Verdict.OK, Authority.check(CONSOLE, target, action), "console can do everything");
        }
    }

    @Test
    void selfBeatsExemptBeatsRank() {
        Standing selfExempt = new Standing(MOD_ID, "Mod", 20, true);
        assertEquals(Verdict.SELF, Authority.check(MOD, selfExempt, Action.PUNISH));
        assertEquals(Verdict.EXEMPT, Authority.check(MOD, exempt(99), Action.PUNISH));
    }

    @Test
    void ipActionsCheckEveryAffectedAccount() {
        List<Standing> fine = List.of(player(0), player(10), player(19));
        assertEquals(Verdict.OK, Authority.check(MOD, fine, Action.IP_PUNISH));
        assertEquals(0, Authority.refusedCount(MOD, fine));

        List<Standing> withEqual = List.of(player(0), player(20), player(5));
        assertEquals(Verdict.RANK, Authority.check(MOD, withEqual, Action.IP_PUNISH));
        assertEquals(1, Authority.refusedCount(MOD, withEqual));

        List<Standing> withExemptAlt = List.of(player(0), exempt(0));
        assertEquals(Verdict.EXEMPT, Authority.check(MOD, withExemptAlt, Action.IP_PUNISH));

        List<Standing> withTheActor = List.of(player(0), MOD);
        assertEquals(Verdict.SELF, Authority.check(MOD, withTheActor, Action.IP_PUNISH), "actor shares the IP");

        List<Standing> mixed = List.of(player(30), exempt(0), MOD, player(1));
        assertEquals(3, Authority.refusedCount(MOD, mixed));
        assertEquals(Verdict.OK, Authority.check(CONSOLE, mixed, Action.IP_PUNISH));
        assertEquals(0, Authority.refusedCount(CONSOLE, mixed));
    }

    @Test
    void removalNeedsAtLeastTheIssuersWeight() {
        Standing admin = new Standing(UUID.randomUUID(), "Admin", 30, false);
        UUID target = UUID.randomUUID();
        assertEquals(Verdict.OK, Authority.checkRemoval(admin, target, List.of(30, 20, 0)), "equal is enough");
        assertEquals(Verdict.ISSUER_RANK, Authority.checkRemoval(admin, target, List.of(20, 40)), "an owner's ban");
        assertEquals(Verdict.SELF, Authority.checkRemoval(admin, admin.uuid(), List.of(0)));
        assertEquals(Verdict.OK, Authority.checkRemoval(CONSOLE, target, List.of(1000)));
    }
}
