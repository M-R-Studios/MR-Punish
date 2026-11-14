package com.mrstudios.punish.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.plugin.Plugin;

/**
 * Opens MR-Punish menus, working around a Geyser race for Bedrock players. When the server swaps
 * one window for another, a Bedrock client closes its old screen and that "closed" arrives after
 * the new window is open, so the server closes the new one too: the menu flashes open and shut.
 * <p>
 * So for a Bedrock player every open is: close what's open (next tick), wait
 * {@link #BEDROCK_SWITCH_TICKS}, then open. Only the newest request for a player runs, so a double
 * tap can't open two menus. A "closed" from a Bedrock player within {@link #STRAY_CLOSE_TICKS} of an
 * open is taken as that late close and the same menu (with its state) comes back, at most
 * {@link #MAX_STRAY_CLOSES} times in a row. Java players are opened at once, as before.
 */
public final class MenuOpener {

    public static final int BEDROCK_SWITCH_TICKS = 4;
    static final int STRAY_CLOSE_TICKS = 6;
    static final int MAX_STRAY_CLOSES = 2;

    /** The opener of the running plugin; null (tests without the plugin) opens directly. */
    private static volatile MenuOpener current;

    private final Plugin plugin;
    private volatile Predicate<UUID> bedrock;
    private final Map<UUID, Integer> requests = new HashMap<>();
    private final Map<UUID, Integer> openedAt = new HashMap<>();
    private final Map<UUID, Integer> strayCloses = new HashMap<>();

    public MenuOpener(Plugin plugin, Predicate<UUID> bedrock) {
        this.plugin = plugin;
        this.bedrock = bedrock;
    }

    public static void install(MenuOpener opener) {
        current = opener;
    }

    static MenuOpener current() {
        return current;
    }

    /** Who counts as a Bedrock player (Floodgate, and only while {@code bedrock.floodgate} is on). */
    public void bedrockCheck(Predicate<UUID> check) {
        this.bedrock = check;
    }

    public boolean isBedrock(Player player) {
        return bedrock.test(player.getUniqueId());
    }

    /** Opens {@code menu} for {@code player}: at once on Java, after the switch dance on Bedrock. */
    void open(MenuHolder menu, Player player) {
        UUID uuid = player.getUniqueId();
        if (!isBedrock(player)) {
            menu.show(player);
            return;
        }
        int request = requests.merge(uuid, 1, Integer::sum);
        // Never close inside an inventory event (we're often in a click handler): next tick.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || requests.get(uuid) != request) {
                return;
            }
            if (hasWindowOpen(player)) {
                player.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
            }
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline() || requests.get(uuid) != request) {
                    return; // a newer request (another tap) wins
                }
                menu.show(player);
                openedAt.put(uuid, plugin.getServer().getCurrentTick());
            }, BEDROCK_SWITCH_TICKS);
        });
    }

    private static boolean hasWindowOpen(Player player) {
        InventoryType type = player.getOpenInventory().getType();
        return type != InventoryType.CRAFTING && type != InventoryType.CREATIVE;
    }

    /**
     * Whether a player's "closed" right now is a Bedrock client's late close of the previous
     * screen: it comes within {@link #STRAY_CLOSE_TICKS} of opening a menu. Counted, so a client
     * that really keeps closing still gets out.
     */
    boolean isStrayClose(Player player) {
        UUID uuid = player.getUniqueId();
        Integer at = openedAt.get(uuid);
        if (at == null || !isBedrock(player) || plugin.getServer().getCurrentTick() - at > STRAY_CLOSE_TICKS) {
            strayCloses.remove(uuid);
            return false;
        }
        return strayCloses.merge(uuid, 1, Integer::sum) <= MAX_STRAY_CLOSES;
    }

    /** A player left: nothing of theirs is kept. */
    public void forget(UUID uuid) {
        requests.remove(uuid);
        openedAt.remove(uuid);
        strayCloses.remove(uuid);
    }
}
