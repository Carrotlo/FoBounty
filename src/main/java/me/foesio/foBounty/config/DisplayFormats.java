package me.foesio.foBounty.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Cached server-wide formatting of plugin-authored numeric/date placeholder values. */
public final class DisplayFormats {
    private static volatile Spec current;
    private static volatile boolean firstLoad = true;

    private DisplayFormats() {
    }

    public static boolean reload(JavaPlugin plugin) {
        Path file = plugin.getDataFolder().toPath().resolve("formats.yml");
        try {
            if (!Files.isRegularFile(file)) {
                if (current == null) plugin.getLogger().warning("formats.yml is missing; amounts and times use raw values");
                return false;
            }
            YamlConfiguration owner = strict(Files.readString(file));
            Spec next;
            if (firstLoad) {
                try (InputStream stream = plugin.getResource("formats.yml")) {
                    if (stream == null) throw new IOException("bundled formats.yml missing");
                    YamlConfiguration bundled = strict(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                    next = startupFallback(owner, bundled, message -> plugin.getLogger().warning(message));
                }
            } else {
                next = parse(owner);
            }
            current = next;
            firstLoad = false;
            return true;
        } catch (IOException | Invalid error) {
            plugin.getLogger().warning("Invalid formats.yml: " + error.getMessage()
                    + (current == null ? "; raw amounts and times remain available" : "; previous format remains active"));
            firstLoad = false;
            return false;
        }
    }

    public static void installFreshDefault(JavaPlugin plugin, int oldSchemaVersion) {
        if (oldSchemaVersion >= PublicGuiUpgrade.VERSION) return;
        Path file = plugin.getDataFolder().toPath().resolve("formats.yml");
        if (!Files.exists(file)) plugin.saveResource("formats.yml", false);
    }

    public static String money(long value) {
        Spec snapshot = current;
        return snapshot == null ? Long.toString(value) : snapshot.money(value);
    }

    public static String timestamp(long epochMillis) {
        Spec snapshot = current;
        return snapshot == null ? Instant.ofEpochMilli(epochMillis).toString() : snapshot.timestamp(epochMillis);
    }

    public static Spec startupFallback(YamlConfiguration owner, YamlConfiguration bundled,
                                       java.util.function.Consumer<String> warning) {
        parse(bundled);
        Set<String> replaced = new HashSet<>();
        while (true) {
            try {
                return parse(owner);
            } catch (Invalid error) {
                if (!bundled.contains(error.path, true) || !replaced.add(error.path)) throw error;
                Object old = owner.get(error.path);
                Object fallback = bundled.get(error.path);
                owner.set(error.path, fallback);
                warning.accept("formats.yml:" + error.path + "=" + old + " (" + error.getMessage()
                        + ") -> bundled default " + fallback + "; installed file unchanged");
            }
        }
    }

    public static Spec parse(YamlConfiguration yaml) {
        for (String key : yaml.getKeys(false)) {
            if (!Set.of("locale", "time-zone", "timestamp-pattern", "compact").contains(key)) {
                throw new Invalid(key, "unknown key");
            }
        }
        String language = required(yaml, "locale");
        Locale locale = Locale.forLanguageTag(language);
        if (locale.getLanguage().isBlank() || !language.matches("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{2,8})*")) {
            throw new Invalid("locale", "invalid BCP 47 language tag");
        }
        String timeZone = required(yaml, "time-zone");
        ZoneId zone;
        try {
            zone = timeZone.equals("system") ? ZoneId.systemDefault() : ZoneId.of(timeZone);
        } catch (RuntimeException error) {
            throw new Invalid("time-zone", "unknown time zone");
        }
        String pattern = required(yaml, "timestamp-pattern");
        DateTimeFormatter time;
        try {
            time = DateTimeFormatter.ofPattern(pattern, locale).withZone(zone);
        } catch (RuntimeException error) {
            throw new Invalid("timestamp-pattern", "invalid date/time pattern");
        }
        String decimal = required(yaml, "compact.decimal-pattern");
        try {
            new DecimalFormat(decimal, DecimalFormatSymbols.getInstance(locale));
        } catch (RuntimeException error) {
            throw new Invalid("compact.decimal-pattern", "invalid decimal pattern");
        }
        Object raw = yaml.get("compact.suffixes");
        if (!(raw instanceof List<?> values) || values.size() != 14
                || values.stream().anyMatch(value -> !(value instanceof String))) {
            throw new Invalid("compact.suffixes", "must have 14 text suffixes");
        }
        List<String> suffixes = values.stream().map(String.class::cast).toList();
        return new Spec(locale, time, decimal, suffixes);
    }

    private static String required(YamlConfiguration yaml, String path) {
        if (!yaml.isString(path)) throw new Invalid(path, "required text is missing or invalid");
        return yaml.getString(path);
    }

    private static YamlConfiguration strict(String text) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
            return yaml;
        } catch (InvalidConfigurationException error) {
            throw new IOException(error.getMessage(), error);
        }
    }

    public record Spec(Locale locale, DateTimeFormatter time, String decimalPattern, List<String> suffixes) {
        public Spec {
            suffixes = List.copyOf(suffixes);
        }

        public String money(long amount) {
            BigDecimal scaled = BigDecimal.valueOf(amount).abs();
            if (scaled.compareTo(BigDecimal.valueOf(1000)) < 0) return Long.toString(amount);
            int index = -1;
            BigDecimal thousand = BigDecimal.valueOf(1000);
            while (scaled.compareTo(thousand) >= 0 && index < suffixes.size() - 1) {
                scaled = scaled.divide(thousand);
                index++;
            }
            if (index < suffixes.size() - 1 && scaled.compareTo(BigDecimal.valueOf(999.995)) >= 0) {
                scaled = scaled.divide(thousand);
                index++;
            }
            DecimalFormat formatter = new DecimalFormat(decimalPattern, DecimalFormatSymbols.getInstance(locale));
            formatter.setRoundingMode(RoundingMode.HALF_UP);
            return (amount < 0 ? "-" : "") + formatter.format(scaled) + suffixes.get(index);
        }

        public String timestamp(long epochMillis) {
            return time.format(Instant.ofEpochMilli(epochMillis));
        }
    }

    private static final class Invalid extends IllegalArgumentException {
        private final String path;
        private Invalid(String path, String message) {
            super(path + ": " + message);
            this.path = path;
        }
    }
}
