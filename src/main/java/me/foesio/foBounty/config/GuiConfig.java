package me.foesio.foBounty.config;

import me.foesio.core.material.MaterialTypes;
import me.foesio.foBounty.util.Style;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class GuiConfig {
    private static final Set<String> MAIN_SHARED_BUTTON_TEMPLATE_PATHS = sharedButtonTemplatePaths(
            "items.search",
            "items.clear-search",
            "items.back",
            "items.next"
    );
    private static final Set<String> HISTORY_SHARED_BUTTON_TEMPLATE_PATHS = sharedButtonTemplatePaths(
            "items.back",
            "items.next",
            "items.back-to-bounties"
    );

    private final JavaPlugin plugin;
    private MainGui mainGui;
    private HistoryGui historyGui;

    public GuiConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        mainGui = loadMainGui();
        historyGui = loadHistoryGui();
    }

    public MainGui mainGui() {
        return mainGui;
    }

    public HistoryGui historyGui() {
        return historyGui;
    }

    private MainGui loadMainGui() {
        YamlConfiguration cfg = YamlFileUpdater.update(plugin, "guis/bounty-main.yml", MAIN_SHARED_BUTTON_TEMPLATE_PATHS, Map.of());
        int rows = readRows(cfg, "rows", 6);
        int size = rows * 9;
        return new MainGui(
                cfg.getString("title", "Bounty Browser"),
                rows,
                readSlots(cfg, "content-slots", size, List.of(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34)),
                readItem(cfg, "filler", size, new GuiItem(-1, Material.GRAY_STAINED_GLASS_PANE, 1, " ", List.of(), null, false, List.of())),
                readItem(cfg, "empty-content", size, new GuiItem(-1, Material.LIGHT_GRAY_STAINED_GLASS_PANE, 1, " ", List.of(), null, false, List.of())),
                readItem(cfg, "items.info", size, new GuiItem(4, Material.CLOCK, 1, "&" + Style.THEME_HEX + "Info", List.of(
                        "&" + Style.WHITE_HEX + "Use &" + Style.THEME_HEX + "/bounty add <player> <amount>",
                        "&" + Style.WHITE_HEX + "Your bounty: &" + Style.THEME_HEX + "${own_bounty}",
                        "&" + Style.WHITE_HEX + "Total earned: &" + Style.THEME_HEX + "${total_earned}"
                ), null, false, List.of())),
                readItem(cfg, "items.filter", size, new GuiItem(48, Material.HOPPER, 1, "&" + Style.THEME_HEX + "Filter", List.of("{filters}"), null, false, List.of())),
                cfg.getString("items.filter.selected-line", "&" + Style.THEME_HEX + Style.BULLET + " {filter}"),
                cfg.getString("items.filter.unselected-line", "&" + Style.WHITE_HEX + Style.BULLET + " {filter}"),
                readItem(cfg, "items.refresh", size, new GuiItem(49, Material.SKELETON_SKULL, 1, "&" + Style.THEME_HEX + "Refresh", List.of("&" + Style.WHITE_HEX + "Click to refresh this page"), null, false, List.of())),
                readButtonSlot(cfg, "items.search", size, 50),
                readButtonSlot(cfg, "items.clear-search", size, 51),
                readItem(cfg, "items.history", size, new GuiItem(47, Material.WRITABLE_BOOK, 1, "&" + Style.THEME_HEX + "History", List.of("&" + Style.WHITE_HEX + "View bounty claim/loss history"), null, false, List.of())),
                readButtonSlot(cfg, "items.back", size, 45),
                readButtonSlot(cfg, "items.next", size, 53),
                readItem(cfg, "bounty-item", size, new GuiItem(-1, Material.PLAYER_HEAD, 1, "&" + Style.THEME_HEX + "{player}", List.of(
                        "&" + Style.WHITE_HEX + "Amount: &" + Style.THEME_HEX + "${amount}",
                        "&" + Style.WHITE_HEX + "Set by:",
                        "{contributions}"
                ), null, false, List.of())),
                cfg.getString("bounty-item.contribution-line", "&" + Style.WHITE_HEX + Style.BULLET + " &" + Style.THEME_HEX + "{setter} &" + Style.WHITE_HEX + "- &" + Style.THEME_HEX + "${amount}")
        );
    }

    private HistoryGui loadHistoryGui() {
        YamlConfiguration cfg = YamlFileUpdater.update(plugin, "guis/history.yml", HISTORY_SHARED_BUTTON_TEMPLATE_PATHS, Map.of());
        int rows = readRows(cfg, "rows", 6);
        int size = rows * 9;
        return new HistoryGui(
                cfg.getString("title", "Bounty History"),
                rows,
                readSlots(cfg, "content-slots", size, List.of(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43)),
                readItem(cfg, "filler", size, new GuiItem(-1, Material.GRAY_STAINED_GLASS_PANE, 1, " ", List.of(), null, false, List.of())),
                readItem(cfg, "empty-content", size, new GuiItem(-1, Material.LIGHT_GRAY_STAINED_GLASS_PANE, 1, " ", List.of(), null, false, List.of())),
                readButtonSlot(cfg, "items.back", size, 45),
                readButtonSlot(cfg, "items.next", size, 53),
                readButtonSlot(cfg, "items.back-to-bounties", size, 49),
                readItem(cfg, "entries.claimed", size, new GuiItem(-1, Material.EMERALD, 1, "&" + Style.THEME_HEX + "Claimed ${amount}", List.of(
                        "&" + Style.WHITE_HEX + "Time: &" + Style.THEME_HEX + "{time}",
                        "&" + Style.WHITE_HEX + "Target: &" + Style.THEME_HEX + "{related}",
                        "&" + Style.WHITE_HEX + "Reason: &" + Style.THEME_HEX + "{reason}"
                ), null, false, List.of())),
                readItem(cfg, "entries.lost", size, new GuiItem(-1, Material.REDSTONE, 1, "&" + Style.THEME_HEX + "Lost ${amount}", List.of(
                        "&" + Style.WHITE_HEX + "Time: &" + Style.THEME_HEX + "{time}",
                        "&" + Style.WHITE_HEX + "By: &" + Style.THEME_HEX + "{related}",
                        "&" + Style.WHITE_HEX + "Reason: &" + Style.THEME_HEX + "{reason}"
                ), null, false, List.of()))
        );
    }

    private static Set<String> sharedButtonTemplatePaths(String... roots) {
        Set<String> paths = new LinkedHashSet<>();
        for (String root : roots) {
            paths.add(root + ".material");
            paths.add(root + ".amount");
            paths.add(root + ".name");
            paths.add(root + ".lore");
            paths.add(root + ".custom-model-data");
            paths.add(root + ".glow");
            paths.add(root + ".flags");
        }
        return Set.copyOf(paths);
    }

    private int readRows(YamlConfiguration cfg, String path, int fallback) {
        int rows = cfg.getInt(path, fallback);
        if (rows < 1 || rows > 6) {
            warn("Invalid GUI rows at " + path + ": " + rows + ". Using " + fallback + ".");
            return fallback;
        }
        return rows;
    }

    private List<Integer> readSlots(YamlConfiguration cfg, String path, int inventorySize, List<Integer> fallback) {
        List<Integer> slots = new ArrayList<>();
        for (int slot : cfg.getIntegerList(path)) {
            if (slot < 0 || slot >= inventorySize) {
                warn("Invalid GUI slot at " + path + ": " + slot + ". Slot ignored.");
                continue;
            }
            if (!slots.contains(slot)) {
                slots.add(slot);
            }
        }
        if (slots.isEmpty()) {
            return fallback.stream().filter(slot -> slot >= 0 && slot < inventorySize).toList();
        }
        return List.copyOf(slots);
    }

    private GuiItem readItem(YamlConfiguration cfg, String path, int inventorySize, GuiItem fallback) {
        ConfigurationSection section = cfg.getConfigurationSection(path);
        if (section == null) {
            return fallback;
        }

        int slot = section.getInt("slot", fallback.slot());
        if (slot < -1 || slot >= inventorySize) {
            warn("Invalid GUI slot at " + path + ".slot: " + slot + ". Using " + fallback.slot() + ".");
            slot = fallback.slot();
        }

        Material material = readMaterial(path, section.getString("material"), fallback.material());
        int amount = section.getInt("amount", fallback.amount());
        if (amount < 1 || amount > 64) {
            warn("Invalid GUI amount at " + path + ".amount: " + amount + ". Using " + fallback.amount() + ".");
            amount = fallback.amount();
        }

        Integer customModelData = fallback.customModelData();
        if (section.isInt("custom-model-data")) {
            customModelData = section.getInt("custom-model-data");
        }
        List<ItemFlag> flags = readFlags(path, section.getStringList("flags"), fallback.flags());

        return new GuiItem(
                slot,
                material,
                amount,
                section.getString("name", fallback.name()),
                section.isList("lore") ? section.getStringList("lore") : fallback.lore(),
                customModelData,
                section.getBoolean("glow", fallback.glow()),
                flags
        );
    }

    private GuiButtonSlot readButtonSlot(YamlConfiguration cfg, String path, int inventorySize, int fallbackSlot) {
        ConfigurationSection section = cfg.getConfigurationSection(path);
        int slot = section == null ? fallbackSlot : section.getInt("slot", fallbackSlot);
        if (slot < 0 || slot >= inventorySize) {
            warn("Invalid GUI slot at " + path + ".slot: " + slot + ". Using " + fallbackSlot + ".");
            slot = fallbackSlot;
        }
        return new GuiButtonSlot(slot);
    }

    private Material readMaterial(String path, String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material material = MaterialTypes.match(raw);
        if (material == null || !material.isItem()) {
            warn("Invalid GUI material at " + path + ".material: " + raw + ". Using " + fallback.name() + ".");
            return fallback;
        }
        return material;
    }

    private List<ItemFlag> readFlags(String path, List<String> rawFlags, List<ItemFlag> fallback) {
        if (rawFlags.isEmpty()) {
            return fallback;
        }
        List<ItemFlag> flags = new ArrayList<>();
        for (String raw : rawFlags) {
            try {
                flags.add(ItemFlag.valueOf(raw.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                warn("Invalid GUI item flag at " + path + ".flags: " + raw + ". Flag ignored.");
            }
        }
        return List.copyOf(flags);
    }

    private void warn(String message) {
        plugin.getLogger().warning(message);
    }

    public record MainGui(String title,
                          int rows,
                          List<Integer> contentSlots,
                          GuiItem filler,
                          GuiItem emptyContent,
                          GuiItem info,
                          GuiItem filter,
                          String filterSelectedLine,
                          String filterUnselectedLine,
                          GuiItem refresh,
                          GuiButtonSlot search,
                          GuiButtonSlot clearSearch,
                          GuiItem history,
                          GuiButtonSlot back,
                          GuiButtonSlot next,
                          GuiItem bountyItem,
                          String contributionLine) {
        public int size() {
            return rows * 9;
        }
    }

    public record HistoryGui(String title,
                             int rows,
                             List<Integer> contentSlots,
                             GuiItem filler,
                             GuiItem emptyContent,
                             GuiButtonSlot back,
                             GuiButtonSlot next,
                             GuiButtonSlot backToBounties,
                             GuiItem claimedEntry,
                             GuiItem lostEntry) {
        public int size() {
            return rows * 9;
        }
    }

    public record GuiItem(int slot,
                          Material material,
                          int amount,
                          String name,
                          List<String> lore,
                          Integer customModelData,
                          boolean glow,
                          List<ItemFlag> flags) {
    }

    public record GuiButtonSlot(int slot) {
    }
}
