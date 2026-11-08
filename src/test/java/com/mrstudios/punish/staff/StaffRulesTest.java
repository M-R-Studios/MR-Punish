package com.mrstudios.punish.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Who sees a vanished player (DESIGN.md 11.2), and which chat lines are staff chat (11.5). */
class StaffRulesTest {

    @Test
    void visibilityTable() {
        // self, vanished, viewer has vanish.see, viewer weight, target weight -> sees
        Object[][] rows = {
            {true, true, false, 0, 30, true},     // yourself, always
            {false, false, false, 0, 30, true},   // not vanished: everyone
            {false, true, false, 40, 30, false},  // no see permission: never, whatever the rank
            {false, true, true, 0, 30, false},    // non-staff with the permission but no rank
            {false, true, true, 20, 30, false},   // lower rank
            {false, true, true, 30, 30, true},    // equal rank
            {false, true, true, 40, 30, true},    // higher rank
            {false, true, true, 0, 0, true},      // both without a rank, viewer may see
        };
        for (Object[] r : rows) {
            assertEquals(r[5], VisibilityRule.sees((boolean) r[0], (boolean) r[1], (boolean) r[2], (int) r[3],
                    (int) r[4]), java.util.Arrays.toString(r));
        }
    }

    @Test
    void staffChatRouting() {
        assertEquals("hello", StaffChat.staffPart("#hello", false, true, "#"));
        assertEquals("hello", StaffChat.staffPart("# hello ", false, true, "#"));
        assertEquals("", StaffChat.staffPart("#", false, true, "#"));
        assertNull(StaffChat.staffPart("hello", false, true, "#"));
        // The prefix is only for staff: a player typing "#1" chats normally.
        assertNull(StaffChat.staffPart("#1 best server", false, false, "#"));
        // Toggled on: every line, also one that starts with the prefix.
        assertEquals("hello", StaffChat.staffPart("hello", true, true, "#"));
        assertEquals("hi", StaffChat.staffPart("#hi", true, true, "#"));
        // Toggled on but the permission is gone: still staff chat (refused later), never public.
        assertEquals("secret", StaffChat.staffPart("secret", true, false, "#"));
        // Prefix turned off.
        assertNull(StaffChat.staffPart("#hello", false, true, ""));
        assertEquals("!x", StaffChat.staffPart("sc!x", false, true, "sc"));
        assertTrue(StaffChat.staffPart("@x", false, true, "@") != null);
        assertFalse(StaffChat.staffPart("x@", false, true, "@") != null);
    }
}
