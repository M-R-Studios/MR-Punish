package com.mrstudios.punish.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.punish.PunishmentService;
import com.mrstudios.punish.punish.PunishmentType;
import com.mrstudios.punish.punish.Requests;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

/**
 * {@code /unban <player> [reason...]} (DESIGN.md 4.1, 7.6): removes every active ban of the player
 * (all duplicates), recording who, when and why. Vanilla's ban list isn't touched (use /pardon).
 */
public final class UnbanCommand implements CommandRegistry.Switchable {

    static final String USAGE = "/unban <player> [reason...]";

    private final MRPunish plugin;
    private final PunishCommands shared;

    public UnbanCommand(MRPunish plugin) {
        this.plugin = plugin;
        this.shared = new PunishCommands(plugin);
    }

    @Override
    public String name() {
        return "unban";
    }

    @Override
    public String description() {
        return "Remove a player's active ban.";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build(String literal) {
        return Commands.literal(literal)
                .requires(source -> source.getSender().hasPermission(PunishmentService.REMOVE))
                .executes(ctx -> usage(ctx.getSource().getSender()))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(shared.players())
                        .executes(ctx -> run(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"), ""))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "reason")))));
    }

    private int usage(CommandSender sender) {
        shared.messages().send(sender, "general.usage", Placeholder.unparsed("usage", USAGE));
        return 0;
    }

    int run(CommandSender sender, String player, String reason) {
        if (!sender.hasPermission(PunishmentService.REMOVE)) {
            shared.messages().send(sender, "general.no-permission");
            return 0;
        }
        String why = shared.cleaned(reason);
        shared.withTarget(sender, player, target -> {
            Requests.Remove request = new Requests.Remove(shared.actor(sender), target, PunishmentType.BAN, null, why);
            plugin.punishments().remove(request).thenAccept(result -> shared.report(sender, request, result));
        });
        return Command.SINGLE_SUCCESS;
    }
}
