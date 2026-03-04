package eu.kotori.justRTP.managers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import eu.kotori.justRTP.JustRTP;
import eu.kotori.justRTP.utils.DataManager;
import eu.kotori.justRTP.utils.DatabaseManager;
import eu.kotori.justRTP.utils.task.CancellableTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class ZoneSyncManager {

    private final JustRTP plugin;
    private final Gson gson;
    private final DataManager dataManager;
    private final DatabaseManager databaseManager;
    private CancellableTask syncTask;

    private static final String REDIS_ZONE_KEY_PREFIX = "justrtp:zones:";
    private static final String REDIS_ZONE_METADATA_KEY = "justrtp:zone_metadata";

    public ZoneSyncManager(JustRTP plugin) {
        this.plugin = plugin;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        this.dataManager = plugin.getDataManager();
        this.databaseManager = plugin.getDatabaseManager();
    }

    public void initialize() {
        if (!plugin.getConfigManager().isZoneSyncEnabled()) {
            plugin.getRTPLogger().debug("SYNC", "[ZoneSync] Zone synchronization is disabled in config");

            return;
        }

        plugin.getLogger().info("[ZoneSync] Initializing zone synchronization...");

        if (shouldUseMysql()) {
            createMysqlTable();
        }

        startSyncTask();

        if (shouldUseRedis() && plugin.getConfigManager().isRedisPubSubEnabled()) {
            subscribeToZoneUpdates();
        }

        plugin.getLogger().info("[ZoneSync] Zone synchronization initialized successfully");
    }

    private void startSyncTask() {
        if (syncTask != null && !syncTask.isCancelled()) {
            syncTask.cancel();
        }

        int interval = plugin.getConfigManager().getZoneSyncInterval();
        String mode = plugin.getConfigManager().getZoneSyncMode().toUpperCase();

        syncTask = plugin.getFoliaScheduler().runTimer(() -> {
            plugin.getFoliaScheduler().runAsync(() -> {
                try {
                    if ("PULL".equals(mode) || "BIDIRECTIONAL".equals(mode)) {
                        pullZones().exceptionally(throwable -> {
                            plugin.getLogger().warning("[ZoneSync] Error during zone pull: " + throwable.getMessage());
                            return null;
                        });
                    }

                    if ("PUSH".equals(mode) || "BIDIRECTIONAL".equals(mode)) {
                        pushZones().exceptionally(throwable -> {
                            plugin.getLogger().warning("[ZoneSync] Error during zone push: " + throwable.getMessage());
                            return null;
                        });
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("[ZoneSync] Unexpected error in sync task: " + e.getMessage());
                }
            });
        }, 20L * interval, 20L * interval);

        plugin.getRTPLogger().debug("SYNC",
                "[ZoneSync] Sync task started with interval: " + interval + " seconds, mode: " + mode);

    }

    public CompletableFuture<Boolean> pullZones() {
        plugin.getRTPLogger().debug("SYNC", "[ZoneSync] Starting zone pull...");

        String currentHash = calculateCurrentZoneHash();

        return getRemoteZoneHash().thenCompose(remoteHash -> {
            if (currentHash.equals(remoteHash) && !remoteHash.isEmpty()) {
                plugin.getRTPLogger().debug("SYNC", "[ZoneSync] Zones are already up to date (hash match)");

                return CompletableFuture.completedFuture(false);
            }

            return fetchRemoteZones().thenApply(remoteZones -> {
                if (remoteZones.isEmpty()) {
                    plugin.getRTPLogger().debug("SYNC", "[ZoneSync] No remote zones found");

                    return false;
                }

                applyRemoteZones(remoteZones);

                updateSyncMetadata(remoteHash);

                plugin.getLogger()
                        .info("[ZoneSync] Successfully pulled " + remoteZones.size() + " zones from remote storage");

                if (plugin.getConfigManager().isZoneSyncAutoReload()) {
                    plugin.getFoliaScheduler().runNow(() -> {
                        plugin.getRtpZoneManager().loadZones();
                        notifyAdmins("zones_pulled", remoteZones.size());
                    });
                }

                return true;
            });
        }).exceptionally(ex -> {
            plugin.getLogger().warning("[ZoneSync] Failed to pull zones: " + ex.getMessage());
            ex.printStackTrace();
            return false;
        });
    }

    public CompletableFuture<Boolean> pushZones() {
        plugin.getRTPLogger().debug("SYNC", "[ZoneSync] Starting zone push...");

        Map<String, String> localZones = serializeLocalZones();
        if (localZones.isEmpty()) {
            plugin.getRTPLogger().debug("SYNC", "[ZoneSync] No local zones to push");

            return CompletableFuture.completedFuture(false);
        }

        String currentHash = calculateCurrentZoneHash();

        CompletableFuture<Void> pushFuture = CompletableFuture.completedFuture(null);

        if (shouldUseRedis()) {
            pushToRedis(localZones, currentHash);
        }

        if (shouldUseMysql()) {
            pushFuture = pushToMysql(localZones, currentHash);
        }

        return pushFuture.thenApply(v -> {
            updateSyncMetadata(currentHash);

            plugin.getLogger().info("[ZoneSync] Successfully pushed " + localZones.size() + " zones to remote storage");

            if (shouldUseRedis() && plugin.getConfigManager().isRedisPubSubEnabled()) {
                publishZoneUpdate();
            }

            notifyAdmins("zones_pushed", localZones.size());
            return true;
        }).exceptionally(ex -> {
            plugin.getLogger().warning("[ZoneSync] Failed to push zones: " + ex.getMessage());
            ex.printStackTrace();
            return false;
        });
    }

    private CompletableFuture<Map<String, String>> fetchRemoteZones() {
        if (shouldUseRedis()) {
            return fetchFromRedisAsync()
                    .thenCompose(redisZones -> {
                        if (!redisZones.isEmpty()) {
                            plugin.getRTPLogger().debug("SYNC",
                                    "[ZoneSync] Fetched " + redisZones.size() + " zones from Redis");

                            return CompletableFuture.completedFuture(redisZones);
                        }

                        if (shouldUseMysql()) {
                            return fetchFromMysql()
                                    .thenApply(mysqlZones -> {
                                        if (!mysqlZones.isEmpty()) {
                                            plugin.getRTPLogger().debug("SYNC", "[ZoneSync] Fetched "
                                                    + mysqlZones.size() + " zones from MySQL (Redis fallback)");

                                        }
                                        return mysqlZones;
                                    });
                        }

                        return CompletableFuture.completedFuture(new HashMap<>());
                    });
        }

        if (shouldUseMysql()) {
            return fetchFromMysql()
                    .thenApply(mysqlZones -> {
                        if (!mysqlZones.isEmpty()) {
                            plugin.getRTPLogger().debug("SYNC",
                                    "[ZoneSync] Fetched " + mysqlZones.size() + " zones from MySQL");

                        }
                        return mysqlZones;
                    });
        }

        return CompletableFuture.completedFuture(new HashMap<>());
    }

    private CompletableFuture<Map<String, String>> fetchFromRedisAsync() {
        if (dataManager == null || !dataManager.isRedisConnected()) {
            return CompletableFuture.completedFuture(new HashMap<>());
        }

        CompletableFuture<Map<String, String>> future = new CompletableFuture<>();

        plugin.getFoliaScheduler().runAsync(() -> {
            Map<String, String> zones = new ConcurrentHashMap<>();

            try {
                File zonesFile = new File(plugin.getDataFolder(), "rtp_zones.yml");
                if (zonesFile.exists()) {
                    FileConfiguration zonesConfig = YamlConfiguration.loadConfiguration(zonesFile);
                    ConfigurationSection zonesSection = zonesConfig.getConfigurationSection("zones");

                    if (zonesSection != null) {
                        List<CompletableFuture<Void>> futures = new ArrayList<>();

                        for (String zoneId : zonesSection.getKeys(false)) {
                            String key = REDIS_ZONE_KEY_PREFIX + zoneId;
                            CompletableFuture<Void> redisFuture = dataManager.getString(key)
                                    .thenAccept(optionalData -> {
                                        optionalData.ifPresent(data -> {
                                            zones.put(zoneId, data);
                                        });
                                    });
                            futures.add(redisFuture);
                        }

                        if (!futures.isEmpty()) {
                            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                    .thenRun(() -> future.complete(zones))
                                    .exceptionally(ex -> {
                                        plugin.getLogger().warning(
                                                "[ZoneSync] Error fetching zones from Redis: " + ex.getMessage());
                                        future.complete(zones);
                                        return null;
                                    });
                        } else {
                            future.complete(zones);
                        }
                    } else {
                        future.complete(zones);
                    }
                } else {
                    future.complete(zones);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[ZoneSync] Error fetching zones from Redis: " + e.getMessage());
                future.complete(zones);
            }
        });

        return future;
    }

    private CompletableFuture<Map<String, String>> fetchFromMysql() {
        if (databaseManager == null || !databaseManager.isConnected()) {
            return CompletableFuture.completedFuture(new HashMap<>());
        }

        return databaseManager.getAllZones()
                .thenApply(zones -> {
                    if (!zones.isEmpty()) {
                        plugin.getRTPLogger().debug("SYNC", "[ZoneSync] Fetched " + zones.size() + " zones from MySQL");

                    }
                    return zones;
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("[ZoneSync] Error fetching zones from MySQL: " + ex.getMessage());
                    return new HashMap<>();
                });
    }

    private void pushToRedis(Map<String, String> zones, String hash) {
        if (dataManager == null || !dataManager.isRedisConnected()) {
            return;
        }

        try {
            for (Map.Entry<String, String> entry : zones.entrySet()) {
                String key = REDIS_ZONE_KEY_PREFIX + entry.getKey();
                dataManager.setString(key, entry.getValue(), 0);
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("hash", hash);
            metadata.put("server", plugin.getConfigManager().getProxyThisServerName());
            metadata.put("timestamp", System.currentTimeMillis());
            metadata.put("version", getNextSyncVersion());

            String metadataJson = gson.toJson(metadata);
            dataManager.setString(REDIS_ZONE_METADATA_KEY, metadataJson, 0);

            plugin.getRTPLogger().debug("SYNC", "[ZoneSync] Pushed zones to Redis successfully");

        } catch (Exception e) {
            plugin.getLogger().warning("[ZoneSync] Error pushing zones to Redis: " + e.getMessage());
        }
    }

    private CompletableFuture<Void> pushToMysql(Map<String, String> zones, String hash) {
        if (databaseManager == null || !databaseManager.isConnected()) {
            return CompletableFuture.completedFuture(null);
        }

        String serverName = plugin.getConfigManager().getProxyThisServerName();
        int version = getNextSyncVersion();
        long timestamp = System.currentTimeMillis();

        return databaseManager.saveZonesBatch(zones, serverName)
                .thenCompose(v -> databaseManager.saveZoneMetadata(hash, serverName, version, timestamp))
                .thenAccept(v -> plugin.getRTPLogger().debug("SYNC",
                        "[ZoneSync] Pushed " + zones.size() + " zones to MySQL successfully"))

                .exceptionally(ex -> {
                    plugin.getLogger().warning("[ZoneSync] Error pushing zones to MySQL: " + ex.getMessage());
                    return null;
                });
    }

    private void applyRemoteZones(Map<String, String> remoteZones) {
        try {
            File zonesFile = new File(plugin.getDataFolder(), "rtp_zones.yml");
            FileConfiguration zonesConfig = YamlConfiguration.loadConfiguration(zonesFile);

            synchronized (zonesFile) {
                zonesConfig.set("zones", null);

                ConfigurationSection zonesSection = zonesConfig.createSection("zones");

                for (Map.Entry<String, String> entry : remoteZones.entrySet()) {
                    String zoneId = entry.getKey();
                    String zoneData = entry.getValue();

                    try {
                        Type type = new TypeToken<Map<String, Object>>() {
                        }.getType();
                        Map<String, Object> zoneMap = gson.fromJson(zoneData, type);

                        ConfigurationSection zoneSection = zonesSection.createSection(zoneId);
                        applyMapToSection(zoneSection, zoneMap);
                    } catch (Exception e) {
                        plugin.getLogger()
                                .warning("[ZoneSync] Failed to apply zone '" + zoneId + "': " + e.getMessage());
                    }
                }

                zonesConfig.save(zonesFile);
            }

            plugin.getRTPLogger().debug("SYNC",
                    "[ZoneSync] Applied " + remoteZones.size() + " zones to local configuration");

        } catch (IOException e) {
            plugin.getLogger().severe("[ZoneSync] Failed to save zones configuration: " + e.getMessage());
        }
    }

    private Map<String, String> serializeLocalZones() {
        Map<String, String> serializedZones = new HashMap<>();

        try {
            File zonesFile = new File(plugin.getDataFolder(), "rtp_zones.yml");
            FileConfiguration zonesConfig = YamlConfiguration.loadConfiguration(zonesFile);

            ConfigurationSection zonesSection = zonesConfig.getConfigurationSection("zones");
            if (zonesSection == null) {
                return serializedZones;
            }

            for (String zoneId : zonesSection.getKeys(false)) {
                ConfigurationSection zoneSection = zonesSection.getConfigurationSection(zoneId);
                if (zoneSection != null) {
                    Map<String, Object> zoneMap = sectionToMap(zoneSection);
                    String zoneJson = gson.toJson(zoneMap);
                    serializedZones.put(zoneId, zoneJson);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[ZoneSync] Failed to serialize local zones: " + e.getMessage());
        }

        return serializedZones;
    }

    private Map<String, Object> sectionToMap(ConfigurationSection section) {
        Map<String, Object> map = new HashMap<>();

        for (String key : section.getKeys(false)) {
            Object value = section.get(key);

            if (value instanceof ConfigurationSection) {
                map.put(key, sectionToMap((ConfigurationSection) value));
            } else {
                map.put(key, value);
            }
        }

        return map;
    }

    @SuppressWarnings("unchecked")
    private void applyMapToSection(ConfigurationSection section, Map<String, Object> map) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                ConfigurationSection subSection = section.createSection(key);
                applyMapToSection(subSection, (Map<String, Object>) value);
            } else {
                section.set(key, value);
            }
        }
    }

    private String calculateCurrentZoneHash() {
        try {
            File zonesFile = new File(plugin.getDataFolder(), "rtp_zones.yml");
            if (!zonesFile.exists()) {
                return "";
            }

            FileConfiguration zonesConfig = YamlConfiguration.loadConfiguration(zonesFile);
            ConfigurationSection zonesSection = zonesConfig.getConfigurationSection("zones");

            if (zonesSection == null) {
                return "";
            }

            Map<String, Object> zonesMap = sectionToMap(zonesSection);
            String zonesString = gson.toJson(zonesMap);
            return generateMD5Hash(zonesString);
        } catch (Exception e) {
            plugin.getLogger().warning("[ZoneSync] Failed to calculate zone hash: " + e.getMessage());
            return "";
        }
    }

    private CompletableFuture<String> getRemoteZoneHash() {
        if (shouldUseRedis() && dataManager != null && dataManager.isRedisConnected()) {
            return dataManager.getString(REDIS_ZONE_METADATA_KEY)
                    .thenApply(metadataOpt -> {
                        if (metadataOpt.isPresent()) {
                            String metadataJson = metadataOpt.get();
                            Type type = new TypeToken<Map<String, Object>>() {
                            }.getType();
                            Map<String, Object> metadata = gson.fromJson(metadataJson, type);
                            return (String) metadata.getOrDefault("hash", "");
                        }
                        return "";
                    })
                    .exceptionally(ex -> {
                        plugin.getRTPLogger().debug("SYNC",
                                "[ZoneSync] Error getting hash from Redis: " + ex.getMessage());

                        return "";
                    });
        }

        if (shouldUseMysql() && databaseManager != null && databaseManager.isConnected()) {
            plugin.getRTPLogger().debug("SYNC",
                    "[ZoneSync] MySQL hash retrieval requires extended DatabaseManager - feature pending");

        }

        return CompletableFuture.completedFuture("");
    }

    private void updateSyncMetadata(String hash) {
        try {
            File zonesFile = new File(plugin.getDataFolder(), "rtp_zones.yml");
            FileConfiguration zonesConfig = YamlConfiguration.loadConfiguration(zonesFile);

            zonesConfig.set("sync-metadata.last-sync-time", System.currentTimeMillis());
            zonesConfig.set("sync-metadata.last-sync-server", plugin.getConfigManager().getProxyThisServerName());
            zonesConfig.set("sync-metadata.sync-version", getNextSyncVersion());
            zonesConfig.set("sync-metadata.config-hash", hash);

            zonesConfig.save(zonesFile);
        } catch (IOException e) {
            plugin.getLogger().warning("[ZoneSync] Failed to update sync metadata: " + e.getMessage());
        }
    }

    private int getNextSyncVersion() {
        try {
            File zonesFile = new File(plugin.getDataFolder(), "rtp_zones.yml");
            if (!zonesFile.exists()) {
                return 1;
            }

            FileConfiguration zonesConfig = YamlConfiguration.loadConfiguration(zonesFile);
            int currentVersion = zonesConfig.getInt("sync-metadata.sync-version", 0);
            return currentVersion + 1;
        } catch (Exception e) {
            return 1;
        }
    }

    private String generateMD5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1)
                    hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            plugin.getLogger().severe("[ZoneSync] MD5 algorithm not found: " + e.getMessage());
            return "";
        }
    }

    private void createMysqlTable() {
        if (databaseManager != null && databaseManager.isConnected()) {
            databaseManager.createZoneSyncTables();
            plugin.getRTPLogger().debug("SYNC", "[ZoneSync] MySQL zone sync tables created/verified");

        }
    }

    private void subscribeToZoneUpdates() {
        if (dataManager == null || !dataManager.isPubSubAvailable()) {
            plugin.getRTPLogger().debug("SYNC", "[ZoneSync] Redis pub/sub not available - using polling mode only");

            return;
        }

        String channel = REDIS_ZONE_KEY_PREFIX + "updates";

        dataManager.subscribe(channel, message -> {
            try {
                Type type = new TypeToken<Map<String, Object>>() {
                }.getType();
                Map<String, Object> notification = gson.fromJson(message, type);

                String event = (String) notification.get("event");
                String serverName = (String) notification.get("server");

                if (serverName.equals(plugin.getConfigManager().getProxyThisServerName())) {
                    return;
                }

                if ("zones_updated".equals(event)) {
                    plugin.getLogger().info("[ZoneSync] Received zone update notification from " + serverName);

                    String mode = plugin.getConfigManager().getZoneSyncMode().toUpperCase();
                    if ("PULL".equals(mode) || "BIDIRECTIONAL".equals(mode)) {
                        plugin.getFoliaScheduler().runAsync(() -> {
                            pullZones().exceptionally(throwable -> {
                                plugin.getLogger().warning("[ZoneSync] Failed to pull zones after notification: " +
                                        throwable.getMessage());
                                return null;
                            });
                        });
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[ZoneSync] Error handling pub/sub notification: " + e.getMessage());
            }
        }).thenAccept(v -> {
            plugin.getLogger()
                    .info("[ZoneSync] Subscribed to Redis pub/sub for instant zone updates on channel: " + channel);
        });
    }

    private void publishZoneUpdate() {
        if (dataManager == null || !dataManager.isPubSubAvailable()) {
            plugin.getRTPLogger().debug("SYNC", "[ZoneSync] Cannot publish zone update - Redis pub/sub not available");

            return;
        }

        String channel = REDIS_ZONE_KEY_PREFIX + "updates";

        Map<String, Object> notification = new HashMap<>();
        notification.put("event", "zones_updated");
        notification.put("server", plugin.getConfigManager().getProxyThisServerName());
        notification.put("timestamp", System.currentTimeMillis());
        notification.put("zone_count", getLocalZoneCount());

        String message = gson.toJson(notification);

        dataManager.publish(channel, message).thenAccept(subscribers -> {
            if (subscribers > 0) {
                plugin.getLogger().info("[ZoneSync] Published zone update notification to " + subscribers
                        + " subscribers on channel: " + channel);
            } else {
                plugin.getRTPLogger().debug("SYNC",
                        "[ZoneSync] Published zone update notification but no subscribers are listening");

            }
        }).exceptionally(throwable -> {
            plugin.getLogger().warning("[ZoneSync] Failed to publish zone update: " + throwable.getMessage());
            return null;
        });
    }

    private int getLocalZoneCount() {
        try {
            File zonesFile = new File(plugin.getDataFolder(), "rtp_zones.yml");
            if (!zonesFile.exists()) {
                return 0;
            }

            FileConfiguration zonesConfig = YamlConfiguration.loadConfiguration(zonesFile);
            ConfigurationSection zonesSection = zonesConfig.getConfigurationSection("zones");
            return zonesSection != null ? zonesSection.getKeys(false).size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void notifyAdmins(String messageKey, int zoneCount) {
        if (!plugin.getConfigManager().isZoneSyncNotifyEnabled()) {
            return;
        }

        plugin.getFoliaScheduler().runNow(() -> {
            Bukkit.getOnlinePlayers().stream()
                    .filter(player -> player.hasPermission("justrtp.admin"))
                    .forEach(player -> {
                        String message = "[ZoneSync] " + messageKey + ": " + zoneCount + " zones (server: " +
                                plugin.getConfigManager().getProxyThisServerName() + ")";
                        player.sendMessage(MiniMessage.miniMessage().deserialize("<green>" + message + "</green>"));
                    });
        });
    }

    private boolean shouldUseRedis() {
        String storage = plugin.getConfigManager().getZoneSyncStorage().toUpperCase();
        return ("REDIS".equals(storage) || "BOTH".equals(storage)) &&
                plugin.getConfigManager().isRedisEnabled() &&
                dataManager != null && dataManager.isRedisConnected();
    }

    private boolean shouldUseMysql() {
        String storage = plugin.getConfigManager().getZoneSyncStorage().toUpperCase();
        return ("MYSQL".equals(storage) || "BOTH".equals(storage)) &&
                plugin.getConfigManager().isProxyMySqlEnabled() &&
                databaseManager != null && databaseManager.isConnected();
    }

    public CompletableFuture<Map<String, Object>> getSyncStatus() {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> status = new HashMap<>();

            try {
                File zonesFile = new File(plugin.getDataFolder(), "rtp_zones.yml");
                FileConfiguration zonesConfig = YamlConfiguration.loadConfiguration(zonesFile);

                status.put("enabled", plugin.getConfigManager().isZoneSyncEnabled());
                status.put("mode", plugin.getConfigManager().getZoneSyncMode());
                status.put("storage", plugin.getConfigManager().getZoneSyncStorage());
                status.put("sync_interval", plugin.getConfigManager().getZoneSyncInterval());
                status.put("last_sync_time", zonesConfig.getLong("sync-metadata.last-sync-time", 0));
                status.put("last_sync_server", zonesConfig.getString("sync-metadata.last-sync-server", ""));
                status.put("sync_version", zonesConfig.getInt("sync-metadata.sync-version", 0));
                status.put("config_hash", zonesConfig.getString("sync-metadata.config-hash", ""));
                status.put("current_hash", calculateCurrentZoneHash());
                status.put("remote_hash", getRemoteZoneHash());
                status.put("redis_connected", dataManager != null && dataManager.isRedisConnected());
                status.put("mysql_connected", databaseManager != null && databaseManager.isConnected());

                ConfigurationSection zonesSection = zonesConfig.getConfigurationSection("zones");
                status.put("local_zone_count", zonesSection != null ? zonesSection.getKeys(false).size() : 0);
            } catch (Exception e) {
                status.put("error", e.getMessage());
            }

            return status;
        });
    }

    public void shutdown() {
        if (syncTask != null && !syncTask.isCancelled()) {
            syncTask.cancel();
        }
        plugin.getRTPLogger().debug("SYNC", "[ZoneSync] Zone synchronization shut down");

    }

    public void reload() {
        shutdown();
        initialize();
    }
}
