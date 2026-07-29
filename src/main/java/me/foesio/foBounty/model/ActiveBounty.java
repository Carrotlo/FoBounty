package me.foesio.foBounty.model;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ActiveBounty {
    private final UUID targetUuid;
    private String targetName;
    private long totalAmount;
    private long createdAt;
    private long updatedAt;
    private final Map<UUID, BountyContribution> contributions;

    public ActiveBounty(UUID targetUuid, String targetName, long totalAmount, long createdAt, long updatedAt) {
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.totalAmount = totalAmount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.contributions = new LinkedHashMap<>();
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(long totalAmount) {
        this.totalAmount = totalAmount;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Collection<BountyContribution> getContributions() {
        return contributions.values();
    }

    public Map<UUID, BountyContribution> getContributionMap() {
        return contributions;
    }
}
