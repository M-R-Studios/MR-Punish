package com.mrstudios.punish.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.gui.PunishMenus;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /punish <player>} (alias {@code /p}): opens the punish menu (DESIGN.md 10.2). Players only. */
public final class PunishMenuCommand implements CommandRegistry.Switchable {

    public static final String PERMISSION = "mrpunish.punish";

    private final MRPunish plugin;
    private final PunishCommands shared;

    public PunishMenuCommand(MRPunish plugin) {
        this.plugin = plugin;
        this.shared = new PunishCommands(plugin);
    }

    @Override
    public String name() {
        return "punish";
    }

    @Override
    public String description() {
        return "Open the punish menu for a player.";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build(String literal) {
        return Commands.literal(literal)
                .requires(source -> source.getSender().hasPermission(PERMISSION))
                .executes(ctx -> usage(ctx.getSource().getSender()))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(shared.players())
                        .executes(ctx -> run(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "player"))));
    }

    private int usage(CommandSender sender) {
        shared.messages().send(sender, "general.usage", Placeholder.unparsed("usage", "/punish <player>"));
        return 0;
    }

    public int run(CommandSender sender, String player) {
        if (!(sender instanceof Player viewer)) {
            shared.messages().send(sender, "general.player-only");
            return 0;
        }
        if (!viewer.hasPermission(PERMISSION)) {
            shared.messages().send(sender, "general.no-permission");
            return 0;
        }
        shared.withTarget(viewer, player, target -> {
            // Checked again: the lookup may have taken a moment.
            if (viewer.isOnline() && viewer.hasPermission(PERMISSION)) {
                plugin.menus().openMain(viewer, new PunishMenus.Flow(target.uuid(), target.name(), null, false, false));
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
