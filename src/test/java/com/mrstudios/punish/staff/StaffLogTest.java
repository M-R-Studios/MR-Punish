package com.mrstudios.punish.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The staff log's groups, file lines and IP scrubbing (DESIGN.md 13.6). */
class StaffLogTest {

    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final long AT = LocalDateTime.of(2026, 11, 7, 14, 3, 12).toInstant(ZoneOffset.UTC).toEpochMilli();

    @Test
    void everyLoggedActionHasExactlyOneGroup() {
        List<String> logged = List.of("PUNISH", "REFUSED", "REMOVE", "REPORT_CREATE", "REPORT_CLAIM", "REPORT_UNCLAIM",
                "REPORT_RESOLVE", "REPORT_REJECT", "REPORT_NOTE", "REPORT_CLAIM_EXPIRED", "INSPECT_INV", "INSPECT_ENDER",
                "INSPECT_HISTORY", "FREEZE", "UNFREEZE", "FREEZE_QUIT", "STAFFMODE_ON", "STAFFMODE_OFF", "VANISH_ON",
                "VANISH_OFF", "RELOAD", "IMPORT", "FORCE_EXIT");
        Set<String> seen = new HashSet<>();
        for (StaffLog.Group g : StaffLog.Group.values()) {
            for (String action : g.actions()) {
                assertTrue(seen.add(action), action + " is in two groups");
            }
        }
        assertEquals(new HashSet<>(logged), seen);
        assertEquals(StaffLog.Group.PUNISHMENTS, StaffLog.groupOf("PUNISH"));
        assertEquals(StaffLog.Group.REMOVALS, StaffLog.groupOf("REMOVE"));
        assertEquals(StaffLog.Group.REPORTS, StaffLog.groupOf("REPORT_CLAIM_EXPIRED"));
        assertEquals(StaffLog.Group.INSPECTIONS, StaffLog.groupOf("INSPECT_HISTORY"));
        assertEquals(StaffLog.Group.STAFF_TOOLS, StaffLog.groupOf("VANISH_OFF"));
        assertEquals(StaffLog.Group.ADMIN, StaffLog.groupOf("FORCE_EXIT"));
        // From a newer version: shown, as admin.
        assertEquals(StaffLog.Group.ADMIN, StaffLog.groupOf("SOMETHING_NEW"));
        assertTrue(StaffLog.Group.ALL.actions().isEmpty());
    }

    @Test
    void theFilterCyclesThroughEveryGroupBackToAll() {
        StaffLog.Group g = StaffLog.Group.ALL;
        for (int i = 0; i < StaffLog.Group.values().length; i++) {
            g = g.next();
        }
        assertEquals(StaffLog.Group.ALL, g);
        assertEquals(StaffLog.Group.PUNISHMENTS, StaffLog.Group.ALL.next());
    }

    @Test
    void anythingLikeAnIpIsScrubbed() {
        assertEquals("joined from [ip] today", StaffLog.scrub("joined from 192.168.1.75 today"));
        assertEquals("v6 [ip] here", StaffLog.scrub("v6 2001:db8:85a3::8a2e:370:7334 here"));
        assertEquals("MUTE toxicity 2h offence=1", StaffLog.scrub("MUTE toxicity 2h offence=1"));
        assertEquals("at 14:03", StaffLog.scrub("at 14:03"));
        assertNull(StaffLog.scrub(null));
    }

    @Test
    void fileLinesAndNames() {
        assertEquals("staff-2026-11-07.log", StaffLog.fileName(AT, UTC));
        assertEquals("2026-11-07 14:03:12 [survival] Alex PUNISH Steve #42 MUTE toxicity 2h offence=1",
                StaffLog.line(AT, UTC, "survival", "Alex", "PUNISH", "Steve", 42L, "MUTE toxicity 2h offence=1"));
        assertEquals("2026-11-07 14:03:12 [survival] Console RELOAD ok",
                StaffLog.line(AT, UTC, "survival", "Console", "RELOAD", null, null, "ok"));
        String line = StaffLog.line(AT, UTC, "s", "Alex", "PUNISH", "Steve", 1L, "a\nb 10.0.0.1");
        assertFalse(line.contains("\n"));
        assertFalse(line.contains("10.0.0.1"), line);
    }
}
