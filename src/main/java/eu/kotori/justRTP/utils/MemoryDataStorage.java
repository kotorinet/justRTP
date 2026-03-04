package eu.kotori.justRTP.utils;

import eu.kotori.justRTP.JustRTP;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MemoryDataStorage implements DataStorage {
    private final JustRTP plugin;
    private final Map<String, String> keyValueStore = new ConcurrentHashMap<>();
    private final Map<String, Long> keyTtl = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<String>> queues = new ConcurrentHashMap<>();

    public MemoryDataStorage(JustRTP plugin) {
        this.plugin = plugin;
    }

    @Override
    public CompletableFuture<Optional<String>> getString(String key) {
        return CompletableFuture.completedFuture(getStringSync(key));
    }

    private Optional<String> getStringSync(String key) {
        Long ttl = keyTtl.get(key);
        if (ttl != null && System.currentTimeMillis() > ttl) {
            keyValueStore.remove(key);
            keyTtl.remove(key);
            return Optional.empty();
        }

        return Optional.ofNullable(keyValueStore.get(key));
    }

    @Override
    public CompletableFuture<Void> setString(String key, String value) {
        keyValueStore.put(key, value);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> setString(String key, String value, int ttlSeconds) {
        keyValueStore.put(key, value);
        if (ttlSeconds > 0) {
            keyTtl.put(key, System.currentTimeMillis() + (ttlSeconds * 1000L));
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> delete(String key) {
        keyValueStore.remove(key);
        keyTtl.remove(key);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Boolean> exists(String key) {
        return CompletableFuture.completedFuture(getStringSync(key).isPresent());
    }

    @Override
    public CompletableFuture<Void> setCooldown(UUID playerId, long expireTime) {
        String key = "cooldown:" + playerId.toString();
        keyValueStore.put(key, String.valueOf(expireTime));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Optional<Long>> getCooldown(UUID playerId) {
        String key = "cooldown:" + playerId.toString();
        Optional<String> value = getStringSync(key);
        if (value.isPresent()) {
            try {
                long expireTime = Long.parseLong(value.get());
                if (expireTime > System.currentTimeMillis()) {
                    return CompletableFuture.completedFuture(Optional.of(expireTime));
                } else {
                    keyValueStore.remove(key);
                }
            } catch (NumberFormatException e) {
                plugin.getRTPLogger().debug("DATA",
                        "[MemoryData] Invalid cooldown format for player " + playerId + ": " + value.get());
                keyValueStore.remove(key);
            }
        }
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<Void> removeCooldown(UUID playerId) {
        String key = "cooldown:" + playerId.toString();
        keyValueStore.remove(key);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> setDelay(UUID playerId, String delayData) {
        String key = "delay:" + playerId.toString();
        keyValueStore.put(key, delayData);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Optional<String>> getDelay(UUID playerId) {
        String key = "delay:" + playerId.toString();
        return CompletableFuture.completedFuture(getStringSync(key));
    }

    @Override
    public CompletableFuture<Void> removeDelay(UUID playerId) {
        String key = "delay:" + playerId.toString();
        keyValueStore.remove(key);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> cacheLocation(String worldName, String locationData) {
        String key = "cache:" + worldName;
        ConcurrentLinkedQueue<String> queue = queues.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
        queue.offer(locationData);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Optional<String>> getCachedLocation(String worldName) {
        String key = "cache:" + worldName;
        ConcurrentLinkedQueue<String> queue = queues.get(key);
        if (queue != null) {
            String location = queue.poll();
            return CompletableFuture.completedFuture(Optional.ofNullable(location));
        }
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<Void> removeCachedLocation(String worldName) {
        String key = "cache:" + worldName;
        queues.remove(key);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> setTeleportRequest(UUID playerId, String requestData) {
        String key = "teleport:" + playerId.toString();
        keyValueStore.put(key, requestData);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Optional<String>> getTeleportRequest(UUID playerId) {
        String key = "teleport:" + playerId.toString();
        return CompletableFuture.completedFuture(getStringSync(key));
    }

    @Override
    public CompletableFuture<Void> removeTeleportRequest(UUID playerId) {
        String key = "teleport:" + playerId.toString();
        keyValueStore.remove(key);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> pushToQueue(String queueName, String data) {
        String key = "queue:" + queueName;
        ConcurrentLinkedQueue<String> queue = queues.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
        queue.offer(data);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Optional<String>> popFromQueue(String queueName) {
        String key = "queue:" + queueName;
        ConcurrentLinkedQueue<String> queue = queues.get(key);
        if (queue != null) {
            String data = queue.poll();
            return CompletableFuture.completedFuture(Optional.ofNullable(data));
        }
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<Long> getQueueSize(String queueName) {
        String key = "queue:" + queueName;
        ConcurrentLinkedQueue<String> queue = queues.get(key);
        return CompletableFuture.completedFuture(queue != null ? (long) queue.size() : 0L);
    }

    @Override
    public CompletableFuture<Void> cleanup() {
        long currentTime = System.currentTimeMillis();

        Set<String> expiredKeys = new HashSet<>();
        keyTtl.entrySet().removeIf(entry -> {
            if (currentTime > entry.getValue()) {
                expiredKeys.add(entry.getKey());
                return true;
            }
            return false;
        });

        expiredKeys.forEach(keyValueStore::remove);

        plugin.getRTPLogger().debug("DATA", "[MemoryData] Cleaned up " + expiredKeys.size() + " expired entries");
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Boolean> deleteKey(String key) {
        keyTtl.remove(key);
        return CompletableFuture.completedFuture(keyValueStore.remove(key) != null);
    }

    @Override
    public CompletableFuture<List<String>> getStringList(String key) {
        String value = keyValueStore.get(key);
        if (value != null && !value.isEmpty()) {
            return CompletableFuture.completedFuture(Arrays.asList(value.split(",")));
        }
        return CompletableFuture.completedFuture(new ArrayList<>());
    }

    @Override
    public CompletableFuture<Boolean> setStringList(String key, List<String> values, int ttlSeconds) {
        String value = String.join(",", values);
        keyValueStore.put(key, value);

        if (ttlSeconds > 0) {
            keyTtl.put(key, System.currentTimeMillis() + (ttlSeconds * 1000L));
        }

        return CompletableFuture.completedFuture(true);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public String getStorageType() {
        return "Memory";
    }

    public void clear() {
        keyValueStore.clear();
        keyTtl.clear();
        queues.clear();
    }

    public int size() {
        return keyValueStore.size();
    }
}