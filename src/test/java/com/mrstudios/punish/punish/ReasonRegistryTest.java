package com.mrstudios.punish.punish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.config.ConfigException;
import com.mrstudios.punish.config.DurationParser;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.config.SettingsLoader;
import com.mrstudios.punish.config.TimeSpan;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReasonRegistryTest {

    private static Reason reason(String key, PunishmentType type, String... aliases) {
        return reason(key, type, true, null, aliases);
    }

    private static Reason reason(String key, PunishmentType type, boolean enabled, String permission,
            String... aliases) {
        List<TimeSpan> steps = type.hasDuration() ? List.of(DurationParser.parse("1h")) : List.of();
        return new Reason(key, type, key, List.of(), "PAPER", -1, List.of(aliases), steps, null, permission, enabled);
    }

    /** Fallbacks named {@code other-<type>} for every type. */
    private static Map<PunishmentType, String> fallbacks() {
        Map<PunishmentType, String> map = new EnumMap<>(PunishmentType.class);
        for (PunishmentType type : PunishmentType.values()) {
            map.put(type, "other-" + type.name().toLowerCase().replace("_", ""));
        }
        return map;
    }

    private static List<Reason> withFallbacks(Reason... reasons) {
        List<Reason> all = new ArrayList<>(List.of(reasons));
        for (PunishmentType type : PunishmentType.values()) {
            all.add(reason("other-" + type.name().toLowerCase().replace("_", ""), type, "other"));
        }
        return all;
    }

    private static ReasonRegistry build(List<String> warnings, Reason... reasons) throws ConfigException {
        return ReasonRegistry.build(withFallbacks(reasons), fallbacks(), warnings);
    }

    /** The shipped config.yml, loaded the way the plugin loads it. */
    private static ReasonRegistry shipped() {
        Settings settings = new SettingsLoader(name -> true, List.of(), Set.of(), SettingsLoader.bundledDefaults())
                .defaults();
        return settings.reasons();
    }

    @Test
    void keyBeatsAnotherReasonsAliasEvenWhenListedLater() throws Exception {
        List<String> warnings = new ArrayList<>();
        ReasonRegistry registry = build(warnings,
                reason("toxicity", PunishmentType.MUTE, "spam"),
                reason("spam", PunishmentType.MUTE, "flood"));

        assertEquals("spam", registry.match(PunishmentType.MUTE, "spam").orElseThrow().key());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("is the key of reason 'spam'"), warnings.get(0));
    }

    @Test
    void firstListedKeepsADuplicateAlias() throws Exception {
        List<String> warnings = new ArrayList<>();
        ReasonRegistry registry = build(warnings,
                reason("a", PunishmentType.BAN, "dupe"),
                reason("b", PunishmentType.BAN, "dupe", "bee"));

        assertEquals("a", registry.match(PunishmentType.BAN, "DUPE").orElseThrow().key());
        assertEquals("b", registry.match(PunishmentType.BAN, "bee").orElseThrow().key());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).startsWith("reasons.b.aliases: 'dupe'"), warnings.get(0));
    }

    @Test
    void aliasesAreScopedToTheirType() throws Exception {
        List<String> warnings = new ArrayList<>();
        ReasonRegistry registry = build(warnings,
                reason("spam-warn", PunishmentType.WARN, "spam"),
                reason("spam", PunishmentType.MUTE));

        assertEquals("spam-warn", registry.match(PunishmentType.WARN, "spam").orElseThrow().key());
        assertEquals("spam", registry.match(PunishmentType.MUTE, "spam").orElseThrow().key());
        assertTrue(registry.match(PunishmentType.BAN, "spam").isEmpty());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void disabledReasonsDontMatchButStayKnown() throws Exception {
        ReasonRegistry registry = build(new ArrayList<>(),
                reason("old", PunishmentType.BAN, false, null, "legacy"));

        assertTrue(registry.match(PunishmentType.BAN, "old").isEmpty());
        assertTrue(registry.match(PunishmentType.BAN, "legacy").isEmpty());
        assertFalse(registry.byKey("old").orElseThrow().enabled());
        assertFalse(registry.enabled(PunishmentType.BAN).stream().anyMatch(r -> r.key().equals("old")));
    }

    @Test
    void anAliasOfADisabledReasonIsFreeForAnother() throws Exception {
        List<String> warnings = new ArrayList<>();
        ReasonRegistry registry = build(warnings,
                reason("old", PunishmentType.BAN, false, null, "grief"),
                reason("griefing", PunishmentType.BAN, "grief"));

        assertEquals("griefing", registry.match(PunishmentType.BAN, "grief").orElseThrow().key());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void resolveSplitsReasonAndNote() throws Exception {
        ReasonRegistry registry = shipped();

        var spam = registry.resolve(PunishmentType.MUTE, "spam flooding chat").orElseThrow();
        assertEquals("spam", spam.reason().key());
        assertEquals("flooding chat", spam.note());
        assertFalse(spam.fallback());

        var warn = registry.resolve(PunishmentType.WARN, "spam").orElseThrow();
        assertEquals("spam-warn", warn.reason().key());
        assertNull(warn.note());

        var hacking = registry.resolve(PunishmentType.BAN, "  Hacking   killaura on 2 alts ").orElseThrow();
        assertEquals("hacking", hacking.reason().key());
        assertEquals("killaura on 2 alts", hacking.note());
    }

    @Test
    void unmatchedFirstWordFallsBackAndTheWholeTextIsTheNote() throws Exception {
        ReasonRegistry registry = shipped();

        var ban = registry.resolve(PunishmentType.BAN, "spam").orElseThrow();
        assertEquals("other-ban", ban.reason().key());
        assertEquals("spam", ban.note());
        assertTrue(ban.fallback());

        var mute = registry.resolve(PunishmentType.MUTE, "being annoying in chat").orElseThrow();
        assertEquals("other-mute", mute.reason().key());
        assertEquals("being annoying in chat", mute.note());
    }

    @Test
    void emptyTextResolvesToNothing() throws Exception {
        ReasonRegistry registry = shipped();
        assertTrue(registry.resolve(PunishmentType.MUTE, "").isEmpty());
        assertTrue(registry.resolve(PunishmentType.MUTE, "   ").isEmpty());
        assertTrue(registry.resolve(PunishmentType.MUTE, null).isEmpty());
    }

    @Test
    void shippedReasonsMatchTheDesignExamples() {
        ReasonRegistry registry = shipped();
        assertEquals("spam-warn", registry.match(PunishmentType.WARN, "spam").orElseThrow().key());
        assertEquals("harassment-ban", registry.match(PunishmentType.BAN, "harassment").orElseThrow().key());
        assertEquals("ban-evasion", registry.match(PunishmentType.IP_BAN, "alt").orElseThrow().key());
        assertEquals("hacking-ip", registry.match(PunishmentType.IP_BAN, "hacking").orElseThrow().key());
        assertEquals("other-ipmute", registry.fallback(PunishmentType.IP_MUTE).key());
        assertEquals(List.of("10m", "30m", "2h", "1d"), registry.byKey("spam").orElseThrow().escalation().stream()
                .map(TimeSpan::toString).toList());
    }

    @Test
    void reasonPermission() throws Exception {
        ReasonRegistry registry = build(new ArrayList<>(),
                reason("severe", PunishmentType.BAN, true, "mrpunish.reason.severe"));
        Reason severe = registry.byKey("severe").orElseThrow();
        Reason open = registry.fallback(PunishmentType.BAN);

        assertFalse(ReasonRegistry.mayUse(severe, perm -> false));
        assertTrue(ReasonRegistry.mayUse(severe, perm -> perm.equals("mrpunish.reason.severe")));
        assertTrue(ReasonRegistry.mayUse(open, perm -> false));
    }

    @Test
    void missingWrongTypeOrDisabledFallbackFails() {
        Map<PunishmentType, String> missing = fallbacks();
        missing.remove(PunishmentType.IP_BAN);
        ConfigException e = assertThrows(ConfigException.class,
                () -> ReasonRegistry.build(withFallbacks(), missing, new ArrayList<>()));
        assertTrue(e.getMessage().contains("fallback-reasons.IP_BAN is missing"), e.getMessage());

        Map<PunishmentType, String> wrongType = fallbacks();
        wrongType.put(PunishmentType.BAN, "other-mute");
        e = assertThrows(ConfigException.class,
                () -> ReasonRegistry.build(withFallbacks(), wrongType, new ArrayList<>()));
        assertTrue(e.getMessage().contains("fallback-reasons.BAN: 'other-mute' is not an enabled BAN reason (it's a MUTE"),
                e.getMessage());

        Map<PunishmentType, String> disabled = fallbacks();
        disabled.put(PunishmentType.WARN, "off");
        e = assertThrows(ConfigException.class, () -> ReasonRegistry.build(
                withFallbacks(reason("off", PunishmentType.WARN, false, null)), disabled, new ArrayList<>()));
        assertTrue(e.getMessage().contains("(it's disabled)"), e.getMessage());
    }

    @Test
    void periodOverride() {
        TimeSpan global = DurationParser.parse("90d");
        Reason own = new Reason("x", PunishmentType.MUTE, "x", List.of(), "PAPER", -1, List.of(),
                List.of(DurationParser.parse("1h")), TimeSpan.PERMANENT, null, true);
        assertEquals(TimeSpan.PERMANENT, own.period(global));
        assertEquals(global, reason("y", PunishmentType.MUTE).period(global));
    }
}
