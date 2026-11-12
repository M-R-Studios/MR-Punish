package com.mrstudios.punish.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Who owns a label: the command tree decides, not Bukkit's command map. EssentialsX's {@code v}
 * alias stays in the command map although MR-Punish's {@code /v} is what runs.
 */
class ClashOwnershipTest {

    @Test
    void theDispatcherDecidesNotTheCommandMap() {
        CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
        Command<Object> vanish = ctx -> 1;
        Command<Object> essentialsBan = ctx -> 2;
        LiteralCommandNode<Object> ours = dispatcher.register(LiteralArgumentBuilder.literal("vanish").executes(vanish));
        // An alias as a copy with the same executor, and one as a redirect.
        dispatcher.register(LiteralArgumentBuilder.literal("v").executes(vanish));
        dispatcher.register(LiteralArgumentBuilder.<Object>literal("vv").redirect(ours));
        dispatcher.register(LiteralArgumentBuilder.literal("ban").executes(essentialsBan));
        Set<Command<Object>> mine = Collections.newSetFromMap(new IdentityHashMap<>());
        mine.add(vanish);

        assertTrue(CommandRegistry.runsOneOf(dispatcher.getRoot().getChild("vanish"), mine));
        assertTrue(CommandRegistry.runsOneOf(dispatcher.getRoot().getChild("v"), mine));
        assertTrue(CommandRegistry.runsOneOf(dispatcher.getRoot().getChild("vv"), mine));
        assertFalse(CommandRegistry.runsOneOf(dispatcher.getRoot().getChild("ban"), mine), "someone else's");
        assertFalse(CommandRegistry.runsOneOf(dispatcher.getRoot().getChild("nothing"), mine), "not registered");

        // The command map still says Essentials owns /v: no clash, because ours runs.
        assertNull(CommandClashCheck.owner(true, "Essentials"));
        // A real clash is still reported.
        assertEquals("Essentials", CommandClashCheck.owner(false, "Essentials"));
        assertNull(CommandClashCheck.owner(false, null));
        List<CommandClashCheck.Clash> clashes = CommandClashCheck.find(List.of("v", "ban"),
                label -> CommandClashCheck.owner(
                        CommandRegistry.runsOneOf(dispatcher.getRoot().getChild(label), mine), "Essentials"));
        assertEquals(List.of(new CommandClashCheck.Clash("ban", "Essentials")), clashes);
    }

    @Test
    void aCommandsYmlAliasPointingAtMrPunishIsOurs() {
        assertTrue(CommandClashCheck.aliasPointsAt(new String[] {"mr-punish:v $1-"}, "mr-punish"));
        assertTrue(CommandClashCheck.aliasPointsAt(new String[] {"/mr-punish:ban $1-", "mr-punish:history $1"},
                "mr-punish"));
        assertFalse(CommandClashCheck.aliasPointsAt(new String[] {"essentials:ban $1-"}, "mr-punish"));
        assertFalse(CommandClashCheck.aliasPointsAt(new String[] {"mr-punish:ban $1-", "say hi"}, "mr-punish"),
                "an alias that also runs something else isn't only ours");
        assertFalse(CommandClashCheck.aliasPointsAt(null, "mr-punish"));
        assertFalse(CommandClashCheck.aliasPointsAt(new String[0], "mr-punish"));
    }
}
