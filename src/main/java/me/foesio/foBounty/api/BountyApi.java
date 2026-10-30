package me.foesio.foBounty.api;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Public FoBounty contract. Obtain it from Bukkit's ServicesManager after FoBounty enables. */
public interface BountyApi {
    /** Returns committed active state, or empty if this target has no bounty. */
    Optional<BountySnapshot> bounty(UUID targetId);

    /** Returns a detached, unmodifiable list of committed active bounties. */
    List<BountySnapshot> activeBounties();

    /** Charges the payer through Vault and places a bounty using player placement rules. */
    PlacementResult placePaid(Player payer, OfflinePlayer target, long amount);

    /** Adds a server-funded bounty with no withdrawal. The sponsor plugin is shown as a contributor. */
    PlacementResult placeSponsored(Plugin sponsor, OfflinePlayer target, long amount);

    /** Removes an active bounty when the sender has fobounty.admin permission. No refund is issued. */
    RemovalResult removeBounty(CommandSender admin, UUID targetId);

    /** Completes with the durable earned total on the database worker; completes exceptionally on failure. */
    CompletionStage<Long> totalEarned(UUID playerId);

    /** Completes with detached, newest-first history on the database worker; completes exceptionally on failure. */
    CompletionStage<List<HistorySnapshot>> history(UUID playerId, int limit);

    record BountySnapshot(UUID targetId, String targetName, long total, long createdAt, long updatedAt) {}

    record HistorySnapshot(UUID playerId, String playerName, HistoryKind kind, UUID relatedId,
                           String relatedName, long amount, String reason, long occurredAt) {}

    enum HistoryKind { CLAIMED, LOST }

    enum PlacementStatus {
        PLACED, INVALID_PAYER, INVALID_TARGET, INVALID_AMOUNT, NO_PERMISSION, NO_ECONOMY,
        INSUFFICIENT_FUNDS, INVALID_SPONSOR, COOLDOWN, CANCELLED, FAILURE
    }

    /** Total is the new active total on PLACED and zero for all other statuses. */
    record PlacementResult(PlacementStatus status, long total) {}

    enum RemovalStatus { REMOVED, NONE, NO_PERMISSION, FAILURE }

    record RemovalResult(RemovalStatus status, long amount) {}
}
