package com.mrstudios.punish.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class DurationParserTest {

    private static final long S = 1000L;
    private static final long M = 60 * S;
    private static final long H = 60 * M;
    private static final long D = 24 * H;

    @ParameterizedTest
    @CsvSource({
            "30s, 30000",
            "10m, 600000",
            "2h, 7200000",
            "1d, 86400000",
            "1w, 604800000",
            "1mo, 2592000000",
            "1y, 31536000000",
            "1d12h, 129600000",
            "1D12H, 129600000",
            "'1d 12h', 129600000",
            "12h1d, 129600000",
            "1h30m15s, 5415000",
            "'  2h  ', 7200000",
            "2mo, 5184000000",
            "1h0m, 3600000"})
    void parsesUnitsAndCombinations(String text, long millis) {
        assertEquals(TimeSpan.ofMillis(millis), DurationParser.parse(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"permanent", "perm", "forever", "PERMANENT", " Forever "})
    void permanentWords(String text) {
        assertEquals(TimeSpan.PERMANENT, DurationParser.parse(text));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsEmpty(String text) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(text));
        assertTrue(e.getMessage().contains("empty"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0s", "0d0h", "-1h", "1.5h", "1", "h", "5x", "1hx", "x1h", "1h-", "abc", "1 h",
            "1h1h", "permanently", "1,5h", "+1h", "1h 2"})
    void rejectsInvalid(String text) {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(text));
    }

    @Test
    void rejectsZeroWithAClearReason() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("0m"));
        assertTrue(e.getMessage().contains("longer than 0"), e.getMessage());
    }

    @Test
    void rejectsMoreThanAHundredYearsAndOverflow() {
        assertEquals(TimeSpan.ofMillis(DurationParser.MAX_MILLIS), DurationParser.parse("100y"));
        for (String text : new String[] {"101y", "100y1s", "999999999999y", "999999999999w", "36501d"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DurationParser.parse(text),
                    text);
            assertTrue(e.getMessage().contains("100 years"), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parse("9999999999999s"));
    }

    @Test
    void formatsTheTwoLargestUnits() {
        assertEquals("6d 23h", DurationParser.format(7 * D - H));
        assertEquals("6d 23h", DurationParser.format(7 * D - S));
        assertEquals("7d", DurationParser.format(7 * D - 1));
        assertEquals("2h", DurationParser.format(2 * H));
        assertEquals("1h 30m", DurationParser.format(H + 30 * M + 15 * S));
        assertEquals("1d", DurationParser.format(D + 5 * M));
        assertEquals("45s", DurationParser.format(45 * S));
        assertEquals("1s", DurationParser.format(1));
        assertEquals("0s", DurationParser.format(0));
        assertEquals("1mo 5d", DurationParser.format(35 * D));
        assertEquals("1y", DurationParser.format(365 * D));
    }

    @Test
    void timeSpanText() {
        assertEquals("permanent", TimeSpan.PERMANENT.toString());
        assertEquals("1d 12h", DurationParser.parse("1d12h").toString());
        assertThrows(IllegalArgumentException.class, () -> TimeSpan.ofMillis(0));
    }
}
