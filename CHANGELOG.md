# Changelog

## [Unreleased]

### Added
- Project skeleton.
- Five commented configuration files: `config.yml` (punishment reasons with escalation ladders, fallback reasons, command switches), `messages.yml`, `gui.yml`, `permissions.yml` (staff ranks) and `scoreboard.yml`, each upgraded automatically by version.
- Staff ranks from `permissions.yml`: weighted ranks whose permission node grants the rank's permissions and those of the ranks it inherits.
- `/mrpunish` (aliases `/staffhelper`, `/mrp`) with `help`, `reload` and `version`.
- `/mrpunish reload` reloads each file on its own, names the file, line and problem for anything that fails, and keeps the previous settings for that file.
- A startup warning, and a list in `/mrpunish version`, for MR-Punish command labels that another plugin or a `commands.yml` alias owns.
