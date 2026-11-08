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
import com.mrstudios.punish.rank.Authority;
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
public final class PunishCommands {

    private static final int MAX_SUGGESTIONS = 20;

    private final MRPunish plugin;

    public PunishCommands(MRPunish plugin) {
        this.plugin = plugin;
    }

    public Messages messages() {
        return plugin.state().messages();
    }

    /** The actor, read now on the main thread. Non-player senders act as console. */
    public Requests.Actor actor(CommandSender sender) {
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
    public void withTarget(CommandSender sender, String input, Consumer<Requests.Target> then) {
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
    public Requests.Target target(TargetResolver.Target found) {
        Player online = plugin.getServer().getPlayer(found.uuid());
        return online != null
                ? Requests.Target.online(plugin.rankCache().standing(online))
                : Requests.Target.offline(found.uuid(), found.name());
    }

    /**
     * For IP commands (DESIGN.md 8.3): finds the IP to punish (the online connection's, else the most
     * recent one seen) and the accounts associated with it, then calls {@code then} on the main thread
     * with a target carrying the hash and every other affected account. Never shows the IP.
     */
    public void withIpTarget(CommandSender sender, Requests.Target target, Consumer<Requests.Target> then) {
        withIpTarget(sender, target, then, () -> { });
    }

    /** As above; {@code failed} runs (main thread) after the sender was told why there's no target. */
    public void withIpTarget(CommandSender sender, Requests.Target target, Consumer<Requests.Target> then,
            Runnable failed) {
        com.mrstudios.punish.ip.IpFeatures ip = plugin.ip();
        if (!ip.enabled()) {
            messages().send(sender, "punish.ip-disabled");
            failed.run();
            return;
        }
        java.util.Optional<String> onlineHash = ip.onlineHash(target.uuid());
        long since = ip.associationSince(System.currentTimeMillis());
        plugin.storage().executor().submit("finding the IP of " + target.name(), c -> {
            com.mrstudios.punish.storage.IpRepo repo = new com.mrstudios.punish.storage.IpRepo(
                    plugin.storage().database());
            java.util.Optional<String> hash = onlineHash.isPresent() ? onlineHash : repo.latestHash(c, target.uuid());
            if (hash.isEmpty()) {
                return null;
            }
            return new Object[] {hash.get(), repo.associated(c, hash.get(), since)};
        }).whenComplete((found, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                messages().send(sender, "general.storage-unavailable");
                failed.run();
                return;
            }
            if (found == null) {
                messages().send(sender, "punish.ip-unknown", Placeholder.unparsed("player", target.name()));
                failed.run();
                return;
            }
            String hash = (String) found[0];
            @SuppressWarnings("unchecked")
            java.util.List<com.mrstudios.punish.storage.IpRepo.Seen> associated =
                    (java.util.List<com.mrstudios.punish.storage.IpRepo.Seen>) found[1];
            java.util.Map<java.util.UUID, com.mrstudios.punish.rank.Authority.Standing> others =
                    new java.util.LinkedHashMap<>();
            for (com.mrstudios.punish.storage.IpRepo.Seen seen : associated) {
                others.put(seen.uuid(), new com.mrstudios.punish.rank.Authority.Standing(seen.uuid(), seen.name(),
                        seen.weight(), seen.exempt()));
            }
            // Accounts online on that IP right now are read live (DESIGN.md 8.4).
            for (java.util.UUID uuid : ip.onlineOn(hash)) {
                Player online = plugin.getServer().getPlayer(uuid);
                if (online != null) {
                    others.put(uuid, plugin.rankCache().standing(online));
                }
            }
            others.remove(target.uuid());
            then.accept(new Requests.Target(target.uuid(), target.name(), target.live(), hash,
                    new java.util.ArrayList<>(others.values())));
        }));
    }

    /** For /unmuteip and /unbanip: every hash the player was seen with within association-days. */
    void withIpHashes(CommandSender sender, Requests.Target target, Consumer<java.util.List<String>> then) {
        com.mrstudios.punish.ip.IpFeatures ip = plugin.ip();
        if (!ip.enabled()) {
            messages().send(sender, "punish.ip-disabled");
            return;
        }
        java.util.Optional<String> onlineHash = ip.onlineHash(target.uuid());
        long since = ip.associationSince(System.currentTimeMillis());
        plugin.storage().executor().submit("finding the IPs of " + target.name(), c ->
                new com.mrstudios.punish.storage.IpRepo(plugin.storage().database()).hashesOf(c, target.uuid(), since))
                .whenComplete((hashes, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        messages().send(sender, "general.storage-unavailable");
                        return;
                    }
                    java.util.List<String> all = new java.util.ArrayList<>(hashes);
                    onlineHash.filter(h -> !all.contains(h)).ifPresent(all::add);
                    if (all.isEmpty()) {
                        messages().send(sender, "punish.ip-unknown", Placeholder.unparsed("player", target.name()));
                        return;
                    }
                    then.accept(all);
                }));
    }

    public String cleaned(String text) {
        return CommandArgs.clean(text, plugin.state().settings().maxTextLength());
    }

    // ---- Outcomes ----

    public void report(CommandSender sender, Requests.Issue request, IssueResult result) {
        PluginState state = plugin.state();
        Messages m = state.messages();
        String player = request.target().name();
        switch (result) {
            case IssueResult.Committed c -> {
                Punishment p = c.punishment();
                m.send(sender, "punish." + verbKey(p.type()),
                        Placeholder.unparsed("accounts", String.valueOf(1 + request.target().affected().size())),
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
            case IssueResult.Refused r when request.type().isIp() && r.verdict() != Authority.Verdict.SELF -> {
                m.send(sender, "punish.ip-rank", Placeholder.unparsed("player", player),
                        Placeholder.unparsed("count", String.valueOf(r.refusedAccounts())));
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
            case IssueResult.Changed ch -> m.send(sender, "punish.changed", Placeholder.unparsed("player", player));
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
            case RemoveResult.NotFound n -> m.send(sender, "unpunish.unwarn-not-found",
                    Placeholder.unparsed("id", String.valueOf(n.id())), Placeholder.unparsed("player", player));
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
