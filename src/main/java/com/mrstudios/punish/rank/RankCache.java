package com.mrstudios.punish.rank;

import com.mrstudios.punish.config.RankSettings;
import com.mrstudios.punish.storage.PlayerTracker;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.entity.Player;

/**
 * Online players' rank weight and exempt flag (DESIGN.md 2.3), read from their permissions. It's
 * refreshed on join, every 60 seconds and on reload; a change since the last refresh is what makes
 * the plugin write the player's {@code players} row again, so the cached rank used for offline
 * players stays current (DESIGN.md 6.4). Authority checks always use {@link #standing(Player)},
 * which reads the permissions at that moment.
 */
public final class RankCache {

    /** @param rankKey null without a rank */
    public record Entry(String rankKey, int weight, boolean exempt) {
    }

    private final Supplier<RankSettings> ranks;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public RankCache(Supplier<RankSettings> ranks) {
        this.ranks = ranks;
    }

    /** Reads {@code player}'s rank now. Main thread. */
    public Entry read(Player player) {
        Optional<Rank> rank = ranks.get().rankOf(player::hasPermission);
        return new Entry(rank.map(Rank::name).orElse(null), rank.map(Rank::weight).orElse(0),
                player.hasPermission(PlayerTracker.EXEMPT));
    }

    /** The player's standing right now, for an authority check; also refreshes the cache. */
    public Authority.Standing standing(Player player) {
        Entry entry = read(player);
        entries.put(player.getUniqueId(), entry);
        return new Authority.Standing(player.getUniqueId(), player.getName(), entry.weight(), entry.exempt());
    }

    /** Re-reads {@code player} and returns whether their rank or exempt flag changed. */
    public boolean refresh(Player player) {
        Entry now = read(player);
        Entry before = entries.put(player.getUniqueId(), now);
        return before != null && !before.equals(now);
    }

    public Optional<Entry> get(UUID uuid) {
        return Optional.ofNullable(entries.get(uuid));
    }

    public void forget(UUID uuid) {
        entries.remove(uuid);
    }
}
