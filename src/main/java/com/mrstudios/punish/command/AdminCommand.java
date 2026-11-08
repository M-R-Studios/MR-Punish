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

        /** Which database, e.g. {@code SQLite (punishments.db)}. */
        String storageDescription();

        /** Whether it's connected, and why not. */
        String storageStatus();

        /** IP features "on", or "off (why)". Never an address. */
        default String ipStatus() {
            return "off";
        }

        /** {@code /mrpunish import vanilla [--clear]}: imports vanilla's ban list (DESIGN.md 7.9). */
        void importVanilla(CommandSender sender, boolean clear);

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
        lines.put("import", Perms.IMPORT);
        return lines;
    }

    /** Whether {@code sender} may use {@code /mrpunish} at all (help needs the base staff node). */
    static boolean mayUse(CommandSender sender) {
        return sender.hasPermission(Perms.STAFF) || sender.hasPermission(Perms.RELOAD)
                || sender.hasPermission(Perms.ADMIN) || sender.hasPermission(Perms.IMPORT);
    }

    /** Sub-commands as typed, each with the permission that shows and allows it. */
    private static final Map<String, String> SUBCOMMANDS = subcommands();

    private static Map<String, String> subcommands() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("help", Perms.STAFF);
        map.put("reload", Perms.RELOAD);
        map.put("version", Perms.ADMIN);
        map.put("import vanilla", Perms.IMPORT);
        map.put("import vanilla --clear", Perms.IMPORT);
        return map;
    }

    /**
     * One greedy argument rather than literal children: Brigadier lists a node's literals in tab
     * completion without checking their {@code requires}, so sub-commands are suggested here only to
     * senders who may use them, and anything else gets a clear answer from {@link #dispatch}.
     */
    public LiteralCommandNode<CommandSourceStack> node() {
        return Commands.literal(LABEL)
                .requires(source -> mayUse(source.getSender()))
                .executes(ctx -> help(ctx.getSource().getSender()))
                .then(Commands.argument("subcommand", StringArgumentType.greedyString())
                        .suggests((ctx, builder) -> {
                            CommandSender sender = ctx.getSource().getSender();
                            String typed = builder.getRemaining().toLowerCase(java.util.Locale.ROOT);
                            SUBCOMMANDS.forEach((sub, permission) -> {
                                if (sub.startsWith(typed) && sender.hasPermission(permission)) {
                                    builder.suggest(sub);
                                }
                            });
                            return builder.buildFuture();
                        })
                        .executes(ctx -> dispatch(ctx.getSource().getSender(),
                                StringArgumentType.getString(ctx, "subcommand"))))
                .build();
    }

    int dispatch(CommandSender sender, String input) {
        String sub = input.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        return switch (sub) {
            case "reload" -> reload(sender);
            case "version" -> version(sender);
            case "import vanilla" -> importVanilla(sender, false);
            case "import vanilla --clear" -> importVanilla(sender, true);
            // "help" and anything unknown show the help (only what the sender may use).
            default -> help(sender);
        };
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

    int importVanilla(CommandSender sender, boolean clear) {
        if (!sender.hasPermission(Perms.IMPORT)) {
            admin.messages().send(sender, "general.no-permission");
            return 0;
        }
        admin.importVanilla(sender, clear);
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
                Placeholder.unparsed("storage", admin.storageDescription()),
                Placeholder.unparsed("storage-status", admin.storageStatus()),
                Placeholder.unparsed("ip-features", admin.ipStatus()),
                Placeholder.unparsed("clashes", admin.clashSummary()));
        return Command.SINGLE_SUCCESS;
    }
}
