package com.mrstudios.punish.gui;

import com.mrstudios.punish.command.CommandArgs;
import com.mrstudios.punish.config.PluginState;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Text typed by staff for a menu (DESIGN.md 12.2): a Bedrock form when Floodgate is used, else the
 * next chat message. The chat message is cancelled at LOWEST, so it is never broadcast, also for a
 * muted staff member. The cancel word or the timeout gives up; quitting, a new prompt or a plugin
 * disable drop the prompt without a callback.
 */
public final class PromptService implements Listener {

    /** What a typed text is. */
    public enum Check {
        VALUE, CANCEL, EMPTY, TOO_LONG
    }

    /** Pure: what {@code typed} means for a prompt. */
    public static Check check(String typed, int maxLength, String cancelWord) {
        String cleaned = CommandArgs.clean(typed, Integer.MAX_VALUE);
        if (cleaned == null) {
            return Check.EMPTY;
        }
        if (cleaned.toLowerCase(Locale.ROOT).equals(cancelWord)) {
            return Check.CANCEL;
        }
        return cleaned.codePointCount(0, cleaned.length()) > maxLength ? Check.TOO_LONG : Check.VALUE;
    }

    private record Pending(Consumer<String> submitted, Runnable cancelled, BukkitTask timeout) {
    }

    private final Plugin plugin;
    private final Supplier<PluginState> state;
    private final BedrockForms forms;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public PromptService(Plugin plugin, Supplier<PluginState> state, BedrockForms forms) {
        this.plugin = plugin;
        this.state = state;
        this.forms = forms;
    }

    public boolean waiting(UUID uuid) {
        return pending.containsKey(uuid);
    }

    /**
     * Asks {@code player} for a note (main thread). The player's menu is closed. Exactly one of
     * {@code submitted} (cleaned, non-empty text) or {@code cancelled} runs later on the main thread,
     * unless the player quits first.
     *
     * @param messageKey   the chat prompt (gets {@code <cancel-word>} plus {@code placeholders})
     * @param formTitleKey the Bedrock form's title
     */
    public void ask(Player player, String messageKey, String formTitleKey, String current, Consumer<String> submitted,
            Runnable cancelled, TagResolver... placeholders) {
        drop(player.getUniqueId());
        player.closeInventory();
        PluginState s = state.get();
        int max = s.settings().maxTextLength();
        if (forms.isBedrock(player.getUniqueId())) {
            UUID uuid = player.getUniqueId();
            forms.askText(player, s.messages().raw(formTitleKey), s.messages().raw("input.form-label"), current,
                    text -> main(() -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        switch (check(text, max, s.settings().input().cancelWord())) {
                            case VALUE -> submitted.accept(CommandArgs.clean(text, max));
                            case TOO_LONG -> {
                                s.messages().send(player, "input.too-long", Placeholder.unparsed("max",
                                        String.valueOf(max)));
                                cancelled.run();
                            }
                            default -> cancelled.run();
                        }
                    }), () -> main(() -> {
                        if (plugin.getServer().getPlayer(uuid) != null) {
                            cancelled.run();
                        }
                    }));
            return;
        }
        String cancelWord = s.settings().input().cancelWord();
        TagResolver all = TagResolver.resolver(TagResolver.resolver(placeholders),
                Placeholder.unparsed("cancel-word", cancelWord));
        s.messages().send(player, messageKey, all);
        UUID uuid = player.getUniqueId();
        Pending[] self = new Pending[1];
        BukkitTask timeout = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(uuid, self[0])) {
                Player online = plugin.getServer().getPlayer(uuid);
                if (online != null) {
                    state.get().messages().send(online, "input.prompt-timeout");
                    cancelled.run();
                }
            }
        }, s.settings().input().chatTimeoutSeconds() * 20L);
        self[0] = new Pending(submitted, cancelled, timeout);
        pending.put(uuid, self[0]);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Pending prompt = pending.get(uuid);
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        String typed = PlainTextComponentSerializer.plainText().serialize(event.message());
        main(() -> answer(event.getPlayer(), prompt, typed));
    }

    private void answer(Player player, Pending prompt, String typed) {
        UUID uuid = player.getUniqueId();
        if (pending.get(uuid) != prompt || !player.isOnline()) {
            return;
        }
        PluginState s = state.get();
        int max = s.settings().maxTextLength();
        switch (check(typed, max, s.settings().input().cancelWord())) {
            case VALUE -> {
                finish(uuid, prompt);
                prompt.submitted().accept(CommandArgs.clean(typed, max));
            }
            case CANCEL -> {
                finish(uuid, prompt);
                s.messages().send(player, "input.prompt-cancelled");
                prompt.cancelled().run();
            }
            case EMPTY -> s.messages().send(player, "input.empty",
                    Placeholder.unparsed("cancel-word", s.settings().input().cancelWord()));
            case TOO_LONG -> s.messages().send(player, "input.too-long", Placeholder.unparsed("max",
                    String.valueOf(max)));
        }
    }

    private void finish(UUID uuid, Pending prompt) {
        if (pending.remove(uuid, prompt)) {
            prompt.timeout().cancel();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        drop(event.getPlayer().getUniqueId());
    }

    /** Drops the player's prompt without a callback. */
    public void drop(UUID uuid) {
        Pending old = pending.remove(uuid);
        if (old != null) {
            old.timeout().cancel();
        }
    }

    /** Plugin disable: every prompt is dropped. */
    public void dropAll() {
        for (UUID uuid : pending.keySet()) {
            drop(uuid);
        }
    }

    private void main(Runnable task) {
        if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }
}
