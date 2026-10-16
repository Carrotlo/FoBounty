package me.foesio.foBounty.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict, cached owner presentation for the native search dialog and chat fallback. */
public final class SearchDialogConfig {
    private final JavaPlugin plugin;
    private final Path file;
    private Definition current;
    private boolean firstLoad = true;

    public SearchDialogConfig(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        this.file = plugin.getDataFolder().toPath().resolve("dialogs/search.yml");
    }

    public boolean reload() {
        try {
            if (!Files.isRegularFile(file)) throw new IOException("dialog file is missing");
            YamlConfiguration owner = strict(Files.readString(file), file.toString());
            Definition next;
            if (firstLoad) {
                YamlConfiguration defaults = bundled(plugin);
                next = withStartupFallback(owner, defaults, problem ->
                        plugin.getLogger().warning("Search dialog startup fallback " + file + ":" + problem));
            } else {
                next = parse(owner);
            }
            current = next;
            firstLoad = false;
            return true;
        } catch (IOException | Invalid error) {
            plugin.getLogger().warning("Search dialog reload failed: " + error.getMessage()
                    + (current == null ? "; search input is unavailable" : "; previous search dialog remains active"));
            firstLoad = false;
            return false;
        }
    }

    public Definition current() {
        return current;
    }

    public static Definition withStartupFallback(YamlConfiguration owner, YamlConfiguration bundled,
                                                 java.util.function.Consumer<String> warning) throws IOException {
        parse(bundled);
        Set<String> replaced = new java.util.HashSet<>();
        while (true) {
            try {
                return parse(owner);
            } catch (Invalid error) {
                if (!bundled.contains(error.path, true) || !replaced.add(error.path)) {
                    throw new IOException(error.getMessage());
                }
                Object old = owner.get(error.path);
                Object fallback = bundled.get(error.path);
                owner.set(error.path, fallback);
                warning.accept(error.path + "=" + old + " (" + error.getMessage() + ") -> " + fallback
                        + "; installed file unchanged");
            }
        }
    }

    public static Definition parse(YamlConfiguration yaml) {
        for (String key : yaml.getKeys(false)) {
            if (!Set.of("title", "body", "field-label", "initial-value", "placeholder",
                    "confirm-button", "cancel-button", "button-order", "body-width", "input-width",
                    "max-length", "label-visible", "can-close-with-escape", "pause",
                    "cancel-keyword", "chat-lines").contains(key)) {
                throw new Invalid(key, "unknown search dialog key");
            }
        }
        String title = text(yaml, "title", true);
        List<String> body = lines(yaml, "body");
        String fieldLabel = text(yaml, "field-label", true);
        String initialValue = text(yaml, "initial-value", false);
        String placeholder = text(yaml, "placeholder", false);
        Button confirm = button(yaml, "confirm-button");
        Button cancel = button(yaml, "cancel-button");
        List<String> order = lines(yaml, "button-order");
        if (!order.equals(List.of("confirm", "cancel")) && !order.equals(List.of("cancel", "confirm"))) {
            throw new Invalid("button-order", "must contain confirm and cancel exactly once");
        }
        int bodyWidth = integer(yaml, "body-width", 1, 1024);
        int inputWidth = integer(yaml, "input-width", 1, 1024);
        int maxLength = integer(yaml, "max-length", 1, 1024);
        boolean labelVisible = bool(yaml, "label-visible");
        boolean canClose = bool(yaml, "can-close-with-escape");
        boolean pause = bool(yaml, "pause");
        String cancelKeyword = text(yaml, "cancel-keyword", true);
        if (cancelKeyword.length() > 32 || cancelKeyword.contains(" ")) {
            throw new Invalid("cancel-keyword", "must be one word of at most 32 characters");
        }
        List<String> chat = lines(yaml, "chat-lines");
        return new Definition(title, body, fieldLabel, initialValue, placeholder, confirm, cancel,
                order, bodyWidth, inputWidth, maxLength, labelVisible, canClose, pause,
                cancelKeyword, chat);
    }

    private static Button button(YamlConfiguration yaml, String root) {
        if (!yaml.isConfigurationSection(root)) throw new Invalid(root, "button section is required");
        String label = text(yaml, root + ".label", true);
        String tooltip = text(yaml, root + ".tooltip", false);
        String icon = text(yaml, root + ".icon", false);
        int width = integer(yaml, root + ".width", 1, 1024);
        return new Button(label, tooltip, icon, width);
    }

    private static String text(YamlConfiguration yaml, String path, boolean required) {
        if (!yaml.contains(path, true)) {
            if (required) throw new Invalid(path, "required text is missing");
            return "";
        }
        if (!yaml.isString(path)) throw new Invalid(path, "must be text");
        return yaml.getString(path, "");
    }

    private static List<String> lines(YamlConfiguration yaml, String path) {
        if (!yaml.contains(path, true)) return List.of();
        Object raw = yaml.get(path);
        if (!(raw instanceof List<?> values) || values.stream().anyMatch(value -> !(value instanceof String))) {
            throw new Invalid(path, "must be a list of text lines");
        }
        return values.stream().map(String.class::cast).toList();
    }

    private static int integer(YamlConfiguration yaml, String path, int min, int max) {
        if (!yaml.isInt(path)) throw new Invalid(path, "integer is required");
        int value = yaml.getInt(path);
        if (value < min || value > max) throw new Invalid(path, "must be in " + min + ".." + max);
        return value;
    }

    private static boolean bool(YamlConfiguration yaml, String path) {
        if (!yaml.isBoolean(path)) throw new Invalid(path, "boolean is required");
        return yaml.getBoolean(path);
    }

    public static YamlConfiguration bundled(JavaPlugin plugin) throws IOException {
        try (InputStream stream = plugin.getResource("dialogs/search.yml")) {
            if (stream == null) throw new IOException("Bundled dialogs/search.yml is missing");
            return strict(new String(stream.readAllBytes(), StandardCharsets.UTF_8), "bundled dialogs/search.yml");
        }
    }

    private static YamlConfiguration strict(String content, String name) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(content);
            return yaml;
        } catch (InvalidConfigurationException error) {
            throw new IOException(name + " is not parseable: " + error.getMessage(), error);
        }
    }

    public record Button(String label, String tooltip, String icon, int width) {
    }

    public record Rendered(String title, List<String> body, String fieldLabel, String initialValue,
                           String placeholder, Button confirm, Button cancel, List<String> buttonOrder,
                           int bodyWidth, int inputWidth, int maxLength, boolean labelVisible,
                           boolean canCloseWithEscape, boolean pause, String cancelKeyword,
                           List<String> chatLines) {
    }

    public record Definition(String title, List<String> body, String fieldLabel, String initialValue,
                             String placeholder, Button confirm, Button cancel, List<String> buttonOrder,
                             int bodyWidth, int inputWidth, int maxLength, boolean labelVisible,
                             boolean canCloseWithEscape, boolean pause, String cancelKeyword,
                             List<String> chatLines) {
        public Rendered render(Map<String, String> values) {
            Map<String, String> resolved = new HashMap<>(values);
            String renderedTitle = replace(title, resolved);
            List<String> renderedBody = body.stream().map(line -> replace(line, resolved)).toList();
            String renderedField = replace(fieldLabel, resolved);
            String renderedInitial = replace(initialValue, resolved);
            String renderedPlaceholder = replace(placeholder, resolved);
            Button renderedConfirm = new Button(replace(confirm.label(), resolved),
                    replace(confirm.tooltip(), resolved), confirm.icon(), confirm.width());
            Button renderedCancel = new Button(replace(cancel.label(), resolved),
                    replace(cancel.tooltip(), resolved), cancel.icon(), cancel.width());
            resolved.put("title", renderedTitle);
            resolved.put("field-label", renderedField);
            resolved.put("placeholder", renderedPlaceholder);
            resolved.put("current", values.getOrDefault("current", ""));
            resolved.put("cancel-keyword", cancelKeyword);
            List<String> renderedChat = new ArrayList<>();
            for (String line : chatLines) {
                if (line.equals("{body}")) renderedChat.addAll(renderedBody);
                else renderedChat.add(replace(line, resolved));
            }
            return new Rendered(renderedTitle, renderedBody, renderedField, renderedInitial,
                    renderedPlaceholder, renderedConfirm, renderedCancel, buttonOrder,
                    bodyWidth, inputWidth, maxLength, labelVisible, canCloseWithEscape,
                    pause, cancelKeyword, List.copyOf(renderedChat));
        }

        private static String replace(String template, Map<String, String> values) {
            String result = template;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                result = result.replace("{" + entry.getKey() + "}", Objects.toString(entry.getValue(), ""));
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
