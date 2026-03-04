package eu.kotori.justRTP.utils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import eu.kotori.justRTP.JustRTP;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.io.File;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class RedisManager {
    private static final Type LIST_TYPE = new TypeToken<List<String>>() {
    }.getType();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();
    private static final int TTL_PRESERVE = Integer.MIN_VALUE;

    private final JustRTP plugin;
    private final ThreadSafetyGuard threadGuard;
    private final DatabaseManager databaseManager;
    private final Gson gson = new Gson();
    private JedisPool jedisPool;
    private JedisPubSub pubSubListener;
    private final ScheduledExecutorService reconnectExecutor;
    private final Map<String, Object> fallbackStorage = new ConcurrentHashMap<>();
    private final Map<String, Long> fallbackExpiry = new ConcurrentHashMap<>();

    private boolean enabled = false;
    private boolean memoryFallbackEnabled = false;
    private boolean mysqlFallbackEnabled = false;
    private boolean autoReconnect = false;
    private int reconnectInterval = 30;
    private boolean usePipelining = false;
    private boolean usePubSub = false;
    private boolean debugEnabled = false;
    private int slowOperationThreshold = 100;

    private String serverPrefix;
    private String globalPrefix;
    private String cooldownsPrefix;
    private String delaysPrefix;
    private String cachePrefix;
    private String teleportsPrefix;
    private String queuePrefix;

    private boolean cooldownsEnabled = false;
    private boolean delaysEnabled = false;
    private boolean locationCacheEnabled = false;
    private boolean teleportRequestsEnabled = false;
    private boolean teleportQueueEnabled = false;

    private int cooldownsTtl = 3600;
    private int delaysTtl = 300;
    private int locationCacheTtl = 1800;
    private int teleportRequestsTtl = 600;
    private int teleportQueueTtl = 300;

    private volatile boolean connected = false;
    private volatile long lastConnectionAttempt = 0;
    private static final long CONNECTION_RETRY_COOLDOWN = 5000;

    public RedisManager(JustRTP plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.threadGuard = new ThreadSafetyGuard(plugin);
        this.databaseManager = databaseManager;
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "JustRTP-Redis-Reconnect");
            t.setDaemon(true);
            return t;
        });

        loadConfiguration();
        if (enabled) {
            connect();
            if (connected && autoReconnect) {
                startReconnectTask();
            }
        }
    }

    private void loadConfiguration() {
        try {
            File redisFile = new File(plugin.getDataFolder(), "redis.yml");
            if (!redisFile.exists()) {
                plugin.saveResource("redis.yml", false);
            }

            FileConfiguration config = YamlConfiguration.loadConfiguration(redisFile);

            enabled = config.getBoolean("enabled", false);
            memoryFallbackEnabled = config.getBoolean("fallback.enable-memory-fallback", true);
            mysqlFallbackEnabled = config.getBoolean("fallback.enable-mysql-fallback", true);
            autoReconnect = config.getBoolean("fallback.auto-reconnect", true);
            reconnectInterval = config.getInt("fallback.reconnect-interval", 30);

            usePipelining = config.getBoolean("performance.use-pipelining", true);
            usePubSub = config.getBoolean("performance.use-pubsub", true);

            debugEnabled = config.getBoolean("debug.enabled", false);
            slowOperationThreshold = config.getInt("debug.slow-operation-threshold", 100);

            serverPrefix = config.getString("key-prefixes.server", plugin.getConfigManager().getProxyThisServerName());
            if (serverPrefix.isEmpty())
                serverPrefix = "server";
            globalPrefix = config.getString("key-prefixes.global", "justrtp");
            cooldownsPrefix = config.getString("key-prefixes.cooldowns", "cooldowns");
            delaysPrefix = config.getString("key-prefixes.delays", "delays");
            cachePrefix = config.getString("key-prefixes.cache", "cache");
            teleportsPrefix = config.getString("key-prefixes.teleports", "teleports");
            queuePrefix = config.getString("key-prefixes.queue", "queue");

            cooldownsEnabled = config.getBoolean("storage.cooldowns.enabled", true);
            delaysEnabled = config.getBoolean("storage.delays.enabled", true);
            locationCacheEnabled = config.getBoolean("storage.location-cache.enabled", true);
            teleportRequestsEnabled = config.getBoolean("storage.teleport-requests.enabled", true);
            teleportQueueEnabled = config.getBoolean("storage.teleport-queue.enabled", true);

            cooldownsTtl = config.getInt("storage.cooldowns.ttl", 3600);
            delaysTtl = config.getInt("storage.delays.ttl", 300);
            locationCacheTtl = config.getInt("storage.location-cache.ttl", 1800);
            teleportRequestsTtl = config.getInt("storage.teleport-requests.ttl", 600);
            teleportQueueTtl = config.getInt("storage.teleport-queue.ttl", 300);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load Redis configuration", e);
            enabled = false;
        }
    }

    private void connect() {
        if (System.currentTimeMillis() - lastConnectionAttempt < CONNECTION_RETRY_COOLDOWN) {
            return;
        }
        lastConnectionAttempt = System.currentTimeMillis();

        try {
            if (jedisPool != null && !jedisPool.isClosed()) {
                jedisPool.close();
            }

            File redisFile = new File(plugin.getDataFolder(), "redis.yml");
            FileConfiguration config = YamlConfiguration.loadConfiguration(redisFile);

            String host = config.getString("connection.host", "localhost");
            int port = config.getInt("connection.port", 6379);
            int database = config.getInt("connection.database", 0);
            String password = config.getString("connection.password", "");
            int timeout = config.getInt("connection.timeout", 5000);
            boolean ssl = config.getBoolean("connection.ssl", false);

            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(config.getInt("pool-settings.max-total", 16));
            poolConfig.setMaxIdle(config.getInt("pool-settings.max-idle", 8));
            poolConfig.setMinIdle(config.getInt("pool-settings.min-idle", 2));
            poolConfig.setMaxWait(java.time.Duration.ofMillis(config.getInt("pool-settings.max-wait", 2000)));
            poolConfig.setTestOnBorrow(config.getBoolean("pool-settings.test-on-borrow", false));
            poolConfig.setTestWhileIdle(config.getBoolean("pool-settings.test-while-idle", true));
            poolConfig.setTimeBetweenEvictionRuns(
                    java.time.Duration.ofMillis(config.getInt("pool-settings.time-between-eviction-runs", 30000)));
            poolConfig.setNumTestsPerEvictionRun(3);
            poolConfig.setBlockWhenExhausted(true);
            poolConfig.setJmxEnabled(false);

            if (password != null && !password.trim().isEmpty()) {
                jedisPool = new JedisPool(poolConfig, host, port, timeout, password, database, ssl);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port, timeout, null, database, ssl);
            }

            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
                connected = true;
                plugin.getLogger().info("Successfully connected to Redis server at " + host + ":" + port
                        + " (database: " + database + ")");

                if (usePubSub) {
                    initializePubSub();
                }
            }

        } catch (Exception e) {
            connected = false;
            plugin.getLogger().log(Level.WARNING, "Failed to connect to Redis: " + e.getMessage());
            if (debugEnabled) {
                plugin.getLogger().log(Level.WARNING, "Redis connection error details:", e);
            }
        }
    }

    private void initializePubSub() {
        if (pubSubListener != null) {
            try {
                pubSubListener.unsubscribe();
            } catch (Exception ignored) {
            }
        }

        plugin.getRTPLogger().debug("REDIS",
                "[Redis-PubSub] Pub/Sub system ready - use subscribe() for channel subscriptions");
    }

    private void startReconnectTask() {
        reconnectExecutor.scheduleWithFixedDelay(() -> {
            if (!connected) {
                plugin.getRTPLogger().debug("REDIS", "[Redis] Attempting to reconnect...");
                connect();
            }
        }, reconnectInterval, reconnectInterval, TimeUnit.SECONDS);
    }

    public boolean isConnected() {
        if (!enabled)
            return false;
        if (!connected)
            return false;

        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ping();
            return true;
        } catch (Exception e) {
            connected = false;
            if (debugEnabled) {
                plugin.getRTPLogger().debug("REDIS", "[Redis] Connection check failed: " + e.getMessage());
            }
            return false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isCooldownsEnabled() {
        return enabled && cooldownsEnabled;
    }

    public boolean isDelaysEnabled() {
        return enabled && delaysEnabled;
    }

    public boolean isLocationCacheEnabled() {
        return enabled && locationCacheEnabled;
    }

    public boolean isTeleportRequestsEnabled() {
        return enabled && teleportRequestsEnabled;
    }

    public boolean isTeleportQueueEnabled() {
        return enabled && teleportQueueEnabled;
    }

    public String getCooldownKey(UUID playerId) {
        return globalPrefix + ":" + cooldownsPrefix + ":" + playerId.toString();
    }

    public String getDelayKey(UUID playerId) {
        return globalPrefix + ":" + delaysPrefix + ":" + playerId.toString();
    }

    public String getLocationCacheKey(String worldName, String cacheType) {
        return globalPrefix + ":" + cachePrefix + ":" + worldName + ":" + cacheType;
    }

    public String getTeleportRequestKey(UUID playerId) {
        return globalPrefix + ":" + teleportsPrefix + ":" + playerId.toString();
    }

    public String getQueueKey(String queueName) {
        return globalPrefix + ":" + queuePrefix + ":" + queueName;
    }

    public String getServerSpecificKey(String key) {
        return globalPrefix + ":" + serverPrefix + ":" + key;
    }

    public CompletableFuture<String> get(String key) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncRedis("get");

            if (!isConnected()) {
                return getFallback(key);
            }

            long startTime = debugEnabled ? System.currentTimeMillis() : 0;
            try (Jedis jedis = jedisPool.getResource()) {
                String result = jedis.get(key);
                if (debugEnabled)
                    logSlowOperation("GET", key, startTime);
                return result;
            } catch (Exception e) {
                handleRedisError("GET", key, e);
                return getFallback(key);
            }
        });
    }

    public CompletableFuture<String> set(String key, String value) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncRedis("set");

            if (!isConnected()) {
                return setFallback(key, value, null);
            }

            long startTime = debugEnabled ? System.currentTimeMillis() : 0;
            try (Jedis jedis = jedisPool.getResource()) {
                String result = jedis.set(key, value);
                if (debugEnabled)
                    logSlowOperation("SET", key, startTime);
                if (memoryFallbackEnabled) {
                    setFallback(key, value, null);
                }
                return result;
            } catch (Exception e) {
                handleRedisError("SET", key, e);
                return setFallback(key, value, null);
            }
        });
    }

    public CompletableFuture<String> setex(String key, int seconds, String value) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncRedis("setex");

            if (!isConnected()) {
                return setFallback(key, value, seconds);
            }

            long startTime = debugEnabled ? System.currentTimeMillis() : 0;
            try (Jedis jedis = jedisPool.getResource()) {
                String result = jedis.setex(key, seconds, value);
                if (debugEnabled)
                    logSlowOperation("SETEX", key, startTime);
                if (memoryFallbackEnabled) {
                    setFallback(key, value, seconds);
                }
                return result;
            } catch (Exception e) {
                handleRedisError("SETEX", key, e);
                return setFallback(key, value, seconds);
            }
        });
    }

    public CompletableFuture<Long> del(String key) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncRedis("del");
            if (!isConnected()) {
                deleteFallback(key);
                return 1L;
            }

            long startTime = debugEnabled ? System.currentTimeMillis() : 0;
            try (Jedis jedis = jedisPool.getResource()) {
                Long result = jedis.del(key);
                if (debugEnabled)
                    logSlowOperation("DEL", key, startTime);
                if (memoryFallbackEnabled) {
                    deleteFallback(key);
                }
                return result;
            } catch (Exception e) {
                handleRedisError("DEL", key, e);
                deleteFallback(key);
                return 1L;
            }
        });
    }

    public CompletableFuture<Long> exists(String key) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncRedis("exists");
            if (!isConnected()) {
                return fallbackStorage.containsKey(key) ? 1L : 0L;
            }

            long startTime = debugEnabled ? System.currentTimeMillis() : 0;
            try (Jedis jedis = jedisPool.getResource()) {
                boolean exists = jedis.exists(key);
                Long result = exists ? 1L : 0L;
                if (debugEnabled)
                    logSlowOperation("EXISTS", key, startTime);
                return result;
            } catch (Exception e) {
                handleRedisError("EXISTS", key, e);
                return fallbackStorage.containsKey(key) ? 1L : 0L;
            }
        });
    }

    public CompletableFuture<Long> expire(String key, int seconds) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncRedis("expire");
            if (!isConnected()) {
                updateFallbackExpiry(key, seconds);
                return 1L;
            }

            long startTime = debugEnabled ? System.currentTimeMillis() : 0;
            try (Jedis jedis = jedisPool.getResource()) {
                Long result = jedis.expire(key, seconds);
                if (debugEnabled)
                    logSlowOperation("EXPIRE", key, startTime);

                if (memoryFallbackEnabled && result != null && result > 0) {
                    updateFallbackExpiry(key, seconds);
                }
                return result;
            } catch (Exception e) {
                handleRedisError("EXPIRE", key, e);
                updateFallbackExpiry(key, seconds);
                return 1L;
            }
        });
    }

    public CompletableFuture<Long> lpush(String key, String... values) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncRedis("lpush");
            if (!isConnected()) {
                return lpushFallback(key, values);
            }

            long startTime = debugEnabled ? System.currentTimeMillis() : 0;
            try (Jedis jedis = jedisPool.getResource()) {
                Long result = jedis.lpush(key, values);
                if (debugEnabled)
                    logSlowOperation("LPUSH", key, startTime);
                if (memoryFallbackEnabled) {
                    lpushFallback(key, values);
                }
                return result;
            } catch (Exception e) {
                handleRedisError("LPUSH", key, e);
                return lpushFallback(key, values);
            }
        });
    }

    public CompletableFuture<String> lpop(String key) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncRedis("lpop");
            if (!isConnected()) {
                return lpopFallback(key);
            }

            long startTime = debugEnabled ? System.currentTimeMillis() : 0;
            try (Jedis jedis = jedisPool.getResource()) {
                String result = jedis.lpop(key);
                if (debugEnabled)
                    logSlowOperation("LPOP", key, startTime);
                if (memoryFallbackEnabled) {
                    lpopFallback(key);
                }
                return result;
            } catch (Exception e) {
                handleRedisError("LPOP", key, e);
                return lpopFallback(key);
            }
        });
    }

    public CompletableFuture<Long> llen(String key) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncRedis("llen");
            if (!isConnected()) {
                return llenFallback(key);
            }

            long startTime = debugEnabled ? System.currentTimeMillis() : 0;
            try (Jedis jedis = jedisPool.getResource()) {
                Long result = jedis.llen(key);
                if (debugEnabled)
                    logSlowOperation("LLEN", key, startTime);
                return result;
            } catch (Exception e) {
                handleRedisError("LLEN", key, e);
                return llenFallback(key);
            }
        });
    }

    public CompletableFuture<String> hget(String key, String field) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncRedis("hget");
            if (!isConnected()) {
                return hgetFallback(key, field);
            }

            long startTime = debugEnabled ? System.currentTimeMillis() : 0;
            try (Jedis jedis = jedisPool.getResource()) {
                String result = jedis.hget(key, field);
                if (debugEnabled)
                    logSlowOperation("HGET", key + ":" + field, startTime);
                return result;
            } catch (Exception e) {
                handleRedisError("HGET", key + ":" + field, e);
                return hgetFallback(key, field);
            }
        });
    }

    public CompletableFuture<String> hset(String key, String field, String value) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncRedis("hset");
            if (!isConnected()) {
                return hsetFallback(key, field, value);
            }

            long startTime = debugEnabled ? System.currentTimeMillis() : 0;
            try (Jedis jedis = jedisPool.getResource()) {
                Long result = jedis.hset(key, field, value);
                if (debugEnabled)
                    logSlowOperation("HSET", key + ":" + field, startTime);
                if (memoryFallbackEnabled) {
                    hsetFallback(key, field, value);
                }
                return result.toString();
            } catch (Exception e) {
                handleRedisError("HSET", key + ":" + field, e);
                return hsetFallback(key, field, value);
            }
        });
    }

    public CompletableFuture<Long> hdel(String key, String field) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncRedis("hdel");
            if (!isConnected()) {
                hdelFallback(key, field);
                return 1L;
            }

            long startTime = debugEnabled ? System.currentTimeMillis() : 0;
            try (Jedis jedis = jedisPool.getResource()) {
                Long result = jedis.hdel(key, field);
                if (debugEnabled)
                    logSlowOperation("HDEL", key + ":" + field, startTime);
                if (memoryFallbackEnabled) {
                    hdelFallback(key, field);
                }
                return result;
            } catch (Exception e) {
                handleRedisError("HDEL", key + ":" + field, e);
                hdelFallback(key, field);
                return 1L;
            }
        });
    }

    public int getCooldownsTtl() {
        return cooldownsTtl;
    }

    public int getDelaysTtl() {
        return delaysTtl;
    }

    public int getLocationCacheTtl() {
        return locationCacheTtl;
    }

    public int getTeleportRequestsTtl() {
        return teleportRequestsTtl;
    }

    public int getTeleportQueueTtl() {
        return teleportQueueTtl;
    }

    private enum FallbackDataType {
        STRING,
        LIST,
        HASH
    }

    private boolean canUseDatabaseFallback() {
        return mysqlFallbackEnabled && databaseManager != null && databaseManager.isConnected();
    }

    private Optional<DatabaseManager.RedisFallbackEntry> fetchFallbackEntry(String key) {
        if (!canUseDatabaseFallback()) {
            return Optional.empty();
        }
        try {
            return databaseManager.getRedisFallbackEntry(key).join();
        } catch (Exception e) {
            if (debugEnabled) {
                plugin.getRTPLogger().debug("SQL",
                        "[Redis-Fallback] Failed to fetch entry for key " + key + ": " + e.getMessage());
            }
            return Optional.empty();
        }
    }

    private boolean isExpired(String key) {
        Long expiresAt = fallbackExpiry.get(key);
        if (expiresAt != null && expiresAt <= System.currentTimeMillis()) {
            fallbackStorage.remove(key);
            fallbackExpiry.remove(key);
            if (canUseDatabaseFallback()) {
                databaseManager.deleteRedisFallbackValue(key).exceptionally(ex -> {
                    if (debugEnabled) {
                        plugin.getRTPLogger().debug("SQL",
                                "[Redis-Fallback] Failed to purge expired key " + key + ": " + ex.getMessage());
                    }
                    return null;
                });
            }
            return true;
        }
        return false;
    }

    private void persistFallbackEntry(String key, FallbackDataType type, Object value, Integer ttlSeconds) {
        if (ttlSeconds == null) {
            fallbackExpiry.remove(key);
        } else if (ttlSeconds == TTL_PRESERVE) {
        } else if (ttlSeconds > 0) {
            fallbackExpiry.put(key, System.currentTimeMillis() + ttlSeconds * 1000L);
        } else {
            fallbackExpiry.remove(key);
        }

        if (!mysqlFallbackEnabled || !canUseDatabaseFallback()) {
            return;
        }

        Long expiresAt = fallbackExpiry.get(key);
        if (ttlSeconds != null && ttlSeconds != TTL_PRESERVE && ttlSeconds <= 0) {
            expiresAt = null;
        }

        String payload;
        switch (type) {
            case STRING -> payload = value != null ? value.toString() : "";
            case LIST -> {
                @SuppressWarnings("unchecked")
                var deque = (ConcurrentLinkedDeque<String>) value;
                payload = gson.toJson(new ArrayList<>(deque));
            }
            case HASH -> {
                @SuppressWarnings("unchecked")
                var map = (ConcurrentHashMap<String, String>) value;
                payload = gson.toJson(new HashMap<>(map));
            }
            default -> payload = "";
        }

        String finalPayload = payload;
        databaseManager.setRedisFallbackValue(key, type.name(), finalPayload, expiresAt)
                .exceptionally(ex -> {
                    if (debugEnabled) {
                        plugin.getRTPLogger().debug("SQL",
                                "[Redis-Fallback] Failed to persist key " + key + ": " + ex.getMessage());
                    }
                    return null;
                });
    }

    private String getFallback(String key) {
        if (isExpired(key)) {
            return null;
        }

        Object value = fallbackStorage.get(key);
        if (value instanceof String str) {
            return str;
        }

        if (value == null) {
            Optional<DatabaseManager.RedisFallbackEntry> entryOpt = fetchFallbackEntry(key);
            if (entryOpt.isPresent() && FallbackDataType.STRING.name().equals(entryOpt.get().dataType())) {
                String payload = entryOpt.get().payload();
                fallbackStorage.put(key, payload);
                if (entryOpt.get().expiresAt() != null) {
                    fallbackExpiry.put(key, entryOpt.get().expiresAt());
                }
                return payload;
            }
        }
        return null;
    }

    private String setFallback(String key, String value, Integer ttlSeconds) {
        fallbackStorage.put(key, value);
        persistFallbackEntry(key, FallbackDataType.STRING, value, ttlSeconds);
        return "OK";
    }

    private void deleteFallback(String key) {
        fallbackStorage.remove(key);
        fallbackExpiry.remove(key);
        if (canUseDatabaseFallback()) {
            databaseManager.deleteRedisFallbackValue(key).exceptionally(ex -> {
                if (debugEnabled) {
                    plugin.getRTPLogger().debug("SQL",
                            "[Redis-Fallback] Failed to delete key " + key + ": " + ex.getMessage());
                }
                return null;
            });
        }
    }

    @SuppressWarnings("unchecked")
    private ConcurrentLinkedDeque<String> getListFallback(String key) {
        if (isExpired(key)) {
            return null;
        }

        Object stored = fallbackStorage.get(key);
        if (stored instanceof ConcurrentLinkedDeque) {
            return (ConcurrentLinkedDeque<String>) stored;
        }

        Optional<DatabaseManager.RedisFallbackEntry> entryOpt = fetchFallbackEntry(key);
        if (entryOpt.isPresent() && FallbackDataType.LIST.name().equals(entryOpt.get().dataType())) {
            List<String> list = gson.fromJson(entryOpt.get().payload(), LIST_TYPE);
            ConcurrentLinkedDeque<String> deque = new ConcurrentLinkedDeque<>(list);
            fallbackStorage.put(key, deque);
            if (entryOpt.get().expiresAt() != null) {
                fallbackExpiry.put(key, entryOpt.get().expiresAt());
            }
            return deque;
        }
        return null;
    }

    private void persistListFallback(String key, ConcurrentLinkedDeque<String> deque) {
        persistFallbackEntry(key, FallbackDataType.LIST, deque, TTL_PRESERVE);
    }

    private Long lpushFallback(String key, String... values) {
        ConcurrentLinkedDeque<String> deque = getListFallback(key);
        if (deque == null) {
            deque = new ConcurrentLinkedDeque<>();
            fallbackStorage.put(key, deque);
        }
        for (String value : values) {
            deque.addFirst(value);
        }
        persistListFallback(key, deque);
        return (long) deque.size();
    }

    private String lpopFallback(String key) {
        ConcurrentLinkedDeque<String> deque = getListFallback(key);
        if (deque == null) {
            return null;
        }
        String value = deque.pollFirst();
        if (deque.isEmpty()) {
            fallbackStorage.remove(key, deque);
            deleteFallback(key);
        } else {
            persistListFallback(key, deque);
        }
        return value;
    }

    private Long llenFallback(String key) {
        ConcurrentLinkedDeque<String> deque = getListFallback(key);
        return deque != null ? (long) deque.size() : 0L;
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, String> getHashFallback(String key) {
        if (isExpired(key)) {
            return null;
        }

        Object stored = fallbackStorage.get(key);
        if (stored instanceof ConcurrentHashMap) {
            return (ConcurrentHashMap<String, String>) stored;
        }

        Optional<DatabaseManager.RedisFallbackEntry> entryOpt = fetchFallbackEntry(key);
        if (entryOpt.isPresent() && FallbackDataType.HASH.name().equals(entryOpt.get().dataType())) {
            Map<String, String> map = gson.fromJson(entryOpt.get().payload(), MAP_TYPE);
            ConcurrentHashMap<String, String> concurrentMap = new ConcurrentHashMap<>(map);
            fallbackStorage.put(key, concurrentMap);
            if (entryOpt.get().expiresAt() != null) {
                fallbackExpiry.put(key, entryOpt.get().expiresAt());
            }
            return concurrentMap;
        }
        return null;
    }

    private void persistHashFallback(String key, ConcurrentHashMap<String, String> map) {
        persistFallbackEntry(key, FallbackDataType.HASH, map, TTL_PRESERVE);
    }

    private String hgetFallback(String key, String field) {
        ConcurrentHashMap<String, String> map = getHashFallback(key);
        if (map == null) {
            return null;
        }
        return map.get(field);
    }

    private String hsetFallback(String key, String field, String value) {
        ConcurrentHashMap<String, String> map = getHashFallback(key);
        if (map == null) {
            map = new ConcurrentHashMap<>();
            fallbackStorage.put(key, map);
        }
        map.put(field, value);
        persistHashFallback(key, map);
        return "1";
    }

    private void hdelFallback(String key, String field) {
        ConcurrentHashMap<String, String> map = getHashFallback(key);
        if (map == null) {
            return;
        }
        map.remove(field);
        if (map.isEmpty()) {
            deleteFallback(key);
        } else {
            persistHashFallback(key, map);
        }
    }

    private void updateFallbackExpiry(String key, int seconds) {
        Long expiresAt = null;
        if (seconds > 0) {
            expiresAt = System.currentTimeMillis() + seconds * 1000L;
            fallbackExpiry.put(key, expiresAt);
        } else {
            fallbackExpiry.remove(key);
        }

        if (canUseDatabaseFallback()) {
            Long finalExpiresAt = expiresAt;
            databaseManager.updateRedisFallbackExpiry(key, finalExpiresAt).exceptionally(ex -> {
                if (debugEnabled) {
                    plugin.getRTPLogger().debug("SQL",
                            "[Redis-Fallback] Failed to update expiry for key " + key + ": " + ex.getMessage());
                }
                return null;
            });
        }
    }

    private void logSlowOperation(String operation, String key, long startTime) {
        if (debugEnabled) {
            long duration = System.currentTimeMillis() - startTime;
            if (duration > slowOperationThreshold) {
                plugin.getRTPLogger().debug("REDIS",
                        "[Redis] Slow " + operation + " operation on key '" + key + "' took " + duration + "ms");
            }
        }
    }

    private void handleRedisError(String operation, String key, Exception e) {
        if (e instanceof JedisConnectionException) {
            connected = false;
            if (autoReconnect && debugEnabled) {
                plugin.getRTPLogger().debug("REDIS", "[Redis] Connection lost during " + operation + " on key '" + key
                        + "', will attempt reconnect");
            }
        } else if (debugEnabled) {
            plugin.getRTPLogger().debug("REDIS",
                    "[Redis] Error during " + operation + " on key '" + key + "': " + e.getMessage());
        }
    }

    public void reload() {
        plugin.getRTPLogger().debug("REDIS", "[Redis] Reloading Redis configuration...");
        loadConfiguration();

        if (enabled) {
            connect();
            if (connected && autoReconnect) {
                startReconnectTask();
            }
        } else {
            shutdown();
        }
    }

    public void shutdown() {
        plugin.getRTPLogger().debug("REDIS", "[Redis] Shutting down Redis manager...");

        try {
            if (pubSubListener != null) {
                pubSubListener.unsubscribe();
            }
        } catch (Exception ignored) {
        }

        try {
            if (jedisPool != null && !jedisPool.isClosed()) {
                jedisPool.close();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error closing Redis connection pool", e);
        }

        try {
            reconnectExecutor.shutdown();
            if (!reconnectExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                reconnectExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            reconnectExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        connected = false;
        fallbackStorage.clear();
        plugin.getRTPLogger().debug("REDIS", "[Redis] Redis manager shutdown complete");
    }

    public void executePipeline(List<Runnable> operations) {
        if (!isConnected() || !usePipelining) {
            operations.forEach(Runnable::run);
            return;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            Pipeline pipeline = jedis.pipelined();

            operations.forEach(Runnable::run);

            pipeline.sync();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error executing Redis pipeline", e);
            operations.forEach(Runnable::run);
        }
    }

    public CompletableFuture<Void> subscribe(String channel, java.util.function.Consumer<String> messageHandler) {
        return CompletableFuture.runAsync(() -> {
            threadGuard.assertAsyncRedis("subscribe");
            if (!isConnected() || !usePubSub) {
                plugin.getRTPLogger().debug("REDIS",
                        "[Redis-PubSub] Pub/Sub not available - Redis not connected or disabled");

                return;
            }

            try {
                plugin.getRTPLogger().debug("REDIS", "[Redis-PubSub] Subscribing to channel: " + channel);

                Thread subThread = new Thread(() -> {
                    try (Jedis jedis = jedisPool.getResource()) {
                        jedis.subscribe(new JedisPubSub() {
                            @Override
                            public void onMessage(String ch, String message) {
                                if (ch.equals(channel)) {
                                    try {
                                        messageHandler.accept(message);
                                    } catch (Exception e) {
                                        plugin.getLogger().log(Level.WARNING,
                                                "[Redis-PubSub] Error handling message on channel " + channel, e);
                                    }
                                }
                            }

                            @Override
                            public void onSubscribe(String ch, int subscribedChannels) {
                                plugin.getRTPLogger().debug("REDIS", "[Redis-PubSub] Subscribed to channel: " + ch);
                            }

                            @Override
                            public void onUnsubscribe(String ch, int subscribedChannels) {
                                plugin.getRTPLogger().debug("REDIS", "[Redis-PubSub] Unsubscribed from channel: " + ch);
                            }
                        }, channel);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.WARNING,
                                "[Redis-PubSub] Error in subscription thread for channel: " + channel, e);
                    }
                }, "JustRTP-Redis-PubSub-" + channel);

                subThread.setDaemon(true);
                subThread.start();

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING,
                        "[Redis-PubSub] Failed to subscribe to channel: " + channel, e);
            }
        });
    }

    public CompletableFuture<Long> publish(String channel, String message) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncRedis("publish");
            if (!isConnected() || !usePubSub) {
                plugin.getRTPLogger().debug("REDIS",
                        "[Redis-PubSub] Pub/Sub not available - Redis not connected or disabled");

                return 0L;
            }

            long startTime = System.currentTimeMillis();
            try (Jedis jedis = jedisPool.getResource()) {
                Long subscribers = jedis.publish(channel, message);
                logSlowOperation("PUBLISH", channel, startTime);
                plugin.getRTPLogger().debug("REDIS",
                        "[Redis-PubSub] Published message to " + channel + ", received by " + subscribers
                                + " subscribers");
                return subscribers;
            } catch (Exception e) {
                handleRedisError("PUBLISH", channel, e);
                return 0L;
            }
        });
    }

    public boolean isPubSubEnabled() {
        return enabled && connected && usePubSub;
    }

    public Map<String, String> getConnectionInfo() {
        Map<String, String> info = new HashMap<>();

        try {
            File redisFile = new File(plugin.getDataFolder(), "redis.yml");
            FileConfiguration config = YamlConfiguration.loadConfiguration(redisFile);

            info.put("enabled", String.valueOf(enabled));
            info.put("host", config.getString("connection.host", "localhost"));
            info.put("port", String.valueOf(config.getInt("connection.port", 6379)));
            info.put("database", String.valueOf(config.getInt("connection.database", 0)));
            info.put("ssl", String.valueOf(config.getBoolean("connection.ssl", false)));
            info.put("connected", String.valueOf(connected));

            if (jedisPool != null && !jedisPool.isClosed()) {
                info.put("pool_active", String.valueOf(jedisPool.getNumActive()));
                info.put("pool_idle", String.valueOf(jedisPool.getNumIdle()));
                info.put("pool_waiting", String.valueOf(jedisPool.getNumWaiters()));
            }

            info.put("cooldowns_enabled", String.valueOf(cooldownsEnabled));
            info.put("delays_enabled", String.valueOf(delaysEnabled));
            info.put("location_cache_enabled", String.valueOf(locationCacheEnabled));
            info.put("teleport_requests_enabled", String.valueOf(teleportRequestsEnabled));
            info.put("pubsub_enabled", String.valueOf(usePubSub));
        } catch (Exception e) {
            plugin.getRTPLogger().debug("REDIS", "Failed to get Redis connection info: " + e.getMessage());
        }

        return info;
    }
}