package com.mrstudios.punish.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * The database layout, written once and turned into SQLite or MySQL DDL by {@link #ddl}. Every
 * statement is idempotent ({@code IF NOT EXISTS}): MySQL commits each DDL statement on its own, so
 * a migration interrupted halfway must be safe to run again.
 */
public final class Schema {

    /** Column kinds, with their SQLite and MySQL types. */
    public enum Kind {
        /** Auto-increment primary key. */
        ID("INTEGER PRIMARY KEY AUTOINCREMENT", "BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY"),
        UUID("TEXT", "CHAR(36)"),
        HASH("TEXT", "CHAR(64)"),
        /** Text with a maximum length (VARCHAR on MySQL, so it can be indexed). */
        TEXT("TEXT", "VARCHAR(%d)"),
        LONGTEXT("TEXT", "TEXT"),
        LONG("INTEGER", "BIGINT"),
        INT("INTEGER", "INT"),
        FLAG("INTEGER", "TINYINT");

        private final String sqlite;
        private final String mysql;

        Kind(String sqlite, String mysql) {
            this.sqlite = sqlite;
            this.mysql = mysql;
        }

        String type(Dialect dialect, int length) {
            return dialect == Dialect.SQLITE ? sqlite : String.format(mysql, length);
        }
    }

    /**
     * @param references the table this column points at (its {@code id}), or null
     */
    public record Column(String name, Kind kind, int length, boolean notNull, String defaultValue, boolean unique,
            String references) {
    }

    public record Index(String name, List<String> columns) {
    }

    /** @param primaryKey the key columns, when the table has no {@link Kind#ID} column */
    public record Table(String name, List<Column> columns, List<String> primaryKey, List<Index> indexes) {
    }

    private Schema() {
    }

    // ---- Small builders, so the layout below reads like the design's SQL. ----

    private static Column col(String name, Kind kind) {
        return new Column(name, kind, 0, true, null, false, null);
    }

    private static Column col(String name, Kind kind, int length) {
        return new Column(name, kind, length, true, null, false, null);
    }

    private static Column opt(String name, Kind kind) {
        return new Column(name, kind, 0, false, null, false, null);
    }

    private static Column opt(String name, Kind kind, int length) {
        return new Column(name, kind, length, false, null, false, null);
    }

    private static Column flag(String name) {
        return new Column(name, Kind.FLAG, 0, true, "0", false, null);
    }

    private static Column ref(String name, String table) {
        return new Column(name, Kind.LONG, 0, false, null, false, table);
    }

    private static Index idx(String name, String... columns) {
        return new Index(name, List.of(columns));
    }

    private static Table table(String name, List<Column> columns, List<String> key, Index... indexes) {
        return new Table(name, columns, key, List.of(indexes));
    }

    /** Schema version 1 (DESIGN.md 9.1). Table and index names are without the prefix. */
    public static List<Table> v1() {
        List<Table> tables = new ArrayList<>();
        tables.add(table("meta", List.of(col("k", Kind.TEXT, 64), col("v", Kind.LONGTEXT)), List.of("k")));
        tables.add(table("players", List.of(
                col("uuid", Kind.UUID), col("name", Kind.TEXT, 32), col("name_lower", Kind.TEXT, 32),
                col("first_seen", Kind.LONG), col("last_seen", Kind.LONG), opt("rank_key", Kind.TEXT, 32),
                new Column("rank_weight", Kind.INT, 0, true, "0", false, null), flag("exempt"), flag("bedrock")),
                List.of("uuid"),
                idx("players_name", "name_lower", "last_seen")));
        tables.add(table("player_ips", List.of(
                col("uuid", Kind.UUID), col("ip_hash", Kind.HASH), col("first_seen", Kind.LONG),
                col("last_seen", Kind.LONG)),
                List.of("uuid", "ip_hash"),
                idx("player_ips_hash", "ip_hash", "last_seen")));
        tables.add(table("ip_locks", List.of(col("ip_hash", Kind.HASH)), List.of("ip_hash")));
        tables.add(table("punishments", List.of(
                col("id", Kind.ID), col("type", Kind.TEXT, 8), col("target_uuid", Kind.UUID),
                col("target_name", Kind.TEXT, 32), opt("ip_hash", Kind.HASH), col("reason_key", Kind.TEXT, 64),
                col("reason_display", Kind.TEXT, 255), opt("note", Kind.TEXT, 500), col("offence", Kind.INT),
                col("step", Kind.INT), opt("duration_ms", Kind.LONG), col("issued_at", Kind.LONG),
                opt("expires_at", Kind.LONG), opt("issuer_uuid", Kind.UUID), col("issuer_name", Kind.TEXT, 32),
                col("issuer_weight", Kind.INT), col("target_weight", Kind.INT),
                col("target_weight_source", Kind.TEXT, 16), col("server", Kind.TEXT, 32), flag("silent"),
                col("origin", Kind.TEXT, 16), col("status", Kind.TEXT, 16), opt("removed_by_uuid", Kind.UUID),
                opt("removed_by_name", Kind.TEXT, 32), opt("removed_at", Kind.LONG),
                opt("removed_reason", Kind.TEXT, 500), ref("replaced_by_id", "punishments"),
                opt("replaced_at", Kind.LONG),
                new Column("import_key", Kind.TEXT, 128, false, null, true, null)),
                List.of(),
                idx("pun_target", "target_uuid", "type", "issued_at"),
                idx("pun_ip", "ip_hash", "type", "status"),
                idx("pun_active", "status", "type", "expires_at"),
                idx("pun_issuer", "issuer_uuid", "issued_at")));
        tables.add(table("deliveries", List.of(
                col("id", Kind.ID), col("uuid", Kind.UUID), col("kind", Kind.TEXT, 32), col("ref_id", Kind.LONG),
                col("created_at", Kind.LONG), opt("delivered_at", Kind.LONG)),
                List.of(),
                idx("deliveries_uuid", "uuid", "delivered_at")));
        tables.add(table("reports", List.of(
                col("id", Kind.ID), col("reporter_uuid", Kind.UUID), col("reporter_name", Kind.TEXT, 32),
                col("target_uuid", Kind.UUID), col("target_name", Kind.TEXT, 32), opt("reason_key", Kind.TEXT, 64),
                col("reason_text", Kind.TEXT, 500), col("server", Kind.TEXT, 32), opt("world", Kind.TEXT, 64),
                opt("x", Kind.INT), opt("y", Kind.INT), opt("z", Kind.INT), col("created_at", Kind.LONG),
                col("status", Kind.TEXT, 16), opt("claimed_by_uuid", Kind.UUID), opt("claimed_by_name", Kind.TEXT, 32),
                opt("claimed_at", Kind.LONG), opt("closed_by_uuid", Kind.UUID), opt("closed_by_name", Kind.TEXT, 32),
                opt("closed_at", Kind.LONG), opt("decision_note", Kind.TEXT, 500),
                new Column("version", Kind.INT, 0, true, "0", false, null)),
                List.of(),
                idx("reports_status", "status", "created_at"),
                idx("reports_reporter", "reporter_uuid", "status"),
                idx("reports_target", "target_uuid", "created_at")));
        tables.add(table("report_notes", List.of(
                col("id", Kind.ID), new Column("report_id", Kind.LONG, 0, true, null, false, "reports"),
                opt("author_uuid", Kind.UUID), col("author_name", Kind.TEXT, 32), col("text", Kind.TEXT, 500),
                col("created_at", Kind.LONG)),
                List.of(),
                idx("report_notes_report", "report_id")));
        tables.add(table("staff_actions", List.of(
                col("id", Kind.ID), col("at", Kind.LONG), col("server", Kind.TEXT, 32), opt("actor_uuid", Kind.UUID),
                col("actor_name", Kind.TEXT, 32), col("action", Kind.TEXT, 32), opt("target_uuid", Kind.UUID),
                opt("target_name", Kind.TEXT, 32), opt("ref_id", Kind.LONG), opt("details", Kind.TEXT, 500)),
                List.of(),
                idx("actions_at", "at"),
                idx("actions_actor", "actor_uuid", "at"),
                idx("actions_target", "target_uuid", "at")));
        tables.add(table("frozen", List.of(
                col("uuid", Kind.UUID), col("name", Kind.TEXT, 32), opt("frozen_by_uuid", Kind.UUID),
                col("frozen_by_name", Kind.TEXT, 32), col("frozen_by_weight", Kind.INT), col("frozen_at", Kind.LONG),
                col("server", Kind.TEXT, 32)),
                List.of("uuid")));
        tables.add(table("sync_events", List.of(
                col("id", Kind.ID), col("at", Kind.LONG), col("server", Kind.TEXT, 32), col("kind", Kind.TEXT, 16),
                opt("target_uuid", Kind.UUID), opt("ip_hash", Kind.HASH), opt("ref_id", Kind.LONG)),
                List.of(),
                idx("sync_at", "at")));
        return List.copyOf(tables);
    }

    /** The statements that create {@code tables} with {@code prefix} in front of every table and index name. */
    public static List<String> ddl(List<Table> tables, Dialect dialect, String prefix) {
        List<String> statements = new ArrayList<>();
        for (Table table : tables) {
            List<String> parts = new ArrayList<>();
            List<String> foreignKeys = new ArrayList<>();
            for (Column column : table.columns()) {
                StringBuilder sql = new StringBuilder(column.name()).append(' ')
                        .append(column.kind().type(dialect, column.length()));
                if (column.kind() != Kind.ID) {
                    if (column.notNull()) {
                        sql.append(" NOT NULL");
                    }
                    if (column.defaultValue() != null) {
                        sql.append(" DEFAULT ").append(column.defaultValue());
                    }
                    if (column.unique()) {
                        sql.append(" UNIQUE");
                    }
                }
                if (column.references() != null) {
                    String target = dialect.quote(prefix + column.references());
                    if (dialect == Dialect.SQLITE) {
                        sql.append(" REFERENCES ").append(target).append("(id)");
                    } else {
                        foreignKeys.add("FOREIGN KEY (" + column.name() + ") REFERENCES " + target + "(id)");
                    }
                }
                parts.add(sql.toString());
            }
            if (!table.primaryKey().isEmpty()) {
                parts.add("PRIMARY KEY (" + String.join(", ", table.primaryKey()) + ")");
            }
            parts.addAll(foreignKeys);
            if (dialect == Dialect.MYSQL) {
                // MySQL has no CREATE INDEX IF NOT EXISTS, so the indexes go into the table itself.
                for (Index index : table.indexes()) {
                    parts.add("INDEX " + dialect.quote(prefix + index.name()) + " ("
                            + String.join(", ", index.columns()) + ")");
                }
            }
            String create = "CREATE TABLE IF NOT EXISTS " + dialect.quote(prefix + table.name()) + " ("
                    + String.join(", ", parts) + ")";
            if (dialect == Dialect.MYSQL) {
                create += " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
            }
            statements.add(create);
            if (dialect == Dialect.SQLITE) {
                for (Index index : table.indexes()) {
                    statements.add("CREATE INDEX IF NOT EXISTS " + dialect.quote(prefix + index.name()) + " ON "
                            + dialect.quote(prefix + table.name()) + " (" + String.join(", ", index.columns()) + ")");
                }
            }
        }
        return statements;
    }
}
