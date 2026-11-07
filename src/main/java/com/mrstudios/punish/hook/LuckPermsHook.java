package com.mrstudios.punish.hook;

import com.mrstudios.punish.config.RankSettings;
import com.mrstudios.punish.rank.Authority;
import com.mrstudios.punish.rank.OfflineRanks;
import com.mrstudios.punish.rank.Rank;
import com.mrstudios.punish.storage.PlayerTracker;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedPermissionData;
import net.luckperms.api.query.QueryOptions;

/**
 * Offline ranks from LuckPerms (DESIGN.md 6.4): the user is loaded asynchronously, then each rank
 * node and {@code mrpunish.exempt} is checked with the global (non-contextual) query options. This
 * is the only class that touches the LuckPerms API, so MR-Punish runs fine without it.
 */
public final class LuckPermsHook implements OfflineRanks {

    private final LuckPerms luckPerms;
    private final Supplier<RankSettings> ranks;

    /** Only call when the LuckPerms plugin is enabled. */
    public LuckPermsHook(Supplier<RankSettings> ranks) {
        this.luckPerms = LuckPermsProvider.get();
        this.ranks = ranks;
    }

    @Override
    public CompletableFuture<Optional<Authority.Standing>> load(UUID uuid) {
        return luckPerms.getUserManager().loadUser(uuid).thenApply(user -> {
            if (user == null) {
                return Optional.empty();
            }
            CachedPermissionData data = user.getCachedData().getPermissionData(QueryOptions.nonContextual());
            Optional<Rank> rank = ranks.get().rankOf(node -> data.checkPermission(node).asBoolean());
            boolean exempt = data.checkPermission(PlayerTracker.EXEMPT).asBoolean();
            return Optional.of(new Authority.Standing(uuid, user.getUsername() == null ? uuid.toString()
                    : user.getUsername(), rank.map(Rank::weight).orElse(0), exempt));
        });
    }
}
