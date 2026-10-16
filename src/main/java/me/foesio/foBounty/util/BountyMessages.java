package me.foesio.foBounty.util;

import me.foesio.core.message.FoMessageService;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.Set;

/** Sends only owner-defined public feedback; missing or empty keys stay silent. */
public final class BountyMessages {
    private static final Set<String> UNUSED_LEGACY_PATHS = Set.of(
            "prefix", "search-prompt", "admin-version",
            "editor-prompt-min-price", "editor-prompt-max-price",
            "editor-prompt-announce-threshold", "editor-prompt-history-cap",
            "update-available", "update-check-failed", "update-current"
    );
    private BountyMessages() {
    }

    public static void send(FoMessageService service, CommandSender recipient, String key, String ignoredFallback) {
        send(service, recipient, key, ignoredFallback, Map.of());
    }

    public static void send(FoMessageService service, CommandSender recipient, String key,
                            String ignoredFallback, Map<String, String> values) {
        if (service.config().isList(key)) {
            if (!service.config().getStringList(key).isEmpty()) {
                service.sendList(recipient, key, List.of(), values);
            }
            return;
        }
        String template = service.config().getString(key);
        if (template != null && !template.isEmpty()) {
            service.send(recipient, key, "", values);
        }
    }

    public static void broadcast(FoMessageService service, String key, Map<String, String> values) {
        String template = service.config().getString(key);
        if (template != null && !template.isEmpty()) {
            service.broadcastConfigured(key, values);
        }
    }

    public static void validateInstalled(JavaPlugin plugin) throws IOException {
        Path file = plugin.getDataFolder().toPath().resolve("messages.yml");
        if (!Files.isRegularFile(file)) throw new IOException("messages.yml is missing");
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file));
        } catch (InvalidConfigurationException error) {
            throw new IOException("messages.yml is not parseable: " + error.getMessage(), error);
        }
        for (String path : yaml.getKeys(true)) {
            if (yaml.isConfigurationSection(path)) continue;
            if (UNUSED_LEGACY_PATHS.contains(path)) continue;
            Object value = yaml.get(path);
            if (value instanceof String) continue;
            if (!path.startsWith("tokens.") && value instanceof List<?> list
                    && list.stream().allMatch(String.class::isInstance)) continue;
            throw new IOException("messages.yml:" + path + " must be text"
                    + (path.startsWith("tokens.") ? "" : " or a list of text lines"));
        }
    }
}
