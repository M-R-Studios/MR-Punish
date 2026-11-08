package com.mrstudios.punish.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.enforce.CommandLabelResolver.Known;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** DESIGN.md 7.8 / X18: a muted player can't reach a blocked command by any other name. */
class CommandLabelResolverTest {

    private static final Set<String> BLOCKED = Set.of("msg", "tell", "w", "r", "me", "mail");

    /** A fake command map: vanilla msg with tell/w, Essentials' msg with many aliases, and help. */
    private static final Map<String, Known> MAP = Map.ofEntries(
            Map.entry("msg", new Known("msg", "msg", List.of("tell", "w"))),
            Map.entry("minecraft:msg", new Known("msg", "msg", List.of("tell", "w"))),
            Map.entry("tell", new Known("msg", "msg", List.of("tell", "w"))),
            Map.entry("minecraft:tell", new Known("msg", "msg", List.of("tell", "w"))),
            Map.entry("essentials:msg", new Known("msg", "msg", List.of("whisper", "pm", "emsg"))),
            Map.entry("emsg", new Known("msg", "emsg", List.of("whisper", "pm", "emsg", "msg"))),
            Map.entry("whisper", new Known("msg", "whisper", List.of("pm", "emsg"))),
            Map.entry("help", new Known("help", "help", List.of("?"))),
            Map.entry("dmme", new Known("dmme", "dmme", List.of())));

    /** commands.yml aliases: whisperto -> msg; chain -> whisperto (two levels); loop <-> loop2; tp -> teleport. */
    private static final Map<String, List<String>> ALIASES = Map.of(
            "whisperto", List.of("msg $1-"),
            "chain", List.of("whisperto $1-"),
            "loop", List.of("loop2 $1-"),
            "loop2", List.of("loop $1-"),
            "spawn", List.of("tp $1 0 64 0"));

    private static final CommandLabelResolver.Lookup LOOKUP = new CommandLabelResolver.Lookup() {
        @Override
        public Optional<Known> command(String label) {
            return Optional.ofNullable(MAP.get(label));
        }

        @Override
        public List<String> aliasTargets(String label) {
            return ALIASES.getOrDefault(label, List.of());
        }
    };

    private static boolean blocked(String message) {
        return CommandLabelResolver.blocked(message, BLOCKED, LOOKUP);
    }

    @Test
    void theCommandAndItsVanillaAliases() {
        assertTrue(blocked("/msg Steve hi"));
        assertTrue(blocked("/MSG Steve hi"), "case");
        assertTrue(blocked("/tell Steve hi"));
        assertTrue(blocked("/me waves"));
        assertTrue(blocked("/r yes"), "blocked by name even if no plugin registers it");
    }

    @Test
    void namespacedForms() {
        assertTrue(blocked("/minecraft:msg Steve hi"));
        assertTrue(blocked("/minecraft:tell Steve hi"));
        assertTrue(blocked("/essentials:msg Steve hi"));
        assertTrue(blocked("/someplugin:mail read"), "unknown namespace, blocked label");
    }

    @Test
    void anotherPluginsAliasOfABlockedCommand() {
        // "pm" isn't in the list, but it's an alias of a command named msg.
        assertTrue(blocked("/whisper Steve hi"));
        assertTrue(blocked("/emsg Steve hi"));
        assertEquals(Set.of("emsg", "msg", "whisper", "pm"), CommandLabelResolver.names("/emsg x", LOOKUP));
    }

    @Test
    void commandsYmlAliasesAreFollowed() {
        assertTrue(blocked("/whisperto Steve hi"));
        assertTrue(blocked("/chain Steve hi"), "an alias of an alias");
        assertFalse(blocked("/loop x"), "a loop ends without an error");
        assertFalse(blocked("/spawn"));
    }

    @Test
    void otherCommandsAreAllowed() {
        assertFalse(blocked("/help"));
        assertFalse(blocked("/dmme"), "a name that only starts like a blocked one");
        assertFalse(blocked("/unknowncommand x"));
        assertFalse(blocked("/"));
        assertFalse(blocked(""));
        assertEquals("msg", CommandLabelResolver.firstWord("/Msg  Steve"));
    }
}
