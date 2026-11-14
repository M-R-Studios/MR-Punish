# Changelog

## [Unreleased]

## [1.0] - 2026-11-14

The first release of MR-Punish: staff management and punishments for Paper 1.21.11.

### Punishments
- `/warn`, `/mute`, `/ban`, and with IP features on `/muteip` and `/banip`, each with reasons from `config.yml` and automatic escalation: every repeat offence for the same reason moves one step up its ladder (for example 7d, then 30d, then permanent) within a configurable history period.
- `-s` for silent punishments, notes for staff, and public announcements you can turn on per type.
- A new punishment never ends sooner than an active one; a longer one replaces it, and the replaced one stays in history.
- `/unwarn`, `/unmute`, `/unban`, `/unmuteip` and `/unbanip`, each with a reason that's recorded.
- Bans are checked at every login, with a full ban screen (reason, staff, expiry, ID). If the database can't answer, `login.fail-policy` decides (an in-memory copy of active bans by default), and admins are told.
- Mutes block chat, private messages and any command in `mute.blocked-commands` by every name it's known by (aliases, namespaced forms, `commands.yml` aliases), plus signs and books. The player is told why and for how long.
- Warnings show a message and a title; players who were offline are told on their next join.
- IP punishments (off by default) store only keyed hashes of IP addresses, never the address, and never show one to anyone. They apply to every account on the IP and are refused if any associated account outranks you.
- `/mrpunish import vanilla [--clear]` imports vanilla's ban list once.

### Menus
- `/punish <player>`: the player's summary and a button per type. Each reason shows the offence number, the next duration and the whole ladder, and whether it replaces or is blocked by an active punishment. Mutes and bans go through a confirmation with the calculated end, a silent toggle and a note.
- `/history <player>`: every punishment, newest first, coloured by status, with filters and details. Removing one asks for a reason and needs a rank at least as high as the issuer's. Nothing is ever deleted.
- `/report` with a reason menu, and `/reports` for staff: claim, resolve or reject with a note, internal notes, teleports, and quick access to the punish menu and history. The reporter is told the outcome.
- `/staff`: the staff menu, with reports, online staff, staff chat, staff mode and vanish toggles, freeze tools, server information, inspection, history and punish shortcuts with a name search, and recent staff actions.
- Every menu is laid out in `gui.yml`. Nothing can be taken out of, moved in or put into any menu, and permissions are checked again at every click.
- Bedrock players (Geyser + Floodgate) get forms wherever text is typed, and menus open reliably: switching between menus no longer flashes a menu open and shut.

### Staff tools
- Staff mode (`/staffmode`): staff tools (none of them opens its own screen on Bedrock; the reports tool is paper, and an existing `gui.yml` with the old book and quill is updated automatically), protection and a staff scoreboard. Your inventory and state come back exactly as they were on exit, logout, kick, death, `/stop`, plugin disable, and even after a crash.
- `/vanish`: hidden from players and lower-ranked staff (including join, quit, advancement and death messages), kept across relogs and restarts, and compatible with EssentialsX's `/list` and AFK messages.
- `/freeze` and `/unfreeze`: a frozen player can't move, teleport, interact or fight. The freeze survives logout, restart and server switches, and a frozen player who logs out can be banned automatically.
- `/invsee` and `/endersee`: live, view-only copies.
- Staff chat (`/sc`, `/staffchat`, the `#` prefix) and `/stafflist`.

### Staff ranks and logs
- Weighted staff ranks in `permissions.yml`. Staff can never act on themselves or anyone of an equal or higher rank; exempt players only by console. Offline players' ranks are read from LuckPerms when it's installed.
- Every staff action is logged: punishments, refusals, removals, reports, inspections, freezes, staff mode, vanish, reloads, imports and force exits. `/stafflog` shows the log, with daily log files in `logs/`.
- `/mrpunish staff <player>`: a staff member's profile. `/mrpunish forceexit <player>` ends someone's staff mode and vanish.

### Storage and networks
- SQLite (default) or MySQL/MariaDB, with automatic table creation and upgrades.
- If the database goes away, MR-Punish keeps running, saves nothing, alerts admins and reconnects on its own.
- Servers sharing one MySQL database share everything. A player banned on one server is kicked from the others; mutes, warnings, freezes and report notifications reach every server within a few seconds.

### Configuration
- Five commented files: `config.yml`, `messages.yml` (every message, MiniMessage), `gui.yml`, `permissions.yml` and `scoreboard.yml`.
- `/mrpunish reload` reloads each file on its own and says exactly what failed and where, keeping the old settings for that file.
- Every command can be turned off or given extra labels. The console warns when another plugin owns one of MR-Punish's labels, and every command also works as `/mr-punish:<name>`.
