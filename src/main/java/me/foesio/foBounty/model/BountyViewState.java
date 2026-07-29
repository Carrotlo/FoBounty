package me.foesio.foBounty.model;

public final class BountyViewState {
    private int page;
    private BountyFilter filter;
    private String searchTarget;

    public BountyViewState() {
        this.page = 0;
        this.filter = BountyFilter.NEWEST;
        this.searchTarget = null;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = Math.max(page, 0);
    }

    public BountyFilter getFilter() {
        return filter;
    }

    public void setFilter(BountyFilter filter) {
        this.filter = filter;
    }

    public String getSearchTarget() {
        return searchTarget;
    }

    public void setSearchTarget(String searchTarget) {
        this.searchTarget = searchTarget;
    }
}
