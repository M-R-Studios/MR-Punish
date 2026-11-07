package com.mrstudios.punish.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads durations such as {@code 30m}, {@code 1d12h} or {@code permanent}. Units: s, m, h, d, w,
 * mo (30 days) and y (365 days), case-insensitive, combined in any order, each at most once, with
 * optional spaces between them. A duration must be more than 0 and at most 100 years.
 */
public final class DurationParser {

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final long WEEK = 7 * DAY;
    private static final long MONTH = 30 * DAY;
    private static final long YEAR = 365 * DAY;

    /** The longest duration accepted: 100 years. */
    public static final long MAX_MILLIS = 100 * YEAR;

    private static final Map<String, Long> UNITS = Map.of(
            "s", SECOND, "m", MINUTE, "h", HOUR, "d", DAY, "w", WEEK, "mo", MONTH, "y", YEAR);

    /** Units used when formatting, largest first. Weeks are left out: "13d" reads better than "1w 6d". */
    private static final Map<String, Long> FORMAT_UNITS = formatUnits();

    /** "mo" is listed before "m" so that "2mo" isn't read as "2m" followed by junk. */
    private static final Pattern PART = Pattern.compile("(\\d{1,12})(mo|[smhdwy])");

    private static final Set<String> PERMANENT_WORDS = Set.of("permanent", "perm", "forever");

    private static final String EXAMPLES = "use e.g. 30m, 2h, 1d12h, 4w or permanent";

    private DurationParser() {
    }

    private static Map<String, Long> formatUnits() {
        Map<String, Long> units = new LinkedHashMap<>();
        units.put("y", YEAR);
        units.put("mo", MONTH);
        units.put("d", DAY);
        units.put("h", HOUR);
        units.put("m", MINUTE);
        units.put("s", SECOND);
        return units;
    }

    /**
     * Parses {@code text}.
     *
     * @throws IllegalArgumentException with a message saying what's wrong, if it isn't a valid duration
     */
    public static TimeSpan parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("an empty duration is not allowed (" + EXAMPLES + ")");
        }
        String input = text.strip().toLowerCase(Locale.ROOT);
        if (PERMANENT_WORDS.contains(input)) {
            return TimeSpan.PERMANENT;
        }
        Matcher matcher = PART.matcher(input);
        Set<String> seen = new HashSet<>();
        long total = 0;
        int at = 0;
        while (at < input.length()) {
            if (Character.isWhitespace(input.charAt(at)) && at > 0) {
                at++;
                continue;
            }
            matcher.region(at, input.length());
            if (!matcher.lookingAt()) {
                throw new IllegalArgumentException("'" + text + "' is not a duration (" + EXAMPLES + ")");
            }
            String unit = matcher.group(2);
            if (!seen.add(unit)) {
                throw new IllegalArgumentException("'" + text + "' uses '" + unit + "' twice (" + EXAMPLES + ")");
            }
            try {
                total = Math.addExact(total, Math.multiplyExact(Long.parseLong(matcher.group(1)), UNITS.get(unit)));
            } catch (ArithmeticException e) {
                throw tooLong(text);
            }
            if (total > MAX_MILLIS) {
                throw tooLong(text);
            }
            at = matcher.end();
        }
        if (total <= 0) {
            throw new IllegalArgumentException("'" + text + "' must be longer than 0 (" + EXAMPLES + ")");
        }
        return TimeSpan.ofMillis(total);
    }

    private static IllegalArgumentException tooLong(String text) {
        return new IllegalArgumentException("'" + text + "' is longer than 100 years; use permanent instead");
    }

    /**
     * A short, readable form of {@code millis} with its two largest units, e.g. {@code 6d 23h},
     * {@code 2h}, {@code 45s}. Rounded up to whole seconds, so a time left is never shown as 0s.
     */
    public static String format(long millis) {
        if (millis <= 0) {
            return "0s";
        }
        long left = Math.ceilDiv(millis, SECOND) * SECOND;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Long> unit : FORMAT_UNITS.entrySet()) {
            long count = left / unit.getValue();
            if (count > 0) {
                parts.add(count + unit.getKey());
                left -= count * unit.getValue();
            }
            if (parts.size() == 2 || (!parts.isEmpty() && count == 0)) {
                break;
            }
        }
        return String.join(" ", parts);
    }
}
