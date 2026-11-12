package com.mrstudios.punish.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.gui.StaffLogMenus;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /staff} (the hub, DESIGN.md 11.7) and {@code /stafflog [staff] [page]} (13.6). Both are
 * switchable; permissions are checked when run.
 */
public final class StaffHubCommands {

    private final MRPunish plugin;
    private final PunishCommands shared;

    public StaffHubCommands(MRPunish plugin) {
        this.plugin = plugin;
        this.shared = new PunishCommands(plugin);
    }

    public List<CommandRegistry.Switchable> all() {
        return List.of(staff(), stafflog());
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

    // ---- /staff ----

    CommandRegistry.Switchable staff() {
        return command("staff", "Open the staff menu.", literal -> Commands.literal(literal)
                .requires(s -> s.getSender().hasPermission(Perms.STAFF))
                .executes(ctx -> hub(ctx.getSource().getSender())));
    }

    int hub(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            shared.messages().send(sender, "general.player-only");
            return 0;
        }
        if (!player.hasPermission(Perms.STAFF)) {
            shared.messages().send(sender, "general.no-permission");
            return 0;
        }
        plugin.staffMenus().openHub(player);
        return Command.SINGLE_SUCCESS;
    }

    // ---- /stafflog ----

    CommandRegistry.Switchable stafflog() {
        return command("stafflog", "Show recent staff actions.", literal -> Commands.literal(literal)
                .requires(s -> s.getSender().hasPermission(StaffLogMenus.PERMISSION))
                .executes(ctx -> log(ctx.getSource().getSender(), null, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> log(ctx.getSource().getSender(), null,
                                IntegerArgumentType.getInteger(ctx, "page"))))
                .then(Commands.argument("staff", StringArgumentType.word())
                        .suggests(shared.players())
                        .executes(ctx -> log(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "staff"), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> log(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "staff"),
                                        IntegerArgumentType.getInteger(ctx, "page"))))));
    }

    /**
     * @param staff a staff member's name to filter by, or null for everyone
     * @param page  1-based; clamped to the pages there are
     */
    int log(CommandSender sender, String staff, int page) {
        if (!sender.hasPermission(StaffLogMenus.PERMISSION)) {
            shared.messages().send(sender, "general.no-permission");
            return 0;
        }
        if (staff == null) {
            show(sender, null, null, page);
            return Command.SINGLE_SUCCESS;
        }
        shared.withTarget(sender, staff, target -> show(sender, target.uuid(), target.name(), page));
        return Command.SINGLE_SUCCESS;
    }

    private void show(CommandSender sender, java.util.UUID actor, String actorName, int page) {
        if (sender instanceof Player viewer) {
            if (viewer.isOnline() && viewer.hasPermission(StaffLogMenus.PERMISSION)) {
                plugin.staffLogMenus().open(viewer, new StaffLogMenus.View(actor, actorName, page - 1, null));
            }
        } else {
            plugin.staffLogMenus().printTo(sender, actor, actorName, page - 1);
        }
    }
}
