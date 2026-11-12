package com.mrstudios.punish.staff;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.ScoreboardSettings;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * The staff mode sidebar (DESIGN.md 11.8): a scoreboard of the staff member's own, so other
 * plugins' boards and other players aren't touched; lines are team prefixes on fixed invisible
 * entries (no flicker) and the score numbers are hidden.
 */
final class StaffBoard {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String[] ENTRIES = new String[ScoreboardSettings.MAX_LINES];

    static {
        String codes = "0123456789abcdef";
        for (int i = 0; i < ENTRIES.length; i++) {
            ENTRIES[i] = "§" + codes.charAt(i) + "§r";
        }
    }

    private final MRPunish plugin;
    private final Player player;
    private final Scoreboard board;
    private final Objective objective;
    private int shownLines;

    StaffBoard(MRPunish plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.board = plugin.getServer().getScoreboardManager().getNewScoreboard();
        PluginState state = plugin.state();
        this.objective = board.registerNewObjective("mrpunish", Criteria.DUMMY,
                MINI.deserialize(state.scoreboard().title()));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        try {
            objective.numberFormat(NumberFormat.blank());
        } catch (RuntimeException | AbstractMethodError notSupported) {
            // Older servers or test doubles: numbers stay visible.
        }
        player.setScoreboard(board);
    }

    Scoreboard scoreboard() {
        return board;
    }

    void update() {
        if (!player.isOnline()) {
            return;
        }
        PluginState state = plugin.state();
        ScoreboardSettings settings = state.scoreboard();
        TagResolver placeholders = placeholders(settings);
        objective.displayName(MINI.deserialize(settings.title(), placeholders));
        List<String> lines = settings.lines();
        for (int i = 0; i < ENTRIES.length; i++) {
            Team team = board.getTeam("mrp" + i);
            if (i >= lines.size()) {
                if (team != null) {
                    team.unregister();
                    board.resetScores(ENTRIES[i]);
                }
                continue;
            }
            if (team == null) {
                team = board.registerNewTeam("mrp" + i);
                team.addEntry(ENTRIES[i]);
            }
            team.prefix(MINI.deserialize(lines.get(i), placeholders));
            objective.getScore(ENTRIES[i]).setScore(lines.size() - i);
        }
        shownLines = lines.size();
    }

    int shownLines() {
        return shownLines;
    }

    private TagResolver placeholders(ScoreboardSettings settings) {
        var server = plugin.getServer();
        int staff = 0;
        for (Player p : server.getOnlinePlayers()) {
            if (p.hasPermission("mrpunish.staff") && plugin.vanish().canSee(player, p)) {
                staff++;
            }
        }
        double tps = plugin.tps();
        var at = player.getLocation();
        boolean vanished = plugin.vanish().isVanished(player.getUniqueId());
        int online = 0;
        for (Player p : server.getOnlinePlayers()) {
            if (plugin.vanish().canSee(player, p)) {
                online++;
            }
        }
        return TagResolver.resolver(
                Placeholder.unparsed("online", String.valueOf(online)),
                Placeholder.unparsed("max", String.valueOf(server.getMaxPlayers())),
                Placeholder.unparsed("staff-online", String.valueOf(staff)),
                Placeholder.unparsed("vanished", String.valueOf(plugin.vanish().count())),
                Placeholder.unparsed("open-reports", String.valueOf(plugin.reports().openCount())),
                Placeholder.unparsed("frozen", String.valueOf(plugin.freeze().count())),
                Placeholder.unparsed("tps", String.format("%.1f", Math.min(20.0, tps))),
                Placeholder.unparsed("mspt", String.format("%.1f", plugin.mspt())),
                Placeholder.unparsed("ping", String.valueOf(player.getPing())),
                Placeholder.unparsed("world", at.getWorld() == null ? "?" : at.getWorld().getName()),
                Placeholder.unparsed("x", String.valueOf(at.getBlockX())),
                Placeholder.unparsed("y", String.valueOf(at.getBlockY())),
                Placeholder.unparsed("z", String.valueOf(at.getBlockZ())),
                Placeholder.component("vanish-state", MINI.deserialize(vanished ? settings.vanishOn()
                        : settings.vanishOff())),
                Placeholder.unparsed("server", plugin.state().settings().effectiveServerName()),
                Placeholder.unparsed("player", player.getName()));
    }
}
