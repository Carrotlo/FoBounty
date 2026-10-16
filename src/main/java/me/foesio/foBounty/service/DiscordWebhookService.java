package me.foesio.foBounty.service;

import me.foesio.core.discord.DiscordWebhookEmbed;
import me.foesio.core.discord.DiscordWebhookField;
import me.foesio.core.discord.DiscordWebhookSettings;
import me.foesio.foBounty.config.WebhookPresentation;
import me.foesio.foBounty.util.Style;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class DiscordWebhookService {
    private final me.foesio.core.discord.DiscordWebhookService client;
    private final WebhookPresentation presentation;

    public DiscordWebhookService(JavaPlugin plugin, int oldSchemaVersion) {
        this.presentation = new WebhookPresentation(plugin, oldSchemaVersion);
        this.client = me.foesio.core.discord.DiscordWebhookService.create(
                plugin,
                () -> DiscordWebhookSettings.fromConfig(plugin.getConfig(), "")
        );
    }

    public boolean reload() {
        return presentation.reload();
    }

    public void sendBountyCreated(String setterName, String targetName, long amount, long total) {
        WebhookPresentation.Spec spec = presentation.current();
        if (spec == null) return;
        sendEmbed(
                "bounty-created",
                spec,
                List.of(
                        DiscordWebhookField.inline(spec.text("bounty-created.setter"), safe(spec, setterName)),
                        DiscordWebhookField.inline(spec.text("bounty-created.target"), safe(spec, targetName)),
                        DiscordWebhookField.inline(spec.text("bounty-created.added"), money(spec, amount)),
                        DiscordWebhookField.inline(spec.text("bounty-created.total"), money(spec, total))
                )
        );
    }

    public void sendBountyRemovedByAdmin(String adminName, String targetName, long amount) {
        WebhookPresentation.Spec spec = presentation.current();
        if (spec == null) return;
        sendEmbed(
                "bounty-removed-by-admin",
                spec,
                List.of(
                        DiscordWebhookField.inline(spec.text("bounty-removed-by-admin.admin"), safe(spec, adminName)),
                        DiscordWebhookField.inline(spec.text("bounty-removed-by-admin.target"), safe(spec, targetName)),
                        DiscordWebhookField.inline(spec.text("bounty-removed-by-admin.removed"), money(spec, amount))
                )
        );
    }

    public void sendBountyClaimed(String killerName, String targetName, long amount) {
        WebhookPresentation.Spec spec = presentation.current();
        if (spec == null) return;
        sendEmbed(
                "bounty-claimed",
                spec,
                List.of(
                        DiscordWebhookField.inline(spec.text("bounty-claimed.killer"), safe(spec, killerName)),
                        DiscordWebhookField.inline(spec.text("bounty-claimed.target"), safe(spec, targetName)),
                        DiscordWebhookField.inline(spec.text("bounty-claimed.amount"), money(spec, amount))
                )
        );
    }

    private void sendEmbed(String eventId, WebhookPresentation.Spec spec, List<DiscordWebhookField> fields) {
        DiscordWebhookEmbed embed = new DiscordWebhookEmbed(
                spec.text(eventId + ".title"),
                spec.text(eventId + ".description"),
                "",
                "",
                spec.color(),
                fields,
                "",
                "",
                Instant.now().toString()
        );
        client.sendEmbed(eventId, embed);
    }

    private String money(WebhookPresentation.Spec spec, long amount) {
        return spec.render("money", Map.of("amount", Style.formatMoney(amount)));
    }

    private String safe(WebhookPresentation.Spec spec, String value) {
        if (value == null || value.isBlank()) {
            return spec.text("unknown-name");
        }
        return value;
    }
}
