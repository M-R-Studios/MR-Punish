package com.mrstudios.punish.config;

import com.mrstudios.punish.rank.Rank;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Builds {@link RankSettings} from {@code permissions.yml}. A broken hierarchy (duplicate weights or
 * permissions, a bad weight, a missing permission, an unknown or cyclic {@code inherits}, a rank
 * granting another rank's node) fails the whole file: it's a security problem, not a cosmetic one.
 * Smaller problems (an unknown granted permission, a bad display name) only warn.
 */
public final class RankLoader {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern NODE = Pattern.compile("[a-z0-9_.-]{1,128}");
    private static final int MIN_WEIGHT = 1;
    private static final int MAX_WEIGHT = 1000;
    private static final Set<String> TOP_LEVEL = Set.of("permissions-version", "ranks");
    private static final Set<String> FIELDS = Set.of("permission", "weight", "display", "inherits", "grants");

    private final Predicate<String> permissionKnown;
    private final YamlConfiguration bundled;
    private final ConfigMigrator migrator = ConfigMigrator.forPermissions();

    /**
     * @param permissionKnown whether a permission is declared by MR-Punish or another plugin
     * @param bundled         the {@code permissions.yml} in the jar
     */
    public RankLoader(Predicate<String> permissionKnown, YamlConfiguration bundled) {
        this.permissionKnown = permissionKnown;
        this.bundled = bundled;
    }

    public static YamlConfiguration bundledDefaults() {
        return YamlFiles.bundled("permissions.yml");
    }

    /** The ranks from the jar's {@code permissions.yml}, used when the file can't be loaded at startup. */
    public RankSettings defaults() {
        LoadResult<RankSettings> result = read(bundled);
        if (!result.ok()) {
            throw new IllegalStateException("The bundled permissions.yml doesn't load: " + result.error());
        }
        return result.value();
    }

    /** Reads {@code file}, migrating it on disk first if it is from an older version. */
    public LoadResult<RankSettings> load(File file, Consumer<String> log) {
        try {
            return read(YamlFiles.load(file, migrator, log));
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    /** Parses and validates YAML text. Migrations are applied in memory only. */
    public LoadResult<RankSettings> load(String text) {
        try {
            YamlConfiguration yaml = YamlFiles.parse(text, migrator.fileName());
            migrator.migrate(yaml);
            return read(yaml);
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    /** A rank as written, before inheritance is resolved. */
    private record Raw(String name, String permission, int weight, String display, List<String> inherits,
            List<String> grants) {
    }

    LoadResult<RankSettings> read(YamlConfiguration yaml) {
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String key : yaml.getKeys(false)) {
            if (!TOP_LEVEL.contains(key)) {
                warnings.add(key + " is not a known setting (typo?), ignored");
            }
        }
        ConfigurationSection section = yaml.getConfigurationSection("ranks");
        if (section == null) {
            return fail(List.of("ranks is missing or not a list of ranks"));
        }

        Map<String, Raw> raws = new LinkedHashMap<>();
        for (String rawName : section.getKeys(false)) {
            String name = rawName.toLowerCase(Locale.ROOT);
            String path = "ranks." + rawName;
            ConfigurationSection rank = section.getConfigurationSection(rawName);
            if (!NAME.matcher(name).matches()) {
                errors.add(path + ": '" + rawName + "' is not a valid rank name (letters, digits, - and _)");
                continue;
            }
            if (raws.containsKey(name)) {
                errors.add(path + ": the rank name '" + name + "' is used twice (names are not case-sensitive)");
                continue;
            }
            if (rank == null) {
                errors.add(path + " is not a rank (it needs permission and weight)");
                continue;
            }
            Raw raw = readRank(name, rank, path + ".", warnings, errors);
            if (raw != null) {
                raws.put(name, raw);
            }
        }
        if (raws.isEmpty() && errors.isEmpty()) {
            warnings.add("ranks is empty: no player has a rank, so only console can punish anyone");
        }

        Map<Integer, String> weights = new HashMap<>();
        Map<String, String> permissions = new HashMap<>();
        for (Raw raw : raws.values()) {
            String other = weights.putIfAbsent(raw.weight(), raw.name());
            if (other != null) {
                errors.add("ranks." + raw.name() + ".weight: " + raw.weight() + " is also the weight of '" + other
                        + "'; every rank needs its own weight");
            }
            other = permissions.putIfAbsent(raw.permission(), raw.name());
            if (other != null) {
                errors.add("ranks." + raw.name() + ".permission: " + raw.permission() + " is also the permission of '"
                        + other + "'");
            }
        }
        for (Raw raw : raws.values()) {
            for (String parent : raw.inherits()) {
                Raw inherited = raws.get(parent);
                if (inherited == null) {
                    errors.add("ranks." + raw.name() + ".inherits: there is no rank '" + parent + "'");
                } else if (inherited.weight() >= raw.weight()) {
                    warnings.add("ranks." + raw.name() + ".inherits: '" + parent + "' has a weight of "
                            + inherited.weight() + ", not lower than " + raw.weight() + " (its grants are inherited anyway)");
                }
            }
            for (String grant : raw.grants()) {
                String owner = permissions.get(grant);
                if (owner != null) {
                    errors.add("ranks." + raw.name() + ".grants: " + grant + " is the permission of rank '" + owner
                            + "'; use inherits to give a rank another rank's grants");
                } else if (!permissionKnown.test(grant)) {
                    warnings.add("ranks." + raw.name() + ".grants: " + grant + " is not a permission of MR-Punish or"
                            + " another plugin (typo?); it's granted anyway");
                }
            }
        }
        if (errors.isEmpty()) {
            for (Raw raw : raws.values()) {
                String cycle = cycle(raw.name(), raws, new ArrayList<>());
                if (cycle != null) {
                    errors.add("ranks." + raw.name() + ".inherits: inheritance goes round in a circle (" + cycle + ")");
                    break;
                }
            }
        }
        if (!errors.isEmpty()) {
            return fail(errors);
        }

        List<Rank> ranks = new ArrayList<>();
        for (Raw raw : raws.values()) {
            Set<String> all = new LinkedHashSet<>();
            collectGrants(raw.name(), raws, all, new HashSet<>());
            ranks.add(new Rank(raw.name(), raw.permission(), raw.weight(), raw.display(), raw.inherits(), raw.grants(),
                    all));
        }
        return LoadResult.loaded(new RankSettings(ranks), warnings);
    }

    private static LoadResult<RankSettings> fail(List<String> errors) {
        return LoadResult.failed("permissions.yml: " + String.join("; ", errors)
                + ". A broken rank hierarchy is never used, so none of this file was applied.");
    }

    private Raw readRank(String name, ConfigurationSection rank, String prefix, List<String> warnings,
            List<String> errors) {
        for (String field : rank.getKeys(false)) {
            if (!FIELDS.contains(field)) {
                warnings.add(prefix + field + " is not a rank setting (typo?), ignored");
            }
        }
        YamlReader r = new YamlReader(rank, prefix, warnings);
        boolean ok = true;

        Object rawPermission = rank.get("permission");
        String permission = YamlReader.isScalar(rawPermission)
                ? rawPermission.toString().strip().toLowerCase(Locale.ROOT) : null;
        if (permission == null || !NODE.matcher(permission).matches()) {
            errors.add(prefix + "permission: " + (rawPermission == null ? "is missing" : "'" + rawPermission
                    + "' is not a permission node"));
            ok = false;
        }

        Object rawWeight = rank.get("weight");
        int weight = 0;
        if ((rawWeight instanceof Integer || rawWeight instanceof Long)
                && ((Number) rawWeight).longValue() >= MIN_WEIGHT && ((Number) rawWeight).longValue() <= MAX_WEIGHT) {
            weight = ((Number) rawWeight).intValue();
        } else {
            errors.add(prefix + "weight: " + (rawWeight == null ? "is missing" : "'" + rawWeight + "' is not")
                    + " a whole number from " + MIN_WEIGHT + " to " + MAX_WEIGHT);
            ok = false;
        }

        String display = r.text("display", name, s -> !s.isEmpty(), "a name");
        String problem = MiniMessageCheck.problem(display, List.of());
        if (problem != null) {
            warnings.add(prefix + "display: " + problem + ", using " + name);
            display = name;
        }

        List<String> inherits = new ArrayList<>();
        if (rank.contains("inherits")) {
            for (String parent : r.textList("inherits", List.of())) {
                inherits.add(parent.strip().toLowerCase(Locale.ROOT));
            }
        }
        if (inherits.contains(name)) {
            errors.add(prefix + "inherits: a rank can't inherit itself");
            ok = false;
        }

        List<String> grants = new ArrayList<>();
        if (rank.contains("grants")) {
            for (String grant : r.textList("grants", List.of())) {
                String node = grant.strip().toLowerCase(Locale.ROOT);
                if (!NODE.matcher(node).matches()) {
                    warnings.add(prefix + "grants: '" + grant + "' is not a permission node, skipped");
                } else if (!grants.contains(node)) {
                    grants.add(node);
                }
            }
        }
        return ok ? new Raw(name, permission, weight, display, inherits, grants) : null;
    }

    /** A description of the first inheritance cycle reachable from {@code name}, or null. */
    private static String cycle(String name, Map<String, Raw> raws, List<String> path) {
        if (path.contains(name)) {
            List<String> loop = new ArrayList<>(path.subList(path.indexOf(name), path.size()));
            loop.add(name);
            return String.join(" -> ", loop);
        }
        Raw raw = raws.get(name);
        if (raw == null) {
            return null;
        }
        path.add(name);
        for (String parent : raw.inherits()) {
            String found = cycle(parent, raws, path);
            if (found != null) {
                return found;
            }
        }
        path.remove(path.size() - 1);
        return null;
    }

    private static void collectGrants(String name, Map<String, Raw> raws, Set<String> into, Set<String> visited) {
        if (!visited.add(name)) {
            return;
        }
        Raw raw = raws.get(name);
        into.addAll(raw.grants());
        for (String parent : raw.inherits()) {
            // The inherited rank's own node too: mrpunish.admin is also the admin-tools permission, and
            // holding a lower rank's node never changes the rank (the heaviest node held wins).
            into.add(raws.get(parent).permission());
            collectGrants(parent, raws, into, visited);
        }
    }
}
