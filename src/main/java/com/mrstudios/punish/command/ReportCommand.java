package com.mrstudios.punish.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.gui.ReportMenus;
import com.mrstudios.punish.report.ReportService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /report <player> [reason...]} (DESIGN.md 13.1). Players only. Without a reason it opens
 * the report reason menu. A reason that is a configured report reason's key or display picks that
 * reason; any other text is the reason as typed.
 */
public final class ReportCommand implements CommandRegistry.Switchable {

    private final MRPunish plugin;
    private final PunishCommands shared;

    public ReportCommand(MRPunish plugin) {
        this.plugin = plugin;
        this.shared = new PunishCommands(plugin);
    }

    @Override
    public String name() {
        return "report";
    }

    @Override
    public String description() {
        return "Report a player to staff.";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build(String literal) {
        return Commands.literal(literal)
                .requires(source -> source.getSender().hasPermission(ReportService.REPORT))
                .executes(ctx -> usage(ctx.getSource().getSender()))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(shared.players())
                        .executes(ctx -> run(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "player"), ""))
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> {
                                    String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
                                    for (Settings.ReportReason r : plugin.state().settings().reports().reasons()) {
                                        if (r.key().startsWith(typed)) {
                                            builder.suggest(r.key());
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> run(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player"),
                                        StringArgumentType.getString(ctx, "reason")))));
    }

    private int usage(CommandSender sender) {
        shared.messages().send(sender, "general.usage", Placeholder.unparsed("usage", "/report <player> [reason...]"));
        return 0;
    }

    /** The configured reason {@code text} names (by key or display, any case), if any. */
    static Optional<Settings.ReportReason> configured(Settings.Reports reports, String text) {
        String wanted = text.strip().toLowerCase(Locale.ROOT);
        return reports.reasons().stream()
                .filter(r -> r.key().equals(wanted)
                        || MiniMessage.miniMessage().stripTags(r.display()).toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst();
    }

    int run(CommandSender sender, String player, String rest) {
        if (!(sender instanceof Player reporter)) {
            shared.messages().send(sender, "general.player-only");
            return 0;
        }
        if (!reporter.hasPermission(ReportService.REPORT)) {
            shared.messages().send(sender, "general.no-permission");
            return 0;
        }
        ReportMenus menus = plugin.reportMenus();
        String text = menus.cleaned(rest);
        shared.withTarget(reporter, player, target -> {
            if (!reporter.isOnline()) {
                return;
            }
            if (reporter.getUniqueId().equals(target.uuid())) {
                shared.messages().send(reporter, "reports.self");
                return;
            }
            if (text == null) {
                menus.openReasons(reporter, target.uuid(), target.name());
                return;
            }
            Optional<Settings.ReportReason> reason = configured(plugin.state().settings().reports(), text);
            if (reason.isPresent()) {
                menus.submit(reporter, target.uuid(), target.name(), reason.get().key(), reason.get().display());
            } else {
                menus.submit(reporter, target.uuid(), target.name(), null, text);
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
