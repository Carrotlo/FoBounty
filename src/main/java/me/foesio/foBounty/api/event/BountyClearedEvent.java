package me.foesio.foBounty.api.event;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/** Fired after an admin removal or natural-death loss is committed to storage. */
public final class BountyClearedEvent extends Event {
    public enum Reason { ADMIN_REMOVAL, NATURAL_DEATH }
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID targetId;
    private final String targetName;
    private final long amount;
    private final Reason reason;
    private final UUID actorId;
    private final String actorName;

    public BountyClearedEvent(UUID targetId, String targetName, long amount, Reason reason,
                              UUID actorId, String actorName) {
        super(!Bukkit.isPrimaryThread());
        this.targetId = targetId;
        this.targetName = targetName;
        this.amount = amount;
        this.reason = reason;
        this.actorId = actorId;
        this.actorName = actorName;
    }

    public UUID getTargetId() { return targetId; }
    public String getTargetName() { return targetName; }
    public long getAmount() { return amount; }
    public Reason getReason() { return reason; }
    /** Null for console actions or natural death. */
    public UUID getActorId() { return actorId; }
    /** Null for natural death. */
    public String getActorName() { return actorName; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
