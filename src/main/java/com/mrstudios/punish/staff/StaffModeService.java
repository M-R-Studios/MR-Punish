package com.mrstudios.punish.staff;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.config.GuiSettings;
import com.mrstudios.punish.config.ItemTemplate;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.gui.HistoryMenus;
import com.mrstudios.punish.gui.InspectMenus;
import com.mrstudios.punish.gui.Items;
import com.mrstudios.punish.gui.MenuHolder;
import com.mrstudios.punish.gui.PunishMenus;
import com.mrstudios.punish.gui.ReportMenus;
import com.mrstudios.punish.rank.Authority;
import com.mrstudios.punish.report.ReportService;
import com.mrstudios.punish.storage.ActionRepo;
import com.mrstudios.punish.storage.ReportRepo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scoreboard.Scoreboard;

/**
 * Staff mode (DESIGN.md 11.1). Entering saves everything it changes into the player's PDC
 * ({@link StaffModeState}) in the same tick as it clears the inventory, so the player's data file
 * always has one or the other. {@link #exit} is the single way out, used by the command, a forced
 * exit, quit (and so kick), death, plugin disable, losing the permission, and a join that finds the
 * state left over from a crash. Exit reads the state from the PDC, never from memory.
 */
public final class StaffModeService implements Listener {

    public static final String PERMISSION = "mrpunish.staffmode";
    private static final String STAFF = "mrpunish.staff";

    /** Why staff mode ended (logged). */
    public enum Cause {
        COMMAND, FORCED, QUIT, DEATH, DISABLE, PERMISSION, RESTORED_ON_JOIN
    }

    /** The tools, in hotbar order, each with the permission it needs. */
    public enum Tool {
        TELEPORT("teleport", PERMISSION), RANDOM("random", PERMISSION), INSPECT("inspect", InspectMenus.INVSEE),
        FREEZE("freeze", FreezeService.PERMISSION), PUNISH("punish", "mrpunish.punish"),
        VANISH("vanish", VanishService.USE), REPORTS("reports", ReportService.REPORTS),
        HUB("hub", com.mrstudios.punish.command.Perms.STAFF);

        final String id;
        final String permission;

        Tool(String id, String permission) {
            this.id = id;
            this.permission = permission;
        }

        static Tool of(String id) {
            for (Tool t : values()) {
                if (t.id.equals(id)) {
                    return t;
                }
            }
            return null;
        }
    }

    private final MRPunish plugin;
    private final Items items;
    private final NamespacedKey stateKey = new NamespacedKey("mrpunish", "staffmode");
    private final NamespacedKey brokenKey = new NamespacedKey("mrpunish", "staffmode-unreadable");
    private final NamespacedKey toolKey = new NamespacedKey("mrpunish", "tool");
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Scoreboard> previousBoards = new ConcurrentHashMap<>();
    private final Map<UUID, StaffBoard> boards = new ConcurrentHashMap<>();
    private int boardClock;

    public StaffModeService(MRPunish plugin, Items items) {
        this.plugin = plugin;
        this.items = items;
    }

    private PluginState state() {
        return plugin.state();
    }

    private Settings.StaffMode settings() {
        return state().settings().staffMode();
    }

    public boolean inStaffMode(UUID uuid) {
        return active.contains(uuid);
    }

    /** Whether {@code item} is a staff mode tool (by its PDC tag). */
    public boolean isTool(ItemStack item) {
        return item != null && !item.getType().isAir() && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(toolKey, PersistentDataType.STRING);
    }

    private Tool toolOf(ItemStack item) {
        if (!isTool(item)) {
            return null;
        }
        return Tool.of(item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING));
    }

    // ---- Enter ----

    /** Enters staff mode; returns the refusal message key's {@code why} part, or null on success. */
    public String enter(Player p) {
        if (inStaffMode(p.getUniqueId())) {
            return null;
        }
        if (plugin.freeze().isFrozen(p.getUniqueId())) {
            return "staff.staffmode-why-frozen";
        }
        if (p.isDead()) {
            return "staff.staffmode-why-dead";
        }
        if (p.isInsideVehicle()) {
            return "staff.staffmode-why-vehicle";
        }
        ItemStack cursor = p.getItemOnCursor();
        boolean holding = cursor != null && !cursor.getType().isAir();
        if (holding && p.getInventory().firstEmpty() == -1) {
            return "staff.staffmode-why-full";
        }
        // The cursor and the 2x2 crafting grid go back into the inventory before it is saved.
        p.closeInventory();
        StaffModeState saved = StaffModeState.capture(p, plugin.vanish().isVanished(p.getUniqueId()),
                plugin.staffChat().toggledOn(p.getUniqueId()), System.currentTimeMillis());
        byte[] bytes;
        try {
            bytes = saved.toBytes();
            StaffModeState.fromBytes(bytes); // must read back before anything is cleared
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Couldn't save " + p.getName() + "'s state for staff mode;"
                    + " nothing was changed", e);
            return "staff.staffmode-why-failed";
        }
        p.getPersistentDataContainer().set(stateKey, PersistentDataType.BYTE_ARRAY, bytes);
        active.add(p.getUniqueId());
        // Same tick as the save: the data file has either the old inventory or this state.
        p.getInventory().clear();
        for (PotionEffect effect : p.getActivePotionEffects()) {
            p.removePotionEffect(effect.getType());
        }
        p.setGameMode(settings().gameMode());
        p.setAllowFlight(true);
        p.setFlying(true);
        var maxHealth = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(maxHealth == null ? 20.0 : maxHealth.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setFireTicks(0);
        p.setFallDistance(0);
        giveTools(p);
        if (settings().vanish() && !plugin.vanish().isVanished(p.getUniqueId())) {
            plugin.vanish().vanish(p);
        }
        if (settings().scoreboard()) {
            previousBoards.put(p.getUniqueId(), p.getScoreboard());
            StaffBoard board = new StaffBoard(plugin, p);
            boards.put(p.getUniqueId(), board);
            board.update();
        }
        log(p, "STAFFMODE_ON", null);
        return null;
    }

    private void giveTools(Player p) {
        GuiSettings g = state().gui();
        for (Tool tool : Tool.values()) {
            if (!p.hasPermission(tool.permission)) {
                continue;
            }
            String path = tool == Tool.VANISH ? (plugin.vanish().isVanished(p.getUniqueId())
                    || settings().vanish() ? "staff-tools.vanish-on" : "staff-tools.vanish-off")
                    : "staff-tools." + tool.id;
            p.getInventory().setItem(g.number(path + ".slot"), tool(g.item(path), tool));
        }
    }

    private ItemStack tool(ItemTemplate template, Tool tool) {
        ItemStack item = items.build(template, TagResolver.empty());
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, tool.id);
        // Not a display item: tools live in the staff member's inventory.
        meta.getPersistentDataContainer().remove(new NamespacedKey(Items.NAMESPACE, "display"));
        item.setItemMeta(meta);
        return item;
    }

    /** The vanish tool shows the current state (after any vanish toggle). */
    public void refreshVanishTool(Player p) {
        if (!inStaffMode(p.getUniqueId()) || !p.hasPermission(VanishService.USE)) {
            return;
        }
        GuiSettings g = state().gui();
        String path = plugin.vanish().isVanished(p.getUniqueId()) ? "staff-tools.vanish-on" : "staff-tools.vanish-off";
        p.getInventory().setItem(g.number(path + ".slot"), tool(g.item(path), Tool.VANISH));
    }

    // ---- Exit ----

    /**
     * Ends staff mode and restores everything from the PDC. Safe to call for anyone: without a
     * stored state it does nothing. Returns whether there was a state to restore.
     */
    public boolean exit(Player p, Cause cause) {
        byte[] bytes = p.getPersistentDataContainer().get(stateKey, PersistentDataType.BYTE_ARRAY);
        boolean wasActive = active.remove(p.getUniqueId());
        if (bytes == null) {
            stopBoard(p);
            return wasActive;
        }
        if (cause != Cause.QUIT && cause != Cause.DISABLE) {
            p.closeInventory();
        }
        StaffModeState saved;
        try {
            saved = StaffModeState.fromBytes(bytes);
        } catch (IllegalArgumentException e) {
            // Never lose it: kept under another key for an admin, and the player isn't stuck.
            p.getPersistentDataContainer().set(brokenKey, PersistentDataType.BYTE_ARRAY, bytes);
            p.getPersistentDataContainer().remove(stateKey);
            removeTools(p);
            stopBoard(p);
            plugin.getLogger().severe("Couldn't read " + p.getName() + "'s saved staff mode state (" + e.getMessage()
                    + "). It was kept in their data under mrpunish:staffmode-unreadable; their items weren't restored.");
            return true;
        }
        p.getInventory().clear();
        p.getInventory().setContents(saved.contents());
        for (PotionEffect effect : p.getActivePotionEffects()) {
            p.removePotionEffect(effect.getType());
        }
        if (cause != Cause.DEATH && settings().exitLocation() == Settings.ExitLocation.RETURN) {
            World world = saved.world() == null ? null : plugin.getServer().getWorld(saved.world());
            if (world != null) {
                p.teleport(new Location(world, saved.x(), saved.y(), saved.z(), saved.yaw(), saved.pitch()));
            } else {
                plugin.getLogger().warning("The world " + p.getName() + " entered staff mode in is gone; they stay"
                        + " where they are.");
            }
        }
        p.setGameMode(saved.gameMode());
        p.setAllowFlight(saved.allowFlight());
        p.setFlying(saved.allowFlight() && saved.flying());
        var maxHealth = p.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHealth == null ? 20.0 : maxHealth.getValue();
        if (!p.isDead()) {
            p.setHealth(Math.max(0.5, Math.min(saved.health(), max)));
        }
        p.setFoodLevel(saved.food());
        p.setSaturation(saved.saturation());
        p.setExhaustion(saved.exhaustion());
        p.setLevel(saved.level());
        p.setExp(saved.exp());
        p.setTotalExperience(saved.totalExperience());
        for (PotionEffect effect : saved.potionEffects()) {
            p.addPotionEffect(effect);
        }
        p.setFireTicks(saved.fireTicks());
        p.setFallDistance(saved.fallDistance());
        p.setRemainingAir(saved.remainingAir());
        p.setInvulnerable(saved.invulnerable());
        if (!saved.wasVanished() && plugin.vanish().isVanished(p.getUniqueId())) {
            plugin.vanish().unvanish(p);
        }
        stopBoard(p);
        // Same tick as the restore.
        p.getPersistentDataContainer().remove(stateKey);
        log(p, "STAFFMODE_OFF", cause.name());
        return true;
    }

    private void removeTools(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isTool(contents[i])) {
                p.getInventory().setItem(i, null);
            }
        }
    }

    private void stopBoard(Player p) {
        StaffBoard board = boards.remove(p.getUniqueId());
        Scoreboard previous = previousBoards.remove(p.getUniqueId());
        if (board != null || previous != null) {
            p.setScoreboard(previous != null ? previous : plugin.getServer().getScoreboardManager().getMainScoreboard());
        }
    }

    private void log(Player p, String action, String details) {
        if (plugin.storage().executor().isShutDown() || !plugin.storage().health().isUp()) {
            return;
        }
        long now = System.currentTimeMillis();
        String server = state().settings().effectiveServerName();
        plugin.storage().executor().submit("logging staff mode", c -> {
            new ActionRepo(plugin.storage().database()).insert(c, new ActionRepo.Action(now, server, p.getUniqueId(),
                    p.getName(), action, p.getUniqueId(), p.getName(), null, details));
            return null;
        });
    }

    // ---- Lifecycle ----

    /** A state left in the PDC means staff mode didn't end (crash, plugin removed): restore it now. */
    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (p.getPersistentDataContainer().has(stateKey, PersistentDataType.BYTE_ARRAY)) {
            exit(p, Cause.RESTORED_ON_JOIN);
            state().messages().send(p, "staff.staffmode-restored-join");
        } else {
            // A stray tool (e.g. copied by another plugin) never stays outside staff mode.
            removeTools(p);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        exit(event.getPlayer(), Cause.QUIT);
    }

    /** Nothing drops: the tools vanish, and the real inventory is in the PDC, not on the player. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!inStaffMode(event.getEntity().getUniqueId())) {
            return;
        }
        event.getDrops().clear();
        event.setKeepInventory(false);
        event.setDroppedExp(0);
        event.setKeepLevel(false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerPostRespawnEvent event) {
        Player p = event.getPlayer();
        if (p.getPersistentDataContainer().has(stateKey, PersistentDataType.BYTE_ARRAY)) {
            exit(p, Cause.DEATH);
            state().messages().send(p, "staff.staffmode-off");
        }
    }

    /** Plugin enable with players online (after /reload): a leftover state is restored. */
    public void enableOnline() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getPersistentDataContainer().has(stateKey, PersistentDataType.BYTE_ARRAY)) {
                exit(p, Cause.RESTORED_ON_JOIN);
                state().messages().send(p, "staff.staffmode-restored-join");
            }
        }
    }

    /** Plugin disable: everyone leaves staff mode with everything restored. */
    public void disable() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (exit(p, Cause.DISABLE)) {
                state().messages().send(p, "staff.staffmode-off");
            }
        }
        active.clear();
    }

    /** The 60 s rank refresh: someone who lost {@code mrpunish.staffmode} leaves it. */
    public void checkPermissions() {
        for (UUID uuid : Set.copyOf(active)) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null && !p.hasPermission(PERMISSION)) {
                exit(p, Cause.PERMISSION);
                state().messages().send(p, "staff.staffmode-off");
            }
        }
    }

    /** Every tick: the scoreboards update every {@code update-ticks}. */
    public void tick() {
        if (boards.isEmpty()) {
            boardClock = 0;
            return;
        }
        if (++boardClock < state().scoreboard().updateTicks()) {
            return;
        }
        boardClock = 0;
        for (StaffBoard board : boards.values()) {
            board.update();
        }
    }

    // ---- Tools ----

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!inStaffMode(p.getUniqueId())) {
            return;
        }
        Tool tool = toolOf(event.getItem());
        if (tool == null) {
            if (!settings().allowBuild() && event.getAction() != Action.LEFT_CLICK_AIR
                    && event.getAction() != Action.RIGHT_CLICK_AIR) {
                // Blocks: containers open if allowed (view-only), nothing else is used.
                boolean container = event.getClickedBlock() != null
                        && event.getClickedBlock().getState() instanceof org.bukkit.block.Container;
                if (!(container && settings().allowContainers() && event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
                    event.setCancelled(true);
                }
            }
            return;
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && settings().allowContainers()
                && event.getClickedBlock() != null
                && event.getClickedBlock().getState() instanceof org.bukkit.block.Container) {
            // Staff always hold a tool: a container still opens (view-only); the tool isn't used.
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        if (!p.hasPermission(tool.permission)) {
            state().messages().send(p, "general.no-permission");
            return;
        }
        switch (tool) {
            case TELEPORT -> new PlayerPicker(p, 0).open(p);
            case RANDOM -> randomTeleport(p);
            case VANISH -> {
                boolean now = plugin.vanish().toggle(p);
                plugin.logAction(p, now ? "VANISH_ON" : "VANISH_OFF", p.getUniqueId(), p.getName(), null, null);
                state().messages().send(p, now ? "staff.vanish-on" : "staff.vanish-off");
                refreshVanishTool(p);
            }
            case REPORTS -> plugin.reportMenus().openList(p, new ReportMenus.View(ReportRepo.Filter.OPEN));
            case HUB -> plugin.staffMenus().openHub(p);
            default -> { } // used on a player
        }
    }

    /** Tools used on a player: only real players, only with the main hand. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseOn(PlayerInteractEntityEvent event) {
        Player p = event.getPlayer();
        if (!inStaffMode(p.getUniqueId())) {
            return;
        }
        // Staff don't manipulate item frames, armour stands or anything else.
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Tool tool = toolOf(p.getInventory().getItemInMainHand());
        Entity clicked = event.getRightClicked();
        if (tool == null || !(clicked instanceof Player target) || clicked.hasMetadata("NPC")
                || plugin.getServer().getPlayer(target.getUniqueId()) != target) {
            return;
        }
        if (!p.hasPermission(tool.permission)) {
            state().messages().send(p, "general.no-permission");
            return;
        }
        switch (tool) {
            case INSPECT -> {
                if (p.isSneaking()) {
                    if (p.hasPermission(InspectMenus.ENDERSEE)) {
                        plugin.inspect().openEnderChest(p, target);
                    } else {
                        state().messages().send(p, "general.no-permission");
                    }
                } else {
                    plugin.inspect().openInventory(p, target);
                }
            }
            case FREEZE -> toggleFreeze(p, target);
            case PUNISH -> plugin.menus().openMain(p, new PunishMenus.Flow(target.getUniqueId(), target.getName(),
                    null, false, false));
            default -> { }
        }
    }

    private void toggleFreeze(Player staff, Player target) {
        Authority.Standing actor = plugin.rankCache().standing(staff);
        if (plugin.freeze().isFrozen(target.getUniqueId())) {
            plugin.freeze().unfreeze(actor, target.getUniqueId(), target.getName()).thenAccept(outcome -> {
                if (outcome instanceof FreezeService.Outcome.Done) {
                    state().messages().send(staff, "staff.unfreeze-done", Placeholder.unparsed("player", target.getName()));
                }
            });
            return;
        }
        plugin.freeze().freeze(staff, actor, target).thenAccept(outcome -> {
            switch (outcome) {
                case FreezeService.Outcome.Done d -> state().messages().send(staff, "staff.freeze-done",
                        Placeholder.unparsed("player", target.getName()));
                case FreezeService.Outcome.Refused r -> state().messages().send(staff,
                        r.verdict() == Authority.Verdict.EXEMPT ? "punish.exempt" : "staff.freeze-rank",
                        Placeholder.unparsed("player", target.getName()));
                case FreezeService.Outcome.Failed f -> state().messages().send(staff, "staff.freeze-failed",
                        Placeholder.unparsed("error-id", f.errorId()));
                case FreezeService.Outcome.Unavailable u -> state().messages().send(staff,
                        "general.storage-unavailable");
                default -> { }
            }
        });
    }

    private void randomTeleport(Player staff) {
        List<Player> candidates = new ArrayList<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (!p.equals(staff) && !p.hasPermission(STAFF) && plugin.vanish().canSee(staff, p)) {
                candidates.add(p);
            }
        }
        if (candidates.isEmpty()) {
            state().messages().send(staff, "staff.tool-random-none");
            return;
        }
        Player target = candidates.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.size()));
        staff.teleport(target.getLocation());
        state().messages().send(staff, "staff.tool-teleported", Placeholder.unparsed("player", target.getName()));
    }

    /** The teleport tool's list of online players the staff member can see. */
    final class PlayerPicker extends MenuHolder {

        private final int page;
        private final List<Player> shown = new ArrayList<>();
        private final int pages;

        PlayerPicker(Player viewer, int page) {
            super(viewer.getUniqueId(), state().gui().number("player-picker.rows"), title(viewer, page));
            List<Player> all = candidates(viewer);
            this.pages = HistoryMenus.pages(all.size(), 45);
            this.page = HistoryMenus.clamp(page, pages);
            shown.addAll(all.subList(Math.min(this.page * 45, all.size()), Math.min(this.page * 45 + 45, all.size())));
        }

        @Override
        protected void draw() {
            GuiSettings g = state().gui();
            ItemTemplate template = new ItemTemplate("PLAYER_HEAD", g.text("player-picker.entry-name"),
                    g.lines("player-picker.entry-lore"), false, null);
            int slot = 0;
            for (Player target : shown) {
                set(slot++, items.head(target.getUniqueId(), target.getName(), template, TagResolver.resolver(
                        Placeholder.unparsed("player", target.getName()),
                        Placeholder.unparsed("world", target.getWorld().getName())), Map.of()), (who, click) -> {
                            if (!who.hasPermission(PERMISSION) || !inStaffMode(who.getUniqueId())) {
                                state().messages().send(who, "general.no-permission");
                                who.closeInventory();
                                return;
                            }
                            who.closeInventory();
                            if (target.isOnline()) {
                                who.teleport(target.getLocation());
                                state().messages().send(who, "staff.tool-teleported",
                                        Placeholder.unparsed("player", target.getName()));
                            }
                        });
            }
            if (page > 0) {
                set(g.number("player-picker.previous-slot"), items.build(g.item("common.previous"), TagResolver.empty()),
                        (who, click) -> new PlayerPicker(who, page - 1).open(who));
            }
            if (page < pages - 1) {
                set(g.number("player-picker.next-slot"), items.build(g.item("common.next"), TagResolver.empty()),
                        (who, click) -> new PlayerPicker(who, page + 1).open(who));
            }
            set(g.number("player-picker.close-slot"), items.build(g.item("common.close"), TagResolver.empty()),
                    (who, click) -> who.closeInventory());
            fill(items.build(g.item("filler"), TagResolver.empty()));
        }
    }

    private List<Player> candidates(Player viewer) {
        List<Player> all = new ArrayList<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (!p.equals(viewer) && plugin.vanish().canSee(viewer, p)) {
                all.add(p);
            }
        }
        all.sort(java.util.Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    private net.kyori.adventure.text.Component title(Player viewer, int page) {
        int pages = HistoryMenus.pages(candidates(viewer).size(), 45);
        return Items.render(state().gui().text("player-picker.title"), TagResolver.resolver(
                Placeholder.unparsed("page", String.valueOf(HistoryMenus.clamp(page, pages) + 1)),
                Placeholder.unparsed("pages", String.valueOf(pages))));
    }

    // ---- Protection ----

    private boolean guarded(Entity entity) {
        return entity instanceof Player p && inStaffMode(p.getUniqueId());
    }

    /**
     * In staff mode nothing in the inventory moves (MR-Punish's own menus handle their clicks), and a
     * real container is view-only.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (guarded(event.getWhoClicked()) && MenuHolder.of(event.getView().getTopInventory()) == null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (guarded(event.getWhoClicked()) && MenuHolder.of(event.getView().getTopInventory()) == null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onOpen(InventoryOpenEvent event) {
        if (guarded(event.getPlayer()) && MenuHolder.of(event.getInventory()) == null
                && event.getInventory().getType() != InventoryType.PLAYER && !settings().allowContainers()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (guarded(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (guarded(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (guarded(event.getEntity()) && !settings().allowPickup()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (guarded(event.getPlayer()) && (!settings().allowBuild() || isTool(event.getItemInHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        if (guarded(event.getPlayer()) && !settings().allowBuild()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (guarded(event.getPlayer()) && !settings().allowBuild()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (guarded(event.getPlayer()) && !settings().allowBuild()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (guarded(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamaged(EntityDamageEvent event) {
        if (guarded(event.getEntity()) && event.getCause() != EntityDamageEvent.DamageCause.VOID
                && event.getCause() != EntityDamageEvent.DamageCause.KILL) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            damager = shooter;
        }
        if (guarded(damager)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHunger(FoodLevelChangeEvent event) {
        if (guarded(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (guarded(event.getTarget())) {
            event.setCancelled(true);
        }
    }
}
