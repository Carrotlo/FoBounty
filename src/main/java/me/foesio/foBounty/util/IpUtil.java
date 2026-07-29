package me.foesio.foBounty.util;

import org.bukkit.entity.Player;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;

public final class IpUtil {
    private IpUtil() {
    }

    public static boolean isSameIp(Player first, Player second) {
        InetAddress a = address(first);
        InetAddress b = address(second);
        return a != null && b != null && a.equals(b);
    }

    public static boolean isSameSubnet(Player first, Player second) {
        InetAddress a = address(first);
        InetAddress b = address(second);
        if (a == null || b == null) {
            return false;
        }
        byte[] firstBytes = a.getAddress();
        byte[] secondBytes = b.getAddress();
        if (firstBytes.length != secondBytes.length) {
            return false;
        }
        if (firstBytes.length == 4) {
            return firstBytes[0] == secondBytes[0]
                    && firstBytes[1] == secondBytes[1]
                    && firstBytes[2] == secondBytes[2];
        }
        if (firstBytes.length == 16) {
            return Arrays.equals(Arrays.copyOf(firstBytes, 8), Arrays.copyOf(secondBytes, 8));
        }
        return false;
    }

    private static InetAddress address(Player player) {
        InetSocketAddress socketAddress = player.getAddress();
        if (socketAddress == null) {
            return null;
        }
        return socketAddress.getAddress();
    }
}
