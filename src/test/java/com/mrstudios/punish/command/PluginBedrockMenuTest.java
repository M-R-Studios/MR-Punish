package com.mrstudios.punish.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.gui.MenuHolder;
import com.mrstudios.punish.gui.MenuOpener;
import com.mrstudios.punish.gui.PunishMenus;
import java.util.UUID;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Bedrock players (through Geyser) and the window-switch race: every menu opens after the old
 * window is closed and a short wait, a stray late close brings the same menu back, a double tap
 * opens one menu, and Java players are opened at once as before.
 */
class PluginBedrockMenuTest {

    private ServerMock server;
    private MRPunish plugin;
    private PlayerMock java;
    private PlayerMock bedrock;
    private PlayerMock steve;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PluginMuteTest.WithAliases.class);
        java = server.addPlayer("JavaMod");
        java.addAttachment(plugin, "mrpunish.mod", true);
        bedrock = server.addPlayer(".BedrockMod");
        bedrock.addAttachment(plugin, "mrpunish.mod", true);
        steve = server.addPlayer("Steve");
        UUID b = bedrock.getUniqueId();
        plugin.menuOpener().bedrockCheck(uuid -> uuid.equals(b));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            server.getScheduler().performOneTick();
        }
    }

    private static MenuHolder menu(PlayerMock p) {
        return MenuHolder.of(p.getOpenInventory().getTopInventory());
    }

    private static String name(PlayerMock p) {
        MenuHolder m = menu(p);
        return m == null ? "none" : m.getClass().getName().replaceAll(".*\\.", "");
    }

    @Test
    void javaPlayersAreOpenedAtOnce() {
        plugin.staffMenus().openHub(java);
        assertEquals("StaffMenus$Hub", name(java), "same tick, as before");
    }

    @Test
    void bedrockPlayersGetTheMenuAfterTheSwitchDelay() {
        plugin.staffMenus().openHub(bedrock);
        assertEquals("none", name(bedrock), "not in the same tick");
        ticks(1);
        assertEquals("none", name(bedrock));
        ticks(MenuOpener.BEDROCK_SWITCH_TICKS - 1);
        assertEquals("none", name(bedrock));
        ticks(1);
        assertEquals("StaffMenus$Hub", name(bedrock));
    }

    @Test
    void aMenuToMenuSwitchClosesTheOldOneFirst() {
        plugin.staffMenus().openHub(bedrock);
        ticks(1 + MenuOpener.BEDROCK_SWITCH_TICKS);
        MenuHolder hub = menu(bedrock);
        plugin.staffMenus().openOnlineStaff(bedrock, 0);
        assertSame(hub, menu(bedrock), "nothing changes inside the click");
        ticks(1);
        assertEquals("none", name(bedrock), "the old window is closed first");
        ticks(MenuOpener.BEDROCK_SWITCH_TICKS);
        assertEquals("StaffMenus$OnlineStaff", name(bedrock));
    }

    @Test
    void aDoubleTapOpensOnlyTheNewestMenu() {
        plugin.staffMenus().openHub(bedrock);
        plugin.staffMenus().openOnlineStaff(bedrock, 0);
        ticks(2 + MenuOpener.BEDROCK_SWITCH_TICKS * 2);
        assertEquals("StaffMenus$OnlineStaff", name(bedrock));
        // Only one window was ever opened: the hub's request was dropped.
        ticks(10);
        assertEquals("StaffMenus$OnlineStaff", name(bedrock));
    }

    @Test
    void aStrayCloseBringsTheSameConfirmationBackWithItsState() {
        plugin.menus().openConfirm(bedrock, new PunishMenus.Flow(steve.getUniqueId(), "Steve", "my note", true, false),
                plugin.state().settings().reasons().byKey("toxicity").orElseThrow());
        // The preview is read from the database first.
        long end = System.currentTimeMillis() + 5000;
        while (!"PunishMenus$ConfirmMenu".equals(name(bedrock)) && System.currentTimeMillis() < end) {
            ticks(1);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        MenuHolder confirm = menu(bedrock);
        assertEquals("PunishMenus$ConfirmMenu", name(bedrock));

        // The client's late close of the screen before, right after the open.
        ticks(1);
        bedrock.closeInventory(InventoryCloseEvent.Reason.PLAYER);
        assertNull(menu(bedrock));
        ticks(1 + MenuOpener.BEDROCK_SWITCH_TICKS);
        assertSame(confirm, menu(bedrock), "the same confirmation (note, silent) comes back");

        // A real close later on is a close.
        ticks(20);
        bedrock.closeInventory(InventoryCloseEvent.Reason.PLAYER);
        ticks(10);
        assertNull(menu(bedrock));
    }

    @Test
    void aClientThatKeepsClosingStillGetsOut() {
        plugin.staffMenus().openHub(bedrock);
        ticks(1 + MenuOpener.BEDROCK_SWITCH_TICKS);
        for (int i = 0; i < 2; i++) {
            bedrock.closeInventory(InventoryCloseEvent.Reason.PLAYER);
            ticks(1 + MenuOpener.BEDROCK_SWITCH_TICKS);
            assertEquals("StaffMenus$Hub", name(bedrock), "stray close " + (i + 1) + " absorbed");
        }
        bedrock.closeInventory(InventoryCloseEvent.Reason.PLAYER);
        ticks(10);
        assertNull(menu(bedrock), "the third in a row is real");
    }

    @Test
    void javaClosesAreNeverTreatedAsStray() {
        plugin.staffMenus().openHub(java);
        MenuHolder hub = menu(java);
        java.closeInventory(InventoryCloseEvent.Reason.PLAYER);
        ticks(10);
        assertNull(menu(java));
        plugin.staffMenus().openHub(java);
        assertNotSame(hub, menu(java));
    }
}
