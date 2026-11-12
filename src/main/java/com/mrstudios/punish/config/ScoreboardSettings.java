package com.mrstudios.punish.config;

import java.util.List;

/**
 * Everything from {@code scoreboard.yml} (DESIGN.md 11.8), validated. Immutable: a reload builds a
 * new one.
 *
 * @param updateTicks how often the board is redrawn (10-200)
 * @param title       MiniMessage
 * @param lines       MiniMessage, top to bottom, at most 15
 */
public record ScoreboardSettings(int updateTicks, String title, List<String> lines, String vanishOn,
        String vanishOff) {

    public static final int MAX_LINES = 15;

    public static final ScoreboardSettings DEFAULTS = new ScoreboardSettings(20,
            "<gradient:#ef4444:#f97316><bold>Staff Mode",
            List.of("<dark_gray><st>                    ", "<gray>Online: <white><online>/<max>",
                    "<gray>Staff: <white><staff-online>", "<gray>Vanish: <vanish-state>", "",
                    "<gray>Open reports: <yellow><open-reports>", "<gray>Frozen: <aqua><frozen>", "",
                    "<gray>TPS: <white><tps> <dark_gray>(<mspt> ms)", "<gray>Ping: <white><ping> ms",
                    "<dark_gray><st>                    "),
            "<green>on", "<red>off");

    /** The placeholders a line may use. */
    public static final List<String> PLACEHOLDERS = List.of("online", "max", "staff-online", "vanished",
            "open-reports", "frozen", "tps", "mspt", "ping", "world", "x", "y", "z", "vanish-state", "server", "player");

    public ScoreboardSettings {
        lines = List.copyOf(lines);
    }
}
