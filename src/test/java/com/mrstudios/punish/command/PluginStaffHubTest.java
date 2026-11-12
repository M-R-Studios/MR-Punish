package com.mrstudios.punish.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.gui.MenuHolder;
import com.mrstudios.punish.staff.StaffLogFile;
import com.mrstudios.punish.storage.ActionRepo;
import com.mrstudios.punish.storage.FrozenRepo;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
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

/**
 * The /staff hub, its menus, /stafflog, the staff profile and the staff log file through the real
 * plugin on MockBukkit (DESIGN.md 11.7, 13.5, 13.6).
 */
class PluginStaffHubTest {

    private static final int HEAD = 4;
    private static final int REPORTS = 10;
    private static final int ONLINE_STAFF = 11;
    private static final int STAFF_CHAT = 12;
    private static final int STAFF_MODE = 13;
    private static final int VANISH = 14;
    private static final int FREEZE_TOOLS = 15;
    private static final int SERVER_INFO = 16;
    private static final int INVSEE = 20;
    private static final int ENDERSEE = 21;
    private static final int HISTORY = 22;
    private static final int STAFF_LOG = 23;
    private static final int PUNISH = 24;
    private static final int CLOSE = 40;

    private ServerMock server;
    private MRPunish plugin;
    private ConsoleCommandSenderMock console;
    private PlayerMock admin;
    private PlayerMock mod;
    private PlayerMock helper;
    private PlayerMock steve;
    private PermissionAttachment modRank;
    private StaffHubCommands hub;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PluginMuteTest.WithAliases.class);
        console = (ConsoleCommandSenderMock) server.getConsoleSender();
        admin = server.addPlayer("Admin");
        admin.addAttachment(plugin, "mrpunish.admin", true);
        mod = server.addPlayer("Mod");
        modRank = mod.addAttachment(plugin, "mrpunish.mod", true);
        helper = server.addPlayer("Helper");
        helper.addAttachment(plugin, "mrpunish.helper", true);
        steve = server.addPlayer("Steve");
        // The test plugin subclass doesn't get plugin.yml's defaults.
        steve.addAttachment(plugin, "mrpunish.report", true);
        helper.addAttachment(plugin, "mrpunish.report", true);
        hub = new StaffHubCommands(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ---- Helpers ----

    private void ticks(int n) {
        for (int i = 0; i < n; i++) {
            server.getScheduler().performOneTick();
        }
    }

    private void until(BooleanSupplier done) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < end) {
            server.getScheduler().performOneTick();
            Thread.sleep(10);
        }
    }

    private static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static List<String> drain(org.bukkit.command.CommandSender p) {
        List<String> lines = new ArrayList<>();
        Component c;
        while ((c = p instanceof PlayerMock pm ? pm.nextComponentMessage()
                : ((ConsoleCommandSenderMock) p).nextComponentMessage()) != null) {
            lines.add(plain(c));
        }
        return lines;
    }

    private List<String> heard(org.bukkit.command.CommandSender p, String text) throws InterruptedException {
        List<String> got = new ArrayList<>();
        until(() -> {
            got.addAll(drain(p));
            return got.stream().anyMatch(l -> l.contains(text));
        });
        return got;
    }

    private static String menu(PlayerMock p) {
        MenuHolder m = MenuHolder.of(p.getOpenInventory().getTopInventory());
        return m == null ? "none" : m.getClass().getName().replaceAll(".*\\.", "");
    }

    private static ItemStack slot(PlayerMock p, int slot) {
        Inventory top = p.getOpenInventory().getTopInventory();
        return top == null || slot >= top.getSize() ? null : top.getItem(slot);
    }

    private static Material type(PlayerMock p, int slot) {
        ItemStack item = slot(p, slot);
        return item == null ? Material.AIR : item.getType();
    }

    private static String name(ItemStack item) {
        return item == null || !item.hasItemMeta() ? "" : plain(item.getItemMeta().displayName());
    }

    private static String lore(ItemStack item) {
        if (item == null || !item.hasItemMeta() || item.getItemMeta().lore() == null) {
            return "";
        }
        return String.join("\n", item.getItemMeta().lore().stream().map(PluginStaffHubTest::plain).toList());
    }

    private void click(PlayerMock p, int slot, ClickType type) {
        server.getPluginManager().callEvent(new InventoryClickEvent(p.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, slot, type, type.isShiftClick()
                        ? InventoryAction.MOVE_TO_OTHER_INVENTORY : InventoryAction.PICKUP_ALL));
        ticks(2);
    }

    private void click(PlayerMock p, int slot) {
        click(p, slot, ClickType.LEFT);
    }

    private void chat(PlayerMock p, String text) {
        AsyncChatEvent event = new AsyncChatEvent(true, p, new HashSet<>(), (source, n, message, viewer) -> message,
                Component.text(text), Component.text(text), null);
        plugin.prompts().onChat(event);
        assertTrue(event.isCancelled());
        ticks(2);
    }

    private void openHub(PlayerMock p) throws InterruptedException {
        hub.hub(p);
        until(() -> menu(p).equals("StaffMenus$Hub") && !lore(slot(p, REPORTS)).contains("?"));
        assertEquals("StaffMenus$Hub", menu(p));
    }

    /** The slot holding {@code who}'s head in the menu {@code p} has open, or -1. */
    private static int headOf(PlayerMock p, String who) {
        for (int i = 0; i < 45; i++) {
            if (type(p, i) == Material.PLAYER_HEAD && name(slot(p, i)).contains(who)) {
                return i;
            }
        }
        return -1;
    }

    private List<ActionRepo.Logged> actions(String action) throws Exception {
        return plugin.storage().executor().submit("test", c -> new ActionRepo(plugin.storage().database())
                .page(c, action == null ? List.of() : List.of(action), null, 0, 500)).get(5, TimeUnit.SECONDS);
    }

    private int count(String action) {
        try {
            return actions(action).size();
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean frozenInDb(PlayerMock p) {
        try {
            return plugin.storage().executor().submit("test", c -> new FrozenRepo(plugin.storage().database())
                    .get(c, p.getUniqueId())).get(5, TimeUnit.SECONDS).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    private void setConfig(String from, String to) throws Exception {
        File config = new File(plugin.getDataFolder(), "config.yml");
        String text = Files.readString(config.toPath(), StandardCharsets.UTF_8);
        assertTrue(text.contains(from), from);
        Files.writeString(config.toPath(), text.replace(from, to), StandardCharsets.UTF_8);
        plugin.reload(console);
        drain(console);
    }

    // ---- The hub ----

    @Test
    void everyButtonIsThereAndWhatYouCantUseIsGrey() throws Exception {
        openHub(admin);
        assertEquals(Material.PLAYER_HEAD, type(admin, HEAD));
        assertTrue(lore(slot(admin, HEAD)).contains("Rank: Administrator"), lore(slot(admin, HEAD)));
        assertTrue(lore(slot(admin, HEAD)).contains("Staff mode: off"));
        List<Material> expected = List.of(Material.WRITABLE_BOOK, Material.PLAYER_HEAD, Material.PAPER, Material.SHIELD,
                Material.ENDER_PEARL, Material.PACKED_ICE, Material.COMPARATOR, Material.CHEST, Material.ENDER_CHEST,
                Material.BOOK, Material.CLOCK, Material.IRON_AXE, Material.BARRIER);
        List<Integer> slots = List.of(REPORTS, ONLINE_STAFF, STAFF_CHAT, STAFF_MODE, VANISH, FREEZE_TOOLS, SERVER_INFO,
                INVSEE, ENDERSEE, HISTORY, STAFF_LOG, PUNISH, CLOSE);
        for (int i = 0; i < slots.size(); i++) {
            assertEquals(expected.get(i), type(admin, slots.get(i)), "slot " + slots.get(i));
        }

        // A helper has no freeze, invsee, endersee or logs: those are greyed and refuse.
        openHub(helper);
        for (int greyed : List.of(FREEZE_TOOLS, INVSEE, ENDERSEE, STAFF_LOG)) {
            assertEquals(Material.GRAY_DYE, type(helper, greyed), "slot " + greyed);
        }
        assertEquals(Material.WRITABLE_BOOK, type(helper, REPORTS));
        drain(helper);
        click(helper, STAFF_LOG);
        assertTrue(drain(helper).stream().anyMatch(l -> l.contains("permission")));
        assertEquals("StaffMenus$Hub", menu(helper));

        // Not staff at all: no hub.
        hub.hub(steve);
        assertEquals("none", menu(steve));

        click(admin, CLOSE);
        assertEquals("none", menu(admin));
    }

    @Test
    void hideModeHidesButtonsYouCantUse() throws Exception {
        File gui = new File(plugin.getDataFolder(), "gui.yml");
        String text = Files.readString(gui.toPath(), StandardCharsets.UTF_8);
        Files.writeString(gui.toPath(), text.replace("  mode: GREY", "  mode: HIDE"), StandardCharsets.UTF_8);
        plugin.reload(console);
        openHub(helper);
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, type(helper, FREEZE_TOOLS));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, type(helper, STAFF_LOG));
        assertEquals(Material.BOOK, type(helper, HISTORY));
    }

    @Test
    void permissionIsCheckedAgainAtTheClick() throws Exception {
        openHub(mod);
        assertEquals(Material.PACKED_ICE, type(mod, FREEZE_TOOLS));
        mod.removeAttachment(modRank);
        mod.addAttachment(plugin, "mrpunish.helper", true);
        drain(mod);
        click(mod, FREEZE_TOOLS);
        assertTrue(drain(mod).stream().anyMatch(l -> l.contains("permission")));
        assertEquals("StaffMenus$Hub", menu(mod));
        assertEquals(Material.GRAY_DYE, type(mod, FREEZE_TOOLS), "redrawn without the button");
    }

    @Test
    void togglesStaffChatVanishAndStaffMode() throws Exception {
        openHub(mod);
        assertTrue(lore(slot(mod, STAFF_CHAT)).contains("off"));
        click(mod, STAFF_CHAT);
        assertTrue(plugin.staffChat().toggledOn(mod.getUniqueId()));
        assertTrue(lore(slot(mod, STAFF_CHAT)).contains("on"));
        assertTrue(lore(slot(mod, HEAD)).contains("Staff chat: on"));

        click(mod, VANISH);
        assertTrue(plugin.vanish().isVanished(mod.getUniqueId()));
        assertTrue(lore(slot(mod, VANISH)).contains("Vanished: on"));
        until(() -> count("VANISH_ON") == 1);
        assertEquals(1, count("VANISH_ON"));
        click(mod, VANISH);
        assertFalse(plugin.vanish().isVanished(mod.getUniqueId()));
        until(() -> count("VANISH_OFF") == 1);

        click(mod, STAFF_MODE);
        assertTrue(plugin.staffMode().inStaffMode(mod.getUniqueId()));
        assertEquals("none", menu(mod), "closed first");
        openHub(mod);
        assertTrue(lore(slot(mod, STAFF_MODE)).contains("on"));
        click(mod, STAFF_MODE);
        assertFalse(plugin.staffMode().inStaffMode(mod.getUniqueId()));
    }

    @Test
    void reportsButtonShowsCountsAndOpensTheList() throws Exception {
        new ReportCommand(plugin).run(steve, "Mod", "spam");
        until(() -> {
            try {
                return plugin.storage().executor().submit("t", c -> new com.mrstudios.punish.storage.ReportRepo(
                        plugin.storage().database()).countOpen(c)).get() == 1;
            } catch (Exception e) {
                return false;
            }
        });
        openHub(admin);
        assertTrue(lore(slot(admin, REPORTS)).contains("Open: 1"), lore(slot(admin, REPORTS)));
        assertTrue(lore(slot(admin, REPORTS)).contains("Claimed: 0"));
        click(admin, REPORTS);
        until(() -> menu(admin).equals("ReportMenus$ListMenu"));
        assertEquals("ReportMenus$ListMenu", menu(admin));
    }

    @Test
    void serverInformationShowsTheNumbersAndRefreshes() throws Exception {
        PunishCommand.ban(plugin).run(console, "Steve", "cheating");
        until(() -> count("PUNISH") == 1);
        openHub(admin);
        String info = lore(slot(admin, SERVER_INFO));
        assertTrue(info.contains("TPS: 20.0, 19.5, 19.8"), info);
        assertTrue(info.contains("MSPT: 3.5"), info);
        assertTrue(info.contains("Players: 3 / "), info); // Steve was banned
        assertTrue(info.contains("Staff online: 3"), info);
        assertTrue(info.contains("Bans in force: 1"), info);
        assertTrue(info.contains("Mutes in force: 0"), info);
        assertTrue(info.contains("Storage: SQLite"), info);
        assertFalse(info.matches("(?s).*\\d+\\.\\d+\\.\\d+\\.\\d+.*"), "no IP: " + info);
        PunishCommand.mute(plugin).run(console, "Helper", "spam");
        until(() -> count("PUNISH") == 2);
        click(admin, SERVER_INFO);
        until(() -> lore(slot(admin, SERVER_INFO)).contains("Mutes in force: 1"));
        assertTrue(lore(slot(admin, SERVER_INFO)).contains("Mutes in force: 1"));
    }

    // ---- Pickers ----

    @Test
    void pickersListVisiblePlayersButNotYouAndRunTheCommand() throws Exception {
        openHub(helper);
        click(helper, HISTORY);
        assertEquals("StaffMenus$Picker", menu(helper));
        assertEquals(-1, headOf(helper, "Helper"), "not yourself");
        assertTrue(headOf(helper, "Steve") >= 0);
        click(helper, headOf(helper, "Steve"));
        until(() -> menu(helper).equals("HistoryMenus$HistoryMenu"));
        assertEquals("HistoryMenus$HistoryMenu", menu(helper));
        // Back goes to the hub.
        click(helper, 49);
        until(() -> menu(helper).equals("StaffMenus$Hub"));
        assertEquals("StaffMenus$Hub", menu(helper));

        openHub(mod);
        click(mod, INVSEE);
        click(mod, headOf(mod, "Steve"));
        assertEquals("InspectMenus$InventoryView", menu(mod));
        openHub(mod);
        click(mod, ENDERSEE);
        click(mod, headOf(mod, "Steve"));
        assertEquals("InspectMenus$EnderView", menu(mod));
        openHub(mod);
        click(mod, PUNISH);
        click(mod, headOf(mod, "Steve"));
        until(() -> menu(mod).equals("PunishMenus$MainMenu"));
        assertEquals("PunishMenus$MainMenu", menu(mod));
    }

    @Test
    void searchByNameFindsOfflinePlayers() throws Exception {
        PlayerMock gone = server.addPlayer("Gone");
        gone.disconnect();
        until(() -> {
            try {
                return plugin.targets().resolve("Gone").get(1, TimeUnit.SECONDS).outcome().name().equals("FOUND");
            } catch (Exception e) {
                return false;
            }
        });
        openHub(mod);
        click(mod, HISTORY);
        click(mod, 47);
        assertEquals("none", menu(mod), "closed for the prompt");
        chat(mod, "Gone");
        until(() -> menu(mod).equals("HistoryMenus$HistoryMenu"));
        assertEquals("HistoryMenus$HistoryMenu", menu(mod));

        openHub(mod);
        click(mod, PUNISH);
        click(mod, 47);
        chat(mod, "Gone");
        until(() -> menu(mod).equals("PunishMenus$MainMenu"));
        assertEquals("PunishMenus$MainMenu", menu(mod));

        openHub(mod);
        click(mod, HISTORY);
        click(mod, 47);
        drain(mod);
        chat(mod, "NeverJoined");
        assertTrue(heard(mod, "NeverJoined").stream().anyMatch(l -> l.contains("NeverJoined")));
    }

    // ---- Online Staff ----

    @Test
    void onlineStaffShowsStaffAndAdminsCanOpenProfilesAndForceExit() throws Exception {
        plugin.staffMode().enter(mod);
        openHub(helper);
        click(helper, ONLINE_STAFF);
        assertEquals("StaffMenus$OnlineStaff", menu(helper));
        assertEquals(-1, headOf(helper, "Steve"), "not staff");
        assertEquals(-1, headOf(helper, "Mod"), "vanished in staff mode, and above a helper");
        int adminHead = headOf(helper, "Admin");
        assertTrue(adminHead >= 0);
        assertTrue(lore(slot(helper, adminHead)).contains("Staff mode: off"));
        assertFalse(lore(slot(helper, adminHead)).contains("Shift-click"), "not an admin");
        plugin.staffMode().enter(admin);
        click(helper, adminHead, ClickType.SHIFT_LEFT);
        assertEquals("StaffMenus$OnlineStaff", menu(helper));
        assertTrue(plugin.staffMode().inStaffMode(admin.getUniqueId()), "helpers can't force exit");
        plugin.staffMode().exit(admin, com.mrstudios.punish.staff.StaffModeService.Cause.COMMAND);

        openHub(admin);
        click(admin, ONLINE_STAFF);
        int modHead = headOf(admin, "Mod");
        assertTrue(lore(slot(admin, modHead)).contains("Staff mode: on"));
        assertTrue(lore(slot(admin, modHead)).contains("Vanished: on"));
        assertTrue(lore(slot(admin, modHead)).contains("Shift-click"));
        assertEquals(0, headOf(admin, "Admin"), "highest rank first");
        drain(admin);
        click(admin, modHead);
        assertEquals("none", menu(admin));
        List<String> profile = heard(admin, "Reports handled");
        assertTrue(profile.stream().anyMatch(l -> l.contains("Mod") && l.contains("Moderator")), profile.toString());

        openHub(admin);
        click(admin, ONLINE_STAFF);
        click(admin, headOf(admin, "Mod"), ClickType.SHIFT_LEFT);
        assertEquals("StaffMenus$Confirm", menu(admin));
        click(admin, 15);
        assertEquals("StaffMenus$OnlineStaff", menu(admin), "cancel goes back");
        assertTrue(plugin.staffMode().inStaffMode(mod.getUniqueId()));
        click(admin, headOf(admin, "Mod"), ClickType.SHIFT_LEFT);
        click(admin, 11);
        assertFalse(plugin.staffMode().inStaffMode(mod.getUniqueId()));
        until(() -> count("FORCE_EXIT") == 1);
        assertEquals(1, count("FORCE_EXIT"));
    }

    // ---- Freeze Tools ----

    @Test
    void freezeToolsListsFrozenPlayersAndFreezesAndUnfreezes() throws Exception {
        openHub(mod);
        click(mod, FREEZE_TOOLS);
        until(() -> menu(mod).equals("StaffMenus$Frozen"));
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, type(mod, 22), "nobody frozen");
        click(mod, 47);
        assertEquals("StaffMenus$Picker", menu(mod));
        click(mod, headOf(mod, "Steve"));
        until(() -> plugin.freeze().isFrozen(steve.getUniqueId()));
        assertTrue(plugin.freeze().isFrozen(steve.getUniqueId()));

        // Offline too, from the database.
        new FreezeCommands(plugin).freeze(mod, "Helper");
        until(() -> plugin.freeze().isFrozen(helper.getUniqueId()));
        helper.disconnect();
        ticks(5);
        openHub(mod);
        click(mod, FREEZE_TOOLS);
        until(() -> menu(mod).equals("StaffMenus$Frozen"));
        int helperHead = headOf(mod, "Helper");
        int steveHead = headOf(mod, "Steve");
        assertTrue(helperHead >= 0 && steveHead >= 0);
        assertTrue(lore(slot(mod, helperHead)).contains("offline"), lore(slot(mod, helperHead)));
        assertTrue(lore(slot(mod, steveHead)).contains("Frozen by: Mod"));
        assertTrue(lore(slot(mod, steveHead)).contains("online"));

        click(mod, helperHead);
        assertEquals("StaffMenus$Confirm", menu(mod));
        click(mod, 11);
        until(() -> !frozenInDb(helper));
        assertFalse(frozenInDb(helper));
        until(() -> menu(mod).equals("StaffMenus$Frozen") && headOf(mod, "Helper") < 0);
        assertEquals(-1, headOf(mod, "Helper"));
        assertTrue(frozenInDb(steve));
    }

    // ---- The staff log ----

    @Test
    void staffLogShowsActionsFiltersAndOpensThePunishment() throws Exception {
        PunishCommand.warn(plugin).run(admin, "Steve", "spam");
        until(() -> count("PUNISH") == 1);
        new FreezeCommands(plugin).freeze(admin, "Steve");
        until(() -> count("FREEZE") == 1);
        long reportId = 0;
        new ReportCommand(plugin).run(steve, "Mod", "toxicity");
        until(() -> count("REPORT_CREATE") == 1);
        reportId = actions("REPORT_CREATE").get(0).action().refId();

        hub.log(admin, null, 1);
        until(() -> menu(admin).equals("StaffLogMenus$LogMenu"));
        assertEquals(Material.ORANGE_WOOL, type(admin, 0), "newest first: the report");
        assertEquals(Material.LIME_WOOL, type(admin, 1), "the freeze");
        assertEquals(Material.RED_WOOL, type(admin, 2), "the warning");
        assertTrue(lore(slot(admin, 2)).contains("By: Admin"));
        assertTrue(lore(slot(admin, 2)).contains("Target: Steve"));
        assertTrue(lore(slot(admin, 2)).contains("Click to open this punishment"));

        // Filter: punishments only.
        click(admin, 47);
        until(() -> type(admin, 1) != Material.LIME_WOOL);
        assertEquals(Material.RED_WOOL, type(admin, 0));
        assertTrue(name(slot(admin, 47)).contains("Punishments"));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, type(admin, 1));

        // The entry opens the warning in Steve's history.
        click(admin, 0);
        until(() -> menu(admin).equals("HistoryMenus$DetailMenu"));
        assertEquals("HistoryMenus$DetailMenu", menu(admin));

        // A report entry opens the report.
        hub.log(admin, null, 1);
        until(() -> menu(admin).equals("StaffLogMenus$LogMenu") && type(admin, 0) == Material.ORANGE_WOOL);
        click(admin, 0);
        until(() -> menu(admin).equals("ReportMenus$DetailMenu"));
        assertEquals("ReportMenus$DetailMenu", menu(admin));
        assertTrue(reportId > 0);

        // Only one staff member, and back to everyone.
        hub.log(admin, "Steve", 1);
        until(() -> menu(admin).equals("StaffLogMenus$LogMenu") && type(admin, 0) == Material.ORANGE_WOOL);
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, type(admin, 1), "only Steve's report");
        assertTrue(name(slot(admin, 51)).contains("Only Steve"));
        click(admin, 51);
        until(() -> type(admin, 1) == Material.LIME_WOOL);
        assertEquals(Material.LIME_WOOL, type(admin, 1));

        // Mods have no mrpunish.logs.
        drain(mod);
        hub.log(mod, null, 1);
        assertTrue(drain(mod).stream().anyMatch(l -> l.contains("permission")));
        assertEquals("none", menu(mod));
    }

    @Test
    void consoleGetsTextAndEveryKindOfActionIsLogged() throws Exception {
        plugin.vanish().vanish(admin);
        new StaffCommands(plugin).vanishSelf(admin);
        plugin.reload(console);
        plugin.forceExit(console, "Mod");
        until(() -> count("RELOAD") == 1 && count("VANISH_OFF") == 1);
        drain(console);
        hub.log(console, null, 1);
        List<String> lines = heard(console, "RELOAD");
        assertTrue(lines.get(0).contains("Staff log"), lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("Console RELOAD -")),
                lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("Admin") && l.contains("VANISH_OFF")), lines.toString());
    }

    @Test
    void historyViewsAreLoggedOnlyWhenTurnedOn() throws Exception {
        new HistoryCommand(plugin).run(admin, "Steve", 1);
        until(() -> menu(admin).equals("HistoryMenus$HistoryMenu"));
        ticks(10);
        assertEquals(0, count("INSPECT_HISTORY"));

        setConfig("  log-history-views: false", "  log-history-views: true");
        new HistoryCommand(plugin).run(admin, "Steve", 1);
        until(() -> count("INSPECT_HISTORY") == 1);
        // Paging and filters in the same view aren't new views.
        click(admin, 47);
        ticks(10);
        assertEquals(1, count("INSPECT_HISTORY"));
        // Your own history isn't an inspection.
        new HistoryCommand(plugin).run(admin, "Admin", 1);
        ticks(10);
        assertEquals(1, count("INSPECT_HISTORY"));
    }

    @Test
    void theFileLogWritesCommittedActionsWithoutIps() throws Exception {
        Path dir = Files.createTempDirectory("mrpunish-log");
        StaffLogFile file = new StaffLogFile(dir, plugin.storage(), plugin::state, plugin.getLogger());
        file.poll();
        Thread.sleep(300);
        PunishCommand.warn(plugin).run(admin, "Steve", "spam from 10.1.2.3");
        until(() -> count("PUNISH") == 1);
        file.poll();
        Thread.sleep(300);
        file.close();
        Path logs = dir.resolve("logs");
        List<Path> files;
        try (var list = Files.list(logs)) {
            files = list.toList();
        }
        assertEquals(1, files.size());
        assertTrue(files.get(0).getFileName().toString().matches("staff-\\d{4}-\\d{2}-\\d{2}\\.log"));
        String text = Files.readString(files.get(0), StandardCharsets.UTF_8);
        assertTrue(text.contains("] Admin PUNISH Steve #"), text);
        assertFalse(text.contains("10.1.2.3"), text);
    }

    // ---- Profile, hub tool, punish head ----

    @Test
    void staffProfileIsForAdmins() throws Exception {
        PunishCommand.warn(plugin).run(mod, "Steve", "spam");
        until(() -> count("PUNISH") == 1);
        AdminCommand admin = new AdminCommand(plugin);
        drain(this.admin);
        admin.dispatch(this.admin, "staff Mod");
        List<String> lines = heard(this.admin, "Last actions");
        assertTrue(lines.stream().anyMatch(l -> l.contains("Punishments (30 days / total): 1 / 1")
                && l.contains("warn 1")), lines.toString());
        until(() -> {
            lines.addAll(drain(this.admin));
            return lines.stream().anyMatch(l -> l.contains("PUNISH") && l.contains("Steve"));
        });
        assertTrue(lines.stream().anyMatch(l -> l.contains("PUNISH") && l.contains("Steve")), lines.toString());
        assertFalse(lines.stream().anyMatch(l -> l.contains("[end")), "not in staff mode: no button");

        plugin.staffMode().enter(mod);
        admin.dispatch(this.admin, "staff Mod");
        assertTrue(heard(this.admin, "staff mode and vanish]").stream().anyMatch(l -> l.contains("[end Mod's")));

        drain(mod);
        admin.dispatch(mod, "staff Admin");
        assertTrue(drain(mod).stream().anyMatch(l -> l.contains("permission")));
    }

    @Test
    void theStaffModeHubToolOpensTheHub() throws Exception {
        plugin.staffMode().enter(mod);
        ItemStack star = mod.getInventory().getItem(6);
        assertEquals(Material.NETHER_STAR, star.getType());
        mod.getInventory().setHeldItemSlot(6);
        PlayerInteractEvent use = new PlayerInteractEvent(mod, Action.RIGHT_CLICK_AIR, star, null,
                org.bukkit.block.BlockFace.SELF, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(use);
        until(() -> menu(mod).equals("StaffMenus$Hub"));
        assertEquals("StaffMenus$Hub", menu(mod));
    }

    @Test
    void thePunishHeadShowsReportsAgainstThePlayer() throws Exception {
        new ReportCommand(plugin).run(helper, "Steve", "spam");
        until(() -> count("REPORT_CREATE") == 1);
        new PunishMenuCommand(plugin).run(mod, "Steve");
        until(() -> menu(mod).equals("PunishMenus$MainMenu") && lore(slot(mod, 4)).contains("Reports against"));
        assertTrue(lore(slot(mod, 4)).contains("Reports against: 1 (1 open)"), lore(slot(mod, 4)));
    }
}
