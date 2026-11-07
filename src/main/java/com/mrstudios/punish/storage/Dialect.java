package com.mrstudios.punish.storage;

import java.util.List;
import java.util.stream.Collectors;

/** The SQL that differs between SQLite and MySQL/MariaDB (DESIGN.md 9.1). */
public enum Dialect {
    SQLITE,
    MYSQL;

    /** A quoted identifier. Only ever called with names built from the validated table prefix. */
    public String quote(String identifier) {
        return this == SQLITE ? '"' + identifier + '"' : '`' + identifier + '`';
    }

    /** {@code INSERT OR IGNORE INTO} / {@code INSERT IGNORE INTO}. */
    public String insertIgnore() {
        return this == SQLITE ? "INSERT OR IGNORE INTO" : "INSERT IGNORE INTO";
    }

    /** Appended to a SELECT that locks the rows it reads (MySQL only; SQLite has one writer anyway). */
    public String forUpdate() {
        return this == SQLITE ? "" : " FOR UPDATE";
    }

    /**
     * An insert that updates {@code update} when a row with the same {@code key} exists.
     *
     * @param table  the quoted table name
     */
    public String upsert(String table, List<String> columns, List<String> key, List<String> update) {
        String values = columns.stream().map(c -> "?").collect(Collectors.joining(", "));
        String insert = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES (" + values + ")";
        if (this == SQLITE) {
            return insert + " ON CONFLICT(" + String.join(", ", key) + ") DO UPDATE SET "
                    + update.stream().map(c -> c + " = excluded." + c).collect(Collectors.joining(", "));
        }
        return insert + " ON DUPLICATE KEY UPDATE "
                + update.stream().map(c -> c + " = VALUES(" + c + ")").collect(Collectors.joining(", "));
    }
}
