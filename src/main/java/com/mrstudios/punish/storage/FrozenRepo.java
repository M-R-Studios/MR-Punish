package com.mrstudios.punish.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code frozen} table (DESIGN.md 9.1, 11.3): one row per frozen player. The row is what makes a
 * freeze survive logout, restart and a server switch, and what {@code /unfreeze} deletes. Only an
 * explicit freeze inserts one.
 */
public final class FrozenRepo {

    /** @param byUuid null when console froze them */
    public record Frozen(UUID uuid, String name, UUID byUuid, String byName, int byWeight, long at, String server) {
    }

    private final Database database;

    public FrozenRepo(Database database) {
        this.database = database;
    }

    private String table() {
        return database.table("frozen");
    }

    /** Inserts the row; false if the player is already frozen. */
    public boolean insert(Connection c, Frozen f) throws SQLException {
        if (get(c, f.uuid()).isPresent()) {
            return false;
        }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO " + table()
                + " (uuid, name, frozen_by_uuid, frozen_by_name, frozen_by_weight, frozen_at, server)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            s.setString(1, f.uuid().toString());
            s.setString(2, f.name());
            s.setString(3, f.byUuid() == null ? null : f.byUuid().toString());
            s.setString(4, f.byName());
            s.setInt(5, f.byWeight());
            s.setLong(6, f.at());
            s.setString(7, f.server());
            s.executeUpdate();
            return true;
        }
    }

    public Optional<Frozen> get(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT uuid, name, frozen_by_uuid, frozen_by_name,"
                + " frozen_by_weight, frozen_at, server FROM " + table() + " WHERE uuid = ?")) {
            s.setString(1, uuid.toString());
            List<Frozen> found = list(s);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        }
    }

    /** Deletes the row; false if there was none. */
    public boolean delete(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("DELETE FROM " + table() + " WHERE uuid = ?")) {
            s.setString(1, uuid.toString());
            return s.executeUpdate() == 1;
        }
    }

    /** Every frozen player, oldest freeze first. */
    public List<Frozen> all(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT uuid, name, frozen_by_uuid, frozen_by_name,"
                + " frozen_by_weight, frozen_at, server FROM " + table() + " ORDER BY frozen_at")) {
            return list(s);
        }
    }

    private static List<Frozen> list(PreparedStatement s) throws SQLException {
        List<Frozen> out = new ArrayList<>();
        try (ResultSet r = s.executeQuery()) {
            while (r.next()) {
                String by = r.getString(3);
                out.add(new Frozen(UUID.fromString(r.getString(1)), r.getString(2), by == null ? null : UUID.fromString(by),
                        r.getString(4), r.getInt(5), r.getLong(6), r.getString(7)));
            }
        }
        return out;
    }
}
