package com.mrstudios.punish.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * One open MR-Punish menu (DESIGN.md 10.1). The holder knows its viewer, its buttons and a render
 * version: data loaded asynchronously is only filled in if the same render is still showing. A
 * menu is a virtual inventory; nothing in it is a real item.
 */
public abstract class MenuHolder implements InventoryHolder {

    /** What a button does when clicked (main thread, after the click was cancelled). */
    @FunctionalInterface
    public interface Action {

        void run(Player player, ClickType click);
    }

    private final UUID viewer;
    private final Inventory inventory;
    private final Map<Integer, Action> buttons = new HashMap<>();
    private int renderVersion;
    private boolean closed;

    protected MenuHolder(UUID viewer, int rows, Component title) {
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    /**
     * The MR-Punish menu {@code inventory} belongs to, or null. Reads the holder without a block
     * snapshot; falls back to the plain getter where that isn't implemented (MockBukkit).
     */
    public static MenuHolder of(Inventory inventory) {
        if (inventory == null) {
            return null;
        }
        InventoryHolder holder;
        try {
            holder = inventory.getHolder(false);
        } catch (RuntimeException notImplemented) {
            holder = inventory.getHolder();
        }
        return holder instanceof MenuHolder menu ? menu : null;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public UUID viewer() {
        return viewer;
    }

    /** Fills the inventory and buttons from the current state. Bumps the render version. */
    public final void render() {
        renderVersion++;
        buttons.clear();
        inventory.clear();
        draw();
    }

    /** Draws the menu (called by {@link #render()}). */
    protected abstract void draw();

    public int renderVersion() {
        return renderVersion;
    }

    protected void set(int slot, ItemStack item, Action action) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, item);
        if (action != null) {
            buttons.put(slot, action);
        } else {
            buttons.remove(slot);
        }
    }

    /** Puts {@code filler} in every empty slot. */
    protected void fill(ItemStack filler) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, filler.clone());
            }
        }
    }

    public Action button(int slot) {
        return buttons.get(slot);
    }

    public boolean hasButton(int slot) {
        return buttons.containsKey(slot);
    }

    /** Whether this menu is still the one its viewer is looking at. */
    public boolean isShowing() {
        if (closed) {
            return false;
        }
        Player player = Bukkit.getPlayer(viewer);
        if (player == null) {
            return false;
        }
        return of(player.getOpenInventory().getTopInventory()) == this;
    }

    void markClosed() {
        closed = true;
    }

    public void open(Player player) {
        render();
        player.openInventory(inventory);
    }
}
