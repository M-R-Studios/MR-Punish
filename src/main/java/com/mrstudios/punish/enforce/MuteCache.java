package com.mrstudios.punish.enforce;

import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.ReplacementRules;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The active mute of each online player (DESIGN.md 2.3), read on the async chat thread. Filled at
 * pre-login together with the ban check, and on every commit; forgotten on quit. Expiry is lazy:
 * an expired mute simply stops blocking (X17). Thread-safe.
 */
public final class MuteCache {

    private final Map<UUID, Punishment> mutes = new ConcurrentHashMap<>();

    /** What the database said for {@code uuid}: its active mute, or none. */
    public void set(UUID uuid, Optional<Punishment> mute) {
        if (mute.isPresent()) {
            mutes.put(uuid, mute.get());
        } else {
            mutes.remove(uuid);
        }
    }

    /** Adds a committed mute; the one that ends later stays. */
    public void put(Punishment mute) {
        mutes.merge(mute.targetUuid(), mute,
                (old, added) -> ReplacementRules.later(old.expiresAt(), added.expiresAt()) ? old : added);
    }

    /** Forgets the mutes with these ids (removed or replaced). */
    public void forget(Collection<Long> ids) {
        mutes.values().removeIf(p -> ids.contains(p.id()));
    }

    public void forget(UUID uuid) {
        mutes.remove(uuid);
    }

    /** The player's active mute at {@code now}, if any. */
    public Optional<Punishment> get(UUID uuid, long now) {
        Punishment mute = mutes.get(uuid);
        if (mute == null) {
            return Optional.empty();
        }
        if (!mute.isActive(now)) {
            mutes.remove(uuid, mute);
            return Optional.empty();
        }
        return Optional.of(mute);
    }
}
