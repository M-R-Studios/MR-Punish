package com.mrstudios.punish.punish;

import com.mrstudios.punish.config.DurationParser;
import com.mrstudios.punish.config.Messages;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.punish.Requests.IssueResult;
import com.mrstudios.punish.punish.Requests.RemoveResult;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;

/**
 * Tells staff, the console and (if configured) everyone about committed punishments and removals
 * (DESIGN.md 5.4). Main thread only. Names, notes and typed reasons are inserted unparsed, so they
 * can never add MiniMessage tags; IP addresses are never part of any message (only an account count).
 */
public final class Notifier {

    public static final String NOTIFY = "mrpunish.notify";

    private final Supplier<Collection<? extends Player>> online;
    private final Logger console;

    public Notifier(Supplier<Collection<? extends Player>> online, Logger console) {
        this.online = online;
        this.console = console;
    }

    /** Staff with {@code mrpunish.notify}, except the actor (who gets their own confirmation). */
    private Collection<? extends Player> staff(UUID actor) {
        return online.get().stream()
                .filter(p -> p.hasPermission(NOTIFY) && !p.getUniqueId().equals(actor))
                .toList();
    }

    public void issued(PluginState state, Requests.Issue request, IssueResult.Committed committed) {
        Messages m = state.messages();
        Settings settings = state.settings();
        Punishment p = committed.punishment();
        Component line = m.component("notify.issued",
                Placeholder.unparsed("staff", p.issuerName()),
                Placeholder.unparsed("type-verb", m.raw("notify.verbs." + p.type().name())),
                Placeholder.unparsed("target", p.targetName()),
                Placeholder.parsed("reason", p.reasonDisplay()),
                Placeholder.component("duration", duration(m, p)),
                Placeholder.unparsed("id", String.valueOf(p.id())),
                Placeholder.component("silent", p.silent() ? m.component("notify.silent-tag") : Component.empty()),
                Placeholder.component("note", p.note() == null ? Component.empty()
                        : m.component("notify.note-suffix", Placeholder.unparsed("note", p.note()))));
        if (p.type().isIp()) {
            int accounts = 1 + request.target().affected().size();
            line = line.append(m.component("notify.ip-suffix", Placeholder.unparsed("accounts", String.valueOf(accounts))));
        }
        sendToStaff(m, settings, request.actor().uuid(), line);
        if (settings.announces(p.type()) && !p.silent() && !m.raw("announce." + p.type().name()).isEmpty()) {
            Component announcement = m.component("announce." + p.type().name(),
                    Placeholder.unparsed("target", p.targetName()),
                    Placeholder.parsed("reason", p.reasonDisplay()));
            for (Player player : online.get()) {
                player.sendMessage(announcement);
            }
            console.info(plain(announcement));
        }
    }

    public void removed(PluginState state, Requests.Remove request, RemoveResult.Removed removed) {
        Messages m = state.messages();
        Punishment first = removed.removed().get(0);
        Component line = m.component("notify.removed",
                Placeholder.unparsed("staff", request.actor().name()),
                Placeholder.unparsed("type", m.raw("types." + request.type().name())),
                Placeholder.unparsed("ids", removed.removed().stream().map(p -> String.valueOf(p.id()))
                        .collect(Collectors.joining(", #"))),
                Placeholder.unparsed("target", first.targetName()),
                Placeholder.unparsed("removal-reason", first.removedReason() == null ? "" : first.removedReason()));
        sendToStaff(m, state.settings(), request.actor().uuid(), line);
    }

    private void sendToStaff(Messages m, Settings settings, UUID actor, Component line) {
        if (plain(line).isEmpty()) {
            return;
        }
        Settings.SoundSetting sound = settings.notifySound();
        for (Player player : staff(actor)) {
            player.sendMessage(line);
            if (sound.enabled()) {
                player.playSound(Sound.sound(sound.sound(), Sound.Source.MASTER, sound.volume(), sound.pitch()));
            }
        }
        console.info(plain(line));
    }

    /** "2h", "6d 23h", "permanent", or for warnings "offence 3". */
    static Component duration(Messages m, Punishment p) {
        if (!p.type().hasDuration()) {
            return m.component("general.warning-offence", Placeholder.unparsed("offence", String.valueOf(p.offence())));
        }
        if (p.durationMs() == null) {
            return m.component("general.permanent");
        }
        return Component.text(DurationParser.format(p.durationMs()));
    }

    static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
