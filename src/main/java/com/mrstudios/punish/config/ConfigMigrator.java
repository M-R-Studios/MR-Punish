package com.mrstudios.punish.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Upgrades one of the five YAML files to its current version. Any rename or removal of an existing
 * key needs a {@link Migration} here and a bump of that file's current version.
 */
public final class ConfigMigrator {

    public static final int CONFIG_VERSION = 1;
    public static final int MESSAGES_VERSION = 1;
    public static final int GUI_VERSION = 2;
    public static final int PERMISSIONS_VERSION = 1;
    public static final int SCOREBOARD_VERSION = 1;

    private final String fileName;
    private final String versionKey;
    private final int current;
    private final List<Migration> migrations;

    public ConfigMigrator(String fileName, String versionKey, int current, List<Migration> migrations) {
        List<Migration> sorted = new ArrayList<>(migrations);
        sorted.sort(Comparator.comparingInt(Migration::from));
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).from() != i + 1) {
                throw new IllegalArgumentException(fileName + " migrations must go 1, 2, 3... without gaps");
            }
        }
        if (sorted.size() != current - 1) {
            throw new IllegalArgumentException(fileName + " needs exactly one migration per version below " + current);
        }
        this.fileName = fileName;
        this.versionKey = versionKey;
        this.current = current;
        this.migrations = List.copyOf(sorted);
    }

    public static ConfigMigrator forConfig() {
        return new ConfigMigrator("config.yml", "config-version", CONFIG_VERSION, List.of());
    }

    public static ConfigMigrator forMessages() {
        return new ConfigMigrator("messages.yml", "messages-version", MESSAGES_VERSION, List.of());
    }

    public static ConfigMigrator forGui() {
        return new ConfigMigrator("gui.yml", "gui-version", GUI_VERSION, List.of(
                // 1 -> 2: the staff-mode reports tool was a book and quill, which Bedrock clients open as an
                // editor. Changed only if it's still a book.
                new Migration(1, yaml -> {
                    String material = yaml.getString("staff-tools.reports.material", "");
                    if (GuiLoader.isBook(material)) {
                        yaml.set("staff-tools.reports.material", "PAPER");
                    }
                })));
    }

    public static ConfigMigrator forPermissions() {
        return new ConfigMigrator("permissions.yml", "permissions-version", PERMISSIONS_VERSION, List.of());
    }

    public static ConfigMigrator forScoreboard() {
        return new ConfigMigrator("scoreboard.yml", "scoreboard-version", SCOREBOARD_VERSION, List.of());
    }

    public String fileName() {
        return fileName;
    }

    public String versionKey() {
        return versionKey;
    }

    public int current() {
        return current;
    }

    /** The file's version. A missing version counts as 1. */
    public int versionOf(YamlConfiguration yaml) throws ConfigException {
        Object raw = yaml.get(versionKey);
        if (raw == null) {
            return 1;
        }
        if (raw instanceof Integer || raw instanceof Long) {
            long version = ((Number) raw).longValue();
            if (version >= 1 && version <= Integer.MAX_VALUE) {
                return (int) version;
            }
        }
        throw new ConfigException(fileName + ": " + versionKey + " '" + raw + "' is not a valid version."
                + " Set it back to the number it had (" + current + " for this version of MR-Punish).");
    }

    /**
     * Applies the migrations in memory and returns the version the file had. When that equals
     * {@link #current()} nothing was changed. A version newer than supported fails and leaves
     * {@code yaml} untouched.
     */
    public int migrate(YamlConfiguration yaml) throws ConfigException {
        int version = versionOf(yaml);
        if (version > current) {
            throw new ConfigException(fileName + " is " + versionKey + " " + version
                    + ", made for a newer MR-Punish (this version reads up to " + current + "). The file was not changed.");
        }
        if (version == current) {
            return version;
        }
        for (Migration migration : migrations) {
            if (migration.from() >= version) {
                migration.apply(yaml);
            }
        }
        yaml.set(versionKey, current);
        return version;
    }

    /**
     * Migrates {@code yaml}, which was read from {@code file}. If anything changed, the original file
     * is first copied to a backup and then overwritten.
     *
     * @return a log line describing the migration, or null if the file was already current
     */
    public String migrateFile(File file, YamlConfiguration yaml) throws ConfigException {
        int version = migrate(yaml);
        if (version == current) {
            return null;
        }
        File backup = backupFile(file, version);
        try {
            Files.copy(file.toPath(), backup.toPath());
            yaml.save(file);
        } catch (IOException e) {
            throw new ConfigException(fileName + ": could not write the migrated file (" + e.getMessage() + ")", e);
        }
        return "Migrated " + fileName + " from v" + version + " to v" + current + " (backup: " + backup.getName() + ")";
    }

    /** {@code config.yml.v1.bak}, or {@code .bak.1}, {@code .bak.2}... if that already exists. */
    static File backupFile(File file, int version) {
        String base = file.getName() + ".v" + version + ".bak";
        File backup = new File(file.getParentFile(), base);
        for (int i = 1; backup.exists(); i++) {
            backup = new File(file.getParentFile(), base + "." + i);
        }
        return backup;
    }
}
