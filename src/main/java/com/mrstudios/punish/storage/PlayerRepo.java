package com.mrstudios.punish.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** The {@code players} table: every known player, with the rank weight cached at their last visit. */
public final class PlayerRepo {

    /**
     * One row of {@code players}.
     *
     * @param rankKey    the rank's name at the last refresh, or null for no rank
     * @param rankWeight 0 for no rank
     */
    public record PlayerRecord(UUID uuid, String name, long firstSeen, long lastSeen, String rankKey, int rankWeight,
            boolean exempt, boolean bedrock) {
    }

    /** What's known about a player when they join or quit (read on the main thread). */
    public record Visit(UUID uuid, String name, long at, String rankKey, int rankWeight, boolean exempt,
            boolean bedrock) {
    }

    private static final List<String> COLUMNS = List.of("uuid", "name", "name_lower", "first_seen", "last_seen",
            "rank_key", "rank_weight", "exempt", "bedrock");
    /** Everything but uuid and first_seen changes on a visit. */
    private static final List<String> UPDATED = List.of("name", "name_lower", "last_seen", "rank_key", "rank_weight",
            "exempt", "bedrock");

    private final Database database;

    public PlayerRepo(Database database) {
        this.database = database;
    }

    /** Records a join or quit: inserts a new player, or updates name, last seen and the cached rank. */
    public void upsert(Connection c, Visit visit) throws SQLException {
        String sql = database.dialect().upsert(database.table("players"), COLUMNS, List.of("uuid"), UPDATED);
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setString(1, visit.uuid().toString());
            statement.setString(2, visit.name());
            statement.setString(3, visit.name().toLowerCase(Locale.ROOT));
            statement.setLong(4, visit.at());
            statement.setLong(5, visit.at());
            statement.setString(6, visit.rankKey());
            statement.setInt(7, visit.rankWeight());
            statement.setInt(8, visit.exempt() ? 1 : 0);
            statement.setInt(9, visit.bedrock() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    public Optional<PlayerRecord> byUuid(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(select() + " WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            return first(statement);
        }
    }

    /** The player last seen with this name (ignoring case): names can move between accounts over time. */
    public Optional<PlayerRecord> byName(Connection c, String name) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(
                select() + " WHERE name_lower = ? ORDER BY last_seen DESC LIMIT 1")) {
            statement.setString(1, name.toLowerCase(Locale.ROOT));
            return first(statement);
        }
    }

    /** Every player's UUID, current name and last-seen time, for the name index. */
    public List<PlayerRecord> all(Connection c) throws SQLException {
        List<PlayerRecord> players = new ArrayList<>();
        try (PreparedStatement statement = c.prepareStatement(select());
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                players.add(read(rows));
            }
        }
        return players;
    }

    private String select() {
        return "SELECT uuid, name, first_seen, last_seen, rank_key, rank_weight, exempt, bedrock FROM "
                + database.table("players");
    }

    private static Optional<PlayerRecord> first(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            return rows.next() ? Optional.of(read(rows)) : Optional.empty();
        }
    }

    private static PlayerRecord read(ResultSet rows) throws SQLException {
        return new PlayerRecord(UUID.fromString(rows.getString(1)), rows.getString(2), rows.getLong(3),
                rows.getLong(4), rows.getString(5), rows.getInt(6), rows.getInt(7) != 0, rows.getInt(8) != 0);
    }
}
