package com.mrstudios.punish.storage;

import com.mrstudios.punish.report.Report;
import com.mrstudios.punish.report.Report.Status;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code reports} and {@code report_notes} tables (DESIGN.md 9.1, 13). Reports are never
 * deleted. Every state change is optimistic-locked: {@code ... WHERE id = ? AND version = ?}, and
 * zero rows changed means someone else changed it first. Every value is a bound parameter.
 */
public final class ReportRepo {

    private static final String COLUMNS = "id, reporter_uuid, reporter_name, target_uuid, target_name, reason_key,"
            + " reason_text, server, world, x, y, z, created_at, status, claimed_by_uuid, claimed_by_name, claimed_at,"
            + " closed_by_uuid, closed_by_name, closed_at, decision_note, version";

    /** Which reports the /reports menu lists. MINE = claimed by the viewer. */
    public enum Filter {
        OPEN, CLAIMED, MINE, CLOSED, ALL;

        public Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private final Database database;

    public ReportRepo(Database database) {
        this.database = database;
    }

    private String reports() {
        return database.table("reports");
    }

    private String notes() {
        return database.table("report_notes");
    }

    /** Inserts {@code r} (id and version ignored) and returns its id. */
    public long insert(Connection c, Report r) throws SQLException {
        String sql = "INSERT INTO " + reports() + " (reporter_uuid, reporter_name, target_uuid, target_name, reason_key,"
                + " reason_text, server, world, x, y, z, created_at, status, version)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)";
        try (PreparedStatement s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, r.reporterUuid().toString());
            s.setString(2, r.reporterName());
            s.setString(3, r.targetUuid().toString());
            s.setString(4, r.targetName());
            s.setString(5, r.reasonKey());
            s.setString(6, r.reasonText());
            s.setString(7, r.server());
            s.setString(8, r.world());
            setInt(s, 9, r.x());
            setInt(s, 10, r.y());
            setInt(s, 11, r.z());
            s.setLong(12, r.createdAt());
            s.setString(13, Status.OPEN.name());
            s.executeUpdate();
            try (ResultSet keys = s.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public Optional<Report> byId(Connection c, long id) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS + " FROM " + reports() + " WHERE id = ?")) {
            s.setLong(1, id);
            List<Report> found = list(s);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        }
    }

    /** The reporter's OPEN or CLAIMED report about {@code target}, if any (duplicate protection). */
    public Optional<Report> openAbout(Connection c, UUID reporter, UUID target) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS + " FROM " + reports()
                + " WHERE reporter_uuid = ? AND target_uuid = ? AND status IN ('OPEN', 'CLAIMED') ORDER BY id LIMIT 1")) {
            s.setString(1, reporter.toString());
            s.setString(2, target.toString());
            List<Report> found = list(s);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        }
    }

    /** How many OPEN or CLAIMED reports {@code reporter} has. */
    public int openBy(Connection c, UUID reporter) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM " + reports()
                + " WHERE reporter_uuid = ? AND status IN ('OPEN', 'CLAIMED')")) {
            s.setString(1, reporter.toString());
            return count(s);
        }
    }

    /** How many reports are OPEN (the reminder). */
    public int countOpen(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM " + reports() + " WHERE status = 'OPEN'")) {
            return count(s);
        }
    }

    private String where(Filter filter, List<Object> params, UUID viewer) {
        return switch (filter) {
            case OPEN -> " WHERE status = 'OPEN' ORDER BY created_at, id";
            case CLAIMED -> " WHERE status = 'CLAIMED' ORDER BY created_at, id";
            case MINE -> {
                params.add(viewer.toString());
                yield " WHERE status = 'CLAIMED' AND claimed_by_uuid = ? ORDER BY created_at, id";
            }
            case CLOSED -> " WHERE status IN ('RESOLVED', 'REJECTED') ORDER BY closed_at DESC, id DESC";
            case ALL -> " ORDER BY id DESC";
        };
    }

    /**
     * One page of the /reports menu: OPEN, CLAIMED and MINE oldest first (first come, first
     * served), CLOSED newest closed first, ALL newest first.
     */
    public List<Report> page(Connection c, Filter filter, UUID viewer, int offset, int limit) throws SQLException {
        List<Object> params = new ArrayList<>();
        String sql = "SELECT " + COLUMNS + " FROM " + reports() + where(filter, params, viewer) + " LIMIT ? OFFSET ?";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            int i = 1;
            for (Object p : params) {
                s.setString(i++, (String) p);
            }
            s.setInt(i++, limit);
            s.setInt(i, offset);
            return list(s);
        }
    }

    public int count(Connection c, Filter filter, UUID viewer) throws SQLException {
        List<Object> params = new ArrayList<>();
        String clause = where(filter, params, viewer);
        int order = clause.indexOf(" ORDER BY");
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM " + reports()
                + (order >= 0 ? clause.substring(0, order) : clause))) {
            int i = 1;
            for (Object p : params) {
                s.setString(i++, (String) p);
            }
            return count(s);
        }
    }

    /** OPEN to CLAIMED by {@code uuid}; false if the version or the status changed meanwhile. */
    public boolean claim(Connection c, long id, int version, UUID uuid, String name, long at) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE " + reports() + " SET status = 'CLAIMED',"
                + " claimed_by_uuid = ?, claimed_by_name = ?, claimed_at = ?, version = version + 1"
                + " WHERE id = ? AND version = ? AND status = 'OPEN'")) {
            s.setString(1, uuid.toString());
            s.setString(2, name);
            s.setLong(3, at);
            s.setLong(4, id);
            s.setInt(5, version);
            return s.executeUpdate() == 1;
        }
    }

    /** CLAIMED back to OPEN (unclaim or claim timeout); false if it changed meanwhile. */
    public boolean unclaim(Connection c, long id, int version) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE " + reports() + " SET status = 'OPEN',"
                + " claimed_by_uuid = NULL, claimed_by_name = NULL, claimed_at = NULL, version = version + 1"
                + " WHERE id = ? AND version = ? AND status = 'CLAIMED'")) {
            s.setLong(1, id);
            s.setInt(2, version);
            return s.executeUpdate() == 1;
        }
    }

    /** OPEN or CLAIMED to RESOLVED or REJECTED; false if it changed meanwhile. */
    public boolean close(Connection c, long id, int version, Status status, UUID byUuid, String byName, long at,
            String note) throws SQLException {
        if (!status.closed()) {
            throw new IllegalArgumentException(status + " is not a closed status");
        }
        try (PreparedStatement s = c.prepareStatement("UPDATE " + reports() + " SET status = ?, closed_by_uuid = ?,"
                + " closed_by_name = ?, closed_at = ?, decision_note = ?, version = version + 1"
                + " WHERE id = ? AND version = ? AND status IN ('OPEN', 'CLAIMED')")) {
            s.setString(1, status.name());
            s.setString(2, byUuid == null ? null : byUuid.toString());
            s.setString(3, byName);
            s.setLong(4, at);
            s.setString(5, note);
            s.setLong(6, id);
            s.setInt(7, version);
            return s.executeUpdate() == 1;
        }
    }

    /** CLAIMED reports claimed before {@code before} (claim timeout). */
    public List<Report> claimedBefore(Connection c, long before) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS + " FROM " + reports()
                + " WHERE status = 'CLAIMED' AND claimed_at < ? ORDER BY id")) {
            s.setLong(1, before);
            return list(s);
        }
    }

    public long addNote(Connection c, long reportId, UUID author, String authorName, String text, long at)
            throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO " + notes()
                + " (report_id, author_uuid, author_name, text, created_at) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            s.setLong(1, reportId);
            s.setString(2, author == null ? null : author.toString());
            s.setString(3, authorName);
            s.setString(4, text);
            s.setLong(5, at);
            s.executeUpdate();
            try (ResultSet keys = s.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /** The report's notes, newest first. */
    public List<Report.Note> notes(Connection c, long reportId, int offset, int limit) throws SQLException {
        List<Report.Note> out = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT id, report_id, author_uuid, author_name, text, created_at"
                + " FROM " + notes() + " WHERE report_id = ? ORDER BY id DESC LIMIT ? OFFSET ?")) {
            s.setLong(1, reportId);
            s.setInt(2, limit);
            s.setInt(3, offset);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String author = r.getString(3);
                    out.add(new Report.Note(r.getLong(1), r.getLong(2), author == null ? null : UUID.fromString(author),
                            r.getString(4), r.getString(5), r.getLong(6)));
                }
            }
        }
        return out;
    }

    public int noteCount(Connection c, long reportId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM " + notes() + " WHERE report_id = ?")) {
            s.setLong(1, reportId);
            return count(s);
        }
    }

    private static int count(PreparedStatement s) throws SQLException {
        try (ResultSet r = s.executeQuery()) {
            return r.next() ? r.getInt(1) : 0;
        }
    }

    private static void setInt(PreparedStatement s, int i, Integer v) throws SQLException {
        if (v == null) {
            s.setNull(i, Types.INTEGER);
        } else {
            s.setInt(i, v);
        }
    }

    private static Integer optInt(ResultSet r, String column) throws SQLException {
        int v = r.getInt(column);
        return r.wasNull() ? null : v;
    }

    private static Long optLong(ResultSet r, String column) throws SQLException {
        long v = r.getLong(column);
        return r.wasNull() ? null : v;
    }

    private static UUID optUuid(String s) {
        return s == null ? null : UUID.fromString(s);
    }

    private static List<Report> list(PreparedStatement s) throws SQLException {
        List<Report> out = new ArrayList<>();
        try (ResultSet r = s.executeQuery()) {
            while (r.next()) {
                out.add(new Report(r.getLong("id"), UUID.fromString(r.getString("reporter_uuid")),
                        r.getString("reporter_name"), UUID.fromString(r.getString("target_uuid")),
                        r.getString("target_name"), r.getString("reason_key"), r.getString("reason_text"),
                        r.getString("server"), r.getString("world"), optInt(r, "x"), optInt(r, "y"), optInt(r, "z"),
                        r.getLong("created_at"), Status.valueOf(r.getString("status")),
                        optUuid(r.getString("claimed_by_uuid")), r.getString("claimed_by_name"),
                        optLong(r, "claimed_at"), optUuid(r.getString("closed_by_uuid")),
                        r.getString("closed_by_name"), optLong(r, "closed_at"), r.getString("decision_note"),
                        r.getInt("version")));
            }
        }
        return out;
    }
}
