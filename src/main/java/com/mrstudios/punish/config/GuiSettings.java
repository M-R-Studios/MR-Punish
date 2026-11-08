package com.mrstudios.punish.config;

import java.util.List;
import java.util.Map;

/**
 * Everything from {@code gui.yml}, validated against the jar's copy: every key the jar has is here
 * (a bad or missing value is the jar's value), so menus can't hit a missing setting. Immutable: a
 * reload builds a new one. Keys are full paths such as {@code punish-menu.head.slot}.
 */
public record GuiSettings(Map<String, ItemTemplate> items, Map<String, String> texts, Map<String, List<String>> lists,
        Map<String, Integer> numbers, Map<String, List<Integer>> slotLists, NoPermissionMode noPermissionMode,
        Map<String, Settings.SoundSetting> sounds) {

    /** What staff see for a button they may not use. */
    public enum NoPermissionMode {
        GREY,
        HIDE
    }

    public GuiSettings {
        items = Map.copyOf(items);
        texts = Map.copyOf(texts);
        lists = Map.copyOf(lists);
        numbers = Map.copyOf(numbers);
        slotLists = Map.copyOf(slotLists);
        sounds = Map.copyOf(sounds);
    }

    public ItemTemplate item(String path) {
        return require(items, path);
    }

    public String text(String path) {
        return require(texts, path);
    }

    public List<String> lines(String path) {
        return require(lists, path);
    }

    public int number(String path) {
        return require(numbers, path);
    }

    public List<Integer> slots(String path) {
        return require(slotLists, path);
    }

    public Settings.SoundSetting sound(String name) {
        return require(sounds, name);
    }

    public boolean has(String path) {
        return items.containsKey(path) || texts.containsKey(path) || numbers.containsKey(path);
    }

    private static <T> T require(Map<String, T> map, String path) {
        T value = map.get(path);
        if (value == null) {
            throw new IllegalArgumentException("No gui.yml setting '" + path + "'");
        }
        return value;
    }
}
