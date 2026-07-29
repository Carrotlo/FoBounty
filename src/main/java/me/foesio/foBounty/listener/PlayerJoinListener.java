package me.foesio.foBounty.listener;

import me.foesio.foBounty.service.BountyService;
import me.foesio.foBounty.service.PlayerLookupService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class PlayerJoinListener implements Listener {
    private final BountyService bountyService;
    private final PlayerLookupService playerLookupService;

    public PlayerJoinListener(BountyService bountyService,
                              PlayerLookupService playerLookupService) {
        this.bountyService = bountyService;
        this.playerLookupService = playerLookupService;
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player player = event.getPlayer();
        playerLookupService.index(player);
        bountyService.primeTotalEarnedAsync(player.getUniqueId(), value -> {
            // cache warm-up only
        });
    }
}
