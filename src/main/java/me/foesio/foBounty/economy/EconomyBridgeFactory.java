package me.foesio.foBounty.economy;

import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

public final class EconomyBridgeFactory {
    private static final String VAULT_BRIDGE_CLASS = "me.foesio.foBounty.economy.vault.VaultEconomyBridge";

    private EconomyBridgeFactory() {
    }

    public static EconomyBridge create(JavaPlugin plugin) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Vault")) {
            return null;
        }

        try {
            Class<?> bridgeClass = Class.forName(VAULT_BRIDGE_CLASS, true, plugin.getClass().getClassLoader());
            Method createMethod = bridgeClass.getMethod("create", JavaPlugin.class);
            return (EconomyBridge) createMethod.invoke(null, plugin);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException exception) {
            plugin.getLogger().warning("Vault economy was detected but could not be initialized: " + exception.getMessage());
            return null;
        }
    }
}
