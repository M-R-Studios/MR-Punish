package com.mrstudios.punish.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.storage.PunishmentRepo;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.sign.Side;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.meta.BookMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** /warn, /unwarn, /mute, /unmute and mute enforcement through the real plugin on MockBukkit. */
class PluginMuteTest {

    private ServerMock server;
    private MRPunish plugin;
    private ConsoleCommandSenderMock console;
    private PunishCommand warn;
    private PunishCommand mute;
    private RemoveCommand unwarn;
    private RemoveCommand unmute;

    /** The plugin with a commands.yml alias "whisperto: [msg $1-]" (MockBukkit doesn't have commands.yml). */
    public static class WithAliases extends MRPunish {
        @Override
        protected java.util.Map<String, String[]> commandAliases() {
            return java.util.Map.of("whisperto", new String[] {"msg $1-"});
        }

        // MockBukkit doesn't implement getTPS() or getAverageTickTime().
        @Override
        public double tps() {
            return 20.0;
        }

        @Override
        public double mspt() {
            return 3.5;
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(WithAliases.class);
        console = (ConsoleCommandSenderMock) server.getConsoleSender();
        warn = PunishCommand.warn(plugin);
        mute = PunishCommand.mute(plugin);
        unwarn = RemoveCommand.unwarn(plugin);
        unmute = RemoveCommand.unmute(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

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

    /** Collects messages from {@code player} until {@code count} arrived (or 5 s pass). */
    private List<String> messages(PlayerMock player, int count) throws InterruptedException {
        List<String> lines = new ArrayList<>();
        until(() -> {
            Component c;
            while ((c = player.nextComponentMessage()) != null) {
                lines.add(plain(c));
            }
            return lines.size() >= count;
        });
        return lines;
    }

    private List<String> console(int count) throws InterruptedException {
        List<String> lines = new ArrayList<>();
        until(() -> {
            Component c;
            while ((c = console.nextComponentMessage()) != null) {
                lines.add(plain(c));
            }
            return lines.size() >= count;
        });
        return lines;
    }

    private List<Punishment> history(UUID uuid) throws Exception {
        return plugin.storage().executor()
                .submit("test", c -> new PunishmentRepo(plugin.storage().database()).ofTarget(c, uuid))
                .get(5, TimeUnit.SECONDS);
    }

    private AsyncChatEvent chat(PlayerMock player, String text) {
        AsyncChatEvent event = new AsyncChatEvent(true, player, new HashSet<>(), (source, name, message, viewer) -> message,
                Component.text(text), Component.text(text), null);
        plugin.mutes().onChat(event);
        return event;
    }

    private PlayerCommandPreprocessEvent command(PlayerMock player, String line) {
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, line);
        plugin.mutes().onCommand(event);
        return event;
    }

    @Test
    void aMutedPlayerCantChatUseBlockedCommandsSignsOrBooks() throws Exception {
        PlayerMock steve = server.addPlayer("Steve");
        mute.run(console, "Steve", "toxicity being rude");
        assertTrue(console(1).get(0).startsWith("MR-Punish » Muted Steve for Toxicity (2h, #"));
        List<String> told = messages(steve, 4);
        assertEquals("You have been muted", told.get(0));
        assertEquals("Reason: Toxicity", told.get(1));
        assertEquals("Duration: 2h", told.get(2));
        assertEquals("Appeal: https://discord.gg/your-server", told.get(3));

        AsyncChatEvent first = chat(steve, "hello");
        assertTrue(first.isCancelled());
        String notice = plain(steve.nextComponentMessage());
        assertTrue(notice.matches("MR-Punish » You are muted for Toxicity\\. (2h|1h 59m) left\\. Appeal: .*"), notice);
        assertTrue(chat(steve, "again").isCancelled());
        assertNull(steve.nextComponentMessage(), "the reminder waits for mute.notify-cooldown-seconds");

        assertTrue(command(steve, "/msg Alex hi").isCancelled());
        assertTrue(command(steve, "/minecraft:tell Alex hi").isCancelled());
        assertTrue(command(steve, "/me waves").isCancelled());
        assertTrue(command(steve, "/whisperto Alex hi").isCancelled(), "a commands.yml alias of msg");
        assertFalse(command(steve, "/help").isCancelled());

        Block block = server.addSimpleWorld("world").getBlockAt(0, 64, 0);
        SignChangeEvent written = new SignChangeEvent(block, steve,
                List.of(Component.text("hi"), Component.empty(), Component.empty(), Component.empty()), Side.FRONT);
        plugin.mutes().onSign(written);
        assertTrue(written.isCancelled());
        SignChangeEvent blank = new SignChangeEvent(block, steve,
                List.of(Component.empty(), Component.text(" "), Component.empty(), Component.empty()), Side.FRONT);
        plugin.mutes().onSign(blank);
        assertFalse(blank.isCancelled(), "a blank sign isn't a message");

        BookMeta meta = (BookMeta) server.getItemFactory().getItemMeta(Material.WRITABLE_BOOK);
        PlayerEditBookEvent signing = new PlayerEditBookEvent(steve, 0, meta, meta, true);
        plugin.mutes().onBook(signing);
        assertTrue(signing.isCancelled());
        PlayerEditBookEvent writing = new PlayerEditBookEvent(steve, 0, meta, meta, false);
        plugin.mutes().onBook(writing);
        assertFalse(writing.isCancelled(), "writing is fine; only signing is blocked");

        unmute.run(console, "Steve", "served");
        assertTrue(console(1).get(0).startsWith("MR-Punish » Removed 1 mute(s) from Steve"));
        until(() -> plugin.mutes().muteOf(steve).isEmpty());
        assertEquals(List.of("MR-Punish » You are no longer muted."), messages(steve, 1));
        assertFalse(chat(steve, "free").isCancelled());
        assertFalse(command(steve, "/msg Alex hi").isCancelled());
    }

    @Test
    void anUnmutedPlayerIsNeverBlocked() {
        PlayerMock alex = server.addPlayer("Alex");
        assertFalse(chat(alex, "hi").isCancelled());
        assertFalse(command(alex, "/msg Steve hi").isCancelled());
    }

    @Test
    void theMuteIsLoadedAtLoginTogetherWithTheBanCheck() throws Exception {
        PlayerMock steve = server.addPlayer("Steve");
        UUID uuid = steve.getUniqueId();
        mute.run(console, "Steve", "spam");
        console(1);
        steve.disconnect();
        until(() -> plugin.mutes().cache().get(uuid, System.currentTimeMillis()).isEmpty());
        assertTrue(plugin.mutes().cache().get(uuid, System.currentTimeMillis()).isEmpty(), "forgotten on quit");

        AsyncPlayerPreLoginEvent login = new AsyncPlayerPreLoginEvent("Steve", java.net.InetAddress.getLoopbackAddress(),
                uuid);
        plugin.bans().onPreLogin(login);
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, login.getLoginResult());
        assertTrue(plugin.mutes().cache().get(uuid, System.currentTimeMillis()).isPresent(), "muted from the start");
    }

    @Test
    void anOnlineWarnShowsTheMessageTheOfflineOneWaitsForTheNextJoin() throws Exception {
        PlayerMock steve = server.addPlayer("Steve");
        warn.run(console, "Steve", "spam stop flooding");
        assertTrue(console(1).get(0).startsWith("MR-Punish » Warned Steve for Spam (#"));
        assertEquals(List.of("You have been warned", "Reason: Spam", "By: Console"), messages(steve, 3));

        UUID uuid = steve.getUniqueId();
        steve.disconnect();
        warn.run(console, "Steve", "toxicity");
        console(1);
        assertEquals(1, plugin.storage().executor().submit("test", c -> new com.mrstudios.punish.storage.ActionRepo(
                plugin.storage().database()).pending(c, uuid, "WARN").size()).get(5, TimeUnit.SECONDS));

        PlayerMock back = new PlayerMock(server, "Steve", uuid);
        server.addPlayer(back);
        List<String> shown = messages(back, 1);
        assertTrue(shown.get(0).startsWith("MR-Punish » You were warned while offline: Toxicity ("), shown.toString());
        assertEquals(0, plugin.storage().executor().submit("test", c -> new com.mrstudios.punish.storage.ActionRepo(
                plugin.storage().database()).pending(c, uuid, "WARN").size()).get(5, TimeUnit.SECONDS),
                "shown once");
    }

    @Test
    void unwarnByIdMostRecentAndInvalid() throws Exception {
        PlayerMock steve = server.addPlayer("Steve");
        warn.run(console, "Steve", "spam");
        console(1);
        warn.run(console, "Steve", "toxicity");
        console(1);
        List<Punishment> warnings = history(steve.getUniqueId());
        long newest = warnings.get(0).id();
        long older = warnings.get(1).id();

        unwarn.run(console, "Steve", "");
        assertEquals("MR-Punish » Removed 1 warning(s) from Steve (#" + newest + ").", console(1).get(0));
        unwarn.run(console, "Steve", "#" + newest + " again");
        assertEquals("MR-Punish » No active warning #" + newest + " for Steve.", console(1).get(0));
        unwarn.run(console, "Steve", "99999");
        assertEquals("MR-Punish » No active warning #99999 for Steve.", console(1).get(0));
        unwarn.run(console, "Steve", older + " given by mistake");
        assertEquals("MR-Punish » Removed 1 warning(s) from Steve (#" + older + ").", console(1).get(0));
        assertEquals("given by mistake", history(steve.getUniqueId()).get(1).removedReason());
        unwarn.run(console, "Steve", "");
        assertEquals("MR-Punish » Steve has no active warning.", console(1).get(0));
    }
}
