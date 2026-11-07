package com.mrstudios.punish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.storage.Migrations;
import com.mrstudios.punish.storage.PlayerRepo;
import com.mrstudios.punish.storage.SqliteDatabase;
import com.mrstudios.punish.punish.TargetResolver;
import java.io.File;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The plugin with its real SQLite storage on MockBukkit: joins are recorded, shutdown drains. */
class PluginStorageTest {

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

    private PlayerRepo.PlayerRecord stored(PlayerMock player) throws Exception {
        return plugin.storage().executor()
                .submit("test", c -> new PlayerRepo(plugin.storage().database()).byUuid(c, player.getUniqueId()))
                .get(5, TimeUnit.SECONDS).orElseThrow();
    }

    @Test
    void enableCreatesTheDatabaseAndAJoinIsRecordedWithTheRank() throws Exception {
        assertTrue(new File(plugin.getDataFolder(), "punishments.db").isFile());
        assertTrue(plugin.storage().health().isUp());

        PlayerMock mod = server.addPlayer("ModBot");
        mod.addAttachment(plugin, "mrpunish.mod", true);
        plugin.storage().executor().submit("flush", c -> null).get(5, TimeUnit.SECONDS);
        PlayerRepo.PlayerRecord joined = stored(mod);
        assertEquals("ModBot", joined.name());
        // The attachment came after the join event: the rank is cached on the next visit (here: quit,
        // fired directly because MockBukkit's disconnect drops attachments before the event).
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerQuitEvent(mod,
                net.kyori.adventure.text.Component.empty(), org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));
        plugin.storage().executor().submit("flush", c -> null).get(5, TimeUnit.SECONDS);
        PlayerRepo.PlayerRecord quit = stored(mod);
        assertEquals("mod", quit.rankKey());
        assertEquals(20, quit.rankWeight());
        assertEquals(joined.firstSeen(), quit.firstSeen());

        var resolved = plugin.targets().resolve("modbot").get(5, TimeUnit.SECONDS);
        assertEquals(TargetResolver.Outcome.FOUND, resolved.outcome());
        assertEquals(mod.getUniqueId(), resolved.target().uuid());
    }

    @Test
    void disableRecordsOnlinePlayersAndClosesTheDatabase() throws Exception {
        PlayerMock owner = server.addPlayer("OwnerBot");
        owner.setOp(true);
        File db = new File(plugin.getDataFolder(), "punishments.db");

        server.getPluginManager().disablePlugin(plugin);
        assertTrue(plugin.storage().executor().isShutDown());

        try (SqliteDatabase reopened = new SqliteDatabase(db)) {
            assertEquals(1, Migrations.shipped(() -> 0L).migrate(reopened, line -> { }), "already at version 1");
            PlayerRepo.PlayerRecord record = reopened.with(c -> new PlayerRepo(reopened).byUuid(c, owner.getUniqueId()))
                    .orElseThrow();
            assertEquals("owner", record.rankKey(), "recorded at disable, while still online");
            assertEquals(40, record.rankWeight());
        }
    }
}
