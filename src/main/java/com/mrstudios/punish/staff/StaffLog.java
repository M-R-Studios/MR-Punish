package com.mrstudios.punish.staff;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The staff action log's rules (DESIGN.md 13.6). Pure: which group an action belongs to (colour and
 * filter), the file line, and a last guard that keeps anything that looks like an IP address out
 * of everything shown or written (details never contain one by design).
 */
public final class StaffLog {

    /** The filter groups, in the order the menu cycles them. {@code ALL} first. */
    public enum Group {
        ALL, PUNISHMENTS, REMOVALS, REPORTS, INSPECTIONS, STAFF_TOOLS, ADMIN;

        public Group next() {
            return values()[(ordinal() + 1) % values().length];
        }

        /** The actions of this group (empty for ALL = every action). */
        public List<String> actions() {
            return switch (this) {
                case ALL -> List.of();
                case PUNISHMENTS -> List.of("PUNISH", "REFUSED");
                case REMOVALS -> List.of("REMOVE");
                case REPORTS -> List.of("REPORT_CREATE", "REPORT_CLAIM", "REPORT_UNCLAIM", "REPORT_RESOLVE",
                        "REPORT_REJECT", "REPORT_NOTE", "REPORT_CLAIM_EXPIRED");
                case INSPECTIONS -> List.of("INSPECT_INV", "INSPECT_ENDER", "INSPECT_HISTORY");
                case STAFF_TOOLS -> List.of("FREEZE", "UNFREEZE", "FREEZE_QUIT", "STAFFMODE_ON", "STAFFMODE_OFF",
                        "VANISH_ON", "VANISH_OFF");
                case ADMIN -> List.of("RELOAD", "IMPORT", "FORCE_EXIT");
            };
        }
    }

    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    /** Eight groups, or a {@code ::} shortening; never a clock time such as {@code 14:03:12}. */
    private static final Pattern IPV6 = Pattern.compile("(?<![0-9A-Za-z:])(?:(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}"
            + "|(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{0,4}::(?:[0-9a-fA-F]{1,4}:){0,6}[0-9a-fA-F]{0,4})(?![0-9A-Za-z:])");
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss",
            Locale.ROOT);
    private static final DateTimeFormatter FILE_DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private StaffLog() {
    }

    /** The group an action belongs to; unknown actions are ADMIN. */
    public static Group groupOf(String action) {
        for (Group g : Group.values()) {
            if (g.actions().contains(action)) {
                return g;
            }
        }
        return Group.ADMIN;
    }

    /** Replaces anything that looks like an IP address with {@code [ip]}. Null stays null. */
    public static String scrub(String text) {
        if (text == null) {
            return null;
        }
        return IPV6.matcher(IPV4.matcher(text).replaceAll("[ip]")).replaceAll("[ip]");
    }

    /** {@code staff-2026-11-07.log} for an action at {@code at}. */
    public static String fileName(long at, ZoneId zone) {
        return "staff-" + FILE_DAY.format(Instant.ofEpochMilli(at).atZone(zone)) + ".log";
    }

    /**
     * One file line: {@code 2026-11-07 14:03:12 [survival] Alex PUNISH Steve #42 MUTE toxicity 2h offence=1}.
     */
    public static String line(long at, ZoneId zone, String server, String actor, String action, String target,
            Long refId, String details) {
        StringBuilder out = new StringBuilder(LINE_TIME.format(Instant.ofEpochMilli(at).atZone(zone)));
        out.append(" [").append(server).append("] ").append(actor).append(' ').append(action);
        if (target != null) {
            out.append(' ').append(target);
        }
        if (refId != null) {
            out.append(" #").append(refId);
        }
        if (details != null && !details.isBlank()) {
            out.append(' ').append(details.replace('\n', ' ').replace('\r', ' '));
        }
        return scrub(out.toString());
    }
}
