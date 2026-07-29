package me.foesio.foBounty.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import me.foesio.core.dialog.NativeDialogConfigDefaults;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class PluginSettings {
    public static final long MAX_SAFE_MONEY = 9_007_199_254_740_991L;
    public static final int MAX_HISTORY_CAP = 5_000;

    private static final Set<String> REMOVED_CONFIG_KEYS = Set.of(
            "settings.update-checker",
            "settings.search-timeout-seconds",
            "gui.main-title",
            "gui.history-title",
            "gui.admin-title"
    );

    private long commandCooldownMs;
    private long bountyAddCooldownMs;
    private long guiRefreshCooldownMs;
    private long guiFilterCooldownMs;
    private long historyRequestCooldownMs;

    private long minPrice;
    private long maxPrice;
    private boolean naturalDeathLosesBounty;

    private boolean announceEnabled;
    private long announceMinimumAmount;
    private boolean bountySetAnnounceEnabled;
    private long bountySetAnnounceMinimumAmount;
    private boolean blockSameIpClaims;
    private boolean blockSameSubnetClaims;
    private boolean blockSameTeamFoTeamsClaims;

    private int historyPageSize;
    private int historyCap;

    private String databaseFile;

    public void load(JavaPlugin plugin) {
        YamlConfiguration diskConfig = YamlFileUpdater.update(plugin, "config.yml", REMOVED_CONFIG_KEYS, Map.of());
        if (applyCoreConfigDefaults(diskConfig)) {
            YamlFileUpdater.save(plugin, "config.yml", diskConfig);
        }
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        commandCooldownMs = readNonNegativeLong(cfg, "settings.cooldowns.command-ms", 100L);
        bountyAddCooldownMs = readNonNegativeLong(cfg, "settings.cooldowns.bounty-add-ms", 1000L);
        guiRefreshCooldownMs = readNonNegativeLong(cfg, "settings.cooldowns.gui-refresh-ms", 100L);
        guiFilterCooldownMs = readNonNegativeLong(cfg, "settings.cooldowns.gui-filter-ms", 100L);
        historyRequestCooldownMs = readNonNegativeLong(cfg, "settings.cooldowns.history-request-ms", 250L);

        minPrice = clamp(cfg.getLong("bounty.min-price", 100L), 1L, MAX_SAFE_MONEY);
        maxPrice = clamp(cfg.getLong("bounty.max-price", 9_999_999_999_999L), minPrice, MAX_SAFE_MONEY);
        naturalDeathLosesBounty = cfg.getBoolean("bounty.natural-death-loses-bounty", false);

        announceEnabled = cfg.getBoolean("announcements.enabled", true);
        announceMinimumAmount = readThreshold(cfg, "announcements.minimum-announce-amount", -1L);
        bountySetAnnounceEnabled = cfg.getBoolean("announcements.bounty-set-enabled", true);
        bountySetAnnounceMinimumAmount = readThreshold(cfg, "announcements.bounty-set-minimum-announce-amount", -1L);
        blockSameIpClaims = cfg.getBoolean("anti-abuse.claim-block.same-ip", false);
        blockSameSubnetClaims = cfg.getBoolean("anti-abuse.claim-block.same-subnet", false);
        blockSameTeamFoTeamsClaims = cfg.getBoolean("anti-abuse.claim-block.same-team-foteams", true);

        historyPageSize = Math.max(1, Math.min(28, cfg.getInt("gui.history-page-size", 28)));
        historyCap = Math.max(1, Math.min(MAX_HISTORY_CAP, cfg.getInt("history.cap", 28)));

        databaseFile = safeDatabaseFile(cfg.getString("database.file", "bounties.db"));
    }

    private boolean applyCoreConfigDefaults(FileConfiguration cfg) {
        boolean shouldSave = !cfg.contains(NativeDialogConfigDefaults.ENABLED_PATH, true)
                || !cfg.contains(NativeDialogConfigDefaults.WARN_ON_FALLBACK_PATH, true)
                || cfg.getComments("native-dialogs").isEmpty()
                || cfg.getComments(NativeDialogConfigDefaults.ENABLED_PATH).isEmpty()
                || cfg.getComments(NativeDialogConfigDefaults.WARN_ON_FALLBACK_PATH).isEmpty();
        NativeDialogConfigDefaults.addDefaults(cfg);
        return shouldSave;
    }

    public void save(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration cfg = YamlFileUpdater.update(plugin, "config.yml", REMOVED_CONFIG_KEYS, Map.of());
        applyCoreConfigDefaults(cfg);
        cfg.set("settings.cooldowns.command-ms", commandCooldownMs);
        cfg.set("settings.cooldowns.bounty-add-ms", bountyAddCooldownMs);
        cfg.set("settings.cooldowns.gui-refresh-ms", guiRefreshCooldownMs);
        cfg.set("settings.cooldowns.gui-filter-ms", guiFilterCooldownMs);
        cfg.set("settings.cooldowns.history-request-ms", historyRequestCooldownMs);
        cfg.set("bounty.min-price", minPrice);
        cfg.set("bounty.max-price", maxPrice);
        cfg.set("bounty.natural-death-loses-bounty", naturalDeathLosesBounty);
        cfg.set("announcements.enabled", announceEnabled);
        cfg.set("announcements.minimum-announce-amount", announceMinimumAmount);
        cfg.set("announcements.bounty-set-enabled", bountySetAnnounceEnabled);
        cfg.set("announcements.bounty-set-minimum-announce-amount", bountySetAnnounceMinimumAmount);
        cfg.set("anti-abuse.claim-block.same-ip", blockSameIpClaims);
        cfg.set("anti-abuse.claim-block.same-subnet", blockSameSubnetClaims);
        cfg.set("anti-abuse.claim-block.same-team-foteams", blockSameTeamFoTeamsClaims);
        cfg.set("gui.history-page-size", historyPageSize);
        cfg.set("history.cap", historyCap);
        cfg.set("database.file", databaseFile);
        try {
            cfg.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save config.yml: " + exception.getMessage());
        }
        plugin.reloadConfig();
    }

    public long getCommandCooldownMs() {
        return commandCooldownMs;
    }

    public long getBountyAddCooldownMs() {
        return bountyAddCooldownMs;
    }

    public long getGuiRefreshCooldownMs() {
        return guiRefreshCooldownMs;
    }

    public long getGuiFilterCooldownMs() {
        return guiFilterCooldownMs;
    }

    public long getHistoryRequestCooldownMs() {
        return historyRequestCooldownMs;
    }

    public long getMinPrice() {
        return minPrice;
    }

    public void setMinPrice(long minPrice) {
        this.minPrice = clamp(minPrice, 1L, MAX_SAFE_MONEY);
        if (maxPrice < this.minPrice) {
            maxPrice = this.minPrice;
        }
    }

    public long getMaxPrice() {
        return maxPrice;
    }

    public void setMaxPrice(long maxPrice) {
        this.maxPrice = clamp(maxPrice, minPrice, MAX_SAFE_MONEY);
    }

    public boolean isNaturalDeathLosesBounty() {
        return naturalDeathLosesBounty;
    }

    public void setNaturalDeathLosesBounty(boolean naturalDeathLosesBounty) {
        this.naturalDeathLosesBounty = naturalDeathLosesBounty;
    }

    public boolean isAnnounceEnabled() {
        return announceEnabled;
    }

    public void setAnnounceEnabled(boolean announceEnabled) {
        this.announceEnabled = announceEnabled;
    }

    public long getAnnounceMinimumAmount() {
        return announceMinimumAmount;
    }

    public void setAnnounceMinimumAmount(long announceMinimumAmount) {
        this.announceMinimumAmount = announceMinimumAmount < 0L ? -1L : Math.min(announceMinimumAmount, MAX_SAFE_MONEY);
    }

    public boolean isBountySetAnnounceEnabled() {
        return bountySetAnnounceEnabled;
    }

    public long getBountySetAnnounceMinimumAmount() {
        return bountySetAnnounceMinimumAmount;
    }

    public boolean isBlockSameIpClaims() {
        return blockSameIpClaims;
    }

    public void setBlockSameIpClaims(boolean blockSameIpClaims) {
        this.blockSameIpClaims = blockSameIpClaims;
    }

    public boolean isBlockSameSubnetClaims() {
        return blockSameSubnetClaims;
    }

    public void setBlockSameSubnetClaims(boolean blockSameSubnetClaims) {
        this.blockSameSubnetClaims = blockSameSubnetClaims;
    }

    public boolean isBlockSameTeamFoTeamsClaims() {
        return blockSameTeamFoTeamsClaims;
    }

    public int getHistoryPageSize() {
        return historyPageSize;
    }

    public int getHistoryCap() {
        return historyCap;
    }

    public void setHistoryCap(int historyCap) {
        this.historyCap = Math.max(1, Math.min(MAX_HISTORY_CAP, historyCap));
    }

    public String getDatabaseFile() {
        return databaseFile;
    }

    private String safeDatabaseFile(String raw) {
        if (raw == null || raw.isBlank()) {
            return "bounties.db";
        }
        try {
            Path path = Path.of(raw).normalize();
            if (path.isAbsolute() || path.startsWith("..")) {
                return "bounties.db";
            }
            String normalized = path.toString();
            return normalized.isBlank() ? "bounties.db" : normalized;
        } catch (InvalidPathException exception) {
            return "bounties.db";
        }
    }

    private long readNonNegativeLong(FileConfiguration cfg, String path, long fallback) {
        return Math.max(0L, cfg.getLong(path, fallback));
    }

    private long readThreshold(FileConfiguration cfg, String path, long fallback) {
        long value = cfg.getLong(path, fallback);
        return value < 0L ? -1L : Math.min(value, MAX_SAFE_MONEY);
    }

    private long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
