package com.mrstudios.punish.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.staff.VanishService;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Item;
import org.bukkit.entity.Zombie;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Staff chat, /stafflist and vanish through the real plugin on MockBukkit (DESIGN.md 11.2, 11.5, 11.6). */
class PluginStaffTest {

    private static final NamespacedKey KEY = new NamespacedKey("mrpunish", "vanished");

    private ServerMock server;
    private MRPunish plugin;
    private ConsoleCommandSenderMock console;
    private PlayerMock helper;
    private PlayerMock mod;
    private PlayerMock admin;
    private PlayerMock steve;
    private PermissionAttachment modRank;
    private StaffCommands commands;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(MRPunish.class);
        console = (ConsoleCommandSenderMock) server.getConsoleSender();
        helper = server.addPlayer("Helper");
        helper.addAttachment(plugin, "mrpunish.helper", true);
        mod = server.addPlayer("Mod");
        modRank = mod.addAttachment(plugin, "mrpunish.mod", true);
        admin = server.addPlayer("Admin");
        admin.addAttachment(plugin, "mrpunish.admin", true);
        steve = server.addPlayer("Steve");
        commands = new StaffCommands(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

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

    private static List<String> drain(org.bukkit.command.CommandSender who) {
        List<String> lines = new ArrayList<>();
        Component c;
        while ((c = who instanceof PlayerMock p ? p.nextComponentMessage()
                : ((ConsoleCommandSenderMock) who).nextComponentMessage()) != null) {
            lines.add(plain(c));
        }
        return lines;
    }

    private void drainAll() {
        for (var p : List.of(helper, mod, admin, steve)) {
            drain(p);
        }
        drain(console);
    }

    /** A chat line through the plugin's chat listeners in the routing order (staff chat, then mute). */
    private AsyncChatEvent chat(PlayerMock p, String text) {
        AsyncChatEvent event = new AsyncChatEvent(true, p, new HashSet<>(), (source, n, message, viewer) -> message,
                Component.text(text), Component.text(text), null);
        plugin.prompts().onChat(event);
        plugin.staffChat().onChat(event);
        plugin.mutes().onChat(event);
        ticks(2);
        return event;
    }

    // ---- Staff chat ----

    @Test
    void scReachesStaffAndConsoleOnly() {
        drainAll();
        commands.sendStaffChat(mod, "<red>anyone seen the griefer?");
        String line = "[Staff] Moderator Mod: <red>anyone seen the griefer?";
        assertTrue(drain(helper).contains(line), "typed tags are shown as text");
        assertTrue(drain(admin).contains(line));
        assertTrue(drain(console).contains(line));
        assertEquals(List.of(), drain(steve), "never to non-staff");
        commands.sendStaffChat(console, "restart in 5");
        assertTrue(drain(mod).contains("[Staff]  Console: restart in 5"));
        commands.sendStaffChat(steve, "let me in");
        assertEquals(List.of("MR-Punish » You don't have permission to do that."), drain(steve));
        assertEquals(List.of(), drain(mod));
    }

    @Test
    void thePrefixAndTheToggle() {
        drainAll();
        AsyncChatEvent prefixed = chat(mod, "#secret plan");
        assertTrue(prefixed.isCancelled(), "never public");
        assertTrue(drain(helper).contains("[Staff] Moderator Mod: secret plan"));
        assertEquals(List.of(), drain(steve));
        // A player without the permission typing # chats normally.
        AsyncChatEvent normal = chat(steve, "#1 server");
        assertFalse(normal.isCancelled());
        assertEquals(List.of(), drain(helper));

        commands.toggleStaffChat(mod);
        assertTrue(drain(mod).contains("MR-Punish » Your chat now goes to staff chat."));
        AsyncChatEvent toggled = chat(mod, "everything goes to staff now");
        assertTrue(toggled.isCancelled());
        assertTrue(drain(admin).contains("[Staff] Moderator Mod: everything goes to staff now"));
        // Losing the permission while toggled on: refused, never shown publicly.
        mod.removeAttachment(modRank);
        mod.recalculatePermissions();
        drainAll();
        AsyncChatEvent lost = chat(mod, "oops");
        assertTrue(lost.isCancelled());
        assertEquals(List.of("MR-Punish » You don't have permission to do that."), drain(mod));
        assertEquals(List.of(), drain(admin));
        assertFalse(plugin.staffChat().toggledOn(mod.getUniqueId()), "the toggle is cleared");
    }

    @Test
    void aMutedStaffMemberCanStillUseStaffChatAndTheToggleEndsOnQuit() throws Exception {
        PunishCommand.mute(plugin).run(console, "Mod", "spam");
        until(() -> plugin.mutes().muteOf(mod).isPresent());
        drainAll();
        AsyncChatEvent staff = chat(mod, "#still here");
        assertTrue(staff.isCancelled());
        assertTrue(drain(helper).contains("[Staff] Moderator Mod: still here"));
        assertTrue(drain(mod).stream().noneMatch(l -> l.contains("muted")), "no mute notice for staff chat");
        AsyncChatEvent pub = chat(mod, "hello everyone");
        assertTrue(pub.isCancelled(), "public chat is still muted");
        commands.toggleStaffChat(mod);
        assertTrue(plugin.staffChat().toggledOn(mod.getUniqueId()));
        server.getPluginManager().callEvent(new PlayerQuitEvent(mod, Component.empty(),
                PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertFalse(plugin.staffChat().toggledOn(mod.getUniqueId()));
    }

    // ---- Vanish ----

    @Test
    void whoSeesAVanishedModerator() {
        commands.vanishSelf(mod);
        assertTrue(drain(mod).contains("MR-Punish » You are now vanished."));
        assertFalse(steve.canSee(mod), "non-staff");
        assertFalse(helper.canSee(mod), "lower rank, even with vanish.see");
        assertTrue(admin.canSee(mod), "higher rank");
        PlayerMock mod2 = server.addPlayer("Mod2");
        mod2.addAttachment(plugin, "mrpunish.mod", true);
        server.getPluginManager().callEvent(new PlayerJoinEvent(mod2, Component.empty()));
        assertTrue(mod2.canSee(mod), "equal rank, joined later");
        PlayerMock late = server.addPlayer("Late");
        server.getPluginManager().callEvent(new PlayerJoinEvent(late, Component.empty()));
        assertFalse(late.canSee(mod), "a player who joins later");
        assertTrue(mod.hasMetadata(VanishService.METADATA));
        assertTrue(mod.isSleepingIgnored());
        commands.vanishSelf(mod);
        assertTrue(drain(mod).contains("MR-Punish » You are visible again."));
        assertTrue(steve.canSee(mod) && helper.canSee(mod) && late.canSee(mod));
        assertFalse(mod.hasMetadata(VanishService.METADATA));
        assertFalse(mod.isSleepingIgnored());
        assertFalse(mod.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE));
    }

    @Test
    void stafflistShowsVanishedStaffOnlyToThoseWhoCanSeeThem() {
        commands.vanishSelf(mod);
        drainAll();
        commands.staffList(steve);
        List<String> forSteve = drain(steve);
        assertEquals("MR-Punish » Online staff (2):", forSteve.get(0));
        assertEquals(List.of("- Administrator Admin", "- Helper Helper"), forSteve.subList(1, 3));
        commands.staffList(admin);
        List<String> forAdmin = drain(admin);
        assertEquals(List.of("MR-Punish » Online staff (3):", "- Administrator Admin", "- Moderator Mod [vanished]",
                "- Helper Helper"), forAdmin);
        commands.staffList(console);
        assertTrue(drain(console).contains("- Moderator Mod [vanished]"));
    }

    @Test
    void noJoinOrQuitMessageAndVanishedAgainAfterARelog() {
        commands.vanishSelf(mod);
        drainAll();
        PlayerQuitEvent quit = new PlayerQuitEvent(mod, Component.text("Mod left"),
                PlayerQuitEvent.QuitReason.DISCONNECTED);
        server.getPluginManager().callEvent(quit);
        assertNull(quit.quitMessage());
        assertTrue(drain(admin).contains("[Staff] Mod left (vanished)"));
        assertEquals(List.of(), drain(steve));
        assertFalse(plugin.vanish().isVanished(mod.getUniqueId()));
        assertTrue(mod.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE), "persisted");
        PlayerJoinEvent join = new PlayerJoinEvent(mod, Component.text("Mod joined"));
        server.getPluginManager().callEvent(join);
        ticks(2);
        assertNull(join.joinMessage());
        assertTrue(plugin.vanish().isVanished(mod.getUniqueId()));
        assertFalse(steve.canSee(mod));
        assertTrue(drain(admin).contains("[Staff] Mod joined (vanished)"));
        assertTrue(drain(mod).contains("MR-Punish » You are still vanished."));
    }

    @Test
    void losingThePermissionMakesYouVisibleNowAndOnJoin() {
        commands.vanishSelf(mod);
        mod.removeAttachment(modRank);
        mod.recalculatePermissions();
        drainAll();
        plugin.vanish().refreshAll();
        assertFalse(plugin.vanish().isVanished(mod.getUniqueId()));
        assertTrue(steve.canSee(mod));
        assertTrue(drain(mod).contains("MR-Punish » You were made visible: you no longer have permission to vanish."));
        // The same for a stored vanish found at join.
        mod.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE, (byte) 1);
        PlayerJoinEvent join = new PlayerJoinEvent(mod, Component.text("Mod joined"));
        server.getPluginManager().callEvent(join);
        ticks(2);
        assertFalse(plugin.vanish().isVanished(mod.getUniqueId()));
        assertEquals("Mod joined", plain(join.joinMessage()));
        assertFalse(mod.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE));
    }

    @Test
    void pluginDisableShowsEveryoneAndEnableVanishesAgain() {
        commands.vanishSelf(mod);
        assertFalse(steve.canSee(mod));
        // What onDisable and onEnable call (MockBukkit can't enable a plugin a second time).
        plugin.vanish().disable();
        assertTrue(steve.canSee(mod), "nobody stays hidden while the plugin is off");
        assertFalse(mod.hasMetadata(VanishService.METADATA));
        assertFalse(mod.isSleepingIgnored());
        assertTrue(mod.getPersistentDataContainer().has(KEY, PersistentDataType.BYTE), "vanish itself is kept");
        plugin.vanish().enableOnline();
        assertTrue(plugin.vanish().isVanished(mod.getUniqueId()));
        assertFalse(steve.canSee(mod));
        // And the real plugin disable.
        server.getPluginManager().disablePlugin(plugin);
        assertTrue(steve.canSee(mod));
        assertTrue(helper.canSee(mod));
        assertFalse(mod.hasMetadata(VanishService.METADATA));
    }

    @Test
    void vanishedPlayersCantPickUpTriggerOrBeTargeted() {
        commands.vanishSelf(mod);
        Location at = mod.getLocation();
        Item item = mod.getWorld().dropItem(at, new ItemStack(Material.DIAMOND));
        EntityPickupItemEvent pickup = new EntityPickupItemEvent(mod, item, 0);
        server.getPluginManager().callEvent(pickup);
        assertTrue(pickup.isCancelled());
        Block plate = at.getBlock();
        plate.setType(Material.STONE_PRESSURE_PLATE);
        PlayerInteractEvent physical = new PlayerInteractEvent(mod, Action.PHYSICAL, null, plate, BlockFace.SELF);
        server.getPluginManager().callEvent(physical);
        assertTrue(physical.isCancelled());
        Zombie zombie = (Zombie) mod.getWorld().spawnEntity(at, org.bukkit.entity.EntityType.ZOMBIE);
        EntityTargetLivingEntityEvent target = new EntityTargetLivingEntityEvent(zombie, mod,
                EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        server.getPluginManager().callEvent(target);
        assertTrue(target.isCancelled());
        // Visible players are unaffected.
        EntityPickupItemEvent steves = new EntityPickupItemEvent(steve, item, 0);
        server.getPluginManager().callEvent(steves);
        assertFalse(steves.isCancelled());
    }

    @Test
    void vanishingOthersNeedsThePermissionRankAndATargetWhoCouldUnvanish() {
        drainAll();
        commands.vanishOther(mod, "Helper");
        assertEquals(List.of("MR-Punish » You don't have permission to do that."), drain(mod));
        commands.vanishOther(admin, "Helper");
        assertTrue(drain(admin).contains("MR-Punish » Helper is now vanished."));
        assertTrue(drain(helper).contains("MR-Punish » Admin vanished you."));
        assertTrue(plugin.vanish().isVanished(helper.getUniqueId()));
        PlayerMock owner = server.addPlayer("Owner");
        owner.addAttachment(plugin, "mrpunish.owner", true);
        commands.vanishOther(admin, "Owner");
        assertEquals(List.of("MR-Punish » You can't vanish Owner: their rank is equal to or higher than yours."),
                drain(admin));
        commands.vanishOther(admin, "Steve");
        assertEquals(List.of("MR-Punish » Steve doesn't have permission to vanish, so they can't be vanished."),
                drain(admin));
        commands.vanishOther(console, "Helper");
        assertTrue(drain(console).contains("MR-Punish » Helper is visible again."));
        assertFalse(plugin.vanish().isVanished(helper.getUniqueId()));
        // A vanished player the sender can't see is "unknown".
        commands.vanishSelf(owner);
        drainAll();
        commands.vanishOther(admin, "Owner");
        assertEquals(List.of("MR-Punish » Owner has never joined this server."), drain(admin));
    }

    @Test
    void theActionBarReminder() {
        commands.vanishSelf(mod);
        for (int i = 0; i < 30 * 20; i++) {
            server.getScheduler().performOneTick();
        }
        // MockBukkit keeps action bars apart from chat; the reminder must not land in chat.
        assertTrue(drain(mod).stream().noneMatch(l -> l.contains("You are vanished")));
    }
}
