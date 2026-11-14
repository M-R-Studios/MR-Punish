package com.mrstudios.punish.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.punish.NameIndex;
import com.mrstudios.punish.punish.TargetResolver;
import com.mrstudios.punish.punish.TargetResolver.Outcome;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The players table, retention, and resolving names through the database. */
class PlayerRepoTest {

    @TempDir
    Path dir;
    private Database db;
    private PlayerRepo repo;

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @BeforeEach
    void setUp() throws Exception {
        db = TestDatabases.open(dir);
        Migrations.shipped(() -> 0L).migrate(db, line -> { });
        repo = new PlayerRepo(db);
    }

    @AfterEach
    void tearDown() {
        TestDatabases.dispose(db);
    }

    private void visit(UUID uuid, String name, long at, String rank, int weight, boolean exempt) throws SQLException {
        db.with(c -> {
            repo.upsert(c, new PlayerRepo.Visit(uuid, name, at, rank, weight, exempt, false));
            return null;
        });
    }

    @Test
    void firstVisitInsertsLaterVisitsUpdateButKeepFirstSeen() throws Exception {
        visit(A, "Steve", 100, null, 0, false);
        PlayerRepo.PlayerRecord first = db.with(c -> repo.byUuid(c, A)).orElseThrow();
        assertEquals(100, first.firstSeen());
        assertNull(first.rankKey());

        visit(A, "Steve2", 500, "mod", 20, true);
        PlayerRepo.PlayerRecord later = db.with(c -> repo.byUuid(c, A)).orElseThrow();
        assertEquals(100, later.firstSeen());
        assertEquals(500, later.lastSeen());
        assertEquals("Steve2", later.name());
        assertEquals("mod", later.rankKey());
        assertEquals(20, later.rankWeight());
        assertTrue(later.exempt());
        assertEquals(1, db.with(repo::all).size());
    }

    @Test
    void aNameBelongsToWhoeverWasSeenWithItLast() throws Exception {
        visit(A, "Alex", 100, null, 0, false);
        visit(B, "alex", 200, null, 0, false); // offline mode: another account took the name later
        assertEquals(B, db.with(c -> repo.byName(c, "ALEX")).orElseThrow().uuid());
        visit(A, "Alex", 300, null, 0, false);
        assertEquals(A, db.with(c -> repo.byName(c, "alex")).orElseThrow().uuid());
        assertTrue(db.with(c -> repo.byName(c, "nobody")).isEmpty());
    }

    @Test
    void namesAreBoundParametersNotSql() throws Exception {
        visit(A, "x'); DROP TABLE players; --", 1, null, 0, false);
        assertEquals(1, db.with(repo::all).size());
        assertTrue(db.with(c -> repo.byName(c, "' OR '1'='1")).isEmpty());
    }

    @Test
    void retentionDeletesOnlyOldSyncEventsAndOldDeliveredMessages() throws Exception {
        long now = 100L * 24 * 60 * 60 * 1000;
        long day = 24L * 60 * 60 * 1000;
        db.with(c -> {
            try (PreparedStatement s = c.prepareStatement(TestDatabases.sql(db, "INSERT INTO sync_events (at, server, kind) VALUES (?, 's', 'PUNISH')"))) {
                s.setLong(1, now - 2 * day);
                s.executeUpdate();
                s.setLong(1, now - 60_000);
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement(
                    TestDatabases.sql(db, "INSERT INTO deliveries (uuid, kind, ref_id, created_at, delivered_at) VALUES ('u', 'WARN', 1, 0, ?)"))) {
                s.setLong(1, now - 40 * day);
                s.executeUpdate();
                s.setLong(1, now - 5 * day);
                s.executeUpdate();
                s.setObject(1, null);
                s.executeUpdate(); // never delivered: kept however old
            }
            return null;
        });
        visit(A, "Old", 1, null, 0, false);

        Map<String, Integer> deleted = db.with(c -> new Retention(db).run(c, now));
        assertEquals(Map.of("sync_events", 1, "deliveries", 1), deleted);
        assertEquals(1, db.with(repo::all).size(), "players are never deleted");
        assertEquals(Map.of("sync_events", 0, "deliveries", 0), db.with(c -> new Retention(db).run(c, now)));
    }

    @Test
    void resolverFallsBackToTheDatabaseAndRemembersTheAnswer() throws Exception {
        visit(A, "OfflineSteve", 100, null, 0, false);
        NameIndex index = new NameIndex();
        int[] queries = {0};
        TargetResolver resolver = new TargetResolver(index, new TargetResolver.Lookups() {
            @Override
            public Optional<TargetResolver.Target> onlineByName(String name) {
                return name.equalsIgnoreCase("OnlineAlex")
                        ? Optional.of(new TargetResolver.Target(B, "OnlineAlex", true)) : Optional.empty();
            }

            @Override
            public Optional<TargetResolver.Target> onlineByUuid(UUID uuid) {
                return uuid.equals(B) ? Optional.of(new TargetResolver.Target(B, "OnlineAlex", true)) : Optional.empty();
            }

            @Override
            public CompletableFuture<Optional<NameIndex.Entry>> storedByName(String name) {
                queries[0]++;
                try {
                    return CompletableFuture.completedFuture(db.with(c -> repo.byName(c, name))
                            .map(p -> new NameIndex.Entry(p.uuid(), p.name(), p.lastSeen())));
                } catch (SQLException e) {
                    return CompletableFuture.failedFuture(e);
                }
            }

            @Override
            public CompletableFuture<Optional<NameIndex.Entry>> storedByUuid(UUID uuid) {
                queries[0]++;
                try {
                    return CompletableFuture.completedFuture(db.with(c -> repo.byUuid(c, uuid))
                            .map(p -> new NameIndex.Entry(p.uuid(), p.name(), p.lastSeen())));
                } catch (SQLException e) {
                    return CompletableFuture.failedFuture(e);
                }
            }
        });

        var steve = resolver.resolve("offlinesteve").get(5, TimeUnit.SECONDS);
        assertEquals(Outcome.FOUND, steve.outcome());
        assertEquals(A, steve.target().uuid());
        assertEquals("OfflineSteve", steve.target().name());
        assertFalse(steve.target().online());
        assertEquals(1, queries[0]);
        resolver.resolve("OfflineSteve").get(5, TimeUnit.SECONDS);
        assertEquals(1, queries[0], "the second lookup is answered by the name index");

        var online = resolver.resolve("onlinealex").get(5, TimeUnit.SECONDS);
        assertTrue(online.target().online());
        assertEquals(Outcome.FOUND, resolver.resolve("0000000000000000000000000000000a").get().outcome());
        assertEquals(Outcome.FOUND, resolver.resolve(B.toString()).get().outcome());

        assertEquals(Outcome.UNKNOWN, resolver.resolve("NeverJoined").get().outcome());
        assertEquals(Outcome.UNKNOWN, resolver.resolve("00000000-0000-0000-0000-0000000000ff").get().outcome());
        for (String bad : new String[] {"", "a b", "<red>x", "name;drop", "abcdefghijabcdefghijabcdefghijabc"}) {
            assertEquals(Outcome.INVALID, resolver.resolve(bad).get().outcome(), bad);
        }
        assertEquals(Outcome.UNKNOWN, resolver.resolve(".BedrockSteve").get().outcome(), "Floodgate prefix allowed");
    }
}
