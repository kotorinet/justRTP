package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class HologramManager {

    private final JustRTP plugin;
    private FileConfiguration hologramsConfig;
    private FileConfiguration displayEntitiesConfig;
    private final File displayEntitiesFile;
    private final Map<String, HologramInstance> activeHolograms = new ConcurrentHashMap<>();
    private final Map<String, Boolean> hologramCreationLocks = new ConcurrentHashMap<>();
    private final String HOLOGRAM_METADATA_KEY = "justrtp_hologram";

    private PacketHologramManager packetHologramManager;
    private FancyHologramManager fancyHologramManager;
    private boolean usePacketEvents = false;
    private boolean useFancyHolograms = false;
    private HologramType hologramType = HologramType.DISPLAY_ENTITY;

    private enum HologramType {
        DISPLAY_ENTITY,
        PACKET_EVENTS,
        FANCY_HOLOGRAMS
    }

    private static class HologramInstance {
        final List<TextDisplay> displays;
        final AtomicLong particleTick = new AtomicLong(0);

        HologramInstance(List<TextDisplay> displays) {
            this.displays = displays;
        }
    }

    public HologramManager(JustRTP plugin) {
        this.plugin = plugin;
        this.displayEntitiesFile = new File(plugin.getDataFolder(), "display_entities.yml");

    }

    public void initialize() {
        File configFile = new File(plugin.getDataFolder(), "holograms.yml");
        if (!configFile.exists()) {
            plugin.saveResource("holograms.yml", false);
        }
        hologramsConfig = YamlConfiguration.loadConfiguration(configFile);
        loadDisplayEntities();

        String preferredEngine = hologramsConfig.getString("preferred-engine", "auto").toLowerCase();
        plugin.getRTPLogger().debug("HOLOGRAM", "Preferred hologram engine: " + preferredEngine);

        initializeHologramEngines(preferredEngine);

        if (useFancyHolograms && fancyHologramManager != null) {
            fancyHologramManager.setHologramsConfig(hologramsConfig);
            fancyHologramManager.loadExistingHolograms();
            plugin.getRTPLogger().debug("HOLOGRAM",
                    "FancyHologramManager initialized with holograms.yml config and loaded existing holograms");
        }

        if (usePacketEvents && packetHologramManager != null) {
            packetHologramManager.initialize();
        }
    }

    private void initializeHologramEngines(String preferredEngine) {
        switch (preferredEngine) {
            case "fancyholograms":
                if (initializeFancyHolograms()) {
                    plugin.getLogger().info("Using FancyHolograms (forced by config)");
                } else {
                    plugin.getLogger().warning("FancyHolograms forced but not available! Falling back to auto-detect.");
                    initializeWithAutoDetect();
                }
                break;

            case "packetevents":
                if (initializePacketEvents()) {
                    plugin.getLogger().info("Using PacketEvents (forced by config)");
                } else {
                    plugin.getLogger().warning("PacketEvents forced but not available! Falling back to auto-detect.");
                    initializeWithAutoDetect();
                }
                break;

            case "entity":
                this.hologramType = HologramType.DISPLAY_ENTITY;
                plugin.getLogger().info("Using Display Entities (forced by config)");
                break;

            case "auto":
            default:
                initializeWithAutoDetect();
                break;
        }
    }

    private void initializeWithAutoDetect() {
        if (initializeFancyHolograms()) {
            plugin.getLogger().info("FancyHolograms detected! Using FancyHolograms for zone displays.");
            return;
        }

        if (initializePacketEvents()) {
            plugin.getLogger().info("PacketEvents detected! Using high-performance packet-based holograms.");
            return;
        }

        this.hologramType = HologramType.DISPLAY_ENTITY;
        plugin.getRTPLogger().debug("HOLOGRAM", "Using entity-based holograms (Display entities).");
    }

    private boolean initializeFancyHolograms() {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("FancyHolograms")) {
                plugin.getRTPLogger().debug("HOLOGRAM", "FancyHolograms plugin not detected");
                return false;
            }

            plugin.getRTPLogger().debug("HOLOGRAM", "FancyHolograms plugin detected, attempting to load classes...");

            if (fancyHologramManager != null && fancyHologramManager.isAvailable()) {
                plugin.getRTPLogger().debug("HOLOGRAM",
                        "FancyHologramManager already initialized, skipping recreation");
                this.useFancyHolograms = true;
                this.hologramType = HologramType.FANCY_HOLOGRAMS;
                return true;
            }

            try {
                Class<?> managerClass = Class.forName("eu.kotori.justRTP.managers.FancyHologramManager");
                plugin.getRTPLogger().debug("HOLOGRAM", "FancyHologramManager class loaded successfully");

                java.lang.reflect.Constructor<?> constructor = managerClass.getConstructor(JustRTP.class);

                Object managerInstance = constructor.newInstance(plugin);
                this.fancyHologramManager = (FancyHologramManager) managerInstance;

                plugin.getRTPLogger().debug("HOLOGRAM", "FancyHologramManager instance created, initializing...");
                fancyHologramManager.initialize();

                if (fancyHologramManager.isAvailable()) {
                    this.useFancyHolograms = true;
                    this.hologramType = HologramType.FANCY_HOLOGRAMS;
                    plugin.getRTPLogger().debug("HOLOGRAM", "FancyHolograms initialized successfully!");
                    return true;
                } else {
                    plugin.getRTPLogger().debug("HOLOGRAM", "FancyHologramManager not available");
                }

            } catch (ClassNotFoundException e) {
                plugin.getLogger().warning("FancyHolograms classes not found. Using fallback holograms.");
                plugin.getRTPLogger().debug("HOLOGRAM", "Missing class: " + e.getMessage());
            } catch (NoClassDefFoundError e) {
                plugin.getLogger().warning("FancyHolograms API classes not compatible. Using fallback holograms.");
                plugin.getRTPLogger().debug("HOLOGRAM", "Missing dependency: " + e.getMessage());
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to initialize FancyHolograms: " + e.getMessage());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking for FancyHolograms: " + e.getMessage());
        }
        return false;
    }

    private boolean initializePacketEvents() {
        try {
            if (Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
                this.packetHologramManager = new PacketHologramManager(plugin);
                this.usePacketEvents = packetHologramManager.isPacketEventsAvailable();

                if (usePacketEvents) {
                    this.hologramType = HologramType.PACKET_EVENTS;
                    Bukkit.getPluginManager().registerEvents(packetHologramManager, plugin);
                    return true;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to initialize PacketEvents holograms: " + e.getMessage());
            plugin.getRTPLogger().debug("HOLOGRAM", "Error details: " + e.toString());
        }
        return false;
    }

    public boolean isUsingPacketEvents() {
        return usePacketEvents;
    }

    public boolean isUsingFancyHolograms() {
        return useFancyHolograms;
    }

    private void loadDisplayEntities() {
        try {
            if (!displayEntitiesFile.exists()) {
                displayEntitiesFile.createNewFile();
            }
            displayEntitiesConfig = YamlConfiguration.loadConfiguration(displayEntitiesFile);
            if (displayEntitiesConfig.getConfigurationSection("zones") == null) {
                displayEntitiesConfig.createSection("zones");
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Could not create or load display_entities.yml!");
            e.printStackTrace();
        }
    }

    private void saveDisplayEntities() {
        try {
            displayEntitiesConfig.save(displayEntitiesFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save display_entities.yml!");
            e.printStackTrace();
        }
    }

    public boolean isHologramActive(String zoneId) {
        if (useFancyHolograms && fancyHologramManager != null) {
            return fancyHologramManager.isHologramActive(zoneId);
        }

        if (usePacketEvents && packetHologramManager != null) {
            return packetHologramManager.isHologramActive(zoneId);
        }

        HologramInstance instance = activeHolograms.get(zoneId.toLowerCase());
        if (instance == null) return false;

        boolean anyValid = instance.displays.stream().anyMatch(d -> d != null && d.isValid() && !d.isDead());
        if (!anyValid) {
            plugin.getRTPLogger().debug("HOLOGRAM",
                    "All display entities for zone " + zoneId + " are dead/invalid, clearing stale reference");
            activeHolograms.remove(zoneId.toLowerCase());
            return false;
        }
        return true;
    }

    public boolean isBeingCreated(String zoneId) {
        return hologramCreationLocks.containsKey(zoneId.toLowerCase());
    }

    public void refreshHologramsForPlayer(Player player) {
        if (player == null || !player.isOnline()) return;

        if (usePacketEvents && packetHologramManager != null) {
            packetHologramManager.updateHologramVisibility(player);
            return;
        }

        if (useFancyHolograms && fancyHologramManager != null) {
            fancyHologramManager.refreshForPlayer(player);
            return;
        }

        for (Map.Entry<String, HologramInstance> entry : activeHolograms.entrySet()) {
            HologramInstance instance = entry.getValue();
            boolean anyValid = instance.displays.stream().anyMatch(d -> d != null && d.isValid() && !d.isDead());
            if (!anyValid) {
                plugin.getRTPLogger().debug("HOLOGRAM",
                        "Refresh detected dead hologram for zone: " + entry.getKey() + ", clearing for respawn");
                activeHolograms.remove(entry.getKey());
            }
        }
    }

    public double getDefaultYOffset() {
        return hologramsConfig.getDouble("hologram-settings.y-offset", 2.5);
    }

    public void createOrUpdateHologram(String zoneId, Location location, int viewDistance) {
        if (useFancyHolograms && fancyHologramManager != null) {
            fancyHologramManager.createOrUpdateHologram(zoneId, location, viewDistance);
            return;
        }

        if (usePacketEvents && packetHologramManager != null) {
            packetHologramManager.createOrUpdateHologram(zoneId, location, viewDistance);
            return;
        }

        if (hologramCreationLocks.putIfAbsent(zoneId.toLowerCase(), true) != null) {
            plugin.getRTPLogger().debug("HOLOGRAM",
                    "Hologram creation/update for " + zoneId + " is already in progress.");
            return;
        }

        plugin.getFoliaScheduler().runAtLocation(location, () -> {
            try {
                removeHologram(zoneId);
                World world = location.getWorld();
                if (world == null)
                    return;

                List<String> lines = hologramsConfig.getStringList("hologram-settings.lines");
                double lineSpacing = hologramsConfig.getDouble("hologram-settings.line-spacing", 0.35);
                float scale = (float) hologramsConfig.getDouble("hologram-settings.scale", 1.0);
                Location textLocation = location.clone();

                List<TextDisplay> textDisplays = new ArrayList<>();
                List<String> entityUuids = new ArrayList<>();

                for (String line : lines) {
                    if (line.isEmpty()) {
                        textLocation.subtract(0, lineSpacing, 0);
                        continue;
                    }
                    TextDisplay display = world.spawn(textLocation, TextDisplay.class, d -> {
                        Transformation transformation = d.getTransformation();
                        transformation.getScale().set(new Vector3f(scale, scale, scale));
                        d.setTransformation(transformation);
                        d.setBillboard(Display.Billboard.CENTER);
                        d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                        d.setShadowed(true);
                        d.setPersistent(false);

                        d.setViewRange(viewDistance);
                        d.text(MiniMessage.miniMessage().deserialize(line, Placeholder.unparsed("zone_id", zoneId),
                                Placeholder.unparsed("time", "")));
                        d.setMetadata(HOLOGRAM_METADATA_KEY, new FixedMetadataValue(plugin, zoneId));
                    });
                    textDisplays.add(display);
                    entityUuids.add(display.getUniqueId().toString());
                    textLocation.subtract(0, lineSpacing, 0);
                }
                activeHolograms.put(zoneId.toLowerCase(), new HologramInstance(textDisplays));
                displayEntitiesConfig.set("zones." + zoneId.toLowerCase(), entityUuids);
                saveDisplayEntities();
            } finally {
                hologramCreationLocks.remove(zoneId.toLowerCase());
            }
        });
    }

    public void updateHologramTime(String zoneId, int time) {
        if (useFancyHolograms && fancyHologramManager != null) {
            fancyHologramManager.updateHologramTime(zoneId, eu.kotori.justRTP.utils.TimeUtils.formatDuration(time));
            return;
        }

        if (usePacketEvents && packetHologramManager != null) {
            packetHologramManager.updateHologramTime(zoneId, time);
            return;
        }

        HologramInstance instance = activeHolograms.get(zoneId.toLowerCase());
        if (instance == null || instance.displays.isEmpty())
            return;

        boolean anyValid = instance.displays.stream().anyMatch(d -> d != null && d.isValid() && !d.isDead());
        if (!anyValid) {
            plugin.getRTPLogger().debug("HOLOGRAM",
                    "All hologram displays for zone " + zoneId + " are invalid/dead. Removing instance.");

            activeHolograms.remove(zoneId.toLowerCase());
            return;
        }

        Location hologramCenter = instance.displays.get(0).getLocation();

        String timeString = String.valueOf(time);

        List<String> lines = hologramsConfig.getStringList("hologram-settings.lines");
        int displayIndex = 0;
        for (String line : lines) {
            if (displayIndex >= instance.displays.size())
                break;
            if (!line.isEmpty()) {
                TextDisplay display = instance.displays.get(displayIndex);
                if (display != null && display.isValid()) {
                    display.text(MiniMessage.miniMessage().deserialize(line,
                            Placeholder.unparsed("zone_id", zoneId),
                            Placeholder.unparsed("time",
                                    eu.kotori.justRTP.utils.TimeUtils.formatDuration(Integer.parseInt(timeString)))));
                }
                displayIndex++;
            }
        }
        spawnParticles(hologramCenter, instance);
    }

    public void updateHologramProgress(String zoneId) {
        if (useFancyHolograms && fancyHologramManager != null) {
            fancyHologramManager.updateHologramProgress(zoneId);
            return;
        }

        if (usePacketEvents && packetHologramManager != null) {
            packetHologramManager.updateHologramProgress(zoneId);
            return;
        }

        HologramInstance instance = activeHolograms.get(zoneId.toLowerCase());
        if (instance == null || instance.displays.isEmpty())
            return;

        boolean anyValid = instance.displays.stream().anyMatch(d -> d != null && d.isValid() && !d.isDead());
        if (!anyValid) {
            plugin.getRTPLogger().debug("HOLOGRAM",
                    "All hologram displays for zone " + zoneId + " are invalid/dead. Removing instance.");

            activeHolograms.remove(zoneId.toLowerCase());
            return;
        }

        Location hologramCenter = instance.displays.get(0).getLocation();

        String progressText = "TELEPORTING...";

        List<String> lines = hologramsConfig.getStringList("hologram-settings.lines");
        int displayIndex = 0;
        for (String line : lines) {
            if (displayIndex >= instance.displays.size())
                break;
            if (!line.isEmpty()) {
                TextDisplay display = instance.displays.get(displayIndex);
                if (display != null && display.isValid()) {
                    display.text(MiniMessage.miniMessage().deserialize(line,
                            Placeholder.unparsed("zone_id", zoneId),
                            Placeholder.unparsed("time", progressText)));
                }
                displayIndex++;
            }
        }
        spawnParticles(hologramCenter, instance);
    }

    private void spawnParticles(Location center, HologramInstance instance) {
        ConfigurationSection particleSection = hologramsConfig.getConfigurationSection("hologram-settings.particles");
        if (particleSection == null || !particleSection.getBoolean("enabled"))
            return;

        try {
            Particle particle = Particle
                    .valueOf(particleSection.getString("particle", "ENCHANTMENT_TABLE").toUpperCase());
            int count = particleSection.getInt("count", 2);
            double radius = particleSection.getDouble("radius", 0.8);
            double angle = (instance.particleTick.getAndIncrement() * 0.1) % (2 * Math.PI);

            for (int i = 0; i < count; i++) {
                double currentAngle = angle + (2 * Math.PI * i / count);
                double x = center.getX() + radius * Math.cos(currentAngle);
                double z = center.getZ() + radius * Math.sin(currentAngle);
                Location particleLocation = new Location(center.getWorld(), x, center.getY(), z);
                center.getWorld().spawnParticle(particle, particleLocation, 1, 0, 0, 0, 0);
            }
        } catch (IllegalArgumentException e) {
            plugin.getRTPLogger().debug("HOLOGRAM",
                    "Invalid particle type in holograms.yml: " + particleSection.getString("particle"));
        }
    }

    public void removeHologram(String zoneId) {
        plugin.getRTPLogger().debug("HOLOGRAM", "HologramManager: Removing hologram for zone: " + zoneId);

        if (useFancyHolograms && fancyHologramManager != null) {
            fancyHologramManager.removeHologram(zoneId);
            return;
        }

        if (usePacketEvents && packetHologramManager != null) {
            packetHologramManager.removeHologram(zoneId);
            return;
        }

        String normalizedZoneId = zoneId.toLowerCase();
        boolean removed = false;

        HologramInstance instance = activeHolograms.remove(normalizedZoneId);
        if (instance != null) {
            for (TextDisplay display : instance.displays) {
                if (display != null && !display.isDead()) {
                    display.remove();
                    removed = true;
                }
            }
            plugin.getRTPLogger().debug("HOLOGRAM",
                    "✓ Removed " + instance.displays.size() + " active display entities for zone: " + zoneId);
        }

        List<String> uuidStrings = displayEntitiesConfig.getStringList("zones." + normalizedZoneId);
        if (!uuidStrings.isEmpty()) {
            int removedCount = 0;
            for (String uuidString : uuidStrings) {
                try {
                    UUID uuid = UUID.fromString(uuidString);
                    Entity entity = Bukkit.getEntity(uuid);
                    if (entity instanceof TextDisplay && entity.isValid()) {
                        entity.remove();
                        removedCount++;
                        removed = true;
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getRTPLogger().debug("HOLOGRAM", "Invalid UUID in display entities config: " + uuidString);
                }
            }
            if (removedCount > 0) {
                plugin.getRTPLogger().debug("HOLOGRAM",
                        "✓ Removed " + removedCount + " persistent display entities for zone: " + zoneId);
            }
        }

        displayEntitiesConfig.set("zones." + normalizedZoneId, null);
        saveDisplayEntities();

        if (removed) {
            plugin.getLogger().info("Successfully removed display entity hologram for zone: " + zoneId);
        } else {
            plugin.getRTPLogger().debug("HOLOGRAM", "No display entities found to remove for zone: " + zoneId);
        }
    }

    public void reloadConfiguration() {
        plugin.getRTPLogger().debug("HOLOGRAM", "Reloading hologram configuration...");

        File configFile = new File(plugin.getDataFolder(), "holograms.yml");
        hologramsConfig = YamlConfiguration.loadConfiguration(configFile);

        if (useFancyHolograms && fancyHologramManager != null) {
            fancyHologramManager.setHologramsConfig(hologramsConfig);
            fancyHologramManager.reloadTemplates();
            plugin.getRTPLogger().debug("HOLOGRAM", "FancyHologramManager templates reloaded");
        }

        plugin.getLogger().info("Hologram configuration reloaded successfully");
        activeHolograms.clear();

        ConfigurationSection zonesSection = displayEntitiesConfig.getConfigurationSection("zones");
        if (zonesSection != null) {
            for (String zoneId : zonesSection.getKeys(false)) {
                List<String> uuidStrings = zonesSection.getStringList(zoneId);
                for (String uuidString : uuidStrings) {
                    try {
                        UUID uuid = UUID.fromString(uuidString);
                        Entity entity = Bukkit.getEntity(uuid);
                        if (entity instanceof TextDisplay && entity.isValid()) {
                            plugin.getFoliaScheduler().runAtEntity(entity, () -> {
                                entity.remove();
                                plugin.getRTPLogger().debug("HOLOGRAM",
                                        "Removed orphaned hologram entity by UUID: " + uuid);
                            });
                        }
                    } catch (Exception e) {
                    }
                }
            }
        }

        displayEntitiesConfig.set("zones", null);
        saveDisplayEntities();

        removeOrphanHologramEntities();
        plugin.getRTPLogger().debug("HOLOGRAM", "Hologram cleanup complete.");

    }

    public void cleanupAllHolograms() {
        plugin.getRTPLogger().debug("HOLOGRAM", "Cleaning up all JustRTP holograms from all worlds...");

        if (usePacketEvents && packetHologramManager != null) {
            packetHologramManager.cleanupAllHolograms();
        }

        activeHolograms.values().forEach(instance -> instance.displays.forEach(display -> {
            if (display == null) return;
            plugin.getFoliaScheduler().runAtEntity(display, display::remove);
        }));
        activeHolograms.clear();

        ConfigurationSection zonesSection = displayEntitiesConfig.getConfigurationSection("zones");
        if (zonesSection != null) {
            for (String zoneId : zonesSection.getKeys(false)) {
                List<String> uuidStrings = zonesSection.getStringList(zoneId);
                for (String uuidString : uuidStrings) {
                    try {
                        UUID uuid = UUID.fromString(uuidString);
                        Entity entity = Bukkit.getEntity(uuid);
                        if (entity instanceof TextDisplay && entity.isValid()) {
                            plugin.getFoliaScheduler().runAtEntity(entity, () -> {
                                entity.remove();
                                plugin.getRTPLogger().debug("HOLOGRAM",
                                        "Removed orphaned hologram entity by UUID: " + uuid);
                            });
                        }
                    } catch (Exception e) {
                    }
                }
            }
        }

        displayEntitiesConfig.set("zones", null);
        saveDisplayEntities();

        removeOrphanHologramEntities();
        plugin.getRTPLogger().debug("HOLOGRAM", "Hologram cleanup complete.");

    }

    private void removeOrphanHologramEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                plugin.getFoliaScheduler().runAtChunk(chunk, () -> {
                    for (Entity entity : chunk.getEntities()) {
                        if (entity instanceof TextDisplay && entity.hasMetadata(HOLOGRAM_METADATA_KEY)) {
                            entity.remove();
                            plugin.getRTPLogger().debug("HOLOGRAM",
                                    "Removed orphaned hologram entity by metadata: " + entity.getUniqueId());
                        }
                    }
                });
            }
        }
    }
}