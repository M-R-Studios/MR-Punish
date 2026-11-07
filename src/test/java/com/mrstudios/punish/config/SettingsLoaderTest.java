package com.mrstudios.punish.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.config.Settings.CommandToggle;
import com.mrstudios.punish.punish.PunishmentType;
import com.mrstudios.punish.punish.Reason;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SettingsLoaderTest {

    /** Any material is an item except names starting with NOT_. */
    private static SettingsLoader loader() {
        return loader(List.of());
    }

    private static SettingsLoader loader(List<String> switchable) {
        return new SettingsLoader(name -> !name.startsWith("NOT_"), switchable, Set.of("mrpunish", "mrp"),
                SettingsLoader.bundledDefaults());
    }

    /** The shipped config.yml exactly as written. */
    static String bundledText() {
        try (var in = SettingsLoaderTest.class.getResourceAsStream("/config.yml")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The shipped file with {@code from} replaced by {@code to} (which must occur). */
    private static String edited(String from, String to) {
        String text = bundledText();
        if (!text.contains(from)) {
            throw new IllegalArgumentException("Not in config.yml: " + from);
        }
        return text.replace(from, to);
    }

    private static boolean has(LoadResult<?> result, String fragment) {
        return result.warnings().stream().anyMatch(w -> w.contains(fragment));
    }

    @Test
    void shippedDefaultsLoadWithoutWarnings() {
        LoadResult<Settings> result = loader().load(bundledText());
        assertTrue(result.ok(), result.error());
        assertEquals(List.of(), result.warnings());
        Settings settings = result.value();
        assertEquals(DurationParser.parse("90d"), settings.historyPeriod());
        assertEquals(28, settings.reasons().all().size());
        for (PunishmentType type : PunishmentType.values()) {
            assertTrue(settings.reasons().fallback(type).enabled());
            assertFalse(settings.reasons().enabled(type).isEmpty());
        }
        assertEquals(Settings.CommandToggle.DEFAULT, settings.command("anything"));
    }

    @Test
    void badHistoryPeriodWarnsAndUsesTheDefault() {
        LoadResult<Settings> result = loader().load(edited("history-period: 90d", "history-period: 0d"));
        assertTrue(result.ok());
        assertTrue(has(result, "escalation.history-period: '0d' must be longer than 0"), result.warnings().toString());
        assertEquals(DurationParser.parse("90d"), result.value().historyPeriod());
    }

    @Test
    void badEscalationDisablesOnlyThatReason() {
        LoadResult<Settings> result = loader().load(edited("escalation: [\"10m\", \"30m\", \"2h\", \"1d\"]",
                "escalation: [\"10m\", \"30x\"]"));
        assertTrue(result.ok(), result.error());
        assertTrue(has(result, "reasons.spam: escalation step 2: '30x' is not a duration"), result.warnings().toString());
        assertTrue(has(result, "so this reason is disabled"));
        Reason spam = result.value().reasons().byKey("spam").orElseThrow();
        assertFalse(spam.enabled());
        assertTrue(result.value().reasons().byKey("toxicity").orElseThrow().enabled());
        // "spam" now falls back: no MUTE reason is called spam any more.
        assertEquals("other-mute", result.value().reasons().resolve(PunishmentType.MUTE, "spam").orElseThrow()
                .reason().key());
    }

    @Test
    void emptyEscalationUnknownTypeUnknownIconAndBadPeriodDisable() {
        String extra = """
                  emptyladder:
                    type: MUTE
                    display: "Empty"
                    icon: PAPER
                    escalation: []
                  badtype:
                    type: KICK
                    display: "Kick"
                    icon: PAPER
                  badicon:
                    type: BAN
                    display: "Bad icon"
                    icon: NOT_A_THING
                    escalation: ["1d"]
                  badperiod:
                    type: BAN
                    display: "Bad period"
                    icon: PAPER
                    escalation: ["1d"]
                    history-period: soon
                  warnwithladder:
                    type: WARN
                    display: "Warn"
                    icon: PAPER
                    escalation: ["1d"]
                """;
        LoadResult<Settings> result = loader().load(edited("reasons:\n", "reasons:\n" + extra));
        assertTrue(result.ok(), result.error());
        var reasons = result.value().reasons();
        assertFalse(reasons.byKey("emptyladder").orElseThrow().enabled());
        assertTrue(reasons.byKey("badtype").isEmpty());
        assertTrue(has(result, "reasons.badtype.type: 'KICK' is not one of WARN, MUTE, BAN, IP_MUTE, IP_BAN"));
        assertFalse(reasons.byKey("badicon").orElseThrow().enabled());
        assertTrue(has(result, "icon 'NOT_A_THING' is not an item"));
        assertFalse(reasons.byKey("badperiod").orElseThrow().enabled());
        assertTrue(has(result, "reasons.badperiod: history-period: 'soon' is not a duration"));
        assertTrue(reasons.byKey("warnwithladder").orElseThrow().enabled());
        assertTrue(has(result, "reasons.warnwithladder.escalation is not used for WARN reasons"));
    }

    @Test
    void reasonFieldsAreRead() {
        String extra = """
                  severe:
                    type: ban
                    display: "<red>Severe"
                    description: ["<gray>One", "<bad:tag>", "<gray>Two"]
                    icon: tnt
                    slot: 13
                    aliases: [Sev, "two words", sev]
                    escalation: ["permanent"]
                    history-period: permanent
                    permission: mrpunish.reason.Severe
                    enabled: true
                    colour: red
                """;
        LoadResult<Settings> result = loader().load(edited("reasons:\n", "reasons:\n" + extra));
        assertTrue(result.ok(), result.error());
        Reason severe = result.value().reasons().byKey("severe").orElseThrow();
        assertTrue(severe.enabled());
        assertEquals(PunishmentType.BAN, severe.type());
        assertEquals("<red>Severe", severe.display());
        assertEquals(List.of("<gray>One", "<gray>Two"), severe.description());
        assertEquals("TNT", severe.icon());
        assertEquals(13, severe.slot());
        assertEquals(List.of("sev"), severe.aliases());
        assertEquals(List.of(TimeSpan.PERMANENT), severe.escalation());
        assertEquals(TimeSpan.PERMANENT, severe.historyPeriod());
        assertEquals("mrpunish.reason.severe", severe.permission());
        assertTrue(has(result, "reasons.severe.description: '<bad:tag>'"));
        assertTrue(has(result, "reasons.severe.aliases: 'two words' is not a single word"));
        assertTrue(has(result, "reasons.severe.colour is not a reason setting"));
    }

    @Test
    void badSlotAndDisplayFallBack() {
        String extra = """
                  odd:
                    type: WARN
                    display: "<notatag>Odd"
                    icon: PAPER
                    slot: 99
                """;
        LoadResult<Settings> result = loader().load(edited("reasons:\n", "reasons:\n" + extra));
        Reason odd = result.value().reasons().byKey("odd").orElseThrow();
        assertTrue(odd.enabled());
        assertEquals(-1, odd.slot());
        assertEquals("odd", odd.display());
        assertTrue(has(result, "reasons.odd.slot: '99' is not a whole number from 0 to 53"));
        assertTrue(has(result, "reasons.odd.display: <notatag> is not a valid tag here"));
    }

    @Test
    void badReasonPermissionDisablesTheReasonRatherThanOpeningIt() {
        String extra = """
                  locked:
                    type: WARN
                    display: "Locked"
                    icon: PAPER
                    permission: "has spaces"
                """;
        LoadResult<Settings> result = loader().load(edited("reasons:\n", "reasons:\n" + extra));
        assertFalse(result.value().reasons().byKey("locked").orElseThrow().enabled());
        assertTrue(has(result, "permission 'has spaces' is not a permission node"));
    }

    @Test
    void invalidAndDuplicateKeys() {
        String extra = """
                  "bad key":
                    type: WARN
                    display: "x"
                    icon: PAPER
                  Spam:
                    type: WARN
                    display: "x"
                    icon: PAPER
                """;
        LoadResult<Settings> result = loader().load(edited("reasons:\n", "reasons:\n" + extra));
        assertTrue(result.ok(), result.error());
        assertTrue(has(result, "'bad key' is not a valid reason key"));
        // "Spam" (listed first now) takes the key; the shipped MUTE "spam" is the second and is disabled.
        assertTrue(has(result, "the key 'spam' is used twice"));
        assertEquals(PunishmentType.WARN, result.value().reasons().byKey("spam").orElseThrow().type());
    }

    @Test
    void duplicateAliasWarnsAndFirstWins() {
        LoadResult<Settings> result = loader().load(edited("aliases: [toxic, rude]", "aliases: [toxic, rude, flood]"));
        assertTrue(result.ok());
        assertTrue(has(result, "reasons.toxicity.aliases: 'flood' is already an alias of reason 'spam'"),
                result.warnings().toString());
        assertEquals("spam", result.value().reasons().match(PunishmentType.MUTE, "flood").orElseThrow().key());
    }

    @Test
    void missingFallbackReasonFailsTheWholeFile() {
        LoadResult<Settings> result = loader().load(edited("  BAN: other-ban\n", ""));
        assertFalse(result.ok());
        assertTrue(result.error().contains("fallback-reasons.BAN is missing"), result.error());
        assertTrue(result.error().startsWith("config.yml"), result.error());
    }

    @Test
    void fallbackNamingAnInvalidReasonFails() {
        LoadResult<Settings> result = loader().load(edited("    escalation: [\"1d\", \"7d\", \"30d\"]\n\n  # ---- IP mutes",
                "    escalation: [\"nope\"]\n\n  # ---- IP mutes"));
        assertFalse(result.ok());
        assertTrue(result.error().contains("fallback-reasons.BAN: 'other-ban' is not an enabled BAN reason (it's disabled)"),
                result.error());
    }

    @Test
    void unknownKeysWarn() {
        LoadResult<Settings> result = loader().load(bundledText() + "colour: red\nescalation2: 1\n");
        assertTrue(has(result, "colour is not a known setting"));
        assertTrue(has(result, "escalation2 is not a known setting"));
        result = loader().load(edited("history-period: 90d", "history-period: 90d\n  speed: 3"));
        assertTrue(has(result, "escalation.speed is not a known setting"));
        result = loader().load(edited("  WARN: other-warn\n", "  WARN: other-warn\n  KICK: other-warn\n"));
        assertTrue(has(result, "fallback-reasons.KICK is not a punishment type"));
    }

    @Test
    void yamlErrorsNameTheLine() {
        LoadResult<Settings> result = loader().load("config-version: 1\nescalation:\n  history-period: [90d\n");
        assertFalse(result.ok());
        assertTrue(result.error().startsWith("config.yml line "), result.error());
    }

    @Test
    void commandToggles() {
        SettingsLoader loader = loader(List.of("ban", "mute"));
        String text = edited("commands: {}", """
                commands:
                  ban: { enabled: false, aliases: [tempban, "bad label", mrp] }
                  mute: { enabled: true, aliases: [tempban, silence] }
                  fly: { enabled: false }
                """);
        LoadResult<Settings> result = loader.load(text);
        assertTrue(result.ok(), result.error());
        Settings settings = result.value();
        assertEquals(new CommandToggle(false, List.of("tempban")), settings.command("ban"));
        assertEquals(new CommandToggle(true, List.of("silence")), settings.command("mute"));
        assertTrue(has(result, "commands.ban.aliases: 'bad label' is not a command label"));
        assertTrue(has(result, "commands.ban.aliases: 'mrp' is already the label of another MR-Punish command"));
        assertTrue(has(result, "commands.mute.aliases: 'tempban' is already the label of another MR-Punish command"));
        assertTrue(has(result, "commands.fly is not an MR-Punish command that can be turned off"));
    }

    @Test
    void missingCommandToggleWarnsAndIsOn() {
        LoadResult<Settings> result = loader(List.of("ban")).load(bundledText());
        assertTrue(has(result, "commands.ban is missing, using enabled: true"));
        assertEquals(CommandToggle.DEFAULT, result.value().command("ban"));
    }

    @Test
    void shippedCommandsSectionIsEmptyAndAnyEntryWarnsForNow() {
        LoadResult<Settings> result = loader().load(edited("commands: {}", "commands:\n  ban: { enabled: false }"));
        assertTrue(has(result, "commands.ban is not an MR-Punish command that can be turned off"));
    }

    @Test
    void storageDefaultsAndValidation() {
        Settings settings = loader().load(bundledText()).value();
        assertEquals(Settings.StorageType.SQLITE, settings.storage().type());
        assertEquals("punishments.db", settings.storage().sqliteFile());
        assertEquals("mrpunish_", settings.storage().mysql().tablePrefix());
        assertEquals(4, settings.storage().mysql().poolSize());
        assertEquals(java.util.Map.of("sslMode", "PREFERRED"), settings.storage().mysql().properties());

        LoadResult<Settings> bad = loader().load(edited("table-prefix: \"mrpunish_\"", "table-prefix: \"x; DROP\"")
                .replace("pool-size: 4", "pool-size: 50")
                .replace("file: punishments.db", "file: ../evil.db")
                .replace("type: SQLITE", "type: POSTGRES"));
        assertTrue(bad.ok(), bad.error());
        assertTrue(has(bad, "storage.mysql.table-prefix: 'x; DROP' is not lower-case letters"), bad.warnings().toString());
        assertTrue(has(bad, "storage.mysql.pool-size: '50' is not a whole number from 2 to 20"));
        assertTrue(has(bad, "storage.sqlite.file: '../evil.db' is not a file name without folders"));
        assertTrue(has(bad, "storage.type: 'POSTGRES' is not SQLITE or MYSQL"));
        assertEquals("mrpunish_", bad.value().storage().mysql().tablePrefix());
        assertEquals("punishments.db", bad.value().storage().sqliteFile());

        LoadResult<Settings> typo = loader().load(edited("    pool-size: 4", "    pool-size: 4\n    poolsize: 4"));
        assertTrue(has(typo, "storage.mysql.poolsize is not a known setting"));
    }
}
