package com.mrstudios.punish.config;

import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Upgrades a file from version {@code from} to {@code from + 1}. Migrations that add keys also set
 * their comments with {@link YamlConfiguration#setComments(String, java.util.List)}.
 */
public record Migration(int from, Consumer<YamlConfiguration> action) {

    public void apply(YamlConfiguration yaml) {
        action.accept(yaml);
    }
}
