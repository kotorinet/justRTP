package eu.kotori.justRTP.utils;

import eu.kotori.justRTP.JustRTP;

import java.util.logging.Level;
import java.util.logging.Logger;

public class RTPLogger {

    private final JustRTP plugin;
    private final Logger logger;
    private boolean debugEnabled;

    private static final String PREFIX = "";

    public RTPLogger(JustRTP plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        reload();
    }

    public void reload() {
        this.debugEnabled = plugin.getConfig().getBoolean("settings.debug", false);
    }

    public void printBanner() {
        String version = plugin.getPluginMeta().getVersion();
        logger.info("");
        logger.info("╔══════════════════════════════════════╗");
        logger.info("║  JustRTP - Random Teleport           ║");
        logger.info("║  Version: " + version + "                      ║");
        logger.info("║  Author: Kotori                      ║");
        logger.info("╚══════════════════════════════════════╝");
        logger.info("");
    }

    public void printInitializationHeader() {
        logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        logger.info("PLUGIN INITIALIZATION");
        logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    public void logModule(String moduleName, boolean success) {
        String status = success ? "✓ LOADED" : "✗ FAILED";
        logger.info(PREFIX + moduleName + " ... " + status);
    }

    public void logModule(String moduleName, String details, boolean success) {
        String status = success ? "✓" : "✗";
        logger.info(PREFIX + status + " " + moduleName + " (" + details + ")");
    }

    public void info(String category, String message) {
        if (category != null && !category.isEmpty()) {
            logger.info(PREFIX + "[" + category + "] " + message);
        } else {
            logger.info(PREFIX + message);
        }
    }

    public void info(String message) {
        info(null, message);
    }

    public void warn(String category, String message) {
        if (category != null && !category.isEmpty()) {
            logger.warning(PREFIX + "[" + category + "] " + message);
        } else {
            logger.warning(PREFIX + message);
        }
    }

    public void warn(String message) {
        warn(null, message);
    }

    public void error(String category, String message) {
        if (category != null && !category.isEmpty()) {
            logger.severe(PREFIX + "[" + category + "] " + message);
        } else {
            logger.severe(PREFIX + message);
        }
    }

    public void error(String message) {
        error(null, message);
    }

    public void error(String category, String message, Throwable throwable) {
        error(category, message);
        if (debugEnabled) {
            logger.log(Level.SEVERE, "Exception details:", throwable);
        } else {
            logger.severe(PREFIX + "  └─ " + throwable.getMessage());
            logger.severe(PREFIX + "  └─ Enable debug mode for full stack trace");
        }
    }

    public void debug(String category, String message) {
        if (!debugEnabled) {
            return;
        }

        if (category != null && !category.isEmpty()) {
            logger.info(PREFIX + "[" + category + "] [DEBUG] " + message);
        } else {
            logger.info(PREFIX + "[DEBUG] " + message);
        }
    }

    public void debug(String message) {
        debug(null, message);
    }

    public void success(String category, String message) {
        if (category != null && !category.isEmpty()) {
            logger.info(PREFIX + "✓ [" + category + "] " + message);
        } else {
            logger.info(PREFIX + "✓ " + message);
        }
    }

    public void success(String message) {
        success(null, message);
    }

    public void separator() {
        logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    public void section(String title) {
        logger.info("");
        logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        logger.info(title);
        logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    public void config(String key, Object value) {
        logger.info(PREFIX + "  " + key + ": " + value);
    }

    public void stat(String label, long value) {
        logger.info(PREFIX + "  " + label + ": " + value);
    }

    public void stat(String label, long value, String unit) {
        logger.info(PREFIX + "  " + label + ": " + value + " " + unit);
    }

    public void printStartupSummary(long startupTimeMs, int loadedWorlds, int cachedLocations) {
        separator();
        logger.info("✓ PLUGIN ENABLED SUCCESSFULLY");
        separator();
        stat("Startup Time", startupTimeMs, "ms");
        stat("Loaded Worlds", loadedWorlds);
        stat("Cached Locations", cachedLocations);
        separator();
        logger.info("");
    }

    public void logTeleport(String playerName, String fromWorld, String toWorld, int x, int y, int z) {
        debug("TELEPORT", String.format("%s: %s → %s (%d, %d, %d)",
                playerName, fromWorld, toWorld, x, y, z));
    }

    public void logCache(String operation, String worldName, int count) {
        debug("CACHE", String.format("%s for %s: %d locations", operation, worldName, count));
    }

    public void logDatabase(String operation, boolean success, long durationMs) {
        String status = success ? "SUCCESS" : "FAILED";
        debug("DATABASE", String.format("%s %s (%dms)", operation, status, durationMs));
    }

    public void logRedis(String operation, boolean success, long durationMs) {
        String status = success ? "SUCCESS" : "FAILED";
        debug("REDIS", String.format("%s %s (%dms)", operation, status, durationMs));
    }

    public void logProxy(String operation, String targetServer, boolean success) {
        String status = success ? "SUCCESS" : "FAILED";
        debug("PROXY", String.format("%s to %s: %s", operation, targetServer, status));
    }

    public void logPermission(String playerName, String permission, boolean granted) {
        String status = granted ? "GRANTED" : "DENIED";
        debug("PERMISSION", String.format("%s: %s - %s", playerName, permission, status));
    }

    public void logEconomy(String playerName, double amount, String reason, boolean success) {
        String status = success ? "SUCCESS" : "FAILED";
        debug("ECONOMY", String.format("%s: $%.2f (%s) - %s", playerName, amount, reason, status));
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }
}
