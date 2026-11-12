package com.mrstudios.punish.staff;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.command.Perms;
import com.mrstudios.punish.command.PunishCommands;
import com.mrstudios.punish.config.Messages;
import com.mrstudios.punish.punish.PunishmentType;
import com.mrstudios.punish.rank.Rank;
import com.mrstudios.punish.storage.ActionRepo;
import com.mrstudios.punish.storage.PlayerRepo;
import com.mrstudios.punish.storage.PunishmentRepo;
import com.mrstudios.punish.storage.ReportRepo;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /mrpunish staff <player>} (DESIGN.md 11.7): a staff member's rank, the punishments they
 * issued (30 days and ever, by type), the reports they handled and their last 10 actions, with a
 * clickable force exit when they're online in staff mode or vanished. Admins only.
 */
public final class StaffProfile {

    static final long THIRTY_DAYS = 30L * 24 * 60 * 60 * 1000;
    static final int LAST_ACTIONS = 10;

    /** What the profile shows, read in one go. */
    record Data(Optional<PlayerRepo.PlayerRecord> record, Map<PunishmentType, Integer> recent,
            Map<PunishmentType, Integer> total, int reports, List<ActionRepo.Logged> actions) {
    }

    private final MRPunish plugin;
    private final PunishCommands shared;

    public StaffProfile(MRPunish plugin) {
        this.plugin = plugin;
        this.shared = new PunishCommands(plugin);
    }

    private Messages messages() {
        return plugin.state().messages();
    }

    public void show(CommandSender sender, String name) {
        if (!sender.hasPermission(Perms.ADMIN)) {
            messages().send(sender, "general.no-permission");
            return;
        }
        shared.withTarget(sender, name, target -> {
            long now = System.currentTimeMillis();
            UUID uuid = target.uuid();
            plugin.storage().executor().submit("loading the staff profile of " + target.name(), c -> {
                PunishmentRepo punishments = new PunishmentRepo(plugin.storage().database());
                return new Data(new PlayerRepo(plugin.storage().database()).byUuid(c, uuid),
                        punishments.issuedBy(c, uuid, now - THIRTY_DAYS), punishments.issuedBy(c, uuid, 0),
                        new ReportRepo(plugin.storage().database()).handledBy(c, uuid),
                        new ActionRepo(plugin.storage().database()).page(c, List.of(), uuid, 0, LAST_ACTIONS));
            }).whenComplete((data, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (error != null) {
                    messages().send(sender, "general.storage-unavailable");
                    return;
                }
                if (!sender.hasPermission(Perms.ADMIN)) {
                    messages().send(sender, "general.no-permission");
                    return;
                }
                print(sender, uuid, target.name(), data);
            }));
        });
    }

    private static int sum(Map<PunishmentType, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private void print(CommandSender sender, UUID uuid, String name, Data data) {
        Player online = plugin.getServer().getPlayer(uuid);
        String rankKey = online != null ? plugin.rankCache().read(online).rankKey()
                : data.record().map(PlayerRepo.PlayerRecord::rankKey).orElse(null);
        String rank = Optional.ofNullable(rankKey).flatMap(k -> plugin.state().ranks().byName(k)).map(Rank::display)
                .orElse(plugin.state().gui().text("punish-menu.head.no-rank"));
        Map<PunishmentType, Integer> total = data.total();
        messages().sendLines(sender, "staff.profile",
                Placeholder.unparsed("player", name),
                Placeholder.parsed("rank", rank),
                Placeholder.unparsed("p30", String.valueOf(sum(data.recent()))),
                Placeholder.unparsed("ptotal", String.valueOf(sum(total))),
                Placeholder.unparsed("w", String.valueOf(total.getOrDefault(PunishmentType.WARN, 0))),
                Placeholder.unparsed("m", String.valueOf(total.getOrDefault(PunishmentType.MUTE, 0))),
                Placeholder.unparsed("b", String.valueOf(total.getOrDefault(PunishmentType.BAN, 0))),
                Placeholder.unparsed("ip-punishments", String.valueOf(total.getOrDefault(PunishmentType.IP_MUTE, 0)
                        + total.getOrDefault(PunishmentType.IP_BAN, 0))),
                Placeholder.unparsed("reports", String.valueOf(data.reports())));
        if (data.actions().isEmpty()) {
            messages().send(sender, "staff.profile-none");
        }
        for (ActionRepo.Logged row : data.actions()) {
            ActionRepo.Action a = row.action();
            messages().send(sender, "staff.profile-action",
                    Placeholder.unparsed("date", plugin.state().settings().display().date(a.at())),
                    Placeholder.unparsed("action", a.action()),
                    Placeholder.unparsed("target", StaffLog.scrub(a.targetName() == null ? "" : a.targetName())));
        }
        if (online != null && (plugin.staffMode().inStaffMode(uuid) || plugin.vanish().isVanished(uuid))) {
            messages().send(sender, "staff.profile-buttons", Placeholder.unparsed("player", name),
                    TagResolver.resolver("forceexit-click", Tag.styling(
                            ClickEvent.runCommand("/mrpunish forceexit " + name))));
        }
    }
}
