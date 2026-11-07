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
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.punish.NameIndex;
import com.mrstudios.punish.punish.TargetResolver;
import com.mrstudios.punish.rank.RankRegistrar;
import com.mrstudios.punish.storage.DatabaseHealth;
import com.mrstudios.punish.storage.Migrations;
import com.mrstudios.punish.storage.MysqlDatabase;
import com.mrstudios.punish.storage.PlayerRepo;
import com.mrstudios.punish.storage.PlayerTracker;
import com.mrstudios.punish.storage.Retention;
import com.mrstudios.punish.storage.SqliteDatabase;
import com.mrstudios.punish.storage.Storage;
import java.io.File;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
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
    private Storage storage;
    private final NameIndex names = new NameIndex();
    private PlayerTracker tracker;
    /** Set once onEnable has opened storage, so the first "storage is up" isn't announced as a recovery. */
    private volatile boolean started;
    private volatile boolean namesLoaded;

    /** How long enable waits for the database (DESIGN.md 2.4 step 3). */
    private static final long STORAGE_START_MS = 15_000;
    /** How long disable waits for submitted database work. */
    private static final long STORAGE_DRAIN_MS = 10_000;
    private static final long HEALTH_CHECK_TICKS = 30 * 20L;
    private static final long DAY_TICKS = 24L * 60 * 60 * 20;
    private static final String ADMIN = "mrpunish.admin";

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

        startStorage();

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
        tracker = new PlayerTracker(storage, names, () -> state.ranks(), System::currentTimeMillis,
                this::alertIfStorageDown);
        getServer().getPluginManager().registerEvents(tracker, this);
        getServer().getScheduler().runTaskTimerAsynchronously(this, storage::check, HEALTH_CHECK_TICKS,
                HEALTH_CHECK_TICKS);
        // Retention a minute after startup, then once a day (DESIGN.md 9.5).
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::runRetention, 60 * 20L, DAY_TICKS);
        // Players already online (after /reload or a plugin manager) are recorded as if they had just joined.
        for (Player player : getServer().getOnlinePlayers()) {
            tracker.record(player);
            alertIfStorageDown(player);
        }

        getLogger().info("MR-Punish " + getPluginMeta().getVersion() + " enabled.");
    }

    /** Opens the database, waiting up to 15 s; on failure the plugin runs in degraded mode (DESIGN.md 2.4). */
    private void startStorage() {
        Settings.Storage settings = state.settings().storage();
        storage = new Storage(settings,
                s -> s.type() == Settings.StorageType.SQLITE
                        ? new SqliteDatabase(new File(getDataFolder(), s.sqliteFile()))
                        : new MysqlDatabase(s.mysql()),
                Migrations.shipped(System::currentTimeMillis), getLogger());
        storage.health().onChange((old, now) -> {
            boolean announce = started;
            if (isEnabled()) {
                getServer().getScheduler().runTask(this, () -> healthChanged(now, announce));
            }
        });
        if (storage.start(STORAGE_START_MS)) {
            loadNames();
        }
        started = true;
    }

    /** Main thread. Tells admins and the console when storage goes down or comes back (DESIGN.md 9.3). */
    private void healthChanged(DatabaseHealth.State now, boolean announce) {
        if (now == DatabaseHealth.State.UP) {
            if (!namesLoaded) {
                loadNames();
            }
            if (announce) {
                getLogger().info("Storage is back: " + storage.describeSettings());
                notifyAdmins("notify.storage-back");
                // Joins while it was down weren't recorded.
                for (Player player : getServer().getOnlinePlayers()) {
                    tracker.record(player);
                }
            }
        } else if (now == DatabaseHealth.State.DOWN) {
            getLogger().severe("Storage is unavailable (" + storage.health().reason() + "). Punishments can't be"
                    + " saved until it's back; checking again every 30 seconds.");
            notifyAdmins("notify.storage-down");
        }
    }

    private void notifyAdmins(String key) {
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.hasPermission(ADMIN)) {
                state.messages().send(player, key);
            }
        }
    }

    /** Every admin who joins while storage is unavailable is told (DESIGN.md 2.4). */
    private void alertIfStorageDown(Player player) {
        if (!storage.health().isUp() && player.hasPermission(ADMIN)) {
            state.messages().send(player, "notify.storage-down");
        }
    }

    /** Fills the name index from the database (one query, off the main thread). */
    private void loadNames() {
        storage.executor().submit("loading player names", c -> new PlayerRepo(storage.database()).all(c))
                .thenAccept(players -> {
                    names.load(players.stream()
                            .map(p -> new NameIndex.Entry(p.uuid(), p.name(), p.lastSeen()))
                            .toList());
                    namesLoaded = true;
                    getLogger().info("Loaded " + players.size() + " known player name(s).");
                });
    }

    private void runRetention() {
        if (!storage.health().isUp()) {
            return;
        }
        storage.executor().transaction("running the daily clean-up",
                c -> new Retention(storage.database()).run(c, System.currentTimeMillis()))
                .thenAccept(deleted -> {
                    int total = deleted.values().stream().mapToInt(Integer::intValue).sum();
                    if (total > 0) {
                        getLogger().info("Daily clean-up removed " + deleted + " old row(s).");
                    }
                });
    }

    @Override
    public void onDisable() {
        if (storage == null) {
            return;
        }
        // On /stop, plugins are disabled while players are still online (DESIGN.md 2.4), so their
        // quit would come too late: record it now, then let every submitted write finish.
        if (tracker != null) {
            for (Player player : getServer().getOnlinePlayers()) {
                tracker.record(player);
            }
        }
        storage.shutdown(STORAGE_DRAIN_MS);
    }

    /** The resolver for command player arguments: online players, the name index, then the database. */
    public TargetResolver targets() {
        return new TargetResolver(names, new TargetResolver.Lookups() {
            @Override
            public Optional<TargetResolver.Target> onlineByName(String name) {
                Player player = getServer().getPlayerExact(name);
                return Optional.ofNullable(player)
                        .map(p -> new TargetResolver.Target(p.getUniqueId(), p.getName(), true));
            }

            @Override
            public Optional<TargetResolver.Target> onlineByUuid(UUID uuid) {
                Player player = getServer().getPlayer(uuid);
                return Optional.ofNullable(player)
                        .map(p -> new TargetResolver.Target(p.getUniqueId(), p.getName(), true));
            }

            @Override
            public CompletableFuture<Optional<NameIndex.Entry>> storedByName(String name) {
                return storage.executor().submit("looking up " + name,
                        c -> new PlayerRepo(storage.database()).byName(c, name).map(MRPunish::entry));
            }

            @Override
            public CompletableFuture<Optional<NameIndex.Entry>> storedByUuid(UUID uuid) {
                return storage.executor().submit("looking up " + uuid,
                        c -> new PlayerRepo(storage.database()).byUuid(c, uuid).map(MRPunish::entry));
            }
        });
    }

    private static NameIndex.Entry entry(PlayerRepo.PlayerRecord record) {
        return new NameIndex.Entry(record.uuid(), record.name(), record.lastSeen());
    }

    public NameIndex names() {
        return names;
    }

    public Storage storage() {
        return storage;
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
        if (loaded.settings().ok() && !updated.settings().storage().equals(storage.settings())) {
            restartNeeded.add("storage");
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
    public String storageDescription() {
        return storage.describeSettings();
    }

    @Override
    public String storageStatus() {
        DatabaseHealth health = storage.health();
        return switch (health.state()) {
            case UP -> "connected";
            case STARTING -> "not connected: " + health.reason();
            case DOWN -> "connection lost: " + health.reason();
            case INCOMPATIBLE -> "not used: " + health.reason();
        };
    }

    @Override
    public String clashSummary() {
        List<Clash> current = clashes;
        return current.isEmpty() ? "none" : current.stream().map(Clash::toString).collect(Collectors.joining(", "));
    }
}
