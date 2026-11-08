package com.mrstudios.punish.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.MRPunish;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
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

/**
 * IP punishments through the real plugin with {@code ip.enabled: true}, and the rule that no raw IP
 * ever appears anywhere: every log line, message and database text value is scanned for it.
 */
class PluginIpTest {

    private static final String HOME = "10.0.0.7";
    private static final String ELSEWHERE = "10.0.0.9";

    /** The plugin started with IP features on (written into config.yml before it loads). */
    public static class WithIp extends MRPunish {
        @Override
        public void onLoad() {
            try (InputStream in = MRPunish.class.getResourceAsStream("/config.yml")) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("  enabled: false\n  # Accounts seen on the same IP", "  enabled: true\n"
                                + "  # Accounts seen on the same IP");
                Files.createDirectories(getDataFolder().toPath());
                Files.writeString(getDataFolder().toPath().resolve("config.yml"), text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private ServerMock server;
    private MRPunish plugin;
    private ConsoleCommandSenderMock console;
    /** Everything MR-Punish logged or sent, for the leak scan. */
    private final List<String> output = new CopyOnWriteArrayList<>();
    private final List<PlayerMock> players = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(WithIp.class);
        plugin.getLogger().addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                output.add(String.valueOf(record.getMessage()));
                if (record.getThrown() != null) {
                    output.add(String.valueOf(record.getThrown()));
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        console = (ConsoleCommandSenderMock) server.getConsoleSender();
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

    /** A player joining from {@code address}. */
    private PlayerMock join(String name, String address) throws Exception {
        PlayerMock player = new PlayerMock(server, name, UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
        player.setAddress(new InetSocketAddress(InetAddress.getByName(address), 50000));
        server.addPlayer(player);
        players.add(player);
        until(() -> plugin.ip().onlineHash(player.getUniqueId()).isPresent());
        // The IP link write is async; wait for it so offline lookups find it.
        Thread.sleep(50);
        return player;
    }

    private String nextConsole() throws InterruptedException {
        String[] line = new String[1];
        until(() -> {
            Component c = console.nextComponentMessage();
            if (c != null) {
                line[0] = plain(c);
            }
            return line[0] != null;
        });
        output.add(String.valueOf(line[0]));
        return line[0];
    }

    private void drainPlayers() {
        for (PlayerMock player : players) {
            Component c;
            while ((c = player.nextComponentMessage()) != null) {
                output.add(plain(c));
            }
        }
    }

    private AsyncPlayerPreLoginEvent login(String name, String address) throws Exception {
        AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(name, InetAddress.getByName(address),
                UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
        plugin.bans().onPreLogin(event);
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            output.add(plain(event.kickMessage()));
        }
        return event;
    }

    /** Every text value in every table, as stored. */
    private List<String> databaseText() throws Exception {
        return plugin.storage().executor().submit("dump", c -> {
            List<String> values = new ArrayList<>();
            List<String> tables = new ArrayList<>();
            try (Statement s = c.createStatement();
                    ResultSet r = s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
                while (r.next()) {
                    tables.add(r.getString(1));
                }
            }
            for (String table : tables) {
                try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM \"" + table + "\"")) {
                    ResultSetMetaData meta = r.getMetaData();
                    while (r.next()) {
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            values.add(table + "." + meta.getColumnName(i) + "=" + r.getString(i));
                        }
                    }
                }
            }
            return values;
        }).get(5, TimeUnit.SECONDS);
    }

    private void assertNoAddressAnywhere() throws Exception {
        drainPlayers();
        List<String> everything = new ArrayList<>(output);
        everything.addAll(databaseText());
        for (Path file : Files.list(plugin.getDataFolder().toPath()).toList()) {
            if (file.toString().endsWith(".yml") || file.toString().endsWith(".log")) {
                everything.add(Files.readString(file));
            }
        }
        assertFalse(everything.isEmpty());
        for (String line : everything) {
            assertFalse(line.contains(HOME) || line.contains(ELSEWHERE) || line.contains("10.0.0."),
                    "an address leaked: " + line);
        }
        String hash = plugin.ip().hash(InetAddress.getByName(HOME)).orElseThrow();
        for (String line : output) {
            assertFalse(line.contains(hash), "a hash leaked into a message or log line: " + line);
        }
    }

    @Test
    void banipKicksEveryAccountOnTheIpAndBlocksNewOnesButNotOthers() throws Exception {
        assertTrue(plugin.ip().enabled());
        PlayerMock steve = join("Steve", HOME);
        PlayerMock alt = join("SteveAlt", HOME);
        PlayerMock other = join("Other", ELSEWHERE);
        PunishCommand.banip(plugin).run(console, "Steve", "ban-evasion joined on an alt");
        String reply = nextConsole();
        assertTrue(reply.startsWith("MR-Punish » IP-banned Steve for Ban evasion (1mo, #"), reply);
        assertTrue(reply.endsWith("2 associated account(s))."), reply);
        until(() -> !steve.isOnline() && !alt.isOnline());
        assertFalse(steve.isOnline());
        assertFalse(alt.isOnline(), "every account on the IP is kicked");
        assertTrue(other.isOnline());

        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, login("BrandNew", HOME).getLoginResult(),
                "a never-seen account from the IP is refused");
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, login("Steve", ELSEWHERE).getLoginResult(),
                "Steve from another IP isn't (it's an IP ban, not an account ban)");

        RemoveCommand.unbanip(plugin).run(console, "Steve", "appeal");
        assertTrue(nextConsole().startsWith("MR-Punish » Removed 1 IP ban(s) from Steve (#"));
        assertEquals(AsyncPlayerPreLoginEvent.Result.ALLOWED, login("BrandNew2", HOME).getLoginResult());
        assertNoAddressAnywhere();
    }

    @Test
    void muteipMutesEveryAccountOnTheIp() throws Exception {
        PlayerMock steve = join("Steve", HOME);
        PlayerMock alt = join("SteveAlt", HOME);
        PlayerMock other = join("Other", ELSEWHERE);
        PunishCommand.muteip(plugin).run(console, "Steve", "spam-ip");
        assertTrue(nextConsole().startsWith("MR-Punish » IP-muted Steve for Spam (alts) (1d, #"));
        for (PlayerMock muted : List.of(steve, alt)) {
            AsyncChatEvent chat = new AsyncChatEvent(true, muted, new HashSet<>(), (s, n, m, v) -> m,
                    Component.text("hi"), Component.text("hi"), null);
            plugin.mutes().onChat(chat);
            assertTrue(chat.isCancelled(), muted.getName() + " is muted through the IP");
        }
        AsyncChatEvent free = new AsyncChatEvent(true, other, new HashSet<>(), (s, n, m, v) -> m,
                Component.text("hi"), Component.text("hi"), null);
        plugin.mutes().onChat(free);
        assertFalse(free.isCancelled());

        RemoveCommand.unmuteip(plugin).run(console, "SteveAlt", "");
        assertTrue(nextConsole().startsWith("MR-Punish » Removed 1 IP mute(s) from SteveAlt"),
                "any account seen on the IP can be named");
        until(() -> plugin.mutes().muteOf(steve).isEmpty());
        assertTrue(plugin.mutes().muteOf(alt).isEmpty());
        assertNoAddressAnywhere();
    }

    @Test
    void aStaffMemberOnTheIpProtectsIt() throws Exception {
        PlayerMock steve = join("Steve", HOME);
        PlayerMock owner = join("OwnerOnSameIp", HOME);
        owner.addAttachment(plugin, "mrpunish.owner", true);
        PlayerMock admin = join("Admin", ELSEWHERE);
        admin.addAttachment(plugin, "mrpunish.admin", true);

        PunishCommand.banip(plugin).run(admin, "Steve", "ban-evasion");
        String[] reply = new String[1];
        until(() -> {
            Component c = admin.nextComponentMessage();
            if (c != null) {
                reply[0] = plain(c);
            }
            return reply[0] != null;
        });
        output.add(reply[0]);
        assertEquals("MR-Punish » You can't IP-punish Steve: 1 account(s) on that IP have a rank equal to or higher"
                + " than yours, or are exempt.", reply[0]);
        assertTrue(steve.isOnline() && owner.isOnline(), "nobody was kicked");
        assertNoAddressAnywhere();
    }

    @Test
    void noIpKnownYet() throws Exception {
        PlayerMock steve = join("Steve", HOME);
        UUID uuid = steve.getUniqueId();
        // A player only known from before IP features were on: no link stored.
        plugin.storage().executor().submit("test", c -> {
            try (Statement s = c.createStatement()) {
                s.execute("DELETE FROM player_ips");
            }
            return null;
        }).get(5, TimeUnit.SECONDS);
        steve.disconnect();
        until(() -> plugin.ip().onlineHash(uuid).isEmpty());
        PunishCommand.banip(plugin).run(console, "Steve", "ban-evasion");
        assertEquals("MR-Punish » No IP is known for Steve yet. They need to join once while IP features are on.",
                nextConsole());
        assertNoAddressAnywhere();
    }

    @Test
    void versionSaysIpFeaturesAreOnAndTheKeyFileExists() throws Exception {
        assertTrue(Files.exists(plugin.getDataFolder().toPath().resolve("ip-secret.key")));
        assertEquals("on", plugin.ipStatus());
        assertNoAddressAnywhere();
    }
}
