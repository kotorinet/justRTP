package eu.kotori.justRTP.handlers;

import eu.kotori.justRTP.JustRTP;
import eu.kotori.justRTP.events.PlayerPostRTPEvent;
import eu.kotori.justRTP.handlers.hooks.HookManager;
import eu.kotori.justRTP.managers.ConfigManager;
import eu.kotori.justRTP.utils.FoliaScheduler;
import eu.kotori.justRTP.utils.SafetyValidator;
import io.papermc.lib.PaperLib;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class RTPService {
    private enum WorldType {
        NORMAL, NETHER, THE_END
    }

    private enum FailureReason {
        BLACKLISTED_BLOCK, LAVA_NEARBY, LIQUID_FLOOR, AIR_FLOOR, OBSTRUCTED, INVALID_BIOME, REGION_CLAIM, UNKNOWN
    }

    private record SearchSummary(Map<FailureReason, Integer> failureCounts) {
        public SearchSummary() {
            this(new EnumMap<>(FailureReason.class));
        }

        public void increment(FailureReason reason) {
            failureCounts.merge(reason, 1, Integer::sum);
        }

        @Override
        public String toString() {
            if (failureCounts.isEmpty())
                return "No failures.";
            return failureCounts.entrySet().stream()
                    .map(entry -> entry.getKey().name() + ": " + entry.getValue())
                    .collect(Collectors.joining(", "));
        }
    }

    private final JustRTP plugin;
    private final ConfigManager config;
    private final HookManager hookManager;
    private EnumSet<Material> blacklistedBlocks;
    private final Map<String, WorldType> worldTypes = new ConcurrentHashMap<>();
    private final Set<String> borderWarningShown = new HashSet<>();

    private String worldMode;
    private Set<String> worldList;
    private String biomeMode;
    private Set<Biome> biomeList;

    public RTPService(JustRTP plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.hookManager = new HookManager(plugin);
        loadConfigValues();
    }

    public void loadConfigValues() {
        blacklistedBlocks = EnumSet.noneOf(Material.class);
        plugin.getConfig().getStringList("blacklist_blocks").forEach(name -> {
            try {
                blacklistedBlocks.add(Material.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid blacklisted block: " + name);
            }
        });

        this.worldMode = plugin.getConfig().getString("rtp_settings.worlds.mode", "BLACKLIST").toUpperCase();
        this.worldList = new HashSet<>(plugin.getConfig().getStringList("rtp_settings.worlds.list"));

        worldTypes.clear();
        ConfigurationSection typesSection = plugin.getConfig().getConfigurationSection("world_types");
        if (typesSection != null) {
            typesSection.getKeys(false).forEach(worldName -> {
                try {
                    worldTypes.put(worldName,
                            WorldType.valueOf(typesSection.getString(worldName, "NORMAL").toUpperCase()));
                } catch (IllegalArgumentException e) {
                    worldTypes.put(worldName, WorldType.NORMAL);
                }
            });
        }

        this.biomeMode = plugin.getConfig().getString("rtp_settings.biomes.mode", "BLACKLIST").toUpperCase();
        this.biomeList = plugin.getConfig().getStringList("rtp_settings.biomes.list").stream()
                .map(str -> {
                    try {
                        String biomeName = str.toUpperCase();
                        Biome biome = null;

                        try {
                            io.papermc.paper.registry.RegistryAccess registryAccess = io.papermc.paper.registry.RegistryAccess.registryAccess();
                            io.papermc.paper.registry.RegistryKey<Biome> biomeKey = io.papermc.paper.registry.RegistryKey.BIOME;
                            biome = registryAccess.getRegistry(biomeKey).get(
                                org.bukkit.NamespacedKey.minecraft(biomeName.toLowerCase())
                            );
                        } catch (Exception ignored) {
                        }

                        if (biome == null) {
                            try {
                                biome = Biome.valueOf(biomeName.toUpperCase());
                            } catch (IllegalArgumentException ignored) {
                            }
                        }

                        if (biome == null) {
                            try {
                                Class<Biome> biomeClass = Biome.class;
                                java.lang.reflect.Method valuesMethod = biomeClass.getMethod("values");
                                Object result = valuesMethod.invoke(null);
                                if (result instanceof Biome[]) {
                                    Biome[] allBiomes = (Biome[]) result;
                                    java.lang.reflect.Method nameMethod = biomeClass.getMethod("name");
                                    for (int i = 0; i < allBiomes.length; i++) {
                                        Biome b = allBiomes[i];
                                        if (b != null) {
                                            String enumName = (String) nameMethod.invoke(b);
                                            if (enumName != null && enumName.equalsIgnoreCase(biomeName)) {
                                                biome = b;
                                                break;
                                            }
                                        }
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        }

                        if (biome == null) {
                            plugin.getLogger().warning("Invalid biome name in config.yml: " + str);
                        }
                        return biome;
                    } catch (Exception e) {
                        plugin.getLogger().warning("Error loading biome: " + str + " - " + e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    public boolean isRtpEnabled(World world) {
        String worldName = world.getName();
        return "WHITELIST".equals(worldMode) ? worldList.contains(worldName) : !worldList.contains(worldName);
    }

    public CompletableFuture<Boolean> teleportPlayer(Player player, Location location) {
        return teleportPlayer(player, location, null, null, 0.0, false, null);
    }

    public CompletableFuture<Boolean> teleportPlayer(Player player, Location location, Integer minRadius,
            Integer maxRadius,
            double cost, boolean isCrossServer, String targetServer) {
        CompletableFuture<Boolean> resultFuture = new CompletableFuture<>();

        if (!player.isOnline()) {
            resultFuture.complete(false);
            return resultFuture;
        }

        Location fromLocation = player.getLocation().clone();
        World targetWorld = location.getWorld();

        plugin.getFoliaScheduler().runAtEntity(player, () -> {
            if (!player.isOnline()) {
                resultFuture.complete(false);
                return;
            }

            plugin.getFoliaScheduler().runAtEntity(player, () -> {
                if (!player.isOnline()) {
                    resultFuture.complete(false);
                    return;
                }

                player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                player.setFallDistance(0f);

                PaperLib.teleportAsync(player, location).thenAccept(success -> {
                    if (success && player.isOnline()) {
                        plugin.getFoliaScheduler().runAtEntity(player, () -> {
                            if (!player.isOnline()) {
                                resultFuture.complete(false);
                                return;
                            }

                            player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                            player.setFallDistance(0f);

                            if (FoliaScheduler.isFolia()) {
                                plugin.getFoliaScheduler().runAtEntityLater(player, () -> {
                                    if (player.isOnline()) {
                                        refreshPlayerChunks(player, location);
                                    }
                                }, 5L);
                            }

                            plugin.getEffectsManager().applyPostTeleportEffects(player);

                            PlayerPostRTPEvent postEvent = new PlayerPostRTPEvent(
                                    player,
                                    fromLocation,
                                    location,
                                    targetWorld,
                                    minRadius,
                                    maxRadius,
                                    cost,
                                    isCrossServer,
                                    targetServer);
                            Bukkit.getPluginManager().callEvent(postEvent);

                            resultFuture.complete(true);
                        });
                    } else {
                        resultFuture.complete(false);
                    }
                });
            });
        });

        return resultFuture;
    }


    private void refreshPlayerChunks(Player player, Location location) {
        if (!player.isOnline() || location.getWorld() == null)
            return;

        World world = location.getWorld();
        int centerChunkX = location.getBlockX() >> 4;
        int centerChunkZ = location.getBlockZ() >> 4;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                PaperLib.getChunkAtAsync(world, chunkX, chunkZ, false);
            }
        }
    }

    public CompletableFuture<Optional<Location>> findSafeLocationForCache(World world) {
        boolean generateChunks = plugin.getConfigManager().shouldGenerateChunks(world);
        int attempts = getDimensionAttempts(world);
        return findLocationAsync(null, world, attempts, Optional.empty(), Optional.empty(), generateChunks);
    }

    public CompletableFuture<Optional<Location>> findSafeLocation(Player player, World world, int attempts,
            Optional<Integer> minRadius, Optional<Integer> maxRadius) {
        boolean generateChunks = plugin.getConfigManager().shouldGenerateChunks(world);
        int finalAttempts = (attempts > 0) ? attempts : getDimensionAttempts(world);
        return findLocationAsync(player, world, finalAttempts, minRadius, maxRadius, generateChunks, 0, 0, false);
    }

    public CompletableFuture<Optional<Location>> findSafeLocation(Player player, World world, int attempts,
            Optional<Integer> minRadius, Optional<Integer> maxRadius, int centerX, int centerZ) {
        boolean generateChunks = plugin.getConfigManager().shouldGenerateChunks(world);
        int finalAttempts = (attempts > 0) ? attempts : getDimensionAttempts(world);
        return findLocationAsync(player, world, finalAttempts, minRadius, maxRadius, generateChunks, centerX, centerZ,
                true);
    }

    private int getDimensionAttempts(World world) {
        World.Environment env = world.getEnvironment();
        if (env == World.Environment.NETHER) {
            return plugin.getConfig().getInt("settings.attempts_nether", 50);
        } else if (env == World.Environment.THE_END) {
            return plugin.getConfig().getInt("settings.attempts_end", 35);
        } else {
            return plugin.getConfig().getInt("settings.attempts", 25);
        }
    }

    private CompletableFuture<Optional<Location>> findLocationAsync(Player player, World world, int attemptsLeft,
            Optional<Integer> minRadius, Optional<Integer> maxRadius, boolean generateChunks) {
        SearchSummary summary = new SearchSummary();
        return findLocationRecursive(player, world, attemptsLeft, minRadius, maxRadius, generateChunks, summary, 0, 0,
                false);
    }

    private CompletableFuture<Optional<Location>> findLocationAsync(Player player, World world, int attemptsLeft,
            Optional<Integer> minRadius, Optional<Integer> maxRadius, boolean generateChunks, int centerX, int centerZ,
            boolean useCustomCenter) {
        SearchSummary summary = new SearchSummary();
        return findLocationRecursive(player, world, attemptsLeft, minRadius, maxRadius, generateChunks, summary,
                centerX, centerZ, useCustomCenter);
    }

    private CompletableFuture<Optional<Location>> findLocationRecursive(Player player, World world, int attemptsLeft,
            Optional<Integer> minRadius, Optional<Integer> maxRadius, boolean generateChunks, SearchSummary summary) {
        return findLocationRecursive(player, world, attemptsLeft, minRadius, maxRadius, generateChunks, summary, 0, 0,
                false);
    }

    private CompletableFuture<Optional<Location>> findLocationRecursive(Player player, World world, int attemptsLeft,
            Optional<Integer> minRadius, Optional<Integer> maxRadius, boolean generateChunks, SearchSummary summary,
            int customCenterX, int customCenterZ, boolean useCustomCenter) {
        if (attemptsLeft <= 0) {
            int totalAttempts = getDimensionAttempts(world);
            String worldType = world.getEnvironment().name();
            plugin.getLogger().warning("Failed to find safe location in " + world.getName() + " (" + worldType
                    + ") after " + totalAttempts + " attempts.");
            plugin.getLogger().warning("Failure breakdown: " + summary);
            plugin.getLogger()
                    .warning("Check world configuration, chunk generation settings, and world_types in config.yml");
            return CompletableFuture.completedFuture(Optional.empty());
        }

        WorldBorder border = world.getWorldBorder();
        double borderSize = border.getSize() / 2;
        Location borderCenter = border.getCenter();

        final double ABSOLUTE_MAX_RADIUS = 10_000_000;
        final double SAFE_BORDER_SIZE = Math.min(borderSize, ABSOLUTE_MAX_RADIUS);

        if (borderSize > ABSOLUTE_MAX_RADIUS && !borderWarningShown.contains(world.getName())) {
            borderWarningShown.add(world.getName());
            plugin.getLogger().warning(
                    "World border for '" + world.getName() + "' is extremely large (" + borderSize + " blocks)!");
            plugin.getLogger()
                    .warning("Limiting RTP radius to " + ABSOLUTE_MAX_RADIUS + " blocks to prevent server crashes.");
        }

        double initialMaxR = Math.min(SAFE_BORDER_SIZE,
                maxRadius.orElse(config.getInt(player, world, "max_radius", (int) SAFE_BORDER_SIZE)));
        double initialMinR = minRadius.orElse(config.getInt(player, world, "min_radius", 100));

        final double finalMinRadius = Math.min(initialMinR, initialMaxR);
        final double finalMaxRadius = Math.max(initialMinR, initialMaxR);

        ConfigurationSection worldConfig = plugin.getConfig()
                .getConfigurationSection("custom_worlds." + world.getName());

        plugin.getRTPLogger().debug("CONFIG", "[RTP Config Debug] World: " + world.getName() +
                ", worldConfig exists: " + (worldConfig != null) +
                ", useCustomCenter: " + useCustomCenter);
        if (worldConfig != null) {
            plugin.getRTPLogger().debug("CONFIG",
                    "[RTP Config Debug] Keys in worldConfig: " + worldConfig.getKeys(false));
            plugin.getRTPLogger().debug("CONFIG",
                    "[RTP Config Debug] center_x raw value: " + worldConfig.get("center_x") +
                            ", center_z raw value: " + worldConfig.get("center_z"));
        } else {
            plugin.getRTPLogger().debug("CONFIG",
                    "[RTP Config Debug] worldConfig is NULL! Config path: custom_worlds." + world.getName());
            plugin.getRTPLogger().debug("CONFIG", "[RTP Config Debug] Available world sections: " +
                    (plugin.getConfig().getConfigurationSection("custom_worlds") != null
                            ? plugin.getConfig().getConfigurationSection("custom_worlds").getKeys(false)
                            : "custom_worlds section is NULL"));
        }

        int cX, cZ;
        if (useCustomCenter) {
            cX = customCenterX;
            cZ = customCenterZ;
            plugin.getRTPLogger().debug("CONFIG", "Using custom center coordinates: X=" + cX + ", Z=" + cZ);
        } else if (worldConfig != null) {
            cX = worldConfig.getInt("center_x", (int) borderCenter.getX());
            cZ = worldConfig.getInt("center_z", (int) borderCenter.getZ());
            plugin.getRTPLogger().debug("CONFIG",
                    "Using world config center: X=" + cX + ", Z=" + cZ + " (world border center: " +
                            (int) borderCenter.getX() + ", " + (int) borderCenter.getZ() + ")");
        } else {
            cX = (int) borderCenter.getX();
            cZ = (int) borderCenter.getZ();
            plugin.getRTPLogger().debug("CONFIG", "Using world border center: X=" + cX + ", Z=" + cZ);
        }

        double angle = ThreadLocalRandom.current().nextDouble(2 * Math.PI);
        double radius = Math.sqrt(ThreadLocalRandom.current().nextDouble()) * (finalMaxRadius - finalMinRadius)
                + finalMinRadius;

        double targetX = cX + radius * Math.cos(angle);
        double targetZ = cZ + radius * Math.sin(angle);

        double borderMinX = borderCenter.getX() - SAFE_BORDER_SIZE;
        double borderMaxX = borderCenter.getX() + SAFE_BORDER_SIZE;
        double borderMinZ = borderCenter.getZ() - SAFE_BORDER_SIZE;
        double borderMaxZ = borderCenter.getZ() + SAFE_BORDER_SIZE;

        final int x = (int) Math.max(borderMinX, Math.min(borderMaxX, targetX));
        final int z = (int) Math.max(borderMinZ, Math.min(borderMaxZ, targetZ));

        plugin.getRTPLogger().debug("COORDS",
                String.format("[RTP Coords] World=%s, RTP Center=(%d,%d), Radius=%.1f (min=%.1f, max=%.1f), " +
                        "Target=(%.1f,%.1f), Final=(%d,%d), Border=[%.1f to %.1f, %.1f to %.1f]",
                        world.getName(), cX, cZ, radius, finalMinRadius, finalMaxRadius,
                        targetX, targetZ, x, z, borderMinX, borderMaxX, borderMinZ, borderMaxZ));

        final int MAX_COORDINATE = 10_000_000;
        if (Math.abs(x) > MAX_COORDINATE || Math.abs(z) > MAX_COORDINATE) {
            plugin.getLogger().severe("╔════════════════════════════════════════════════════════════╗");
            plugin.getLogger().severe("║  CRITICAL: Extreme coordinates detected!                  ║");
            plugin.getLogger().severe("║  X=" + x + ", Z=" + z + "                                  ║");
            plugin.getLogger().severe("║  This would cause chunk loading to freeze the server!     ║");
            plugin.getLogger().severe("║  Retrying with safer coordinates...                       ║");
            plugin.getLogger().severe("╚════════════════════════════════════════════════════════════╝");
            return findLocationRecursive(player, world, attemptsLeft - 1, minRadius, maxRadius, generateChunks, summary,
                    customCenterX, customCenterZ, useCustomCenter);
        }

        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        
        return PaperLib.getChunkAtAsync(world, chunkX, chunkZ, generateChunks).thenCompose(chunk -> {
            if (chunk == null) {
                plugin.getRTPLogger().debug("CHUNK",
                        "Failed to load chunk at " + chunkX + ", " + chunkZ + " in " + world.getName()
                                + " (generateChunks=" + generateChunks + ")");
                summary.increment(FailureReason.UNKNOWN);

                int totalAttempts = getDimensionAttempts(world);
                int attemptsMade = totalAttempts - attemptsLeft + 1;
                if (attemptsMade % 10 == 0) {
                    plugin.getRTPLogger().debug("CHUNK",
                            "Chunk loading failures in " + world.getName() + " after " + attemptsMade
                                    + " attempts. Retrying...");
                }

                return delayedFindLocationRecursive(player, world, attemptsLeft - 1, minRadius, maxRadius,
                        generateChunks, summary, customCenterX, customCenterZ, useCustomCenter);
            }
            
            if (generateChunks) {
                List<CompletableFuture<Chunk>> neighborFutures = new ArrayList<>();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        neighborFutures.add(PaperLib.getChunkAtAsync(world, chunkX + dx, chunkZ + dz, true));
                    }
                }
                
                return CompletableFuture.allOf(neighborFutures.toArray(new CompletableFuture[0]))
                    .thenCompose(v -> {
                        CompletableFuture<Void> delayFuture = new CompletableFuture<>();
                        plugin.getFoliaScheduler().runLater(() -> delayFuture.complete(null), 2L);
                        return delayFuture;
                    })
                    .thenCompose(v -> processChunkForLocation(chunk, x, z, world, player, attemptsLeft, 
                        minRadius, maxRadius, generateChunks, summary, customCenterX, customCenterZ, useCustomCenter));
            }
            
            return processChunkForLocation(chunk, x, z, world, player, attemptsLeft, 
                minRadius, maxRadius, generateChunks, summary, customCenterX, customCenterZ, useCustomCenter);
        });
    }
    
    private CompletableFuture<Optional<Location>> processChunkForLocation(Chunk chunk, int x, int z, 
            World world, Player player, int attemptsLeft, Optional<Integer> minRadius, Optional<Integer> maxRadius,
            boolean generateChunks, SearchSummary summary, int customCenterX, int customCenterZ, boolean useCustomCenter) {
        
        CompletableFuture<Optional<Location>> future = new CompletableFuture<>();
        
        Location chunkLocation = new Location(world, x, 64, z);
        plugin.getFoliaScheduler().runAtLocation(chunkLocation, () -> {
            processChunkOnRegionThread(chunk, x, z, world, player, attemptsLeft, minRadius, maxRadius,
                generateChunks, summary, customCenterX, customCenterZ, useCustomCenter)
                .thenAccept(future::complete)
                .exceptionally(ex -> {
                    future.completeExceptionally(ex);
                    return null;
                });
        });
        
        return future;
    }
    
    private CompletableFuture<Optional<Location>> processChunkOnRegionThread(Chunk chunk, int x, int z, 
            World world, Player player, int attemptsLeft, Optional<Integer> minRadius, Optional<Integer> maxRadius,
            boolean generateChunks, SearchSummary summary, int customCenterX, int customCenterZ, boolean useCustomCenter) {

        final WorldType type;
        WorldType configuredType = worldTypes.get(world.getName());
        if (configuredType == null) {
            if (world.getEnvironment() == World.Environment.NETHER) {
                type = WorldType.NETHER;
            } else if (world.getEnvironment() == World.Environment.THE_END) {
                type = WorldType.THE_END;
            } else {
                type = WorldType.NORMAL;
            }
        } else {
            type = configuredType;
        }

        if (world.getEnvironment() == World.Environment.NETHER && type != WorldType.NETHER) {
            plugin.getLogger().warning("[NETHER OVERRIDE] World '" + world.getName()
                    + "' has NETHER environment but type was " + type + " - FORCING to NETHER for safety!");
        }

        Optional<Location> safeSpot;
        switch (type) {
            case NETHER:
                safeSpot = findSafeInNether(chunk, x, z, summary);
                break;
            case THE_END:
                safeSpot = findSafeInEnd(chunk, x, z, summary);
                break;
            default:
                safeSpot = findSafeInNormal(chunk, x, z, summary);
                break;
        }

        if (safeSpot.isPresent()) {
            Location loc = safeSpot.get();

            return SafetyValidator.isLocationAbsolutelySafeAsync(loc).thenCompose(safe -> {
                if (!safe) {
                    String reason = SafetyValidator.getUnsafeReason(loc);
                    plugin.getRTPLogger().debug("SAFETY",
                            "Safety validator rejected location in " + world.getName() + " at " +
                                    loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() +
                                    " - Reason: " + reason + " (retrying...)");
                    summary.increment(FailureReason.UNKNOWN);
                    return delayedFindLocationRecursive(player, world, attemptsLeft - 1, minRadius, maxRadius,
                            generateChunks, summary, customCenterX, customCenterZ, useCustomCenter);
                }

                boolean isNetherWorld = (type == WorldType.NETHER)
                        || (world.getEnvironment() == World.Environment.NETHER);

                if (isNetherWorld) {
                    double y = loc.getY();
                    if (y >= 126.0) {
                        plugin.getLogger().severe("╔════════════════════════════════════════════════════════════╗");
                        plugin.getLogger().severe("║  CRITICAL NETHER ROOF SPAWN PREVENTED!                    ║");
                        plugin.getLogger().severe("║  Location rejected: Y=" + y + " >= 126                    ║");
                        plugin.getLogger().severe("║  World: " + world.getName() + " (Type: " + type + ", Env: "
                                + world.getEnvironment() + ") ║");
                        plugin.getLogger()
                                .severe("║  Head would be at: Y=" + (y + 1) + " (NETHER CEILING!)           ║");
                        plugin.getLogger().severe("║  Continuing search for safe location...                   ║");
                        plugin.getLogger().severe("╚════════════════════════════════════════════════════════════╝");
                        summary.increment(FailureReason.UNKNOWN);
                        return delayedFindLocationRecursive(player, world, attemptsLeft - 1, minRadius, maxRadius,
                                generateChunks, summary, customCenterX, customCenterZ, useCustomCenter);
                    }
                    plugin.getRTPLogger().debug("NETHER",
                            "[NETHER SAFE] ✓ Nether location verified safe: Y=" + y + " (head at Y=" + (y + 1)
                                    + ") in " + world.getName());
                }

                if (world.getEnvironment() == World.Environment.THE_END) {
                    double y = loc.getY();
                    if (y < 10 || y > 120) {
                        plugin.getRTPLogger().debug("END",
                                "[END SAFETY] Rejected Y=" + y + " (out of safe range 10-120)");
                        summary.increment(FailureReason.UNKNOWN);
                        return delayedFindLocationRecursive(player, world, attemptsLeft - 1, minRadius, maxRadius,
                                generateChunks, summary, customCenterX, customCenterZ, useCustomCenter);
                    }
                    plugin.getRTPLogger().debug("END",
                            "[END SAFE] ✓ End location verified safe: Y=" + y + " in " + world.getName());
                }

                if (world.getEnvironment() == World.Environment.NORMAL) {
                    double y = loc.getY();
                    if (y >= 127 || y < world.getMinHeight() + 5) {
                        plugin.getRTPLogger().debug("OVERWORLD",
                                "[OVERWORLD SAFETY] Rejected Y=" + y + " (invalid height)");
                        summary.increment(FailureReason.UNKNOWN);
                        return delayedFindLocationRecursive(player, world, attemptsLeft - 1, minRadius, maxRadius,
                                generateChunks, summary, customCenterX, customCenterZ, useCustomCenter);
                    }
                    plugin.getRTPLogger().debug("OVERWORLD",
                            "[OVERWORLD SAFE] ✓ Overworld location verified safe: Y=" + y + " in "
                                    + world.getName());
                }

                plugin.getRTPLogger().debug("SUCCESS",
                        "Success: Found safe location at " + loc.getBlockX() + "," + loc.getBlockY() + ","
                                + loc.getBlockZ() + " after " + (getDimensionAttempts(world) - attemptsLeft + 1)
                                + " attempts.");
                return CompletableFuture.completedFuture(Optional.of(loc));
            });
        }
        return delayedFindLocationRecursive(player, world, attemptsLeft - 1, minRadius, maxRadius, generateChunks,
                summary, customCenterX, customCenterZ, useCustomCenter);
    }

    private CompletableFuture<Optional<Location>> delayedFindLocationRecursive(Player player, World world,
            int attemptsLeft, Optional<Integer> minRadius, Optional<Integer> maxRadius, boolean generateChunks,
            SearchSummary summary, int customCenterX, int customCenterZ, boolean useCustomCenter) {
        if (attemptsLeft <= 0) {
            return findLocationRecursive(player, world, attemptsLeft, minRadius, maxRadius, generateChunks, summary,
                    customCenterX, customCenterZ, useCustomCenter);
        }

        CompletableFuture<Optional<Location>> future = new CompletableFuture<>();
        plugin.getFoliaScheduler().runLater(() -> {
            findLocationRecursive(player, world, attemptsLeft, minRadius, maxRadius, generateChunks, summary,
                    customCenterX, customCenterZ, useCustomCenter)
                    .thenAccept(future::complete)
                    .exceptionally(ex -> {
                        future.completeExceptionally(ex);
                        return null;
                    });
        }, 1L);

        return future;

    }

    private Optional<Location> findSafeInNormal(Chunk chunk, int x, int z, SearchSummary summary) {
        if (chunk.getWorld().getEnvironment() == World.Environment.NETHER) {
            plugin.getLogger().severe("╔══════════════════════════════════════════════════════════╗");
            plugin.getLogger().severe("║  CRITICAL BUG: findSafeInNormal() called for NETHER!   ║");
            plugin.getLogger().severe("║  World: " + chunk.getWorld().getName() + "                              ║");
            plugin.getLogger().severe("║  Redirecting to findSafeInNether() for safety!          ║");
            plugin.getLogger().severe("╚══════════════════════════════════════════════════════════╝");
            return findSafeInNether(chunk, x, z, summary);
        }

        int groundY = chunk.getWorld().getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (groundY <= chunk.getWorld().getMinHeight()) {
            plugin.getRTPLogger().debug("OVERWORLD",
                    "No valid ground found at " + x + ", " + z + " in " + chunk.getWorld().getName());
            return Optional.empty();
        }

        if (groundY >= 127) {
            plugin.getRTPLogger().debug("SAFETY",
                    "[SAFETY] findSafeInNormal() found groundY=" + groundY + " >= 127 in " +
                            chunk.getWorld().getName() + " - rejecting for safety");
            summary.increment(FailureReason.UNKNOWN);
            return Optional.empty();
        }

        Location groundLoc = new Location(chunk.getWorld(), x, groundY, z);

        if (isSafe(groundLoc, summary).isEmpty()) {
            Location spawnLoc = new Location(chunk.getWorld(), x + 0.5, groundY + 1, z + 0.5);

            if (spawnLoc.getY() >= 127.0) {
                plugin.getLogger().severe("[CRITICAL SAFETY] findSafeInNormal() would spawn at Y=" + spawnLoc.getY() +
                        " >= 127! Rejecting to prevent nether ceiling spawn.");
                summary.increment(FailureReason.UNKNOWN);
                return Optional.empty();
            }

            plugin.getRTPLogger().debug("OVERWORLD",
                    "Found safe overworld spawn at Y=" + spawnLoc.getY() + " (ground at Y=" + groundY + ")");
            return Optional.of(spawnLoc);
        }
        return Optional.empty();
    }

    private Optional<Location> findSafeInNether(Chunk chunk, int x, int z, SearchSummary summary) {

        String worldName = chunk.getWorld().getName();
        plugin.getRTPLogger().debug("NETHER",
                "[NETHER SEARCH] Starting nether location search in '" + worldName + "' at X=" + x + " Z=" + z);

        int minHeight = Math.max(chunk.getWorld().getMinHeight(), 5);
        int maxSearchY = 120;
        int searchAttempts = 0;

        plugin.getRTPLogger().debug("NETHER",
                "[NETHER SEARCH] Search range: Y=" + maxSearchY + " down to Y=" + minHeight + " (total range: "
                        + (maxSearchY - minHeight) + " blocks)");

        for (int y = maxSearchY; y > minHeight; y--) {
            searchAttempts++;

            if (y >= 126) {
                plugin.getLogger().severe("CRITICAL: Nether search attempted Y=" + y + " >= 126! Skipping.");
                continue;
            }

            if ((y + 2) >= 127) {
                continue;
            }

            Block groundBlock = chunk.getBlock(x & 15, y - 1, z & 15);
            Block feetBlock = chunk.getBlock(x & 15, y, z & 15);
            Block headBlock = chunk.getBlock(x & 15, y + 1, z & 15);

            if (groundBlock.getType().isSolid() && feetBlock.getType() == Material.AIR
                    && headBlock.getType() == Material.AIR) {
                Location loc = groundBlock.getLocation();
                Optional<FailureReason> safetyCheck = isSafe(loc, summary);
                if (safetyCheck.isEmpty()) {
                    Location safeLocation = feetBlock.getLocation().add(0.5, 0.5, 0.5);

                    double finalY = safeLocation.getY();
                    if (finalY >= 126.0) {
                        plugin.getLogger()
                                .severe("CRITICAL BUG DETECTED: findSafeInNether generated Y=" + finalY + " >= 126!");
                        plugin.getLogger().severe(
                                "This location would put player head at Y=" + (finalY + 1) + " (NETHER CEILING!)");
                        plugin.getLogger().severe("Rejecting location and continuing search.");
                        continue;
                    }

                    if (finalY + 1.0 >= 127.0) {
                        plugin.getLogger().severe("CRITICAL: Player head would be at Y=" + (finalY + 1.0) + " >= 127!");
                        continue;
                    }

                    plugin.getRTPLogger().debug("NETHER",
                            "Found safe nether location at Y=" + finalY + " (head at Y=" + (finalY + 1)
                                    + ") after " + searchAttempts + " attempts");
                    return Optional.of(safeLocation);
                }
            }
        }

        plugin.getRTPLogger().debug("NETHER",
                "No safe nether location found after " + searchAttempts + " Y-level checks (Y=" + maxSearchY
                        + " down to Y=" + minHeight + ")");
        summary.increment(FailureReason.UNKNOWN);
        return Optional.empty();
    }

    private Optional<Location> findSafeInEnd(Chunk chunk, int x, int z, SearchSummary summary) {
        World world = chunk.getWorld();
        String worldName = world.getName();

        plugin.getRTPLogger().debug("END",
                "[END SEARCH] Starting End location search in '" + worldName + "' at X=" + x + " Z=" + z);

        int maxSearchY = Math.min(120, world.getMaxHeight() - 1);
        int minSearchY = Math.max(10, world.getMinHeight() + 1);
        int searchAttempts = 0;

        plugin.getRTPLogger().debug("END", "[END SEARCH] Search range: Y=" + maxSearchY + " down to Y=" + minSearchY);

        for (int y = maxSearchY; y > minSearchY; y--) {
            searchAttempts++;

            if (y < 10 || y > 120) {
                continue;
            }

            Block groundBlock = chunk.getBlock(x & 15, y - 1, z & 15);

            Material groundType = groundBlock.getType();
            if (!groundType.isSolid()) {
                continue;
            }

            if (groundType != Material.END_STONE &&
                    groundType != Material.OBSIDIAN &&
                    groundType != Material.END_STONE_BRICKS &&
                    groundType != Material.PURPUR_BLOCK) {
                plugin.getRTPLogger().debug("END",
                        "[END SEARCH] Found non-standard ground type: " + groundType + " at Y=" + y);
            }

            Block feetBlock = chunk.getBlock(x & 15, y, z & 15);
            Block headBlock = chunk.getBlock(x & 15, y + 1, z & 15);

            if (feetBlock.getType().isAir() && headBlock.getType().isAir()) {
                Location loc = groundBlock.getLocation();
                Optional<FailureReason> safetyCheck = isSafe(loc, summary);

                if (safetyCheck.isEmpty()) {
                    Location safeLocation = feetBlock.getLocation().add(0.5, 0.5, 0.5);

                    double finalY = safeLocation.getY();
                    if (finalY < 10.0 || finalY > 120.0) {
                        plugin.getRTPLogger().debug("END",
                                "[END SAFETY] Generated unsafe Y=" + finalY + " - rejecting");
                        continue;
                    }

                    boolean hasGroundBelow = false;
                    for (int checkY = y - 2; checkY > Math.max(0, y - 10); checkY--) {
                        Block checkBlock = chunk.getBlock(x & 15, checkY, z & 15);
                        if (checkBlock.getType().isSolid()) {
                            hasGroundBelow = true;
                            break;
                        }
                    }

                    if (!hasGroundBelow) {
                        plugin.getRTPLogger().debug("END",
                                "[END SEARCH] No ground below Y=" + y + " - may be floating");
                        continue;
                    }

                    plugin.getRTPLogger().debug("END",
                            "[END SEARCH] Found safe End location at Y=" + finalY + " (" + groundType + ") after "
                                    + searchAttempts + " attempts");
                    return Optional.of(safeLocation);
                }
            }
        }

        plugin.getRTPLogger().debug("END",
                "[END SEARCH] No safe End location found after " + searchAttempts + " Y-level checks");
        summary.increment(FailureReason.UNKNOWN);
        return Optional.empty();
    }

    public boolean isSafeForSpread(Location location) {
        Block floor = location.getBlock();
        if (blacklistedBlocks.contains(floor.getType()))
            return false;
        if (floor.isLiquid())
            return false;
        if (floor.getType().isAir())
            return false;
        Block feet = location.clone().add(0, 1, 0).getBlock();
        Block head = location.clone().add(0, 2, 0).getBlock();
        return feet.isPassable() && head.isPassable();
    }

    private Optional<FailureReason> isSafe(Location location, SearchSummary summary) {
        Block floor = location.getBlock();
        if (blacklistedBlocks.contains(floor.getType())) {
            summary.increment(FailureReason.BLACKLISTED_BLOCK);
            return Optional.of(FailureReason.BLACKLISTED_BLOCK);
        }
        if (location.getWorld().getEnvironment() != World.Environment.THE_END) {
            Block belowFloor = location.clone().subtract(0, 1, 0).getBlock();
            if (belowFloor.getType() == Material.LAVA) {
                summary.increment(FailureReason.LAVA_NEARBY);
                return Optional.of(FailureReason.LAVA_NEARBY);
            }
        }
        if (floor.isLiquid()) {
            summary.increment(FailureReason.LIQUID_FLOOR);
            return Optional.of(FailureReason.LIQUID_FLOOR);
        }
        if (floor.getType().isAir()) {
            summary.increment(FailureReason.AIR_FLOOR);
            return Optional.of(FailureReason.AIR_FLOOR);
        }

        Block feet = location.clone().add(0, 1, 0).getBlock();
        Block head = location.clone().add(0, 2, 0).getBlock();
        if (!feet.isPassable() || !head.isPassable()) {
            summary.increment(FailureReason.OBSTRUCTED);
            return Optional.of(FailureReason.OBSTRUCTED);
        }
        Biome biome = floor.getBiome();
        boolean biomeAllowed = "BLACKLIST".equals(biomeMode) ? !biomeList.contains(biome) : biomeList.contains(biome);
        if (!biomeAllowed) {
            summary.increment(FailureReason.INVALID_BIOME);
            return Optional.of(FailureReason.INVALID_BIOME);
        }
        if (plugin.getConfig().getBoolean("settings.respect_regions") && !hookManager.isLocationSafe(location)) {
            summary.increment(FailureReason.REGION_CLAIM);
            return Optional.of(FailureReason.REGION_CLAIM);
        }
        return Optional.empty();
    }

}
