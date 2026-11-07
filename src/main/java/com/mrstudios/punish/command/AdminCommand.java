package com.mrstudios.punish.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mrstudios.punish.config.Messages;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

/**
 * {@code /mrpunish [help|reload|version]}, with the aliases {@code /staffhelper} and {@code /mrp}.
 * Permissions are checked in the executor as well as in {@code requires}, so a client with a stale
 * command tree can't get around them.
 */
public final class AdminCommand {

    public static final String LABEL = "mrpunish";
    public static final List<String> ALIASES = List.of("staffhelper", "mrp");

    /** What the command needs from the plugin. */
    public interface Admin {

        Messages messages();

        /** Reloads every file and tells {@code sender} the outcome. */
        void reload(CommandSender sender);

        String pluginVersion();

        /** Server software and Minecraft version. */
        String serverVersion();

        /** The labels other plugins own instead of MR-Punish, or "none". */
        String clashSummary();
    }

    /** Help lines in order, each with the permission needed to see it. */
    private static final Map<String, String> HELP = helpLines();

    private final Admin admin;

    public AdminCommand(Admin admin) {
        this.admin = admin;
    }

    private static Map<String, String> helpLines() {
        Map<String, String> lines = new LinkedHashMap<>();
        lines.put("reload", Perms.RELOAD);
        lines.put("version", Perms.ADMIN);
        return lines;
    }

    /** Whether {@code sender} may use {@code /mrpunish} at all (help needs the base staff node). */
    static boolean mayUse(CommandSender sender) {
        return sender.hasPermission(Perms.STAFF) || sender.hasPermission(Perms.RELOAD)
                || sender.hasPermission(Perms.ADMIN);
    }

    public LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal(LABEL)
                .requires(source -> mayUse(source.getSender()))
                .executes(ctx -> help(ctx.getSource().getSender()))
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource().getSender())))
                .then(Commands.literal("reload")
                        .requires(source -> source.getSender().hasPermission(Perms.RELOAD))
                        .executes(ctx -> reload(ctx.getSource().getSender())))
                .then(Commands.literal("version")
                        .requires(source -> source.getSender().hasPermission(Perms.ADMIN))
                        .executes(ctx -> version(ctx.getSource().getSender())))
                // Anything else shows the help instead of Brigadier's "unknown command".
                .then(Commands.argument("unknown", StringArgumentType.greedyString())
                        .executes(ctx -> help(ctx.getSource().getSender())))
                .build();
    }

    int help(CommandSender sender) {
        Messages messages = admin.messages();
        if (!mayUse(sender)) {
            messages.send(sender, "general.no-permission");
            return 0;
        }
        List<String> visible = HELP.entrySet().stream()
                .filter(e -> sender.hasPermission(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        if (visible.isEmpty()) {
            messages.send(sender, "admin.help-none");
            return Command.SINGLE_SUCCESS;
        }
        messages.send(sender, "admin.help-header");
        for (String command : visible) {
            messages.send(sender, "admin.help." + command);
        }
        return Command.SINGLE_SUCCESS;
    }

    int reload(CommandSender sender) {
        if (!sender.hasPermission(Perms.RELOAD)) {
            admin.messages().send(sender, "general.no-permission");
            return 0;
        }
        admin.reload(sender);
        return Command.SINGLE_SUCCESS;
    }

    int version(CommandSender sender) {
        if (!sender.hasPermission(Perms.ADMIN)) {
            admin.messages().send(sender, "general.no-permission");
            return 0;
        }
        admin.messages().send(sender, "admin.version",
                Placeholder.unparsed("version", admin.pluginVersion()),
                Placeholder.unparsed("server", admin.serverVersion()),
                Placeholder.unparsed("clashes", admin.clashSummary()));
        return Command.SINGLE_SUCCESS;
    }
}
