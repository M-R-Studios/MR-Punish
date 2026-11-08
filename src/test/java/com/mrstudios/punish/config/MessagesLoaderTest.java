package com.mrstudios.punish.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MessagesLoaderTest {

    /** Placeholders a message may use but the shipped text doesn't (titles are kept short). */
    private static final Set<String> OPTIONAL = Set.of("target.warn-title reason", "target.warn-title staff",
            "target.warn-subtitle staff", "target.warn-offline staff", "target.mute-chat staff");

    private final MessagesLoader loader = new MessagesLoader(MessagesLoader.bundledDefaults());

    /** The shipped messages.yml exactly as written. */
    private static String bundledText() {
        try (var in = MessagesLoaderTest.class.getResourceAsStream("/messages.yml")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace(String.valueOf((char) 13), "");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void shippedMessagesLoadWithoutWarnings() {
        LoadResult<Messages> result = loader.load(bundledText());
        assertTrue(result.ok(), result.error());
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void everyShippedMessageRendersWithItsPlaceholders() {
        Messages messages = loader.defaults();
        YamlConfiguration yaml = MessagesLoader.bundledDefaults();
        for (String key : yaml.getKeys(true)) {
            if (yaml.isConfigurationSection(key) || key.equals("messages-version") || yaml.isList(key)) {
                continue;
            }
            TagResolver[] filled = MessagesLoader.placeholders(key).stream()
                    .map(name -> Placeholder.unparsed(name, "[" + name + "]"))
                    .toArray(TagResolver[]::new);
            String text = plain(messages.component(key, filled));
            for (String name : MessagesLoader.placeholders(key)) {
                if (OPTIONAL.contains(key + " " + name)) {
                    continue;
                }
                assertTrue(text.contains("[" + name + "]"), key + " doesn't show <" + name + ">: " + text);
            }
            assertFalse(text.contains("<"), key + " has an unfilled tag: " + text);
        }
    }

    @Test
    void prefixIsInserted() {
        String text = plain(loader.defaults().component("general.no-permission"));
        assertEquals("MR-Punish » You don't have permission to do that.", text);
    }

    @Test
    void missingKeyFallsBackWithAWarning() {
        String text = bundledText().replaceAll("(?m)^  no-permission:.*\\n", "");
        LoadResult<Messages> result = loader.load(text);
        assertTrue(result.warnings().contains("general.no-permission is missing, using the default"),
                result.warnings().toString());
        assertTrue(result.value().raw("general.no-permission").contains("You don't have permission"));
    }

    @Test
    void wrongPlaceholderOrBadTagFallsBack() {
        String text = bundledText()
                .replace("Reloaded with <count> warning(s):", "Reloaded with <player> warning(s):")
                .replace("<red>You don't have permission", "<click:nope>You don't have permission");
        LoadResult<Messages> result = loader.load(text);
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith(
                "admin.reload-warnings: <player> is not a valid tag here")), result.warnings().toString());
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("general.no-permission: <click:nope>")),
                result.warnings().toString());
        assertTrue(result.value().raw("admin.reload-warnings").contains("<count>"));
    }

    @Test
    void escapedTagsAndEmptyMessagesAreFine() {
        String text = bundledText().replace("There are no MR-Punish commands you can use.", "Use \\\\<this>")
                .replaceAll("(?m)^  reload-success:.*$", "  reload-success: ''");
        LoadResult<Messages> result = loader.load(text);
        assertEquals(List.of(), result.warnings());
        assertEquals("", result.value().raw("admin.reload-success"));
    }

    @Test
    void unknownKeysWarn() {
        LoadResult<Messages> result = loader.load(bundledText() + "extra: hi\ngeneral2:\n  x: y\n");
        assertTrue(result.warnings().contains("extra is not a known message (typo?), ignored"));
        assertTrue(result.warnings().contains("general2 is not a known message (typo?), ignored"));
        assertFalse(result.warnings().contains("general2.x is not a known message (typo?), ignored"));
    }

    @Test
    void yamlErrorFails() {
        LoadResult<Messages> result = loader.load("prefix: 'unclosed\n");
        assertFalse(result.ok());
        assertTrue(result.error().startsWith("messages.yml"), result.error());
    }
}
