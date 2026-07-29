package me.foesio.foBounty.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class YamlFileUpdater {
    private YamlFileUpdater() {
    }

    static YamlConfiguration update(JavaPlugin plugin,
                                    String resourceName,
                                    Set<String> obsoletePaths,
                                    Map<String, String> exactDefaultReplacements) {
        File file = new File(plugin.getDataFolder(), resourceName);
        if (!file.exists()) {
            plugin.saveResource(resourceName, false);
        }

        YamlConfiguration current = loadFile(plugin, file);
        YamlConfiguration defaults = loadResource(plugin, resourceName);
        if (current == null) {
            return defaults == null ? new YamlConfiguration() : defaults;
        }
        if (defaults == null) {
            return current;
        }

        boolean changed = false;
        for (String path : obsoletePaths) {
            if (current.isSet(path)) {
                current.set(path, null);
                changed = true;
            }
        }

        for (Map.Entry<String, String> entry : exactDefaultReplacements.entrySet()) {
            String path = entry.getKey();
            String oldDefault = entry.getValue();
            if (oldDefault.equals(current.getString(path))) {
                current.set(path, defaults.get(path));
                changed = true;
            }
        }

        changed |= copyMissingDefaults(current, defaults);
        changed |= copyMissingComments(current, defaults);

        if (changed) {
            save(plugin, file, current);
        }
        return current;
    }

    static void save(JavaPlugin plugin, String resourceName, YamlConfiguration configuration) {
        save(plugin, new File(plugin.getDataFolder(), resourceName), configuration);
    }

    private static boolean copyMissingDefaults(YamlConfiguration current, YamlConfiguration defaults) {
        boolean changed = false;
        for (String path : defaults.getKeys(true)) {
            if (current.isSet(path)) {
                continue;
            }
            Object value = defaults.get(path);
            if (value instanceof ConfigurationSection) {
                current.createSection(path);
            } else {
                current.set(path, value);
            }
            changed = true;
        }
        return changed;
    }

    private static boolean copyMissingComments(YamlConfiguration current, YamlConfiguration defaults) {
        boolean changed = false;
        if (current.options().getHeader().isEmpty() && !defaults.options().getHeader().isEmpty()) {
            current.options().setHeader(defaults.options().getHeader());
            changed = true;
        }
        if (current.options().getFooter().isEmpty() && !defaults.options().getFooter().isEmpty()) {
            current.options().setFooter(defaults.options().getFooter());
            changed = true;
        }
        for (String path : defaults.getKeys(true)) {
            List<String> comments = defaults.getComments(path);
            if (!comments.isEmpty() && current.getComments(path).isEmpty()) {
                current.setComments(path, comments);
                changed = true;
            }

            List<String> inlineComments = defaults.getInlineComments(path);
            if (!inlineComments.isEmpty() && current.getInlineComments(path).isEmpty()) {
                current.setInlineComments(path, inlineComments);
                changed = true;
            }
        }
        return changed;
    }

    private static YamlConfiguration loadFile(JavaPlugin plugin, File file) {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.options().parseComments(true);
        try {
            configuration.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().warning("Could not load " + file.getName() + ": " + exception.getMessage());
            return null;
        }
        return configuration;
    }

    private static YamlConfiguration loadResource(JavaPlugin plugin, String resourceName) {
        InputStream stream = plugin.getResource(resourceName);
        if (stream == null) {
            plugin.getLogger().warning("Missing bundled resource " + resourceName);
            return null;
        }

        YamlConfiguration configuration = new YamlConfiguration();
        configuration.options().parseComments(true);
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            configuration.load(reader);
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().warning("Could not load bundled " + resourceName + ": " + exception.getMessage());
        }
        return configuration;
    }

    private static void save(JavaPlugin plugin, File file, YamlConfiguration configuration) {
        try {
            configuration.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not update " + file.getName() + ": " + exception.getMessage());
        }
    }
}
