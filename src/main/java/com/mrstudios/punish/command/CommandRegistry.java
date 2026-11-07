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
        registrar.register(admin.node(), "MR-Punish help, reload and version.", AdminCommand.ALIASES);
        labels.put(AdminCommand.LABEL, false);
        AdminCommand.ALIASES.forEach(alias -> labels.put(alias, false));

        Map<String, CommandToggle> toggles = new LinkedHashMap<>();
        for (Switchable command : switchable) {
            CommandToggle toggle = current.command(command.name());
            toggles.put(command.name(), toggle);
            CommandPlan plan = CommandPlan.of(namespace, command.name(), toggle);
            registrar.register(command.build(plan.literal()).build(), command.description(), plan.aliases());
            plan.claims().forEach(label -> labels.put(label, true));
        }
        registeredWith = Map.copyOf(toggles);
        claimed = java.util.Collections.unmodifiableMap(labels);
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
