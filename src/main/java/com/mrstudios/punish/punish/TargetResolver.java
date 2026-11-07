package com.mrstudios.punish.punish;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Turns a command's player argument into a known player (DESIGN.md 4.1, X1-X3): a name or a UUID
 * (with or without dashes). Online players and the {@link NameIndex} answer at once; anything else
 * is looked up in the database. Players who never joined are unknown (v1 has no Mojang lookup).
 */
public final class TargetResolver {

    /** Letters, digits, _ and the . and - of Floodgate prefixes and UUIDs. */
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_.\\-]{1,36}");
    private static final Pattern UUID_PLAIN = Pattern.compile("[0-9a-fA-F]{32}");
    private static final Pattern UUID_DASHED =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int MAX_NAME = 32;

    public enum Outcome {
        FOUND,
        /** Characters a player name can't have: {@code invalid-player}. */
        INVALID,
        /** Never joined this server (or network): {@code unknown-player}. */
        UNKNOWN
    }

    /** @param online whether the player was online when resolved */
    public record Target(UUID uuid, String name, boolean online) {
    }

    /** @param target set when {@link Outcome#FOUND} */
    public record Resolution(Outcome outcome, String input, Target target) {

        static Resolution found(String input, Target target) {
            return new Resolution(Outcome.FOUND, input, target);
        }
    }

    /** What the resolver needs from the server and the database. */
    public interface Lookups {

        /** The online player with this exact name (ignoring case). Main thread. */
        Optional<Target> onlineByName(String name);

        Optional<Target> onlineByUuid(UUID uuid);

        /** The most recently seen player with this name, from the database. */
        CompletableFuture<Optional<NameIndex.Entry>> storedByName(String name);

        CompletableFuture<Optional<NameIndex.Entry>> storedByUuid(UUID uuid);
    }

    private final NameIndex index;
    private final Lookups lookups;

    public TargetResolver(NameIndex index, Lookups lookups) {
        this.index = index;
        this.lookups = lookups;
    }

    /**
     * Resolves {@code input}. Called on the main thread; the future may complete on a database
     * thread, so callers hop back before touching the server.
     */
    public CompletableFuture<Resolution> resolve(String input) {
        String text = input == null ? "" : input.strip();
        if (!ALLOWED.matcher(text).matches()) {
            return CompletableFuture.completedFuture(new Resolution(Outcome.INVALID, text, null));
        }
        Optional<UUID> uuid = parseUuid(text);
        if (uuid.isPresent()) {
            Optional<Target> online = lookups.onlineByUuid(uuid.get());
            if (online.isPresent()) {
                return CompletableFuture.completedFuture(Resolution.found(text, online.get()));
            }
            Optional<NameIndex.Entry> known = index.byUuid(uuid.get());
            if (known.isPresent()) {
                return CompletableFuture.completedFuture(Resolution.found(text, offline(known.get())));
            }
            return lookups.storedByUuid(uuid.get()).thenApply(found -> fromStore(text, found));
        }
        if (text.length() > MAX_NAME) {
            return CompletableFuture.completedFuture(new Resolution(Outcome.INVALID, text, null));
        }
        Optional<Target> online = lookups.onlineByName(text);
        if (online.isPresent()) {
            return CompletableFuture.completedFuture(Resolution.found(text, online.get()));
        }
        Optional<NameIndex.Entry> known = index.byName(text);
        if (known.isPresent()) {
            return CompletableFuture.completedFuture(Resolution.found(text, offline(known.get())));
        }
        return lookups.storedByName(text).thenApply(found -> fromStore(text, found));
    }

    private Resolution fromStore(String input, Optional<NameIndex.Entry> found) {
        if (found.isEmpty()) {
            return new Resolution(Outcome.UNKNOWN, input, null);
        }
        index.record(found.get().uuid(), found.get().name(), found.get().lastSeen());
        return Resolution.found(input, offline(found.get()));
    }

    private static Target offline(NameIndex.Entry entry) {
        return new Target(entry.uuid(), entry.name(), false);
    }

    /** A UUID written with or without dashes. */
    static Optional<UUID> parseUuid(String text) {
        String dashed;
        if (UUID_DASHED.matcher(text).matches()) {
            dashed = text;
        } else if (UUID_PLAIN.matcher(text).matches()) {
            dashed = text.substring(0, 8) + "-" + text.substring(8, 12) + "-" + text.substring(12, 16) + "-"
                    + text.substring(16, 20) + "-" + text.substring(20);
        } else {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(dashed));
    }
}
