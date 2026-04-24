package eu.kotori.justRTP.utils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface DataStorage {

    CompletableFuture<Optional<String>> getString(String key);
    CompletableFuture<Void> setString(String key, String value);
    CompletableFuture<Void> setString(String key, String value, int ttlSeconds);
    CompletableFuture<Void> delete(String key);
    CompletableFuture<Boolean> exists(String key);

    CompletableFuture<Void> setCooldown(UUID playerId, long expireTime);
    CompletableFuture<Optional<Long>> getCooldown(UUID playerId);
    CompletableFuture<Void> removeCooldown(UUID playerId);

    CompletableFuture<Void> setDelay(UUID playerId, String delayData);
    CompletableFuture<Optional<String>> getDelay(UUID playerId);
    CompletableFuture<Void> removeDelay(UUID playerId);

    CompletableFuture<Void> cacheLocation(String worldName, String locationData);
    CompletableFuture<Optional<String>> getCachedLocation(String worldName);
    CompletableFuture<Void> removeCachedLocation(String worldName);

    CompletableFuture<Void> setTeleportRequest(UUID playerId, String requestData);
    CompletableFuture<Optional<String>> getTeleportRequest(UUID playerId);
    CompletableFuture<Void> removeTeleportRequest(UUID playerId);

    CompletableFuture<Void> pushToQueue(String queueName, String data);
    CompletableFuture<Optional<String>> popFromQueue(String queueName);
    CompletableFuture<Long> getQueueSize(String queueName);

    CompletableFuture<Void> cleanup();

    CompletableFuture<Boolean> deleteKey(String key);
    CompletableFuture<List<String>> getStringList(String key);
    CompletableFuture<Boolean> setStringList(String key, List<String> values, int ttlSeconds);

    boolean isAvailable();
    String getStorageType();
}