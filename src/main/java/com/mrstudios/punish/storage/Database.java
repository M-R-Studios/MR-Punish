package com.mrstudios.punish.storage;

import java.sql.SQLException;

/**
 * Where MR-Punish's data lives: SQLite or MySQL. Every method blocks, so it's only called on the
 * {@code MR-Punish-DB} threads (through {@link DbExecutor}).
 */
public interface Database extends AutoCloseable {

    Dialect dialect();

    /** The quoted name of table {@code name}, with the prefix. */
    String table(String name);

    /** The table-name prefix (empty for SQLite). */
    String prefix();

    /** Runs {@code work} with a connection. The connection must not be closed by {@code work}. */
    <T> T with(SqlWork<T> work) throws SQLException;

    /**
     * Copies the database to a backup before a migration changes existing tables. SQLite writes
     * {@code <file>.v<version>.bak}; MySQL can't, so it returns an instruction to log instead.
     *
     * @return a log line saying what was done
     */
    String backup(int version) throws SQLException;

    /** For logs and {@code /mrpunish version}, e.g. {@code SQLite (punishments.db)}. Never contains a password. */
    String describe();

    @Override
    void close();
}
