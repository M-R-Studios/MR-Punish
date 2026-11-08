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
 * Links between accounts and IP hashes ({@code player_ips}, DESIGN.md 8.1, 8.3), and the IP-secret
 * fingerprint in {@code meta}. Only hashes are ever stored here; this class never sees an address.
 */
public final class IpRepo {

    /** An account seen on a hash, with its cached rank. */
    public record Seen(UUID uuid, String name, int weight, boolean exempt, long lastSeen) {
    }

    private static final String FINGERPRINT = "ip_fingerprint";

    private final Database database;

    public IpRepo(Database database) {
        this.database = database;
    }

    /** Records that {@code uuid} joined from {@code hash} at {@code at}. */
    public void seen(Connection c, UUID uuid, String hash, long at) throws SQLException {
        String sql = database.dialect().upsert(database.table("player_ips"),
                List.of("uuid", "ip_hash", "first_seen", "last_seen"), List.of("uuid", "ip_hash"), List.of("last_seen"));
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, uuid.toString());
            s.setString(2, hash);
            s.setLong(3, at);
            s.setLong(4, at);
            s.executeUpdate();
        }
    }

    /** The hash the account was seen with most recently (DESIGN.md 8.3: "the punished IP"). */
    public Optional<String> latestHash(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT ip_hash FROM " + database.table("player_ips")
                + " WHERE uuid = ? ORDER BY last_seen DESC LIMIT 1")) {
            s.setString(1, uuid.toString());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
            }
        }
    }

    /** Every hash the account was seen with since {@code since} (for /unbanip, /unmuteip). */
    public List<String> hashesOf(Connection c, UUID uuid, long since) throws SQLException {
        List<String> hashes = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT ip_hash FROM " + database.table("player_ips")
                + " WHERE uuid = ? AND last_seen >= ? ORDER BY last_seen DESC")) {
            s.setString(1, uuid.toString());
            s.setLong(2, since);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    hashes.add(r.getString(1));
                }
            }
        }
        return hashes;
    }

    /** The accounts seen on {@code hash} since {@code since}, with their cached rank (the "associated" ones). */
    public List<Seen> associated(Connection c, String hash, long since) throws SQLException {
        List<Seen> seen = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT i.uuid, COALESCE(p.name, i.uuid), COALESCE(p.rank_weight, 0),"
                + " COALESCE(p.exempt, 0), i.last_seen FROM " + database.table("player_ips") + " i LEFT JOIN "
                + database.table("players") + " p ON p.uuid = i.uuid WHERE i.ip_hash = ? AND i.last_seen >= ?"
                + " ORDER BY i.last_seen DESC")) {
            s.setString(1, hash);
            s.setLong(2, since);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    seen.add(new Seen(UUID.fromString(r.getString(1)), r.getString(2), r.getInt(3), r.getInt(4) != 0,
                            r.getLong(5)));
                }
            }
        }
        return seen;
    }

    /** Deletes every link (IP features turned off). Returns how many. */
    public int purge(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("DELETE FROM " + database.table("player_ips"))) {
            return s.executeUpdate();
        }
    }

    /** Deletes links not used since {@code before} (DESIGN.md 8.1 retention). Returns how many. */
    public int deleteOlderThan(Connection c, long before) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("DELETE FROM " + database.table("player_ips")
                + " WHERE last_seen < ?")) {
            s.setLong(1, before);
            return s.executeUpdate();
        }
    }

    /** The fingerprint of the IP secret this database was used with, if any. */
    public Optional<String> fingerprint(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT v FROM " + database.table("meta") + " WHERE k = ?")) {
            s.setString(1, FINGERPRINT);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
            }
        }
    }

    /** Records the fingerprint the first time IP features are used with this database. */
    public void setFingerprint(Connection c, String fingerprint) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(database.dialect().insertIgnore() + " " + database.table("meta")
                + " (k, v) VALUES (?, ?)")) {
            s.setString(1, FINGERPRINT);
            s.setString(2, fingerprint);
            s.executeUpdate();
        }
    }
}
