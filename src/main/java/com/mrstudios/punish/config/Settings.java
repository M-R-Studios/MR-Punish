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
 * @param display       date format, time zone and appeal link for messages and the ban screen
 * @param login         what to do when a login can't be checked
 * @param banKickOnline kick a banned player who is online
 * @param maxTextLength longest note or removal reason kept
 * @param punishedSound played to the staff member when a punishment is applied
 * @param failedSound   played to the staff member when an action is refused or fails
 * @param warnTitle     also show a warned player a title
 * @param mute          what a muted player can't do
 * @param warnedSound   played to the warned player
 * @param ip            IP punishments and tracking
 * @param input         chat prompts
 * @param floodgate     use Floodgate forms for Bedrock players (read at startup)
 */
public record Settings(TimeSpan historyPeriod, ReasonRegistry reasons, Map<String, CommandToggle> commands,
        Storage storage, String serverName, TimeSpan warningActiveFor, Map<PunishmentType, Boolean> announcements,
        boolean luckperms, SoundSetting notifySound, Display display, Login login, boolean banKickOnline,
        int maxTextLength, SoundSetting punishedSound, SoundSetting failedSound, boolean warnTitle, Mute mute,
        SoundSetting warnedSound, Ip ip, Input input, boolean floodgate, Reports reports,
        Vanish vanish, StaffChat staffChat, Freeze freeze) {

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

    /** @param zone the time zone dates are shown in */
    public record Display(java.time.format.DateTimeFormatter dateFormat, java.time.ZoneId zone, String appealLink) {

        public String date(long millis) {
            return dateFormat.format(java.time.Instant.ofEpochMilli(millis).atZone(zone));
        }
    }

    /**
     * {@code mute.*}.
     *
     * @param blockedCommands command names and aliases a muted player can't use, lower case
     */
    public record Mute(java.util.Set<String> blockedCommands, boolean blockSigns, boolean blockBooks,
            int notifyCooldownSeconds) {

        public Mute {
            blockedCommands = java.util.Set.copyOf(blockedCommands);
        }
    }

    /**
     * {@code ip.*}.
     *
     * @param enabled          read at startup (changing it needs a restart)
     * @param retentionDays    0 = keep the links forever
     */
    public record Ip(boolean enabled, int associationDays, int retentionDays, int ipv6Prefix,
            boolean purgeWhenDisabled) {
    }

    /** {@code input.*}: how long a chat prompt waits, and the word that cancels it (lower case). */
    public record Input(int chatTimeoutSeconds, String cancelWord) {
    }

    public enum FailPolicy {
        CACHE,
        DENY,
        ALLOW
    }

    public record Login(FailPolicy failPolicy, int timeoutMs) {
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

    /**
     * {@code reports.*} (DESIGN.md 13). Minutes of 0 turn the claim timeout or the reminder off.
     *
     * @param reasons the report reasons offered in the /report menu, in order
     */
    public record Reports(int cooldownSeconds, int maxOpenPerPlayer, boolean allowReportingStaff,
            int claimTimeoutMinutes, int reminderMinutes, java.util.List<ReportReason> reasons, SoundSetting sound) {

        public Reports {
            reasons = java.util.List.copyOf(reasons);
        }

        public java.util.Optional<ReportReason> reason(String key) {
            return reasons.stream().filter(r -> r.key().equals(key)).findFirst();
        }
    }

    /** One configured report reason. @param display MiniMessage */
    public record ReportReason(String key, String display, String icon) {
    }

    /** {@code vanish.*} (DESIGN.md 11.2). @param reminderSeconds 0 = no action-bar reminder */
    public record Vanish(boolean persist, boolean hideFromServerList, boolean blockPickup, boolean blockPhysical,
            boolean noMobTargeting, int reminderSeconds) {
    }

    /** {@code staff-chat.*} (DESIGN.md 11.5). @param prefix "" = no prefix routing */
    public record StaffChat(String prefix, SoundSetting sound) {
    }

    /** What happens when a frozen player logs out (DESIGN.md 11.3). */
    public enum FreezeQuit {
        NOTIFY, BAN
    }

    /**
     * {@code freeze.*} (DESIGN.md 11.3).
     *
     * @param allowedCommands command names a frozen player may still use, lower case
     * @param reminderSeconds 0 = the frozen message and title only once
     */
    public record Freeze(java.util.Set<String> allowedCommands, int reminderSeconds, FreezeQuit onQuit,
            SoundSetting sound) {

        public Freeze {
            allowedCommands = java.util.Set.copyOf(allowedCommands);
        }
    }
}
