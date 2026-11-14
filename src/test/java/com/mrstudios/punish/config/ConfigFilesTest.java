package com.mrstudios.punish.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.config.ReloadReport.Line;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loading and reloading the five files together (the automated part of manual check M2). */
class ConfigFilesTest {

    @TempDir
    Path dir;
    private ConfigFiles files;
    private final List<String> log = new ArrayList<>();

    @BeforeEach
    void setUp() {
        files = new ConfigFiles(dir.toFile(), this::copyFromJar,
                new SettingsLoader(name -> true, SettingsLoaderTest.SHIPPED_COMMANDS, Set.of("mrpunish"),
                        SettingsLoader.bundledDefaults()),
                new MessagesLoader(MessagesLoader.bundledDefaults()),
                new GuiLoader(),
                new RankLoader(name -> true, RankLoader.bundledDefaults()),
                new ScoreboardLoader());
    }

    private void copyFromJar(String name) {
        try (InputStream in = ConfigFilesTest.class.getResourceAsStream("/" + name)) {
            Files.copy(in, dir.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void replaceIn(String file, String from, String to) throws IOException {
        Path path = dir.resolve(file);
        String text = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(text.contains(from), file + " doesn't contain " + from);
        Files.writeString(path, text.replace(from, to), StandardCharsets.UTF_8);
    }

    @Test
    void firstLoadCreatesAllFiveFilesWithTheirComments() throws IOException {
        ConfigFiles.Loaded loaded = files.load(log::add);
        for (String name : ConfigFiles.NAMES) {
            String text = Files.readString(dir.resolve(name));
            assertTrue(text.startsWith("# MR-Punish") || text.startsWith("# Staff mode"), name + ": " + text);
        }
        assertEquals(List.of(), loaded.warnings());
        assertEquals(List.of(), loaded.failures().keySet().stream().toList());
        assertEquals(List.of(Line.of("admin.reload-success")), ReloadReport.lines(loaded, List.of()));
    }

    @Test
    void eachBrokenFileFailsAloneAndKeepsItsPreviousSettings() throws IOException {
        PluginState before = files.load(log::add).applyTo(files.defaults());

        replaceIn("config.yml", "  BAN: other-ban\n", "");                    // missing fallback reason
        replaceIn("messages.yml", "prefix: \"", "prefix: \"\"\"");             // YAML error
        replaceIn("permissions.yml", "weight: 20", "weight: 10");              // duplicate rank weight
        replaceIn("gui.yml", "gui-version: 2", "gui-version: 2\n  : [");      // YAML error
        ConfigFiles.Loaded loaded = files.load(log::add);
        PluginState after = loaded.applyTo(before);

        assertEquals(List.of("config.yml", "messages.yml", "gui.yml", "permissions.yml"),
                List.copyOf(loaded.failures().keySet()));
        assertTrue(loaded.failures().get("config.yml").contains("fallback-reasons.BAN is missing"));
        assertTrue(loaded.failures().get("messages.yml").startsWith("messages.yml line 7"),
                loaded.failures().get("messages.yml"));
        assertTrue(loaded.failures().get("permissions.yml").contains("is also the weight of 'helper'"));
        assertTrue(loaded.failures().get("gui.yml").startsWith("gui.yml line "));
        assertSame(before.settings(), after.settings());
        assertSame(before.messages(), after.messages());
        assertSame(before.gui(), after.gui());
        assertSame(before.ranks(), after.ranks());
        // scoreboard.yml was fine, so it was reloaded.
        assertTrue(loaded.scoreboard().ok());

        List<Line> lines = ReloadReport.lines(loaded, List.of());
        assertEquals(new Line("admin.reload-failed", "file", "config.yml"), lines.get(0));
        assertEquals("admin.reload-detail", lines.get(1).key());
        assertEquals(8, lines.size());
        assertFalse(lines.contains(Line.of("admin.reload-success")));
    }

    @Test
    void aBadValueOnlyWarnsAndTheRestApplies() throws IOException {
        PluginState before = files.load(log::add).applyTo(files.defaults());
        replaceIn("config.yml", "history-period: 90d", "history-period: 30d");
        replaceIn("config.yml", "escalation: [\"10m\", \"30m\", \"2h\", \"1d\"]", "escalation: [\"10m\", \"-5m\"]");

        ConfigFiles.Loaded loaded = files.load(log::add);
        PluginState after = loaded.applyTo(before);

        assertEquals(DurationParser.parse("30d"), after.settings().historyPeriod());
        assertFalse(after.settings().reasons().byKey("spam").orElseThrow().enabled());
        List<Line> lines = ReloadReport.lines(loaded, List.of("commands"));
        assertEquals(new Line("admin.reload-warnings", "count", "1"), lines.get(0));
        assertTrue(lines.get(1).value().startsWith("config.yml: reasons.spam: escalation step 2"), lines.get(1).value());
        assertEquals(new Line("admin.reload-restart-needed", "what", "commands"), lines.get(2));
    }

    @Test
    void aFileDeletedWhileRunningIsRecreatedFromTheJar() throws IOException {
        files.load(log::add);
        Files.delete(dir.resolve("scoreboard.yml"));
        ConfigFiles.Loaded loaded = files.load(log::add);
        assertTrue(Files.exists(dir.resolve("scoreboard.yml")));
        assertTrue(loaded.failures().isEmpty());
    }

    @Test
    void aFileFromANewerVersionIsLeftAlone() throws IOException {
        files.load(log::add);
        replaceIn("permissions.yml", "permissions-version: 1", "permissions-version: 2");
        String text = Files.readString(dir.resolve("permissions.yml"));
        ConfigFiles.Loaded loaded = files.load(log::add);
        assertTrue(loaded.failures().get("permissions.yml").contains("newer MR-Punish"));
        assertEquals(text, Files.readString(dir.resolve("permissions.yml")));
    }

    @Test
    void defaultsAreTheJarFiles() {
        PluginState defaults = files.defaults();
        assertEquals(4, defaults.ranks().ranks().size());
        assertEquals("other-ban", defaults.settings().reasons().fallback(com.mrstudios.punish.punish.PunishmentType.BAN)
                .key());
    }
}
