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
        for (String key : yaml.getKeys(false)) {
            if (!key.equals(migrator.versionKey())) {
                warnings.add(key + " is not a known setting (typo?), ignored");
            }
        }
        return LoadResult.loaded(ScoreboardSettings.DEFAULTS, warnings);
    }
}
