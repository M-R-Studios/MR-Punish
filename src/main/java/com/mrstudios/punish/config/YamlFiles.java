package com.mrstudios.punish.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Consumer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/** Reads YAML files and turns parser errors into one readable line with the line number. */
final class YamlFiles {

    private YamlFiles() {
    }

    /** Reads, parses and migrates {@code file}. Migration log lines go to {@code log}. */
    static YamlConfiguration load(File file, ConfigMigrator migrator, Consumer<String> log) throws ConfigException {
        String name = migrator.fileName();
        String text;
        try {
            text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigException(name + ": could not read the file (" + e + ")", e);
        }
        YamlConfiguration yaml = parse(text, name);
        String migrated = migrator.migrateFile(file, yaml);
        if (migrated != null) {
            log.accept(migrated);
        }
        return yaml;
    }

    static YamlConfiguration parse(String text, String name) throws ConfigException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException e) {
            throw new ConfigException(describe(name, e), e);
        }
        return yaml;
    }

    /** The copy of {@code name} bundled in the jar. */
    static YamlConfiguration bundled(String name) {
        try (java.io.InputStream in = YamlFiles.class.getResourceAsStream("/" + name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is missing from the jar");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), "bundled " + name);
        } catch (IOException | ConfigException e) {
            throw new IllegalStateException("Could not read the bundled " + name, e);
        }
    }

    /** e.g. {@code config.yml line 12, column 5: mapping values are not allowed here}. */
    static String describe(String name, InvalidConfigurationException e) {
        if (e.getCause() instanceof MarkedYAMLException marked) {
            Mark mark = marked.getProblemMark() != null ? marked.getProblemMark() : marked.getContextMark();
            String problem = marked.getProblem() != null ? marked.getProblem() : marked.getContext();
            if (mark != null && problem != null) {
                return name + " line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1) + ": " + problem;
            }
        }
        String message = e.getMessage() == null ? e.toString() : e.getMessage().strip();
        int newline = message.indexOf('\n');
        return name + ": " + (newline < 0 ? message : message.substring(0, newline));
    }
}
