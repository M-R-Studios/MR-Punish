package com.mrstudios.punish.config;

import java.util.List;
import java.util.Map;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Every player-facing string from {@code messages.yml}, already validated. Keys are full paths
 * ({@code admin.reload-success}). Most are single texts; a few (lore and multi-line screens)
 * are lists of lines. Placeholder values are passed as
 * {@link Placeholder#unparsed} so names and input typed by players are never parsed as tags.
 */
public final class Messages {

    static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final Map<String, String> texts;
    private final Map<String, List<String>> lists;
    private final TagResolver prefix;

    Messages(Map<String, String> texts, Map<String, List<String>> lists) {
        this.texts = Map.copyOf(texts);
        this.lists = Map.copyOf(lists);
        this.prefix = Placeholder.component("prefix", MINI_MESSAGE.deserialize(this.texts.getOrDefault("prefix", "")));
    }

    /** The MiniMessage text of {@code key}, before placeholders are filled in. */
    public String raw(String key) {
        String text = texts.get(key);
        if (text == null) {
            throw new IllegalArgumentException("No message '" + key + "'");
        }
        return text;
    }

    /** The MiniMessage lines of the list {@code key}, before placeholders are filled in. */
    public List<String> rawLines(String key) {
        List<String> lines = lists.get(key);
        if (lines == null) {
            throw new IllegalArgumentException("No message list '" + key + "'");
        }
        return lines;
    }

    public Component component(String key, TagResolver... placeholders) {
        return render(raw(key), placeholders);
    }

    /** Every line of the list {@code key}, rendered. Empty lines are kept (useful for lore). */
    public List<Component> components(String key, TagResolver... placeholders) {
        return rawLines(key).stream().map(line -> render(line, placeholders)).toList();
    }

    /** Sends {@code key} to {@code to}. A message set to "" sends nothing. */
    public void send(Audience to, String key, TagResolver... placeholders) {
        String text = raw(key);
        if (!text.isEmpty()) {
            to.sendMessage(render(text, placeholders));
        }
    }

    /** Sends each line of the list {@code key}, skipping empty lines. */
    public void sendLines(Audience to, String key, TagResolver... placeholders) {
        for (String line : rawLines(key)) {
            if (!line.isEmpty()) {
                to.sendMessage(render(line, placeholders));
            }
        }
    }

    private Component render(String text, TagResolver... placeholders) {
        return MINI_MESSAGE.deserialize(text, TagResolver.resolver(prefix, TagResolver.resolver(placeholders)));
    }
}
