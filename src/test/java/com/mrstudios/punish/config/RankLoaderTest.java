package com.mrstudios.punish.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.rank.Rank;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RankLoaderTest {

    /** Permissions known to the "server": everything starting with mrpunish. or other. */
    private final RankLoader loader = new RankLoader(
            name -> name.startsWith("mrpunish.") || name.startsWith("other."), RankLoader.bundledDefaults());

    private static String shipped() {
        try (InputStream in = RankLoaderTest.class.getResourceAsStream("/permissions.yml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String edited(String from, String to) {
        String text = shipped();
        if (!text.contains(from)) {
            throw new IllegalArgumentException("Not in permissions.yml: " + from);
        }
        return text.replace(from, to);
    }

    @Test
    void shippedRanksLoadWithoutWarnings() {
        LoadResult<RankSettings> result = loader.load(shipped());
        assertTrue(result.ok(), result.error());
        assertEquals(List.of(), result.warnings());
        RankSettings ranks = result.value();
        assertEquals(List.of("owner", "admin", "mod", "helper"), ranks.ranks().stream().map(Rank::name).toList());
        assertEquals(List.of(40, 30, 20, 10), ranks.ranks().stream().map(Rank::weight).toList());
    }

    @Test
    void grantsAreFlattenedThroughInheritance() {
        RankSettings ranks = loader.load(shipped()).value();
        Set<String> owner = ranks.byName("owner").orElseThrow().allGrants();
        Set<String> mod = ranks.byName("mod").orElseThrow().allGrants();
        Set<String> helper = ranks.byName("helper").orElseThrow().allGrants();

        assertTrue(owner.containsAll(Set.of("mrpunish.import", "mrpunish.remove", "mrpunish.ban", "mrpunish.warn")));
        assertTrue(mod.containsAll(Set.of("mrpunish.ban", "mrpunish.warn", "mrpunish.staff")));
        assertFalse(mod.contains("mrpunish.remove"));
        assertFalse(helper.contains("mrpunish.ban"));
        // Inherited rank nodes are children too (mrpunish.admin doubles as the admin-tools node); the
        // rank itself stays "the heaviest node you have".
        assertTrue(owner.containsAll(Set.of("mrpunish.admin", "mrpunish.mod", "mrpunish.helper")));
        assertFalse(owner.contains("mrpunish.owner"));
        assertFalse(helper.contains("mrpunish.helper"));
        assertFalse(mod.contains("mrpunish.admin"));
    }

    @Test
    void rankOfIsTheHeaviestHeldNode() {
        RankSettings ranks = loader.load(shipped()).value();
        Set<String> held = Set.of("mrpunish.helper", "mrpunish.admin");
        assertEquals("admin", ranks.rankOf(held::contains).orElseThrow().name());
        assertEquals(30, ranks.weightOf(held::contains));
        assertEquals(0, ranks.weightOf(p -> false));
    }

    @Test
    void duplicateWeightFailsTheWholeFile() {
        LoadResult<RankSettings> result = loader.load(edited("weight: 20", "weight: 10"));
        assertFalse(result.ok());
        assertTrue(result.error().contains("ranks.mod.weight: 10 is also the weight of 'helper'"), result.error());
        assertTrue(result.error().startsWith("permissions.yml: "), result.error());
    }

    @Test
    void badWeightsFail() {
        for (String weight : List.of("0", "1001", "ten", "1.5")) {
            LoadResult<RankSettings> result = loader.load(edited("weight: 40", "weight: " + weight));
            assertFalse(result.ok(), weight);
            assertTrue(result.error().contains("ranks.owner.weight"), result.error());
        }
        LoadResult<RankSettings> missing = loader.load(edited("    weight: 40\n", ""));
        assertTrue(missing.error().contains("ranks.owner.weight: is missing"), missing.error());
    }

    @Test
    void missingOrDuplicatePermissionFails() {
        LoadResult<RankSettings> missing = loader.load(edited("    permission: mrpunish.mod\n", ""));
        assertFalse(missing.ok());
        assertTrue(missing.error().contains("ranks.mod.permission: is missing"), missing.error());

        LoadResult<RankSettings> duplicate = loader.load(edited("permission: mrpunish.mod", "permission: mrpunish.helper"));
        assertFalse(duplicate.ok());
        assertTrue(duplicate.error().contains("is also the permission of 'helper'"), duplicate.error());
    }

    @Test
    void unknownInheritsAndCyclesFail() {
        LoadResult<RankSettings> unknown = loader.load(edited("inherits: [admin]", "inherits: [boss]"));
        assertFalse(unknown.ok());
        assertTrue(unknown.error().contains("ranks.owner.inherits: there is no rank 'boss'"), unknown.error());

        LoadResult<RankSettings> cycle = loader.load(edited("inherits: []", "inherits: [owner]"));
        assertFalse(cycle.ok());
        assertTrue(cycle.error().contains("goes round in a circle"), cycle.error());

        LoadResult<RankSettings> self = loader.load(edited("inherits: [mod]", "inherits: [admin]"));
        assertFalse(self.ok());
        assertTrue(self.error().contains("a rank can't inherit itself"), self.error());
    }

    @Test
    void grantingAnotherRanksNodeFails() {
        LoadResult<RankSettings> result = loader.load(edited("      - mrpunish.import", "      - mrpunish.import\n"
                + "      - mrpunish.helper"));
        // Granting a lower rank's node is still refused: inherits is the one way to share grants.
        assertFalse(result.ok());
        assertTrue(result.error().contains("mrpunish.helper is the permission of rank 'helper'"), result.error());
    }

    @Test
    void unknownGrantOnlyWarnsAndIsKept() {
        LoadResult<RankSettings> result = loader.load(edited("      - mrpunish.import",
                "      - mrpunish.import\n      - somethingelse.fly\n      - other.fly\n      - 'bad node!'"));
        assertTrue(result.ok(), result.error());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("somethingelse.fly is not a permission")),
                result.warnings().toString());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("'bad node!' is not a permission node")));
        assertEquals(2, result.warnings().size(), result.warnings().toString());
        Set<String> owner = result.value().byName("owner").orElseThrow().allGrants();
        assertTrue(owner.contains("somethingelse.fly") && owner.contains("other.fly"));
    }

    @Test
    void inheritingAHeavierRankWarns() {
        String text = edited("    weight: 10\n", "    weight: 50\n");
        LoadResult<RankSettings> result = loader.load(text);
        assertTrue(result.ok(), result.error());
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("ranks.mod.inherits: 'helper' has a weight of 50")),
                result.warnings().toString());
    }

    @Test
    void badDisplayAndUnknownKeysWarn() {
        LoadResult<RankSettings> result = loader.load(edited("display: \"<green>Helper\"", "display: \"<nope>Helper\"")
                + "colour: red\n");
        assertTrue(result.ok());
        assertEquals("helper", result.value().byName("helper").orElseThrow().display());
        assertTrue(result.warnings().stream().anyMatch(w -> w.startsWith("ranks.helper.display: <nope>")));
        assertTrue(result.warnings().contains("colour is not a known setting (typo?), ignored"));
    }

    @Test
    void missingRanksFailsAndEmptyRanksWarns() {
        LoadResult<RankSettings> missing = loader.load("permissions-version: 1\n");
        assertFalse(missing.ok());
        LoadResult<RankSettings> empty = loader.load("permissions-version: 1\nranks: {}\n");
        assertTrue(empty.ok());
        assertTrue(empty.warnings().get(0).startsWith("ranks is empty"));
    }

    @Test
    void customRankWithItsOwnNode() {
        String text = edited("ranks:\n", """
                ranks:
                  trial:
                    permission: mrpunish.trial
                    weight: 5
                    display: "<gray>Trial"
                    grants: [mrpunish.staff, mrpunish.warn]
                """);
        LoadResult<RankSettings> result = loader.load(text);
        assertTrue(result.ok(), result.error());
        assertEquals(5, result.value().byName("trial").orElseThrow().weight());
        assertEquals("trial", result.value().ranks().get(4).name());
    }
}
