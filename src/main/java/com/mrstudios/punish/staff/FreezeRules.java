package com.mrstudios.punish.staff;

import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/** What a frozen player may still do (DESIGN.md 11.3). Pure. */
public final class FreezeRules {

    private FreezeRules() {
    }

    /**
     * A move is blocked when the position changes at all; looking around (yaw and pitch only) is
     * allowed.
     */
    public static boolean moveBlocked(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        return fromX != toX || fromY != toY || fromZ != toZ;
    }

    /**
     * Teleports are blocked except those made by commands and plugins (staff moving them to a
     * room, our own snap-back): ender pearls, chorus fruit, portals, spectating, beds and the rest
     * are blocked.
     */
    public static boolean teleportAllowed(TeleportCause cause) {
        return cause == TeleportCause.COMMAND || cause == TeleportCause.PLUGIN;
    }

    /** Whether a reminder is due this second. {@code every} 0 = only the first time. */
    public static boolean reminderDue(long secondsFrozen, int every) {
        return every > 0 && secondsFrozen > 0 && secondsFrozen % every == 0;
    }
}
