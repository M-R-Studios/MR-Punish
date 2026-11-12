package com.mrstudios.punish.gui;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.command.FreezeCommands;
import com.mrstudios.punish.command.PunishCommands;
import com.mrstudios.punish.command.PunishMenuCommand;
import com.mrstudios.punish.command.Perms;
import com.mrstudios.punish.command.StaffCommands;
import com.mrstudios.punish.config.GuiSettings;
import com.mrstudios.punish.config.ItemTemplate;
import com.mrstudios.punish.config.Messages;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.punish.PunishmentType;
import com.mrstudios.punish.rank.Authority;
import com.mrstudios.punish.rank.RankDisplay;
import com.mrstudios.punish.report.ReportService;
import com.mrstudios.punish.staff.FreezeService;
import com.mrstudios.punish.staff.StaffChat;
import com.mrstudios.punish.staff.StaffModeService;
import com.mrstudios.punish.staff.VanishService;
import com.mrstudios.punish.storage.FrozenRepo;
import com.mrstudios.punish.storage.PunishmentRepo;
import com.mrstudios.punish.storage.ReportRepo;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

/**
 * The {@code /staff} hub and the menus it opens (DESIGN.md 11.7): the player picker with name
 * search, Online Staff, Freeze Tools and its confirmation, and Server Information. Every button runs
 * the same code as the command it stands for, and checks its permission again at the click: the
 * hub's {@code mrpunish.staff} and the button's own.
 */
public final class StaffMenus {

    public static final String LOGS = "mrpunish.logs";
    private static final int PAGE_SIZE = 45;
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** What a player picker is for, and the permission it needs. */
    public enum Pick {
        INVSEE(InspectMenus.INVSEE), ENDERSEE(InspectMenus.ENDERSEE), HISTORY(HistoryMenus.PERMISSION),
        PUNISH(PunishMenuCommand.PERMISSION), FREEZE(FreezeService.PERMISSION);

        final String permission;

        Pick(String permission) {
            this.permission = permission;
        }
    }

    /** The counts the hub shows, read when it opens and on each Server Information click. */
    record Counts(int openReports, int claimedReports, int bans, int mutes) {

        static final Counts UNKNOWN = new Counts(-1, -1, -1, -1);
    }

    private final MRPunish plugin;
    private final Items items;
    private final PromptService prompts;
    private final PunishCommands shared;
    private final StaffCommands staffCommands;
    private final FreezeCommands freezeCommands;
    private final PunishMenuCommand punishCommand;

    public StaffMenus(MRPunish plugin, Items items, PromptService prompts) {
        this.plugin = plugin;
        this.items = items;
        this.prompts = prompts;
        this.shared = new PunishCommands(plugin);
        this.staffCommands = new StaffCommands(plugin);
        this.freezeCommands = new FreezeCommands(plugin);
        this.punishCommand = new PunishMenuCommand(plugin);
    }

    // ---- Shared helpers ----

    private PluginState state() {
        return plugin.state();
    }

    private GuiSettings gui() {
        return state().gui();
    }

    private Messages messages() {
        return state().messages();
    }

    private void sound(Player player, String name) {
        Settings.SoundSetting s = gui().sound(name);
        if (s.enabled()) {
            player.playSound(Sound.sound(s.sound(), Sound.Source.MASTER, s.volume(), s.pitch()));
        }
    }

    private void denied(Player player, String key, TagResolver... placeholders) {
        sound(player, "denied");
        messages().send(player, key, placeholders);
    }

    /** Whether {@code p} may use a hub button needing {@code permission} right now. */
    private static boolean may(Player p, String permission) {
        return p.hasPermission(Perms.STAFF) && p.hasPermission(permission);
    }

    private String onOff(boolean on) {
        return gui().text(on ? "staff-menu.state-on" : "staff-menu.state-off");
    }

    /**
     * A button needing {@code permission}: greyed (or hidden) without it, and checked again when
     * clicked, before {@code action} runs.
     */
    private void guarded(MenuHolder menu, Player viewer, String path, String permission, TagResolver placeholders,
            MenuHolder.Action action) {
        GuiSettings g = gui();
        ItemTemplate template = g.item(path);
        int slot = g.number(path + ".slot");
        if (!may(viewer, permission)) {
            if (g.noPermissionMode() == GuiSettings.NoPermissionMode.GREY) {
                menu.set(slot, items.build(g.item("no-permission.item"),
                        Placeholder.unparsed("name", MINI.stripTags(template.name()))),
                        (p, click) -> denied(p, "general.no-permission"));
            }
            return;
        }
        menu.set(slot, items.build(template, placeholders), (p, click) -> {
            if (!may(p, permission)) {
                denied(p, "general.no-permission");
                menu.render();
                return;
            }
            sound(p, "click");
            action.run(p, click);
        });
    }

    private void bottom(MenuHolder menu, String path, int page, int pages, Consumer<Integer> turn,
            Consumer<Player> back) {
        GuiSettings g = gui();
        if (page > 0) {
            menu.set(g.number(path + ".previous-slot"), items.build(g.item("common.previous"), TagResolver.empty()),
                    (p, click) -> {
                        sound(p, "page");
                        turn.accept(page - 1);
                    });
        }
        if (page < pages - 1) {
            menu.set(g.number(path + ".next-slot"), items.build(g.item("common.next"), TagResolver.empty()),
                    (p, click) -> {
                        sound(p, "page");
                        turn.accept(page + 1);
                    });
        }
        menu.set(g.number(path + ".back-slot"), items.build(g.item(back == null ? "common.close" : "common.back"),
                TagResolver.empty()), (p, click) -> {
                    sound(p, "click");
                    if (back == null) {
                        p.closeInventory();
                    } else {
                        back.accept(p);
                    }
                });
    }

    private static <T> List<T> slice(List<T> all, int page) {
        return all.subList(Math.min(page * PAGE_SIZE, all.size()), Math.min(page * PAGE_SIZE + PAGE_SIZE, all.size()));
    }

    // ---- The hub ----

    /** {@code /staff}: opens the hub. */
    public void openHub(Player viewer) {
        if (!viewer.hasPermission(Perms.STAFF)) {
            denied(viewer, "general.no-permission");
            return;
        }
        new Hub(viewer).open(viewer);
    }

    CompletableFuture<Counts> counts() {
        if (plugin.storage().executor().isShutDown() || !plugin.storage().health().isUp()) {
            return CompletableFuture.completedFuture(Counts.UNKNOWN);
        }
        long now = System.currentTimeMillis();
        return plugin.storage().executor().submit("counting for the staff menu", c -> {
            ReportRepo reports = new ReportRepo(plugin.storage().database());
            PunishmentRepo punishments = new PunishmentRepo(plugin.storage().database());
            return new Counts(reports.countOpen(c), reports.countClaimed(c),
                    punishments.countActive(c, PunishmentType.BAN, now)
                            + punishments.countActive(c, PunishmentType.IP_BAN, now),
                    punishments.countActive(c, PunishmentType.MUTE, now)
                            + punishments.countActive(c, PunishmentType.IP_MUTE, now));
        }).exceptionally(e -> Counts.UNKNOWN);
    }

    private static String count(int n) {
        return n < 0 ? "?" : String.valueOf(n);
    }

    final class Hub extends MenuHolder {

        private Counts counts;
        private boolean loading;

        Hub(Player viewer) {
            super(viewer.getUniqueId(), gui().number("staff-menu.rows"),
                    Items.render(gui().text("staff-menu.title"), TagResolver.empty()));
        }

        /** Reads the counts again; the hub is drawn again when they arrive, if it's still showing. */
        void refresh() {
            if (loading) {
                return;
            }
            loading = true;
            counts().thenAccept(loaded -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                loading = false;
                counts = loaded;
                if (isShowing()) {
                    render();
                }
            }));
        }

        @Override
        protected void draw() {
            Player viewer = plugin.getServer().getPlayer(viewer());
            if (viewer == null) {
                return;
            }
            if (counts == null) {
                refresh();
            }
            Counts shown = counts == null ? Counts.UNKNOWN : counts;
            GuiSettings g = gui();
            boolean staffMode = plugin.staffMode().inStaffMode(viewer.getUniqueId());
            boolean vanished = plugin.vanish().isVanished(viewer.getUniqueId());
            boolean staffChat = plugin.staffChat().toggledOn(viewer.getUniqueId());
            ItemTemplate head = new ItemTemplate("PLAYER_HEAD", g.text("staff-menu.head.name"),
                    g.lines("staff-menu.head.lore"), false, null);
            set(g.number("staff-menu.head.slot"), items.head(viewer.getUniqueId(), viewer.getName(), head,
                    TagResolver.resolver(Placeholder.unparsed("player", viewer.getName()),
                            Placeholder.parsed("rank", RankDisplay.of(state().ranks(), viewer::hasPermission,
                                    g.text("punish-menu.head.no-rank"))),
                            Placeholder.parsed("staffmode", onOff(staffMode)),
                            Placeholder.parsed("vanish", onOff(vanished)),
                            Placeholder.parsed("staffchat", onOff(staffChat))), Map.of()), null);

            guarded(this, viewer, "staff-menu.reports", ReportService.REPORTS, TagResolver.resolver(
                    Placeholder.unparsed("open", count(shown.openReports())),
                    Placeholder.unparsed("claimed", count(shown.claimedReports()))),
                    (p, click) -> plugin.reportMenus().openList(p, new ReportMenus.View(ReportRepo.Filter.OPEN)));
            guarded(this, viewer, "staff-menu.online-staff", Perms.STAFF, TagResolver.empty(),
                    (p, click) -> openOnlineStaff(p, 0));
            guarded(this, viewer, "staff-menu.staff-chat", StaffChat.PERMISSION,
                    Placeholder.parsed("state", onOff(staffChat)), (p, click) -> {
                        staffCommands.toggleStaffChat(p);
                        render();
                    });
            guarded(this, viewer, "staff-menu.staff-mode", StaffModeService.PERMISSION,
                    Placeholder.parsed("state", onOff(staffMode)), (p, click) -> {
                        p.closeInventory();
                        staffCommands.toggleStaffMode(p);
                    });
            guarded(this, viewer, "staff-menu.vanish", VanishService.USE, Placeholder.parsed("state", onOff(vanished)),
                    (p, click) -> {
                        staffCommands.vanishSelf(p);
                        render();
                    });
            guarded(this, viewer, "staff-menu.freeze-tools", FreezeService.PERMISSION,
                    Placeholder.unparsed("frozen", String.valueOf(plugin.freeze().count())),
                    (p, click) -> openFrozen(p, 0));
            guarded(this, viewer, "staff-menu.server-info", Perms.STAFF, serverInfo(shown), (p, click) -> {
                counts = null;
                render();
            });
            Consumer<Player> back = StaffMenus.this::openHub;
            guarded(this, viewer, "staff-menu.invsee", InspectMenus.INVSEE, TagResolver.empty(),
                    (p, click) -> openPicker(p, Pick.INVSEE, 0, back));
            guarded(this, viewer, "staff-menu.endersee", InspectMenus.ENDERSEE, TagResolver.empty(),
                    (p, click) -> openPicker(p, Pick.ENDERSEE, 0, back));
            guarded(this, viewer, "staff-menu.history", HistoryMenus.PERMISSION, TagResolver.empty(),
                    (p, click) -> openPicker(p, Pick.HISTORY, 0, back));
            guarded(this, viewer, "staff-menu.stafflog", LOGS, TagResolver.empty(),
                    (p, click) -> plugin.staffLogMenus().open(p, new StaffLogMenus.View(null, null, 0, back)));
            guarded(this, viewer, "staff-menu.punish", PunishMenuCommand.PERMISSION, TagResolver.empty(),
                    (p, click) -> openPicker(p, Pick.PUNISH, 0, back));
            set(g.number("staff-menu.close-slot"), items.build(g.item("common.close"), TagResolver.empty()),
                    (p, click) -> {
                        sound(p, "click");
                        p.closeInventory();
                    });
            fill(items.build(g.item("filler"), TagResolver.empty()));
        }
    }

    /** Server Information's lore placeholders (DESIGN.md 11.7). */
    TagResolver serverInfo(Counts counts) {
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMb = runtime.maxMemory() / (1024 * 1024);
        double[] tps = plugin.tpsAll();
        long staff = plugin.getServer().getOnlinePlayers().stream().filter(p -> p.hasPermission(Perms.STAFF)).count();
        return TagResolver.resolver(
                Placeholder.unparsed("tps-1m", tps(tps[0])),
                Placeholder.unparsed("tps-5m", tps(tps[1])),
                Placeholder.unparsed("tps-15m", tps(tps[2])),
                Placeholder.unparsed("mspt", String.format(Locale.ROOT, "%.1f", plugin.mspt())),
                Placeholder.unparsed("memory-used", String.valueOf(usedMb)),
                Placeholder.unparsed("memory-max", String.valueOf(maxMb)),
                Placeholder.unparsed("uptime", com.mrstudios.punish.config.DurationParser.format(
                        Math.max(60_000L, ManagementFactory.getRuntimeMXBean().getUptime()))),
                Placeholder.unparsed("online", String.valueOf(plugin.getServer().getOnlinePlayers().size())),
                Placeholder.unparsed("max", String.valueOf(plugin.getServer().getMaxPlayers())),
                Placeholder.unparsed("staff", String.valueOf(staff)),
                Placeholder.unparsed("vanished", String.valueOf(plugin.vanish().count())),
                Placeholder.unparsed("open-reports", count(counts.openReports())),
                Placeholder.unparsed("bans", count(counts.bans())),
                Placeholder.unparsed("mutes", count(counts.mutes())),
                Placeholder.unparsed("storage", plugin.storage().describeSettings()),
                Placeholder.unparsed("storage-status", plugin.storageStatus()),
                Placeholder.unparsed("version", plugin.pluginVersion()));
    }

    private static String tps(double tps) {
        return String.format(Locale.ROOT, "%.1f", Math.min(20.0, tps));
    }

    // ---- Player picker ----

    /** Opens a picker of the online players {@code viewer} can see (not themselves). */
    public void openPicker(Player viewer, Pick pick, int page, Consumer<Player> back) {
        if (!may(viewer, pick.permission)) {
            denied(viewer, "general.no-permission");
            viewer.closeInventory();
            return;
        }
        new Picker(viewer, pick, page, back).open(viewer);
    }

    /**
     * Runs what the picker is for on {@code name} (a head or a typed name), exactly as the command
     * would, permission checked again now.
     */
    void choose(Player p, Pick pick, String name, Consumer<Player> back) {
        if (!may(p, pick.permission)) {
            denied(p, "general.no-permission");
            p.closeInventory();
            return;
        }
        switch (pick) {
            case INVSEE -> freezeCommands.invsee(p, name);
            case ENDERSEE -> freezeCommands.endersee(p, name);
            case PUNISH -> punishCommand.run(p, name);
            case FREEZE -> {
                p.closeInventory();
                freezeCommands.freeze(p, name);
            }
            case HISTORY -> shared.withTarget(p, name, target -> {
                if (p.isOnline() && may(p, HistoryMenus.PERMISSION)) {
                    plugin.history().open(p, new HistoryMenus.View(target.uuid(), target.name(), 0, back));
                }
            });
        }
    }

    /** "Search by name": asks for a name in chat (or a Bedrock form), then {@link #choose}. */
    void search(Player p, Pick pick, Consumer<Player> back) {
        prompts.ask(p, "input.prompt-search", "input.form-title-search", null,
                name -> choose(p, pick, name, back),
                () -> openPicker(p, pick, 0, back));
    }

    final class Picker extends MenuHolder {

        private final Pick pick;
        private final int page;
        private final int pages;
        private final List<Player> shown;
        private final Consumer<Player> back;

        Picker(Player viewer, Pick pick, int page, Consumer<Player> back) {
            super(viewer.getUniqueId(), gui().number("staff-picker.rows"), title(viewer, pick, page));
            List<Player> all = candidates(viewer);
            this.pick = pick;
            this.pages = HistoryMenus.pages(all.size(), PAGE_SIZE);
            this.page = HistoryMenus.clamp(page, pages);
            this.shown = new ArrayList<>(slice(all, this.page));
            this.back = back;
        }

        @Override
        protected void draw() {
            GuiSettings g = gui();
            ItemTemplate template = new ItemTemplate("PLAYER_HEAD", g.text("staff-picker.entry-name"),
                    g.lines("staff-picker.entry-lore"), false, null);
            int slot = 0;
            for (Player target : shown) {
                String name = target.getName();
                set(slot++, items.head(target.getUniqueId(), name, template, TagResolver.resolver(
                        Placeholder.unparsed("player", name),
                        Placeholder.unparsed("world", target.getWorld().getName())), Map.of()), (who, click) -> {
                            sound(who, "click");
                            choose(who, pick, name, back);
                        });
            }
            set(g.number("staff-picker.search.slot"), items.build(g.item("staff-picker.search"), TagResolver.empty()),
                    (who, click) -> {
                        if (!may(who, pick.permission)) {
                            denied(who, "general.no-permission");
                            who.closeInventory();
                            return;
                        }
                        sound(who, "click");
                        search(who, pick, back);
                    });
            bottom(this, "staff-picker", page, pages, to -> openPicker(plugin.getServer().getPlayer(viewer()), pick, to,
                    back), back);
            fill(items.build(g.item("filler"), TagResolver.empty()));
        }
    }

    private Component title(Player viewer, Pick pick, int page) {
        int pages = HistoryMenus.pages(candidates(viewer).size(), PAGE_SIZE);
        return Items.render(gui().text("staff-picker.title"), TagResolver.resolver(
                Placeholder.parsed("purpose", gui().text("staff-picker.purposes." + pick.name())),
                Placeholder.unparsed("page", String.valueOf(HistoryMenus.clamp(page, pages) + 1)),
                Placeholder.unparsed("pages", String.valueOf(pages))));
    }

    /** Online players {@code viewer} can see, not themselves, by name. */
    private List<Player> candidates(Player viewer) {
        return plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> !p.equals(viewer) && plugin.vanish().canSee(viewer, p))
                .map(p -> (Player) p)
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    // ---- Online Staff ----

    public void openOnlineStaff(Player viewer, int page) {
        if (!may(viewer, Perms.STAFF)) {
            denied(viewer, "general.no-permission");
            viewer.closeInventory();
            return;
        }
        new OnlineStaff(viewer, page).open(viewer);
    }

    /** Online staff {@code viewer} can see, highest rank first. */
    private List<Player> onlineStaff(Player viewer) {
        return plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> p.hasPermission(Perms.STAFF) && plugin.vanish().canSee(viewer, p))
                .map(p -> (Player) p)
                .sorted(Comparator.comparingInt((Player p) -> -plugin.rankCache().read(p).weight())
                        .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    final class OnlineStaff extends MenuHolder {

        private final int page;
        private final int pages;
        private final List<Player> shown;

        OnlineStaff(Player viewer, int page) {
            super(viewer.getUniqueId(), gui().number("online-staff.rows"), Items.render(gui().text("online-staff.title"),
                    TagResolver.resolver(
                            Placeholder.unparsed("page", String.valueOf(HistoryMenus.clamp(page,
                                    HistoryMenus.pages(onlineStaff(viewer).size(), PAGE_SIZE)) + 1)),
                            Placeholder.unparsed("pages", String.valueOf(HistoryMenus.pages(
                                    onlineStaff(viewer).size(), PAGE_SIZE))))));
            List<Player> all = onlineStaff(viewer);
            this.pages = HistoryMenus.pages(all.size(), PAGE_SIZE);
            this.page = HistoryMenus.clamp(page, pages);
            this.shown = new ArrayList<>(slice(all, this.page));
        }

        @Override
        protected void draw() {
            GuiSettings g = gui();
            Player viewer = plugin.getServer().getPlayer(viewer());
            if (viewer == null) {
                return;
            }
            boolean admin = viewer.hasPermission(Perms.ADMIN);
            ItemTemplate template = new ItemTemplate("PLAYER_HEAD", g.text("online-staff.entry-name"),
                    g.lines("online-staff.entry-lore"), false, null);
            List<Component> adminLines = !admin ? List.of() : g.lines("online-staff.admin-lore").stream()
                    .map(line -> Items.render(line, TagResolver.empty())).toList();
            String noRank = g.text("punish-menu.head.no-rank");
            int slot = 0;
            for (Player staff : shown) {
                String name = staff.getName();
                set(slot++, items.head(staff.getUniqueId(), name, template, TagResolver.resolver(
                        Placeholder.unparsed("player", name),
                        Placeholder.parsed("rank", RankDisplay.of(state().ranks(), staff::hasPermission, noRank)),
                        Placeholder.parsed("staffmode", onOff(plugin.staffMode().inStaffMode(staff.getUniqueId()))),
                        Placeholder.parsed("vanish", onOff(plugin.vanish().isVanished(staff.getUniqueId())))),
                        Map.of("admin-lore", adminLines)), (who, click) -> clicked(who, name, click));
            }
            bottom(this, "online-staff", page, pages, to -> openOnlineStaff(plugin.getServer().getPlayer(viewer()), to),
                    StaffMenus.this::openHub);
            fill(items.build(g.item("filler"), TagResolver.empty()));
        }

        private void clicked(Player who, String name, ClickType click) {
            if (!may(who, Perms.STAFF)) {
                denied(who, "general.no-permission");
                who.closeInventory();
                return;
            }
            if (!who.hasPermission(Perms.ADMIN)) {
                return; // Only admins can do anything with an entry.
            }
            sound(who, "click");
            if (click.isShiftClick()) {
                openConfirm(who, gui().text("staff-confirm.forceexit").replace("<player>", MINI.escapeTags(name)),
                        p -> {
                            if (!may(p, Perms.ADMIN)) {
                                denied(p, "general.no-permission");
                                return;
                            }
                            p.closeInventory();
                            plugin.forceExit(p, name);
                        }, p -> openOnlineStaff(p, page));
                return;
            }
            who.closeInventory();
            plugin.staffProfile(who, name);
        }
    }

    // ---- Confirmation ----

    /**
     * A yes/no menu. {@code yes} runs on Confirm (it checks its own permission again); {@code no}
     * on Cancel.
     */
    public void openConfirm(Player viewer, String what, Consumer<Player> yes, Consumer<Player> no) {
        new Confirm(viewer, what, yes, no).open(viewer);
    }

    final class Confirm extends MenuHolder {

        private final String what;
        private final Consumer<Player> yes;
        private final Consumer<Player> no;

        Confirm(Player viewer, String what, Consumer<Player> yes, Consumer<Player> no) {
            super(viewer.getUniqueId(), gui().number("staff-confirm.rows"),
                    Items.render(gui().text("staff-confirm.title"), TagResolver.empty()));
            this.what = what;
            this.yes = yes;
            this.no = no;
        }

        @Override
        protected void draw() {
            GuiSettings g = gui();
            set(g.number("staff-confirm.confirm.slot"), items.build(g.item("staff-confirm.confirm"),
                    Placeholder.parsed("what", what)), (p, click) -> {
                        sound(p, "click");
                        yes.accept(p);
                    });
            set(g.number("staff-confirm.cancel.slot"), items.build(g.item("staff-confirm.cancel"),
                    Placeholder.parsed("what", what)), (p, click) -> {
                        sound(p, "click");
                        no.accept(p);
                    });
            fill(items.build(g.item("filler"), TagResolver.empty()));
        }
    }

    // ---- Freeze Tools ----

    /** Loads every frozen player (online and offline, from the database), then opens the list. */
    public void openFrozen(Player viewer, int page) {
        if (!may(viewer, FreezeService.PERMISSION)) {
            denied(viewer, "general.no-permission");
            viewer.closeInventory();
            return;
        }
        if (plugin.storage().executor().isShutDown() || !plugin.storage().health().isUp()) {
            messages().send(viewer, "general.storage-unavailable");
            return;
        }
        plugin.storage().executor().submit("loading the frozen players",
                c -> new FrozenRepo(plugin.storage().database()).all(c))
                .whenComplete((rows, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!viewer.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        messages().send(viewer, "general.storage-unavailable");
                        return;
                    }
                    if (!may(viewer, FreezeService.PERMISSION)) {
                        denied(viewer, "general.no-permission");
                        viewer.closeInventory();
                        return;
                    }
                    List<FrozenRepo.Frozen> sorted = rows.stream()
                            .sorted(Comparator.comparingLong(FrozenRepo.Frozen::at).reversed()).toList();
                    new Frozen(viewer, sorted, page).open(viewer);
                }));
    }

    void unfreeze(Player p, FrozenRepo.Frozen row, int page) {
        if (!may(p, FreezeService.PERMISSION)) {
            denied(p, "general.no-permission");
            p.closeInventory();
            return;
        }
        Authority.Standing actor = shared.actor(p).standing();
        plugin.freeze().unfreeze(actor, row.uuid(), row.name()).thenAccept(outcome -> {
            switch (outcome) {
                case FreezeService.Outcome.Done d -> messages().send(p, "staff.unfreeze-done",
                        Placeholder.unparsed("player", row.name()));
                case FreezeService.Outcome.NotFrozen n -> messages().send(p, "staff.not-frozen",
                        Placeholder.unparsed("player", row.name()));
                case FreezeService.Outcome.Unavailable u -> messages().send(p, "general.storage-unavailable");
                case FreezeService.Outcome.Failed f -> messages().send(p, "staff.freeze-failed",
                        Placeholder.unparsed("error-id", f.errorId()));
                default -> { }
            }
            if (p.isOnline()) {
                openFrozen(p, page);
            }
        });
    }

    final class Frozen extends MenuHolder {

        private final List<FrozenRepo.Frozen> shown;
        private final int page;
        private final int pages;

        Frozen(Player viewer, List<FrozenRepo.Frozen> all, int page) {
            super(viewer.getUniqueId(), gui().number("frozen-menu.rows"), Items.render(gui().text("frozen-menu.title"),
                    TagResolver.resolver(
                            Placeholder.unparsed("page", String.valueOf(HistoryMenus.clamp(page,
                                    HistoryMenus.pages(all.size(), PAGE_SIZE)) + 1)),
                            Placeholder.unparsed("pages", String.valueOf(HistoryMenus.pages(all.size(), PAGE_SIZE))))));
            this.pages = HistoryMenus.pages(all.size(), PAGE_SIZE);
            this.page = HistoryMenus.clamp(page, pages);
            this.shown = new ArrayList<>(slice(all, this.page));
        }

        @Override
        protected void draw() {
            GuiSettings g = gui();
            Player viewer = plugin.getServer().getPlayer(viewer());
            if (viewer == null) {
                return;
            }
            if (shown.isEmpty()) {
                set(g.number("frozen-menu.empty.slot"), items.build(g.item("frozen-menu.empty"), TagResolver.empty()),
                        null);
            }
            ItemTemplate template = new ItemTemplate("PLAYER_HEAD", g.text("frozen-menu.entry-name"),
                    g.lines("frozen-menu.entry-lore"), false, null);
            int slot = 0;
            for (FrozenRepo.Frozen row : shown) {
                boolean online = plugin.getServer().getPlayer(row.uuid()) != null;
                set(slot++, items.head(row.uuid(), row.name(), template, TagResolver.resolver(
                        Placeholder.unparsed("player", row.name()),
                        Placeholder.unparsed("by", row.byName()),
                        Placeholder.unparsed("since", state().settings().display().date(row.at())),
                        Placeholder.unparsed("server", String.valueOf(row.server())),
                        Placeholder.parsed("status", g.text(online ? "frozen-menu.online" : "frozen-menu.offline"))),
                        Map.of()), (who, click) -> {
                            if (!may(who, FreezeService.PERMISSION)) {
                                denied(who, "general.no-permission");
                                who.closeInventory();
                                return;
                            }
                            sound(who, "click");
                            openConfirm(who, g.text("staff-confirm.unfreeze").replace("<player>",
                                    MINI.escapeTags(row.name())), p -> unfreeze(p, row, page), p -> openFrozen(p, page));
                        });
            }
            guarded(this, viewer, "frozen-menu.freeze", FreezeService.PERMISSION, TagResolver.empty(),
                    (p, click) -> openPicker(p, Pick.FREEZE, 0, back -> openFrozen(back, page)));
            bottom(this, "frozen-menu", page, pages, to -> openFrozen(plugin.getServer().getPlayer(viewer()), to),
                    StaffMenus.this::openHub);
            fill(items.build(g.item("filler"), TagResolver.empty()));
        }
    }
}
