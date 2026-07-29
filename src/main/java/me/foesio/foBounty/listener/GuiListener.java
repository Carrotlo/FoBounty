package me.foesio.foBounty.listener;

import me.foesio.core.scheduler.FoScheduler;
import me.foesio.foBounty.gui.AdminEditorHolder;
import me.foesio.foBounty.gui.BountyMainHolder;
import me.foesio.foBounty.gui.GuiManager;
import me.foesio.foBounty.gui.HistoryHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

public final class GuiListener implements Listener {
    private final GuiManager guiManager;
    private final FoScheduler scheduler;

    public GuiListener(GuiManager guiManager, FoScheduler scheduler) {
        this.guiManager = guiManager;
        this.scheduler = scheduler;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (holder instanceof BountyMainHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
                return;
            }
            guiManager.onMainClick(player, event.getRawSlot(), event.getCurrentItem());
            return;
        }

        if (holder instanceof AdminEditorHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
                return;
            }
            guiManager.onAdminClick(player, event.getRawSlot());
            return;
        }

        if (holder instanceof HistoryHolder historyHolder) {
            event.setCancelled(true);
            if (event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
                return;
            }
            guiManager.onHistoryClick(player, event.getRawSlot(), historyHolder);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof BountyMainHolder || holder instanceof AdminEditorHolder || holder instanceof HistoryHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (!(holder instanceof BountyMainHolder) || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (guiManager.consumeSuppressedClose(player)) {
            return;
        }

        scheduler.runForPlayer(player, () -> {
            InventoryHolder currentTop = player.getOpenInventory().getTopInventory().getHolder();
            if (!(currentTop instanceof BountyMainHolder)) {
                guiManager.clearSearch(player.getUniqueId());
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        guiManager.clearViewerState(event.getPlayer().getUniqueId());
    }
}
