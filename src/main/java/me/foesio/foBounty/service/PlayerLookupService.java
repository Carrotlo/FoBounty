package me.foesio.foBounty.service;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerLookupService {
    private final Map<String, UUID> nameToUuid = new ConcurrentHashMap<>();

    public void rebuildIndex() {
        nameToUuid.clear();
        for (OfflinePlayer offlinePlayer : Bukkit.getOfflinePlayers()) {
            index(offlinePlayer);
        }
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            index(onlinePlayer);
        }
    }

    public void index(OfflinePlayer player) {
        String name = player.getName();
        if (name == null || name.isBlank()) {
            return;
        }
        String lower = normalize(name);
        nameToUuid.put(lower, player.getUniqueId());
    }

    public OfflinePlayer findExact(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        Player onlineExact = Bukkit.getPlayerExact(input);
        if (onlineExact != null) {
            index(onlineExact);
            return onlineExact;
        }
        UUID uuid = nameToUuid.get(normalize(input));
        if (uuid == null) {
            return null;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        index(offline);
        return offline;
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
