package com.mrstudios.punish.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The daily clean-up (DESIGN.md 9.5). Punishments, reports, report notes and players are never
 * deleted. The settings-driven parts (IP links, staff log, log files) are added with the features
 * that write them.
 */
public final class Retention {

    /** Network sync events are only needed for a few seconds; a day is plenty. */
    static final long SYNC_EVENTS_MS = TimeUnit.DAYS.toMillis(1);
    /** Offline messages that were already delivered. */
    static final long DELIVERED_MS = TimeUnit.DAYS.toMillis(30);

    private final Database database;

    public Retention(Database database) {
        this.database = database;
    }

    /** Deletes what's past its time and returns how many rows went from each table. */
    public Map<String, Integer> run(Connection c, long now) throws SQLException {
        Map<String, Integer> deleted = new LinkedHashMap<>();
        deleted.put("sync_events", delete(c, "DELETE FROM " + database.table("sync_events") + " WHERE at < ?",
                now - SYNC_EVENTS_MS));
        deleted.put("deliveries", delete(c, "DELETE FROM " + database.table("deliveries")
                + " WHERE delivered_at IS NOT NULL AND delivered_at < ?", now - DELIVERED_MS));
        return deleted;
    }

    private static int delete(Connection c, String sql, long before) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setLong(1, before);
            return statement.executeUpdate();
        }
    }
}
