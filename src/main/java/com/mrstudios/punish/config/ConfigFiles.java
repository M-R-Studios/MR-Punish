package com.mrstudios.punish.config;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Loads the five YAML files from the plugin folder, each on its own: one file failing never stops
 * the others, and a failed file keeps whatever was in use before (at startup: the jar's defaults).
 */
public final class ConfigFiles {

    public static final String CONFIG = "config.yml";
    public static final String MESSAGES = "messages.yml";
    public static final String GUI = "gui.yml";
    public static final String PERMISSIONS = "permissions.yml";
    public static final String SCOREBOARD = "scoreboard.yml";
    public static final List<String> NAMES = List.of(CONFIG, MESSAGES, GUI, PERMISSIONS, SCOREBOARD);

    private final File folder;
    private final Consumer<String> saveDefault;
    private final SettingsLoader settings;
    private final MessagesLoader messages;
    private final GuiLoader gui;
    private final RankLoader ranks;
    private final ScoreboardLoader scoreboard;

    /**
     * @param folder      the plugin's data folder
     * @param saveDefault copies a bundled file (by name) into {@code folder}
     */
    public ConfigFiles(File folder, Consumer<String> saveDefault, SettingsLoader settings, MessagesLoader messages,
            GuiLoader gui, RankLoader ranks, ScoreboardLoader scoreboard) {
        this.folder = folder;
        this.saveDefault = saveDefault;
        this.settings = settings;
        this.messages = messages;
        this.gui = gui;
        this.ranks = ranks;
        this.scoreboard = scoreboard;
    }

    /** The result of loading every file once. */
    public record Loaded(LoadResult<Settings> settings, LoadResult<Messages> messages, LoadResult<GuiSettings> gui,
            LoadResult<RankSettings> ranks, LoadResult<ScoreboardSettings> scoreboard) {

        /** The new state: each file that loaded replaces its part of {@code previous}; failed files keep it. */
        public PluginState applyTo(PluginState previous) {
            return new PluginState(
                    settings.ok() ? settings.value() : previous.settings(),
                    messages.ok() ? messages.value() : previous.messages(),
                    gui.ok() ? gui.value() : previous.gui(),
                    ranks.ok() ? ranks.value() : previous.ranks(),
                    scoreboard.ok() ? scoreboard.value() : previous.scoreboard());
        }

        private Map<String, LoadResult<?>> byFile() {
            Map<String, LoadResult<?>> files = new LinkedHashMap<>();
            files.put(CONFIG, settings);
            files.put(MESSAGES, messages);
            files.put(GUI, gui);
            files.put(PERMISSIONS, ranks);
            files.put(SCOREBOARD, scoreboard);
            return files;
        }

        /** Every warning, each starting with its file name. */
        public List<String> warnings() {
            List<String> warnings = new ArrayList<>();
            byFile().forEach((file, result) -> result.warnings().forEach(w -> warnings.add(file + ": " + w)));
            return warnings;
        }

        /** The error of each file that failed, by file name, in file order. */
        public Map<String, String> failures() {
            Map<String, String> failures = new LinkedHashMap<>();
            byFile().forEach((file, result) -> {
                if (!result.ok()) {
                    failures.put(file, result.error());
                }
            });
            return failures;
        }
    }

    /** Loads all five files, first copying any that don't exist yet from the jar. */
    public Loaded load(Consumer<String> log) {
        for (String name : NAMES) {
            if (!new File(folder, name).exists()) {
                saveDefault.accept(name);
            }
        }
        return new Loaded(
                settings.load(new File(folder, CONFIG), log),
                messages.load(new File(folder, MESSAGES), log),
                gui.load(new File(folder, GUI), log),
                ranks.load(new File(folder, PERMISSIONS), log),
                scoreboard.load(new File(folder, SCOREBOARD), log));
    }

    /** Everything from the jar's own files: what a file that fails at startup falls back to. */
    public PluginState defaults() {
        return new PluginState(settings.defaults(), messages.defaults(), GuiSettings.DEFAULTS, ranks.defaults(),
                ScoreboardSettings.DEFAULTS);
    }
}
