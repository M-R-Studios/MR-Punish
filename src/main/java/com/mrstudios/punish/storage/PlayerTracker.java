package com.mrstudios.punish.storage;

import com.mrstudios.punish.config.RankSettings;
import com.mrstudios.punish.punish.NameIndex;
import com.mrstudios.punish.rank.Rank;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Records every join and quit in {@code players} (name, last seen, and the rank weight and exempt
 * flag at that moment, used for offline players without LuckPerms; DESIGN.md 6.4), and keeps the
 * {@link NameIndex} current. The rank is read on the main thread; the write runs on a database thread.
 */
public final class PlayerTracker implements Listener {

    public static final String EXEMPT = "mrpunish.exempt";

    /** What else should happen when a player joins (e.g. telling admins that storage is down). */
    public interface JoinHook {

        void joined(Player player);
    }

    private final Storage storage;
    private final NameIndex names;
    private final Supplier<RankSettings> ranks;
    private final LongSupplier clock;
    private final JoinHook onJoin;

    public PlayerTracker(Storage storage, NameIndex names, Supplier<RankSettings> ranks, LongSupplier clock,
            JoinHook onJoin) {
        this.storage = storage;
        this.names = names;
        this.ranks = ranks;
        this.clock = clock;
        this.onJoin = onJoin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        record(event.getPlayer());
        onJoin.joined(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        record(event.getPlayer());
    }

    /** What's known about {@code player} right now. Main thread. */
    public PlayerRepo.Visit visit(Player player) {
        Optional<Rank> rank = ranks.get().rankOf(player::hasPermission);
        // Bedrock detection comes with the Floodgate hook (DESIGN.md 12); until then everyone is Java.
        return new PlayerRepo.Visit(player.getUniqueId(), player.getName(), clock.getAsLong(),
                rank.map(Rank::name).orElse(null), rank.map(Rank::weight).orElse(0), player.hasPermission(EXEMPT),
                false);
    }

    /**
     * Updates the name index and writes the visit. If storage is down the write is skipped: the
     * next join or quit records the player again.
     */
    public CompletableFuture<Void> record(Player player) {
        PlayerRepo.Visit visit = visit(player);
        names.record(visit.uuid(), visit.name(), visit.at());
        if (!storage.health().isUp()) {
            return CompletableFuture.completedFuture(null);
        }
        return storage.executor().submit("recording " + visit.name() + "'s visit", c -> {
            new PlayerRepo(storage.database()).upsert(c, visit);
            return null;
        });
    }
}
