package me.foesio.foBounty.model;

import java.util.UUID;

public final class BountyHistoryEntry {
    private final UUID playerUuid;
    private final String playerName;
    private final HistoryType type;
    private final UUID relatedUuid;
    private final String relatedName;
    private final long amount;
    private final String reason;
    private final long occurredAt;

    public BountyHistoryEntry(UUID playerUuid,
                              String playerName,
                              HistoryType type,
                              UUID relatedUuid,
                              String relatedName,
                              long amount,
                              String reason,
                              long occurredAt) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.type = type;
        this.relatedUuid = relatedUuid;
        this.relatedName = relatedName;
        this.amount = amount;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    public UUID getPlayerUuid() {
        return playerUuid;
    }

    public String getPlayerName() {
        return playerName;
    }

    public HistoryType getType() {
        return type;
    }

    public UUID getRelatedUuid() {
        return relatedUuid;
    }

    public String getRelatedName() {
        return relatedName;
    }

    public long getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public long getOccurredAt() {
        return occurredAt;
    }
}
