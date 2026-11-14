package com.mrstudios.punish.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.plugin.Plugin;

/**
 * Every click and drag while an MR-Punish menu is open is cancelled first, in the menu and in the
 * player's own inventory below it (DESIGN.md 10.1); only then may {@link ClickRules} run a button.
 * LOWEST and not ignoring cancelled events, so no other plugin can let a click through first.
 */
public final class MenuListener implements Listener {

    private final Plugin plugin;

    public MenuListener(Plugin plugin) {
        this.plugin = plugin;
    }

    private static MenuHolder menu(org.bukkit.inventory.Inventory inventory) {
        return MenuHolder.of(inventory);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        MenuHolder menu = menu(event.getView().getTopInventory());
        if (menu == null) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(menu.viewer())) {
            return;
        }
        // Geyser and some clients need the real contents back after a cancelled click.
        plugin.getServer().getScheduler().runTask(plugin, player::updateInventory);
        int raw = event.getRawSlot();
        if (!ClickRules.runsButton(raw, event.getView().getTopInventory().getSize(), event.getClick(),
                menu.hasButton(raw))) {
            return;
        }
        MenuHolder.Action action = menu.button(raw);
        // Opening, closing and anything that reaches the database happen after this event.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (menu.isShowing() && menu.button(raw) == action) {
                action.run(player, event.getClick());
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (menu(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(InventoryMoveItemEvent event) {
        if (menu(event.getSource()) != null || menu(event.getDestination()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        MenuHolder menu = menu(event.getInventory());
        if (menu == null) {
            return;
        }
        MenuOpener opener = MenuOpener.current();
        if (opener != null && event.getPlayer() instanceof Player player
                && event.getReason() == InventoryCloseEvent.Reason.PLAYER && player.getUniqueId().equals(menu.viewer())
                && opener.isStrayClose(player)) {
            // A Bedrock client's late close of the screen before this one: the same menu, with its
            // state, comes back (a confirmation isn't lost).
            plugin.getLogger().fine("Ignored a stray menu close from " + player.getName() + " (Bedrock); reopening.");
            menu.markClosed();
            opener.open(menu, player);
            return;
        }
        menu.markClosed();
    }
}
