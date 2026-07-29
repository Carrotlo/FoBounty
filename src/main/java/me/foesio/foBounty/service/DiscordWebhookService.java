package me.foesio.foBounty.service;

import me.foesio.core.discord.DiscordWebhookEmbed;
import me.foesio.core.discord.DiscordWebhookField;
import me.foesio.core.discord.DiscordWebhookSettings;
import me.foesio.foBounty.util.Style;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.List;

public final class DiscordWebhookService {
    private static final int EMBED_COLOR = 261256; // #03fc88
    private final me.foesio.core.discord.DiscordWebhookService client;

    public DiscordWebhookService(JavaPlugin plugin) {
        this.client = me.foesio.core.discord.DiscordWebhookService.create(
                plugin,
                () -> DiscordWebhookSettings.fromConfig(plugin.getConfig(), "")
        );
    }

    public void sendBountyCreated(String setterName, String targetName, long amount, long total) {
        sendEmbed(
                "bounty-created",
                "FoBounty • Bounty Created",
                List.of(
                        DiscordWebhookField.inline("Setter", safe(setterName)),
                        DiscordWebhookField.inline("Target", safe(targetName)),
                        DiscordWebhookField.inline("Added", "$" + Style.formatMoney(amount)),
                        DiscordWebhookField.inline("Total", "$" + Style.formatMoney(total))
                )
        );
    }

    public void sendBountyRemovedByAdmin(String adminName, String targetName, long amount) {
        sendEmbed(
                "bounty-removed-by-admin",
                "FoBounty • Bounty Removed By Admin",
                List.of(
                        DiscordWebhookField.inline("Admin", safe(adminName)),
                        DiscordWebhookField.inline("Target", safe(targetName)),
                        DiscordWebhookField.inline("Removed Amount", "$" + Style.formatMoney(amount))
                )
        );
    }

    public void sendBountyClaimed(String killerName, String targetName, long amount) {
        sendEmbed(
                "bounty-claimed",
                "FoBounty • Bounty Claimed",
                List.of(
                        DiscordWebhookField.inline("Killer", safe(killerName)),
                        DiscordWebhookField.inline("Target", safe(targetName)),
                        DiscordWebhookField.inline("Amount", "$" + Style.formatMoney(amount))
                )
        );
    }

    private void sendEmbed(String eventId, String title, List<DiscordWebhookField> fields) {
        DiscordWebhookEmbed embed = new DiscordWebhookEmbed(
                title,
                "",
                "",
                EMBED_COLOR,
                fields,
                "",
                "",
                Instant.now().toString()
        );
        client.sendEmbed(eventId, embed);
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown";
        }
        return value;
    }
}
