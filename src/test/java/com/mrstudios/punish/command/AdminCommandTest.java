package com.mrstudios.punish.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.config.Messages;
import com.mrstudios.punish.config.MessagesLoader;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class AdminCommandTest {

    private ServerMock server;
    private Plugin plugin;
    private final List<String> reloadedBy = new ArrayList<>();
    private AdminCommand command;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        Messages messages = new MessagesLoader(MessagesLoader.bundledDefaults()).defaults();
        command = new AdminCommand(new AdminCommand.Admin() {
            @Override
            public Messages messages() {
                return messages;
            }

            @Override
            public void reload(CommandSender sender) {
                reloadedBy.add(sender.getName());
            }

            @Override
            public String pluginVersion() {
                return "0.1";
            }

            @Override
            public String serverVersion() {
                return "Paper 1.21.11";
            }

            @Override
            public String storageDescription() {
                return "SQLite (punishments.db)";
            }

            @Override
            public String storageStatus() {
                return "connected";
            }

            @Override
            public String clashSummary() {
                return "none";
            }
        });
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private PlayerMock player(String... permissions) {
        PlayerMock player = server.addPlayer();
        for (String permission : permissions) {
            player.addAttachment(plugin, permission, true);
        }
        return player;
    }

    private static List<String> messages(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        Component next;
        while ((next = player.nextComponentMessage()) != null) {
            lines.add(PlainTextComponentSerializer.plainText().serialize(next));
        }
        return lines;
    }

    @Test
    void helpListsOnlyWhatTheSenderMayUse() {
        PlayerMock reloader = player(Perms.STAFF, Perms.RELOAD);
        command.help(reloader);
        List<String> lines = messages(reloader);
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).endsWith("MR-Punish commands:"));
        assertTrue(lines.get(1).contains("/mrpunish reload"));

        PlayerMock admin = player(Perms.STAFF, Perms.RELOAD, Perms.ADMIN);
        command.help(admin);
        assertEquals(3, messages(admin).size());
    }

    @Test
    void staffWithoutAnyAdminCommandIsToldSo() {
        PlayerMock helper = player(Perms.STAFF);
        assertTrue(AdminCommand.mayUse(helper));
        command.help(helper);
        assertEquals(List.of("MR-Punish » There are no MR-Punish commands you can use."), messages(helper));
    }

    @Test
    void everyActionChecksPermissionInTheExecutor() {
        PlayerMock nobody = player();
        assertFalse(AdminCommand.mayUse(nobody));
        command.help(nobody);
        command.reload(nobody);
        command.version(nobody);
        List<String> lines = messages(nobody);
        assertEquals(3, lines.size());
        assertTrue(lines.stream().allMatch(l -> l.endsWith("You don't have permission to do that.")), lines.toString());
        assertTrue(reloadedBy.isEmpty());

        // Reload permission alone doesn't allow version, and the other way round.
        PlayerMock reloader = player(Perms.RELOAD);
        command.version(reloader);
        assertTrue(messages(reloader).get(0).endsWith("You don't have permission to do that."));
        command.reload(reloader);
        assertEquals(List.of(reloader.getName()), reloadedBy);
    }

    @Test
    void versionShowsPluginServerAndClashes() {
        PlayerMock admin = player(Perms.ADMIN);
        command.version(admin);
        assertEquals(List.of("MR-Punish » MR-Punish 0.1 on Paper 1.21.11. Storage: SQLite (punishments.db) (connected). Clashes: none"), messages(admin));
        assertNull(admin.nextComponentMessage());
    }

    @Test
    void consoleMayDoEverything() {
        command.reload(server.getConsoleSender());
        assertEquals(List.of("CONSOLE"), reloadedBy);
        assertTrue(AdminCommand.mayUse(server.getConsoleSender()));
    }
}
