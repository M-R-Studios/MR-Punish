package com.mrstudios.punish.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

/**
 * The {@code staff_actions} log (DESIGN.md 13.6) and the {@code deliveries} queue for players who
 * were offline. Details are short human-readable text and never contain an IP address.
 */
public final class ActionRepo {

    /** One staff action. @param actorUuid null for console */
    public record Action(long at, String server, UUID actorUuid, String actorName, String action, UUID targetUuid,
            String targetName, Long refId, String details) {
    }

    private final Database database;

    public ActionRepo(Database database) {
        this.database = database;
    }

    public void insert(Connection c, Action a) throws SQLException {
        String sql = "INSERT INTO " + database.table("staff_actions") + " (at, server, actor_uuid, actor_name, action,"
                + " target_uuid, target_name, ref_id, details) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, a.at());
            s.setString(2, a.server());
            s.setString(3, a.actorUuid() == null ? null : a.actorUuid().toString());
            s.setString(4, a.actorName());
            s.setString(5, a.action());
            s.setString(6, a.targetUuid() == null ? null : a.targetUuid().toString());
            s.setString(7, a.targetName());
            if (a.refId() == null) {
                s.setNull(8, Types.BIGINT);
            } else {
                s.setLong(8, a.refId());
            }
            s.setString(9, a.details() == null ? null : truncate(a.details(), 500));
            s.executeUpdate();
        }
    }

    /** Queues a message for a player who's offline, shown on their next join. */
    public void deliver(Connection c, UUID player, String kind, long refId, long at) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO " + database.table("deliveries")
                + " (uuid, kind, ref_id, created_at) VALUES (?, ?, ?, ?)")) {
            s.setString(1, player.toString());
            s.setString(2, kind);
            s.setLong(3, refId);
            s.setLong(4, at);
            s.executeUpdate();
        }
    }

    /** A message waiting for a player: its row id and what it refers to (a punishment id for WARN). */
    public record Delivery(long id, String kind, long refId) {
    }

    /** The player's undelivered messages of {@code kind}, oldest first. */
    public java.util.List<Delivery> pending(Connection c, UUID player, String kind) throws SQLException {
        java.util.List<Delivery> out = new java.util.ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT id, kind, ref_id FROM " + database.table("deliveries")
                + " WHERE uuid = ? AND kind = ? AND delivered_at IS NULL ORDER BY id")) {
            s.setString(1, player.toString());
            s.setString(2, kind);
            try (java.sql.ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    out.add(new Delivery(r.getLong(1), r.getString(2), r.getLong(3)));
                }
            }
        }
        return out;
    }

    /** Marks a delivery as shown. Only an undelivered row changes, so it's shown once. */
    public boolean markDelivered(Connection c, long id, long at) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE " + database.table("deliveries")
                + " SET delivered_at = ? WHERE id = ? AND delivered_at IS NULL")) {
            s.setLong(1, at);
            s.setLong(2, id);
            return s.executeUpdate() == 1;
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
