package com.mrstudios.punish.storage;

import com.mrstudios.punish.punish.EscalationCalculator;
import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.Punishment.Origin;
import com.mrstudios.punish.punish.Punishment.StoredStatus;
import com.mrstudios.punish.punish.Punishment.WeightSource;
import com.mrstudios.punish.punish.PunishmentType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The {@code punishments} table (DESIGN.md 9.1). Rows are never deleted: removal and replacement
 * update the status and record who, when and why. Every value is a bound parameter.
 */
public final class PunishmentRepo {

    private static final String COLUMNS = "id, type, target_uuid, target_name, ip_hash, reason_key, reason_display, note,"
            + " offence, step, duration_ms, issued_at, expires_at, issuer_uuid, issuer_name, issuer_weight,"
            + " target_weight, target_weight_source, server, silent, origin, status, removed_by_uuid, removed_by_name,"
            + " removed_at, removed_reason, replaced_by_id, replaced_at";

    private final Database database;

    public PunishmentRepo(Database database) {
        this.database = database;
    }

    private String table() {
        return database.table("punishments");
    }

    /**
     * Whose punishments a query is about: an account (WARN, MUTE, BAN) or an IP hash (IP types),
     * so alts share one IP ladder and one IP slot (DESIGN.md 7.3, 8.3).
     */
    public record Subject(UUID uuid, String ipHash) {

        public static Subject of(PunishmentType type, UUID uuid, String ipHash) {
            return type.isIp() ? new Subject(null, ipHash) : new Subject(uuid, null);
        }

        String column() {
            return ipHash != null ? "ip_hash" : "target_uuid";
        }

        String value() {
            return ipHash != null ? ipHash : uuid.toString();
        }
    }

    /** Inserts {@code p} (its id is ignored) and returns the new id. */
    public long insert(Connection c, Punishment p) throws SQLException {
        String sql = "INSERT INTO " + table() + " (" + COLUMNS.substring(4) + ") VALUES ("
                + "?, ".repeat(26) + "?)";
        try (PreparedStatement s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            s.setString(i++, p.type().name());
            s.setString(i++, p.targetUuid().toString());
            s.setString(i++, p.targetName());
            s.setString(i++, p.ipHash());
            s.setString(i++, p.reasonKey());
            s.setString(i++, p.reasonDisplay());
            s.setString(i++, p.note());
            s.setInt(i++, p.offence());
            s.setInt(i++, p.step());
            setLong(s, i++, p.durationMs());
            s.setLong(i++, p.issuedAt());
            setLong(s, i++, p.expiresAt());
            s.setString(i++, p.issuerUuid() == null ? null : p.issuerUuid().toString());
            s.setString(i++, p.issuerName());
            s.setInt(i++, p.issuerWeight());
            s.setInt(i++, p.targetWeight());
            s.setString(i++, p.targetWeightSource().name());
            s.setString(i++, p.server());
            s.setInt(i++, p.silent() ? 1 : 0);
            s.setString(i++, p.origin().name());
            s.setString(i++, p.status().name());
            s.setString(i++, p.removedByUuid() == null ? null : p.removedByUuid().toString());
            s.setString(i++, p.removedByName());
            setLong(s, i++, p.removedAt());
            s.setString(i++, p.removedReason());
            setLong(s, i++, p.replacedById());
            setLong(s, i, p.replacedAt());
            s.executeUpdate();
            try (ResultSet keys = s.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No id was returned for the new punishment");
                }
                return keys.getLong(1);
            }
        }
    }

    /** The subject's earlier punishments of {@code type} and {@code reasonKey}, any status, for escalation. */
    public List<EscalationCalculator.Prior> priors(Connection c, PunishmentType type, String reasonKey, Subject subject)
            throws SQLException {
        String sql = "SELECT id, issued_at, status FROM " + table() + " WHERE " + subject.column()
                + " = ? AND type = ? AND reason_key = ?";
        List<EscalationCalculator.Prior> priors = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, subject.value());
            s.setString(2, type.name());
            s.setString(3, reasonKey);
            try (ResultSet rows = s.executeQuery()) {
                while (rows.next()) {
                    priors.add(new EscalationCalculator.Prior(rows.getLong(1), type, reasonKey, rows.getLong(2),
                            StoredStatus.valueOf(rows.getString(3))));
                }
            }
        }
        return priors;
    }

    /** The subject's punishments of {@code type} that are active at {@code now}, oldest first. */
    public List<Punishment> active(Connection c, PunishmentType type, Subject subject, long now) throws SQLException {
        String sql = "SELECT " + COLUMNS + " FROM " + table() + " WHERE " + subject.column()
                + " = ? AND type = ? AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > ?) ORDER BY id";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, subject.value());
            s.setString(2, type.name());
            s.setLong(3, now);
            return list(s);
        }
    }

    public Optional<Punishment> byId(Connection c, long id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS + " FROM " + table() + " WHERE id = ?")) {
            s.setLong(1, id);
            List<Punishment> found = list(s);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        }
    }

    /** Every punishment of the account (as target), newest first. */
    public List<Punishment> ofTarget(Connection c, UUID target) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "SELECT " + COLUMNS + " FROM " + table() + " WHERE target_uuid = ? ORDER BY id DESC")) {
            s.setString(1, target.toString());
            return list(s);
        }
    }

    /** Marks {@code ids} REPLACED by {@code replacement}. Only rows still ACTIVE change. */
    public int markReplaced(Connection c, Collection<Long> ids, long replacement, long at) throws SQLException {
        if (ids.isEmpty()) {
            return 0;
        }
        String sql = "UPDATE " + table() + " SET status = 'REPLACED', replaced_by_id = ?, replaced_at = ? WHERE status ="
                + " 'ACTIVE' AND id IN (" + placeholders(ids.size()) + ")";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, replacement);
            s.setLong(2, at);
            int i = 3;
            for (long id : ids) {
                s.setLong(i++, id);
            }
            return s.executeUpdate();
        }
    }

    /** Marks {@code ids} REMOVED with who, when and why. Only rows still ACTIVE change. */
    public int markRemoved(Connection c, Collection<Long> ids, UUID byUuid, String byName, long at, String reason)
            throws SQLException {
        String sql = "UPDATE " + table() + " SET status = 'REMOVED', removed_by_uuid = ?, removed_by_name = ?,"
                + " removed_at = ?, removed_reason = ? WHERE status = 'ACTIVE' AND id IN (" + placeholders(ids.size()) + ")";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, byUuid == null ? null : byUuid.toString());
            s.setString(2, byName);
            s.setLong(3, at);
            s.setString(4, reason);
            int i = 5;
            for (long id : ids) {
                s.setLong(i++, id);
            }
            return s.executeUpdate();
        }
    }

    /**
     * Serialises transactions on this subject across servers (DESIGN.md 9.3): MySQL locks the
     * player's row (and for IP types an {@code ip_locks} row); SQLite already has a single writer.
     */
    public void lock(Connection c, UUID target, String ipHash) throws SQLException {
        if (database.dialect() != Dialect.MYSQL) {
            return;
        }
        try (PreparedStatement s = c.prepareStatement("SELECT uuid FROM " + database.table("players")
                + " WHERE uuid = ?" + database.dialect().forUpdate())) {
            s.setString(1, target.toString());
            s.executeQuery().close();
        }
        if (ipHash != null) {
            try (PreparedStatement s = c.prepareStatement(database.dialect().insertIgnore() + " "
                    + database.table("ip_locks") + " (ip_hash) VALUES (?)")) {
                s.setString(1, ipHash);
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("SELECT ip_hash FROM " + database.table("ip_locks")
                    + " WHERE ip_hash = ?" + database.dialect().forUpdate())) {
                s.setString(1, ipHash);
                s.executeQuery().close();
            }
        }
    }

    private static String placeholders(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> "?").collect(Collectors.joining(", "));
    }

    private static void setLong(PreparedStatement s, int index, Long value) throws SQLException {
        if (value == null) {
            s.setNull(index, Types.BIGINT);
        } else {
            s.setLong(index, value);
        }
    }

    private static List<Punishment> list(PreparedStatement s) throws SQLException {
        List<Punishment> out = new ArrayList<>();
        try (ResultSet r = s.executeQuery()) {
            while (r.next()) {
                out.add(read(r));
            }
        }
        return out;
    }

    private static Long longOrNull(ResultSet r, String column) throws SQLException {
        long value = r.getLong(column);
        return r.wasNull() ? null : value;
    }

    private static UUID uuidOrNull(String text) {
        return text == null ? null : UUID.fromString(text);
    }

    private static Punishment read(ResultSet r) throws SQLException {
        return new Punishment(
                r.getLong("id"),
                PunishmentType.valueOf(r.getString("type")),
                UUID.fromString(r.getString("target_uuid")),
                r.getString("target_name"),
                r.getString("ip_hash"),
                r.getString("reason_key"),
                r.getString("reason_display"),
                r.getString("note"),
                r.getInt("offence"),
                r.getInt("step"),
                longOrNull(r, "duration_ms"),
                r.getLong("issued_at"),
                longOrNull(r, "expires_at"),
                uuidOrNull(r.getString("issuer_uuid")),
                r.getString("issuer_name"),
                r.getInt("issuer_weight"),
                r.getInt("target_weight"),
                WeightSource.valueOf(r.getString("target_weight_source")),
                r.getString("server"),
                r.getInt("silent") != 0,
                Origin.valueOf(r.getString("origin")),
                StoredStatus.valueOf(r.getString("status")),
                uuidOrNull(r.getString("removed_by_uuid")),
                r.getString("removed_by_name"),
                longOrNull(r, "removed_at"),
                r.getString("removed_reason"),
                longOrNull(r, "replaced_by_id"),
                longOrNull(r, "replaced_at"));
    }
}
