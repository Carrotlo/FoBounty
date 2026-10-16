package me.foesio.foBounty.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class SoundConfigStructureTest {
    @Test
    void publicSoundPathsResolveAsCoreConfigurationSections() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File("src/main/resources/sounds.yml"));
        for (String path : new String[]{"bounty.set", "bounty.claimed", "bounty.lost",
                "bounty.error", "gui.open", "gui.filter", "gui.search", "gui.cancel"}) {
            assertNotNull(yaml.getConfigurationSection(path), path);
            assertTrue(yaml.getBoolean(path + ".enabled"), path);
        }
        yaml.set("gui.filter.enabled", false);
        yaml.set("gui.filter.category", "PLAYERS");
        yaml.set("gui.filter.pitch", 1.35);
        assertFalse(yaml.getBoolean("gui.filter.enabled"));
        assertEquals("PLAYERS", yaml.getString("gui.filter.category"));
        assertEquals(1.35, yaml.getDouble("gui.filter.pitch"));
    }
}
