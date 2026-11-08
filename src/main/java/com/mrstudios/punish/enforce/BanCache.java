package com.mrstudios.punish.enforce;

import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.ReplacementRules;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory copy of the active bans (DESIGN.md 2.3), keyed by account. Never authoritative:
 * logins always ask the database first; this copy only decides under {@code login.fail-policy: CACHE}
 * when the database can't answer. Expiry is lazy: an expired entry is treated as absent. Thread-safe.
 */
public final class BanCache {

    private final Map<UUID, Punishment> bans = new ConcurrentHashMap<>();
    /** Active IP bans by IP hash. */
    private final Map<String, Punishment> ipBans = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    /** Replaces the whole copy with {@code active} (startup, and after the database comes back). */
    public synchronized void load(Collection<Punishment> active) {
        bans.clear();
        ipBans.clear();
        for (Punishment ban : active) {
            put(ban);
        }
        loaded = true;
    }

    /** Whether the copy was ever filled from the database. */
    public boolean loaded() {
        return loaded;
    }

    /**
     * Adds a committed ban (an IP ban by its hash); if there's already one, the one that ends later
     * stays.
     */
    public synchronized void put(Punishment ban) {
        if (ban.type().isIp()) {
            if (ban.ipHash() != null) {
                ipBans.merge(ban.ipHash(), ban,
                        (old, added) -> ReplacementRules.later(old.expiresAt(), added.expiresAt()) ? old : added);
            }
            return;
        }
        bans.merge(ban.targetUuid(), ban,
                (old, added) -> ReplacementRules.later(old.expiresAt(), added.expiresAt()) ? old : added);
    }

    /** Forgets the bans with these ids (removed or replaced). */
    public synchronized void forget(Collection<Long> ids) {
        bans.values().removeIf(p -> ids.contains(p.id()));
        ipBans.values().removeIf(p -> ids.contains(p.id()));
    }

    /** The account's active ban at {@code now}, if any. */
    public Optional<Punishment> get(UUID uuid, long now) {
        Punishment ban = bans.get(uuid);
        if (ban == null) {
            return Optional.empty();
        }
        if (!ban.isActive(now)) {
            bans.remove(uuid, ban);
            return Optional.empty();
        }
        return Optional.of(ban);
    }

    /** The active IP ban on {@code hash} at {@code now}, if any. */
    public Optional<Punishment> getIp(String hash, long now) {
        if (hash == null) {
            return Optional.empty();
        }
        Punishment ban = ipBans.get(hash);
        if (ban == null) {
            return Optional.empty();
        }
        if (!ban.isActive(now)) {
            ipBans.remove(hash, ban);
            return Optional.empty();
        }
        return Optional.of(ban);
    }

    public int size() {
        return bans.size() + ipBans.size();
    }
}
