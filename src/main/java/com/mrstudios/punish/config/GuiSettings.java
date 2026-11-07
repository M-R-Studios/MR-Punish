package com.mrstudios.punish.config;

/**
 * Everything from {@code gui.yml}, validated. Immutable: a reload builds a new one.
 * The menus add their settings here as they're built; for now the file only has its version.
 */
public record GuiSettings() {

    public static final GuiSettings DEFAULTS = new GuiSettings();
}
