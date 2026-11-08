package com.mrstudios.punish.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.gui.Items;
import com.mrstudios.punish.gui.MenuHolder;
import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.PunishmentType;
import com.mrstudios.punish.storage.PunishmentRepo;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The punish menus through the real plugin on MockBukkit (DESIGN.md 10.1-10.4). */
class PluginMenuTest {

    private static final int HEAD = 4;
    private static final int WARNINGS = 19;
    private static final int MUTES = 20;
    private static final int IP_MUTES = 23;
    private static final int FIRST_REASON = 10;
    private static final int REASON_NOTE = 49;
    private static final int CONFIRM = 11;
    private static final int CONFIRM_NOTE = 13;
    private static final int SILENT = 22;

    private ServerMock server;
    private MRPunish plugin;
    private PlayerMock mod;
    private PlayerMock steve;
    private PermissionAttachment modRank;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(MRPunish.class);
        mod = server.addPlayer("Mod");
        modRank = mod.addAttachment(plugin, "mrpunish.mod", true);
        steve = server.addPlayer("Steve");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private void until(BooleanSupplier done) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < end) {
            server.getScheduler().performOneTick();
            Thread.sleep(10);
        }
    }

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            server.getScheduler().performOneTick();
        }
    }

    private static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    private List<String> drain(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        Component c;
        while ((c = player.nextComponentMessage()) != null) {
            lines.add(plain(c));
        }
        return lines;
    }

    private List<Punishment> history(UUID uuid) throws Exception {
        return plugin.storage().executor()
                .submit("test", c -> new PunishmentRepo(plugin.storage().database()).ofTarget(c, uuid))
                .get(5, TimeUnit.SECONDS);
    }

    private static MenuHolder menu(PlayerMock player) {
        return MenuHolder.of(player.getOpenInventory().getTopInventory());
    }

    private static String menuName(PlayerMock player) {
        MenuHolder m = menu(player);
        return m == null ? "none" : m.getClass().getSimpleName();
    }

    private static ItemStack slot(PlayerMock player, int slot) {
        Inventory top = player.getOpenInventory().getTopInventory();
        return top == null || slot >= top.getSize() ? null : top.getItem(slot);
    }

    private static String name(ItemStack item) {
        return item == null || !item.hasItemMeta() ? "" : plain(item.getItemMeta().displayName());
    }

    private static String lore(ItemStack item) {
        if (item == null || !item.hasItemMeta() || item.getItemMeta().lore() == null) {
            return "";
        }
        return String.join("\n", item.getItemMeta().lore().stream().map(PluginMenuTest::plain).toList());
    }

    private InventoryClickEvent click(PlayerMock player, int rawSlot, ClickType type, int hotbar) {
        InventoryView view = player.getOpenInventory();
        InventoryType.SlotType slotType = rawSlot < 0 ? InventoryType.SlotType.OUTSIDE : InventoryType.SlotType.CONTAINER;
        InventoryClickEvent event = type == ClickType.NUMBER_KEY
                ? new InventoryClickEvent(view, slotType, rawSlot, type, InventoryAction.HOTBAR_SWAP, hotbar)
                : new InventoryClickEvent(view, slotType, rawSlot, type, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private void leftClick(PlayerMock player, int slot) {
        click(player, slot, ClickType.LEFT, -1);
        ticks(2);
    }

    private void openPunish(PlayerMock staff, String target) throws InterruptedException {
        new PunishMenuCommand(plugin).run(staff, target);
        until(() -> slot(staff, HEAD) != null && slot(staff, HEAD).getType() == Material.PLAYER_HEAD);
        assertEquals("MainMenu", menuName(staff));
    }

    private void waitForReasons(PlayerMock staff) throws InterruptedException {
        until(() -> lore(slot(staff, FIRST_REASON)).contains("Offence:"));
        assertTrue(lore(slot(staff, FIRST_REASON)).contains("Offence:"), lore(slot(staff, FIRST_REASON)));
    }

    private int displayItemsHeld(PlayerMock player) {
        Items items = new Items();
        int n = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (items.isDisplay(item)) {
                n++;
            }
        }
        return n + (items.isDisplay(player.getItemOnCursor()) ? 1 : 0);
    }

    @Test
    void mainMenuShowsTheHeadAndCategoriesByPermission() throws Exception {
        openPunish(mod, "Steve");
        ItemStack head = slot(mod, HEAD);
        assertEquals("Steve", name(head));
        String lore = lore(head);
        assertTrue(lore.contains(steve.getUniqueId().toString()), lore);
        assertTrue(lore.contains("Status: online"), lore);
        assertTrue(lore.contains("Rank: Player"), lore);
        assertTrue(lore.contains("Total punishments: 0"), lore);
        assertTrue(lore.contains("none"), lore);
        assertEquals(Material.PAPER, slot(mod, WARNINGS).getType());
        assertEquals("Warnings", name(slot(mod, WARNINGS)));
        assertEquals(Material.BOOK, slot(mod, MUTES).getType());
        // A moderator has no mrpunish.ip: GREY mode shows the no-permission item.
        assertEquals(Material.GRAY_DYE, slot(mod, IP_MUTES).getType());
        assertEquals("IP Mutes", name(slot(mod, IP_MUTES)));
        leftClick(mod, IP_MUTES);
        assertTrue(drain(mod).contains("MR-Punish » You don't have permission to do that."));
        assertEquals("MainMenu", menuName(mod));
        // Every item in the menu is marked as a display item.
        Items items = new Items();
        for (ItemStack item : mod.getOpenInventory().getTopInventory().getContents()) {
            assertTrue(items.isDisplay(item), String.valueOf(item));
        }
    }

    @Test
    void hideModeShowsFiller() throws Exception {
        File gui = new File(plugin.getDataFolder(), "gui.yml");
        String text = Files.readString(gui.toPath(), StandardCharsets.UTF_8);
        Files.writeString(gui.toPath(), text.replace("  mode: GREY", "  mode: HIDE"), StandardCharsets.UTF_8);
        plugin.reload(server.getConsoleSender());
        openPunish(mod, "Steve");
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, slot(mod, IP_MUTES).getType());
        leftClick(mod, IP_MUTES);
        assertEquals(List.of(), drain(mod));
    }

    @Test
    void everyDupeTrickInEveryMenuDoesNothing() throws Exception {
        mod.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        openPunish(mod, "Steve");
        tricks(mod);
        leftClick(mod, MUTES);
        assertEquals("ReasonMenu", menuName(mod));
        waitForReasons(mod);
        tricks(mod);
        leftClick(mod, FIRST_REASON);
        assertEquals("ConfirmMenu", menuName(mod));
        tricks(mod);
        mod.setGameMode(GameMode.CREATIVE);
        tricks(mod);
        assertEquals(new ItemStack(Material.DIAMOND, 3), mod.getInventory().getItem(0));
        assertEquals(0, displayItemsHeld(mod));
        assertTrue(history(steve.getUniqueId()).isEmpty(), "no trick confirmed anything");
    }

    /** Number keys (incl. F = offhand 40), Q, Ctrl-Q, double-click, middle, creative, drag: top and bottom. */
    private void tricks(PlayerMock staff) {
        MenuHolder before = menu(staff);
        int top = staff.getOpenInventory().getTopInventory().getSize();
        ItemStack[] shown = staff.getOpenInventory().getTopInventory().getContents().clone();
        ClickType[] types = {ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND, ClickType.DROP, ClickType.CONTROL_DROP,
            ClickType.DOUBLE_CLICK, ClickType.MIDDLE, ClickType.CREATIVE, ClickType.UNKNOWN,
            ClickType.WINDOW_BORDER_LEFT};
        for (int raw = 0; raw < top + 36; raw++) {
            for (ClickType type : types) {
                InventoryClickEvent event = click(staff, raw, type, type == ClickType.NUMBER_KEY ? 0 : -1);
                assertTrue(event.isCancelled(), type + " on " + raw);
            }
            if (raw >= top) {
                // Shift and plain clicks in the player's own inventory are cancelled as well.
                for (ClickType type : new ClickType[] {ClickType.LEFT, ClickType.SHIFT_LEFT, ClickType.RIGHT,
                        ClickType.SHIFT_RIGHT}) {
                    assertTrue(click(staff, raw, type, -1).isCancelled(), type + " on " + raw);
                }
            }
        }
        assertTrue(click(staff, -999, ClickType.LEFT, -1).isCancelled());
        InventoryDragEvent drag = new InventoryDragEvent(staff.getOpenInventory(), null,
                new ItemStack(Material.DIAMOND), false, java.util.Map.of(1, new ItemStack(Material.DIAMOND),
                        top + 1, new ItemStack(Material.DIAMOND)));
        server.getPluginManager().callEvent(drag);
        assertTrue(drag.isCancelled(), "drag");
        ticks(3);
        assertTrue(menu(staff) == before, "still the same menu");
        org.junit.jupiter.api.Assertions.assertArrayEquals(shown, staff.getOpenInventory().getTopInventory()
                .getContents(), "menu unchanged");
        assertEquals(0, displayItemsHeld(staff));
    }

    @Test
    void aWarningAppliesOnClick() throws Exception {
        openPunish(mod, "Steve");
        leftClick(mod, WARNINGS);
        assertEquals("ReasonMenu", menuName(mod));
        waitForReasons(mod);
        String lore = lore(slot(mod, FIRST_REASON));
        assertEquals("Spam", name(slot(mod, FIRST_REASON)));
        assertTrue(lore.contains("Offence: 1st"), lore);
        assertTrue(lore.contains("Previous: 0 in the last"), lore);
        assertTrue(lore.contains("Next punishment: Warning"), lore);
        assertTrue(lore.contains("Click to warn"), lore);
        drain(mod);
        leftClick(mod, FIRST_REASON);
        until(() -> !drain(mod).isEmpty() || "MainMenu".equals(menuName(mod)));
        until(() -> lore(slot(mod, HEAD)).contains("Total punishments: 1"));
        List<Punishment> history = history(steve.getUniqueId());
        assertEquals(1, history.size());
        assertEquals(PunishmentType.WARN, history.get(0).type());
        assertEquals("spam-warn", history.get(0).reasonKey());
        assertEquals(Punishment.Origin.GUI, history.get(0).origin());
        assertEquals("MainMenu", menuName(mod));
        assertTrue(lore(slot(mod, HEAD)).contains("Warning"), lore(slot(mod, HEAD)));
    }

    @Test
    void aMuteGoesThroughTheConfirmationWithTheLadderAndSilent() throws Exception {
        openPunish(mod, "Steve");
        leftClick(mod, MUTES);
        waitForReasons(mod);
        String lore = lore(slot(mod, FIRST_REASON));
        assertTrue(lore.contains("Next punishment: Mute for 10m"), lore);
        assertTrue(lore.contains("Escalation: 10m → 30m → 2h → 1d"), lore);
        assertTrue(lore.contains("Click to review"), lore);
        leftClick(mod, FIRST_REASON);
        assertEquals("ConfirmMenu", menuName(mod));
        assertTrue(history(steve.getUniqueId()).isEmpty(), "nothing before Confirm");
        String summary = lore(slot(mod, HEAD));
        assertTrue(summary.contains("Duration: 10m"), summary);
        assertEquals(Material.ENDER_PEARL, slot(mod, SILENT).getType());
        leftClick(mod, SILENT);
        assertEquals(Material.ENDER_EYE, slot(mod, SILENT).getType());
        drain(mod);
        leftClick(mod, CONFIRM);
        until(() -> !historySafe(steveSafe()).isEmpty());
        until(() -> menu(mod) == null);
        Punishment mute = history(steve.getUniqueId()).get(0);
        assertEquals(PunishmentType.MUTE, mute.type());
        assertTrue(mute.silent());
        assertEquals(Punishment.Origin.GUI, mute.origin());
        assertEquals(10 * 60_000L, mute.durationMs());
        assertNull(menu(mod), "closed after the commit");

        // The next mute for spam replaces this one; the reason item says so.
        openPunish(mod, "Steve");
        leftClick(mod, MUTES);
        waitForReasons(mod);
        lore = lore(slot(mod, FIRST_REASON));
        assertTrue(lore.contains("Offence: 2nd"), lore);
        assertTrue(lore.contains("Replaces #" + mute.id()), lore);
        assertTrue(lore.contains("Previous: 1 in the last"), lore);
    }

    private UUID steveSafe() {
        return steve.getUniqueId();
    }

    private List<Punishment> historySafe(UUID uuid) {
        try {
            return history(uuid);
        } catch (Exception e) {
            return List.of();
        }
    }

    @Test
    void aBlockedReasonSaysSoAndDoesNothing() throws Exception {
        PunishCommand.mute(plugin).run(server.getConsoleSender(), "Steve", "toxicity");
        until(() -> !historySafe(steve.getUniqueId()).isEmpty());
        long id = historySafe(steve.getUniqueId()).get(0).id();
        openPunish(mod, "Steve");
        leftClick(mod, MUTES);
        waitForReasons(mod);
        // spam's 10m would end before toxicity's 2h.
        String lore = lore(slot(mod, FIRST_REASON));
        assertTrue(lore.contains("Blocked: active Mute #" + id + " ends later"), lore);
        leftClick(mod, FIRST_REASON);
        assertEquals("ReasonMenu", menuName(mod));
        assertEquals(1, historySafe(steve.getUniqueId()).size());
    }

    @Test
    void aSecondStaffMemberConfirmingTheSamePreviewGetsChanged() throws Exception {
        PlayerMock other = server.addPlayer("OtherMod");
        other.addAttachment(plugin, "mrpunish.mod", true);
        PunishCommand.mute(plugin).run(mod, "Steve", "spam");
        PunishCommand.mute(plugin).run(other, "Steve", "spam");
        until(() -> "ConfirmMenu".equals(menuName(mod)) && "ConfirmMenu".equals(menuName(other)));
        leftClick(mod, CONFIRM);
        until(() -> historySafe(steve.getUniqueId()).size() == 1);
        until(() -> menu(mod) == null);
        drain(other);
        leftClick(other, CONFIRM);
        List<String> said = new ArrayList<>();
        until(() -> {
            said.addAll(drain(other));
            return !said.isEmpty();
        });
        assertTrue(said.contains("MR-Punish » Someone punished Steve while you were deciding. Check the new preview."),
                said.toString());
        until(() -> lore(slot(other, HEAD)).contains("Offence: 2nd"));
        assertEquals("ConfirmMenu", menuName(other));
        assertTrue(lore(slot(other, HEAD)).contains("Duration: 30m"), lore(slot(other, HEAD)));
        assertEquals(1, historySafe(steve.getUniqueId()).size(), "the second confirm wrote nothing");
    }

    @Test
    void permissionLostWhileTheMenuIsOpenIsRefusedAtTheClick() throws Exception {
        PunishCommand.mute(plugin).run(mod, "Steve", "spam");
        until(() -> "ConfirmMenu".equals(menuName(mod)));
        mod.removeAttachment(modRank);
        mod.recalculatePermissions();
        drain(mod);
        leftClick(mod, CONFIRM);
        ticks(5);
        assertTrue(drain(mod).contains("MR-Punish » You don't have permission to do that."));
        assertTrue(historySafe(steve.getUniqueId()).isEmpty());
    }

    @Test
    void rankIsCheckedWhenTheConfirmationWouldOpen() throws Exception {
        PlayerMock admin = server.addPlayer("Admin");
        admin.addAttachment(plugin, "mrpunish.admin", true);
        PunishCommand.mute(plugin).run(mod, "Admin", "spam");
        List<String> said = new ArrayList<>();
        until(() -> {
            said.addAll(drain(mod));
            return !said.isEmpty();
        });
        assertEquals(List.of("MR-Punish » You can't punish Admin: their rank is equal to or higher than yours."), said);
        assertNull(menu(mod));
    }

    @Test
    void aWarnOrMuteWithoutReasonOpensTheReasonMenuAndConsoleGetsUsage() throws Exception {
        PunishCommand.warn(plugin).run(mod, "Steve", "");
        until(() -> "ReasonMenu".equals(menuName(mod)));
        assertEquals("ReasonMenu", menuName(mod));
        PunishCommand.mute(plugin).run(mod, "Steve", "-s");
        until(() -> menu(mod) != null && lore(slot(mod, FIRST_REASON)).contains("Mute for"));
        assertTrue(lore(slot(mod, FIRST_REASON)).contains("Mute for 10m"));
        PunishCommand.warn(plugin).run(server.getConsoleSender(), "Steve", "");
        assertEquals("MR-Punish » Usage: /warn <player> [-s] <reason> [note...]",
                plain(((org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock) server.getConsoleSender())
                        .nextComponentMessage()));
    }

    @Test
    void aNoteTypedInChatIsAttachedEvenWhenTheStaffMemberIsMuted() throws Exception {
        PunishCommand.mute(plugin).run(server.getConsoleSender(), "Mod", "spam");
        until(() -> plugin.mutes().muteOf(mod).isPresent());
        openPunish(mod, "Steve");
        leftClick(mod, WARNINGS);
        waitForReasons(mod);
        drain(mod);
        leftClick(mod, REASON_NOTE);
        assertNull(menu(mod), "the menu closes for the prompt");
        assertTrue(drain(mod).stream().anyMatch(l -> l.startsWith("MR-Punish » Type a note for Steve in chat")));
        AsyncChatEvent chat = chat(mod, "  first offence, \u0007calm  ");
        assertTrue(chat.isCancelled(), "the note is never broadcast");
        until(() -> "ReasonMenu".equals(menuName(mod)));
        assertTrue(drain(mod).stream().noneMatch(l -> l.contains("muted")), "no mute notice for prompt input");
        waitForReasons(mod);
        assertTrue(lore(slot(mod, REASON_NOTE)).contains("first offence, calm"), lore(slot(mod, REASON_NOTE)));
        leftClick(mod, FIRST_REASON);
        until(() -> !historySafe(steve.getUniqueId()).isEmpty());
        assertEquals("first offence, calm", historySafe(steve.getUniqueId()).get(0).note());

        // Shift-click clears; the cancel word goes back unchanged.
        until(() -> "MainMenu".equals(menuName(mod)) && lore(slot(mod, HEAD)).contains("Total punishments: 1"));
        leftClick(mod, WARNINGS);
        waitForReasons(mod);
        leftClick(mod, REASON_NOTE);
        chat(mod, "temp");
        until(() -> "ReasonMenu".equals(menuName(mod)));
        waitForReasons(mod);
        click(mod, REASON_NOTE, ClickType.SHIFT_LEFT, -1);
        ticks(2);
        assertTrue(lore(slot(mod, REASON_NOTE)).contains("none"), lore(slot(mod, REASON_NOTE)));
        leftClick(mod, REASON_NOTE);
        drain(mod);
        chat(mod, "CANCEL");
        until(() -> "ReasonMenu".equals(menuName(mod)));
        assertTrue(drain(mod).contains("MR-Punish » Cancelled."));
        assertFalse(plugin.prompts().waiting(mod.getUniqueId()));
    }

    @Test
    void theConfirmationNoteAndAPromptDroppedOnQuit() throws Exception {
        PunishCommand.mute(plugin).run(mod, "Steve", "spam");
        until(() -> "ConfirmMenu".equals(menuName(mod)));
        leftClick(mod, CONFIRM_NOTE);
        assertTrue(plugin.prompts().waiting(mod.getUniqueId()));
        chat(mod, "typed in chat");
        until(() -> "ConfirmMenu".equals(menuName(mod)));
        assertTrue(lore(slot(mod, HEAD)).contains("Note: typed in chat"), lore(slot(mod, HEAD)));
        leftClick(mod, CONFIRM_NOTE);
        assertTrue(plugin.prompts().waiting(mod.getUniqueId()));
        mod.disconnect();
        ticks(2);
        assertFalse(plugin.prompts().waiting(mod.getUniqueId()));
    }

    @Test
    void theGuardRemovesMenuItemsThatGetOut() throws Exception {
        openPunish(mod, "Steve");
        ItemStack leaked = slot(mod, WARNINGS).clone();
        mod.closeInventory();
        mod.getInventory().setItem(5, leaked);
        mod.setItemOnCursor(leaked.clone());
        server.getPluginManager().callEvent(new InventoryCloseEvent(mod.getOpenInventory()));
        assertNull(mod.getInventory().getItem(5));
        assertEquals(0, displayItemsHeld(mod));
    }

    private AsyncChatEvent chat(PlayerMock player, String text) {
        AsyncChatEvent event = new AsyncChatEvent(true, player, new HashSet<>(), (source, n, message, viewer) -> message,
                Component.text(text), Component.text(text), null);
        // Async events can't be called from the test thread: the two LOWEST listeners, mute first
        // (it is called directly, so it doesn't skip the cancelled event by itself).
        plugin.mutes().onChat(event);
        assertFalse(event.isCancelled(), "mute enforcement leaves prompt input alone");
        plugin.prompts().onChat(event);
        ticks(2);
        return event;
    }

    @Test
    void consoleCantOpenTheMenu() {
        new PunishMenuCommand(plugin).run(server.getConsoleSender(), "Steve");
        assertEquals("MR-Punish » Only players can use this command.",
                plain(((org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock) server.getConsoleSender())
                        .nextComponentMessage()));
        assertNotNull(plugin.menus());
    }
}
