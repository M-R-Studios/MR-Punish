package com.mrstudios.punish.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.rank.Authority;
import com.mrstudios.punish.rank.RankDisplay;
import com.mrstudios.punish.staff.StaffChat;
import com.mrstudios.punish.staff.StaffModeService;
import com.mrstudios.punish.staff.VanishService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Comparator;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /staffchat}, {@code /sc <message>}, {@code /stafflist} and {@code /vanish [player]}
 * (DESIGN.md 11.2, 11.5, 11.6). Each is a switchable command; permissions are checked when run.
 */
public final class StaffCommands {

    public static final String STAFFLIST = "mrpunish.stafflist";

    private final MRPunish plugin;
    private final PunishCommands shared;

    public StaffCommands(MRPunish plugin) {
        this.plugin = plugin;
        this.shared = new PunishCommands(plugin);
    }

    private PluginState state() {
        return plugin.state();
    }

    private boolean refuse(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return false;
        }
        shared.messages().send(sender, "general.no-permission");
        return true;
    }

    private static CommandRegistry.Switchable command(String name, String description,
            java.util.function.Function<String, LiteralArgumentBuilder<CommandSourceStack>> build) {
        return new CommandRegistry.Switchable() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public LiteralArgumentBuilder<CommandSourceStack> build(String literal) {
                return build.apply(literal);
            }
        };
    }

    public List<CommandRegistry.Switchable> all() {
        return List.of(staffchat(), sc(), stafflist(), vanish(), staffmode());
    }

    // ---- /staffchat ----

    CommandRegistry.Switchable staffchat() {
        return command("staffchat", "Send all your chat to staff chat, or back.", literal -> Commands.literal(literal)
                .requires(s -> s.getSender().hasPermission(StaffChat.PERMISSION))
                .executes(ctx -> toggleStaffChat(ctx.getSource().getSender())));
    }

    int toggleStaffChat(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            shared.messages().send(sender, "general.player-only");
            return 0;
        }
        if (refuse(sender, StaffChat.PERMISSION)) {
            return 0;
        }
        shared.messages().send(player, plugin.staffChat().toggle(player) ? "staff.staffchat-on" : "staff.staffchat-off");
        return Command.SINGLE_SUCCESS;
    }

    // ---- /sc ----

    CommandRegistry.Switchable sc() {
        return command("sc", "Send one message to staff chat.", literal -> Commands.literal(literal)
                .requires(s -> s.getSender().hasPermission(StaffChat.PERMISSION))
                .executes(ctx -> {
                    shared.messages().send(ctx.getSource().getSender(), "general.usage",
                            Placeholder.unparsed("usage", "/sc <message>"));
                    return 0;
                })
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> sendStaffChat(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "message")))));
    }

    int sendStaffChat(CommandSender sender, String message) {
        if (refuse(sender, StaffChat.PERMISSION)) {
            return 0;
        }
        plugin.staffChat().send(sender, message);
        return Command.SINGLE_SUCCESS;
    }

    // ---- /stafflist ----

    CommandRegistry.Switchable stafflist() {
        return command("stafflist", "List the staff who are online.", literal -> Commands.literal(literal)
                .requires(s -> s.getSender().hasPermission(STAFFLIST))
                .executes(ctx -> staffList(ctx.getSource().getSender())));
    }

    int staffList(CommandSender sender) {
        if (refuse(sender, STAFFLIST)) {
            return 0;
        }
        PluginState s = state();
        VanishService vanish = plugin.vanish();
        String noRank = s.gui().text("punish-menu.head.no-rank");
        List<Player> staff = plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> p.hasPermission(Perms.STAFF))
                .filter(p -> !(sender instanceof Player viewer) || vanish.canSee(viewer, p))
                .map(p -> (Player) p)
                .sorted(Comparator.comparingInt((Player p) -> -plugin.rankCache().read(p).weight())
                        .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (staff.isEmpty()) {
            shared.messages().send(sender, "staff.stafflist-none");
            return Command.SINGLE_SUCCESS;
        }
        shared.messages().send(sender, "staff.stafflist-header", Placeholder.unparsed("count",
                String.valueOf(staff.size())));
        for (Player p : staff) {
            String tags = vanish.isVanished(p.getUniqueId()) ? s.messages().raw("staff.stafflist-tag-vanished") : "";
            // Staff mode is shown to staff (and console) only.
            if (plugin.staffMode().inStaffMode(p.getUniqueId())
                    && (!(sender instanceof Player viewer) || viewer.hasPermission(Perms.STAFF))) {
                tags += s.messages().raw("staff.stafflist-tag-staffmode");
            }
            shared.messages().send(sender, "staff.stafflist-line",
                    Placeholder.parsed("rank", RankDisplay.of(s.ranks(), p::hasPermission, noRank)),
                    Placeholder.unparsed("player", p.getName()), Placeholder.parsed("tags", tags));
        }
        return Command.SINGLE_SUCCESS;
    }

    // ---- /staffmode ----

    CommandRegistry.Switchable staffmode() {
        return command("staffmode", "Enter or leave staff mode.", literal -> Commands.literal(literal)
                .requires(s -> s.getSender().hasPermission(StaffModeService.PERMISSION))
                .executes(ctx -> toggleStaffMode(ctx.getSource().getSender())));
    }

    int toggleStaffMode(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            shared.messages().send(sender, "general.player-only");
            return 0;
        }
        if (refuse(sender, StaffModeService.PERMISSION)) {
            return 0;
        }
        StaffModeService service = plugin.staffMode();
        if (service.inStaffMode(player.getUniqueId())) {
            service.exit(player, StaffModeService.Cause.COMMAND);
            shared.messages().send(player, "staff.staffmode-off");
            return Command.SINGLE_SUCCESS;
        }
        String why = service.enter(player);
        if (why != null) {
            shared.messages().send(player, "staff.staffmode-refused",
                    Placeholder.parsed("why", shared.messages().raw(why)));
            return 0;
        }
        shared.messages().send(player, "staff.staffmode-on");
        return Command.SINGLE_SUCCESS;
    }

    // ---- /vanish ----

    CommandRegistry.Switchable vanish() {
        return command("vanish", "Vanish, or vanish another player.", literal -> Commands.literal(literal)
                .requires(s -> s.getSender().hasPermission(VanishService.USE)
                        || s.getSender().hasPermission(VanishService.OTHERS))
                .executes(ctx -> vanishSelf(ctx.getSource().getSender()))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(shared.players())
                        .executes(ctx -> vanishOther(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "player")))));
    }

    int vanishSelf(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            shared.messages().send(sender, "general.player-only");
            return 0;
        }
        if (refuse(sender, VanishService.USE)) {
            return 0;
        }
        shared.messages().send(player, plugin.vanish().toggle(player) ? "staff.vanish-on" : "staff.vanish-off");
        return Command.SINGLE_SUCCESS;
    }

    int vanishOther(CommandSender sender, String name) {
        Player target = plugin.getServer().getPlayerExact(name);
        if (sender instanceof Player self && target != null && target.equals(self)) {
            return vanishSelf(sender);
        }
        if (refuse(sender, VanishService.OTHERS)) {
            return 0;
        }
        if (target == null || (sender instanceof Player viewer && !plugin.vanish().canSee(viewer, target))) {
            shared.messages().send(sender, "general.unknown-player", Placeholder.unparsed("player", name));
            return 0;
        }
        Authority.Standing actor = shared.actor(sender).standing();
        if (Authority.check(actor, plugin.rankCache().standing(target), Authority.Action.VANISH_OTHER)
                != Authority.Verdict.OK) {
            shared.messages().send(sender, "staff.vanish-rank", Placeholder.unparsed("player", target.getName()));
            return 0;
        }
        if (!plugin.vanish().isVanished(target.getUniqueId()) && !target.hasPermission(VanishService.USE)) {
            // They couldn't make themselves visible again.
            shared.messages().send(sender, "staff.vanish-target-cant", Placeholder.unparsed("player", target.getName()));
            return 0;
        }
        boolean now = plugin.vanish().toggle(target);
        shared.messages().send(sender, now ? "staff.vanish-other-on" : "staff.vanish-other-off",
                Placeholder.unparsed("player", target.getName()));
        shared.messages().send(target, now ? "staff.vanish-by-other-on" : "staff.vanish-by-other-off",
                Placeholder.unparsed("staff", actor.name()));
        return Command.SINGLE_SUCCESS;
    }
}
