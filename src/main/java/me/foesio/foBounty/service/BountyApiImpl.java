package me.foesio.foBounty.service;

import me.foesio.foBounty.api.BountyApi;
import me.foesio.foBounty.config.PluginSettings;
import me.foesio.foBounty.model.ActiveBounty;
import me.foesio.foBounty.model.BountyFilter;
import me.foesio.foBounty.model.BountyHistoryEntry;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class BountyApiImpl implements BountyApi {
    private final JavaPlugin plugin;
    private final PluginSettings settings;
    private final BountyService service;
    private final BountyNotifications notifications;

    public BountyApiImpl(JavaPlugin plugin, PluginSettings settings, BountyService service,
                         BountyNotifications notifications) {
        this.plugin = plugin;
        this.settings = settings;
        this.service = service;
        this.notifications = notifications;
    }

    @Override
    public Optional<BountySnapshot> bounty(UUID targetId) {
        requireEnabled();
        ActiveBounty found = service.getBountySnapshot(Objects.requireNonNull(targetId, "targetId"), 0);
        return Optional.ofNullable(found).map(this::snapshot);
    }

    @Override
    public List<BountySnapshot> activeBounties() {
        requireEnabled();
        return service.getSortedBounties(BountyFilter.NEWEST, null).stream().map(this::snapshot).toList();
    }

    @Override
    public PlacementResult placePaid(Player payer, OfflinePlayer target, long amount) {
        requireEnabled();
        Objects.requireNonNull(payer, "payer");
        Objects.requireNonNull(target, "target");
        if (!payer.isOnline()) return new PlacementResult(PlacementStatus.INVALID_PAYER, 0);
        if (!payer.hasPermission("fobounty.use")) return new PlacementResult(PlacementStatus.NO_PERMISSION, 0);
        if (target.getName() == null || (!target.isOnline() && !target.hasPlayedBefore())
                || payer.getUniqueId().equals(target.getUniqueId())) {
            return new PlacementResult(PlacementStatus.INVALID_TARGET, 0);
        }
        if (amount < settings.getMinPrice() || amount > settings.getMaxPrice()) {
            return new PlacementResult(PlacementStatus.INVALID_AMOUNT, 0);
        }
        BountyService.AddResult result = service.addBounty(payer, target, amount);
        if (result.getStatus() == BountyService.AddStatus.SUCCESS) {
            notifications.placed(payer.getName(), target.getName(), amount, result.getTotal());
        }
        return placementResult(result);
    }

    @Override
    public PlacementResult placeSponsored(Plugin sponsor, OfflinePlayer target, long amount) {
        requireEnabled();
        Objects.requireNonNull(sponsor, "sponsor");
        Objects.requireNonNull(target, "target");
        if (!sponsor.isEnabled()) return new PlacementResult(PlacementStatus.INVALID_SPONSOR, 0);
        if (target.getName() == null || (!target.isOnline() && !target.hasPlayedBefore())) {
            return new PlacementResult(PlacementStatus.INVALID_TARGET, 0);
        }
        if (amount < settings.getMinPrice() || amount > settings.getMaxPrice()) {
            return new PlacementResult(PlacementStatus.INVALID_AMOUNT, 0);
        }
        BountyService.AddResult result = service.addSponsoredBounty(sponsor, target, amount);
        if (result.getStatus() == BountyService.AddStatus.SUCCESS) {
            notifications.placed("Plugin: " + sponsor.getName(), target.getName(), amount, result.getTotal());
        }
        return placementResult(result);
    }

    private PlacementResult placementResult(BountyService.AddResult result) {
        return new PlacementResult(switch (result.getStatus()) {
            case SUCCESS -> PlacementStatus.PLACED;
            case NO_ECONOMY -> PlacementStatus.NO_ECONOMY;
            case NO_MONEY -> PlacementStatus.INSUFFICIENT_FUNDS;
            case COOLDOWN -> PlacementStatus.COOLDOWN;
            case CANCELLED -> PlacementStatus.CANCELLED;
            case FAILURE -> PlacementStatus.FAILURE;
        }, result.getTotal());
    }

    @Override
    public RemovalResult removeBounty(CommandSender admin, UUID targetId) {
        requireEnabled();
        Objects.requireNonNull(admin, "admin");
        Objects.requireNonNull(targetId, "targetId");
        if (!admin.hasPermission("fobounty.admin")) return new RemovalResult(RemovalStatus.NO_PERMISSION, 0);
        BountyService.RemoveResult result = service.removeBounty(targetId,
                admin instanceof Player player ? player.getUniqueId() : null, admin.getName());
        if (result.getStatus() == BountyService.RemoveStatus.REMOVED) {
            notifications.removed(admin.getName(), result.getTargetName(), result.getAmount());
        }
        return new RemovalResult(switch (result.getStatus()) {
            case REMOVED -> RemovalStatus.REMOVED;
            case NONE -> RemovalStatus.NONE;
            case FAILURE -> RemovalStatus.FAILURE;
        }, result.getAmount());
    }

    @Override
    public CompletionStage<Long> totalEarned(UUID playerId) {
        requireEnabled();
        return service.loadTotalEarnedAsync(Objects.requireNonNull(playerId, "playerId"));
    }

    @Override
    public CompletionStage<List<HistorySnapshot>> history(UUID playerId, int limit) {
        requireEnabled();
        Objects.requireNonNull(playerId, "playerId");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return service.loadHistoryAsync(playerId, limit).thenApply(entries -> entries.stream().map(this::historySnapshot).toList());
    }

    private BountySnapshot snapshot(ActiveBounty value) {
        return new BountySnapshot(value.getTargetUuid(), value.getTargetName(), value.getTotalAmount(),
                value.getCreatedAt(), value.getUpdatedAt());
    }

    private HistorySnapshot historySnapshot(BountyHistoryEntry value) {
        return new HistorySnapshot(value.getPlayerUuid(), value.getPlayerName(),
                HistoryKind.valueOf(value.getType().name()), value.getRelatedUuid(), value.getRelatedName(),
                value.getAmount(), value.getReason(), value.getOccurredAt());
    }

    private void requireEnabled() {
        if (!plugin.isEnabled()) throw new IllegalStateException("FoBounty is disabled");
    }
}
