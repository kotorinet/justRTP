package eu.kotori.justRTP.managers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class CooldownManager {
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    public void setCooldown(UUID uuid, String worldName, int seconds) {
        if (seconds <= 0) {
            removeCooldown(uuid, worldName);
            return;
        }

        cooldowns.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                 .put(worldName, System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds));
    }

    public void removeCooldown(UUID uuid, String worldName) {
        Map<String, Long> worldCooldowns = cooldowns.get(uuid);
        if (worldCooldowns != null) {
            worldCooldowns.remove(worldName);
            if (worldCooldowns.isEmpty()) {
                cooldowns.remove(uuid);
            }
        }
    }

    public void clearCooldown(UUID uuid) {
        cooldowns.remove(uuid);
    }

    public long getRemaining(UUID uuid, String worldName) {
        Map<String, Long> worldCooldowns = cooldowns.get(uuid);
        if (worldCooldowns == null) {
            return 0;
        }

        Long expiryTime = worldCooldowns.get(worldName);
        if (expiryTime == null || expiryTime <= System.currentTimeMillis()) {
            worldCooldowns.remove(worldName);
            if (worldCooldowns.isEmpty()) {
                cooldowns.remove(uuid);
            }
            return 0;
        }

        return TimeUnit.MILLISECONDS.toSeconds(expiryTime - System.currentTimeMillis());
    }

    public boolean hasCooldown(UUID uuid, String worldName) {
        return getRemaining(uuid, worldName) > 0;
    }

    public Map<String, Long> getAllCooldowns(UUID uuid) {
        Map<String, Long> worldCooldowns = cooldowns.get(uuid);
        if (worldCooldowns == null) {
            return new HashMap<>();
        }

        Map<String, Long> result = new HashMap<>();
        long currentTime = System.currentTimeMillis();

        worldCooldowns.entrySet().removeIf(entry -> entry.getValue() <= currentTime);

        for (Map.Entry<String, Long> entry : worldCooldowns.entrySet()) {
            long remaining = TimeUnit.MILLISECONDS.toSeconds(entry.getValue() - currentTime);
            if (remaining > 0) {
                result.put(entry.getKey(), remaining);
            }
        }

        return result;
    }
}