package me.foesio.foBounty.model;

import java.util.UUID;

public final class BountyContribution {
    private final UUID setterUuid;
    private String setterName;
    private long amount;

    public BountyContribution(UUID setterUuid, String setterName, long amount) {
        this.setterUuid = setterUuid;
        this.setterName = setterName;
        this.amount = amount;
    }

    public UUID getSetterUuid() {
        return setterUuid;
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
