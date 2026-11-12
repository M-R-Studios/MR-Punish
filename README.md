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

## History

`/history <player> [page]` (alias `/hist`, or the History button in `/punish`) shows every punishment of the player, newest first, 45 a page: active ones red, expired grey, removed light blue, replaced yellow (and black for IP punishments while IP features are off). IP punishments on any IP the player used recently are included and marked "(IP)", with how many accounts they apply to; no IP is ever shown. Filters cycle through the types and the statuses (active, expired, removed, replaced).

Left-click an entry for its details; with `mrpunish.remove` (and `mrpunish.ip` for IP punishments), right-click an active entry or use Remove in the details to remove it. You're asked for a reason in chat (or a form on Bedrock with Floodgate); `-` removes it without one, `cancel` goes back. Only active punishments can be removed, and only if your rank is at least that of whoever issued it. Nothing is ever deleted: removed entries stay in the history with who removed them, when and why. Console gets a text list, 10 a page.

## Reports

Any player can `/report <player> [reason...]`. Without a reason a menu offers the reasons from `config.yml` (`reports.reasons`) and "Other", which asks for a typed reason in chat (or a form on Bedrock with Floodgate). A player waits `reports.cooldown-seconds` between reports (`mrpunish.report.nocooldown` skips it), can't report the same player again while their earlier report is still open, and can have at most `reports.max-open-per-player` open reports. With `reports.allow-reporting-staff: false`, staff can't be reported.

Staff with `mrpunish.reports` are told about each new report (click to open `/reports`) and reminded of open reports every `reports.reminder-minutes`. `/reports [open|claimed|mine|closed|all]` lists them, oldest open first. A report's details let staff claim it (others then can't decide it, except admins, which is logged), resolve or reject it with an optional note, add internal notes, and open the player's punish menu or history. A claim is released after `reports.claim-timeout-minutes` without a decision. The reporter is told the outcome at once, or on their next join. If two staff act on the same report at once, only the first change counts; the other is told it changed and sees the latest version. Reports and notes are never deleted.

## Staff chat and /stafflist

`/sc <message>` sends one message to staff chat (`mrpunish.staffchat`, console too); `/staffchat` sends all your chat there until you run it again or leave; a chat line starting with `staff-chat.prefix` (`#`) goes there too, for staff only (other players typing `#` chat normally). Staff chat reaches only online players with `mrpunish.staffchat` and the console, and typed text is shown as text. It works for a muted staff member. If you lose the permission while staff chat is toggled on, your next line is refused instead of going to public chat.

`/stafflist` (everyone) lists the online staff by rank. Vanished staff appear only to those who can see them, marked [vanished].

## Vanish

`/vanish` (`mrpunish.vanish`) hides you from players without `mrpunish.vanish.see` and from staff of a lower rank; staff of your rank or higher still see you. `/vanish <player>` needs `mrpunish.vanish.others` and a higher rank, and only works on players who could make themselves visible again. While vanished there's no join or quit message (staff who can see you are told instead), you're left out of the server list and its player count, you can't pick up items or arrows, don't trigger pressure plates or tripwires, mobs ignore you and sleeping skips you; the action bar reminds you every `vanish.reminder-seconds`. MR-Punish sets the `vanished` metadata that many plugins read, and with EssentialsX installed it also marks vanished players as hidden in Essentials, so its `/list` and AFK messages leave them out. Use one vanish system, not both: MR-Punish's `/vanish` or Essentials'.

With `vanish.persist` you stay vanished across a relog or restart. Nobody can get stuck invisible: hiding isn't saved by the server, losing `mrpunish.vanish` makes you visible again (at once, or at your next join), and when MR-Punish is disabled everyone is shown again (enabling it vanishes those players again).

## Staff mode

`/staffmode` (alias `/sm`, `mrpunish.staffmode`) saves your whole state (inventory, armour, off-hand, game mode, flight, location, health, food, experience, effects, fire, air), clears your inventory and gives you the staff tools: a teleport compass (pick an online player), a random-player teleport, an inspect book (right-click a player for their inventory, sneak for their ender chest), a freeze wand, a punish axe, a vanish toggle, a reports book and a nether star that opens the `/staff` menu. You only get the tools you have the permission for. You fly, can't be hurt, don't get hungry, aren't targeted by mobs and are vanished (`staff-mode.vanish`); the staff scoreboard (`scoreboard.yml`) is shown. Tools can't be dropped, moved or placed; containers open but nothing can be taken (`staff-mode.allow-containers`), and building and pickup are off unless `allow-build` / `allow-pickup`.

`/staffmode` again gives everything back exactly as it was, and so do logging out, a kick, `/stop` and disabling the plugin. If you die in staff mode nothing drops and you get everything back on respawn. The saved state is kept in your own player data, so after a crash your next join restores it ("You left staff mode unexpectedly last time"). Losing `mrpunish.staffmode` ends it within a minute. `/mrpunish forceexit <player>` (admins, with a higher rank) ends someone's staff mode and vanish. `/stafflist` shows "[staff mode]" to staff only. In a report's details, the teleport buttons (to the report location and to the reported player) work while you're in staff mode or vanished.

## The staff menu and staff log

`/staff` (`mrpunish.staff`) opens the staff menu: your own head (rank, staff mode, vanish and staff chat), Player Reports (with the open and claimed counts), Online Staff, Staff Chat, Staff Mode and Vanish toggles, Freeze Tools, Server Information, Inventory and Ender Chest Inspection, Punishment History, Recent Staff Actions and Punish a player. Each button needs the same permission as its command and is checked again when you click; buttons you can't use are greyed or hidden (`gui.yml` `no-permission.mode`). In staff mode, the nether star tool opens it.

- The player pickers list the online players you can see (not yourself). **Search by name** asks for any name in chat (or a form on Bedrock), so history and punishments work for offline players too.
- **Online Staff** shows the staff you can see with their rank, staff mode and vanish. Admins (`mrpunish.admin`) click a head for that member's profile in chat, or shift-click to end their staff mode and vanish (with a confirmation).
- **Freeze Tools** lists everyone frozen, online and offline, with who froze them and when; click to unfreeze (with a confirmation). "Freeze a player" opens a picker.
- **Server Information** shows TPS, MSPT, memory, uptime, players, staff online, vanished players, open reports, bans and mutes in force, storage and the plugin version. Click to refresh.

Every staff action is logged: punishments, refusals, removals, report activity, inspections, freezes, staff mode, vanish, reloads, imports and force exits. `/stafflog [staff] [page]` and Recent Staff Actions (`mrpunish.logs`) show the log, newest first, coloured by kind, with a filter by kind and, when you name a staff member, by that member. Click a punishment or removal to open it in the player's history, or a report action to open the report. The console gets a text list. Opening someone's history is logged too with `logs.log-history-views: true`.

The log is also written to `plugins/MR-Punish/logs/staff-YYYY-MM-DD.log` (`logs.file.enabled`), and files older than `logs.file.keep-days` are deleted. `logs.retention-days` deletes old log entries from the database (punishments and reports are never deleted). Nothing that looks like an IP address is ever written or shown.

`/mrpunish staff <player>` (`mrpunish.admin`) shows a staff member's rank, the punishments they issued (last 30 days and in total, by type), the reports they handled and their last 10 actions, with a clickable "end staff mode and vanish" when they're in staff mode or vanished.

The punish menu's head also shows how many reports there are against the player and how many are still open.

## Freeze

`/freeze <player>` (`mrpunish.freeze`, and a higher rank than the player) freezes an online player: they can look around and chat (unless muted), but can't move, teleport by pearl, chorus fruit or portal, ride, break or place blocks, use items or buckets, open containers, click in their inventory, drop, pick up or swap items, eat, shoot, glide, or hurt or be hurt. Commands are blocked except `freeze.allowed-commands` (and staff chat for staff). Staff can still teleport them with commands. A player frozen in mid-air hovers, so they aren't kicked for flying. They get a message and a title, repeated every `freeze.reminder-seconds`.

The freeze is saved in the database, so it survives logout, restart and moving to another server on the same database. `/unfreeze <player>` releases them, online or offline, and needs only `mrpunish.freeze`. When a frozen player logs out, staff are told; with `freeze.on-quit: BAN` they're also banned for the `freeze-logout` reason, issued by whoever froze them. When MR-Punish is disabled the freeze effect is lifted (flight restored) and comes back when it's enabled again.

## Inventory and ender chest

`/invsee <player>` (`mrpunish.invsee`) and `/endersee <player>` (`mrpunish.endersee`) show an online player's inventory (with armour, off-hand, health, food and level) or ender chest. They update live but are view-only copies: nothing can be taken, moved or duplicated. The player isn't told; every inspection is logged.

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

To give a label back to vanilla or another plugin, turn it off in `config.yml` (`commands.ban.enabled: false`, then restart): `/ban` is then the other command, and MR-Punish's stays available as `/mr-punish:ban`. **With EssentialsX**, also add an alias in the server's `commands.yml` (for example `ban: ["essentials:ban $1-"]`), and the same the other way round: EssentialsX keeps labels like `/v` in its own name and passes them on to MR-Punish only by default, which the startup warning points out; `v: ["mr-punish:v $1-"]` makes `/v` MR-Punish's for sure (the warning then goes away): by default EssentialsX hands its own command to any other plugin with the same name, which includes `/mr-punish:ban`, so without the alias `/ban` can still run MR-Punish's for a while after startup. To make a label that another plugin took point at MR-Punish instead, add an alias to the server's `commands.yml`, for example `ban: ["mr-punish:ban $1-"]`.

| Command | Permission | What it does |
|---|---|---|
| `/mrpunish` (aliases `/staffhelper`, `/mrp`) | `mrpunish.staff`, `mrpunish.reload`, `mrpunish.admin` or `mrpunish.import` | Help, listing only the commands you can use. |
| `/mrpunish reload` | `mrpunish.reload` | Reloads all five files and reports what failed and why. |
| `/mrpunish version` | `mrpunish.admin` | Plugin and server version, storage and whether it's connected, and any command labels owned by other plugins. |
| `/mrpunish import vanilla [--clear]` | `mrpunish.import` | Imports vanilla's player bans; `--clear` removes the imported entries from the vanilla list afterwards. |
| `/punish <player>` (alias `/p`) | `mrpunish.punish` | Opens the punish menu. Players only. |
| `/history <player> [page]` (alias `/hist`) | `mrpunish.history` | The history menu; console gets a text list. Removing needs `mrpunish.remove`. |
| `/report <player> [reason...]` | `mrpunish.report` (everyone) | Reports a player; without a reason, opens the reason menu. Players only. |
| `/reports [open\|claimed\|mine\|closed\|all]` | `mrpunish.reports` | The reports menu. Players only. |
| `/sc <message>` | `mrpunish.staffchat` | One message to staff chat. Console can use it. |
| `/staffchat` | `mrpunish.staffchat` | Sends all your chat to staff chat, or back. |
| `/stafflist` | `mrpunish.stafflist` (everyone) | Online staff with rank. |
| `/vanish [player]` (alias `/v`) | `mrpunish.vanish`; another player: `mrpunish.vanish.others` and a higher rank | Toggles vanish. |
| `/staffmode` (alias `/sm`) | `mrpunish.staffmode` | Enters or leaves staff mode. |
| `/mrpunish forceexit <player>` | `mrpunish.admin` and a higher rank | Ends someone's staff mode and vanish. |
| `/staff` | `mrpunish.staff` | The staff menu. Players only. |
| `/stafflog [staff] [page]` | `mrpunish.logs` | The staff action log; console gets a text list. |
| `/mrpunish staff <player>` | `mrpunish.admin` | A staff member's profile. |
| `/freeze <player>` | `mrpunish.freeze` and a higher rank | Freezes an online player. |
| `/unfreeze <player>` | `mrpunish.freeze` | Unfreezes, also an offline player. |
| `/invsee <player>` | `mrpunish.invsee` | View-only live copy of an online player's inventory. |
| `/endersee <player>` | `mrpunish.endersee` | View-only live copy of an online player's ender chest. |
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
| `mrpunish.staff` | helper | false | Base staff permission: `/mrpunish` help, the `/staff` menu, staff notifications. |
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
