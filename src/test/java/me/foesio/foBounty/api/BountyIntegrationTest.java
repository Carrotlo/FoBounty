package me.foesio.foBounty.api;

import me.foesio.core.economy.VaultEconomyBridge;
import me.foesio.core.scheduler.FoScheduler;
import me.foesio.core.sound.FoSoundService;
import me.foesio.foBounty.api.event.BountyClaimCheckEvent;
import me.foesio.foBounty.api.event.BountyClaimedEvent;
import me.foesio.foBounty.api.event.BountyClearedEvent;
import me.foesio.foBounty.api.event.BountyPlaceCheckEvent;
import me.foesio.foBounty.api.event.BountyPlacedEvent;
import me.foesio.foBounty.config.PluginSettings;
import me.foesio.foBounty.data.SQLiteStore;
import me.foesio.foBounty.model.ActiveBounty;
import me.foesio.foBounty.model.BountyContribution;
import me.foesio.foBounty.service.BountyService;
import me.foesio.foBounty.service.BountyApiImpl;
import me.foesio.foBounty.service.BountyNotifications;
import me.foesio.foBounty.service.FoTeamsHookService;
import me.foesio.foBounty.service.PlayerLookupService;
import me.foesio.foBounty.util.CooldownService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BountyIntegrationTest {
    @TempDir Path folder;

    @Test
    void placementAndClaimChecksDoNotChangeMoneyOrStorageWhenCancelled() throws Exception {
        JavaPlugin plugin = plugin();
        PluginSettings settings = settings();
        VaultEconomyBridge economy = mock(VaultEconomyBridge.class);
        when(economy.withdraw(any(OfflinePlayer.class), anyDouble())).thenReturn(true);
        when(economy.deposit(any(OfflinePlayer.class), anyDouble())).thenReturn(true);
        Player payer = player("Payer");
        Player victim = player("Target");
        Player killer = player("Killer");
        Plugin sponsor = mock(Plugin.class);
        when(sponsor.getName()).thenReturn("QuestPlugin");
        List<Event> events = new ArrayList<>();
        AtomicBoolean cancelPlace = new AtomicBoolean(true);
        AtomicBoolean cancelClaim = new AtomicBoolean(true);
        PluginManager manager = mock(PluginManager.class);
        doAnswer(call -> {
            Event event = call.getArgument(0);
            events.add(event);
            if (event instanceof BountyPlaceCheckEvent check && cancelPlace.get()) check.setCancelled(true);
            if (event instanceof BountyClaimCheckEvent check && cancelClaim.get()) check.setCancelled(true);
            return null;
        }).when(manager).callEvent(any(Event.class));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            BountyService service = new BountyService(plugin, settings, mock(PlayerLookupService.class),
                    mock(FoTeamsHookService.class), mock(FoScheduler.class), mock(FoSoundService.class),
                    new CooldownService());
            service.setEconomy(economy);
            service.init();
            try {
                try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("bounties.db"));
                     Statement statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO player_stats(player_uuid, player_name, total_earned) VALUES('"
                            + killer.getUniqueId() + "','Killer',100)");
                }
                assertEquals(BountyService.AddStatus.CANCELLED, service.addBounty(payer, victim, 200).getStatus());
                assertEquals(0, service.getCurrentBounty(victim.getUniqueId()));
                verify(economy, never()).withdraw(any(OfflinePlayer.class), anyDouble());
                assertEquals(List.of(BountyPlaceCheckEvent.class), events.stream().map(Object::getClass).toList());

                cancelPlace.set(false);
                events.clear();
                assertEquals(BountyService.AddStatus.SUCCESS, service.addBounty(payer, victim, 200).getStatus());
                assertEquals(List.of(BountyPlaceCheckEvent.class, BountyPlacedEvent.class),
                        events.stream().map(Object::getClass).toList());
                assertEquals(200, service.getCurrentBounty(victim.getUniqueId()));

                events.clear();
                assertEquals(BountyService.AddStatus.SUCCESS,
                        service.addSponsoredBounty(sponsor, victim, 300).getStatus());
                assertNull(((BountyPlacedEvent) events.get(1)).getPayerId());
                ActiveBounty stored = new SQLiteStore(plugin, "bounties.db")
                        .loadActiveBounties().get(victim.getUniqueId());
                assertEquals(500, stored.getTotalAmount());
                assertTrue(stored.getContributions().stream()
                        .anyMatch(value -> value.getKind() == BountyContribution.Kind.SPONSOR));

                events.clear();
                assertEquals(BountyService.ClaimStatus.BLOCKED_EXTERNAL,
                        service.handlePlayerDeath(victim, killer).getStatus());
                assertEquals(500, service.getCurrentBounty(victim.getUniqueId()));
                verify(economy, never()).deposit(eq(killer), anyDouble());
                assertEquals(List.of(BountyClaimCheckEvent.class), events.stream().map(Object::getClass).toList());

                cancelClaim.set(false);
                events.clear();
                assertEquals(BountyService.ClaimStatus.CLAIMED, service.handlePlayerDeath(victim, killer).getStatus());
                assertEquals(List.of(BountyClaimCheckEvent.class, BountyClaimedEvent.class),
                        events.stream().map(Object::getClass).toList());
                assertEquals(0, service.getCurrentBounty(victim.getUniqueId()));
                assertEquals(600, service.loadTotalEarnedAsync(killer.getUniqueId()).get(5, TimeUnit.SECONDS));
                for (int attempt = 0; attempt < 100 && service.getCachedTotalEarned(killer.getUniqueId()) != 600; attempt++) {
                    Thread.sleep(10);
                }
                assertEquals(600, service.getCachedTotalEarned(killer.getUniqueId()));
                assertEquals(1, service.loadHistoryAsync(killer.getUniqueId(), 10).get(5, TimeUnit.SECONDS).size());

                events.clear();
                service.addSponsoredBounty(sponsor, victim, 300);
                events.clear();
                assertEquals(BountyService.RemoveStatus.REMOVED,
                        service.removeBounty(victim.getUniqueId(), "Admin").getStatus());
                BountyClearedEvent cleared = assertInstanceOf(BountyClearedEvent.class, events.get(0));
                assertEquals(BountyClearedEvent.Reason.ADMIN_REMOVAL, cleared.getReason());
                assertEquals("Admin", cleared.getActorName());
                verify(economy, times(1)).withdraw(any(OfflinePlayer.class), anyDouble());

                events.clear();
                assertEquals(BountyService.RemoveStatus.NONE,
                        service.removeBounty(victim.getUniqueId(), "Admin").getStatus());
                assertTrue(events.isEmpty());

                when(settings.isNaturalDeathLosesBounty()).thenReturn(true);
                service.addSponsoredBounty(sponsor, victim, 300);
                events.clear();
                assertEquals(BountyService.ClaimStatus.LOST_NATURAL,
                        service.handlePlayerDeath(victim, null).getStatus());
                BountyClearedEvent natural = assertInstanceOf(BountyClearedEvent.class, events.get(0));
                assertEquals(BountyClearedEvent.Reason.NATURAL_DEATH, natural.getReason());
                assertNull(natural.getActorName());
            } finally {
                service.shutdown();
            }
        }
    }

    @Test
    void oldContributionTableMigratesWithoutLosingPlayerRows() throws Exception {
        JavaPlugin plugin = plugin();
        UUID target = UUID.randomUUID();
        UUID payer = UUID.randomUUID();
        String url = "jdbc:sqlite:" + folder.resolve("legacy.db");
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE active_bounties(target_uuid TEXT PRIMARY KEY, target_name TEXT NOT NULL, "
                    + "total_amount INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
            statement.executeUpdate("CREATE TABLE bounty_contributions(target_uuid TEXT NOT NULL, setter_uuid TEXT NOT NULL, "
                    + "setter_name TEXT NOT NULL, amount INTEGER NOT NULL, PRIMARY KEY(target_uuid, setter_uuid))");
            statement.executeUpdate("INSERT INTO active_bounties VALUES('" + target + "','Target',200,1,1)");
            statement.executeUpdate("INSERT INTO bounty_contributions VALUES('" + target + "','" + payer + "','Payer',200)");
        }
        SQLiteStore store = new SQLiteStore(plugin, "legacy.db");
        store.init();
        store.init();
        ActiveBounty loaded = store.loadActiveBounties().get(target);
        assertEquals(200, loaded.getTotalAmount());
        assertEquals(BountyContribution.Kind.PLAYER, loaded.getContributions().iterator().next().getKind());
    }

    @Test
    void publicReadsAreDetachedAndUnauthorisedActionsDoNotReachStorage() {
        JavaPlugin plugin = plugin();
        when(plugin.isEnabled()).thenReturn(true);
        PluginSettings settings = settings();
        BountyService service = mock(BountyService.class);
        BountyApiImpl api = new BountyApiImpl(plugin, settings, service, mock(BountyNotifications.class));
        UUID targetId = UUID.randomUUID();
        ActiveBounty mutable = new ActiveBounty(targetId, "Target", 200, 1, 2);
        when(service.getBountySnapshot(targetId, 0)).thenReturn(mutable);
        when(service.getSortedBounties(any(), isNull())).thenReturn(List.of(mutable));

        BountyApi.BountySnapshot snapshot = api.bounty(targetId).orElseThrow();
        List<BountyApi.BountySnapshot> all = api.activeBounties();
        mutable.setTotalAmount(900);
        assertEquals(200, snapshot.total());
        assertEquals(200, all.get(0).total());
        assertThrows(UnsupportedOperationException.class, () -> all.clear());

        Player payer = player("Payer");
        OfflinePlayer target = mock(OfflinePlayer.class);
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("Target");
        assertEquals(BountyApi.PlacementStatus.NO_PERMISSION, api.placePaid(payer, target, 200).status());
        assertEquals(BountyApi.RemovalStatus.NO_PERMISSION, api.removeBounty(payer, targetId).status());
        verify(service, never()).addBounty(any(), any(), anyLong());
        verify(service, never()).removeBounty(any(), any(), any());
    }

    private JavaPlugin plugin() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("FoBounty-test"));
        return plugin;
    }

    private PluginSettings settings() {
        PluginSettings settings = mock(PluginSettings.class);
        when(settings.getDatabaseFile()).thenReturn("bounties.db");
        when(settings.getMinPrice()).thenReturn(100L);
        when(settings.getMaxPrice()).thenReturn(1_000_000L);
        when(settings.getHistoryCap()).thenReturn(28);
        return settings;
    }

    private Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        return player;
    }
}
