package com.mrstudios.punish.storage;

import com.mrstudios.punish.config.Settings;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Opens the database, keeps track of whether it can be used, and reconnects (DESIGN.md 2.4, 9.3).
 * If it can't be opened at startup the plugin runs in degraded mode and {@link #check()} keeps
 * trying every 30 seconds; a database lost while running is pinged until it answers.
 */
public final class Storage {

    /** Opens the configured database. */
    @FunctionalInterface
    public interface Opener {

        Database open(Settings.Storage settings) throws SQLException;
    }

    private final Settings.Storage settings;
    private final Opener opener;
    private final Migrations migrations;
    private final Logger log;
    private final DatabaseHealth health = new DatabaseHealth();
    private final DbExecutor executor;
    private volatile Database database;
    /** Failed open attempts so far; only touched on the database thread. */
    private int failedOpens;

    public Storage(Settings.Storage settings, Opener opener, Migrations migrations, Logger log) {
        this.settings = settings;
        this.opener = opener;
        this.migrations = migrations;
        this.log = log;
        int threads = settings.type() == Settings.StorageType.SQLITE ? 1 : settings.mysql().poolSize();
        this.executor = new DbExecutor(threads, () -> database, health, log);
    }

    /**
     * Opens and migrates the database, waiting up to {@code timeoutMs}. The one blocking wait in the
     * plugin, at startup before anyone can join (DESIGN.md 2.4 step 3).
     *
     * @return whether storage is up
     */
    public boolean start(long timeoutMs) {
        try {
            return open().get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            String why = "opening the database took longer than " + timeoutMs / 1000 + " seconds";
            health.markNotOpened(why, false);
            log.severe("Storage unavailable: " + why + ". MR-Punish keeps trying every 30 seconds.");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            return false;
        }
    }

    /** One open attempt on a database thread; completes with whether storage is up afterwards. */
    private CompletableFuture<Boolean> open() {
        return executor.maintenance("opening the database", () -> {
            if (database != null) {
                return health.isUp();
            }
            Database opened = null;
            try {
                opened = opener.open(settings);
                migrations.migrate(opened, log::info);
                database = opened;
                health.markUp();
                log.info("Storage: " + opened.describe() + ", schema version " + Migrations.CURRENT + ".");
                return true;
            } catch (Migrations.SchemaTooNewException e) {
                close(opened);
                health.markNotOpened("the database was created by a newer MR-Punish", true);
                log.severe("Storage unavailable: " + e.getMessage());
                return false;
            } catch (SQLException | RuntimeException e) {
                close(opened);
                health.markNotOpened(e.getMessage(), false);
                if (failedOpens++ == 0) {
                    log.log(Level.SEVERE, "Storage unavailable: couldn't open " + describeSettings() + ": "
                            + e.getMessage() + ". MR-Punish keeps trying every 30 seconds.", e);
                } else {
                    // The stack trace was logged the first time; one line per retry is enough.
                    log.warning("Storage still unavailable (attempt " + failedOpens + "): " + e.getMessage());
                }
                return false;
            }
        });
    }

    private static void close(Database db) {
        if (db != null) {
            db.close();
        }
    }

    /** Every 30 seconds: retries a database that never opened, pings one that went down. */
    public void check() {
        switch (health.state()) {
            case STARTING -> open();
            case DOWN -> executor.maintenance("checking the database connection", () -> {
                try {
                    database.with(c -> {
                        try (Statement statement = c.createStatement()) {
                            statement.execute("SELECT 1");
                        }
                        return null;
                    });
                    health.markUp();
                    log.info("Storage: the database answers again (" + database.describe() + ").");
                } catch (SQLException e) {
                    health.markDown(e.getMessage());
                }
                return null;
            });
            default -> {
                // UP: nothing to do. INCOMPATIBLE: retrying can't help.
            }
        }
    }

    public DbExecutor executor() {
        return executor;
    }

    public DatabaseHealth health() {
        return health;
    }

    /** The open database, or null while it isn't open. Only for the database threads. */
    public Database database() {
        return database;
    }

    public Settings.Storage settings() {
        return settings;
    }

    /** e.g. {@code SQLite (punishments.db)}, without passwords. */
    public String describeSettings() {
        Database db = database;
        if (db != null) {
            return db.describe();
        }
        return settings.type() == Settings.StorageType.SQLITE
                ? "SQLite (" + settings.sqliteFile() + ")"
                : "MySQL (" + settings.mysql().host() + ":" + settings.mysql().port() + "/" + settings.mysql().database()
                        + ")";
    }

    /**
     * Lets submitted work finish (up to {@code timeoutMs}) and closes the database.
     *
     * @return true if all submitted work finished in time
     */
    public boolean shutdown(long timeoutMs) {
        boolean drained = executor.shutdown(timeoutMs);
        if (!drained) {
            log.warning("Database work was still running after " + timeoutMs / 1000 + " seconds at shutdown;"
                    + " anything not yet committed was not saved.");
        }
        close(database);
        database = null;
        return drained;
    }
}
