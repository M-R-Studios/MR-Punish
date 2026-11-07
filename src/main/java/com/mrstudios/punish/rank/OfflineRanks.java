package com.mrstudios.punish.rank;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Where the rank of an offline player comes from besides the cached {@code players} row (DESIGN.md
 * 6.4): LuckPerms when it's installed. An empty result means "unknown here, use the cache".
 */
public interface OfflineRanks {

    OfflineRanks NONE = uuid -> CompletableFuture.completedFuture(Optional.empty());

    /** Loads the player's rank weight and exempt flag; may complete on another thread. */
    CompletableFuture<Optional<Authority.Standing>> load(UUID uuid);
}
