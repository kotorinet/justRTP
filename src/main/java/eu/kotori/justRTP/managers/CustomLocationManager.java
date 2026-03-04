package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class CustomLocationManager {
    private final JustRTP plugin;
    private FileConfiguration locationsConfig;
    private File locationsFile;
    private final Map<String, CustomLocation> customLocations = new HashMap<>();

    public CustomLocationManager(JustRTP plugin) {
        this.plugin = plugin;
        loadConfiguration();
    }

    public void loadConfiguration() {
        locationsFile = new File(plugin.getDataFolder(), "custom_locations.yml");
        if (!locationsFile.exists()) {
            plugin.saveResource("custom_locations.yml", false);
        }

        locationsConfig = YamlConfiguration.loadConfiguration(locationsFile);
        loadLocations();
    }

    private void loadLocations() {
        customLocations.clear();

        ConfigurationSection locationsSection = locationsConfig.getConfigurationSection("locations");
        if (locationsSection == null) {
            plugin.getLogger().warning("No custom locations found in custom_locations.yml");
            return;
        }

        for (String locationId : locationsSection.getKeys(false)) {
            try {
                ConfigurationSection locationSection = locationsSection.getConfigurationSection(locationId);
                if (locationSection == null)
                    continue;

                if (!locationSection.getBoolean("enabled", true)) {
                    plugin.getRTPLogger().debug("CONFIG", "Custom location '" + locationId + "' is disabled");
                    continue;
                }

                String worldName = locationSection.getString("world");
                World world = Bukkit.getWorld(worldName);

                if (world == null) {
                    plugin.getLogger()
                            .warning("World '" + worldName + "' not found for custom location '" + locationId + "'");
                    continue;
                }

                int centerX = locationSection.getInt("center-x");
                int centerZ = locationSection.getInt("center-z");
                int minRadius = locationSection.getInt("min-radius", 0);
                int maxRadius = locationSection.getInt("max-radius", 1000);
                String permission = locationSection.getString("permission", "");
                String displayName = locationSection.getString("display-name", locationId);

                CustomLocation location = new CustomLocation(
                        locationId,
                        world,
                        centerX,
                        centerZ,
                        minRadius,
                        maxRadius,
                        permission,
                        displayName);

                customLocations.put(locationId.toLowerCase(), location);
                plugin.getRTPLogger().debug("CONFIG",
                        "Loaded custom location: " + locationId + " (" + displayName + ") in " + worldName);

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load custom location '" + locationId + "': " + e.getMessage());
                e.printStackTrace();
            }
        }

        plugin.getLogger().info("Loaded " + customLocations.size() + " custom location(s)");
    }

    public boolean isEnabled() {
        return locationsConfig.getBoolean("enabled", true);
    }

    public boolean hasLocation(String locationId) {
        return customLocations.containsKey(locationId.toLowerCase());
    }

    public CustomLocation getLocation(String locationId) {
        return customLocations.get(locationId.toLowerCase());
    }

    public Set<String> getLocationIds() {
        return new HashSet<>(customLocations.keySet());
    }

    public List<CustomLocation> getLocations() {
        return new ArrayList<>(customLocations.values());
    }

    public List<CustomLocation> getAvailableLocations(Player player) {
        List<CustomLocation> available = new ArrayList<>();
        for (CustomLocation location : customLocations.values()) {
            if (location.hasPermission(player)) {
                available.add(location);
            }
        }
        return available;
    }

    public List<String> getAvailableLocationIds(Player player) {
        List<String> available = new ArrayList<>();
        for (CustomLocation location : customLocations.values()) {
            if (location.hasPermission(player)) {
                available.add(location.getId());
            }
        }
        return available;
    }

    public CompletableFuture<Boolean> teleportToLocation(Player player, String locationId) {
        if (!isEnabled()) {
            plugin.getLocaleManager().sendMessage(player, "custom_locations.feature_disabled");
            return CompletableFuture.completedFuture(false);
        }

        CustomLocation location = getLocation(locationId);
        if (location == null) {
            plugin.getLocaleManager().sendMessage(player, "custom_locations.not_found",
                    Placeholder.unparsed("location", locationId));
            return CompletableFuture.completedFuture(false);
        }

        if (!location.hasPermission(player)) {
            plugin.getLocaleManager().sendMessage(player, "custom_locations.no_permission",
                    Placeholder.unparsed("location", location.getDisplayName()));
            return CompletableFuture.completedFuture(false);
        }

        plugin.getLocaleManager().sendMessage(player, "custom_locations.searching",
                Placeholder.unparsed("location", location.getDisplayName()));

        if (plugin.getDelayManager().isDelayed(player.getUniqueId())) {
            plugin.getLocaleManager().sendMessage(player, "teleport.already_in_progress");
            return CompletableFuture.completedFuture(false);
        }

        if (!player.isOp() && !player.hasPermission("justrtp.cooldown.bypass")) {
            long remainingCooldown = plugin.getCooldownManager().getRemaining(
                    player.getUniqueId(),
                    location.getWorld().getName());
            if (remainingCooldown > 0) {
                plugin.getLocaleManager().sendMessage(player, "teleport.cooldown",
                        Placeholder.unparsed("time",
                                eu.kotori.justRTP.utils.TimeUtils.formatDuration(remainingCooldown)));
                return CompletableFuture.completedFuture(false);
            }
        }

        int delay = plugin.getConfigManager().getDelay(player, location.getWorld());
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        plugin.getDelayManager().startDelay(player, () -> {
            if (!player.isOp() && !player.hasPermission("justrtp.cooldown.bypass")) {
                int cooldown = plugin.getConfigManager().getCooldown(player, location.getWorld());
                plugin.getCooldownManager().setCooldown(
                        player.getUniqueId(),
                        location.getWorld().getName(),
                        cooldown);
            }

            plugin.getTeleportQueueManager().requestTeleport(
                    player,
                    location.getWorld(),
                    Optional.of(location.getMinRadius()),
                    Optional.of(location.getMaxRadius()),
                    location.getCenterX(),
                    location.getCenterZ()).thenAccept(result -> {
                        if (result) {
                            plugin.getLocaleManager().sendMessage(player, "custom_locations.success",
                                    Placeholder.unparsed("location", location.getDisplayName()));
                        }
                        future.complete(result);
                    });
        }, delay);

        return future;
    }

    public void reload() {
        loadConfiguration();
    }

    public static class CustomLocation {
        private final String id;
        private final World world;
        private final int centerX;
        private final int centerZ;
        private final int minRadius;
        private final int maxRadius;
        private final String permission;
        private final String displayName;

        public CustomLocation(String id, World world, int centerX, int centerZ, int minRadius, int maxRadius,
                String permission, String displayName) {
            this.id = id;
            this.world = world;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.minRadius = minRadius;
            this.maxRadius = maxRadius;
            this.permission = permission;
            this.displayName = displayName;
        }

        public String getId() {
            return id;
        }

        public World getWorld() {
            return world;
        }

        public int getCenterX() {
            return centerX;
        }

        public int getCenterZ() {
            return centerZ;
        }

        public int getMinRadius() {
            return minRadius;
        }

        public int getMaxRadius() {
            return maxRadius;
        }

        public String getPermission() {
            return permission;
        }

        public String getDisplayName() {
            return displayName;
        }

        public boolean hasPermission(Player player) {
            if (permission == null || permission.isEmpty()) {
                return true;
            }
            return player.hasPermission(permission);
        }
    }
}
