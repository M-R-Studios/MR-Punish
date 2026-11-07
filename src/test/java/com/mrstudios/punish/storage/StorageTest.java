package com.mrstudios.punish.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.config.Settings;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opening, degraded mode, recovery, transactions and shutdown, on real SQLite files. */
class StorageTest {

    @TempDir
    Path dir;
    private final List<LogRecord> logged = new ArrayList<>();
    private Logger log;
    private Storage storage;

    private static final Settings.Storage SQLITE = new Settings.Storage(Settings.StorageType.SQLITE, "punishments.db",
            new Settings.Mysql("localhost", 3306, "x", "x", "secret", "", 4, Map.of()));

    @BeforeEach
    void setUp() {
        log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.shutdown(5000);
        }
    }

    private Storage sqlite() {
        return new Storage(SQLITE, s -> new SqliteDatabase(dir.resolve(s.sqliteFile()).toFile()),
                Migrations.shipped(System::currentTimeMillis), log);
    }

    private static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static Throwable failure(CompletableFuture<?> future) {
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        return e.getCause();
    }

    private int count(String table) throws Exception {
        return get(storage.executor().submit("counting", c -> {
            try (Statement s = c.createStatement(); ResultSet rows = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rows.next();
                return rows.getInt(1);
            }
        }));
    }

    private static void insertPlayer(java.sql.Connection c, String uuid) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "INSERT INTO players (uuid, name, name_lower, first_seen, last_seen) VALUES (?, 'A', 'a', 1, 1)")) {
            s.setString(1, uuid);
            s.executeUpdate();
        }
    }

    @Test
    void startOpensAndMigrates() throws Exception {
        storage = sqlite();
        assertTrue(storage.start(5000));
        assertEquals(DatabaseHealth.State.UP, storage.health().state());
        assertEquals("SQLite (punishments.db)", storage.describeSettings());
        assertTrue(dir.resolve("punishments.db").toFile().isFile());
        assertEquals(0, count("players"));
    }

    @Test
    void aFailedTransactionLeavesNothingAndReportsAnErrorId() throws Exception {
        storage = sqlite();
        storage.start(5000);

        Throwable e = failure(storage.executor().transaction("issuing a test row", c -> {
            insertPlayer(c, "a");
            insertPlayer(c, "a"); // duplicate key: the whole transaction must roll back
            return null;
        }));

        StorageException se = assertInstanceOf(StorageException.class, e);
        assertTrue(se.errorId().matches("[0-9a-f]{8}"), se.errorId());
        assertEquals(0, count("players"));
        assertTrue(logged.stream().anyMatch(r -> r.getMessage().contains("Database error " + se.errorId())
                && r.getThrown() != null), "logged with the id and the stack trace");
        assertTrue(storage.health().isUp(), "a bad statement is not a lost connection");

        get(storage.executor().transaction("issuing", c -> {
            insertPlayer(c, "b");
            return null;
        }));
        assertEquals(1, count("players"));
    }

    @Test
    void cantOpenMeansDegradedModeThenRecovers() throws Exception {
        AtomicBoolean reachable = new AtomicBoolean(false);
        List<String> changes = new ArrayList<>();
        storage = new Storage(SQLITE, s -> {
            if (!reachable.get()) {
                throw new SQLTransientConnectionException("Connection refused", "08001");
            }
            return new SqliteDatabase(dir.resolve(s.sqliteFile()).toFile());
        }, Migrations.shipped(System::currentTimeMillis), log);
        storage.health().onChange((old, now) -> changes.add(old + "->" + now));

        assertFalse(storage.start(5000));
        assertEquals(DatabaseHealth.State.STARTING, storage.health().state());
        assertTrue(storage.health().reason().contains("Connection refused"));
        StorageException refused = assertInstanceOf(StorageException.class,
                failure(storage.executor().submit("writing", c -> null)));
        assertNull(refused.errorId(), "refused at once, nothing ran");

        storage.check(); // still unreachable
        get(storage.executor().maintenance("wait", () -> null));
        assertEquals(DatabaseHealth.State.STARTING, storage.health().state());

        reachable.set(true);
        storage.check();
        get(storage.executor().maintenance("wait", () -> null));
        assertTrue(storage.health().isUp());
        assertEquals(List.of("STARTING->UP"), changes);
        assertEquals(0, count("players"));
    }

    @Test
    void aNewerSchemaIsNeverUsedOrRetried() throws Exception {
        storage = sqlite();
        storage.start(5000);
        get(storage.executor().submit("bump", c -> {
            try (Statement s = c.createStatement()) {
                s.execute("UPDATE meta SET v = '9' WHERE k = 'schema_version'");
            }
            return null;
        }));
        storage.shutdown(5000);

        AtomicInteger opens = new AtomicInteger();
        storage = new Storage(SQLITE, s -> {
            opens.incrementAndGet();
            return new SqliteDatabase(dir.resolve(s.sqliteFile()).toFile());
        }, Migrations.shipped(System::currentTimeMillis), log);
        assertFalse(storage.start(5000));
        assertEquals(DatabaseHealth.State.INCOMPATIBLE, storage.health().state());
        assertTrue(logged.stream().anyMatch(r -> r.getMessage().contains("created by a newer MR-Punish")));
        storage.check();
        get(storage.executor().maintenance("wait", () -> null));
        assertEquals(1, opens.get(), "not retried");
    }

    @Test
    void aLostConnectionGoesDownAndComesBackOnPing() throws Exception {
        storage = sqlite();
        storage.start(5000);
        List<String> changes = new ArrayList<>();
        storage.health().onChange((old, now) -> changes.add(old + "->" + now));

        Throwable e = failure(storage.executor().submit("reading", c -> {
            throw new SQLException("Communications link failure", "08S01");
        }));
        assertInstanceOf(StorageException.class, e);
        assertEquals(DatabaseHealth.State.DOWN, storage.health().state());
        assertNull(((StorageException) failure(storage.executor().submit("writing", c -> null))).errorId());

        storage.check();
        get(storage.executor().maintenance("wait", () -> null));
        assertTrue(storage.health().isUp());
        assertEquals(List.of("UP->DOWN", "DOWN->UP"), changes);
    }

    @Test
    void shutdownLetsSubmittedWorkFinishAndRefusesNewWork() throws Exception {
        storage = sqlite();
        storage.start(5000);
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String uuid = "u" + i;
            writes.add(storage.executor().transaction("slow write", c -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                insertPlayer(c, uuid);
                return null;
            }));
        }

        assertTrue(storage.shutdown(5000), "drained in time");
        for (CompletableFuture<Void> write : writes) {
            assertTrue(write.isDone() && !write.isCompletedExceptionally());
        }
        StorageException late = assertInstanceOf(StorageException.class,
                failure(storage.executor().submit("too late", c -> null)));
        assertTrue(late.getMessage().contains("stopping"));

        try (SqliteDatabase reopened = new SqliteDatabase(dir.resolve("punishments.db").toFile())) {
            int rows = reopened.with(c -> {
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM players")) {
                    r.next();
                    return r.getInt(1);
                }
            });
            assertEquals(5, rows, "every submitted write was committed");
        }
        storage = null;
    }

    @Test
    void connectionLossClassification() {
        assertTrue(DatabaseHealth.isConnectionLoss(new SQLException("x", "08001")));
        assertTrue(DatabaseHealth.isConnectionLoss(new SQLTransientConnectionException("pool timeout")));
        assertTrue(DatabaseHealth.isConnectionLoss(new RuntimeException(new SQLException("x", "08S01"))));
        assertFalse(DatabaseHealth.isConnectionLoss(new SQLException("syntax error", "42000")));
        assertFalse(DatabaseHealth.isConnectionLoss(new SQLException("constraint", null, 19)));
        assertTrue(DatabaseHealth.isConnectionLoss(new org.sqlite.SQLiteException("disk I/O error",
                org.sqlite.SQLiteErrorCode.SQLITE_IOERR)));
        assertTrue(DatabaseHealth.isConnectionLoss(new org.sqlite.SQLiteException("unable to open",
                org.sqlite.SQLiteErrorCode.SQLITE_CANTOPEN)));
        assertFalse(DatabaseHealth.isConnectionLoss(new org.sqlite.SQLiteException("constraint",
                org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT)));
    }

    @Test
    void descriptionsNeverShowThePassword() {
        Settings.Storage mysql = new Settings.Storage(Settings.StorageType.MYSQL, "x.db",
                new Settings.Mysql("db.example", 3306, "punish", "user", "hunter2", "mr_", 4, Map.of()));
        storage = new Storage(mysql, s -> {
            throw new SQLException("nope", "08001");
        }, Migrations.shipped(System::currentTimeMillis), log);
        assertEquals("MySQL (db.example:3306/punish)", storage.describeSettings());
        assertFalse(mysql.toString().contains("hunter2"));
    }
}
