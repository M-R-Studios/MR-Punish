package com.mrstudios.punish.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.report.Report;
import com.mrstudios.punish.report.Report.Status;
import com.mrstudios.punish.storage.ReportRepo.Filter;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Report states and the optimistic lock (DESIGN.md 13.2, 9.3). */
class ReportRepoTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID MOD = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID MOD2 = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    @TempDir
    Path dir;
    private SqliteDatabase db;
    private ReportRepo repo;

    @BeforeEach
    void setUp() throws Exception {
        db = new SqliteDatabase(dir.resolve("punishments.db").toFile());
        Migrations.shipped(() -> 0L).migrate(db, line -> { });
        repo = new ReportRepo(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private long report(UUID reporter, UUID target, long at) throws Exception {
        Report r = new Report(0, reporter, "R", target, "T", "spam", "Spam", "default", "world", 1, 64, -3, at,
                Status.OPEN, null, null, null, null, null, null, null, 0);
        return db.with(c -> repo.insert(c, r));
    }

    private Report get(long id) throws Exception {
        return db.with(c -> repo.byId(c, id)).orElseThrow();
    }

    @Test
    void aNewReportIsOpenAtVersionZeroWithItsLocation() throws Exception {
        Report r = get(report(ALEX, STEVE, 100));
        assertEquals(Status.OPEN, r.status());
        assertEquals(0, r.version());
        assertEquals("world", r.world());
        assertEquals(-3, r.z());
        assertNull(r.claimedByUuid());
    }

    @Test
    void duplicatesAndOpenCountsOnlyCountOpenAndClaimed() throws Exception {
        long first = report(ALEX, STEVE, 100);
        report(ALEX, MOD, 101);
        assertEquals(first, db.with(c -> repo.openAbout(c, ALEX, STEVE)).orElseThrow().id());
        assertEquals(2, (int) db.with(c -> repo.openBy(c, ALEX)));
        assertTrue((boolean) db.with(c -> repo.claim(c, first, 0, MOD, "Mod", 200)));
        assertEquals(first, db.with(c -> repo.openAbout(c, ALEX, STEVE)).orElseThrow().id(), "claimed still counts");
        assertTrue((boolean) db.with(c -> repo.close(c, first, 1, Status.RESOLVED, MOD, "Mod", 300, "done")));
        assertTrue(db.with(c -> repo.openAbout(c, ALEX, STEVE)).isEmpty(), "closed doesn't");
        assertEquals(1, (int) db.with(c -> repo.openBy(c, ALEX)));
        assertEquals(1, (int) db.with(c -> repo.countOpen(c)));
    }

    @Test
    void claimUnclaimAndCloseBumpTheVersionAndAStaleVersionChangesNothing() throws Exception {
        long id = report(ALEX, STEVE, 100);
        assertTrue((boolean) db.with(c -> repo.claim(c, id, 0, MOD, "Mod", 200)));
        Report claimed = get(id);
        assertEquals(Status.CLAIMED, claimed.status());
        assertEquals(1, claimed.version());
        assertTrue(claimed.claimedBy(MOD));
        // A second staff member with the version they saw before the claim.
        assertFalse((boolean) db.with(c -> repo.claim(c, id, 0, MOD2, "Mod2", 201)));
        assertFalse((boolean) db.with(c -> repo.close(c, id, 0, Status.REJECTED, MOD2, "Mod2", 202, null)));
        assertEquals(claimed, get(id), "nothing changed");
        assertTrue((boolean) db.with(c -> repo.unclaim(c, id, 1)));
        Report open = get(id);
        assertEquals(Status.OPEN, open.status());
        assertEquals(2, open.version());
        assertNull(open.claimedByName());
        assertFalse((boolean) db.with(c -> repo.unclaim(c, id, 2)), "not claimed any more");
        assertTrue((boolean) db.with(c -> repo.close(c, id, 2, Status.REJECTED, MOD2, "Mod2", 300, "no evidence")));
        Report closed = get(id);
        assertEquals(Status.REJECTED, closed.status());
        assertEquals("no evidence", closed.decisionNote());
        assertEquals(300L, closed.closedAt());
        assertEquals(3, closed.version());
        // Closed reports stay closed, even with the right version.
        assertFalse((boolean) db.with(c -> repo.close(c, id, 3, Status.RESOLVED, MOD, "Mod", 400, null)));
        assertFalse((boolean) db.with(c -> repo.claim(c, id, 3, MOD, "Mod", 400)));
        assertThrows(IllegalArgumentException.class, () -> repo.close(null, id, 3, Status.OPEN, MOD, "Mod", 1, null));
    }

    @Test
    void twoResolvesWithTheSameVersionOnlyOneWins() throws Exception {
        long id = report(ALEX, STEVE, 100);
        boolean first = db.with(c -> repo.close(c, id, 0, Status.RESOLVED, MOD, "Mod", 200, "a"));
        boolean second = db.with(c -> repo.close(c, id, 0, Status.REJECTED, MOD2, "Mod2", 201, "b"));
        assertTrue(first);
        assertFalse(second);
        assertEquals(Status.RESOLVED, get(id).status());
        assertEquals("Mod", get(id).closedByName());
    }

    @Test
    void filtersAndOrder() throws Exception {
        long old = report(ALEX, STEVE, 100);
        long newer = report(MOD2, STEVE, 200);
        long mine = report(STEVE, ALEX, 150);
        long closedFirst = report(ALEX, MOD, 50);
        long closedLater = report(MOD2, MOD, 60);
        db.with(c -> repo.claim(c, mine, 0, MOD, "Mod", 300));
        db.with(c -> repo.close(c, closedFirst, 0, Status.RESOLVED, MOD, "Mod", 400, null));
        db.with(c -> repo.close(c, closedLater, 0, Status.REJECTED, MOD, "Mod", 500, null));
        assertEquals(List.of(old, newer), ids(Filter.OPEN, MOD), "oldest first");
        assertEquals(List.of(mine), ids(Filter.CLAIMED, MOD2));
        assertEquals(List.of(mine), ids(Filter.MINE, MOD));
        assertEquals(List.of(), ids(Filter.MINE, MOD2));
        assertEquals(List.of(closedLater, closedFirst), ids(Filter.CLOSED, MOD), "latest closed first");
        assertEquals(List.of(closedLater, closedFirst, mine, newer, old), ids(Filter.ALL, MOD));
        assertEquals(2, (int) db.with(c -> repo.count(c, Filter.OPEN, MOD)));
        assertEquals(1, (int) db.with(c -> repo.count(c, Filter.MINE, MOD)));
        assertEquals(5, (int) db.with(c -> repo.count(c, Filter.ALL, MOD)));
        assertEquals(List.of(newer), db.with(c -> repo.page(c, Filter.OPEN, MOD, 1, 45)).stream().map(Report::id)
                .toList(), "offset");
        assertEquals(Filter.CLAIMED, Filter.OPEN.next());
        assertEquals(Filter.OPEN, Filter.ALL.next());
    }

    private List<Long> ids(Filter filter, UUID viewer) throws Exception {
        return db.with(c -> repo.page(c, filter, viewer, 0, 45)).stream().map(Report::id).toList();
    }

    @Test
    void notesAppendNewestFirstAndClaimsExpireByTime() throws Exception {
        long id = report(ALEX, STEVE, 100);
        db.with(c -> repo.addNote(c, id, MOD, "Mod", "first", 10));
        db.with(c -> repo.addNote(c, id, null, "Console", "second", 20));
        List<Report.Note> notes = db.with(c -> repo.notes(c, id, 0, 7));
        assertEquals(List.of("second", "first"), notes.stream().map(Report.Note::text).toList());
        assertNull(notes.get(0).authorUuid());
        assertEquals(2, (int) db.with(c -> repo.noteCount(c, id)));
        long other = report(MOD2, STEVE, 100);
        db.with(c -> repo.claim(c, id, 0, MOD, "Mod", 1000));
        db.with(c -> repo.claim(c, other, 0, MOD, "Mod", 5000));
        assertEquals(List.of(id), db.with(c -> repo.claimedBefore(c, 2000)).stream().map(Report::id).toList());
    }
}
