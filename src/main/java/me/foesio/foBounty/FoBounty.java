package me.foesio.foBounty;

import me.foesio.core.FoCoreContext;
import me.foesio.core.FoPluginCore;
import me.foesio.core.dialog.ConfiguredTextDialogs;
import me.foesio.core.dialog.DialogButton;
import me.foesio.core.dialog.TextDialogRequest;
import me.foesio.core.message.FoMessageMigrations;
import me.foesio.core.message.FoMessageService;
import me.foesio.core.reload.FoReloadRegistry;
import me.foesio.core.reload.FoReloadResult;
import me.foesio.core.sound.FoAdminSounds;
import me.foesio.core.sound.FoEditorSounds;
import me.foesio.core.sound.FoGuiSounds;
import me.foesio.core.sound.FoSoundService;
import me.foesio.core.update.UpdateNoticeService;
import me.foesio.foBounty.command.BountyAdminCommand;
import me.foesio.foBounty.command.BountyCommand;
import me.foesio.foBounty.config.GuiConfig;
import me.foesio.foBounty.config.PluginSettings;
import me.foesio.foBounty.economy.EconomyBridge;
import me.foesio.foBounty.economy.EconomyBridgeFactory;
import me.foesio.foBounty.gui.GuiManager;
import me.foesio.foBounty.listener.GuiListener;
import me.foesio.foBounty.listener.PlayerDeathListener;
import me.foesio.foBounty.listener.PlayerJoinListener;
import me.foesio.foBounty.service.BountyService;
import me.foesio.foBounty.service.DiscordWebhookService;
import me.foesio.foBounty.service.FoTeamsHookService;
import me.foesio.foBounty.service.PlayerLookupService;
import me.foesio.foBounty.util.CooldownService;
import me.foesio.foBounty.util.Style;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.List;

public final class FoBounty extends JavaPlugin {
    private static final String UPDATE_PROJECT_ID = "fobounty";
    private static final int BSTATS_PLUGIN_ID = 32976;
    private static final String EDITOR_SAVED_MESSAGE = "{prefix}{good}Updated {theme}{setting}{good} to {theme}{value}{good}.";
    private static final String EDITOR_INVALID_INPUT_MESSAGE = "{prefix}{bad}Invalid value for {theme}{setting}{bad}.";

    private final PluginSettings settings = new PluginSettings();
    private FoCoreContext core;
    private FoMessageService messages;
    private FoSoundService sounds;
    private FoEditorSounds editorSounds;
    private FoGuiSounds guiSounds;
    private FoAdminSounds adminSounds;
    private ConfiguredTextDialogs textDialogs;
    private GuiConfig guiConfig;
    private BountyService bountyService;
    private GuiManager guiManager;
    private PlayerLookupService playerLookupService;

    @Override
    public void onEnable() {
        settings.load(this);
        core = createCoreContext();
        sounds = core.createSounds();
        editorSounds = FoEditorSounds.create(sounds);
        guiSounds = FoGuiSounds.create(sounds);
        adminSounds = FoAdminSounds.create(sounds);
        core.warnIfNativeDialogsUnavailable();
        startMetrics(core);
        copyLegacyMessageFileIfNeeded();
        messages = FoMessageService.load(this, messageMigrations());
        UpdateNoticeService updates = core.createUpdateNotices(messages, UPDATE_PROJECT_ID, adminSounds).start();
        textDialogs = ConfiguredTextDialogs.create(this)
                .register("search", searchDialogFallback());
        textDialogs.load();
        guiConfig = new GuiConfig(this);
        guiConfig.load();
        playerLookupService = new PlayerLookupService();
        playerLookupService.rebuildIndex();
        FoTeamsHookService foTeamsHookService = new FoTeamsHookService(this);
        DiscordWebhookService discordWebhookService = new DiscordWebhookService(this);

        bountyService = new BountyService(this, settings, playerLookupService, foTeamsHookService, core.scheduler(), sounds);
        bountyService.setEconomy(setupEconomy());
        try {
            bountyService.init();
        } catch (SQLException exception) {
            getLogger().severe("Failed to initialize SQLite: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        CooldownService cooldownService = new CooldownService();
        guiManager = new GuiManager(this, settings, messages, guiConfig, bountyService, cooldownService,
                core.textInputService(), textDialogs, core.inventoryCloseSuppressor(), core.scheduler(), editorSounds, guiSounds);

        BountyCommand bountyCommand = new BountyCommand(
                settings,
                messages,
                bountyService,
                discordWebhookService,
                playerLookupService,
                guiManager,
                cooldownService,
                adminSounds,
                sounds
        );
        BountyAdminCommand adminCommand = new BountyAdminCommand(
                this,
                messages,
                bountyService,
                discordWebhookService,
                playerLookupService,
                guiManager,
                this::reloadAll,
                updates::checkAndSendVersion,
                adminSounds,
                sounds
        );

        if (getCommand("fobounty") != null) {
            getCommand("fobounty").setExecutor(bountyCommand);
            getCommand("fobounty").setTabCompleter(bountyCommand);
        }
        if (getCommand("fobountyadmin") != null) {
            getCommand("fobountyadmin").setExecutor(adminCommand);
            getCommand("fobountyadmin").setTabCompleter(adminCommand);
        }

        Bukkit.getPluginManager().registerEvents(new GuiListener(guiManager, core.scheduler()), this);
        Bukkit.getPluginManager().registerEvents(new PlayerDeathListener(settings, messages, bountyService, discordWebhookService, sounds), this);
        Bukkit.getPluginManager().registerEvents(new PlayerJoinListener(
                bountyService,
                playerLookupService
        ), this);

        registerPlaceholders();
    }

    @Override
    public void onDisable() {
        if (bountyService != null) {
            bountyService.shutdown();
        }
        if (core != null) {
            core.close();
            core = null;
        }
    }

    private EconomyBridge setupEconomy() {
        return EconomyBridgeFactory.create(this);
    }

    private FoReloadResult reloadAll() {
        FoReloadResult result = FoReloadRegistry.create()
                .add("settings", () -> settings.load(this))
                .add("storage", bountyService::reloadStorageIfNeeded)
                .add("sounds", sounds::reload)
                .addMessages(messages)
                .add("core", this::reloadCoreContext)
                .add("dialogs", textDialogs::reload)
                .add("guis", () -> {
                    guiConfig.load();
                    guiManager.setGuiConfig(guiConfig);
                })
                .add("player-index", () -> {
                    playerLookupService.rebuildIndex();
                    bountyService.reindexKnownPlayers();
                })
                .add("economy", () -> bountyService.setEconomy(setupEconomy()))
                .reload();
        if (!result.successful()) {
            getLogger().warning("Reload failed at " + result.failedStep() + ": " + result.errorMessage());
        }
        return result;
    }

    private FoCoreContext createCoreContext() {
        return FoPluginCore.create(this);
    }

    public FoSoundService sounds() {
        return sounds;
    }

    public FoEditorSounds editorSounds() {
        return editorSounds;
    }

    public FoAdminSounds adminSounds() {
        return adminSounds;
    }

    private void startMetrics(FoCoreContext context) {
        context.metrics(BSTATS_PLUGIN_ID);
    }

    private void reloadCoreContext() {
        FoCoreContext next = createCoreContext();
        next.warnIfNativeDialogsUnavailable();
        FoCoreContext previous = core;
        core = next;
        if (guiManager != null) {
            guiManager.setDialogServices(core.textInputService(), core.inventoryCloseSuppressor());
        }
        if (previous != null) {
            previous.close();
        }
        startMetrics(core);
    }

    private void copyLegacyMessageFileIfNeeded() {
        File current = new File(getDataFolder(), "messages.yml");
        File legacy = new File(getDataFolder(), "message.yml");
        if (current.exists() || !legacy.exists()) {
            return;
        }
        try {
            File parent = current.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                getLogger().warning("Could not create plugin data folder for messages.yml");
                return;
            }
            Files.copy(legacy.toPath(), current.toPath());
            getLogger().info("Copied legacy message.yml to messages.yml so existing message edits are preserved.");
        } catch (IOException exception) {
            getLogger().warning("Could not copy legacy message.yml to messages.yml: " + exception.getMessage());
        }
    }

    private FoMessageMigrations messageMigrations() {
        return FoMessageMigrations.create()
                .add(config -> {
                    String legacyPrefix = config.getString("prefix");
                    if (legacyPrefix == null) {
                        return false;
                    }
                    config.set("tokens.prefix", legacyPrefix);
                    config.set("prefix", null);
                    return true;
                })
                .add(config -> {
                    boolean changed = false;
                    changed |= setMessageDefault(config, "editor.saved",
                            config.getString("editor-saved", EDITOR_SAVED_MESSAGE));
                    changed |= setMessageDefault(config, "editor.invalid-input",
                            config.getString("editor-invalid", EDITOR_INVALID_INPUT_MESSAGE));
                    changed |= setMessageDefault(config, "editor.save-failed",
                            "{prefix}{bad}Could not save {theme}{setting}{bad}. {muted}{error}");
                    changed |= setMessageDefault(config, "editor.cancelled",
                            "{prefix}{muted}Editor input cancelled.");
                    changed |= setMessageDefault(config, "editor.opened",
                            "{prefix}{theme}Editor opened.");
                    changed |= setMessageDefault(config, "editor.search-applied",
                            "{prefix}{theme}Search applied: {white}{query}");
                    changed |= setMessageDefault(config, "editor.search-cleared",
                            "{prefix}{muted}Search cleared.");
                    changed |= setMessageDefault(config, "editor.item-required",
                            "{prefix}{bad}Hold an item on your cursor or in your main hand first.");
                    changed |= setMessageDefault(config, "editor.deleted",
                            "{prefix}{bad}Deleted {theme}{entry}{bad}.");
                    return changed;
                })
                .replaceExact(
                        "add-announce",
                        "{theme}FoBounty &8» {white}{setter} set {theme}${amount}{white} on {theme}{target}{white}. Total now: {theme}${total}{white}.",
                        "{prefix}{white}{setter} set {theme}${amount}{white} on {theme}{target}{white}. Total now: {theme}${total}{white}."
                )
                .replaceExact(
                        "claim-announce",
                        "{theme}FoBounty &8» {white}{killer} claimed {theme}${amount}{white} for {target}.",
                        "{prefix}{white}{killer} claimed {theme}${amount}{white} for {target}."
                )
                .replaceExact(
                        "reload-success",
                        "{prefix}{good}Reloaded config.yml, message.yml, guis, and dialogs.",
                        "{prefix}{good}Reloaded config.yml, messages.yml, guis, and dialogs."
                )
                .removeExact(
                        "search-prompt",
                        "{prefix}{muted}Type a player name in chat to search, or {theme}cancel{muted}."
                )
                .removeExact(
                        "editor-prompt-min-price",
                        "{prefix}{muted}Type a new min price in chat, or {theme}cancel{muted}."
                )
                .removeExact(
                        "editor-prompt-max-price",
                        "{prefix}{muted}Type a new max price in chat, or {theme}cancel{muted}."
                )
                .removeExact(
                        "editor-prompt-announce-threshold",
                        "{prefix}{muted}Type a new announce threshold in chat, or {theme}cancel{muted}."
                )
                .removeExact(
                        "editor-prompt-history-cap",
                        "{prefix}{muted}Type a new history cap in chat, or {theme}cancel{muted}."
                )
                .build();
    }

    private boolean setMessageDefault(FileConfiguration config, String path, String value) {
        if (config.isSet(path)) {
            return false;
        }
        config.set(path, value);
        return true;
    }

    private void registerPlaceholders() {
        core.placeholders("fobounty")
                .author("Carrotio")
                .persist(true)
                .logRegistration(false)
                .offline("total_earned", this::placeholderTotalEarned)
                .offline("total_earned_formatted", player -> Style.formatMoney(placeholderTotalEarnedRaw(player)))
                .offline("current_bounty", player -> String.valueOf(placeholderCurrentBountyRaw(player)))
                .offline("current_bounty_formatted", player -> Style.formatMoney(placeholderCurrentBountyRaw(player)))
                .registerIfAvailable();
    }

    private String placeholderTotalEarned(OfflinePlayer player) {
        return String.valueOf(placeholderTotalEarnedRaw(player));
    }

    private long placeholderTotalEarnedRaw(OfflinePlayer player) {
        if (player == null) {
            return 0L;
        }
        bountyService.primeTotalEarnedAsync(player.getUniqueId(), ignored -> {
        });
        return bountyService.getCachedTotalEarned(player.getUniqueId());
    }

    private long placeholderCurrentBountyRaw(OfflinePlayer player) {
        if (player == null) {
            return 0L;
        }
        return bountyService.getCurrentBounty(player.getUniqueId());
    }

    private TextDialogRequest searchDialogFallback() {
        return new TextDialogRequest(
                Style.THEME_HEX + "Search",
                List.of(
                        Style.MUTED_HEX + "Enter a player name to filter active bounties.",
                        Style.MUTED_HEX + "Use cancel to return without changing the search."
                ),
                Style.WHITE_HEX + "Player name",
                "{current}",
                "",
                DialogButton.search("Search", "", 100),
                DialogButton.cancel("Cancel", "", 100),
                300,
                300,
                32,
                true,
                false,
                false
        );
    }

}
