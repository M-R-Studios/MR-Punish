package com.mrstudios.punish.config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;

/** Builds {@link GuiSettings} from {@code gui.yml}. Unknown keys warn (probably a typo). */
public final class GuiLoader {

    private final ConfigMigrator migrator = ConfigMigrator.forGui();

    /** Reads {@code file}, migrating it on disk first if it is from an older version. */
    public LoadResult<GuiSettings> load(File file, Consumer<String> log) {
        try {
            return read(YamlFiles.load(file, migrator, log));
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    /** Parses and validates YAML text. Migrations are applied in memory only. */
    public LoadResult<GuiSettings> load(String text) {
        try {
            YamlConfiguration yaml = YamlFiles.parse(text, migrator.fileName());
            migrator.migrate(yaml);
            return read(yaml);
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    LoadResult<GuiSettings> read(YamlConfiguration yaml) {
        List<String> warnings = new ArrayList<>();
        for (String key : yaml.getKeys(false)) {
            if (!key.equals(migrator.versionKey())) {
                warnings.add(key + " is not a known setting (typo?), ignored");
            }
        }
        return LoadResult.loaded(GuiSettings.DEFAULTS, warnings);
    }
}
