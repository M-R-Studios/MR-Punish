package com.mrstudios.punish.punish;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Actor-and-target pairs with a punishment or removal being saved (DESIGN.md 7.10 step 6, X8): a
 * double click or a repeated command while one is in flight is answered with {@code punish.busy}.
 */
public final class InFlight {

    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    /** @param actor null for console */
    static String key(UUID actor, UUID target) {
        return (actor == null ? "console" : actor.toString()) + ">" + target;
    }

    /** Claims the pair; false if it's already in flight. */
    public boolean acquire(UUID actor, UUID target) {
        return pending.add(key(actor, target));
    }

    public void release(UUID actor, UUID target) {
        pending.remove(key(actor, target));
    }

    public boolean isBusy(UUID actor, UUID target) {
        return pending.contains(key(actor, target));
    }
}
