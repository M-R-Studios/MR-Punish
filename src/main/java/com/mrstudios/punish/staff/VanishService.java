package com.mrstudios.punish.staff;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.rank.RankCache;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Vanish (DESIGN.md 11.2). The state is an in-memory set plus, with {@code vanish.persist}, the PDC
 * byte {@code mrpunish:vanished} on the player, so a vanished player is vanished again after a
 * relog or restart. Visibility uses {@code hidePlayer}/{@code showPlayer} (which the server never
 * saves, so a crash can't leave anyone hidden for good) and the {@code vanished} metadata other
 * plugins read. Every way out undoes it: unvanish, quit, losing {@code mrpunish.vanish}, and plugin
 * disable (everyone is shown again; the PDC byte stays so re-enabling vanishes them again).
 */
public final class VanishService implements Listener {

    public static final String USE = "mrpunish.vanish";
    public static final String OTHERS = "mrpunish.vanish.others";
    public static final String SEE = "mrpunish.vanish.see";
    /** The metadata EssentialsX, TAB, DiscordSRV and others check. */
    public static final String METADATA = "vanished";

    private final Plugin plugin;
    private final Supplier<PluginState> state;
    private final RankCache ranks;
    private final NamespacedKey key;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Boolean> sleepingBefore = new HashMap<>();
    private int reminderClock;
    /** Told when someone becomes hidden or visible (EssentialsX). Main thread. */
    private volatile java.util.function.BiConsumer<Player, Boolean> hiddenHook = (player, hidden) -> { };

    public void onHiddenChange(java.util.function.BiConsumer<Player, Boolean> hook) {
        this.hiddenHook = hook;
    }

    public VanishService(Plugin plugin, Supplier<PluginState> state, RankCache ranks) {
        this.plugin = plugin;
        this.state = state;
        this.ranks = ranks;
        this.key = new NamespacedKey("mrpunish", "vanished");
    }

    private Settings.Vanish settings() {
        return state.get().settings().vanish();
    }

    public boolean isVanished(UUID uuid) {
        return vanished.contains(uuid);
    }

    /** How many vanished players are online. */
    public int count() {
        return vanished.size();
    }

    /** Whether {@code viewer} may see {@code target} now (permission and rank read now). */
    public boolean canSee(Player viewer, Player target) {
        return VisibilityRule.sees(viewer.getUniqueId().equals(target.getUniqueId()), isVanished(target.getUniqueId()),
                viewer.hasPermission(SEE), ranks.read(viewer).weight(), ranks.read(target).weight());
    }

    private void apply(Player viewer, Player target) {
        if (viewer.equals(target)) {
            return;
        }
        if (canSee(viewer, target)) {
            viewer.showPlayer(plugin, target);
        } else {
            viewer.hidePlayer(plugin, target);
        }
    }

    /** Everyone against {@code target}. */
    private void refreshTarget(Player target) {
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            apply(viewer, target);
        }
    }

    /** {@code viewer} against every vanished player. */
    private void refreshViewer(Player viewer) {
        for (UUID uuid : vanished) {
            Player target = plugin.getServer().getPlayer(uuid);
            if (target != null) {
                apply(viewer, target);
            }
        }
    }

    /**
     * Every pair again (the 60 s rank refresh, {@code /mrpunish reload}). A vanished player who has
     * lost {@code mrpunish.vanish} is made visible, so nobody is stuck invisible.
     */
    public void refreshAll() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (isVanished(p.getUniqueId()) && !p.hasPermission(USE)) {
                unvanish(p);
                state.get().messages().send(p, "staff.vanish-lost-permission");
                continue;
            }
            refreshViewer(p);
        }
    }

    /**
     * Who sees whom again, without the permission check (right after the plugin enabled, when a
     * permissions plugin may still be loading online players after a /reload).
     */
    public void refreshVisibility() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            refreshViewer(p);
        }
    }

    public void vanish(Player p) {
        if (!vanished.add(p.getUniqueId())) {
            return;
        }
        p.setMetadata(METADATA, new FixedMetadataValue(plugin, true));
        sleepingBefore.put(p.getUniqueId(), p.isSleepingIgnored());
        p.setSleepingIgnored(true);
        if (settings().persist()) {
            p.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        }
        refreshTarget(p);
        hiddenHook.accept(p, true);
    }

    public void unvanish(Player p) {
        p.getPersistentDataContainer().remove(key);
        if (!vanished.remove(p.getUniqueId())) {
            return;
        }
        p.removeMetadata(METADATA, plugin);
        Boolean sleeping = sleepingBefore.remove(p.getUniqueId());
        p.setSleepingIgnored(sleeping != null && sleeping);
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            viewer.showPlayer(plugin, p);
        }
        hiddenHook.accept(p, false);
    }

    /** Toggles; returns whether {@code p} is vanished now. */
    public boolean toggle(Player p) {
        if (isVanished(p.getUniqueId())) {
            unvanish(p);
            return false;
        }
        vanish(p);
        return true;
    }

    private void toSeers(Player vanishedPlayer, String key) {
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (!viewer.equals(vanishedPlayer) && canSee(viewer, vanishedPlayer)) {
                state.get().messages().send(viewer, key, Placeholder.unparsed("player", vanishedPlayer.getName()));
            }
        }
    }

    /**
     * A joining player (or one already online when the plugin enables): vanished again if they
     * were (PDC), and hidden from them whoever they may not see. Returns whether they're vanished.
     */
    private boolean restore(Player p, boolean checkPermission) {
        boolean marked = p.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
        boolean again = false;
        if (marked) {
            if (!settings().persist()) {
                p.getPersistentDataContainer().remove(key);
            } else if (checkPermission && !p.hasPermission(USE)) {
                p.getPersistentDataContainer().remove(key);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (p.isOnline()) {
                        state.get().messages().send(p, "staff.vanish-lost-permission");
                    }
                });
            } else {
                vanish(p);
                again = true;
            }
        }
        refreshViewer(p);
        return again;
    }

    // ---- Events ----

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // At join the permissions plugin has loaded the player (it does so before the join).
        if (restore(p, true)) {
            event.joinMessage(null);
            toSeers(p, "staff.vanish-joined");
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && isVanished(p.getUniqueId())) {
                    state.get().messages().send(p, "staff.vanish-still");
                    // Again after every join handler, in case another plugin reset it at join.
                    hiddenHook.accept(p, true);
                }
            });
        }
    }

    /** Again last, so no other plugin's join message gives a vanished player away. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoinLate(PlayerJoinEvent event) {
        if (isVanished(event.getPlayer().getUniqueId())) {
            event.joinMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (isVanished(p.getUniqueId())) {
            event.quitMessage(null);
            toSeers(p, "staff.vanish-left");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuitLate(PlayerQuitEvent event) {
        if (isVanished(event.getPlayer().getUniqueId())) {
            event.quitMessage(null);
        }
    }

    /**
     * A vanished player's advancement isn't announced to everyone: only those who can see them
     * (and the console) get the message.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(org.bukkit.event.player.PlayerAdvancementDoneEvent event) {
        net.kyori.adventure.text.Component message = event.message();
        if (message != null && isVanished(event.getPlayer().getUniqueId())) {
            event.message(null);
            toSeers(event.getPlayer(), message);
        }
    }

    /** The same for a vanished player's death message. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        net.kyori.adventure.text.Component message = event.deathMessage();
        if (message != null && isVanished(event.getPlayer().getUniqueId())) {
            event.deathMessage(null);
            toSeers(event.getPlayer(), message);
        }
    }

    private void toSeers(Player vanishedPlayer, net.kyori.adventure.text.Component message) {
        for (Player viewer : plugin.getServer().getOnlinePlayers()) {
            if (viewer.equals(vanishedPlayer) || canSee(viewer, vanishedPlayer)) {
                viewer.sendMessage(message);
            }
        }
        plugin.getServer().getConsoleSender().sendMessage(message);
    }

    /** Leaves the PDC byte (persist) but drops everything else. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitDone(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (vanished.remove(p.getUniqueId())) {
            p.removeMetadata(METADATA, plugin);
            Boolean sleeping = sleepingBefore.remove(p.getUniqueId());
            p.setSleepingIgnored(sleeping != null && sleeping);
            if (!settings().persist()) {
                p.getPersistentDataContainer().remove(key);
                hiddenHook.accept(p, false);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(PaperServerListPingEvent event) {
        if (!settings().hideFromServerList() || vanished.isEmpty()) {
            return;
        }
        event.getListedPlayers().removeIf(info -> vanished.contains(info.id()));
        int online = 0;
        for (UUID uuid : vanished) {
            if (plugin.getServer().getPlayer(uuid) != null) {
                online++;
            }
        }
        event.setNumPlayers(Math.max(0, event.getNumPlayers() - online));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p && isVanished(p.getUniqueId()) && settings().blockPickup()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    @SuppressWarnings("deprecation")
    public void onArrow(PlayerPickupArrowEvent event) {
        if (isVanished(event.getPlayer().getUniqueId()) && settings().blockPickup()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPhysical(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL && isVanished(event.getPlayer().getUniqueId())
                && settings().blockPhysical()) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player p && isVanished(p.getUniqueId()) && settings().noMobTargeting()) {
            event.setCancelled(true);
        }
    }

    // ---- Lifecycle ----

    /**
     * Plugin enable with players online (after /reload): re-apply from the PDC, quietly. The
     * permission isn't checked here: after a /reload the permissions plugin may still be loading
     * online players, and a vanish must not be lost to that. The rank refresh checks it later.
     */
    public void enableOnline() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            restore(p, false);
        }
    }

    /**
     * Plugin disable: everyone is shown to everyone (no client is left with hidden entries) and the
     * metadata is removed. The PDC byte stays, so enabling again vanishes them again.
     */
    public void disable() {
        for (UUID uuid : Set.copyOf(vanished)) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p == null) {
                continue;
            }
            p.removeMetadata(METADATA, plugin);
            Boolean sleeping = sleepingBefore.remove(uuid);
            p.setSleepingIgnored(sleeping != null && sleeping);
            for (Player viewer : plugin.getServer().getOnlinePlayers()) {
                viewer.showPlayer(plugin, p);
            }
            hiddenHook.accept(p, false);
        }
        vanished.clear();
    }

    /** Every second: the action-bar reminder every {@code vanish.reminder-seconds}. */
    public void tick() {
        int every = settings().reminderSeconds();
        if (every <= 0 || vanished.isEmpty()) {
            reminderClock = 0;
            return;
        }
        if (++reminderClock < every) {
            return;
        }
        reminderClock = 0;
        for (UUID uuid : vanished) {
            Player p = plugin.getServer().getPlayer(uuid);
            if (p != null) {
                p.sendActionBar(state.get().messages().component("staff.vanish-reminder", TagResolver.empty()));
            }
        }
    }
}
