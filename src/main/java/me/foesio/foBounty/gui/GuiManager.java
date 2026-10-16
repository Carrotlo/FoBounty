package me.foesio.foBounty.gui;

import me.foesio.foBounty.util.BountyMessages;
import me.foesio.core.dialog.DialogButton;
import me.foesio.core.dialog.DialogInputService;
import me.foesio.core.dialog.DialogIcons;
import me.foesio.core.dialog.TextDialogRequest;
import me.foesio.core.editor.EditorItemFactory;
import me.foesio.core.gui.publicgui.PublicGuiClick;
import me.foesio.core.gui.publicgui.PublicGuiDefinition;
import me.foesio.core.gui.publicgui.PublicGuiItemRenderer;
import me.foesio.core.gui.publicgui.PublicGuiRenderInput;
import me.foesio.core.gui.publicgui.PublicGuiService;
import me.foesio.core.gui.publicgui.PublicGuiText;
import me.foesio.core.inventory.InventoryCloseSuppressor;
import me.foesio.core.message.FoMessageService;
import me.foesio.core.message.FoStyle;
import me.foesio.core.scheduler.FoScheduler;
import me.foesio.core.sound.FoEditorSounds;
import me.foesio.core.sound.FoGuiSounds;
import me.foesio.foBounty.config.PluginSettings;
import me.foesio.foBounty.input.SearchInputService;
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
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
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
    private PublicGuiService publicGuis;
    private final BountyService bountyService;
    private final CooldownService cooldownService;
    private final FoScheduler scheduler;
    private final FoEditorSounds editorSounds;
    private final FoGuiSounds guiSounds;
    private DialogInputService dialogInputs;
    private final SearchInputService searchInputs;
    private InventoryCloseSuppressor closeSuppressor;

    private final Map<UUID, BountyViewState> viewStates = new ConcurrentHashMap<>();
    private final Map<UUID, HistoryState> historyStates = new ConcurrentHashMap<>();
    private final Set<UUID> warnedFallbackAdmins = ConcurrentHashMap.newKeySet();


    public GuiManager(JavaPlugin plugin,
                      PluginSettings settings,
                      FoMessageService messages,
                      BountyService bountyService,
                      CooldownService cooldownService,
                      DialogInputService dialogInputs,
                      SearchInputService searchInputs,
                      InventoryCloseSuppressor closeSuppressor,
                      FoScheduler scheduler,
                      FoEditorSounds editorSounds,
                      FoGuiSounds guiSounds) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.bountyService = bountyService;
        this.cooldownService = cooldownService;
        this.scheduler = scheduler;
        this.editorSounds = editorSounds;
        this.guiSounds = guiSounds;
        this.dialogInputs = dialogInputs;
        this.searchInputs = searchInputs;
        this.closeSuppressor = closeSuppressor;
    }

    public void setDialogServices(DialogInputService dialogInputs, InventoryCloseSuppressor closeSuppressor) {
        this.dialogInputs = dialogInputs;
        this.closeSuppressor = closeSuppressor;
    }

    public void setPublicGuis(PublicGuiService publicGuis) {
        this.publicGuis = publicGuis;
    }

    public void openMain(Player player) {
        BountyViewState state = viewStates.computeIfAbsent(player.getUniqueId(), ignored -> new BountyViewState());
        openMain(player, state, () -> guiSounds.open(player));
    }

    public void openMainWithSearch(Player player, String searchTarget) {
        BountyViewState state = viewStates.computeIfAbsent(player.getUniqueId(), ignored -> new BountyViewState());
        state.setSearchTarget(sanitizeSearchTarget(searchTarget));
        state.setPage(0);
        boolean noMatch = bountyService.getSortedBounties(state.getFilter(), state.getSearchTarget()).isEmpty();
        openMain(player, state, () -> {
            if (noMatch) {
                BountyMessages.send(messages, player, "search-no-match", "",
                        Map.of("player", state.getSearchTarget()));
                guiSounds.error(player);
            } else {
                guiSounds.search(player);
            }
        });
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
        if (!searchInputs.available()) {
            unavailable(player);
            return;
        }
        BountyViewState state = viewStates.computeIfAbsent(player.getUniqueId(), ignored -> new BountyViewState());
        warnFallbackAdminOnce(player);
        suppressNextClose(player);
        Map<String, String> values = uiValues();
        values.put("current", safeValue(state.getSearchTarget()));
        searchInputs.open(player, values, input -> handleSearchInput(player, input), () -> {
            guiSounds.cancel(player);
            sendCancelled(player);
        });
        guiSounds.open(player);
    }

    private void handleSearchInput(Player player, String input) {
        String searchTarget = sanitizeSearchTarget(input);
        if (searchTarget.isBlank()) {
            BountyMessages.send(messages, player, "search-empty", "");
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

    public void onPublicClick(PublicGuiClick click) {
        Player player = click.player();
        BountyViewState main = viewStates.computeIfAbsent(player.getUniqueId(), ignored -> new BountyViewState());
        HistoryState history = historyStates.get(player.getUniqueId());
        switch (click.actionId()) {
            case "bounty.previous-page" -> {
                if ("bounty.main".equals(click.menuId())) {
                    if (!cooldownService.isOnCooldown(player.getUniqueId(), "main-page", settings.getGuiRefreshCooldownMs())) {
                        main.setPage(Math.max(0, main.getPage() - 1));
                        openMain(player, main, () -> guiSounds.previousPage(player));
                    }
                } else if (history != null && history.page > 0 && !isHistoryOnCooldown(player)) {
                    history.page--;
                    openHistory(player, history.targetUuid, history.targetName, false, () -> guiSounds.previousPage(player));
                }
            }
            case "bounty.next-page" -> {
                if ("bounty.main".equals(click.menuId())) {
                    if (!cooldownService.isOnCooldown(player.getUniqueId(), "main-page", settings.getGuiRefreshCooldownMs())) {
                        main.setPage(main.getPage() + 1);
                        openMain(player, main, () -> guiSounds.nextPage(player));
                    }
                } else if (history != null && history.page < history.maxPage && !isHistoryOnCooldown(player)) {
                    history.page++;
                    openHistory(player, history.targetUuid, history.targetName, false, () -> guiSounds.nextPage(player));
                }
            }
            case "bounty.refresh" -> {
                if (!cooldownService.isOnCooldown(player.getUniqueId(), "refresh", settings.getGuiRefreshCooldownMs())) {
                    openMain(player, main, () -> guiSounds.click(player));
                }
            }
            case "bounty.filter" -> {
                if (!cooldownService.isOnCooldown(player.getUniqueId(), "filter", settings.getGuiFilterCooldownMs())) {
                    main.setFilter(main.getFilter().next());
                    main.setPage(0);
                    openMain(player, main, () -> guiSounds.filter(player));
                }
            }
            case "bounty.search" -> {
                if (!cooldownService.isOnCooldown(player.getUniqueId(), "search", settings.getGuiFilterCooldownMs())) {
                    beginSearch(player);
                }
            }
            case "bounty.clear-search" -> {
                main.setSearchTarget(null);
                main.setPage(0);
                openMain(player, main, () -> guiSounds.clearSearch(player));
            }
            case "bounty.history" -> openHistory(player, player.getUniqueId(), player.getName());
            case "bounty.entry-history" -> {
                try {
                    UUID target = UUID.fromString(click.entryId());
                    ActiveBounty bounty = findBountyByUuid(target);
                    if (bounty != null) {
                        openHistory(player, target, bounty.getTargetName(), () -> guiSounds.select(player));
                    }
                } catch (IllegalArgumentException ignored) {
                    // An entry may have been removed since the view was rendered.
                }
            }
            case "bounty.back-to-bounties" -> openMain(player, main, () -> guiSounds.back(player));
            default -> throw new IllegalArgumentException("Unknown public GUI action " + click.actionId());
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
        if (publicGuis == null || publicGuis.snapshot() == null) {
            unavailable(viewer);
            return;
        }
        UUID viewerUuid = viewer.getUniqueId();
        HistoryState state = historyStates.computeIfAbsent(viewerUuid,
                ignored -> new HistoryState(targetUuid, targetName, 0));
        if (!targetUuid.equals(state.targetUuid)) {
            state.page = 0;
        }
        state.targetUuid = targetUuid;
        state.targetName = targetName;

        bountyService.fetchHistoryForPlayerAsync(viewer, targetUuid, settings.getHistoryCap(), entries -> {
            if (!viewer.isOnline() || publicGuis.snapshot() == null) {
                return;
            }
            HistoryState current = historyStates.get(viewerUuid);
            if (current == null || !targetUuid.equals(current.targetUuid)) {
                return;
            }
            PublicGuiDefinition gui = publicGuis.snapshot().require("bounty.history");
            List<Integer> slots = gui.entries().get("history").slots();
            int pageSize = Math.max(1, Math.min(settings.getHistoryPageSize(), slots.size()));
            int maxPage = Math.max(0, (entries.size() - 1) / pageSize);
            current.page = Math.min(current.page, maxPage);
            current.maxPage = maxPage;
            int from = Math.min(entries.size(), current.page * pageSize);
            int to = Math.min(entries.size(), from + pageSize);
            Map<String, String> values = uiValues();
            values.put("page", String.valueOf(current.page + 1));
            values.put("max_page", String.valueOf(maxPage + 1));
            Map<String, String> buttonStates = new HashMap<>();
            if (current.page == 0) hideIfEnabled(gui, buttonStates, "previous-page");
            if (current.page >= maxPage) hideIfEnabled(gui, buttonStates, "next-page");
            Map<Integer, PublicGuiRenderInput.EntryValue> rendered = new HashMap<>();
            for (int index = from; index < to; index++) {
                BountyHistoryEntry entry = entries.get(index);
                ItemStack source = historyEntrySource(viewer, gui, entry, values);
                rendered.put(slots.get(index - from), new PublicGuiRenderInput.EntryValue(
                        targetUuid + ":" + index, source, Map.of()));
            }
            try {
                publicGuis.openEntryPoint(viewer, "gui:bounty-history", new PublicGuiRenderInput(
                        values, buttonStates, Map.of(), Map.of("history", rendered)));
                if (afterOpen != null) afterOpen.run();
            } catch (RuntimeException error) {
                plugin.getLogger().warning("Could not open bounty history GUI: " + error.getMessage());
                unavailable(viewer);
            }
        });
    }

    private void openMain(Player player, BountyViewState state) {
        openMain(player, state, null);
    }

    private void openMain(Player player, BountyViewState state, Runnable afterOpen) {
        if (publicGuis == null || publicGuis.snapshot() == null) {
            unavailable(player);
            return;
        }
        PublicGuiDefinition gui = publicGuis.snapshot().require("bounty.main");
        List<Integer> slots = gui.entries().get("bounties").slots();
        List<ActiveBounty> sorted = bountyService.getSortedBounties(state.getFilter(), state.getSearchTarget());
        int pageSize = slots.size();
        int maxPage = Math.max(0, (sorted.size() - 1) / pageSize);
        state.setPage(Math.min(state.getPage(), maxPage));
        int from = Math.min(sorted.size(), state.getPage() * pageSize);
        int to = Math.min(sorted.size(), from + pageSize);

        Map<String, String> values = uiValues();
        long ownBounty = bountyService.getCurrentBounty(player.getUniqueId());
        long totalEarned = bountyService.getCachedTotalEarned(player.getUniqueId());
        values.put("own_bounty", Style.formatMoney(ownBounty));
        values.put("own_bounty_raw", String.valueOf(ownBounty));
        values.put("total_earned", Style.formatMoney(totalEarned));
        values.put("total_earned_raw", String.valueOf(totalEarned));
        values.put("search", safeValue(state.getSearchTarget()));
        values.put("page", String.valueOf(state.getPage() + 1));
        values.put("max_page", String.valueOf(maxPage + 1));
        boolean hasSearch = state.getSearchTarget() != null && !state.getSearchTarget().isBlank();
        values.put("current", hasSearch ? state.getSearchTarget() : stateName(gui, "label-no-search"));
        values.put("target", stateName(gui, "label-clear-search-target"));

        Map<String, String> buttonStates = new HashMap<>();
        if (state.getPage() == 0) hideIfEnabled(gui, buttonStates, "previous-page");
        if (state.getPage() >= maxPage) hideIfEnabled(gui, buttonStates, "next-page");
        if (!hasSearch) hideIfEnabled(gui, buttonStates, "clear-search");
        List<String> filterLabels = List.of(BountyFilter.values()).stream()
                .map(filter -> stateName(gui, "filter-" + filter.name().toLowerCase(Locale.ROOT)))
                .toList();
        Map<String, PublicGuiText.CycleSelection> cycles = new HashMap<>();
        if (gui.buttons().containsKey("filter") && gui.buttons().get("filter").enabled()) {
            cycles.put("filter", new PublicGuiText.CycleSelection(filterLabels, state.getFilter().ordinal()));
        }
        Map<Integer, PublicGuiRenderInput.EntryValue> rendered = new HashMap<>();
        for (int index = from; index < to; index++) {
            ActiveBounty summary = sorted.get(index);
            ActiveBounty bounty = bountyService.getBountySnapshot(summary.getTargetUuid(), MAX_RENDERED_CONTRIBUTIONS + 1);
            if (bounty == null) continue;
            ItemStack source = bountyEntrySource(player, gui, bounty, values);
            rendered.put(slots.get(index - from), new PublicGuiRenderInput.EntryValue(
                    bounty.getTargetUuid().toString(), source, Map.of()));
        }
        try {
            publicGuis.openEntryPoint(player, "command:/bounty", new PublicGuiRenderInput(
                    values, buttonStates, cycles, Map.of("bounties", rendered)));
            if (afterOpen != null) afterOpen.run();
        } catch (RuntimeException error) {
            plugin.getLogger().warning("Could not open bounty browser GUI: " + error.getMessage());
            unavailable(player);
        }
    }

    private ItemStack bountyEntrySource(Player viewer, PublicGuiDefinition gui, ActiveBounty bounty,
                                        Map<String, String> base) {
        Map<String, String> values = new HashMap<>(base);
        values.put("player", safeName(bounty.getTargetName(), stateName(gui, "label-unknown-player")));
        values.put("amount", Style.formatMoney(bounty.getTotalAmount()));
        values.put("amount_raw", String.valueOf(bounty.getTotalAmount()));
        values.put("created_at", Style.formatTimestamp(bounty.getCreatedAt()));
        values.put("updated_at", Style.formatTimestamp(bounty.getUpdatedAt()));
        List<String> contributions = new ArrayList<>();
        int shown = 0;
        for (BountyContribution contribution : bounty.getContributions()) {
            if (shown >= MAX_RENDERED_CONTRIBUTIONS) break;
            Map<String, String> lineValues = new HashMap<>(values);
            lineValues.put("setter", safeName(contribution.getSetterName(), stateName(gui, "label-unknown-player")));
            lineValues.put("amount", Style.formatMoney(contribution.getAmount()));
            lineValues.put("amount_raw", String.valueOf(contribution.getAmount()));
            PublicGuiDefinition.FixedItem line = gui.states().get("contribution-line");
            if (line != null) contributions.addAll(PublicGuiText.lore(line.lore(),
                    lineValues, null, null, "states.contribution-line.lore"));
            shown++;
        }
        if (bounty.getContributions().size() > shown) {
            PublicGuiDefinition.FixedItem line = gui.states().get("more-contributors");
            if (line != null) contributions.addAll(PublicGuiText.lore(line.lore(),
                    values, null, null, "states.more-contributors.lore"));
        }
        PublicGuiDefinition.FixedItem template = gui.states().get("bounty-template");
        PublicGuiDefinition.FixedItem expanded = expandBountyTemplate(template, contributions);
        ItemStack source = PublicGuiItemRenderer.fixed(viewer, expanded, values, null, null, "states.bounty-template");
        if (template.skullSkinUrl() == null && source.getType() == Material.PLAYER_HEAD
                && source.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(bounty.getTargetUuid()));
            source.setItemMeta(skull);
        }
        return source;
    }

    static PublicGuiDefinition.FixedItem expandBountyTemplate(PublicGuiDefinition.FixedItem template,
                                                              List<String> contributions) {
        List<String> lore = new ArrayList<>();
        for (String line : template.lore()) {
            if ("{contributions}".equals(line)) lore.addAll(contributions);
            else lore.add(line);
        }
        return new PublicGuiDefinition.FixedItem(
                template.material(), template.name(), lore, template.amount(),
                template.customModelData(), template.glow(), template.itemFlags(), template.skullSkinUrl());
    }

    private ItemStack historyEntrySource(Player viewer, PublicGuiDefinition gui, BountyHistoryEntry entry,
                                         Map<String, String> base) {
        String id = entry.getType() == HistoryType.CLAIMED ? "claimed-template" : "lost-template";
        Map<String, String> values = new HashMap<>(base);
        String related = entry.getType() == HistoryType.CLAIMED
                ? safeName(entry.getRelatedName(), stateName(gui, "label-unknown-player"))
                : safeName(entry.getRelatedName(), stateName(gui, "label-natural-death"));
        values.put("type", stateName(gui, "type-" + entry.getType().name().toLowerCase(Locale.ROOT)));
        values.put("player", safeName(entry.getPlayerName(), stateName(gui, "label-unknown-player")));
        values.put("related", related);
        values.put("target", related);
        values.put("amount", Style.formatMoney(entry.getAmount()));
        values.put("amount_raw", String.valueOf(entry.getAmount()));
        String reasonId = "reason-" + safeValue(entry.getReason()).toLowerCase(Locale.ROOT).replace('_', '-');
        values.put("reason", gui.states().containsKey(reasonId) ? stateName(gui, reasonId) : "");
        values.put("time", Style.formatTimestamp(entry.getOccurredAt()));
        return PublicGuiItemRenderer.fixed(viewer, gui.states().get(id), values, null, null, "states." + id);
    }

    private Map<String, String> uiValues() {
        return new HashMap<>(messages.tokenValues());
    }

    private void hideIfEnabled(PublicGuiDefinition gui, Map<String, String> states, String id) {
        PublicGuiDefinition.Button button = gui.buttons().get(id);
        if (button != null && button.enabled() && button.states().containsKey("hidden")) {
            states.put(id, "hidden");
        }
    }

    private String stateName(PublicGuiDefinition gui, String id) {
        PublicGuiDefinition.FixedItem state = gui.states().get(id);
        return state == null || state.name() == null ? "" : state.name();
    }

    private ActiveBounty findBountyByUuid(UUID uuid) {
        return bountyService.getBountySnapshot(uuid, 0);
    }

    private void unavailable(Player player) {
        BountyMessages.send(messages, player, "gui-unavailable", "");
    }

    private void openForViewer(Player player, Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null) inventory.setItem(slot, DialogIcons.forViewer(player, item));
        }
        player.openInventory(inventory);
    }

    private boolean isHistoryOnCooldown(Player player) {
        return cooldownService.isOnCooldown(player.getUniqueId(), "history-gui", settings.getHistoryRequestCooldownMs());
    }

    private void sendSavedEditor(Player player, String key, String value) {
        Map<String, String> replacements = new HashMap<>();
        replacements.put("setting", key);
        replacements.put("value", value);
        BountyMessages.send(messages, player, "editor.saved", EDITOR_SAVED_MESSAGE, replacements);
    }

    private void sendInvalidEditor(Player player, String key) {
        Map<String, String> replacements = new HashMap<>();
        replacements.put("setting", key);
        BountyMessages.send(messages, player, "editor.invalid-input", EDITOR_INVALID_INPUT_MESSAGE, replacements);
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
        BountyMessages.send(messages, player, "search-cancelled", "search-cancelled");
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
        BountyMessages.send(messages, player, "native-dialogs-unavailable", "native-dialogs-unavailable");
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
        private int maxPage;

        private HistoryState(UUID targetUuid, String targetName, int page) {
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.page = page;
        }
    }

}
