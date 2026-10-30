package me.foesio.foBounty.api.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Fired for an otherwise eligible player kill, before Vault payout or database changes. */
public final class BountyClaimCheckEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID targetId;
    private final UUID killerId;
    private final long amount;
    private boolean cancelled;

    public BountyClaimCheckEvent(UUID targetId, UUID killerId, long amount) {
        super(!Bukkit.isPrimaryThread());
        this.targetId = targetId;
        this.killerId = killerId;
        this.amount = amount;
    }

    public UUID getTargetId() { return targetId; }
    public UUID getKillerId() { return killerId; }
    public long getAmount() { return amount; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
