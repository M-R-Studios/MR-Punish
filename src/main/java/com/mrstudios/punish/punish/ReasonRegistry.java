package com.mrstudios.punish.punish;

import com.mrstudios.punish.config.ConfigException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Every configured reason, and which word picks which reason for each type. Words are matched
 * case-insensitively and only within one type, so {@code /warn x spam} and {@code /mute x spam} can
 * pick different reasons. A reason's key always beats another reason's alias; between two aliases
 * the reason listed first in the file keeps the word.
 */
public final class ReasonRegistry {

    /**
     * What a command's reason text resolved to.
     *
     * @param reason   the reason to use
     * @param note     the staff note, or null if there is none
     * @param fallback true when the first word matched nothing and the type's fallback reason is used
     *                 (the whole text is then the note)
     */
    public record Resolution(Reason reason, String note, boolean fallback) {
    }

    private final Map<String, Reason> byKey;
    private final Map<PunishmentType, Map<String, Reason>> words;
    private final Map<PunishmentType, List<Reason>> enabledByType;
    private final Map<PunishmentType, Reason> fallbacks;

    private ReasonRegistry(Map<String, Reason> byKey, Map<PunishmentType, Map<String, Reason>> words,
            Map<PunishmentType, List<Reason>> enabledByType, Map<PunishmentType, Reason> fallbacks) {
        this.byKey = byKey;
        this.words = words;
        this.enabledByType = enabledByType;
        this.fallbacks = fallbacks;
    }

    /**
     * Builds the registry from reasons in file order (keys already unique and lower case).
     *
     * @param fallbackKeys the {@code fallback-reasons} entries, by type
     * @param warnings     receives a line for every alias that lost to another reason
     * @throws ConfigException if a type has no fallback, or its fallback isn't an enabled reason of
     *                         that type: commands couldn't handle an unmatched reason safely
     */
    public static ReasonRegistry build(Collection<Reason> reasons, Map<PunishmentType, String> fallbackKeys,
            List<String> warnings) throws ConfigException {
        Map<String, Reason> byKey = new LinkedHashMap<>();
        Map<PunishmentType, Map<String, Reason>> words = new EnumMap<>(PunishmentType.class);
        Map<PunishmentType, List<Reason>> enabledByType = new EnumMap<>(PunishmentType.class);
        for (PunishmentType type : PunishmentType.values()) {
            words.put(type, new HashMap<>());
            enabledByType.put(type, new ArrayList<>());
        }
        for (Reason reason : reasons) {
            if (byKey.putIfAbsent(reason.key(), reason) != null) {
                throw new IllegalArgumentException("Duplicate reason key " + reason.key());
            }
            if (reason.enabled()) {
                words.get(reason.type()).put(reason.key(), reason);
                enabledByType.get(reason.type()).add(reason);
            }
        }
        for (Reason reason : reasons) {
            if (!reason.enabled()) {
                continue;
            }
            Map<String, Reason> typeWords = words.get(reason.type());
            for (String alias : reason.aliases()) {
                Reason owner = typeWords.putIfAbsent(alias, reason);
                if (owner == null || owner == reason) {
                    continue;
                }
                String why = owner.key().equals(alias)
                        ? "is the key of reason '" + owner.key() + "'"
                        : "is already an alias of reason '" + owner.key() + "', which is listed first";
                warnings.add("reasons." + reason.key() + ".aliases: '" + alias + "' " + why + " ("
                        + reason.type() + "), so it picks '" + owner.key() + "'");
            }
        }

        Map<PunishmentType, Reason> fallbacks = new EnumMap<>(PunishmentType.class);
        List<String> errors = new ArrayList<>();
        for (PunishmentType type : PunishmentType.values()) {
            String key = fallbackKeys.get(type);
            Reason reason = key == null ? null : byKey.get(key.toLowerCase(Locale.ROOT));
            if (key == null) {
                errors.add("fallback-reasons." + type + " is missing");
            } else if (reason == null || reason.type() != type || !reason.enabled()) {
                errors.add("fallback-reasons." + type + ": '" + key + "' is not an enabled " + type + " reason"
                        + (reason == null ? "" : reason.type() != type ? " (it's a " + reason.type() + " reason)"
                        : " (it's disabled)"));
            } else {
                fallbacks.put(type, reason);
            }
        }
        if (!errors.isEmpty()) {
            throw new ConfigException("config.yml: " + String.join("; ", errors)
                    + ". Every type needs an enabled fallback reason, so the whole file was not used.");
        }

        Map<PunishmentType, Map<String, Reason>> frozenWords = new EnumMap<>(PunishmentType.class);
        words.forEach((type, map) -> frozenWords.put(type, Map.copyOf(map)));
        Map<PunishmentType, List<Reason>> frozenEnabled = new EnumMap<>(PunishmentType.class);
        enabledByType.forEach((type, list) -> frozenEnabled.put(type, List.copyOf(list)));
        return new ReasonRegistry(java.util.Collections.unmodifiableMap(byKey), frozenWords, frozenEnabled,
                fallbacks);
    }

    /** The reason with this key, enabled or not (old punishments may name a disabled reason). */
    public Optional<Reason> byKey(String key) {
        return Optional.ofNullable(byKey.get(key.toLowerCase(Locale.ROOT)));
    }

    /** Every loaded reason, in file order. */
    public Collection<Reason> all() {
        return byKey.values();
    }

    /** The enabled reasons of {@code type}, in file order. */
    public List<Reason> enabled(PunishmentType type) {
        return enabledByType.get(type);
    }

    /** Every word (key or alias) that picks an enabled reason of {@code type}. */
    public Map<String, Reason> words(PunishmentType type) {
        return words.get(type);
    }

    /** The reason {@code type} falls back to when a command's reason matches nothing. Always enabled. */
    public Reason fallback(PunishmentType type) {
        return fallbacks.get(type);
    }

    /** The enabled reason of {@code type} whose key or alias is {@code word}. */
    public Optional<Reason> match(PunishmentType type, String word) {
        return Optional.ofNullable(words.get(type).get(word.toLowerCase(Locale.ROOT)));
    }

    /**
     * Resolves the text a command got after the player name (and {@code -s}). The first word picks
     * the reason and the rest is the note. If the first word matches nothing, the fallback reason is
     * used and the whole text is the note. Empty text resolves to nothing (the caller opens a menu
     * or shows the usage).
     */
    public Optional<Resolution> resolve(PunishmentType type, String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        String[] split = trimmed.split("\\s+", 2);
        Optional<Reason> matched = match(type, split[0]);
        if (matched.isPresent()) {
            String note = split.length > 1 ? split[1].strip() : null;
            return Optional.of(new Resolution(matched.get(), note, false));
        }
        return Optional.of(new Resolution(fallback(type), trimmed, true));
    }

    /** Whether someone with these permissions may use {@code reason} (its optional extra permission). */
    public static boolean mayUse(Reason reason, Predicate<String> hasPermission) {
        return reason.permission() == null || hasPermission.test(reason.permission());
    }
}
