package com.mrstudios.punish.gui;

import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * DESIGN.md 10.1: a menu item outside a menu means a bug or an exploit. It is removed wherever it
 * shows up (dropped, picked up, in a player's inventory or on their cursor), with a warning.
 */
public final class DisplayGuard implements Listener {

    private final Items items;
    private final Logger log;

    public DisplayGuard(Items items, Logger log) {
        this.items = items;
        this.log = log;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawn(ItemSpawnEvent event) {
        if (items.isDisplay(event.getEntity().getItemStack())) {
            event.setCancelled(true);
            log.warning("Removed an MR-Punish menu item that was dropped. Menu items should never leave a menu.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (items.isDisplay(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
            log.warning("Removed an MR-Punish menu item that " + event.getEntity().getName() + " tried to pick up.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            sweep(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        sweep(event.getPlayer());
    }

    /** Removes every menu item from the player's inventory and cursor. Returns how many were found. */
    public int sweep(Player player) {
        int found = 0;
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (items.isDisplay(item)) {
                inventory.setItem(slot, null);
                found++;
            }
        }
        if (items.isDisplay(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
            found++;
        }
        if (found > 0) {
            log.warning("Removed " + found + " MR-Punish menu item(s) from " + player.getName()
                    + "'s inventory. Menu items should never leave a menu.");
        }
        return found;
    }
}
