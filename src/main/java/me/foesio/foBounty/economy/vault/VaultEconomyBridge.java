package me.foesio.foBounty.economy.vault;

import me.foesio.foBounty.economy.EconomyBridge;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class VaultEconomyBridge implements EconomyBridge {
    private final Economy economy;

    private VaultEconomyBridge(Economy economy) {
        this.economy = economy;
    }

    public static EconomyBridge create(JavaPlugin plugin) {
        RegisteredServiceProvider<Economy> registration = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            return null;
        }
        return new VaultEconomyBridge(registration.getProvider());
    }

    @Override
    public boolean withdraw(Player player, long amount) {
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        return response.transactionSuccess();
    }

    @Override
    public boolean deposit(Player player, long amount) {
        EconomyResponse response = economy.depositPlayer(player, amount);
        return response.transactionSuccess();
    }
}
