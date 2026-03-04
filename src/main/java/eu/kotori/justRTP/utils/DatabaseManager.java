package eu.kotori.justRTP.utils;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.kotori.justRTP.JustRTP;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;

public class DatabaseManager {
    public record ProxyTeleportRequest(
            UUID playerUUID, String originServer, String targetServer, String commandArgs, String targetWorld,
            Location location, String status, Integer minRadius, Integer maxRadius, String requestType) {
    }

    private enum RequestStatus {
        PENDING, PROCESSING, COMPLETE, FAILED, IN_TRANSFER, TRANSFER_CONFIRMED
    }

    public record RedisFallbackEntry(String dataType, String payload, Long expiresAt) {
    }

    private final JustRTP plugin;
    private final ThreadSafetyGuard threadGuard;
    private HikariDataSource dataSource;
    private boolean supportsSkipLocked = false;

    public DatabaseManager(JustRTP plugin, FoliaScheduler scheduler) {
        this.plugin = plugin;
        this.threadGuard = new ThreadSafetyGuard(plugin);
        connect();
        if (isConnected()) {
            scheduler.runAsync(this::updateTableSchema);
            scheduler.runAsync(() -> {
                measureDatabaseLatency().thenAccept(latency -> {
                    if (latency > 0) {
                        String dbType = isExternalDatabase() ? "External" : "Local";
                        plugin.getLogger().info(dbType + " database latency: " + latency + "ms");
                        if (latency > 1000) {
                            plugin.getLogger().warning("High database latency detected (" + latency
                                    + "ms). Cross-server teleports may be slower.");
                        }
                    }
                });
            });

            scheduler.runTimer(() -> cleanupRedisFallbackEntries()
                    .exceptionally(ex -> {
                        if (plugin.getConfig().getBoolean("settings.debug", false)) {
                            plugin.getLogger().log(Level.WARNING, "Failed to cleanup Redis fallback cache", ex);
                        }
                        return null;
                    }), 6000L, 6000L);
        }
    }

    private void connect() {
        try {
            if (dataSource != null && !dataSource.isClosed()) {
                plugin.getRTPLogger().debug("SQL", "Closing existing database connection before reconnecting.");
                dataSource.close();
                dataSource = null;
            }

            File mysqlFile = new File(plugin.getDataFolder(), "mysql.yml");
            FileConfiguration config = YamlConfiguration.loadConfiguration(mysqlFile);

            if (!config.getBoolean("enabled", false)) {
                plugin.getRTPLogger().debug("SQL", "MySQL is disabled in mysql.yml.");
                return;
            }

            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
                plugin.getRTPLogger().debug("SQL", "MySQL driver loaded successfully.");
            } catch (ClassNotFoundException e) {
                plugin.getLogger()
                        .severe("MySQL driver not found! Make sure mysql-connector-j is included in the JAR.");
                throw new SQLException("MySQL driver not found: " + e.getMessage());
            }

            String host = config.getString("host", "localhost");
            int port = config.getInt("port", 3306);
            String database = config.getString("database", "justrtp");
            String username = config.getString("username", "root");
            String password = config.getString("password", "password");

            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hikariConfig.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database +
                    "?useSSL=false&autoReconnect=true&failOverReadOnly=false&maxReconnects=10&allowPublicKeyRetrieval=true"
                    +
                    "&useLocalSessionState=true&useLocalTransactionState=true&rewriteBatchedStatements=true" +
                    "&cacheResultSetMetadata=true&cacheServerConfiguration=true&elideSetAutoCommits=true" +
                    "&maintainTimeStats=false&useUnbufferedInput=false");
            hikariConfig.setUsername(username);
            hikariConfig.setPassword(password);
            hikariConfig.setMaximumPoolSize(config.getInt("pool-settings.maximum-pool-size", 8));
            hikariConfig.setMinimumIdle(config.getInt("pool-settings.minimum-idle", 2));
            hikariConfig.setConnectionTimeout(config.getInt("pool-settings.connection-timeout", 10000));
            hikariConfig.setIdleTimeout(config.getInt("pool-settings.idle-timeout", 300000));
            hikariConfig.setMaxLifetime(config.getInt("pool-settings.max-lifetime", 900000));
            hikariConfig.setLeakDetectionThreshold(60000);
            hikariConfig.setConnectionTestQuery("/* ping */ SELECT 1");
            hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
            hikariConfig.addDataSourceProperty("prepStmtCacheSize", "500");
            hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "4096");
            hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
            hikariConfig.addDataSourceProperty("useLocalSessionState", "true");
            hikariConfig.addDataSourceProperty("rewriteBatchedStatements", "true");
            hikariConfig.addDataSourceProperty("cacheResultSetMetadata", "true");
            hikariConfig.addDataSourceProperty("cacheServerConfiguration", "true");
            hikariConfig.addDataSourceProperty("elideSetAutoCommits", "true");
            hikariConfig.addDataSourceProperty("maintainTimeStats", "false");

            plugin.getRTPLogger().debug("SQL", "Attempting MySQL connection to " + host + ":" + port + "/" + database
                    + " as user '" + username + "'");
            plugin.getLogger().info("Initializing HikariCP connection pool...");

            dataSource = new HikariDataSource(hikariConfig);

            plugin.getLogger().info("HikariCP pool started successfully");

            try (Connection testConn = dataSource.getConnection()) {
                if (!testConn.isValid(5)) {
                    throw new SQLException("Database connection test failed");
                }
                plugin.getRTPLogger().debug("SQL", "Database connection validated successfully.");

                DatabaseMetaData metaData = testConn.getMetaData();
                String dbProductName = metaData.getDatabaseProductName();
                String dbVersion = metaData.getDatabaseProductVersion();
                int majorVersion = metaData.getDatabaseMajorVersion();
                int minorVersion = metaData.getDatabaseMinorVersion();

                plugin.getLogger().info("Connected to " + dbProductName + " " + dbVersion);

                if (dbProductName.toLowerCase().contains("mysql")) {
                    if (majorVersion >= 8) {
                        supportsSkipLocked = true;
                        plugin.getRTPLogger().debug("SQL", "MySQL 8.0+ detected - SKIP LOCKED enabled");
                    } else {
                        supportsSkipLocked = false;
                        plugin.getLogger().warning("MySQL " + majorVersion + "." + minorVersion
                                + " detected - SKIP LOCKED not supported (requires MySQL 8.0+)");
                        plugin.getLogger().warning("Cross-server teleports will use fallback locking mechanism");
                    }
                } else if (dbProductName.toLowerCase().contains("mariadb")) {
                    if (majorVersion > 10 || (majorVersion == 10 && minorVersion >= 6)) {
                        supportsSkipLocked = true;
                        plugin.getRTPLogger().debug("SQL", "MariaDB 10.6+ detected - SKIP LOCKED enabled");
                    } else {
                        supportsSkipLocked = false;
                        plugin.getLogger().warning("MariaDB " + majorVersion + "." + minorVersion
                                + " detected - SKIP LOCKED not supported (requires MariaDB 10.6+)");
                        plugin.getLogger().warning("Cross-server teleports will use fallback locking mechanism");
                    }
                } else {
                    supportsSkipLocked = false;
                    plugin.getLogger().warning("Unknown database type: " + dbProductName + " - SKIP LOCKED disabled");
                }
            }

            plugin.getLogger()
                    .info("Successfully connected to MySQL database at " + host + ":" + port + "/" + database);
        } catch (Exception e) {
            lastConnectionError = e.getMessage();
            plugin.getLogger().severe("Could not connect to MySQL database! " + e.getMessage());
            if (plugin.getConfig().getBoolean("settings.debug", false)) {
                e.printStackTrace();
            }
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
            }
            dataSource = null;
        }
    }

    public boolean isConnected() {
        if (dataSource == null || dataSource.isClosed()) {
            return false;
        }

        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(3);
        } catch (SQLException e) {
            plugin.getRTPLogger().debug("SQL", "Database connection validation failed: " + e.getMessage());

            if (e.getMessage().contains("Communications link failure") ||
                    e.getMessage().contains("Connection timed out") ||
                    e.getMessage().contains("Connection refused")) {
                plugin.getLogger().warning("Database connection lost, attempting automatic reconnection...");

                plugin.getFoliaScheduler().runAsync(() -> {
                    try {
                        Thread.sleep(5000);
                        forceReconnect();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            return false;
        }
    }

    private void updateTableSchema() {
        if (!isConnected())
            return;
        plugin.getRTPLogger().debug("SQL", "Checking and updating database schema...");

        String createTableSQL = "CREATE TABLE IF NOT EXISTS justrtp_teleports (" +
                "player_uuid VARCHAR(36) NOT NULL PRIMARY KEY," +
                "origin_server VARCHAR(255) NOT NULL," +
                "target_server VARCHAR(255)," +
                "target_world VARCHAR(255)," +
                "command_args TEXT," +
                "loc_x DOUBLE, loc_y DOUBLE, loc_z DOUBLE, loc_yaw FLOAT, loc_pitch FLOAT," +
                "min_radius INT, max_radius INT," +
                "status VARCHAR(32) NOT NULL DEFAULT 'PENDING'," +
                "request_type VARCHAR(32) NOT NULL DEFAULT 'INDIVIDUAL'," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "INDEX idx_status_server (status, target_server)," +
                "INDEX idx_origin_status (origin_server, status)," +
                "INDEX idx_created_at (created_at)," +
                "INDEX idx_updated_at (updated_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

        String createFallbackTableSQL = "CREATE TABLE IF NOT EXISTS justrtp_redis_fallback (" +
                "cache_key VARCHAR(255) NOT NULL PRIMARY KEY," +
                "data_type VARCHAR(16) NOT NULL," +
                "payload LONGTEXT NOT NULL," +
                "expires_at BIGINT NULL," +
                "INDEX idx_expires_at (expires_at)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createTableSQL);
            stmt.executeUpdate(createFallbackTableSQL);

            try {
                stmt.executeUpdate("CREATE INDEX idx_status_server ON justrtp_teleports (status, target_server)");
                plugin.getRTPLogger().debug("SQL", "Created index idx_status_server for optimized queries.");
            } catch (SQLException e) {
            }

            try {
                stmt.executeUpdate("CREATE INDEX idx_origin_status ON justrtp_teleports (origin_server, status)");
                plugin.getRTPLogger().debug("SQL", "Created index idx_origin_status for optimized queries.");
            } catch (SQLException e) {
            }

            try {
                stmt.executeUpdate("CREATE INDEX idx_created_at ON justrtp_teleports (created_at)");
                plugin.getRTPLogger().debug("SQL", "Created index idx_created_at for optimized cleanup queries.");
            } catch (SQLException e) {
            }

            try {
                stmt.executeUpdate("CREATE INDEX idx_updated_at ON justrtp_teleports (updated_at)");
                plugin.getRTPLogger().debug("SQL", "Created index idx_updated_at for optimized cleanup queries.");
            } catch (SQLException e) {
            }

            try {
                stmt.executeUpdate("CREATE INDEX idx_expires_at ON justrtp_redis_fallback (expires_at)");
                plugin.getRTPLogger().debug("SQL", "Created index idx_expires_at for optimized fallback cleanup.");
            } catch (SQLException e) {
            }

            try {
                String addColumnSQL = "ALTER TABLE justrtp_teleports ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP";
                stmt.executeUpdate(addColumnSQL);
                plugin.getRTPLogger().debug("SQL", "Added updated_at column to existing table.");
            } catch (SQLException e) {
                plugin.getRTPLogger().debug("SQL",
                        "updated_at column already exists or couldn't be added: " + e.getMessage());
            }

            try {
                String expandStatusSQL = "ALTER TABLE justrtp_teleports MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PENDING'";
                stmt.executeUpdate(expandStatusSQL);
                plugin.getRTPLogger().debug("SQL", "Expanded status column to support longer values.");
            } catch (SQLException e) {
                plugin.getRTPLogger().debug("SQL", "Status column expansion not needed or failed: " + e.getMessage());
            }

            try {
                String expandTypeSQL = "ALTER TABLE justrtp_teleports MODIFY COLUMN request_type VARCHAR(32) NOT NULL DEFAULT 'INDIVIDUAL'";
                stmt.executeUpdate(expandTypeSQL);
                plugin.getRTPLogger().debug("SQL", "Expanded request_type column to support longer values.");
            } catch (SQLException e) {
                plugin.getRTPLogger().debug("SQL",
                        "Request_type column expansion not needed or failed: " + e.getMessage());
            }

            try {
                String addProcessingServerSQL = "ALTER TABLE justrtp_teleports ADD COLUMN processing_server VARCHAR(255) NULL";
                stmt.executeUpdate(addProcessingServerSQL);
                plugin.getRTPLogger().debug("SQL",
                        "Added processing_server column for cross-server race condition prevention.");
            } catch (SQLException e) {
                plugin.getRTPLogger().debug("SQL",
                        "processing_server column already exists or couldn't be added: " + e.getMessage());
            }

            try {
                stmt.executeUpdate("CREATE INDEX idx_processing_server ON justrtp_teleports (processing_server)");
                plugin.getRTPLogger().debug("SQL", "Created index idx_processing_server for optimized queries.");
            } catch (SQLException e) {
                plugin.getRTPLogger().debug("SQL", "Index idx_processing_server already exists: " + e.getMessage());
            }

            plugin.getRTPLogger().debug("SQL", "Database schema check complete with performance indexes.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not create or update table schema!", e);
        }

        createServerWorldsTable();
        createZoneSyncTables();
    }

    private void createServerWorldsTable() {
        if (!isConnected())
            return;
        plugin.getRTPLogger().debug("SQL", "Creating server_worlds table for cross-server world discovery...");

        String createTableSQL = "CREATE TABLE IF NOT EXISTS justrtp_server_worlds (" +
                "server_name VARCHAR(255) NOT NULL," +
                "world_name VARCHAR(255) NOT NULL," +
                "world_type VARCHAR(32)," +
                "is_enabled BOOLEAN DEFAULT TRUE," +
                "last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "PRIMARY KEY (server_name, world_name)," +
                "INDEX idx_server_enabled (server_name, is_enabled)," +
                "INDEX idx_last_updated (last_updated)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createTableSQL);
            plugin.getRTPLogger().debug("SQL", "Server worlds table created/verified successfully.");
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not create server_worlds table!", e);
        }
    }

    public CompletableFuture<Void> createTeleportRequest(UUID playerUUID, String originServer, String targetServer,
            String commandArgs, String targetWorld, Optional<Integer> minRadius, Optional<Integer> maxRadius,
            String requestType) {
        return CompletableFuture.runAsync(() -> {
            threadGuard.assertAsyncDatabase("createTeleportRequest");

            if (!isConnected())
                return;
            plugin.getRTPLogger().debug("SQL",
                    "Creating teleport request in DB for " + playerUUID + " to server " + targetServer);

            try (Connection conn = dataSource.getConnection()) {
                String cleanupSql = "DELETE FROM justrtp_teleports WHERE player_uuid = ? AND status NOT IN ('IN_TRANSFER', 'PROCESSING')";
                try (PreparedStatement cleanupStmt = conn.prepareStatement(cleanupSql)) {
                    cleanupStmt.setString(1, playerUUID.toString());
                    int deleted = cleanupStmt.executeUpdate();
                    if (deleted > 0) {
                        plugin.getRTPLogger().debug("SQL", "Cleaned up " + deleted + " old requests for " + playerUUID);
                    }
                }

                String sql = "INSERT INTO justrtp_teleports (player_uuid, origin_server, target_server, command_args, target_world, min_radius, max_radius, status, request_type) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, playerUUID.toString());
                    pstmt.setString(2, originServer);
                    pstmt.setString(3, targetServer);
                    pstmt.setString(4, commandArgs);
                    pstmt.setString(5, targetWorld);
                    pstmt.setObject(6, minRadius.orElse(null));
                    pstmt.setObject(7, maxRadius.orElse(null));
                    pstmt.setString(8, RequestStatus.PENDING.name());
                    pstmt.setString(9, requestType);
                    int result = pstmt.executeUpdate();
                    plugin.getRTPLogger().debug("SQL",
                            "Created teleport request for " + playerUUID + ", affected rows: " + result);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not create teleport request for " + playerUUID, e);
                throw new CompletionException("Failed to create teleport request for " + playerUUID, e);
            }
        });
    }

    public CompletableFuture<Void> setRedisFallbackValue(String key, String dataType, String payload, Long expiresAt) {
        return CompletableFuture.runAsync(() -> {
            threadGuard.assertAsyncDatabase("setRedisFallbackValue");
            if (!isConnected())
                return;

            String sql = "INSERT INTO justrtp_redis_fallback (cache_key, data_type, payload, expires_at) " +
                    "VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE data_type = VALUES(data_type), payload = VALUES(payload), expires_at = VALUES(expires_at)";

            try (Connection conn = dataSource.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, key);
                pstmt.setString(2, dataType);
                pstmt.setString(3, payload);
                if (expiresAt != null) {
                    pstmt.setLong(4, expiresAt);
                } else {
                    pstmt.setNull(4, Types.BIGINT);
                }
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not persist Redis fallback value for key " + key, e);
            }
        });
    }

    public CompletableFuture<Optional<RedisFallbackEntry>> getRedisFallbackEntry(String key) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncDatabase("getRedisFallbackEntry");
            if (!isConnected())
                return Optional.empty();

            String sql = "SELECT data_type, payload, expires_at FROM justrtp_redis_fallback WHERE cache_key = ?";
            try (Connection conn = dataSource.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, key);
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    Long expiresAt = rs.getObject("expires_at") != null ? rs.getLong("expires_at") : null;
                    if (expiresAt != null && expiresAt <= System.currentTimeMillis()) {
                        deleteRedisFallbackValue(key);
                        return Optional.empty();
                    }
                    return Optional.of(new RedisFallbackEntry(
                            rs.getString("data_type"),
                            rs.getString("payload"),
                            expiresAt));
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not fetch Redis fallback value for key " + key, e);
            }
            return Optional.empty();
        });
    }

    public CompletableFuture<Void> deleteRedisFallbackValue(String key) {
        return CompletableFuture.runAsync(() -> {
            threadGuard.assertAsyncDatabase("deleteRedisFallbackValue");
            if (!isConnected())
                return;

            String sql = "DELETE FROM justrtp_redis_fallback WHERE cache_key = ?";
            try (Connection conn = dataSource.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, key);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not delete Redis fallback value for key " + key, e);
            }
        });
    }

    public CompletableFuture<Void> updateRedisFallbackExpiry(String key, Long expiresAt) {
        return CompletableFuture.runAsync(() -> {
            threadGuard.assertAsyncDatabase("updateRedisFallbackExpiry");
            if (!isConnected())
                return;

            String sql = "UPDATE justrtp_redis_fallback SET expires_at = ? WHERE cache_key = ?";
            try (Connection conn = dataSource.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                if (expiresAt != null) {
                    pstmt.setLong(1, expiresAt);
                } else {
                    pstmt.setNull(1, Types.BIGINT);
                }
                pstmt.setString(2, key);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not update Redis fallback expiry for key " + key, e);
            }
        });
    }

    public CompletableFuture<Void> cleanupRedisFallbackEntries() {
        return CompletableFuture.runAsync(() -> {
            threadGuard.assertAsyncDatabase("cleanupRedisFallbackEntries");
            if (!isConnected())
                return;

            String sql = "DELETE FROM justrtp_redis_fallback WHERE expires_at IS NOT NULL AND expires_at <= ?";
            try (Connection conn = dataSource.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setLong(1, System.currentTimeMillis());
                int removed = pstmt.executeUpdate();
                if (removed > 0 && plugin.getConfig().getBoolean("settings.debug", false)) {
                    plugin.getRTPLogger().debug("SQL",
                            "[DB] Cleaned up " + removed + " expired Redis fallback entries");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not cleanup Redis fallback cache", e);
            }
        });
    }

    public CompletableFuture<Void> updateTeleportRequestWithLocation(UUID playerUUID, Location location) {
        return CompletableFuture.runAsync(() -> {
            threadGuard.assertAsyncDatabase("updateTeleportRequestWithLocation");
            if (!isConnected())
                return;

            Location safeLocation = location;
            if (location.getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER) {
                double y = location.getY();

                if (y >= 126.0) {
                    plugin.getLogger().severe("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    plugin.getLogger().severe("CRITICAL SAFETY VIOLATION PREVENTED!");
                    plugin.getLogger().severe("Attempted to save nether location at Y=" + y + " (>= 126.0)");
                    plugin.getLogger().severe("This would spawn player at/above nether ceiling (Y=127)!");
                    plugin.getLogger().severe("Location: " + location.getWorld().getName() + " [" + location.getBlockX()
                            + ", " + location.getBlockY() + ", " + location.getBlockZ() + "]");
                    plugin.getLogger().severe("Player UUID: " + playerUUID);
                    plugin.getLogger().severe("Clamping Y to safe value: 120.0");
                    plugin.getLogger().severe("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

                    safeLocation = location.clone();
                    safeLocation.setY(120.0);
                }

                if (y + 1.0 >= 127.0) {
                    plugin.getLogger().severe(
                            "NETHER SAFETY: Player head would be at Y=" + (y + 1.0) + " (>= 127), clamping to Y=120");
                    safeLocation = location.clone();
                    safeLocation.setY(120.0);
                }
            }

            plugin.getRTPLogger().debug("SQL", "Updating teleport request for " + playerUUID + " with location "
                    + safeLocation + " (Y=" + safeLocation.getY() + ")");
            String sql = "UPDATE justrtp_teleports SET target_world = ?, loc_x = ?, loc_y = ?, loc_z = ?, loc_yaw = ?, loc_pitch = ?, status = ? WHERE player_uuid = ?";
            try (Connection conn = dataSource.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, safeLocation.getWorld().getName());
                pstmt.setDouble(2, safeLocation.getX());
                pstmt.setDouble(3, safeLocation.getY());
                pstmt.setDouble(4, safeLocation.getZ());
                pstmt.setFloat(5, safeLocation.getYaw());
                pstmt.setFloat(6, safeLocation.getPitch());
                pstmt.setString(7, RequestStatus.COMPLETE.name());
                pstmt.setString(8, playerUUID.toString());
                pstmt.executeUpdate();
                plugin.getRTPLogger().debug("SQL", "[DB-CACHE] Updated spawn location for cross-server instant spawn: "
                        + safeLocation + " (Y=" + safeLocation.getY() + ")");

            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not update teleport request with location", e);
                throw new CompletionException("Failed to update teleport request location for " + playerUUID, e);
            }
        });
    }

    public CompletableFuture<Void> updateGroupTeleportRequestWithLocation(UUID leaderUUID, Location location) {
        return CompletableFuture.runAsync(() -> {
            threadGuard.assertAsyncDatabase("updateGroupTeleportRequestWithLocation");
            if (!isConnected())
                return;

            Location safeLocation = location;
            if (location.getWorld().getEnvironment() == org.bukkit.World.Environment.NETHER) {
                double y = location.getY();

                if (y >= 126.0 || y + 1.0 >= 127.0) {
                    plugin.getLogger().severe("NETHER SAFETY: Group teleport location at Y=" + y
                            + " would put players at/above ceiling!");
                    plugin.getLogger().severe("Clamping group location to Y=120 for safety");
                    safeLocation = location.clone();
                    safeLocation.setY(120.0);
                }
            }

            plugin.getRTPLogger().debug("SQL", "Updating GROUP teleport request for leader " + leaderUUID
                    + " with location " + safeLocation + " (Y=" + safeLocation.getY() + ")");

            List<UUID> allMemberUuids = new ArrayList<>();
            allMemberUuids.add(leaderUUID);

            String fetchSql = "SELECT command_args FROM justrtp_teleports WHERE player_uuid = ?";
            String updateSql = "UPDATE justrtp_teleports SET target_world = ?, loc_x = ?, loc_y = ?, loc_z = ?, loc_yaw = ?, loc_pitch = ?, status = ? WHERE player_uuid = ?";

            try (Connection conn = dataSource.getConnection()) {
                try (PreparedStatement fetchStmt = conn.prepareStatement(fetchSql)) {
                    fetchStmt.setString(1, leaderUUID.toString());
                    try (ResultSet rs = fetchStmt.executeQuery()) {
                        if (rs.next()) {
                            String commandArgs = rs.getString("command_args");
                            if (commandArgs != null) {
                                try {
                                    String[] parts = commandArgs.split(":", 2);
                                    if (parts.length == 2 && parts[0].equals("GROUP_TELEPORT")) {
                                        for (String uuidStr : parts[1].split(",")) {
                                            if (!uuidStr.isBlank()) {
                                                allMemberUuids.add(UUID.fromString(uuidStr.trim()));
                                            }
                                        }
                                    }
                                } catch (Exception parseEx) {
                                    plugin.getLogger().warning("Could not parse group members from leader request: "
                                            + leaderUUID + " - " + parseEx.getMessage());
                                }
                            }
                        } else {
                            plugin.getLogger().warning(
                                    "No leader teleport request found while updating group location for " + leaderUUID);
                        }
                    }
                }

                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    for (UUID memberUUID : allMemberUuids) {
                        updateStmt.setString(1, safeLocation.getWorld().getName());
                        updateStmt.setDouble(2, safeLocation.getX());
                        updateStmt.setDouble(3, safeLocation.getY());
                        updateStmt.setDouble(4, safeLocation.getZ());
                        updateStmt.setFloat(5, safeLocation.getYaw());
                        updateStmt.setFloat(6, safeLocation.getPitch());
                        updateStmt.setString(7, RequestStatus.COMPLETE.name());
                        updateStmt.setString(8, memberUUID.toString());
                        updateStmt.addBatch();
                    }
                    updateStmt.executeBatch();
                    plugin.getRTPLogger().debug("SQL", "Updated " + allMemberUuids.size()
                            + " group members to COMPLETE status with Y=" + safeLocation.getY());
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not update group teleport request with location", e);
                throw new CompletionException("Failed to update group teleport location for leader " + leaderUUID, e);
            }

            List<UUID> membersSnapshot = new ArrayList<>(allMemberUuids);
            for (UUID memberUUID : membersSnapshot) {
                String requestType = memberUUID.equals(leaderUUID) ? "GROUP_LEADER" : "GROUP_MEMBER";
                plugin.getRTPLogger().debug("SQL",
                        "[DB-CACHE] Updated group spawn location for " + memberUUID + " with type " + requestType);
            }
        });
    }

    public CompletableFuture<Void> failTeleportRequest(UUID playerUUID) {
        return CompletableFuture.runAsync(() -> {
            threadGuard.assertAsyncDatabase("failTeleportRequest");
            if (!isConnected())
                return;

            String sql = "UPDATE justrtp_teleports SET status = ?, updated_at = NOW() WHERE player_uuid = ? AND status IN ('PENDING', 'PROCESSING')";

            try (Connection conn = dataSource.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, RequestStatus.FAILED.name());
                pstmt.setString(2, playerUUID.toString());

                int updated = pstmt.executeUpdate();
                if (updated > 0) {
                    plugin.getRTPLogger().debug("SQL",
                            "Marked teleport request as FAILED for " + playerUUID + " (was PENDING or PROCESSING)");
                } else {
                    plugin.getRTPLogger().debug("SQL", "Could not mark teleport request as FAILED for " + playerUUID
                            + " - may already be COMPLETE or FAILED");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not mark teleport request as failed for " + playerUUID, e);
                throw new CompletionException("Failed to mark teleport request as failed for " + playerUUID, e);
            }
        });
    }

    public CompletableFuture<Boolean> markTeleportRequestAsTransferring(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncDatabase("markTeleportRequestAsTransferring");
            if (!isConnected())
                return false;

            plugin.getRTPLogger().debug("SQL", "Attempting atomic IN_TRANSFER transition for " + playerUUID);

            String sqlWithUpdatedAt = "UPDATE justrtp_teleports SET status = ?, updated_at = NOW() WHERE player_uuid = ? AND status = ?";
            String sqlFallback = "UPDATE justrtp_teleports SET status = ? WHERE player_uuid = ? AND status = ?";

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

                try (PreparedStatement pstmt = conn.prepareStatement(sqlWithUpdatedAt)) {
                    pstmt.setString(1, RequestStatus.IN_TRANSFER.name());
                    pstmt.setString(2, playerUUID.toString());
                    pstmt.setString(3, RequestStatus.COMPLETE.name());

                    int updated = pstmt.executeUpdate();
                    conn.commit();

                    if (updated > 0) {
                        plugin.getRTPLogger().debug("SQL",
                                "Atomic IN_TRANSFER transition successful for " + playerUUID);
                        return true;
                    } else {
                        plugin.getRTPLogger().debug("SQL",
                                "Atomic IN_TRANSFER transition failed - request not in correct state for "
                                        + playerUUID);
                        return false;
                    }
                } catch (SQLException e) {
                    if (e.getMessage().contains("updated_at")) {
                        try (PreparedStatement pstmt = conn.prepareStatement(sqlFallback)) {
                            pstmt.setString(1, RequestStatus.IN_TRANSFER.name());
                            pstmt.setString(2, playerUUID.toString());
                            pstmt.setString(3, RequestStatus.COMPLETE.name());

                            int updated = pstmt.executeUpdate();
                            conn.commit();

                            if (updated > 0) {
                                plugin.getRTPLogger().debug("SQL", "Atomic IN_TRANSFER transition successful for "
                                        + playerUUID + " (fallback method)");
                                return true;
                            } else {
                                plugin.getRTPLogger().debug("SQL",
                                        "Atomic IN_TRANSFER transition failed - request not in correct state for "
                                                + playerUUID);
                                return false;
                            }
                        } catch (SQLException e2) {
                            conn.rollback();
                            throw e2;
                        }
                    } else {
                        conn.rollback();
                        throw e;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger()
                        .severe("Failed atomic IN_TRANSFER transition for " + playerUUID + ": " + e.getMessage());
                return false;
            }
        });
    }

    public CompletableFuture<Boolean> confirmPlayerTransfer(UUID playerUUID, String targetServer) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncDatabase("confirmPlayerTransfer");
            if (!isConnected())
                return false;

            plugin.getRTPLogger().debug("SQL", "Confirming player transfer for " + playerUUID + " to " + targetServer);

            String sqlWithUpdatedAt = "UPDATE justrtp_teleports SET status = 'TRANSFER_CONFIRMED', updated_at = NOW() WHERE player_uuid = ? AND status = ? AND target_server = ?";
            String sqlFallback = "UPDATE justrtp_teleports SET status = 'TRANSFER_CONFIRMED' WHERE player_uuid = ? AND status = ? AND target_server = ?";

            try (Connection conn = dataSource.getConnection()) {
                try (PreparedStatement pstmt = conn.prepareStatement(sqlWithUpdatedAt)) {
                    pstmt.setString(1, playerUUID.toString());
                    pstmt.setString(2, RequestStatus.IN_TRANSFER.name());
                    pstmt.setString(3, targetServer);

                    int updated = pstmt.executeUpdate();

                    if (updated > 0) {
                        plugin.getRTPLogger().debug("SQL",
                                "Transfer confirmed for " + playerUUID + " to " + targetServer);
                        return true;
                    } else {
                        plugin.getRTPLogger().debug("SQL", "No IN_TRANSFER request found to confirm for " + playerUUID);
                        return false;
                    }
                } catch (SQLException e) {
                    if (e.getMessage().contains("updated_at")) {
                        try (PreparedStatement pstmt = conn.prepareStatement(sqlFallback)) {
                            pstmt.setString(1, playerUUID.toString());
                            pstmt.setString(2, RequestStatus.IN_TRANSFER.name());
                            pstmt.setString(3, targetServer);

                            int updated = pstmt.executeUpdate();

                            if (updated > 0) {
                                plugin.getRTPLogger().debug("SQL", "Transfer confirmed for " + playerUUID + " to "
                                        + targetServer + " (fallback method)");
                                return true;
                            } else {
                                plugin.getRTPLogger().debug("SQL",
                                        "No IN_TRANSFER request found to confirm for " + playerUUID);
                                return false;
                            }
                        } catch (SQLException e2) {
                            plugin.getLogger().severe(
                                    "Failed to confirm transfer for " + playerUUID + " (fallback): " + e2.getMessage());
                            return false;
                        }
                    } else {
                        plugin.getLogger()
                                .severe("Failed to confirm transfer for " + playerUUID + ": " + e.getMessage());
                        return false;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to get connection for transfer confirmation: " + e.getMessage());
                return false;
            }
        });
    }

    public CompletableFuture<Void> confirmTeleportTransfer(UUID playerUUID, String targetServer) {
        return confirmPlayerTransfer(playerUUID, targetServer)
                .thenAccept(success -> {
                    if (success) {
                        plugin.getRTPLogger().debug("SQL",
                                "Teleport transfer confirmed for " + playerUUID + " on " + targetServer);
                    } else {
                        plugin.getRTPLogger().debug("SQL", "Could not confirm transfer for " + playerUUID
                                + " - request may be in different state");
                    }
                });
    }

    public CompletableFuture<Void> removeTeleportRequestAfterVerification(UUID playerUUID) {
        return CompletableFuture.runAsync(() -> {
            threadGuard.assertAsyncDatabase("removeTeleportRequestAfterVerification");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (!isConnected())
                return;

            plugin.getRTPLogger().debug("SQL", "Removing verified teleport request from DB for " + playerUUID);

            String sql = "DELETE FROM justrtp_teleports WHERE player_uuid = ? AND (status = ? OR status = ? OR status = ? OR created_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE))";

            try (Connection conn = dataSource.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, playerUUID.toString());
                pstmt.setString(2, "TRANSFER_CONFIRMED");
                pstmt.setString(3, RequestStatus.COMPLETE.name());
                pstmt.setString(4, RequestStatus.IN_TRANSFER.name());

                int rowsAffected = pstmt.executeUpdate();
                if (rowsAffected > 0) {
                    plugin.getRTPLogger().debug("SQL",
                            "Successfully removed verified teleport request for " + playerUUID);
                } else {
                    plugin.getRTPLogger().debug("SQL", "No teleport request found to remove for " + playerUUID);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not remove verified teleport request", e);
            }
        });
    }

    public CompletableFuture<Optional<ProxyTeleportRequest>> findAndMarkPendingTeleportRequestForServer(
            String serverName) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncDatabase("findAndMarkPendingTeleportRequestForServer");

            if (!isConnected())
                return Optional.empty();

            String selectSql = "SELECT * FROM justrtp_teleports " +
                    "WHERE target_server = ? AND status = ? " +
                    "ORDER BY created_at ASC LIMIT 1 " +
                    "FOR UPDATE" + (supportsSkipLocked ? " SKIP LOCKED" : "");
            String updateSql = "UPDATE justrtp_teleports SET status = ?, updated_at = NOW(), processing_server = ? WHERE player_uuid = ? AND status = ?";

            Connection conn = null;
            try {
                conn = dataSource.getConnection();
                conn.setAutoCommit(false);
                conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

                try (PreparedStatement selectPstmt = conn.prepareStatement(selectSql)) {
                    int queryTimeout = supportsSkipLocked ? 10 : 3;
                    selectPstmt.setQueryTimeout(queryTimeout);
                    selectPstmt.setFetchSize(1);
                    selectPstmt.setString(1, serverName);
                    selectPstmt.setString(2, RequestStatus.PENDING.name());
                    ResultSet rs = selectPstmt.executeQuery();

                    if (rs.next()) {
                        ProxyTeleportRequest request = mapResultSetToRequest(rs);

                        try (PreparedStatement updatePstmt = conn.prepareStatement(updateSql)) {
                            updatePstmt.setString(1, RequestStatus.PROCESSING.name());
                            updatePstmt.setString(2, plugin.getConfigManager().getProxyThisServerName());
                            updatePstmt.setString(3, request.playerUUID().toString());
                            updatePstmt.setString(4, RequestStatus.PENDING.name());

                            int updated = updatePstmt.executeUpdate();
                            if (updated == 0) {
                                plugin.getRTPLogger().debug("SQL", "Request for " + request.playerUUID()
                                        + " was already processed by another server");
                                conn.rollback();
                                return Optional.empty();
                            }
                        }

                        conn.commit();
                        plugin.getRTPLogger().debug("SQL", "Successfully claimed request for " + request.playerUUID()
                                + " (status: PENDING -> PROCESSING)");
                        return Optional.of(request);
                    } else {
                        conn.rollback();
                        return Optional.empty();
                    }
                } catch (SQLException e) {
                    try {
                        if (conn != null)
                            conn.rollback();
                    } catch (SQLException ex) {
                        plugin.getLogger().log(Level.SEVERE, "Failed to rollback transaction", ex);
                    }

                    if (!e.getMessage().contains("timeout") && !e.getMessage().contains("lock")) {
                        plugin.getLogger().log(Level.SEVERE,
                                "Could not poll and mark pending teleport request for server " + serverName, e);
                    }
                    return Optional.empty();
                } finally {
                    try {
                        if (conn != null)
                            conn.setAutoCommit(true);
                    } catch (SQLException e) {
                        plugin.getLogger().log(Level.WARNING, "Failed to reset auto-commit", e);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Database connection error during polling", e);
                return Optional.empty();
            } finally {
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (SQLException e) {
                        plugin.getLogger().log(Level.WARNING, "Failed to close connection", e);
                    }
                }
            }
        });
    }

    public CompletableFuture<List<ProxyTeleportRequest>> findFinalizedTeleportRequestsByOrigin(String originServer) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncDatabase("findFinalizedTeleportRequestsByOrigin");

            List<ProxyTeleportRequest> requests = new ArrayList<>();
            if (!isConnected())
                return requests;
            String sql = "SELECT * FROM justrtp_teleports WHERE origin_server = ? AND (status = ? OR status = ?) " +
                    "ORDER BY created_at DESC LIMIT 100";
            try (Connection conn = dataSource.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setFetchSize(100);
                pstmt.setString(1, originServer);
                pstmt.setString(2, RequestStatus.COMPLETE.name());
                pstmt.setString(3, RequestStatus.FAILED.name());
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    requests.add(mapResultSetToRequest(rs));
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not poll for finalized teleport requests for server " + originServer, e);
            }
            return requests;
        });
    }

    public CompletableFuture<Optional<ProxyTeleportRequest>> getTeleportRequest(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncDatabase("getTeleportRequest");
            if (!isConnected())
                return Optional.empty();
            String sql = "SELECT * FROM justrtp_teleports WHERE player_uuid = ?";
            try (Connection conn = dataSource.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, playerUUID.toString());
                ResultSet rs = pstmt.executeQuery();
                if (rs.next()) {
                    return Optional.of(mapResultSetToRequest(rs));
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not get teleport request for " + playerUUID, e);
            }
            return Optional.empty();
        });
    }

    private ProxyTeleportRequest mapResultSetToRequest(ResultSet rs) throws SQLException {
        Integer minRadius = rs.getObject("min_radius") != null ? rs.getInt("min_radius") : null;
        Integer maxRadius = rs.getObject("max_radius") != null ? rs.getInt("max_radius") : null;
        String status = rs.getString("status");

        Location location = null;
        RequestStatus requestStatus = RequestStatus.valueOf(status);
        if (requestStatus == RequestStatus.COMPLETE || "TRANSFER_CONFIRMED".equals(status)
                || "IN_TRANSFER".equals(status)) {
            String targetWorldName = rs.getString("target_world");
            if (targetWorldName != null) {
                World world = plugin.getServer().getWorld(targetWorldName);
                if (world != null) {
                    if (rs.getObject("loc_x") != null) {
                        location = new Location(
                                world,
                                rs.getDouble("loc_x"),
                                rs.getDouble("loc_y"),
                                rs.getDouble("loc_z"),
                                rs.getFloat("loc_yaw"),
                                rs.getFloat("loc_pitch"));
                    }
                }
            }
        }

        return new ProxyTeleportRequest(
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("origin_server"),
                rs.getString("target_server"),
                rs.getString("command_args"),
                rs.getString("target_world"),
                location,
                status,
                minRadius,
                maxRadius,
                rs.getString("request_type"));
    }

    public CompletableFuture<Void> removeTeleportRequest(UUID playerUUID) {
        return removeTeleportRequestSafe(playerUUID, false);
    }

    public CompletableFuture<Void> removeTeleportRequestSafe(UUID playerUUID, boolean verifyNotInTransfer) {
        return CompletableFuture.runAsync(() -> {
            threadGuard.assertAsyncDatabase("removeTeleportRequestSafe");
            if (!isConnected())
                return;

            if (verifyNotInTransfer) {
                String checkSql = "SELECT status FROM justrtp_teleports WHERE player_uuid = ?";
                try (Connection conn = dataSource.getConnection();
                        PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                    checkStmt.setString(1, playerUUID.toString());
                    ResultSet rs = checkStmt.executeQuery();
                    if (rs.next()) {
                        String status = rs.getString("status");
                        if (RequestStatus.IN_TRANSFER.name().equals(status)) {
                            plugin.getRTPLogger().debug("SQL",
                                    "Preventing removal of IN_TRANSFER request for " + playerUUID
                                            + " - player still being sent to server");
                            return;
                        }
                    }
                } catch (SQLException e) {
                    plugin.getLogger().log(Level.WARNING, "Could not verify teleport request status before removal", e);
                }
            }

            plugin.getRTPLogger().debug("SQL", "Removing teleport request from DB for " + playerUUID);
            String sql = "DELETE FROM justrtp_teleports WHERE player_uuid = ?";
            try (Connection conn = dataSource.getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, playerUUID.toString());
                int rowsAffected = pstmt.executeUpdate();
                if (rowsAffected > 0) {
                    plugin.getRTPLogger().debug("SQL", "Successfully removed teleport request for " + playerUUID);
                } else {
                    plugin.getRTPLogger().debug("SQL", "No teleport request found to remove for " + playerUUID);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not remove teleport request", e);
            }
        });
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("MySQL connection closed.");
        }
        dataSource = null;
    }

    public CompletableFuture<Void> cleanupStuckRequests(int timeoutSeconds) {
        return CompletableFuture.runAsync(() -> {
            threadGuard.assertAsyncDatabase("cleanupStuckRequests");
            if (!isConnected())
                return;

            String stuckPendingSql = "UPDATE justrtp_teleports SET status = 'FAILED' WHERE (status = 'PENDING' OR status = 'PROCESSING') AND created_at < DATE_SUB(NOW(), INTERVAL ? SECOND)";

            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(stuckPendingSql)) {
                pstmt.setInt(1, timeoutSeconds);
                int updatedRows = pstmt.executeUpdate();
                if (updatedRows > 0) {
                    plugin.getRTPLogger().debug("SQL", "[Cleanup] Marked " + updatedRows
                            + " stuck PENDING/PROCESSING requests as FAILED (timeout: " + timeoutSeconds + "s)");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not cleanup stuck PENDING/PROCESSING requests", e);
            }
        });
    }

    public CompletableFuture<Void> cleanupExpiredRequests() {
        return CompletableFuture.runAsync(() -> {
            threadGuard.assertAsyncDatabase("cleanupExpiredRequests");
            if (!isConnected())
                return;

            String confirmedAndFailedSql = "DELETE FROM justrtp_teleports WHERE (status = ? OR status = ?) AND created_at < DATE_SUB(NOW(), INTERVAL 2 MINUTE)";
            String completedSql = "DELETE FROM justrtp_teleports WHERE status = ? AND created_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE)";
            String stuckTransferSql = "UPDATE justrtp_teleports SET status = 'FAILED' WHERE status = 'IN_TRANSFER' AND updated_at < DATE_SUB(NOW(), INTERVAL 3 MINUTE)";
            String stuckTransferFallbackSql = "UPDATE justrtp_teleports SET status = 'FAILED' WHERE status = 'IN_TRANSFER' AND created_at < DATE_SUB(NOW(), INTERVAL 3 MINUTE)";

            try (Connection conn = dataSource.getConnection()) {
                try (PreparedStatement pstmt = conn.prepareStatement(stuckTransferSql)) {
                    int updatedRows = pstmt.executeUpdate();
                    if (updatedRows > 0) {
                        plugin.getRTPLogger().debug("SQL", "[Cleanup] Marked " + updatedRows
                                + " stuck IN_TRANSFER requests as FAILED (>3min in transfer)");
                    }
                } catch (SQLException e) {
                    try (PreparedStatement pstmt = conn.prepareStatement(stuckTransferFallbackSql)) {
                        int updatedRows = pstmt.executeUpdate();
                        if (updatedRows > 0) {
                            plugin.getRTPLogger().debug("SQL", "[Cleanup] Marked " + updatedRows
                                    + " stuck IN_TRANSFER requests as FAILED (fallback check)");
                        }
                    } catch (SQLException e2) {
                        plugin.getLogger().log(Level.WARNING, "Could not cleanup stuck transfer requests", e2);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not get database connection for cleanup", e);
            }

            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(confirmedAndFailedSql)) {
                pstmt.setString(1, "TRANSFER_CONFIRMED");
                pstmt.setString(2, RequestStatus.FAILED.name());
                int deletedRows = pstmt.executeUpdate();
                if (deletedRows > 0) {
                    plugin.getRTPLogger().debug("SQL",
                            "[Cleanup] Deleted " + deletedRows + " TRANSFER_CONFIRMED/FAILED requests (>2min old)");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not cleanup confirmed/failed requests", e);
            }

            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(completedSql)) {
                pstmt.setString(1, RequestStatus.COMPLETE.name());
                int deletedRows = pstmt.executeUpdate();
                if (deletedRows > 0) {
                    plugin.getRTPLogger().debug("SQL", "[Cleanup] Deleted " + deletedRows
                            + " COMPLETE requests (>5min old, player likely joined)");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not cleanup completed requests", e);
            }

            String abandonedSql = "DELETE FROM justrtp_teleports WHERE (status = 'PENDING' OR status = 'PROCESSING') AND created_at < DATE_SUB(NOW(), INTERVAL 15 MINUTE)";
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(abandonedSql)) {
                int deletedRows = pstmt.executeUpdate();
                if (deletedRows > 0) {
                    plugin.getRTPLogger().debug("SQL",
                            "[Cleanup] Deleted " + deletedRows + " abandoned PENDING/PROCESSING requests (>15min old)");
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Could not cleanup abandoned requests", e);
            }

            plugin.getRTPLogger().debug("SQL", "[Cleanup] Intelligent cleanup cycle completed");
        });
    }

    public void forceReconnect() {
        plugin.getRTPLogger().debug("SQL", "Forcing database reconnection...");
        close();
        connect();
        if (isConnected()) {
            updateTableSchema();
            plugin.getLogger().info("Database reconnection successful.");
        } else {
            plugin.getLogger().severe("Database reconnection failed.");
        }
    }

    public CompletableFuture<Long> measureDatabaseLatency() {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncDatabase("measureDatabaseLatency");
            if (!isConnected())
                return -1L;

            long startTime = System.currentTimeMillis();
            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement("SELECT 1")) {
                pstmt.executeQuery();
                return System.currentTimeMillis() - startTime;
            } catch (SQLException e) {
                plugin.getRTPLogger().debug("SQL", "Database latency test failed: " + e.getMessage());
                return -1L;
            }
        });
    }

    public boolean isExternalDatabase() {
        if (!isConnected())
            return false;

        try {
            File mysqlFile = new File(plugin.getDataFolder(), "mysql.yml");
            FileConfiguration config = YamlConfiguration.loadConfiguration(mysqlFile);
            String host = config.getString("host", "localhost");

            return !host.equals("localhost") && !host.equals("127.0.0.1") && !host.startsWith("localhost:")
                    && !host.startsWith("127.0.0.1:");
        } catch (Exception e) {
            return false;
        }
    }

    public Map<String, String> getConnectionInfo() {
        Map<String, String> info = new HashMap<>();

        try {
            File mysqlFile = new File(plugin.getDataFolder(), "mysql.yml");
            FileConfiguration config = YamlConfiguration.loadConfiguration(mysqlFile);

            info.put("enabled", String.valueOf(config.getBoolean("enabled", false)));
            info.put("host", config.getString("host", "localhost"));
            info.put("port", String.valueOf(config.getInt("port", 3306)));
            info.put("database", config.getString("database", "justrtp"));
            info.put("username", config.getString("username", "root"));

            if (dataSource != null && !dataSource.isClosed()) {
                info.put("pool_size", String.valueOf(dataSource.getHikariPoolMXBean().getTotalConnections()));
                info.put("active_connections", String.valueOf(dataSource.getHikariPoolMXBean().getActiveConnections()));
                info.put("idle_connections", String.valueOf(dataSource.getHikariPoolMXBean().getIdleConnections()));
                info.put("waiting_threads",
                        String.valueOf(dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection()));
            }
        } catch (Exception e) {
            plugin.getRTPLogger().debug("SQL", "Failed to get connection info: " + e.getMessage());
        }

        return info;
    }

    private String lastConnectionError = null;

    public String getLastConnectionError() {
        return lastConnectionError;
    }

    public void createZoneSyncTables() {
        if (!isConnected())
            return;

        String createZonesTable = "CREATE TABLE IF NOT EXISTS justrtp_zones (" +
                "zone_id VARCHAR(255) PRIMARY KEY," +
                "zone_data TEXT NOT NULL," +
                "server_name VARCHAR(255)," +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                ")";

        String createMetadataTable = "CREATE TABLE IF NOT EXISTS justrtp_zone_metadata (" +
                "id INT PRIMARY KEY AUTO_INCREMENT," +
                "config_hash VARCHAR(32) NOT NULL," +
                "server_name VARCHAR(255) NOT NULL," +
                "sync_version INT NOT NULL," +
                "timestamp BIGINT NOT NULL," +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                ")";

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(createZonesTable);
            stmt.executeUpdate(createMetadataTable);
            plugin.getRTPLogger().debug("SQL", "[ZoneSync] MySQL zone sync tables created/verified");
        } catch (SQLException e) {
            plugin.getLogger().warning("[ZoneSync] Failed to create zone sync tables: " + e.getMessage());
        }
    }

    public CompletableFuture<Void> saveZone(String zoneId, String zoneData, String serverName) {
        return CompletableFuture.runAsync(() -> {
            threadGuard.assertAsyncDatabase("saveZone");
            if (!isConnected())
                return;

            String sql = "INSERT INTO justrtp_zones (zone_id, zone_data, server_name) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE zone_data = ?, server_name = ?, updated_at = CURRENT_TIMESTAMP";

            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, zoneId);
                pstmt.setString(2, zoneData);
                pstmt.setString(3, serverName);
                pstmt.setString(4, zoneData);
                pstmt.setString(5, serverName);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("[ZoneSync] Failed to save zone '" + zoneId + "': " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Void> saveZonesBatch(Map<String, String> zones, String serverName) {
        return CompletableFuture.runAsync(() -> {
            threadGuard.assertAsyncDatabase("saveZonesBatch");
            if (!isConnected() || zones.isEmpty())
                return;

            String sql = "INSERT INTO justrtp_zones (zone_id, zone_data, server_name) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE zone_data = VALUES(zone_data), server_name = VALUES(server_name), " +
                    "updated_at = CURRENT_TIMESTAMP";

            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {

                conn.setAutoCommit(false);

                for (Map.Entry<String, String> entry : zones.entrySet()) {
                    pstmt.setString(1, entry.getKey());
                    pstmt.setString(2, entry.getValue());
                    pstmt.setString(3, serverName);
                    pstmt.addBatch();
                }

                pstmt.executeBatch();
                conn.commit();

                plugin.getRTPLogger().debug("SQL", "[ZoneSync] Saved " + zones.size() + " zones to MySQL in batch");
            } catch (SQLException e) {
                plugin.getLogger().warning("[ZoneSync] Failed to save zones batch: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Optional<String>> getZone(String zoneId) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncDatabase("getZone");
            if (!isConnected())
                return Optional.empty();

            String sql = "SELECT zone_data FROM justrtp_zones WHERE zone_id = ?";

            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, zoneId);

                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getString("zone_data"));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("[ZoneSync] Failed to get zone '" + zoneId + "': " + e.getMessage());
            }

            return Optional.empty();
        });
    }

    public CompletableFuture<Map<String, String>> getAllZones() {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncDatabase("getAllZones");
            Map<String, String> zones = new HashMap<>();
            if (!isConnected())
                return zones;

            String sql = "SELECT zone_id, zone_data FROM justrtp_zones";

            try (Connection conn = dataSource.getConnection();
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql)) {

                while (rs.next()) {
                    zones.put(rs.getString("zone_id"), rs.getString("zone_data"));
                }

                plugin.getRTPLogger().debug("SQL", "[ZoneSync] Loaded " + zones.size() + " zones from MySQL");
            } catch (SQLException e) {
                plugin.getLogger().warning("[ZoneSync] Failed to get all zones: " + e.getMessage());
            }

            return zones;
        });
    }

    public CompletableFuture<Boolean> deleteZone(String zoneId) {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncDatabase("deleteZone");
            if (!isConnected())
                return false;

            String sql = "DELETE FROM justrtp_zones WHERE zone_id = ?";

            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, zoneId);
                int rowsAffected = pstmt.executeUpdate();
                return rowsAffected > 0;
            } catch (SQLException e) {
                plugin.getLogger().warning("[ZoneSync] Failed to delete zone '" + zoneId + "': " + e.getMessage());
                return false;
            }
        });
    }

    public CompletableFuture<Integer> getZoneCount() {
        return CompletableFuture.supplyAsync(() -> {
            threadGuard.assertAsyncDatabase("getZoneCount");
            if (!isConnected())
                return 0;

            String sql = "SELECT COUNT(*) FROM justrtp_zones";

            try (Connection conn = dataSource.getConnection();
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("[ZoneSync] Failed to get zone count: " + e.getMessage());
            }

            return 0;
        });
    }

    public CompletableFuture<Void> saveZoneMetadata(String hash, String serverName, int syncVersion, long timestamp) {
        return CompletableFuture.runAsync(() -> {
            if (!isConnected())
                return;

            String deleteSql = "DELETE FROM justrtp_zone_metadata";
            String insertSql = "INSERT INTO justrtp_zone_metadata (config_hash, server_name, sync_version, timestamp) "
                    +
                    "VALUES (?, ?, ?, ?)";

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);

                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate(deleteSql);
                }

                try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                    pstmt.setString(1, hash);
                    pstmt.setString(2, serverName);
                    pstmt.setInt(3, syncVersion);
                    pstmt.setLong(4, timestamp);
                    pstmt.executeUpdate();
                }

                conn.commit();
            } catch (SQLException e) {
                plugin.getLogger().warning("[ZoneSync] Failed to save zone metadata: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<Optional<Map<String, Object>>> getZoneMetadata() {
        return CompletableFuture.supplyAsync(() -> {
            if (!isConnected())
                return Optional.empty();

            String sql = "SELECT config_hash, server_name, sync_version, timestamp FROM justrtp_zone_metadata " +
                    "ORDER BY id DESC LIMIT 1";

            try (Connection conn = dataSource.getConnection();
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("hash", rs.getString("config_hash"));
                    metadata.put("server", rs.getString("server_name"));
                    metadata.put("version", rs.getInt("sync_version"));
                    metadata.put("timestamp", rs.getLong("timestamp"));
                    return Optional.of(metadata);
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("[ZoneSync] Failed to get zone metadata: " + e.getMessage());
            }

            return Optional.empty();
        });
    }

    public CompletableFuture<Void> updateServerWorlds(String serverName, List<World> worlds) {
        return CompletableFuture.runAsync(() -> {
            if (!isConnected())
                return;

            plugin.getRTPLogger().debug("SQL", "Updating available worlds for server: " + serverName);

            String deleteSql = "DELETE FROM justrtp_server_worlds WHERE server_name = ?";
            String insertSql = "INSERT INTO justrtp_server_worlds (server_name, world_name, world_type, is_enabled) VALUES (?, ?, ?, ?)";

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);

                try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                    deleteStmt.setString(1, serverName);
                    deleteStmt.executeUpdate();
                }

                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    for (World world : worlds) {
                        boolean isEnabled = plugin.getRtpService().isRtpEnabled(world);
                        insertStmt.setString(1, serverName);
                        insertStmt.setString(2, world.getName());
                        insertStmt.setString(3, world.getEnvironment().name());
                        insertStmt.setBoolean(4, isEnabled);
                        insertStmt.addBatch();
                    }
                    insertStmt.executeBatch();
                }

                conn.commit();
                plugin.getRTPLogger().debug("SQL",
                        "Successfully updated " + worlds.size() + " worlds for server " + serverName);
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to update server worlds for " + serverName + ": " + e.getMessage());
            }
        });
    }

    public CompletableFuture<List<String>> getServerWorlds(String serverName) {
        return CompletableFuture.supplyAsync(() -> {
            if (!isConnected())
                return Collections.emptyList();

            plugin.getRTPLogger().debug("SQL", "Fetching available worlds for server: " + serverName);

            String sql = "SELECT world_name FROM justrtp_server_worlds " +
                    "WHERE server_name = ? AND is_enabled = TRUE " +
                    "AND last_updated > DATE_SUB(NOW(), INTERVAL 5 MINUTE) " +
                    "ORDER BY world_name";

            List<String> worlds = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                    PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, serverName);

                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        worlds.add(rs.getString("world_name"));
                    }
                }

                plugin.getRTPLogger().debug("SQL",
                        "Found " + worlds.size() + " enabled worlds for server " + serverName);
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to fetch server worlds for " + serverName + ": " + e.getMessage());
            }

            return worlds;
        });
    }

    public CompletableFuture<List<String>> getActiveServers() {
        return CompletableFuture.supplyAsync(() -> {
            if (!isConnected())
                return Collections.emptyList();

            String sql = "SELECT DISTINCT server_name FROM justrtp_server_worlds " +
                    "WHERE last_updated > DATE_SUB(NOW(), INTERVAL 5 MINUTE) " +
                    "ORDER BY server_name";

            List<String> servers = new ArrayList<>();

            try (Connection conn = dataSource.getConnection();
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    servers.add(rs.getString("server_name"));
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to fetch active servers: " + e.getMessage());
            }

            return servers;
        });
    }
}