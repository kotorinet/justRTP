package eu.kotori.justRTP;

import eu.kotori.justRTP.addons.AddonManager;
import eu.kotori.justRTP.bstats.bukkit.Metrics;
import eu.kotori.justRTP.commands.RTPZoneCommand;
import eu.kotori.justRTP.commands.RTPZoneTabCompleter;
import eu.kotori.justRTP.handlers.JumpRTPListener;
import eu.kotori.justRTP.handlers.PlayerListener;
import eu.kotori.justRTP.handlers.RTPService;
import eu.kotori.justRTP.handlers.WorldListener;
import eu.kotori.justRTP.handlers.hooks.PlaceholderAPIHook;
import eu.kotori.justRTP.handlers.hooks.VaultHook;
import eu.kotori.justRTP.managers.*;
import eu.kotori.justRTP.utils.*;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class JustRTP extends JavaPlugin {

    private static final int CONFIG_VERSION = 28;
    private static final int MESSAGES_CONFIG_VERSION = 19;
    private static final int MYSQL_CONFIG_VERSION = 5;
    private static final int ANIMATIONS_CONFIG_VERSION = 2;
    private static final int COMMANDS_CONFIG_VERSION = 4;
    private static final int ZONES_CONFIG_VERSION = 11;
    private static final int HOLOGRAMS_CONFIG_VERSION = 8;
    private static final int REDIS_CONFIG_VERSION = 3;
    private static final int CUSTOM_LOCATIONS_CONFIG_VERSION = 1;

    private static JustRTP instance;
    private RTPLogger rtpLogger;
    private ConfigManager configManager;
    private CommandManager commandManager;
    private LocaleManager localeManager;
    private CooldownManager cooldownManager;
    private DelayManager delayManager;
    private RTPService rtpService;
    private TeleportQueueManager teleportQueueManager;
    private EffectsManager effectsManager;
    private FoliaScheduler foliaScheduler;
    private ProxyManager proxyManager;
    private DatabaseManager databaseManager;
    private LocationCacheManager locationCacheManager;
    private AnimationManager animationManager;
    private ConfirmationManager confirmationManager;
    private VaultHook vaultHook;
    private PlaceholderAPIHook placeholderAPIHook;
    private CrossServerManager crossServerManager;
    private RTPZoneManager rtpZoneManager;
    private ZoneSetupManager zoneSetupManager;
    private HologramManager hologramManager;
    private ZoneSyncManager zoneSyncManager;
    private CustomLocationManager customLocationManager;
    private RTPGuiManager rtpGuiManager;
    private SpectatorSwitchManager spectatorSwitchManager;
    private NearClaimRTPManager nearClaimRTPManager;
    private AddonManager addonManager;
    private JumpRTPListener jumpRTPListener;
    private UpdateChecker updateChecker;

    private long startupTime;

    @Override
    public void onEnable() {
        startupTime = System.currentTimeMillis();
        instance = this;

        System.setProperty("org.slf4j.simpleLogger.log.eu.kotori.justRTP.lib.hikaricp", "warn");
        System.setProperty("org.slf4j.simpleLogger.showDateTime", "false");
        System.setProperty("org.slf4j.simpleLogger.showThreadName", "false");
        System.setProperty("org.slf4j.simpleLogger.showLogName", "false");

        saveDefaultConfig();
        saveDefaultResource("messages.yml");
        saveDefaultResource("animations.yml");
        saveDefaultMysqlConfig();
        saveDefaultResource("commands.yml");
        saveDefaultResource("rtp_zones.yml");
        saveDefaultResource("holograms.yml");
        saveDefaultResource("display_entities.yml");
        saveDefaultResource("cache.yml");
        saveDefaultResource("redis.yml");
        saveDefaultResource("custom_locations.yml");

        configManager = new ConfigManager(this);

        rtpLogger = new RTPLogger(this);
        rtpLogger.printBanner();
        rtpLogger.printInitializationHeader();

        rtpLogger.info("CONFIG", "Checking configuration versions...");
        ConfigUpdater.update(this, "config.yml", CONFIG_VERSION);
        ConfigUpdater.update(this, "messages.yml", MESSAGES_CONFIG_VERSION);
        ConfigUpdater.update(this, "mysql.yml", MYSQL_CONFIG_VERSION);
        ConfigUpdater.update(this, "animations.yml", ANIMATIONS_CONFIG_VERSION);
        ConfigUpdater.update(this, "commands.yml", COMMANDS_CONFIG_VERSION);
        ConfigUpdater.update(this, "rtp_zones.yml", ZONES_CONFIG_VERSION);
        ConfigUpdater.update(this, "holograms.yml", HOLOGRAMS_CONFIG_VERSION);
        ConfigUpdater.update(this, "redis.yml", REDIS_CONFIG_VERSION);
        ConfigUpdater.update(this, "custom_locations.yml", CUSTOM_LOCATIONS_CONFIG_VERSION);
        rtpLogger.debug("INIT", "Initializing core managers...");
        commandManager = new CommandManager(this);
        foliaScheduler = new FoliaScheduler(this);

        if (configManager.isProxyMySqlEnabled()) {
            rtpLogger.info("DATABASE", "Initializing MySQL connection...");
            databaseManager = new DatabaseManager(this, foliaScheduler);
            if (!databaseManager.isConnected()) {
                rtpLogger.error("DATABASE", "Failed to connect to MySQL - Proxy features disabled");
            } else {
                rtpLogger.success("DATABASE", "MySQL connection established");
            }
        }

        this.getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        rtpLogger.info("HOOKS", "Checking for external plugin integrations...");
        vaultHook = new VaultHook(this);
        rtpLogger.logModule("Vault Economy", vaultHook.hasEconomy());

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholderAPIHook = new PlaceholderAPIHook(this);
            placeholderAPIHook.register();
            rtpLogger.success("HOOKS", "PlaceholderAPI integration enabled");
        } else {
            rtpLogger.debug("HOOKS", "PlaceholderAPI not found - skipping");
        }

        rtpLogger.debug("INIT", "Loading plugin managers...");
        animationManager = new AnimationManager(this);
        localeManager = new LocaleManager(this);
        proxyManager = new ProxyManager(this);
        crossServerManager = new CrossServerManager(this);
        cooldownManager = new CooldownManager();
        rtpService = new RTPService(this);
        delayManager = new DelayManager(this);
        teleportQueueManager = new TeleportQueueManager(this);
        effectsManager = new EffectsManager(this);
        confirmationManager = new ConfirmationManager(this);
        zoneSetupManager = new ZoneSetupManager(this);
        hologramManager = new HologramManager(this);
        rtpZoneManager = new RTPZoneManager(this);
        zoneSyncManager = new ZoneSyncManager(this);
        customLocationManager = new CustomLocationManager(this);
        rtpGuiManager = new RTPGuiManager(this);
        spectatorSwitchManager = new SpectatorSwitchManager(this);
        nearClaimRTPManager = new NearClaimRTPManager(this);
        addonManager = new AddonManager(this);

        locationCacheManager = new LocationCacheManager(this);

        rtpLogger.debug("INIT", "Registering commands and event listeners...");
        commandManager.registerCommands();
        registerZoneCommands();
        playerListener = new PlayerListener(this);
        getServer().getPluginManager().registerEvents(playerListener, this);
        getServer().getPluginManager().registerEvents(new WorldListener(this), this);

        if (configManager.isJumpRtpEnabled()) {
            jumpRTPListener = new JumpRTPListener(this);
            getServer().getPluginManager().registerEvents(jumpRTPListener, this);
            rtpLogger.success("JUMPRTP", "Jump RTP feature enabled");
        }

        rtpLogger.success("COMMANDS", "Commands registered successfully");

        foliaScheduler.runLater(() -> {
            rtpLogger.info("HOLOGRAMS", "Initializing hologram system...");
            hologramManager.initialize();

            if (!hologramManager.isUsingPacketEvents() && !hologramManager.isUsingFancyHolograms()) {
                rtpLogger.warn("HOLOGRAMS", "Using entity-based holograms (Display entities)");
                rtpLogger.info("HOLOGRAMS",
                        "Recommendation: Install FancyHolograms or PacketEvents for better performance");
                rtpLogger.debug("HOLOGRAMS", "FancyHolograms: https//:modrinth.com/plugin/fancyholograms");
                rtpLogger.debug("HOLOGRAMS", "PacketEvents: https://modrinth.com/plugin/packetevents");
            } else if (hologramManager.isUsingFancyHolograms()) {
                rtpLogger.success("HOLOGRAMS", "FancyHolograms integration enabled");
            } else if (hologramManager.isUsingPacketEvents()) {
                rtpLogger.success("HOLOGRAMS", "PacketEvents integration enabled");
            }

            hologramManager.cleanupAllHolograms();

            rtpLogger.info("ZONES", "Loading RTP zones...");
            rtpZoneManager.loadZones();

            rtpLogger.info("CACHE", "Initializing location cache...");
            locationCacheManager.initialize();

            if (configManager.isZoneSyncEnabled()) {
                rtpLogger.info("SYNC", "Initializing zone synchronization...");
                zoneSyncManager.initialize();
            }

            rtpLogger.info("ADDONS", "Loading addons...");
            addonManager.loadAddons();

            StartupMessage.sendStartupMessage(this);

            int onlinePlayers = getServer().getOnlinePlayers().size();
            if (onlinePlayers > 0) {
                rtpLogger.debug("ZONES", "Initializing " + onlinePlayers + " online players in zones");
                for (Player player : getServer().getOnlinePlayers()) {
                    rtpZoneManager.handlePlayerMove(player, player.getLocation());
                }
            }

            startServerWorldsHeartbeat();

            long startupDuration = System.currentTimeMillis() - startupTime;
            int loadedWorlds = getServer().getWorlds().size();
            int cachedLocations = locationCacheManager.getTotalCachedLocations();
            rtpLogger.printStartupSummary(startupDuration, loadedWorlds, cachedLocations);
        }, 20L);

        if (getConfig().getBoolean("bstats.enabled", true)) {
            int pluginId = 26850;
            new Metrics(this, pluginId);
            rtpLogger.info("METRICS", "bStats enabled - Thank you for supporting plugin development!");
        } else {
            rtpLogger.debug("METRICS", "bStats disabled in configuration");
        }

        updateChecker = new UpdateChecker(this);
        getServer().getPluginManager().registerEvents(updateChecker, this);
        updateChecker.checkForUpdates();
    }

    @Override
    public void onDisable() {
        if (rtpLogger != null) {
            rtpLogger.separator();
            rtpLogger.info("SHUTDOWN", "Disabling JustRTP...");
        }

        if (addonManager != null) {
            rtpLogger.debug("SHUTDOWN", "Disabling addons...");
            addonManager.disableAddons();
        }

        if (rtpZoneManager != null) {
            rtpLogger.debug("SHUTDOWN", "Shutting down zone tasks...");
            rtpZoneManager.shutdownAllTasks();
        }

        if (effectsManager != null) {
            rtpLogger.debug("SHUTDOWN", "Removing boss bars...");
            effectsManager.removeAllBossBars();
        }

        if (databaseManager != null && databaseManager.isConnected()) {
            rtpLogger.info("DATABASE", "Closing MySQL connection...");
            databaseManager.close();
        }

        if (locationCacheManager != null) {
            rtpLogger.info("CACHE", "Saving location cache...");
            locationCacheManager.shutdown();
        }

        if (hologramManager != null) {
            rtpLogger.debug("SHUTDOWN", "Cleaning up holograms...");
            hologramManager.cleanupAllHolograms();
        }

        this.getServer().getMessenger().unregisterOutgoingPluginChannel(this);

        if (rtpLogger != null) {
            rtpLogger.success("Plugin disabled successfully");
            rtpLogger.separator();
        }
    }

    public void reload() {
        if (rtpZoneManager != null)
            rtpZoneManager.shutdownAllTasks();

        ConfigUpdater.update(this, "config.yml", CONFIG_VERSION);
        ConfigUpdater.update(this, "messages.yml", MESSAGES_CONFIG_VERSION);
        ConfigUpdater.update(this, "mysql.yml", MYSQL_CONFIG_VERSION);
        ConfigUpdater.update(this, "animations.yml", ANIMATIONS_CONFIG_VERSION);
        ConfigUpdater.update(this, "commands.yml", COMMANDS_CONFIG_VERSION);
        ConfigUpdater.update(this, "rtp_zones.yml", ZONES_CONFIG_VERSION);
        ConfigUpdater.update(this, "holograms.yml", HOLOGRAMS_CONFIG_VERSION);
        ConfigUpdater.update(this, "redis.yml", REDIS_CONFIG_VERSION);
        ConfigUpdater.update(this, "custom_locations.yml", CUSTOM_LOCATIONS_CONFIG_VERSION);

        vaultHook.setupEconomy();

        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null && placeholderAPIHook == null) {
            placeholderAPIHook = new PlaceholderAPIHook(this);
            placeholderAPIHook.register();
            getLogger().info("PlaceholderAPI hook registered on reload.");
        }

        crossServerManager.reload();
        rtpLogger.reload();
        localeManager.loadMessages();
        configManager.reload();
        rtpService.loadConfigValues();
        teleportQueueManager.reload();
        animationManager.reload();
        effectsManager.reload();
        commandManager.registerCommands();
        hologramManager.initialize();
        hologramManager.reloadConfiguration();
        rtpZoneManager.loadZones();
        customLocationManager.reload();
        rtpGuiManager.reload();
        spectatorSwitchManager.reload();
        nearClaimRTPManager.reload();

        if (databaseManager != null && databaseManager.isConnected()) {
            databaseManager.close();
        }
        if (configManager.isProxyMySqlEnabled()) {
            databaseManager = new DatabaseManager(this, foliaScheduler);
            if (!databaseManager.isConnected()) {
                getLogger().severe("Disabling proxy features due to failed MySQL connection on reload.");
            }
        } else {
            databaseManager = null;
        }

        if (locationCacheManager != null) {
            locationCacheManager.shutdown();
        }
        locationCacheManager = new LocationCacheManager(this);
        locationCacheManager.initialize();
        animationManager = new AnimationManager(this);

        for (Player player : getServer().getOnlinePlayers()) {
            rtpZoneManager.handlePlayerMove(player, player.getLocation());
        }

        getLogger().info("JustRTP configuration reloaded and updated.");
    }

    private void registerZoneCommands() {
        PluginCommand rtpZoneCmd = getCommand("rtpzone");
        if (rtpZoneCmd != null) {
            rtpZoneCmd.setExecutor(new RTPZoneCommand(this));
            rtpZoneCmd.setTabCompleter(new RTPZoneTabCompleter(this));
        }
    }

    private void saveDefaultMysqlConfig() {
        File mysqlFile = new File(getDataFolder(), "mysql.yml");
        if (!mysqlFile.exists()) {
            saveResource("mysql.yml", false);
        }
    }

    private void saveDefaultResource(String resourcePath) {
        File resourceFile = new File(getDataFolder(), resourcePath);
        if (!resourceFile.exists()) {
            saveResource(resourcePath, false);
        }
    }

    @Deprecated
    public void debug(String message) {
        if (rtpLogger != null) {
            rtpLogger.debug(message);
        } else if (configManager != null && configManager.isDebugMode()) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    public boolean isDebugMode() {
        return configManager != null && configManager.isDebugMode();
    }

    public static JustRTP getInstance() {
        return instance;
    }

    public RTPLogger getRTPLogger() {
        return rtpLogger;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public LocaleManager getLocaleManager() {
        return localeManager;
    }

    public CooldownManager getCooldownManager() {
        return cooldownManager;
    }

    public DelayManager getDelayManager() {
        return delayManager;
    }

    public RTPService getRtpService() {
        return rtpService;
    }

    public TeleportQueueManager getTeleportQueueManager() {
        return teleportQueueManager;
    }

    public EffectsManager getEffectsManager() {
        return effectsManager;
    }

    public FoliaScheduler getFoliaScheduler() {
        return foliaScheduler;
    }

    public ProxyManager getProxyManager() {
        return proxyManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public LocationCacheManager getLocationCacheManager() {
        return locationCacheManager;
    }

    public AnimationManager getAnimationManager() {
        return animationManager;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public ConfirmationManager getConfirmationManager() {
        return confirmationManager;
    }

    public VaultHook getVaultHook() {
        return vaultHook;
    }

    public PlaceholderAPIHook getPlaceholderAPIHook() {
        return placeholderAPIHook;
    }

    public CrossServerManager getCrossServerManager() {
        return crossServerManager;
    }

    public RTPZoneManager getRtpZoneManager() {
        return rtpZoneManager;
    }

    public ZoneSetupManager getZoneSetupManager() {
        return zoneSetupManager;
    }

    public HologramManager getHologramManager() {
        return hologramManager;
    }

    public ZoneSyncManager getZoneSyncManager() {
        return zoneSyncManager;
    }

    public AddonManager getAddonManager() {
        return addonManager;
    }

    public CustomLocationManager getCustomLocationManager() {
        return customLocationManager;
    }

    public RTPGuiManager getRtpGuiManager() {
        return rtpGuiManager;
    }

    public SpectatorSwitchManager getSpectatorSwitchManager() {
        return spectatorSwitchManager;
    }

    public NearClaimRTPManager getNearClaimRTPManager() {
        return nearClaimRTPManager;
    }

    private DataManager dataManager;

    public DataManager getDataManager() {
        if (dataManager == null) {
            dataManager = new DataManager(this);
        }
        return dataManager;
    }

    private PlayerListener playerListener;

    public PlayerListener getPlayerListener() {
        return playerListener;
    }

    public void setPlayerListener(PlayerListener listener) {
        this.playerListener = listener;
    }

    private void startServerWorldsHeartbeat() {
        if (!configManager.isProxyMySqlEnabled() || databaseManager == null || !databaseManager.isConnected()) {
            rtpLogger.debug("PROXY", "Server worlds heartbeat disabled (MySQL not configured)");
            return;
        }

        String thisServer = configManager.getProxyThisServerName();
        if (thisServer == null || thisServer.isEmpty() || thisServer.equals("server-name")) {
            rtpLogger.warn("PROXY", "Cannot start server worlds heartbeat: 'this_server_name' not configured");
            return;
        }

        updateServerWorldsList();

        foliaScheduler.runTimer(() -> updateServerWorldsList(), 2400L, 2400L);

        if (configManager.isJumpRtpEnabled() && jumpRTPListener != null) {
            foliaScheduler.runTimer(() -> jumpRTPListener.cleanupCooldowns(), 1200L, 1200L);
        }

        rtpLogger.debug("PROXY", "Server worlds heartbeat started for server: " + thisServer);
    }

    private void updateServerWorldsList() {
        String thisServer = configManager.getProxyThisServerName();
        List<World> worlds = new ArrayList<>(getServer().getWorlds());

        databaseManager.updateServerWorlds(thisServer, worlds)
                .exceptionally(ex -> {
                    rtpLogger.error("DATABASE", "Failed to update server worlds: " + ex.getMessage());
                    return null;
                });
    }
}