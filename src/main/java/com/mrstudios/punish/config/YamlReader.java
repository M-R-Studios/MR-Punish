package com.mrstudios.punish.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Reads typed values from a YAML section. Every missing or invalid value falls back to its default
 * and adds a warning that names the key, the bad value and what's accepted.
 */
final class YamlReader {

    private final ConfigurationSection yaml;
    private final String prefix;
    final List<String> warnings;

    YamlReader(ConfigurationSection yaml, List<String> warnings) {
        this(yaml, "", warnings);
    }

    /** @param prefix put in front of every path in warnings, e.g. {@code reasons.spam.} */
    YamlReader(ConfigurationSection yaml, String prefix, List<String> warnings) {
        this.yaml = yaml;
        this.prefix = prefix;
        this.warnings = warnings;
    }

    String full(String path) {
        return prefix + path;
    }

    Object value(String path, Object def) {
        Object value = yaml.get(path);
        if (value == null) {
            warnings.add(full(path) + " is missing, using " + def);
        }
        return value;
    }

    void invalid(String path, Object value, String accepted, Object def) {
        warnings.add(full(path) + ": '" + value + "' is not " + accepted + ", using " + def);
    }

    static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    boolean bool(String path, boolean def) {
        Object value = value(path, def);
        if (value == null) {
            return def;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        invalid(path, value, "true or false", def);
        return def;
    }

    int wholeNumber(String path, int min, int max, int def) {
        Object value = value(path, def);
        if (value == null) {
            return def;
        }
        if (value instanceof Integer || value instanceof Long) {
            long n = ((Number) value).longValue();
            if (n >= min && n <= max) {
                return (int) n;
            }
        }
        String accepted = max == Integer.MAX_VALUE
                ? "a whole number of at least " + min
                : "a whole number from " + min + " to " + max;
        invalid(path, value, accepted, def);
        return def;
    }

    /** Text, trimmed. Numbers and booleans are accepted as text. */
    String text(String path, String def, Predicate<String> valid, String accepted) {
        Object value = value(path, "\"" + def + "\"");
        if (value == null) {
            return def;
        }
        if (isScalar(value)) {
            String s = value.toString().strip();
            if (valid.test(s)) {
                return s;
            }
        }
        invalid(path, value, accepted, "\"" + def + "\"");
        return def;
    }

    /** A duration such as {@code 90d}; {@code def} is its text form, which must be valid. */
    TimeSpan duration(String path, String def) {
        TimeSpan fallback = DurationParser.parse(def);
        Object value = value(path, def);
        if (value == null) {
            return fallback;
        }
        try {
            if (isScalar(value)) {
                return DurationParser.parse(value.toString());
            }
        } catch (IllegalArgumentException e) {
            warnings.add(full(path) + ": " + e.getMessage() + ", using " + def);
            return fallback;
        }
        invalid(path, value, "a duration", def);
        return fallback;
    }

    <E extends Enum<E>> E choice(String path, Class<E> type, E def) {
        Object value = value(path, def);
        if (value == null) {
            return def;
        }
        if (value instanceof String s) {
            for (E option : type.getEnumConstants()) {
                if (option.name().equalsIgnoreCase(s.strip())) {
                    return option;
                }
            }
        }
        String options = java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name)
                .collect(java.util.stream.Collectors.joining(" or "));
        invalid(path, value, options, def);
        return def;
    }

    /** A section of name: value pairs (free-form names). */
    java.util.Map<String, String> properties(String path, java.util.Map<String, String> def) {
        Object value = value(path, def);
        if (value == null) {
            return def;
        }
        if (!(value instanceof ConfigurationSection section)) {
            invalid(path, value, "a list of name: value pairs", def);
            return def;
        }
        java.util.Map<String, String> properties = new java.util.LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object entry = section.get(key);
            if (isScalar(entry)) {
                properties.put(key, entry.toString());
            } else {
                warnings.add(full(path) + "." + key + ": '" + entry + "' is not a single value, skipped");
            }
        }
        return properties;
    }

    /** A list of single values as text; anything else in the list is skipped with a warning. */
    List<String> textList(String path, List<String> def) {
        Object value = value(path, def);
        if (value == null) {
            return def;
        }
        if (!(value instanceof List<?> list)) {
            invalid(path, value, "a list", def);
            return def;
        }
        List<String> out = new ArrayList<>();
        for (Object entry : list) {
            if (isScalar(entry)) {
                out.add(entry.toString());
            } else {
                warnings.add(full(path) + ": '" + entry + "' is not a single value, skipped");
            }
        }
        return out;
    }
}
