package com.mrstudios.punish.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.gui.HistoryMenus;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /history <player> [page]} (alias {@code /hist}): the history menu for players, a text list
 * (10 per page) for console (DESIGN.md 10.5).
 */
public final class HistoryCommand implements CommandRegistry.Switchable {

    private final MRPunish plugin;
    private final PunishCommands shared;

    public HistoryCommand(MRPunish plugin) {
        this.plugin = plugin;
        this.shared = new PunishCommands(plugin);
    }

    @Override
    public String name() {
        return "history";
    }

    @Override
    public String description() {
        return "Show a player's punishment history.";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build(String literal) {
        return Commands.literal(literal)
                .requires(source -> source.getSender().hasPermission(HistoryMenus.PERMISSION))
                .executes(ctx -> usage(ctx.getSource().getSender()))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(shared.players())
                        .executes(ctx -> run(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> run(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player"),
                                        IntegerArgumentType.getInteger(ctx, "page")))));
    }

    private int usage(CommandSender sender) {
        shared.messages().send(sender, "general.usage", Placeholder.unparsed("usage", "/history <player> [page]"));
        return 0;
    }

    /** @param page 1-based; clamped to the pages there are */
    int run(CommandSender sender, String player, int page) {
        if (!sender.hasPermission(HistoryMenus.PERMISSION)) {
            shared.messages().send(sender, "general.no-permission");
            return 0;
        }
        shared.withTarget(sender, player, target -> {
            if (sender instanceof Player viewer) {
                if (viewer.isOnline() && viewer.hasPermission(HistoryMenus.PERMISSION)) {
                    plugin.history().open(viewer, new HistoryMenus.View(target.uuid(), target.name(), page - 1, null));
                }
            } else {
                plugin.history().printTo(sender, target.uuid(), target.name(), page - 1);
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
