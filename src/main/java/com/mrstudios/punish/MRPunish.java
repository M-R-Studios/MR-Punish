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
import com.mrstudios.punish.hook.LuckPermsHook;
import com.mrstudios.punish.punish.Notifier;
import com.mrstudios.punish.punish.PunishmentService;
import com.mrstudios.punish.rank.OfflineRanks;
import com.mrstudios.punish.rank.RankCache;
import com.mrstudios.punish.rank.RankRegistrar;
import com.mrstudios.punish.command.PunishCommand;
import com.mrstudios.punish.command.HistoryCommand;
import com.mrstudios.punish.command.ReportCommand;
import com.mrstudios.punish.command.ReportsCommand;
import com.mrstudios.punish.command.FreezeCommands;
import com.mrstudios.punish.command.StaffCommands;
import com.mrstudios.punish.gui.InspectMenus;
import com.mrstudios.punish.staff.FreezeService;
import com.mrstudios.punish.staff.StaffModeService;
import com.mrstudios.punish.staff.StaffChat;
import com.mrstudios.punish.staff.VanishService;
import com.mrstudios.punish.command.PunishMenuCommand;
import com.mrstudios.punish.gui.BedrockForms;
import com.mrstudios.punish.gui.DisplayGuard;
import com.mrstudios.punish.gui.HistoryMenus;
import com.mrstudios.punish.gui.ReportMenus;
import com.mrstudios.punish.report.ReportNotices;
import com.mrstudios.punish.report.ReportService;
import com.mrstudios.punish.gui.Items;
import com.mrstudios.punish.gui.MenuHolder;
import com.mrstudios.punish.gui.MenuListener;
import com.mrstudios.punish.gui.PromptService;
import com.mrstudios.punish.gui.PunishMenus;
import com.mrstudios.punish.hook.FloodgateHook;
import com.mrstudios.punish.command.RemoveCommand;
import com.mrstudios.punish.enforce.CommandLabelResolver;
import com.mrstudios.punish.enforce.MuteCache;
import com.mrstudios.punish.enforce.MuteEnforcement;
import com.mrstudios.punish.enforce.BanCache;
import com.mrstudios.punish.enforce.BanEnforcement;
import com.mrstudios.punish.imports.VanillaImporter;
import com.mrstudios.punish.storage.StorageException;
import com.mrstudios.punish.imports.VanillaBanLists;
import com.mrstudios.punish.ip.IpFeatures;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.BanEntry;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.Registry;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
    private RankCache rankCache;
    private PunishmentService punishments;
    private BanEnforcement bans;
    private MuteEnforcement mutes;
    private PromptService prompts;
    private StaffChat staffChat;
    private VanishService vanish;
    private FreezeService freeze;
    private StaffModeService staffMode;
    private com.mrstudios.punish.staff.StaffLogFile staffLogFile;
    private InspectMenus inspect;
    private PunishMenus menus;
    private HistoryMenus history;
    private ReportService reports;
    private ReportMenus reportMenus;
    private com.mrstudios.punish.gui.StaffMenus staffMenus;
    private com.mrstudios.punish.gui.StaffLogMenus staffLogMenus;
    private com.mrstudios.punish.staff.StaffProfile staffProfile;
    /** {@code bedrock.floodgate} as at startup (changing it needs a restart). */
    private boolean floodgateSetting;
    private IpFeatures ip;
    /** Whether the LuckPerms hook was set up at startup (changing that needs a restart). */
    private boolean luckPermsHooked;
    private static final long RANK_REFRESH_TICKS = 60 * 20L;
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
        List<CommandRegistry.Switchable> switchable = new java.util.ArrayList<>(List.of(PunishCommand.ban(this),
                RemoveCommand.unban(this),
                PunishCommand.warn(this), RemoveCommand.unwarn(this), PunishCommand.mute(this),
                RemoveCommand.unmute(this), PunishCommand.muteip(this), PunishCommand.banip(this),
                RemoveCommand.unmuteip(this), RemoveCommand.unbanip(this), new PunishMenuCommand(this),
                new HistoryCommand(this), new ReportCommand(this), new ReportsCommand(this)));
        switchable.addAll(new StaffCommands(this).all());
        switchable.addAll(new FreezeCommands(this).all());
        switchable.addAll(new com.mrstudios.punish.command.StaffHubCommands(this).all());
        Set<String> reserved = new HashSet<>(AdminCommand.ALIASES);
        reserved.add(AdminCommand.LABEL);

        files = new ConfigFiles(getDataFolder(), name -> saveResource(name, false),
                new SettingsLoader(MRPunish::isItem, key -> Registry.SOUNDS.get(key) != null,
                        CommandRegistry.names(switchable), reserved,
                        SettingsLoader.bundledDefaults()),
                new MessagesLoader(MessagesLoader.bundledDefaults()),
                new GuiLoader(MRPunish::isItem, key -> Registry.SOUNDS.get(key) != null, GuiLoader.bundledDefaults()),
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
        rankCache = new RankCache(() -> state.ranks());
        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.LOWEST)
            public void onJoin(PlayerJoinEvent event) {
                rankCache.refresh(event.getPlayer());
            }

            @EventHandler(priority = EventPriority.MONITOR)
            public void onQuit(PlayerQuitEvent event) {
                rankCache.forget(event.getPlayer().getUniqueId());
            }
        }, this);
        // Every 60 s: a rank or exempt change while online updates the cached rank in the database
        // (DESIGN.md 6.4), so it's right for when the player is offline.
        getServer().getScheduler().runTaskTimer(this, this::refreshRanks, RANK_REFRESH_TICKS, RANK_REFRESH_TICKS);
        OfflineRanks offline = OfflineRanks.NONE;
        if (state.settings().luckperms() && getServer().getPluginManager().isPluginEnabled("LuckPerms")) {
            offline = new LuckPermsHook(() -> state.ranks());
            luckPermsHooked = true;
            getLogger().info("Hooked into LuckPerms for the ranks of offline players.");
        }
        punishments = new PunishmentService(storage, () -> state, offline,
                task -> getServer().getScheduler().runTask(this, task),
                new Notifier(getServer()::getOnlinePlayers, getLogger()), System::currentTimeMillis, getLogger());
        ip = new IpFeatures(state.settings().ip(), storage, getLogger());
        ip.start(getDataFolder().toPath());
        MuteCache muteCache = new MuteCache();
        bans = new BanEnforcement(this, storage, () -> state, new BanCache(), muteCache, ip, System::currentTimeMillis,
                getLogger());
        getServer().getPluginManager().registerEvents(bans, this);
        punishments.addListener(bans);
        mutes = new MuteEnforcement(this, storage, () -> state, muteCache, ip, commandLookup(),
                System::currentTimeMillis, getLogger());
        getServer().getPluginManager().registerEvents(mutes, this);
        punishments.addListener(mutes);
        startMenus();
        // Players already online (after /reload): their connection's IP hash, before their mutes load.
        for (Player player : getServer().getOnlinePlayers()) {
            bans.track(player);
        }
        mutes.loadOnline();
        bans.loadCache();
        // Players let in unverified are checked again as soon as the database answers (DESIGN.md 7.7 step 5).
        getServer().getScheduler().runTaskTimer(this, () -> bans.recheckUnverified(), HEALTH_CHECK_TICKS,
                HEALTH_CHECK_TICKS);
        hintAboutVanillaBans();
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
                // The IP key check may have been waiting for the database; caches load once it's done.
                ip.verify().thenRun(() -> getServer().getScheduler().runTask(this, () -> {
                    bans.loadCache();
                    mutes.loadOnline();
                }));
                bans.loadCache();
                mutes.loadOnline();
                int rechecked = bans.recheckUnverified();
                getLogger().info("Storage is back: " + storage.describeSettings() + ". Re-checked " + rechecked
                        + " player(s) who were let in unverified.");
                notifyAdmins("notify.storage-back",
                        Placeholder.unparsed("count", String.valueOf(rechecked)));
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

    private void notifyAdmins(String key, TagResolver... placeholders) {
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.hasPermission(ADMIN)) {
                state.messages().send(player, key, placeholders);
            }
        }
    }

    /** DESIGN.md 7.9: vanilla bans keep working on their own; say so once if there are any. */
    private void hintAboutVanillaBans() {
        int players = VanillaBanLists.profiles(getServer()).getEntries().size();
        int ips = VanillaBanLists.ipCount(getServer());
        if (players + ips > 0) {
            getLogger().info((players + ips) + " vanilla ban(s) exist (" + players + " player, " + ips + " IP);"
                    + " MR-Punish doesn't manage them. Run /mrpunish import vanilla to import them.");
        }
    }

    /** {@code /mrpunish import vanilla [--clear]} (DESIGN.md 7.9). */
    @Override
    public void importVanilla(CommandSender sender, boolean clear) {
        Messages messages = state.messages();
        if (!storage.health().isUp()) {
            messages.send(sender, "general.storage-unavailable");
            return;
        }
        // Read on the main thread, copied to plain records for the database thread.
        ProfileBanList list = VanillaBanLists.profiles(getServer());
        List<VanillaImporter.Entry> entries = new ArrayList<>();
        for (BanEntry<?> entry : list.getEntries()) {
            PlayerProfile profile = entry.getBanTarget() instanceof PlayerProfile p ? p : null;
            entries.add(new VanillaImporter.Entry(profile == null ? null : profile.getUniqueId(),
                    profile == null ? null : profile.getName(), entry.getReason(), entry.getSource(),
                    entry.getCreated() == null ? System.currentTimeMillis() : entry.getCreated().getTime(),
                    entry.getExpiration() == null ? null : entry.getExpiration().getTime()));
        }
        int ipEntries = VanillaBanLists.ipCount(getServer());
        // IP entries are hashed here and only the hash goes on (DESIGN.md 7.9, 8.1).
        List<VanillaImporter.IpEntry> ipBans = new ArrayList<>();
        if (ip.enabled()) {
            for (org.bukkit.BanEntry<?> entry : VanillaBanLists.ips(getServer())) {
                java.net.InetAddress address = VanillaBanLists.address(entry.getBanTarget());
                Optional<String> hash = address == null ? Optional.empty() : ip.hash(address);
                ipBans.add(new VanillaImporter.IpEntry(hash.orElse(null), entry.getReason(), entry.getSource(),
                        entry.getCreated() == null ? System.currentTimeMillis() : entry.getCreated().getTime(),
                        entry.getExpiration() == null ? null : entry.getExpiration().getTime()));
            }
        }
        messages.send(sender, "admin.import-start");
        UUID actorUuid = sender instanceof Player p ? p.getUniqueId() : null;
        String actorName = sender instanceof Player ? sender.getName() : messages.raw("general.console-name");
        String server = state.settings().effectiveServerName();
        storage.executor().transaction("importing vanilla bans", c -> new VanillaImporter(storage.database())
                        .run(c, entries, ipBans, actorName, actorUuid, server, System.currentTimeMillis()))
                .whenComplete((result, error) -> getServer().getScheduler().runTask(this, () -> {
                    Messages m = state.messages();
                    if (error != null) {
                        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause()
                                != null ? error.getCause() : error;
                        String id = cause instanceof StorageException se && se.errorId() != null ? se.errorId() : "-";
                        m.send(sender, "admin.import-failed", Placeholder.unparsed("error-id", id));
                        return;
                    }
                    m.send(sender, "admin.import-done",
                            Placeholder.unparsed("players", String.valueOf(result.players())),
                            Placeholder.unparsed("skipped", String.valueOf(result.skipped())),
                            Placeholder.unparsed("unknown", String.valueOf(result.unknown())));
                    if (ip.enabled()) {
                        m.send(sender, "admin.import-done-ips", Placeholder.unparsed("ips", String.valueOf(result.ips())));
                    } else if (ipEntries > 0) {
                        m.send(sender, "admin.import-ip-disabled", Placeholder.unparsed("count", String.valueOf(ipEntries)));
                    }
                    getLogger().info(sender.getName() + " imported vanilla bans: " + result.players() + " imported, "
                            + result.skipped() + " already imported, " + result.unknown() + " unreadable.");
                    bans.loadCache();
                    if (clear) {
                        // Only after the commit: entries now in MR-Punish leave the vanilla list.
                        int cleared = 0;
                        ProfileBanList now = VanillaBanLists.profiles(getServer());
                        for (BanEntry<?> entry : now.getEntries()) {
                            PlayerProfile profile = entry.getBanTarget() instanceof PlayerProfile p ? p : null;
                            if (profile != null && result.imported().contains(profile.getUniqueId())) {
                                now.pardon(profile);
                                cleared++;
                            }
                        }
                        cleared += VanillaBanLists.pardonIps(getServer(), result.importedIpHashes(), ip);
                        m.send(sender, "admin.import-cleared", Placeholder.unparsed("count", String.valueOf(cleared)));
                    }
                }));
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
                c -> new Retention(storage.database()).run(c, System.currentTimeMillis(),
                        ip.enabled() ? state.settings().ip().retentionDays() : 0, state.settings().logs().retentionDays()))
                .thenAccept(deleted -> {
                    int total = deleted.values().stream().mapToInt(Integer::intValue).sum();
                    if (total > 0) {
                        getLogger().info("Daily clean-up removed " + deleted + " old row(s).");
                    }
                });
        if (staffLogFile != null) {
            staffLogFile.prune();
        }
    }

    /** Menus, their item guard and text prompts (DESIGN.md 10.1, 12.2). */
    private void startMenus() {
        BedrockForms forms = BedrockForms.NONE;
        floodgateSetting = state.settings().floodgate();
        if (state.settings().floodgate() && getServer().getPluginManager().isPluginEnabled("floodgate")) {
            try {
                forms = new FloodgateHook();
                getLogger().info("Hooked into Floodgate: Bedrock players type notes in a form.");
            } catch (LinkageError | RuntimeException e) {
                getLogger().warning("Couldn't hook into Floodgate (" + e + "); Bedrock players type notes in chat.");
            }
        }
        Items items = new Items();
        prompts = new PromptService(this, () -> state, forms);
        getServer().getPluginManager().registerEvents(prompts, this);
        staffChat = new StaffChat(this, () -> state, prompts::waiting);
        getServer().getPluginManager().registerEvents(staffChat, this);
        // Prompt input and staff chat aren't public chat, so a mute doesn't block them (DESIGN.md X20).
        mutes.skipChatWhen(event -> prompts.waiting(event.getPlayer().getUniqueId()) || staffChat.claims(event));
        vanish = new VanishService(this, () -> state, rankCache);
        getServer().getPluginManager().registerEvents(vanish, this);
        // EssentialsX enables after MR-Punish (no dependency either way), so it's looked up a tick later.
        getServer().getScheduler().runTask(this, () -> {
            com.mrstudios.punish.hook.EssentialsHook essentials =
                    com.mrstudios.punish.hook.EssentialsHook.find(getServer().getPluginManager(), getLogger());
            if (essentials != null) {
                vanish.onHiddenChange(essentials::setHidden);
                getLogger().info("Hooked into EssentialsX: vanished players are hidden from its /list and messages.");
                for (Player online : getServer().getOnlinePlayers()) {
                    if (vanish.isVanished(online.getUniqueId())) {
                        essentials.setHidden(online, true);
                    }
                }
            }
        });
        vanish.enableOnline();
        // Again once every plugin has enabled (after a /reload): another plugin enabling after us
        // (EssentialsX does) may show everyone to everyone, which would reveal vanished players.
        getServer().getScheduler().runTask(this, vanish::refreshVisibility);
        getServer().getScheduler().runTaskTimer(this, vanish::tick, 20L, 20L);
        freeze = new FreezeService(this);
        getServer().getPluginManager().registerEvents(freeze, this);
        freeze.enableOnline();
        getServer().getScheduler().runTaskTimer(this, freeze::tick, 20L, 20L);
        staffLogFile = new com.mrstudios.punish.staff.StaffLogFile(getDataFolder().toPath(), storage, () -> state,
                getLogger());
        getServer().getScheduler().runTaskTimer(this, staffLogFile::poll, 20L, 60L);
        staffMode = new StaffModeService(this, items);
        getServer().getPluginManager().registerEvents(staffMode, this);
        staffMode.enableOnline();
        getServer().getScheduler().runTaskTimer(this, staffMode::tick, 1L, 1L);
        inspect = new InspectMenus(this, items);
        getServer().getPluginManager().registerEvents(inspect, this);
        getServer().getScheduler().runTaskTimer(this, inspect::tick, 10L, 10L);
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getServer().getPluginManager().registerEvents(new DisplayGuard(items, getLogger()), this);
        menus = new PunishMenus(this, items, prompts);
        history = new HistoryMenus(this, items, prompts);
        reports = new ReportService(storage, () -> state, task -> getServer().getScheduler().runTask(this, task),
                System::currentTimeMillis, getLogger());
        ReportNotices notices = new ReportNotices(this, () -> state, reports, getLogger());
        reports.listener(notices);
        getServer().getPluginManager().registerEvents(notices, this);
        reportMenus = new ReportMenus(this, items, prompts);
        staffMenus = new com.mrstudios.punish.gui.StaffMenus(this, items, prompts);
        staffLogMenus = new com.mrstudios.punish.gui.StaffLogMenus(this, items);
        staffProfile = new com.mrstudios.punish.staff.StaffProfile(this);
        // Every minute: release expired claims, and remind staff of open reports when it's time.
        getServer().getScheduler().runTaskTimer(this, notices::tick, 20L * 60, 20L * 60);
        reports.refreshOpenCount();
    }

    public ReportService reports() {
        return reports;
    }

    public com.mrstudios.punish.gui.StaffMenus staffMenus() {
        return staffMenus;
    }

    public com.mrstudios.punish.gui.StaffLogMenus staffLogMenus() {
        return staffLogMenus;
    }

    /** {@code /mrpunish staff <player>} (DESIGN.md 11.7). */
    @Override
    public void staffProfile(CommandSender sender, String name) {
        staffProfile.show(sender, name);
    }

    public ReportMenus reportMenus() {
        return reportMenus;
    }

    public HistoryMenus history() {
        return history;
    }

    public PunishMenus menus() {
        return menus;
    }

    public StaffChat staffChat() {
        return staffChat;
    }

    /**
     * Logs a staff action that isn't part of a transaction (DESIGN.md 13.6): a separate async insert;
     * if it fails, the action itself stands and the console says so.
     *
     * @param actor null for the console
     */
    public void logAction(Player actor, String action, UUID targetUuid, String targetName, Long refId, String details) {
        if (storage == null || storage.executor().isShutDown() || !storage.health().isUp()) {
            getLogger().warning("Couldn't log " + action + " by " + (actor == null ? "console" : actor.getName())
                    + ": the database is unavailable.");
            return;
        }
        long now = System.currentTimeMillis();
        String server = state.settings().effectiveServerName();
        UUID actorUuid = actor == null ? null : actor.getUniqueId();
        String actorName = actor == null ? state.messages().raw("general.console-name") : actor.getName();
        storage.executor().submit("logging " + action, c -> {
            new com.mrstudios.punish.storage.ActionRepo(storage.database()).insert(c,
                    new com.mrstudios.punish.storage.ActionRepo.Action(now, server, actorUuid, actorName, action,
                            targetUuid, targetName, refId, details));
            return null;
        }).exceptionally(e -> {
            getLogger().warning("Couldn't log " + action + " by " + actorName + ": " + e.getMessage());
            return null;
        });
    }

    /** {@code /mrpunish forceexit <player>}: ends their staff mode and vanish, with authority over them. */
    @Override
    public void forceExit(CommandSender sender, String name) {
        Player target = getServer().getPlayerExact(name);
        if (target == null) {
            state.messages().send(sender, "staff.forceexit-none", Placeholder.unparsed("player", name));
            return;
        }
        com.mrstudios.punish.rank.Authority.Standing actor = sender instanceof Player p ? rankCache.standing(p)
                : com.mrstudios.punish.rank.Authority.Standing.console(state.messages().raw("general.console-name"));
        if (!actor.isConsole() && com.mrstudios.punish.rank.Authority.check(actor, rankCache.standing(target),
                com.mrstudios.punish.rank.Authority.Action.FORCE_EXIT) != com.mrstudios.punish.rank.Authority.Verdict.OK) {
            state.messages().send(sender, "staff.forceexit-rank", Placeholder.unparsed("player", target.getName()));
            return;
        }
        boolean left = staffMode.exit(target, StaffModeService.Cause.FORCED);
        boolean wasVanished = vanish.isVanished(target.getUniqueId());
        if (wasVanished) {
            vanish.unvanish(target);
        }
        if (!left && !wasVanished) {
            state.messages().send(sender, "staff.forceexit-none", Placeholder.unparsed("player", target.getName()));
            return;
        }
        state.messages().send(sender, "staff.forceexit-done", Placeholder.unparsed("player", target.getName()));
        logAction(sender instanceof Player p ? p : null, "FORCE_EXIT", target.getUniqueId(), target.getName(), null,
                (left ? "staff mode" : "") + (left && wasVanished ? " and " : "") + (wasVanished ? "vanish" : ""));
        state.messages().send(target, "staff.staffmode-forced", Placeholder.unparsed("staff", actor.name()));
    }

    /** The server's TPS over 1 minute (the staff scoreboard). Overridable for tests. */
    public double tps() {
        return getServer().getTPS()[0];
    }

    /** The TPS over 1, 5 and 15 minutes (the /staff menu's server information). Overridable for tests. */
    public double[] tpsAll() {
        return getServer().getTPS();
    }

    /** The average tick time in ms (the staff scoreboard). Overridable for tests. */
    public double mspt() {
        return getServer().getAverageTickTime();
    }

    public StaffModeService staffMode() {
        return staffMode;
    }

    public FreezeService freeze() {
        return freeze;
    }

    public InspectMenus inspect() {
        return inspect;
    }

    public VanishService vanish() {
        return vanish;
    }

    public PromptService prompts() {
        return prompts;
    }

    @Override
    public void onDisable() {
        if (prompts != null) {
            prompts.dropAll();
        }
        // First, so everyone gets their inventory and state back before anything else is undone.
        if (staffMode != null) {
            staffMode.disable();
        }
        // Nobody stays hidden while MR-Punish is off; vanish itself stays in the PDC (DESIGN.md 11.2).
        if (vanish != null) {
            vanish.disable();
        }
        // The freeze effect is lifted (flight restored); the frozen rows stay, so enabling re-applies it.
        if (freeze != null) {
            freeze.disable();
        }
        // Nobody keeps a menu of a disabled plugin open.
        for (Player player : getServer().getOnlinePlayers()) {
            if (MenuHolder.of(player.getOpenInventory().getTopInventory()) != null) {
                player.closeInventory();
            }
        }
        if (storage == null) {
            return;
        }
        // From here, commands and logins get "the server is stopping" from the closed executor.
        // On /stop, plugins are disabled while players are still online (DESIGN.md 2.4), so their
        // quit would come too late: record it now, then let every submitted write finish.
        if (tracker != null) {
            for (Player player : getServer().getOnlinePlayers()) {
                tracker.record(player);
            }
        }
        if (staffLogFile != null) {
            staffLogFile.close();
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

    /** Re-reads every online player's rank; writes the ones that changed (main thread). */
    private void refreshRanks() {
        for (Player player : getServer().getOnlinePlayers()) {
            if (rankCache.refresh(player)) {
                tracker.record(player);
            }
        }
        // Rank or permission changes change who sees which vanished player (DESIGN.md 11.2).
        if (vanish != null) {
            vanish.refreshAll();
        }
        // Someone who lost mrpunish.staffmode leaves staff mode (DESIGN.md 11.1).
        if (staffMode != null) {
            staffMode.checkPermissions();
        }
    }

    /** Issues and removes punishments. Commands and menus arrive in later steps. */
    public PunishmentService punishments() {
        return punishments;
    }

    public IpFeatures ip() {
        return ip;
    }

    @Override
    public String ipStatus() {
        return ip == null ? "off" : ip.describe();
    }

    public BanEnforcement bans() {
        return bans;
    }

    public MuteEnforcement mutes() {
        return mutes;
    }

    /** The aliases in the server's commands.yml, label to command lines. Protected so tests can supply them. */
    protected Map<String, String[]> commandAliases() {
        return getServer().getCommandAliases();
    }

    /** The server's commands and commands.yml aliases, for the mute's command blocking (DESIGN.md 7.8). */
    public CommandLabelResolver.Lookup commandLookup() {
        return new CommandLabelResolver.Lookup() {
            @Override
            public Optional<CommandLabelResolver.Known> command(String label) {
                org.bukkit.command.Command command = getServer().getCommandMap().getCommand(label);
                return Optional.ofNullable(command).map(c -> new CommandLabelResolver.Known(c.getName(), c.getLabel(),
                        c.getAliases()));
            }

            @Override
            public List<String> aliasTargets(String label) {
                String[] targets = commandAliases().get(label);
                return targets == null ? List.of() : List.of(targets);
            }
        };
    }

    public RankCache rankCache() {
        return rankCache;
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
                label -> CommandClashCheck.owner(commands.ownsInDispatcher(label)
                                || CommandClashCheck.aliasPointsAt(commandAliases().get(label), commands.namespace()),
                        CommandClashCheck.ownerIn(getServer().getCommandMap(), this, label)));
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
            refreshRanks();
        } else if (vanish != null) {
            vanish.refreshAll();
        }
        List<String> restartNeeded = new ArrayList<>();
        if (loaded.settings().ok() && commands.needsRestart(updated.settings())) {
            restartNeeded.add("commands");
        }
        if (loaded.settings().ok() && !updated.settings().storage().equals(storage.settings())) {
            restartNeeded.add("storage");
        }
        if (loaded.settings().ok() && updated.settings().ip().enabled() != ip.settings().enabled()) {
            restartNeeded.add("ip.enabled");
        }
        boolean wantLuckPerms = updated.settings().luckperms()
                && getServer().getPluginManager().isPluginEnabled("LuckPerms");
        if (loaded.settings().ok() && wantLuckPerms != luckPermsHooked) {
            restartNeeded.add("hooks.luckperms");
        }
        if (loaded.settings().ok() && updated.settings().floodgate() != floodgateSetting) {
            restartNeeded.add("bedrock.floodgate");
        }

        Messages messages = updated.messages();
        boolean console = sender instanceof ConsoleCommandSender;
        if (!console) {
            getLogger().info(sender.getName() + " reloaded MR-Punish.");
        }
        logAction(sender instanceof Player p ? p : null, "RELOAD", null, null, null,
                loaded.failures().isEmpty() ? (loaded.warnings().isEmpty() ? "ok" : loaded.warnings().size() + " warning(s)")
                        : "failed: " + String.join(", ", loaded.failures().keySet()));
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
