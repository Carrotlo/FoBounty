package me.foesio.foBounty;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FoBountyMessageMigrationTest {
    @TempDir Path temp;

    @Test
    void oldActiveMessageFileWinsOverFlatSidecarAndRollbackRestoresBoth() throws Exception {
        Path legacy = temp.resolve("message.yml");
        Path current = temp.resolve("messages.yml");
        byte[] active = "prefix: '§bActive'\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] stale = "prefix: '§cStale'\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(legacy, active);
        Files.write(current, stale);

        FoBounty.LegacyMessageSelection selected = FoBounty.selectLegacyMessages(temp, 4);
        assertTrue(selected.changed());
        assertArrayEquals(active, Files.readAllBytes(current));
        assertArrayEquals(stale, Files.readAllBytes(temp.resolve("messages.yml.gui-v4-backup")));
        Files.writeString(current, "tokens:\n  prefix: changed\n");
        FoBounty.restoreMessageFile(current, active, selected);
        assertArrayEquals(stale, Files.readAllBytes(current));
        assertArrayEquals(active, Files.readAllBytes(legacy));

        Files.writeString(current, "tokens:\n  prefix: owner-retry\n");
        assertFalse(FoBounty.selectLegacyMessages(temp, 4).changed());
        assertEquals("tokens:\n  prefix: owner-retry\n", Files.readString(current));
        assertFalse(FoBounty.selectLegacyMessages(temp, 5).changed());
    }

    @Test
    void failedUpgradeRemovesNewMessageCopyWhenNoSidecarExisted() throws Exception {
        Path legacy = temp.resolve("message.yml");
        Path current = temp.resolve("messages.yml");
        Files.writeString(legacy, "prefix: '§bActive'\n");
        FoBounty.LegacyMessageSelection selected = FoBounty.selectLegacyMessages(temp, 4);
        assertTrue(selected.changed());
        assertTrue(Files.exists(current));
        FoBounty.restoreMessageFile(current, Files.readAllBytes(current), selected);
        assertFalse(Files.exists(current));
        assertTrue(Files.exists(legacy));
    }

    @Test
    void ambiguousOldVersionWithNewFormatSidecarLeavesBothFilesUntouched() throws Exception {
        Path legacy = temp.resolve("message.yml");
        Path current = temp.resolve("messages.yml");
        byte[] active = "prefix: '§bActive'\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] nested = "tokens:\n  prefix: '§cCurrent'\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(legacy, active);
        Files.write(current, nested);

        assertThrows(java.io.IOException.class, () -> FoBounty.selectLegacyMessages(temp, 1));
        assertArrayEquals(active, Files.readAllBytes(legacy));
        assertArrayEquals(nested, Files.readAllBytes(current));
        assertFalse(Files.exists(temp.resolve("messages.yml.gui-v1-backup")));
    }

    @Test
    void releasedFlatMessagesReceiveOnlyNewDefaultsAndRetryPreservesRemovedKeys() throws Exception {
        YamlConfiguration newest = new YamlConfiguration();
        newest.loadFromString(Files.readString(Path.of("src/main/resources/messages.yml")));
        YamlConfiguration released = new YamlConfiguration();
        released.set("tokens.prefix", "§bEigen prefix");
        released.set("search-no-match", "Geen resultaat: {player}");

        assertTrue(FoBounty.seedNewMessageFields(released, newest, true));
        assertEquals("§bEigen prefix", released.getString("tokens.prefix"));
        assertEquals("Geen resultaat: {player}", released.getString("search-no-match"));
        assertEquals(newest.getString("search-empty"), released.getString("search-empty"));
        assertEquals("Natural Death", released.getString("history-labels.reasons.NATURAL_DEATH"));

        released.set("search-empty", null);
        released.set("gui-unavailable", null);
        assertFalse(FoBounty.seedNewMessageFields(released, newest, false));
        assertFalse(released.contains("search-empty", true));
        assertFalse(released.contains("gui-unavailable", true));
    }

    @Test
    void invalidLegacyPrefixUsesBundledValueWithoutDiscardingValidOwnerTokens() {
        YamlConfiguration released = new YamlConfiguration();
        released.set("prefix", 42);
        released.set("tokens.good", "§aEigen groen");
        List<String> warnings = new ArrayList<>();
        assertTrue(FoBounty.moveLegacyPrefix(released, "{theme}FoBounty", warnings::add));
        assertEquals("{theme}FoBounty", released.getString("tokens.prefix"));
        assertEquals("§aEigen groen", released.getString("tokens.good"));
        assertFalse(released.contains("prefix", true));
        assertEquals(1, warnings.size());

        YamlConfiguration malformedNested = new YamlConfiguration();
        malformedNested.set("tokens.prefix", 12);
        malformedNested.set("prefix", "§dEigen oud label");
        assertTrue(FoBounty.moveLegacyPrefix(malformedNested, "bundled", warnings::add));
        assertEquals("§dEigen oud label", malformedNested.getString("tokens.prefix"));
        assertFalse(malformedNested.contains("prefix", true));
    }
}
