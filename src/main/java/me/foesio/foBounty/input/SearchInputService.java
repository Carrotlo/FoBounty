package me.foesio.foBounty.input;

import me.foesio.core.dialog.NativeDialogSupport;
import me.foesio.core.message.FoMessageService;
import me.foesio.foBounty.config.SearchDialogConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Owner-configured Paper search dialog with an owner-configured chat fallback. */
public final class SearchInputService implements Listener {
    private final JavaPlugin plugin;
    private final FoMessageService messages;
    private final SearchDialogConfig config;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private NativeDialogSupport support;

    public SearchInputService(JavaPlugin plugin, FoMessageService messages, NativeDialogSupport support) {
        this.plugin = plugin;
        this.messages = messages;
        this.support = support;
        this.config = new SearchDialogConfig(plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        config.reload();
    }

    public boolean reload() {
        return config.reload();
    }

    public boolean available() {
        return config.current() != null;
    }

    public void setSupport(NativeDialogSupport support) {
        this.support = support;
    }

    /** Returns true only when native input opened. False means chat fallback or unavailable. */
    public boolean open(Player player, Map<String, String> values,
                        Consumer<String> onSubmit, Runnable onCancel) {
        SearchDialogConfig.Definition definition = config.current();
        if (definition == null) return false;
        SearchDialogConfig.Rendered rendered = definition.render(values);
        pending.remove(player.getUniqueId());
        if (support.canUseNativeDialogs(player)) {
            try {
                Class<?> bridge = Class.forName("me.foesio.foBounty.input.paper.PaperSearchDialog", true,
                        plugin.getClass().getClassLoader());
                Object result = bridge.getMethod("open", JavaPlugin.class, Player.class,
                                SearchDialogConfig.Rendered.class, Consumer.class, Runnable.class)
                        .invoke(null, plugin, player, rendered, onSubmit, onCancel);
                if (Boolean.TRUE.equals(result)) return true;
            } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                     | InvocationTargetException | LinkageError error) {
                support.disableForSession("FoBounty search dialog failed: " + error.getClass().getSimpleName());
                plugin.getLogger().warning("Native search dialog failed; using configured chat input: " + error.getMessage());
            }
        }
        pending.put(player.getUniqueId(), new Pending(rendered.cancelKeyword(), onSubmit, onCancel));
        player.closeInventory();
        for (String line : rendered.chatLines()) {
            if (!line.isEmpty()) messages.sendTemplate(player, line);
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    @SuppressWarnings("deprecation")
    public void onChat(AsyncPlayerChatEvent event) {
        Pending prompt = pending.remove(event.getPlayer().getUniqueId());
        if (prompt == null) return;
        event.setCancelled(true);
        String input = event.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!event.getPlayer().isOnline()) return;
            if (input.trim().equalsIgnoreCase(prompt.cancelKeyword())) prompt.onCancel().run();
            else prompt.onSubmit().accept(input);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    public void close() {
        pending.clear();
        org.bukkit.event.HandlerList.unregisterAll(this);
    }

    private record Pending(String cancelKeyword, Consumer<String> onSubmit, Runnable onCancel) {
    }
}
