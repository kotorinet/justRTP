package eu.kotori.justRTP.utils;

import eu.kotori.justRTP.JustRTP;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RedisDataStorage implements DataStorage {
    private final JustRTP plugin;
    private final RedisManager redisManager;
    private final Gson gson = new Gson();

    public RedisDataStorage(JustRTP plugin, RedisManager redisManager) {
        this.plugin = plugin;
        this.redisManager = redisManager;
    }

    @Override
    public CompletableFuture<Optional<String>> getString(String key) {
        return redisManager.get(key).thenApply(value -> Optional.ofNullable(value));
    }

    @Override
    public CompletableFuture<Void> setString(String key, String value) {
        return redisManager.set(key, value).thenApply(result -> null);
    }

    @Override
    public CompletableFuture<Void> setString(String key, String value, int ttlSeconds) {
        return redisManager.setex(key, ttlSeconds, value).thenApply(result -> null);
    }

    @Override
    public CompletableFuture<Void> delete(String key) {
        return redisManager.del(key).thenApply(result -> null);
    }

    @Override
    public CompletableFuture<Boolean> exists(String key) {
        return redisManager.exists(key).thenApply(result -> result > 0);
    }

    @Override
    public CompletableFuture<Void> setCooldown(UUID playerId, long expireTime) {
        if (!redisManager.isCooldownsEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        String key = redisManager.getCooldownKey(playerId);
        String value = String.valueOf(expireTime);
        int ttl = redisManager.getCooldownsTtl();

        return setString(key, value, ttl);
    }

    @Override
    public CompletableFuture<Optional<Long>> getCooldown(UUID playerId) {
        if (!redisManager.isCooldownsEnabled()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        String key = redisManager.getCooldownKey(playerId);
        return getString(key).thenApply(optValue -> {
            if (optValue.isPresent()) {
                try {
                    long expireTime = Long.parseLong(optValue.get());
                    if (expireTime > System.currentTimeMillis()) {
                        return Optional.of(expireTime);
                    } else {
                        delete(key);
                        return Optional.empty();
                    }
                } catch (NumberFormatException e) {
                    plugin.getRTPLogger().debug("REDIS",
                            "[RedisData] Invalid cooldown format for player " + playerId + ": " + optValue.get());
                    delete(key);
                }
            }
            return Optional.empty();
        });
    }

    @Override
    public CompletableFuture<Void> removeCooldown(UUID playerId) {
        if (!redisManager.isCooldownsEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        String key = redisManager.getCooldownKey(playerId);
        return delete(key);
    }

    @Override
    public CompletableFuture<Void> setDelay(UUID playerId, String delayData) {
        if (!redisManager.isDelaysEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        String key = redisManager.getDelayKey(playerId);
        int ttl = redisManager.getDelaysTtl();

        return setString(key, delayData, ttl);
    }

    @Override
    public CompletableFuture<Optional<String>> getDelay(UUID playerId) {
        if (!redisManager.isDelaysEnabled()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        String key = redisManager.getDelayKey(playerId);
        return getString(key);
    }

    @Override
    public CompletableFuture<Void> removeDelay(UUID playerId) {
        if (!redisManager.isDelaysEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        String key = redisManager.getDelayKey(playerId);
        return delete(key);
    }

    @Override
    public CompletableFuture<Void> cacheLocation(String worldName, String locationData) {
        if (!redisManager.isLocationCacheEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        String key = redisManager.getLocationCacheKey(worldName, "locations");
        return redisManager.lpush(key, locationData).thenApply(result -> {
            redisManager.expire(key, redisManager.getLocationCacheTtl());
            return null;
        });
    }

    @Override
    public CompletableFuture<Optional<String>> getCachedLocation(String worldName) {
        if (!redisManager.isLocationCacheEnabled()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        String key = redisManager.getLocationCacheKey(worldName, "locations");
        return redisManager.lpop(key).thenApply(value -> Optional.ofNullable(value));
    }

    @Override
    public CompletableFuture<Void> removeCachedLocation(String worldName) {
        if (!redisManager.isLocationCacheEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        String key = redisManager.getLocationCacheKey(worldName, "locations");
        return delete(key);
    }

    @Override
    public CompletableFuture<Void> setTeleportRequest(UUID playerId, String requestData) {
        if (!redisManager.isTeleportRequestsEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        String key = redisManager.getTeleportRequestKey(playerId);
        int ttl = redisManager.getTeleportRequestsTtl();

        return setString(key, requestData, ttl);
    }

    @Override
    public CompletableFuture<Optional<String>> getTeleportRequest(UUID playerId) {
        if (!redisManager.isTeleportRequestsEnabled()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        String key = redisManager.getTeleportRequestKey(playerId);
        return getString(key);
    }

    @Override
    public CompletableFuture<Void> removeTeleportRequest(UUID playerId) {
        if (!redisManager.isTeleportRequestsEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        String key = redisManager.getTeleportRequestKey(playerId);
        return delete(key);
    }

    @Override
    public CompletableFuture<Void> pushToQueue(String queueName, String data) {
        if (!redisManager.isTeleportQueueEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        String key = redisManager.getQueueKey(queueName);
        return redisManager.lpush(key, data).thenApply(result -> {
            redisManager.expire(key, redisManager.getTeleportQueueTtl());
            return null;
        });
    }

    @Override
    public CompletableFuture<Optional<String>> popFromQueue(String queueName) {
        if (!redisManager.isTeleportQueueEnabled()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        String key = redisManager.getQueueKey(queueName);
        return redisManager.lpop(key).thenApply(value -> Optional.ofNullable(value));
    }

    @Override
    public CompletableFuture<Long> getQueueSize(String queueName) {
        if (!redisManager.isTeleportQueueEnabled()) {
            return CompletableFuture.completedFuture(0L);
        }

        String key = redisManager.getQueueKey(queueName);
        return redisManager.llen(key);
    }

    @Override
    public CompletableFuture<Void> cleanup() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Boolean> deleteKey(String key) {
        return redisManager.del(key).thenApply(result -> result > 0);
    }

    @Override
    public CompletableFuture<List<String>> getStringList(String key) {
        return redisManager.get(key).thenApply(value -> {
            if (value != null && !value.isEmpty()) {
                return Arrays.asList(value.split(","));
            }
            return new ArrayList<>();
        });
    }

    @Override
    public CompletableFuture<Boolean> setStringList(String key, List<String> values, int ttlSeconds) {
        String joinedValue = String.join(",", values);
        if (ttlSeconds > 0) {
            return redisManager.setex(key, ttlSeconds, joinedValue).thenApply(result -> "OK".equals(result));
        } else {
            return redisManager.set(key, joinedValue).thenApply(result -> "OK".equals(result));
        }
    }

    @Override
    public boolean isAvailable() {
        return redisManager.isConnected();
    }

    @Override
    public String getStorageType() {
        return "Redis";
    }

    public <T> CompletableFuture<Void> setObject(String key, T object) {
        try {
            String json = gson.toJson(object);
            return setString(key, json);
        } catch (Exception e) {
            plugin.getLogger().warning("[RedisData] Failed to serialize object for key " + key + ": " + e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    public <T> CompletableFuture<Void> setObject(String key, T object, int ttlSeconds) {
        try {
            String json = gson.toJson(object);
            return setString(key, json, ttlSeconds);
        } catch (Exception e) {
            plugin.getLogger().warning("[RedisData] Failed to serialize object for key " + key + ": " + e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    public <T> CompletableFuture<Optional<T>> getObject(String key, Class<T> clazz) {
        return getString(key).thenApply(optJson -> {
            if (optJson.isPresent()) {
                try {
                    T object = gson.fromJson(optJson.get(), clazz);
                    return Optional.of(object);
                } catch (JsonSyntaxException e) {
                    plugin.getLogger()
                            .warning("[RedisData] Failed to deserialize object for key " + key + ": " + e.getMessage());
                    delete(key);
                }
            }
            return Optional.empty();
        });
    }
}