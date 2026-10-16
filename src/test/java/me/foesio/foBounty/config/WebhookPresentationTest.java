package me.foesio.foBounty.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebhookPresentationTest {
    private YamlConfiguration defaults() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(Path.of("src/main/resources/webhook.yml")));
        return yaml;
    }

    @Test
    void translatedFieldsAndMoneyTemplateReachEmbedValues() throws Exception {
        YamlConfiguration yaml = defaults();
        yaml.set("bounty-created.title", "Beloning geplaatst");
        yaml.set("bounty-created.setter", "Geplaatst door");
        yaml.set("money", "{amount} munten");
        yaml.set("unknown-name", "Onbekend");
        WebhookPresentation.Spec spec = WebhookPresentation.parse(yaml);
        assertEquals("Beloning geplaatst", spec.text("bounty-created.title"));
        assertEquals("Geplaatst door", spec.text("bounty-created.setter"));
        assertEquals("25 munten", spec.render("money", Map.of("amount", "25")));
        assertEquals("Onbekend", spec.text("unknown-name"));
    }

    @Test
    void coldStartRepairsOnlyBadColorAndReloadRejectsIt() throws Exception {
        YamlConfiguration owner = defaults();
        owner.set("color", -1);
        owner.set("bounty-claimed.title", "Één claim");
        List<String> warnings = new ArrayList<>();
        WebhookPresentation.Spec spec = WebhookPresentation.startupFallback(owner, defaults(), warnings::add);
        assertEquals("Één claim", spec.text("bounty-claimed.title"));
        assertEquals(defaults().getInt("color"), spec.color());
        assertEquals(1, warnings.size());
        assertThrows(IllegalArgumentException.class, () -> {
            YamlConfiguration badReload = defaults();
            badReload.set("color", -1);
            WebhookPresentation.parse(badReload);
        });
        YamlConfiguration typo = defaults();
        typo.set("bounty-created.settter", "typo");
        assertThrows(IllegalArgumentException.class, () -> WebhookPresentation.parse(typo));
    }
}
