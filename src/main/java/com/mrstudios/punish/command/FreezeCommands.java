package com.mrstudios.punish.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.gui.InspectMenus;
import com.mrstudios.punish.rank.Authority;
import com.mrstudios.punish.staff.FreezeService;
import com.mrstudios.punish.staff.FreezeService.Outcome;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.function.BiFunction;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /freeze <player>}, {@code /unfreeze <player>} (offline too), {@code /invsee <player>} and
 * {@code /endersee <player>} (DESIGN.md 11.3, 11.4). Permissions are checked when run.
 */
public final class FreezeCommands {

    private final MRPunish plugin;
    private final PunishCommands shared;

    public FreezeCommands(MRPunish plugin) {
        this.plugin = plugin;
        this.shared = new PunishCommands(plugin);
    }

    public List<CommandRegistry.Switchable> all() {
        return List.of(
                command("freeze", "Freeze a player.", FreezeService.PERMISSION, this::freeze),
                command("unfreeze", "Unfreeze a player (also offline).", FreezeService.PERMISSION, this::unfreeze),
                command("invsee", "View a player's inventory (view only).", InspectMenus.INVSEE, this::invsee),
                command("endersee", "View a player's ender chest (view only).", InspectMenus.ENDERSEE, this::endersee));
    }

    private CommandRegistry.Switchable command(String name, String description, String permission,
            BiFunction<CommandSender, String, Integer> run) {
        return new CommandRegistry.Switchable() {
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
                        .requires(s -> s.getSender().hasPermission(permission))
                        .executes(ctx -> {
                            shared.messages().send(ctx.getSource().getSender(), "general.usage",
                                    Placeholder.unparsed("usage", "/" + name + " <player>"));
                            return 0;
                        })
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(shared.players())
                                .executes(ctx -> run.apply(ctx.getSource().getSender(),
                                        StringArgumentType.getString(ctx, "player"))));
            }
        };
    }

    private boolean refuse(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return false;
        }
        shared.messages().send(sender, "general.no-permission");
        return true;
    }

    /** An online player the sender can see (vanished players they can't see are "unknown"). */
    private Player visible(CommandSender sender, String name) {
        Player target = plugin.getServer().getPlayerExact(name);
        if (target != null && sender instanceof Player viewer && !plugin.vanish().canSee(viewer, target)) {
            return null;
        }
        return target;
    }

    int freeze(CommandSender sender, String name) {
        if (refuse(sender, FreezeService.PERMISSION)) {
            return 0;
        }
        Player target = visible(sender, name);
        if (target == null) {
            shared.messages().send(sender, "staff.freeze-offline", Placeholder.unparsed("player", name));
            return 0;
        }
        if (sender instanceof Player p && p.equals(target)) {
            shared.messages().send(sender, "staff.freeze-self");
            return 0;
        }
        Authority.Standing actor = shared.actor(sender).standing();
        plugin.freeze().freeze(sender, actor, target).thenAccept(outcome -> {
            switch (outcome) {
                case Outcome.Done d -> shared.messages().send(sender, "staff.freeze-done",
                        Placeholder.unparsed("player", target.getName()));
                case Outcome.Already a -> shared.messages().send(sender, "staff.already-frozen",
                        Placeholder.unparsed("player", target.getName()));
                case Outcome.Refused r -> shared.messages().send(sender, r.verdict() == Authority.Verdict.SELF
                        ? "staff.freeze-self" : r.verdict() == Authority.Verdict.EXEMPT ? "punish.exempt"
                        : "staff.freeze-rank", Placeholder.unparsed("player", target.getName()));
                case Outcome.Unavailable u -> shared.messages().send(sender, "general.storage-unavailable");
                case Outcome.Failed f -> shared.messages().send(sender, "staff.freeze-failed",
                        Placeholder.unparsed("error-id", f.errorId()));
                case Outcome.NotFrozen n -> { }
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    int unfreeze(CommandSender sender, String name) {
        if (refuse(sender, FreezeService.PERMISSION)) {
            return 0;
        }
        Authority.Standing actor = shared.actor(sender).standing();
        shared.withTarget(sender, name, target -> plugin.freeze().unfreeze(actor, target.uuid(), target.name())
                .thenAccept(outcome -> {
                    switch (outcome) {
                        case Outcome.Done d -> shared.messages().send(sender, "staff.unfreeze-done",
                                Placeholder.unparsed("player", target.name()));
                        case Outcome.NotFrozen n -> shared.messages().send(sender, "staff.not-frozen",
                                Placeholder.unparsed("player", target.name()));
                        case Outcome.Unavailable u -> shared.messages().send(sender, "general.storage-unavailable");
                        case Outcome.Failed f -> shared.messages().send(sender, "staff.freeze-failed",
                                Placeholder.unparsed("error-id", f.errorId()));
                        default -> { }
                    }
                }));
        return Command.SINGLE_SUCCESS;
    }

    private int inspect(CommandSender sender, String name, String permission, boolean ender) {
        if (!(sender instanceof Player viewer)) {
            shared.messages().send(sender, "general.player-only");
            return 0;
        }
        if (refuse(sender, permission)) {
            return 0;
        }
        Player target = visible(sender, name);
        if (target == null) {
            shared.messages().send(sender, "staff.inspect-offline", Placeholder.unparsed("player", name));
            return 0;
        }
        if (target.equals(viewer) && !ender) {
            shared.messages().send(sender, "staff.invsee-self");
            return 0;
        }
        if (ender) {
            plugin.inspect().openEnderChest(viewer, target);
        } else {
            plugin.inspect().openInventory(viewer, target);
        }
        return Command.SINGLE_SUCCESS;
    }

    int invsee(CommandSender sender, String name) {
        return inspect(sender, name, InspectMenus.INVSEE, false);
    }

    int endersee(CommandSender sender, String name) {
        return inspect(sender, name, InspectMenus.ENDERSEE, true);
    }
}
