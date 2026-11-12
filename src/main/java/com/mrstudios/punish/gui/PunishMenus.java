package com.mrstudios.punish.gui;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.command.PunishCommands;
import com.mrstudios.punish.config.DurationParser;
import com.mrstudios.punish.config.GuiSettings;
import com.mrstudios.punish.config.ItemTemplate;
import com.mrstudios.punish.config.Messages;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.config.TimeSpan;
import com.mrstudios.punish.punish.Preview;
import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.PunishmentService;
import com.mrstudios.punish.punish.PunishmentType;
import com.mrstudios.punish.punish.Reason;
import com.mrstudios.punish.punish.ReasonRegistry;
import com.mrstudios.punish.punish.Requests;
import com.mrstudios.punish.punish.Requests.IssueResult;
import com.mrstudios.punish.punish.TargetResolver;
import com.mrstudios.punish.rank.Authority;
import com.mrstudios.punish.rank.Rank;
import com.mrstudios.punish.storage.PlayerRepo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/**
 * The punish menus (DESIGN.md 10.2-10.4): {@code /punish <player>}, one reason menu per type with
 * what each reason would do now, and the confirmation for mutes and bans. Every button checks
 * permission (and, for an online target, rank) again when it is clicked; the punishment itself goes
 * through {@link PunishmentService#issue} with the preview the staff member saw, so a change made by
 * someone else in between is caught (E12).
 */
public final class PunishMenus {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final List<PunishmentType> CATEGORIES = List.of(PunishmentType.WARN, PunishmentType.MUTE,
            PunishmentType.BAN, PunishmentType.IP_MUTE, PunishmentType.IP_BAN);
    private static final int PREVIOUS_SHOWN = 3;

    private final MRPunish plugin;
    private final PunishCommands shared;
    private final Items items;
    private final PromptService prompts;

    public PunishMenus(MRPunish plugin, Items items, PromptService prompts) {
        this.plugin = plugin;
        this.shared = new PunishCommands(plugin);
        this.items = items;
        this.prompts = prompts;
    }

    /** One staff member punishing one player: what carries over between the menus. */
    public static final class Flow {

        final UUID targetUuid;
        final String targetName;
        String note;
        boolean silent;
        /** Opened from a command: cancelling closes instead of going back. */
        final boolean fromCommand;

        public Flow(UUID targetUuid, String targetName, String note, boolean silent, boolean fromCommand) {
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.note = note;
            this.silent = silent;
            this.fromCommand = fromCommand;
        }
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

    /** Whether {@code player} may issue {@code type} right now. */
    static boolean mayIssue(Player player, PunishmentType type) {
        return player.hasPermission(PunishmentService.permission(type))
                && (!type.isIp() || player.hasPermission(PunishmentService.IP));
    }

    private void sound(Player player, String name) {
        Settings.SoundSetting s = gui().sound(name);
        if (s.enabled()) {
            player.playSound(Sound.sound(s.sound(), Sound.Source.MASTER, s.volume(), s.pitch()));
        }
    }

    private void denied(Player player, String messageKey, TagResolver... placeholders) {
        sound(player, "denied");
        if (messageKey != null) {
            messages().send(player, messageKey, placeholders);
        }
    }

    private String typeName(PunishmentType type) {
        return gui().text("type-names." + type.name());
    }

    private TagResolver base(Flow flow, PunishmentType type) {
        return TagResolver.resolver(Placeholder.unparsed("player", flow.targetName),
                Placeholder.unparsed("type-name", type == null ? "" : typeName(type)));
    }

    private ItemStack filler() {
        return items.build(gui().item("filler"), TagResolver.empty());
    }

    /** A button the viewer may not use, by {@code no-permission.mode}; null for HIDE. */
    private ItemStack noPermission(ItemTemplate button) {
        if (gui().noPermissionMode() == GuiSettings.NoPermissionMode.HIDE) {
            return null;
        }
        return items.build(gui().item("no-permission.item"),
                Placeholder.unparsed("name", MINI.stripTags(button.name())));
    }

    private String duration(TimeSpan span) {
        return span.permanent() ? messages().raw("general.permanent") : DurationParser.format(span.millis());
    }

    /** 1st, 2nd, 3rd, 4th... 11th, 12th, 13th, 21st. */
    public static String ordinal(int n) {
        int mod100 = n % 100;
        String suffix = mod100 >= 11 && mod100 <= 13 ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }

    /** Authority over an online target, read now; empty when allowed or the target is offline. */
    private Optional<String> refusal(Player viewer, Flow flow, PunishmentType type) {
        if (type.isIp()) {
            return Optional.empty(); // the service checks every affected account
        }
        Player target = plugin.getServer().getPlayer(flow.targetUuid);
        if (target == null) {
            return viewer.getUniqueId().equals(flow.targetUuid) ? Optional.of("punish.self") : Optional.empty();
        }
        Authority.Verdict verdict = Authority.check(plugin.rankCache().standing(viewer),
                plugin.rankCache().standing(target), Authority.Action.PUNISH);
        return switch (verdict) {
            case OK -> Optional.empty();
            case SELF -> Optional.of("punish.self");
            case EXEMPT -> Optional.of("punish.exempt");
            default -> Optional.of("punish.rank");
        };
    }

    /** The target as the service needs it, read now (IP types: with the hash and affected accounts). */
    private void target(Player viewer, Flow flow, PunishmentType type, Consumer<Requests.Target> then) {
        target(viewer, flow, type, then, () -> { });
    }

    /** {@code failed} runs when an IP target can't be found (the viewer was told why). */
    private void target(Player viewer, Flow flow, PunishmentType type, Consumer<Requests.Target> then,
            Runnable failed) {
        Requests.Target target = shared.target(new TargetResolver.Target(flow.targetUuid, flow.targetName,
                plugin.getServer().getPlayer(flow.targetUuid) != null));
        if (type.isIp()) {
            shared.withIpTarget(viewer, target, then, failed);
        } else {
            then.accept(target);
        }
    }

    /**
     * Whether the viewer has authority over everyone the punishment would affect that is known now
     * (online target, accounts on the IP), read now; a confirmation that can only be refused isn't shown.
     */
    private boolean authorised(Player viewer, Requests.Target target, Reason reason) {
        List<Authority.Standing> affected = new ArrayList<>();
        if (target.live() != null) {
            affected.add(target.live());
        }
        affected.addAll(target.affected());
        if (affected.isEmpty()) {
            return true;
        }
        Requests.Actor actor = shared.actor(viewer);
        Authority.Verdict verdict = Authority.check(actor.standing(), affected,
                reason.type().isIp() ? Authority.Action.IP_PUNISH : Authority.Action.PUNISH);
        if (verdict == Authority.Verdict.OK) {
            return true;
        }
        sound(viewer, "denied");
        shared.report(viewer, new Requests.Issue(actor, target, reason, null, false, Punishment.Origin.GUI, null),
                new IssueResult.Refused(verdict, Authority.refusedCount(actor.standing(), affected)));
        return false;
    }

    private void prompt(Player viewer, Flow flow, Runnable reopen) {
        prompts.ask(viewer, "input.prompt-note", "input.form-title-note", flow.note, text -> {
            flow.note = text;
            reopen.run();
        }, reopen, Placeholder.unparsed("player", flow.targetName));
    }

    // ---- Entry points ----

    /** {@code /punish <player>}. */
    public void openMain(Player viewer, Flow flow) {
        new MainMenu(viewer, flow).open(viewer);
    }

    /** A reason menu, e.g. for {@code /mute <player>} without a reason. */
    public void openReasons(Player viewer, Flow flow, PunishmentType type) {
        new ReasonMenu(viewer, flow, type).open(viewer);
    }

    /**
     * {@code /mute <player> <reason>} from a player: works out the preview, then opens the
     * confirmation (DESIGN.md 10.4).
     */
    public void openConfirm(Player viewer, Flow flow, Reason reason) {
        target(viewer, flow, reason.type(), target -> plugin.punishments().preview(target, reason)
                .whenComplete((preview, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!viewer.isOnline()) {
                        return;
                    }
                    if (error != null) {
                        messages().send(viewer, "general.storage-unavailable");
                        return;
                    }
                    if (authorised(viewer, target, reason)) {
                        new ConfirmMenu(viewer, flow, reason, preview, target).open(viewer);
                    }
                })));
    }

    // ---- /punish <player> ----

    /** What the head shows, from one query. {@code reports}: reports against them, {total, open or claimed}. */
    record HeadData(Optional<PlayerRepo.PlayerRecord> record, List<Punishment> punishments, int[] reports) {
    }

    final class MainMenu extends MenuHolder {

        private final Flow flow;
        private HeadData data;
        private boolean loading;

        MainMenu(Player viewer, Flow flow) {
            super(viewer.getUniqueId(), gui().number("punish-menu.rows"),
                    Items.render(gui().text("punish-menu.title"), Placeholder.unparsed("player", flow.targetName)));
            this.flow = flow;
        }

        @Override
        protected void draw() {
            GuiSettings g = gui();
            long now = System.currentTimeMillis();
            Map<PunishmentType, Integer> active = new EnumMap<>(PunishmentType.class);
            if (data == null) {
                set(g.number("punish-menu.head.slot"), items.build(g.item("common.loading"), TagResolver.empty()), null);
                load();
            } else {
                for (Punishment p : data.punishments()) {
                    if (p.isActive(now)) {
                        active.merge(p.type(), 1, Integer::sum);
                    }
                }
                set(g.number("punish-menu.head.slot"), head(now, active), null);
            }
            for (PunishmentType type : CATEGORIES) {
                String path = "punish-menu.categories." + type.name();
                ItemTemplate template = g.item(path);
                int slot = g.number(path + ".slot");
                boolean ipOff = type.isIp() && !plugin.ip().enabled();
                Player viewer = plugin.getServer().getPlayer(viewer());
                if (viewer == null || !mayIssue(viewer, type)) {
                    ItemStack grey = noPermission(template);
                    if (grey != null) {
                        set(slot, grey, (p, click) -> denied(p, "general.no-permission"));
                    }
                    continue;
                }
                List<String> lore = new ArrayList<>(template.lore());
                if (ipOff) {
                    lore.addAll(g.lines("punish-menu.ip-disabled-lore"));
                }
                ItemTemplate shown = new ItemTemplate(template.material(), template.name(), lore, template.glint(),
                        template.customModelData());
                String count = data == null ? "…" : String.valueOf(active.getOrDefault(type, 0));
                set(slot, items.build(shown, Placeholder.unparsed("active", count)), (p, click) -> category(p, type));
            }
            ItemTemplate history = g.item("punish-menu.history");
            int historySlot = g.number("punish-menu.history.slot");
            Player who = plugin.getServer().getPlayer(viewer());
            if (who == null || !who.hasPermission(HistoryMenus.PERMISSION)) {
                ItemStack grey = noPermission(history);
                if (grey != null) {
                    set(historySlot, grey, (p, click) -> denied(p, "general.no-permission"));
                }
            } else {
                String total = data == null ? "…" : String.valueOf(data.punishments().size());
                set(historySlot, items.build(history, Placeholder.unparsed("total", total)), (p, click) -> {
                    if (!p.hasPermission(HistoryMenus.PERMISSION)) {
                        denied(p, "general.no-permission");
                        render();
                        return;
                    }
                    sound(p, "click");
                    plugin.history().open(p, new HistoryMenus.View(flow.targetUuid, flow.targetName, 0,
                            back -> openMain(back, flow)));
                });
            }
            set(g.number("punish-menu.close-slot"), items.build(g.item("common.close"), TagResolver.empty()),
                    (p, click) -> {
                        sound(p, "click");
                        p.closeInventory();
                    });
            fill(filler());
        }

        private void category(Player player, PunishmentType type) {
            if (!mayIssue(player, type)) {
                denied(player, "general.no-permission");
                render();
                return;
            }
            if (type.isIp() && !plugin.ip().enabled()) {
                denied(player, "punish.ip-disabled");
                return;
            }
            Optional<String> refused = refusal(player, flow, type);
            if (refused.isPresent()) {
                denied(player, refused.get(), Placeholder.unparsed("player", flow.targetName));
                return;
            }
            sound(player, "click");
            openReasons(player, flow, type);
        }

        private void load() {
            if (loading) {
                return;
            }
            loading = true;
            int version = renderVersion();
            UUID target = flow.targetUuid;
            plugin.storage().executor().submit("loading the punish menu for " + flow.targetName, c -> new HeadData(
                    new PlayerRepo(plugin.storage().database()).byUuid(c, target),
                    plugin.history().all(c, target, System.currentTimeMillis()),
                    new com.mrstudios.punish.storage.ReportRepo(plugin.storage().database()).against(c, target)))
                    .whenComplete((loaded, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                        loading = false;
                        if (error != null) {
                            Player viewer = plugin.getServer().getPlayer(viewer());
                            if (viewer != null && isShowing()) {
                                messages().send(viewer, "general.storage-unavailable");
                            }
                            data = new HeadData(Optional.empty(), List.of(), new int[] {0, 0});
                        } else {
                            data = loaded;
                        }
                        if (isShowing() && renderVersion() == version) {
                            render();
                        }
                    }));
        }

        private ItemStack head(long now, Map<PunishmentType, Integer> active) {
            GuiSettings g = gui();
            Player online = plugin.getServer().getPlayer(flow.targetUuid);
            String status;
            if (online != null) {
                status = g.text("punish-menu.head.online");
            } else {
                String date = data.record().map(r -> state().settings().display().date(r.lastSeen())).orElse("?");
                status = g.text("punish-menu.head.offline").replace("<date>", MINI.escapeTags(date));
            }
            String rankKey = online != null ? plugin.rankCache().read(online).rankKey()
                    : data.record().map(PlayerRepo.PlayerRecord::rankKey).orElse(null);
            String rank = Optional.ofNullable(rankKey).flatMap(k -> state().ranks().byName(k)).map(Rank::display)
                    .orElse(g.text("punish-menu.head.no-rank"));
            List<Component> previous = data.punishments().stream()
                    .sorted(Comparator.comparingLong(Punishment::issuedAt).reversed())
                    .limit(PREVIOUS_SHOWN)
                    .map(p -> Items.render(g.text("punish-menu.head.previous-line"), TagResolver.resolver(
                            Placeholder.unparsed("type", typeName(p.type())),
                            Placeholder.parsed("reason", p.reasonDisplay()),
                            Placeholder.unparsed("date", state().settings().display().date(p.issuedAt())))))
                    .collect(Collectors.toCollection(ArrayList::new));
            if (previous.isEmpty()) {
                previous.add(Items.render(g.text("punish-menu.head.none"), TagResolver.empty()));
            }
            int activeTotal = active.values().stream().mapToInt(Integer::intValue).sum();
            ItemTemplate template = new ItemTemplate("PLAYER_HEAD", g.text("punish-menu.head.name"),
                    g.lines("punish-menu.head.lore"), false, null);
            return items.head(flow.targetUuid, flow.targetName, template, TagResolver.resolver(
                    Placeholder.unparsed("player", flow.targetName),
                    Placeholder.unparsed("uuid", flow.targetUuid.toString()),
                    Placeholder.parsed("status", status),
                    Placeholder.parsed("rank", rank),
                    Placeholder.unparsed("active", String.valueOf(activeTotal)),
                    Placeholder.unparsed("total", String.valueOf(data.punishments().size())),
                    Placeholder.unparsed("reports", String.valueOf(data.reports()[0])),
                    Placeholder.unparsed("reports-open", String.valueOf(data.reports()[1]))),
                    Map.of("previous", previous));
        }
    }

    // ---- Reason menus ----

    final class ReasonMenu extends MenuHolder {

        private final Flow flow;
        private final PunishmentType type;
        private Map<String, Preview> previews;
        private Requests.Target target;
        private boolean loading;

        ReasonMenu(Player viewer, Flow flow, PunishmentType type) {
            super(viewer.getUniqueId(), gui().number("reason-menu.rows"),
                    Items.render(gui().text("reason-menu.title"), base(flow, type)));
            this.flow = flow;
            this.type = type;
        }

        private List<Reason> reasons() {
            return state().settings().reasons().enabled(type);
        }

        @Override
        protected void draw() {
            GuiSettings g = gui();
            Player viewer = plugin.getServer().getPlayer(viewer());
            if (previews == null) {
                load();
            }
            List<Integer> free = new ArrayList<>(g.slots("reason-menu.slots"));
            Set<Integer> fixed = new HashSet<>();
            for (Reason reason : reasons()) {
                if (reason.slot() >= 0) {
                    fixed.add(reason.slot());
                }
            }
            free.removeAll(fixed);
            int next = 0;
            for (Reason reason : reasons()) {
                int slot;
                if (reason.slot() >= 0) {
                    slot = reason.slot();
                } else if (next < free.size()) {
                    slot = free.get(next++);
                } else {
                    continue; // more reasons than slots; gui.yml lists more slots
                }
                ItemTemplate template = new ItemTemplate(reason.icon(), reason.display(), g.lines("reason-menu.reason-lore"),
                        false, null);
                if (viewer == null || !ReasonRegistry.mayUse(reason, viewer::hasPermission)) {
                    ItemStack grey = noPermission(template);
                    if (grey != null) {
                        set(slot, grey, (p, click) -> denied(p, "punish.reason-no-permission",
                                Placeholder.parsed("reason", reason.display())));
                    }
                    continue;
                }
                Preview preview = previews == null ? null : previews.get(reason.key());
                if (preview == null) {
                    set(slot, items.build(g.item("common.loading").withName(reason.display()), TagResolver.empty()),
                            null);
                    continue;
                }
                set(slot, reasonItem(template, reason, preview), (p, click) -> choose(p, reason, preview));
            }
            String note = flow.note == null ? g.text("reason-menu.note.empty") : MINI.escapeTags(flow.note);
            set(g.number("reason-menu.note.slot"), items.build(g.item("reason-menu.note"),
                    Placeholder.parsed("note", note)), (p, click) -> {
                        sound(p, "click");
                        if (ClickRules.isShift(click)) {
                            flow.note = null;
                            render();
                        } else {
                            prompt(p, flow, () -> openReasons(p, flow, type));
                        }
                    });
            set(g.number("reason-menu.back-slot"), items.build(g.item(flow.fromCommand ? "common.close" : "common.back"),
                    TagResolver.empty()), (p, click) -> {
                        sound(p, "click");
                        if (flow.fromCommand) {
                            p.closeInventory();
                        } else {
                            openMain(p, flow);
                        }
                    });
            fill(filler());
        }

        private ItemStack reasonItem(ItemTemplate template, Reason reason, Preview preview) {
            GuiSettings g = gui();
            TimeSpan period = reason.period(state().settings().historyPeriod());
            String next;
            if (!type.hasDuration()) {
                next = g.text("reason-menu.next-warn");
            } else if (preview.duration().permanent()) {
                next = g.text("reason-menu.next-permanent");
            } else {
                next = g.text("reason-menu.next-timed");
            }
            StringBuilder ladder = new StringBuilder();
            List<TimeSpan> steps = reason.escalation();
            if (steps.isEmpty()) {
                ladder.append(g.text("reason-menu.ladder-none"));
            }
            for (int i = 0; i < steps.size(); i++) {
                if (i > 0) {
                    ladder.append(g.text("reason-menu.ladder-separator"));
                }
                String step = g.text(i == preview.step() - 1 ? "reason-menu.ladder-current" : "reason-menu.ladder-step");
                ladder.append(step.replace("<duration>", MINI.escapeTags(duration(steps.get(i)))));
            }
            String action;
            if (!type.hasDuration()) {
                action = g.text("reason-menu.action-warn");
            } else if (preview.decision().blocked()) {
                Long end = preview.decision().blockedBy().endsAt();
                action = g.text("reason-menu.action-blocked")
                        .replace("<id>", String.valueOf(preview.decision().blockedBy().id()))
                        .replace("<remaining>", end == null ? MINI.escapeTags(messages().raw("general.permanent"))
                                : DurationParser.format(Math.max(0, end - System.currentTimeMillis())));
            } else if (!preview.decision().replaces().isEmpty()) {
                action = g.text("reason-menu.action-replaces").replace("<id>", preview.decision().replaces().stream()
                        .map(String::valueOf).collect(Collectors.joining(", #")));
            } else {
                action = g.text("reason-menu.action-review");
            }
            TagResolver placeholders = TagResolver.resolver(base(flow, type),
                    Placeholder.unparsed("offence-ordinal", ordinal(preview.offence())),
                    Placeholder.unparsed("previous", String.valueOf(preview.counted().size())),
                    Placeholder.unparsed("period", period.permanent() ? g.text("reason-menu.period-forever")
                            : DurationParser.format(period.millis())),
                    Placeholder.unparsed("duration", preview.duration() == null ? "" : duration(preview.duration())));
            List<Component> description = reason.description().stream()
                    .map(line -> Items.render(line, TagResolver.empty())).toList();
            Component nextC = Items.render(next, placeholders);
            Component ladderC = Items.render(ladder.toString(), TagResolver.empty());
            Component actionC = Items.render(action, placeholders);
            return items.build(template, TagResolver.resolver(placeholders,
                    Placeholder.component("next", nextC), Placeholder.component("ladder", ladderC),
                    Placeholder.component("action", actionC)), Map.of("description", description));
        }

        private void load() {
            if (loading) {
                return;
            }
            loading = true;
            Player viewer = plugin.getServer().getPlayer(viewer());
            if (viewer == null) {
                return;
            }
            int version = renderVersion();
            target(viewer, flow, type, resolved -> plugin.punishments().previewAll(resolved, type, reasons())
                    .whenComplete((loaded, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                        loading = false;
                        if (error != null) {
                            if (isShowing()) {
                                messages().send(viewer, "general.storage-unavailable");
                            }
                            return;
                        }
                        target = resolved;
                        previews = loaded;
                        if (isShowing() && renderVersion() == version) {
                            render();
                        }
                    })), () -> loading = false);
        }

        /** Reloads the previews (someone else punished the player meanwhile). */
        private void reload() {
            previews = null;
            render();
        }

        private void choose(Player player, Reason reason, Preview preview) {
            if (!mayIssue(player, type)) {
                denied(player, "general.no-permission");
                render();
                return;
            }
            if (!ReasonRegistry.mayUse(reason, player::hasPermission)) {
                denied(player, "punish.reason-no-permission", Placeholder.parsed("reason", reason.display()));
                render();
                return;
            }
            Optional<String> refused = refusal(player, flow, type);
            if (refused.isPresent()) {
                denied(player, refused.get(), Placeholder.unparsed("player", flow.targetName));
                return;
            }
            if (preview.decision().blocked()) {
                denied(player, null);
                return;
            }
            sound(player, "click");
            if (type.hasDuration()) {
                if (authorised(player, target, reason)) {
                    new ConfirmMenu(player, flow, reason, preview, target).open(player);
                }
                return;
            }
            // Warnings apply on click (DESIGN.md 10.3), with the preview this item showed.
            if (loading) {
                return;
            }
            loading = true;
            target(player, flow, type, fresh -> {
                Requests.Issue request = new Requests.Issue(shared.actor(player), fresh, reason, flow.note, flow.silent,
                        Punishment.Origin.GUI, preview);
                plugin.punishments().issue(request).thenAccept(result -> {
                    loading = false;
                    if (!player.isOnline()) {
                        return;
                    }
                    if (result instanceof IssueResult.Changed) {
                        shared.report(player, request, result);
                        if (isShowing()) {
                            reload();
                        }
                        return;
                    }
                    shared.report(player, request, result);
                    if (result instanceof IssueResult.Committed) {
                        flow.note = null;
                        if (flow.fromCommand) {
                            player.closeInventory();
                        } else {
                            openMain(player, flow);
                        }
                    } else if (isShowing()) {
                        render();
                    }
                });
            }, () -> loading = false);
        }
    }

    // ---- Confirmation ----

    final class ConfirmMenu extends MenuHolder {

        private final Flow flow;
        private final Reason reason;
        private final Preview preview;
        private final Requests.Target shownTarget;
        private boolean submitting;

        ConfirmMenu(Player viewer, Flow flow, Reason reason, Preview preview, Requests.Target shownTarget) {
            super(viewer.getUniqueId(), gui().number("confirm-menu.rows"),
                    Items.render(gui().text("confirm-menu.title"), base(flow, reason.type())));
            this.flow = flow;
            this.reason = reason;
            this.preview = preview;
            this.shownTarget = shownTarget;
        }

        @Override
        protected void draw() {
            GuiSettings g = gui();
            PunishmentType type = reason.type();
            String duration = duration(preview.duration());
            String end = preview.endsAt() == null ? g.text("confirm-menu.never")
                    : state().settings().display().date(preview.endsAt());
            String note = flow.note == null ? g.text("reason-menu.note.empty") : MINI.escapeTags(flow.note);
            TagResolver placeholders = TagResolver.resolver(base(flow, type),
                    Placeholder.parsed("reason", reason.display()),
                    Placeholder.unparsed("offence-ordinal", ordinal(preview.offence())),
                    Placeholder.unparsed("duration", duration),
                    Placeholder.unparsed("end", end),
                    Placeholder.parsed("note", note));
            List<Component> replaces = preview.decision().replaces().isEmpty() ? List.of()
                    : List.of(Items.render(g.text("confirm-menu.replaces-line"), Placeholder.unparsed("id",
                            preview.decision().replaces().stream().map(String::valueOf)
                                    .collect(Collectors.joining(", #")))));
            List<Component> accounts = !type.isIp() || shownTarget == null ? List.of()
                    : List.of(Items.render(g.text("confirm-menu.accounts-line"), Placeholder.unparsed("accounts",
                            String.valueOf(shownTarget.affected().size()))));
            set(g.number("confirm-menu.summary.slot"), items.build(g.item("confirm-menu.summary"), placeholders,
                    Map.of("replaces", replaces, "accounts", accounts)), null);
            int confirmSlot = g.number("confirm-menu.confirm.slot");
            if (submitting) {
                set(confirmSlot, items.build(g.item("confirm-menu.submitting"), placeholders), null);
            } else {
                set(confirmSlot, items.build(g.item("confirm-menu.confirm"), placeholders), (p, click) -> confirm(p));
            }
            set(g.number("confirm-menu.note.slot"), items.build(g.item("confirm-menu.note"), placeholders),
                    (p, click) -> {
                        if (submitting) {
                            return;
                        }
                        sound(p, "click");
                        if (ClickRules.isShift(click)) {
                            flow.note = null;
                            render();
                        } else {
                            prompt(p, flow, () -> new ConfirmMenu(p, flow, reason, preview, shownTarget).open(p));
                        }
                    });
            set(g.number("confirm-menu.cancel.slot"), items.build(g.item("confirm-menu.cancel"), placeholders),
                    (p, click) -> {
                        if (submitting) {
                            return;
                        }
                        sound(p, "click");
                        back(p);
                    });
            set(g.number("confirm-menu.silent.slot"), items.build(g.item(flow.silent ? "confirm-menu.silent.when-on"
                    : "confirm-menu.silent.when-off"), placeholders), (p, click) -> {
                        if (submitting) {
                            return;
                        }
                        sound(p, "click");
                        flow.silent = !flow.silent;
                        render();
                    });
            fill(filler());
        }

        private void back(Player player) {
            if (flow.fromCommand) {
                player.closeInventory();
            } else {
                openReasons(player, flow, reason.type());
            }
        }

        private void confirm(Player player) {
            if (submitting) {
                return;
            }
            PunishmentType type = reason.type();
            // Checked again now, on the main thread (DESIGN.md 10.1); the service checks once more.
            if (!mayIssue(player, type)) {
                denied(player, "general.no-permission");
                back(player);
                return;
            }
            if (!ReasonRegistry.mayUse(reason, player::hasPermission)) {
                denied(player, "punish.reason-no-permission", Placeholder.parsed("reason", reason.display()));
                back(player);
                return;
            }
            Optional<String> refused = refusal(player, flow, type);
            if (refused.isPresent()) {
                denied(player, refused.get(), Placeholder.unparsed("player", flow.targetName));
                back(player);
                return;
            }
            sound(player, "click");
            submitting = true;
            render();
            target(player, flow, type, fresh -> {
                Requests.Issue request = new Requests.Issue(shared.actor(player), fresh, reason, flow.note, flow.silent,
                        Punishment.Origin.GUI, preview);
                plugin.punishments().issue(request).thenAccept(result -> done(player, request, result));
            }, () -> {
                submitting = false;
                if (isShowing()) {
                    render();
                }
            });
        }

        private void done(Player player, Requests.Issue request, IssueResult result) {
            submitting = false;
            if (!player.isOnline()) {
                return;
            }
            shared.report(player, request, result);
            switch (result) {
                case IssueResult.Committed c -> {
                    if (isShowing()) {
                        player.closeInventory();
                    }
                }
                case IssueResult.Changed ch -> new ConfirmMenu(player, flow, reason, ch.current(), request.target())
                        .open(player);
                case IssueResult.WouldShorten w -> back(player);
                case IssueResult.Refused r -> back(player);
                case IssueResult.NoPermission n -> back(player);
                default -> {
                    if (isShowing()) {
                        render();
                    }
                }
            }
        }
    }
}
