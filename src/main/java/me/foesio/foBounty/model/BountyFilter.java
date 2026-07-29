package me.foesio.foBounty.model;

public enum BountyFilter {
    OLDEST("Oldest"),
    NEWEST("Newest"),
    HIGHEST("Highest"),
    LOWEST("Lowest");

    private final String label;

    BountyFilter(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public BountyFilter next() {
        BountyFilter[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
