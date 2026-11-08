package com.mrstudios.punish.punish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.config.GuiSettings;
import com.mrstudios.punish.config.MessagesLoader;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.RankLoader;
import com.mrstudios.punish.config.ScoreboardSettings;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.config.SettingsLoader;
import com.mrstudios.punish.punish.Punishment.Origin;
import com.mrstudios.punish.punish.Punishment.StoredStatus;
import com.mrstudios.punish.punish.Punishment.WeightSource;
import com.mrstudios.punish.punish.Requests.Actor;
import com.mrstudios.punish.rank.Authority.Standing;
import com.mrstudios.punish.rank.RankCache;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Who is told about a punishment and what they see (DESIGN.md 5.4); the rank cache refresh. */
class NotifierTest {

    private ServerMock server;
    private Plugin plugin;
    private PluginState state;
    private Notifier notifier;
    private final List<String> console = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        Settings settings = new SettingsLoader(name -> true, List.of(), Set.of(), SettingsLoader.bundledDefaults())
                .defaults();
        state = new PluginState(settings, new MessagesLoader(MessagesLoader.bundledDefaults()).defaults(),
                new com.mrstudios.punish.config.GuiLoader().defaults(), new RankLoader(name -> true, RankLoader.bundledDefaults()).defaults(),
                ScoreboardSettings.DEFAULTS);
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                console.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        notifier = new Notifier(server::getOnlinePlayers, log);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerMock player(String name, String... permissions) {
        PlayerMock player = server.addPlayer(name);
        for (String permission : permissions) {
            player.addAttachment(plugin, permission, true);
        }
        return player;
    }

    private static List<String> seen(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return lines;
    }

    private static Punishment punishment(PunishmentType type, boolean silent, String note, Long durationMs,
            String ipHash) {
        return new Punishment(42, type, UUID.randomUUID(), "Steve", ipHash, "hacking", "<red>Hacking", note, 2, 2,
                durationMs, 0, null, UUID.randomUUID(), "Mod", 20, 0, WeightSource.CACHED, "default", silent,
                Origin.COMMAND, StoredStatus.ACTIVE, null, null, null, null, null, null);
    }

    private Requests.Issue request(Actor actor, PunishmentType type, String ipHash, int alts) {
        List<Standing> affected = new ArrayList<>();
        for (int i = 0; i < alts; i++) {
            affected.add(new Standing(UUID.randomUUID(), "Alt" + i, 0, false));
        }
        Reason reason = state.settings().reasons().byKey("hacking").orElseThrow();
        return new Requests.Issue(actor, new Requests.Target(UUID.randomUUID(), "Steve", null, ipHash, affected), reason,
                null, false, Origin.COMMAND, null);
    }

    @Test
    void staffAreToldExceptTheActorAndEveryoneSeesTheBanAnnouncement() {
        PlayerMock mod = player("Mod", Notifier.NOTIFY);
        PlayerMock otherStaff = player("Helper", Notifier.NOTIFY);
        PlayerMock regular = player("Regular");
        Actor actor = new Actor(new Standing(mod.getUniqueId(), "Mod", 20, false), p -> true);
        Punishment ban = punishment(PunishmentType.BAN, false, "used <bold>killaura</bold>", 7 * 24 * 3600_000L, null);

        notifier.issued(state, request(actor, PunishmentType.BAN, null, 0),
                new Requests.IssueResult.Committed(ban, List.of(), null));

        assertEquals(List.of("Steve was banned for Hacking."), seen(mod), "the actor only gets the announcement");
        List<String> staffLines = seen(otherStaff);
        assertEquals(2, staffLines.size(), staffLines.toString());
        assertEquals("[MR] Mod banned Steve for Hacking (7d, #42) Note: used <bold>killaura</bold>", staffLines.get(0),
                "names and notes are never parsed as tags");
        assertEquals(List.of("Steve was banned for Hacking."), seen(regular), "the note stays with staff");
        assertTrue(console.contains("[MR] Mod banned Steve for Hacking (7d, #42) Note: used <bold>killaura</bold>"));
    }

    @Test
    void silentAndUnannouncedTypesOnlyGoToStaff() {
        PlayerMock staff = player("Staff", Notifier.NOTIFY);
        PlayerMock regular = player("Regular");
        Actor consoleActor = Actor.console("Console");
        Punishment silentBan = punishment(PunishmentType.BAN, true, null, null, null);
        notifier.issued(state, request(consoleActor, PunishmentType.BAN, null, 0),
                new Requests.IssueResult.Committed(silentBan, List.of(), null));
        assertEquals(List.of("[MR] Mod banned Steve for Hacking (permanent, #42, silent)"), seen(staff));
        assertNull(regular.nextComponentMessage());

        Punishment mute = punishment(PunishmentType.MUTE, false, null, 3600_000L, null);
        notifier.issued(state, request(consoleActor, PunishmentType.MUTE, null, 0),
                new Requests.IssueResult.Committed(mute, List.of(), null));
        assertEquals(List.of("[MR] Mod muted Steve for Hacking (1h, #42)"), seen(staff));
        assertNull(regular.nextComponentMessage(), "mutes aren't announced by default");
    }

    @Test
    void ipPunishmentsShowACountAndNeverTheAddressOrHash() {
        PlayerMock staff = player("Staff", Notifier.NOTIFY);
        PlayerMock regular = player("Regular");
        String hash = "f".repeat(64);
        Punishment ipBan = punishment(PunishmentType.IP_BAN, false, null, null, hash);
        notifier.issued(state, request(Actor.console("Console"), PunishmentType.IP_BAN, hash, 2),
                new Requests.IssueResult.Committed(ipBan, List.of(), null));
        List<String> everything = new ArrayList<>(seen(staff));
        everything.addAll(seen(regular));
        everything.addAll(console);
        assertTrue(everything.contains("[MR] Mod IP-banned Steve for Hacking (permanent, #42) [IP, 3 account(s)]"),
                everything.toString());
        for (String line : everything) {
            assertFalse(line.contains(hash) || line.matches(".*\\d+\\.\\d+\\.\\d+\\.\\d+.*"), line);
        }
    }

    @Test
    void warningsShowTheOffenceAndRemovalsListTheIds() {
        PlayerMock staff = player("Staff", Notifier.NOTIFY);
        Punishment warn = punishment(PunishmentType.WARN, false, null, null, null);
        notifier.issued(state, request(Actor.console("Console"), PunishmentType.WARN, null, 0),
                new Requests.IssueResult.Committed(warn, List.of(), null));
        assertEquals(List.of("[MR] Mod warned Steve for Hacking (offence 2, #42)"), seen(staff));

        Punishment a = new Punishment(3, PunishmentType.MUTE, UUID.randomUUID(), "Steve", null, "spam", "Spam", null, 1, 1,
                600_000L, 0, 600_000L, null, "Console", 0, 0, WeightSource.CACHED, "default", false, Origin.COMMAND,
                StoredStatus.REMOVED, null, "Admin", 5L, "appeal <red>ok", null, null);
        Punishment b = new Punishment(4, a.type(), a.targetUuid(), "Steve", null, "spam", "Spam", null, 1, 1, 600_000L, 0,
                600_000L, null, "Console", 0, 0, WeightSource.CACHED, "default", false, Origin.COMMAND,
                StoredStatus.REMOVED, null, "Admin", 5L, "appeal <red>ok", null, null);
        notifier.removed(state, new Requests.Remove(Actor.console("Admin"), Requests.Target.offline(a.targetUuid(),
                "Steve"), PunishmentType.MUTE, null, null), new Requests.RemoveResult.Removed(List.of(a, b)));
        assertEquals(List.of("[MR] Admin removed mute #3, #4 from Steve: appeal <red>ok"), seen(staff));
    }

    @Test
    void rankCacheSeesChangesOnlyOnRefresh() {
        RankCache cache = new RankCache(state::ranks);
        PlayerMock player = player("Riser");
        assertFalse(cache.refresh(player), "first sight isn't a change");
        assertEquals(0, cache.get(player.getUniqueId()).orElseThrow().weight());
        assertFalse(cache.refresh(player));

        // Rank nodes only give a rank; MockBukkit doesn't know MR-Punish's registered children here.
        player.addAttachment(plugin, "mrpunish.mod", true);
        assertTrue(cache.refresh(player));
        assertEquals("mod", cache.get(player.getUniqueId()).orElseThrow().rankKey());
        assertEquals(20, cache.standing(player).weight());
        player.addAttachment(plugin, "mrpunish.exempt", true);
        assertTrue(cache.refresh(player));
        assertTrue(cache.get(player.getUniqueId()).orElseThrow().exempt());
        cache.forget(player.getUniqueId());
        assertTrue(cache.get(player.getUniqueId()).isEmpty());
    }
}
