package com.mrstudios.punish.punish;

import static com.mrstudios.punish.punish.ServiceHarness.CONSOLE;
import static com.mrstudios.punish.punish.ServiceHarness.DAY;
import static com.mrstudios.punish.punish.ServiceHarness.staff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.punish.Requests.IssueResult;
import com.mrstudios.punish.punish.Requests.RemoveResult;
import com.mrstudios.punish.punish.Requests.Target;
import com.mrstudios.punish.rank.Authority;
import com.mrstudios.punish.storage.IpRepo;
import com.mrstudios.punish.storage.Retention;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** IP links, the association window and authority over every account on the IP (DESIGN.md 8.3, 6.3). */
class IpPunishmentTest {

    @TempDir
    Path dir;
    private ServiceHarness h;

    private static final String HOME = "a".repeat(64);
    private static final String CAFE = "b".repeat(64);
    private static final UUID STEVE = UUID.randomUUID();
    private static final UUID ALT = UUID.randomUUID();
    private static final UUID OLD_ALT = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final Requests.Actor ADMIN = staff(ADMIN_ID, "Admin", 30);

    @BeforeEach
    void setUp() throws Exception {
        h = new ServiceHarness(dir);
        h.known(STEVE, "Steve", 0, false);
        h.known(ALT, "Alt", 0, false);
        h.known(OLD_ALT, "OldAlt", 40, false);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private void seen(UUID uuid, String hash, long at) throws Exception {
        ServiceHarness.get(h.storage.executor().submit("test", c -> {
            new IpRepo(h.storage.database()).seen(c, uuid, hash, at);
            return null;
        }));
    }

    private <T> T db(com.mrstudios.punish.storage.SqlWork<T> work) throws Exception {
        return ServiceHarness.get(h.storage.executor().submit("test", work));
    }

    private Target onHome(UUID uuid, String name) {
        return new Target(uuid, name, null, HOME, List.of());
    }

    @Test
    void linksAreUpsertedAndTheLatestHashIsThePunishedOne() throws Exception {
        seen(STEVE, CAFE, 10 * DAY);
        seen(STEVE, HOME, 90 * DAY);
        seen(STEVE, CAFE, 20 * DAY);
        IpRepo repo = new IpRepo(h.storage.database());
        assertEquals(HOME, db(c -> repo.latestHash(c, STEVE)).orElseThrow());
        assertEquals(List.of(HOME), db(c -> repo.hashesOf(c, STEVE, 70 * DAY)));
        assertEquals(List.of(HOME, CAFE), db(c -> repo.hashesOf(c, STEVE, 0)));
        assertEquals(2, h.count("player_ips"));
    }

    @Test
    void associatedAccountsAreThoseSeenWithinTheWindow() throws Exception {
        seen(STEVE, HOME, 99 * DAY);
        seen(ALT, HOME, 95 * DAY);
        seen(OLD_ALT, HOME, 50 * DAY);  // 50 days ago: outside the 30-day window
        List<IpRepo.Seen> associated = db(c -> new IpRepo(h.storage.database()).associated(c, HOME, 70 * DAY));
        assertEquals(List.of(STEVE, ALT), associated.stream().map(IpRepo.Seen::uuid).toList());
        assertEquals("Alt", associated.get(1).name());
    }

    @Test
    void theTransactionChecksEveryAssociatedAccountsRank() throws Exception {
        seen(STEVE, HOME, 99 * DAY);
        seen(ALT, HOME, 99 * DAY);
        // The command passed no affected accounts; the transaction still finds the owner on the same IP.
        UUID ownerAlt = UUID.randomUUID();
        h.known(ownerAlt, "OwnerAlt", 40, false);
        seen(ownerAlt, HOME, 98 * DAY);
        IssueResult result = h.issue(ADMIN, onHome(STEVE, "Steve"), "ban-evasion");
        IssueResult.Refused refused = assertInstanceOf(IssueResult.Refused.class, result);
        assertEquals(Authority.Verdict.RANK, refused.verdict());
        assertEquals(1, refused.refusedAccounts());
        assertEquals(0, h.count("punishments"));
    }

    @Test
    void anAccountOutsideTheWindowDoesntBlockTheBan() throws Exception {
        seen(STEVE, HOME, 99 * DAY);
        seen(OLD_ALT, HOME, 10 * DAY);  // the weight-40 account used this IP long ago (a café, a school)
        IssueResult.Committed committed = assertInstanceOf(IssueResult.Committed.class,
                h.issue(ADMIN, onHome(STEVE, "Steve"), "ban-evasion"));
        assertEquals(HOME, committed.punishment().ipHash());
        assertEquals(Punishment.WeightSource.CACHED, committed.punishment().targetWeightSource());
        assertTrue(h.rows("SELECT details FROM staff_actions WHERE action = 'PUNISH'").get(0).get("details").toString()
                .endsWith("accounts=1"));
    }

    @Test
    void anExemptAltOrTheActorsOwnAccountOnTheIpRefuses() throws Exception {
        UUID vip = UUID.randomUUID();
        h.known(vip, "Vip", 0, true);
        seen(STEVE, HOME, 99 * DAY);
        seen(vip, HOME, 99 * DAY);
        assertEquals(Authority.Verdict.EXEMPT, ((IssueResult.Refused) h.issue(ADMIN, onHome(STEVE, "Steve"),
                "ban-evasion")).verdict());
        assertInstanceOf(IssueResult.Committed.class, h.issue(CONSOLE, onHome(STEVE, "Steve"), "ban-evasion"));

        seen(ADMIN_ID, CAFE, 99 * DAY);
        seen(ALT, CAFE, 99 * DAY);
        assertEquals(Authority.Verdict.SELF, ((IssueResult.Refused) h.issue(ADMIN,
                new Target(ALT, "Alt", null, CAFE, List.of()), "spam-ip")).verdict(),
                "an IP action never hits the actor's own account");
    }

    @Test
    void unbanipRemovesTheIpBansOnEveryHashThePlayerUsed() throws Exception {
        Punishment home = h.committed(h.issue(CONSOLE, onHome(STEVE, "Steve"), "ban-evasion"));
        Punishment cafe = h.committed(h.issue(CONSOLE, new Target(STEVE, "Steve", null, CAFE, List.of()), "hacking-ip"));
        Punishment accountBan = h.committed(h.issue(CONSOLE, ServiceHarness.offline(STEVE, "Steve"), "hacking"));

        RemoveResult.Removed removed = assertInstanceOf(RemoveResult.Removed.class, ServiceHarness.get(h.service.remove(
                new Requests.Remove(ADMIN, ServiceHarness.offline(STEVE, "Steve"), PunishmentType.IP_BAN, null, "appeal",
                        List.of(HOME, CAFE)))));
        assertEquals(List.of(home.id(), cafe.id()), removed.removed().stream().map(Punishment::id).toList());
        assertTrue(h.byId(accountBan.id()).isActive(h.clock.get()), "the account ban is a separate slot (E11)");

        Requests.Remove needsIp = new Requests.Remove(staff(UUID.randomUUID(), "Mod", 20, p -> !p.equals("mrpunish.ip")),
                ServiceHarness.offline(STEVE, "Steve"), PunishmentType.IP_BAN, null, null, List.of(HOME));
        assertInstanceOf(RemoveResult.NoPermission.class, ServiceHarness.get(h.service.remove(needsIp)));
    }

    @Test
    void retentionDeletesOldLinksAndPurgeDeletesAll() throws Exception {
        long now = h.clock.get();
        seen(STEVE, HOME, now - 100 * DAY);
        seen(ALT, HOME, now - DAY);
        Map<String, Integer> deleted = db(c -> new Retention(h.storage.database()).run(c, now, 90));
        assertEquals(1, deleted.get("player_ips"));
        assertEquals(1, h.count("player_ips"));
        assertEquals(Map.of("sync_events", 0, "deliveries", 0),
                db(c -> new Retention(h.storage.database()).run(c, now, 0)), "0 = keep forever");
        int purged = db(c -> new IpRepo(h.storage.database()).purge(c));
        assertEquals(1, purged);
        assertEquals(0, h.count("player_ips"));
    }

    @Test
    void theFingerprintIsWrittenOnceAndThenOnlyRead() throws Exception {
        IpRepo repo = new IpRepo(h.storage.database());
        assertTrue(db(repo::fingerprint).isEmpty());
        db(c -> {
            repo.setFingerprint(c, "0123456789abcdef");
            repo.setFingerprint(c, "ffffffffffffffff"); // never overwritten
            return null;
        });
        assertEquals("0123456789abcdef", db(repo::fingerprint).orElseThrow());
    }
}
