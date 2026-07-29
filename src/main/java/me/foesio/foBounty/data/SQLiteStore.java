package me.foesio.foBounty.data;

import me.foesio.foBounty.config.PluginSettings;
import me.foesio.foBounty.model.ActiveBounty;
import me.foesio.foBounty.model.BountyContribution;
import me.foesio.foBounty.model.BountyHistoryEntry;
import me.foesio.foBounty.model.HistoryType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SQLiteStore {
    private final JavaPlugin plugin;
    private final String jdbcUrl;

    public SQLiteStore(JavaPlugin plugin, String fileName) {
        this.plugin = plugin;
        File db = new File(plugin.getDataFolder(), fileName);
        File parent = db.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("Could not create SQLite folder: " + parent.getPath());
        }
        this.jdbcUrl = "jdbc:sqlite:" + db.getAbsolutePath();
    }

    public void init() throws SQLException {
        try (Connection connection = open()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS active_bounties (" +
                                "target_uuid TEXT PRIMARY KEY," +
                                "target_name TEXT NOT NULL," +
                                "total_amount INTEGER NOT NULL," +
                                "created_at INTEGER NOT NULL," +
                                "updated_at INTEGER NOT NULL" +
                                ")"
                );
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS bounty_contributions (" +
                                "target_uuid TEXT NOT NULL," +
                                "setter_uuid TEXT NOT NULL," +
                                "setter_name TEXT NOT NULL," +
                                "amount INTEGER NOT NULL," +
                                "PRIMARY KEY(target_uuid, setter_uuid)" +
                                ")"
                );
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS player_stats (" +
                                "player_uuid TEXT PRIMARY KEY," +
                                "player_name TEXT NOT NULL," +
                                "total_earned INTEGER NOT NULL DEFAULT 0" +
                                ")"
                );
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS bounty_history (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                                "player_uuid TEXT NOT NULL," +
                                "player_name TEXT NOT NULL," +
                                "entry_type TEXT NOT NULL," +
                                "related_uuid TEXT," +
                                "related_name TEXT," +
                                "amount INTEGER NOT NULL," +
                                "reason TEXT NOT NULL," +
                                "occurred_at INTEGER NOT NULL" +
                                ")"
                );
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_history_player_time ON bounty_history(player_uuid, occurred_at DESC)");
            }
        }
    }

    public Map<UUID, ActiveBounty> loadActiveBounties() throws SQLException {
        Map<UUID, ActiveBounty> result = new HashMap<>();
        try (Connection connection = open()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT target_uuid, target_name, total_amount, created_at, updated_at FROM active_bounties"
            )) {
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        UUID targetUuid = parseUuid("active_bounties", "target_uuid", rs.getString("target_uuid"));
                        if (targetUuid == null) {
                            continue;
                        }
                        long totalAmount = rs.getLong("total_amount");
                        if (totalAmount <= 0L) {
                            plugin.getLogger().warning("Skipping invalid active_bounties row for " + targetUuid + ": total_amount must be positive.");
                            continue;
                        }
                        if (totalAmount > PluginSettings.MAX_SAFE_MONEY) {
                            plugin.getLogger().warning("Skipping invalid active_bounties row for " + targetUuid + ": total_amount exceeds safe economy limit.");
                            continue;
                        }
                        ActiveBounty bounty = new ActiveBounty(
                                targetUuid,
                                rs.getString("target_name"),
                                totalAmount,
                                rs.getLong("created_at"),
                                rs.getLong("updated_at")
                        );
                        result.put(targetUuid, bounty);
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT target_uuid, setter_uuid, setter_name, amount FROM bounty_contributions"
            )) {
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        UUID targetUuid = parseUuid("bounty_contributions", "target_uuid", rs.getString("target_uuid"));
                        if (targetUuid == null) {
                            continue;
                        }
                        ActiveBounty bounty = result.get(targetUuid);
                        if (bounty == null) {
                            continue;
                        }
                        UUID setterUuid = parseUuid("bounty_contributions", "setter_uuid", rs.getString("setter_uuid"));
                        if (setterUuid == null) {
                            continue;
                        }
                        long amount = rs.getLong("amount");
                        if (amount <= 0L) {
                            plugin.getLogger().warning("Skipping invalid bounty_contributions row for " + setterUuid + ": amount must be positive.");
                            continue;
                        }
                        if (amount > PluginSettings.MAX_SAFE_MONEY) {
                            plugin.getLogger().warning("Skipping invalid bounty_contributions row for " + setterUuid + ": amount exceeds safe economy limit.");
                            continue;
                        }
                        BountyContribution contribution = new BountyContribution(
                                setterUuid,
                                rs.getString("setter_name"),
                                amount
                        );
                        bounty.getContributionMap().put(setterUuid, contribution);
                    }
                }
            }
        }
        return result;
    }

    public void upsertBountyWithContributionTransactional(ActiveBounty bounty, BountyContribution contribution) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                upsertActiveBounty(connection, bounty);
                upsertContribution(connection, bounty.getTargetUuid(), contribution);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void deleteActiveBounty(UUID targetUuid) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                deleteActiveBounty(connection, targetUuid);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public long getTotalEarned(UUID playerUuid) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT total_earned FROM player_stats WHERE player_uuid=?"
             )) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Math.max(0L, rs.getLong("total_earned"));
                }
            }
        }
        return 0L;
    }

    public void recordClaim(UUID targetUuid,
                            UUID killerUuid,
                            String killerName,
                            long amount,
                            BountyHistoryEntry killerEntry,
                            BountyHistoryEntry victimEntry,
                            int historyCap) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                deleteActiveBounty(connection, targetUuid);
                addEarned(connection, killerUuid, killerName, amount);
                insertHistory(connection, killerEntry);
                insertHistory(connection, victimEntry);
                trimHistoryToCap(connection, killerEntry.getPlayerUuid(), historyCap);
                trimHistoryToCap(connection, victimEntry.getPlayerUuid(), historyCap);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public void recordNaturalLoss(UUID targetUuid,
                                  BountyHistoryEntry victimEntry,
                                  int historyCap) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                deleteActiveBounty(connection, targetUuid);
                insertHistory(connection, victimEntry);
                trimHistoryToCap(connection, victimEntry.getPlayerUuid(), historyCap);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public List<BountyHistoryEntry> getHistory(UUID playerUuid, int limit) throws SQLException {
        List<BountyHistoryEntry> list = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id, player_uuid, player_name, entry_type, related_uuid, related_name, amount, reason, occurred_at " +
                             "FROM bounty_history WHERE player_uuid=? ORDER BY occurred_at DESC LIMIT ?"
             )) {
            statement.setString(1, playerUuid.toString());
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    BountyHistoryEntry entry = readHistoryEntry(rs);
                    if (entry != null) {
                        list.add(entry);
                    }
                }
            }
        }
        return list;
    }

    private BountyHistoryEntry readHistoryEntry(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        UUID playerUuid = parseUuid("bounty_history#" + id, "player_uuid", rs.getString("player_uuid"));
        HistoryType type = parseHistoryType(id, rs.getString("entry_type"));
        if (playerUuid == null || type == null) {
            return null;
        }
        long amount = rs.getLong("amount");
        if (amount < 0L) {
            plugin.getLogger().warning("Skipping invalid bounty_history row " + id + ": amount cannot be negative.");
            return null;
        }

        String relatedRaw = rs.getString("related_uuid");
        UUID relatedUuid = null;
        if (relatedRaw != null) {
            relatedUuid = parseOptionalUuid("bounty_history row " + id, "related_uuid", relatedRaw);
        }

        return new BountyHistoryEntry(
                playerUuid,
                rs.getString("player_name"),
                type,
                relatedUuid,
                rs.getString("related_name"),
                amount,
                rs.getString("reason"),
                rs.getLong("occurred_at")
        );
    }

    private UUID parseUuid(String table, String column, String raw) {
        if (raw == null || raw.isBlank()) {
            plugin.getLogger().warning("Skipping invalid " + table + " row: " + column + " is empty.");
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Skipping invalid " + table + " row: " + column + " is not a UUID: " + raw);
            return null;
        }
    }

    private UUID parseOptionalUuid(String context, String column, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Ignoring invalid " + column + " in " + context + ": " + raw);
            return null;
        }
    }

    private HistoryType parseHistoryType(long id, String raw) {
        if (raw == null || raw.isBlank()) {
            plugin.getLogger().warning("Skipping invalid bounty_history row " + id + ": entry_type is empty.");
            return null;
        }
        try {
            return HistoryType.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Skipping invalid bounty_history row " + id + ": unknown entry_type " + raw);
            return null;
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void upsertActiveBounty(Connection connection, ActiveBounty bounty) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO active_bounties(target_uuid, target_name, total_amount, created_at, updated_at) " +
                        "VALUES(?,?,?,?,?) " +
                        "ON CONFLICT(target_uuid) DO UPDATE SET " +
                        "target_name=excluded.target_name, total_amount=excluded.total_amount, " +
                        "created_at=excluded.created_at, updated_at=excluded.updated_at"
        )) {
            statement.setString(1, bounty.getTargetUuid().toString());
            statement.setString(2, bounty.getTargetName());
            statement.setLong(3, bounty.getTotalAmount());
            statement.setLong(4, bounty.getCreatedAt());
            statement.setLong(5, bounty.getUpdatedAt());
            statement.executeUpdate();
        }
    }

    private void upsertContribution(Connection connection, UUID targetUuid, BountyContribution contribution) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO bounty_contributions(target_uuid, setter_uuid, setter_name, amount) " +
                        "VALUES(?,?,?,?) ON CONFLICT(target_uuid, setter_uuid) DO UPDATE SET " +
                        "setter_name=excluded.setter_name, amount=excluded.amount"
        )) {
            statement.setString(1, targetUuid.toString());
            statement.setString(2, contribution.getSetterUuid().toString());
            statement.setString(3, contribution.getSetterName());
            statement.setLong(4, contribution.getAmount());
            statement.executeUpdate();
        }
    }

    private void deleteActiveBounty(Connection connection, UUID targetUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM active_bounties WHERE target_uuid=?"
        )) {
            statement.setString(1, targetUuid.toString());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM bounty_contributions WHERE target_uuid=?"
        )) {
            statement.setString(1, targetUuid.toString());
            statement.executeUpdate();
        }
    }

    private void addEarned(Connection connection, UUID playerUuid, String playerName, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO player_stats(player_uuid, player_name, total_earned) VALUES(?,?,?) " +
                        "ON CONFLICT(player_uuid) DO UPDATE SET " +
                        "player_name=excluded.player_name, " +
                        "total_earned=CASE " +
                        "WHEN player_stats.total_earned < 0 THEN excluded.total_earned " +
                        "WHEN player_stats.total_earned > ? - excluded.total_earned THEN ? " +
                        "ELSE player_stats.total_earned + excluded.total_earned END"
        )) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerName);
            statement.setLong(3, amount);
            statement.setLong(4, Long.MAX_VALUE);
            statement.setLong(5, Long.MAX_VALUE);
            statement.executeUpdate();
        }
    }

    private void insertHistory(Connection connection, BountyHistoryEntry entry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO bounty_history(player_uuid, player_name, entry_type, related_uuid, related_name, amount, reason, occurred_at) " +
                        "VALUES(?,?,?,?,?,?,?,?)"
        )) {
            statement.setString(1, entry.getPlayerUuid().toString());
            statement.setString(2, entry.getPlayerName());
            statement.setString(3, entry.getType().name());
            statement.setString(4, entry.getRelatedUuid() == null ? null : entry.getRelatedUuid().toString());
            statement.setString(5, entry.getRelatedName());
            statement.setLong(6, entry.getAmount());
            statement.setString(7, entry.getReason());
            statement.setLong(8, entry.getOccurredAt());
            statement.executeUpdate();
        }
    }

    private void trimHistoryToCap(Connection connection, UUID playerUuid, int cap) throws SQLException {
        int effectiveCap = Math.max(1, cap);
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM bounty_history WHERE player_uuid=? AND id NOT IN (" +
                        "SELECT id FROM bounty_history WHERE player_uuid=? ORDER BY occurred_at DESC, id DESC LIMIT ?" +
                        ")"
        )) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerUuid.toString());
            statement.setInt(3, effectiveCap);
            statement.executeUpdate();
        }
    }
}
