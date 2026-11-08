package com.mrstudios.punish.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.Punishment.StoredStatus;
import com.mrstudios.punish.storage.PunishmentRepo;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** /ban, /unban and the login check through the real plugin on MockBukkit, with SQLite storage. */
class PluginBanTest {

    private ServerMock server;
    private MRPunish plugin;
    private BanCommand ban;
    private UnbanCommand unban;
    private ConsoleCommandSenderMock console;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(MRPunish.class);
        ban = new BanCommand(plugin);
        unban = new UnbanCommand(plugin);
        console = (ConsoleCommandSenderMock) server.getConsoleSender();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Runs server ticks until {@code done} or 5 s pass (database work runs on its own thread). */
    private void until(BooleanSupplier done) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < end) {
            server.getScheduler().performOneTick();
            Thread.sleep(10);
        }
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private List<String> consoleLines() {
        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = console.nextComponentMessage()) != null) {
            lines.add(plain(next));
        }
        return lines;
    }

    private String nextConsoleLine() throws InterruptedException {
        String[] line = new String[1];
        until(() -> {
            Component next = console.nextComponentMessage();
            if (next != null) {
                line[0] = plain(next);
            }
            return line[0] != null;
        });
        return line[0];
    }

    private List<Punishment> history(UUID uuid) throws Exception {
        return plugin.storage().executor()
                .submit("test", c -> new PunishmentRepo(plugin.storage().database()).ofTarget(c, uuid))
                .get(5, TimeUnit.SECONDS);
    }

    private AsyncPlayerPreLoginEvent login(String name, UUID uuid) throws Exception {
        AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(name, InetAddress.getLoopbackAddress(), uuid);
        plugin.bans().onPreLogin(event);
        return event;
    }

    /** A player who joined once and left, so they're known. */
    private UUID known(String name) throws InterruptedException {
        PlayerMock player = server.addPlayer(name);
        UUID uuid = player.getUniqueId();
        player.disconnect();
        until(() -> plugin.names().byName(name).isPresent());
        return uuid;
    }

    @Test
    void consoleBansAnOfflinePlayerAndTheyCantJoinUntilUnbanned() throws Exception {
        UUID steve = known("Steve");
        ban.run(console, "steve", "hacking killaura on two alts");
        String reply = nextConsoleLine();
        assertTrue(reply.startsWith("MR-Punish » Banned Steve for Hacking (7d, #"), reply);
        assertTrue(reply.endsWith(", offence 1)."), reply);

        Punishment p = history(steve).get(0);
        assertEquals("killaura on two alts", p.note());
        assertEquals("Console", p.issuerName());

        AsyncPlayerPreLoginEvent denied = login("Steve", steve);
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, denied.getLoginResult());
        String screen = plain(denied.kickMessage());
        assertTrue(screen.startsWith("You are banned from this server"), screen);
        assertTrue(screen.contains("Reason: Hacking") && screen.contains("Banned by: Console")
                && screen.contains("Ban ID: #" + p.id()) && screen.contains("(6d 23h left)")
                || screen.contains("(7d left)"), screen);

        consoleLines();
        unban.run(console, "Steve", "appeal accepted");
        assertEquals("MR-Punish » Removed 1 ban(s) from Steve (#" + p.id() + ").", nextConsoleLine());
        assertEquals(StoredStatus.REMOVED, history(steve).get(0).status());
        assertEquals("appeal accepted", history(steve).get(0).removedReason());
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, login("Steve", steve).getLoginResult());
    }

    @Test
    void anOnlineTargetIsKickedAndTheBanIsCached() throws Exception {
        PlayerMock steve = server.addPlayer("Steve");
        ban.run(console, "Steve", "-s griefing");
        String reply = nextConsoleLine();
        assertTrue(reply.contains("Banned Steve for Griefing (3d"), reply);
        until(() -> !steve.isOnline());
        assertFalse(steve.isOnline(), "kicked");
        assertTrue(plugin.bans().cache().get(steve.getUniqueId(), System.currentTimeMillis()).isPresent());
        assertTrue(history(steve.getUniqueId()).get(0).silent());
    }

    @Test
    void unknownInvalidAndUsage() throws Exception {
        ban.run(console, "NeverJoined", "hacking");
        assertEquals("MR-Punish » NeverJoined has never joined this server.", nextConsoleLine());
        ban.run(console, "bad;name", "hacking");
        assertEquals("MR-Punish » 'bad;name' isn't a valid player name.", nextConsoleLine());
        known("Alex");
        ban.run(console, "Alex", "-s");
        assertEquals("MR-Punish » Usage: " + BanCommand.USAGE, nextConsoleLine());
        unban.run(console, "Alex", "");
        assertEquals("MR-Punish » Alex has no active ban.", nextConsoleLine());
    }

    @Test
    void aPlayerWithoutRankCantBanAndOneWithoutPermissionIsRefused() throws Exception {
        known("Target");
        PlayerMock nobody = server.addPlayer("Nobody");
        ban.run(nobody, "Target", "hacking");
        assertEquals("MR-Punish » You don't have permission to do that.", plain(nobody.nextComponentMessage()));

        PlayerMock mod = server.addPlayer("Mod");
        mod.addAttachment(plugin, "mrpunish.mod", true);
        PlayerMock admin = server.addPlayer("Admin");
        admin.addAttachment(plugin, "mrpunish.admin", true);
        ban.run(mod, "Admin", "hacking");
        String[] reply = new String[1];
        until(() -> {
            Component c = mod.nextComponentMessage();
            if (c != null) {
                reply[0] = plain(c);
            }
            return reply[0] != null;
        });
        assertEquals("MR-Punish » You can't punish Admin: their rank is equal to or higher than yours.", reply[0]);
        assertTrue(admin.isOnline());
        assertTrue(history(admin.getUniqueId()).isEmpty());
    }

    @Test
    void failPolicyCacheWhileTheDatabaseIsDownThenTheRecheck() throws Exception {
        UUID steve = known("Steve");
        ban.run(console, "Steve", "hacking");
        nextConsoleLine();
        until(() -> plugin.bans().cache().get(steve, System.currentTimeMillis()).isPresent());

        plugin.storage().health().markDown("test outage");
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, login("Steve", steve).getLoginResult(),
                "the cache still knows the ban");
        UUID alex = UUID.randomUUID();
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, login("Alex", alex).getLoginResult());
        assertTrue(plugin.bans().unverified().contains(alex), "let in, to be checked later");

        // Alex is online, and was banned while the database was out of reach (e.g. on another server).
        PlayerMock alexOnline = new PlayerMock(server, "Alex", alex);
        server.addPlayer(alexOnline);
        plugin.storage().health().markUp();
        plugin.storage().executor().submit("test", c -> {
            try (var s = c.prepareStatement("INSERT INTO punishments (type, target_uuid, target_name, reason_key,"
                    + " reason_display, offence, step, issued_at, issuer_name, issuer_weight, target_weight,"
                    + " target_weight_source, server, origin, status) VALUES ('BAN', ?, 'Alex', 'hacking', 'Hacking',"
                    + " 1, 1, 0, 'Elsewhere', 0, 0, 'CACHED', 'other', 'COMMAND', 'ACTIVE')")) {
                s.setString(1, alex.toString());
                s.executeUpdate();
            }
            return null;
        }).get(5, TimeUnit.SECONDS);
        assertEquals(1, plugin.bans().recheckUnverified());
        until(() -> !alexOnline.isOnline());
        assertFalse(alexOnline.isOnline(), "kicked by the re-check");
        assertFalse(plugin.bans().unverified().contains(alex));
    }

    @Test
    void startupHintAndImportOfTheVanillaList() throws Exception {
        PlayerMock grief = server.addPlayer("Griefer");
        UUID uuid = grief.getUniqueId();
        grief.disconnect();
        com.mrstudios.punish.imports.VanillaBanLists.profiles(server)
                .addBan(server.createProfile(uuid, "Griefer"), "Griefed spawn", (java.util.Date) null, "Notch");

        plugin.importVanilla(console, true);
        List<String> lines = new ArrayList<>();
        until(() -> {
            Component c = console.nextComponentMessage();
            if (c != null) {
                lines.add(plain(c));
            }
            return lines.size() >= 3;
        });
        assertEquals("MR-Punish » Importing vanilla bans…", lines.get(0));
        assertEquals("MR-Punish » Imported 1 player ban(s). Skipped 0 already imported, 0 unreadable.", lines.get(1));
        assertEquals("MR-Punish » Removed 1 imported entries from the vanilla ban list.", lines.get(2));
        assertTrue(com.mrstudios.punish.imports.VanillaBanLists.profiles(server).getEntries().isEmpty());
        assertEquals("imported-vanilla", history(uuid).get(0).reasonKey());
        assertNotNull(plugin.bans().cache().get(uuid, System.currentTimeMillis()));

        plugin.importVanilla(console, false);
        lines.clear();
        until(() -> {
            Component c = console.nextComponentMessage();
            if (c != null) {
                lines.add(plain(c));
            }
            return lines.size() >= 2;
        });
        assertEquals("MR-Punish » Imported 0 player ban(s). Skipped 0 already imported, 0 unreadable.", lines.get(1),
                "the cleared entry isn't in the vanilla list any more");
        assertEquals(1, history(uuid).size());
    }
}
