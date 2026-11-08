package com.mrstudios.punish.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.gui.MenuHolder;
import com.mrstudios.punish.report.Report;
import com.mrstudios.punish.report.Report.Status;
import com.mrstudios.punish.report.ReportService;
import com.mrstudios.punish.report.ReportService.Change;
import com.mrstudios.punish.storage.ReportRepo;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** /report, /reports and the report states through the real plugin on MockBukkit (DESIGN.md 13). */
class PluginReportTest {

    private static final int CLAIM = 10;
    private static final int RESOLVE = 12;
    private static final int REJECT = 14;
    private static final int ADD_NOTE = 16;
    private static final int FIRST_NOTE = 28;
    private static final int ALL_NOTES = 44;

    private ServerMock server;
    private MRPunish plugin;
    private PlayerMock alex;
    private PlayerMock steve;
    private PlayerMock mod;
    private PlayerMock mod2;
    private PermissionAttachment modRank;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(MRPunish.class);
        alex = server.addPlayer("Alex");
        steve = server.addPlayer("Steve");
        mod = server.addPlayer("Mod");
        modRank = mod.addAttachment(plugin, "mrpunish.mod", true);
        mod2 = server.addPlayer("Mod2");
        mod2.addAttachment(plugin, "mrpunish.mod", true);
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

    private static List<String> drain(PlayerMock p) {
        List<String> lines = new ArrayList<>();
        Component c;
        while ((c = p.nextComponentMessage()) != null) {
            lines.add(plain(c));
        }
        return lines;
    }

    /** Waits until {@code p} got a line containing {@code text}; returns everything it got. */
    private List<String> heard(PlayerMock p, String text) throws InterruptedException {
        List<String> got = new ArrayList<>();
        until(() -> {
            got.addAll(drain(p));
            return got.stream().anyMatch(l -> l.contains(text));
        });
        return got;
    }

    private static String menuName(PlayerMock p) {
        MenuHolder m = MenuHolder.of(p.getOpenInventory().getTopInventory());
        return m == null ? "none" : m.getClass().getSimpleName();
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
        return String.join("\n", item.getItemMeta().lore().stream().map(PluginReportTest::plain).toList());
    }

    private void click(PlayerMock p, int slot) {
        server.getPluginManager().callEvent(new InventoryClickEvent(p.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        ticks(2);
    }

    private void chat(PlayerMock p, String text) {
        AsyncChatEvent event = new AsyncChatEvent(true, p, new HashSet<>(), (source, n, message, viewer) -> message,
                Component.text(text), Component.text(text), null);
        plugin.prompts().onChat(event);
        assertTrue(event.isCancelled());
        ticks(2);
    }

    private List<Report> all() throws Exception {
        return plugin.storage().executor().submit("test", c -> new ReportRepo(plugin.storage().database())
                .page(c, ReportRepo.Filter.ALL, mod.getUniqueId(), 0, 100)).get(5, TimeUnit.SECONDS);
    }

    private List<Report> allSafe() {
        try {
            return all();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Report byId(long id) throws Exception {
        return plugin.storage().executor().submit("test", c -> new ReportRepo(plugin.storage().database())
                .byId(c, id)).get(5, TimeUnit.SECONDS).orElseThrow();
    }

    private List<Report.Note> notes(long id) throws Exception {
        return plugin.storage().executor().submit("test", c -> new ReportRepo(plugin.storage().database())
                .notes(c, id, 0, 100)).get(5, TimeUnit.SECONDS);
    }

    private <T> T await(CompletableFuture<T> future) throws Exception {
        until(future::isDone);
        return future.get(1, TimeUnit.SECONDS);
    }

    private long report(PlayerMock reporter, String target, String reason) throws Exception {
        int before = all().size();
        new ReportCommand(plugin).run(reporter, target, reason);
        until(() -> allSafe().size() > before);
        return all().get(0).id();
    }

    private void openDetail(PlayerMock staff, int listSlot) throws InterruptedException {
        new ReportsCommand(plugin).run(staff, "all");
        until(() -> "ListMenu".equals(menuName(staff)));
        click(staff, listSlot);
        until(() -> "DetailMenu".equals(menuName(staff)));
        assertEquals("DetailMenu", menuName(staff));
    }

    private ReportService.Actor actor(PlayerMock p) {
        return new ReportService.Actor(p.getUniqueId(), p.getName(), p::hasPermission);
    }

    @Test
    void aTypedReportIsSavedWithTheLocationAndStaffAreTold() throws Exception {
        long id = report(alex, "Steve", "spamming in chat \u0007");
        Report r = byId(id);
        assertEquals(Status.OPEN, r.status());
        assertNull(r.reasonKey());
        assertEquals("spamming in chat", r.reasonText());
        assertEquals(alex.getLocation().getWorld().getName(), r.world());
        assertEquals(alex.getLocation().getBlockY(), r.y());
        assertTrue(heard(alex, "Thanks.").contains("MR-Punish » Thanks. Your report on Steve was sent to staff (#" + id
                + ")."));
        assertTrue(heard(mod, "reported").contains("[Report] Alex reported Steve: spamming in chat (#" + id + ") [view]"));
        assertTrue(drain(steve).stream().noneMatch(l -> l.contains("reported")), "only staff are told");
        // A configured reason by its key.
        long second = report(steve, "Alex", "hacking");
        assertEquals("hacking", byId(second).reasonKey());
        assertEquals("Hacking", byId(second).reasonText());
    }

    @Test
    void selfCooldownDuplicateAndCap() throws Exception {
        new ReportCommand(plugin).run(alex, "Alex", "x");
        assertTrue(heard(alex, "yourself").contains("MR-Punish » You can't report yourself."));
        report(alex, "Steve", "spam");
        drain(alex);
        new ReportCommand(plugin).run(alex, "Mod", "spam");
        assertTrue(heard(alex, "Wait").stream().anyMatch(l -> l.startsWith("MR-Punish » Wait ")
                && l.endsWith(" before reporting again.")));
        alex.addAttachment(plugin, ReportService.NO_COOLDOWN, true);
        long firstId = all().get(0).id();
        new ReportCommand(plugin).run(alex, "Steve", "again");
        assertTrue(heard(alex, "already").contains("MR-Punish » You already reported Steve. Staff are looking at it (#"
                + firstId + ")."));
        report(alex, "Mod", "a");
        report(alex, "Mod2", "b");
        server.addPlayer("Other");
        drain(alex);
        new ReportCommand(plugin).run(alex, "Other", "c");
        assertTrue(heard(alex, "open reports").contains("MR-Punish » You have 3 open reports. Wait until staff have"
                + " handled them."));
        assertEquals(3, all().size());
    }

    @Test
    void staffCantBeReportedWhenTheConfigSaysSo() throws Exception {
        File config = new File(plugin.getDataFolder(), "config.yml");
        String text = Files.readString(config.toPath(), StandardCharsets.UTF_8);
        Files.writeString(config.toPath(), text.replace("allow-reporting-staff: true", "allow-reporting-staff: false"),
                StandardCharsets.UTF_8);
        plugin.reload(server.getConsoleSender());
        new ReportCommand(plugin).run(alex, "Mod", "abuse");
        assertTrue(heard(alex, "staff").contains("MR-Punish » You can't report staff members."));
        assertTrue(all().isEmpty());
    }

    @Test
    void theReasonMenuAndOtherThroughChat() throws Exception {
        new ReportCommand(plugin).run(alex, "Steve", "");
        until(() -> "ReasonMenu".equals(menuName(alex)));
        assertEquals(Material.DIAMOND_SWORD, type(alex, 10));
        assertEquals("Hacking", name(slot(alex, 10)));
        assertEquals(Material.WRITABLE_BOOK, type(alex, 22));
        click(alex, 10);
        until(() -> !allSafe().isEmpty());
        assertEquals("hacking", all().get(0).reasonKey());

        new ReportCommand(plugin).run(steve, "Alex", "");
        until(() -> "ReasonMenu".equals(menuName(steve)));
        drain(steve);
        click(steve, 22);
        assertTrue(plugin.prompts().waiting(steve.getUniqueId()));
        assertTrue(drain(steve).contains("MR-Punish » Type the reason for reporting Alex in chat, or cancel."));
        chat(steve, "stole my diamonds");
        until(() -> allSafe().size() == 2);
        assertEquals("stole my diamonds", all().get(0).reasonText());
        assertNull(all().get(0).reasonKey());
    }

    @Test
    void claimResolveWithANoteAndTheReporterIsToldNow() throws Exception {
        long id = report(alex, "Steve", "spam");
        openDetail(mod, 0);
        assertEquals("#" + id + " Steve", name(slot(mod, 4)));
        assertTrue(lore(slot(mod, 4)).contains("Status: Open"), lore(slot(mod, 4)));
        assertEquals(Material.LIME_DYE, type(mod, CLAIM));
        drain(mod);
        click(mod, CLAIM);
        assertTrue(heard(mod, "claimed").contains("MR-Punish » You claimed report #" + id + "."));
        until(() -> type(mod, CLAIM) == Material.GRAY_DYE);
        assertEquals(Status.CLAIMED, byId(id).status());
        assertTrue(lore(slot(mod, 4)).contains("Claimed by: Mod"));
        click(mod, RESOLVE);
        assertTrue(plugin.prompts().waiting(mod.getUniqueId()));
        drain(alex);
        chat(mod, "muted for 30m");
        until(() -> {
            try {
                return byId(id).status() == Status.RESOLVED;
            } catch (Exception e) {
                return false;
            }
        });
        Report done = byId(id);
        assertEquals("muted for 30m", done.decisionNote());
        assertEquals("Mod", done.closedByName());
        assertTrue(heard(alex, "handled").contains("MR-Punish » Your report on Steve was handled by staff. Thank you!"));
        assertTrue(heard(mod2, "resolved").contains("[Report] Mod resolved #" + id + " (Steve)."));
        until(() -> "DetailMenu".equals(menuName(mod)) && lore(slot(mod, 4)).contains("Resolved"));
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, type(mod, RESOLVE), "closed: no more decisions");
        assertTrue(lore(slot(mod, 4)).contains("Closed by Mod on "));
    }

    @Test
    void anOfflineReporterIsToldOnTheNextJoinOnce() throws Exception {
        long id = report(alex, "Steve", "spam");
        alex.disconnect();
        ticks(2);
        Change result = await(plugin.reports().close(actor(mod), id, 0, Status.REJECTED, null));
        assertInstanceOf(Change.Done.class, result);
        alex.reconnect();
        assertTrue(heard(alex, "reviewed").contains("MR-Punish » Your report on Steve was reviewed and closed."));
        alex.disconnect();
        alex.reconnect();
        ticks(20);
        assertTrue(drain(alex).stream().noneMatch(l -> l.contains("reviewed")), "shown once");
    }

    @Test
    void twoStaffDecidingTheSameReportOnlyOneWins() throws Exception {
        long id = report(alex, "Steve", "spam");
        CompletableFuture<Change> a = plugin.reports().close(actor(mod), id, 0, Status.RESOLVED, "a");
        CompletableFuture<Change> b = plugin.reports().close(actor(mod2), id, 0, Status.REJECTED, "b");
        Change ra = await(a);
        Change rb = await(b);
        assertInstanceOf(Change.Done.class, ra);
        assertInstanceOf(Change.Changed.class, rb);
        assertEquals(Status.RESOLVED, ((Change.Changed) rb).current().status());
        assertEquals("a", byId(id).decisionNote());

        // The same through two open detail menus: the stale one gets "changed".
        long second = report(steve, "Alex", "spam");
        openDetail(mod, 0);
        openDetail(mod2, 0);
        click(mod, CLAIM);
        until(() -> {
            try {
                return byId(second).status() == Status.CLAIMED;
            } catch (Exception e) {
                return false;
            }
        });
        drain(mod2);
        click(mod2, REJECT);
        chat(mod2, "no proof");
        List<String> said = heard(mod2, "changed");
        assertTrue(said.contains("MR-Punish » Report #" + second + " was changed by someone else. Showing the latest"
                + " version."), said.toString());
        assertEquals(Status.CLAIMED, byId(second).status());
        until(() -> "DetailMenu".equals(menuName(mod2)) && lore(slot(mod2, 4)).contains("Claimed by: Mod"));
        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, type(mod2, REJECT), "claimed by someone else");
    }

    @Test
    void aClaimBlocksOthersButAnAdminCanOverride() throws Exception {
        long id = report(alex, "Steve", "spam");
        assertInstanceOf(Change.Done.class, await(plugin.reports().claim(actor(mod), id, 0)));
        Change other = await(plugin.reports().close(actor(mod2), id, 1, Status.RESOLVED, null));
        assertInstanceOf(Change.ClaimedByOther.class, other);
        assertInstanceOf(Change.ClaimedByOther.class, await(plugin.reports().claim(actor(mod2), id, 1)));
        PlayerMock admin = server.addPlayer("Admin");
        admin.addAttachment(plugin, "mrpunish.admin", true);
        assertInstanceOf(Change.Done.class, await(plugin.reports().close(actor(admin), id, 1, Status.REJECTED, null)));
        String details = plugin.storage().executor().submit("test", c -> {
            try (PreparedStatement s = c.prepareStatement("SELECT details FROM " + plugin.storage().database()
                    .table("staff_actions") + " WHERE action = 'REPORT_REJECT'")) {
                var rows = s.executeQuery();
                return rows.next() ? rows.getString(1) : null;
            }
        }).get(5, TimeUnit.SECONDS);
        assertNotNull(details);
        assertTrue(details.startsWith("override: claimed by Mod"), details);
        assertInstanceOf(Change.Closed.class, await(plugin.reports().claim(actor(mod), id, 2)));
    }

    @Test
    void anExpiredClaimIsReleased() throws Exception {
        long id = report(alex, "Steve", "spam");
        await(plugin.reports().claim(actor(mod), id, 0));
        plugin.storage().executor().submit("test", c -> {
            try (PreparedStatement s = c.prepareStatement("UPDATE " + plugin.storage().database().table("reports")
                    + " SET claimed_at = ? WHERE id = ?")) {
                s.setLong(1, System.currentTimeMillis() - 31 * 60_000L);
                s.setLong(2, id);
                return s.executeUpdate();
            }
        }).get(5, TimeUnit.SECONDS);
        drain(mod);
        List<Report> released = await(plugin.reports().expireClaims());
        assertEquals(1, released.size());
        Report r = byId(id);
        assertEquals(Status.OPEN, r.status());
        assertNull(r.claimedByName());
        assertTrue(heard(mod, "expired").contains("MR-Punish » Your claim on report #" + id + " expired."));
        assertTrue(await(plugin.reports().expireClaims()).isEmpty(), "only once");
    }

    @Test
    void notesAndAllNotes() throws Exception {
        long id = report(alex, "Steve", "spam");
        openDetail(mod, 0);
        click(mod, ADD_NOTE);
        chat(mod, "checked the logs");
        until(() -> "DetailMenu".equals(menuName(mod)) && type(mod, FIRST_NOTE) == Material.PAPER);
        assertTrue(lore(slot(mod, FIRST_NOTE)).contains("checked the logs"));
        assertTrue(name(slot(mod, FIRST_NOTE)).startsWith("Mod "));
        assertEquals(1, notes(id).size());
        assertEquals("All notes (1)", name(slot(mod, ALL_NOTES)));
        click(mod, ALL_NOTES);
        until(() -> "NotesMenu".equals(menuName(mod)));
        assertEquals(Material.PAPER, type(mod, 0));
        // Notes stay possible on a closed report.
        await(plugin.reports().close(actor(mod), id, 0, Status.RESOLVED, null));
        assertInstanceOf(Change.Done.class, await(plugin.reports().addNote(actor(mod2), id, "follow-up")));
        assertEquals(2, notes(id).size());
    }

    @Test
    void permissionIsCheckedAtTheClick() throws Exception {
        long id = report(alex, "Steve", "spam");
        openDetail(mod, 0);
        mod.removeAttachment(modRank);
        mod.recalculatePermissions();
        drain(mod);
        click(mod, CLAIM);
        assertTrue(heard(mod, "permission").contains("MR-Punish » You don't have permission to do that."));
        assertEquals(Status.OPEN, byId(id).status());
        assertFalse("DetailMenu".equals(menuName(mod)) && type(mod, CLAIM) == Material.GRAY_DYE);
        new ReportsCommand(plugin).run(alex, "open");
        assertTrue(heard(alex, "permission").contains("MR-Punish » You don't have permission to do that."));
    }

    @Test
    void theListShowsFiltersAndConsoleCantUseTheCommands() throws Exception {
        long first = report(alex, "Steve", "spam");
        long second = report(steve, "Alex", "spam");
        await(plugin.reports().claim(actor(mod), second, 0));
        new ReportsCommand(plugin).run(mod, "open");
        until(() -> "ListMenu".equals(menuName(mod)));
        assertEquals("#" + first + " Steve", name(slot(mod, 0)));
        assertEquals(Material.PLAYER_HEAD, type(mod, 0));
        assertTrue(lore(slot(mod, 0)).contains("Reported by: Alex"));
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, type(mod, 1));
        assertEquals("Showing: Open", name(slot(mod, 47)));
        click(mod, 47);
        until(() -> name(slot(mod, 47)).equals("Showing: Claimed"));
        assertEquals("#" + second + " Alex", name(slot(mod, 0)));
        assertTrue(lore(slot(mod, 0)).contains("Claimed by: Mod"));
        new ReportsCommand(plugin).run(server.getConsoleSender(), "open");
        new ReportCommand(plugin).run(server.getConsoleSender(), "Steve", "x");
        var console = (org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock) server.getConsoleSender();
        assertEquals("MR-Punish » Only players can use this command.", plain(console.nextComponentMessage()));
        assertEquals("MR-Punish » Only players can use this command.", plain(console.nextComponentMessage()));
    }
}
