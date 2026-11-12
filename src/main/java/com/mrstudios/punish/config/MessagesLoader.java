package com.mrstudios.punish.config;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Builds {@link Messages} from {@code messages.yml}. A missing key, or a message with a tag
 * MiniMessage can't use, falls back to the text bundled in the jar with a warning naming the key.
 */
public final class MessagesLoader {

    private static final String VERSION_KEY = "messages-version";

    /**
     * Placeholders each message may use besides {@code <prefix>} and the standard MiniMessage tags.
     * Messages not listed take none.
     */
    private static final Map<String, Set<String>> PLACEHOLDERS = Map.ofEntries(
            Map.entry("admin.version", Set.of("version", "server", "storage", "storage-status", "ip-features", "clashes")),
            Map.entry("general.warning-offence", Set.of("offence")),
            Map.entry("notify.issued", Set.of("staff", "type-verb", "target", "reason", "duration", "id", "silent",
                    "note")),
            Map.entry("notify.removed", Set.of("staff", "type", "ids", "target", "removal-reason")),
            Map.entry("notify.ip-suffix", Set.of("accounts")),
            Map.entry("notify.note-suffix", Set.of("note")),
            Map.entry("announce.WARN", Set.of("target", "reason")),
            Map.entry("announce.MUTE", Set.of("target", "reason")),
            Map.entry("announce.BAN", Set.of("target", "reason")),
            Map.entry("announce.IP_MUTE", Set.of("target", "reason")),
            Map.entry("announce.IP_BAN", Set.of("target", "reason")),
            Map.entry("general.invalid-player", Set.of("input")),
            Map.entry("punish.changed", Set.of("player")),
            Map.entry("staff.staffchat-format", Set.of("rank", "player", "message")),
            Map.entry("staff.stafflist-header", Set.of("count")),
            Map.entry("staff.stafflist-line", Set.of("rank", "player", "tags")),
            Map.entry("staff.vanish-other-on", Set.of("player")),
            Map.entry("staff.vanish-other-off", Set.of("player")),
            Map.entry("staff.vanish-by-other-on", Set.of("staff")),
            Map.entry("staff.vanish-by-other-off", Set.of("staff")),
            Map.entry("staff.vanish-rank", Set.of("player")),
            Map.entry("staff.profile", Set.of("player", "rank", "p30", "ptotal", "w", "m", "b", "ip-punishments", "reports")),
            Map.entry("staff.profile-action", Set.of("date", "action", "target")),
            Map.entry("staff.profile-buttons", Set.of("forceexit-click", "player")),
            Map.entry("staff.stafflog-header", Set.of("filter", "page", "pages")),
            Map.entry("staff.stafflog-line", Set.of("date", "actor", "action", "target", "details")),
            Map.entry("input.prompt-search", Set.of("cancel-word")),
            Map.entry("staff.staffmode-forced", Set.of("staff")),
            Map.entry("staff.staffmode-refused", Set.of("why")),
            Map.entry("staff.tool-teleported", Set.of("player")),
            Map.entry("staff.forceexit-done", Set.of("player")),
            Map.entry("staff.forceexit-none", Set.of("player")),
            Map.entry("staff.forceexit-rank", Set.of("player")),
            Map.entry("staff.report-teleport-offline", Set.of("player")),
            Map.entry("staff.freeze-done", Set.of("player")),
            Map.entry("staff.unfreeze-done", Set.of("player")),
            Map.entry("staff.already-frozen", Set.of("player")),
            Map.entry("staff.not-frozen", Set.of("player")),
            Map.entry("staff.freeze-offline", Set.of("player")),
            Map.entry("staff.freeze-rank", Set.of("player")),
            Map.entry("staff.freeze-failed", Set.of("error-id")),
            Map.entry("staff.inspect-offline", Set.of("player")),
            Map.entry("staff.inspect-closed", Set.of("player")),
            Map.entry("notify.freeze-quit", Set.of("player")),
            Map.entry("notify.freeze-join", Set.of("player", "staff")),
            Map.entry("notify.freeze-ban-failed", Set.of("player", "why")),
            Map.entry("notify.froze", Set.of("staff", "player")),
            Map.entry("notify.unfroze", Set.of("staff", "player")),
            Map.entry("staff.vanish-target-cant", Set.of("player")),
            Map.entry("staff.vanish-joined", Set.of("player")),
            Map.entry("staff.vanish-left", Set.of("player")),
            Map.entry("reports.sent", Set.of("player", "id")),
            Map.entry("reports.cooldown", Set.of("remaining")),
            Map.entry("reports.duplicate", Set.of("player", "id")),
            Map.entry("reports.too-many", Set.of("max")),
            Map.entry("reports.failed", Set.of("error-id")),
            Map.entry("reports.new", Set.of("reporter", "target", "reason", "id")),
            Map.entry("reports.claimed", Set.of("id")),
            Map.entry("reports.already-claimed", Set.of("id", "staff")),
            Map.entry("reports.unclaimed", Set.of("id")),
            Map.entry("reports.claim-expired", Set.of("id")),
            Map.entry("reports.closed", Set.of("id")),
            Map.entry("reports.not-found", Set.of("id")),
            Map.entry("reports.action-failed", Set.of("id", "error-id")),
            Map.entry("reports.resolved", Set.of("staff", "id", "target")),
            Map.entry("reports.rejected", Set.of("staff", "id", "target")),
            Map.entry("reports.note-added", Set.of("id")),
            Map.entry("reports.reporter-resolved", Set.of("target")),
            Map.entry("reports.reporter-rejected", Set.of("target")),
            Map.entry("reports.reminder", Set.of("count")),
            Map.entry("reports.prompt-reason", Set.of("player", "cancel-word")),
            Map.entry("reports.prompt-note", Set.of("id", "cancel-word")),
            Map.entry("reports.prompt-decision", Set.of("id", "cancel-word")),
            Map.entry("reports.changed", Set.of("id")),
            Map.entry("history.console-header", Set.of("player", "total", "page", "pages")),
            Map.entry("history.console-line", Set.of("id", "type", "reason", "date", "duration", "staff", "status")),
            Map.entry("history.empty", Set.of("player")),
            Map.entry("history.remove-prompt", Set.of("id", "cancel-word")),
            Map.entry("history.not-active", Set.of("id")),
            Map.entry("input.prompt-note", Set.of("player", "cancel-word")),
            Map.entry("input.too-long", Set.of("max")),
            Map.entry("input.empty", Set.of("cancel-word")),
            Map.entry("general.unknown-player", Set.of("player")),
            Map.entry("general.usage", Set.of("usage")),
            Map.entry("punish.banned", Set.of("player", "reason", "duration", "id", "offence")),
            Map.entry("punish.warned", Set.of("player", "reason", "id", "offence")),
            Map.entry("punish.ip-muted", Set.of("player", "reason", "duration", "id", "accounts")),
            Map.entry("punish.ip-banned", Set.of("player", "reason", "duration", "id", "accounts")),
            Map.entry("punish.ip-rank", Set.of("player", "count")),
            Map.entry("punish.ip-unknown", Set.of("player")),
            Map.entry("admin.import-done-ips", Set.of("ips")),
            Map.entry("admin.import-ip-disabled", Set.of("count")),
            Map.entry("punish.muted", Set.of("player", "reason", "duration", "id", "offence")),
            Map.entry("unpunish.unwarn-not-found", Set.of("id", "player")),
            Map.entry("target.warn-chat", Set.of("reason", "staff")),
            Map.entry("target.warn-title", Set.of("reason", "staff")),
            Map.entry("target.warn-subtitle", Set.of("reason", "staff")),
            Map.entry("target.warn-offline", Set.of("reason", "staff", "date")),
            Map.entry("target.mute-chat", Set.of("reason", "staff", "duration", "appeal-link", "appeal-click")),
            Map.entry("target.muted-blocked", Set.of("reason", "remaining", "appeal-link")),
            Map.entry("target.muted-blocked-permanent", Set.of("reason", "appeal-link")),
            Map.entry("punish.replaced", Set.of("old-id")),
            Map.entry("punish.failed", Set.of("error-id")),
            Map.entry("punish.would-shorten", Set.of("player", "type", "id", "end", "remaining")),
            Map.entry("punish.would-shorten-permanent", Set.of("player", "type", "id")),
            Map.entry("punish.rank", Set.of("player")),
            Map.entry("punish.exempt", Set.of("player")),
            Map.entry("punish.reason-no-permission", Set.of("reason")),
            Map.entry("unpunish.removed", Set.of("count", "type", "player", "ids")),
            Map.entry("unpunish.none", Set.of("player", "type")),
            Map.entry("unpunish.issuer-rank", Set.of("id")),
            Map.entry("unpunish.failed", Set.of("error-id")),
            Map.entry("target.ban-screen", Set.of("reason", "staff", "expires", "remaining", "id", "appeal-link")),
            Map.entry("target.ban-screen-permanent", Set.of("reason", "staff", "id", "appeal-link")),
            Map.entry("notify.storage-back", Set.of("count")),
            Map.entry("notify.login-check-failed", Set.of("reason", "policy")),
            Map.entry("admin.import-done", Set.of("players", "skipped", "unknown")),
            Map.entry("admin.import-cleared", Set.of("count")),
            Map.entry("admin.import-failed", Set.of("error-id")),
            Map.entry("admin.reload-warnings", Set.of("count")),
            Map.entry("admin.reload-failed", Set.of("file")),
            Map.entry("admin.reload-detail", Set.of("detail")),
            Map.entry("admin.reload-restart-needed", Set.of("what")));

    private final YamlConfiguration defaults;
    private final ConfigMigrator migrator = ConfigMigrator.forMessages();

    /** @param defaults the {@code messages.yml} bundled in the jar */
    public MessagesLoader(YamlConfiguration defaults) {
        this.defaults = defaults;
    }

    public static YamlConfiguration bundledDefaults() {
        return YamlFiles.bundled("messages.yml");
    }

    /** The placeholder names {@code key} may use (for tests and tools). */
    static Set<String> placeholders(String key) {
        return PLACEHOLDERS.getOrDefault(key, Set.of());
    }

    /** The bundled messages, used when {@code messages.yml} can't be loaded at startup. */
    public Messages defaults() {
        return read(defaults).value();
    }

    /** Reads {@code file}, migrating it on disk first if it is from an older version. */
    public LoadResult<Messages> load(File file, Consumer<String> log) {
        try {
            return read(YamlFiles.load(file, migrator, log));
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    /** Parses and validates YAML text. Migrations are applied in memory only. */
    public LoadResult<Messages> load(String text) {
        try {
            YamlConfiguration yaml = YamlFiles.parse(text, migrator.fileName());
            migrator.migrate(yaml);
            return read(yaml);
        } catch (ConfigException e) {
            return LoadResult.failed(e.getMessage());
        }
    }

    LoadResult<Messages> read(YamlConfiguration yaml) {
        List<String> warnings = new ArrayList<>();
        Map<String, String> texts = new HashMap<>();
        Map<String, List<String>> lists = new HashMap<>();
        for (String key : defaults.getKeys(true)) {
            if (key.equals(VERSION_KEY) || defaults.isConfigurationSection(key)) {
                continue;
            }
            if (defaults.isList(key)) {
                lists.put(key, readLines(yaml, key, warnings));
            } else {
                texts.put(key, readText(yaml, key, warnings));
            }
        }
        for (String key : yaml.getKeys(true)) {
            if (defaults.contains(key)) {
                continue;
            }
            int dot = key.lastIndexOf('.');
            // Below an unknown key or a known message: already reported once.
            if (dot >= 0 && !defaults.isConfigurationSection(key.substring(0, dot))) {
                continue;
            }
            warnings.add(key + " is not a known message (typo?), ignored");
        }
        return LoadResult.loaded(new Messages(texts, lists), warnings);
    }

    private String readText(YamlConfiguration yaml, String key, List<String> warnings) {
        String def = defaults.getString(key);
        Object value = yaml.get(key);
        if (value == null) {
            warnings.add(key + " is missing, using the default");
            return def;
        }
        if (!(value instanceof String text)) {
            warnings.add(key + ": '" + value + "' is not text, using the default");
            return def;
        }
        String problem = problem(key, text);
        if (problem != null) {
            warnings.add(key + ": " + problem + ", using the default");
            return def;
        }
        return text;
    }

    private List<String> readLines(YamlConfiguration yaml, String key, List<String> warnings) {
        List<String> def = defaults.getStringList(key);
        Object value = yaml.get(key);
        if (value == null) {
            warnings.add(key + " is missing, using the default");
            return def;
        }
        if (!(value instanceof List<?> list)) {
            warnings.add(key + " is not a list of lines, using the default");
            return def;
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            String problem = list.get(i) instanceof String line ? problem(key, line) : "is not text";
            if (problem != null) {
                warnings.add(key + " line " + (i + 1) + ": " + problem + ", using the default " + key);
                return def;
            }
            lines.add((String) list.get(i));
        }
        return lines;
    }

    /** Why {@code text} can't be used for {@code key}, or null if it's fine. */
    static String problem(String key, String text) {
        Set<String> allowed = new HashSet<>(placeholders(key));
        if (!key.equals("prefix")) {
            allowed.add("prefix");
        }
        return MiniMessageCheck.problem(text, allowed);
    }
}
