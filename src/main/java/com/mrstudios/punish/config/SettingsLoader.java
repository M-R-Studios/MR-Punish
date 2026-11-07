package com.mrstudios.punish.config;

import com.mrstudios.punish.config.Settings.CommandToggle;
import com.mrstudios.punish.punish.PunishmentType;
import com.mrstudios.punish.punish.Reason;
import com.mrstudios.punish.punish.ReasonRegistry;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Builds {@link Settings} from {@code config.yml}. Invalid values fall back to their defaults with a
 * warning; an invalid reason is disabled with a warning and the others still load. A type without an
 * enabled fallback reason fails the whole file.
 */
public final class SettingsLoader {

    /** Reason keys and aliases: lower-case letters, digits, - and _. Stored with each punishment. */
    private static final Pattern WORD = Pattern.compile("[a-z0-9_-]{1,32}");
    /** Permission nodes and command labels. */
    private static final Pattern PERMISSION = Pattern.compile("[a-z0-9_.-]{1,128}");
    private static final Pattern LABEL = Pattern.compile("[a-z0-9_-]{1,32}");

    private static final Set<String> TOP_LEVEL = Set.of("config-version", "escalation", "fallback-reasons", "reasons",
            "commands");
    private static final Set<String> REASON_FIELDS = Set.of("type", "display", "description", "icon", "slot", "aliases",
            "escalation", "history-period", "permission", "enabled");
    private static final Set<String> TOGGLE_FIELDS = Set.of("enabled", "aliases");
    private static final String DEFAULT_HISTORY_PERIOD = "90d";
    private static final String DEFAULT_ICON = "PAPER";
    /** Largest inventory (6 rows). */
    private static final int MAX_SLOT = 53;

    private final Predicate<String> isItem;
    private final Set<String> reservedLabels;
    private final Map<String, CommandToggle> commandDefaults;
    private final YamlConfiguration bundled;
    private final ConfigMigrator migrator = ConfigMigrator.forConfig();

    /**
     * @param isItem         whether a material name (upper case) names an item
     * @param toggleable     names of the commands {@code commands.*} can switch, in order
     * @param reservedLabels labels taken by commands that can't be switched ({@code mrpunish}...)
     * @param bundled        the {@code config.yml} in the jar
     */
    public SettingsLoader(Predicate<String> isItem, List<String> toggleable, Set<String> reservedLabels,
            YamlConfiguration bundled) {
        this.isItem = isItem;
        this.reservedLabels = Set.copyOf(reservedLabels);
        this.bundled = bundled;
        Map<String, CommandToggle> defaults = new LinkedHashMap<>();
        for (String name : toggleable) {
            defaults.put(name, CommandToggle.DEFAULT);
        }
        // The jar's own entries (e.g. extra aliases) are the defaults where they're valid.
        Map<String, CommandToggle> fromJar = readCommands(bundled, defaults, new ArrayList<>());
        defaults.putAll(fromJar);
        this.commandDefaults = defaults;
    }

    public static YamlConfiguration bundledDefaults() {
        return YamlFiles.bundled("config.yml");
    }

    /**
     * The settings from the jar's {@code config.yml}, used when the file can't be loaded at startup.
     *
     * @throws IllegalStateException if the jar's own file doesn't load (a build error)
     */
    public Settings defaults() {
        LoadResult<Settings> result = read(bundled);
        if (!result.ok()) {
            throw new IllegalStateException("The bundled config.yml doesn't load: " + result.error());
        }
        return result.value();
    }

    /** Reads {@code file}, migrating it on disk first if it is from an older version. */
    public LoadResult<Settings> load(File file, Consumer<String> log) {
        try {
            return read(YamlFiles.load(file, migrator, log));
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    /** Parses and validates YAML text. Migrations are applied in memory only. */
    public LoadResult<Settings> load(String text) {
        try {
            YamlConfiguration yaml = YamlFiles.parse(text, migrator.fileName());
            migrator.migrate(yaml);
            return read(yaml);
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    LoadResult<Settings> read(YamlConfiguration yaml) {
        List<String> warnings = new ArrayList<>();
        YamlReader r = new YamlReader(yaml, warnings);
        warnUnknownTopLevel(yaml, warnings);

        TimeSpan historyPeriod = r.duration("escalation.history-period", DEFAULT_HISTORY_PERIOD);
        warnUnknownIn(yaml, "escalation", Set.of("history-period"), warnings);

        Map<PunishmentType, String> fallbacks = new EnumMap<>(PunishmentType.class);
        ConfigurationSection fallbackSection = yaml.getConfigurationSection("fallback-reasons");
        if (fallbackSection != null) {
            for (String key : fallbackSection.getKeys(false)) {
                PunishmentType type = type(key);
                Object value = fallbackSection.get(key);
                if (type == null) {
                    warnings.add("fallback-reasons." + key + " is not a punishment type (" + types() + "), ignored");
                } else if (YamlReader.isScalar(value)) {
                    fallbacks.put(type, value.toString().strip());
                }
            }
        }

        List<Reason> reasons = readReasons(yaml, warnings);
        ReasonRegistry registry;
        try {
            registry = ReasonRegistry.build(reasons, fallbacks, warnings);
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }

        Map<String, CommandToggle> commands = readCommands(yaml, commandDefaults, warnings);
        return LoadResult.loaded(new Settings(historyPeriod, registry, commands), warnings);
    }

    private static PunishmentType type(String name) {
        for (PunishmentType type : PunishmentType.values()) {
            if (type.name().equalsIgnoreCase(name.strip())) {
                return type;
            }
        }
        return null;
    }

    private static String types() {
        return Arrays.stream(PunishmentType.values()).map(Enum::name).collect(Collectors.joining(", "));
    }

    private List<Reason> readReasons(YamlConfiguration yaml, List<String> warnings) {
        List<Reason> reasons = new ArrayList<>();
        ConfigurationSection section = yaml.getConfigurationSection("reasons");
        if (section == null) {
            warnings.add("reasons is missing or not a list of reasons, so there are no reasons");
            return reasons;
        }
        Set<String> seen = new HashSet<>();
        for (String rawKey : section.getKeys(false)) {
            String key = rawKey.toLowerCase(Locale.ROOT);
            String path = "reasons." + rawKey;
            if (!WORD.matcher(key).matches()) {
                warnings.add(path + ": '" + rawKey + "' is not a valid reason key (letters, digits, - and _,"
                        + " at most 32 characters), so this reason is disabled");
                continue;
            }
            if (!seen.add(key)) {
                warnings.add(path + ": the key '" + key + "' is used twice (keys are not case-sensitive), so the"
                        + " second one is disabled");
                continue;
            }
            ConfigurationSection reasonSection = section.getConfigurationSection(rawKey);
            if (reasonSection == null) {
                warnings.add(path + " is not a reason (it needs type, display, icon...), so it is disabled");
                continue;
            }
            Reason reason = readReason(key, reasonSection, path + ".", warnings);
            if (reason != null) {
                reasons.add(reason);
            }
        }
        return reasons;
    }

    /** One reason, or null if its type is unusable. Other problems disable it or use a default. */
    private Reason readReason(String key, ConfigurationSection section, String prefix, List<String> warnings) {
        YamlReader r = new YamlReader(section, prefix, warnings);
        for (String field : section.getKeys(false)) {
            if (!REASON_FIELDS.contains(field)) {
                warnings.add(prefix + field + " is not a reason setting (typo?), ignored");
            }
        }

        Object rawType = section.get("type");
        PunishmentType type = rawType instanceof String s ? type(s) : null;
        if (type == null) {
            warnings.add(prefix + "type: '" + rawType + "' is not one of " + types() + ", so this reason is disabled");
            return null;
        }
        List<String> problems = new ArrayList<>();

        String display = r.text("display", key, s -> !s.isEmpty(), "a name");
        String displayProblem = MiniMessageCheck.problem(display, List.of());
        if (displayProblem != null) {
            warnings.add(prefix + "display: " + displayProblem + ", using " + key);
            display = key;
        }

        List<String> description = new ArrayList<>();
        if (section.contains("description")) {
            for (String line : r.textList("description", List.of())) {
                String problem = MiniMessageCheck.problem(line, List.of());
                if (problem == null) {
                    description.add(line);
                } else {
                    warnings.add(prefix + "description: '" + line + "' " + problem + ", line skipped");
                }
            }
        }

        String icon = r.text("icon", DEFAULT_ICON, s -> !s.isEmpty(), "a material name").toUpperCase(Locale.ROOT);
        if (!isItem.test(icon)) {
            problems.add("icon '" + icon + "' is not an item");
        }

        int slot = -1;
        if (section.contains("slot")) {
            slot = r.wholeNumber("slot", 0, MAX_SLOT, -1);
        }

        List<String> aliases = new ArrayList<>();
        if (section.contains("aliases")) {
            for (String alias : r.textList("aliases", List.of())) {
                String word = alias.strip().toLowerCase(Locale.ROOT);
                if (!WORD.matcher(word).matches()) {
                    warnings.add(prefix + "aliases: '" + alias + "' is not a single word (letters, digits, - and _),"
                            + " skipped");
                } else if (!aliases.contains(word)) {
                    aliases.add(word);
                }
            }
        }

        List<TimeSpan> escalation = new ArrayList<>();
        if (type.hasDuration()) {
            Object raw = section.get("escalation");
            if (!(raw instanceof List<?> list) || list.isEmpty()) {
                problems.add("escalation must be a list of at least one duration, e.g. [\"1h\", \"1d\", \"permanent\"]");
            } else {
                for (int i = 0; i < list.size(); i++) {
                    Object step = list.get(i);
                    try {
                        if (!YamlReader.isScalar(step)) {
                            throw new IllegalArgumentException("'" + step + "' is not a duration");
                        }
                        escalation.add(DurationParser.parse(step.toString()));
                    } catch (IllegalArgumentException e) {
                        problems.add("escalation step " + (i + 1) + ": " + e.getMessage());
                        break;
                    }
                }
            }
        } else if (section.contains("escalation")) {
            warnings.add(prefix + "escalation is not used for WARN reasons, ignored");
        }

        TimeSpan historyPeriod = null;
        if (section.contains("history-period")) {
            Object raw = section.get("history-period");
            try {
                if (!YamlReader.isScalar(raw)) {
                    throw new IllegalArgumentException("'" + raw + "' is not a duration");
                }
                historyPeriod = DurationParser.parse(raw.toString());
            } catch (IllegalArgumentException e) {
                problems.add("history-period: " + e.getMessage());
            }
        }

        String permission = null;
        if (section.contains("permission")) {
            Object raw = section.get("permission");
            String node = YamlReader.isScalar(raw) ? raw.toString().strip().toLowerCase(Locale.ROOT) : "";
            if (PERMISSION.matcher(node).matches()) {
                permission = node;
            } else {
                // Ignoring it would let everyone use a reason that was meant to be restricted.
                problems.add("permission '" + raw + "' is not a permission node");
            }
        }

        boolean enabled = !section.contains("enabled") || r.bool("enabled", true);
        if (!problems.isEmpty()) {
            warnings.add(prefix.substring(0, prefix.length() - 1) + ": " + String.join("; ", problems)
                    + ", so this reason is disabled");
            enabled = false;
        }
        return new Reason(key, type, display, description, icon, slot, aliases, escalation, historyPeriod, permission,
                enabled);
    }

    /** {@code commands.*}: one toggle per known command; unknown entries and taken labels warn. */
    private Map<String, CommandToggle> readCommands(YamlConfiguration yaml, Map<String, CommandToggle> defaults,
            List<String> warnings) {
        Map<String, CommandToggle> commands = new LinkedHashMap<>();
        Object raw = yaml.get("commands");
        ConfigurationSection section = yaml.getConfigurationSection("commands");
        if (raw != null && section == null) {
            warnings.add("commands: '" + raw + "' is not a list of commands, using the defaults");
        }
        Set<String> taken = new HashSet<>(reservedLabels);
        taken.addAll(defaults.keySet());
        for (Map.Entry<String, CommandToggle> entry : defaults.entrySet()) {
            String name = entry.getKey();
            CommandToggle def = entry.getValue();
            String prefix = "commands." + name + ".";
            ConfigurationSection toggle = section == null ? null : section.getConfigurationSection(name);
            if (toggle == null) {
                if (section != null) {
                    warnings.add("commands." + name + " is missing, using enabled: " + def.enabled() + ", aliases: "
                            + def.aliases());
                }
                commands.put(name, withFreeAliases(def, taken, prefix, warnings));
                continue;
            }
            for (String field : toggle.getKeys(false)) {
                if (!TOGGLE_FIELDS.contains(field)) {
                    warnings.add(prefix + field + " is not a command setting (typo?), ignored");
                }
            }
            YamlReader r = new YamlReader(toggle, prefix, warnings);
            boolean enabled = r.bool("enabled", def.enabled());
            List<String> aliases = new ArrayList<>();
            for (String alias : r.textList("aliases", def.aliases())) {
                String label = alias.strip().toLowerCase(Locale.ROOT);
                if (!LABEL.matcher(label).matches()) {
                    warnings.add(prefix + "aliases: '" + alias + "' is not a command label (letters, digits, - and _),"
                            + " skipped");
                } else if (!aliases.contains(label)) {
                    aliases.add(label);
                }
            }
            commands.put(name, withFreeAliases(new CommandToggle(enabled, aliases), taken, prefix, warnings));
        }
        if (section != null) {
            for (String name : section.getKeys(false)) {
                if (!defaults.containsKey(name)) {
                    warnings.add("commands." + name + " is not an MR-Punish command that can be turned off, ignored");
                }
            }
        }
        return commands;
    }

    /** Drops aliases already used as a label by another command (the first one listed keeps it). */
    private static CommandToggle withFreeAliases(CommandToggle toggle, Set<String> taken, String prefix,
            List<String> warnings) {
        List<String> free = new ArrayList<>();
        for (String alias : toggle.aliases()) {
            if (taken.add(alias)) {
                free.add(alias);
            } else {
                warnings.add(prefix + "aliases: '" + alias + "' is already the label of another MR-Punish command,"
                        + " skipped");
            }
        }
        return free.size() == toggle.aliases().size() ? toggle : new CommandToggle(toggle.enabled(), free);
    }

    private static void warnUnknownTopLevel(YamlConfiguration yaml, List<String> warnings) {
        for (String key : yaml.getKeys(false)) {
            if (!TOP_LEVEL.contains(key)) {
                warnings.add(key + " is not a known setting (typo?), ignored");
            }
        }
    }

    private static void warnUnknownIn(YamlConfiguration yaml, String path, Set<String> known, List<String> warnings) {
        ConfigurationSection section = yaml.getConfigurationSection(path);
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            if (!known.contains(key)) {
                warnings.add(path + "." + key + " is not a known setting (typo?), ignored");
            }
        }
    }
}
