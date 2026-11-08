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
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

/**
 * {@code /unmute <player> [reason...]}, {@code /unban <player> [reason...]} and
 * {@code /unwarn <player> [id] [reason...]} (DESIGN.md 4.1, 7.6). Unmute and unban remove every
 * active one (all duplicates); unwarn removes the given warning, or the most recent active one.
 * Everything is recorded with who, when and why. Vanilla's ban list isn't touched (use /pardon).
 */
public final class RemoveCommand implements CommandRegistry.Switchable {

    /** An unwarn id: a number, optionally written as #12. */
    private static final Pattern ID = Pattern.compile("#?\\d{1,18}");

    private final MRPunish plugin;
    private final PunishCommands shared;
    private final PunishmentType type;
    private final String name;
    private final String description;

    private RemoveCommand(MRPunish plugin, PunishmentType type, String name, String description) {
        this.plugin = plugin;
        this.shared = new PunishCommands(plugin);
        this.type = type;
        this.name = name;
        this.description = description;
    }

    public static RemoveCommand unwarn(MRPunish plugin) {
        return new RemoveCommand(plugin, PunishmentType.WARN, "unwarn", "Remove a warning.");
    }

    public static RemoveCommand unmute(MRPunish plugin) {
        return new RemoveCommand(plugin, PunishmentType.MUTE, "unmute", "Remove a player's active mute.");
    }

    public static RemoveCommand unban(MRPunish plugin) {
        return new RemoveCommand(plugin, PunishmentType.BAN, "unban", "Remove a player's active ban.");
    }

    public String usage() {
        return type == PunishmentType.WARN ? "/unwarn <player> [id] [reason...]" : "/" + name + " <player> [reason...]";
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
        shared.messages().send(sender, "general.usage", Placeholder.unparsed("usage", usage()));
        return 0;
    }

    int run(CommandSender sender, String player, String rest) {
        if (!sender.hasPermission(PunishmentService.REMOVE)) {
            shared.messages().send(sender, "general.no-permission");
            return 0;
        }
        Long id = null;
        String text = rest == null ? "" : rest.strip();
        if (type == PunishmentType.WARN && !text.isEmpty()) {
            String first = text.split("\\s+", 2)[0];
            if (ID.matcher(first).matches()) {
                id = Long.parseLong(first.startsWith("#") ? first.substring(1) : first);
                text = text.substring(first.length()).strip();
            }
        }
        String why = shared.cleaned(text);
        Long chosen = id;
        shared.withTarget(sender, player, target -> {
            Requests.Remove request = new Requests.Remove(shared.actor(sender), target, type, chosen, why);
            plugin.punishments().remove(request).thenAccept(result -> shared.report(sender, request, result));
        });
        return Command.SINGLE_SUCCESS;
    }
}
