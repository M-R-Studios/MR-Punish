package com.mrstudios.punish.gui;

import com.mrstudios.punish.config.ItemTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Builds menu items from {@code gui.yml} templates. Every item is new and marked
 * {@code mrpunish:display}, so one that ever leaves a menu (a bug or an exploit) is recognised and
 * removed by {@link DisplayGuard} (DESIGN.md 10.1).
 */
public final class Items {

    public static final String NAMESPACE = "mrpunish";
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final NamespacedKey key = new NamespacedKey(NAMESPACE, "display");

    public boolean isDisplay(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /**
     * An item from {@code template}. A lore line that is exactly {@code <name>} for a key of
     * {@code multi} is replaced by those lines (none removes the line), so lists such as previous
     * offences or a reason's description can have any length.
     */
    public ItemStack build(ItemTemplate template, TagResolver placeholders, Map<String, List<Component>> multi) {
        Material material = Material.matchMaterial(template.material());
        ItemStack item = new ItemStack(material == null || !material.isItem() ? Material.STONE : material);
        return decorate(item, template, placeholders, multi);
    }

    public ItemStack build(ItemTemplate template, TagResolver placeholders) {
        return build(template, placeholders, Map.of());
    }

    /** A player head for {@code uuid} (no remote lookup: textures if the server has them cached). */
    public ItemStack head(UUID uuid, String name, ItemTemplate template, TagResolver placeholders,
            Map<String, List<Component>> multi) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta skull) {
            skull.setPlayerProfile(Bukkit.createProfile(uuid, name));
            item.setItemMeta(skull);
        }
        return decorate(item, template, placeholders, multi);
    }

    private ItemStack decorate(ItemStack item, ItemTemplate template, TagResolver placeholders,
            Map<String, List<Component>> multi) {
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(MINI.deserialize(template.name(), placeholders)));
        List<Component> lore = new ArrayList<>();
        for (String line : template.lore()) {
            String trimmed = line.strip();
            if (trimmed.startsWith("<") && trimmed.endsWith(">") && multi.containsKey(trimmed.substring(1,
                    trimmed.length() - 1))) {
                multi.get(trimmed.substring(1, trimmed.length() - 1)).forEach(c -> lore.add(plain(c)));
            } else {
                lore.add(plain(MINI.deserialize(line, placeholders)));
            }
        }
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        if (template.glint()) {
            meta.setEnchantmentGlintOverride(true);
        }
        if (template.customModelData() != null) {
            setModel(meta, template.customModelData());
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    @SuppressWarnings("deprecation")
    private static void setModel(ItemMeta meta, int model) {
        meta.setCustomModelData(model);
    }

    /**
     * A copy of a real item for a view-only menu (invsee, endersee), marked as a display item so a
     * copy that ever got out would be removed. Null for empty slots.
     */
    public ItemStack displayCopy(ItemStack source) {
        if (source == null || source.getType().isAir()) {
            return null;
        }
        ItemStack copy = source.clone();
        ItemMeta meta = copy.getItemMeta();
        if (meta == null) {
            return copy;
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        copy.setItemMeta(meta);
        return copy;
    }

    /** MiniMessage text for menus: not italic unless asked for. */
    public static Component render(String text, TagResolver placeholders) {
        return plain(MINI.deserialize(text, placeholders));
    }

    private static Component plain(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
