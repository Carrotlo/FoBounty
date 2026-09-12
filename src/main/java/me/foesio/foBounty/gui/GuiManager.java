package me.foesio.foBounty.gui;

import me.foesio.core.dialog.ConfiguredTextDialogs;
import me.foesio.core.dialog.DialogButton;
import me.foesio.core.dialog.DialogInputService;
import me.foesio.core.dialog.DialogIcons;
import me.foesio.core.dialog.TextDialogRequest;
import me.foesio.core.editor.CycleOption;
import me.foesio.core.editor.CycleOptions;
import me.foesio.core.editor.EditorItemFactory;
import me.foesio.core.gui.FoButtonStyle;
import me.foesio.core.gui.GuiButtonConfig;
import me.foesio.core.gui.GuiTitles;
import me.foesio.core.inventory.InventoryCloseSuppressor;
import me.foesio.core.message.FoMessageService;
import me.foesio.core.message.FoStyle;
import me.foesio.core.scheduler.FoScheduler;
import me.foesio.core.sound.FoEditorSounds;
import me.foesio.core.sound.FoGuiSounds;
import me.foesio.foBounty.config.GuiConfig;
import me.foesio.foBounty.config.GuiConfig.GuiItem;
import me.foesio.foBounty.config.GuiConfig.GuiButtonSlot;
import me.foesio.foBounty.config.GuiConfig.HistoryGui;
import me.foesio.foBounty.config.GuiConfig.MainGui;
import me.foesio.foBounty.config.PluginSettings;
import me.foesio.foBounty.model.ActiveBounty;
import me.foesio.foBounty.model.AdminPromptType;
import me.foesio.foBounty.model.BountyContribution;
import me.foesio.foBounty.model.BountyFilter;
import me.foesio.foBounty.model.BountyHistoryEntry;
import me.foesio.foBounty.model.BountyViewState;
import me.foesio.foBounty.model.HistoryType;
import me.foesio.foBounty.service.BountyService;
import me.foesio.foBounty.util.CooldownService;
import me.foesio.foBounty.util.Items;
import me.foesio.foBounty.util.Style;
import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class GuiManager {
    private static final int INVENTORY_SIZE = 36;
    private static final int MAX_SEARCH_TARGET_LENGTH = 32;
    private static final int MAX_RENDERED_CONTRIBUTIONS = 12;
    private static final int SLOT_ADMIN_MIN_PRICE = 10;
    private static final int SLOT_ADMIN_MAX_PRICE = 11;
    private static final int SLOT_ADMIN_CLAIM_ANNOUNCEMENTS = 12;
    private static final int SLOT_ADMIN_ANNOUNCE_THRESHOLD = 13;
    private static final int SLOT_ADMIN_NATURAL_DEATH = 14;
    private static final int SLOT_ADMIN_BLOCK_SAME_IP = 15;
    private static final int SLOT_ADMIN_BLOCK_SAME_SUBNET = 16;
    private static final int SLOT_ADMIN_HISTORY_CAP = 19;
    private static final String ADMIN_EDITOR_TITLE = "Bounty Editor";
    private static final String EDITOR_SAVED_MESSAGE = "{prefix}{good}Updated {theme}{setting}{good} to {theme}{value}{good}.";
    private static final String EDITOR_INVALID_INPUT_MESSAGE = "{prefix}{bad}Invalid value for {theme}{setting}{bad}.";

    private final JavaPlugin plugin;
    private final PluginSettings settings;
    private final FoMessageService messages;
    private GuiConfig guiConfig;
    private final BountyService bountyService;
    private final CooldownService cooldownService;
    private final FoScheduler scheduler;
    private final FoEditorSounds editorSounds;
    private final FoGuiSounds guiSounds;
    private final GuiButtonConfig buttons = GuiButtonConfig.defaults();
    private DialogInputService dialogInputs;
    private final ConfiguredTextDialogs textDialogs;
    private InventoryCloseSuppressor closeSuppressor;

    private final Map<UUID, BountyViewState> viewStates = new ConcurrentHashMap<>();
    private final Map<UUID, HistoryState> historyStates = new ConcurrentHashMap<>();
    private final Set<UUID> warnedFallbackAdmins = ConcurrentHashMap.newKeySet();

    private final NamespacedKey bountyTargetKey;

    public GuiManager(JavaPlugin plugin,
                      PluginSettings settings,
                      FoMessageService messages,
                      GuiConfig guiConfig,
                      BountyService bountyService,
                      CooldownService cooldownService,
                      DialogInputService dialogInputs,
                      ConfiguredTextDialogs textDialogs,
                      InventoryCloseSuppressor closeSuppressor,
                      FoScheduler scheduler,
                      FoEditorSounds editorSounds,
                      FoGuiSounds guiSounds) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.guiConfig = guiConfig;
        this.bountyService = bountyService;
        this.cooldownService = cooldownService;
        this.scheduler = scheduler;
        this.editorSounds = editorSounds;
        this.guiSounds = guiSounds;
        this.dialogInputs = dialogInputs;
        this.textDialogs = textDialogs;
        this.closeSuppressor = closeSuppressor;
        this.bountyTargetKey = new NamespacedKey(plugin, "bounty_target");
    }

    public void setDialogServices(DialogInputService dialogInputs, InventoryCloseSuppressor closeSuppressor) {
        this.dialogInputs = dialogInputs;
        this.closeSuppressor = closeSuppressor;
    }

    public void setGuiConfig(GuiConfig guiConfig) {
        this.guiConfig = guiConfig;
    }

    public void openMain(Player player) {
        BountyViewState state = viewStates.computeIfAbsent(player.getUniqueId(), ignored -> new BountyViewState());
        openMain(player, state, () -> guiSounds.open(player));
    }

    public void openMainWithSearch(Player player, String searchTarget) {
        BountyViewState state = viewStates.computeIfAbsent(player.getUniqueId(), ignored -> new BountyViewState());
        state.setSearchTarget(sanitizeSearchTarget(searchTarget));
        state.setPage(0);
        openMain(player, state, () -> guiSounds.search(player));
    }

    public void clearSearch(UUID viewerUuid) {
        BountyViewState state = viewStates.get(viewerUuid);
        if (state != null) {
            state.setSearchTarget(null);
            state.setPage(0);
        }
    }

    public void openAdminEditor(Player player) {
        openAdminEditor(player, true);
    }

    private void openAdminEditor(Player player, boolean playOpenSound) {
        AdminEditorHolder holder = new AdminEditorHolder();
        String title = Style.colorize("&8" + Style.smallCaps(ADMIN_EDITOR_TITLE));
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, title);
        holder.setInventory(inventory);

        ItemStack filler = Items.filler(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            inventory.setItem(i, filler);
        }

        inventory.setItem(SLOT_ADMIN_MIN_PRICE, editorButton(player, Material.GOLD_INGOT, FoStyle.THEME, "Min Price", List.of(
                "Current: $" + Style.formatMoney(settings.getMinPrice())
        ), "edit the minimum price"));
        inventory.setItem(SLOT_ADMIN_MAX_PRICE, editorButton(player, Material.EMERALD_BLOCK, FoStyle.THEME, "Max Price", List.of(
                "Current: $" + Style.formatMoney(settings.getMaxPrice())
        ), "edit the maximum price"));
        inventory.setItem(SLOT_ADMIN_NATURAL_DEATH, EditorItemFactory.toggle(player, "Natural Death Loses Bounty", settings.isNaturalDeathLosesBounty()));
        inventory.setItem(SLOT_ADMIN_CLAIM_ANNOUNCEMENTS, EditorItemFactory.toggle(player, "Claim Announcements", settings.isAnnounceEnabled()));
        inventory.setItem(SLOT_ADMIN_ANNOUNCE_THRESHOLD, editorButton(player, Material.LECTERN, FoStyle.THEME, "Announce Threshold", List.of(
                "Current: " + settings.getAnnounceMinimumAmount(),
                "-1 announces any claim."
        ), "edit the announce threshold"));
        inventory.setItem(SLOT_ADMIN_BLOCK_SAME_IP, EditorItemFactory.toggle(player, "Block Same IP Claims", settings.isBlockSameIpClaims()));
        inventory.setItem(SLOT_ADMIN_BLOCK_SAME_SUBNET, EditorItemFactory.toggle(player, "Block Same Subnet Claims", settings.isBlockSameSubnetClaims()));
        inventory.setItem(SLOT_ADMIN_HISTORY_CAP, editorButton(player, Material.BOOKSHELF, FoStyle.THEME, "History Cap", List.of(
                "Current: " + settings.getHistoryCap()
        ), "edit the history cap"));
        openForViewer(player, inventory);
        if (playOpenSound) {
            editorSounds.open(player);
        }
    }

    private ItemStack editorButton(Player player, Material material, String color, String label,
                                   List<String> information, String action) {
        return EditorItemFactory.button(player, material, color, label, information, action);
    }

    public void beginSearch(Player player) {
        BountyViewState state = viewStates.computeIfAbsent(player.getUniqueId(), ignored -> new BountyViewState());
        TextDialogRequest request = textDialogs.request("search", dialogReplacements(Map.of(
                "current", safeValue(state.getSearchTarget())
        )));
        openTextInput(player, request,
                input -> handleSearchInput(player, input),
                () -> {
                    guiSounds.cancel(player);
                    sendCancelled(player);
                },
                () -> guiSounds.open(player));
    }

    private void handleSearchInput(Player player, String input) {
        String searchTarget = sanitizeSearchTarget(input);
        if (searchTarget.equalsIgnoreCase("cancel")) {
            guiSounds.cancel(player);
            sendCancelled(player);
            return;
        }
        if (searchTarget.isBlank()) {
            Map<String, String> replacements = Map.of("player", " ");
            messages.send(player, "search-no-match", "search-no-match", replacements);
            guiSounds.error(player);
            return;
        }
        openMainWithSearch(player, searchTarget);
    }

    private void handleAdminPrompt(Player player, AdminPromptType promptType, String input) {
        if (input.equalsIgnoreCase("cancel")) {
            editorSounds.back(player);
            sendCancelled(player);
            reopenAdminEditor(player);
            return;
        }
        try {
            Long parsedValue;
            if (promptType == AdminPromptType.ANNOUNCE_THRESHOLD && input.trim().equals("-1")) {
                parsedValue = -1L;
            } else {
                parsedValue = Style.parseAmount(input);
            }
            if (parsedValue == null) {
                sendInvalidEditor(player, promptKey(promptType));
                reopenAdminEditor(player);
                return;
            }
            long parsed = parsedValue;
            String savedKey = promptKey(promptType);
            String savedValue = String.valueOf(parsed);
            switch (promptType) {
                case MIN_PRICE -> {
                    if (parsed <= 0 || parsed > settings.getMaxPrice()) {
                        sendInvalidEditor(player, "min-price");
                        reopenAdminEditor(player);
                        return;
                    }
                    settings.setMinPrice(parsed);
                    savedKey = "min-price";
                    savedValue = String.valueOf(settings.getMinPrice());
                }
                case MAX_PRICE -> {
                    if (parsed < settings.getMinPrice() || parsed > PluginSettings.MAX_SAFE_MONEY) {
                        sendInvalidEditor(player, "max-price");
                        reopenAdminEditor(player);
                        return;
                    }
                    settings.setMaxPrice(parsed);
                    savedKey = "max-price";
                    savedValue = String.valueOf(settings.getMaxPrice());
                }
                case ANNOUNCE_THRESHOLD -> {
                    if (parsed > PluginSettings.MAX_SAFE_MONEY) {
                        sendInvalidEditor(player, "announce-threshold");
                        reopenAdminEditor(player);
                        return;
                    }
                    settings.setAnnounceMinimumAmount(parsed);
                    savedKey = "announce-threshold";
                    savedValue = String.valueOf(settings.getAnnounceMinimumAmount());
                }
                case HISTORY_CAP -> {
                    if (parsed < 1 || parsed > PluginSettings.MAX_HISTORY_CAP) {
                        sendInvalidEditor(player, "history-cap");
                        reopenAdminEditor(player);
                        return;
                    }
                    settings.setHistoryCap((int) parsed);
                    savedKey = "history-cap";
                    savedValue = String.valueOf(parsed);
                }
            }
            settings.save(plugin);
            sendSavedEditor(player, savedKey, savedValue);
            editorSounds.save(player);
        } catch (NumberFormatException ignored) {
            sendInvalidEditor(player, promptKey(promptType));
        }
        reopenAdminEditor(player);
    }

    public void onMainClick(Player player, int slot, ItemStack currentItem) {
        BountyViewState state = viewStates.computeIfAbsent(player.getUniqueId(), ignored -> new BountyViewState());
        MainGui gui = guiConfig.mainGui();
        if (slot == gui.back().slot()) {
            if (cooldownService.isOnCooldown(player.getUniqueId(), "main-page", settings.getGuiRefreshCooldownMs())) {
                return;
            }
            state.setPage(Math.max(0, state.getPage() - 1));
            openMain(player, state, () -> guiSounds.previousPage(player));
            return;
        }
        if (slot == gui.next().slot()) {
            if (cooldownService.isOnCooldown(player.getUniqueId(), "main-page", settings.getGuiRefreshCooldownMs())) {
                return;
            }
            state.setPage(state.getPage() + 1);
            openMain(player, state, () -> guiSounds.nextPage(player));
            return;
        }
        if (slot == gui.refresh().slot()) {
            if (!cooldownService.isOnCooldown(player.getUniqueId(), "refresh", settings.getGuiRefreshCooldownMs())) {
                openMain(player, state, () -> guiSounds.click(player));
            }
            return;
        }
        if (slot == gui.filter().slot()) {
            if (!cooldownService.isOnCooldown(player.getUniqueId(), "filter", settings.getGuiFilterCooldownMs())) {
                state.setFilter(state.getFilter().next());
                state.setPage(0);
                openMain(player, state, () -> guiSounds.filter(player));
            }
            return;
        }
        if (slot == gui.search().slot()) {
            if (cooldownService.isOnCooldown(player.getUniqueId(), "search", settings.getGuiFilterCooldownMs())) {
                return;
            }
            beginSearch(player);
            return;
        }
        if (slot == gui.clearSearch().slot()) {
            if (state.getSearchTarget() != null && !state.getSearchTarget().isBlank()) {
                state.setSearchTarget(null);
                state.setPage(0);
                openMain(player, state, () -> guiSounds.clearSearch(player));
            }
            return;
        }
        if (slot == gui.history().slot()) {
            openHistory(player, player.getUniqueId(), player.getName());
            return;
        }

        if (currentItem == null) {
            return;
        }
        ItemMeta meta = currentItem.getItemMeta();
        if (meta == null) {
            return;
        }
        String targetRaw = meta.getPersistentDataContainer().get(bountyTargetKey, PersistentDataType.STRING);
        if (targetRaw == null) {
            return;
        }
        try {
            UUID targetUuid = UUID.fromString(targetRaw);
            ActiveBounty bounty = findBountyByUuid(targetUuid);
            if (bounty != null) {
                openHistory(player, targetUuid, bounty.getTargetName(), () -> guiSounds.select(player));
            }
        } catch (IllegalArgumentException ignored) {
            // ignored
        }
    }

    public void onAdminClick(Player player, int slot) {
        switch (slot) {
            case SLOT_ADMIN_MIN_PRICE -> beginAdminPrompt(player, AdminPromptType.MIN_PRICE);
            case SLOT_ADMIN_MAX_PRICE -> beginAdminPrompt(player, AdminPromptType.MAX_PRICE);
            case SLOT_ADMIN_NATURAL_DEATH -> {
                settings.setNaturalDeathLosesBounty(!settings.isNaturalDeathLosesBounty());
                settings.save(plugin);
                editorSounds.toggle(player, settings.isNaturalDeathLosesBounty());
                openAdminEditor(player, false);
            }
            case SLOT_ADMIN_CLAIM_ANNOUNCEMENTS -> {
                settings.setAnnounceEnabled(!settings.isAnnounceEnabled());
                settings.save(plugin);
                editorSounds.toggle(player, settings.isAnnounceEnabled());
                openAdminEditor(player, false);
            }
            case SLOT_ADMIN_ANNOUNCE_THRESHOLD -> beginAdminPrompt(player, AdminPromptType.ANNOUNCE_THRESHOLD);
            case SLOT_ADMIN_BLOCK_SAME_IP -> {
                settings.setBlockSameIpClaims(!settings.isBlockSameIpClaims());
                settings.save(plugin);
                editorSounds.toggle(player, settings.isBlockSameIpClaims());
                openAdminEditor(player, false);
            }
            case SLOT_ADMIN_BLOCK_SAME_SUBNET -> {
                settings.setBlockSameSubnetClaims(!settings.isBlockSameSubnetClaims());
                settings.save(plugin);
                editorSounds.toggle(player, settings.isBlockSameSubnetClaims());
                openAdminEditor(player, false);
            }
            case SLOT_ADMIN_HISTORY_CAP -> beginAdminPrompt(player, AdminPromptType.HISTORY_CAP);
            default -> {
            }
        }
    }

    private void beginAdminPrompt(Player player, AdminPromptType promptType) {
        TextDialogRequest prompt = createAdminPrompt(promptType);
        openTextInput(player, prompt,
                input -> handleAdminPrompt(player, promptType, input),
                () -> {
                    editorSounds.back(player);
                    sendCancelled(player);
                    reopenAdminEditor(player);
                },
                () -> editorSounds.open(player));
    }

    private TextDialogRequest createAdminPrompt(AdminPromptType promptType) {
        return switch (promptType) {
            case MIN_PRICE -> numberPrompt(
                    "Min Price",
                    String.valueOf(settings.getMinPrice()),
                    "1",
                    String.valueOf(settings.getMaxPrice())
            );
            case MAX_PRICE -> numberPrompt(
                    "Max Price",
                    String.valueOf(settings.getMaxPrice()),
                    String.valueOf(settings.getMinPrice()),
                    String.valueOf(PluginSettings.MAX_SAFE_MONEY)
            );
            case ANNOUNCE_THRESHOLD -> numberPrompt(
                    "Announce Threshold",
                    String.valueOf(settings.getAnnounceMinimumAmount()),
                    "-1",
                    String.valueOf(PluginSettings.MAX_SAFE_MONEY)
            );
            case HISTORY_CAP -> numberPrompt(
                    "History Cap",
                    String.valueOf(settings.getHistoryCap()),
                    "1",
                    String.valueOf(PluginSettings.MAX_HISTORY_CAP)
            );
        };
    }

    private TextDialogRequest numberPrompt(String title, String current, String min, String max) {
        return new TextDialogRequest(
                Style.THEME_HEX + title,
                List.of(
                        Style.MUTED_HEX + "Current value: " + Style.THEME_HEX + current,
                        Style.MUTED_HEX + "Accepted range: " + Style.THEME_HEX + min
                                + Style.MUTED_HEX + " to " + Style.THEME_HEX + max + Style.MUTED_HEX + ".",
                        Style.MUTED_HEX + "Money values may use compact suffixes from K through Td."
                ),
                Style.WHITE_HEX + "New value",
                current,
                "Example: 1k, 5M, 2.5b",
                DialogButton.save("Save", "", 100),
                DialogButton.cancel("Cancel", "", 100),
                300,
                300,
                32,
                true,
                true,
                false
        );
    }

    private String promptKey(AdminPromptType promptType) {
        return switch (promptType) {
            case MIN_PRICE -> "min-price";
            case MAX_PRICE -> "max-price";
            case ANNOUNCE_THRESHOLD -> "announce-threshold";
            case HISTORY_CAP -> "history-cap";
        };
    }

    public void onHistoryClick(Player player, int slot, HistoryHolder holder) {
        HistoryState state = historyStates.computeIfAbsent(player.getUniqueId(),
                ignored -> new HistoryState(holder.getTargetUuid(), holder.getTargetName(), 0));
        if (!holder.getTargetUuid().equals(state.targetUuid)) {
            state.targetUuid = holder.getTargetUuid();
            state.targetName = holder.getTargetName();
            state.page = 0;
        }
        HistoryGui gui = guiConfig.historyGui();
        if (slot == gui.back().slot()) {
            if (isHistoryOnCooldown(player)) {
                return;
            }
            state.page = Math.max(0, state.page - 1);
            openHistory(player, state.targetUuid, state.targetName, false, () -> guiSounds.previousPage(player));
            return;
        }
        if (slot == gui.next().slot()) {
            if (isHistoryOnCooldown(player)) {
                return;
            }
            state.page = state.page + 1;
            openHistory(player, state.targetUuid, state.targetName, false, () -> guiSounds.nextPage(player));
            return;
        }
        if (slot == gui.backToBounties().slot()) {
            BountyViewState mainState = viewStates.computeIfAbsent(player.getUniqueId(), ignored -> new BountyViewState());
            openMain(player, mainState, () -> guiSounds.back(player));
        }
    }

    public void openHistory(Player viewer, UUID targetUuid, String targetName) {
        openHistory(viewer, targetUuid, targetName, () -> guiSounds.open(viewer));
    }

    private void openHistory(Player viewer, UUID targetUuid, String targetName, Runnable afterOpen) {
        openHistory(viewer, targetUuid, targetName, true, afterOpen);
    }

    private void openHistory(Player viewer, UUID targetUuid, String targetName, boolean applyCooldown, Runnable afterOpen) {
        if (applyCooldown && isHistoryOnCooldown(viewer)) {
            return;
        }
        UUID viewerUuid = viewer.getUniqueId();
        HistoryState state = historyStates.computeIfAbsent(viewer.getUniqueId(),
                ignored -> new HistoryState(targetUuid, targetName, 0));
        if (!targetUuid.equals(state.targetUuid)) {
            state.page = 0;
        }
        state.targetUuid = targetUuid;
        state.targetName = targetName;

        bountyService.fetchHistoryForPlayerAsync(viewer, targetUuid, settings.getHistoryCap(), entries -> {
            if (!viewer.isOnline()) {
                return;
            }
            HistoryState currentState = historyStates.get(viewerUuid);
            if (currentState == null || !targetUuid.equals(currentState.targetUuid)) {
                return;
            }
            HistoryGui gui = guiConfig.historyGui();
            int pageSize = Math.max(1, Math.min(settings.getHistoryPageSize(), gui.contentSlots().size()));
            int maxPage = Math.max(0, (entries.size() - 1) / pageSize);
            currentState.page = Math.min(currentState.page, maxPage);
            int from = Math.min(entries.size(), currentState.page * pageSize);
            int to = Math.min(entries.size(), from + pageSize);
            List<BountyHistoryEntry> pageEntries = entries.subList(from, to);

            HistoryHolder holder = new HistoryHolder(targetUuid, targetName);
            String title = GuiTitles.format(gui.title());
            Inventory inventory = Bukkit.createInventory(holder, gui.size(), title);
            holder.setInventory(inventory);

            fillInventory(viewer, inventory, gui.filler());
            fillSlots(viewer, inventory, gui.contentSlots(), gui.emptyContent());

            for (int i = 0; i < pageEntries.size() && i < pageSize; i++) {
                BountyHistoryEntry entry = pageEntries.get(i);
                inventory.setItem(gui.contentSlots().get(i), createHistoryEntryItem(viewer, gui, entry));
            }

            if (currentState.page > 0) {
                placeButton(inventory, gui.back(), buttons.previousPage(viewer, currentState.page, maxPage));
            }
            if (currentState.page < maxPage) {
                placeButton(inventory, gui.next(), buttons.nextPage(viewer, currentState.page, maxPage));
            }
            placeButton(inventory, gui.backToBounties(), buttons.back(viewer));

            openForViewer(viewer, inventory);
            if (afterOpen != null) {
                afterOpen.run();
            }
        });
    }

    private void openMain(Player player, BountyViewState state) {
        openMain(player, state, null);
    }

    private void openMain(Player player, BountyViewState state, Runnable afterOpen) {
        MainGui gui = guiConfig.mainGui();
        List<ActiveBounty> sorted = bountyService.getSortedBounties(state.getFilter(), state.getSearchTarget());
        int pageSize = gui.contentSlots().size();
        int maxPage = Math.max(0, (sorted.size() - 1) / pageSize);
        state.setPage(Math.min(state.getPage(), maxPage));

        int from = Math.min(sorted.size(), state.getPage() * pageSize);
        int to = Math.min(sorted.size(), from + pageSize);
        List<ActiveBounty> pageEntries = sorted.subList(from, to);

        BountyMainHolder holder = new BountyMainHolder();
        String title = GuiTitles.format(gui.title());
        Inventory inventory = Bukkit.createInventory(holder, gui.size(), title);
        holder.setInventory(inventory);

        fillInventory(player, inventory, gui.filler());
        fillSlots(player, inventory, gui.contentSlots(), gui.emptyContent());

        long ownBounty = bountyService.getCurrentBounty(player.getUniqueId());
        long totalEarned = bountyService.getCachedTotalEarned(player.getUniqueId());
        Map<String, String> basePlaceholders = new HashMap<>();
        basePlaceholders.put("own_bounty", Style.formatMoney(ownBounty));
        basePlaceholders.put("own_bounty_raw", String.valueOf(ownBounty));
        basePlaceholders.put("total_earned", Style.formatMoney(totalEarned));
        basePlaceholders.put("total_earned_raw", String.valueOf(totalEarned));
        basePlaceholders.put("search", safeValue(state.getSearchTarget()));
        basePlaceholders.put("page", String.valueOf(state.getPage() + 1));
        basePlaceholders.put("max_page", String.valueOf(maxPage + 1));

        placeItem(player, inventory, gui.info(), basePlaceholders);
        placeFilterItem(player, inventory, gui, state.getFilter());
        placeItem(player, inventory, gui.refresh(), basePlaceholders);
        placeButton(inventory, gui.search(), buttons.search(player, state.getSearchTarget()));
        if (state.getSearchTarget() != null && !state.getSearchTarget().isBlank()) {
            placeButton(inventory, gui.clearSearch(), buttons.clearSearch(player, "bounties"));
        }
        placeItem(player, inventory, gui.history(), basePlaceholders);

        if (state.getPage() > 0) {
            placeButton(inventory, gui.back(), buttons.previousPage(player, state.getPage(), maxPage));
        }
        if (state.getPage() < maxPage) {
            placeButton(inventory, gui.next(), buttons.nextPage(player, state.getPage(), maxPage));
        }

        for (int i = 0; i < pageEntries.size(); i++) {
            ActiveBounty summary = pageEntries.get(i);
            ActiveBounty bounty = bountyService.getBountySnapshot(summary.getTargetUuid(), MAX_RENDERED_CONTRIBUTIONS + 1);
            if (bounty == null) {
                continue;
            }
            inventory.setItem(gui.contentSlots().get(i), createBountyItem(player, gui, bounty));
        }

        openForViewer(player, inventory);
        if (afterOpen != null) {
            afterOpen.run();
        }
    }

    private ActiveBounty findBountyByUuid(UUID uuid) {
        return bountyService.getBountySnapshot(uuid, 0);
    }

    private void openForViewer(Player player, Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null) {
                inventory.setItem(slot, DialogIcons.forViewer(player, item));
            }
        }
        player.openInventory(inventory);
    }

    private void fillInventory(Player viewer, Inventory inventory, GuiItem item) {
        ItemStack stack = createConfiguredItem(viewer, item, Map.of(), Map.of());
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, stack);
        }
    }

    private void fillSlots(Player viewer, Inventory inventory, List<Integer> slots, GuiItem item) {
        ItemStack stack = createConfiguredItem(viewer, item, Map.of(), Map.of());
        for (int slot : slots) {
            if (isValidSlot(inventory, slot)) {
                inventory.setItem(slot, stack);
            }
        }
    }

    private void placeItem(Player viewer, Inventory inventory, GuiItem item, Map<String, String> placeholders) {
        if (!isValidSlot(inventory, item.slot())) {
            return;
        }
        inventory.setItem(item.slot(), createConfiguredItem(viewer, item, placeholders, Map.of()));
    }

    private void placeButton(Inventory inventory, GuiButtonSlot button, ItemStack item) {
        if (!isValidSlot(inventory, button.slot())) {
            return;
        }
        inventory.setItem(button.slot(), item);
    }

    private void placeFilterItem(Player viewer, Inventory inventory, MainGui gui, BountyFilter selected) {
        if (isValidSlot(inventory, gui.filter().slot())) {
            List<CycleOption> options = List.of(BountyFilter.values()).stream()
                    .map(filter -> new CycleOption(filter.name(), filter.getLabel()))
                    .toList();
            String configuredName = messages.renderTemplateForViewer(viewer, gui.filter().name(), Map.of());
            List<String> filterInformation = CycleOptions.information(viewer, messages, selected.name(), options)
                    .stream()
                    .map(FoButtonStyle::informationLine)
                    .toList();
            List<String> configuredLore = renderLore(
                    gui.filter().lore(),
                    Map.of(),
                    Map.of("filters", filterInformation)
            );
            inventory.setItem(gui.filter().slot(), Items.make(
                    viewer,
                    gui.filter().material(),
                    configuredName,
                    configuredLore
            ));
        }
    }

    private ItemStack createBountyItem(Player viewer, MainGui gui, ActiveBounty bounty) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", safeName(bounty.getTargetName(), "Unknown"));
        placeholders.put("target", safeName(bounty.getTargetName(), "Unknown"));
        placeholders.put("amount", Style.formatMoney(bounty.getTotalAmount()));
        placeholders.put("amount_raw", String.valueOf(bounty.getTotalAmount()));
        placeholders.put("created_at", Style.formatTimestamp(bounty.getCreatedAt()));
        placeholders.put("updated_at", Style.formatTimestamp(bounty.getUpdatedAt()));

        List<String> contributions = new ArrayList<>();
        boolean hasMoreContributions = false;
        int shownContributions = 0;
        for (BountyContribution contribution : bounty.getContributions()) {
            if (shownContributions >= MAX_RENDERED_CONTRIBUTIONS) {
                hasMoreContributions = true;
                break;
            }
            Map<String, String> contributionPlaceholders = new HashMap<>();
            contributionPlaceholders.put("setter", safeName(contribution.getSetterName(), "Unknown"));
            contributionPlaceholders.put("amount", Style.formatMoney(contribution.getAmount()));
            contributionPlaceholders.put("amount_raw", String.valueOf(contribution.getAmount()));
            contributions.add(render(gui.contributionLine(), contributionPlaceholders));
            shownContributions++;
        }
        if (hasMoreContributions) {
            contributions.add("&" + Style.MUTED_HEX + Style.BULLET + " More contributors hidden");
        }

        GuiItem item = gui.bountyItem();
        ItemStack stack;
        List<String> lore = renderLore(item.lore(), placeholders, Map.of("contributions", contributions));
        if (item.material() == Material.PLAYER_HEAD) {
            stack = Items.playerHead(viewer, bounty.getTargetUuid(), render(item.name(), placeholders), lore);
        } else {
            stack = Items.make(viewer, item.material(), render(item.name(), placeholders), lore);
        }
        stack.setAmount(item.amount());
        applyItemOptions(stack, item);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(bountyTargetKey, PersistentDataType.STRING, bounty.getTargetUuid().toString());
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack createHistoryEntryItem(Player viewer, HistoryGui gui, BountyHistoryEntry entry) {
        GuiItem item = entry.getType() == HistoryType.CLAIMED ? gui.claimedEntry() : gui.lostEntry();
        String related = entry.getType() == HistoryType.CLAIMED
                ? safeName(entry.getRelatedName(), "Unknown")
                : safeName(entry.getRelatedName(), "Natural Death");
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("type", entry.getType().name().toLowerCase(Locale.ROOT));
        placeholders.put("player", safeName(entry.getPlayerName(), "Unknown"));
        placeholders.put("related", related);
        placeholders.put("target", related);
        placeholders.put("amount", Style.formatMoney(entry.getAmount()));
        placeholders.put("amount_raw", String.valueOf(entry.getAmount()));
        placeholders.put("reason", entry.getReason());
        placeholders.put("time", Style.formatTimestamp(entry.getOccurredAt()));
        return createConfiguredItem(viewer, item, placeholders, Map.of());
    }

    private ItemStack createConfiguredItem(Player viewer, GuiItem item, Map<String, String> placeholders, Map<String, List<String>> expansions) {
        ItemStack stack = Items.make(viewer, item.material(), render(item.name(), placeholders), renderLore(item.lore(), placeholders, expansions));
        stack.setAmount(item.amount());
        applyItemOptions(stack, item);
        return stack;
    }

    private void applyItemOptions(ItemStack stack, GuiItem item) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        if (item.customModelData() != null) {
            meta.setCustomModelData(item.customModelData());
        }
        for (ItemFlag flag : item.flags()) {
            meta.addItemFlags(flag);
        }
        if (item.glow()) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        stack.setItemMeta(meta);
    }

    private List<String> renderLore(List<String> lore, Map<String, String> placeholders, Map<String, List<String>> expansions) {
        List<String> rendered = new ArrayList<>();
        for (String line : lore) {
            String trimmed = line.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                String key = trimmed.substring(1, trimmed.length() - 1);
                List<String> expansion = expansions.get(key);
                if (expansion != null) {
                    rendered.addAll(expansion);
                    continue;
                }
            }
            rendered.add(render(line, placeholders));
        }
        return rendered;
    }

    private String render(String value, Map<String, String> placeholders) {
        String rendered = value == null ? "" : value;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            rendered = rendered.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return rendered;
    }

    private boolean isValidSlot(Inventory inventory, int slot) {
        return slot >= 0 && slot < inventory.getSize();
    }

    private boolean isHistoryOnCooldown(Player player) {
        return cooldownService.isOnCooldown(player.getUniqueId(), "history-gui", settings.getHistoryRequestCooldownMs());
    }

    private void sendSavedEditor(Player player, String key, String value) {
        Map<String, String> replacements = new HashMap<>();
        replacements.put("setting", key);
        replacements.put("value", value);
        messages.send(player, "editor.saved", EDITOR_SAVED_MESSAGE, replacements);
    }

    private void sendInvalidEditor(Player player, String key) {
        Map<String, String> replacements = new HashMap<>();
        replacements.put("setting", key);
        messages.send(player, "editor.invalid-input", EDITOR_INVALID_INPUT_MESSAGE, replacements);
        editorSounds.error(player);
    }

    private void reopenAdminEditor(Player player) {
        scheduler.runForPlayer(player, () -> openAdminEditor(player, false));
    }

    private void openTextInput(Player player,
                               TextDialogRequest request,
                               Consumer<String> onInput,
                               Runnable onCancel,
                               Runnable onNativeOpened) {
        warnFallbackAdminOnce(player);
        suppressNextClose(player);
        if (dialogInputs.openTextInput(player, request, onInput, onCancel) && onNativeOpened != null) {
            onNativeOpened.run();
        }
    }

    private void sendCancelled(Player player) {
        messages.send(player, "search-cancelled", "search-cancelled");
    }

    public boolean consumeSuppressedClose(Player player) {
        return closeSuppressor != null && closeSuppressor.consumeSuppressedClose(player);
    }

    public void clearViewerState(UUID viewerUuid) {
        viewStates.remove(viewerUuid);
        historyStates.remove(viewerUuid);
        warnedFallbackAdmins.remove(viewerUuid);
        cooldownService.clear(viewerUuid);
    }

    private void suppressNextClose(Player player) {
        if (closeSuppressor == null) {
            return;
        }
        closeSuppressor.suppressNextClose(player);
        closeSuppressor.clearLater(plugin, player, 5L);
    }

    private void warnFallbackAdminOnce(Player player) {
        if (dialogInputs.support().canUseNativeDialogs(player)
                || !dialogInputs.support().warnOnFallback()
                || !player.hasPermission("fobounty.admin")
                || !warnedFallbackAdmins.add(player.getUniqueId())) {
            return;
        }
        messages.send(player, "native-dialogs-unavailable", "native-dialogs-unavailable");
    }

    private Map<String, String> dialogReplacements(Map<String, String> replacements) {
        Map<String, String> rendered = new HashMap<>(messages.tokenValues());
        rendered.putAll(replacements);
        return rendered;
    }

    private String safeValue(String value) {
        return value == null ? "" : value;
    }

    private String sanitizeSearchTarget(String input) {
        String value = input == null ? "" : input.trim();
        if (value.length() <= MAX_SEARCH_TARGET_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_SEARCH_TARGET_LENGTH);
    }

    private String safeName(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private static final class HistoryState {
        private UUID targetUuid;
        private String targetName;
        private int page;

        private HistoryState(UUID targetUuid, String targetName, int page) {
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.page = page;
        }
    }
}
