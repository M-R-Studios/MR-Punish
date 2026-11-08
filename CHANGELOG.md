# Changelog

## [Unreleased]

### Added
- Project skeleton.
- Five commented configuration files: `config.yml` (punishment reasons with escalation ladders, fallback reasons, command switches), `messages.yml`, `gui.yml`, `permissions.yml` (staff ranks) and `scoreboard.yml`, each upgraded automatically by version.
- Staff ranks from `permissions.yml`: weighted ranks whose permission node grants the rank's permissions and those of the ranks it inherits.
- `/mrpunish` (aliases `/staffhelper`, `/mrp`) with `help`, `reload` and `version`.
- `/mrpunish reload` reloads each file on its own, names the file, line and problem for anything that fails, and keeps the previous settings for that file.
- A startup warning, and a list in `/mrpunish version`, for MR-Punish command labels that another plugin or a `commands.yml` alias owns.
- Storage in SQLite (default) or MySQL/MariaDB, with automatic table creation and upgrades. A database from a newer MR-Punish is refused rather than changed.
- Degraded mode: if the database is unreachable at startup or lost while running, MR-Punish keeps running, alerts admins and retries every 30 seconds.
- Every player who joins is remembered with their name and their rank at their last visit.
- `/mrpunish version` shows the storage in use and whether it's connected.
- `/ban` and `/unban`: bans with automatic escalation per reason, notes, silent bans (`-s`), tab completion, and console support. A ban can't be shortened by issuing a shorter one; replaced and removed bans stay in history.
- Bans are checked at every login, with a ban screen showing the reason, who banned, the expiry and the ban ID. Online players are kicked when banned.
- `login.fail-policy` (CACHE, DENY or ALLOW) for logins the database can't answer in time; players let in unchecked are checked again once it's back, and admins are alerted.
- Public ban announcements (`announcements.*`), and staff notifications with a sound.
- `/mrpunish import vanilla [--clear]` copies vanilla's ban list into MR-Punish once; the console mentions vanilla bans at startup.
- Offline players' ranks are read from LuckPerms when it's installed.
- `/warn` and `/unwarn`: warnings with escalation counting, a chat message, title and sound for the warned player, and delivery on the next join for players who were offline.
- `/mute` and `/unmute`: a muted player can't chat, use blocked commands (`mute.blocked-commands`, including their aliases, namespaced forms and `commands.yml` aliases), write signs or sign books; they're told how long is left.
- IP punishments, off by default (`ip.enabled`): `/banip`, `/muteip`, `/unbanip`, `/unmuteip`. Only keyed hashes of IP addresses are stored (secret in `ip-secret.key`); no address is ever shown, logged or stored. Every account on the IP is affected, accounts seen on it recently are rank-checked, and staff see only an account count.
- `/mrpunish import vanilla` also imports vanilla IP bans when IP features are on.
- `/mrpunish version` shows whether IP features are on.
- `/punish <player>` (alias `/p`): a menu with the player's head and history summary, a reason menu per type showing each reason's offence number, escalation ladder and next punishment (and whether it replaces or is blocked by an active one), and a confirmation with the calculated duration, a silent toggle and a note. Warnings apply on click.
- Notes for the punish menus are typed in chat, or in a form for Bedrock players when Floodgate is installed (`bedrock.floodgate`).
- `gui.yml` now lays out every menu: titles, slots, items, lore, sounds and how buttons without permission look (`no-permission.mode`).

- `/history <player> [page]` (alias `/hist`) and a History button in `/punish`: every punishment of the player, 45 a page, coloured by status, with type and status filters and a details view. IP punishments on the player's recent IPs are included (never showing the IP). Console gets a text list.
- Removing a punishment from the history (right-click, or Remove in the details) with a typed reason; only active punishments, and only by staff ranked at least as high as whoever issued it. Removed entries stay in the history.
- The `/punish` head now counts IP punishments on the player's recent IPs too.

- `/report <player> [reason...]` with a reason menu (configured reasons and a typed "Other"), a cooldown, duplicate protection and a cap on open reports per player.
- `/reports` for staff: a list with filters, and a detail view to claim, unclaim, resolve or reject (with a note), add notes, and open the player's punish menu or history. Claims expire; staff are reminded of open reports; the reporter is told the outcome now or on their next join. Two staff acting on the same report can't both change it.

- Staff chat: `/sc`, the `/staffchat` toggle and the `#` prefix. Only staff and the console see it; it works for muted staff.
- `/stafflist`: online staff by rank; vanished staff only for those who can see them.
- `/vanish [player]`: hidden from players and lower-ranked staff, kept across relogs and restarts (`vanish.persist`), no join or quit messages, left out of the server list, no item pickup, pressure plates or mob targeting, an action-bar reminder, and the `vanished` metadata for other plugins. Nobody stays hidden when MR-Punish is disabled or when they lose the permission.

### Changed
- `/mute` and `/ban` (and `/muteip`, `/banip`) from a player now open the confirmation menu instead of applying straight away; without a reason, `/warn`, `/mute` and `/ban` open the reason menu. Console is unchanged.
