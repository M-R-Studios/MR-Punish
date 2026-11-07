package com.mrstudios.punish.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationsTest {

    @TempDir
    Path dir;
    private final List<String> log = new ArrayList<>();
    private SqliteDatabase db;

    @AfterEach
    void close() {
        if (db != null) {
            db.close();
        }
    }

    private SqliteDatabase open() throws SQLException {
        db = new SqliteDatabase(dir.resolve("punishments.db").toFile());
        return db;
    }

    private static Set<String> tables(Connection c) throws SQLException {
        Set<String> names = new TreeSet<>();
        try (Statement s = c.createStatement();
                ResultSet rows = s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
            while (rows.next()) {
                names.add(rows.getString(1));
            }
        }
        names.remove("sqlite_sequence");
        return names;
    }

    private static String meta(Connection c, String key) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rows = s.executeQuery("SELECT v FROM meta WHERE k = '" + key + "'")) {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static void exec(Database db, String sql) throws SQLException {
        db.with(c -> {
            try (Statement s = c.createStatement()) {
                s.execute(sql);
            }
            return null;
        });
    }

    @Test
    void migratesAnEmptyDatabaseFromZeroToVersionOne() throws Exception {
        SqliteDatabase db = open();
        int before = Migrations.shipped(() -> 1234L).migrate(db, log::add);

        assertEquals(0, before);
        assertEquals(Set.of("deliveries", "frozen", "ip_locks", "meta", "player_ips", "players", "punishments",
                "report_notes", "reports", "staff_actions", "sync_events"), db.with(MigrationsTest::tables));
        assertEquals("1", db.with(c -> meta(c, "schema_version")));
        assertEquals("1234", db.with(c -> meta(c, "created_at")));
        assertEquals(List.of("Created the database tables (schema version 1) in SQLite (punishments.db)"), log);
    }

    @Test
    void runningAgainChangesNothing() throws Exception {
        SqliteDatabase db = open();
        Migrations.shipped(() -> 1L).migrate(db, log::add);
        log.clear();
        assertEquals(1, Migrations.shipped(() -> 2L).migrate(db, log::add));
        assertEquals(List.of(), log);
        assertEquals("1", db.with(c -> meta(c, "created_at")), "created_at keeps the first value");
    }

    @Test
    void refusesANewerSchemaAndLeavesItAlone() throws Exception {
        SqliteDatabase db = open();
        Migrations.shipped(() -> 1L).migrate(db, log::add);
        exec(db, "UPDATE meta SET v = '2' WHERE k = 'schema_version'");
        exec(db, "CREATE TABLE future_table (x INTEGER)");

        Migrations.SchemaTooNewException e = assertThrows(Migrations.SchemaTooNewException.class,
                () -> Migrations.shipped(() -> 1L).migrate(db, log::add));
        assertTrue(e.getMessage().contains("created by a newer MR-Punish"), e.getMessage());
        assertEquals("2", db.with(c -> meta(c, "schema_version")));
        assertTrue(db.with(MigrationsTest::tables).contains("future_table"));
    }

    @Test
    void aFailingStepRollsBackAndKeepsTheOldVersion() throws Exception {
        SqliteDatabase db = open();
        Migrations.shipped(() -> 1L).migrate(db, log::add);
        Migrations broken = new Migrations(List.of(Migrations.shipped(() -> 1L).steps().get(0),
                step(2, c -> {
                    try (Statement s = c.createStatement()) {
                        s.execute("CREATE TABLE half_done (x INTEGER)");
                        s.execute("THIS IS NOT SQL");
                    }
                })), () -> 1L);

        assertThrows(SQLException.class, () -> broken.migrate(db, log::add));
        assertEquals("1", db.with(c -> meta(c, "schema_version")));
        assertFalse(db.with(MigrationsTest::tables).contains("half_done"), "the step's changes were rolled back");
    }

    @Test
    void upgradingExistingTablesBacksUpTheFileFirst() throws Exception {
        SqliteDatabase db = open();
        Migrations.shipped(() -> 1L).migrate(db, log::add);
        exec(db, "INSERT INTO players (uuid, name, name_lower, first_seen, last_seen) VALUES ('u', 'A', 'a', 1, 1)");
        Migrations v2 = new Migrations(List.of(Migrations.shipped(() -> 1L).steps().get(0),
                step(2, c -> {
                    try (Statement s = c.createStatement()) {
                        s.execute("ALTER TABLE players ADD COLUMN locale TEXT");
                    }
                })), () -> 1L);
        log.clear();

        assertEquals(1, v2.migrate(db, log::add));
        File backup = dir.resolve("punishments.db.v1.bak").toFile();
        assertTrue(backup.isFile(), "backup written");
        assertTrue(log.get(0).startsWith("Backed up punishments.db to punishments.db.v1.bak"), log.toString());
        assertEquals("2", db.with(c -> meta(c, "schema_version")));
        try (SqliteDatabase copy = new SqliteDatabase(backup)) {
            assertEquals("1", copy.with(c -> meta(c, "schema_version")));
            assertEquals(Set.of("deliveries", "frozen", "ip_locks", "meta", "player_ips", "players", "punishments",
                    "report_notes", "reports", "staff_actions", "sync_events"), copy.with(MigrationsTest::tables));
        }
    }

    @Test
    void gapsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Migrations(List.of(step(2, c -> { })), () -> 1L));
    }

    @Test
    void aDeletedFileIsRecreated() throws Exception {
        SqliteDatabase db = open();
        Migrations.shipped(() -> 1L).migrate(db, log::add);
        db.close();
        Files.delete(dir.resolve("punishments.db"));
        Files.deleteIfExists(dir.resolve("punishments.db-wal"));
        Files.deleteIfExists(dir.resolve("punishments.db-shm"));

        SqliteDatabase again = open();
        assertEquals(0, Migrations.shipped(() -> 1L).migrate(again, log::add));
        assertEquals("1", again.with(c -> meta(c, "schema_version")));
    }

    interface StepBody {
        void run(Connection c) throws SQLException;
    }

    private static Migrations.Step step(int version, StepBody body) {
        return new Migrations.Step() {
            @Override
            public int version() {
                return version;
            }

            @Override
            public void apply(Connection connection, Database database) throws SQLException {
                body.run(connection);
            }
        };
    }
}
