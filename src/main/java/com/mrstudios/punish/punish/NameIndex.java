package com.mrstudios.punish.punish;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every known player's name and UUID, in memory (DESIGN.md 2.3): loaded once at startup and updated
 * on every join, so tab completion and name lookups never wait for the database. Never authoritative:
 * a miss falls back to a database query. Safe to use from any thread.
 */
public final class NameIndex {

    /** A player as last seen. */
    public record Entry(UUID uuid, String name, long lastSeen) {
    }

    private final Map<String, Entry> byName = new ConcurrentHashMap<>();
    private final Map<UUID, Entry> byUuid = new ConcurrentHashMap<>();

    /** Adds players read from the database (startup). Newer information already in the index wins. */
    public void load(Collection<Entry> entries) {
        for (Entry entry : entries) {
            record(entry);
        }
    }

    /** A player was seen with {@code name} at {@code at} (a join, or a database lookup). */
    public void record(UUID uuid, String name, long at) {
        record(new Entry(uuid, name, at));
    }

    private synchronized void record(Entry entry) {
        Entry old = byUuid.get(entry.uuid());
        if (old != null && old.lastSeen() > entry.lastSeen()) {
            return;
        }
        byUuid.put(entry.uuid(), entry);
        if (old != null && !old.name().equalsIgnoreCase(entry.name())) {
            // That name no longer belongs to this player; an older owner is found in the database.
            byName.remove(key(old.name()), old);
        }
        // A name used by two accounts over time belongs to the one seen most recently (DESIGN.md X2).
        byName.merge(key(entry.name()), entry,
                (current, added) -> !current.uuid().equals(added.uuid()) && current.lastSeen() > added.lastSeen()
                        ? current : added);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public Optional<Entry> byName(String name) {
        return Optional.ofNullable(byName.get(key(name)));
    }

    public Optional<Entry> byUuid(UUID uuid) {
        return Optional.ofNullable(byUuid.get(uuid));
    }

    /** Up to {@code limit} known names starting with {@code prefix} (ignoring case), in no fixed order. */
    public List<String> suggest(String prefix, int limit) {
        String lower = key(prefix);
        List<String> names = new ArrayList<>();
        for (Entry entry : byName.values()) {
            if (names.size() >= limit) {
                break;
            }
            if (key(entry.name()).startsWith(lower)) {
                names.add(entry.name());
            }
        }
        return names;
    }

    public int size() {
        return byUuid.size();
    }
}
