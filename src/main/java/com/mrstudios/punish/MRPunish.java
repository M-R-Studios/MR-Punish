package com.mrstudios.punish;

import com.mrstudios.punish.command.AdminCommand;
import com.mrstudios.punish.command.CommandClashCheck;
import com.mrstudios.punish.command.CommandClashCheck.Clash;
import com.mrstudios.punish.command.CommandRegistry;
import com.mrstudios.punish.config.ConfigFiles;
import com.mrstudios.punish.config.GuiLoader;
import com.mrstudios.punish.config.Messages;
import com.mrstudios.punish.config.MessagesLoader;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.RankLoader;
import com.mrstudios.punish.config.ReloadReport;
import com.mrstudios.punish.config.ScoreboardLoader;
import com.mrstudios.punish.config.SettingsLoader;
import com.mrstudios.punish.rank.RankRegistrar;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

// Not final: MockBukkit subclasses the plugin class in tests.
public class MRPunish extends JavaPlugin implements AdminCommand.Admin {

    /** The active settings of all five files. Swapped as a whole on reload. */
    private volatile PluginState state;
    private ConfigFiles files;
    private RankRegistrar ranks;
    private CommandRegistry commands;
    private volatile List<Clash> clashes = List.of();

    @Override
    public void onEnable() {
        // Commands that config.yml can switch off. Later steps add theirs.
        List<CommandRegistry.Switchable> switchable = List.of();
        Set<String> reserved = new HashSet<>(AdminCommand.ALIASES);
        reserved.add(AdminCommand.LABEL);

        files = new ConfigFiles(getDataFolder(), name -> saveResource(name, false),
                new SettingsLoader(MRPunish::isItem, CommandRegistry.names(switchable), reserved,
                        SettingsLoader.bundledDefaults()),
                new MessagesLoader(MessagesLoader.bundledDefaults()),
                new GuiLoader(),
                new RankLoader(name -> getServer().getPluginManager().getPermission(name) != null,
                        RankLoader.bundledDefaults()),
                new ScoreboardLoader());
        ConfigFiles.Loaded loaded = files.load(getLogger()::info);
        state = loaded.applyTo(files.defaults());
        for (String warning : loaded.warnings()) {
            getLogger().warning(warning);
        }
        loaded.failures().forEach((file, error) ->
                getLogger().severe(file + " could not be loaded, so the built-in defaults are used for it: " + error));

        ranks = new RankRegistrar(getServer().getPluginManager(), getPluginMeta().getPermissions(),
                getServer()::getOnlinePlayers);
        ranks.apply(state.ranks());

        commands = new CommandRegistry(getPluginMeta().namespace(), new AdminCommand(this), switchable,
                () -> state.settings());
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> commands.register(event.registrar()));
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onServerLoad(ServerLoadEvent event) {
                checkClashes();
            }
        }, this);

        getLogger().info("MR-Punish " + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        // Nothing to undo yet: no player state is changed by the foundation.
    }

    /** Whether {@code name} (upper case) is an item material, for reason icons. */
    private static boolean isItem(String name) {
        Material material = Material.matchMaterial(name);
        return material != null && material.isItem() && !material.isAir();
    }

    /** Logs one warning for each label another plugin or alias owns (DESIGN.md 4.2). */
    private void checkClashes() {
        Map<String, Boolean> claimed = commands.claimed();
        clashes = CommandClashCheck.find(claimed.keySet(),
                label -> CommandClashCheck.ownerIn(getServer().getCommandMap(), this, label));
        for (Clash clash : clashes) {
            getLogger().warning(CommandClashCheck.warning(clash, commands.namespace(), claimed.get(clash.label())));
        }
    }

    public PluginState state() {
        return state;
    }

    @Override
    public Messages messages() {
        return state.messages();
    }

    /**
     * Reloads all five files. A file that fails keeps its previous settings; the sender and the
     * console are told what failed and why (DESIGN.md 5.2).
     */
    @Override
    public void reload(CommandSender sender) {
        ConfigFiles.Loaded loaded = files.load(getLogger()::info);
        PluginState updated = loaded.applyTo(state);
        state = updated;
        if (loaded.ranks().ok()) {
            ranks.apply(updated.ranks());
        }
        List<String> restartNeeded = new ArrayList<>();
        if (loaded.settings().ok() && commands.needsRestart(updated.settings())) {
            restartNeeded.add("commands");
        }

        Messages messages = updated.messages();
        boolean console = sender instanceof ConsoleCommandSender;
        if (!console) {
            getLogger().info(sender.getName() + " reloaded MR-Punish.");
        }
        for (ReloadReport.Line line : ReloadReport.lines(loaded, restartNeeded)) {
            Component text = line.name() == null
                    ? messages.component(line.key())
                    : messages.component(line.key(), Placeholder.unparsed(line.name(), line.value()));
            if (!messages.raw(line.key()).isEmpty()) {
                sender.sendMessage(text);
            }
            if (!console) {
                getLogger().info(PlainTextComponentSerializer.plainText().serialize(text));
            }
        }
    }

    @Override
    public String pluginVersion() {
        return getPluginMeta().getVersion();
    }

    @Override
    public String serverVersion() {
        return getServer().getName() + " " + getServer().getMinecraftVersion();
    }

    @Override
    public String clashSummary() {
        List<Clash> current = clashes;
        return current.isEmpty() ? "none" : current.stream().map(Clash::toString).collect(Collectors.joining(", "));
    }
}
