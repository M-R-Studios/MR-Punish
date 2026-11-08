# MR-Punish

Staff management and punishments for Paper servers: escalating punishments, reports, staff tools and full history.

By **MR Studios**. Work in progress: this is the configuration foundation. Punishments, menus and staff tools are being added step by step, and this README grows with them.

Requires Paper 1.21.11 and Java 21.

## Files

MR-Punish creates five files in `plugins/MR-Punish/`, each with comments explaining every option:

| File | What's in it |
|---|---|
| `config.yml` | Punishment reasons and their escalation, the fallback reason for each type, command switches. |
| `messages.yml` | Every message (MiniMessage). Set a message to `""` to turn it off. |
| `gui.yml` | Every menu: titles, slots, items, names, lore and sounds, and how buttons you can't use look (`no-permission.mode`: `GREY` or `HIDE`). |
| `permissions.yml` | Staff ranks: weights, display names and what each rank grants. This is MR-Punish's own file, not the server's `permissions.yml`. |
| `scoreboard.yml` | The staff-mode scoreboard (filled in when staff mode is added). |

After editing, run `/mrpunish reload`. Each file reloads on its own: if one has a mistake, the reply names the file, the line and the problem, and the previous settings for that file stay in use while the others still reload. Small mistakes (an invalid value) fall back to the default for that value and are listed as warnings. A broken rank hierarchy in `permissions.yml` (two ranks with the same weight, a missing permission, an unknown or circular `inherits`) is never applied, and a `config.yml` without an enabled fallback reason for every type isn't either. If a file is broken when the server starts, its built-in defaults are used and the console says why.

## Storage

MR-Punish stores everything in a database: SQLite by default (`plugins/MR-Punish/punishments.db`, nothing to set up), or MySQL/MariaDB (`storage.type: MYSQL` in `config.yml`). Several servers can share one MySQL database; SQLite is for a single server and must never sit on a network share. Changing the storage type needs a restart, and data isn't copied between the two.

The tables are created on first start and upgraded automatically when MR-Punish updates (SQLite gets a backup copy first; for MySQL, the console asks you to make one). A database made by a newer MR-Punish is never touched by an older one.

If the database can't be reached at startup or goes away while the server runs, MR-Punish keeps running in a degraded mode. It doesn't save anything, tells online admins (`mrpunish.admin`) and every admin who joins, and tries again every 30 seconds until the database is back. `/mrpunish version` shows whether storage is connected and why not.

## Warnings and mutes

`/warn` and `/mute` work like `/ban`: `<player> [-s] <reason> [note...]`, with reasons and escalation from `config.yml`. A warned player gets a chat message and a title (`warnings.title`); if they're offline, they're told on their next join. Warnings stack and never replace each other; they show as active for `warnings.active-for`.

A muted player can't chat, can't use the commands in `mute.blocked-commands`, and (by default) can't write on signs or sign books. A command is blocked by every name it's known by: its aliases, namespaced forms such as `/minecraft:tell` or `/essentials:msg`, other plugins' aliases for the same command, and aliases in the server's `commands.yml` that run it. The muted player is told the reason and how long is left, at most every `mute.notify-cooldown-seconds`. A mute that runs out simply stops blocking. `/unmute` removes every active mute; `/unwarn <player> [id]` removes one warning (the most recent if no id is given).

## IP punishments

IP features are **off** by default (`ip.enabled: false`). While they're off, MR-Punish stores nothing about IP addresses, and the IP commands say they're disabled. Turn them on in `config.yml` and restart.

When they're on, MR-Punish never stores or shows an IP address. It keeps only a keyed hash of each address (HMAC-SHA256), using a random secret in `plugins/MR-Punish/ip-secret.key` that's created the first time. **Back that file up**: if it's lost, existing IP punishments stop matching. On a network that shares one MySQL database, copy the same file to every server. If the key doesn't match the one the database was used with, IP features stay off and the console says why. IPv6 addresses are grouped by `ip.ipv6-prefix` (a /64 by default).

`/banip` and `/muteip` punish the player's current IP (or the last one they were seen with). Every account on that IP is affected: online accounts are kicked or muted at once, and any account joining from it is refused or muted. Accounts seen on the same IP within `ip.association-days` count as associated: if any of them is exempt or has a rank equal to or higher than yours, the action is refused, and staff only ever see a count of accounts, never names or addresses. `/unbanip` and `/unmuteip` remove the IP punishments on every IP the player used within `ip.association-days`. A player's IP links are deleted after `ip.retention-days` without a login from that IP, and all of them are deleted at startup if IP features are turned off.

## The punish menu

`/punish <player>` (alias `/p`) opens a menu with the player's head (UUID, online or last seen, rank, active and total punishments, the last three) and a button per punishment type you're allowed to use. Each type opens its reasons, and every reason shows what it would do right now: the offence number, how many earlier offences count, the next punishment and the escalation ladder with the step that would be used, plus whether it replaces an active punishment or is blocked by one that ends later. A warning applies on click; a mute, ban, IP mute or IP ban opens a confirmation with the calculated duration and end, a silent toggle and an optional note. Notes are typed in chat (type `cancel` to go back; `input.cancel-word`, `input.chat-timeout-seconds`); Bedrock players get a Floodgate form when Floodgate is installed (`bedrock.floodgate`). Typed notes are never shown in chat, even for a muted staff member.

From a player, `/mute` and `/ban` (and `/muteip`, `/banip`) with a reason open the confirmation directly (`-s` pre-sets silent), and `/warn`, `/mute` or `/ban` without a reason open the reason menu. `/warn` with a reason and everything from console apply straight away.

Permissions and rank are checked again on every click. If someone else punishes the same player while you're deciding, your confirmation is refused with the new preview, so nothing is applied twice. Nothing can be taken out of, moved in or put into any MR-Punish menu.

## Bans

`/ban <player> [-s] <reason> [note...]` bans with the reason's next escalation step from `config.yml`: the first word picks a ban reason by its key or one of its aliases, and the rest is a staff note. If the first word isn't a ban reason, the "other" ban reason is used and the whole text becomes the note. `-s` (anywhere after the name) skips the public announcement; staff are still notified. A new ban can never end sooner than an active one (remove it first to shorten it), and history is never deleted: replaced and removed bans stay visible with who, when and why. `/unban` removes every active ban of the player.

Bans are checked against the database at every login. If the database can't answer in time, `login.fail-policy` decides: `CACHE` (default) uses an in-memory copy of active bans, `DENY` refuses everyone who can't be checked, and `ALLOW` lets everyone in. Players let in without a check are checked again as soon as the database answers, and kicked if they're banned. Admins are told whenever logins can't be checked.

**Vanilla bans.** MR-Punish doesn't read or change vanilla's ban list, and vanilla bans keep working by themselves (a player in vanilla's list is still refused by vanilla). Vanilla's own command stays available as `/minecraft:ban`, and `/pardon` only affects vanilla bans. `/mrpunish import vanilla` copies vanilla's player bans (and, with IP features on, its IP bans, stored only as hashes) into MR-Punish once (running it again imports nothing new); `--clear` then removes the imported entries from the vanilla list. At startup the console says if vanilla bans exist.

## Staff ranks

Ranks are defined in `plugins/MR-Punish/permissions.yml`. Each rank has a permission node, a weight and a list of permissions it grants. Give staff the rank node in your permissions plugin, for example with LuckPerms:

```
/lp group mod permission set mrpunish.mod
```

Anyone with a rank node automatically gets everything the rank grants, plus everything from the ranks it inherits. A player's rank is the heaviest rank node they have. With [LuckPerms](https://luckperms.net) installed (recommended), the rank of an offline player is read from LuckPerms, so promoting someone while they're offline protects them at once; without it, MR-Punish uses the rank they had when they were last online. Either way, the higher of the two is used. MR-Punish remembers each player's rank from their last visit, for when they're offline. Staff can only act on players whose rank weight is lower than their own; players without a rank have weight 0, and console can do everything. Operators get `mrpunish.owner` by default.

## Commands

MR-Punish registers its commands through Paper's command system. Every command also works with the `mr-punish:` prefix (e.g. `/mr-punish:ban`), which helps if another plugin uses the same name. At startup, the console warns about any MR-Punish label that another plugin or a `commands.yml` alias owns, and `/mrpunish version` lists them.

To give a label back to vanilla or another plugin, turn it off in `config.yml` (`commands.ban.enabled: false`, then restart): `/ban` is then the other command, and MR-Punish's stays available as `/mr-punish:ban`. To make a label that another plugin took point at MR-Punish instead, add an alias to the server's `commands.yml`, for example `ban: ["mr-punish:ban $1-"]`.

| Command | Permission | What it does |
|---|---|---|
| `/mrpunish` (aliases `/staffhelper`, `/mrp`) | `mrpunish.staff`, `mrpunish.reload`, `mrpunish.admin` or `mrpunish.import` | Help, listing only the commands you can use. |
| `/mrpunish reload` | `mrpunish.reload` | Reloads all five files and reports what failed and why. |
| `/mrpunish version` | `mrpunish.admin` | Plugin and server version, storage and whether it's connected, and any command labels owned by other plugins. |
| `/mrpunish import vanilla [--clear]` | `mrpunish.import` | Imports vanilla's player bans; `--clear` removes the imported entries from the vanilla list afterwards. |
| `/punish <player>` (alias `/p`) | `mrpunish.punish` | Opens the punish menu. Players only. |
| `/ban <player> [-s] <reason> [note...]` | `mrpunish.ban` | Bans with the reason's next escalation step. From a player: opens the confirmation (no reason: the reason menu). Works from console. |
| `/unban <player> [reason...]` | `mrpunish.remove` | Removes every active ban of the player, recording the reason. |
| `/mute <player> [-s] <reason> [note...]` | `mrpunish.mute` | Mutes with the reason's next escalation step. From a player: opens the confirmation (no reason: the reason menu). |
| `/unmute <player> [reason...]` | `mrpunish.remove` | Removes every active mute of the player. |
| `/warn <player> [-s] <reason> [note...]` | `mrpunish.warn` | Warns; an offline player is told on their next join. From a player without a reason: opens the reason menu. |
| `/unwarn <player> [id] [reason...]` | `mrpunish.remove` | Removes warning `id` (a number, or `#12`), or the most recent active warning. |
| `/banip <player> [-s] <reason> [note...]` (alias `/ipban`) | `mrpunish.ban` + `mrpunish.ip` | Bans the player's IP (IP features must be on). |
| `/muteip <player> [-s] <reason> [note...]` (alias `/ipmute`) | `mrpunish.mute` + `mrpunish.ip` | Mutes the player's IP. |
| `/unbanip <player> [reason...]` (alias `/ipunban`) | `mrpunish.remove` + `mrpunish.ip` | Removes the IP bans on every IP the player used recently. |
| `/unmuteip <player> [reason...]` (alias `/ipunmute`) | `mrpunish.remove` + `mrpunish.ip` | Removes the IP mutes on every IP the player used recently. |

## Permissions

Rank nodes grant the feature permissions through `permissions.yml`, so you can change what each rank can do. The default rank column shows which shipped rank grants each one. The permissions for features that aren't built yet are already declared so ranks can be set up now.

| Permission | Default rank | Plugin default | Meaning |
|---|---|---|---|
| `mrpunish.helper` | (rank node) | false | Helper rank. |
| `mrpunish.mod` | (rank node) | false | Moderator rank. |
| `mrpunish.admin` | (rank node) | false | Administrator rank, and admin tools (`/mrpunish version`). |
| `mrpunish.owner` | (rank node) | op | Owner rank. |
| `mrpunish.exempt` | nobody | false | Can only be punished by console. |
| `mrpunish.staff` | helper | false | Base staff permission: `/mrpunish` help, staff notifications. |
| `mrpunish.punish` | helper | false | The `/punish` menu. |
| `mrpunish.warn` | helper | false | Warn players. |
| `mrpunish.mute` | helper | false | Mute players. |
| `mrpunish.ban` | mod | false | Ban players. |
| `mrpunish.ip` | admin | false | IP mutes and IP bans. |
| `mrpunish.remove` | admin | false | Remove punishments. |
| `mrpunish.history` | helper | false | View punishment history. |
| `mrpunish.notify` | helper | false | Receive punishment notifications. |
| `mrpunish.staffchat` | helper | false | Staff chat. |
| `mrpunish.staffmode` | helper | false | Staff mode. |
| `mrpunish.vanish` | helper | false | Vanish yourself. |
| `mrpunish.vanish.others` | admin | false | Vanish or unvanish others. |
| `mrpunish.vanish.see` | helper | false | See vanished players of your rank or lower. |
| `mrpunish.freeze` | mod | false | Freeze and unfreeze players. |
| `mrpunish.invsee` | mod | false | View inventories. |
| `mrpunish.endersee` | mod | false | View ender chests. |
| `mrpunish.reports` | helper | false | View and handle reports. |
| `mrpunish.report` | everyone | true | Report players. |
| `mrpunish.report.nocooldown` | helper | false | Ignore the report cooldown. |
| `mrpunish.stafflist` | everyone | true | `/stafflist`. |
| `mrpunish.logs` | admin | false | The staff action log. |
| `mrpunish.reload` | admin | false | `/mrpunish reload`. |
| `mrpunish.import` | owner | false | Import the vanilla ban lists. |

A reason in `config.yml` can also set its own `permission:`, which is then needed to use that reason.

## Reporting bugs

Open an issue using the bug report form: [new issue](https://github.com/M-R-Studios/MR-Punish/issues/new/choose). For questions and setup help, join the [MR Studios Discord](https://discord.gg/hTsrHsGcTg).

## Building

```bash
mvn -B clean package
```

The jar is written to `target/`.
