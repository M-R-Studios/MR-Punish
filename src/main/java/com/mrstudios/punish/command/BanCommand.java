package com.mrstudios.punish.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.PunishmentService;
import com.mrstudios.punish.punish.PunishmentType;
import com.mrstudios.punish.punish.ReasonRegistry;
import com.mrstudios.punish.punish.Requests;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

/**
 * {@code /ban <player> [-s] <reason> [note...]} (DESIGN.md 4.1). The first word picks a BAN reason
 * (key or alias); anything else falls back to the BAN fallback reason with the whole text as the
 * note. The duration comes from the reason's escalation. Until the punish menu exists (step 7), a
 * ban from a player applies straight away, as it does from console.
 */
public final class BanCommand implements CommandRegistry.Switchable {

    static final String USAGE = "/ban <player> [-s] <reason> [note...]";

    private final MRPunish plugin;
    private final PunishCommands shared;

    public BanCommand(MRPunish plugin) {
        this.plugin = plugin;
        this.shared = new PunishCommands(plugin);
    }

    @Override
    public String name() {
        return "ban";
    }

    @Override
    public String description() {
        return "Ban a player with the reason's next escalation step.";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build(String literal) {
        String permission = PunishmentService.permission(PunishmentType.BAN);
        return Commands.literal(literal)
                .requires(source -> source.getSender().hasPermission(permission))
                .executes(ctx -> usage(ctx.getSource().getSender()))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(shared.players())
                        .executes(ctx -> run(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"), ""))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .suggests(shared.reasons(PunishmentType.BAN))
                                .executes(ctx -> run(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "reason")))));
    }

    private int usage(CommandSender sender) {
        shared.messages().send(sender, "general.usage", Placeholder.unparsed("usage", USAGE));
        return 0;
    }

    int run(CommandSender sender, String player, String rest) {
        if (!sender.hasPermission(PunishmentService.permission(PunishmentType.BAN))) {
            shared.messages().send(sender, "general.no-permission");
            return 0;
        }
        CommandArgs.Parsed parsed = CommandArgs.parse(rest);
        if (parsed.text().isEmpty()) {
            return usage(sender);
        }
        ReasonRegistry reasons = plugin.state().settings().reasons();
        ReasonRegistry.Resolution resolution = reasons.resolve(PunishmentType.BAN, parsed.text()).orElseThrow();
        String note = shared.cleaned(resolution.note());
        shared.withTarget(sender, player, target -> {
            // Read again at the moment of the action (DESIGN.md 2.1 #4).
            Requests.Issue request = new Requests.Issue(shared.actor(sender), target, resolution.reason(), note,
                    parsed.silent(), Punishment.Origin.COMMAND, null);
            plugin.punishments().issue(request).thenAccept(result -> shared.report(sender, request, result));
        });
        return Command.SINGLE_SUCCESS;
    }
}
