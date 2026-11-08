package com.mrstudios.punish.staff;

import com.mrstudios.punish.command.CommandArgs;
import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.rank.RankDisplay;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Staff chat (DESIGN.md 11.5): {@code /sc}, the {@code /staffchat} toggle and the
 * {@code staff-chat.prefix}. Messages go only to online players with {@code mrpunish.staffchat}
 * (checked when sending) and the console. Chat routing order: prompt input, then staff chat, then
 * mute, then normal chat; a muted staff member can still use staff chat. A message meant for staff
 * chat is never let through to public chat: someone toggled on or using the prefix who has lost the
 * permission gets "no permission" instead.
 */
public final class StaffChat implements Listener {

    public static final String PERMISSION = "mrpunish.staffchat";

    private final Plugin plugin;
    private final Supplier<PluginState> state;
    private final Predicate<UUID> typingInput;
    private final Set<UUID> toggled = ConcurrentHashMap.newKeySet();

    public StaffChat(Plugin plugin, Supplier<PluginState> state, Predicate<UUID> typingInput) {
        this.plugin = plugin;
        this.state = state;
        this.typingInput = typingInput;
    }

    public boolean toggledOn(UUID uuid) {
        return toggled.contains(uuid);
    }

    /** Toggles routing of all of {@code player}'s chat; returns whether it's on now. */
    public boolean toggle(Player player) {
        if (toggled.remove(player.getUniqueId())) {
            return false;
        }
        toggled.add(player.getUniqueId());
        return true;
    }

    /**
     * What a chat line is, pure: {@code null} when it's normal chat, else the staff message (may be
     * empty, e.g. just the prefix). The prefix only counts for staff (a player typing "#1" chats
     * normally); a toggled-on line is always staff chat, also if the permission was lost meanwhile
     * (it's then refused rather than shown publicly).
     *
     * @param prefix "" = no prefix routing
     */
    public static String staffPart(String text, boolean toggledOn, boolean staff, String prefix) {
        if (staff && !prefix.isEmpty() && text.startsWith(prefix)) {
            return text.substring(prefix.length()).strip();
        }
        return toggledOn ? text.strip() : null;
    }

    /** Whether this chat event is staff chat (for mute enforcement, which must leave it alone). */
    public boolean claims(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (typingInput.test(uuid)) {
            return false;
        }
        return staffPart(plain(event), toggledOn(uuid), event.getPlayer().hasPermission(PERMISSION),
                state.get().settings().staffChat().prefix()) != null;
    }

    private static String plain(AsyncChatEvent event) {
        return PlainTextComponentSerializer.plainText().serialize(event.message());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (typingInput.test(player.getUniqueId())) {
            return; // prompt input comes first
        }
        String message = staffPart(plain(event), toggledOn(player.getUniqueId()), player.hasPermission(PERMISSION),
                state.get().settings().staffChat().prefix());
        if (message == null) {
            return;
        }
        event.setCancelled(true);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.hasPermission(PERMISSION)) {
                toggled.remove(player.getUniqueId());
                state.get().messages().send(player, "general.no-permission");
                return;
            }
            send(player, message);
        });
    }

    /** Sends {@code message} from {@code sender} (a player or the console). Main thread. */
    public void send(CommandSender sender, String message) {
        PluginState s = state.get();
        String text = CommandArgs.clean(message, s.settings().maxTextLength());
        if (text == null) {
            return;
        }
        String name;
        String rank;
        if (sender instanceof Player p) {
            name = p.getName();
            rank = RankDisplay.of(s.ranks(), p::hasPermission, s.gui().text("punish-menu.head.no-rank"));
        } else {
            name = s.messages().raw("general.console-name");
            rank = "";
        }
        var placeholders = new net.kyori.adventure.text.minimessage.tag.resolver.TagResolver[] {
            Placeholder.parsed("rank", rank), Placeholder.unparsed("player", name),
            Placeholder.unparsed("message", text)};
        Settings.SoundSetting sound = s.settings().staffChat().sound();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.hasPermission(PERMISSION)) {
                s.messages().send(p, "staff.staffchat-format", placeholders);
                if (sound.enabled() && !p.equals(sender)) {
                    p.playSound(Sound.sound(sound.sound(), Sound.Source.MASTER, sound.volume(), sound.pitch()));
                }
            }
        }
        s.messages().send(plugin.getServer().getConsoleSender(), "staff.staffchat-format", placeholders);
    }

    /** Not persisted: a staff member who rejoins doesn't talk into staff chat without knowing. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        toggled.remove(event.getPlayer().getUniqueId());
    }
}
