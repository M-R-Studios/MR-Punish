package com.mrstudios.punish.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The {@code sync_events} table (DESIGN.md 9.4): one row per change other servers sharing a MySQL
 * database must apply (kick a newly banned player, refresh a mute, freeze or unfreeze, show a
 * report). Rows are written in the same transaction as the change, so an event exists only for a
 * committed change. An event only says what to look at; the poller re-reads the referenced row.
 * On SQLite (one server) nothing is written.
 */
public final class SyncRepo {

    public static final String PUNISH = "PUNISH";
    public static final String REMOVE = "REMOVE";
    public static final String FREEZE = "FREEZE";
    public static final String UNFREEZE = "UNFREEZE";
    public static final String REPORT = "REPORT";

    /**
     * This run of the plugin: two servers with the same {@code server-name} (the default is
     * "default") still tell their events apart.
     */
    private static final String RUN_ID = String.format(Locale.ROOT, "%08x", ThreadLocalRandom.current().nextInt());

    public record Event(long id, long at, String origin, String kind, UUID targetUuid, String ipHash, Long refId) {
    }

    private final Database database;
    private final boolean enabled;

    public SyncRepo(Database database) {
        this(database, database.dialect() == Dialect.MYSQL);
    }

    /** @param enabled whether events are written (tests write them on SQLite too) */
    public SyncRepo(Database database, boolean enabled) {
        this.database = database;
        this.enabled = enabled;
    }

    /** {@code server-name#run}, at most 32 characters: who wrote an event. */
    public static String origin(String serverName) {
        String name = serverName.length() > 23 ? serverName.substring(0, 23) : serverName;
        return name + "#" + RUN_ID;
    }

    /** Whether this run of the plugin wrote an event with {@code origin}. */
    public static boolean fromHere(String origin) {
        return origin != null && origin.endsWith("#" + RUN_ID);
    }

    public boolean enabled() {
        return enabled;
    }

    private String table() {
        return database.table("sync_events");
    }

    /** Records an event from this server (inside the caller's transaction). Nothing on SQLite. */
    public void insert(Connection c, long at, String serverName, String kind, UUID target, String ipHash, Long refId)
            throws SQLException {
        insertAs(c, at, origin(serverName), kind, target, ipHash, refId);
    }

    /** As {@link #insert}, with the origin given (tests: an event from "another server"). */
    public void insertAs(Connection c, long at, String origin, String kind, UUID target, String ipHash, Long refId)
            throws SQLException {
        if (!enabled) {
            return;
        }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO " + table()
                + " (at, server, kind, target_uuid, ip_hash, ref_id) VALUES (?, ?, ?, ?, ?, ?)")) {
            s.setLong(1, at);
            s.setString(2, origin);
            s.setString(3, kind);
            s.setString(4, target == null ? null : target.toString());
            s.setString(5, ipHash);
            if (refId == null) {
                s.setNull(6, java.sql.Types.BIGINT);
            } else {
                s.setLong(6, refId);
            }
            s.executeUpdate();
        }
    }

    /** Events after {@code afterId}, oldest first. */
    public List<Event> after(Connection c, long afterId, int limit) throws SQLException {
        List<Event> out = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT id, at, server, kind, target_uuid, ip_hash, ref_id FROM "
                + table() + " WHERE id > ? ORDER BY id LIMIT ?")) {
            s.setLong(1, afterId);
            s.setInt(2, limit);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String target = r.getString("target_uuid");
                    long ref = r.getLong("ref_id");
                    Long refId = r.wasNull() ? null : ref;
                    out.add(new Event(r.getLong("id"), r.getLong("at"), r.getString("server"), r.getString("kind"),
                            target == null ? null : UUID.fromString(target), r.getString("ip_hash"), refId));
                }
            }
        }
        return out;
    }

    /** The newest event's id (0 if none): the poller starts after it, so old events aren't replayed. */
    public long maxId(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT MAX(id) FROM " + table());
                ResultSet r = s.executeQuery()) {
            return r.next() ? r.getLong(1) : 0;
        }
    }
}
