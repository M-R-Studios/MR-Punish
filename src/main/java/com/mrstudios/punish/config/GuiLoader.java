package com.mrstudios.punish.config;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Builds {@link GuiSettings} from {@code gui.yml}. The jar's file is the schema: every key it has is
 * read with the same kind of value, and a missing or invalid one falls back to the jar's with a
 * warning naming the key. A section with a {@code material} is an item. Slots must fit their menu's
 * rows. Unknown keys warn.
 */
public final class GuiLoader {

    private static final Set<String> ITEM_FIELDS = Set.of("material", "name", "lore", "glint", "custom-model-data");
    private static final Set<String> SOUND_FIELDS = Set.of("enabled", "sound", "volume", "pitch");
    private static final int MAX_SLOT = 53;

    private final Predicate<String> isItem;
    private final Predicate<NamespacedKey> soundExists;
    private final YamlConfiguration bundled;
    private final ConfigMigrator migrator = ConfigMigrator.forGui();

    public GuiLoader(Predicate<String> isItem, Predicate<NamespacedKey> soundExists, YamlConfiguration bundled) {
        this.isItem = isItem;
        this.soundExists = soundExists;
        this.bundled = bundled;
    }

    /** Accepts every material and sound (tests without a server). */
    public GuiLoader() {
        this(name -> true, key -> true, bundledDefaults());
    }

    public static YamlConfiguration bundledDefaults() {
        return YamlFiles.bundled("gui.yml");
    }

    /** The jar's menus, used when {@code gui.yml} can't be loaded at startup. */
    public GuiSettings defaults() {
        return read(bundled).value();
    }

    /** Reads {@code file}, migrating it on disk first if it is from an older version. */
    public LoadResult<GuiSettings> load(File file, Consumer<String> log) {
        try {
            return read(YamlFiles.load(file, migrator, log));
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    /** Parses and validates YAML text. Migrations are applied in memory only. */
    public LoadResult<GuiSettings> load(String text) {
        try {
            YamlConfiguration yaml = YamlFiles.parse(text, migrator.fileName());
            migrator.migrate(yaml);
            return read(yaml);
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    private static boolean isItemSection(ConfigurationSection section) {
        return section != null && section.contains("material");
    }

    LoadResult<GuiSettings> read(YamlConfiguration yaml) {
        List<String> warnings = new ArrayList<>();
        Map<String, ItemTemplate> items = new HashMap<>();
        Map<String, String> texts = new HashMap<>();
        Map<String, List<String>> lists = new HashMap<>();
        Map<String, Integer> numbers = new HashMap<>();
        Map<String, List<Integer>> slotLists = new HashMap<>();
        Map<String, Settings.SoundSetting> sounds = new HashMap<>();

        // Sounds.
        ConfigurationSection defaultSounds = bundled.getConfigurationSection("sounds");
        for (String name : defaultSounds.getKeys(false)) {
            sounds.put(name, sound(yaml, "sounds." + name, warnings));
        }

        // Everything else, walking the jar's file.
        for (String key : bundled.getKeys(true)) {
            if (key.equals(migrator.versionKey()) || key.startsWith("sounds")) {
                continue;
            }
            if (bundled.isConfigurationSection(key)) {
                if (isItemSection(bundled.getConfigurationSection(key))) {
                    items.put(key, item(yaml, key, warnings));
                }
                continue;
            }
            String parent = key.contains(".") ? key.substring(0, key.lastIndexOf('.')) : "";
            String field = key.substring(key.lastIndexOf('.') + 1);
            if (!parent.isEmpty() && isItemSection(bundled.getConfigurationSection(parent))
                    && ITEM_FIELDS.contains(field)) {
                continue; // read as part of the item
            }
            Object def = bundled.get(key);
            if (def instanceof Integer n) {
                numbers.put(key, number(yaml, key, n, warnings));
            } else if (def instanceof String s) {
                texts.put(key, text(yaml, key, s, warnings));
            } else if (def instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Integer) {
                slotLists.put(key, slotList(yaml, key, bundled.getIntegerList(key), warnings));
            } else if (def instanceof List<?>) {
                lists.put(key, textList(yaml, key, bundled.getStringList(key), warnings));
            }
        }

        GuiSettings.NoPermissionMode mode = GuiSettings.NoPermissionMode.GREY;
        String rawMode = texts.getOrDefault("no-permission.mode", "GREY").strip().toUpperCase(Locale.ROOT);
        try {
            mode = GuiSettings.NoPermissionMode.valueOf(rawMode);
        } catch (IllegalArgumentException e) {
            warnings.add("no-permission.mode: '" + rawMode + "' is not GREY or HIDE, using GREY");
        }

        checkSlots(numbers, slotLists, items, warnings);
        warnUnknown(yaml, warnings);
        return LoadResult.loaded(new GuiSettings(items, texts, lists, numbers, slotLists, mode, sounds), warnings);
    }

    private ItemTemplate item(YamlConfiguration yaml, String path, List<String> warnings) {
        ConfigurationSection def = bundled.getConfigurationSection(path);
        ItemTemplate fallback = new ItemTemplate(def.getString("material").toUpperCase(Locale.ROOT),
                def.getString("name", ""), def.getStringList("lore"), def.getBoolean("glint", false),
                def.contains("custom-model-data") ? def.getInt("custom-model-data") : null);
        ConfigurationSection section = yaml.getConfigurationSection(path);
        if (section == null) {
            warnings.add(path + " is missing, using the default item");
            return fallback;
        }
        String material = section.getString("material", fallback.material()).strip().toUpperCase(Locale.ROOT);
        if (!isItem.test(material)) {
            warnings.add(path + ".material: '" + material + "' is not an item, using " + fallback.material());
            material = fallback.material();
        }
        Object name = section.get("name");
        String itemName = name == null ? fallback.name() : name.toString();
        List<String> lore = fallback.lore();
        if (section.contains("lore")) {
            if (section.isList("lore")) {
                lore = section.getStringList("lore");
            } else {
                warnings.add(path + ".lore is not a list of lines, using the default");
            }
        }
        boolean glint = section.contains("glint") ? section.getBoolean("glint") : fallback.glint();
        Integer model = section.contains("custom-model-data") ? Integer.valueOf(section.getInt("custom-model-data"))
                : fallback.customModelData();
        return new ItemTemplate(material, itemName, lore, glint, model);
    }

    private static int number(YamlConfiguration yaml, String path, int def, List<String> warnings) {
        Object value = yaml.get(path);
        if (value == null) {
            warnings.add(path + " is missing, using " + def);
            return def;
        }
        if (!(value instanceof Integer n)) {
            warnings.add(path + ": '" + value + "' is not a whole number, using " + def);
            return def;
        }
        boolean rows = path.endsWith(".rows");
        if (rows ? n < 1 || n > 6 : n < 0 || n > MAX_SLOT) {
            warnings.add(path + ": " + n + " is not " + (rows ? "1-6 rows" : "a slot from 0 to " + MAX_SLOT) + ", using "
                    + def);
            return def;
        }
        return n;
    }

    private static String text(YamlConfiguration yaml, String path, String def, List<String> warnings) {
        Object value = yaml.get(path);
        if (value == null) {
            warnings.add(path + " is missing, using the default");
            return def;
        }
        if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) {
            warnings.add(path + " is not text, using the default");
            return def;
        }
        return value.toString();
    }

    private static List<String> textList(YamlConfiguration yaml, String path, List<String> def, List<String> warnings) {
        if (!yaml.contains(path)) {
            warnings.add(path + " is missing, using the default");
            return def;
        }
        if (!yaml.isList(path)) {
            warnings.add(path + " is not a list, using the default");
            return def;
        }
        return yaml.getStringList(path);
    }

    private static List<Integer> slotList(YamlConfiguration yaml, String path, List<Integer> def,
            List<String> warnings) {
        if (!yaml.isList(path)) {
            warnings.add(path + (yaml.contains(path) ? " is not a list of slots" : " is missing") + ", using the default");
            return def;
        }
        List<Integer> slots = new ArrayList<>();
        for (Object entry : yaml.getList(path)) {
            if (entry instanceof Integer n && n >= 0 && n <= MAX_SLOT) {
                slots.add(n);
            } else {
                warnings.add(path + ": '" + entry + "' is not a slot from 0 to " + MAX_SLOT + ", skipped");
            }
        }
        return slots.isEmpty() ? def : slots;
    }

    private Settings.SoundSetting sound(YamlConfiguration yaml, String path, List<String> warnings) {
        ConfigurationSection def = bundled.getConfigurationSection(path);
        NamespacedKey defKey = NamespacedKey.fromString(def.getString("sound"));
        Settings.SoundSetting fallback = new Settings.SoundSetting(def.getBoolean("enabled"), defKey,
                (float) def.getDouble("volume"), (float) def.getDouble("pitch"));
        ConfigurationSection section = yaml.getConfigurationSection(path);
        if (section == null) {
            warnings.add(path + " is missing, using the default sound");
            return fallback;
        }
        boolean enabled = section.getBoolean("enabled", fallback.enabled());
        NamespacedKey key = defKey;
        String raw = section.getString("sound");
        NamespacedKey parsed = raw == null ? null : NamespacedKey.fromString(raw.strip());
        if (parsed == null || !soundExists.test(parsed)) {
            if (raw != null) {
                warnings.add(path + ".sound: '" + raw + "' is not a known sound, using " + defKey);
            }
        } else {
            key = parsed;
        }
        double volume = section.getDouble("volume", fallback.volume());
        double pitch = section.getDouble("pitch", fallback.pitch());
        if (volume < 0 || volume > 10) {
            warnings.add(path + ".volume: " + volume + " is not 0.0-10.0, using " + fallback.volume());
            volume = fallback.volume();
        }
        if (pitch < 0.5 || pitch > 2) {
            warnings.add(path + ".pitch: " + pitch + " is not 0.5-2.0, using " + fallback.pitch());
            pitch = fallback.pitch();
        }
        return new Settings.SoundSetting(enabled, key, (float) volume, (float) pitch);
    }

    /** Every slot of a menu must fit its rows; one that doesn't goes back to the jar's (which does). */
    private void checkSlots(Map<String, Integer> numbers, Map<String, List<Integer>> slotLists,
            Map<String, ItemTemplate> items, List<String> warnings) {
        for (Map.Entry<String, Integer> entry : Map.copyOf(numbers).entrySet()) {
            String path = entry.getKey();
            if (!path.endsWith("slot")) {
                continue;
            }
            String menu = path.substring(0, path.indexOf('.'));
            Integer rows = numbers.get(menu + ".rows");
            if (rows != null && entry.getValue() >= rows * 9) {
                int def = bundled.getInt(path);
                warnings.add(path + ": slot " + entry.getValue() + " doesn't fit the " + rows + " rows of " + menu
                        + ", using " + def);
                numbers.put(path, def);
            }
        }
        for (Map.Entry<String, List<Integer>> entry : Map.copyOf(slotLists).entrySet()) {
            String menu = entry.getKey().substring(0, entry.getKey().indexOf('.'));
            Integer rows = numbers.get(menu + ".rows");
            if (rows != null) {
                List<Integer> fit = entry.getValue().stream().filter(s -> s < rows * 9).toList();
                if (fit.size() != entry.getValue().size()) {
                    warnings.add(entry.getKey() + ": slots that don't fit the " + rows + " rows were skipped");
                    slotLists.put(entry.getKey(), fit.isEmpty() ? bundled.getIntegerList(entry.getKey()) : fit);
                }
            }
        }
    }

    private void warnUnknown(YamlConfiguration yaml, List<String> warnings) {
        List<String> unknown = new ArrayList<>();
        for (String key : yaml.getKeys(true)) {
            if (bundled.contains(key) || unknown.stream().anyMatch(u -> key.startsWith(u + "."))) {
                continue;
            }
            String parent = key.contains(".") ? key.substring(0, key.lastIndexOf('.')) : "";
            String field = key.substring(key.lastIndexOf('.') + 1);
            boolean itemField = !parent.isEmpty() && isItemSection(bundled.getConfigurationSection(parent))
                    && ITEM_FIELDS.contains(field);
            boolean soundField = parent.startsWith("sounds.") && SOUND_FIELDS.contains(field);
            if (!itemField && !soundField) {
                unknown.add(key);
                warnings.add(key + " is not a known setting (typo?), ignored");
            }
        }
    }
}
