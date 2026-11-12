package com.mrstudios.punish.command;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.gui.MenuHolder;
import com.mrstudios.punish.staff.StaffModeService;
import com.mrstudios.punish.staff.StaffModeState;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Staff mode through the real plugin on MockBukkit (DESIGN.md 11.1, 11.8): state round trips and tool guards. */
class PluginStaffModeTest {

    private static final NamespacedKey STATE = new NamespacedKey("mrpunish", "staffmode");

    private ServerMock server;
    private MRPunish plugin;
    private PlayerMock mod;
    private PlayerMock admin;
    private PlayerMock steve;
    private PermissionAttachment modRank;
    private StaffCommands commands;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PluginMuteTest.WithAliases.class);
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

    private static List<String> drain(PlayerMock p) {
        List<String> lines = new ArrayList<>();
        Component c;
        while ((c = p.nextComponentMessage()) != null) {
            lines.add(plain(c));
        }
        return lines;
    }

    private boolean cancelled(Event event) {
        server.getPluginManager().callEvent(event);
        return ((Cancellable) event).isCancelled();
    }

    private static ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        item.setItemMeta(meta);
        return item;
    }

    /** A full, unusual state: named and enchanted items, armour, off-hand, effects, creative and flying. */
    private ItemStack[] dressUp(PlayerMock p) {
        ItemStack sword = named(Material.DIAMOND_SWORD, "Excalibur");
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 5);
        p.getInventory().setItem(0, sword);
        p.getInventory().setItem(17, new ItemStack(Material.COBBLESTONE, 64));
        p.getInventory().setItem(35, new ItemStack(Material.GOLDEN_APPLE, 3));
        p.getInventory().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        p.getInventory().setBoots(named(Material.LEATHER_BOOTS, "Comfy"));
        p.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        p.setGameMode(GameMode.CREATIVE);
        p.setAllowFlight(true);
        p.setFlying(true);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 2400, 1));
        p.setHealth(13.0);
        p.setFoodLevel(7);
        p.setLevel(31);
        p.setExp(0.4f);
        p.setFireTicks(40);
        p.setRemainingAir(120);
        return p.getInventory().getContents();
    }

    private void assertRestored(PlayerMock p, ItemStack[] before) {
        assertArrayEquals(before, p.getInventory().getContents());
        assertEquals("Excalibur", plain(p.getInventory().getItem(0).getItemMeta().displayName()));
        assertEquals(5, p.getInventory().getItem(0).getEnchantmentLevel(Enchantment.SHARPNESS));
        assertEquals(GameMode.CREATIVE, p.getGameMode());
        assertTrue(p.getAllowFlight());
        assertTrue(p.isFlying());
        assertTrue(p.hasPotionEffect(PotionEffectType.SPEED));
        assertEquals(1, p.getPotionEffect(PotionEffectType.SPEED).getAmplifier());
        assertEquals(13.0, p.getHealth());
        assertEquals(7, p.getFoodLevel());
        assertEquals(31, p.getLevel());
        assertEquals(0.4f, p.getExp());
        assertEquals(120, p.getRemainingAir());
        assertFalse(p.getPersistentDataContainer().has(STATE, PersistentDataType.BYTE_ARRAY), "state removed");
        assertFalse(plugin.staffMode().inStaffMode(p.getUniqueId()));
        for (ItemStack item : p.getInventory().getContents()) {
            assertFalse(plugin.staffMode().isTool(item), "no tool left");
        }
    }

    @Test
    void theStateSurvivesAByteRoundTrip() {
        dressUp(mod);
        StaffModeState state = StaffModeState.capture(mod, true, false, 1234L);
        StaffModeState back = StaffModeState.fromBytes(state.toBytes());
        assertArrayEquals(state.contents(), back.contents());
        assertEquals(state.effects(), back.effects());
        assertEquals(GameMode.CREATIVE, back.gameMode());
        assertEquals(13.0, back.health());
        assertTrue(back.wasVanished());
        assertEquals(1234L, back.savedAt());
        assertEquals(mod.getWorld().getUID(), back.world());
        byte[] bytes = state.toBytes();
        bytes[0] = 9;
        assertThrows(IllegalArgumentException.class, () -> StaffModeState.fromBytes(bytes), "unknown version");
        assertThrows(IllegalArgumentException.class, () -> StaffModeState.fromBytes(new byte[] {1, 0, 0}));
    }

    @Test
    void enterAndExitRestoreEverythingExactly() {
        ItemStack[] before = dressUp(mod);
        drain(mod);
        commands.toggleStaffMode(mod);
        assertEquals(List.of("MR-Punish » Staff mode on."), drain(mod));
        assertTrue(plugin.staffMode().inStaffMode(mod.getUniqueId()));
        assertTrue(mod.getPersistentDataContainer().has(STATE, PersistentDataType.BYTE_ARRAY));
        assertEquals(GameMode.SURVIVAL, mod.getGameMode());
        assertTrue(mod.isFlying());
        assertFalse(mod.hasPotionEffect(PotionEffectType.SPEED));
        assertNull(mod.getInventory().getHelmet());
        // Tools for the moderator's permissions: teleport, random, inspect, freeze, punish, vanish (showing
        // "vanished") and reports.
        List<Material> tools = new ArrayList<>();
        for (int slot = 0; slot < 9; slot++) {
            ItemStack item = mod.getInventory().getItem(slot);
            if (item != null) {
                assertTrue(plugin.staffMode().isTool(item), "only tools: " + item);
                tools.add(item.getType());
            }
        }
        assertEquals(List.of(Material.COMPASS, Material.ENDER_EYE, Material.BOOK, Material.BLAZE_ROD,
                Material.IRON_AXE, Material.NETHER_STAR, Material.LIME_DYE, Material.WRITABLE_BOOK), tools);
        assertTrue(plugin.vanish().isVanished(mod.getUniqueId()), "staff-mode.vanish");
        assertNotEquals(server.getScoreboardManager().getMainScoreboard(), mod.getScoreboard(), "staff board");
        commands.toggleStaffMode(mod);
        assertEquals(List.of("MR-Punish » Staff mode off. Your inventory and state were restored."), drain(mod));
        assertRestored(mod, before);
        assertFalse(plugin.vanish().isVanished(mod.getUniqueId()), "wasn't vanished before");
        assertEquals(server.getScoreboardManager().getMainScoreboard(), mod.getScoreboard(), "previous board back");
    }

    @Test
    void toolsCantBeDroppedMovedOrPlacedAndStaffDontFightOrStarve() {
        commands.toggleStaffMode(mod);
        ItemStack wand = mod.getInventory().getItem(3);
        Item dropped = mod.getWorld().dropItem(mod.getLocation(), wand.clone());
        assertTrue(cancelled(new PlayerDropItemEvent(mod, dropped)));
        assertTrue(cancelled(new PlayerSwapHandItemsEvent(mod, new ItemStack(Material.AIR), wand)));
        var chest = server.createInventory(null, 27);
        var view = mod.openInventory(chest);
        assertTrue(cancelled(new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 30, ClickType.LEFT,
                InventoryAction.PICKUP_ALL)), "own inventory");
        assertTrue(cancelled(new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 3, ClickType.SHIFT_LEFT,
                InventoryAction.MOVE_TO_OTHER_INVENTORY)), "the container is view-only");
        mod.closeInventory();
        Item loot = mod.getWorld().dropItem(mod.getLocation(), new ItemStack(Material.DIAMOND));
        assertTrue(cancelled(new EntityPickupItemEvent(mod, loot, 0)), "allow-pickup: false");
        Block block = mod.getLocation().clone().add(2, 0, 0).getBlock();
        block.setType(Material.STONE);
        assertTrue(cancelled(new BlockBreakEvent(block, mod)), "allow-build: false");
        assertTrue(cancelled(new EntityDamageEvent(mod, EntityDamageEvent.DamageCause.FALL,
                DamageSource.builder(DamageType.FALL).build(), 4)));
        assertTrue(cancelled(new FoodLevelChangeEvent(mod, 5)));
        // /kill still kills (not cancelled).
        assertFalse(cancelled(new EntityDamageEvent(mod, EntityDamageEvent.DamageCause.KILL,
                DamageSource.builder(DamageType.GENERIC_KILL).build(), 1000)));
    }

    private void useOn(PlayerMock staff, int slot, PlayerMock target) {
        staff.getInventory().setHeldItemSlot(slot);
        server.getPluginManager().callEvent(new PlayerInteractEntityEvent(staff, target, EquipmentSlot.HAND));
        ticks(2);
    }

    private void use(PlayerMock staff, int slot) {
        staff.getInventory().setHeldItemSlot(slot);
        server.getPluginManager().callEvent(new PlayerInteractEvent(staff, Action.RIGHT_CLICK_AIR,
                staff.getInventory().getItem(slot), null, BlockFace.SELF, EquipmentSlot.HAND));
        ticks(2);
    }

    private static String menu(PlayerMock p) {
        MenuHolder m = MenuHolder.of(p.getOpenInventory().getTopInventory());
        return m == null ? "none" : m.getClass().getSimpleName();
    }

    @Test
    void eachToolWorks() throws Exception {
        commands.toggleStaffMode(mod);
        useOn(mod, 3, steve);
        until(() -> plugin.freeze().isFrozen(steve.getUniqueId()));
        assertTrue(plugin.freeze().isFrozen(steve.getUniqueId()), "freeze wand");
        useOn(mod, 3, steve);
        until(() -> !plugin.freeze().isFrozen(steve.getUniqueId()));
        assertFalse(plugin.freeze().isFrozen(steve.getUniqueId()), "and again unfreezes");
        useOn(mod, 2, steve);
        assertEquals("InventoryView", menu(mod));
        mod.closeInventory();
        mod.setSneaking(true);
        useOn(mod, 2, steve);
        assertEquals("EnderView", menu(mod));
        mod.closeInventory();
        mod.setSneaking(false);
        useOn(mod, 4, steve);
        assertEquals("MainMenu", menu(mod));
        mod.closeInventory();
        use(mod, 0);
        assertEquals("PlayerPicker", menu(mod));
        mod.closeInventory();
        Location far = steve.getLocation().clone().add(100, 0, 0);
        steve.teleport(far);
        use(mod, 1);
        assertEquals(far.getBlockX(), mod.getLocation().getBlockX(), "random player: the only non-staff one");
        drain(mod);
        use(mod, 7);
        assertFalse(plugin.vanish().isVanished(mod.getUniqueId()));
        assertEquals(Material.GRAY_DYE, mod.getInventory().getItem(7).getType(), "the tool shows the state");
        use(mod, 8);
        until(() -> "ListMenu".equals(menu(mod)));
        assertEquals("ListMenu", menu(mod));
    }

    @Test
    void quitAndDisableRestore() {
        ItemStack[] before = dressUp(mod);
        commands.toggleStaffMode(mod);
        mod.disconnect();
        ticks(2);
        assertRestored(mod, before);
        mod.reconnect();
        commands.toggleStaffMode(mod);
        server.getPluginManager().disablePlugin(plugin);
        assertRestored(mod, before);
    }

    @Test
    void aStateLeftByACrashIsRestoredOnJoin() {
        ItemStack[] before = dressUp(mod);
        // What a crash leaves: the state in the PDC and the staff inventory on the player.
        byte[] saved = StaffModeState.capture(mod, false, false, System.currentTimeMillis()).toBytes();
        mod.getPersistentDataContainer().set(STATE, PersistentDataType.BYTE_ARRAY, saved);
        mod.getInventory().clear();
        mod.getInventory().setItem(0, new ItemStack(Material.BLAZE_ROD));
        mod.setGameMode(GameMode.SURVIVAL);
        drain(mod);
        server.getPluginManager().callEvent(new PlayerJoinEvent(mod, Component.text("joined")));
        assertRestored(mod, before);
        assertTrue(drain(mod).contains("MR-Punish » You left staff mode unexpectedly last time. Your inventory and state"
                + " were restored."));
    }

    @Test
    void deathDropsNothingAndRespawnRestores() {
        ItemStack[] before = dressUp(mod);
        commands.toggleStaffMode(mod);
        List<ItemStack> drops = new ArrayList<>(List.of(mod.getInventory().getItem(3), mod.getInventory().getItem(4)));
        PlayerDeathEvent death = new PlayerDeathEvent(mod, DamageSource.builder(DamageType.GENERIC_KILL).build(), drops,
                30, Component.text("died"), true);
        server.getPluginManager().callEvent(death);
        assertTrue(death.getDrops().isEmpty(), "no drops");
        assertEquals(0, death.getDroppedExp());
        assertFalse(death.getKeepInventory());
        server.getPluginManager().callEvent(new PlayerPostRespawnEvent(mod, mod.getLocation(), false, false, false,
                PlayerRespawnEvent.RespawnReason.DEATH));
        assertArrayEquals(before, mod.getInventory().getContents());
        assertFalse(plugin.staffMode().inStaffMode(mod.getUniqueId()));
    }

    @Test
    void refusalsPermissionLossAndForceExit() throws Exception {
        // Frozen: refused.
        new FreezeCommands(plugin).freeze(admin, "Mod");
        until(() -> plugin.freeze().isFrozen(mod.getUniqueId()));
        drain(mod);
        commands.toggleStaffMode(mod);
        assertEquals(List.of("MR-Punish » You can't enter staff mode right now: you are frozen."), drain(mod));
        new FreezeCommands(plugin).unfreeze(admin, "Mod");
        until(() -> !plugin.freeze().isFrozen(mod.getUniqueId()));
        // Permission lost: out at the rank refresh.
        commands.toggleStaffMode(mod);
        mod.removeAttachment(modRank);
        mod.recalculatePermissions();
        plugin.staffMode().checkPermissions();
        assertFalse(plugin.staffMode().inStaffMode(mod.getUniqueId()));
        // Forced out by an admin.
        modRank = mod.addAttachment(plugin, "mrpunish.mod", true);
        commands.toggleStaffMode(mod);
        drain(admin);
        drain(mod);
        plugin.forceExit(admin, "Mod");
        assertFalse(plugin.staffMode().inStaffMode(mod.getUniqueId()));
        assertFalse(plugin.vanish().isVanished(mod.getUniqueId()));
        assertTrue(drain(admin).contains("MR-Punish » Mod is out of staff mode and vanish."));
        assertTrue(drain(mod).contains("MR-Punish » Admin took you out of staff mode."));
        plugin.forceExit(mod, "Admin");
        assertTrue(drain(mod).contains("MR-Punish » You can't force Admin out: their rank is equal to or higher than"
                + " yours."));
    }

    @Test
    void stafflistTagIsForStaffOnly() {
        // plugin.yml gives everyone mrpunish.stafflist; the test plugin subclass doesn't load those defaults.
        admin.addAttachment(plugin, StaffCommands.STAFFLIST, true);
        steve.addAttachment(plugin, StaffCommands.STAFFLIST, true);
        commands.toggleStaffMode(mod);
        plugin.vanish().unvanish(mod);
        drain(admin);
        drain(steve);
        commands.staffList(admin);
        List<String> forAdmin = drain(admin);
        assertTrue(forAdmin.contains("- Moderator Mod [staff mode]"), forAdmin.toString());
        commands.staffList(steve);
        List<String> forSteve = drain(steve);
        assertTrue(forSteve.contains("- Moderator Mod"), "players don't see the staff mode tag: " + forSteve);
    }
}
