package me.foesio.foBounty.listener;

import me.foesio.core.message.FoMessageService;
import me.foesio.core.sound.FoSoundService;
import me.foesio.foBounty.config.PluginSettings;
import me.foesio.foBounty.service.BountyService;
import me.foesio.foBounty.service.DiscordWebhookService;
import me.foesio.foBounty.util.Style;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerDeathListener implements Listener {
    private static final long RECENT_ATTACK_WINDOW_MS = 12_000L;
    private static final long ANCHOR_ATTRIBUTION_WINDOW_MS = 8_000L;
    private static final long CRYSTAL_ATTRIBUTION_WINDOW_MS = 12_000L;
    private static final double ANCHOR_MAX_DISTANCE_SQUARED = 64.0D;
    private static final double CRYSTAL_MAX_DISTANCE_SQUARED = 100.0D;
    private static final int MAX_RECENT_ANCHOR_ACTIVATIONS = 512;
    private static final int MAX_RECENT_CRYSTAL_PLACEMENTS = 2048;

    private final PluginSettings settings;
    private final FoMessageService messages;
    private final BountyService bountyService;
    private final DiscordWebhookService discordWebhookService;
    private final FoSoundService sounds;
    private final Map<UUID, RecentAttacker> recentAttackers = new ConcurrentHashMap<>();
    private final Deque<AnchorActivation> recentAnchorActivations = new ArrayDeque<>();
    private final Deque<CrystalPlacement> recentCrystalPlacements = new ArrayDeque<>();

    public PlayerDeathListener(PluginSettings settings,
                               FoMessageService messages,
                               BountyService bountyService,
                               DiscordWebhookService discordWebhookService,
                               FoSoundService sounds) {
        this.settings = settings;
        this.messages = messages;
        this.bountyService = bountyService;
        this.discordWebhookService = discordWebhookService;
        this.sounds = sounds;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        long now = System.currentTimeMillis();
        Player attacker = resolveFromEntity(event.getDamager());
        if (attacker == null && event.getDamager() instanceof EnderCrystal crystal) {
            attacker = resolveRecentCrystalPlacer(crystal.getLocation(), victim.getUniqueId(), now);
        }
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        recentAttackers.put(victim.getUniqueId(), new RecentAttacker(attacker.getUniqueId(), now));
        pruneRecentAttackers(now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRespawnAnchorInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.RESPAWN_ANCHOR) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (recentAnchorActivations) {
            pruneAnchorActivations(now);
            recentAnchorActivations.addLast(new AnchorActivation(event.getPlayer().getUniqueId(), event.getClickedBlock().getLocation(), now));
            trimOldest(recentAnchorActivations, MAX_RECENT_ANCHOR_ACTIVATIONS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalPlaceAttempt(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!isHandInteraction(event.getHand()) || event.getClickedBlock() == null) {
            return;
        }
        ItemStack handItem = event.getItem();
        if (handItem == null || handItem.getType() != Material.END_CRYSTAL) {
            return;
        }
        long now = System.currentTimeMillis();
        Location location = event.getClickedBlock().getLocation().add(0.5D, 1.0D, 0.5D);
        synchronized (recentCrystalPlacements) {
            pruneCrystalPlacements(now);
            recentCrystalPlacements.addLast(new CrystalPlacement(event.getPlayer().getUniqueId(), location, now));
            trimOldest(recentCrystalPlacements, MAX_RECENT_CRYSTAL_PLACEMENTS);
        }
    }

    private boolean isHandInteraction(EquipmentSlot hand) {
        return hand == EquipmentSlot.HAND || hand == EquipmentSlot.OFF_HAND;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = resolveKiller(event);
        recentAttackers.remove(victim.getUniqueId());
        BountyService.ClaimResult result = bountyService.handlePlayerDeath(victim, killer);
        if (result.getStatus() == BountyService.ClaimStatus.CLAIMED && killer != null) {
            Map<String, String> replacements = new HashMap<>();
            replacements.put("amount", Style.formatMoney(result.getAmount()));
            replacements.put("target", result.getTargetName());
            messages.send(killer, "claim-reward", "claim-reward", replacements);
            sounds.play(killer, "bounty.claimed");
            discordWebhookService.sendBountyClaimed(killer.getName(), result.getTargetName(), result.getAmount());

            if (settings.isAnnounceEnabled()
                    && (settings.getAnnounceMinimumAmount() < 0
                    || result.getAmount() >= settings.getAnnounceMinimumAmount())) {
                Map<String, String> announce = new HashMap<>();
                announce.put("killer", killer.getName());
                announce.put("amount", Style.formatMoney(result.getAmount()));
                announce.put("target", result.getTargetName());
                messages.broadcastConfigured("claim-announce", announce);
            }
            return;
        }

        if (result.getStatus() == BountyService.ClaimStatus.NO_ECONOMY && killer != null) {
            messages.send(killer, "no-economy", "no-economy");
            sounds.play(killer, "bounty.error");
            return;
        }

        if (result.getStatus() == BountyService.ClaimStatus.BLOCKED_ANTI_ABUSE && killer != null) {
            messages.send(killer, "claim-blocked-alt", "claim-blocked-alt");
            sounds.play(killer, "bounty.error");
            return;
        }

        if (result.getStatus() == BountyService.ClaimStatus.BLOCKED_SAME_TEAM && killer != null) {
            messages.send(killer, "claim-blocked-team", "claim-blocked-team");
            sounds.play(killer, "bounty.error");
            return;
        }

        if (result.getStatus() == BountyService.ClaimStatus.FAILURE) {
            if (killer != null) {
                messages.send(killer, "claim-failed", "claim-failed");
                sounds.play(killer, "bounty.error");
            } else {
                messages.send(victim, "claim-failed", "claim-failed");
                sounds.play(victim, "bounty.error");
            }
            return;
        }

        if (result.getStatus() == BountyService.ClaimStatus.LOST_NATURAL) {
            Map<String, String> replacements = Map.of("amount", Style.formatMoney(result.getAmount()));
            messages.send(victim, "natural-lost", "natural-lost", replacements);
            sounds.play(victim, "bounty.lost");
        }
    }

    private Player resolveKiller(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        DamageType damageType = event.getDamageSource().getDamageType();

        Player causing = resolveFromEntity(event.getDamageSource().getCausingEntity());
        if (isValidKiller(victim, causing)) {
            return causing;
        }

        Player direct = resolveFromEntity(event.getDamageSource().getDirectEntity());
        if (isValidKiller(victim, direct)) {
            return direct;
        }

        long now = System.currentTimeMillis();
        if (isAnchorDamage(damageType)) {
            Location sourceLocation = event.getDamageSource().getSourceLocation();
            Location anchorCheckLocation = sourceLocation != null ? sourceLocation : victim.getLocation();
            Player anchorAttributed = resolveRecentAnchorActivator(anchorCheckLocation, victim.getUniqueId(), now);
            if (isValidKiller(victim, anchorAttributed)) {
                return anchorAttributed;
            }
        }
        if (isExplosionDamage(damageType)) {
            RecentAttacker recentAttacker = recentAttackers.get(victim.getUniqueId());
            if (recentAttacker != null && now - recentAttacker.timestamp <= RECENT_ATTACK_WINDOW_MS) {
                Player recent = Bukkit.getPlayer(recentAttacker.attackerUuid);
                if (isValidKiller(victim, recent)) {
                    return recent;
                }
            }
        }

        return null;
    }

    private Player resolveFromEntity(Entity entity) {
        if (entity == null) {
            return null;
        }
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player source) {
            return source;
        }
        return null;
    }

    private Player resolveRecentAnchorActivator(Location damageLocation, UUID victimUuid, long now) {
        synchronized (recentAnchorActivations) {
            pruneAnchorActivations(now);
            Iterator<AnchorActivation> iterator = recentAnchorActivations.descendingIterator();
            while (iterator.hasNext()) {
                AnchorActivation activation = iterator.next();
                if (activation.playerUuid.equals(victimUuid)) {
                    continue;
                }
                if (!sameWorld(activation.location, damageLocation)) {
                    continue;
                }
                if (activation.location.distanceSquared(damageLocation) > ANCHOR_MAX_DISTANCE_SQUARED) {
                    continue;
                }
                return Bukkit.getPlayer(activation.playerUuid);
            }
            return null;
        }
    }

    private void pruneRecentAttackers(long now) {
        recentAttackers.entrySet().removeIf(entry -> now - entry.getValue().timestamp > RECENT_ATTACK_WINDOW_MS);
    }

    private void pruneAnchorActivations(long now) {
        while (!recentAnchorActivations.isEmpty()) {
            AnchorActivation first = recentAnchorActivations.peekFirst();
            if (first == null || now - first.timestamp <= ANCHOR_ATTRIBUTION_WINDOW_MS) {
                break;
            }
            recentAnchorActivations.pollFirst();
        }
    }

    private Player resolveRecentCrystalPlacer(Location crystalLocation, UUID victimUuid, long now) {
        synchronized (recentCrystalPlacements) {
            pruneCrystalPlacements(now);
            Iterator<CrystalPlacement> iterator = recentCrystalPlacements.descendingIterator();
            while (iterator.hasNext()) {
                CrystalPlacement placement = iterator.next();
                if (placement.playerUuid.equals(victimUuid)) {
                    continue;
                }
                if (!sameWorld(placement.location, crystalLocation)) {
                    continue;
                }
                if (placement.location.distanceSquared(crystalLocation) > CRYSTAL_MAX_DISTANCE_SQUARED) {
                    continue;
                }
                return Bukkit.getPlayer(placement.playerUuid);
            }
            return null;
        }
    }

    private void pruneCrystalPlacements(long now) {
        while (!recentCrystalPlacements.isEmpty()) {
            CrystalPlacement first = recentCrystalPlacements.peekFirst();
            if (first == null || now - first.timestamp <= CRYSTAL_ATTRIBUTION_WINDOW_MS) {
                break;
            }
            recentCrystalPlacements.pollFirst();
        }
    }

    private <T> void trimOldest(Deque<T> entries, int maxSize) {
        while (entries.size() > maxSize) {
            entries.pollFirst();
        }
    }

    private boolean isValidKiller(Player victim, Player candidate) {
        return candidate != null && !candidate.getUniqueId().equals(victim.getUniqueId()) && candidate.isOnline();
    }

    private boolean sameWorld(Location first, Location second) {
        return first != null && second != null && first.getWorld() != null && first.getWorld().equals(second.getWorld());
    }

    private boolean isExplosionDamage(DamageType damageType) {
        return damageType == DamageType.EXPLOSION || damageType == DamageType.PLAYER_EXPLOSION;
    }

    private boolean isAnchorDamage(DamageType damageType) {
        return damageType == DamageType.BAD_RESPAWN_POINT;
    }

    private static final class RecentAttacker {
        private final UUID attackerUuid;
        private final long timestamp;

        private RecentAttacker(UUID attackerUuid, long timestamp) {
            this.attackerUuid = attackerUuid;
            this.timestamp = timestamp;
        }
    }

    private static final class AnchorActivation {
        private final UUID playerUuid;
        private final Location location;
        private final long timestamp;

        private AnchorActivation(UUID playerUuid, Location location, long timestamp) {
            this.playerUuid = playerUuid;
            this.location = location;
            this.timestamp = timestamp;
        }
    }

    private static final class CrystalPlacement {
        private final UUID playerUuid;
        private final Location location;
        private final long timestamp;

        private CrystalPlacement(UUID playerUuid, Location location, long timestamp) {
            this.playerUuid = playerUuid;
            this.location = location;
            this.timestamp = timestamp;
        }
    }
}
