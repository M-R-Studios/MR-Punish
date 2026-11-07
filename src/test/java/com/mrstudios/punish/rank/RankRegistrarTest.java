package com.mrstudios.punish.rank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.config.LoadResult;
import com.mrstudios.punish.config.RankLoader;
import com.mrstudios.punish.config.RankSettings;
import java.util.List;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class RankRegistrarTest {

    private ServerMock server;
    private Plugin plugin;
    private RankRegistrar registrar;
    private final RankLoader loader = new RankLoader(name -> true, RankLoader.bundledDefaults());

    /** The rank nodes as plugin.yml declares them. */
    private static final List<Permission> DECLARED = List.of(
            new Permission("mrpunish.helper", "Helper rank.", PermissionDefault.FALSE),
            new Permission("mrpunish.mod", "Moderator rank.", PermissionDefault.FALSE),
            new Permission("mrpunish.admin", "Administrator rank.", PermissionDefault.FALSE),
            new Permission("mrpunish.owner", "Owner rank.", PermissionDefault.OP));

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        for (Permission permission : DECLARED) {
            server.getPluginManager().addPermission(permission);
        }
        registrar = new RankRegistrar(server.getPluginManager(), DECLARED, server::getOnlinePlayers);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static String shippedText() {
        try (var in = RankRegistrarTest.class.getResourceAsStream("/permissions.yml")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private RankSettings shipped() {
        return loader.defaults();
    }

    private PlayerMock withNode(String node) {
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, node, true);
        return player;
    }

    @Test
    void rankNodeGrantsItsOwnAndInheritedPermissions() {
        registrar.apply(shipped());
        PlayerMock mod = withNode("mrpunish.mod");

        assertTrue(mod.hasPermission("mrpunish.ban"));
        assertTrue(mod.hasPermission("mrpunish.warn"));
        assertTrue(mod.hasPermission("mrpunish.staff"));
        assertFalse(mod.hasPermission("mrpunish.remove"));
        assertFalse(mod.hasPermission("mrpunish.import"));
    }

    @Test
    void opGetsTheOwnerRankByDefaultButNotExempt() {
        registrar.apply(shipped());
        PlayerMock op = server.addPlayer();
        op.setOp(true);
        op.recalculatePermissions();

        assertTrue(op.hasPermission("mrpunish.owner"));
        assertTrue(op.hasPermission("mrpunish.import"));
        assertTrue(op.hasPermission("mrpunish.remove"));
        assertTrue(op.hasPermission("mrpunish.admin"), "owner inherits the admin node (admin tools)");
        assertEquals(PermissionDefault.OP, server.getPluginManager().getPermission("mrpunish.owner").getDefault());

        PlayerMock nobody = server.addPlayer();
        assertFalse(nobody.hasPermission("mrpunish.owner"));
        assertFalse(nobody.hasPermission("mrpunish.warn"));
    }

    @Test
    void reapplyingChangesWhatOnlinePlayersHave() {
        registrar.apply(shipped());
        PlayerMock helper = withNode("mrpunish.helper");
        assertFalse(helper.hasPermission("mrpunish.ban"));

        String text = shippedText().replace("      - mrpunish.mute\n", "      - mrpunish.mute\n      - mrpunish.ban\n");
        LoadResult<RankSettings> changed = loader.load(text);
        assertTrue(changed.ok(), changed.error());
        registrar.apply(changed.value());

        assertTrue(helper.hasPermission("mrpunish.ban"), "online players are recalculated");
    }

    @Test
    void aRemovedRankGoesBackToItsPluginYmlForm() {
        registrar.apply(shipped());
        PlayerMock owner = withNode("mrpunish.owner");
        assertTrue(owner.hasPermission("mrpunish.import"));

        LoadResult<RankSettings> withoutOwner = loader.load("""
                permissions-version: 1
                ranks:
                  helper: { permission: mrpunish.helper, weight: 10, display: Helper, grants: [mrpunish.staff] }
                """);
        assertTrue(withoutOwner.ok(), withoutOwner.error());
        registrar.apply(withoutOwner.value());

        Permission node = server.getPluginManager().getPermission("mrpunish.owner");
        assertTrue(node.getChildren().isEmpty());
        assertEquals(PermissionDefault.OP, node.getDefault());
        assertFalse(owner.hasPermission("mrpunish.import"));
    }

    @Test
    void customRankNodeDefaultsToFalse() {
        LoadResult<RankSettings> custom = loader.load("""
                permissions-version: 1
                ranks:
                  trial: { permission: mrpunish.trial, weight: 5, display: Trial, grants: [mrpunish.warn] }
                """);
        registrar.apply(custom.value());
        Permission node = server.getPluginManager().getPermission("mrpunish.trial");
        assertEquals(PermissionDefault.FALSE, node.getDefault());
        assertTrue(withNode("mrpunish.trial").hasPermission("mrpunish.warn"));
    }
}
