package com.mrstudios.punish.command;

import com.mrstudios.punish.config.Settings.CommandToggle;
import java.util.ArrayList;
import java.util.List;

/**
 * How one switchable command is registered, worked out from its {@code commands.<name>} toggle.
 * Paper always registers a plugin's Brigadier command under its plain label and under
 * {@code <namespace>:<label>}.
 *
 * <ul>
 *   <li>On: the node is registered as {@code <name>} with the configured aliases, so it gets
 *       {@code /<name>}, {@code /<alias>} and {@code /mr-punish:<name>}.</li>
 *   <li>Off: the node is registered under the literal {@code mr-punish:<name>} with no aliases, so
 *       the plain label stays with vanilla or the other plugin and the namespaced form still works.</li>
 * </ul>
 *
 * @param literal the literal the node is built with
 * @param aliases the aliases passed to Paper
 * @param claims  the plain labels MR-Punish takes over (checked for clashes at startup)
 */
public record CommandPlan(String literal, List<String> aliases, List<String> claims) {

    public CommandPlan {
        aliases = List.copyOf(aliases);
        claims = List.copyOf(claims);
    }

    public static CommandPlan of(String namespace, String name, CommandToggle toggle) {
        if (!toggle.enabled()) {
            return new CommandPlan(namespace + ":" + name, List.of(), List.of());
        }
        List<String> claims = new ArrayList<>();
        claims.add(name);
        claims.addAll(toggle.aliases());
        return new CommandPlan(name, toggle.aliases(), claims);
    }
}
