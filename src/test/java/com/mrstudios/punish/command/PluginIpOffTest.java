package com.mrstudios.punish.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.MRPunish;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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

/** M15 in a test: with ip.enabled false (the default), nothing about IPs is stored or used. */
class PluginIpOffTest {

    private ServerMock server;
    private MRPunish plugin;
    private ConsoleCommandSenderMock console;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(MRPunish.class);
        console = (ConsoleCommandSenderMock) server.getConsoleSender();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private String nextConsole() throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            server.getScheduler().performOneTick();
            Component c = console.nextComponentMessage();
            if (c != null) {
                return PlainTextComponentSerializer.plainText().serialize(c);
            }
            Thread.sleep(10);
        }
        return null;
    }

    @Test
    void ipCommandsSayTheyreOffAndNothingIsStored() throws Exception {
        assertFalse(plugin.ip().enabled());
        PlayerMock steve = new PlayerMock(server, "Steve", UUID.randomUUID());
        steve.setAddress(new InetSocketAddress(InetAddress.getByName("10.0.0.7"), 50000));
        server.addPlayer(steve);
        AsyncPlayerPreLoginEvent login = new AsyncPlayerPreLoginEvent("Alex", InetAddress.getByName("10.0.0.7"),
                UUID.randomUUID());
        plugin.bans().onPreLogin(login);
        Thread.sleep(100);

        for (Runnable command : new Runnable[] {
                () -> PunishCommand.banip(plugin).run(console, "Steve", "ban-evasion"),
                () -> PunishCommand.muteip(plugin).run(console, "Steve", "spam-ip"),
                () -> RemoveCommand.unbanip(plugin).run(console, "Steve", ""),
                () -> RemoveCommand.unmuteip(plugin).run(console, "Steve", "")}) {
            command.run();
            assertEquals("MR-Punish » IP punishments are turned off (ip.enabled in config.yml).", nextConsole());
        }
        int links = plugin.storage().executor().submit("count", c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM player_ips")) {
                r.next();
                return r.getInt(1);
            }
        }).get(5, TimeUnit.SECONDS);
        assertEquals(0, links, "no IP links while IP features are off");
        assertTrue(plugin.ip().onlineHash(steve.getUniqueId()).isEmpty());
        assertFalse(Files.exists(plugin.getDataFolder().toPath().resolve("ip-secret.key")), "no key is created");
        assertTrue(plugin.ipStatus().startsWith("off"));
    }
}
