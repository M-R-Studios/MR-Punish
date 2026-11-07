package com.mrstudios.punish.config;

import com.mrstudios.punish.punish.PunishmentType;
import com.mrstudios.punish.punish.ReasonRegistry;
import java.util.List;
import java.util.Map;
import org.bukkit.NamespacedKey;

/**
 * Everything from {@code config.yml}, validated. Immutable: a reload builds a new one.
 *
 * @param historyPeriod how far back earlier offences count for escalation, unless a reason sets its own
 * @param reasons       every punishment reason and the fallback for each type
 * @param commands      the on/off switch and extra labels of each command that has one, by name
 * @param storage       where punishments are stored (read once at startup; changing it needs a restart)
 * @param serverName    this server's name in punishments and logs ({@code default} when empty)
 * @param warningActiveFor how long a warning shows as active
 * @param announcements which types are announced to every player
 * @param luckperms     use the LuckPerms hook for offline ranks (read at startup)
 * @param notifySound   played to staff with each notification
 */
public record Settings(TimeSpan historyPeriod, ReasonRegistry reasons, Map<String, CommandToggle> commands,
        Storage storage, String serverName, TimeSpan warningActiveFor, Map<PunishmentType, Boolean> announcements,
        boolean luckperms, SoundSetting notifySound) {

    public Settings {
        commands = Map.copyOf(commands);
        announcements = Map.copyOf(announcements);
    }

    /** The name stored with punishments: {@code server-name}, or {@code default}. */
    public String effectiveServerName() {
        return serverName.isEmpty() ? "default" : serverName;
    }

    public boolean announces(PunishmentType type) {
        return announcements.getOrDefault(type, false);
    }

    /** A sound from {@code sounds.*}. */
    public record SoundSetting(boolean enabled, NamespacedKey sound, float volume, float pitch) {
    }

    /**
     * {@code commands.<name>} in {@code config.yml}.
     *
     * @param enabled whether the command gets its plain label (and aliases), or only the namespaced one
     * @param aliases extra labels, lower case
     */
    public record CommandToggle(boolean enabled, List<String> aliases) {

        public static final CommandToggle DEFAULT = new CommandToggle(true, List.of());

        public CommandToggle {
            aliases = List.copyOf(aliases);
        }
    }

    public enum StorageType {
        SQLITE,
        MYSQL
    }

    /** @param sqliteFile a file name inside the plugin folder */
    public record Storage(StorageType type, String sqliteFile, Mysql mysql) {
    }

    /**
     * @param tablePrefix put in front of every table name
     * @param properties  extra JDBC connection properties
     */
    public record Mysql(String host, int port, String database, String username, String password,
            String tablePrefix, int poolSize, Map<String, String> properties) {

        public Mysql {
            properties = Map.copyOf(properties);
        }

        @Override
        public String toString() {
            // Keeps the password out of logs and error reports.
            return "Mysql[host=" + host + ", port=" + port + ", database=" + database + ", username=" + username
                    + ", tablePrefix=" + tablePrefix + ", poolSize=" + poolSize + ", properties=" + properties + "]";
        }
    }

    /** The toggle for {@code name}; commands that aren't configured are on, with no extra labels. */
    public CommandToggle command(String name) {
        return commands.getOrDefault(name, CommandToggle.DEFAULT);
    }
}
