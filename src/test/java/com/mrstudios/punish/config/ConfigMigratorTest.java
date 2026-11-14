package com.mrstudios.punish.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigMigratorTest {

    /** A fake v1 -> v2 migration that renames {@code old-key} to {@code new.key}. */
    private static final Migration RENAME = new Migration(1, yaml -> {
        yaml.set("new.key", yaml.get("old-key"));
        yaml.set("old-key", null);
    });

    private static ConfigMigrator v2() {
        return new ConfigMigrator("config.yml", "config-version", 2, List.of(RENAME));
    }

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    @Test
    void currentVersionIsUntouched() throws Exception {
        YamlConfiguration yaml = yaml("config-version: 2\nold-key: 5\n");
        String before = yaml.saveToString();

        assertEquals(2, v2().migrate(yaml));
        assertEquals(before, yaml.saveToString());
    }

    @Test
    void theShippedMigratorsAndTheirVersions() throws Exception {
        List<ConfigMigrator> shipped = List.of(ConfigMigrator.forConfig(), ConfigMigrator.forMessages(),
                ConfigMigrator.forGui(), ConfigMigrator.forPermissions(), ConfigMigrator.forScoreboard());
        List<String> names = new ArrayList<>();
        for (ConfigMigrator migrator : shipped) {
            YamlConfiguration yaml = yaml(migrator.versionKey() + ": 1\nsome: value\n");
            // gui.yml 1 -> 2 only touches a reports tool that's still a book; nothing else changes.
            int expected = migrator.fileName().equals("gui.yml") ? 2 : 1;
            assertEquals(expected, migrator.current());
            assertEquals(1, migrator.migrate(yaml));
            assertEquals(expected, yaml.getInt(migrator.versionKey()));
            assertEquals("value", yaml.getString("some"));
            assertEquals(2, yaml.getKeys(false).size());
            names.add(migrator.fileName() + "/" + migrator.versionKey());
        }
        assertEquals(List.of("config.yml/config-version", "messages.yml/messages-version", "gui.yml/gui-version",
                "permissions.yml/permissions-version", "scoreboard.yml/scoreboard-version"), names);
    }

    @Test
    void eachShippedFileRefusesANewerVersion() throws Exception {
        for (ConfigMigrator migrator : List.of(ConfigMigrator.forGui(), ConfigMigrator.forPermissions())) {
            YamlConfiguration yaml = yaml(migrator.versionKey() + ": " + (migrator.current() + 1) + "\n");
            ConfigException e = assertThrows(ConfigException.class, () -> migrator.migrate(yaml));
            assertTrue(e.getMessage().startsWith(migrator.fileName()), e.getMessage());
        }
    }

    @Test
    void migrationRenamesKeyKeepsValueAndSetsVersion() throws Exception {
        YamlConfiguration yaml = yaml("config-version: 1\nold-key: 5\n");

        assertEquals(1, v2().migrate(yaml));
        assertEquals(5, yaml.getInt("new.key"));
        assertFalse(yaml.contains("old-key"));
        assertEquals(2, yaml.getInt("config-version"));
    }

    @Test
    void newerVersionFailsWithoutModifying() throws Exception {
        YamlConfiguration yaml = yaml("config-version: 3\nold-key: 5\n");
        String before = yaml.saveToString();

        ConfigException e = assertThrows(ConfigException.class, () -> v2().migrate(yaml));
        assertTrue(e.getMessage().contains("newer MR-Punish"), e.getMessage());
        assertEquals(before, yaml.saveToString());
    }

    @Test
    void missingVersionCountsAsOne() throws Exception {
        YamlConfiguration yaml = yaml("old-key: 5\n");

        assertEquals(1, v2().migrate(yaml));
        assertEquals(5, yaml.getInt("new.key"));
        assertEquals(2, yaml.getInt("config-version"));
    }

    @Test
    void invalidVersionFails() throws Exception {
        assertThrows(ConfigException.class, () -> v2().migrate(yaml("config-version: two\n")));
        assertThrows(ConfigException.class, () -> v2().migrate(yaml("config-version: 0\n")));
    }

    @Test
    void migrationsRunInOrderAcrossSeveralVersions() throws Exception {
        List<Integer> ran = new ArrayList<>();
        List<Migration> migrations = new ArrayList<>();
        for (int from = 3; from >= 1; from--) {
            int f = from;
            migrations.add(new Migration(f, yaml -> ran.add(f)));
        }
        ConfigMigrator migrator = new ConfigMigrator("config.yml", "config-version", 4, migrations);

        YamlConfiguration fromOne = yaml("config-version: 1\n");
        migrator.migrate(fromOne);
        assertEquals(List.of(1, 2, 3), ran);
        assertEquals(4, fromOne.getInt("config-version"));

        ran.clear();
        migrator.migrate(yaml("config-version: 2\n"));
        assertEquals(List.of(2, 3), ran);
    }

    @Test
    void gapsInMigrationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ConfigMigrator("config.yml", "config-version", 3,
                List.of(new Migration(2, yaml -> { }))));
        assertThrows(IllegalArgumentException.class, () -> new ConfigMigrator("config.yml", "config-version", 3,
                List.of(RENAME)));
    }

    @Test
    void migrateFileWritesBackupThenSavesWithComments(@TempDir Path dir) throws Exception {
        File file = dir.resolve("config.yml").toFile();
        String original = "# Top comment\nconfig-version: 1\n# Old key comment\nold-key: 5\n";
        Files.writeString(file.toPath(), original, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("config.yml.v1.bak"), "older backup", StandardCharsets.UTF_8);

        YamlConfiguration yaml = YamlFiles.parse(original, "config.yml");
        String log = v2().migrateFile(file, yaml);

        assertEquals("Migrated config.yml from v1 to v2 (backup: config.yml.v1.bak.1)", log);
        assertEquals(original, Files.readString(dir.resolve("config.yml.v1.bak.1")));
        assertEquals("older backup", Files.readString(dir.resolve("config.yml.v1.bak")));
        String migrated = Files.readString(file.toPath());
        assertTrue(migrated.contains("# Top comment"), migrated);
        YamlConfiguration reread = yaml(migrated);
        assertEquals(2, reread.getInt("config-version"));
        assertEquals(5, reread.getInt("new.key"));
    }

    @Test
    void migrateFileDoesNothingWhenCurrent(@TempDir Path dir) throws Exception {
        File file = dir.resolve("config.yml").toFile();
        String original = "config-version: 2\nold-key: 5\n";
        Files.writeString(file.toPath(), original, StandardCharsets.UTF_8);

        assertNull(v2().migrateFile(file, YamlFiles.parse(original, "config.yml")));
        assertEquals(original, Files.readString(file.toPath()));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }
}
