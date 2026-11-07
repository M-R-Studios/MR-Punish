package com.mrstudios.punish.rank;

import java.util.List;
import java.util.Set;

/**
 * One staff rank from {@code permissions.yml}.
 *
 * @param name       the rank's key, lower case
 * @param permission the node that gives the rank
 * @param weight     authority: staff can only act on players with a lower weight (1-1000, unique)
 * @param display    the rank's name as shown to players (MiniMessage)
 * @param inherits   the ranks whose grants this rank also gets
 * @param grants     the permissions listed for this rank itself
 * @param allGrants  {@code grants} plus every inherited rank's node and grants, flattened: the
 *                   children of the rank's Bukkit permission
 */
public record Rank(String name, String permission, int weight, String display, List<String> inherits,
        List<String> grants, Set<String> allGrants) {

    public Rank {
        inherits = List.copyOf(inherits);
        grants = List.copyOf(grants);
        allGrants = java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(allGrants));
    }
}
