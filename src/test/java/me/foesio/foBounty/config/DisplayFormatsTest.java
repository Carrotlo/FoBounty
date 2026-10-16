package me.foesio.foBounty.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DisplayFormatsTest {
    private YamlConfiguration defaults() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(Path.of("src/main/resources/formats.yml")));
        return yaml;
    }

    @Test
    void localeSuffixAndDatePatternReachRenderedPlaceholderValues() throws Exception {
        YamlConfiguration yaml = defaults();
        yaml.set("locale", "nl-NL");
        yaml.set("time-zone", "UTC");
        yaml.set("timestamp-pattern", "dd/MM/yyyy HH:mm");
        List<String> suffixes = new ArrayList<>(yaml.getStringList("compact.suffixes"));
        suffixes.set(0, " duizend");
        yaml.set("compact.suffixes", suffixes);
        DisplayFormats.Spec format = DisplayFormats.parse(yaml);
        assertEquals("1,50 duizend", format.money(1500));
        assertEquals("01/01/2026 00:00", format.timestamp(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()));
    }

    @Test
    void badStartupFieldUsesOnlyMatchingBundledDefaultInMemory() throws Exception {
        YamlConfiguration bundled = defaults();
        YamlConfiguration owner = defaults();
        owner.set("locale", "nl-NL");
        owner.set("timestamp-pattern", "[bad");
        List<String> warnings = new ArrayList<>();
        DisplayFormats.Spec format = DisplayFormats.startupFallback(owner, bundled, warnings::add);
        assertEquals("nl", format.locale().getLanguage());
        assertEquals("yyyy-MM-dd HH:mm", owner.getString("timestamp-pattern"));
        assertEquals(1, warnings.size());
        assertThrows(IllegalArgumentException.class, () -> {
            owner.set("compact.suffixes", List.of("K"));
            DisplayFormats.parse(owner);
        });
    }
}
