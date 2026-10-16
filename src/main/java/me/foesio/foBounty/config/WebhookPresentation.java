package me.foesio.foBounty.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owner-controlled Discord embed wording, cached across failed reloads. */
public final class WebhookPresentation {
    private static final List<String> TEXT_PATHS = List.of(
            "unknown-name", "money",
            "bounty-created.title", "bounty-created.setter", "bounty-created.target",
            "bounty-created.added", "bounty-created.total",
            "bounty-removed-by-admin.title", "bounty-removed-by-admin.admin",
            "bounty-removed-by-admin.target", "bounty-removed-by-admin.removed",
            "bounty-claimed.title", "bounty-claimed.killer", "bounty-claimed.target",
            "bounty-claimed.amount"
    );
    private final JavaPlugin plugin;
    private final Path file;
    private Spec current;
    private boolean firstLoad = true;

    public WebhookPresentation(JavaPlugin plugin, int oldVersion) {
        this.plugin = plugin;
        this.file = plugin.getDataFolder().toPath().resolve("webhook.yml");
        if (oldVersion < PublicGuiUpgrade.VERSION && !Files.exists(file)) {
            plugin.saveResource("webhook.yml", false);
        }
        reload();
    }

    public boolean reload() {
        try {
            if (!Files.isRegularFile(file)) throw new IOException("webhook.yml is missing");
            YamlConfiguration owner = strict(Files.readString(file));
            Spec next;
            if (firstLoad) {
                try (InputStream stream = plugin.getResource("webhook.yml")) {
                    if (stream == null) throw new IOException("bundled webhook.yml is missing");
                    next = startupFallback(owner, strict(new String(stream.readAllBytes(), StandardCharsets.UTF_8)),
                            warning -> plugin.getLogger().warning("Webhook startup fallback " + warning));
                }
            } else {
                next = parse(owner);
            }
            current = next;
            firstLoad = false;
            return true;
        } catch (IOException | Invalid error) {
            plugin.getLogger().warning("Webhook presentation reload failed: " + error.getMessage()
                    + (current == null ? "; Discord embeds are unavailable" : "; previous embed presentation remains active"));
            firstLoad = false;
            return false;
        }
    }

    public Spec current() {
        return current;
    }

    public static Spec startupFallback(YamlConfiguration owner, YamlConfiguration bundled,
                                       java.util.function.Consumer<String> warning) {
        parse(bundled);
        Set<String> replaced = new java.util.HashSet<>();
        while (true) {
            try {
                return parse(owner);
            } catch (Invalid error) {
                if (!bundled.contains(error.path, true) || !replaced.add(error.path)) throw error;
                Object invalid = owner.get(error.path);
                owner.set(error.path, bundled.get(error.path));
                warning.accept(error.path + "=" + invalid + " (" + error.getMessage()
                        + ") -> bundled default " + bundled.get(error.path) + "; installed file unchanged");
            }
        }
    }

    public static Spec parse(YamlConfiguration yaml) {
        Set<String> allowedText = new java.util.HashSet<>(TEXT_PATHS);
        for (String event : List.of("bounty-created", "bounty-removed-by-admin", "bounty-claimed")) {
            allowedText.add(event + ".description");
        }
        for (String root : yaml.getKeys(false)) {
            if (!Set.of("color", "unknown-name", "money", "bounty-created",
                    "bounty-removed-by-admin", "bounty-claimed").contains(root)) {
                throw new Invalid(root, "unknown webhook key");
            }
        }
        for (String path : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(path) && !path.equals("color") && !allowedText.contains(path)) {
                throw new Invalid(path, "unknown webhook field");
            }
        }
        if (!yaml.isInt("color") || yaml.getInt("color") < 0 || yaml.getInt("color") > 0xFFFFFF) {
            throw new Invalid("color", "must be a 24-bit RGB integer");
        }
        Map<String, String> texts = new LinkedHashMap<>();
        for (String path : TEXT_PATHS) {
            if (!yaml.isString(path)) throw new Invalid(path, "required text is missing or invalid");
            texts.put(path, yaml.getString(path));
        }
        for (String event : List.of("bounty-created", "bounty-removed-by-admin", "bounty-claimed")) {
            String path = event + ".description";
            if (yaml.contains(path, true)) {
                if (!yaml.isString(path)) throw new Invalid(path, "must be text");
                texts.put(path, yaml.getString(path));
            }
        }
        return new Spec(yaml.getInt("color"), Map.copyOf(texts));
    }

    private static YamlConfiguration strict(String content) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(content);
            return yaml;
        } catch (InvalidConfigurationException error) {
            throw new IOException("webhook.yml is not parseable: " + error.getMessage(), error);
        }
    }

    public record Spec(int color, Map<String, String> texts) {
        public String text(String path) {
            return texts.getOrDefault(path, "");
        }

        public String render(String path, Map<String, String> values) {
            String result = text(path);
            for (Map.Entry<String, String> value : values.entrySet()) {
                result = result.replace("{" + value.getKey() + "}", value.getValue());
            }
            return result;
        }
    }

    private static final class Invalid extends IllegalArgumentException {
        private final String path;
        private Invalid(String path, String reason) {
            super(path + ": " + reason);
            this.path = path;
        }
    }
}
