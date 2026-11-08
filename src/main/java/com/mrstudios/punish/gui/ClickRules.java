package com.mrstudios.punish.gui;

import java.util.Set;
import org.bukkit.event.inventory.ClickType;

/**
 * Which clicks in a menu do anything (DESIGN.md 10.1). Pure. The listener cancels every click and
 * drag first; only a click this accepts then runs a button. Accepted: a left, right or shift click
 * on a button in the menu itself. Everything else (number keys and F, Q and Ctrl-Q, double-click,
 * middle and creative clicks, clicks in the player's own inventory, empty slots) does nothing.
 */
public final class ClickRules {

    private static final Set<ClickType> ACCEPTED =
            Set.of(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT);

    private ClickRules() {
    }

    /**
     * @param rawSlot   the clicked raw slot (top inventory first, then the player's)
     * @param topSize   the menu's size
     * @param hasButton whether that slot of the menu has a button
     */
    public static boolean runsButton(int rawSlot, int topSize, ClickType click, boolean hasButton) {
        return click != null && ACCEPTED.contains(click) && rawSlot >= 0 && rawSlot < topSize && hasButton;
    }

    /** Whether the click is a "shift" variant (used for shortcuts such as clearing a note). */
    public static boolean isShift(ClickType click) {
        return click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
    }
}
