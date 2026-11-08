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
    /** Active IP mutes by IP hash (DESIGN.md 8.4: every account on the hash sees it at once). */
    private final Map<String, Punishment> ipMutes = new ConcurrentHashMap<>();

    /** What the database said for {@code uuid}: its active mute, or none. */
    public void set(UUID uuid, Optional<Punishment> mute) {
        if (mute.isPresent()) {
            mutes.put(uuid, mute.get());
        } else {
            mutes.remove(uuid);
        }
    }

    /** What the database said for {@code hash}: its active IP mute, or none. */
    public void setIp(String hash, Optional<Punishment> mute) {
        if (hash == null) {
            return;
        }
        if (mute.isPresent()) {
            ipMutes.put(hash, mute.get());
        } else {
            ipMutes.remove(hash);
        }
    }

    /** Adds a committed mute (an IP mute by its hash); the one that ends later stays. */
    public void put(Punishment mute) {
        if (mute.type().isIp()) {
            if (mute.ipHash() != null) {
                ipMutes.merge(mute.ipHash(), mute,
                        (old, added) -> ReplacementRules.later(old.expiresAt(), added.expiresAt()) ? old : added);
            }
            return;
        }
        mutes.merge(mute.targetUuid(), mute,
                (old, added) -> ReplacementRules.later(old.expiresAt(), added.expiresAt()) ? old : added);
    }

    /** Forgets the mutes with these ids (removed or replaced). */
    public void forget(Collection<Long> ids) {
        mutes.values().removeIf(p -> ids.contains(p.id()));
        ipMutes.values().removeIf(p -> ids.contains(p.id()));
    }

    public void forget(UUID uuid) {
        mutes.remove(uuid);
    }

    /** The active IP mute on {@code hash} at {@code now}, if any. */
    public Optional<Punishment> getIp(String hash, long now) {
        if (hash == null) {
            return Optional.empty();
        }
        Punishment mute = ipMutes.get(hash);
        if (mute == null) {
            return Optional.empty();
        }
        if (!mute.isActive(now)) {
            ipMutes.remove(hash, mute);
            return Optional.empty();
        }
        return Optional.of(mute);
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
