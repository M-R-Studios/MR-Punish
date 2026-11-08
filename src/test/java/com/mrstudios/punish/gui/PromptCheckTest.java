package com.mrstudios.punish.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mrstudios.punish.gui.PromptService.Check;
import org.junit.jupiter.api.Test;

/** What typed prompt text means, and the ordinals in menu lore. */
class PromptCheckTest {

    @Test
    void typedText() {
        assertEquals(Check.VALUE, PromptService.check("spam in chat", 20, "cancel"));
        assertEquals(Check.CANCEL, PromptService.check("cancel", 20, "cancel"));
        assertEquals(Check.CANCEL, PromptService.check("  CANCEL ", 20, "cancel"));
        assertEquals(Check.VALUE, PromptService.check("cancel this", 20, "cancel"));
        assertEquals(Check.EMPTY, PromptService.check("   ", 20, "cancel"));
        assertEquals(Check.EMPTY, PromptService.check("\u0007\u0000", 20, "cancel"));
        assertEquals(Check.EMPTY, PromptService.check(null, 20, "cancel"));
        assertEquals(Check.VALUE, PromptService.check("x".repeat(20), 20, "cancel"));
        assertEquals(Check.TOO_LONG, PromptService.check("x".repeat(21), 20, "cancel"));
        // Control characters don't count towards the length.
        assertEquals(Check.VALUE, PromptService.check("x".repeat(20) + "\u0007", 20, "cancel"));
    }

    @Test
    void ordinals() {
        String[] expected = {"1st", "2nd", "3rd", "4th", "10th", "11th", "12th", "13th", "21st", "22nd", "23rd", "101st",
            "111th", "112th"};
        int[] n = {1, 2, 3, 4, 10, 11, 12, 13, 21, 22, 23, 101, 111, 112};
        for (int i = 0; i < n.length; i++) {
            assertEquals(expected[i], PunishMenus.ordinal(n[i]));
        }
    }
}
