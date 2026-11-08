package com.mrstudios.punish.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * DESIGN.md 5.4: no message can show an IP. Every message in messages.yml is rendered with every
 * placeholder it accepts filled, and nothing IP- or hash-shaped may come out. No placeholder is
 * ever an address: the only IP-related value messages get is {@code <accounts>}, a count.
 */
class MessageLeakTest {

    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b");
    private static final Pattern IPV6 = Pattern.compile("\\b[0-9a-fA-F]{1,4}(:[0-9a-fA-F]{0,4}){2,7}\\b");
    private static final Pattern HASH = Pattern.compile("\\b[0-9a-f]{32,}\\b");

    @Test
    void noMessageShowsAnIpOrAHash() {
        MessagesLoader loader = new MessagesLoader(MessagesLoader.bundledDefaults());
        Messages messages = loader.defaults();
        YamlConfiguration yaml = MessagesLoader.bundledDefaults();
        int checked = 0;
        for (String key : yaml.getKeys(true)) {
            if (yaml.isConfigurationSection(key) || key.equals("messages-version")) {
                continue;
            }
            List<TagResolver> values = new ArrayList<>();
            for (String name : MessagesLoader.placeholders(key)) {
                values.add(name.equals("appeal-click") ? TagResolver.empty() : Placeholder.unparsed(name, sample(name)));
            }
            TagResolver[] filled = values.toArray(TagResolver[]::new);
            List<String> rendered = new ArrayList<>();
            if (yaml.isList(key)) {
                messages.components(key, filled).forEach(c -> rendered.add(plain(c)));
            } else {
                rendered.add(plain(messages.component(key, filled)));
            }
            for (String text : rendered) {
                assertFalse(IPV4.matcher(text).find(), key + " shows something IPv4-shaped: " + text);
                assertFalse(IPV6.matcher(text).find(), key + " shows something IPv6-shaped: " + text);
                assertFalse(HASH.matcher(text).find(), key + " shows something hash-shaped: " + text);
            }
            checked++;
        }
        assertTrue(checked > 60, "every message was rendered (" + checked + ")");
        for (String key : yaml.getKeys(true)) {
            for (String name : MessagesLoader.placeholders(key)) {
                assertFalse(java.util.Set.of("ip", "ip-address", "address", "hash", "ip-hash", "host").contains(name),
                        key + " takes an address-like placeholder <" + name + ">; only <accounts> (a count) is allowed");
            }
        }
    }

    /** A realistic value for each placeholder: names, counts, dates, durations. Never an address. */
    private static String sample(String name) {
        return switch (name) {
            case "accounts", "count", "offence", "players", "ips", "skipped", "unknown", "id", "old-id" -> "3";
            case "date", "expires", "end" -> "2026-11-08 18:39";
            case "duration", "remaining" -> "6d 23h";
            case "appeal-link" -> "https://discord.gg/example";
            default -> "Steve";
        };
    }

    private static String plain(net.kyori.adventure.text.Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }
}
