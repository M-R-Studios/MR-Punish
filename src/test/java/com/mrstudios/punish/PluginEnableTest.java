package com.mrstudios.punish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.config.ConfigFiles;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The whole plugin on MockBukkit: enable, ranks applied, reload from a player. */
class PluginEnableTest {

    private ServerMock server;
    private MRPunish plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(MRPunish.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
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
    void enableCreatesTheFiveFilesAndAppliesRanks() {
        assertTrue(plugin.isEnabled());
        for (String name : ConfigFiles.NAMES) {
            assertTrue(new File(plugin.getDataFolder(), name).isFile(), name);
        }
        PlayerMock mod = server.addPlayer();
        mod.addAttachment(plugin, "mrpunish.mod", true);
        assertTrue(mod.hasPermission("mrpunish.ban"));
        assertFalse(mod.hasPermission("mrpunish.reload"));
        assertEquals(4, plugin.state().ranks().ranks().size());
    }

    @Test
    void reloadReportsSuccessThenAFailureAndKeepsTheOldRanks() throws Exception {
        PlayerMock admin = server.addPlayer();
        admin.addAttachment(plugin, "mrpunish.admin", true);

        plugin.reload(admin);
        assertEquals(List.of("MR-Punish » Reloaded config.yml, messages.yml, gui.yml, permissions.yml and scoreboard.yml."),
                messages(admin));

        File permissions = new File(plugin.getDataFolder(), "permissions.yml");
        String text = Files.readString(permissions.toPath(), StandardCharsets.UTF_8);
        Files.writeString(permissions.toPath(), text.replace("weight: 20", "weight: 10")
                .replace("      - mrpunish.import", "      - mrpunish.import\n      - mrpunish.exempt"),
                StandardCharsets.UTF_8);
        var ranksBefore = plugin.state().ranks();
        plugin.reload(admin);

        List<String> lines = messages(admin);
        assertEquals(2, lines.size(), lines.toString());
        assertEquals("MR-Punish » Reload failed for permissions.yml; kept the previous settings for it:", lines.get(0));
        assertTrue(lines.get(1).contains("ranks.mod.weight: 10 is also the weight of 'helper'"), lines.get(1));
        assertEquals(ranksBefore, plugin.state().ranks());
        assertTrue(admin.hasPermission("mrpunish.remove"));
    }
}
