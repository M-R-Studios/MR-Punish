package com.mrstudios.punish.config;

import java.util.List;

/**
 * One menu item from {@code gui.yml}: what it looks like, before placeholders are filled in.
 *
 * @param material        an item material name (upper case), checked when loaded
 * @param name            MiniMessage
 * @param lore            MiniMessage lines
 * @param glint           show the enchantment glint
 * @param customModelData for resource packs, or null
 */
public record ItemTemplate(String material, String name, List<String> lore, boolean glint, Integer customModelData) {

    public ItemTemplate {
        lore = List.copyOf(lore);
    }

    /** This item with another name (the no-permission item takes the button's name). */
    public ItemTemplate withName(String newName) {
        return new ItemTemplate(material, newName, lore, glint, customModelData);
    }
}
