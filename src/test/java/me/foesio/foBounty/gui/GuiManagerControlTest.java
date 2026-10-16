package me.foesio.foBounty.gui;

import me.foesio.core.gui.publicgui.GuiSnapshot;
import me.foesio.core.gui.publicgui.PublicGuiDefinition;
import me.foesio.core.gui.publicgui.PublicGuiFrame;
import me.foesio.core.gui.publicgui.PublicGuiPlanner;
import me.foesio.core.gui.publicgui.PublicGuiRenderInput;
import me.foesio.core.gui.publicgui.PublicGuiSchema;
import me.foesio.core.gui.publicgui.PublicGuiText;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GuiManagerControlTest {
    @Test
    void movedCoreControlMovesItsClickAndDisabledControlHasNoGhostAction() throws Exception {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File("src/main/resources/guis/bounty-main.yml"));
        yaml.set("buttons.filter.slot", 44);
        PublicGuiDefinition definition = PublicGuiSchema.parse(PublicBountyGuis.MAIN,
                "guis/bounty-main.yml", yaml, PublicBountyGuis.mainContract());
        PublicGuiRenderInput input = new PublicGuiRenderInput(Map.of(
                "own_bounty", "1", "total_earned", "2", "current", "None", "target", "bounties",
                "page", "1", "max_page", "1"
        ), Map.of("previous-page", "hidden", "next-page", "hidden", "clear-search", "hidden"),
                Map.of("filter", new PublicGuiText.CycleSelection(List.of("Oldest", "Newest", "Highest", "Lowest"), 0)),
                Map.of());
        PublicGuiFrame frame = PublicGuiPlanner.plan(new GuiSnapshot<>(1, Map.of(PublicBountyGuis.MAIN, definition)),
                PublicBountyGuis.MAIN, input);
        assertEquals("bounty.filter", frame.bindings().get(44).action());
        assertFalse(frame.bindings().containsKey(48));
        assertFalse(frame.bindings().containsKey(45));
        assertFalse(frame.bindings().containsKey(51));
        assertEquals("&7&l | {theme}» {option}", definition.buttons().get("filter").cycle().selectedFormat());

        yaml.set("buttons.filter.enabled", false);
        definition = PublicGuiSchema.parse(PublicBountyGuis.MAIN, "guis/bounty-main.yml", yaml,
                PublicBountyGuis.mainContract());
        frame = PublicGuiPlanner.plan(new GuiSnapshot<>(2, Map.of(PublicBountyGuis.MAIN, definition)),
                PublicBountyGuis.MAIN, new PublicGuiRenderInput(input.placeholders(), input.buttonStates(),
                        Map.of(), Map.of()));
        assertFalse(frame.bindings().containsKey(44));
        assertFalse(frame.visuals().containsKey(44) && !definition.fillerEnabled());
    }

    @Test
    void historyBackIsRealNavigationAndPageControlsCanBeInert() throws Exception {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File("src/main/resources/guis/history.yml"));
        PublicGuiDefinition definition = PublicGuiSchema.parse(PublicBountyGuis.HISTORY,
                "guis/history.yml", yaml, PublicBountyGuis.historyContract());
        PublicGuiFrame frame = PublicGuiPlanner.plan(new GuiSnapshot<>(1, Map.of(PublicBountyGuis.HISTORY, definition)),
                PublicBountyGuis.HISTORY, new PublicGuiRenderInput(Map.of("page", "1", "max_page", "1"),
                        Map.of("previous-page", "hidden", "next-page", "hidden"), Map.of(), Map.of()));
        assertEquals("bounty.back-to-bounties", frame.bindings().get(49).action());
        assertFalse(frame.bindings().containsKey(45));
        assertFalse(frame.bindings().containsKey(53));
    }

    @Test
    void configuredSkullSkinAndOwnerLoreSurviveDynamicBountyExpansion() throws Exception {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File("src/main/resources/guis/bounty-main.yml"));
        String skin = "https://textures.minecraft.net/texture/abcdef1234";
        yaml.set("states.bounty-template.skull-skin-url", skin);
        yaml.set("states.bounty-template.name", "Één {player}");
        yaml.set("states.bounty-template.lore", List.of("Vooraf", "{contributions}", "Na afloop"));
        PublicGuiDefinition definition = PublicGuiSchema.parse(PublicBountyGuis.MAIN,
                "guis/bounty-main.yml", yaml, PublicBountyGuis.mainContract());
        PublicGuiDefinition.FixedItem expanded = GuiManager.expandBountyTemplate(
                definition.states().get("bounty-template"), List.of("Bijdrage één"));
        assertEquals(skin, expanded.skullSkinUrl());
        assertEquals("Één {player}", expanded.name());
        assertEquals(List.of("Vooraf", "Bijdrage één", "Na afloop"), expanded.lore());

        yaml.set("states.bounty-template.lore", List.of());
        definition = PublicGuiSchema.parse(PublicBountyGuis.MAIN,
                "guis/bounty-main.yml", yaml, PublicBountyGuis.mainContract());
        assertEquals(List.of(), GuiManager.expandBountyTemplate(
                definition.states().get("bounty-template"), List.of("hidden")).lore());
    }
}
