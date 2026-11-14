package com.mrstudios.punish.storage;

import com.mrstudios.punish.config.Settings;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The database the storage and service tests run on: SQLite in a temp folder, or MySQL/MariaDB when
 * {@code MRPUNISH_TEST_MYSQL=host:port/database} is set (with {@code MRPUNISH_TEST_MYSQL_USER} and
 * {@code MRPUNISH_TEST_MYSQL_PASSWORD}). On MySQL each test gets its own random table prefix, and its
 * tables are dropped afterwards. Nothing is skipped either way: the same tests run on whichever it is.
 */
public final class TestDatabases {

    private TestDatabases() {
    }

    /** The MySQL settings from the environment, or null for SQLite. */
    public static Settings.Mysql mysql(String prefix) {
        String target = System.getenv("MRPUNISH_TEST_MYSQL");
        if (target == null || target.isBlank()) {
            return null;
        }
        String hostPort = target.substring(0, target.indexOf('/'));
        String database = target.substring(target.indexOf('/') + 1);
        String host = hostPort.contains(":") ? hostPort.substring(0, hostPort.indexOf(':')) : hostPort;
        int port = hostPort.contains(":") ? Integer.parseInt(hostPort.substring(hostPort.indexOf(':') + 1)) : 3306;
        return new Settings.Mysql(host, port, database, System.getenv("MRPUNISH_TEST_MYSQL_USER"),
                System.getenv("MRPUNISH_TEST_MYSQL_PASSWORD"), prefix, 4, Map.of());
    }

    public static boolean onMysql() {
        return mysql("") != null;
    }

    /** A random prefix such as {@code t1a2b3c4d_}: fresh tables for one test. */
    public static String freshPrefix() {
        return String.format(Locale.ROOT, "t%08x_", ThreadLocalRandom.current().nextInt());
    }

    /** A new, empty database (not migrated). */
    public static Database open(Path dir) throws SQLException {
        Settings.Mysql mysql = mysql(freshPrefix());
        return mysql == null ? new SqliteDatabase(dir.resolve("punishments.db").toFile()) : new MysqlDatabase(mysql);
    }

    /** Drops a MySQL test's tables, then closes. SQLite: just closes (the temp folder goes). */
    public static void dispose(Database db) {
        if (db == null) {
            return;
        }
        if (db.dialect() == Dialect.MYSQL && !db.prefix().isEmpty()) {
            try {
                db.with(c -> {
                    List<String> tables = new ArrayList<>();
                    try (Statement s = c.createStatement();
                            ResultSet r = s.executeQuery("SELECT table_name FROM information_schema.tables"
                                    + " WHERE table_schema = DATABASE() AND table_name LIKE '"
                                    + db.prefix().replace("_", "\\_") + "%'")) {
                        while (r.next()) {
                            tables.add(r.getString(1));
                        }
                    }
                    try (Statement s = c.createStatement()) {
                        // Child tables reference their parents; the order doesn't matter this way.
                        s.execute("SET FOREIGN_KEY_CHECKS = 0");
                        for (String t : tables) {
                            s.execute("DROP TABLE IF EXISTS `" + t + "`");
                        }
                    }
                    return null;
                });
            } catch (SQLException e) {
                // Leftover test tables in the test database; harmless.
            }
        }
        db.close();
    }

    private static final java.util.regex.Pattern TABLES = java.util.regex.Pattern.compile(
            "\\b(meta|players|player_ips|ip_locks|punishments|deliveries|reports|report_notes|staff_actions|frozen"
                    + "|sync_events)\\b");

    /** {@code sql} with the shipped table names replaced by {@code db}'s (its prefix, quoted). */
    public static String sql(Database db, String sql) {
        java.util.regex.Matcher m = TABLES.matcher(sql);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(db.table(m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The tables in {@code db} (without the prefix), for either dialect. */
    public static List<String> tables(Database db) throws SQLException {
        return db.with(c -> {
            List<String> names = new ArrayList<>();
            String sql = db.dialect() == Dialect.SQLITE
                    ? "SELECT name FROM sqlite_master WHERE type = 'table' AND name <> 'sqlite_sequence'"
                    : "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name"
                            + " LIKE '" + db.prefix().replace("_", "\\_") + "%'";
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
                while (r.next()) {
                    names.add(r.getString(1).substring(db.prefix().length()));
                }
            }
            names.sort(null);
            return names;
        });
    }
}
