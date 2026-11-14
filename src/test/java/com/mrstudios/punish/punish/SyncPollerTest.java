package com.mrstudios.punish.punish;

import static com.mrstudios.punish.punish.ServiceHarness.offline;
import static com.mrstudios.punish.punish.ServiceHarness.staff;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.network.SyncPoller;
import com.mrstudios.punish.punish.Requests.Actor;
import com.mrstudios.punish.punish.Requests.IssueResult;
import com.mrstudios.punish.punish.Requests.Target;
import com.mrstudios.punish.report.Report;
import com.mrstudios.punish.storage.Dialect;
import com.mrstudios.punish.storage.FrozenRepo;
import com.mrstudios.punish.storage.ReportRepo;
import com.mrstudios.punish.storage.SyncRepo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Network sync (DESIGN.md 9.4) on whichever database the tests run on: the events the transactions
 * write, and what the poller does with events from "another server".
 */
class SyncPollerTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000005e");
    private static final Actor MOD = staff(UUID.randomUUID(), "Mod", 20);
    private static final Actor MOD2 = staff(UUID.randomUUID(), "Mod2", 20);
    private static final String OTHER = "lobby#0badf00d";

    @TempDir
    Path dir;
    private ServiceHarness h;
    private final List<String> applied = new ArrayList<>();
    private SyncPoller poller;

    @BeforeEach
    void setUp() throws Exception {
        h = new ServiceHarness(dir);
        h.known(STEVE, "Steve", 0, false);
        poller = new SyncPoller(h.storage, new SyncPoller.Target() {
            @Override
            public void punishmentIssued(Punishment p) {
                applied.add("issued " + p.type() + " #" + p.id() + " " + p.status());
            }

            @Override
            public void punishmentRemoved(Punishment p) {
                applied.add("removed #" + p.id());
            }

            @Override
            public void frozen(FrozenRepo.Frozen row) {
                applied.add("frozen " + row.name() + " by " + row.byName());
            }

            @Override
            public void unfrozen(UUID uuid) {
                applied.add("unfrozen " + uuid);
            }

            @Override
            public void reportCreated(Report report) {
                if (report.id() < 0) {
                    throw new IllegalStateException("boom");
                }
                applied.add("report #" + report.id() + " " + report.targetName());
            }
        }, Runnable::run, () -> 3, h.clock::get, Logger.getAnonymousLogger());
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private Target steve() {
        return offline(STEVE, "Steve");
    }

    private void poll() throws Exception {
        ServiceHarness.get(poller.poll());
    }

    private void event(String origin, String kind, UUID target, Long ref) throws Exception {
        ServiceHarness.get(h.storage.executor().submit("test", c -> {
            new SyncRepo(h.storage.database(), true).insertAs(c, h.clock.get(), origin, kind, target, null, ref);
            return null;
        }));
    }

    @Test
    void transactionsWriteEventsOnMysqlOnly() throws Exception {
        boolean mysql = h.storage.database().dialect() == Dialect.MYSQL;
        Punishment ban = h.committed(h.issue(MOD, steve(), "hacking"));
        h.remove(MOD, steve(), PunishmentType.BAN, ban.id(), "appeal");
        List<java.util.Map<String, Object>> rows = h.rows("SELECT kind, target_uuid, ref_id, server FROM sync_events"
                + " ORDER BY id");
        if (!mysql) {
            assertEquals(List.of(), rows, "SQLite is one server: nothing to sync");
            return;
        }
        assertEquals(List.of("PUNISH", "REMOVE"), rows.stream().map(r -> r.get("kind")).toList());
        assertEquals(STEVE.toString(), rows.get(0).get("target_uuid"));
        assertEquals(ban.id(), ((Number) rows.get(0).get("ref_id")).longValue());
        assertTrue(SyncRepo.fromHere((String) rows.get(0).get("server")), "written as this server");
        // A refused punishment writes nothing.
        h.known(UUID.fromString("00000000-0000-0000-0000-0000000000ad"), "Admin", 30, false);
        h.issue(MOD, offline(UUID.fromString("00000000-0000-0000-0000-0000000000ad"), "Admin"), "hacking");
        assertEquals(2, h.count("sync_events"));
    }

    @Test
    void startsAfterTheNewestEventAndSkipsItsOwn() throws Exception {
        Punishment ban = h.committed(h.issue(MOD, steve(), "hacking"));
        event(OTHER, SyncRepo.PUNISH, STEVE, ban.id());          // before startup: not replayed
        poll();
        assertTrue(poller.lastId() > 0);
        poll();
        assertEquals(List.of(), applied);

        event(SyncRepo.origin("lobby"), SyncRepo.PUNISH, STEVE, ban.id());  // this server's own
        event(OTHER, SyncRepo.PUNISH, STEVE, ban.id());
        poll();
        assertEquals(List.of("issued BAN #" + ban.id() + " ACTIVE"), applied);
        poll();
        assertEquals(1, applied.size(), "each event once");
    }

    @Test
    void theRowIsReadAgainNotTheEvent() throws Exception {
        poll();
        Punishment mute = h.committed(h.issue(MOD, steve(), "spam"));
        event(OTHER, SyncRepo.REMOVE, STEVE, mute.id());           // claims a removal that didn't happen
        event(OTHER, SyncRepo.PUNISH, STEVE, 999_999L);            // no such punishment
        event(OTHER, SyncRepo.PUNISH, STEVE, null);
        event(OTHER, "SOMETHING_NEW", STEVE, mute.id());           // from a newer version
        poll();
        assertEquals(List.of(), applied);

        h.remove(MOD, steve(), PunishmentType.MUTE, mute.id(), "mistake");
        event(OTHER, SyncRepo.REMOVE, STEVE, mute.id());
        event(OTHER, SyncRepo.PUNISH, STEVE, mute.id());           // the target decides: it's no longer active
        poll();
        assertEquals(List.of("removed #" + mute.id(), "issued MUTE #" + mute.id() + " REMOVED"), applied);
    }

    @Test
    void freezesFollowTheFrozenTable() throws Exception {
        poll();
        FrozenRepo frozen = new FrozenRepo(h.storage.database());
        event(OTHER, SyncRepo.FREEZE, STEVE, null);                // row already gone again: nothing
        ServiceHarness.get(h.storage.executor().submit("t", c -> frozen.insert(c,
                new FrozenRepo.Frozen(STEVE, "Steve", null, "Mod", 20, h.clock.get(), "lobby"))));
        event(OTHER, SyncRepo.UNFREEZE, STEVE, null);              // frozen again meanwhile: stays frozen
        event(OTHER, SyncRepo.FREEZE, STEVE, null);
        poll();
        assertEquals(List.of("frozen Steve by Mod", "frozen Steve by Mod"), applied);
        applied.clear();
        ServiceHarness.get(h.storage.executor().submit("t", c -> frozen.delete(c, STEVE)));
        event(OTHER, SyncRepo.UNFREEZE, STEVE, null);
        poll();
        assertEquals(List.of("unfrozen " + STEVE), applied);
    }

    @Test
    void reportsAreAnnouncedAndOneFailureDoesntStopTheRest() throws Exception {
        poll();
        long id = ServiceHarness.get(h.storage.executor().submit("t", c -> new ReportRepo(h.storage.database())
                .insert(c, new Report(0, UUID.randomUUID(), "Alex", STEVE, "Steve", "spam", "Spam", "lobby", "world",
                        1, 64, -3, h.clock.get(), Report.Status.OPEN, null, null, null, null, null, null, null, 0))));
        event(OTHER, SyncRepo.REPORT, STEVE, id);
        event(OTHER, SyncRepo.REPORT, STEVE, id);
        poll();
        assertEquals(List.of("report #" + id + " Steve", "report #" + id + " Steve"), applied);
    }

    /** M32 at the service level: two staff confirm the same preview at once; one wins, one is told it changed. */
    @Test
    void simultaneousConfirmsOneCommitsTheOtherIsChanged() throws Exception {
        Preview seen = ServiceHarness.get(h.service.preview(steve(), h.reason("spam")));
        CompletableFuture<IssueResult> a = h.service.issue(new Requests.Issue(MOD, steve(), h.reason("spam"), null,
                false, Punishment.Origin.GUI, seen));
        CompletableFuture<IssueResult> b = h.service.issue(new Requests.Issue(MOD2, steve(), h.reason("spam"), null,
                false, Punishment.Origin.GUI, seen));
        List<IssueResult> results = List.of(ServiceHarness.get(a), ServiceHarness.get(b));
        assertEquals(1, results.stream().filter(r -> r instanceof IssueResult.Committed).count(), results.toString());
        assertInstanceOf(IssueResult.Changed.class, results.stream().filter(r -> !(r instanceof IssueResult.Committed))
                .findFirst().orElseThrow());
        assertEquals(1, h.count("punishments"));
    }
}
