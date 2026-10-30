package me.foesio.foBounty.api.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Fired after the killer was paid and the claim was committed to storage. */
public final class BountyClaimedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID targetId;
    private final String targetName;
    private final UUID killerId;
    private final String killerName;
    private final long amount;

    public BountyClaimedEvent(UUID targetId, String targetName, UUID killerId, String killerName, long amount) {
        super(!Bukkit.isPrimaryThread());
        this.targetId = targetId;
        this.targetName = targetName;
        this.killerId = killerId;
        this.killerName = killerName;
        this.amount = amount;
    }

    public UUID getTargetId() { return targetId; }
    public String getTargetName() { return targetName; }
    public UUID getKillerId() { return killerId; }
    public String getKillerName() { return killerName; }
    public long getAmount() { return amount; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
