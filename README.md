# FoBounty

[![Modrinth](https://img.shields.io/badge/Download-Modrinth-1bd96a?logo=modrinth&logoColor=white)](https://modrinth.com/plugin/fobounty) [![Versions](https://img.shields.io/badge/Supported_versions-Modrinth-1bd96a)](https://modrinth.com/plugin/fobounty/versions) [![Discord](https://img.shields.io/badge/Support-Discord-5865f2?logo=discord&logoColor=white)](https://discord.gg/kEF77QCfu7)

A configurable money bounty system with player GUIs and history.

*Developed by Carrotio.*

## Features

- Lets players place and inspect money bounties through commands and GUIs.
- Tracks bounty history and total earnings with searchable player views.
- Provides anti-abuse controls and configurable announcements.
- Supports Vault economy, optional FoTeams behavior, and Discord webhooks.
- Includes an in-game administrator editor and PlaceholderAPI values.

## Download and dependencies

Download the latest compatible version from [Modrinth](https://modrinth.com/plugin/fobounty). Check the [versions page](https://modrinth.com/plugin/fobounty/versions) for supported Minecraft versions and server platforms.

- **Required:** None
- **Optional integrations:** `PlaceholderAPI`, `Vault`, `FoTeams`

## Support

For help or issue reports, join the [Carrotio Discord server](https://discord.gg/kEF77QCfu7).

<details>
<summary><strong>Commands and permissions</strong></summary>

### Commands

- `/fobounty [add <player> <amount>|history <player>]` — Plugin command. Aliases: `/bounty`.
- `/fobountyadmin <editor|reload|version|remove> [player]` — Plugin command. Aliases: `/bountyadmin`.

### Permissions

- `fobounty.use` — Use FoBounty player commands and GUI. Default: `true`.
- `fobounty.admin` — Use FoBounty admin commands. Default: `op`.

</details>

<details>
<summary><strong>PlaceholderAPI</strong></summary>

Requires PlaceholderAPI.

- `%fobounty_total_earned%` — Raw amount earned from claimed bounties.
- `%fobounty_total_earned_formatted%` — Formatted amount earned from claimed bounties.
- `%fobounty_current_bounty%` — Raw bounty currently placed on the player.
- `%fobounty_current_bounty_formatted%` — Formatted bounty currently placed on the player.

</details>

<details>
<summary><strong>Configuration</strong></summary>

- `config.yml` — Main behavior and feature settings.
- `messages.yml` — Player-facing messages and interface text.
- `sounds.yml` — Configurable interaction sounds.
- `formats.yml` — Number suffixes, locale, time zone, and history date format.
- `webhook.yml` — Discord embed titles, labels, color, and money display.
- `dialogs/*.yml` — Native dialog layouts and text.
- `guis/*.yml` — GUI layouts, slots, labels, and lore.

In `guis/bounty-main.yml`, `states.bounty-template.skull-skin-url` may be set to an HTTP(S) skin URL to use one fixed player-head texture. If omitted, each bounty head uses the target player's skin. Other item text and sprites come from the same GUI file.

Main config areas: `settings`, `bounty`, `announcements`, `anti-abuse`, `discord-webhook`, `gui`, `history`, `database`.

</details>

<details>
<summary><strong>Developer API</strong></summary>

This plugin does not expose a public developer API.

</details>
