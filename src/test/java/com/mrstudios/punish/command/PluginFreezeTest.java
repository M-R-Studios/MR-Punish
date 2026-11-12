package com.mrstudios.punish.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.gui.Items;
import com.mrstudios.punish.gui.MenuHolder;
import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.storage.FrozenRepo;
import com.mrstudios.punish.storage.PunishmentRepo;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Item;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
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

/** Freeze, /invsee and /endersee through the real plugin on MockBukkit (DESIGN.md 11.3, 11.4). */
class PluginFreezeTest {

    private ServerMock server;
    private MRPunish plugin;
    private ConsoleCommandSenderMock console;
    private PlayerMock mod;
    private PlayerMock admin;
    private PlayerMock steve;
    private PermissionAttachment modRank;
    private FreezeCommands commands;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(PluginMuteTest.WithAliases.class);
        console = (ConsoleCommandSenderMock) server.getConsoleSender();
        mod = server.addPlayer("Mod");
        modRank = mod.addAttachment(plugin, "mrpunish.mod", true);
        admin = server.addPlayer("Admin");
        admin.addAttachment(plugin, "mrpunish.admin", true);
        steve = server.addPlayer("Steve");
        commands = new FreezeCommands(plugin);
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

    private Optional<FrozenRepo.Frozen> row(UUID uuid) throws Exception {
        return plugin.storage().executor().submit("test", c -> new FrozenRepo(plugin.storage().database()).get(c, uuid))
                .get(5, TimeUnit.SECONDS);
    }

    private boolean rowSafe(UUID uuid) {
        try {
            return row(uuid).isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    private void freezeSteve() throws InterruptedException {
        commands.freeze(mod, "Steve");
        until(() -> plugin.freeze().isFrozen(steve.getUniqueId()));
        assertTrue(plugin.freeze().isFrozen(steve.getUniqueId()));
    }

    private boolean cancelled(Event event) {
        server.getPluginManager().callEvent(event);
        return ((Cancellable) event).isCancelled();
    }

    @Test
    void freezeCommitsTheRowThenAppliesTheEffect() throws Exception {
        drain(mod);
        freezeSteve();
        FrozenRepo.Frozen r = row(steve.getUniqueId()).orElseThrow();
        assertEquals("Mod", r.byName());
        assertEquals(20, r.byWeight());
        assertTrue(drain(mod).contains("MR-Punish » Froze Steve."));
        List<String> told = drain(steve);
        assertTrue(told.contains("You have been frozen by staff"), told.toString());
        assertTrue(drain(admin).contains("[MR] Mod froze Steve"));
        commands.freeze(mod, "Steve");
        List<String> again = new ArrayList<>();
        until(() -> {
            again.addAll(drain(mod));
            return !again.isEmpty();
        });
        assertEquals(List.of("MR-Punish » Steve is already frozen."), again);
    }

    @Test
    void selfRankAndOfflineAreRefused() throws Exception {
        drain(mod);
        commands.freeze(mod, "Mod");
        assertEquals(List.of("MR-Punish » You can't freeze yourself."), drain(mod));
        commands.freeze(mod, "Admin");
        List<String> said = new ArrayList<>();
        until(() -> {
            said.addAll(drain(mod));
            return !said.isEmpty();
        });
        assertEquals(List.of("MR-Punish » You can't freeze Admin: their rank is equal to or higher than yours."), said);
        commands.freeze(mod, "Nobody");
        assertEquals(List.of("MR-Punish » Nobody isn't online. Only online players can be frozen."), drain(mod));
        commands.freeze(steve, "Mod");
        assertEquals(List.of("MR-Punish » You don't have permission to do that."), drain(steve));
        assertFalse(rowSafe(admin.getUniqueId()));
    }

    @Test
    void everyBlockedAction() throws Exception {
        steve.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 2));
        Inventory chest = server.createInventory(null, 27);
        org.bukkit.inventory.InventoryView chestView = steve.openInventory(chest);
        steve.closeInventory();
        freezeSteve();
        Location at = steve.getLocation().clone();
        // Moving snaps back; looking around is fine.
        Location moved = at.clone().add(3, 0, 0);
        var move = steve.simulatePlayerMove(moved);
        assertEquals(at.getX(), move.getTo().getX());
        Location look = at.clone();
        look.setYaw(90);
        var turn = steve.simulatePlayerMove(look);
        assertEquals(90, turn.getTo().getYaw());
        // Teleports: pearls blocked, staff commands allowed.
        assertTrue(cancelled(new PlayerTeleportEvent(steve, at, moved, PlayerTeleportEvent.TeleportCause.ENDER_PEARL)));
        assertFalse(cancelled(new PlayerTeleportEvent(steve, at, moved, PlayerTeleportEvent.TeleportCause.COMMAND)));
        Block block = at.clone().add(0, -1, 0).getBlock();
        block.setType(Material.STONE);
        assertTrue(cancelled(new BlockBreakEvent(block, steve)));
        assertTrue(cancelled(new PlayerInteractEvent(steve, Action.RIGHT_CLICK_BLOCK, steve.getInventory().getItem(0),
                block, BlockFace.UP)));
        Item dropped = steve.getWorld().dropItem(at, new ItemStack(Material.DIRT));
        assertTrue(cancelled(new PlayerDropItemEvent(steve, dropped)));
        assertTrue(cancelled(new EntityPickupItemEvent(steve, dropped, 0)));
        assertTrue(cancelled(new PlayerSwapHandItemsEvent(steve, new ItemStack(Material.AIR),
                new ItemStack(Material.DIAMOND))));
        assertTrue(cancelled(new InventoryClickEvent(chestView, InventoryType.SlotType.CONTAINER, 30, ClickType.LEFT,
                InventoryAction.PICKUP_ALL)));
        assertTrue(cancelled(new InventoryOpenEvent(chestView)));
        // Hitting and being hit.
        Zombie zombie = (Zombie) steve.getWorld().spawnEntity(at, org.bukkit.entity.EntityType.ZOMBIE);
        assertTrue(cancelled(new EntityDamageByEntityEvent(steve, zombie, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK).build(), 4)));
        assertTrue(cancelled(new EntityDamageByEntityEvent(zombie, steve, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.MOB_ATTACK).build(), 4)));
        // Commands: blocked, except the allowed ones.
        assertTrue(cancelled(new PlayerCommandPreprocessEvent(steve, "/spawn")));
        assertTrue(cancelled(new PlayerCommandPreprocessEvent(steve, "/mr-punish:report Mod help")));
        assertFalse(cancelled(new PlayerCommandPreprocessEvent(steve, "/msg Mod why am I frozen")));
        assertEquals(new ItemStack(Material.DIAMOND, 2), steve.getInventory().getItem(0), "nothing moved");
        drain(steve);
        assertTrue(cancelled(new BlockBreakEvent(block, steve)));
        assertTrue(drain(steve).isEmpty(), "the blocked notice is throttled");
        // A frozen staff member may still use staff chat.
        steve.addAttachment(plugin, "mrpunish.staffchat", true);
        assertFalse(cancelled(new PlayerCommandPreprocessEvent(steve, "/sc help")));
    }

    @Test
    void unfreezeLiftsItAndWorksOffline() throws Exception {
        freezeSteve();
        drain(steve);
        commands.unfreeze(admin, "Steve");
        until(() -> !plugin.freeze().isFrozen(steve.getUniqueId()));
        assertFalse(rowSafe(steve.getUniqueId()));
        assertTrue(drain(steve).contains("MR-Punish » You are no longer frozen."));
        assertFalse(steve.simulatePlayerMove(steve.getLocation().clone().add(2, 0, 0)).isCancelled());
        // Offline.
        freezeSteve();
        steve.disconnect();
        ticks(2);
        assertTrue(rowSafe(steve.getUniqueId()), "the row stays when they log out");
        drain(admin);
        commands.unfreeze(admin, "Steve");
        List<String> said = new ArrayList<>();
        until(() -> {
            said.addAll(drain(admin));
            return said.stream().anyMatch(l -> l.contains("Unfroze"));
        });
        assertTrue(said.contains("MR-Punish » Unfroze Steve."), said.toString());
        assertFalse(rowSafe(steve.getUniqueId()));
        steve.reconnect();
        ticks(10);
        assertFalse(plugin.freeze().isFrozen(steve.getUniqueId()));
        commands.unfreeze(admin, "Steve");
        said.clear();
        until(() -> {
            said.addAll(drain(admin));
            return !said.isEmpty();
        });
        assertTrue(said.contains("MR-Punish » Steve isn't frozen."), said.toString());
    }

    @Test
    void logoutAndRejoinWhileFrozen() throws Exception {
        freezeSteve();
        drain(admin);
        steve.disconnect();
        ticks(2);
        assertTrue(drain(admin).contains("[MR] Steve logged out while frozen."));
        assertFalse(plugin.freeze().isFrozen(steve.getUniqueId()), "no effect while offline");
        assertTrue(rowSafe(steve.getUniqueId()));
        steve.reconnect();
        until(() -> plugin.freeze().isFrozen(steve.getUniqueId()));
        assertTrue(plugin.freeze().isFrozen(steve.getUniqueId()), "frozen again on join");
        assertTrue(drain(admin).contains("[MR] Steve joined and is still frozen (frozen by Mod)."));
    }

    @Test
    void onQuitBanBansThroughThePipelineAsTheFreezer() throws Exception {
        File config = new File(plugin.getDataFolder(), "config.yml");
        String text = Files.readString(config.toPath(), StandardCharsets.UTF_8);
        Files.writeString(config.toPath(), text.replace("  on-quit: NOTIFY", "  on-quit: BAN"), StandardCharsets.UTF_8);
        plugin.reload(console);
        freezeSteve();
        steve.disconnect();
        List<Punishment> bans = new ArrayList<>();
        until(() -> {
            try {
                bans.clear();
                bans.addAll(plugin.storage().executor().submit("test", c -> new PunishmentRepo(plugin.storage()
                        .database()).ofTarget(c, steve.getUniqueId())).get(5, TimeUnit.SECONDS));
            } catch (Exception e) {
                return false;
            }
            return !bans.isEmpty();
        });
        assertEquals(1, bans.size());
        Punishment ban = bans.get(0);
        assertEquals("freeze-logout", ban.reasonKey());
        assertEquals(Punishment.Origin.FREEZE_QUIT, ban.origin());
        assertEquals("Mod", ban.issuerName());
        assertEquals(7L * 24 * 3600 * 1000, ban.durationMs());
        assertTrue(rowSafe(steve.getUniqueId()), "the freeze row stays");
    }

    @Test
    void disableLiftsTheEffectAndEnableReappliesIt() throws Exception {
        steve.setAllowFlight(false);
        freezeSteve();
        plugin.freeze().disable();
        assertFalse(plugin.freeze().isFrozen(steve.getUniqueId()));
        assertFalse(steve.getAllowFlight(), "flight restored");
        assertTrue(rowSafe(steve.getUniqueId()), "the row stays");
        plugin.freeze().enableOnline();
        until(() -> plugin.freeze().isFrozen(steve.getUniqueId()));
        assertTrue(plugin.freeze().isFrozen(steve.getUniqueId()));
        server.getPluginManager().disablePlugin(plugin);
        assertFalse(steve.getAllowFlight());
    }

    @Test
    void theVoidSendsAFrozenPlayerBack() throws Exception {
        freezeSteve();
        Location start = steve.getLocation().clone();
        EntityDamageEvent voidDamage = new EntityDamageEvent(steve, EntityDamageEvent.DamageCause.VOID,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.OUT_OF_WORLD).build(), 4);
        assertTrue(cancelled(voidDamage));
        assertEquals(start.getBlockY(), steve.getLocation().getBlockY());
    }

    // ---- Inspection ----

    private static MenuHolder menu(PlayerMock p) {
        return MenuHolder.of(p.getOpenInventory().getTopInventory());
    }

    @Test
    void invseeIsALiveViewOnlyCopy() throws Exception {
        steve.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        steve.getInventory().setHelmet(new ItemStack(Material.IRON_HELMET));
        commands.invsee(mod, "Steve");
        assertEquals("InventoryView", menu(mod).getClass().getSimpleName());
        Inventory top = mod.getOpenInventory().getTopInventory();
        assertEquals(Material.DIAMOND, top.getItem(0).getType());
        assertEquals(5, top.getItem(0).getAmount());
        assertEquals(Material.IRON_HELMET, top.getItem(36).getType());
        assertEquals(Material.PLAYER_HEAD, top.getItem(49).getType());
        assertTrue(new Items().isDisplay(top.getItem(0)), "a marked copy, not the real item");
        // Live: the target's change shows within 10 ticks.
        steve.getInventory().setItem(1, new ItemStack(Material.EMERALD, 3));
        ticks(10);
        assertEquals(Material.EMERALD, mod.getOpenInventory().getTopInventory().getItem(1).getType());
        // Every click type does nothing to either inventory.
        for (ClickType type : ClickType.values()) {
            InventoryClickEvent click = type == ClickType.NUMBER_KEY
                    ? new InventoryClickEvent(mod.getOpenInventory(), InventoryType.SlotType.CONTAINER, 0, type,
                            InventoryAction.HOTBAR_SWAP, 0)
                    : new InventoryClickEvent(mod.getOpenInventory(), InventoryType.SlotType.CONTAINER, 0, type,
                            InventoryAction.PICKUP_ALL);
            server.getPluginManager().callEvent(click);
            assertTrue(click.isCancelled(), type.name());
        }
        ticks(12);
        assertEquals(new ItemStack(Material.DIAMOND, 5), steve.getInventory().getItem(0));
        for (ItemStack item : mod.getInventory().getContents()) {
            assertTrue(item == null || item.getType().isAir(), "nothing reached the viewer: " + item);
        }
        assertTrue(mod.getItemOnCursor().getType().isAir());
        // The target leaves: the view closes.
        drain(mod);
        steve.disconnect();
        ticks(2);
        assertNull(menu(mod));
        assertTrue(drain(mod).contains("MR-Punish » Steve left; the view was closed."));
    }

    @Test
    void enderseeAndRefusals() throws Exception {
        steve.getEnderChest().setItem(4, new ItemStack(Material.GOLD_INGOT, 7));
        commands.endersee(mod, "Steve");
        assertEquals("EnderView", menu(mod).getClass().getSimpleName());
        assertEquals(7, mod.getOpenInventory().getTopInventory().getItem(4).getAmount());
        steve.getEnderChest().setItem(4, null);
        ticks(10);
        assertNull(mod.getOpenInventory().getTopInventory().getItem(4));
        drain(mod);
        commands.invsee(mod, "Mod");
        assertEquals(List.of("MR-Punish » Use your own inventory."), drain(mod));
        commands.invsee(steve, "Mod");
        assertEquals(List.of("MR-Punish » You don't have permission to do that."), drain(steve));
        commands.invsee(console, "Steve");
        assertEquals("MR-Punish » Only players can use this command.", plain(console.nextComponentMessage()));
        commands.invsee(mod, "Nobody");
        assertEquals(List.of("MR-Punish » Nobody isn't online."), drain(mod));
    }

    @Test
    void midAirFreezeFliesSoNobodyIsKickedAndFlightIsRestored() throws Exception {
        steve.teleport(steve.getLocation().clone().add(0, 20, 0));
        steve.setAllowFlight(false);
        freezeSteve();
        if (!((org.bukkit.entity.Entity) steve).isOnGround()) {
            assertTrue(steve.getAllowFlight());
            assertTrue(steve.isFlying());
        }
        commands.unfreeze(admin, "Steve");
        until(() -> !plugin.freeze().isFrozen(steve.getUniqueId()));
        assertFalse(steve.getAllowFlight());
        assertFalse(steve.isFlying());
    }
}
