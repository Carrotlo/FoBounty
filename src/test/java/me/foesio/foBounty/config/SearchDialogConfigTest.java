package me.foesio.foBounty.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SearchDialogConfigTest {
    private YamlConfiguration yaml(String path) throws Exception {
        YamlConfiguration result = new YamlConfiguration();
        result.loadFromString(Files.readString(Path.of("src/main/resources/", path)));
        return result;
    }

    @Test
    void migratedSearchValuesReachNativeAndChatPresentation() throws Exception {
        YamlConfiguration baseline = yaml("gui-legacy/v4/search.yml");
        YamlConfiguration owner = yaml("gui-legacy/v4/search.yml");
        YamlConfiguration newest = yaml("dialogs/search.yml");
        owner.set("title", "Één speler zoeken");
        owner.set("field-label", "Naam van speler");
        owner.set("body", List.of("Speler: {current}"));
        owner.set("confirm-button", "Zoeken");
        owner.set("button-width", 130);
        YamlConfiguration migrated = PublicGuiUpgrade.migrateSearch(owner, baseline, newest, ignored -> {});
        SearchDialogConfig.Rendered rendered = SearchDialogConfig.parse(migrated).render(Map.of("current", "Foesio"));
        assertEquals("Één speler zoeken", rendered.title());
        assertEquals(List.of("Speler: Foesio"), rendered.body());
        assertEquals("Naam van speler", rendered.fieldLabel());
        assertEquals("Zoeken", rendered.confirm().label());
        assertEquals(130, rendered.confirm().width());
        assertEquals(130, rendered.cancel().width());
        assertEquals("Foesio", rendered.initialValue());
        assertTrue(rendered.chatLines().contains("Speler: Foesio"));
        assertEquals(List.of("cancel", "confirm"), rendered.buttonOrder());
    }

    @Test
    void missingOptionalDecorationStaysMissingAndStartupOnlyRepairsBadField() throws Exception {
        YamlConfiguration newest = yaml("dialogs/search.yml");
        YamlConfiguration owner = yaml("dialogs/search.yml");
        owner.set("confirm-button.icon", null);
        owner.set("confirm-button.tooltip", null);
        owner.set("body", List.of());
        SearchDialogConfig.Definition parsed = SearchDialogConfig.parse(owner);
        assertEquals("", parsed.confirm().icon());
        assertEquals("", parsed.confirm().tooltip());
        assertEquals(List.of(), parsed.body());

        owner.set("input-width", -2);
        owner.set("title", "Mijn titel");
        List<String> warnings = new ArrayList<>();
        parsed = SearchDialogConfig.withStartupFallback(owner, newest, warnings::add);
        assertEquals("Mijn titel", parsed.title());
        assertEquals(newest.getInt("input-width"), parsed.inputWidth());
        assertNull(owner.get("confirm-button.icon"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("input-width"));

        YamlConfiguration badReload = yaml("dialogs/search.yml");
        badReload.set("input-width", -2);
        assertThrows(IllegalArgumentException.class, () -> SearchDialogConfig.parse(badReload));
    }

    @Test
    void invalidLegacyButtonWidthRepairsOnlyWidth() throws Exception {
        YamlConfiguration baseline = yaml("gui-legacy/v4/search.yml");
        YamlConfiguration owner = yaml("gui-legacy/v4/search.yml");
        YamlConfiguration newest = yaml("dialogs/search.yml");
        owner.set("title", "Zoek speler Één");
        owner.set("button-width", -50);
        List<String> warnings = new ArrayList<>();
        YamlConfiguration migrated = PublicGuiUpgrade.migrateSearch(owner, baseline, newest, warnings::add);
        SearchDialogConfig.Definition parsed = SearchDialogConfig.parse(migrated);
        assertEquals("Zoek speler Één", parsed.title());
        assertEquals(newest.getInt("confirm-button.width"), parsed.confirm().width());
        assertEquals(newest.getInt("cancel-button.width"), parsed.cancel().width());
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("confirm-button.width")));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("cancel-button.width")));
    }

    @Test
    void oldChatPromptKeepsCustomTextAndIntentionalRemoval() throws Exception {
        YamlConfiguration baseline = yaml("gui-legacy/v1/message.yml");
        YamlConfiguration previous = yaml("gui-legacy/v1/message.yml");
        YamlConfiguration dialog = yaml("dialogs/search.yml");
        List<String> newestChat = dialog.getStringList("chat-lines");

        PublicGuiUpgrade.migrateLegacySearchPrompt(previous, baseline, dialog, ignored -> {});
        assertEquals(newestChat, dialog.getStringList("chat-lines"));

        previous.set("search-prompt", "Één aangepaste instructie");
        PublicGuiUpgrade.migrateLegacySearchPrompt(previous, baseline, dialog, ignored -> {});
        assertEquals(List.of("Één aangepaste instructie"), dialog.getStringList("chat-lines"));

        previous.set("search-prompt", "");
        PublicGuiUpgrade.migrateLegacySearchPrompt(previous, baseline, dialog, ignored -> {});
        assertEquals(List.of(), dialog.getStringList("chat-lines"));

        previous.set("prefix", null);
        dialog.set("chat-lines", newestChat);
        PublicGuiUpgrade.migrateLegacySearchPrompt(previous, baseline, dialog, ignored -> {});
        assertEquals(List.of(), dialog.getStringList("chat-lines"));
        assertEquals(List.of(), SearchDialogConfig.parse(dialog).chatLines());

        previous.set("search-prompt", null);
        dialog.set("chat-lines", newestChat);
        PublicGuiUpgrade.migrateLegacySearchPrompt(previous, baseline, dialog, ignored -> {});
        assertEquals(List.of(), dialog.getStringList("chat-lines"));
    }
}
