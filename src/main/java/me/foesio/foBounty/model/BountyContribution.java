package me.foesio.foBounty.model;

import java.util.UUID;

public final class BountyContribution {
    public enum Kind { PLAYER, SPONSOR }
    private final UUID setterUuid;
    private final Kind kind;
    private String setterName;
    private long amount;

    public BountyContribution(UUID setterUuid, String setterName, long amount) {
        this(setterUuid, setterName, amount, Kind.PLAYER);
    }

    public BountyContribution(UUID setterUuid, String setterName, long amount, Kind kind) {
        this.setterUuid = setterUuid;
        this.setterName = setterName;
        this.amount = amount;
        this.kind = kind;
    }

    public UUID getSetterUuid() {
        return setterUuid;
    }

    public Kind getKind() {
        return kind;
    }

    public String getSetterName() {
        return setterName;
    }

    public void setSetterName(String setterName) {
        this.setterName = setterName;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }
}
