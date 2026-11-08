package com.mrstudios.punish.command;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.config.DurationParser;
import com.mrstudios.punish.config.Messages;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.PunishmentType;
import com.mrstudios.punish.punish.Reason;
import com.mrstudios.punish.punish.ReasonRegistry;
import com.mrstudios.punish.punish.Requests;
import com.mrstudios.punish.punish.Requests.IssueResult;
import com.mrstudios.punish.punish.Requests.RemoveResult;
import com.mrstudios.punish.punish.TargetResolver;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

/**
 * What every punishment command shares: who the actor is, resolving the player argument, sending
 * the outcome, and tab completion from memory only (DESIGN.md 4.1).
 */
final class PunishCommands {

    private static final int MAX_SUGGESTIONS = 20;

    private final MRPunish plugin;

    PunishCommands(MRPunish plugin) {
        this.plugin = plugin;
    }

    Messages messages() {
        return plugin.state().messages();
    }

    /** The actor, read now on the main thread. Non-player senders act as console. */
    Requests.Actor actor(CommandSender sender) {
        if (sender instanceof Player player) {
            return new Requests.Actor(plugin.rankCache().standing(player), player::hasPermission);
        }
        String name = sender instanceof ConsoleCommandSender ? messages().raw("general.console-name") : sender.getName();
        return new Requests.Actor(com.mrstudios.punish.rank.Authority.Standing.console(name), sender::hasPermission);
    }

    /**
     * Resolves {@code input} and calls {@code then} on the main thread with the target, or tells the
     * sender it's invalid or unknown.
     */
    void withTarget(CommandSender sender, String input, Consumer<Requests.Target> then) {
        plugin.targets().resolve(input).whenComplete((resolution, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        messages().send(sender, "general.storage-unavailable");
                        return;
                    }
                    switch (resolution.outcome()) {
                        case INVALID -> messages().send(sender, "general.invalid-player",
                                Placeholder.unparsed("input", resolution.input()));
                        case UNKNOWN -> messages().send(sender, "general.unknown-player",
                                Placeholder.unparsed("player", resolution.input()));
                        case FOUND -> then.accept(target(resolution.target()));
                    }
                }));
    }

    /** An online target's standing is read live, now. */
    Requests.Target target(TargetResolver.Target found) {
        Player online = plugin.getServer().getPlayer(found.uuid());
        return online != null
                ? Requests.Target.online(plugin.rankCache().standing(online))
                : Requests.Target.offline(found.uuid(), found.name());
    }

    String cleaned(String text) {
        return CommandArgs.clean(text, plugin.state().settings().maxTextLength());
    }

    // ---- Outcomes ----

    void report(CommandSender sender, Requests.Issue request, IssueResult result) {
        PluginState state = plugin.state();
        Messages m = state.messages();
        String player = request.target().name();
        switch (result) {
            case IssueResult.Committed c -> {
                Punishment p = c.punishment();
                m.send(sender, "punish." + verbKey(p.type()),
                        Placeholder.unparsed("player", p.targetName()),
                        Placeholder.parsed("reason", p.reasonDisplay()),
                        Placeholder.unparsed("duration", p.durationMs() == null ? m.raw("general.permanent")
                                : DurationParser.format(p.durationMs())),
                        Placeholder.unparsed("id", String.valueOf(p.id())),
                        Placeholder.unparsed("offence", String.valueOf(p.offence())));
                for (long old : c.replaced()) {
                    m.send(sender, "punish.replaced", Placeholder.unparsed("old-id", String.valueOf(old)));
                }
                sound(sender, state.settings().punishedSound());
            }
            case IssueResult.WouldShorten w -> {
                Punishment b = w.blocking();
                String type = m.raw("types." + b.type().name());
                if (b.expiresAt() == null) {
                    m.send(sender, "punish.would-shorten-permanent", Placeholder.unparsed("player", player),
                            Placeholder.unparsed("type", type), Placeholder.unparsed("id", String.valueOf(b.id())));
                } else {
                    long now = System.currentTimeMillis();
                    m.send(sender, "punish.would-shorten", Placeholder.unparsed("player", player),
                            Placeholder.unparsed("type", type), Placeholder.unparsed("id", String.valueOf(b.id())),
                            Placeholder.unparsed("end", state.settings().display().date(b.expiresAt())),
                            Placeholder.unparsed("remaining", DurationParser.format(b.expiresAt() - now)));
                }
                sound(sender, state.settings().failedSound());
            }
            case IssueResult.Refused r -> {
                String key = switch (r.verdict()) {
                    case SELF -> "punish.self";
                    case EXEMPT -> "punish.exempt";
                    default -> "punish.rank";
                };
                m.send(sender, key, Placeholder.unparsed("player", player));
                sound(sender, state.settings().failedSound());
            }
            case IssueResult.NoPermission n -> {
                if (n.permission().equals(request.reason().permission())) {
                    m.send(sender, "punish.reason-no-permission",
                            Placeholder.parsed("reason", request.reason().display()));
                } else {
                    m.send(sender, "general.no-permission");
                }
            }
            case IssueResult.Busy b -> m.send(sender, "punish.busy");
            case IssueResult.UnknownTarget u -> m.send(sender, "general.unknown-player",
                    Placeholder.unparsed("player", player));
            case IssueResult.Unavailable u -> unavailable(sender);
            case IssueResult.Failed f -> {
                m.send(sender, "punish.failed", Placeholder.unparsed("error-id", f.errorId()));
                sound(sender, state.settings().failedSound());
            }
            case IssueResult.Changed ch -> m.send(sender, "punish.busy");
        }
    }

    void report(CommandSender sender, Requests.Remove request, RemoveResult result) {
        Messages m = messages();
        String player = request.target().name();
        String type = m.raw("types." + request.type().name());
        switch (result) {
            case RemoveResult.Removed r -> m.send(sender, "unpunish.removed",
                    Placeholder.unparsed("count", String.valueOf(r.removed().size())),
                    Placeholder.unparsed("type", type), Placeholder.unparsed("player", player),
                    Placeholder.unparsed("ids", r.removed().stream().map(p -> "#" + p.id())
                            .collect(Collectors.joining(", "))));
            case RemoveResult.None n -> m.send(sender, "unpunish.none", Placeholder.unparsed("player", player),
                    Placeholder.unparsed("type", type));
            case RemoveResult.NotFound n -> m.send(sender, "unpunish.none", Placeholder.unparsed("player", player),
                    Placeholder.unparsed("type", type));
            case RemoveResult.IssuerRank i -> m.send(sender, "unpunish.issuer-rank",
                    Placeholder.unparsed("id", String.valueOf(i.id())));
            case RemoveResult.Self s -> m.send(sender, "unpunish.self");
            case RemoveResult.NoPermission n -> m.send(sender, "general.no-permission");
            case RemoveResult.Busy b -> m.send(sender, "punish.busy");
            case RemoveResult.Unavailable u -> unavailable(sender);
            case RemoveResult.Failed f -> m.send(sender, "unpunish.failed",
                    Placeholder.unparsed("error-id", f.errorId()));
        }
    }

    private void unavailable(CommandSender sender) {
        messages().send(sender, plugin.storage().executor().isShutDown()
                ? "general.server-stopping" : "general.storage-unavailable");
    }

    private static String verbKey(PunishmentType type) {
        return switch (type) {
            case WARN -> "warned";
            case MUTE -> "muted";
            case BAN -> "banned";
            case IP_MUTE -> "ip-muted";
            case IP_BAN -> "ip-banned";
        };
    }

    private static void sound(CommandSender sender, Settings.SoundSetting sound) {
        if (sound.enabled() && sender instanceof Player player) {
            player.playSound(Sound.sound(sound.sound(), Sound.Source.MASTER, sound.volume(), sound.pitch()));
        }
    }

    // ---- Tab completion: memory only ----

    /** Online names the sender can see first, then known names, at most 20. */
    SuggestionProvider<CommandSourceStack> players() {
        return (ctx, builder) -> {
            CommandSender sender = ctx.getSource().getSender();
            String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
            Set<String> names = new LinkedHashSet<>();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if ((!(sender instanceof Player viewer) || viewer.canSee(p))
                        && p.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                    names.add(p.getName());
                }
            }
            for (String known : plugin.names().suggest(typed, MAX_SUGGESTIONS)) {
                if (names.size() >= MAX_SUGGESTIONS) {
                    break;
                }
                names.add(known);
            }
            names.stream().limit(MAX_SUGGESTIONS).forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    /** For {@code [-s] [reason] [note...]}: -s and the reasons of {@code type} the sender may use. */
    SuggestionProvider<CommandSourceStack> reasons(PunishmentType type) {
        return (ctx, builder) -> suggestReasons(ctx.getSource().getSender(), type, builder);
    }

    private CompletableFuture<Suggestions> suggestReasons(CommandSender sender, PunishmentType type,
            SuggestionsBuilder builder) {
        String rest = builder.getRemaining();
        int[] at = CommandArgs.completing(rest);
        SuggestionsBuilder word = builder.createOffset(builder.getStart() + at[0]);
        String typed = rest.substring(at[0]).toLowerCase(Locale.ROOT);
        if (at[1] == 1) {
            if (CommandArgs.SILENT.startsWith(typed) && !rest.toLowerCase(Locale.ROOT).contains(CommandArgs.SILENT + " ")) {
                word.suggest(CommandArgs.SILENT);
            }
            ReasonRegistry reasons = plugin.state().settings().reasons();
            for (Map.Entry<String, Reason> entry : reasons.words(type).entrySet()) {
                if (entry.getKey().startsWith(typed) && ReasonRegistry.mayUse(entry.getValue(), sender::hasPermission)) {
                    word.suggest(entry.getKey());
                }
            }
        }
        return word.buildFuture();
    }
}
