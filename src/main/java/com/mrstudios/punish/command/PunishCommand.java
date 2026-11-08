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
 * {@code /warn}, {@code /mute} and {@code /ban <player> [-s] <reason> [note...]} (DESIGN.md 4.1). The
 * first word picks a reason of the command's type (key or alias); anything else falls back to the
 * type's fallback reason with the whole text as the note. Durations come from the reason's escalation.
 * Until the punish menu exists (step 7), a punishment from a player applies straight away, as it
 * does from console.
 */
public final class PunishCommand implements CommandRegistry.Switchable {

    private final MRPunish plugin;
    private final PunishCommands shared;
    private final PunishmentType type;
    private final String name;
    private final String description;

    private PunishCommand(MRPunish plugin, PunishmentType type, String name, String description) {
        this.plugin = plugin;
        this.shared = new PunishCommands(plugin);
        this.type = type;
        this.name = name;
        this.description = description;
    }

    public static PunishCommand warn(MRPunish plugin) {
        return new PunishCommand(plugin, PunishmentType.WARN, "warn", "Warn a player.");
    }

    public static PunishCommand mute(MRPunish plugin) {
        return new PunishCommand(plugin, PunishmentType.MUTE, "mute", "Mute a player with the reason's next step.");
    }

    public static PunishCommand muteip(MRPunish plugin) {
        return new PunishCommand(plugin, PunishmentType.IP_MUTE, "muteip", "Mute a player's IP.");
    }

    public static PunishCommand banip(MRPunish plugin) {
        return new PunishCommand(plugin, PunishmentType.IP_BAN, "banip", "Ban a player's IP.");
    }

    public static PunishCommand ban(MRPunish plugin) {
        return new PunishCommand(plugin, PunishmentType.BAN, "ban", "Ban a player with the reason's next step.");
    }

    public String usage() {
        return "/" + name + " <player> [-s] <reason> [note...]";
    }

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
        String permission = PunishmentService.permission(type);
        return Commands.literal(literal)
                .requires(source -> source.getSender().hasPermission(permission)
                        && (!type.isIp() || source.getSender().hasPermission(PunishmentService.IP)))
                .executes(ctx -> usage(ctx.getSource().getSender()))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(shared.players())
                        .executes(ctx -> run(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"), ""))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .suggests(shared.reasons(type))
                                .executes(ctx -> run(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "reason")))));
    }

    private int usage(CommandSender sender) {
        shared.messages().send(sender, "general.usage", Placeholder.unparsed("usage", usage()));
        return 0;
    }

    int run(CommandSender sender, String player, String rest) {
        if (!sender.hasPermission(PunishmentService.permission(type))
                || (type.isIp() && !sender.hasPermission(PunishmentService.IP))) {
            shared.messages().send(sender, "general.no-permission");
            return 0;
        }
        CommandArgs.Parsed parsed = CommandArgs.parse(rest);
        if (parsed.text().isEmpty()) {
            return usage(sender);
        }
        ReasonRegistry reasons = plugin.state().settings().reasons();
        ReasonRegistry.Resolution resolution = reasons.resolve(type, parsed.text()).orElseThrow();
        String note = shared.cleaned(resolution.note());
        shared.withTarget(sender, player, found -> {
            java.util.function.Consumer<Requests.Target> issue = target -> {
                // Read again at the moment of the action (DESIGN.md 2.1 #4).
                Requests.Issue request = new Requests.Issue(shared.actor(sender), target, resolution.reason(), note,
                        parsed.silent(), Punishment.Origin.COMMAND, null);
                plugin.punishments().issue(request).thenAccept(result -> shared.report(sender, request, result));
            };
            if (type.isIp()) {
                shared.withIpTarget(sender, found, issue);
            } else {
                issue.accept(found);
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
