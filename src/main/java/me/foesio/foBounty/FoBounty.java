package me.foesio.foBounty;

import me.foesio.core.FoCoreContext;
import me.foesio.core.FoPluginCore;
import me.foesio.core.economy.VaultEconomyBridge;
import me.foesio.core.message.FoMessageMigrations;
import me.foesio.core.message.FoMessageService;
import me.foesio.core.gui.publicgui.PublicGuiService;
import me.foesio.core.reload.FoReloadRegistry;
import me.foesio.core.reload.FoReloadResult;
import me.foesio.core.sound.FoAdminSounds;
import me.foesio.core.sound.FoEditorSounds;
import me.foesio.core.sound.FoGuiSounds;
import me.foesio.core.sound.FoSoundService;
import me.foesio.core.update.UpdateNoticeService;
import me.foesio.foBounty.api.BountyApi;
import me.foesio.foBounty.command.BountyAdminCommand;
import me.foesio.foBounty.command.BountyCommand;
import me.foesio.foBounty.config.PublicGuiUpgrade;
import me.foesio.foBounty.config.DisplayFormats;
import me.foesio.foBounty.config.PluginSettings;
import me.foesio.foBounty.gui.GuiManager;
import me.foesio.foBounty.gui.PublicBountyGuis;
import me.foesio.foBounty.input.SearchInputService;
import me.foesio.foBounty.listener.GuiListener;
import me.foesio.foBounty.listener.PlayerDeathListener;
import me.foesio.foBounty.listener.PlayerJoinListener;
import me.foesio.foBounty.service.BountyService;
import me.foesio.foBounty.service.BountyApiImpl;
import me.foesio.foBounty.service.BountyNotifications;
import me.foesio.foBounty.service.DiscordWebhookService;
import me.foesio.foBounty.service.FoTeamsHookService;
import me.foesio.foBounty.service.PlayerLookupService;
import me.foesio.foBounty.util.CooldownService;
import me.foesio.foBounty.util.BountyMessages;
import me.foesio.foBounty.util.Style;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.InvalidConfigurationException;

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
    private SearchInputService searchInputs;
    private PublicGuiService publicGuis;
    private BountyService bountyService;
    private DiscordWebhookService discordWebhookService;
    private GuiManager guiManager;
    private PlayerLookupService playerLookupService;

    @Override
    public void onEnable() {
        boolean freshInstall = !getDataFolder().exists();
        settings.load(this);
        core = createCoreContext();
        int oldSchemaVersion = core.migrations().version();
        LegacyMessageSelection messageSelection = LegacyMessageSelection.unchanged();
        boolean migrationReady = true;
        try {
            messageSelection = selectLegacyMessages(getDataFolder().toPath(), oldSchemaVersion);
        } catch (IOException error) {
            migrationReady = false;
            getLogger().severe("Public UI migration cannot select the older message file: " + error.getMessage());
        }
        boolean releasedFlatMessages = migrationReady && oldSchemaVersion < PublicGuiUpgrade.VERSION
                && flatLegacyMessagesInstalled();
        if (freshInstall || (releasedFlatMessages && !Files.exists(getDataFolder().toPath().resolve("sounds.yml")))) {
            saveResource("sounds.yml", false);
        }
        if (freshInstall) {
            saveResource("messages.yml", false);
        }
        sounds = FoSoundService.loadPreservingRemovals(this);
        editorSounds = FoEditorSounds.create(sounds);
        guiSounds = FoGuiSounds.create(sounds);
        adminSounds = FoAdminSounds.create(sounds);
        core.warnIfNativeDialogsUnavailable();
        startMetrics(core);
        boolean messagesExistedBeforeUpgrade = Files.isRegularFile(getDataFolder().toPath().resolve("messages.yml"));
        byte[] originalMessages = null;
        if (migrationReady) {
            try {
                originalMessages = snapshotMessagesBeforeUpgrade(freshInstall);
            } catch (IOException error) {
                migrationReady = false;
                getLogger().severe("Public UI migration cannot back up messages.yml: " + error.getMessage());
                restoreMessagesAfterFailedUpgrade(null, messageSelection);
            }
        }
        boolean flatLegacyMessages = migrationReady && flatLegacyMessagesInstalled();
        messages = FoMessageService.loadPreservingRemovals(this,
                migrationReady && core.migrations().version() < PublicGuiUpgrade.VERSION
                        ? messageMigrations(flatLegacyMessages) : FoMessageMigrations.none());
        if (migrationReady && oldSchemaVersion < PublicGuiUpgrade.VERSION
                && !messagesMigrationPersisted(messagesExistedBeforeUpgrade)) {
            migrationReady = false;
            getLogger().severe("Public UI migration cannot confirm messages.yml was saved; schema version remains pending.");
            restoreMessagesAfterFailedUpgrade(originalMessages, messageSelection);
        }
        DisplayFormats.installFreshDefault(this, core.migrations().version());
        DisplayFormats.reload(this);
        if (migrationReady && !PublicGuiUpgrade.installOrUpgrade(this, core.migrations(), freshInstall, originalMessages)) {
            restoreMessagesAfterFailedUpgrade(originalMessages, messageSelection);
        }
        messages = FoMessageService.loadPreservingRemovals(this, FoMessageMigrations.none());
        UpdateNoticeService updates = core.createUpdateNotices(messages, UPDATE_PROJECT_ID, adminSounds).start();
        searchInputs = new SearchInputService(this, messages, core.textInputService().support());
        playerLookupService = new PlayerLookupService();
        playerLookupService.rebuildIndex();
        FoTeamsHookService foTeamsHookService = new FoTeamsHookService(this);
        discordWebhookService = new DiscordWebhookService(this, oldSchemaVersion);

        CooldownService cooldownService = new CooldownService();
        bountyService = new BountyService(this, settings, playerLookupService, foTeamsHookService,
                core.scheduler(), sounds, cooldownService);
        bountyService.setEconomy(setupEconomy());
        try {
            bountyService.init();
        } catch (SQLException exception) {
            getLogger().severe("Failed to initialize SQLite: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        guiManager = new GuiManager(this, settings, messages, bountyService, cooldownService,
                core.textInputService(), searchInputs, core.inventoryCloseSuppressor(), core.scheduler(), editorSounds, guiSounds);
        publicGuis = PublicBountyGuis.create(this, guiManager);
        guiManager.setPublicGuis(publicGuis);
        publicGuis.start();
        publicGuis.reload();

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
        Bukkit.getServicesManager().register(BountyApi.class,
                new BountyApiImpl(this, settings, bountyService,
                        new BountyNotifications(settings, messages, discordWebhookService)),
                this, ServicePriority.Normal);
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregisterAll(this);
        if (searchInputs != null) {
            searchInputs.close();
            searchInputs = null;
        }
        if (publicGuis != null) {
            publicGuis.close();
            publicGuis = null;
        }
        if (bountyService != null) {
            bountyService.shutdown();
        }
        if (core != null) {
            core.close();
            core = null;
        }
    }

    private VaultEconomyBridge setupEconomy() {
        VaultEconomyBridge economy = core.createVaultEconomy();
        return economy.isAvailable() ? economy : null;
    }

    private FoReloadResult reloadAll() {
        FoReloadResult result = FoReloadRegistry.create()
                .add("settings", () -> settings.load(this))
                .add("storage", bountyService::reloadStorageIfNeeded)
                .add("formats", () -> {
                    if (!DisplayFormats.reload(this)) throw new IllegalStateException("Invalid formats.yml");
                })
                .add("sounds", () -> {
                    if (!sounds.reloadChecked()) throw new IllegalStateException("Invalid sounds.yml");
                })
                .add("messages", () -> {
                    BountyMessages.validateInstalled(this);
                    messages.reload();
                })
                .add("webhook", () -> {
                    if (!discordWebhookService.reload()) throw new IllegalStateException("Invalid webhook.yml");
                })
                .add("core", this::reloadCoreContext)
                .add("dialogs", () -> {
                    if (!searchInputs.reload()) throw new IllegalStateException("Invalid dialogs/search.yml");
                })
                .add("guis", () -> {
                    if (!publicGuis.reload().successful()) throw new IllegalStateException("Invalid public GUI file; previous menus remain active");
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
        if (searchInputs != null) searchInputs.setSupport(core.textInputService().support());
        if (previous != null) {
            previous.close();
        }
        startMetrics(core);
    }

    static LegacyMessageSelection selectLegacyMessages(Path root, int oldSchemaVersion) throws IOException {
        if (oldSchemaVersion >= PublicGuiUpgrade.VERSION) return LegacyMessageSelection.unchanged();
        Path legacy = root.resolve("message.yml");
        Path current = root.resolve("messages.yml");
        if (!Files.exists(legacy)) return LegacyMessageSelection.unchanged();
        if (Files.isSymbolicLink(legacy) || !Files.isRegularFile(legacy)
                || Files.isSymbolicLink(current) || (Files.exists(current) && !Files.isRegularFile(current))) {
            throw new IOException("Unsafe legacy or current message file");
        }
        byte[] before = Files.isRegularFile(current) ? Files.readAllBytes(current) : null;
        if (before != null) {
            YamlConfiguration currentYaml = strictMessageYaml(before, "messages.yml");
            if (currentYaml.contains("tokens", true)) {
                Path backup = current.resolveSibling("messages.yml.gui-v" + oldSchemaVersion + "-backup");
                if (oldSchemaVersion > 1 || Files.isRegularFile(backup)) {
                    return LegacyMessageSelection.unchanged(); // A prior Core release or migration owns this shape.
                }
                throw new IOException("Both message.yml and new-format messages.yml exist without migration history;"
                        + " cannot determine which owner file is active");
            }
        }
        byte[] activeLegacy = Files.readAllBytes(legacy);
        strictMessageYaml(activeLegacy, "message.yml");
        if (java.util.Arrays.equals(before, activeLegacy)) return LegacyMessageSelection.unchanged();
        Files.createDirectories(root);
        if (before != null) {
            Path backup = current.resolveSibling("messages.yml.gui-v" + oldSchemaVersion + "-backup");
            if (Files.exists(backup) && (Files.isSymbolicLink(backup) || !Files.isRegularFile(backup))) {
                throw new IOException("Unsafe messages.yml migration backup");
            }
            if (!Files.exists(backup)) writeNewFileAtomically(backup, before);
        }
        replaceMessageBytes(current, activeLegacy);
        return new LegacyMessageSelection(true, before);
    }

    private static YamlConfiguration strictMessageYaml(byte[] bytes, String name) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(new String(bytes, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException error) {
            throw new IOException(name + " cannot be parsed: " + error.getMessage(), error);
        }
        return yaml;
    }

    private static void writeNewFileAtomically(Path target, byte[] bytes) throws IOException {
        Path stage = Files.createTempFile(target.getParent(), ".bounty-message-", ".staged");
        try {
            Files.write(stage, bytes);
            try {
                Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(stage, target);
            }
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    private static void replaceMessageBytes(Path target, byte[] bytes) throws IOException {
        Path stage = Files.createTempFile(target.getParent(), ".bounty-message-", ".staged");
        try {
            Files.write(stage, bytes);
            try {
                Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(stage, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(stage);
        }
    }

    private FoMessageMigrations messageMigrations(boolean flatLegacyMessages) {
        return FoMessageMigrations.create()
                .add(config -> moveLegacyPrefix(config, bundledMessageText("tokens.prefix"),
                        getLogger()::warning))
                .add(config -> {
                    return refreshKnownMessageDefaults(config);
                })
                .add(config -> seedNewMessageFields(config, flatLegacyMessages))
                .add(this::repairInvalidLegacyMessages)
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

    private boolean refreshKnownMessageDefaults(FileConfiguration config) {
        try (InputStream previousStream = getResource("gui-legacy/v1/message.yml");
             InputStream newestStream = getResource("messages.yml")) {
            if (previousStream == null || newestStream == null) return false;
            YamlConfiguration previous = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(previousStream, StandardCharsets.UTF_8));
            YamlConfiguration newest = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(newestStream, StandardCharsets.UTF_8));
            boolean changed = false;
            if (Objects.equals(config.getString("tokens.prefix"), previous.getString("prefix"))) {
                config.set("tokens.prefix", newest.getString("tokens.prefix"));
                changed = true;
            }
            for (String path : previous.getKeys(true)) {
                if (path.equals("prefix") || !newest.isSet(path) || !config.isSet(path)
                        || !Objects.deepEquals(config.get(path), previous.get(path))) continue;
                config.set(path, newest.get(path));
                changed = true;
            }
            return changed;
        } catch (IOException error) {
            getLogger().warning("Could not compare older message defaults: " + error.getMessage());
            return false;
        }
    }

    private String bundledMessageText(String path) {
        try (InputStream stream = getResource("messages.yml")) {
            if (stream == null) return null;
            YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            return bundled.getString(path);
        } catch (IOException error) {
            getLogger().warning("Could not read bundled messages.yml: " + error.getMessage());
            return null;
        }
    }

    static boolean moveLegacyPrefix(FileConfiguration config, String newestPrefix, Consumer<String> warning) {
        if (config.isString("tokens.prefix") || !config.contains("prefix", true)) return false;
        Object oldPrefix = config.get("prefix");
        String legacyPrefix = oldPrefix instanceof String text ? text : newestPrefix;
        if (legacyPrefix == null) return false;
        if (!(oldPrefix instanceof String)) {
            warning.accept("Legacy messages.yml:prefix=" + oldPrefix
                    + " is invalid; using newest bundled default " + legacyPrefix);
        }
        if (config.contains("tokens", true) && !config.isConfigurationSection("tokens")) {
            config.set("tokens", null);
        }
        config.set("tokens.prefix", legacyPrefix);
        config.set("prefix", null);
        return true;
    }

    private boolean repairInvalidLegacyMessages(FileConfiguration config) {
        try (InputStream stream = getResource("messages.yml")) {
            if (stream == null) return false;
            YamlConfiguration newest = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            boolean changed = false;
            for (String path : newest.getKeys(true)) {
                if (newest.isConfigurationSection(path) || !config.contains(path, true)) continue;
                Object value = config.get(path);
                boolean valid = value instanceof String || (!path.startsWith("tokens.")
                        && value instanceof List<?> list && list.stream().allMatch(String.class::isInstance));
                if (valid) continue;
                getLogger().warning("Legacy messages.yml:" + path + "=" + value
                        + " is invalid; using newest bundled default " + newest.get(path));
                config.set(path, newest.get(path));
                changed = true;
            }
            return changed;
        } catch (IOException error) {
            getLogger().warning("Could not validate older messages.yml: " + error.getMessage());
            return false;
        }
    }

    private boolean seedNewMessageFields(FileConfiguration config, boolean flatLegacyMessages) {
        try (InputStream stream = getResource("messages.yml")) {
            if (stream == null) return false;
            YamlConfiguration newest = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            return seedNewMessageFields(config, newest, flatLegacyMessages);
        } catch (IOException error) {
            getLogger().warning("Could not install newly introduced message fields: " + error.getMessage());
            return false;
        }
    }

    static boolean seedNewMessageFields(FileConfiguration config, FileConfiguration newest,
                                        boolean flatLegacyMessages) {
        // A retry can have an old migration version but an already updated messages.yml.
        // Only a released flat message file proves these keys were never available.
        if (!flatLegacyMessages) return false;
        List<String> introduced = List.of("gui-unavailable", "search-empty",
                "history-labels.unknown-player", "history-labels.reasons.PVP",
                "history-labels.reasons.NATURAL_DEATH",
                "tokens.theme", "tokens.muted", "tokens.white", "tokens.good", "tokens.bad",
                "claim-failed", "reload-failed", "admin-remove-failed");
        boolean changed = false;
        for (String path : introduced) {
            if (config.contains(path, true) || !newest.contains(path, true)) continue;
            config.set(path, newest.get(path));
            changed = true;
        }
        return changed;
    }

    private boolean flatLegacyMessagesInstalled() {
        Path file = getDataFolder().toPath().resolve("messages.yml");
        if (!Files.isRegularFile(file)) return false;
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(file));
            return yaml.contains("prefix", true) && !yaml.contains("tokens", true);
        } catch (IOException | InvalidConfigurationException error) {
            return false;
        }
    }

    private byte[] snapshotMessagesBeforeUpgrade(boolean freshInstall) throws IOException {
        if (freshInstall || core.migrations().version() >= PublicGuiUpgrade.VERSION) return null;
        Path file = getDataFolder().toPath().resolve("messages.yml");
        if (!Files.isRegularFile(file)) return null;
        byte[] bytes = Files.readAllBytes(file);
        try {
            new YamlConfiguration().loadFromString(new String(bytes, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException error) {
            throw new IOException("messages.yml cannot be parsed: " + error.getMessage(), error);
        }
        Path backup = file.resolveSibling(file.getFileName() + ".gui-v"
                + core.migrations().version() + "-backup");
        if (!Files.exists(backup)) {
            Path stage = Files.createTempFile(file.getParent(), ".bounty-message-backup-", ".staged");
            try {
                Files.write(stage, bytes);
                try {
                    Files.move(stage, backup, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(stage, backup);
                }
            } finally {
                Files.deleteIfExists(stage);
            }
        }
        return bytes;
    }

    private void restoreMessagesAfterFailedUpgrade(byte[] original, LegacyMessageSelection selection) {
        Path file = getDataFolder().toPath().resolve("messages.yml");
        try {
            restoreMessageFile(file, original, selection);
        } catch (IOException error) {
            getLogger().severe("Could not restore messages.yml after failed GUI migration: " + error.getMessage());
        }
    }

    static void restoreMessageFile(Path file, byte[] original, LegacyMessageSelection selection) throws IOException {
        byte[] rollback = selection.changed() ? selection.previousCurrent() : original;
        if (rollback == null && !selection.changed()) return;
        if (rollback == null) Files.deleteIfExists(file);
        else replaceMessageBytes(file, rollback);
    }

    record LegacyMessageSelection(boolean changed, byte[] previousCurrent) {
        static LegacyMessageSelection unchanged() {
            return new LegacyMessageSelection(false, null);
        }
    }

    private boolean messagesMigrationPersisted(boolean originallyPresent) {
        Path file = getDataFolder().toPath().resolve("messages.yml");
        if (!originallyPresent) return !Files.exists(file);
        if (!Files.isRegularFile(file)) return false;
        try {
            YamlConfiguration disk = new YamlConfiguration();
            disk.loadFromString(Files.readString(file));
            Set<String> leaves = new java.util.HashSet<>();
            for (String path : messages.config().getKeys(true)) {
                if (!messages.config().isConfigurationSection(path)) leaves.add(path);
            }
            for (String path : disk.getKeys(true)) {
                if (!disk.isConfigurationSection(path)) leaves.add(path);
            }
            for (String path : leaves) {
                if (!Objects.deepEquals(messages.config().get(path), disk.get(path))) return false;
            }
            return true;
        } catch (IOException | InvalidConfigurationException error) {
            return false;
        }
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


}
