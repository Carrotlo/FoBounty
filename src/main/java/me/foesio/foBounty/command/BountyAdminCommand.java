package me.foesio.foBounty.command;

import me.foesio.core.message.FoMessageService;
import me.foesio.core.reload.FoReloadResult;
import me.foesio.core.sound.FoAdminSounds;
import me.foesio.core.sound.FoSoundService;
import me.foesio.foBounty.gui.GuiManager;
import me.foesio.foBounty.service.BountyService;
import me.foesio.foBounty.service.DiscordWebhookService;
import me.foesio.foBounty.service.PlayerLookupService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class BountyAdminCommand implements TabExecutor {
    private final JavaPlugin plugin;
    private final FoMessageService messages;
    private final BountyService bountyService;
    private final DiscordWebhookService discordWebhookService;
    private final PlayerLookupService playerLookupService;
    private final GuiManager guiManager;
    private final Supplier<FoReloadResult> reloadCallback;
    private final Consumer<CommandSender> updateCheckCallback;
    private final FoAdminSounds adminSounds;
    private final FoSoundService sounds;

    public BountyAdminCommand(JavaPlugin plugin,
                              FoMessageService messages,
                              BountyService bountyService,
                              DiscordWebhookService discordWebhookService,
                              PlayerLookupService playerLookupService,
                              GuiManager guiManager,
                              Supplier<FoReloadResult> reloadCallback,
                              Consumer<CommandSender> updateCheckCallback,
                              FoAdminSounds adminSounds,
                              FoSoundService sounds) {
        this.plugin = plugin;
        this.messages = messages;
        this.bountyService = bountyService;
        this.discordWebhookService = discordWebhookService;
        this.playerLookupService = playerLookupService;
        this.guiManager = guiManager;
        this.reloadCallback = reloadCallback;
        this.updateCheckCallback = updateCheckCallback;
        this.adminSounds = adminSounds;
        this.sounds = sounds;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("fobounty.admin")) {
            messages.send(sender, "no-permission", "no-permission");
            adminSounds.updateError(sender);
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "editor" -> {
                if (!(sender instanceof Player player)) {
                    messages.send(sender, "player-only", "player-only");
                    return true;
                }
                guiManager.openAdminEditor(player);
                return true;
            }
            case "reload" -> {
                FoReloadResult result = reloadCallback.get();
                if (result.successful()) {
                    if (sender instanceof Player player) {
                        adminSounds.reload(player);
                    }
                    messages.send(sender, "reload-success", "reload-success");
                } else {
                    if (sender instanceof Player player) {
                        adminSounds.reloadError(player);
                    }
                    messages.send(sender, "reload-failed", "reload-failed", Map.of(
                            "step", result.failedStep(),
                            "error", result.errorMessage()
                    ));
                }
                return true;
            }
            case "version" -> {
                Map<String, String> replacements = new HashMap<>();
                replacements.put("version", plugin.getDescription().getVersion());
                messages.send(sender, "admin-version", "admin-version", replacements);
                updateCheckCallback.accept(sender);
                return true;
            }
            case "remove" -> {
                if (args.length < 2) {
                    messages.send(sender, "admin-remove-usage", "admin-remove-usage", Map.of("command", label));
                    adminSounds.updateError(sender);
                    return true;
                }
                OfflinePlayer target = playerLookupService.findExact(args[1]);
                if (target == null || target.getName() == null) {
                    messages.send(sender, "unknown-player", "unknown-player", Map.of("player", args[1]));
                    playAdminError(sender);
                    return true;
                }
                BountyService.RemoveResult result = bountyService.removeBounty(target.getUniqueId());
                switch (result.getStatus()) {
                    case REMOVED -> {
                        messages.send(sender, "admin-remove-success", "admin-remove-success", Map.of("player", target.getName()));
                        playAdminRemove(sender);
                        discordWebhookService.sendBountyRemovedByAdmin(sender.getName(), target.getName(), result.getAmount());
                    }
                    case NONE -> {
                        messages.send(sender, "admin-remove-none", "admin-remove-none", Map.of("player", target.getName()));
                        playAdminError(sender);
                    }
                    case FAILURE -> {
                        messages.send(sender, "admin-remove-failed", "admin-remove-failed", Map.of("player", target.getName()));
                        playAdminError(sender);
                    }
                }
                return true;
            }
            default -> {
                sendUsage(sender, label);
                return true;
            }
        }
    }

    private void sendUsage(CommandSender sender, String label) {
        messages.send(sender, "admin-usage", "admin-usage", Map.of("command", label));
        adminSounds.updateError(sender);
    }

    private void playAdminRemove(CommandSender sender) {
        if (sender instanceof Player player) {
            sounds.play(player, "bounty.admin-remove");
        }
    }

    private void playAdminError(CommandSender sender) {
        adminSounds.updateError(sender);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("fobounty.admin")) {
            return List.of();
        }

        List<String> candidates = new ArrayList<>();
        if (args.length == 1) {
            candidates.add("editor");
            candidates.add("reload");
            candidates.add("version");
            candidates.add("remove");
            return partial(args, candidates);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
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
