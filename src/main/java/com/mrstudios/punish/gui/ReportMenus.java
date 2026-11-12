package com.mrstudios.punish.gui;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.command.CommandArgs;
import com.mrstudios.punish.config.DurationParser;
import com.mrstudios.punish.config.GuiSettings;
import com.mrstudios.punish.config.ItemTemplate;
import com.mrstudios.punish.config.Messages;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.report.Report;
import com.mrstudios.punish.report.Report.Status;
import com.mrstudios.punish.report.ReportNotices;
import com.mrstudios.punish.report.ReportService;
import com.mrstudios.punish.report.ReportService.Change;
import com.mrstudios.punish.report.ReportService.Created;
import com.mrstudios.punish.storage.ReportRepo;
import com.mrstudios.punish.storage.ReportRepo.Filter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The report menus (DESIGN.md 13.1, 13.3): the reason menu for {@code /report <player>}, the
 * {@code /reports} list, a report's detail with its actions, and all of a report's notes. Every
 * button checks permission when it is clicked; every state change passes the version the menu
 * showed, so a menu that is out of date gets {@code reports.changed} and the latest version.
 */
public final class ReportMenus {

    public static final int PAGE_SIZE = 45;
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final MRPunish plugin;
    private final Items items;
    private final PromptService prompts;

    public ReportMenus(MRPunish plugin, Items items, PromptService prompts) {
        this.plugin = plugin;
        this.items = items;
        this.prompts = prompts;
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

    private ReportService service() {
        return plugin.reports();
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

    private ItemStack filler() {
        return items.build(gui().item("filler"), TagResolver.empty());
    }

    static ReportService.Actor actor(Player player) {
        return new ReportService.Actor(player.getUniqueId(), player.getName(), player::hasPermission);
    }

    private static TagResolver id(long id) {
        return Placeholder.unparsed("id", String.valueOf(id));
    }

    private String ago(long at) {
        return DurationParser.format(Math.max(1000, System.currentTimeMillis() - at));
    }

    // ---- Making a report ----

    /**
     * Makes the report and tells the reporter the outcome. Shared by {@code /report <player> <text>}
     * and the reason menu.
     */
    public void submit(Player reporter, UUID target, String targetName, String reasonKey, String reasonText) {
        Player online = plugin.getServer().getPlayer(target);
        Boolean staff = online == null ? null : online.hasPermission(ReportService.STAFF);
        Location at = reporter.getLocation();
        ReportService.Where where = new ReportService.Where(at.getWorld() == null ? null : at.getWorld().getName(),
                at.getBlockX(), at.getBlockY(), at.getBlockZ());
        service().create(actor(reporter), target, targetName, staff, reasonKey, reasonText, where)
                .thenAccept(result -> tell(reporter, targetName, result));
    }

    private void tell(Player reporter, String targetName, Created result) {
        TagResolver player = Placeholder.unparsed("player", targetName);
        switch (result) {
            case Created.Sent s -> {
                messages().send(reporter, "reports.sent", player, id(s.report().id()));
                sound(reporter, "click");
            }
            case Created.Duplicate d -> denied(reporter, "reports.duplicate", player, id(d.existingId()));
            case Created.TooMany t -> denied(reporter, "reports.too-many", Placeholder.unparsed("max",
                    String.valueOf(t.max())));
            case Created.Cooldown c -> denied(reporter, "reports.cooldown", Placeholder.unparsed("remaining",
                    DurationParser.format(Math.max(1000, (c.remainingMs() + 999) / 1000 * 1000))));
            case Created.Self s -> denied(reporter, "reports.self");
            case Created.Staff s -> denied(reporter, "reports.staff");
            case Created.NoPermission n -> denied(reporter, "general.no-permission");
            case Created.Unavailable u -> denied(reporter, "general.storage-unavailable");
            case Created.Failed f -> denied(reporter, "reports.failed", Placeholder.unparsed("error-id", f.errorId()));
        }
    }

    public void openReasons(Player reporter, UUID target, String targetName) {
        new ReasonMenu(reporter, target, targetName).open(reporter);
    }

    final class ReasonMenu extends MenuHolder {

        private final UUID target;
        private final String targetName;

        ReasonMenu(Player viewer, UUID target, String targetName) {
            super(viewer.getUniqueId(), gui().number("report-reason-menu.rows"),
                    Items.render(gui().text("report-reason-menu.title"), Placeholder.unparsed("player", targetName)));
            this.target = target;
            this.targetName = targetName;
        }

        @Override
        protected void draw() {
            GuiSettings g = gui();
            List<Integer> slots = g.slots("report-reason-menu.slots");
            List<Settings.ReportReason> reasons = state().settings().reports().reasons();
            for (int i = 0; i < reasons.size() && i < slots.size(); i++) {
                Settings.ReportReason reason = reasons.get(i);
                ItemTemplate template = new ItemTemplate(reason.icon(), g.text("report-reason-menu.reason-name"),
                        g.lines("report-reason-menu.reason-lore"), false, null);
                set(slots.get(i), items.build(template, TagResolver.resolver(Placeholder.parsed("reason",
                        reason.display()), Placeholder.unparsed("player", targetName))), (p, click) -> {
                            p.closeInventory();
                            submit(p, target, targetName, reason.key(), reason.display());
                        });
            }
            set(g.number("report-reason-menu.other.slot"), items.build(g.item("report-reason-menu.other"),
                    TagResolver.empty()), (p, click) -> {
                        sound(p, "click");
                        prompts.ask(p, "reports.prompt-reason", "input.form-title-report", null,
                                text -> submit(p, target, targetName, null, text),
                                () -> openReasons(p, target, targetName), Placeholder.unparsed("player", targetName));
                    });
            set(g.number("report-reason-menu.close-slot"), items.build(g.item("common.close"), TagResolver.empty()),
                    (p, click) -> p.closeInventory());
            fill(filler());
        }
    }

    // ---- /reports ----

    /** What the list shows. */
    public static final class View {

        Filter filter;
        int page;

        public View(Filter filter) {
            this.filter = filter;
        }
    }

    record ListPage(int total, int page, int pages, List<Report> reports, Map<Long, Integer> notes) {
    }

    private CompletableFuture<ListPage> loadList(UUID viewer, View view) {
        return plugin.storage().executor().submit("loading reports", c -> {
            ReportRepo repo = new ReportRepo(plugin.storage().database());
            int total = repo.count(c, view.filter, viewer);
            int pages = HistoryMenus.pages(total, PAGE_SIZE);
            int page = HistoryMenus.clamp(view.page, pages);
            List<Report> reports = repo.page(c, view.filter, viewer, page * PAGE_SIZE, PAGE_SIZE);
            Map<Long, Integer> notes = new HashMap<>();
            for (Report r : reports) {
                notes.put(r.id(), repo.noteCount(c, r.id()));
            }
            return new ListPage(total, page, pages, reports, notes);
        });
    }

    public void openList(Player viewer, View view) {
        loadList(viewer.getUniqueId(), view).whenComplete((loaded, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!viewer.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        messages().send(viewer, "general.storage-unavailable");
                        return;
                    }
                    if (!viewer.hasPermission(ReportService.REPORTS)) {
                        denied(viewer, "general.no-permission");
                        return;
                    }
                    view.page = loaded.page();
                    new ListMenu(viewer, view, loaded).open(viewer);
                }));
    }

    private TagResolver common(Report r) {
        Settings.Display display = state().settings().display();
        return TagResolver.resolver(id(r.id()), Placeholder.unparsed("target", r.targetName()),
                Placeholder.unparsed("reporter", r.reporterName()), ReportNotices.reason(r),
                Placeholder.unparsed("date", display.date(r.createdAt())), Placeholder.unparsed("ago", ago(r.createdAt())),
                Placeholder.unparsed("server", r.server()),
                Placeholder.parsed("status", messages().raw("reports.status." + r.status().name())));
    }

    private List<Component> claimedLine(String path, Report r) {
        return r.status() != Status.CLAIMED ? List.of() : List.of(Items.render(gui().text(path),
                Placeholder.unparsed("staff", String.valueOf(r.claimedByName()))));
    }

    final class ListMenu extends MenuHolder {

        private final View view;
        private final ListPage page;

        ListMenu(Player viewer, View view, ListPage page) {
            super(viewer.getUniqueId(), gui().number("reports-menu.rows"), Items.render(gui().text("reports-menu.title"),
                    TagResolver.resolver(Placeholder.unparsed("filter", gui().text("reports-menu.filters."
                                    + view.filter.name())),
                            Placeholder.unparsed("page", String.valueOf(page.page() + 1)),
                            Placeholder.unparsed("pages", String.valueOf(page.pages())))));
            this.view = view;
            this.page = page;
        }

        @Override
        protected void draw() {
            GuiSettings g = gui();
            if (page.reports().isEmpty()) {
                set(g.number("reports-menu.empty.slot"), items.build(g.item("reports-menu.empty"), TagResolver.empty()),
                        null);
            }
            int slot = 0;
            ItemTemplate template = new ItemTemplate("PLAYER_HEAD", g.text("reports-menu.entry-name"),
                    g.lines("reports-menu.entry-lore"), false, null);
            for (Report r : page.reports()) {
                TagResolver placeholders = TagResolver.resolver(common(r), Placeholder.unparsed("notes",
                        String.valueOf(page.notes().getOrDefault(r.id(), 0))));
                set(slot++, items.head(r.targetUuid(), r.targetName(), template, placeholders,
                        Map.of("claimed-line", claimedLine("reports-menu.claimed-line", r))), (p, click) -> {
                            if (!p.hasPermission(ReportService.REPORTS)) {
                                denied(p, "general.no-permission");
                                p.closeInventory();
                                return;
                            }
                            sound(p, "click");
                            openDetail(p, view, r.id());
                        });
            }
            if (page.page() > 0) {
                set(g.number("reports-menu.previous-slot"), items.build(g.item("common.previous"), TagResolver.empty()),
                        (p, click) -> turn(p, view.page - 1));
            }
            if (page.page() < page.pages() - 1) {
                set(g.number("reports-menu.next-slot"), items.build(g.item("common.next"), TagResolver.empty()),
                        (p, click) -> turn(p, view.page + 1));
            }
            set(g.number("reports-menu.filter.slot"), items.build(g.item("reports-menu.filter"), Placeholder.unparsed(
                    "filter", g.text("reports-menu.filters." + view.filter.name()))), (p, click) -> {
                        view.filter = view.filter.next();
                        turn(p, 0);
                    });
            set(g.number("reports-menu.close-slot"), items.build(g.item("common.close"), TagResolver.empty()),
                    (p, click) -> p.closeInventory());
            fill(filler());
        }

        private void turn(Player p, int to) {
            sound(p, "page");
            view.page = Math.max(0, to);
            openList(p, view);
        }
    }

    // ---- Detail ----

    record Detail(Report report, List<Report.Note> notes, int noteCount) {
    }

    public void openDetail(Player viewer, View back, long id) {
        plugin.storage().executor().submit("loading report #" + id, c -> {
            ReportRepo repo = new ReportRepo(plugin.storage().database());
            var report = repo.byId(c, id);
            if (report.isEmpty()) {
                return null;
            }
            int slots = gui().slots("report-detail.note-slots").size();
            return new Detail(report.get(), repo.notes(c, id, 0, slots), repo.noteCount(c, id));
        }).whenComplete((detail, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline()) {
                return;
            }
            if (error != null) {
                messages().send(viewer, "general.storage-unavailable");
            } else if (detail == null) {
                denied(viewer, "reports.not-found", id(id));
            } else if (!viewer.hasPermission(ReportService.REPORTS)) {
                denied(viewer, "general.no-permission");
                viewer.closeInventory();
            } else {
                new DetailMenu(viewer, back, detail).open(viewer);
            }
        }));
    }

    /** Tells the viewer what a change did and shows the report as it is now. */
    private void after(Player viewer, View back, long id, Change result, String doneKey) {
        if (!viewer.isOnline()) {
            return;
        }
        switch (result) {
            case Change.Done d -> {
                if (doneKey != null) {
                    messages().send(viewer, doneKey, id(id));
                }
                sound(viewer, "click");
            }
            case Change.Changed ch -> denied(viewer, "reports.changed", id(id));
            case Change.Closed cl -> denied(viewer, "reports.closed", id(id));
            case Change.ClaimedByOther o -> denied(viewer, "reports.already-claimed", id(id),
                    Placeholder.unparsed("staff", String.valueOf(o.report().claimedByName())));
            case Change.NotFound n -> {
                denied(viewer, "reports.not-found", id(id));
                viewer.closeInventory();
                return;
            }
            case Change.NoPermission n -> {
                denied(viewer, "general.no-permission");
                viewer.closeInventory();
                return;
            }
            case Change.Unavailable u -> denied(viewer, "general.storage-unavailable");
            case Change.Failed f -> denied(viewer, "reports.action-failed", id(id),
                    Placeholder.unparsed("error-id", f.errorId()));
        }
        openDetail(viewer, back, id);
    }

    final class DetailMenu extends MenuHolder {

        private final View back;
        private final Report report;
        private final Detail detail;
        private boolean busy;

        DetailMenu(Player viewer, View back, Detail detail) {
            super(viewer.getUniqueId(), gui().number("report-detail.rows"), Items.render(gui().text("report-detail.title"),
                    id(detail.report().id())));
            this.back = back;
            this.detail = detail;
            this.report = detail.report();
        }

        private ItemStack unavailable(String buttonPath, String why) {
            return items.build(gui().item("report-detail.unavailable"), TagResolver.resolver(
                    Placeholder.unparsed("name", MINI.stripTags(gui().item(buttonPath).name())),
                    Placeholder.parsed("why", why)));
        }

        private String whyClaimed() {
            return gui().text("report-detail.why-claimed").replace("<staff>",
                    MINI.escapeTags(String.valueOf(report.claimedByName())));
        }

        @Override
        protected void draw() {
            GuiSettings g = gui();
            Player viewer = plugin.getServer().getPlayer(viewer());
            boolean admin = viewer != null && viewer.hasPermission(ReportService.ADMIN);
            UUID me = viewer();
            Settings.Display display = state().settings().display();
            String location = report.world() == null ? g.text("report-detail.no-location")
                    : report.world() + " " + report.x() + ", " + report.y() + ", " + report.z();
            List<Component> closed = !report.status().closed() ? List.of()
                    : List.of(Items.render(g.text("report-detail.closed-line"), TagResolver.resolver(
                            Placeholder.unparsed("staff", String.valueOf(report.closedByName())),
                            Placeholder.unparsed("date", report.closedAt() == null ? "?" : display.date(report.closedAt())),
                            Placeholder.unparsed("note", report.decisionNote() == null ? "-" : report.decisionNote()))));
            set(g.number("report-detail.summary.slot"), items.build(g.item("report-detail.summary"),
                    TagResolver.resolver(common(report), Placeholder.unparsed("location", location)),
                    Map.of("claimed-line", claimedLine("reports-menu.claimed-line", report), "closed-line", closed)),
                    null);

            boolean claimedByOther = report.status() == Status.CLAIMED && !report.claimedBy(me);
            // Claim / unclaim.
            int claimSlot = g.number("report-detail.claim.slot");
            if (report.status().closed()) {
                set(claimSlot, unavailable("report-detail.claim", g.text("report-detail.why-closed")), null);
            } else if (report.status() == Status.OPEN) {
                set(claimSlot, items.build(g.item("report-detail.claim"), TagResolver.empty()),
                        (p, click) -> act(p, service().claim(actor(p), report.id(), report.version()), "reports.claimed"));
            } else if (!claimedByOther || admin) {
                set(g.number("report-detail.unclaim.slot"), items.build(g.item("report-detail.unclaim"),
                        TagResolver.empty()), (p, click) -> act(p, service().unclaim(actor(p), report.id(),
                                report.version()), "reports.unclaimed"));
            } else {
                set(claimSlot, unavailable("report-detail.claim", whyClaimed()), null);
            }
            // Resolve / reject.
            for (Status to : List.of(Status.RESOLVED, Status.REJECTED)) {
                String path = to == Status.RESOLVED ? "report-detail.resolve" : "report-detail.reject";
                int slot = g.number(path + ".slot");
                if (report.status().closed()) {
                    set(slot, unavailable(path, g.text("report-detail.why-closed")), null);
                } else if (claimedByOther && !admin) {
                    set(slot, unavailable(path, whyClaimed()), null);
                } else {
                    set(slot, items.build(g.item(path), TagResolver.empty()), (p, click) -> decide(p, to));
                }
            }
            set(g.number("report-detail.add-note.slot"), items.build(g.item("report-detail.add-note"),
                    TagResolver.empty()), (p, click) -> {
                        if (!p.hasPermission(ReportService.REPORTS)) {
                            denied(p, "general.no-permission");
                            p.closeInventory();
                            return;
                        }
                        sound(p, "click");
                        prompts.ask(p, "reports.prompt-note", "input.form-title-report-note", null,
                                text -> service().addNote(actor(p), report.id(), text)
                                        .thenAccept(r -> after(p, back, report.id(), r, "reports.note-added")),
                                () -> openDetail(p, back, report.id()), id(report.id()));
                    });
            // Punish and history of the target.
            TagResolver target = Placeholder.unparsed("target", report.targetName());
            ItemTemplate punish = g.item("report-detail.punish");
            if (viewer != null && viewer.hasPermission(com.mrstudios.punish.command.PunishMenuCommand.PERMISSION)) {
                set(g.number("report-detail.punish.slot"), items.build(punish, target), (p, click) -> {
                    if (!p.hasPermission(com.mrstudios.punish.command.PunishMenuCommand.PERMISSION)) {
                        denied(p, "general.no-permission");
                        render();
                        return;
                    }
                    sound(p, "click");
                    plugin.menus().openMain(p, new PunishMenus.Flow(report.targetUuid(), report.targetName(), null,
                            false, false));
                });
            } else {
                noPermission(g.number("report-detail.punish.slot"), punish, report.targetName());
            }
            ItemTemplate history = g.item("report-detail.history");
            if (viewer != null && viewer.hasPermission(HistoryMenus.PERMISSION)) {
                set(g.number("report-detail.history.slot"), items.build(history, target), (p, click) -> {
                    if (!p.hasPermission(HistoryMenus.PERMISSION)) {
                        denied(p, "general.no-permission");
                        render();
                        return;
                    }
                    sound(p, "click");
                    plugin.history().open(p, new HistoryMenus.View(report.targetUuid(), report.targetName(), 0,
                            who -> openDetail(who, back, report.id())));
                });
            } else {
                noPermission(g.number("report-detail.history.slot"), history, report.targetName());
            }
            // Teleports: staff mode or vanish only, so a visible staff member never appears next to them.
            ItemTemplate toLocation = g.item("report-detail.teleport-location");
            ItemTemplate toTarget = g.item("report-detail.teleport-target");
            if (viewer != null && viewer.hasPermission(com.mrstudios.punish.staff.StaffModeService.PERMISSION)) {
                set(g.number("report-detail.teleport-location.slot"), items.build(toLocation, TagResolver.resolver(
                        target, Placeholder.unparsed("location", location))), (p, click) -> teleport(p, null));
                set(g.number("report-detail.teleport-target.slot"), items.build(toTarget, target),
                        (p, click) -> teleport(p, report.targetUuid()));
            } else {
                noPermission(g.number("report-detail.teleport-location.slot"), toLocation, report.targetName());
                noPermission(g.number("report-detail.teleport-target.slot"), toTarget, report.targetName());
            }
            // Latest notes.
            List<Integer> noteSlots = g.slots("report-detail.note-slots");
            for (int i = 0; i < detail.notes().size() && i < noteSlots.size(); i++) {
                set(noteSlots.get(i), note(detail.notes().get(i)), null);
            }
            set(g.number("report-detail.back-slot"), items.build(g.item("common.back"), TagResolver.empty()),
                    (p, click) -> {
                        sound(p, "click");
                        openList(p, back);
                    });
            set(g.number("report-detail.all-notes.slot"), items.build(g.item("report-detail.all-notes"),
                    Placeholder.unparsed("count", String.valueOf(detail.noteCount()))), (p, click) -> {
                        sound(p, "click");
                        openNotes(p, back, report.id(), 0);
                    });
            fill(filler());
        }

        private void noPermission(int slot, ItemTemplate button, String targetName) {
            if (gui().noPermissionMode() == GuiSettings.NoPermissionMode.GREY) {
                set(slot, items.build(gui().item("no-permission.item"), Placeholder.unparsed("name",
                        MINI.stripTags(button.name().replace("<target>", MINI.escapeTags(targetName))))),
                        (p, click) -> denied(p, "general.no-permission"));
            }
        }

        /** To the report's location ({@code target} null) or to the reported player, if they're online. */
        private void teleport(Player p, UUID target) {
            if (!p.hasPermission(com.mrstudios.punish.staff.StaffModeService.PERMISSION)) {
                denied(p, "general.no-permission");
                render();
                return;
            }
            if (!plugin.staffMode().inStaffMode(p.getUniqueId()) && !plugin.vanish().isVanished(p.getUniqueId())) {
                denied(p, "staff.report-teleport-visible");
                return;
            }
            if (target == null) {
                org.bukkit.World world = report.world() == null ? null : plugin.getServer().getWorld(report.world());
                if (world == null || report.x() == null) {
                    denied(p, "staff.report-teleport-no-location");
                    return;
                }
                p.closeInventory();
                p.teleport(new org.bukkit.Location(world, report.x() + 0.5, report.y(), report.z() + 0.5));
                return;
            }
            Player online = plugin.getServer().getPlayer(target);
            if (online == null) {
                denied(p, "staff.report-teleport-offline", Placeholder.unparsed("player", report.targetName()));
                return;
            }
            p.closeInventory();
            p.teleport(online.getLocation());
        }

        private void act(Player p, CompletableFuture<Change> change, String doneKey) {
            if (busy) {
                return;
            }
            busy = true;
            change.thenAccept(result -> after(p, back, report.id(), result, doneKey));
        }

        private void decide(Player p, Status to) {
            if (!p.hasPermission(ReportService.REPORTS)) {
                denied(p, "general.no-permission");
                p.closeInventory();
                return;
            }
            sound(p, "click");
            long id = report.id();
            int version = report.version();
            prompts.ask(p, "reports.prompt-decision", "input.form-title-decision", null, text -> {
                String note = text.equals("-") ? null : text;
                service().close(actor(p), id, version, to, note).thenAccept(result -> after(p, back, id, result, null));
            }, () -> openDetail(p, back, id), id(id));
        }
    }

    private ItemStack note(Report.Note n) {
        return items.build(gui().item("report-detail.note"), TagResolver.resolver(
                Placeholder.unparsed("author", n.authorName()),
                Placeholder.unparsed("date", state().settings().display().date(n.createdAt())),
                Placeholder.unparsed("text", n.text())));
    }

    // ---- All notes ----

    public void openNotes(Player viewer, View back, long id, int page) {
        plugin.storage().executor().submit("loading the notes of report #" + id, c -> {
            ReportRepo repo = new ReportRepo(plugin.storage().database());
            int total = repo.noteCount(c, id);
            int pages = HistoryMenus.pages(total, PAGE_SIZE);
            int shown = HistoryMenus.clamp(page, pages);
            return new Object[] {shown, pages, repo.notes(c, id, shown * PAGE_SIZE, PAGE_SIZE)};
        }).whenComplete((loaded, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline()) {
                return;
            }
            if (error != null) {
                messages().send(viewer, "general.storage-unavailable");
                return;
            }
            @SuppressWarnings("unchecked")
            List<Report.Note> notes = (List<Report.Note>) loaded[2];
            new NotesMenu(viewer, back, id, (int) loaded[0], (int) loaded[1], notes).open(viewer);
        }));
    }

    final class NotesMenu extends MenuHolder {

        private final View back;
        private final long id;
        private final int page;
        private final int pages;
        private final List<Report.Note> notes;

        NotesMenu(Player viewer, View back, long id, int page, int pages, List<Report.Note> notes) {
            super(viewer.getUniqueId(), gui().number("report-notes.rows"), Items.render(gui().text("report-notes.title"),
                    TagResolver.resolver(id(id), Placeholder.unparsed("page", String.valueOf(page + 1)),
                            Placeholder.unparsed("pages", String.valueOf(pages)))));
            this.back = back;
            this.id = id;
            this.page = page;
            this.pages = pages;
            this.notes = new ArrayList<>(notes);
        }

        @Override
        protected void draw() {
            GuiSettings g = gui();
            for (int i = 0; i < notes.size() && i < PAGE_SIZE; i++) {
                set(i, note(notes.get(i)), null);
            }
            if (page > 0) {
                set(g.number("report-notes.previous-slot"), items.build(g.item("common.previous"), TagResolver.empty()),
                        (p, click) -> openNotes(p, back, id, page - 1));
            }
            if (page < pages - 1) {
                set(g.number("report-notes.next-slot"), items.build(g.item("common.next"), TagResolver.empty()),
                        (p, click) -> openNotes(p, back, id, page + 1));
            }
            set(g.number("report-notes.back-slot"), items.build(g.item("common.back"), TagResolver.empty()),
                    (p, click) -> openDetail(p, back, id));
            fill(filler());
        }
    }

    /** Cleans typed report text the same way as notes. */
    public String cleaned(String text) {
        return CommandArgs.clean(text, state().settings().maxTextLength());
    }
}
