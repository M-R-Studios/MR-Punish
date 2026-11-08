package com.mrstudios.punish.staff;

/**
 * Who sees whom (DESIGN.md 11.2). Pure. A viewer sees a vanished player only if it's themselves, or
 * they have {@code mrpunish.vanish.see} and a rank weight at least the vanished player's. Everyone
 * sees players who aren't vanished.
 */
public final class VisibilityRule {

    private VisibilityRule() {
    }

    public static boolean sees(boolean self, boolean targetVanished, boolean viewerCanSeeVanished, int viewerWeight,
            int targetWeight) {
        if (self || !targetVanished) {
            return true;
        }
        return viewerCanSeeVanished && viewerWeight >= targetWeight;
    }
}
