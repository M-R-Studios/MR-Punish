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
| `gui.yml` | Menu layouts (filled in as the menus are added). |
| `permissions.yml` | Staff ranks: weights, display names and what each rank grants. This is MR-Punish's own file, not the server's `permissions.yml`. |
| `scoreboard.yml` | The staff-mode scoreboard (filled in when staff mode is added). |

After editing, run `/mrpunish reload`. Each file reloads on its own: if one has a mistake, the reply names the file, the line and the problem, and the previous settings for that file stay in use while the others still reload. Small mistakes (an invalid value) fall back to the default for that value and are listed as warnings. A broken rank hierarchy in `permissions.yml` (two ranks with the same weight, a missing permission, an unknown or circular `inherits`) is never applied, and a `config.yml` without an enabled fallback reason for every type isn't either. If a file is broken when the server starts, its built-in defaults are used and the console says why.

## Staff ranks

Ranks are defined in `plugins/MR-Punish/permissions.yml`. Each rank has a permission node, a weight and a list of permissions it grants. Give staff the rank node in your permissions plugin, for example with LuckPerms:

```
/lp group mod permission set mrpunish.mod
```

Anyone with a rank node automatically gets everything the rank grants, plus everything from the ranks it inherits. A player's rank is the heaviest rank node they have. Staff can only act on players whose rank weight is lower than their own; players without a rank have weight 0, and console can do everything. Operators get `mrpunish.owner` by default.

## Commands

MR-Punish registers its commands through Paper's command system. Every command also works with the `mr-punish:` prefix (e.g. `/mr-punish:mrpunish`), which helps if another plugin uses the same name. At startup, the console warns about any MR-Punish label that another plugin or a `commands.yml` alias owns, and `/mrpunish version` lists them.

| Command | Permission | What it does |
|---|---|---|
| `/mrpunish` (aliases `/staffhelper`, `/mrp`) | `mrpunish.staff`, `mrpunish.reload` or `mrpunish.admin` | Help, listing only the commands you can use. |
| `/mrpunish reload` | `mrpunish.reload` | Reloads all five files and reports what failed and why. |
| `/mrpunish version` | `mrpunish.admin` | Plugin and server version, and any command labels owned by other plugins. |

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
