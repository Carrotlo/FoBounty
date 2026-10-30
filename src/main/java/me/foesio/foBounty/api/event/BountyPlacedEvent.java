package me.foesio.foBounty.api.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Fired after a new bounty or contribution is committed to storage. */
public final class BountyPlacedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID targetId;
    private final String targetName;
    private final UUID payerId;
    private final String sourceName;
    private final long amount;
    private final long previousTotal;
    private final long newTotal;

    public BountyPlacedEvent(UUID targetId, String targetName, UUID payerId, String sourceName,
                             long amount, long previousTotal, long newTotal) {
        super(!Bukkit.isPrimaryThread());
        this.targetId = targetId;
        this.targetName = targetName;
        this.payerId = payerId;
        this.sourceName = sourceName;
        this.amount = amount;
        this.previousTotal = previousTotal;
        this.newTotal = newTotal;
    }

    public UUID getTargetId() { return targetId; }
    public String getTargetName() { return targetName; }
    /** Null for sponsored placement. */
    public UUID getPayerId() { return payerId; }
    public String getSourceName() { return sourceName; }
    public long getAmount() { return amount; }
    public long getPreviousTotal() { return previousTotal; }
    public long getNewTotal() { return newTotal; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
