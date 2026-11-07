package com.mrstudios.punish.punish;

import static com.mrstudios.punish.punish.ServiceHarness.CONSOLE;
import static com.mrstudios.punish.punish.ServiceHarness.DAY;
import static com.mrstudios.punish.punish.ServiceHarness.HOUR;
import static com.mrstudios.punish.punish.ServiceHarness.MINUTE;
import static com.mrstudios.punish.punish.ServiceHarness.offline;
import static com.mrstudios.punish.punish.ServiceHarness.online;
import static com.mrstudios.punish.punish.ServiceHarness.staff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.punish.Punishment.EffectiveStatus;
import com.mrstudios.punish.punish.Punishment.StoredStatus;
import com.mrstudios.punish.punish.Punishment.WeightSource;
import com.mrstudios.punish.punish.Requests.Actor;
import com.mrstudios.punish.punish.Requests.IssueResult;
import com.mrstudios.punish.punish.Requests.RemoveResult;
import com.mrstudios.punish.punish.Requests.Target;
import com.mrstudios.punish.rank.Authority;
import com.mrstudios.punish.rank.Authority.Standing;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The issue and remove pipeline on a real SQLite database (DESIGN.md 7.4-7.6, 7.10, E1-E13). */
class PunishmentServiceTest {

    @TempDir
    Path dir;
    private ServiceHarness h;

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000005e");
    private static final UUID HELPER_ID = UUID.randomUUID();
    private static final UUID MOD_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final Actor HELPER = staff(HELPER_ID, "Helper", 10);
    private static final Actor MOD = staff(MOD_ID, "Mod", 20);
    private static final Actor MOD2 = staff(UUID.randomUUID(), "Mod2", 20);
    private static final Actor ADMIN = staff(ADMIN_ID, "Admin", 30);
    private static final Actor OWNER = staff(OWNER_ID, "Owner", 40);

    @BeforeEach
    void setUp() throws Exception {
        h = new ServiceHarness(dir);
        h.known(STEVE, "Steve", 0, false);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private Target steve() {
        return offline(STEVE, "Steve");
    }

    private void day(double day) {
        h.clock.set((long) (day * DAY));
    }

    // ---- E1-E13 end to end ----

    @Test
    void e1FirstToxicityMuteIsTwoHours() throws Exception {
        Punishment p = h.committed(h.issue(MOD, steve(), "toxicity"));
        assertEquals(1, p.offence());
        assertEquals(2 * HOUR, p.durationMs());
        assertEquals(h.clock.get() + 2 * HOUR, p.expiresAt());
        assertEquals(StoredStatus.ACTIVE, p.status());
        assertEquals("Toxicity", p.reasonDisplay());
        assertEquals("Mod", p.issuerName());
        assertEquals(20, p.issuerWeight());
        assertEquals(WeightSource.CACHED, p.targetWeightSource());
        assertEquals("default", p.server());
    }

    @Test
    void e2RemovedDoesntCountExpiredDoes() throws Exception {
        day(50);
        h.committed(h.issue(MOD, steve(), "toxicity"));          // expires day 50 + 2h
        day(70);
        h.committed(h.issue(MOD, steve(), "toxicity"));          // offence 2, 1d
        assertInstanceOf(RemoveResult.Removed.class, h.remove(ADMIN, steve(), PunishmentType.MUTE, null, "mistake"));
        day(100);
        Punishment p = h.committed(h.issue(MOD, steve(), "toxicity"));
        assertEquals(2, p.offence());
        assertEquals(DAY, p.durationMs());
    }

    @Test
    void e3OlderThanNinetyDaysDoesntCount() throws Exception {
        day(5);
        h.committed(h.issue(MOD, steve(), "toxicity"));
        day(60);
        h.committed(h.issue(MOD, steve(), "toxicity"));
        day(100);
        Punishment p = h.committed(h.issue(MOD, steve(), "toxicity"));
        // Day 60's (1d) mute has expired; only it counts.
        assertEquals(2, p.offence());
        assertEquals(DAY, p.durationMs());
    }

    @Test
    void e4ALongerMuteReplacesAndTheOldOneStillCounts() throws Exception {
        Punishment spam = h.committed(h.issue(MOD, steve(), "spam"));                   // 10m
        h.clock.addAndGet(6 * MINUTE);                                                   // 4 minutes left
        IssueResult result = h.issue(MOD, steve(), "toxicity");                          // 2h
        IssueResult.Committed committed = assertInstanceOf(IssueResult.Committed.class, result);
        assertEquals(List.of(spam.id()), committed.replaced());

        Punishment old = h.byId(spam.id());
        assertEquals(StoredStatus.REPLACED, old.status());
        assertEquals(committed.punishment().id(), old.replacedById());
        assertEquals(h.clock.get(), old.replacedAt());
        // Still a spam offence: the next spam is offence 2 (30m)...
        h.clock.addAndGet(3 * HOUR);
        assertEquals(2, h.committed(h.issue(MOD, steve(), "spam")).offence());
    }

    @Test
    void e5ShorterIsRefusedAndNothingIsRecorded() throws Exception {
        day(10);
        Punishment toxicity = h.committed(h.issue(CONSOLE, steve(), "toxicity"));  // 2h
        // Make it a 30d mute with 20 days left on day 100, as in the example.
        h.exec("UPDATE punishments SET expires_at = " + (120 * DAY) + ", duration_ms = " + (30 * DAY) + " WHERE id = "
                + toxicity.id());
        day(100);
        int before = h.count("punishments");

        IssueResult result = h.issue(MOD, steve(), "spam");
        IssueResult.WouldShorten refused = assertInstanceOf(IssueResult.WouldShorten.class, result);
        assertEquals(toxicity.id(), refused.blocking().id());
        assertEquals(before, h.count("punishments"));
        assertEquals(0, h.rows("SELECT * FROM staff_actions WHERE action = 'PUNISH' AND target_uuid = '" + STEVE
                + "' AND details LIKE 'MUTE spam%'").size());

        // E6: an admin removes it, then the spam mute goes through, still as offence 1.
        RemoveResult.Removed removed = assertInstanceOf(RemoveResult.Removed.class,
                h.remove(ADMIN, steve(), PunishmentType.MUTE, null, "wrong reason"));
        assertEquals(StoredStatus.REMOVED, removed.removed().get(0).status());
        Punishment spam = h.committed(h.issue(HELPER, steve(), "spam"));
        assertEquals(1, spam.offence());
        assertEquals(10 * MINUTE, spam.durationMs());
    }

    @Test
    void e7FourthHackingBanIsPermanent() throws Exception {
        for (int i = 0; i < 3; i++) {
            day(20 + 20 * i);
            h.committed(h.issue(CONSOLE, steve(), "hacking"));
            h.exec("UPDATE punishments SET expires_at = " + (h.clock.get() + 1) + " WHERE status = 'ACTIVE'");
        }
        day(100);
        Punishment fourth = h.committed(h.issue(MOD, steve(), "hacking"));
        assertEquals(4, fourth.offence());
        assertEquals(3, fourth.step());
        assertNull(fourth.durationMs());
        assertNull(fourth.expiresAt());
    }

    @Test
    void e8PermanentOverPermanent() throws Exception {
        Punishment first = h.committed(h.issue(CONSOLE, steve(), "discrimination-ban"));
        h.exec("UPDATE punishments SET expires_at = NULL, duration_ms = NULL WHERE id = " + first.id());
        assertInstanceOf(IssueResult.WouldShorten.class, h.issue(MOD, steve(), "hacking"));
        // Two earlier discrimination bans make the next one permanent too.
        h.clock.addAndGet(1000);
        IssueResult.Committed again = assertInstanceOf(IssueResult.Committed.class,
                h.issue(CONSOLE, steve(), "discrimination-ban"));
        assertNull(again.punishment().expiresAt());
        assertEquals(List.of(first.id()), again.replaced());
    }

    @Test
    void e9ExtendingAShortRemainderReplaces() throws Exception {
        Punishment hacking = h.committed(h.issue(MOD, steve(), "hacking"));     // 7d
        h.clock.addAndGet(6 * DAY);                                              // 1 day left
        IssueResult.Committed griefing = assertInstanceOf(IssueResult.Committed.class,
                h.issue(MOD, steve(), "griefing"));                              // 3d
        assertEquals(List.of(hacking.id()), griefing.replaced());
        assertEquals(1, griefing.punishment().offence());
        h.clock.addAndGet(4 * DAY);
        assertEquals(2, h.committed(h.issue(MOD, steve(), "hacking")).offence(), "the replaced ban still counts");
    }

    @Test
    void e10RemovingClearsEveryDuplicateTogether() throws Exception {
        Punishment a = h.committed(h.issue(MOD, steve(), "spam"));
        // A second active mute that bypassed the rules (an import or a network race).
        h.exec("INSERT INTO punishments (type, target_uuid, target_name, reason_key, reason_display, offence, step,"
                + " duration_ms, issued_at, expires_at, issuer_name, issuer_weight, target_weight, target_weight_source,"
                + " server, origin, status) SELECT type, target_uuid, target_name, 'toxicity', 'Toxicity', 1, 1,"
                + " duration_ms, issued_at, expires_at, issuer_name, issuer_weight, target_weight, target_weight_source,"
                + " server, origin, status FROM punishments WHERE id = " + a.id());

        RemoveResult.Removed removed = assertInstanceOf(RemoveResult.Removed.class,
                h.remove(ADMIN, steve(), PunishmentType.MUTE, null, null));
        assertEquals(2, removed.removed().size());
        Punishment first = removed.removed().get(0);
        Punishment second = removed.removed().get(1);
        assertEquals(StoredStatus.REMOVED, first.status());
        assertEquals(StoredStatus.REMOVED, second.status());
        assertEquals(first.removedAt(), second.removedAt());
        assertEquals("Admin", second.removedByName());
        assertEquals(ADMIN_ID, second.removedByUuid());
        assertEquals("No reason given", first.removedReason());
        assertEquals(2, h.count("punishments"), "rows are never deleted");
        assertInstanceOf(RemoveResult.None.class, h.remove(ADMIN, steve(), PunishmentType.MUTE, null, null));
    }

    @Test
    void e11AccountAndIpSlotsAreIndependent() throws Exception {
        Punishment ban = h.committed(h.issue(MOD, steve(), "hacking"));
        Target ip = new Target(STEVE, "Steve", null, "a".repeat(64), List.of());
        Punishment ipBan = h.committed(h.issue(ADMIN, ip, "ban-evasion"));
        assertEquals(PunishmentType.IP_BAN, ipBan.type());
        assertEquals("a".repeat(64), ipBan.ipHash());
        assertEquals(StoredStatus.ACTIVE, h.byId(ban.id()).status(), "the account ban is untouched");

        assertInstanceOf(RemoveResult.Removed.class, h.remove(ADMIN, steve(), PunishmentType.BAN, null, "appeal"));
        assertTrue(h.byId(ipBan.id()).isActive(h.clock.get()), "the IP ban stays until /unbanip");
    }

    @Test
    void e12AChangeSinceThePreviewWritesNothing() throws Exception {
        h.committed(h.issue(MOD, steve(), "spam"));
        h.clock.addAndGet(HOUR);                                  // that 10m mute expired
        Preview seen = ServiceHarness.get(h.service.preview(steve(), h.reason("spam")));
        assertEquals(2, seen.offence());

        h.committed(h.issue(MOD2, steve(), "spam"));             // someone else mutes meanwhile
        int before = h.count("punishments");
        IssueResult result = h.issue(MOD, steve(), "spam", null, seen);
        IssueResult.Changed changed = assertInstanceOf(IssueResult.Changed.class, result);
        assertEquals(3, changed.current().offence());
        assertTrue(changed.current().decision().blocked() || !changed.current().decision().replaces().isEmpty());
        assertEquals(before, h.count("punishments"));

        // Confirming the new preview works.
        assertInstanceOf(IssueResult.Committed.class, h.issue(MOD, steve(), "spam", null, changed.current()));
    }

    @Test
    void e13WarningsStackAndAreNeverReplaced() throws Exception {
        for (int d : new int[] {5, 20, 60, 95}) {
            day(d);
            h.committed(h.issue(HELPER, steve(), "spam-warn"));
        }
        day(100);
        IssueResult.Committed warn = assertInstanceOf(IssueResult.Committed.class, h.issue(HELPER, steve(), "spam-warn"));
        assertEquals(4, warn.punishment().offence());
        assertEquals(List.of(), warn.replaced());
        assertNull(warn.punishment().durationMs());
        assertEquals(100 * DAY + 90 * DAY, warn.punishment().expiresAt(), "warnings.active-for");
        assertEquals(5, h.history(STEVE).size());
    }

    // ---- What a commit writes, and what a failure doesn't ----

    @Test
    void aCommitWritesThePunishmentTheStaffActionAndAnOfflineWarnDelivery() throws Exception {
        Punishment warn = h.committed(h.issue(HELPER, steve(), "toxicity-warn", "said <red>bad</red> things", null));
        assertEquals("said <red>bad</red> things", warn.note(), "notes are stored as typed");
        List<Map<String, Object>> actions = h.rows("SELECT * FROM staff_actions");
        assertEquals(1, actions.size());
        assertEquals("PUNISH", actions.get(0).get("action"));
        assertEquals(warn.id(), ((Number) actions.get(0).get("ref_id")).longValue());
        assertEquals(HELPER_ID.toString(), actions.get(0).get("actor_uuid"));
        assertTrue(((String) actions.get(0).get("details")).startsWith("WARN toxicity-warn - offence=1"));
        List<Map<String, Object>> deliveries = h.rows("SELECT * FROM deliveries");
        assertEquals(1, deliveries.size());
        assertEquals("WARN", deliveries.get(0).get("kind"));

        h.committed(h.issue(HELPER, online(STEVE, "Steve", 0), "spam-warn"));
        assertEquals(1, h.count("deliveries"), "an online player gets the warning at once, no delivery");
    }

    @Test
    void anSqlFailureRollsEverythingBackAndReturnsAnErrorId() throws Exception {
        h.committed(h.issue(MOD, steve(), "spam"));
        h.clock.addAndGet(HOUR);
        int punishments = h.count("punishments");
        // Make the staff action insert fail after the punishment insert and the replacement update.
        h.exec("ALTER TABLE staff_actions RENAME TO staff_actions_gone");

        IssueResult result = h.issue(MOD, steve(), "toxicity");
        IssueResult.Failed failed = assertInstanceOf(IssueResult.Failed.class, result);
        assertTrue(failed.errorId().matches("[0-9a-f]{8}"));
        assertTrue(h.logged.stream().anyMatch(l -> l.contains(failed.errorId())));
        h.exec("ALTER TABLE staff_actions_gone RENAME TO staff_actions");
        assertEquals(punishments, h.count("punishments"), "the punishment insert was rolled back");
        assertTrue(h.storage.health().isUp(), "a broken statement isn't a lost connection");
        assertFalse(h.service.inFlight().isBusy(MOD_ID, STEVE), "the in-flight claim is released");
    }

    @Test
    void storageDownMeansNothingRuns() throws Exception {
        h.storage.health().markDown("test");
        assertInstanceOf(IssueResult.Unavailable.class, h.issue(MOD, steve(), "spam"));
        assertInstanceOf(RemoveResult.Unavailable.class, h.remove(ADMIN, steve(), PunishmentType.MUTE, null, null));
        h.storage.health().markUp();
        assertEquals(0, h.count("punishments"));
    }

    // ---- Authority and permissions ----

    @Test
    void rankExemptAndSelfAreRefusedAndLogged() throws Exception {
        UUID modTarget = UUID.randomUUID();
        h.known(modTarget, "OtherMod", 20, false);
        Requests.IssueResult equal = h.issue(MOD, offline(modTarget, "OtherMod"), "spam");
        assertEquals(Authority.Verdict.RANK, assertInstanceOf(IssueResult.Refused.class, equal).verdict());

        UUID exemptId = UUID.randomUUID();
        h.known(exemptId, "Vip", 0, true);
        assertEquals(Authority.Verdict.EXEMPT, ((IssueResult.Refused) h.issue(OWNER, offline(exemptId, "Vip"), "spam"))
                .verdict());
        assertInstanceOf(IssueResult.Committed.class, h.issue(CONSOLE, offline(exemptId, "Vip"), "spam"),
                "console may punish exempt players");

        assertEquals(Authority.Verdict.SELF, ((IssueResult.Refused) h.issue(MOD, online(MOD_ID, "Mod", 20), "spam"))
                .verdict());
        assertEquals(Authority.Verdict.RANK, ((IssueResult.Refused) h.issue(MOD, online(ADMIN_ID, "Admin", 30), "spam"))
                .verdict());

        Thread.sleep(200); // the main-thread refusals are logged asynchronously
        assertEquals(4, h.rows("SELECT * FROM staff_actions WHERE action = 'REFUSED'").size());
        assertEquals(1, h.count("punishments"));
    }

    @Test
    void theTargetIsRecheckedInsideTheTransaction() throws Exception {
        // Promoted while offline: the cached weight read in the transaction protects them (X5).
        UUID target = UUID.randomUUID();
        h.known(target, "Rising", 0, false);
        h.known(target, "Rising", 30, false);
        assertInstanceOf(IssueResult.Refused.class, h.issue(MOD, offline(target, "Rising"), "spam"));
    }

    @Test
    void permissionsAreCheckedForTypeIpAndReason() throws Exception {
        Actor noBan = staff(UUID.randomUUID(), "Helper2", 10, p -> !p.equals("mrpunish.ban"));
        assertEquals("mrpunish.ban", ((IssueResult.NoPermission) h.issue(noBan, steve(), "hacking")).permission());
        Actor noIp = staff(UUID.randomUUID(), "Mod3", 20, p -> !p.equals("mrpunish.ip"));
        Target ip = new Target(STEVE, "Steve", null, "b".repeat(64), List.of());
        assertEquals("mrpunish.ip", ((IssueResult.NoPermission) h.issue(noIp, ip, "ban-evasion")).permission());
        Actor noRemove = staff(UUID.randomUUID(), "Mod4", 20, p -> !p.equals("mrpunish.remove"));
        assertInstanceOf(RemoveResult.NoPermission.class, h.remove(noRemove, steve(), PunishmentType.MUTE, null, null));
        assertEquals(0, h.count("punishments"));
    }

    @Test
    void unknownTargetsCantBePunished() throws Exception {
        assertInstanceOf(IssueResult.UnknownTarget.class, h.issue(MOD, offline(UUID.randomUUID(), "Nobody"), "spam"));
    }

    @Test
    void ipActionsCheckEveryAffectedAccount() throws Exception {
        Target ip = new Target(STEVE, "Steve", null, "c".repeat(64),
                List.of(new Standing(UUID.randomUUID(), "Alt", 0, false), new Standing(ADMIN_ID, "Admin", 30, false)));
        IssueResult.Refused refused = assertInstanceOf(IssueResult.Refused.class, h.issue(ADMIN, ip, "ban-evasion"));
        assertEquals(Authority.Verdict.SELF, refused.verdict());
        Target withOwnerAlt = new Target(STEVE, "Steve", null, "c".repeat(64),
                List.of(new Standing(UUID.randomUUID(), "OwnerAlt", 40, false)));
        IssueResult.Refused rank = assertInstanceOf(IssueResult.Refused.class, h.issue(ADMIN, withOwnerAlt, "ban-evasion"));
        assertEquals(1, rank.refusedAccounts());
        Target onlyAlts = new Target(STEVE, "Steve", null, "c".repeat(64),
                List.of(new Standing(UUID.randomUUID(), "Alt", 0, false)));
        IssueResult.Committed committed = assertInstanceOf(IssueResult.Committed.class,
                h.issue(OWNER, onlyAlts, "ban-evasion"));
        assertTrue(h.rows("SELECT details FROM staff_actions WHERE action = 'PUNISH'").get(0).get("details").toString()
                .endsWith("accounts=2"));
        assertEquals("c".repeat(64), committed.punishment().ipHash());
    }

    @Test
    void ipEscalationIsCountedPerHashSoAltsShareTheLadder() throws Exception {
        UUID alt = UUID.randomUUID();
        h.known(alt, "Alt", 0, false);
        String hash = "d".repeat(64);
        Punishment first = h.committed(h.issue(ADMIN, new Target(STEVE, "Steve", null, hash, List.of()), "spam-ip"));
        h.clock.addAndGet(2 * DAY);
        Punishment onAlt = h.committed(h.issue(ADMIN, new Target(alt, "Alt", null, hash, List.of()), "spam-ip"));
        assertEquals(1, first.offence());
        assertEquals(2, onAlt.offence(), "the alt's first IP mute is the hash's second");
        Punishment otherHash = h.committed(h.issue(ADMIN, new Target(alt, "Alt", null, "e".repeat(64), List.of()),
                "spam-ip"));
        assertEquals(1, otherHash.offence());
    }

    // ---- Weight sources ----

    @Test
    void luckPermsWeightIsUsedAndTheHigherOfItAndTheCacheWins() throws Exception {
        UUID target = UUID.randomUUID();
        h.known(target, "Lp", 0, false);
        h.offline = uuid -> CompletableFuture.completedFuture(Optional.of(new Standing(uuid, "Lp", 30, false)));
        assertEquals(Authority.Verdict.RANK, ((IssueResult.Refused) h.issue(MOD, offline(target, "Lp"), "spam")).verdict(),
                "promoted in LuckPerms while offline");

        h.offline = uuid -> CompletableFuture.completedFuture(Optional.of(new Standing(uuid, "Lp", 0, false)));
        Punishment p = h.committed(h.issue(MOD, offline(target, "Lp"), "spam"));
        assertEquals(WeightSource.LUCKPERMS, p.targetWeightSource());

        UUID cachedHigh = UUID.randomUUID();
        h.known(cachedHigh, "Op", 40, false);
        assertInstanceOf(IssueResult.Refused.class, h.issue(MOD, offline(cachedHigh, "Op"), "spam"),
                "a weight only the cache knows (e.g. op) still protects them");
    }

    @Test
    void aFailingOrSlowLuckPermsFallsBackToTheCache() throws Exception {
        h.offline = uuid -> CompletableFuture.failedFuture(new IllegalStateException("LuckPerms broke"));
        Punishment p = h.committed(h.issue(MOD, steve(), "spam"));
        assertEquals(WeightSource.CACHED, p.targetWeightSource());
        assertTrue(h.logged.stream().anyMatch(l -> l.contains("Couldn't load " + STEVE + " from LuckPerms")));
    }

    @Test
    void anOnlineTargetsLiveWeightIsUsed() throws Exception {
        Punishment p = h.committed(h.issue(MOD, online(STEVE, "Steve", 10), "spam"));
        assertEquals(WeightSource.LIVE, p.targetWeightSource());
        assertEquals(10, p.targetWeight());
    }

    // ---- Removal rules ----

    @Test
    void aRemoverNeedsAtLeastTheIssuersWeight() throws Exception {
        Punishment ownerBan = h.committed(h.issue(OWNER, steve(), "hacking"));
        RemoveResult refused = h.remove(ADMIN, steve(), PunishmentType.BAN, null, "appeal");
        assertEquals(ownerBan.id(), assertInstanceOf(RemoveResult.IssuerRank.class, refused).id());
        assertEquals(StoredStatus.ACTIVE, h.byId(ownerBan.id()).status(), "nothing changed");
        assertInstanceOf(RemoveResult.Removed.class, h.remove(OWNER, steve(), PunishmentType.BAN, null, "appeal"));
    }

    @Test
    void oneHeavierIssuerRefusesTheWholeRemoval() throws Exception {
        Punishment mod = h.committed(h.issue(MOD, steve(), "spam"));
        h.exec("INSERT INTO punishments (type, target_uuid, target_name, reason_key, reason_display, offence, step,"
                + " issued_at, issuer_name, issuer_weight, target_weight, target_weight_source, server, origin, status)"
                + " VALUES ('MUTE', '" + STEVE + "', 'Steve', 'toxicity', 'Toxicity', 1, 1, " + h.clock.get()
                + ", 'Owner', 40, 0, 'CACHED', 'default', 'COMMAND', 'ACTIVE')");
        assertInstanceOf(RemoveResult.IssuerRank.class, h.remove(ADMIN, steve(), PunishmentType.MUTE, null, "x"));
        assertEquals(StoredStatus.ACTIVE, h.byId(mod.id()).status(), "the removable one wasn't removed either");
    }

    @Test
    void consolePunishmentsCanBeRemovedByAdminsAndConsoleRemovesAnything() throws Exception {
        Punishment byConsole = h.committed(h.issue(CONSOLE, steve(), "hacking"));
        assertEquals(0, byConsole.issuerWeight());
        assertNull(byConsole.issuerUuid());
        assertInstanceOf(RemoveResult.Removed.class, h.remove(ADMIN, steve(), PunishmentType.BAN, null, null));
        h.committed(h.issue(OWNER, steve(), "hacking"));
        assertInstanceOf(RemoveResult.Removed.class, h.remove(CONSOLE, steve(), PunishmentType.BAN, null, null));
    }

    @Test
    void nobodyRemovesTheirOwnPunishment() throws Exception {
        h.known(ADMIN_ID, "Admin", 30, false);
        h.committed(h.issue(OWNER, offline(ADMIN_ID, "Admin"), "spam"));
        assertInstanceOf(RemoveResult.Self.class, h.remove(ADMIN, offline(ADMIN_ID, "Admin"), PunishmentType.MUTE, null,
                null));
    }

    @Test
    void unwarnRemovesOneWarningByIdOrTheMostRecent() throws Exception {
        Punishment w1 = h.committed(h.issue(HELPER, steve(), "spam-warn"));
        Punishment w2 = h.committed(h.issue(HELPER, steve(), "toxicity-warn"));
        RemoveResult.Removed latest = assertInstanceOf(RemoveResult.Removed.class,
                h.remove(ADMIN, steve(), PunishmentType.WARN, null, null));
        assertEquals(List.of(w2.id()), latest.removed().stream().map(Punishment::id).toList());
        assertInstanceOf(RemoveResult.NotFound.class, h.remove(ADMIN, steve(), PunishmentType.WARN, w2.id(), null),
                "already removed");
        assertInstanceOf(RemoveResult.NotFound.class, h.remove(ADMIN, steve(), PunishmentType.WARN, 9999L, null));
        assertInstanceOf(RemoveResult.Removed.class, h.remove(ADMIN, steve(), PunishmentType.WARN, w1.id(), null));
    }

    @Test
    void anIdOfAnotherPlayerOrTypeOrNotActiveIsNotFound() throws Exception {
        UUID other = UUID.randomUUID();
        h.known(other, "Other", 0, false);
        Punishment othersMute = h.committed(h.issue(MOD, offline(other, "Other"), "spam"));
        assertInstanceOf(RemoveResult.NotFound.class, h.remove(ADMIN, steve(), PunishmentType.MUTE, othersMute.id(), null));
        assertInstanceOf(RemoveResult.NotFound.class, h.remove(ADMIN, offline(other, "Other"), PunishmentType.BAN,
                othersMute.id(), null));
        h.clock.addAndGet(DAY);
        assertEquals(EffectiveStatus.EXPIRED, h.byId(othersMute.id()).effectiveStatus(h.clock.get()));
        assertInstanceOf(RemoveResult.NotFound.class, h.remove(ADMIN, offline(other, "Other"), PunishmentType.MUTE,
                othersMute.id(), null), "expired records can't be removed");
    }

    @Test
    void removalWritesAStaffAction() throws Exception {
        Punishment p = h.committed(h.issue(MOD, steve(), "hacking"));
        h.remove(ADMIN, steve(), PunishmentType.BAN, null, "appeal accepted");
        Map<String, Object> row = h.rows("SELECT * FROM staff_actions WHERE action = 'REMOVE'").get(0);
        assertEquals(p.id(), ((Number) row.get("ref_id")).longValue());
        assertEquals("BAN [#" + p.id() + "] appeal accepted", row.get("details"));
    }

    // ---- In flight ----

    @Test
    void aSecondActionForTheSamePairWhileOneIsInFlightIsBusy() throws Exception {
        h.service.inFlight().acquire(MOD_ID, STEVE);
        assertInstanceOf(IssueResult.Busy.class, h.issue(MOD, steve(), "spam"));
        assertInstanceOf(RemoveResult.Busy.class, h.remove(MOD, steve(), PunishmentType.MUTE, null, null));
        assertInstanceOf(IssueResult.Committed.class, h.issue(MOD2, steve(), "spam"), "another actor isn't blocked");
        h.service.inFlight().release(MOD_ID, STEVE);
        h.clock.addAndGet(HOUR);
        assertInstanceOf(IssueResult.Committed.class, h.issue(MOD, steve(), "spam"));
    }

    @Test
    void concurrentIssuesForTheSameTargetAreSerialised() throws Exception {
        // Two staff confirm the same preview at once: one commits, the other sees CHANGED (X9).
        Preview seen = ServiceHarness.get(h.service.preview(steve(), h.reason("spam")));
        CompletableFuture<IssueResult> a = h.service.issue(new Requests.Issue(MOD, steve(), h.reason("spam"), null,
                false, Punishment.Origin.GUI, seen));
        CompletableFuture<IssueResult> b = h.service.issue(new Requests.Issue(MOD2, steve(), h.reason("spam"), null,
                false, Punishment.Origin.GUI, seen));
        List<IssueResult> results = List.of(ServiceHarness.get(a), ServiceHarness.get(b));
        assertEquals(1, results.stream().filter(r -> r instanceof IssueResult.Committed).count());
        assertEquals(1, results.stream().filter(r -> r instanceof IssueResult.Changed).count());
        assertEquals(1, h.count("punishments"));
    }
}
