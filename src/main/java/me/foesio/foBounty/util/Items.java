package me.foesio.foBounty.util;

import me.foesio.core.dialog.DialogIcons;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.UUID;

public final class Items {
    private Items() {
    }

    public static ItemStack make(Material material, String name, List<String> lore) {
        return make(null, material, name, lore);
    }

    public static ItemStack make(Player viewer, Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (!DialogIcons.applyItemMeta(meta, viewer, name, lore)) {
                meta.setDisplayName(DialogIcons.fallbackText(Style.colorize(name)));
                meta.setLore(lore == null ? List.of() : lore.stream()
                        .map(line -> DialogIcons.fallbackText(Style.colorize(line)))
                        .toList());
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
        return playerHead(null, playerUuid, displayName, lore);
    }

    public static ItemStack playerHead(Player viewer, UUID playerUuid, String displayName, List<String> lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta raw = stack.getItemMeta();
        if (raw instanceof SkullMeta meta) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerUuid);
            meta.setOwningPlayer(player);
            if (!DialogIcons.applyItemMeta(meta, viewer, displayName, lore)) {
                meta.setDisplayName(DialogIcons.fallbackText(Style.colorize(displayName)));
                meta.setLore(lore == null ? List.of() : lore.stream()
                        .map(line -> DialogIcons.fallbackText(Style.colorize(line)))
                        .toList());
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
