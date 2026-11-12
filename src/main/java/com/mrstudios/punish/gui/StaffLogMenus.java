package com.mrstudios.punish.gui;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.config.GuiSettings;
import com.mrstudios.punish.config.ItemTemplate;
import com.mrstudios.punish.config.Messages;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.report.ReportService;
import com.mrstudios.punish.staff.StaffLog;
import com.mrstudios.punish.storage.ActionRepo;
import com.mrstudios.punish.storage.ReportRepo;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /stafflog [staff] [page]} and the hub's Recent Staff Actions (DESIGN.md 13.6): the staff
 * action log, newest first, 45 a page, one item per action coloured by its group. Filters: the
 * action group, and "only this staff member" when one was given. A PUNISH or REMOVE entry opens the
 * target's history at that punishment; a report entry opens the report. Console gets 10 lines a
 * page. Anything that looks like an IP is scrubbed before it's shown.
 */
public final class StaffLogMenus {

    public static final String PERMISSION = StaffMenus.LOGS;
    public static final int PAGE_SIZE = 45;
    public static final int CONSOLE_PAGE_SIZE = 10;

    /** One loaded page. */
    public record Page(int total, int page, int pages, List<ActionRepo.Logged> entries) {
    }

    /** What is being looked at; kept across pages, filters and the menus an entry opens. */
    public static final class View {

        final UUID actor;
        final String actorName;
        StaffLog.Group group = StaffLog.Group.ALL;
        /** Only the given staff member's actions (on when one was given). */
        boolean onlyActor;
        int page;
        /** Where Back goes; null closes. */
        final Consumer<Player> back;
        int generation;

        /** @param actor a staff member to filter by, or null */
        public View(UUID actor, String actorName, int page, Consumer<Player> back) {
            this.actor = actor;
            this.actorName = actorName;
            this.onlyActor = actor != null;
            this.page = page;
            this.back = back;
        }
    }

    private final MRPunish plugin;
    private final Items items;

    public StaffLogMenus(MRPunish plugin, Items items) {
        this.plugin = plugin;
        this.items = items;
    }

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

    private void denied(Player player, String key) {
        sound(player, "denied");
        messages().send(player, key);
    }

    /** One page, clamped (0-based). */
    public CompletableFuture<Page> load(StaffLog.Group group, UUID actor, int page, int size) {
        if (plugin.storage().executor().isShutDown() || !plugin.storage().health().isUp()) {
            return CompletableFuture.failedFuture(new IllegalStateException("storage unavailable"));
        }
        List<String> actions = group.actions();
        return plugin.storage().executor().submit("loading the staff log", c -> {
            ActionRepo repo = new ActionRepo(plugin.storage().database());
            int total = repo.count(c, actions, actor);
            int pages = HistoryMenus.pages(total, size);
            int shown = HistoryMenus.clamp(page, pages);
            return new Page(total, shown, pages, repo.page(c, actions, actor, shown * size, size));
        });
    }

    /** Loads the page {@code view} asks for, then opens it (only the newest request is shown). */
    public void open(Player viewer, View view) {
        if (!viewer.hasPermission(PERMISSION)) {
            denied(viewer, "general.no-permission");
            viewer.closeInventory();
            return;
        }
        int generation = ++view.generation;
        StaffLog.Group group = view.group;
        load(group, view.onlyActor ? view.actor : null, view.page, PAGE_SIZE).whenComplete((loaded, error) ->
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
                    new LogMenu(viewer, view, loaded, group).open(viewer);
                }));
    }

    /** Console (and any non-player): 10 lines a page. */
    public void printTo(CommandSender sender, UUID actor, String actorName, int page) {
        load(StaffLog.Group.ALL, actor, page, CONSOLE_PAGE_SIZE).whenComplete((loaded, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        messages().send(sender, "general.storage-unavailable");
                        return;
                    }
                    if (loaded.total() == 0) {
                        messages().send(sender, "staff.stafflog-empty");
                        return;
                    }
                    messages().send(sender, "staff.stafflog-header",
                            Placeholder.unparsed("filter", actorName == null ? gui().text("stafflog-menu.groups.ALL")
                                    : actorName),
                            Placeholder.unparsed("page", String.valueOf(loaded.page() + 1)),
                            Placeholder.unparsed("pages", String.valueOf(loaded.pages())));
                    for (ActionRepo.Logged row : loaded.entries()) {
                        ActionRepo.Action a = row.action();
                        messages().send(sender, "staff.stafflog-line",
                                Placeholder.unparsed("date", state().settings().display().date(a.at())),
                                Placeholder.unparsed("actor", a.actorName()),
                                Placeholder.unparsed("action", a.action()),
                                Placeholder.unparsed("target", a.targetName() == null
                                        ? gui().text("stafflog-menu.none") : a.targetName()),
                                Placeholder.unparsed("details", details(a)));
                    }
                }));
    }

    /** The details as shown: ref id first, IPs scrubbed. */
    static String details(ActionRepo.Action a) {
        String details = a.details() == null ? "" : a.details().replace('\n', ' ').replace('\r', ' ');
        return StaffLog.scrub((a.refId() == null ? "" : "#" + a.refId() + " ") + details).strip();
    }

    /** Whether clicking this entry opens something: a punishment's history or a report. */
    static boolean opensHistory(ActionRepo.Action a) {
        return (a.action().equals("PUNISH") || a.action().equals("REMOVE")) && a.refId() != null
                && a.targetUuid() != null;
    }

    static boolean opensReport(ActionRepo.Action a) {
        return a.action().startsWith("REPORT_") && a.refId() != null;
    }

    final class LogMenu extends MenuHolder {

        private final View view;
        private final Page page;
        private final StaffLog.Group group;

        LogMenu(Player viewer, View view, Page loaded, StaffLog.Group group) {
            super(viewer.getUniqueId(), gui().number("stafflog-menu.rows"), Items.render(gui().text("stafflog-menu.title"),
                    TagResolver.resolver(Placeholder.unparsed("page", String.valueOf(loaded.page() + 1)),
                            Placeholder.unparsed("pages", String.valueOf(loaded.pages())))));
            this.view = view;
            this.page = loaded;
            this.group = group;
        }

        @Override
        protected void draw() {
            GuiSettings g = gui();
            if (page.entries().isEmpty()) {
                set(g.number("stafflog-menu.empty.slot"), items.build(g.item("stafflog-menu.empty"), TagResolver.empty()),
                        null);
            }
            int slot = 0;
            for (ActionRepo.Logged row : page.entries()) {
                ActionRepo.Action a = row.action();
                set(slot++, entry(a), (who, click) -> clicked(who, a));
            }
            if (page.page() > 0) {
                set(g.number("stafflog-menu.previous-slot"), items.build(g.item("common.previous"), TagResolver.empty()),
                        (who, click) -> turn(who, group, view.onlyActor, page.page() - 1));
            }
            if (page.page() < page.pages() - 1) {
                set(g.number("stafflog-menu.next-slot"), items.build(g.item("common.next"), TagResolver.empty()),
                        (who, click) -> turn(who, group, view.onlyActor, page.page() + 1));
            }
            set(g.number("stafflog-menu.group-filter.slot"), items.build(g.item("stafflog-menu.group-filter"),
                    Placeholder.unparsed("filter", g.text("stafflog-menu.groups." + group.name()))),
                    (who, click) -> turn(who, group.next(), view.onlyActor, 0));
            if (view.actor != null) {
                set(g.number("stafflog-menu.actor-filter.slot"), items.build(g.item("stafflog-menu.actor-filter"),
                        TagResolver.resolver(Placeholder.unparsed("player", view.actorName),
                                Placeholder.parsed("state", g.text(view.onlyActor ? "staff-menu.state-on"
                                        : "staff-menu.state-off")))),
                        (who, click) -> turn(who, group, !view.onlyActor, 0));
            }
            set(g.number("stafflog-menu.back-slot"), items.build(g.item(view.back == null ? "common.close"
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

        private org.bukkit.inventory.ItemStack entry(ActionRepo.Action a) {
            GuiSettings g = gui();
            StaffLog.Group of = StaffLog.groupOf(a.action());
            ItemTemplate look = g.item("stafflog-menu.group-items." + of.name());
            String details = details(a);
            List<Component> detailLine = details.isEmpty() ? List.of()
                    : List.of(Items.render(g.text("stafflog-menu.details-line"), Placeholder.unparsed("details", details)));
            String hint = opensHistory(a) ? "stafflog-menu.open-history" : opensReport(a) ? "stafflog-menu.open-report"
                    : null;
            List<Component> action = hint == null ? List.of() : List.of(Items.render(g.text(hint), TagResolver.empty()));
            ItemTemplate template = new ItemTemplate(look.material(), look.name(), g.lines("stafflog-menu.entry-lore"),
                    look.glint(), look.customModelData());
            String none = g.text("stafflog-menu.none");
            return items.build(template, TagResolver.resolver(
                    Placeholder.unparsed("action", a.action()),
                    Placeholder.unparsed("actor", a.actorName()),
                    Placeholder.unparsed("target", a.targetName() == null ? none : a.targetName()),
                    Placeholder.unparsed("date", state().settings().display().date(a.at())),
                    Placeholder.unparsed("server", String.valueOf(a.server()))),
                    Map.of("details-line", detailLine, "action-line", action));
        }

        private void turn(Player who, StaffLog.Group next, boolean onlyActor, int to) {
            if (!who.hasPermission(PERMISSION)) {
                denied(who, "general.no-permission");
                who.closeInventory();
                return;
            }
            sound(who, "page");
            view.group = next;
            view.onlyActor = onlyActor && view.actor != null;
            view.page = Math.max(0, to);
            StaffLogMenus.this.open(who, view);
        }

        private void clicked(Player who, ActionRepo.Action a) {
            if (!who.hasPermission(PERMISSION)) {
                denied(who, "general.no-permission");
                who.closeInventory();
                return;
            }
            if (opensHistory(a)) {
                if (!who.hasPermission(HistoryMenus.PERMISSION)) {
                    denied(who, "general.no-permission");
                    return;
                }
                sound(who, "click");
                plugin.history().openDetail(who, new HistoryMenus.View(a.targetUuid(), a.targetName(), 0,
                        back -> StaffLogMenus.this.open(back, view)), a.refId());
            } else if (opensReport(a)) {
                if (!who.hasPermission(ReportService.REPORTS)) {
                    denied(who, "general.no-permission");
                    return;
                }
                sound(who, "click");
                plugin.reportMenus().openDetail(who, new ReportMenus.View(ReportRepo.Filter.ALL), a.refId());
            }
        }
    }
}
