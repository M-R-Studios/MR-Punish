package com.mrstudios.punish.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.junit.jupiter.api.Test;

/** What a frozen player may still do (DESIGN.md 11.3). */
class FreezeRulesTest {

    @Test
    void lookingAroundIsAllowedMovingIsNot() {
        assertFalse(FreezeRules.moveBlocked(1, 64, 1, 1, 64, 1), "only yaw/pitch changed");
        assertTrue(FreezeRules.moveBlocked(1, 64, 1, 1.01, 64, 1));
        assertTrue(FreezeRules.moveBlocked(1, 64, 1, 1, 64.5, 1), "jumping");
        assertTrue(FreezeRules.moveBlocked(1, 64, 1, 1, 63.9, 1), "falling");
        assertTrue(FreezeRules.moveBlocked(1, 64, 1, 1, 64, 0.99));
    }

    @Test
    void onlyCommandAndPluginTeleportsGetThrough() {
        Set<TeleportCause> allowed = Set.of(TeleportCause.COMMAND, TeleportCause.PLUGIN);
        for (TeleportCause cause : TeleportCause.values()) {
            assertEquals(allowed.contains(cause), FreezeRules.teleportAllowed(cause), cause.name());
        }
        assertFalse(FreezeRules.teleportAllowed(TeleportCause.ENDER_PEARL));
        assertFalse(FreezeRules.teleportAllowed(TeleportCause.CONSUMABLE_EFFECT), "chorus fruit");
        assertFalse(FreezeRules.teleportAllowed(TeleportCause.NETHER_PORTAL));
        assertFalse(FreezeRules.teleportAllowed(TeleportCause.SPECTATE));
    }

    @Test
    void reminders() {
        assertFalse(FreezeRules.reminderDue(0, 10), "the first message is sent when frozen");
        assertFalse(FreezeRules.reminderDue(9, 10));
        assertTrue(FreezeRules.reminderDue(10, 10));
        assertTrue(FreezeRules.reminderDue(20, 10));
        assertFalse(FreezeRules.reminderDue(10, 0), "0 = only once");
    }
}
