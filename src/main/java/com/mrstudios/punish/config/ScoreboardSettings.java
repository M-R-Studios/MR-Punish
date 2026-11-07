package com.mrstudios.punish.config;

/**
 * Everything from {@code scoreboard.yml}, validated. Immutable: a reload builds a new one.
 * Staff mode adds its scoreboard settings here; for now the file only has its version.
 */
public record ScoreboardSettings() {

    public static final ScoreboardSettings DEFAULTS = new ScoreboardSettings();
}
