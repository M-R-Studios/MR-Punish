package com.mrstudios.punish.config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;

/** Builds {@link ScoreboardSettings} from {@code scoreboard.yml}. Unknown keys warn (probably a typo). */
public final class ScoreboardLoader {

    private final ConfigMigrator migrator = ConfigMigrator.forScoreboard();

    /** Reads {@code file}, migrating it on disk first if it is from an older version. */
    public LoadResult<ScoreboardSettings> load(File file, Consumer<String> log) {
        try {
            return read(YamlFiles.load(file, migrator, log));
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    /** Parses and validates YAML text. Migrations are applied in memory only. */
    public LoadResult<ScoreboardSettings> load(String text) {
        try {
            YamlConfiguration yaml = YamlFiles.parse(text, migrator.fileName());
            migrator.migrate(yaml);
            return read(yaml);
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    LoadResult<ScoreboardSettings> read(YamlConfiguration yaml) {
        List<String> warnings = new ArrayList<>();
        java.util.Set<String> known = java.util.Set.of(migrator.versionKey(), "update-ticks", "title", "lines",
                "vanish-on", "vanish-off");
        for (String key : yaml.getKeys(false)) {
            if (!known.contains(key)) {
                warnings.add(key + " is not a known setting (typo?), ignored");
            }
        }
        ScoreboardSettings def = ScoreboardSettings.DEFAULTS;
        int ticks = def.updateTicks();
        if (yaml.contains("update-ticks")) {
            int n = yaml.getInt("update-ticks", -1);
            if (!yaml.isInt("update-ticks") || n < 10 || n > 200) {
                warnings.add("update-ticks: '" + yaml.get("update-ticks") + "' is not 10-200, using " + ticks);
            } else {
                ticks = n;
            }
        } else {
            warnings.add("update-ticks is missing, using " + ticks);
        }
        String title = text(yaml, "title", def.title(), warnings);
        List<String> lines = def.lines();
        if (yaml.isList("lines")) {
            List<String> read = new ArrayList<>();
            for (String line : yaml.getStringList("lines")) {
                String problem = MiniMessageCheck.problem(line, ScoreboardSettings.PLACEHOLDERS);
                if (problem != null) {
                    warnings.add("lines: '" + line + "' " + problem + ", skipped");
                } else {
                    read.add(line);
                }
            }
            if (read.size() > ScoreboardSettings.MAX_LINES) {
                warnings.add("lines: only the first " + ScoreboardSettings.MAX_LINES + " lines are shown");
                read = read.subList(0, ScoreboardSettings.MAX_LINES);
            }
            lines = read;
        } else {
            warnings.add("lines is " + (yaml.contains("lines") ? "not a list" : "missing") + ", using the default");
        }
        return LoadResult.loaded(new ScoreboardSettings(ticks, title, lines, text(yaml, "vanish-on", def.vanishOn(),
                warnings), text(yaml, "vanish-off", def.vanishOff(), warnings)), warnings);
    }

    private static String text(YamlConfiguration yaml, String key, String def, List<String> warnings) {
        if (!yaml.isString(key)) {
            warnings.add(key + (yaml.contains(key) ? " is not text" : " is missing") + ", using the default");
            return def;
        }
        String value = yaml.getString(key);
        String problem = MiniMessageCheck.problem(value, ScoreboardSettings.PLACEHOLDERS);
        if (problem != null) {
            warnings.add(key + ": " + problem + ", using the default");
            return def;
        }
        return value;
    }
}
