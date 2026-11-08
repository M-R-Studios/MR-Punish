package com.mrstudios.punish.report;

import com.mrstudios.punish.config.Messages;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.Settings;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * Everything players are told about reports (DESIGN.md 13.1-13.4): new reports and decisions to
 * staff with {@code mrpunish.reports}, the outcome to the reporter (now, or on their next join),
 * expired claims to the claimer, and the reminder of open reports. Main thread.
 */
public final class ReportNotices implements ReportService.Listener, Listener {

    private final Plugin plugin;
    private final Server server;
    private final Supplier<PluginState> state;
    private final ReportService reports;
    private final Logger log;
    private long lastReminder = System.currentTimeMillis();

    public ReportNotices(Plugin plugin, Supplier<PluginState> state, ReportService reports, Logger log) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.state = state;
        this.reports = reports;
        this.log = log;
    }

    /**
     * The reason as a placeholder: a configured reason's display is MiniMessage from config, a
     * typed reason is shown as plain text.
     */
    public static TagResolver reason(Report r) {
        return r.reasonKey() != null ? Placeholder.parsed("reason", r.reasonText())
                : Placeholder.unparsed("reason", r.reasonText());
    }

    private Messages messages() {
        return state.get().messages();
    }

    private void toStaff(String key, TagResolver... placeholders) {
        for (Player p : server.getOnlinePlayers()) {
            if (p.hasPermission(ReportService.REPORTS)) {
                messages().send(p, key, placeholders);
            }
        }
    }

    @Override
    public void created(Report r) {
        toStaff("reports.new", Placeholder.unparsed("reporter", r.reporterName()),
                Placeholder.unparsed("target", r.targetName()), reason(r),
                Placeholder.unparsed("id", String.valueOf(r.id())));
        Settings.SoundSetting sound = state.get().settings().reports().sound();
        if (sound.enabled()) {
            for (Player p : server.getOnlinePlayers()) {
                if (p.hasPermission(ReportService.REPORTS)) {
                    p.playSound(Sound.sound(sound.sound(), Sound.Source.MASTER, sound.volume(), sound.pitch()));
                }
            }
        }
        String reason = r.reasonKey() != null ? MiniMessage.miniMessage().stripTags(r.reasonText()) : r.reasonText();
        log.info("Report #" + r.id() + ": " + r.reporterName() + " reported " + r.targetName() + ": " + reason);
    }

    @Override
    public void closed(Report r, ReportService.Actor by) {
        toStaff(r.status() == Report.Status.RESOLVED ? "reports.resolved" : "reports.rejected",
                Placeholder.unparsed("staff", by.name()), Placeholder.unparsed("id", String.valueOf(r.id())),
                Placeholder.unparsed("target", r.targetName()));
        Player reporter = server.getPlayer(r.reporterUuid());
        if (reporter != null) {
            deliver(reporter);
        }
    }

    @Override
    public void claimExpired(Report r) {
        if (r.claimedByUuid() != null) {
            Player claimer = server.getPlayer(r.claimedByUuid());
            if (claimer != null) {
                messages().send(claimer, "reports.claim-expired", Placeholder.unparsed("id", String.valueOf(r.id())));
            }
        }
    }

    /** Shows the player every report outcome waiting for them (each only once). */
    public void deliver(Player player) {
        reports.takeDeliveries(player.getUniqueId()).thenAccept(outcomes -> {
            if (!player.isOnline()) {
                return;
            }
            for (Report r : outcomes) {
                messages().send(player, r.status() == Report.Status.RESOLVED ? "reports.reporter-resolved"
                        : "reports.reporter-rejected", Placeholder.unparsed("target", r.targetName()));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        deliver(event.getPlayer());
    }

    /** Every minute: expire claims, and remind staff every {@code reports.reminder-minutes}. */
    public void tick() {
        reports.expireClaims();
        int minutes = state.get().settings().reports().reminderMinutes();
        long now = System.currentTimeMillis();
        if (minutes <= 0 || now - lastReminder < minutes * 60_000L - 1000) {
            return;
        }
        lastReminder = now;
        reports.refreshOpenCount().thenAccept(count -> server.getScheduler().runTask(plugin, () -> {
                    if (count > 0) {
                        toStaff("reports.reminder", Placeholder.unparsed("count", String.valueOf(count)));
                    }
                }));
    }
}
