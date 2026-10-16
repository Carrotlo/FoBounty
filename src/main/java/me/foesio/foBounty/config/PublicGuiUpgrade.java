package me.foesio.foBounty.config;

import me.foesio.core.gui.publicgui.GuiDefinitionException;
import me.foesio.core.gui.publicgui.PublicGuiDefinition;
import me.foesio.core.gui.publicgui.PublicGuiSchema;
import me.foesio.core.gui.publicgui.PublicGuiStartupFallback;
import me.foesio.core.migration.FoMigrationStore;
import me.foesio.foBounty.gui.PublicBountyGuis;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One-time conversion from the released FoBounty GUI shapes to Core's public-GUI schema. */
public final class PublicGuiUpgrade {
    public static final int VERSION = 5;
    private static final List<String> FILES = List.of("guis/bounty-main.yml", "guis/history.yml", "dialogs/search.yml");
    private static final Set<String> ITEM_FIELDS = Set.of("material", "amount", "name", "lore", "custom-model-data", "glow", "flags");

    private PublicGuiUpgrade() {
    }

    public static boolean installOrUpgrade(JavaPlugin plugin, FoMigrationStore migrations,
                                           boolean freshInstall, byte[] originalMessages) {
        if (migrations.version() >= VERSION) {
            return true;
        }
        int originalVersion = migrations.version();
        final Applied[] applied = new Applied[1];
        boolean advanced = migrations.runToVersion(VERSION, () -> {
            try {
                applied[0] = prepareAndCommit(plugin, originalVersion, freshInstall, originalMessages);
                return true;
            } catch (IOException | RuntimeException error) {
                plugin.getLogger().severe("Public GUI migration remains pending: " + error.getMessage());
                return false;
            }
        });
        if (!advanced && applied[0] != null) {
            try {
                applied[0].rollback();
            } catch (IOException error) {
                plugin.getLogger().severe("Could not roll back GUI files after version-save failure: " + error.getMessage());
            }
        }
        return advanced;
    }

    private static Applied prepareAndCommit(JavaPlugin plugin, int originalVersion, boolean freshInstall,
                                            byte[] originalMessages) throws IOException {
        Path root = plugin.getDataFolder().toPath();
        Map<Path, byte[]> originals = new LinkedHashMap<>();
        Map<Path, byte[]> proposed = new LinkedHashMap<>();
        boolean anyExisting = FILES.stream().filter(relative -> relative.startsWith("guis/"))
                .anyMatch(relative -> Files.exists(root.resolve(relative)));
        if (!anyExisting && !freshInstall) {
            throw new IOException("Installed GUI files are missing on an existing installation; preserving possible owner deletion");
        }
        if (anyExisting && freshInstall) {
            throw new IOException("A fresh install already contains GUI files; inspect them before migration");
        }
        for (String relative : FILES) {
            Path target = root.resolve(relative);
            if (Files.isSymbolicLink(target) || (Files.exists(target) && !Files.isRegularFile(target))) {
                throw new IOException("Unsafe installed GUI path " + target);
            }
            byte[] original = Files.isRegularFile(target) ? Files.readAllBytes(target) : null;
            if (anyExisting && original == null && relative.startsWith("guis/")) {
                throw new IOException("One installed GUI file is missing: " + relative);
            }
            if (anyExisting && original == null) {
                continue; // Preserve an absent old dialog file; search stays unavailable.
            }
            originals.put(target, original);
            YamlConfiguration newest = bundled(plugin, relative);
            YamlConfiguration staged = newest;
            if (original != null) {
                YamlConfiguration owner = strict(new String(original, StandardCharsets.UTF_8), relative);
                String baselinePath = baselineFor(relative, owner, originalVersion);
                YamlConfiguration baseline = bundled(plugin, baselinePath + target.getFileName());
                staged = relative.startsWith("guis/")
                        ? migrate(relative, owner, baseline, newest, problem ->
                            plugin.getLogger().warning("GUI migration " + relative + ": " + problem))
                        : migrateSearch(owner, baseline, newest, problem ->
                            plugin.getLogger().warning("Search dialog migration: " + problem));
                if (relative.equals("dialogs/search.yml")) {
                    preserveLegacySearchPrompt(originalMessages, staged, plugin);
                }
            }
            validate(relative, staged);
            proposed.put(target, staged.saveToString().getBytes(StandardCharsets.UTF_8));
        }
        Applied transaction = new Applied(originals, originalVersion);
        transaction.commit(proposed);
        return transaction;
    }

    static String baselineFor(String relative, YamlConfiguration owner, int originalVersion) throws IOException {
        if (relative.startsWith("guis/")) {
            boolean newer = owner.contains("items.previous-page", true)
                    || owner.contains("items.next-page", true) || owner.contains("labels", true);
            boolean older = owner.contains("items.back", true) || owner.contains("items.next", true);
            if (newer && older) {
                throw new IOException(relative + ": mixed legacy paging layouts; cannot identify a safe baseline");
            }
            if (newer) {
                return "gui-legacy/v4/";
            }
            if (older) {
                // The released 2.0 files kept the back/next keys but supplied only
                // their slots (and likewise search/clear-search on the main menu).
                // Earlier releases bundled full item definitions for these controls.
                // Identify that released shape before comparing owner edits with defaults;
                // otherwise absent 2.0 fields look like deleted required v1 fields.
                List<String> slotOnly = relative.equals("guis/bounty-main.yml")
                        ? List.of("items.search", "items.clear-search", "items.back", "items.next")
                        : List.of("items.back", "items.next", "items.back-to-bounties");
                long absentMaterials = slotOnly.stream()
                        .filter(path -> owner.contains(path + ".slot", true)
                                && !owner.contains(path + ".material", true)).count();
                if (absentMaterials >= slotOnly.size() - 1) {
                    return "gui-legacy/v2/";
                }
                return "gui-legacy/v1/";
            }
            throw new IOException(relative + ": legacy GUI shape is ambiguous after missing paging controls");
        }
        return originalVersion <= 1 ? "gui-legacy/v1/" : "gui-legacy/v4/";
    }

    /** Pure transform: newest defaults plus valid owner edits. Invalid copied fields use newest defaults. */
    public static YamlConfiguration migrate(String relative, YamlConfiguration owner,
                                             YamlConfiguration baseline, YamlConfiguration newest,
                                             java.util.function.Consumer<String> warning) throws IOException {
        YamlConfiguration next = strict(newest.saveToString(), relative + " bundled");
        Map<String, String> fields = mappings(relative);
        for (Map.Entry<String, String> mapping : fields.entrySet()) {
            String oldPath = mapping.getKey();
            String newPath = mapping.getValue();
            boolean knownOld = baseline.contains(oldPath, true);
            // The 2.0 resource left some controls at `slot` only, while its
            // legacy reader still accepted owner-supplied item appearance.
            // Keep those explicit edits; do not invent absent bundled values.
            int lastDot = oldPath.lastIndexOf('.');
            boolean ownerAddedItemField = lastDot > 0 && oldPath.startsWith("items.")
                    && ITEM_FIELDS.contains(oldPath.substring(lastDot + 1))
                    && baseline.contains(oldPath.substring(0, lastDot) + ".slot", true)
                    && owner.contains(oldPath, true);
            if (!knownOld && !ownerAddedItemField) continue;
            if ((oldPath.startsWith("filler.") && !owner.contains("filler", true))
                    || (oldPath.startsWith("empty-content.") && !owner.contains("empty-content", true))) {
                continue; // A removed optional legacy item has no required child material.
            }
            boolean ownerHas = owner.contains(oldPath, true);
            if (oldPath.startsWith("items.") && !owner.contains(oldPath.substring(0, oldPath.lastIndexOf('.')), true)
                    && (newPath.startsWith("buttons.") || newPath.startsWith("decorations."))) {
                continue; // The whole optional old control was deliberately removed.
            }
            if (!ownerHas) {
                if (required(newPath)) {
                    throw new IOException(relative + ": missing previously required field " + oldPath);
                }
                next.set(newPath, null);
                continue;
            }
            Object value = owner.get(oldPath);
            if (Objects.deepEquals(value, baseline.get(oldPath))) {
                continue;
            }
            if (newPath.endsWith(".slots") && oldPath.endsWith(".slot")) {
                value = List.of(value);
            }
            if (oldPath.endsWith(".selected-line") || oldPath.endsWith(".unselected-line")) {
                if (value instanceof String line) value = line.replace("{filter}", "{option}");
            }
            if (oldPath.equals("items.filter.lore") && value instanceof List<?> lines) {
                value = lines.stream().map(line -> "{filters}".equals(line) ? "{options}" : line).toList();
            }
            if (oldPath.equals("bounty-item.contribution-line") || oldPath.equals("bounty-item.more-contributors-line")) {
                value = List.of(value);
            }
            next.set(newPath, value);
        }
        if (relative.equals("guis/bounty-main.yml")) {
            if (!owner.contains("items.info", true) && baseline.contains("items.info", true)) {
                next.set("decorations.info", null);
            }
            for (String id : List.of("filter", "refresh", "search", "clear-search", "history", "previous-page", "next-page")) {
                String legacy = switch (id) {
                    case "previous-page" -> baseline.contains("items.previous-page", true) ? "previous-page" : "back";
                    case "next-page" -> baseline.contains("items.next-page", true) ? "next-page" : "next";
                    default -> id;
                };
                if (!owner.contains("items." + legacy, true) && baseline.contains("items." + legacy, true)) {
                    disableRemovedControl(next, id);
                }
            }
        } else {
            for (String id : List.of("previous-page", "next-page", "back-to-bounties")) {
                String legacy = switch (id) {
                    case "previous-page" -> baseline.contains("items.previous-page", true) ? "previous-page" : "back";
                    case "next-page" -> baseline.contains("items.next-page", true) ? "next-page" : "next";
                    default -> id;
                };
                if (!owner.contains("items." + legacy, true) && baseline.contains("items." + legacy, true)) {
                    disableRemovedControl(next, id);
                }
            }
        }
        if (!owner.contains("filler", true) && baseline.contains("filler", true)) {
            next.set("filler", null);
            next.set("filler.enabled", false);
        }
        if (!owner.contains("empty-content", true) && baseline.contains("empty-content", true)) {
            String region = relative.equals("guis/bounty-main.yml") ? "bounties" : "history";
            next.set("entries." + region + ".empty-item", null);
        }
        Set<String> removedInvalidOptionals = new java.util.HashSet<>();
        while (true) {
            try {
                PublicGuiStartupFallback.parse(next, newest, yaml -> parseRequired(relative, yaml), replacement ->
                        warning.accept(replacement.path() + "=" + replacement.invalidValue() + " ("
                                + replacement.reason() + ") -> newest bundled default " + replacement.defaultValue()));
                break;
            } catch (GuiDefinitionException error) {
                String path = error.key();
                if (!optionalItemField(path) || newest.contains(path, true)
                        || !next.contains(path, true) || !removedInvalidOptionals.add(path)) {
                    throw new IOException(relative + ": cannot isolate invalid migrated field " + path
                            + ": " + error.getMessage(), error);
                }
                Object invalid = next.get(path);
                next.set(path, null);
                warning.accept(path + "=" + invalid + " (" + error.getMessage()
                        + ") -> newest bundled default <absent>");
            }
        }
        return next;
    }

    private static void disableRemovedControl(YamlConfiguration next, String id) {
        int slot = next.getInt("buttons." + id + ".slot");
        String action = next.getString("buttons." + id + ".action");
        next.set("buttons." + id, null);
        next.set("buttons." + id + ".slot", slot);
        next.set("buttons." + id + ".enabled", false);
        next.set("buttons." + id + ".action", action);
    }

    private static boolean optionalItemField(String path) {
        if (!(path.startsWith("buttons.") || path.startsWith("decorations.") || path.startsWith("states.")
                || path.startsWith("entries.") || path.startsWith("filler."))) return false;
        return path.endsWith(".amount") || path.endsWith(".custom-model-data")
                || path.endsWith(".glow") || path.endsWith(".item-flags")
                || path.endsWith(".name") || path.endsWith(".lore");
    }

    public static YamlConfiguration migrateSearch(YamlConfiguration owner, YamlConfiguration baseline,
                                                   YamlConfiguration newest,
                                                   java.util.function.Consumer<String> warning) throws IOException {
        YamlConfiguration next = strict(newest.saveToString(), "dialogs/search.yml bundled");
        Map<String, String> fields = Map.ofEntries(
                Map.entry("title", "title"), Map.entry("body", "body"),
                Map.entry("field-label", "field-label"), Map.entry("initial-value", "initial-value"),
                Map.entry("confirm-button", "confirm-button.label"),
                Map.entry("cancel-button", "cancel-button.label"),
                Map.entry("body-width", "body-width"), Map.entry("input-width", "input-width"),
                Map.entry("max-length", "max-length")
        );
        for (Map.Entry<String, String> field : fields.entrySet()) {
            String old = field.getKey();
            if (!baseline.contains(old, true)) continue;
            String destination = field.getValue();
            if (!owner.contains(old, true)) {
                if (old.equals("title") || old.equals("field-label")
                        || old.equals("confirm-button") || old.equals("cancel-button")) {
                    throw new IOException("dialogs/search.yml: missing previously required field " + old);
                }
                next.set(destination, null);
                continue;
            }
            Object value = owner.get(old);
            if (!Objects.deepEquals(value, baseline.get(old))) next.set(destination, value);
        }
        if (baseline.contains("button-width", true) && owner.contains("button-width", true)
                && !Objects.equals(owner.get("button-width"), baseline.get("button-width"))) {
            next.set("confirm-button.width", owner.get("button-width"));
            next.set("cancel-button.width", owner.get("button-width"));
        }
        SearchDialogConfig.withStartupFallback(next, newest, replacement -> warning.accept(replacement));
        return next;
    }

    private static void preserveLegacySearchPrompt(byte[] originalMessages, YamlConfiguration dialog,
                                                   JavaPlugin plugin) throws IOException {
        if (originalMessages == null) {
            dialog.set("chat-lines", List.of()); // The old owner message file was absent.
            return;
        }
        YamlConfiguration previous = strict(new String(originalMessages, StandardCharsets.UTF_8), "older messages.yml");
        YamlConfiguration baseline = bundled(plugin, "gui-legacy/v1/message.yml");
        migrateLegacySearchPrompt(previous, baseline, dialog,
                problem -> plugin.getLogger().warning("Search prompt migration: " + problem));
    }

    static void migrateLegacySearchPrompt(YamlConfiguration previous, YamlConfiguration baseline,
                                          YamlConfiguration dialog, java.util.function.Consumer<String> warning) {
        boolean flatLegacy = !previous.contains("tokens", true)
                && (previous.contains("prefix", true) || previous.contains("no-permission", true)
                || previous.contains("search-cancelled", true));
        if (!flatLegacy && !previous.contains("search-prompt", true)) return;
        String path = "search-prompt";
        if (!previous.contains(path, true)) {
            dialog.set("chat-lines", List.of());
            return;
        }
        Object value = previous.get(path);
        if (Objects.deepEquals(value, baseline.get(path))) return;
        if (value instanceof String prompt) {
            dialog.set("chat-lines", prompt.isEmpty() ? List.of() : List.of(prompt));
            return;
        }
        warning.accept(path + "=" + value + " is invalid; using newest bundled chat-lines");
    }

    private static boolean required(String path) {
        return path.equals("title") || path.equals("rows") || path.equals("filler.enabled")
                || path.endsWith(".slot") || path.endsWith(".slots") || path.endsWith(".material");
    }

    private static Map<String, String> mappings(String relative) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("title", "title");
        map.put("rows", "rows");
        putItem(map, "filler", "filler");
        String region = relative.equals("guis/bounty-main.yml") ? "bounties" : "history";
        map.put("content-slots", "entries." + region + ".slots");
        putItem(map, "empty-content", "entries." + region + ".empty-item");
        if (region.equals("bounties")) {
            putItem(map, "items.info", "decorations.info");
            map.put("items.info.slot", "decorations.info.slots");
            for (String id : List.of("filter", "refresh", "search", "clear-search", "history", "previous-page", "next-page")) {
                putItem(map, "items." + id, "buttons." + id);
                map.put("items." + id + ".slot", "buttons." + id + ".slot");
            }
            putItem(map, "items.back", "buttons.previous-page");
            map.put("items.back.slot", "buttons.previous-page.slot");
            putItem(map, "items.next", "buttons.next-page");
            map.put("items.next.slot", "buttons.next-page.slot");
            map.put("items.filter.selected-line", "buttons.filter.cycle.selected-format");
            map.put("items.filter.unselected-line", "buttons.filter.cycle.unselected-format");
            for (String id : List.of("OLDEST", "NEWEST", "HIGHEST", "LOWEST")) {
                map.put("items.filter.labels." + id, "states.filter-" + id.toLowerCase() + ".name");
            }
            putItem(map, "bounty-item", "states.bounty-template");
            map.put("bounty-item.contribution-line", "states.contribution-line.lore");
            map.put("bounty-item.more-contributors-line", "states.more-contributors.lore");
            for (String id : List.of("no-search", "clear-search-target", "unknown-player")) {
                map.put("labels." + id, "states.label-" + id + ".name");
            }
        } else {
            for (String id : List.of("previous-page", "next-page", "back-to-bounties")) {
                putItem(map, "items." + id, "buttons." + id);
                map.put("items." + id + ".slot", "buttons." + id + ".slot");
            }
            putItem(map, "items.back", "buttons.previous-page");
            map.put("items.back.slot", "buttons.previous-page.slot");
            putItem(map, "items.next", "buttons.next-page");
            map.put("items.next.slot", "buttons.next-page.slot");
            for (String id : List.of("claimed", "lost")) putItem(map, "entries." + id, "states." + id + "-template");
            map.put("labels.unknown-player", "states.label-unknown-player.name");
            map.put("labels.natural-death", "states.label-natural-death.name");
            map.put("labels.reasons.PVP", "states.reason-pvp.name");
            map.put("labels.reasons.NATURAL_DEATH", "states.reason-natural-death.name");
        }
        return map;
    }

    private static void putItem(Map<String, String> map, String oldRoot, String newRoot) {
        for (String field : ITEM_FIELDS) {
            map.put(oldRoot + "." + field, newRoot + "." + (field.equals("flags") ? "item-flags" : field));
        }
    }

    private static PublicGuiDefinition parseRequired(String relative, YamlConfiguration yaml) throws GuiDefinitionException {
        boolean main = relative.equals("guis/bounty-main.yml");
        PublicGuiDefinition parsed = PublicGuiSchema.parse(main ? PublicBountyGuis.MAIN : PublicBountyGuis.HISTORY,
                relative, yaml, main ? PublicBountyGuis.mainContract() : PublicBountyGuis.historyContract());
        for (String id : main ? List.of("bounty-template") : List.of("claimed-template", "lost-template")) {
            if (!parsed.states().containsKey(id)) throw new GuiDefinitionException("states." + id, "required state is missing");
        }
        if (!parsed.entries().containsKey(main ? "bounties" : "history")) {
            throw new GuiDefinitionException("entries." + (main ? "bounties" : "history"), "required entry region is missing");
        }
        List<String> buttons = main
                ? List.of("filter", "refresh", "search", "clear-search", "history", "previous-page", "next-page")
                : List.of("previous-page", "next-page", "back-to-bounties");
        for (String id : buttons) {
            if (!parsed.buttons().containsKey(id)) {
                throw new GuiDefinitionException("buttons." + id, "required plugin button is missing");
            }
        }
        Set<String> usedActions = new java.util.HashSet<>();
        parsed.buttons().values().forEach(button -> {
            if (button.action() != null) usedActions.add(button.action());
            usedActions.addAll(button.actions().values());
        });
        parsed.entries().values().forEach(entry -> usedActions.addAll(entry.actions().values()));
        for (String action : (main ? PublicBountyGuis.mainContract() : PublicBountyGuis.historyContract()).actions()) {
            if (!usedActions.contains(action)) {
                throw new GuiDefinitionException("<actions>", "registered action " + action + " has no control or entry");
            }
        }
        return parsed;
    }

    private static void validate(String relative, YamlConfiguration yaml) throws IOException {
        if (relative.equals("dialogs/search.yml")) {
            try {
                SearchDialogConfig.parse(yaml);
            } catch (IllegalArgumentException error) {
                throw new IOException(relative + ": " + error.getMessage(), error);
            }
            return;
        }
        try {
            parseRequired(relative, yaml);
        } catch (GuiDefinitionException error) {
            throw new IOException(relative + ": " + error.key() + ": " + error.getMessage(), error);
        }
    }

    private static YamlConfiguration bundled(JavaPlugin plugin, String relative) throws IOException {
        try (InputStream stream = plugin.getResource(relative)) {
            if (stream == null) throw new IOException("Missing bundled " + relative);
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return strict(content, relative);
        }
    }

    private static YamlConfiguration strict(String text, String name) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
            return yaml;
        } catch (InvalidConfigurationException error) {
            throw new IOException("Invalid " + name + ": " + error.getMessage(), error);
        }
    }

    static final class Applied {
        private final Map<Path, byte[]> originals;
        private final int version;

        Applied(Map<Path, byte[]> originals, int version) {
            this.originals = originals;
            this.version = version;
        }

        void commit(Map<Path, byte[]> proposed) throws IOException {
            List<Path> staged = new ArrayList<>();
            Map<Path, Path> stages = new LinkedHashMap<>();
            try {
                for (Map.Entry<Path, byte[]> entry : proposed.entrySet()) {
                    Path target = entry.getKey();
                    Files.createDirectories(target.getParent());
                    Path stage = Files.createTempFile(target.getParent(), ".bounty-gui-", ".staged");
                    Files.write(stage, entry.getValue());
                    stages.put(target, stage);
                    staged.add(stage);
                }
                for (Map.Entry<Path, byte[]> entry : originals.entrySet()) {
                    if (entry.getValue() == null) continue;
                    Path target = entry.getKey();
                    Path backup = target.resolveSibling(target.getFileName() + ".gui-v" + version + "-backup");
                    if (!Files.exists(backup)) {
                        Path stage = Files.createTempFile(target.getParent(), ".bounty-backup-", ".staged");
                        try {
                            Files.write(stage, entry.getValue());
                            try {
                                Files.move(stage, backup, StandardCopyOption.ATOMIC_MOVE);
                            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                                Files.move(stage, backup);
                            }
                        } finally {
                            Files.deleteIfExists(stage);
                        }
                    }
                }
                for (Map.Entry<Path, Path> entry : stages.entrySet()) replace(entry.getValue(), entry.getKey());
            } catch (IOException | RuntimeException error) {
                rollback();
                throw error;
            } finally {
                for (Path stage : staged) Files.deleteIfExists(stage);
            }
        }

        void rollback() throws IOException {
            IOException failure = null;
            for (Map.Entry<Path, byte[]> entry : originals.entrySet()) {
                try {
                    if (entry.getValue() == null) {
                        Files.deleteIfExists(entry.getKey());
                    } else {
                        Path stage = Files.createTempFile(entry.getKey().getParent(), ".bounty-rollback-", ".staged");
                        try {
                            Files.write(stage, entry.getValue());
                            replace(stage, entry.getKey());
                        } finally {
                            Files.deleteIfExists(stage);
                        }
                    }
                } catch (IOException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }

        private void replace(Path stage, Path target) throws IOException {
            try {
                Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(stage, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
