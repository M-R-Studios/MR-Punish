package com.mrstudios.punish.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What {@code /mrpunish reload} tells the sender: success, or every warning, every failed file with
 * its reason, and anything that only applies after a restart.
 */
public final class ReloadReport {

    /**
     * One message to send.
     *
     * @param key   the message key in {@code messages.yml}
     * @param name  the placeholder it fills, or null
     * @param value that placeholder's value (plain text)
     */
    public record Line(String key, String name, String value) {

        static Line of(String key) {
            return new Line(key, null, null);
        }
    }

    private ReloadReport() {
    }

    /**
     * @param restartNeeded what changed that only applies after a restart, e.g. {@code commands}
     */
    public static List<Line> lines(ConfigFiles.Loaded loaded, List<String> restartNeeded) {
        List<Line> lines = new ArrayList<>();
        List<String> warnings = loaded.warnings();
        Map<String, String> failures = loaded.failures();
        if (warnings.isEmpty() && failures.isEmpty()) {
            lines.add(Line.of("admin.reload-success"));
        }
        if (!warnings.isEmpty()) {
            lines.add(new Line("admin.reload-warnings", "count", String.valueOf(warnings.size())));
            for (String warning : warnings) {
                lines.add(new Line("admin.reload-detail", "detail", warning));
            }
        }
        failures.forEach((file, error) -> {
            lines.add(new Line("admin.reload-failed", "file", file));
            lines.add(new Line("admin.reload-detail", "detail", error));
        });
        if (!restartNeeded.isEmpty()) {
            lines.add(new Line("admin.reload-restart-needed", "what", String.join(", ", restartNeeded)));
        }
        return lines;
    }
}
