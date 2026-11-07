package com.mrstudios.punish.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * The database in a file in the plugin folder: one connection used by one thread, so SQLite never
 * sees two writers (DESIGN.md 9.2). If the connection breaks it's opened again on next use.
 */
public final class SqliteDatabase implements Database {

    private final File file;
    private Connection connection;

    /** Opens (and creates, if needed) {@code file}. */
    public SqliteDatabase(File file) throws SQLException {
        this.file = file;
        try {
            // Paper ships the driver; load it by name in case the service lookup can't see it.
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("The SQLite driver isn't available on this server", e);
        }
        File folder = file.getAbsoluteFile().getParentFile();
        if (folder != null && !folder.isDirectory() && !folder.mkdirs()) {
            throw new SQLException("Couldn't create the folder " + folder);
        }
        connection = open();
    }

    private Connection open() throws SQLException {
        Properties properties = new Properties();
        // BEGIN IMMEDIATE: a transaction takes the write lock when it starts (DESIGN.md 9.3).
        properties.setProperty("transaction_mode", "IMMEDIATE");
        Connection opened = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath(), properties);
        try (Statement statement = opened.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA synchronous=NORMAL");
        } catch (SQLException e) {
            opened.close();
            throw e;
        }
        return opened;
    }

    @Override
    public Dialect dialect() {
        return Dialect.SQLITE;
    }

    @Override
    public String table(String name) {
        return Dialect.SQLITE.quote(name);
    }

    @Override
    public String prefix() {
        return "";
    }

    @Override
    public synchronized <T> T with(SqlWork<T> work) throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = open();
        }
        return work.run(connection);
    }

    @Override
    public String backup(int version) throws SQLException {
        File backup = new File(file.getAbsoluteFile().getParentFile(), file.getName() + ".v" + version + ".bak");
        for (int i = 1; backup.exists(); i++) {
            backup = new File(file.getAbsoluteFile().getParentFile(), file.getName() + ".v" + version + ".bak." + i);
        }
        String path = backup.getAbsolutePath();
        with(c -> {
            // A consistent copy even with the write-ahead log in use.
            try (PreparedStatement statement = c.prepareStatement("VACUUM INTO ?")) {
                statement.setString(1, path);
                statement.execute();
            }
            return null;
        });
        return "Backed up " + file.getName() + " to " + backup.getName() + " before upgrading it";
    }

    @Override
    public String describe() {
        return "SQLite (" + file.getName() + ")";
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Shutting down; nothing more to do with it.
            }
            connection = null;
        }
    }
}
