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
    private volatile boolean loaded;

    /** Replaces the whole copy with {@code active} (startup, and after the database comes back). */
    public synchronized void load(Collection<Punishment> active) {
        bans.clear();
        for (Punishment ban : active) {
            put(ban);
        }
        loaded = true;
    }

    /** Whether the copy was ever filled from the database. */
    public boolean loaded() {
        return loaded;
    }

    /** Adds a committed ban; if the account already has one, the one that ends later stays. */
    public synchronized void put(Punishment ban) {
        bans.merge(ban.targetUuid(), ban,
                (old, added) -> ReplacementRules.later(old.expiresAt(), added.expiresAt()) ? old : added);
    }

    /** Forgets the bans with these ids (removed or replaced). */
    public synchronized void forget(Collection<Long> ids) {
        bans.values().removeIf(p -> ids.contains(p.id()));
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

    public int size() {
        return bans.size();
    }
}
