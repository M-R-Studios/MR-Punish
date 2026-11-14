package com.mrstudios.punish.enforce;

import com.mrstudios.punish.config.PluginState;
import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.punish.Punishment;
import com.mrstudios.punish.punish.PunishmentService;
import com.mrstudios.punish.punish.PunishmentType;
import com.mrstudios.punish.punish.Requests;
import com.mrstudios.punish.storage.PunishmentRepo;
import com.mrstudios.punish.storage.Storage;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Logger;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Bans at login and after a commit (DESIGN.md 7.7, 7.8): the async pre-login check with the fail
 * policy, the ban cache, kicking an online target, and re-checking players let in unverified.
 */
public final class BanEnforcement implements Listener, PunishmentService.Listener {

    public static final String ADMIN = "mrpunish.admin";
    private static final long LOG_EVERY_MS = 10_000;
    private static final long ALERT_EVERY_MS = 60_000;

    private final Plugin plugin;
    private final Storage storage;
    private final Supplier<PluginState> state;
    private final BanCache cache;
    private final MuteCache mutes;
    private final com.mrstudios.punish.ip.IpFeatures ip;
    private final LongSupplier clock;
    private final Logger log;
    private final Set<UUID> unverified = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastLog = new AtomicLong(Long.MIN_VALUE / 2);
    private final AtomicInteger unlogged = new AtomicInteger();
    private final AtomicLong lastAlert = new AtomicLong(Long.MIN_VALUE / 2);

    public BanEnforcement(Plugin plugin, Storage storage, Supplier<PluginState> state, BanCache cache,
            MuteCache mutes, com.mrstudios.punish.ip.IpFeatures ip, LongSupplier clock, Logger log) {
        this.plugin = plugin;
        this.storage = storage;
        this.state = state;
        this.cache = cache;
        this.mutes = mutes;
        this.ip = ip;
        this.clock = clock;
        this.log = log;
    }

    public BanCache cache() {
        return cache;
    }

    public Set<UUID> unverified() {
        return unverified;
    }

    // ---- Login ----

    /** LOW, so plugins at NORMAL and above see our result; logins others already refused are left alone. */
    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        PluginState current = state.get();
        Settings settings = current.settings();
        UUID uuid = event.getUniqueId();
        // Hashed here and only here; the address itself goes nowhere (DESIGN.md 8.1).
        String hash = ip.hash(event.getAddress()).orElse(null);
        if (hash != null) {
            ip.prepare(uuid, hash);
        }
        String[] failure = new String[1];
        LoginCheck.Lookup lookup = lookup(uuid, hash, settings.login().timeoutMs(), failure);
        long now = clock.getAsLong();
        LoginCheck.Decision decision = LoginCheck.decide(uuid, hash, lookup, settings.login().failPolicy(), cache,
                now);
        switch (decision.outcome()) {
            case DENY_BANNED -> event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    BanScreen.of(current.messages(), settings.display(), decision.ban(), now));
            case DENY_UNVERIFIED -> event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    BanScreen.unverified(current.messages()));
            case ALLOW_UNVERIFIED -> unverified.add(uuid);
            case ALLOW -> unverified.remove(uuid);
        }
        if (lookup.ok()) {
            // Loaded together with the ban, so a muted player can't chat in their first seconds.
            mutes.set(uuid, lookup.mute());
            mutes.setIp(hash, lookup.ipMute());
        }
        if (!lookup.ok()) {
            reportFailure(event.getName(), failure[0], settings.login().failPolicy(), decision.outcome());
        }
    }

    /** Asks the database for the account's active ban, waiting at most {@code timeoutMs}. Any thread but main. */
    LoginCheck.Lookup lookup(UUID uuid, String hash, long timeoutMs, String[] failure) {
        if (!storage.health().isUp()) {
            failure[0] = "unavailable: " + storage.health().reason();
            return LoginCheck.Lookup.failed();
        }
        long now = clock.getAsLong();
        try {
            return storage.executor().submit("checking a login", c -> find(c, uuid, hash, now))
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            failure[0] = "too slow (over " + timeoutMs + " ms)";
        } catch (ExecutionException e) {
            failure[0] = "error: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure[0] = "interrupted";
        }
        return LoginCheck.Lookup.failed();
    }

    /**
     * The account's latest-ending active ban (its own or an IP ban on {@code hash}), its mute and the
     * IP mute on {@code hash}, in one read. Database thread.
     *
     * @param hash the connection's IP hash, or null when IP features are off
     */
    LoginCheck.Lookup find(java.sql.Connection c, UUID uuid, String hash, long now) throws java.sql.SQLException {
        PunishmentRepo repo = new PunishmentRepo(storage.database());
        PunishmentRepo.Subject subject = new PunishmentRepo.Subject(uuid, null);
        Comparator<Punishment> byEnd = Comparator.comparing(p -> p.expiresAt() == null ? Long.MAX_VALUE : p.expiresAt());
        List<Punishment> bans = new java.util.ArrayList<>(repo.active(c, PunishmentType.BAN, subject, now));
        Optional<Punishment> ipMute = Optional.empty();
        if (hash != null) {
            PunishmentRepo.Subject ipSubject = new PunishmentRepo.Subject(null, hash);
            bans.addAll(repo.active(c, PunishmentType.IP_BAN, ipSubject, now));
            ipMute = repo.active(c, PunishmentType.IP_MUTE, ipSubject, now).stream().max(byEnd);
        }
        return LoginCheck.Lookup.of(bans.stream().max(byEnd),
                repo.active(c, PunishmentType.MUTE, subject, now).stream().max(byEnd), ipMute);
    }

    /** One WARNING per 10 s (with a count of the rest), one admin alert per minute (DESIGN.md 7.7). */
    private void reportFailure(String name, String why, Settings.FailPolicy policy, LoginCheck.Outcome outcome) {
        long now = System.currentTimeMillis();
        long last = lastLog.get();
        if (now - last >= LOG_EVERY_MS && lastLog.compareAndSet(last, now)) {
            int others = unlogged.getAndSet(0);
            log.warning("Couldn't check " + name + "'s punishments at login (database " + why + "). Policy " + policy
                    + ": " + describe(outcome) + "." + (others > 0 ? " " + others + " more login(s) since the last"
                    + " message had the same problem." : ""));
        } else {
            unlogged.incrementAndGet();
        }
        long alerted = lastAlert.get();
        if (now - alerted >= ALERT_EVERY_MS && lastAlert.compareAndSet(alerted, now) && plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (player.hasPermission(ADMIN)) {
                        state.get().messages().send(player, "notify.login-check-failed",
                                Placeholder.unparsed("reason", why), Placeholder.unparsed("policy", policy.name()));
                    }
                }
            });
        }
    }

    private static String describe(LoginCheck.Outcome outcome) {
        return switch (outcome) {
            case DENY_BANNED -> "denied (banned in the in-memory copy)";
            case DENY_UNVERIFIED -> "denied with the try-again screen";
            case ALLOW_UNVERIFIED -> "let in, to be checked again once the database answers";
            case ALLOW -> "let in";
        };
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        unverified.remove(event.getPlayer().getUniqueId());
    }

    /** LOWEST, so the hash is known before anything else handles the join. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        track(event.getPlayer());
    }

    /** Learns the hash of the player's connection and records the IP link (when IP features are on). */
    public void track(Player player) {
        java.net.InetSocketAddress address = player.getAddress();
        Optional<String> hash = ip.joined(player.getUniqueId(), address == null ? null : address.getAddress());
        if (hash.isPresent() && storage.health().isUp()) {
            UUID uuid = player.getUniqueId();
            long now = clock.getAsLong();
            storage.executor().submit("recording an IP link",
                    c -> {
                        new com.mrstudios.punish.storage.IpRepo(storage.database()).seen(c, uuid, hash.get(), now);
                        return null;
                    });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitIp(PlayerQuitEvent event) {
        ip.quit(event.getPlayer().getUniqueId());
    }

    /**
     * Checks again everyone let in unverified who is still online; a ban found now kicks them.
     * Main thread. Returns how many checks were started.
     */
    public int recheckUnverified() {
        if (unverified.isEmpty() || !storage.health().isUp()) {
            return 0;
        }
        int started = 0;
        for (UUID uuid : List.copyOf(unverified)) {
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null) {
                unverified.remove(uuid);
                continue;
            }
            started++;
            long now = clock.getAsLong();
            String hash = ip.onlineHash(uuid).orElse(null);
            storage.executor().submit("re-checking " + player.getName(), c -> find(c, uuid, hash, now))
                    .thenAccept(lookup -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                        unverified.remove(uuid);
                        mutes.set(uuid, lookup.mute());
                        mutes.setIp(hash, lookup.ipMute());
                        Optional<Punishment> ban = lookup.ban();
                        Player online = plugin.getServer().getPlayer(uuid);
                        if (ban.isPresent() && online != null) {
                            PluginState current = state.get();
                            log.info("Re-check: " + online.getName() + " is banned (#" + ban.get().id()
                                    + "); kicking them.");
                            online.kick(BanScreen.of(current.messages(), current.settings().display(), ban.get(),
                                    clock.getAsLong()), PlayerKickEvent.Cause.BANNED);
                        }
                    }));
        }
        return started;
    }

    /** Fills the cache from the database: startup, and whenever the database comes back. */
    public void loadCache() {
        if (!storage.health().isUp()) {
            return;
        }
        long now = clock.getAsLong();
        storage.executor().submit("loading the active bans",
                c -> {
                    PunishmentRepo repo = new PunishmentRepo(storage.database());
                    List<Punishment> all = new java.util.ArrayList<>(repo.allActive(c, PunishmentType.BAN, now));
                    if (ip.enabled()) {
                        all.addAll(repo.allActive(c, PunishmentType.IP_BAN, now));
                    }
                    return all;
                })
                .thenAccept(bans -> {
                    cache.load(bans);
                    log.info("Loaded " + bans.size() + " active ban(s) into the login cache.");
                });
    }

    // ---- After a commit (main thread) ----

    @Override
    public void issued(PluginState snapshot, Requests.Issue request, Requests.IssueResult.Committed committed) {
        applyBan(snapshot, committed.punishment(), committed.replaced());
    }

    /**
     * A ban issued on another server sharing the database (DESIGN.md 9.4): cached, and the players
     * it covers kicked if they're here. {@code p} was read again from the database.
     */
    public void remoteIssued(Punishment p) {
        if (p.isActive(clock.getAsLong())) {
            applyBan(state.get(), p, List.of());
        }
    }

    /** A ban removed on another server: no longer in the login cache here. */
    public void remoteRemoved(Punishment p) {
        cache.forget(List.of(p.id()));
    }

    private void applyBan(PluginState snapshot, Punishment p, List<Long> replaced) {
        if (p.type() != PunishmentType.BAN && p.type() != PunishmentType.IP_BAN) {
            return;
        }
        cache.forget(replaced);
        cache.put(p);
        if (!snapshot.settings().banKickOnline()) {
            return;
        }
        // An IP ban kicks every account on that IP right now (DESIGN.md 8.4).
        List<UUID> targets = p.type() == PunishmentType.IP_BAN ? ip.onlineOn(p.ipHash()) : List.of(p.targetUuid());
        for (UUID uuid : targets) {
            Player online = plugin.getServer().getPlayer(uuid);
            if (online != null) {
                online.kick(BanScreen.of(snapshot.messages(), snapshot.settings().display(), p, clock.getAsLong()),
                        PlayerKickEvent.Cause.BANNED);
            }
        }
    }

    @Override
    public void removed(PluginState snapshot, Requests.Remove request, Requests.RemoveResult.Removed removed) {
        if (request.type() == PunishmentType.BAN || request.type() == PunishmentType.IP_BAN) {
            cache.forget(removed.removed().stream().map(Punishment::id).toList());
        }
    }
}
