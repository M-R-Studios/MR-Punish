package com.mrstudios.punish.enforce;

import com.mrstudios.punish.config.Settings.FailPolicy;
import com.mrstudios.punish.punish.Punishment;
import java.util.Optional;
import java.util.UUID;

/**
 * What happens to a joining player (DESIGN.md 7.7), as a pure decision: the database's answer if
 * it gave one, else {@code login.fail-policy}. Never silently lets a banned player in because of a
 * failure, and never locks everyone out without saying why.
 */
public final class LoginCheck {

    public enum Outcome {
        /** Checked: not banned. */
        ALLOW,
        /** Not checked, let in: checked again once the database answers. */
        ALLOW_UNVERIFIED,
        /** Banned (by the database, or by the cache under CACHE). */
        DENY_BANNED,
        /** Not checked, refused with the "try again" screen. */
        DENY_UNVERIFIED
    }

    /** @param ban set for {@link Outcome#DENY_BANNED} */
    public record Decision(Outcome outcome, Punishment ban) {
    }

    /**
     * What the database answered.
     *
     * @param ok   false if the lookup failed or timed out
     * @param ban  the active ban it found (only when {@code ok})
     * @param mute the active mute it found, for the mute cache (only when {@code ok})
     * @param ipMute the active IP mute on the joining connection's hash (only when {@code ok})
     */
    public record Lookup(boolean ok, Optional<Punishment> ban, Optional<Punishment> mute,
            Optional<Punishment> ipMute) {

        public static Lookup failed() {
            return new Lookup(false, Optional.empty(), Optional.empty(), Optional.empty());
        }

        public static Lookup of(Optional<Punishment> ban) {
            return new Lookup(true, ban, Optional.empty(), Optional.empty());
        }

        public static Lookup of(Optional<Punishment> ban, Optional<Punishment> mute, Optional<Punishment> ipMute) {
            return new Lookup(true, ban, mute, ipMute);
        }
    }

    private LoginCheck() {
    }

    public static Decision decide(UUID uuid, Lookup lookup, FailPolicy policy, BanCache cache, long now) {
        return decide(uuid, null, lookup, policy, cache, now);
    }

    /**
     * @param ipHash the joining connection's hash, or null when IP features are off; under CACHE an
     *               IP ban in the copy denies the login too
     */
    public static Decision decide(UUID uuid, String ipHash, Lookup lookup, FailPolicy policy, BanCache cache,
            long now) {
        if (lookup.ok()) {
            return lookup.ban().map(b -> new Decision(Outcome.DENY_BANNED, b))
                    .orElse(new Decision(Outcome.ALLOW, null));
        }
        return switch (policy) {
            case CACHE -> {
                if (!cache.loaded()) {
                    yield new Decision(Outcome.DENY_UNVERIFIED, null);
                }
                yield cache.get(uuid, now).or(() -> cache.getIp(ipHash, now))
                        .map(b -> new Decision(Outcome.DENY_BANNED, b))
                        .orElse(new Decision(Outcome.ALLOW_UNVERIFIED, null));
            }
            case DENY -> new Decision(Outcome.DENY_UNVERIFIED, null);
            case ALLOW -> new Decision(Outcome.ALLOW_UNVERIFIED, null);
        };
    }
}
