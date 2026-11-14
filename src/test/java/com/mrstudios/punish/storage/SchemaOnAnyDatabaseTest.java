package com.mrstudios.punish.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Migrations on whichever database the tests run on (DESIGN.md 9.2): with {@code MRPUNISH_TEST_MYSQL}
 * set this is the MySQL/MariaDB check (M3 style): create, run again, refuse a newer schema.
 */
class SchemaOnAnyDatabaseTest {

    @TempDir
    Path dir;
    private Database db;
    private final List<String> log = new ArrayList<>();

    @AfterEach
    void tearDown() {
        TestDatabases.dispose(db);
    }

    private String schemaVersion() throws Exception {
        return db.with(c -> {
            try (Statement s = c.createStatement();
                    ResultSet r = s.executeQuery(TestDatabases.sql(db, "SELECT v FROM meta WHERE k = 'schema_version'"))) {
                return r.next() ? r.getString(1) : null;
            }
        });
    }

    @Test
    void createsEveryTableThenLeavesThemAloneThenRefusesANewerSchema() throws Exception {
        db = TestDatabases.open(dir);
        assertEquals(0, Migrations.shipped(() -> 1L).migrate(db, log::add));
        assertEquals(List.of("deliveries", "frozen", "ip_locks", "meta", "player_ips", "players", "punishments",
                "report_notes", "reports", "staff_actions", "sync_events"), TestDatabases.tables(db));
        assertEquals("1", schemaVersion());
        assertTrue(log.get(0).startsWith("Created the database tables (schema version 1) in "
                + (db.dialect() == Dialect.MYSQL ? "MySQL" : "SQLite")), log.toString());

        log.clear();
        assertEquals(1, Migrations.shipped(() -> 2L).migrate(db, log::add));
        assertEquals(List.of(), log);

        db.with(c -> {
            try (Statement s = c.createStatement()) {
                s.execute(TestDatabases.sql(db, "UPDATE meta SET v = '2' WHERE k = 'schema_version'"));
            }
            return null;
        });
        assertThrows(Migrations.SchemaTooNewException.class, () -> Migrations.shipped(() -> 1L).migrate(db, log::add));
        assertEquals("2", schemaVersion(), "left alone");
    }
}
