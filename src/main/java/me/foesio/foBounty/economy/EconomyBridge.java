package me.foesio.foBounty.economy;

import org.bukkit.entity.Player;

public interface EconomyBridge {
    boolean withdraw(Player player, long amount);

    boolean deposit(Player player, long amount);
}
