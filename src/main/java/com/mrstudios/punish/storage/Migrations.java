package com.mrstudios.punish.storage;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Brings the database up to the schema version this MR-Punish knows (DESIGN.md 9.2). The version is
 * in {@code meta.schema_version}; no meta table means version 0. A newer version than this plugin
 * knows is refused, so an older plugin never writes into a layout it doesn't understand.
 */
public final class Migrations {

    /** The schema version this build of MR-Punish creates and reads. */
    public static final int CURRENT = 1;

    /** Upgrades the schema from {@code version - 1} to {@code version}. */
    public interface Step {

        int version();

        void apply(Connection connection, Database database) throws SQLException;
    }

    /** The database was made by a newer MR-Punish. Not a connection problem: retrying won't help. */
    public static final class SchemaTooNewException extends SQLException {

        public SchemaTooNewException(int found, int known) {
            super("The database has schema version " + found + ", so it was created by a newer MR-Punish (this one"
                    + " knows up to " + known + "). Nothing was changed. Update MR-Punish, or point it at another database.");
        }
    }

    private final List<Step> steps;
    private final LongSupplier clock;

    public Migrations(List<Step> steps, LongSupplier clock) {
        List<Step> sorted = new ArrayList<>(steps);
        sorted.sort(Comparator.comparingInt(Step::version));
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).version() != i + 1) {
                throw new IllegalArgumentException("Schema migrations must go 1, 2, 3... without gaps");
            }
        }
        this.steps = List.copyOf(sorted);
        this.clock = clock;
    }

    /** The migrations this build ships: version 1 creates every table. */
    public static Migrations shipped(LongSupplier clock) {
        return new Migrations(List.of(new Step() {
            @Override
            public int version() {
                return 1;
            }

            @Override
            public void apply(Connection connection, Database database) throws SQLException {
                try (Statement statement = connection.createStatement()) {
                    for (String sql : Schema.ddl(Schema.v1(), database.dialect(), database.prefix())) {
                        statement.execute(sql);
                    }
                }
            }
        }), clock);
    }

    List<Step> steps() {
        return steps;
    }

    public int latest() {
        return steps.isEmpty() ? 0 : steps.get(steps.size() - 1).version();
    }

    /**
     * Applies every step newer than the database's version, each in its own transaction.
     *
     * @return the version the database had before
     * @throws SchemaTooNewException if the database is newer than {@link #latest()}
     */
    public int migrate(Database database, Consumer<String> log) throws SQLException {
        int before = database.with(c -> version(c, database));
        if (before > latest()) {
            throw new SchemaTooNewException(before, latest());
        }
        for (Step step : steps) {
            if (step.version() <= before) {
                continue;
            }
            if (before >= 1 && step.version() == before + 1) {
                // Existing tables are about to change.
                log.accept(database.backup(before));
            }
            database.with(c -> {
                boolean auto = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    step.apply(c, database);
                    writeMeta(c, database, "schema_version", String.valueOf(step.version()), true);
                    writeMeta(c, database, "created_at", String.valueOf(clock.getAsLong()), false);
                    c.commit();
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(auto);
                }
                return null;
            });
            log.accept((before == 0 && step.version() == 1 ? "Created the database tables" : "Upgraded the database")
                    + " (schema version " + step.version() + ") in " + database.describe());
        }
        return before;
    }

    /** {@code meta.schema_version}, or 0 if there's no meta table or no version in it. */
    static int version(Connection connection, Database database) throws SQLException {
        if (!tableExists(connection, database.prefix() + "meta")) {
            return 0;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT v FROM " + database.table("meta") + " WHERE k = ?")) {
            statement.setString(1, "schema_version");
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return 0;
                }
                try {
                    return Integer.parseInt(rows.getString(1).strip());
                } catch (NumberFormatException e) {
                    throw new SQLException("meta.schema_version is '" + rows.getString(1) + "', not a number");
                }
            }
        }
    }

    private static boolean tableExists(Connection connection, String name) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        for (String candidate : List.of(name, name.toUpperCase(java.util.Locale.ROOT))) {
            try (ResultSet tables = meta.getTables(connection.getCatalog(), null, candidate, new String[] {"TABLE"})) {
                if (tables.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void writeMeta(Connection connection, Database database, String key, String value, boolean replace)
            throws SQLException {
        String sql = replace
                ? database.dialect().upsert(database.table("meta"), List.of("k", "v"), List.of("k"), List.of("v"))
                : database.dialect().insertIgnore() + " " + database.table("meta") + " (k, v) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }
}
