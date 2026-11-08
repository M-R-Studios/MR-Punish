package com.mrstudios.punish.rank;

import com.mrstudios.punish.config.RankSettings;
import java.util.function.Predicate;

/**
 * How a player's rank is shown (DESIGN.md 6.5): the MR-Punish rank's {@code display}, else the
 * "no rank" text. MiniMessage from config.
 */
public final class RankDisplay {

    private RankDisplay() {
    }

    public static String of(RankSettings ranks, Predicate<String> permissions, String noRank) {
        return ranks.rankOf(permissions).map(Rank::display).orElse(noRank);
    }
}
