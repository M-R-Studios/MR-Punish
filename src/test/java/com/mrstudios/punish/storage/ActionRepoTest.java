package com.mrstudios.punish.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading the staff action log: pages, filters, tailing and retention (DESIGN.md 13.6, 9.5). */
class ActionRepoTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID MOD = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path dir;
    private Database db;
    private ActionRepo repo;

    @BeforeEach
    void setUp() throws Exception {
        db = TestDatabases.open(dir);
        Migrations.shipped(() -> 0L).migrate(db, line -> { });
        repo = new ActionRepo(db);
    }

    @AfterEach
    void tearDown() {
        TestDatabases.dispose(db);
    }

    private void log(long at, UUID actor, String name, String action) throws Exception {
        db.with(c -> {
            repo.insert(c, new ActionRepo.Action(at, "s", actor, name, action, STEVE, "Steve", null, "d"));
            return null;
        });
    }

    @Test
    void pagesAreNewestFirstAndFilterByActionAndActor() throws Exception {
        log(1, ALEX, "Alex", "PUNISH");
        log(2, MOD, "Mod", "FREEZE");
        log(3, ALEX, "Alex", "REMOVE");
        log(4, MOD, "Mod", "PUNISH");
        log(5, null, "Console", "RELOAD");

        List<ActionRepo.Logged> all = db.with(c -> repo.page(c, List.of(), null, 0, 10));
        assertEquals(List.of("RELOAD", "PUNISH", "REMOVE", "FREEZE", "PUNISH"),
                all.stream().map(l -> l.action().action()).toList());
        assertEquals(5, (int) db.with(c -> repo.count(c, List.of(), null)));

        List<ActionRepo.Logged> second = db.with(c -> repo.page(c, List.of(), null, 2, 2));
        assertEquals(List.of(3L, 2L), second.stream().map(l -> l.action().at()).toList());

        assertEquals(2, (int) db.with(c -> repo.count(c, List.of("PUNISH", "REFUSED"), null)));
        assertEquals(1, (int) db.with(c -> repo.count(c, List.of("PUNISH"), MOD)));
        List<ActionRepo.Logged> alex = db.with(c -> repo.page(c, List.of(), ALEX, 0, 10));
        assertEquals(List.of("REMOVE", "PUNISH"), alex.stream().map(l -> l.action().action()).toList());
        ActionRepo.Action reload = all.get(0).action();
        assertEquals(null, reload.actorUuid());
        assertEquals(STEVE, reload.targetUuid());
    }

    @Test
    void tailingReadsOnlyNewRowsInOrder() throws Exception {
        assertEquals(0L, (long) db.with(repo::maxId));
        log(1, ALEX, "Alex", "PUNISH");
        long start = db.with(repo::maxId);
        log(2, ALEX, "Alex", "FREEZE");
        log(3, ALEX, "Alex", "UNFREEZE");
        List<ActionRepo.Logged> after = db.with(c -> repo.after(c, start, 500));
        assertEquals(List.of("FREEZE", "UNFREEZE"), after.stream().map(l -> l.action().action()).toList());
        assertTrue(after.get(0).id() < after.get(1).id());
        assertEquals(1, db.with(c -> repo.after(c, start, 1)).size());
    }

    @Test
    void retentionDeletesOnlyOldLogRowsAndOnlyWhenSet() throws Exception {
        long now = TimeUnit.DAYS.toMillis(100);
        log(now - TimeUnit.DAYS.toMillis(40), ALEX, "Alex", "PUNISH");
        log(now - TimeUnit.DAYS.toMillis(10), ALEX, "Alex", "FREEZE");
        Retention retention = new Retention(db);
        Map<String, Integer> kept = db.with(c -> retention.run(c, now, 0, 0));
        assertFalse(kept.containsKey("staff_actions"));
        assertEquals(2, (int) db.with(c -> repo.count(c, List.of(), null)));
        Map<String, Integer> deleted = db.with(c -> retention.run(c, now, 0, 30));
        assertEquals(1, deleted.get("staff_actions"));
        assertEquals(List.of("FREEZE"), db.with(c -> repo.page(c, List.of(), null, 0, 10)).stream()
                .map(l -> l.action().action()).toList());
    }
}
