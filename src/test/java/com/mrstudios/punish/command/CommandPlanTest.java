package com.mrstudios.punish.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.command.CommandClashCheck.Clash;
import com.mrstudios.punish.config.Settings.CommandToggle;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandPlanTest {

    @Test
    void enabledCommandTakesItsLabelAndAliases() {
        CommandPlan plan = CommandPlan.of("mr-punish", "muteip", new CommandToggle(true, List.of("ipmute")));
        assertEquals("muteip", plan.literal());
        assertEquals(List.of("ipmute"), plan.aliases());
        assertEquals(List.of("muteip", "ipmute"), plan.claims());
    }

    @Test
    void disabledCommandOnlyKeepsTheNamespacedLabel() {
        CommandPlan plan = CommandPlan.of("mr-punish", "ban", new CommandToggle(false, List.of("tempban")));
        assertEquals("mr-punish:ban", plan.literal());
        assertEquals(List.of(), plan.aliases());
        assertEquals(List.of(), plan.claims());
    }

    @Test
    void clashesAreLabelsOwnedBySomeoneElse() {
        Map<String, String> owners = Map.of("ban", "Essentials", "mrp", "an alias in the server's commands.yml");
        List<Clash> clashes = CommandClashCheck.find(List.of("mrpunish", "ban", "mrp", "warn"), owners::get);
        assertEquals(List.of(new Clash("ban", "Essentials"), new Clash("mrp", "an alias in the server's commands.yml")),
                clashes);
        assertEquals("/ban (Essentials)", clashes.get(0).toString());
    }

    @Test
    void clashWarningGivesTheFixes() {
        String switchable = CommandClashCheck.warning(new Clash("ban", "Essentials"), "mr-punish", true);
        assertTrue(switchable.startsWith("/ban belongs to Essentials, not MR-Punish. Use /mr-punish:ban"), switchable);
        assertTrue(switchable.contains("commands.*"), switchable);
        assertTrue(switchable.contains("ban: [\"mr-punish:ban $1-\"]"), switchable);

        String fixed = CommandClashCheck.warning(new Clash("mrp", "Other"), "mr-punish", false);
        assertTrue(!fixed.contains("commands.*") && fixed.contains("mrp: [\"mr-punish:mrp $1-\"]"), fixed);
    }
}
