package com.mrstudios.punish.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.config.Settings.CommandToggle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Registers every command through Paper's Brigadier API. {@code /mrpunish} is always registered;
 * each other command follows its {@code commands.<name>} toggle (see {@link CommandPlan}). Paper
 * runs the registration again on {@code /reload} and datapack reloads, with the settings loaded at
 * that moment; {@code /mrpunish reload} doesn't, so a changed toggle needs a restart.
 */
public final class CommandRegistry {

    /** A command that {@code commands.*} can switch. Later steps add theirs here. */
    public interface Switchable {

        String name();

        String description();

        /** The command's tree under {@code literal} (its name, or the namespaced form when it's off). */
        LiteralArgumentBuilder<CommandSourceStack> build(String literal);
    }

    private final String namespace;
    private final AdminCommand admin;
    private final List<Switchable> switchable;
    private final Supplier<Settings> settings;
    /** The toggles the registered commands were built with: what a reload compares against. */
    private volatile Map<String, CommandToggle> registeredWith = Map.of();
    /** Plain labels MR-Punish took over, and whether each can be switched off. */
    private volatile Map<String, Boolean> claimed = Map.of();
    /** The server's command tree as of the last registration, and the executors of MR-Punish's nodes. */
    private volatile com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher;
    private final java.util.Set<com.mojang.brigadier.Command<CommandSourceStack>> ours =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * @param namespace the plugin's command namespace ({@code mr-punish})
     */
    public CommandRegistry(String namespace, AdminCommand admin, List<Switchable> switchable,
            Supplier<Settings> settings) {
        this.namespace = namespace;
        this.admin = admin;
        this.switchable = List.copyOf(switchable);
        this.settings = settings;
    }

    /** The names {@code commands.*} accepts, in order. */
    public static List<String> names(List<Switchable> switchable) {
        return switchable.stream().map(Switchable::name).toList();
    }

    /** Called from the {@code LifecycleEvents.COMMANDS} handler. */
    public void register(Commands registrar) {
        Settings current = settings.get();
        Map<String, Boolean> labels = new LinkedHashMap<>();
        ours.clear();
        var adminNode = admin.node();
        ours.add(adminNode.getCommand());
        registrar.register(adminNode, "MR-Punish help, reload and version.", AdminCommand.ALIASES);
        labels.put(AdminCommand.LABEL, false);
        AdminCommand.ALIASES.forEach(alias -> labels.put(alias, false));

        Map<String, CommandToggle> toggles = new LinkedHashMap<>();
        for (Switchable command : switchable) {
            CommandToggle toggle = current.command(command.name());
            toggles.put(command.name(), toggle);
            CommandPlan plan = CommandPlan.of(namespace, command.name(), toggle);
            var node = command.build(plan.literal()).build();
            ours.add(node.getCommand());
            registrar.register(node, command.description(), plan.aliases());
            plan.claims().forEach(label -> labels.put(label, true));
        }
        registeredWith = Map.copyOf(toggles);
        try {
            dispatcher = registrar.getDispatcher();
        } catch (RuntimeException notHere) {
            dispatcher = null;
        }
        claimed = java.util.Collections.unmodifiableMap(labels);
    }

    /**
     * Whether typing {@code label} runs MR-Punish's command: the dispatcher's root node for it
     * executes (or redirects to) one of MR-Punish's nodes. Bukkit's command map can still list
     * another plugin's alias for a label MR-Punish won in the dispatcher (EssentialsX's {@code v}),
     * so this is what decides a clash. False if unknown.
     */
    public boolean ownsInDispatcher(String label) {
        var current = dispatcher;
        if (current == null) {
            return false;
        }
        return runsOneOf(current.getRoot().getChild(label), ours);
    }

    /** Whether {@code node} (a root child, or null) executes or redirects to one of {@code commands}. Pure. */
    static <S> boolean runsOneOf(com.mojang.brigadier.tree.CommandNode<S> node,
            java.util.Set<com.mojang.brigadier.Command<S>> commands) {
        if (node == null) {
            return false;
        }
        if (node.getCommand() != null && commands.contains(node.getCommand())) {
            return true;
        }
        return node.getRedirect() != null && node.getRedirect().getCommand() != null
                && commands.contains(node.getRedirect().getCommand());
    }

    /** The plain labels MR-Punish registered, each mapped to whether {@code commands.*} can switch it. */
    public Map<String, Boolean> claimed() {
        return claimed;
    }

    /** Whether {@code updated} switches any command differently from how it was registered. */
    public boolean needsRestart(Settings updated) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, CommandToggle> entry : registeredWith.entrySet()) {
            if (!updated.command(entry.getKey()).equals(entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        return !changed.isEmpty();
    }

    public String namespace() {
        return namespace;
    }
}
