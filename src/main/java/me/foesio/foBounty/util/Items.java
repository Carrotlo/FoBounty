package me.foesio.foBounty.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Items {
    private Items() {
    }

    public static ItemStack make(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Style.colorize(name));
            if (lore != null) {
                List<String> colored = new ArrayList<>(lore.size());
                for (String line : lore) {
                    colored.add(Style.colorize(line));
                }
                meta.setLore(colored);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static ItemStack filler(Material material) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static ItemStack playerHead(UUID playerUuid, String displayName, List<String> lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta raw = stack.getItemMeta();
        if (raw instanceof SkullMeta meta) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
            meta.setOwningPlayer(player);
            meta.setDisplayName(Style.colorize(displayName));
            List<String> colored = new ArrayList<>();
            for (String line : lore) {
                colored.add(Style.colorize(line));
            }
            meta.setLore(colored);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
