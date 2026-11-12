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

    /** A logged action with its row id. */
    public record Logged(long id, Action action) {
    }

    private static final String COLUMNS = "id, at, server, actor_uuid, actor_name, action, target_uuid, target_name,"
            + " ref_id, details";

    /** @param actions only these actions (empty = all); @param actor only this actor (null = all) */
    private String where(java.util.List<String> actions, UUID actor, java.util.List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (!actions.isEmpty()) {
            where.append(" AND action IN (").append(String.join(", ", java.util.Collections.nCopies(actions.size(), "?")))
                    .append(")");
            params.addAll(actions);
        }
        if (actor != null) {
            where.append(" AND actor_uuid = ?");
            params.add(actor.toString());
        }
        return where.toString();
    }

    private static void bind(PreparedStatement s, java.util.List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object v = params.get(i);
            if (v instanceof Long l) {
                s.setLong(i + 1, l);
            } else if (v instanceof Integer n) {
                s.setInt(i + 1, n);
            } else {
                s.setString(i + 1, (String) v);
            }
        }
    }

    /** One page of the log, newest first. */
    public java.util.List<Logged> page(Connection c, java.util.List<String> actions, UUID actor, int offset, int limit)
            throws SQLException {
        java.util.List<Object> params = new java.util.ArrayList<>();
        String sql = "SELECT " + COLUMNS + " FROM " + database.table("staff_actions") + where(actions, actor, params)
                + " ORDER BY id DESC LIMIT ? OFFSET ?";
        params.add(limit);
        params.add(offset);
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, params);
            return list(s);
        }
    }

    public int count(Connection c, java.util.List<String> actions, UUID actor) throws SQLException {
        java.util.List<Object> params = new java.util.ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM " + database.table("staff_actions")
                + where(actions, actor, params))) {
            bind(s, params);
            try (java.sql.ResultSet r = s.executeQuery()) {
                return r.next() ? r.getInt(1) : 0;
            }
        }
    }

    /** Rows after {@code afterId}, oldest first (the file log tails the table). */
    public java.util.List<Logged> after(Connection c, long afterId, int limit) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS + " FROM " + database.table("staff_actions")
                + " WHERE id > ? ORDER BY id LIMIT ?")) {
            s.setLong(1, afterId);
            s.setInt(2, limit);
            return list(s);
        }
    }

    public long maxId(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT MAX(id) FROM " + database.table("staff_actions"));
                java.sql.ResultSet r = s.executeQuery()) {
            return r.next() ? r.getLong(1) : 0;
        }
    }

    /** Retention ({@code logs.retention-days}). */
    public int deleteOlderThan(Connection c, long before) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("DELETE FROM " + database.table("staff_actions")
                + " WHERE at < ?")) {
            s.setLong(1, before);
            return s.executeUpdate();
        }
    }

    private static java.util.List<Logged> list(PreparedStatement s) throws SQLException {
        java.util.List<Logged> out = new java.util.ArrayList<>();
        try (java.sql.ResultSet r = s.executeQuery()) {
            while (r.next()) {
                String actor = r.getString("actor_uuid");
                String target = r.getString("target_uuid");
                long ref = r.getLong("ref_id");
                Long refId = r.wasNull() ? null : ref;
                out.add(new Logged(r.getLong("id"), new Action(r.getLong("at"), r.getString("server"),
                        actor == null ? null : UUID.fromString(actor), r.getString("actor_name"), r.getString("action"),
                        target == null ? null : UUID.fromString(target), r.getString("target_name"), refId,
                        r.getString("details"))));
            }
        }
        return out;
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
