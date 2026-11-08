package com.mrstudios.punish.enforce;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mrstudios.punish.config.Settings.FailPolicy;
import com.mrstudios.punish.enforce.LoginCheck.Lookup;
import com.mrstudios.punish.enforce.LoginCheck.Outcome;
import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.Punishment.Origin;
import com.mrstudios.punish.punish.Punishment.StoredStatus;
import com.mrstudios.punish.punish.Punishment.WeightSource;
import com.mrstudios.punish.punish.PunishmentType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** DESIGN.md 7.7: the database's answer, and each fail policy with and without a loaded cache. */
class LoginCheckTest {

    private static final long NOW = 1_000_000L;
    private static final UUID STEVE = UUID.randomUUID();

    static Punishment ban(long id, UUID target, Long expiresAt) {
        return new Punishment(id, PunishmentType.BAN, target, "Steve", null, "hacking", "Hacking", null, 1, 1, null, 0,
                expiresAt, null, "Console", 0, 0, WeightSource.CACHED, "default", false, Origin.COMMAND,
                StoredStatus.ACTIVE, null, null, null, null, null, null);
    }

    private static BanCache loaded(Punishment... bans) {
        BanCache cache = new BanCache();
        cache.load(List.of(bans));
        return cache;
    }

    @Test
    void theDatabaseAnswerAlwaysWins() {
        Punishment ban = ban(1, STEVE, null);
        for (FailPolicy policy : FailPolicy.values()) {
            assertEquals(Outcome.DENY_BANNED,
                    LoginCheck.decide(STEVE, Lookup.of(Optional.of(ban)), policy, new BanCache(), NOW).outcome());
            assertEquals(Outcome.ALLOW,
                    LoginCheck.decide(STEVE, Lookup.of(Optional.empty()), policy, loaded(ban), NOW).outcome(),
                    "the cache never overrules the database");
        }
    }

    @Test
    void cachePolicy() {
        BanCache withBan = loaded(ban(1, STEVE, null));
        LoginCheck.Decision banned = LoginCheck.decide(STEVE, Lookup.failed(), FailPolicy.CACHE, withBan, NOW);
        assertEquals(Outcome.DENY_BANNED, banned.outcome());
        assertEquals(1, banned.ban().id());
        assertEquals(Outcome.ALLOW_UNVERIFIED,
                LoginCheck.decide(UUID.randomUUID(), Lookup.failed(), FailPolicy.CACHE, withBan, NOW).outcome());
        assertEquals(Outcome.DENY_UNVERIFIED,
                LoginCheck.decide(STEVE, Lookup.failed(), FailPolicy.CACHE, new BanCache(), NOW).outcome(),
                "a cache that was never loaded can't vouch for anyone");
        BanCache expired = loaded(ban(2, STEVE, NOW - 1));
        assertEquals(Outcome.ALLOW_UNVERIFIED,
                LoginCheck.decide(STEVE, Lookup.failed(), FailPolicy.CACHE, expired, NOW).outcome());
    }

    @Test
    void denyAndAllowPolicies() {
        BanCache withBan = loaded(ban(1, STEVE, null));
        assertEquals(Outcome.DENY_UNVERIFIED,
                LoginCheck.decide(UUID.randomUUID(), Lookup.failed(), FailPolicy.DENY, withBan, NOW).outcome());
        assertEquals(Outcome.ALLOW_UNVERIFIED,
                LoginCheck.decide(STEVE, Lookup.failed(), FailPolicy.ALLOW, withBan, NOW).outcome(),
                "ALLOW lets even a cached ban in, to be checked once the database is back");
    }

    @Test
    void banCacheKeepsTheLatestEndingBanAndForgets() {
        BanCache cache = new BanCache();
        assertFalse(cache.loaded());
        cache.put(ban(1, STEVE, NOW + 100));
        cache.put(ban(2, STEVE, NOW + 50));
        assertEquals(1, cache.get(STEVE, NOW).orElseThrow().id());
        cache.put(ban(3, STEVE, null));
        assertEquals(3, cache.get(STEVE, NOW).orElseThrow().id());
        cache.forget(List.of(3L));
        assertTrue(cache.get(STEVE, NOW).isEmpty());
        cache.put(ban(4, STEVE, NOW + 10));
        assertTrue(cache.get(STEVE, NOW + 10).isEmpty(), "expired is absent");
        assertEquals(0, cache.size(), "and removed lazily");
        cache.load(List.of());
        assertTrue(cache.loaded());
    }
}
