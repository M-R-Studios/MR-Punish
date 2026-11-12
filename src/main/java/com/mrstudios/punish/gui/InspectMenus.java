package com.mrstudios.punish.gui;

import com.mrstudios.punish.MRPunish;
import com.mrstudios.punish.config.GuiSettings;
import com.mrstudios.punish.config.ItemTemplate;
import com.mrstudios.punish.storage.ActionRepo;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * {@code /invsee} and {@code /endersee} (DESIGN.md 11.4): view-only live copies. The menu holds
 * copies marked {@code mrpunish:display}, never the real items, refreshed every 10 ticks; every click
 * and drag is cancelled by {@link MenuListener}. So there is nothing to take, move or duplicate: at
 * worst a copy, which {@link DisplayGuard} removes. The target isn't told; every inspection is logged.
 */
public final class InspectMenus implements Listener {

    public static final String INVSEE = "mrpunish.invsee";
    public static final String ENDERSEE = "mrpunish.endersee";

    private final MRPunish plugin;
    private final Items items;
    private final Set<View> open = ConcurrentHashMap.newKeySet();

    public InspectMenus(MRPunish plugin, Items items) {
        this.plugin = plugin;
        this.items = items;
    }

    private GuiSettings gui() {
        return plugin.state().gui();
    }

    /** A live view of one target. */
    abstract class View extends MenuHolder {

        final UUID target;
        final String targetName;

        View(Player viewer, Player target, String titlePath, String rowsPath) {
            super(viewer.getUniqueId(), gui().number(rowsPath),
                    Items.render(gui().text(titlePath), Placeholder.unparsed("player", target.getName())));
            this.target = target.getUniqueId();
            this.targetName = target.getName();
        }

        Player targetPlayer() {
            return plugin.getServer().getPlayer(target);
        }
    }

    final class InventoryView extends View {

        /** The head is only rebuilt when what it shows changes (each new profile may look up a skin). */
        private String headState;
        private ItemStack head;

        InventoryView(Player viewer, Player target) {
            super(viewer, target, "invsee.title", "invsee.rows");
        }

        @Override
        protected void draw() {
            Player t = targetPlayer();
            if (t == null) {
                return;
            }
            GuiSettings g = gui();
            PlayerInventory inv = t.getInventory();
            for (int i = 0; i < 36; i++) {
                set(i, items.displayCopy(inv.getItem(i)), null);
            }
            ItemStack[] armour = {inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()};
            List<Integer> armourSlots = g.slots("invsee.armor-slots");
            for (int i = 0; i < armour.length && i < armourSlots.size(); i++) {
                set(armourSlots.get(i), items.displayCopy(armour[i]), null);
            }
            set(g.number("invsee.offhand-slot"), items.displayCopy(inv.getItemInOffHand()), null);
            var maxHealth = t.getAttribute(Attribute.MAX_HEALTH);
            String health = String.format("%.1f / %.1f", t.getHealth(), maxHealth == null ? 20.0 : maxHealth.getValue());
            String gameMode = t.getGameMode().name().toLowerCase(java.util.Locale.ROOT);
            String state = health + "|" + t.getFoodLevel() + "|" + t.getLevel() + "|" + gameMode + "|"
                    + t.getWorld().getName();
            if (head == null || !state.equals(headState)) {
                ItemTemplate template = new ItemTemplate("PLAYER_HEAD", g.text("invsee.head.name"),
                        g.lines("invsee.head.lore"), false, null);
                head = items.head(t.getUniqueId(), t.getName(), template, TagResolver.resolver(
                        Placeholder.unparsed("player", t.getName()),
                        Placeholder.unparsed("health", health),
                        Placeholder.unparsed("food", String.valueOf(t.getFoodLevel())),
                        Placeholder.unparsed("level", String.valueOf(t.getLevel())),
                        Placeholder.unparsed("gamemode", gameMode),
                        Placeholder.unparsed("world", t.getWorld().getName())), Map.of());
                headState = state;
            }
            set(g.number("invsee.head.slot"), head.clone(), null);
            set(g.number("invsee.close-slot"), items.build(g.item("common.close"), TagResolver.empty()),
                    (p, click) -> p.closeInventory());
            ItemStack filler = items.build(g.item("filler"), TagResolver.empty());
            for (int slot = 36; slot < getInventory().getSize(); slot++) {
                if (getInventory().getItem(slot) == null) {
                    getInventory().setItem(slot, filler.clone());
                }
            }
        }
    }

    final class EnderView extends View {

        EnderView(Player viewer, Player target) {
            super(viewer, target, "endersee.title", "endersee.rows");
        }

        @Override
        protected void draw() {
            Player t = targetPlayer();
            if (t == null) {
                return;
            }
            ItemStack[] contents = t.getEnderChest().getContents();
            for (int i = 0; i < contents.length && i < getInventory().getSize(); i++) {
                set(i, items.displayCopy(contents[i]), null);
            }
        }
    }

    // ---- Entry points ----

    public void openInventory(Player viewer, Player target) {
        View view = new InventoryView(viewer, target);
        open.add(view);
        view.open(viewer);
        log(viewer, target, "INSPECT_INV");
    }

    public void openEnderChest(Player viewer, Player target) {
        View view = new EnderView(viewer, target);
        open.add(view);
        view.open(viewer);
        log(viewer, target, "INSPECT_ENDER");
    }

    private void log(Player viewer, Player target, String action) {
        long now = System.currentTimeMillis();
        String server = plugin.state().settings().effectiveServerName();
        if (plugin.storage().executor().isShutDown()) {
            return;
        }
        plugin.storage().executor().submit("logging an inspection", c -> {
            new ActionRepo(plugin.storage().database()).insert(c, new ActionRepo.Action(now, server,
                    viewer.getUniqueId(), viewer.getName(), action, target.getUniqueId(), target.getName(), null, null));
            return null;
        });
    }

    /** Every 10 ticks: refresh the open views; forget closed ones. */
    public void tick() {
        for (View view : open) {
            if (!view.isShowing()) {
                open.remove(view);
            } else if (view.targetPlayer() != null) {
                view.render();
            }
        }
    }

    /** The target left: their viewers' menus close with {@code inspect-closed}. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID quitter = event.getPlayer().getUniqueId();
        for (View view : open) {
            if (view.viewer().equals(quitter)) {
                open.remove(view);
                continue;
            }
            if (view.target.equals(quitter)) {
                open.remove(view);
                Player viewer = plugin.getServer().getPlayer(view.viewer());
                if (viewer != null && view.isShowing()) {
                    viewer.closeInventory();
                    plugin.state().messages().send(viewer, "staff.inspect-closed",
                            Placeholder.unparsed("player", view.targetName));
                }
            }
        }
    }
}
