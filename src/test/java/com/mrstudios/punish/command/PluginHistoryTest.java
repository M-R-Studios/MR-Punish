package com.mrstudios.punish.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.gui.MenuHolder;
import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.Punishment.Origin;
import com.mrstudios.punish.punish.Punishment.StoredStatus;
import com.mrstudios.punish.punish.Punishment.WeightSource;
import com.mrstudios.punish.punish.PunishmentType;
import com.mrstudios.punish.storage.PunishmentRepo;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** /history, its menus and removal through the real plugin on MockBukkit (DESIGN.md 10.5, 7.6). */
class PluginHistoryTest {

    private static final int NEXT = 53;
    private static final int PREVIOUS = 45;
    private static final int TYPE_FILTER = 47;
    private static final int STATUS_FILTER = 48;
    private static final int BACK = 49;
    private static final int DETAIL_ENTRY = 13;
    private static final int DETAIL_REMOVE = 11;

    private ServerMock server;
    private MRPunish plugin;
    private PlayerMock admin;
    private PlayerMock steve;
    private PermissionAttachment adminRank;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(MRPunish.class);
        admin = server.addPlayer("Admin");
        adminRank = admin.addAttachment(plugin, "mrpunish.admin", true);
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

    private static List<String> drain(org.bukkit.command.CommandSender who) {
        List<String> lines = new ArrayList<>();
        Component c;
        while ((c = who instanceof PlayerMock p ? p.nextComponentMessage()
                : ((ConsoleCommandSenderMock) who).nextComponentMessage()) != null) {
            lines.add(plain(c));
        }
        return lines;
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
        return String.join("\n", item.getItemMeta().lore().stream().map(PluginHistoryTest::plain).toList());
    }

    private static Material type(PlayerMock player, int slot) {
        ItemStack item = slot(player, slot);
        return item == null ? Material.AIR : item.getType();
    }

    private void click(PlayerMock player, int slot, ClickType type) {
        server.getPluginManager().callEvent(new InventoryClickEvent(player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, slot, type, InventoryAction.PICKUP_ALL));
        ticks(2);
    }

    private void chat(PlayerMock player, String text) {
        AsyncChatEvent event = new AsyncChatEvent(true, player, new HashSet<>(), (source, n, message, viewer) -> message,
                Component.text(text), Component.text(text), null);
        plugin.prompts().onChat(event);
        assertTrue(event.isCancelled(), "prompt input is never broadcast");
        ticks(2);
    }

    /**
     * Clicks a button that opens another history page and waits until that new menu is showing (a
     * different holder). The plugin only opens a history menu once its page has loaded.
     */
    private void clickForNewPage(PlayerMock player, int slot) throws InterruptedException {
        MenuHolder before = menu(player);
        click(player, slot, ClickType.LEFT);
        until(() -> menu(player) != before && "HistoryMenu".equals(menuName(player)));
        assertTrue(menu(player) != before && "HistoryMenu".equals(menuName(player)), "a new page opened");
    }

    /** Inserts a punishment directly (fast setup for many entries). */
    private long insert(PunishmentType type, UUID target, Long expiresAt, StoredStatus status, int issuerWeight)
            throws Exception {
        long now = System.currentTimeMillis();
        Punishment p = new Punishment(0, type, target, "Steve", null, type == PunishmentType.WARN ? "spam-warn" : "spam",
                "Spam", null, 1, 1, type.hasDuration() && expiresAt != null ? 600_000L : null, now - 1000, expiresAt,
                null, issuerWeight > 0 ? "Owner" : "Console", issuerWeight, 0, WeightSource.LIVE, "default", false,
                Origin.COMMAND, status, null, null, null, null, null, null);
        return plugin.storage().executor().submit("test", c -> new PunishmentRepo(plugin.storage().database())
                .insert(c, p)).get(5, TimeUnit.SECONDS);
    }

    private Punishment byId(long id) throws Exception {
        return plugin.storage().executor().submit("test", c -> new PunishmentRepo(plugin.storage().database())
                .byId(c, id)).get(5, TimeUnit.SECONDS).orElseThrow();
    }

    private void openHistory(PlayerMock viewer, String target, int page) throws InterruptedException {
        new HistoryCommand(plugin).run(viewer, target, page);
        until(() -> "HistoryMenu".equals(menuName(viewer)));
        assertEquals("HistoryMenu", menuName(viewer));
    }

    @Test
    void pagesFiltersAndStatusColours() throws Exception {
        for (int i = 0; i < 50; i++) {
            insert(PunishmentType.WARN, steve.getUniqueId(), null, StoredStatus.ACTIVE, 0);
        }
        long expired = insert(PunishmentType.MUTE, steve.getUniqueId(), System.currentTimeMillis() - 1,
                StoredStatus.ACTIVE, 0);
        long replaced = insert(PunishmentType.BAN, steve.getUniqueId(), null, StoredStatus.REPLACED, 0);
        long active = insert(PunishmentType.MUTE, steve.getUniqueId(), System.currentTimeMillis() + 600_000,
                StoredStatus.ACTIVE, 0);
        openHistory(admin, "Steve", 1);
        String title = plain(admin.getOpenInventory().title());
        assertEquals("History – Steve (1/2)", title);
        assertEquals("#" + active + " Mute – Spam", name(slot(admin, 0)));
        assertEquals(Material.RED_CONCRETE, type(admin, 0));
        assertEquals(Material.YELLOW_CONCRETE, type(admin, 1), "replaced");
        assertEquals(Material.GRAY_CONCRETE, type(admin, 2), "expired");
        assertTrue(lore(slot(admin, 0)).contains("Status: Active"), lore(slot(admin, 0)));
        assertTrue(lore(slot(admin, 0)).contains("Right-click to remove"));
        assertTrue(lore(slot(admin, 2)).contains("Status: Expired"));
        assertFalse(lore(slot(admin, 2)).contains("Right-click to remove"));
        assertEquals("#" + replaced + " Ban – Spam", name(slot(admin, 1)));
        assertEquals(Material.ARROW, type(admin, NEXT));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, type(admin, PREVIOUS), "no previous on page 1");

        clickForNewPage(admin, NEXT);
        assertEquals("History – Steve (2/2)", plain(admin.getOpenInventory().title()));
        assertEquals(Material.RED_CONCRETE, type(admin, 7), "8 entries on page 2 (53 - 45)");
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, type(admin, 8));
        assertEquals(Material.ARROW, type(admin, PREVIOUS));

        // Type: All -> Warning (page back to 1).
        clickForNewPage(admin, TYPE_FILTER);
        assertEquals("Type: Warning", name(slot(admin, TYPE_FILTER)));
        assertEquals("History – Steve (1/2)", plain(admin.getOpenInventory().title()));
        assertTrue(name(slot(admin, 0)).contains("Warning"), name(slot(admin, 0)));
        // -> Mute: the active and the expired one.
        clickForNewPage(admin, TYPE_FILTER);
        assertEquals("Type: Mute", name(slot(admin, TYPE_FILTER)));
        assertEquals("#" + active + " Mute – Spam", name(slot(admin, 0)));
        assertEquals("#" + expired + " Mute – Spam", name(slot(admin, 1)));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, type(admin, 2));
        // Status: All -> Active -> Expired -> Removed.
        clickForNewPage(admin, STATUS_FILTER);
        assertEquals("Status: Active", name(slot(admin, STATUS_FILTER)));
        assertEquals("#" + active + " Mute – Spam", name(slot(admin, 0)));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, type(admin, 1));
        clickForNewPage(admin, STATUS_FILTER);
        assertEquals("Status: Expired", name(slot(admin, STATUS_FILTER)));
        assertEquals("#" + expired + " Mute – Spam", name(slot(admin, 0)));
        clickForNewPage(admin, STATUS_FILTER);
        assertEquals("Status: Removed", name(slot(admin, STATUS_FILTER)));
        assertEquals(Material.BARRIER, type(admin, 22));
        assertEquals("No punishments", name(slot(admin, 22)));
    }

    @Test
    void twoFilterClicksBeforeThePageArrivesShowOnlyTheNewestRequest() throws Exception {
        insert(PunishmentType.WARN, steve.getUniqueId(), null, StoredStatus.ACTIVE, 0);
        long active = insert(PunishmentType.MUTE, steve.getUniqueId(), System.currentTimeMillis() + 600_000,
                StoredStatus.ACTIVE, 0);
        insert(PunishmentType.MUTE, steve.getUniqueId(), null, StoredStatus.REMOVED, 0);
        openHistory(admin, "Steve", 1);
        MenuHolder before = menu(admin);
        // Both clicks land on the same (old) menu before either page has loaded.
        for (int slot : new int[] {TYPE_FILTER, STATUS_FILTER}) {
            server.getPluginManager().callEvent(new InventoryClickEvent(admin.getOpenInventory(),
                    InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        }
        until(() -> menu(admin) != before && "HistoryMenu".equals(menuName(admin)));
        ticks(20);
        MenuHolder shown = menu(admin);
        // The second click asked for "Type: All, Status: Active"; nothing from the first is shown.
        assertEquals("Type: All", name(slot(admin, TYPE_FILTER)));
        assertEquals("Status: Active", name(slot(admin, STATUS_FILTER)));
        assertEquals("#" + active + " Mute – Spam", name(slot(admin, 0)));
        assertTrue(name(slot(admin, 1)).contains("Warning"), name(slot(admin, 1)));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, type(admin, 2), "the removed mute is filtered out");
        assertTrue(menu(admin) == shown, "no later page replaced it");
    }

    @Test
    void rightClickRemovesWithATypedReasonAndKeepsTheRecord() throws Exception {
        long id = insert(PunishmentType.MUTE, steve.getUniqueId(), System.currentTimeMillis() + 600_000,
                StoredStatus.ACTIVE, 0);
        openHistory(admin, "Steve", 1);
        drain(admin);
        click(admin, 0, ClickType.RIGHT);
        assertNull(menu(admin), "the menu closes for the prompt");
        assertTrue(drain(admin).contains("MR-Punish » Type a removal reason for #" + id
                + " in chat (- for none), or cancel."));
        chat(admin, "appealed on discord");
        until(() -> "HistoryMenu".equals(menuName(admin)) && type(admin, 0) == Material.LIGHT_BLUE_CONCRETE);
        Punishment removed = byId(id);
        assertEquals(StoredStatus.REMOVED, removed.status());
        assertEquals("appealed on discord", removed.removedReason());
        assertEquals("Admin", removed.removedByName());
        assertTrue(lore(slot(admin, 0)).contains("Removed by Admin on "), lore(slot(admin, 0)));
        assertTrue(lore(slot(admin, 0)).contains(": appealed on discord"));
        assertTrue(drain(admin).stream().anyMatch(l -> l.startsWith("MR-Punish » Removed 1 mute(s) from Steve")));
        // A second removal of the same entry: it isn't active any more.
        click(admin, 0, ClickType.RIGHT);
        assertTrue(drain(admin).contains("MR-Punish » #" + id + " isn't active, so it can't be removed."));
        assertEquals("HistoryMenu", menuName(admin));
    }

    @Test
    void theDetailMenusRemoveButtonAndDashForNoReason() throws Exception {
        long id = insert(PunishmentType.BAN, steve.getUniqueId(), null, StoredStatus.ACTIVE, 0);
        openHistory(admin, "Steve", 1);
        click(admin, 0, ClickType.LEFT);
        assertEquals("DetailMenu", menuName(admin));
        assertEquals("#" + id + " – Steve", plain(admin.getOpenInventory().title()));
        assertEquals(Material.RED_CONCRETE, type(admin, DETAIL_ENTRY));
        assertEquals(Material.LAVA_BUCKET, type(admin, DETAIL_REMOVE));
        assertEquals("Remove this Ban", name(slot(admin, DETAIL_REMOVE)));
        click(admin, DETAIL_REMOVE, ClickType.LEFT);
        assertTrue(plugin.prompts().waiting(admin.getUniqueId()));
        chat(admin, "-");
        until(() -> "HistoryMenu".equals(menuName(admin)) && type(admin, 0) == Material.LIGHT_BLUE_CONCRETE);
        assertEquals("No reason given", byId(id).removedReason());
    }

    @Test
    void cancellingTheReasonGoesBackUnchanged() throws Exception {
        long id = insert(PunishmentType.BAN, steve.getUniqueId(), null, StoredStatus.ACTIVE, 0);
        openHistory(admin, "Steve", 1);
        click(admin, 0, ClickType.RIGHT);
        chat(admin, "cancel");
        until(() -> "HistoryMenu".equals(menuName(admin)) && type(admin, 0) == Material.RED_CONCRETE);
        assertEquals(StoredStatus.ACTIVE, byId(id).status());
    }

    @Test
    void replacedAndExpiredEntriesCantBeRemoved() throws Exception {
        long replaced = insert(PunishmentType.BAN, steve.getUniqueId(), null, StoredStatus.REPLACED, 0);
        openHistory(admin, "Steve", 1);
        drain(admin);
        click(admin, 0, ClickType.RIGHT);
        assertTrue(drain(admin).contains("MR-Punish » #" + replaced + " isn't active, so it can't be removed."));
        assertFalse(plugin.prompts().waiting(admin.getUniqueId()));
        click(admin, 0, ClickType.LEFT);
        assertEquals("DetailMenu", menuName(admin));
        assertEquals(Material.GRAY_DYE, type(admin, DETAIL_REMOVE));
        assertEquals("Can't be removed", name(slot(admin, DETAIL_REMOVE)));
        click(admin, DETAIL_REMOVE, ClickType.LEFT);
        assertFalse(plugin.prompts().waiting(admin.getUniqueId()));
        assertEquals(StoredStatus.REPLACED, byId(replaced).status());
    }

    @Test
    void anAdminCantRemoveAnOwnersBan() throws Exception {
        long id = insert(PunishmentType.BAN, steve.getUniqueId(), null, StoredStatus.ACTIVE, 40);
        openHistory(admin, "Steve", 1);
        drain(admin);
        click(admin, 0, ClickType.RIGHT);
        assertTrue(drain(admin).contains("MR-Punish » #" + id
                + " was issued by a higher rank than yours, so you can't remove it."));
        assertFalse(plugin.prompts().waiting(admin.getUniqueId()));
        assertEquals(StoredStatus.ACTIVE, byId(id).status());
    }

    @Test
    void permissionLostBeforeOrWhileTypingIsRefused() throws Exception {
        long id = insert(PunishmentType.MUTE, steve.getUniqueId(), null, StoredStatus.ACTIVE, 0);
        openHistory(admin, "Steve", 1);
        click(admin, 0, ClickType.RIGHT);
        assertTrue(plugin.prompts().waiting(admin.getUniqueId()));
        admin.removeAttachment(adminRank);
        admin.recalculatePermissions();
        drain(admin);
        chat(admin, "too late");
        ticks(5);
        assertTrue(drain(admin).contains("MR-Punish » You don't have permission to do that."));
        assertEquals(StoredStatus.ACTIVE, byId(id).status());

        adminRank = admin.addAttachment(plugin, "mrpunish.admin", true);
        openHistory(admin, "Steve", 1);
        admin.removeAttachment(adminRank);
        admin.recalculatePermissions();
        drain(admin);
        click(admin, 0, ClickType.RIGHT);
        assertTrue(drain(admin).contains("MR-Punish » You don't have permission to do that."));
        assertFalse(plugin.prompts().waiting(admin.getUniqueId()));
        assertEquals(StoredStatus.ACTIVE, byId(id).status());
    }

    @Test
    void aHelperSeesHistoryButNoRemoveButton() throws Exception {
        PlayerMock helper = server.addPlayer("Helper");
        helper.addAttachment(plugin, "mrpunish.helper", true);
        insert(PunishmentType.MUTE, steve.getUniqueId(), null, StoredStatus.ACTIVE, 0);
        openHistory(helper, "Steve", 1);
        assertTrue(lore(slot(helper, 0)).contains("Left-click for details"));
        assertFalse(lore(slot(helper, 0)).contains("Right-click to remove"));
        click(helper, 0, ClickType.LEFT);
        assertEquals(Material.GRAY_DYE, type(helper, DETAIL_REMOVE), "GREY no-permission item");
        click(helper, DETAIL_REMOVE, ClickType.LEFT);
        assertFalse(plugin.prompts().waiting(helper.getUniqueId()));
    }

    @Test
    void consoleGetsTenLinesAPage() throws Exception {
        ConsoleCommandSenderMock console = (ConsoleCommandSenderMock) server.getConsoleSender();
        for (int i = 0; i < 12; i++) {
            insert(PunishmentType.WARN, steve.getUniqueId(), null, StoredStatus.ACTIVE, 0);
        }
        long removedMute = insert(PunishmentType.MUTE, steve.getUniqueId(), null, StoredStatus.REMOVED, 0);
        HistoryCommand history = new HistoryCommand(plugin);
        history.run(console, "Steve", 1);
        List<String> lines = new ArrayList<>();
        until(() -> {
            lines.addAll(drain(console));
            return lines.size() >= 11;
        });
        assertEquals("MR-Punish » Steve: 13 punishment(s), page 1/2", lines.get(0));
        assertEquals(11, lines.size());
        assertTrue(lines.get(1).startsWith("#" + removedMute + " mute Spam "), lines.get(1));
        assertTrue(lines.get(1).endsWith("by Console Removed"), lines.get(1));
        lines.clear();
        history.run(console, "Steve", 9);
        until(() -> {
            lines.addAll(drain(console));
            return lines.size() >= 4;
        });
        assertEquals("MR-Punish » Steve: 13 punishment(s), page 2/2", lines.get(0), "clamped to the last page");
        assertEquals(4, lines.size());
        lines.clear();
        history.run(console, "Admin", 1);
        until(() -> {
            lines.addAll(drain(console));
            return !lines.isEmpty();
        });
        assertEquals(List.of("MR-Punish » Admin has no punishments."), lines);
    }

    @Test
    void theMainMenusHistoryButtonAndBack() throws Exception {
        insert(PunishmentType.WARN, steve.getUniqueId(), null, StoredStatus.ACTIVE, 0);
        new PunishMenuCommand(plugin).run(admin, "Steve");
        until(() -> lore(slot(admin, 25)).contains("Punishments: 1"));
        assertEquals(Material.BOOKSHELF, type(admin, 25));
        click(admin, 25, ClickType.LEFT);
        until(() -> "HistoryMenu".equals(menuName(admin)) && type(admin, 0) == Material.RED_CONCRETE);
        assertEquals(Material.ARROW, type(admin, BACK));
        click(admin, BACK, ClickType.LEFT);
        assertEquals("MainMenu", menuName(admin));
        // Without mrpunish.history the button is greyed.
        PlayerMock nobody = server.addPlayer("Muter");
        nobody.addAttachment(plugin, "mrpunish.punish", true);
        nobody.addAttachment(plugin, "mrpunish.mute", true);
        new PunishMenuCommand(plugin).run(nobody, "Steve");
        until(() -> "MainMenu".equals(menuName(nobody)) && type(nobody, 4) == Material.PLAYER_HEAD);
        assertEquals(Material.GRAY_DYE, type(nobody, 25));
    }
}
