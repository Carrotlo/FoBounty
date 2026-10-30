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

FoBounty 2.2 exposes `me.foesio.foBounty.api.BountyApi` through Bukkit's `ServicesManager`. It also fires Bukkit events in `me.foesio.foBounty.api.event`. These types belong to FoBounty and do not expose shaded FoPluginCore classes.

### Consumer setup

Compile against the matching FoBounty jar with Maven `provided` scope or Gradle `compileOnly`. Do not shade FoBounty into the consumer. This jar is a local artifact, not a claim that these coordinates are published in a repository. For a local Maven install, run from the FoBounty project folder:

```sh
mvn install:install-file -Dfile=target/FoBounty-2.2.jar -DgroupId=me.foesio -DartifactId=FoBounty -Dversion=2.2 -Dpackaging=jar
```

Then add a consumer dependency:

```xml
<dependency>
    <groupId>me.foesio</groupId>
    <artifactId>FoBounty</artifactId>
    <version>2.2</version>
    <scope>provided</scope>
</dependency>
```

For a `plugin.yml` consumer, add `softdepend: [FoBounty]` when the integration is optional, or `depend: [FoBounty]` when required. Optional consumers must check that FoBounty is enabled and load direct API references from a separate integration class only after that check. A `paper-plugin.yml` consumer also needs a `dependencies.server` entry that loads FoBounty first and allows its classes on the consumer classpath (`join-classpath: true`).

```java
Plugin provider = Bukkit.getPluginManager().getPlugin("FoBounty");
if (provider != null && provider.isEnabled()) {
    BountyApi api = Bukkit.getServicesManager().load(BountyApi.class);
    if (api != null) {
        api.bounty(targetId).ifPresent(bounty ->
                getLogger().info("Current bounty: " + bounty.total()));
    }
}
```

Register Bukkit event listeners normally. For example, `BountyClaimedEvent` supplies the winning player's UUID and paid amount. Reacquire the service after a plugin reload or re-enable; cached API references throw `IllegalStateException` while FoBounty is disabled. No hot reload of a consumer's class linkage is promised.

### Reads

- `bounty(UUID)` returns one committed active bounty, or empty if none exists.
- `activeBounties()` returns a detached, unmodifiable list, newest first. Snapshot records contain immutable UUID, name, amount, and timestamp values. They cannot mutate FoBounty state.
- `totalEarned(UUID)` reads the durable earned total from SQLite. `history(UUID, limit)` reads newest-first history up to the configured cap. Both return `CompletionStage` values. They complete on FoBounty's database worker and complete exceptionally on database or executor failure. Schedule Bukkit world/player work onto its owning server or player scheduler from callbacks. Historical totals saturate at `Long.MAX_VALUE`.

### Actions

- `placePaid(Player payer, OfflinePlayer target, long amount)` requires `fobounty.use`, an online or previously played target other than the payer, configured price bounds, the shared placement cooldown, and an available Vault economy. It withdraws money immediately, then stores the contribution. A database failure triggers a refund attempt. `PlacementResult` distinguishes success, validation, permission, cooldown, insufficient funds, cancellation, unavailable economy, and failure. A successful call uses the same announcements and webhook as `/bounty add`.
- `placeSponsored(Plugin sponsor, OfflinePlayer target, long amount)` requires an enabled sponsor plugin, an online or previously played target, configured price bounds, and an available Vault economy. It does **not** withdraw money. The sponsor appears as `Plugin: <name>` in contribution displays. The claim payout creates currency through Vault. Admin removal or natural-death loss does not reimburse a sponsor. This call also uses placement announcements and webhook.
- `removeBounty(CommandSender admin, UUID targetId)` requires `fobounty.admin`. It deletes an active bounty without refund, reports `NONE` when absent, and uses the admin-removal webhook on success.

All action methods are synchronous and must be called from a valid server execution context: a player's owning thread for paid placement or player admin removal, and an appropriate server scheduler context for sponsored placement or console removal. Do not invoke them from arbitrary async workers. No action is guaranteed atomic across Vault and SQLite; if a compensating refund or payout reversal fails, FoBounty logs a severe error. Callers should inspect the result rather than infer success from a balance or event alone.

### Events

| Event | Timing and payload |
| --- | --- |
| `BountyPlaceCheckEvent` | Cancellable. Runs after target, amount, and availability checks, before withdrawal or database writes. Contains target, payer UUID (null for sponsor), source name, amount, and previous total. Cancellation leaves the bounty and balance unchanged; a paid placement still consumes its attempt cooldown. |
| `BountyPlacedEvent` | Fires only after placement commits. Contains the same identities plus amount added, previous total, and new total. Includes both first placements and top-ups. |
| `BountyClaimCheckEvent` | Cancellable. Runs for an otherwise eligible PvP kill after FoBounty's team and anti-abuse checks, before payout or database writes. Contains target, killer, and amount. Cancellation leaves the bounty active. Natural death does not trigger it. |
| `BountyClaimedEvent` | Fires only after Vault payout and the claim transaction commit. Contains target, killer, and paid amount. |
| `BountyClearedEvent` | Fires only after admin removal or configured natural-death loss commits. Contains target, amount, reason, and admin UUID/name when applicable. Claims emit `BountyClaimedEvent` instead. |

Events fire on the triggering action's thread. Their Bukkit asynchronous flag reflects `Bukkit.isPrimaryThread()` at dispatch; on regionized servers, a player region thread may be reported as asynchronous. Listeners must not assume they may access unrelated player or world state. Event payloads are immutable values. Failed operations, cancelled checks, no-op removals, startup loads, and storage reloads do not fire change events.

### Event listener example

Inside a Bukkit `Listener` registered by your plugin (import this event class, `org.bukkit.event.EventHandler`, and `org.bukkit.Bukkit` where used):

```java
@EventHandler
public void onBountyPlaceCheck(BountyPlaceCheckEvent event) {
    Bukkit.getLogger().info("Bounty requested for " + event.getTargetName());
}
```

### Complete public API method index

Signatures use short type names; import Fo types from this plugin's `api` package and Bukkit types from Bukkit.

#### `BountyApi`

```java
Optional<BountyApi.BountySnapshot> bounty(UUID);
List<BountyApi.BountySnapshot> activeBounties();
BountyApi.PlacementResult placePaid(Player, OfflinePlayer, long);
BountyApi.PlacementResult placeSponsored(Plugin, OfflinePlayer, long);
BountyApi.RemovalResult removeBounty(command.CommandSender, UUID);
CompletionStage<Long> totalEarned(UUID);
CompletionStage<List<BountyApi.HistorySnapshot>> history(UUID, int);
```


### Complete Bukkit event index

- `BountyClaimCheckEvent`.
- `BountyClaimedEvent`.
- `BountyClearedEvent`.
- `BountyPlaceCheckEvent`.
- `BountyPlacedEvent`.

</details>
