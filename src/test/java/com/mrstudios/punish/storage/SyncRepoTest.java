package com.mrstudios.punish.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code sync_events} table (DESIGN.md 9.4), on SQLite or MySQL (see {@link TestDatabases}). */
class SyncRepoTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;
    private Database db;

    @BeforeEach
    void setUp() throws Exception {
        db = TestDatabases.open(dir);
        Migrations.shipped(() -> 0L).migrate(db, line -> { });
    }

    @AfterEach
    void tearDown() {
        TestDatabases.dispose(db);
    }

    @Test
    void writtenOnlyOnMysql() throws Exception {
        SyncRepo shipped = new SyncRepo(db);
        assertEquals(db.dialect() == Dialect.MYSQL, shipped.enabled());
        db.with(c -> {
            shipped.insert(c, 1, "lobby", SyncRepo.PUNISH, STEVE, null, 5L);
            return null;
        });
        assertEquals(shipped.enabled() ? 1 : 0, db.with(c -> shipped.after(c, 0, 10)).size());
    }

    @Test
    void eventsReadBackInOrderFromAnId() throws Exception {
        SyncRepo repo = new SyncRepo(db, true);
        assertEquals(0L, (long) db.with(repo::maxId));
        db.with(c -> {
            repo.insertAs(c, 10, "other#00000001", SyncRepo.PUNISH, STEVE, "abc", 7L);
            repo.insert(c, 11, "lobby", SyncRepo.FREEZE, STEVE, null, null);
            repo.insertAs(c, 12, "other#00000001", SyncRepo.REPORT, null, null, 3L);
            return null;
        });
        long start = db.with(repo::maxId);
        List<SyncRepo.Event> all = db.with(c -> repo.after(c, 0, 10));
        assertEquals(List.of(SyncRepo.PUNISH, SyncRepo.FREEZE, SyncRepo.REPORT),
                all.stream().map(SyncRepo.Event::kind).toList());
        SyncRepo.Event first = all.get(0);
        assertEquals(STEVE, first.targetUuid());
        assertEquals("abc", first.ipHash());
        assertEquals(7L, first.refId());
        assertNull(all.get(1).refId());
        assertNull(all.get(2).targetUuid());
        assertEquals(start, all.get(2).id());
        assertTrue(db.with(c -> repo.after(c, start, 10)).isEmpty());
        assertEquals(1, db.with(c -> repo.after(c, all.get(0).id(), 1)).size());

        // Who wrote it: this run of the plugin, whatever its server-name.
        assertTrue(SyncRepo.fromHere(all.get(1).origin()));
        assertFalse(SyncRepo.fromHere(first.origin()));
        assertFalse(SyncRepo.fromHere(null));
    }

    @Test
    void originsFitTheColumnAndTellRunsApart() {
        String long32 = SyncRepo.origin("a-very-long-server-name-indeed-yes");
        assertTrue(long32.length() <= 32, long32);
        assertTrue(SyncRepo.fromHere(long32));
        assertTrue(SyncRepo.fromHere(SyncRepo.origin("default")));
        assertFalse(SyncRepo.fromHere("default#ffffffff") && SyncRepo.fromHere("default#00000000"));
    }

    @Test
    void retentionKeepsOnlyTheLastDay() throws Exception {
        SyncRepo repo = new SyncRepo(db, true);
        long day = 24L * 60 * 60 * 1000;
        long now = 10 * day;
        db.with(c -> {
            repo.insertAs(c, now - 2 * day, "o#1", SyncRepo.PUNISH, STEVE, null, 1L);
            repo.insertAs(c, now - 1000, "o#1", SyncRepo.PUNISH, STEVE, null, 2L);
            return null;
        });
        assertEquals(1, (int) db.with(c -> new Retention(db).run(c, now)).get("sync_events"));
        assertEquals(List.of(2L), db.with(c -> repo.after(c, 0, 10)).stream().map(SyncRepo.Event::refId).toList());
    }
}
