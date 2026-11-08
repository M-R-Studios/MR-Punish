package com.mrstudios.punish.enforce;

import com.mrstudios.punish.config.DurationParser;
import com.mrstudios.punish.config.Messages;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.PunishmentService;
import com.mrstudios.punish.punish.PunishmentType;
import com.mrstudios.punish.punish.Requests;
import com.mrstudios.punish.storage.ActionRepo;
import com.mrstudios.punish.storage.PunishmentRepo;
import com.mrstudios.punish.storage.Storage;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Warnings and mutes (DESIGN.md 7.8): blocks a muted player's chat, blocked commands (by every name
 * they're known by), signs and book signing; tells warned and muted players; shows warnings given
 * while a player was offline when they join.
 */
public final class MuteEnforcement implements Listener, PunishmentService.Listener {

    private final Plugin plugin;
    private final Storage storage;
    private final Supplier<PluginState> state;
    private final MuteCache cache;
    private final CommandLabelResolver.Lookup commands;
    private final LongSupplier clock;
    private final Logger log;
    /** When each player was last told they're muted (for mute.notify-cooldown-seconds). */
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

    public MuteEnforcement(Plugin plugin, Storage storage, Supplier<PluginState> state, MuteCache cache,
            CommandLabelResolver.Lookup commands, LongSupplier clock, Logger log) {
        this.plugin = plugin;
        this.storage = storage;
        this.state = state;
        this.cache = cache;
        this.commands = commands;
        this.clock = clock;
        this.log = log;
    }

    public MuteCache cache() {
        return cache;
    }

    // ---- Blocking ----

    /** The player's mute right now, if they're muted. Any thread. */
    public Optional<Punishment> muteOf(Player player) {
        return cache.get(player.getUniqueId(), clock.getAsLong());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Optional<Punishment> mute = muteOf(event.getPlayer());
        if (mute.isPresent()) {
            event.setCancelled(true);
            notice(event.getPlayer(), mute.get());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Optional<Punishment> mute = muteOf(event.getPlayer());
        if (mute.isEmpty()) {
            return;
        }
        if (CommandLabelResolver.blocked(event.getMessage(), state.get().settings().mute().blockedCommands(),
                commands)) {
            event.setCancelled(true);
            notice(event.getPlayer(), mute.get());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        if (!state.get().settings().mute().blockSigns()) {
            return;
        }
        Optional<Punishment> mute = muteOf(event.getPlayer());
        if (mute.isEmpty()) {
            return;
        }
        boolean written = event.lines().stream()
                .anyMatch(line -> !net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(line).isBlank());
        if (written) {
            event.setCancelled(true);
            notice(event.getPlayer(), mute.get());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent event) {
        if (!event.isSigning() || !state.get().settings().mute().blockBooks()) {
            return;
        }
        Optional<Punishment> mute = muteOf(event.getPlayer());
        if (mute.isPresent()) {
            event.setCancelled(true);
            notice(event.getPlayer(), mute.get());
        }
    }

    /** {@code target.muted-blocked}, at most once per {@code mute.notify-cooldown-seconds}. */
    private void notice(Player player, Punishment mute) {
        PluginState current = state.get();
        long now = clock.getAsLong();
        long cooldown = current.settings().mute().notifyCooldownSeconds() * 1000L;
        Long last = lastNotice.get(player.getUniqueId());
        if (last != null && now - last < cooldown) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        Messages m = current.messages();
        String appeal = current.settings().display().appealLink();
        if (mute.expiresAt() == null) {
            m.send(player, "target.muted-blocked-permanent", Placeholder.parsed("reason", mute.reasonDisplay()),
                    Placeholder.unparsed("appeal-link", appeal));
        } else {
            m.send(player, "target.muted-blocked", Placeholder.parsed("reason", mute.reasonDisplay()),
                    Placeholder.unparsed("remaining", DurationParser.format(mute.expiresAt() - now)),
                    Placeholder.unparsed("appeal-link", appeal));
        }
    }

    // ---- Joins and quits ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        deliverOfflineWarnings(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cache.forget(event.getPlayer().getUniqueId());
        lastNotice.remove(event.getPlayer().getUniqueId());
    }

    /** Shows each warning given while the player was offline, once (DESIGN.md 7.8). */
    public void deliverOfflineWarnings(Player player) {
        if (!storage.health().isUp()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        storage.executor().submit("loading " + player.getName() + "'s offline warnings", c -> {
            ActionRepo actions = new ActionRepo(storage.database());
            PunishmentRepo punishments = new PunishmentRepo(storage.database());
            List<Punishment> warnings = new ArrayList<>();
            for (ActionRepo.Delivery delivery : actions.pending(c, uuid, "WARN")) {
                Optional<Punishment> warning = punishments.byId(c, delivery.refId());
                // Marked first: a warning is shown at most once, even if two servers race.
                if (actions.markDelivered(c, delivery.id(), clock.getAsLong()) && warning.isPresent()
                        && warning.get().status() != Punishment.StoredStatus.REMOVED) {
                    warnings.add(warning.get());
                }
            }
            return warnings;
        }).thenAccept(warnings -> {
            if (warnings.isEmpty()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player online = plugin.getServer().getPlayer(uuid);
                if (online == null) {
                    return;
                }
                PluginState current = state.get();
                for (Punishment warning : warnings) {
                    current.messages().send(online, "target.warn-offline",
                            Placeholder.parsed("reason", warning.reasonDisplay()),
                            Placeholder.unparsed("staff", warning.issuerName()),
                            Placeholder.unparsed("date", current.settings().display().date(warning.issuedAt())));
                }
            });
        });
    }

    /** Loads the mutes of players already online (after /reload or a plugin manager). */
    public void loadOnline() {
        if (!storage.health().isUp()) {
            return;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            long now = clock.getAsLong();
            storage.executor().submit("loading a mute", c -> new PunishmentRepo(storage.database())
                    .active(c, PunishmentType.MUTE, new PunishmentRepo.Subject(uuid, null), now).stream()
                    .reduce((a, b) -> com.mrstudios.punish.punish.ReplacementRules.later(a.expiresAt(), b.expiresAt())
                            ? a : b))
                    .thenAccept(mute -> cache.set(uuid, mute));
        }
    }

    // ---- After a commit (main thread) ----

    @Override
    public void issued(PluginState snapshot, Requests.Issue request, Requests.IssueResult.Committed committed) {
        Punishment p = committed.punishment();
        Player online = plugin.getServer().getPlayer(p.targetUuid());
        Messages m = snapshot.messages();
        Settings settings = snapshot.settings();
        if (p.type() == PunishmentType.MUTE) {
            cache.forget(committed.replaced());
            cache.put(p);
            if (online != null) {
                m.sendLines(online, "target.mute-chat", Placeholder.parsed("reason", p.reasonDisplay()),
                        Placeholder.unparsed("staff", p.issuerName()),
                        Placeholder.unparsed("duration", p.durationMs() == null ? m.raw("general.permanent")
                                : DurationParser.format(p.durationMs())),
                        Placeholder.unparsed("appeal-link", settings.display().appealLink()),
                        // A click style filled in by code: MiniMessage doesn't replace placeholders inside a
                        // <click:...> argument, so the URL can't be written into messages.yml as one.
                        TagResolver.resolver("appeal-click", net.kyori.adventure.text.minimessage.tag.Tag.styling(
                                net.kyori.adventure.text.event.ClickEvent.openUrl(settings.display().appealLink()))));
            }
        } else if (p.type() == PunishmentType.WARN && online != null) {
            TagResolver[] values = {Placeholder.parsed("reason", p.reasonDisplay()),
                    Placeholder.unparsed("staff", p.issuerName())};
            m.sendLines(online, "target.warn-chat", values);
            if (settings.warnTitle()) {
                Component main = m.component("target.warn-title", values);
                Component sub = m.component("target.warn-subtitle", values);
                online.showTitle(Title.title(main, sub));
            }
            Settings.SoundSetting sound = settings.warnedSound();
            if (sound.enabled()) {
                online.playSound(Sound.sound(sound.sound(), Sound.Source.MASTER, sound.volume(), sound.pitch()));
            }
        }
    }

    @Override
    public void removed(PluginState snapshot, Requests.Remove request, Requests.RemoveResult.Removed removed) {
        if (request.type() != PunishmentType.MUTE) {
            return;
        }
        List<Long> ids = removed.removed().stream().map(Punishment::id).toList();
        cache.forget(ids);
        Player online = plugin.getServer().getPlayer(request.target().uuid());
        if (online != null && cache.get(online.getUniqueId(), clock.getAsLong()).isEmpty()) {
            snapshot.messages().send(online, "target.unmuted");
        }
        if (log.isLoggable(java.util.logging.Level.FINE)) {
            log.fine("Unmuted " + request.target().name() + ": " + ids);
        }
    }
}
