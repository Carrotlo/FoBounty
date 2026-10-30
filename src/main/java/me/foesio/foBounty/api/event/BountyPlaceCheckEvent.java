package me.foesio.foBounty.api.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Fired after placement validation, before a Vault withdrawal or database change. */
public final class BountyPlaceCheckEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID targetId;
    private final String targetName;
    private final UUID payerId;
    private final String sourceName;
    private final long amount;
    private final long previousTotal;
    private boolean cancelled;

    public BountyPlaceCheckEvent(UUID targetId, String targetName, UUID payerId, String sourceName,
                                 long amount, long previousTotal) {
        super(!Bukkit.isPrimaryThread());
        this.targetId = targetId;
        this.targetName = targetName;
        this.payerId = payerId;
        this.sourceName = sourceName;
        this.amount = amount;
        this.previousTotal = previousTotal;
    }

    public UUID getTargetId() { return targetId; }
    public String getTargetName() { return targetName; }
    /** Null for sponsored placement. */
    public UUID getPayerId() { return payerId; }
    public String getSourceName() { return sourceName; }
    public long getAmount() { return amount; }
    public long getPreviousTotal() { return previousTotal; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
