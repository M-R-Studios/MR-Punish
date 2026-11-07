package com.mrstudios.punish.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.FormattedCommandAlias;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;

/**
 * Finds the labels MR-Punish registered that resolve to someone else's command once the server has
 * loaded (another plugin, or an alias in Bukkit's {@code commands.yml}).
 */
public final class CommandClashCheck {

    /**
     * A label that runs another command than ours.
     *
     * @param label the label typed, e.g. {@code ban}
     * @param owner who it belongs to instead, e.g. {@code Essentials}
     */
    public record Clash(String label, String owner) {

        @Override
        public String toString() {
            return "/" + label + " (" + owner + ")";
        }
    }

    private CommandClashCheck() {
    }

    /**
     * @param ownerOf the owner of a label, or null if it's ours (or not registered at all)
     */
    public static List<Clash> find(Collection<String> labels, Function<String, String> ownerOf) {
        List<Clash> clashes = new ArrayList<>();
        for (String label : labels) {
            String owner = ownerOf.apply(label);
            if (owner != null) {
                clashes.add(new Clash(label, owner));
            }
        }
        return clashes;
    }

    /** Who owns {@code label} in {@code map}: null if it's {@code plugin}'s or unknown. */
    public static String ownerIn(CommandMap map, Plugin plugin, String label) {
        Command command = map.getCommand(label);
        if (command == null) {
            return null;
        }
        if (command instanceof PluginIdentifiableCommand identifiable) {
            Plugin owner = identifiable.getPlugin();
            return owner == plugin ? null : owner.getName();
        }
        if (command instanceof FormattedCommandAlias) {
            return "an alias in the server's commands.yml";
        }
        // Vanilla commands are replaced by ours, so anything else is unexpected: name it as it is.
        return command.getClass().getSimpleName() + " /" + command.getName();
    }

    /**
     * The startup log line for one clash, with the fixes.
     *
     * @param switchable whether {@code commands.*} can turn the label off for MR-Punish
     */
    public static String warning(Clash clash, String namespace, boolean switchable) {
        String redirect = "point the label at MR-Punish in the server's commands.yml (aliases: " + clash.label()
                + ": [\"" + namespace + ":" + clash.label() + " $1-\"])";
        return "/" + clash.label() + " belongs to " + clash.owner() + ", not MR-Punish. Use /" + namespace
                + ":" + clash.label() + " for MR-Punish's, or "
                + (switchable ? "turn the label off for MR-Punish in config.yml (commands.*), or " : "") + redirect + ".";
    }
}
