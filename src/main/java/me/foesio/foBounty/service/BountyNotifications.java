package me.foesio.foBounty.service;

import me.foesio.core.message.FoMessageService;
import me.foesio.foBounty.config.PluginSettings;
import me.foesio.foBounty.util.BountyMessages;
import me.foesio.foBounty.util.Style;

import java.util.Map;

/** Shared announcement path for commands and external API actions. */
public final class BountyNotifications {
    private final PluginSettings settings;
    private final FoMessageService messages;
    private final DiscordWebhookService webhooks;

    public BountyNotifications(PluginSettings settings, FoMessageService messages, DiscordWebhookService webhooks) {
        this.settings = settings;
        this.messages = messages;
        this.webhooks = webhooks;
    }

    public void placed(String sourceName, String targetName, long amount, long total) {
        if (settings.isBountySetAnnounceEnabled()
                && (settings.getBountySetAnnounceMinimumAmount() < 0
                || amount >= settings.getBountySetAnnounceMinimumAmount())) {
            BountyMessages.broadcast(messages, "add-announce", Map.of(
                    "setter", sourceName, "target", targetName,
                    "amount", Style.formatMoney(amount), "total", Style.formatMoney(total)));
        }
        webhooks.sendBountyCreated(sourceName, targetName, amount, total);
    }

    public void removed(String actorName, String targetName, long amount) {
        webhooks.sendBountyRemovedByAdmin(actorName, targetName, amount);
    }
}
