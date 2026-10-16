package me.foesio.foBounty.command;

import me.foesio.foBounty.util.BountyMessages;
import me.foesio.core.message.FoMessageService;
import me.foesio.core.sound.FoAdminSounds;
import me.foesio.core.sound.FoSoundService;
import me.foesio.foBounty.config.PluginSettings;
import me.foesio.foBounty.gui.GuiManager;
import me.foesio.foBounty.model.BountyHistoryEntry;
import me.foesio.foBounty.model.HistoryType;
import me.foesio.foBounty.service.BountyService;
import me.foesio.foBounty.service.DiscordWebhookService;
import me.foesio.foBounty.service.PlayerLookupService;
import me.foesio.foBounty.util.CooldownService;
import me.foesio.foBounty.util.Style;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BountyCommand implements TabExecutor {
    private final PluginSettings settings;
    private final FoMessageService messages;
    private final BountyService bountyService;
    private final DiscordWebhookService discordWebhookService;
    private final PlayerLookupService playerLookupService;
    private final GuiManager guiManager;
    private final CooldownService cooldownService;
    private final FoAdminSounds adminSounds;
    private final FoSoundService sounds;

    public BountyCommand(PluginSettings settings,
                         FoMessageService messages,
                         BountyService bountyService,
                         DiscordWebhookService discordWebhookService,
                         PlayerLookupService playerLookupService,
                         GuiManager guiManager,
                         CooldownService cooldownService,
                         FoAdminSounds adminSounds,
                         FoSoundService sounds) {
        this.settings = settings;
        this.messages = messages;
        this.bountyService = bountyService;
        this.discordWebhookService = discordWebhookService;
        this.playerLookupService = playerLookupService;
        this.guiManager = guiManager;
        this.cooldownService = cooldownService;
        this.adminSounds = adminSounds;
        this.sounds = sounds;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("fobounty.use")) {
            BountyMessages.send(messages, sender, "no-permission", "no-permission");
            adminSounds.updateError(sender);
            return true;
        }
        if (!(sender instanceof Player player)) {
            BountyMessages.send(messages, sender, "player-only", "player-only");
            return true;
        }

        if (cooldownService.isOnCooldown(player.getUniqueId(), "bounty-command", settings.getCommandCooldownMs())) {
            adminSounds.updateError(player);
            return true;
        }

        if (args.length == 0) {
            guiManager.openMain(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("add")) {
            return handleAdd(player, args);
        }

        if (args[0].equalsIgnoreCase("history")) {
            return handleHistory(player, args);
        }

        guiManager.openMain(player);
        adminSounds.updateError(player);
        return true;
    }

    private boolean handleAdd(Player player, String[] args) {
        if (args.length < 3) {
            guiManager.openMain(player);
            adminSounds.updateError(player);
            return true;
        }
        OfflinePlayer target = playerLookupService.findExact(args[1]);
        if (target == null || target.getName() == null) {
            Map<String, String> replacements = Map.of("player", args[1]);
            BountyMessages.send(messages, player, "unknown-player", "unknown-player", replacements);
            playAddError(player);
            return true;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            BountyMessages.send(messages, player, "self-bounty", "self-bounty");
            playAddError(player);
            return true;
        }
        long amount;
        Long parsedAmount = Style.parseAmount(args[2]);
        if (parsedAmount == null) {
            BountyMessages.send(messages, player, "invalid-number", "invalid-number");
            playAddError(player);
            return true;
        }
        amount = parsedAmount;

        if (amount < settings.getMinPrice()) {
            BountyMessages.send(messages, player, "amount-too-low", "amount-too-low", Map.of("min", Style.formatMoney(settings.getMinPrice())));
            playAddError(player);
            return true;
        }
        if (amount > settings.getMaxPrice()) {
            BountyMessages.send(messages, player, "amount-too-high", "amount-too-high", Map.of("max", Style.formatMoney(settings.getMaxPrice())));
            playAddError(player);
            return true;
        }
        if (cooldownService.isOnCooldown(player.getUniqueId(), "bounty-add", settings.getBountyAddCooldownMs())) {
            adminSounds.updateError(player);
            return true;
        }

        BountyService.AddResult result = bountyService.addBounty(player, target, amount);
        switch (result.getStatus()) {
            case NO_ECONOMY -> {
                BountyMessages.send(messages, player, "no-economy", "no-economy");
                playAddError(player);
            }
            case NO_MONEY -> {
                BountyMessages.send(messages, player, "not-enough-money", "not-enough-money");
                playAddError(player);
            }
            case FAILURE -> {
                BountyMessages.send(messages, player, "add-failed", "add-failed");
                playAddError(player);
            }
            case SUCCESS -> {
                Map<String, String> replacements = new HashMap<>();
                replacements.put("amount", Style.formatMoney(amount));
                replacements.put("player", target.getName());
                replacements.put("total", Style.formatMoney(result.getTotal()));
                BountyMessages.send(messages, player, "add-success", "add-success", replacements);
                if (settings.isBountySetAnnounceEnabled()
                        && (settings.getBountySetAnnounceMinimumAmount() < 0
                        || amount >= settings.getBountySetAnnounceMinimumAmount())) {
                    Map<String, String> announce = new HashMap<>();
                    announce.put("setter", player.getName());
                    announce.put("target", target.getName());
                    announce.put("amount", Style.formatMoney(amount));
                    announce.put("total", Style.formatMoney(result.getTotal()));
                    BountyMessages.broadcast(messages, "add-announce", announce);
                }
                discordWebhookService.sendBountyCreated(player.getName(), target.getName(), amount, result.getTotal());
            }
        }
        return true;
    }

    private void playAddError(Player player) {
        adminSounds.updateError(player);
    }

    private boolean handleHistory(Player player, String[] args) {
        if (cooldownService.isOnCooldown(player.getUniqueId(), "history", settings.getHistoryRequestCooldownMs())) {
            adminSounds.updateError(player);
            return true;
        }

        OfflinePlayer target;
        if (args.length >= 2) {
            target = playerLookupService.findExact(args[1]);
            if (target == null || target.getName() == null) {
                BountyMessages.send(messages, player, "unknown-player", "unknown-player", Map.of("player", args[1]));
                adminSounds.updateError(player);
                return true;
            }
        } else {
            target = player;
        }

        OfflinePlayer finalTarget = target;
        bountyService.fetchHistoryForPlayerAsync(player, target.getUniqueId(), 30, history -> sendHistoryInChat(player, finalTarget, history));
        return true;
    }

    private void sendHistoryInChat(Player viewer, OfflinePlayer target, List<BountyHistoryEntry> history) {
        if (!viewer.isOnline()) {
            return;
        }
        Map<String, String> headerMap = Map.of("player", safeName(target.getName(), historyLabel("unknown-player")));
        BountyMessages.send(messages, viewer, "history-header", "history-header", headerMap);
        if (history.isEmpty()) {
            BountyMessages.send(messages, viewer, "history-empty", "history-empty", headerMap);
            return;
        }
        for (BountyHistoryEntry entry : history) {
            Map<String, String> replacements = new HashMap<>();
            replacements.put("time", Style.formatTimestamp(entry.getOccurredAt()));
            replacements.put("amount", Style.formatMoney(entry.getAmount()));
            replacements.put("target", safeName(entry.getRelatedName(), historyLabel("unknown-player")));
            replacements.put("reason", historyReason(entry.getReason()));
            if (entry.getType() == HistoryType.CLAIMED) {
                BountyMessages.send(messages, viewer, "history-line-claimed", "history-line-claimed", replacements);
            } else {
                BountyMessages.send(messages, viewer, "history-line-lost", "history-line-lost", replacements);
            }
        }
    }

    private String safeName(String name, String fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        return name;
    }

    private String historyLabel(String key) {
        return messages.config().getString("history-labels." + key, "");
    }

    private String historyReason(String code) {
        if (code == null || !code.matches("[A-Z_]+")) return "";
        return historyLabel("reasons." + code);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("fobounty.use")) {
            return List.of();
        }

        List<String> candidates = new ArrayList<>();
        if (args.length == 1) {
            candidates.add("add");
            candidates.add("history");
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("history"))) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                candidates.add(online.getName());
            }
        }
        return partial(args, candidates);
    }

    private List<String> partial(String[] args, List<String> candidates) {
        if (args.length == 0 || candidates.isEmpty()) {
            return List.of();
        }
        List<String> completions = new ArrayList<>();
        StringUtil.copyPartialMatches(args[args.length - 1], candidates, completions);
        Collections.sort(completions);
        return completions;
    }
}
