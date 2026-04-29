package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import eu.kotori.justRTP.events.PlayerRTPZoneEnterEvent;
import eu.kotori.justRTP.events.PlayerRTPZoneLeaveEvent;
import eu.kotori.justRTP.utils.RTPZone;
import eu.kotori.justRTP.utils.SafetyValidator;
import eu.kotori.justRTP.utils.task.CancellableTask;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class RTPZoneManager {

    private final JustRTP plugin;
    private final Map<String, RTPZone> zones = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerZoneMap = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> zonePlayersMap = new ConcurrentHashMap<>();
    private final Map<String, CancellableTask> activeZoneTasks = new ConcurrentHashMap<>();
    private final Set<UUID> ignoringPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> recentlyTeleported = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> zoneCountdowns = new ConcurrentHashMap<>();
    private File zonesFile;
    private FileConfiguration zonesConfig;
    private CancellableTask hologramHealerTask;

    public RTPZoneManager(JustRTP plugin) {
        this.plugin = plugin;
        this.zonesFile = new File(plugin.getDataFolder(), "rtp_zones.yml");
    }

    public void loadZones() {
        if (!zonesFile.exists()) {
            plugin.saveResource("rtp_zones.yml", false);
        }
        zonesConfig = YamlConfiguration.loadConfiguration(zonesFile);
        shutdownAllTasks();
        zones.clear();
        playerZoneMap.clear();
        zonePlayersMap.clear();
        ignoringPlayers.clear();
        recentlyTeleported.clear();

        ConfigurationSection zonesSection = zonesConfig.getConfigurationSection("zones");
        if (zonesSection == null) {
            plugin.getLogger().info("No RTP zones found to load.");
            return;
        }

        for (String zoneId : zonesSection.getKeys(false)) {
            try {
                RTPZone zone = new RTPZone(zoneId, zonesSection.getConfigurationSection(zoneId));
                zones.put(zoneId.toLowerCase(), zone);
                startZoneScheduler(zone);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Failed to load RTP Zone '" + zoneId + "': " + e.getMessage());
            }
        }
        startHologramHealerTask();
        plugin.getLogger().info("Loaded and activated " + zones.size() + " RTP Arena Zones.");
    }

    private void startZoneScheduler(RTPZone zone) {
        CancellableTask existingTask = activeZoneTasks.remove(zone.getId().toLowerCase());
        if (existingTask != null) {
            existingTask.cancel();
        }

        Location zoneCenter = zone.getCenterLocation();
        if (zoneCenter == null) {
            plugin.getLogger().severe("Could not start scheduler for zone '" + zone.getId()
                    + "' because its center location is in an unloaded world.");
            return;
        }

        if (zone.getHologramLocation() != null) {
            plugin.getHologramManager().createOrUpdateHologram(zone.getId(), zone.getHologramLocation(),
                    zone.getHologramViewDistance());
        }

        final int interval = zone.getInterval();
        final java.util.concurrent.atomic.AtomicInteger countdown = new java.util.concurrent.atomic.AtomicInteger(
                interval);

        zoneCountdowns.put(zone.getId().toLowerCase(), interval);

        plugin.getHologramManager().updateHologramTime(zone.getId(), interval);

        CancellableTask task = plugin.getFoliaScheduler().runTimerAtLocation(zoneCenter, () -> {
            try {
                List<Player> playersInZone = getPlayersInZone(zone.getId());

                if (countdown.get() <= 0) {
                    plugin.getHologramManager().updateHologramProgress(zone.getId());

                    if (!playersInZone.isEmpty()) {
                        for (Player player : playersInZone) {
                            if (!isIgnoring(player)) {
                                plugin.getFoliaScheduler().runAtEntity(player, () -> {
                                    player.clearTitle();
                                });
                            }
                        }

                        teleportPlayersInZone(playersInZone, zone);
                    }

                    countdown.set(interval);
                    zoneCountdowns.put(zone.getId().toLowerCase(), interval);
                    plugin.getHologramManager().updateHologramTime(zone.getId(), interval);
                    return;
                }

                int currentTime = countdown.decrementAndGet();

                zoneCountdowns.put(zone.getId().toLowerCase(), currentTime);

                plugin.getHologramManager().updateHologramTime(zone.getId(), currentTime);

                for (Player player : playersInZone) {
                    if (!isIgnoring(player)) {
                        final int timeToPass = currentTime;
                        plugin.getFoliaScheduler().runAtEntity(player,
                                () -> updateWaitingEffects(player, zone, timeToPass));
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error in RTPZone scheduler for zone '" + zone.getId() + "':", e);
            }
        }, 1L, 20L);

        activeZoneTasks.put(zone.getId().toLowerCase(), task);
    }

    private void startHologramHealerTask() {
        if (hologramHealerTask != null && !hologramHealerTask.isCancelled()) {
            hologramHealerTask.cancel();
        }
        hologramHealerTask = plugin.getFoliaScheduler().runTimer(() -> {
            for (RTPZone zone : zones.values()) {
                Location holoLoc = zone.getHologramLocation();
                if (holoLoc != null
                        && holoLoc.getWorld().isChunkLoaded(holoLoc.getBlockX() >> 4, holoLoc.getBlockZ() >> 4)) {
                    if (!plugin.getHologramManager().isHologramActive(zone.getId())
                            && !plugin.getHologramManager().isBeingCreated(zone.getId())) {
                        plugin.getRTPLogger().debug("ZONE",
                                "Healer task is respawning missing hologram for zone: " + zone.getId());

                        plugin.getHologramManager().createOrUpdateHologram(zone.getId(), holoLoc,
                                zone.getHologramViewDistance());
                    }
                }
            }
        }, 40L, 40L);
    }

    private void teleportPlayersInZone(List<Player> players, RTPZone zone) {
        List<Player> teleportCandidates = players.stream()
                .filter(p -> p != null && p.isOnline() && !isIgnoring(p))
                .collect(Collectors.toList());

        plugin.getRTPLogger().debug("ZONE", "No valid teleport candidates for zone " + zone.getId());

        plugin.getRTPLogger().debug("ZONE",
                "Teleporting " + teleportCandidates.size() + " players from zone " + zone.getId());

        Set<UUID> playersInThisZone = zonePlayersMap.get(zone.getId().toLowerCase());
        if (playersInThisZone != null) {
            for (Player p : teleportCandidates) {
                if (p == null || !p.isOnline())
                    continue;

                UUID playerUUID = p.getUniqueId();

                playerZoneMap.remove(playerUUID);
                playersInThisZone.remove(playerUUID);

                recentlyTeleported.add(playerUUID);
                plugin.getFoliaScheduler().runLater(() -> recentlyTeleported.remove(playerUUID), 100L);

                plugin.getFoliaScheduler().runAtEntity(p, () -> {
                    p.clearTitle();
                    plugin.getEffectsManager().clearActionBar(p);
                    plugin.getEffectsManager().applyEffects(p, getZoneEffects(zone, "teleport"));
                });
            }
        }

        List<String> targets = zone.getTargets();
        String selectedTarget = targets.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(targets.size()));

        World targetWorld = Bukkit.getWorld(selectedTarget);
        if (targetWorld != null) {
            handleLocalZoneTeleport(teleportCandidates, zone, targetWorld);
        } else if (plugin.getConfigManager().getProxyEnabled()) {
            handleProxyZoneTeleport(teleportCandidates, zone, selectedTarget);
        } else {
            plugin.getLogger().warning("Zone '" + zone.getId() + "' target world '" + selectedTarget +
                    "' not found and proxy is disabled. Teleport aborted for " +
                    teleportCandidates.size() + " players.");
            teleportCandidates.forEach(p -> plugin.getFoliaScheduler().runAtEntity(p,
                    () -> plugin.getLocaleManager().sendMessage(p, "command.world_not_found",
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("world",
                                    selectedTarget),
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("worlds",
                                    getAvailableWorldsList()))));
        }
    }

    private void handleProxyZoneTeleport(List<Player> players, RTPZone zone, String targetServer) {
        plugin.getCrossServerManager().sendGroupFindLocationRequest(players, targetServer,
                Optional.of(zone.getMinRadius()), Optional.of(zone.getMaxRadius()));
    }

    private void handleLocalZoneTeleport(List<Player> players, RTPZone zone, World targetWorld) {
        plugin.getRTPLogger().debug("ZONE",
                "[ZONE RTP] Starting zone teleport for " + players.size() + " player(s) in zone '" +
                        zone.getId() + "' to world '" + targetWorld.getName() + "' (" + targetWorld.getEnvironment()
                        + ")");

        if (targetWorld.getEnvironment() == World.Environment.NETHER) {
            plugin.getRTPLogger().debug("ZONE", "╔════════════════════════════════════════════╗");
            plugin.getRTPLogger().debug("ZONE", "║  NETHER ZONE TELEPORT INITIATED           ║");
            plugin.getRTPLogger().debug("ZONE", "║  World: " + targetWorld.getName() + "              ║");
            plugin.getRTPLogger().debug("ZONE", "║  Environment: NETHER                      ║");
            plugin.getRTPLogger().debug("ZONE", "║  Safety: Y < 127 ENFORCED                 ║");
            plugin.getRTPLogger().debug("ZONE", "╚════════════════════════════════════════════╝");

        }

        findSafeLocationsForPlayers(players, zone, targetWorld);
    }

    private void findSafeLocationsForPlayers(List<Player> players, RTPZone zone, World targetWorld) {
        if (players == null || players.isEmpty()) {
            plugin.getLogger().warning("[ZONE RTP] No players provided for zone " + zone.getId());
            return;
        }

        plugin.getRTPLogger().debug("ZONE", "[ZONE RTP] ═══════════════════════════════════════════════════");
        plugin.getRTPLogger().debug("ZONE", "[ZONE RTP] Starting group teleport for " + players.size() + " player(s):");
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            plugin.getRTPLogger().debug("ZONE", "[ZONE RTP]   " + (i + 1) + ". " + (p != null ? p.getName() : "NULL") +
                    (p != null && p.isOnline() ? " (ONLINE)" : " (OFFLINE)"));
        }
        plugin.getRTPLogger().debug("ZONE", "[ZONE RTP] ═══════════════════════════════════════════════════");

        plugin.getHologramManager().updateHologramProgress(zone.getId());

        int minSpread = zone.getMinSpreadDistance();
        int maxSpread = zone.getMaxSpreadDistance();

        plugin.getRTPLogger().debug("ZONE",
                "[ZONE RTP] Spread configuration: min=" + minSpread + " blocks, max=" + maxSpread + " blocks");

        Player firstPlayer = players.get(0);
        if (firstPlayer == null || !firstPlayer.isOnline()) {
            plugin.getLogger().warning("[ZONE RTP] First player is null or offline, aborting group teleport");
            return;
        }

        plugin.getRTPLogger().debug("ZONE",
                "[ZONE RTP] Finding central safe location using " + firstPlayer.getName() + " as reference");

        plugin.getRtpService()
                .findSafeLocation(firstPlayer, targetWorld, 0,
                        Optional.of(zone.getMinRadius()),
                        Optional.of(zone.getMaxRadius()))
                .thenAccept(centralLocationOpt -> {
                    if (!centralLocationOpt.isPresent()) {
                        plugin.getLogger()
                                .warning("[ZONE RTP] Could not find central safe location for zone " + zone.getId());

                        for (Player player : players) {
                            if (player != null && player.isOnline()) {
                                plugin.getFoliaScheduler().runAtEntity(player, () -> {
                                    plugin.getLocaleManager().sendMessage(player, "teleport.no_location_found");
                                    plugin.getLocaleManager().sendMessage(player, "zone.teleport_failed");
                                });
                            }
                        }
                        return;
                    }

                    Location centralLocation = centralLocationOpt.get();
                    plugin.getRTPLogger().debug("ZONE", "[ZONE RTP] ✓ Central location found at X:" +
                            centralLocation.getBlockX() + " Y:" + centralLocation.getBlockY() + " Z:" +
                            centralLocation.getBlockZ() + " in " + targetWorld.getName());
                    plugin.getRTPLogger().debug("ZONE",
                            "[ZONE RTP] Now finding individual spawn points SEQUENTIALLY for each player...");

                    List<Location> foundLocations = Collections.synchronizedList(new ArrayList<>());
                    CompletableFuture<List<Location>> sequentialFuture = new CompletableFuture<>();

                    plugin.getRTPLogger().debug("ZONE",
                            "[ZONE RTP] Starting SEQUENTIAL location search for " + players.size() + " players");

                    findLocationsSequentially(players, 0, centralLocation, foundLocations, zone, 50, sequentialFuture);

                    sequentialFuture.thenAccept(locations -> {
                        if (locations != null && locations.size() == players.size()) {
                            plugin.getRTPLogger().debug("ZONE", "[ZONE RTP] ✓ All " + locations.size()
                                    + " locations found via SEQUENTIAL processing");

                            List<Player> validPlayers = new ArrayList<>();
                            List<Location> validLocations = new ArrayList<>();

                            for (int i = 0; i < players.size(); i++) {
                                Player p = players.get(i);
                                Location loc = locations.get(i);

                                if (p != null && p.isOnline() && loc != null) {
                                    validPlayers.add(p);
                                    validLocations.add(loc);
                                } else {
                                    plugin.getLogger().warning(String.format(
                                            "[ZONE RTP] Skipping player %s - %s",
                                            p != null ? p.getName() : "NULL",
                                            loc == null ? "no location found" : "player offline"));
                                }
                            }

                            if (!validPlayers.isEmpty()) {
                                plugin.getRTPLogger().debug("ZONE", String.format(
                                        "[ZONE RTP] Proceeding with teleportation for %d/%d players",
                                        validPlayers.size(), players.size()));

                                performGroupTeleportation(validPlayers, validLocations, zone, "ZONE_RTP");
                            } else {
                                plugin.getLogger().warning("[ZONE RTP] No valid players to teleport after filtering");
                            }
                        } else {
                            plugin.getLogger().warning("[ZONE RTP] ✗ Sequential processing failed or incomplete");
                            for (Player player : players) {
                                if (player != null && player.isOnline()) {
                                    plugin.getFoliaScheduler().runAtEntity(player, () -> {
                                        plugin.getLocaleManager().sendMessage(player, "teleport.no_location_found");
                                        plugin.getLocaleManager().sendMessage(player, "zone.teleport_failed");
                                    });
                                }
                            }
                        }
                    }).exceptionally(ex -> {
                        plugin.getLogger().severe("[ZONE RTP] Sequential processing exception: " + ex.getMessage());
                        ex.printStackTrace();
                        return null;
                    });
                })
                .exceptionally(throwable -> {
                    plugin.getLogger().severe("[ZONE RTP] Error finding central location: " + throwable.getMessage());
                    throwable.printStackTrace();
                    return null;
                });
    }

    private CompletableFuture<Location> findSafeLocationNearby(
            Location centralLocation,
            RTPZone zone,
            List<Location> foundLocations,
            int totalPlayers,
            int playerIndex,
            int maxAttempts) {

        int minSpread = zone.getMinSpreadDistance();
        int maxSpread = zone.getMaxSpreadDistance();

        double baseAngle = (2 * Math.PI * playerIndex) / totalPlayers;

        double angleVariation = (Math.random() - 0.5) * (Math.PI / 12);
        double angle = baseAngle + angleVariation;

        double baseDistance;
        if (totalPlayers == 1) {
            baseDistance = minSpread + ((maxSpread - minSpread) * Math.random());
        } else {
            double layerFactor = ((double) playerIndex / totalPlayers);
            baseDistance = minSpread + ((maxSpread - minSpread) * layerFactor);
        }

        double distanceVariation = (maxSpread - minSpread) * 0.2 * Math.random();
        double distance = Math.max(minSpread + 5, Math.min(maxSpread - 5, baseDistance + distanceVariation));

        int offsetX = (int) (Math.cos(angle) * distance);
        int offsetZ = (int) (Math.sin(angle) * distance);

        Location targetLocation = centralLocation.clone().add(offsetX, 0, offsetZ);

        plugin.getLogger().info(String.format(
                "[ZoneTP-Find] Player [%d/%d] → Angle: %.1f° | Distance: %.1f blocks | Offset: X%d Z%d | Already found: %d | Range: %d-%d blocks",
                playerIndex + 1, totalPlayers, Math.toDegrees(angle), distance, offsetX, offsetZ,
                foundLocations.size(), minSpread, maxSpread));

        return searchForSafeLocation(targetLocation, zone, foundLocations, baseAngle, centralLocation, minSpread,
                maxSpread, 0, maxAttempts);
    }

    private CompletableFuture<Location> searchForSafeLocation(
            Location targetLocation,
            RTPZone zone,
            List<Location> foundLocations,
            double baseAngle,
            Location centralLocation,
            int minSpread,
            int maxSpread,
            int attempt,
            int maxAttempts) {

        if (attempt >= maxAttempts) {
            plugin.getLogger().warning(String.format(
                    "[ZoneTP-Find] ✗ Exhausted all %d attempts to find valid location",
                    maxAttempts));
            return CompletableFuture.completedFuture(null);
        }

        World world = targetLocation.getWorld();
        int targetX = targetLocation.getBlockX();
        int targetZ = targetLocation.getBlockZ();

        return findSafeYAtLocationAsync(world, targetX, targetZ)
                .thenCompose(adjustedTarget -> {
                    if (adjustedTarget == null) {
                        plugin.getLogger().warning(String.format(
                                "[ZoneTP-Find] Attempt %d/%d: No safe Y found at X=%d Z=%d, trying different position",
                                attempt + 1, maxAttempts, targetX, targetZ));

                        double retryAngle = baseAngle + ((attempt + 1) * Math.PI / 3);
                        double retryDistance = minSpread + (Math.random() * (maxSpread - minSpread));
                        int retryOffsetX = (int) (Math.cos(retryAngle) * retryDistance);
                        int retryOffsetZ = (int) (Math.sin(retryAngle) * retryDistance);
                        Location retryTarget = centralLocation.clone().add(retryOffsetX, 0, retryOffsetZ);

                        return searchForSafeLocation(retryTarget, zone, foundLocations, baseAngle, centralLocation,
                                minSpread, maxSpread, attempt + 1, maxAttempts);
                    }

                    return SafetyValidator.isLocationAbsolutelySafeAsync(adjustedTarget)
                            .thenCompose(isSafe -> {
                                if (!isSafe) {
                                    plugin.getLogger().warning(String.format(
                                            "[ZoneTP-Find] Attempt %d/%d: Location unsafe at (%.1f, %.1f, %.1f), trying with adjusted angle",
                                            attempt + 1, maxAttempts, adjustedTarget.getX(), adjustedTarget.getY(),
                                            adjustedTarget.getZ()));

                                    double retryAngle = baseAngle + ((attempt + 1) * Math.PI / 3);
                                    double retryDistance = minSpread + (Math.random() * (maxSpread - minSpread));
                                    int retryOffsetX = (int) (Math.cos(retryAngle) * retryDistance);
                                    int retryOffsetZ = (int) (Math.sin(retryAngle) * retryDistance);
                                    Location retryTarget = centralLocation.clone().add(retryOffsetX, 0, retryOffsetZ);

                                    return searchForSafeLocation(retryTarget, zone, foundLocations, baseAngle,
                                            centralLocation,
                                            minSpread, maxSpread, attempt + 1, maxAttempts);
                                }

                                synchronized (foundLocations) {
                                    for (int i = 0; i < foundLocations.size(); i++) {
                                        Location existing = foundLocations.get(i);
                                        if (existing == null)
                                            continue;

                                        double distance = existing.distance(adjustedTarget);
                                        if (distance < minSpread) {
                                            plugin.getLogger().info(String.format(
                                                    "[ZoneTP-Find] Attempt %d/%d: Too close to player #%d (%.1f blocks < %d min) at (%.1f, %.1f, %.1f), adjusting position",
                                                    attempt + 1, maxAttempts, i + 1, distance, minSpread,
                                                    existing.getX(), existing.getY(), existing.getZ()));

                                            double retryAngle = baseAngle + ((attempt + 1) * Math.PI / 4);

                                            double distanceShift = ((attempt % 2 == 0) ? 1.2 : 0.8);
                                            double retryDistance = Math.min(maxSpread,
                                                    Math.max(minSpread, (minSpread + maxSpread) / 2.0 * distanceShift));

                                            int retryOffsetX = (int) (Math.cos(retryAngle) * retryDistance);
                                            int retryOffsetZ = (int) (Math.sin(retryAngle) * retryDistance);
                                            Location retryTarget = centralLocation.clone().add(retryOffsetX, 0,
                                                    retryOffsetZ);

                                            plugin.getRTPLogger().debug("ZONE", String.format(
                                                    "[ZoneTP-Find] Retrying with angle=%.1f°, distance=%.1f blocks, offset=(%d, %d)",
                                                    Math.toDegrees(retryAngle), retryDistance, retryOffsetX,
                                                    retryOffsetZ));

                                            return searchForSafeLocation(retryTarget, zone, foundLocations, baseAngle,
                                                    centralLocation,
                                                    minSpread, maxSpread, attempt + 1, maxAttempts);
                                        }
                                    }

                                    double actualDistance = centralLocation.distance(adjustedTarget);
                                    plugin.getRTPLogger().debug("ZONE", String.format(
                                            "[ZoneTP-Find] ✓ Valid location at (%.1f, %.1f, %.1f) - %.1f blocks from center, min %.1f blocks from all %d existing players (attempt %d/%d)",
                                            adjustedTarget.getX(), adjustedTarget.getY(), adjustedTarget.getZ(),
                                            actualDistance,
                                            foundLocations.isEmpty() ? 0.0
                                                    : foundLocations.stream()
                                                            .filter(Objects::nonNull)
                                                            .mapToDouble(loc -> loc.distance(adjustedTarget))
                                                            .min().orElse(0.0),
                                            foundLocations.size(),
                                            attempt + 1, maxAttempts));

                                    return CompletableFuture.completedFuture(adjustedTarget);
                                }
                            });
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe(String.format(
                            "[ZoneTP-Find] Exception during location search (attempt %d/%d): %s",
                            attempt + 1, maxAttempts, ex.getMessage()));
                    ex.printStackTrace();
                    return null;
                });
    }

    private CompletableFuture<Location> findSafeYAtLocationAsync(World world, int x, int z) {
        return io.papermc.lib.PaperLib.getChunkAtAsync(world, x >> 4, z >> 4).thenApply(chunk -> {
            if (chunk == null)
                return null;

            World.Environment env = world.getEnvironment();
            int lx = x & 15;
            int lz = z & 15;

            switch (env) {
                case NETHER:
                    return findSafeYInNether(world, chunk, lx, lz, x, z);
                case THE_END:
                    return findSafeYInEnd(world, chunk, lx, lz, x, z);
                case NORMAL:
                default:
                    return findSafeYInOverworld(world, chunk, lx, lz, x, z);
            }
        });
    }

    private Location findSafeYInOverworld(World world, org.bukkit.Chunk chunk, int lx, int lz, int absX, int absZ) {

        int highestY = world.getHighestBlockYAt(absX, absZ);
        if (highestY < world.getMinHeight())
            highestY = world.getMaxHeight();

        Location loc = new Location(world, absX + 0.5, highestY + 1, absZ + 0.5);

        if (highestY + 2 > world.getMaxHeight())
            return null;

        org.bukkit.block.Block ground = chunk.getBlock(lx, highestY, lz);
        org.bukkit.block.Block feet = chunk.getBlock(lx, highestY + 1, lz);
        org.bukkit.block.Block head = chunk.getBlock(lx, highestY + 2, lz);

        if (ground.getType().isSolid() && !ground.isLiquid() &&
                feet.isPassable() && head.isPassable()) {
            return loc;
        }

        for (int y = highestY; y > world.getMinHeight() + 5; y--) {
            if (y >= world.getMaxHeight())
                continue;

            ground = chunk.getBlock(lx, y - 1, lz);
            feet = chunk.getBlock(lx, y, lz);
            head = chunk.getBlock(lx, y + 1, lz);

            if (ground.getType().isSolid() && !ground.isLiquid() &&
                    feet.isPassable() && !feet.isLiquid() &&
                    head.isPassable() && !head.isLiquid()) {
                return new Location(world, absX + 0.5, y, absZ + 0.5);
            }
        }

        return null;
    }

    private Location findSafeYInNether(World world, org.bukkit.Chunk chunk, int lx, int lz, int absX, int absZ) {
        for (int y = 120; y > 5; y--) {
            if (y >= 126 || (y + 1) >= 127) {
                continue;
            }

            org.bukkit.block.Block ground = chunk.getBlock(lx, y - 1, lz);
            org.bukkit.block.Block feet = chunk.getBlock(lx, y, lz);
            org.bukkit.block.Block head = chunk.getBlock(lx, y + 1, lz);

            if (ground.getType().isSolid() && !ground.isLiquid() &&
                    ground.getType() != org.bukkit.Material.MAGMA_BLOCK &&
                    feet.getType() == org.bukkit.Material.AIR &&
                    head.getType() == org.bukkit.Material.AIR) {
                return new Location(world, absX + 0.5, y, absZ + 0.5);
            }
        }

        return null;
    }

    private Location findSafeYInEnd(World world, org.bukkit.Chunk chunk, int lx, int lz, int absX, int absZ) {
        for (int y = 120; y > 10; y--) {
            org.bukkit.block.Block ground = chunk.getBlock(lx, y - 1, lz);
            org.bukkit.block.Block feet = chunk.getBlock(lx, y, lz);
            org.bukkit.block.Block head = chunk.getBlock(lx, y + 1, lz);

            org.bukkit.Material groundType = ground.getType();

            if ((groundType == org.bukkit.Material.END_STONE ||
                    groundType == org.bukkit.Material.OBSIDIAN ||
                    groundType == org.bukkit.Material.END_STONE_BRICKS) &&
                    feet.isPassable() && head.isPassable()) {

                int voidCount = 0;
                for (int xOff = -1; xOff <= 1; xOff++) {
                    for (int zOff = -1; zOff <= 1; zOff++) {
                        if (xOff == 0 && zOff == 0)
                            continue;

                        int nx = lx + xOff;
                        int nz = lz + zOff;

                        if (nx >= 0 && nx <= 15 && nz >= 0 && nz <= 15) {
                            org.bukkit.block.Block nearby = chunk.getBlock(nx, y - 1, nz);
                            if (!nearby.getType().isSolid()) {
                                voidCount++;
                            }
                        }
                    }
                }

                if (voidCount <= 3) {
                    return new Location(world, absX + 0.5, y, absZ + 0.5);
                }
            }
        }

        return null;
    }

    private void updateWaitingEffects(Player player, RTPZone zone, int timeRemaining) {
        ConfigurationSection waitingEffects = getZoneEffects(zone, "waiting");

        if (timeRemaining > 0) {
            boolean shownZoneTitle = false;
            ConfigurationSection titleSection = waitingEffects != null
                    ? waitingEffects.getConfigurationSection("title")
                    : null;
            if (titleSection != null && titleSection.getBoolean("enabled", false)) {
                String titleText = titleSection.getString("main_title", "");
                String subtitleText = titleSection.getString("subtitle", "");
                if (!titleText.isBlank() || !subtitleText.isBlank()) {
                    long fadeIn = titleSection.getLong("fade_in", 0);
                    long stay = titleSection.getLong("stay", 25);
                    long fadeOut = titleSection.getLong("fade_out", 5);

                    Title.Times times = Title.Times.times(Duration.ofMillis(fadeIn * 50), Duration.ofMillis(stay * 50),
                            Duration.ofMillis(fadeOut * 50));
                    Title title = Title.title(
                            MiniMessage.miniMessage().deserialize(titleText,
                                    Placeholder.unparsed("time",
                                            eu.kotori.justRTP.utils.TimeUtils.formatDuration(timeRemaining))),
                            MiniMessage.miniMessage().deserialize(subtitleText,
                                    Placeholder.unparsed("time",
                                            eu.kotori.justRTP.utils.TimeUtils.formatDuration(timeRemaining))),
                            times);
                    player.showTitle(title);
                    shownZoneTitle = true;
                }
            }

            if (!shownZoneTitle && plugin.getConfig().getBoolean("zone_title.enabled", false)) {
                String titleText = plugin.getConfig().getString("zone_title.main_title", "");
                String subtitleText = plugin.getConfig().getString("zone_title.subtitle", "");
                if (!titleText.isBlank() || !subtitleText.isBlank()) {
                    long fadeIn = plugin.getConfig().getLong("zone_title.fade_in", 0);
                    long stay = plugin.getConfig().getLong("zone_title.stay", 25);
                    long fadeOut = plugin.getConfig().getLong("zone_title.fade_out", 5);

                    Title.Times times = Title.Times.times(Duration.ofMillis(fadeIn * 50), Duration.ofMillis(stay * 50),
                            Duration.ofMillis(fadeOut * 50));
                    Title title = Title.title(
                            MiniMessage.miniMessage().deserialize(titleText,
                                    Placeholder.unparsed("time",
                                            eu.kotori.justRTP.utils.TimeUtils.formatDuration(timeRemaining))),
                            MiniMessage.miniMessage().deserialize(subtitleText,
                                    Placeholder.unparsed("time",
                                            eu.kotori.justRTP.utils.TimeUtils.formatDuration(timeRemaining))),
                            times);
                    player.showTitle(title);
                }
            }
        }

        if (waitingEffects == null) {
            return;
        }

        ConfigurationSection actionBarSection = waitingEffects.getConfigurationSection("action_bar");
        if (actionBarSection != null && actionBarSection.getBoolean("enabled", false)) {
            String text = actionBarSection.getString("text", "");
            if (!text.isBlank()) {
                player.sendActionBar(MiniMessage.miniMessage().deserialize(text,
                        Placeholder.unparsed("time", eu.kotori.justRTP.utils.TimeUtils.formatDuration(timeRemaining))));
            }
        }

        ConfigurationSection soundSection = waitingEffects.getConfigurationSection("sound");
        if (soundSection != null && soundSection.getBoolean("enabled", false) && timeRemaining <= 3
                && timeRemaining > 0) {
            try {
                String configSoundName = soundSection.getString("name", "");
                if (configSoundName == null || configSoundName.trim().isEmpty()) {
                    return;
                }
                String soundName = configSoundName.trim().toUpperCase();
                Sound sound = null;

                java.util.List<String> soundVariants = new java.util.ArrayList<>();
                soundVariants.add(soundName);

                String noUnderscores = soundName.replace("_", "");
                soundVariants.add(noUnderscores);

                soundVariants.add(noUnderscores.replaceAll("(?<!^)(?=[A-Z])", "_"));

                if (soundName.contains("_")) {
                    String[] parts = soundName.split("_");

                    if (parts.length >= 2) {
                        StringBuilder merged = new StringBuilder();
                        for (int i = 0; i < parts.length - 2; i++) {
                            if (i > 0)
                                merged.append("_");
                            merged.append(parts[i]);
                        }
                        if (parts.length > 2)
                            merged.append("_");
                        merged.append(parts[parts.length - 2]).append(parts[parts.length - 1]);
                        soundVariants.add(merged.toString());
                    }

                    String lastPart = parts[parts.length - 1];
                    String smartSplit = splitCompoundWord(lastPart);
                    if (!smartSplit.equals(lastPart)) {
                        StringBuilder withSplit = new StringBuilder();
                        for (int i = 0; i < parts.length - 1; i++) {
                            if (i > 0)
                                withSplit.append("_");
                            withSplit.append(parts[i]);
                        }
                        if (parts.length > 1)
                            withSplit.append("_");
                        withSplit.append(smartSplit);
                        soundVariants.add(withSplit.toString());
                    }
                }

                plugin.getRTPLogger().debug("ZONE",
                        "Zone sound: Trying " + soundVariants.size() + " variants for '" + configSoundName + "'");

                for (String variant : soundVariants) {
                    if (sound != null)
                        break;

                    try {
                        sound = Sound.valueOf(variant);
                        if (sound != null) {
                            plugin.getRTPLogger().debug("ZONE",
                                    "Zone sound: Found '" + variant + "' via Sound.valueOf()");

                        }
                    } catch (IllegalArgumentException e) {
                    }

                    if (sound == null) {
                        try {
                            String registryKey = variant.toLowerCase().replace("_", ".");
                            org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.minecraft(registryKey);
                            sound = org.bukkit.Registry.SOUNDS.get(key);
                            if (sound != null) {
                                plugin.getRTPLogger().debug("ZONE",
                                        "Zone sound: Found '" + variant + "' via Registry API (key: " + registryKey
                                                + ")");

                            }
                        } catch (Exception e) {
                        }
                    }

                    if (sound == null) {
                        try {
                            org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.minecraft(variant.toLowerCase());
                            sound = org.bukkit.Registry.SOUNDS.get(key);
                            if (sound != null) {
                                plugin.getRTPLogger().debug("ZONE",
                                        "Zone sound: Found '" + variant + "' via Registry API (underscore key)");

                            }
                        } catch (Exception e) {
                        }
                    }
                }

                if (sound != null) {
                    float volume = (float) soundSection.getDouble("volume", 1.0);
                    float pitch = (float) soundSection.getDouble("pitch", 1.0);
                    player.playSound(player.getLocation(), sound, volume, pitch);
                    plugin.getRTPLogger().debug("ZONE", "Zone sound: Successfully played " + sound);

                } else {
                    plugin.getRTPLogger().debug("ZONE",
                            "Zone sound: Invalid sound name in zone effects: " + soundSection.getString("name")
                                    + " (tried variants: " + soundVariants + ")");

                }
            } catch (Exception e) {
                plugin.getRTPLogger().debug("ZONE",
                        "Zone sound: Error playing zone sound: " + soundSection.getString("name") + " - "
                                + e.getMessage());

                e.printStackTrace();
            }
        }
    }

    private String splitCompoundWord(String word) {
        String[] commonSuffixes = { "UP", "DOWN", "IN", "OUT", "ON", "OFF", "ORB" };
        for (String suffix : commonSuffixes) {
            if (word.endsWith(suffix) && word.length() > suffix.length()) {
                return word.substring(0, word.length() - suffix.length()) + "_" + suffix;
            }
        }
        return word;
    }

    private RTPZone getZoneAt(Location location) {
        for (RTPZone zone : zones.values()) {
            if (zone.contains(location)) {
                return zone;
            }
        }
        return null;
    }

    public void handlePlayerMove(Player player, Location to) {
        if (recentlyTeleported.contains(player.getUniqueId())) {
            return;
        }

        String currentZoneId = playerZoneMap.get(player.getUniqueId());
        RTPZone newZone = getZoneAt(to);

        if (newZone != null) {
            if (!Objects.equals(currentZoneId, newZone.getId())) {

                if (currentZoneId != null) {
                    RTPZone oldZone = getZone(currentZoneId);
                    if (oldZone != null) {
                        Set<UUID> playersInOldZone = zonePlayersMap.get(currentZoneId.toLowerCase());
                        int remainingPlayers = (playersInOldZone != null) ? playersInOldZone.size() - 1 : 0;

                        PlayerRTPZoneLeaveEvent leaveEvent = new PlayerRTPZoneLeaveEvent(
                                player,
                                oldZone,
                                PlayerRTPZoneLeaveEvent.LeaveReason.MOVED_OUT,
                                Math.max(0, remainingPlayers));
                        Bukkit.getPluginManager().callEvent(leaveEvent);

                        plugin.getEffectsManager().applyEffects(player, getZoneEffects(oldZone, "on_leave"));
                        if (playersInOldZone != null) {
                            playersInOldZone.remove(player.getUniqueId());
                        }
                    }
                }

                Set<UUID> playersInNewZone = zonePlayersMap.computeIfAbsent(newZone.getId().toLowerCase(),
                        k -> ConcurrentHashMap.newKeySet());
                int playersInZone = playersInNewZone.size() + 1;

                PlayerRTPZoneEnterEvent enterEvent = new PlayerRTPZoneEnterEvent(
                        player,
                        newZone,
                        playersInZone);
                Bukkit.getPluginManager().callEvent(enterEvent);

                if (!enterEvent.isCancelled()) {
                    playerZoneMap.put(player.getUniqueId(), newZone.getId());
                    playersInNewZone.add(player.getUniqueId());
                    plugin.getEffectsManager().applyEffects(player, getZoneEffects(newZone, "on_enter"));
                }
            }
        } else {
            if (currentZoneId != null) {
                RTPZone oldZone = getZone(currentZoneId);
                if (oldZone != null) {
                    Set<UUID> playersInOldZone = zonePlayersMap.get(currentZoneId.toLowerCase());
                    int remainingPlayers = (playersInOldZone != null) ? playersInOldZone.size() - 1 : 0;

                    PlayerRTPZoneLeaveEvent leaveEvent = new PlayerRTPZoneLeaveEvent(
                            player,
                            oldZone,
                            PlayerRTPZoneLeaveEvent.LeaveReason.MOVED_OUT,
                            Math.max(0, remainingPlayers));
                    Bukkit.getPluginManager().callEvent(leaveEvent);

                    plugin.getEffectsManager().applyEffects(player, getZoneEffects(oldZone, "on_leave"));
                }
                playerZoneMap.remove(player.getUniqueId());
                Set<UUID> playersInOldZone = zonePlayersMap.get(currentZoneId.toLowerCase());
                if (playersInOldZone != null) {
                    playersInOldZone.remove(player.getUniqueId());
                }
            }
        }
    }

    public void handlePlayerQuit(Player player) {
        String zoneId = playerZoneMap.remove(player.getUniqueId());
        if (zoneId != null) {
            RTPZone zone = getZone(zoneId);
            Set<UUID> players = zonePlayersMap.get(zoneId.toLowerCase());
            int remainingPlayers = (players != null) ? players.size() - 1 : 0;

            if (zone != null) {
                PlayerRTPZoneLeaveEvent leaveEvent = new PlayerRTPZoneLeaveEvent(
                        player,
                        zone,
                        PlayerRTPZoneLeaveEvent.LeaveReason.DISCONNECTED,
                        Math.max(0, remainingPlayers));
                Bukkit.getPluginManager().callEvent(leaveEvent);
            }

            if (players != null) {
                players.remove(player.getUniqueId());
            }
        }
        ignoringPlayers.remove(player.getUniqueId());
        recentlyTeleported.remove(player.getUniqueId());
    }

    private List<Player> getPlayersInZone(String zoneId) {
        Set<UUID> playerUuids = zonePlayersMap.get(zoneId.toLowerCase());
        if (playerUuids == null) {
            return Collections.emptyList();
        }
        return playerUuids.stream()
                .map(Bukkit::getPlayer)
                .filter(p -> p != null && p.isOnline())
                .collect(Collectors.toList());
    }

    private ConfigurationSection getZoneEffects(RTPZone zone, String effectType) {
        String path = "zones." + zone.getId() + ".effects." + effectType;
        if (zonesConfig.isConfigurationSection(path)) {
            return zonesConfig.getConfigurationSection(path);
        }
        return plugin.getConfig().getConfigurationSection("zone_effects." + effectType);
    }

    public RTPZone getZone(String zoneId) {
        return zones.get(zoneId.toLowerCase());
    }

    public void shutdownAllTasks() {
        activeZoneTasks.values().forEach(CancellableTask::cancel);
        activeZoneTasks.clear();
        if (hologramHealerTask != null && !hologramHealerTask.isCancelled()) {
            hologramHealerTask.cancel();
        }
        if (plugin.getHologramManager() != null) {
            plugin.getHologramManager().cleanupAllHolograms();
        }
    }

    public void toggleIgnore(Player player) {
        if (ignoringPlayers.contains(player.getUniqueId())) {
            ignoringPlayers.remove(player.getUniqueId());
            plugin.getLocaleManager().sendMessage(player, "zone.command.ignore_disabled");
        } else {
            ignoringPlayers.add(player.getUniqueId());
            plugin.getLocaleManager().sendMessage(player, "zone.command.ignore_enabled");
        }
    }

    public boolean isIgnoring(Player player) {
        return ignoringPlayers.contains(player.getUniqueId());
    }

    public void saveZone(RTPZone zone) {
        String zoneId = zone.getId().toLowerCase();
        zones.put(zoneId, zone);

        ConfigurationSection section = zonesConfig.getConfigurationSection("zones." + zone.getId());
        if (section == null) {
            section = zonesConfig.createSection("zones." + zone.getId());
        }
        zone.serialize(section);

        try {
            zonesConfig.save(zonesFile);
            plugin.getRTPLogger().debug("ZONE", "Saved zone to config: " + zone.getId());

        } catch (IOException e) {
            plugin.getLogger().severe("Could not save RTP Zone to file: " + e.getMessage());
            return;
        }

        Location holoLoc = zone.getHologramLocation();
        if (holoLoc != null) {
            int viewDistance = zone.getHologramViewDistance();
            plugin.getRTPLogger().debug("ZONE", "Auto-creating hologram for zone: " + zone.getId() + " at " + holoLoc);

            plugin.getHologramManager().createOrUpdateHologram(zone.getId(), holoLoc, viewDistance);

            plugin.getFoliaScheduler().runLater(() -> {
                plugin.getHologramManager().updateHologramTime(zone.getId(), zone.getInterval());
            }, 2L);
        }

        startZoneScheduler(zone);

        plugin.getRTPLogger().debug("ZONE", "Zone fully initialized with hologram and scheduler: " + zone.getId());

    }

    public void setHologramForZone(Player player, String zoneId, Location location, int viewDistance) {
        RTPZone zone = getZone(zoneId);
        if (zone == null) {
            return;
        }
        zone.setHologramData(location, viewDistance);
        saveZone(zone);
        startZoneScheduler(zone);
    }

    public void deleteHologramForZone(Player player, String zoneId) {
        RTPZone zone = getZone(zoneId);
        if (zone == null) {
            plugin.getLocaleManager().sendMessage(player, "zone.error.not_found", Placeholder.unparsed("id", zoneId));
            return;
        }
        if (zone.getHologramLocation() == null) {
            plugin.getLocaleManager().sendMessage(player, "zone.command.hologram_not_found",
                    Placeholder.unparsed("id", zoneId));
            return;
        }
        zone.setHologramData(null, 0);
        saveZone(zone);
        plugin.getHologramManager().removeHologram(zoneId);
        plugin.getLocaleManager().sendMessage(player, "zone.command.delhologram_success",
                Placeholder.unparsed("id", zoneId));
    }

    public void deleteZone(Player player, String zoneId) {
        String lowerId = zoneId.toLowerCase();
        if (!zones.containsKey(lowerId)) {
            plugin.getLocaleManager().sendMessage(player, "zone.error.not_found", Placeholder.unparsed("id", zoneId));
            return;
        }

        plugin.getRTPLogger().debug("ZONE", "Starting zone deletion process for: " + zoneId);

        CancellableTask task = activeZoneTasks.remove(lowerId);
        if (task != null) {
            task.cancel();
            plugin.getRTPLogger().debug("ZONE", "✓ Stopped zone scheduler for: " + zoneId);

        }

        Set<UUID> playersInZone = zonePlayersMap.remove(lowerId);
        if (playersInZone != null && !playersInZone.isEmpty()) {
            playersInZone.forEach(uuid -> playerZoneMap.remove(uuid));
            plugin.getRTPLogger().debug("ZONE", "✓ Cleared " + playersInZone.size() + " players from zone: " + zoneId);

        }

        zoneCountdowns.remove(lowerId);
        plugin.getRTPLogger().debug("ZONE", "✓ Removed countdown tracking for: " + zoneId);

        plugin.getHologramManager().removeHologram(zoneId);
        plugin.getRTPLogger().debug("ZONE", "✓ Removed hologram for zone: " + zoneId);

        zones.remove(lowerId);
        plugin.getRTPLogger().debug("ZONE", "✓ Removed zone from memory: " + zoneId);

        zonesConfig.set("zones." + zoneId, null);

        try {
            zonesConfig.save(zonesFile);
            plugin.getLocaleManager().sendMessage(player, "zone.command.delete_success",
                    Placeholder.unparsed("id", zoneId));
            plugin.getLogger().info(
                    "Successfully deleted RTP Zone '" + zoneId + "' (scheduler, players, countdown, hologram, config)");
        } catch (IOException e) {
            plugin.getLogger().severe("Could not delete RTP Zone from file: " + e.getMessage());
            plugin.getLocaleManager().sendMessage(player, "zone.error.save_failed");
        }
    }

    public void listZones(Player player) {
        if (zones.isEmpty()) {
            plugin.getLocaleManager().sendMessage(player, "zone.command.list_empty");
            return;
        }
        MiniMessage mm = MiniMessage.miniMessage();
        plugin.getLocaleManager().sendMessage(player, "zone.command.list_header");
        for (RTPZone zone : zones.values()) {
            player.sendMessage(mm.deserialize(
                    plugin.getLocaleManager().getRawMessage("zone.command.list_format"),
                    Placeholder.unparsed("id", zone.getId()),
                    Placeholder.unparsed("target", String.join(", ", zone.getTargets()))));
        }
    }

    public int getZoneCountdown(String zoneId) {
        return zoneCountdowns.getOrDefault(zoneId.toLowerCase(), -1);
    }

    public String getPlayerZone(Player player) {
        return playerZoneMap.get(player.getUniqueId());
    }

    public Collection<RTPZone> getAllZones() {
        return zones.values();
    }

    public boolean zoneExists(String id) {
        return zones.containsKey(id.toLowerCase());
    }

    public Set<String> getZoneIds() {
        return zones.keySet();
    }

    private void findLocationsSequentially(
            List<Player> players,
            int currentIndex,
            Location firstLocation,
            List<Location> foundLocations,
            RTPZone zone,
            int maxAttempts,
            CompletableFuture<List<Location>> finalFuture) {

        if (currentIndex >= players.size()) {
            plugin.getLogger().info(String.format(
                    "[ZoneTP-Sequential] ✓ All %d/%d locations found successfully",
                    foundLocations.size(), players.size()));
            finalFuture.complete(foundLocations);
            return;
        }

        Player player = players.get(currentIndex);

        if (player == null || !player.isOnline()) {
            plugin.getLogger().warning(String.format(
                    "[ZoneTP-Sequential] [%d/%d] Player %s is offline, skipping",
                    currentIndex + 1, players.size(), player != null ? player.getName() : "NULL"));
            findLocationsSequentially(players, currentIndex + 1, firstLocation, foundLocations, zone, maxAttempts,
                    finalFuture);
            return;
        }

        plugin.getLogger().info(String.format(
                "[ZoneTP-Sequential] [%d/%d] Starting search for %s (foundLocations size: %d)",
                currentIndex + 1, players.size(), player.getName(), foundLocations.size()));

        findSafeLocationNearby(firstLocation, zone, foundLocations, players.size(), currentIndex, maxAttempts)
                .thenAccept(location -> {
                    if (location != null) {
                        synchronized (foundLocations) {
                            foundLocations.add(location);
                        }
                        plugin.getLogger().info(String.format(
                                "[ZoneTP-Sequential] [%d/%d] ✓ Found location for %s at (%.1f, %.1f, %.1f) - foundLocations now has %d entries",
                                currentIndex + 1, players.size(), player.getName(),
                                location.getX(), location.getY(), location.getZ(), foundLocations.size()));

                        findLocationsSequentially(players, currentIndex + 1, firstLocation, foundLocations, zone,
                                maxAttempts, finalFuture);
                    } else {
                        plugin.getLogger().warning(String.format(
                                "[ZoneTP-Sequential] [%d/%d] ✗ Failed to find location for %s after %d attempts",
                                currentIndex + 1, players.size(), player.getName(), maxAttempts));

                        synchronized (foundLocations) {
                            foundLocations.add(null);
                        }

                        plugin.getFoliaScheduler().runAtEntity(player, () -> {
                            plugin.getLocaleManager().sendMessage(player, "teleport.no_location_found");
                            plugin.getLocaleManager().sendMessage(player, "zone.teleport_failed");
                        });

                        findLocationsSequentially(players, currentIndex + 1, firstLocation, foundLocations, zone,
                                maxAttempts, finalFuture);
                    }
                })
                .exceptionally(ex -> {
                    plugin.getLogger().severe(String.format(
                            "[ZoneTP-Sequential] [%d/%d] Exception for %s: %s",
                            currentIndex + 1, players.size(), player.getName(), ex.getMessage()));
                    ex.printStackTrace();

                    synchronized (foundLocations) {
                        foundLocations.add(null);
                    }

                    findLocationsSequentially(players, currentIndex + 1, firstLocation, foundLocations, zone,
                            maxAttempts, finalFuture);
                    return null;
                });
    }

    private void performGroupTeleportation(
            List<Player> players,
            List<Location> locations,
            RTPZone zone,
            String teleportReason) {

        plugin.getLogger().info(String.format(
                "[ZoneTP-Execute] Starting teleportation for %d players to zone '%s'",
                players.size(), zone.getId()));

        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failCount = new java.util.concurrent.atomic.AtomicInteger(0);

        performGroupTeleportationAsync(players, locations, zone, 0, successCount, failCount);
    }

    private void performGroupTeleportationAsync(
            List<Player> players,
            List<Location> locations,
            RTPZone zone,
            int currentIndex,
            java.util.concurrent.atomic.AtomicInteger successCount,
            java.util.concurrent.atomic.AtomicInteger failCount) {

        if (currentIndex >= players.size()) {
            int finalSuccess = successCount.get();
            int finalFail = failCount.get();

            plugin.getLogger().info("╔════════════════════════════════════════════════════════════╗");
            plugin.getLogger().info("║  ZONE TELEPORT COMPLETE: " + zone.getId() + "                  ║");
            plugin.getLogger().info("║  Total Players: " + players.size() + "                         ║");
            plugin.getLogger().info("║  Successful: " + finalSuccess + "                              ║");
            plugin.getLogger().info("║  Failed: " + finalFail + "                                     ║");
            plugin.getLogger().info("╚════════════════════════════════════════════════════════════╝");

            if (finalFail > 0) {
                plugin.getLogger().warning("[ZONE RTP] Zone " + zone.getId() + " had " + finalFail +
                        " failed teleports. Check world configuration and zone radius settings.");
            }
            return;
        }

        Player player = players.get(currentIndex);
        Location location = locations.get(currentIndex);
        final int playerIndex = currentIndex;
        final int totalPlayers = players.size();

        if (player == null || !player.isOnline()) {
            plugin.getLogger().warning(String.format(
                    "[ZoneTP-Execute] [%d/%d] Player %s is offline, skipping",
                    playerIndex + 1, totalPlayers, player != null ? player.getName() : "NULL"));
            failCount.incrementAndGet();
            performGroupTeleportationAsync(players, locations, zone, currentIndex + 1, successCount, failCount);
            return;
        }

        if (location == null) {
            plugin.getLogger().warning(String.format(
                    "[ZoneTP-Execute] [%d/%d] No location for %s, skipping",
                    playerIndex + 1, totalPlayers, player.getName()));
            failCount.incrementAndGet();
            performGroupTeleportationAsync(players, locations, zone, currentIndex + 1, successCount, failCount);
            return;
        }

        plugin.getLogger().info(String.format(
                "[ZoneTP-Execute] [%d/%d] Teleporting %s to (%.1f, %.1f, %.1f)",
                playerIndex + 1, totalPlayers, player.getName(),
                location.getX(), location.getY(), location.getZ()));

        SafetyValidator.isLocationAbsolutelySafeAsync(location)
                .thenAccept(safe -> {
                    if (!safe) {
                        String reason = SafetyValidator.getUnsafeReason(location);
                        plugin.getLogger().severe("╔════════════════════════════════════════════════════════════╗");
                        plugin.getLogger().severe("║  ZONE TELEPORT SAFETY VALIDATOR BLOCKED UNSAFE LOCATION!  ║");
                        plugin.getLogger().severe("║  Player: " + player.getName() + "                         ║");
                        plugin.getLogger().severe("║  World: " + location.getWorld().getName() + " ("
                                + location.getWorld().getEnvironment() + ")  ║");
                        plugin.getLogger()
                                .severe("║  Location: " + location.getBlockX() + "," + location.getBlockY() + ","
                                        + location.getBlockZ() + "  ║");
                        plugin.getLogger().severe("║  Reason: " + reason + "                                   ║");
                        plugin.getLogger().severe("║  THIS IS A CRITICAL SAFETY FAILURE - PLEASE REPORT!       ║");
                        plugin.getLogger().severe("╚════════════════════════════════════════════════════════════╝");
                        failCount.incrementAndGet();
                        plugin.getFoliaScheduler().runAtEntity(player, () -> {
                            plugin.getLocaleManager().sendMessage(player, "teleport.no_location_found");
                            plugin.getLocaleManager().sendMessage(player, "zone.teleport_failed");
                        });
                        performGroupTeleportationAsync(players, locations, zone, currentIndex + 1, successCount,
                                failCount);
                        return;
                    }

                    World.Environment env = location.getWorld().getEnvironment();
                    double y = location.getY();

                    if (env == World.Environment.NETHER) {
                        if (y >= 126.0 || (y + 1.0) >= 127.0) {
                            plugin.getLogger().severe("╔════════════════════════════════════════════════════════════╗");
                            plugin.getLogger().severe("║  EMERGENCY: NETHER ROOF SPAWN BLOCKED IN ZONE!            ║");
                            plugin.getLogger().severe("║  Player: " + player.getName() + "                         ║");
                            plugin.getLogger()
                                    .severe("║  Location: Y=" + y + " (head at Y=" + (y + 1) + ")          ║");
                            plugin.getLogger().severe("║  This should NEVER happen - RTPService failed!             ║");
                            plugin.getLogger().severe("╚════════════════════════════════════════════════════════════╝");
                            failCount.incrementAndGet();
                            plugin.getFoliaScheduler().runAtEntity(player, () -> {
                                plugin.getLocaleManager().sendMessage(player, "teleport.no_location_found");
                                plugin.getLocaleManager().sendMessage(player, "zone.teleport_failed");
                            });
                            performGroupTeleportationAsync(players, locations, zone, currentIndex + 1, successCount,
                                    failCount);
                            return;
                        }
                        plugin.getLogger()
                                .info("[ZONE RTP - NETHER SAFE] ✓ Verified Y=" + y + " < 126 (head at Y=" + (y + 1)
                                        + ") for " + player.getName());
                    } else if (env == World.Environment.THE_END) {
                        if (y < 10 || y > 120) {
                            plugin.getLogger()
                                    .severe("[ZONE RTP - END SAFETY] Rejected Y=" + y + " (out of range 10-120) for "
                                            + player.getName());
                            failCount.incrementAndGet();
                            plugin.getFoliaScheduler().runAtEntity(player, () -> {
                                plugin.getLocaleManager().sendMessage(player, "teleport.no_location_found");
                                plugin.getLocaleManager().sendMessage(player, "zone.teleport_failed");
                            });
                            performGroupTeleportationAsync(players, locations, zone, currentIndex + 1, successCount,
                                    failCount);
                            return;
                        }
                        plugin.getLogger()
                                .info("[ZONE RTP - END SAFE] ✓ Verified Y=" + y + " (range 10-120) for "
                                        + player.getName());
                    }

                    successCount.incrementAndGet();

                    plugin.getFoliaScheduler().runAtEntity(player, () -> {
                        player.clearTitle();
                        plugin.getEffectsManager().clearActionBar(player);

                        plugin.getEffectsManager().applyEffects(player, getZoneEffects(zone, "teleport"));

                        plugin.getRtpService().teleportPlayer(player, location);

                        plugin.getLocaleManager().sendMessage(player, "zone.teleport_success");

                        plugin.getLogger().info(String.format(
                                "[ZoneTP-Execute] ✓ [%d/%d] Successfully teleported %s to Y=%.1f",
                                playerIndex + 1, totalPlayers, player.getName(), location.getY()));
                    });

                    performGroupTeleportationAsync(players, locations, zone, currentIndex + 1, successCount, failCount);
                })
                .exceptionally(ex -> {
                    plugin.getLogger()
                            .severe("Error validating location for " + player.getName() + ": " + ex.getMessage());
                    failCount.incrementAndGet();
                    plugin.getFoliaScheduler().runAtEntity(player, () -> {
                        plugin.getLocaleManager().sendMessage(player, "teleport.no_location_found");
                        plugin.getLocaleManager().sendMessage(player, "zone.teleport_failed");
                    });
                    performGroupTeleportationAsync(players, locations, zone, currentIndex + 1, successCount, failCount);
                    return null;
                });
    }

    private String getAvailableWorldsList() {
        return Bukkit.getWorlds().stream()
                .filter(w -> plugin.getRtpService().isRtpEnabled(w))
                .map(World::getName)
                .collect(Collectors.joining(", "));
    }
}