package me.foesio.foBounty.gui;

import me.foesio.core.gui.publicgui.PublicGuiContract;
import me.foesio.core.gui.publicgui.PublicGuiService;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Set;

/** Registers both ordinary player menus with Core's single read-only click route. */
public final class PublicBountyGuis {
    public static final String MAIN = "bounty.main";
    public static final String HISTORY = "bounty.history";

    private PublicBountyGuis() {
    }

    public static PublicGuiContract mainContract() {
        return new PublicGuiContract(Set.of(
                "bounty.filter", "bounty.refresh", "bounty.search", "bounty.clear-search",
                "bounty.history", "bounty.previous-page", "bounty.next-page", "bounty.entry-history"
        ), Map.of(), Set.of(
                "own_bounty", "own_bounty_raw", "total_earned", "total_earned_raw",
                "search", "current", "target", "page", "max_page", "player",
                "amount", "amount_raw", "created_at", "updated_at", "contributions", "setter"
        ), PublicGuiContract.InteractionMode.CORE);
    }

    public static PublicGuiContract historyContract() {
        return new PublicGuiContract(Set.of(
                "bounty.previous-page", "bounty.next-page", "bounty.back-to-bounties"
        ), Map.of(), Set.of(
                "page", "max_page", "type", "player", "related", "target",
                "amount", "amount_raw", "reason", "time"
        ), PublicGuiContract.InteractionMode.CORE);
    }

    public static PublicGuiService create(JavaPlugin plugin, GuiManager manager) {
        PublicGuiService guis = new PublicGuiService(plugin)
                .menu(MAIN, plugin.getDataFolder().toPath().resolve("guis/bounty-main.yml"), mainContract())
                .menu(HISTORY, plugin.getDataFolder().toPath().resolve("guis/history.yml"), historyContract())
                .entryPoint("command:/bounty", MAIN)
                .entryPoint("gui:bounty-history", HISTORY)
                .navigation("bounty.history", HISTORY)
                .navigation("bounty.back-to-bounties", MAIN)
                .requireEntry(MAIN, "bounties", 0)
                .requireEntry(HISTORY, "history", 0)
                .requireState(MAIN, "bounty-template")
                .requireState(HISTORY, "claimed-template")
                .requireState(HISTORY, "lost-template");
        for (String id : Set.of("filter", "refresh", "search", "clear-search", "history", "previous-page", "next-page")) {
            guis.requireButton(MAIN, id);
        }
        for (String id : Set.of("previous-page", "next-page", "back-to-bounties")) {
            guis.requireButton(HISTORY, id);
        }
        for (String action : Set.of("bounty.filter", "bounty.refresh", "bounty.search", "bounty.clear-search",
                "bounty.history", "bounty.previous-page", "bounty.next-page", "bounty.entry-history",
                "bounty.back-to-bounties")) {
            guis.action(action, manager::onPublicClick);
        }
        return guis;
    }
}
