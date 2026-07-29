package me.foesio.foBounty.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CooldownService {
    private final Map<String, Long> lastUse = new ConcurrentHashMap<>();

    public boolean isOnCooldown(UUID playerUuid, String actionKey, long cooldownMs) {
        String key = playerUuid + ":" + actionKey;
        long now = System.currentTimeMillis();
        AtomicBoolean onCooldown = new AtomicBoolean(false);
        lastUse.compute(key, (ignored, previous) -> {
            if (previous != null && now - previous < cooldownMs) {
                onCooldown.set(true);
                return previous;
            }
            return now;
        });
        return onCooldown.get();
    }

    public void clear(UUID playerUuid) {
        String prefix = playerUuid + ":";
        lastUse.keySet().removeIf(key -> key.startsWith(prefix));
    }
}
