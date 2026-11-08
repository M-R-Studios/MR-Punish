package com.mrstudios.punish.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CommandArgsTest {

    @Test
    void silentFlagAnywhereAfterThePlayer() {
        assertEquals(new CommandArgs.Parsed(true, "hacking killaura"), CommandArgs.parse("-s hacking killaura"));
        assertEquals(new CommandArgs.Parsed(true, "hacking killaura"), CommandArgs.parse("hacking -S killaura"));
        assertEquals(new CommandArgs.Parsed(true, "hacking"), CommandArgs.parse("hacking -s"));
        assertEquals(new CommandArgs.Parsed(false, "hacking   x"), new CommandArgs.Parsed(false, "hacking   x"));
        assertEquals(new CommandArgs.Parsed(false, "hacking x"), CommandArgs.parse("  hacking    x "));
        assertEquals(new CommandArgs.Parsed(true, ""), CommandArgs.parse("-s"));
        assertEquals(new CommandArgs.Parsed(false, ""), CommandArgs.parse(""));
        assertFalse(CommandArgs.parse("-silent x").silent(), "only the exact flag");
    }

    @Test
    void notesAreCleanedAndCut() {
        assertEquals("a b", CommandArgs.clean("  a\u0007 b\n ", 200));
        assertNull(CommandArgs.clean(" \t ", 200));
        assertNull(CommandArgs.clean(null, 200));
        assertEquals("abcd", CommandArgs.clean("abcdefgh", 4));
        assertEquals("<red>kept as typed</red>", CommandArgs.clean("<red>kept as typed</red>", 200));
        assertEquals("😀😀", CommandArgs.clean("😀😀😀", 2),
                "cut by characters, never in the middle of one");
    }

    @Test
    void completionKnowsWhenTheReasonIsBeingTyped() {
        assertArrayEquals(new int[] {0, 1}, CommandArgs.completing("hac"));
        assertArrayEquals(new int[] {3, 1}, CommandArgs.completing("-s hac"));
        assertArrayEquals(new int[] {8, 0}, CommandArgs.completing("hacking kill"));
        assertTrue(CommandArgs.completing("")[1] == 1);
    }
}
