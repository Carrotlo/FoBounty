package me.foesio.foBounty.config;

import me.foesio.core.gui.publicgui.PublicGuiDefinition;
import me.foesio.core.gui.publicgui.PublicGuiSchema;
import me.foesio.foBounty.gui.PublicBountyGuis;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PublicGuiUpgradeTest {
    @TempDir Path temp;
    private YamlConfiguration yaml(String relative) throws Exception {
        YamlConfiguration result = new YamlConfiguration();
        result.loadFromString(Files.readString(Path.of("src/main/resources/", relative)));
        return result;
    }

    @Test
    void choosesBaselineFromGuiShapeWhenVersionNumberLagsBehind() throws Exception {
        assertEquals("gui-legacy/v4/", PublicGuiUpgrade.baselineFor("guis/bounty-main.yml",
                yaml("gui-legacy/v4/bounty-main.yml"), 1));
        assertEquals("gui-legacy/v1/", PublicGuiUpgrade.baselineFor("guis/history.yml",
                yaml("gui-legacy/v1/history.yml"), 4));
        assertEquals("gui-legacy/v2/", PublicGuiUpgrade.baselineFor("guis/bounty-main.yml",
                yaml("gui-legacy/v2/bounty-main.yml"), 0));
        assertEquals("gui-legacy/v2/", PublicGuiUpgrade.baselineFor("guis/history.yml",
                yaml("gui-legacy/v2/history.yml"), 0));
        YamlConfiguration ambiguous = yaml("gui-legacy/v1/history.yml");
        ambiguous.set("items.back", null);
        ambiguous.set("items.next", null);
        assertThrows(java.io.IOException.class,
                () -> PublicGuiUpgrade.baselineFor("guis/history.yml", ambiguous, 1));
    }

    @Test
    void releasedTwoPointZeroFilesMigrateCustomAppearanceWithoutInventingMissingFields() throws Exception {
        YamlConfiguration oldMain = yaml("gui-legacy/v2/bounty-main.yml");
        YamlConfiguration newMain = yaml("guis/bounty-main.yml");
        oldMain.set("title", "&8MIGRATION BOUNTY");
        oldMain.set("filler.material", "BLUE_STAINED_GLASS_PANE");
        oldMain.set("items.filter.material", "GOLD_INGOT");
        oldMain.set("items.filter.name", "&aMIGRATED FILTER");
        oldMain.set("items.filter.lore", List.of("&7MIGRATION FILTER LORE", "{filters}"));
        oldMain.set("items.filter.selected-line", "&a» {filter}");
        oldMain.set("items.search.name", "&eSearch in my language");
        YamlConfiguration migratedMain = PublicGuiUpgrade.migrate("guis/bounty-main.yml", oldMain,
                yaml("gui-legacy/v2/bounty-main.yml"), newMain, ignored -> {});
        assertEquals("&8MIGRATION BOUNTY", migratedMain.getString("title"));
        assertEquals("BLUE_STAINED_GLASS_PANE", migratedMain.getString("filler.material"));
        assertEquals("GOLD_INGOT", migratedMain.getString("buttons.filter.material"));
        assertEquals("&aMIGRATED FILTER", migratedMain.getString("buttons.filter.name"));
        assertEquals(List.of("&7MIGRATION FILTER LORE", "{options}"),
                migratedMain.getStringList("buttons.filter.lore"));
        assertEquals("&a» {option}", migratedMain.getString("buttons.filter.cycle.selected-format"));
        assertEquals(newMain.getString("buttons.search.material"), migratedMain.getString("buttons.search.material"));
        assertEquals("&eSearch in my language", migratedMain.getString("buttons.search.name"));
        assertEquals(newMain.getString("buttons.previous-page.material"),
                migratedMain.getString("buttons.previous-page.material"));
        assertFalse(migratedMain.contains("content-slots", true));
        PublicGuiSchema.parse(PublicBountyGuis.MAIN, "guis/bounty-main.yml", migratedMain,
                PublicBountyGuis.mainContract());

        YamlConfiguration oldHistory = yaml("gui-legacy/v2/history.yml");
        oldHistory.set("title", "&8MIGRATION HISTORY");
        oldHistory.set("filler.material", "BLUE_STAINED_GLASS_PANE");
        oldHistory.set("entries.claimed.material", "DIAMOND");
        YamlConfiguration migratedHistory = PublicGuiUpgrade.migrate("guis/history.yml", oldHistory,
                yaml("gui-legacy/v2/history.yml"), yaml("guis/history.yml"), ignored -> {});
        assertEquals("&8MIGRATION HISTORY", migratedHistory.getString("title"));
        assertEquals("BLUE_STAINED_GLASS_PANE", migratedHistory.getString("filler.material"));
        assertEquals("DIAMOND", migratedHistory.getString("states.claimed-template.material"));
        assertFalse(migratedHistory.contains("content-slots", true));
        PublicGuiSchema.parse(PublicBountyGuis.HISTORY, "guis/history.yml", migratedHistory,
                PublicBountyGuis.historyContract());
    }

    @Test
    void oldMainKeepsCustomizedAndRemovedFieldsAndRepairsOnlyInvalidFields() throws Exception {
        YamlConfiguration owner = yaml("gui-legacy/v4/bounty-main.yml");
        YamlConfiguration baseline = yaml("gui-legacy/v4/bounty-main.yml");
        YamlConfiguration newest = yaml("guis/bounty-main.yml");
        owner.set("items.filter.name", "§bÉén filter");
        owner.set("items.filter.lore", List.of());
        owner.set("items.filter.material", "NOT_A_MATERIAL");
        owner.set("items.refresh.slot", 48); // Collides with filter; only this slot should reset.
        owner.set("items.history", null); // Deliberately remove this optional control.
        owner.set("bounty-item.contribution-line", "&a{setter}: {amount}");
        owner.set("labels.no-search", "Geen zoekopdracht");
        List<String> warnings = new ArrayList<>();
        YamlConfiguration migrated = PublicGuiUpgrade.migrate("guis/bounty-main.yml", owner,
                baseline, newest, warnings::add);
        assertEquals("§bÉén filter", migrated.getString("buttons.filter.name"));
        assertEquals(List.of(), migrated.getStringList("buttons.filter.lore"));
        assertEquals(newest.getString("buttons.filter.material"), migrated.getString("buttons.filter.material"));
        assertEquals(newest.getInt("buttons.refresh.slot"), migrated.getInt("buttons.refresh.slot"));
        assertFalse(migrated.getBoolean("buttons.history.enabled"));
        assertFalse(migrated.contains("buttons.history.name", true));
        assertFalse(migrated.contains("buttons.history.lore", true));
        assertFalse(migrated.contains("buttons.history.material", true));
        assertEquals("&a{setter}: {amount}", migrated.getStringList("states.contribution-line.lore").getFirst());
        assertEquals("Geen zoekopdracht", migrated.getString("states.label-no-search.name"));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("buttons.filter.material")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("buttons.refresh.slot")));
        assertFalse(migrated.contains("items", true));
        PublicGuiDefinition parsed = PublicGuiSchema.parse(PublicBountyGuis.MAIN,
                "guis/bounty-main.yml", migrated, PublicBountyGuis.mainContract());
        assertEquals("§bÉén filter", parsed.buttons().get("filter").item().name());
    }

    @Test
    void oldHistoryKeepsTranslatedEntryAndMovesLegacyBackControl() throws Exception {
        YamlConfiguration owner = yaml("gui-legacy/v1/history.yml");
        YamlConfiguration baseline = yaml("gui-legacy/v1/history.yml");
        YamlConfiguration newest = yaml("guis/history.yml");
        owner.set("entries.claimed.name", "Ontvangen {amount}");
        owner.set("items.back.slot", 44);
        owner.set("items.back-to-bounties", null);
        YamlConfiguration migrated = PublicGuiUpgrade.migrate("guis/history.yml", owner,
                baseline, newest, ignored -> {});
        assertEquals("Ontvangen {amount}", migrated.getString("states.claimed-template.name"));
        assertEquals(44, migrated.getInt("buttons.previous-page.slot"));
        assertEquals(newest.getString("buttons.next-page.name"), migrated.getString("buttons.next-page.name"));
        assertEquals("bounty.back-to-bounties", migrated.getString("buttons.back-to-bounties.action"));
        assertFalse(migrated.getBoolean("buttons.back-to-bounties.enabled"));
        assertFalse(migrated.contains("buttons.back-to-bounties.name", true));
        PublicGuiSchema.parse(PublicBountyGuis.HISTORY, "guis/history.yml", migrated,
                PublicBountyGuis.historyContract());
    }

    @Test
    void v1UpgradeDoesNotPromoteUnknownLaterSchemaFields() throws Exception {
        YamlConfiguration owner = yaml("gui-legacy/v1/bounty-main.yml");
        YamlConfiguration baseline = yaml("gui-legacy/v1/bounty-main.yml");
        YamlConfiguration newest = yaml("guis/bounty-main.yml");
        owner.set("bounty-item.more-contributors-line", "Injected text from a later schema");
        YamlConfiguration migrated = PublicGuiUpgrade.migrate("guis/bounty-main.yml", owner,
                baseline, newest, ignored -> {});
        assertEquals(newest.getStringList("states.more-contributors.lore"),
                migrated.getStringList("states.more-contributors.lore"));
    }

    @Test
    void removedLegacyFillerAndEmptyItemStayAbsentAfterUpgrade() throws Exception {
        for (String version : List.of("v1", "v4")) for (String relative : List.of("bounty-main.yml", "history.yml")) {
            YamlConfiguration owner = yaml("gui-legacy/" + version + "/" + relative);
            YamlConfiguration baseline = yaml("gui-legacy/" + version + "/" + relative);
            YamlConfiguration newest = yaml("guis/" + relative);
            owner.set("filler", null);
            owner.set("empty-content", null);
            YamlConfiguration migrated = PublicGuiUpgrade.migrate("guis/" + relative, owner,
                    baseline, newest, ignored -> {});
            String region = relative.equals("bounty-main.yml") ? "bounties" : "history";
            assertFalse(migrated.getBoolean("filler.enabled"));
            assertFalse(migrated.contains("filler.material", true));
            assertFalse(migrated.contains("entries." + region + ".empty-item", true));
            PublicGuiDefinition parsed = PublicGuiSchema.parse(
                    region.equals("bounties") ? PublicBountyGuis.MAIN : PublicBountyGuis.HISTORY,
                    "guis/" + relative, migrated,
                    region.equals("bounties") ? PublicBountyGuis.mainContract() : PublicBountyGuis.historyContract());
            assertNull(parsed.filler());
            assertNull(parsed.entries().get(region).emptyItem());
        }
    }

    @Test
    void invalidKnownLoreRepairsWhileUnknownAmountIsDropped() throws Exception {
        YamlConfiguration baseline = yaml("gui-legacy/v4/history.yml");
        YamlConfiguration owner = yaml("gui-legacy/v4/history.yml");
        YamlConfiguration newest = yaml("guis/history.yml");
        owner.set("items.back-to-bounties.name", "Terug naar beloningen");
        owner.set("items.back-to-bounties.lore", List.of("goed", 42));
        owner.set("entries.claimed.name", "Ontvangen {amount}");
        owner.set("entries.claimed.amount", 99);
        List<String> warnings = new ArrayList<>();
        YamlConfiguration migrated = PublicGuiUpgrade.migrate("guis/history.yml", owner,
                baseline, newest, warnings::add);
        assertEquals("Terug naar beloningen", migrated.getString("buttons.back-to-bounties.name"));
        assertEquals(newest.getStringList("buttons.back-to-bounties.lore"),
                migrated.getStringList("buttons.back-to-bounties.lore"));
        assertEquals("Ontvangen {amount}", migrated.getString("states.claimed-template.name"));
        assertFalse(migrated.contains("states.claimed-template.amount", true));
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("buttons.back-to-bounties.lore")));
        assertFalse(warnings.stream().anyMatch(warning -> warning.contains("states.claimed-template.amount")));
    }

    @Test
    void currentSchemaRemovalIsStrictAndOldMissingRequiredFieldIsNotInvented() throws Exception {
        YamlConfiguration current = yaml("guis/history.yml");
        current.set("buttons.back-to-bounties.lore", null);
        PublicGuiDefinition parsed = PublicGuiSchema.parse(PublicBountyGuis.HISTORY,
                "guis/history.yml", current, PublicBountyGuis.historyContract());
        assertEquals(List.of(), parsed.buttons().get("back-to-bounties").item().lore());
        current.set("rows", null);
        assertThrows(Exception.class, () -> PublicGuiSchema.parse(PublicBountyGuis.HISTORY,
                "guis/history.yml", current, PublicBountyGuis.historyContract()));
        YamlConfiguration owner = yaml("gui-legacy/v1/history.yml");
        owner.set("rows", null);
        assertThrows(Exception.class, () -> PublicGuiUpgrade.migrate("guis/history.yml", owner,
                yaml("gui-legacy/v1/history.yml"), yaml("guis/history.yml"), ignored -> {}));
    }

    @Test
    void backupKeepsOriginalBytesAndRollbackRestoresAllActiveFiles() throws Exception {
        Path main = temp.resolve("guis/bounty-main.yml");
        Path history = temp.resolve("guis/history.yml");
        Files.createDirectories(main.getParent());
        byte[] oldMain = "title: 'Één old'\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] oldHistory = "title: old\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(main, oldMain);
        Files.write(history, oldHistory);
        Map<Path, byte[]> originals = new LinkedHashMap<>();
        originals.put(main, oldMain);
        originals.put(history, oldHistory);
        PublicGuiUpgrade.Applied change = new PublicGuiUpgrade.Applied(originals, 4);
        change.commit(Map.of(main, "title: new-main\n".getBytes(),
                history, "title: new-history\n".getBytes()));
        assertArrayEquals(oldMain, Files.readAllBytes(main.resolveSibling("bounty-main.yml.gui-v4-backup")));
        assertArrayEquals(oldHistory, Files.readAllBytes(history.resolveSibling("history.yml.gui-v4-backup")));
        assertEquals("title: new-main\n", Files.readString(main));
        change.rollback();
        assertArrayEquals(oldMain, Files.readAllBytes(main));
        assertArrayEquals(oldHistory, Files.readAllBytes(history));
    }
}
