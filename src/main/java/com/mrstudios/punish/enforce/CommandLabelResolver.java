package com.mrstudios.punish.enforce;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Which command a typed command line really runs, so a mute can't be dodged with another name for
 * it (DESIGN.md 7.8, X18). Pure: the server's command map and {@code commands.yml} aliases come in
 * through {@link Lookup}. Every name collected is lower case and without a {@code namespace:}.
 */
public final class CommandLabelResolver {

    /** How deep {@code commands.yml} aliases are followed (an alias may point at another alias). */
    private static final int MAX_DEPTH = 4;

    /** A registered command as the command map knows it. */
    public record Known(String name, String label, List<String> aliases) {
    }

    public interface Lookup {

        /** The command the map resolves {@code label} to (a plain or namespaced label). */
        Optional<Known> command(String label);

        /** The command lines a {@code commands.yml} alias runs (e.g. {@code "msg $1-"}), or empty. */
        List<String> aliasTargets(String label);
    }

    private CommandLabelResolver() {
    }

    /** Every name the command in {@code message} ({@code "/msg Steve hi"}) is known by. */
    public static Set<String> names(String message, Lookup lookup) {
        Set<String> names = new LinkedHashSet<>();
        collect(firstWord(message), lookup, names, 0);
        return names;
    }

    /** Whether {@code message} runs a command whose name or any alias is in {@code blocked}. */
    public static boolean blocked(String message, Set<String> blocked, Lookup lookup) {
        if (firstWord(message).isEmpty()) {
            return false;
        }
        for (String name : names(message, lookup)) {
            if (blocked.contains(name)) {
                return true;
            }
        }
        return false;
    }

    static String firstWord(String message) {
        String text = message == null ? "" : message.strip();
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        int space = text.indexOf(' ');
        return (space < 0 ? text : text.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    private static String bare(String label) {
        int colon = label.indexOf(':');
        return colon < 0 ? label : label.substring(colon + 1);
    }

    private static void collect(String raw, Lookup lookup, Set<String> names, int depth) {
        if (raw.isEmpty() || depth > MAX_DEPTH) {
            return;
        }
        String bare = bare(raw);
        if (!names.add(bare) && depth > 0) {
            return;
        }
        for (String label : raw.equals(bare) ? List.of(raw) : List.of(raw, bare)) {
            lookup.command(label).ifPresent(known -> {
                names.add(bare(known.name().toLowerCase(Locale.ROOT)));
                names.add(bare(known.label().toLowerCase(Locale.ROOT)));
                for (String alias : known.aliases()) {
                    names.add(bare(alias.toLowerCase(Locale.ROOT)));
                }
            });
            for (String target : lookup.aliasTargets(label)) {
                collect(firstWord(target), lookup, names, depth + 1);
            }
        }
    }
}
