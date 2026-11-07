package com.mrstudios.punish.config;

import com.mrstudios.punish.rank.Rank;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The staff ranks from {@code permissions.yml}, validated: weights are unique and the inheritance
 * has no cycles. Immutable: a reload builds a new one.
 *
 * @param ranks every rank, heaviest first
 */
public record RankSettings(List<Rank> ranks) {

    public RankSettings {
        ranks = ranks.stream().sorted(Comparator.comparingInt(Rank::weight).reversed()).toList();
    }

    /** The heaviest rank whose permission {@code hasPermission} accepts, if any. */
    public Optional<Rank> rankOf(Predicate<String> hasPermission) {
        return ranks.stream().filter(rank -> hasPermission.test(rank.permission())).findFirst();
    }

    /** The weight of {@link #rankOf}; 0 for players without a rank. */
    public int weightOf(Predicate<String> hasPermission) {
        return rankOf(hasPermission).map(Rank::weight).orElse(0);
    }

    public Optional<Rank> byName(String name) {
        return ranks.stream().filter(rank -> rank.name().equals(name)).findFirst();
    }
}
