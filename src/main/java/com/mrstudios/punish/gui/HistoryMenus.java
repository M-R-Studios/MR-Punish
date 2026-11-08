package com.mrstudios.punish.gui;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.command.PunishCommands;
import com.mrstudios.punish.config.DurationParser;
import com.mrstudios.punish.config.GuiSettings;
import com.mrstudios.punish.config.ItemTemplate;
import com.mrstudios.punish.config.Messages;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.PunishmentService;
import com.mrstudios.punish.punish.PunishmentType;
import com.mrstudios.punish.punish.Requests;
import com.mrstudios.punish.storage.IpRepo;
import com.mrstudios.punish.storage.PunishmentRepo;
import com.mrstudios.punish.storage.PunishmentRepo.HistoryFilter;
import com.mrstudios.punish.storage.PunishmentRepo.StatusFilter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/**
 * The history menu and its detail menu (DESIGN.md 10.5). Entries are the account's punishments plus
 * IP punishments on any IP it was seen with recently; filtering and paging happen in the query.
 * Removal (right-click on an active entry, or the detail menu's Remove button) asks for a reason and
 * goes through {@link PunishmentService#remove}, with permission and the issuer's rank checked at the
 * click and again in the transaction. Nothing is ever deleted: a removed entry stays, marked removed.
 */
public final class HistoryMenus {

    public static final String PERMISSION = "mrpunish.history";
    public static final int PAGE_SIZE = 45;
    /** Console lines per page. */
    public static final int CONSOLE_PAGE_SIZE = 10;

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final List<PunishmentType> TYPE_CYCLE = List.of(PunishmentType.WARN, PunishmentType.MUTE,
            PunishmentType.BAN, PunishmentType.IP_MUTE, PunishmentType.IP_BAN);

    private final MRPunish plugin;
    private final PunishCommands shared;
    private final Items items;
    private final PromptService prompts;

    public HistoryMenus(MRPunish plugin, Items items, PromptService prompts) {
        this.plugin = plugin;
        this.shared = new PunishCommands(plugin);
        this.items = items;
        this.prompts = prompts;
    }

    /** The next type filter: all, then each type, then all again. */
    public static PunishmentType nextType(PunishmentType current) {
        if (current == null) {
            return TYPE_CYCLE.get(0);
        }
        int i = TYPE_CYCLE.indexOf(current);
        return i + 1 < TYPE_CYCLE.size() ? TYPE_CYCLE.get(i + 1) : null;
    }

    public static StatusFilter nextStatus(StatusFilter current) {
        StatusFilter[] all = StatusFilter.values();
        return all[(current.ordinal() + 1) % all.length];
    }

    /** Pages for {@code total} entries (at least 1), and {@code page} clamped to them (0-based). */
    public static int pages(int total, int size) {
        return Math.max(1, (total + size - 1) / size);
    }

    public static int clamp(int page, int pages) {
        return Math.max(0, Math.min(page, pages - 1));
    }

    /** What one history page shows. */
    public record Page(int total, int page, int pages, List<Punishment> entries, Map<String, Integer> accountsByHash) {
    }

    /** Whose history, and how it is being looked at. */
    public static final class View {

        final UUID targetUuid;
        final String targetName;
        int page;
        PunishmentType type;
        StatusFilter status = StatusFilter.ALL;
        /** Where Back goes; null closes. */
        final Consumer<Player> back;
        /** Bumped by every open: only the newest requested page is ever shown. */
        int generation;

        public View(UUID targetUuid, String targetName, int page, Consumer<Player> back) {
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.page = page;
            this.back = back;
        }

        HistoryFilter filter() {
            return new HistoryFilter(type, status);
        }
    }

    // ---- Loading ----

    /** The hashes whose IP punishments belong in the account's history (DESIGN.md 8.2). */
    private List<String> hashes(Connection c, UUID uuid, long now) throws SQLException {
        List<String> hashes = new ArrayList<>(new IpRepo(plugin.storage().database())
                .hashesOf(c, uuid, plugin.ip().associationSince(now)));
        plugin.ip().onlineHash(uuid).filter(h -> !hashes.contains(h)).ifPresent(hashes::add);
        return hashes;
    }

    /** Every punishment in the account's history, newest first (punish-menu head). Database thread. */
    public List<Punishment> all(Connection c, UUID uuid, long now) throws SQLException {
        return new PunishmentRepo(plugin.storage().database()).history(c, uuid, hashes(c, uuid, now),
                HistoryFilter.ALL, now, 0, Integer.MAX_VALUE);
    }

    /** One page, clamped. {@code size} is 45 for the menu and 10 for the console. */
    public CompletableFuture<Page> load(UUID uuid, String name, HistoryFilter filter, int page, int size) {
        long now = System.currentTimeMillis();
        long since = plugin.ip().associationSince(now);
        return plugin.storage().executor().submit("loading the history of " + name, c -> {
            PunishmentRepo repo = new PunishmentRepo(plugin.storage().database());
            List<String> hashes = hashes(c, uuid, now);
            int total = repo.countHistory(c, uuid, hashes, filter, now);
            int pages = pages(total, size);
            int shown = clamp(page, pages);
            List<Punishment> entries = repo.history(c, uuid, hashes, filter, now, shown * size, size);
            Map<String, Integer> accounts = new HashMap<>();
            IpRepo ips = new IpRepo(plugin.storage().database());
            for (Punishment p : entries) {
                if (p.ipHash() != null && !accounts.containsKey(p.ipHash())) {
                    accounts.put(p.ipHash(), Math.max(1, ips.associated(c, p.ipHash(), since).size()));
                }
            }
            return new Page(total, shown, pages, entries, accounts);
        });
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

    private String typeName(PunishmentType type) {
        return gui().text("type-names." + type.name());
    }

    /** ACTIVE, EXPIRED, REMOVED, REPLACED, or NOT_ENFORCED for an active IP punishment while IP features are off. */
    String status(Punishment p, long now) {
        Punishment.EffectiveStatus effective = p.effectiveStatus(now);
        if (effective == Punishment.EffectiveStatus.ACTIVE && p.type().isIp() && !plugin.ip().enabled()) {
            return "NOT_ENFORCED";
        }
        return effective.name();
    }

    private String duration(Punishment p) {
        if (!p.type().hasDuration()) {
            return "-";
        }
        return p.durationMs() == null ? messages().raw("general.permanent") : DurationParser.format(p.durationMs());
    }

    /** Whether the viewer could remove {@code p} now (permission only; rank is checked at the click). */
    static boolean mayRemove(Player viewer, Punishment p) {
        return viewer.hasPermission(PunishmentService.REMOVE)
                && (!p.type().isIp() || viewer.hasPermission(PunishmentService.IP));
    }

    /** The entry item: material by status, lore with every detail that applies. */
    ItemStack entry(Player viewer, Punishment p, Page page, long now, boolean withAction) {
        GuiSettings g = gui();
        String status = status(p, now);
        ItemTemplate look = g.item("history-menu.status-items." + status);
        Settings.Display display = state().settings().display();
        List<Component> note = p.note() == null ? List.of()
                : List.of(Items.render(g.text("history-menu.note-line"), Placeholder.unparsed("note", p.note())));
        List<Component> removed = p.status() != Punishment.StoredStatus.REMOVED ? List.of()
                : List.of(Items.render(g.text("history-menu.removed-line"), TagResolver.resolver(
                        Placeholder.unparsed("by", String.valueOf(p.removedByName())),
                        Placeholder.unparsed("date", p.removedAt() == null ? "?" : display.date(p.removedAt())),
                        Placeholder.unparsed("reason", String.valueOf(p.removedReason())))));
        List<Component> replaced = p.status() != Punishment.StoredStatus.REPLACED ? List.of()
                : List.of(Items.render(g.text("history-menu.replaced-line"), TagResolver.resolver(
                        Placeholder.unparsed("by-id", String.valueOf(p.replacedById())),
                        Placeholder.unparsed("date", p.replacedAt() == null ? "?" : display.date(p.replacedAt())))));
        List<Component> ip = p.ipHash() == null ? List.of()
                : List.of(Items.render(g.text("history-menu.ip-line"), Placeholder.unparsed("accounts",
                        String.valueOf(page == null ? 1 : page.accountsByHash().getOrDefault(p.ipHash(), 1)))));
        List<Component> action = !withAction ? List.of()
                : List.of(Items.render(g.text(status.equals("ACTIVE") && mayRemove(viewer, p)
                        ? "history-menu.action-remove" : "history-menu.action-details"), TagResolver.empty()));
        ItemTemplate template = new ItemTemplate(look.material(), g.text("history-menu.entry-name"),
                g.lines("history-menu.entry-lore"), look.glint(), look.customModelData());
        TagResolver placeholders = TagResolver.resolver(
                Placeholder.unparsed("id", String.valueOf(p.id())),
                Placeholder.unparsed("type-name", typeName(p.type())),
                Placeholder.parsed("reason", p.reasonDisplay()),
                Placeholder.parsed("status", messages().raw("status." + status)),
                Placeholder.unparsed("duration", duration(p)),
                Placeholder.unparsed("issued", display.date(p.issuedAt())),
                Placeholder.unparsed("expires", p.expiresAt() == null ? g.text("history-menu.never")
                        : display.date(p.expiresAt())),
                Placeholder.unparsed("staff", p.issuerName()),
                Placeholder.unparsed("offence-ordinal", PunishMenus.ordinal(p.offence())));
        return items.build(template, placeholders, Map.of("note-line", note, "removed-line", removed,
                "replaced-line", replaced, "ip-line", ip, "action-line", action));
    }

    // ---- Entry points ----

    /**
     * Loads the page {@code view} asks for, then opens it. The menu the viewer has open (if any)
     * stays until the page has arrived, so a menu is never shown without its entries. If the view is
     * opened again meanwhile (another filter or page click), only the newest request is shown.
     */
    public void open(Player viewer, View view) {
        int generation = ++view.generation;
        HistoryFilter filter = view.filter();
        load(view.targetUuid, view.targetName, filter, view.page, PAGE_SIZE).whenComplete((loaded, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!viewer.isOnline() || generation != view.generation) {
                        return;
                    }
                    if (error != null) {
                        messages().send(viewer, "general.storage-unavailable");
                        return;
                    }
                    if (!viewer.hasPermission(PERMISSION)) {
                        denied(viewer, "general.no-permission");
                        viewer.closeInventory();
                        return;
                    }
                    view.page = loaded.page();
                    new HistoryMenu(viewer, view, loaded, filter).open(viewer);
                }));
    }

    /** Console (and any non-player): 10 lines per page. */
    public void printTo(org.bukkit.command.CommandSender sender, UUID uuid, String name, int page) {
        load(uuid, name, HistoryFilter.ALL, page, CONSOLE_PAGE_SIZE).whenComplete((loaded, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        messages().send(sender, "general.storage-unavailable");
                        return;
                    }
                    if (loaded.total() == 0) {
                        messages().send(sender, "history.empty", Placeholder.unparsed("player", name));
                        return;
                    }
                    long now = System.currentTimeMillis();
                    messages().send(sender, "history.console-header", Placeholder.unparsed("player", name),
                            Placeholder.unparsed("total", String.valueOf(loaded.total())),
                            Placeholder.unparsed("page", String.valueOf(loaded.page() + 1)),
                            Placeholder.unparsed("pages", String.valueOf(loaded.pages())));
                    for (Punishment p : loaded.entries()) {
                        String type = messages().raw("types." + p.type().name());
                        messages().send(sender, "history.console-line",
                                Placeholder.unparsed("id", String.valueOf(p.id())),
                                Placeholder.unparsed("type", p.ipHash() != null ? type + " (IP)" : type),
                                Placeholder.parsed("reason", p.reasonDisplay()),
                                Placeholder.unparsed("date", state().settings().display().date(p.issuedAt())),
                                Placeholder.unparsed("duration", duration(p)),
                                Placeholder.unparsed("staff", p.issuerName()),
                                Placeholder.parsed("status", messages().raw("status." + status(p, now))));
                    }
                }));
    }

    // ---- Removal ----

    /**
     * Starts removing {@code p} (right-click, or the detail menu's Remove): checks now, asks for a
     * reason, checks again, removes, and shows the history page again from the database.
     */
    void startRemoval(Player viewer, View view, Punishment p) {
        if (!refuseRemoval(viewer, view, p)) {
            return;
        }
        sound(viewer, "click");
        Runnable reopen = () -> open(viewer, view);
        prompts.ask(viewer, "history.remove-prompt", "input.form-title-reason", null, text -> {
            if (!refuseRemoval(viewer, view, p)) {
                reopen.run();
                return;
            }
            String reason = text.equals("-") ? null : text;
            Requests.Target target = Requests.Target.offline(view.targetUuid, view.targetName);
            Requests.Remove request = new Requests.Remove(shared.actor(viewer), target, p.type(), p.id(), reason,
                    p.ipHash() == null ? List.of() : List.of(p.ipHash()));
            plugin.punishments().remove(request).thenAccept(result -> {
                if (!viewer.isOnline()) {
                    return;
                }
                if (result instanceof Requests.RemoveResult.NotFound) {
                    denied(viewer, "history.not-active", Placeholder.unparsed("id", String.valueOf(p.id())));
                } else {
                    shared.report(viewer, request, result);
                }
                reopen.run();
            });
        }, reopen, Placeholder.unparsed("id", String.valueOf(p.id())));
    }

    /** True if the viewer may remove {@code p} right now; otherwise says why and returns false. */
    private boolean refuseRemoval(Player viewer, View view, Punishment p) {
        if (!mayRemove(viewer, p)) {
            denied(viewer, "general.no-permission");
            return false;
        }
        if (!p.isActive(System.currentTimeMillis())) {
            denied(viewer, "history.not-active", Placeholder.unparsed("id", String.valueOf(p.id())));
            return false;
        }
        if (viewer.getUniqueId().equals(view.targetUuid)) {
            denied(viewer, "unpunish.self");
            return false;
        }
        // The issuer-weight rule (DESIGN.md 7.6), read now; the transaction checks it again.
        int weight = plugin.rankCache().standing(viewer).weight();
        if (p.issuerWeight() > weight) {
            denied(viewer, "unpunish.issuer-rank", Placeholder.unparsed("id", String.valueOf(p.id())));
            return false;
        }
        return true;
    }

    // ---- Menus ----

    final class HistoryMenu extends MenuHolder {

        private final View view;
        private final Page page;
        /** The filter this page was loaded with (the view may have moved on since). */
        private final HistoryFilter filter;

        HistoryMenu(Player viewer, View view, Page loaded, HistoryFilter filter) {
            super(viewer.getUniqueId(), gui().number("history-menu.rows"), Items.render(gui().text("history-menu.title"),
                    TagResolver.resolver(Placeholder.unparsed("player", view.targetName),
                            Placeholder.unparsed("page", String.valueOf(loaded.page() + 1)),
                            Placeholder.unparsed("pages", String.valueOf(loaded.pages())))));
            this.view = view;
            this.page = loaded;
            this.filter = filter;
        }

        @Override
        protected void draw() {
            GuiSettings g = gui();
            Player viewer = plugin.getServer().getPlayer(viewer());
            long now = System.currentTimeMillis();
            if (page.entries().isEmpty()) {
                set(g.number("history-menu.empty.slot"), items.build(g.item("history-menu.empty"),
                        TagResolver.empty()), null);
            } else {
                int slot = 0;
                for (Punishment p : page.entries()) {
                    set(slot++, entry(viewer, p, page, now, true), (who, click) -> clicked(who, p, click));
                }
            }
            if (page.page() > 0) {
                set(g.number("history-menu.previous-slot"), items.build(g.item("common.previous"), TagResolver.empty()),
                        (who, click) -> turn(who, filter, page.page() - 1));
            }
            if (page.page() < page.pages() - 1) {
                set(g.number("history-menu.next-slot"), items.build(g.item("common.next"), TagResolver.empty()),
                        (who, click) -> turn(who, filter, page.page() + 1));
            }
            String all = g.text("history-menu.filter-all");
            set(g.number("history-menu.type-filter.slot"), items.build(g.item("history-menu.type-filter"),
                    Placeholder.unparsed("filter", filter.type() == null ? all : typeName(filter.type()))),
                    (who, click) -> turn(who, new HistoryFilter(nextType(filter.type()), filter.status()), 0));
            set(g.number("history-menu.status-filter.slot"), items.build(g.item("history-menu.status-filter"),
                    Placeholder.parsed("filter", filter.status() == StatusFilter.ALL ? MINI.escapeTags(all)
                            : messages().raw("status." + filter.status().name()))),
                    (who, click) -> turn(who, new HistoryFilter(filter.type(), nextStatus(filter.status())), 0));
            set(g.number("history-menu.back-slot"), items.build(g.item(view.back == null ? "common.close"
                    : "common.back"), TagResolver.empty()), (who, click) -> {
                        sound(who, "click");
                        if (view.back == null) {
                            who.closeInventory();
                        } else {
                            view.back.accept(who);
                        }
                    });
            fill(items.build(g.item("filler"), TagResolver.empty()));
        }

        /** Shows {@code to} of {@code next}, computed from what this menu shows (not the live view). */
        private void turn(Player who, HistoryFilter next, int to) {
            if (!who.hasPermission(PERMISSION)) {
                denied(who, "general.no-permission");
                who.closeInventory();
                return;
            }
            sound(who, "page");
            view.type = next.type();
            view.status = next.status();
            view.page = Math.max(0, to);
            HistoryMenus.this.open(who, view);
        }

        private void clicked(Player who, Punishment p, ClickType click) {
            if (!who.hasPermission(PERMISSION)) {
                denied(who, "general.no-permission");
                who.closeInventory();
                return;
            }
            if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
                startRemoval(who, view, p);
                return;
            }
            sound(who, "click");
            new DetailMenu(who, view, p, page).open(who);
        }
    }

    final class DetailMenu extends MenuHolder {

        private final View view;
        private final Punishment entry;
        private final Page page;

        DetailMenu(Player viewer, View view, Punishment entry, Page page) {
            super(viewer.getUniqueId(), gui().number("history-detail.rows"), Items.render(gui().text("history-detail.title"),
                    TagResolver.resolver(Placeholder.unparsed("id", String.valueOf(entry.id())),
                            Placeholder.unparsed("player", view.targetName))));
            this.view = view;
            this.entry = entry;
            this.page = page;
        }

        @Override
        protected void draw() {
            GuiSettings g = gui();
            Player viewer = plugin.getServer().getPlayer(viewer());
            long now = System.currentTimeMillis();
            set(g.number("history-detail.entry-slot"), entry(viewer, entry, page, now, false), null);
            boolean removable = entry.isActive(now);
            if (!removable) {
                set(g.number("history-detail.not-removable.slot"), items.build(g.item("history-detail.not-removable"),
                        TagResolver.empty()), (who, click) -> denied(who, "history.not-active",
                                Placeholder.unparsed("id", String.valueOf(entry.id()))));
            } else if (viewer == null || !mayRemove(viewer, entry)) {
                ItemTemplate remove = g.item("history-detail.remove");
                if (g.noPermissionMode() == GuiSettings.NoPermissionMode.GREY) {
                    set(g.number("history-detail.remove.slot"), items.build(g.item("no-permission.item"),
                            Placeholder.unparsed("name", MINI.stripTags(remove.name()
                                    .replace("<type-name>", typeName(entry.type()))))),
                            (who, click) -> denied(who, "general.no-permission"));
                }
            } else {
                set(g.number("history-detail.remove.slot"), items.build(g.item("history-detail.remove"),
                        Placeholder.unparsed("type-name", typeName(entry.type()))),
                        (who, click) -> startRemoval(who, view, entry));
            }
            set(g.number("history-detail.back-slot"), items.build(g.item("common.back"), TagResolver.empty()),
                    (who, click) -> {
                        sound(who, "click");
                        HistoryMenus.this.open(who, view);
                    });
            fill(items.build(g.item("filler"), TagResolver.empty()));
        }
    }
}
