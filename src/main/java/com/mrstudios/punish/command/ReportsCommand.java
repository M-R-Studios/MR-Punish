package com.mrstudios.punish.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.gui.ReportMenus;
import com.mrstudios.punish.report.ReportService;
import com.mrstudios.punish.storage.ReportRepo.Filter;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Locale;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /reports [open|claimed|mine|closed|all]}: the reports menu (DESIGN.md 13.3). Players only. */
public final class ReportsCommand implements CommandRegistry.Switchable {

    private final MRPunish plugin;
    private final PunishCommands shared;

    public ReportsCommand(MRPunish plugin) {
        this.plugin = plugin;
        this.shared = new PunishCommands(plugin);
    }

    @Override
    public String name() {
        return "reports";
    }

    @Override
    public String description() {
        return "Open the reports menu.";
    }

    @Override
    public LiteralArgumentBuilder<CommandSourceStack> build(String literal) {
        return Commands.literal(literal)
                .requires(source -> source.getSender().hasPermission(ReportService.REPORTS))
                .executes(ctx -> run(ctx.getSource().getSender(), "open"))
                .then(Commands.argument("filter", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (Filter f : Filter.values()) {
                                String name = f.name().toLowerCase(Locale.ROOT);
                                if (name.startsWith(builder.getRemaining().toLowerCase(Locale.ROOT))) {
                                    builder.suggest(name);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> run(ctx.getSource().getSender(), StringArgumentType.getString(ctx, "filter"))));
    }

    int run(CommandSender sender, String filter) {
        if (!(sender instanceof Player viewer)) {
            shared.messages().send(sender, "general.player-only");
            return 0;
        }
        if (!viewer.hasPermission(ReportService.REPORTS)) {
            shared.messages().send(sender, "general.no-permission");
            return 0;
        }
        Filter chosen;
        try {
            chosen = Filter.valueOf(filter.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            shared.messages().send(sender, "general.usage",
                    Placeholder.unparsed("usage", "/reports [open|claimed|mine|closed|all]"));
            return 0;
        }
        plugin.reportMenus().openList(viewer, new ReportMenus.View(chosen));
        return Command.SINGLE_SUCCESS;
    }
}
