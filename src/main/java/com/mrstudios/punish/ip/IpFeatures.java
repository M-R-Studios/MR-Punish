package com.mrstudios.punish.ip;

import com.mrstudios.punish.config.Settings;
import com.mrstudios.punish.storage.IpRepo;
import com.mrstudios.punish.storage.Storage;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Whether IP features are on, the hasher, and the hash of each online account's connection
 * (DESIGN.md 8.1, 8.3). They're on only if {@code ip.enabled} was true at startup, the key file
 * could be read or created, and its fingerprint matches the one this database was used with.
 */
public final class IpFeatures {

    private final Settings.Ip settings;
    private final Storage storage;
    private final Logger log;
    private volatile IpHasher hasher;
    private volatile boolean verified;
    private volatile String off = "turned off in config.yml";
    /** The hash of each online account's current connection. */
    private final Map<UUID, String> online = new ConcurrentHashMap<>();
    /** Hashes computed at pre-login, waiting for the join. */
    private final Map<UUID, String> pending = new ConcurrentHashMap<>();

    public IpFeatures(Settings.Ip settings, Storage storage, Logger log) {
        this.settings = settings;
        this.storage = storage;
        this.log = log;
    }

    public Settings.Ip settings() {
        return settings;
    }

    /**
     * Startup: with IP features off, purges stored links (if configured); with them on, loads or
     * creates the key and checks its fingerprint. Called again when storage comes back, if the
     * check couldn't run before.
     */
    public void start(Path folder) {
        if (!settings.enabled()) {
            if (settings.purgeWhenDisabled() && storage.health().isUp()) {
                try {
                    int deleted = storage.executor().submit("deleting stored IP links",
                            c -> new IpRepo(storage.database()).purge(c)).get(10, TimeUnit.SECONDS);
                    if (deleted > 0) {
                        log.info("IP features are off: deleted " + deleted + " stored IP link(s) (ip.purge-when-disabled).");
                    }
                } catch (Exception e) {
                    log.warning("Couldn't delete the stored IP links: " + e.getClass().getSimpleName());
                }
            }
            return;
        }
        if (hasher == null) {
            try {
                IpSecret.Loaded loaded = IpSecret.loadOrCreate(folder.resolve(IpSecret.FILE));
                hasher = new IpHasher(loaded.secret(), settings.ipv6Prefix());
                if (loaded.created()) {
                    log.info("Created " + IpSecret.FILE + " for IP features. Back it up: if it's lost, existing IP"
                            + " punishments stop matching. On a network, copy the same file to every server.");
                }
            } catch (IOException e) {
                off = "the key file can't be used: " + e.getMessage();
                log.severe("IP features stay off: " + off);
                return;
            }
        }
        try {
            // At startup, before anyone can join: worth a short wait so logins are checked by IP at once.
            verify().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warning("The IP key check is taking long; IP features turn on once it's done.");
        }
    }

    /**
     * Compares the key's fingerprint with the database's (writing it the first time), off the main
     * thread. Completes when the check is done; never fails.
     */
    public java.util.concurrent.CompletableFuture<Void> verify() {
        if (hasher == null || verified) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        if (!storage.health().isUp()) {
            off = "waiting for the database to check the IP key";
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        String mine = hasher.fingerprint();
        return storage.executor().submit("checking the IP key", c -> {
            IpRepo repo = new IpRepo(storage.database());
            Optional<String> existing = repo.fingerprint(c);
            if (existing.isEmpty()) {
                repo.setFingerprint(c, mine);
            }
            return existing;
        }).handle((stored, error) -> {
            if (error != null) {
                off = "the IP key couldn't be checked yet";
                log.warning("Couldn't check the IP key yet (" + error.getClass().getSimpleName() + "); will try again.");
            } else if (stored.isPresent() && !stored.get().equals(mine)) {
                off = IpSecret.FILE + " doesn't match the key this database was used with";
                log.severe("IP features stay off: " + IpSecret.FILE + " is a different key from the one this database"
                        + " was used with (another server uses another key, or the file was replaced). IP punishments"
                        + " won't match until the same " + IpSecret.FILE + " is copied to every server.");
            } else {
                verified = true;
                log.info("IP features are on.");
            }
            return null;
        });
    }

    /** Whether IP punishments are issued and enforced right now. */
    public boolean enabled() {
        return settings.enabled() && verified;
    }

    /** "on", or "off: why", for /mrpunish version. */
    public String describe() {
        return enabled() ? "on" : "off (" + off + ")";
    }

    /** The hash of {@code address}, if IP features are on. Never returns or logs the address. */
    public Optional<String> hash(InetAddress address) {
        IpHasher current = hasher;
        if (!enabled() || current == null || address == null) {
            return Optional.empty();
        }
        return Optional.of(current.hash(address));
    }

    /** Pre-login: remembers the joining account's hash until the join. */
    public void prepare(UUID uuid, String hash) {
        pending.put(uuid, hash);
    }

    /** Join: the account's connection hash is now known for as long as it's online. */
    public Optional<String> joined(UUID uuid, InetAddress fallback) {
        String hash = pending.remove(uuid);
        if (hash == null) {
            hash = hash(fallback).orElse(null);
        }
        if (hash != null) {
            online.put(uuid, hash);
        }
        return Optional.ofNullable(hash);
    }

    public void quit(UUID uuid) {
        online.remove(uuid);
        pending.remove(uuid);
    }

    /** The current connection hash of an online account. */
    public Optional<String> onlineHash(UUID uuid) {
        return Optional.ofNullable(online.get(uuid));
    }

    /** Online accounts on {@code hash} right now (DESIGN.md 8.3, affected accounts). */
    public java.util.List<UUID> onlineOn(String hash) {
        return online.entrySet().stream().filter(e -> e.getValue().equals(hash)).map(Map.Entry::getKey).toList();
    }

    /** {@code now - association-days}. */
    public long associationSince(long now) {
        return now - TimeUnit.DAYS.toMillis(settings.associationDays());
    }
}
