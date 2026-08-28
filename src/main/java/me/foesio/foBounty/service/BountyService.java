package me.foesio.foBounty.service;

import me.foesio.core.scheduler.FoScheduler;
import me.foesio.core.sound.FoSoundService;
import me.foesio.foBounty.config.PluginSettings;
import me.foesio.foBounty.data.SQLiteStore;
import me.foesio.foBounty.economy.EconomyBridge;
import me.foesio.foBounty.model.ActiveBounty;
import me.foesio.foBounty.model.BountyContribution;
import me.foesio.foBounty.model.BountyFilter;
import me.foesio.foBounty.model.BountyHistoryEntry;
import me.foesio.foBounty.model.HistoryType;
import me.foesio.foBounty.util.IpUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class BountyService {
    private static final int MAX_ASYNC_DB_QUEUE_SIZE = 256;

    public enum AddStatus {
        SUCCESS,
        NO_ECONOMY,
        NO_MONEY,
        FAILURE
    }

    public enum ClaimStatus {
        NONE,
        CLAIMED,
        LOST_NATURAL,
        BLOCKED_SAME_TEAM,
        BLOCKED_ANTI_ABUSE,
        NO_ECONOMY,
        FAILURE
    }

    public enum RemoveStatus {
        NONE,
        REMOVED,
        FAILURE
    }

    public static final class AddResult {
        private final AddStatus status;
        private final long total;

        public AddResult(AddStatus status, long total) {
            this.status = status;
            this.total = total;
        }

        public AddStatus getStatus() {
            return status;
        }

        public long getTotal() {
            return total;
        }
    }

    public static final class ClaimResult {
        private final ClaimStatus status;
        private final long amount;
        private final String targetName;

        public ClaimResult(ClaimStatus status, long amount, String targetName) {
            this.status = status;
            this.amount = amount;
            this.targetName = targetName;
        }

        public ClaimStatus getStatus() {
            return status;
        }

        public long getAmount() {
            return amount;
        }

        public String getTargetName() {
            return targetName;
        }
    }

    public static final class RemoveResult {
        private final RemoveStatus status;
        private final long amount;

        public RemoveResult(RemoveStatus status, long amount) {
            this.status = status;
            this.amount = amount;
        }

        public RemoveStatus getStatus() {
            return status;
        }

        public long getAmount() {
            return amount;
        }
    }

    private final JavaPlugin plugin;
    private final PluginSettings settings;
    private final PlayerLookupService playerLookupService;
    private final FoTeamsHookService foTeamsHookService;
    private final FoScheduler scheduler;
    private final FoSoundService sounds;
    private final ExecutorService dbExecutor;

    private final Map<UUID, ActiveBounty> activeBounties = new ConcurrentHashMap<>();
    private final Map<UUID, Long> totalEarnedCache = new ConcurrentHashMap<>();
    private final Set<UUID> pendingTotalEarnedLoads = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingHistoryLoads = ConcurrentHashMap.newKeySet();
    private final Map<BountyFilter, CachedBountyList> sortedBountyCache = new EnumMap<>(BountyFilter.class);
    private long bountyRevision;

    private volatile SQLiteStore store;
    private volatile String databaseFile;
    private EconomyBridge economy;

    public BountyService(JavaPlugin plugin,
                         PluginSettings settings,
                         PlayerLookupService playerLookupService,
                         FoTeamsHookService foTeamsHookService,
                         FoScheduler scheduler,
                         FoSoundService sounds) {
        this.plugin = plugin;
        this.settings = settings;
        this.playerLookupService = playerLookupService;
        this.foTeamsHookService = foTeamsHookService;
        this.scheduler = scheduler;
        this.sounds = sounds;
        this.databaseFile = settings.getDatabaseFile();
        this.store = new SQLiteStore(plugin, databaseFile);
        this.dbExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_ASYNC_DB_QUEUE_SIZE),
                r -> {
                    Thread thread = new Thread(r, "FoBounty-DB");
                    thread.setDaemon(true);
                    return thread;
                }
        );
    }

    public void setEconomy(EconomyBridge economy) {
        this.economy = economy;
    }

    public void init() throws SQLException {
        store.init();
        replaceActiveBounties(store.loadActiveBounties());
    }

    public synchronized void reloadStorageIfNeeded() throws SQLException {
        String nextDatabaseFile = settings.getDatabaseFile();
        if (nextDatabaseFile.equals(databaseFile)) {
            return;
        }

        SQLiteStore nextStore = new SQLiteStore(plugin, nextDatabaseFile);
        nextStore.init();
        Map<UUID, ActiveBounty> loadedBounties = nextStore.loadActiveBounties();
        store = nextStore;
        databaseFile = nextDatabaseFile;
        totalEarnedCache.clear();
        replaceActiveBounties(loadedBounties);
        plugin.getLogger().info("Reloaded bounty storage from " + nextDatabaseFile + ".");
    }

    public synchronized void reindexKnownPlayers() {
        for (ActiveBounty bounty : activeBounties.values()) {
            playerLookupService.index(Bukkit.getOfflinePlayer(bounty.getTargetUuid()));
            for (BountyContribution contribution : bounty.getContributions()) {
                playerLookupService.index(Bukkit.getOfflinePlayer(contribution.getSetterUuid()));
            }
        }
    }

    public void shutdown() {
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                int droppedTasks = dbExecutor.shutdownNow().size();
                if (droppedTasks > 0) {
                    plugin.getLogger().warning("Stopped " + droppedTasks + " pending bounty database task(s) during shutdown.");
                }
            }
        } catch (InterruptedException exception) {
            dbExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public synchronized AddResult addBounty(Player setter, OfflinePlayer target, long amount) {
        if (economy == null) {
            return new AddResult(AddStatus.NO_ECONOMY, 0);
        }
        if (amount <= 0L) {
            return new AddResult(AddStatus.FAILURE, 0);
        }

        String targetName = target.getName() != null ? target.getName() : "Unknown";
        long now = System.currentTimeMillis();
        UUID targetUuid = target.getUniqueId();
        ActiveBounty existing = activeBounties.get(targetUuid);
        long currentTotal = existing == null ? 0L : existing.getTotalAmount();
        BountyContribution existingContribution = existing == null ? null : existing.getContributionMap().get(setter.getUniqueId());
        long currentContribution = existingContribution == null ? 0L : existingContribution.getAmount();
        long newTotal = addOrReject(currentTotal, amount);
        long newContributionAmount = addOrReject(currentContribution, amount);
        if (newTotal < 0L || newContributionAmount < 0L) {
            plugin.getLogger().warning("Rejected bounty add that would exceed the safe economy amount for target " + targetUuid + ".");
            return new AddResult(AddStatus.FAILURE, 0);
        }

        if (!economy.withdraw(setter, amount)) {
            return new AddResult(AddStatus.NO_MONEY, 0);
        }

        boolean createdBounty = existing == null;
        String previousTargetName = existing == null ? null : existing.getTargetName();
        long previousTotalAmount = existing == null ? 0L : existing.getTotalAmount();
        long previousCreatedAt = existing == null ? 0L : existing.getCreatedAt();
        long previousUpdatedAt = existing == null ? 0L : existing.getUpdatedAt();
        boolean hadContribution = existingContribution != null;
        String previousSetterName = existingContribution == null ? null : existingContribution.getSetterName();
        long previousContributionAmount = existingContribution == null ? 0L : existingContribution.getAmount();

        ActiveBounty bounty = existing;
        if (bounty == null) {
            bounty = new ActiveBounty(targetUuid, targetName, 0, now, now);
            activeBounties.put(targetUuid, bounty);
        }

        bounty.setTargetName(targetName);
        if (bounty.getCreatedAt() <= 0L) {
            bounty.setCreatedAt(now);
        }
        bounty.setUpdatedAt(now);
        bounty.setTotalAmount(newTotal);

        BountyContribution contribution = bounty.getContributionMap().computeIfAbsent(setter.getUniqueId(),
                ignored -> new BountyContribution(setter.getUniqueId(), setter.getName(), 0));
        contribution.setSetterName(setter.getName());
        contribution.setAmount(newContributionAmount);

        try {
            store.upsertBountyWithContributionTransactional(bounty, contribution);
        } catch (SQLException exception) {
            if (createdBounty) {
                activeBounties.remove(targetUuid);
            } else {
                bounty.setTargetName(previousTargetName);
                bounty.setTotalAmount(previousTotalAmount);
                bounty.setCreatedAt(previousCreatedAt);
                bounty.setUpdatedAt(previousUpdatedAt);
                if (hadContribution) {
                    contribution.setSetterName(previousSetterName);
                    contribution.setAmount(previousContributionAmount);
                } else {
                    bounty.getContributionMap().remove(setter.getUniqueId());
                }
            }
            if (!economy.deposit(setter, amount)) {
                plugin.getLogger().severe("Failed to refund " + setter.getName() + " after bounty DB failure.");
            }
            plugin.getLogger().warning("Failed to persist added bounty: " + exception.getMessage());
            return new AddResult(AddStatus.FAILURE, 0);
        }
        playerLookupService.index(setter);
        playerLookupService.index(target);
        markBountiesChanged();
        sounds.play(setter, "bounty.set");

        return new AddResult(AddStatus.SUCCESS, bounty.getTotalAmount());
    }

    public synchronized RemoveResult removeBounty(UUID targetUuid) {
        ActiveBounty existing = activeBounties.get(targetUuid);
        if (existing == null) {
            return new RemoveResult(RemoveStatus.NONE, 0L);
        }
        long removedAmount = existing.getTotalAmount();
        try {
            store.deleteActiveBounty(targetUuid);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Failed to delete bounty: " + exception.getMessage());
            return new RemoveResult(RemoveStatus.FAILURE, 0L);
        }
        activeBounties.remove(targetUuid);
        markBountiesChanged();
        return new RemoveResult(RemoveStatus.REMOVED, removedAmount);
    }

    public synchronized ClaimResult handlePlayerDeath(Player victim, Player killer) {
        ActiveBounty bounty = activeBounties.get(victim.getUniqueId());
        if (bounty == null) {
            return new ClaimResult(ClaimStatus.NONE, 0, victim.getName());
        }

        if (killer != null) {
            if (settings.isBlockSameTeamFoTeamsClaims()
                    && foTeamsHookService.areInSameTeam(killer.getUniqueId(), victim.getUniqueId())) {
                return new ClaimResult(ClaimStatus.BLOCKED_SAME_TEAM, bounty.getTotalAmount(), victim.getName());
            }
            if (isBlockedByAntiAbuse(killer, victim)) {
                return new ClaimResult(ClaimStatus.BLOCKED_ANTI_ABUSE, bounty.getTotalAmount(), victim.getName());
            }
            if (economy == null) {
                return new ClaimResult(ClaimStatus.NO_ECONOMY, 0, victim.getName());
            }
            long amount = bounty.getTotalAmount();
            if (!economy.deposit(killer, amount)) {
                return new ClaimResult(ClaimStatus.NO_ECONOMY, 0, victim.getName());
            }

            long now = System.currentTimeMillis();
            BountyHistoryEntry killerEntry = new BountyHistoryEntry(
                    killer.getUniqueId(),
                    killer.getName(),
                    HistoryType.CLAIMED,
                    victim.getUniqueId(),
                    victim.getName(),
                    amount,
                    "PVP",
                    now
            );
            BountyHistoryEntry victimEntry = new BountyHistoryEntry(
                    victim.getUniqueId(),
                    victim.getName(),
                    HistoryType.LOST,
                    killer.getUniqueId(),
                    killer.getName(),
                    amount,
                    "PVP",
                    now
            );

            try {
                store.recordClaim(victim.getUniqueId(), killer.getUniqueId(), killer.getName(), amount,
                        killerEntry, victimEntry, settings.getHistoryCap());
            } catch (SQLException exception) {
                if (!economy.withdraw(killer, amount)) {
                    plugin.getLogger().severe("Failed to reverse " + safeName(killer.getName()) + "'s bounty payout after claim DB failure.");
                }
                plugin.getLogger().warning("Failed to persist bounty claim: " + exception.getMessage());
                return new ClaimResult(ClaimStatus.FAILURE, amount, victim.getName());
            }

            activeBounties.remove(victim.getUniqueId());
            markBountiesChanged();
            totalEarnedCache.merge(killer.getUniqueId(), amount, this::cappedAdd);
            playerLookupService.index(killer);
            playerLookupService.index(victim);
            return new ClaimResult(ClaimStatus.CLAIMED, amount, victim.getName());
        }

        if (settings.isNaturalDeathLosesBounty()) {
            long amount = bounty.getTotalAmount();
            long now = System.currentTimeMillis();
            BountyHistoryEntry victimEntry = new BountyHistoryEntry(
                    victim.getUniqueId(),
                    victim.getName(),
                    HistoryType.LOST,
                    null,
                    null,
                    amount,
                    "NATURAL_DEATH",
                    now
            );
            try {
                store.recordNaturalLoss(victim.getUniqueId(), victimEntry, settings.getHistoryCap());
            } catch (SQLException exception) {
                plugin.getLogger().warning("Failed to persist natural bounty loss: " + exception.getMessage());
                return new ClaimResult(ClaimStatus.FAILURE, amount, victim.getName());
            }
            activeBounties.remove(victim.getUniqueId());
            markBountiesChanged();
            playerLookupService.index(victim);
            return new ClaimResult(ClaimStatus.LOST_NATURAL, amount, victim.getName());
        }

        return new ClaimResult(ClaimStatus.NONE, 0, victim.getName());
    }

    public synchronized List<ActiveBounty> getSortedBounties(BountyFilter filter, String searchTarget) {
        List<ActiveBounty> list = sortedSummariesForFilter(filter);
        if (searchTarget != null && !searchTarget.isBlank()) {
            String lowered = searchTarget.toLowerCase(Locale.ROOT);
            list.removeIf(bounty -> bounty.getTargetName() == null
                    || !bounty.getTargetName().toLowerCase(Locale.ROOT).contains(lowered));
        }
        return list;
    }

    public synchronized ActiveBounty getBountySnapshot(UUID targetUuid, int contributionLimit) {
        ActiveBounty bounty = activeBounties.get(targetUuid);
        return bounty == null ? null : copyBounty(bounty, contributionLimit);
    }

    public synchronized long getCurrentBounty(UUID targetUuid) {
        ActiveBounty bounty = activeBounties.get(targetUuid);
        return bounty == null ? 0L : bounty.getTotalAmount();
    }

    public void primeTotalEarnedAsync(UUID playerUuid, Consumer<Long> callback) {
        Long cached = totalEarnedCache.get(playerUuid);
        if (cached != null) {
            callback.accept(cached);
            return;
        }
        if (!pendingTotalEarnedLoads.add(playerUuid)) {
            callback.accept(getCachedTotalEarned(playerUuid));
            return;
        }
        boolean queued = enqueue(() -> {
            long total = 0L;
            SQLiteStore currentStore = store;
            try {
                total = currentStore.getTotalEarned(playerUuid);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Failed to fetch total earned: " + exception.getMessage());
            } finally {
                pendingTotalEarnedLoads.remove(playerUuid);
            }
            long finalTotal = total;
            totalEarnedCache.put(playerUuid, finalTotal);
            scheduler.runGlobal(() -> callback.accept(finalTotal));
        });
        if (!queued) {
            pendingTotalEarnedLoads.remove(playerUuid);
        }
    }

    public long getCachedTotalEarned(UUID playerUuid) {
        return totalEarnedCache.getOrDefault(playerUuid, 0L);
    }

    public void fetchHistoryForPlayerAsync(Player player, UUID playerUuid, int limit, Consumer<List<BountyHistoryEntry>> callback) {
        int effectiveLimit = Math.max(1, Math.min(limit, settings.getHistoryCap()));
        String requestKey = player.getUniqueId() + ":" + playerUuid;
        if (!pendingHistoryLoads.add(requestKey)) {
            return;
        }
        boolean queued = enqueue(() -> {
            List<BountyHistoryEntry> entries = List.of();
            SQLiteStore currentStore = store;
            try {
                entries = currentStore.getHistory(playerUuid, effectiveLimit);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Failed to load history: " + exception.getMessage());
            } finally {
                pendingHistoryLoads.remove(requestKey);
            }
            List<BountyHistoryEntry> finalEntries = entries;
            scheduler.runForPlayer(player, () -> callback.accept(finalEntries));
        });
        if (!queued) {
            pendingHistoryLoads.remove(requestKey);
        }
    }

    private Comparator<ActiveBounty> comparatorFor(BountyFilter filter) {
        return switch (filter) {
            case OLDEST -> Comparator.comparingLong(ActiveBounty::getCreatedAt);
            case NEWEST -> Comparator.comparingLong(ActiveBounty::getCreatedAt).reversed();
            case HIGHEST -> Comparator.comparingLong(ActiveBounty::getTotalAmount).reversed();
            case LOWEST -> Comparator.comparingLong(ActiveBounty::getTotalAmount);
        };
    }

    private List<ActiveBounty> sortedSummariesForFilter(BountyFilter filter) {
        CachedBountyList cached = sortedBountyCache.get(filter);
        if (cached != null && cached.revision == bountyRevision) {
            return new ArrayList<>(cached.entries);
        }

        List<ActiveBounty> list = new ArrayList<>();
        for (ActiveBounty bounty : activeBounties.values()) {
            list.add(copyBounty(bounty, 0));
        }
        list.sort(comparatorFor(filter));
        sortedBountyCache.put(filter, new CachedBountyList(bountyRevision, List.copyOf(list)));
        return list;
    }

    private void replaceActiveBounties(Map<UUID, ActiveBounty> loadedBounties) {
        activeBounties.clear();
        activeBounties.putAll(loadedBounties);
        markBountiesChanged();
        reindexKnownPlayers();
    }

    private void markBountiesChanged() {
        bountyRevision++;
        sortedBountyCache.clear();
    }

    private boolean enqueue(Runnable task) {
        try {
            dbExecutor.submit(() -> {
                try {
                    task.run();
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(Level.WARNING, "Unexpected async bounty task failure.", exception);
                }
            });
            return true;
        } catch (RejectedExecutionException exception) {
            plugin.getLogger().fine("Skipped bounty database task because the executor is shut down.");
            return false;
        }
    }

    private boolean isBlockedByAntiAbuse(Player killer, Player victim) {
        if (settings.isBlockSameIpClaims() && IpUtil.isSameIp(killer, victim)) {
            return true;
        }
        return settings.isBlockSameSubnetClaims() && IpUtil.isSameSubnet(killer, victim);
    }

    private long addOrReject(long current, long amount) {
        if (current < 0L || amount < 0L || current > PluginSettings.MAX_SAFE_MONEY - amount) {
            return -1L;
        }
        return current + amount;
    }

    private long cappedAdd(long current, long amount) {
        if (current < 0L || amount < 0L || current > Long.MAX_VALUE - amount) {
            return Long.MAX_VALUE;
        }
        return current + amount;
    }

    private ActiveBounty copyBounty(ActiveBounty source, int contributionLimit) {
        ActiveBounty copy = new ActiveBounty(
                source.getTargetUuid(),
                source.getTargetName(),
                source.getTotalAmount(),
                source.getCreatedAt(),
                source.getUpdatedAt()
        );
        int copied = 0;
        int effectiveLimit = Math.max(0, contributionLimit);
        for (BountyContribution contribution : source.getContributions()) {
            if (copied >= effectiveLimit) {
                break;
            }
            BountyContribution copiedContribution = new BountyContribution(
                    contribution.getSetterUuid(),
                    contribution.getSetterName(),
                    contribution.getAmount()
            );
            copy.getContributionMap().put(copiedContribution.getSetterUuid(), copiedContribution);
            copied++;
        }
        return copy;
    }

    private String safeName(String value) {
        return value == null || value.isBlank() ? "Unknown" : value;
    }

    private static final class CachedBountyList {
        private final long revision;
        private final List<ActiveBounty> entries;

        private CachedBountyList(long revision, List<ActiveBounty> entries) {
            this.revision = revision;
            this.entries = entries;
        }
    }
}
