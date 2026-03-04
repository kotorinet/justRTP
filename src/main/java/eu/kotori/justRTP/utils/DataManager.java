package eu.kotori.justRTP.utils;

import eu.kotori.justRTP.JustRTP;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class DataManager implements DataStorage {
    private final JustRTP plugin;
    private final RedisManager redisManager;
    private final RedisDataStorage redisStorage;
    private final MemoryDataStorage memoryStorage;
    private final DatabaseManager databaseManager;

    private volatile DataStorage primaryStorage;
    private volatile DataStorage fallbackStorage;
    private volatile long lastFailoverTime = 0;
    private static final long FAILOVER_COOLDOWN = 30000;

    public DataManager(JustRTP plugin) {
        this.plugin = plugin;
        this.redisManager = new RedisManager(plugin, plugin.getDatabaseManager());
        this.redisStorage = new RedisDataStorage(plugin, redisManager);
        this.memoryStorage = new MemoryDataStorage(plugin);
        this.databaseManager = plugin.getDatabaseManager();

        determinePrimaryStorage();
        setupFailoverMonitoring();
    }

    public DataManager(JustRTP plugin, eu.kotori.justRTP.managers.ConfigManager configManager) {
        this(plugin);
    }

    private void determinePrimaryStorage() {
        if (redisManager.isEnabled() && redisManager.isConnected()) {
            primaryStorage = redisStorage;
            fallbackStorage = memoryStorage;
            plugin.getLogger().info("Data storage initialized: Primary=Redis, Fallback=Memory");
        } else {
            primaryStorage = memoryStorage;
            fallbackStorage = null;
            plugin.getLogger().info("Data storage initialized: Primary=Memory (Redis disabled/unavailable)");
        }
    }

    private void setupFailoverMonitoring() {
        plugin.getFoliaScheduler().runTimer(() -> {
            if (primaryStorage == redisStorage && !redisStorage.isAvailable()) {
                performFailover();
            } else if (primaryStorage == memoryStorage && redisManager.isEnabled() && redisManager.isConnected()) {
                performFailback();
            }
        }, 400L, 400L);
    }

    private void performFailover() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFailoverTime < FAILOVER_COOLDOWN) {
            return;
        }

        lastFailoverTime = currentTime;

        if (primaryStorage == redisStorage) {
            plugin.getLogger().warning("Redis connection lost, failing over to memory storage");
            primaryStorage = memoryStorage;
            fallbackStorage = null;
        }
    }

    private void performFailback() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastFailoverTime < FAILOVER_COOLDOWN) {
            return;
        }

        lastFailoverTime = currentTime;

        if (primaryStorage == memoryStorage && redisStorage.isAvailable()) {
            plugin.getLogger().info("Redis connection restored, switching back from memory storage");
            primaryStorage = redisStorage;
            fallbackStorage = memoryStorage;
        }
    }

    private <T> CompletableFuture<T> executeWithFallback(
            String operation,
            CompletableFuture<T> primaryOperation,
            CompletableFuture<T> fallbackOperation,
            T defaultValue) {

        return primaryOperation.handle((result, throwable) -> {
            if (throwable != null) {
                plugin.getRTPLogger().debug("DATA",
                        "[DataManager] Primary " + operation + " failed: " + throwable.getMessage());

                if (fallbackStorage != null) {
                    plugin.getRTPLogger().debug("DATA", "[DataManager] Attempting fallback for " + operation);
                    return fallbackOperation;
                } else {
                    plugin.getRTPLogger().debug("DATA",
                            "[DataManager] No fallback available for " + operation + ", returning default");
                    return CompletableFuture.completedFuture(defaultValue);
                }
            }
            return CompletableFuture.completedFuture(result);
        }).thenCompose(future -> future);
    }

    @Override
    public CompletableFuture<Optional<String>> getString(String key) {
        CompletableFuture<Optional<String>> primary = primaryStorage.getString(key);
        CompletableFuture<Optional<String>> fallback = fallbackStorage != null ? fallbackStorage.getString(key)
                : CompletableFuture.completedFuture(Optional.empty());

        return executeWithFallback("getString", primary, fallback, Optional.empty());
    }

    @Override
    public CompletableFuture<Void> setString(String key, String value) {
        CompletableFuture<Void> primary = primaryStorage.setString(key, value);

        return primary.exceptionally(ex -> {
            plugin.getRTPLogger().debug("DATA", "[DataManager] Primary setString failed: " + ex.getMessage());
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> setString(String key, String value, int ttlSeconds) {
        CompletableFuture<Void> primary = primaryStorage.setString(key, value, ttlSeconds);

        return primary.exceptionally(ex -> {
            plugin.getRTPLogger().debug("DATA", "[DataManager] Primary setString with TTL failed: " + ex.getMessage());
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> delete(String key) {
        CompletableFuture<Void> primary = primaryStorage.delete(key);

        if (fallbackStorage != null) {
            fallbackStorage.delete(key).exceptionally(ex -> {
                return null;
            });
        }

        return primary.exceptionally(ex -> {
            plugin.getRTPLogger().debug("DATA", "[DataManager] Primary delete failed: " + ex.getMessage());
            return null;
        });
    }

    @Override
    public CompletableFuture<Boolean> exists(String key) {
        CompletableFuture<Boolean> primary = primaryStorage.exists(key);
        CompletableFuture<Boolean> fallback = fallbackStorage != null ? fallbackStorage.exists(key)
                : CompletableFuture.completedFuture(false);

        return executeWithFallback("exists", primary, fallback, false);
    }

    @Override
    public CompletableFuture<Void> setCooldown(UUID playerId, long expireTime) {
        CompletableFuture<Void> primary = primaryStorage.setCooldown(playerId, expireTime);

        if (fallbackStorage != null) {
            fallbackStorage.setCooldown(playerId, expireTime);
        }

        return primary;
    }

    @Override
    public CompletableFuture<Optional<Long>> getCooldown(UUID playerId) {
        return primaryStorage.getCooldown(playerId).thenCompose(result -> {
            if (result.isPresent()) {
                return CompletableFuture.completedFuture(result);
            }

            if (fallbackStorage != null) {
                return fallbackStorage.getCooldown(playerId);
            }

            return CompletableFuture.completedFuture(Optional.empty());
        });
    }

    @Override
    public CompletableFuture<Void> removeCooldown(UUID playerId) {
        CompletableFuture<Void> primary = primaryStorage.removeCooldown(playerId);

        if (fallbackStorage != null) {
            fallbackStorage.removeCooldown(playerId);
        }

        return primary;
    }

    @Override
    public CompletableFuture<Void> setDelay(UUID playerId, String delayData) {
        CompletableFuture<Void> primary = primaryStorage.setDelay(playerId, delayData);

        if (fallbackStorage != null) {
            fallbackStorage.setDelay(playerId, delayData);
        }

        return primary;
    }

    @Override
    public CompletableFuture<Optional<String>> getDelay(UUID playerId) {
        return primaryStorage.getDelay(playerId).thenCompose(result -> {
            if (result.isPresent()) {
                return CompletableFuture.completedFuture(result);
            }

            if (fallbackStorage != null) {
                return fallbackStorage.getDelay(playerId);
            }

            return CompletableFuture.completedFuture(Optional.empty());
        });
    }

    @Override
    public CompletableFuture<Void> removeDelay(UUID playerId) {
        CompletableFuture<Void> primary = primaryStorage.removeDelay(playerId);

        if (fallbackStorage != null) {
            fallbackStorage.removeDelay(playerId);
        }

        return primary;
    }

    @Override
    public CompletableFuture<Void> cacheLocation(String worldName, String locationData) {
        return primaryStorage.cacheLocation(worldName, locationData);
    }

    @Override
    public CompletableFuture<Optional<String>> getCachedLocation(String worldName) {
        return primaryStorage.getCachedLocation(worldName).thenCompose(result -> {
            if (result.isPresent()) {
                return CompletableFuture.completedFuture(result);
            }

            if (fallbackStorage != null) {
                return fallbackStorage.getCachedLocation(worldName);
            }

            return CompletableFuture.completedFuture(Optional.empty());
        });
    }

    @Override
    public CompletableFuture<Void> removeCachedLocation(String worldName) {
        CompletableFuture<Void> primary = primaryStorage.removeCachedLocation(worldName);

        if (fallbackStorage != null) {
            fallbackStorage.removeCachedLocation(worldName);
        }

        return primary;
    }

    @Override
    public CompletableFuture<Void> setTeleportRequest(UUID playerId, String requestData) {
        if (redisManager.isTeleportRequestsEnabled() && databaseManager != null && databaseManager.isConnected()) {
            primaryStorage.setTeleportRequest(playerId, requestData);
        }

        return primaryStorage.setTeleportRequest(playerId, requestData);
    }

    @Override
    public CompletableFuture<Optional<String>> getTeleportRequest(UUID playerId) {
        return primaryStorage.getTeleportRequest(playerId);
    }

    @Override
    public CompletableFuture<Void> removeTeleportRequest(UUID playerId) {
        CompletableFuture<Void> primary = primaryStorage.removeTeleportRequest(playerId);

        if (fallbackStorage != null) {
            fallbackStorage.removeTeleportRequest(playerId);
        }

        return primary;
    }

    @Override
    public CompletableFuture<Void> pushToQueue(String queueName, String data) {
        return primaryStorage.pushToQueue(queueName, data);
    }

    @Override
    public CompletableFuture<Optional<String>> popFromQueue(String queueName) {
        return primaryStorage.popFromQueue(queueName);
    }

    @Override
    public CompletableFuture<Long> getQueueSize(String queueName) {
        return primaryStorage.getQueueSize(queueName);
    }

    @Override
    public CompletableFuture<Void> cleanup() {
        CompletableFuture<Void> primary = primaryStorage.cleanup();

        if (fallbackStorage != null) {
            fallbackStorage.cleanup();
        }

        return primary;
    }

    @Override
    public boolean isAvailable() {
        return primaryStorage.isAvailable() || (fallbackStorage != null && fallbackStorage.isAvailable());
    }

    @Override
    public String getStorageType() {
        String primary = primaryStorage.getStorageType();
        if (fallbackStorage != null) {
            return primary + " (fallback: " + fallbackStorage.getStorageType() + ")";
        }
        return primary;
    }

    @Override
    public CompletableFuture<Boolean> deleteKey(String key) {
        CompletableFuture<Boolean> primary = primaryStorage.deleteKey(key);
        CompletableFuture<Boolean> fallback = fallbackStorage != null ? fallbackStorage.deleteKey(key)
                : CompletableFuture.completedFuture(false);

        return executeWithFallback("deleteKey", primary, fallback, false);
    }

    @Override
    public CompletableFuture<List<String>> getStringList(String key) {
        CompletableFuture<List<String>> primary = primaryStorage.getStringList(key);
        CompletableFuture<List<String>> fallback = fallbackStorage != null ? fallbackStorage.getStringList(key)
                : CompletableFuture.completedFuture(new ArrayList<>());

        return executeWithFallback("getStringList", primary, fallback, new ArrayList<>());
    }

    @Override
    public CompletableFuture<Boolean> setStringList(String key, List<String> values, int ttlSeconds) {
        CompletableFuture<Boolean> primary = primaryStorage.setStringList(key, values, ttlSeconds);
        CompletableFuture<Boolean> fallback = fallbackStorage != null
                ? fallbackStorage.setStringList(key, values, ttlSeconds)
                : CompletableFuture.completedFuture(false);

        return executeWithFallback("setStringList", primary, fallback, false);
    }

    public void reload() {
        try {
            plugin.getRTPLogger().debug("DATA", "[DataManager] Reloading data storage configuration...");

            redisManager.reload();
            determinePrimaryStorage();

            plugin.getLogger().info("Data storage reloaded: " + getStorageType());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to reload data storage", e);
        }
    }

    public void shutdown() {
        try {
            plugin.getRTPLogger().debug("DATA", "[DataManager] Shutting down data storage...");

            redisManager.shutdown();
            memoryStorage.clear();

            plugin.getRTPLogger().debug("DATA", "[DataManager] Data storage shutdown complete");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error during data storage shutdown", e);
        }
    }

    public boolean isRedisEnabled() {
        return redisManager.isEnabled();
    }

    public boolean isRedisConnected() {
        return redisManager.isConnected();
    }

    public DataStorage getPrimaryStorage() {
        return primaryStorage;
    }

    public DataStorage getFallbackStorage() {
        return fallbackStorage;
    }

    public String getStorageStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("Primary: ").append(primaryStorage.getStorageType());
        stats.append(" (Available: ").append(primaryStorage.isAvailable()).append(")");

        if (fallbackStorage != null) {
            stats.append(", Fallback: ").append(fallbackStorage.getStorageType());
            stats.append(" (Available: ").append(fallbackStorage.isAvailable()).append(")");
        }

        if (memoryStorage == primaryStorage || memoryStorage == fallbackStorage) {
            stats.append(", Memory entries: ").append(memoryStorage.size());
        }

        return stats.toString();
    }

    public CompletableFuture<Void> subscribe(String channel, java.util.function.Consumer<String> messageHandler) {
        if (redisManager.isPubSubEnabled()) {
            return redisManager.subscribe(channel, messageHandler);
        }
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Long> publish(String channel, String message) {
        if (redisManager.isPubSubEnabled()) {
            return redisManager.publish(channel, message);
        }
        return CompletableFuture.completedFuture(0L);
    }

    public boolean isPubSubAvailable() {
        return redisManager.isPubSubEnabled();
    }

    public RedisManager getRedisManager() {
        return redisManager;
    }
}