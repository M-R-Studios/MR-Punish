package com.mrstudios.punish.config;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Builds {@link Messages} from {@code messages.yml}. A missing key, or a message with a tag
 * MiniMessage can't use, falls back to the text bundled in the jar with a warning naming the key.
 */
public final class MessagesLoader {

    private static final String VERSION_KEY = "messages-version";

    /**
     * Placeholders each message may use besides {@code <prefix>} and the standard MiniMessage tags.
     * Messages not listed take none.
     */
    private static final Map<String, Set<String>> PLACEHOLDERS = Map.ofEntries(
            Map.entry("admin.version", Set.of("version", "server", "storage", "storage-status", "clashes")),
            Map.entry("admin.reload-warnings", Set.of("count")),
            Map.entry("admin.reload-failed", Set.of("file")),
            Map.entry("admin.reload-detail", Set.of("detail")),
            Map.entry("admin.reload-restart-needed", Set.of("what")));

    private final YamlConfiguration defaults;
    private final ConfigMigrator migrator = ConfigMigrator.forMessages();

    /** @param defaults the {@code messages.yml} bundled in the jar */
    public MessagesLoader(YamlConfiguration defaults) {
        this.defaults = defaults;
    }

    public static YamlConfiguration bundledDefaults() {
        return YamlFiles.bundled("messages.yml");
    }

    /** The placeholder names {@code key} may use (for tests and tools). */
    static Set<String> placeholders(String key) {
        return PLACEHOLDERS.getOrDefault(key, Set.of());
    }

    /** The bundled messages, used when {@code messages.yml} can't be loaded at startup. */
    public Messages defaults() {
        return read(defaults).value();
    }

    /** Reads {@code file}, migrating it on disk first if it is from an older version. */
    public LoadResult<Messages> load(File file, Consumer<String> log) {
        try {
            return read(YamlFiles.load(file, migrator, log));
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    /** Parses and validates YAML text. Migrations are applied in memory only. */
    public LoadResult<Messages> load(String text) {
        try {
            YamlConfiguration yaml = YamlFiles.parse(text, migrator.fileName());
            migrator.migrate(yaml);
            return read(yaml);
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    LoadResult<Messages> read(YamlConfiguration yaml) {
        List<String> warnings = new ArrayList<>();
        Map<String, String> texts = new HashMap<>();
        Map<String, List<String>> lists = new HashMap<>();
        for (String key : defaults.getKeys(true)) {
            if (key.equals(VERSION_KEY) || defaults.isConfigurationSection(key)) {
                continue;
            }
            if (defaults.isList(key)) {
                lists.put(key, readLines(yaml, key, warnings));
            } else {
                texts.put(key, readText(yaml, key, warnings));
            }
        }
        for (String key : yaml.getKeys(true)) {
            if (defaults.contains(key)) {
                continue;
            }
            int dot = key.lastIndexOf('.');
            // Below an unknown key or a known message: already reported once.
            if (dot >= 0 && !defaults.isConfigurationSection(key.substring(0, dot))) {
                continue;
            }
            warnings.add(key + " is not a known message (typo?), ignored");
        }
        return LoadResult.loaded(new Messages(texts, lists), warnings);
    }

    private String readText(YamlConfiguration yaml, String key, List<String> warnings) {
        String def = defaults.getString(key);
        Object value = yaml.get(key);
        if (value == null) {
            warnings.add(key + " is missing, using the default");
            return def;
        }
        if (!(value instanceof String text)) {
            warnings.add(key + ": '" + value + "' is not text, using the default");
            return def;
        }
        String problem = problem(key, text);
        if (problem != null) {
            warnings.add(key + ": " + problem + ", using the default");
            return def;
        }
        return text;
    }

    private List<String> readLines(YamlConfiguration yaml, String key, List<String> warnings) {
        List<String> def = defaults.getStringList(key);
        Object value = yaml.get(key);
        if (value == null) {
            warnings.add(key + " is missing, using the default");
            return def;
        }
        if (!(value instanceof List<?> list)) {
            warnings.add(key + " is not a list of lines, using the default");
            return def;
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String problem = list.get(i) instanceof String line ? problem(key, line) : "is not text";
            if (problem != null) {
                warnings.add(key + " line " + (i + 1) + ": " + problem + ", using the default " + key);
                return def;
            }
            lines.add((String) list.get(i));
        }
        return lines;
    }

    /** Why {@code text} can't be used for {@code key}, or null if it's fine. */
    static String problem(String key, String text) {
        Set<String> allowed = new HashSet<>(placeholders(key));
        if (!key.equals("prefix")) {
            allowed.add("prefix");
        }
        return MiniMessageCheck.problem(text, allowed);
    }
}
