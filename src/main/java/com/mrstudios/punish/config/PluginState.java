package com.mrstudios.punish.config;

/**
 * The active settings of all five files. Swapped as a whole through one volatile reference, so an
 * action that reads it once at its start never mixes old and new settings.
 */
public record PluginState(Settings settings, Messages messages, GuiSettings gui, RankSettings ranks,
        ScoreboardSettings scoreboard) {
}
