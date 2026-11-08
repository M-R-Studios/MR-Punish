package com.mrstudios.punish.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

/** Every click type on every kind of slot: only left, right and shift clicks on a menu button run it. */
class ClickRulesTest {

    private static final int TOP = 27;
    private static final Set<ClickType> RUNS =
            Set.of(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT);

    @Test
    void everyClickTypeOnAButton() {
        for (ClickType click : ClickType.values()) {
            assertEquals(RUNS.contains(click), ClickRules.runsButton(4, TOP, click, true), click.name());
        }
    }

    @Test
    void nothingRunsOffAButton() {
        for (ClickType click : ClickType.values()) {
            assertFalse(ClickRules.runsButton(5, TOP, click, false), "empty slot " + click);
            assertFalse(ClickRules.runsButton(TOP, TOP, click, true), "first slot of the player's inventory " + click);
            assertFalse(ClickRules.runsButton(TOP + 30, TOP, click, true), "player's hotbar " + click);
            assertFalse(ClickRules.runsButton(-999, TOP, click, true), "outside the window " + click);
            assertFalse(ClickRules.runsButton(-1, TOP, click, true), "border " + click);
        }
        assertFalse(ClickRules.runsButton(4, TOP, null, true));
    }

    @Test
    void theDupeTricksDoNothing() {
        for (ClickType trick : new ClickType[] {ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND, ClickType.DROP,
                ClickType.CONTROL_DROP, ClickType.DOUBLE_CLICK, ClickType.MIDDLE, ClickType.CREATIVE,
                ClickType.WINDOW_BORDER_LEFT, ClickType.WINDOW_BORDER_RIGHT, ClickType.UNKNOWN}) {
            assertFalse(ClickRules.runsButton(0, TOP, trick, true), trick.name());
        }
    }

    @Test
    void shiftVariants() {
        assertTrue(ClickRules.isShift(ClickType.SHIFT_LEFT));
        assertTrue(ClickRules.isShift(ClickType.SHIFT_RIGHT));
        assertFalse(ClickRules.isShift(ClickType.LEFT));
        assertFalse(ClickRules.isShift(ClickType.RIGHT));
    }
}
