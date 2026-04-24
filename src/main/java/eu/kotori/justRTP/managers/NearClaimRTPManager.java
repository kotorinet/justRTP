package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import eu.kotori.justRTP.utils.FormatUtils;
import io.papermc.lib.PaperLib;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class NearClaimRTPManager {
    private final JustRTP plugin;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<UUID, Long>();
    private ClaimProvider claimProvider;
    private boolean enabled = false;

    public NearClaimRTPManager(JustRTP plugin) {
        this.plugin = plugin;
        this.reload();
    }

    public void reload() {
        this.enabled = this.plugin.getConfig().getBoolean("near_claim_rtp.enabled", false);
        if (!this.enabled) {
            this.plugin.getRTPLogger().debug("NEARCLAIM", "Near Claim RTP feature is disabled");
            return;
        }
        this.detectClaimProvider();
        if (this.claimProvider == null) {
            this.plugin.getRTPLogger().warn("NEARCLAIM", "Near Claim RTP enabled but no supported claim plugin found!");
            this.plugin.getRTPLogger().info("NEARCLAIM", "Supported plugins: SimpleClaim, GriefPrevention, Lands, WorldGuard");
            this.enabled = false;
        } else {
            this.plugin.getRTPLogger().success("NEARCLAIM", "Near Claim RTP enabled with " + this.claimProvider.getName());
        }
    }

    private void detectClaimProvider() {
        Plugin simpleClaim = Bukkit.getPluginManager().getPlugin("SimpleClaim");
        if (simpleClaim != null && simpleClaim.isEnabled()) {
            this.claimProvider = new SimpleClaimProvider(this.plugin);
            return;
        }
        Plugin griefPrevention = Bukkit.getPluginManager().getPlugin("GriefPrevention");
        if (griefPrevention != null && griefPrevention.isEnabled()) {
            this.claimProvider = new GriefPreventionProvider(this.plugin);
            return;
        }
        Plugin lands = Bukkit.getPluginManager().getPlugin("Lands");
        if (lands != null && lands.isEnabled()) {
            this.claimProvider = new LandsProvider(this.plugin);
            return;
        }
        Plugin worldGuard = Bukkit.getPluginManager().getPlugin("WorldGuard");
        if (worldGuard != null && worldGuard.isEnabled()) {
            this.claimProvider = new WorldGuardProvider(this.plugin);
            return;
        }
        this.claimProvider = null;
    }

    public boolean isEnabled() {
        return this.enabled && this.claimProvider != null;
    }

    public void performNearClaimRTP(Player player) {
        long remaining;
        if (!this.isEnabled()) {
            this.plugin.getLocaleManager().sendMessage((CommandSender)player, "near_claim_rtp.feature_disabled", new TagResolver[0]);
            return;
        }
        if (!player.hasPermission("justrtp.command.rtp.nearclaim")) {
            this.plugin.getLocaleManager().sendMessage((CommandSender)player, "command.no_permission", new TagResolver[0]);
            return;
        }
        long now = System.currentTimeMillis();
        if (this.cooldowns.containsKey(player.getUniqueId()) && (remaining = this.cooldowns.get(player.getUniqueId()) - now) > 0L) {
            this.plugin.getLocaleManager().sendMessage((CommandSender)player, "near_claim_rtp.cooldown", Map.of("time", String.valueOf(remaining / 1000L)));
            return;
        }
        World targetWorld = this.getTargetWorld(player);
        if (targetWorld == null) {
            this.plugin.getLocaleManager().sendMessage((CommandSender)player, "near_claim_rtp.no_world", new TagResolver[0]);
            return;
        }
        int minDistance = this.plugin.getConfig().getInt("near_claim_rtp.min_distance", 50);
        int maxDistance = this.plugin.getConfig().getInt("near_claim_rtp.max_distance", 200);
        int maxAttempts = this.plugin.getConfig().getInt("near_claim_rtp.max_attempts", 10);
        boolean excludeSelf = this.plugin.getConfig().getBoolean("near_claim_rtp.exclude_own_claims", true);
        this.plugin.getLocaleManager().sendMessage((CommandSender)player, "near_claim_rtp.searching", new TagResolver[0]);
        this.plugin.getFoliaScheduler().runAsync(() -> {
            List<ClaimInfo> rawClaims = this.claimProvider.getAllClaims(targetWorld);
            if (rawClaims.isEmpty()) {
                this.plugin.getFoliaScheduler().runAtEntity((Entity)player, () -> this.plugin.getLocaleManager().sendMessage((CommandSender)player, "near_claim_rtp.no_claims", new TagResolver[0]));
                return;
            }
            List<ClaimInfo> claims = excludeSelf
                    ? rawClaims.stream().filter(claim -> !claim.getOwnerUUID().equals(player.getUniqueId())).collect(Collectors.toList())
                    : rawClaims;
            if (claims.isEmpty()) {
                this.plugin.getFoliaScheduler().runAtEntity((Entity)player, () -> this.plugin.getLocaleManager().sendMessage((CommandSender)player, "near_claim_rtp.no_other_claims", new TagResolver[0]));
                return;
            }
            ClaimInfo targetClaim = claims.get(ThreadLocalRandom.current().nextInt(claims.size()));
            Location claimCenter = targetClaim.getCenter();
            this.plugin.getRTPLogger().debug("NEARCLAIM", "Selected claim owned by " + targetClaim.getOwnerName() + " at " + String.valueOf(claimCenter));

            tryFindSafeNearClaim(claimCenter, minDistance, maxDistance, maxAttempts).thenAccept(optSafe -> {
                if (optSafe.isEmpty()) {
                    this.plugin.getFoliaScheduler().runAtEntity((Entity)player, () -> this.plugin.getLocaleManager().sendMessage((CommandSender)player, "near_claim_rtp.no_safe_location", new TagResolver[0]));
                    return;
                }
                Location finalLocation = optSafe.get();
                String ownerName = targetClaim.getOwnerName();
                this.plugin.getFoliaScheduler().runAtEntity((Entity)player, () -> {
                    double cost;
                    int cooldownSeconds = this.plugin.getConfig().getInt("near_claim_rtp.cooldown", 60);
                    if (cooldownSeconds > 0) {
                        this.cooldowns.put(player.getUniqueId(), now + (long)cooldownSeconds * 1000L);
                    }
                    if ((cost = this.plugin.getConfig().getDouble("near_claim_rtp.cost", 0.0)) > 0.0 && this.plugin.getVaultHook() != null && this.plugin.getVaultHook().hasEconomy()) {
                        if (this.plugin.getVaultHook().getBalance(player) < cost) {
                            this.plugin.getLocaleManager().sendMessage((CommandSender)player, "economy.insufficient_funds", Map.of("cost", FormatUtils.formatCost(cost)));
                            return;
                        }
                        this.plugin.getVaultHook().withdrawPlayer(player, cost);
                    }
                    player.teleportAsync(finalLocation).thenAccept(success -> {
                        if (success.booleanValue()) {
                            this.plugin.getLocaleManager().sendMessage((CommandSender)player, "near_claim_rtp.success", Map.of("owner", ownerName, "x", String.valueOf(finalLocation.getBlockX()), "y", String.valueOf(finalLocation.getBlockY()), "z", String.valueOf(finalLocation.getBlockZ())));
                            this.plugin.getRTPLogger().debug("NEARCLAIM", player.getName() + " teleported near claim of " + ownerName);
                        } else {
                            this.plugin.getLocaleManager().sendMessage((CommandSender)player, "near_claim_rtp.teleport_failed", new TagResolver[0]);
                        }
                    });
                });
            });
        });
    }

    private CompletableFuture<Optional<Location>> tryFindSafeNearClaim(Location claimCenter, int minDistance, int maxDistance, int attemptsLeft) {
        if (attemptsLeft <= 0) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Location candidate = this.generateLocationNearClaim(claimCenter, minDistance, maxDistance);
        World world = candidate.getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        int chunkX = candidate.getBlockX() >> 4;
        int chunkZ = candidate.getBlockZ() >> 4;
        CompletableFuture<Optional<Location>> stepFuture = new CompletableFuture<>();
        PaperLib.getChunkAtAsync(world, chunkX, chunkZ, true).thenAccept(chunk -> {
            this.plugin.getFoliaScheduler().runAtLocation(candidate, () -> {
                try {
                    candidate.setY((double)(world.getHighestBlockYAt(candidate) + 1));
                    if (this.plugin.getRtpService().isSafeForSpread(candidate)) {
                        stepFuture.complete(Optional.of(candidate));
                    } else {
                        stepFuture.complete(Optional.empty());
                    }
                } catch (Throwable t) {
                    stepFuture.complete(Optional.empty());
                }
            });
        }).exceptionally(ex -> {
            stepFuture.complete(Optional.empty());
            return null;
        });
        return stepFuture.thenCompose(result -> {
            if (result.isPresent()) return CompletableFuture.completedFuture(result);
            return tryFindSafeNearClaim(claimCenter, minDistance, maxDistance, attemptsLeft - 1);
        });
    }

    private World getTargetWorld(Player player) {
        String worldName = this.plugin.getConfig().getString("near_claim_rtp.target_world", "");
        if (worldName.isEmpty()) {
            return player.getWorld();
        }
        World world = Bukkit.getWorld((String)worldName);
        return world != null ? world : player.getWorld();
    }

    private Location generateLocationNearClaim(Location claimCenter, int minDistance, int maxDistance) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble() * 2.0 * Math.PI;
        int distance = minDistance + random.nextInt(Math.max(1, maxDistance - minDistance + 1));
        double offsetX = Math.cos(angle) * (double)distance;
        double offsetZ = Math.sin(angle) * (double)distance;
        Location location = claimCenter.clone();
        location.add(offsetX, 0.0, offsetZ);
        return location;
    }

    private static interface ClaimProvider {
        public String getName();

        public List<ClaimInfo> getAllClaims(World var1);
    }

    private static class SimpleClaimProvider
    implements ClaimProvider {
        private final JustRTP plugin;

        public SimpleClaimProvider(JustRTP plugin) {
            this.plugin = plugin;
        }

        @Override
        public String getName() {
            return "SimpleClaim";
        }

        @Override
        public List<ClaimInfo> getAllClaims(World world) {
            ArrayList<ClaimInfo> claims = new ArrayList<ClaimInfo>();
            try {
                Plugin simpleClaimPlugin = Bukkit.getPluginManager().getPlugin("SimpleClaim");
                if (simpleClaimPlugin == null) {
                    return claims;
                }
                Class<?> apiClass = Class.forName("me.xkuyax.simpleclaim.api.SimpleClaimAPI");
                Method getInstanceMethod = apiClass.getMethod("getInstance", new Class[0]);
                Object apiInstance = getInstanceMethod.invoke(null, new Object[0]);
                Method getAllClaimsMethod = apiClass.getMethod("getAllClaims", new Class[0]);
                Collection allClaims = (Collection)getAllClaimsMethod.invoke(apiInstance, new Object[0]);
                for (Object claim : allClaims) {
                    try {
                        Method getWorldMethod = claim.getClass().getMethod("getWorld", new Class[0]);
                        World claimWorld = (World)getWorldMethod.invoke(claim, new Object[0]);
                        if (!claimWorld.equals((Object)world)) continue;
                        Method getOwnerMethod = claim.getClass().getMethod("getOwner", new Class[0]);
                        UUID ownerUUID = (UUID)getOwnerMethod.invoke(claim, new Object[0]);
                        String ownerName = Bukkit.getOfflinePlayer((UUID)ownerUUID).getName();
                        if (ownerName == null) {
                            ownerName = "Unknown";
                        }
                        Method getCenterMethod = claim.getClass().getMethod("getCenter", new Class[0]);
                        Location center = (Location)getCenterMethod.invoke(claim, new Object[0]);
                        claims.add(new ClaimInfo(ownerUUID, ownerName, center));
                    }
                    catch (Exception e) {
                        this.plugin.getRTPLogger().debug("NEARCLAIM", "Error processing claim: " + e.getMessage());
                    }
                }
            }
            catch (Exception e) {
                this.plugin.getRTPLogger().error("NEARCLAIM", "Error accessing SimpleClaim API: " + e.getMessage());
            }
            return claims;
        }
    }

    private static class GriefPreventionProvider
    implements ClaimProvider {
        private final JustRTP plugin;

        public GriefPreventionProvider(JustRTP plugin) {
            this.plugin = plugin;
        }

        @Override
        public String getName() {
            return "GriefPrevention";
        }

        @Override
        public List<ClaimInfo> getAllClaims(World world) {
            ArrayList<ClaimInfo> claims = new ArrayList<ClaimInfo>();
            try {
                Plugin gpPlugin = Bukkit.getPluginManager().getPlugin("GriefPrevention");
                if (gpPlugin == null) {
                    return claims;
                }
                Class<?> gpClass = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
                Method getInstanceMethod = gpClass.getMethod("instance", new Class[0]);
                Object gpInstance = getInstanceMethod.invoke(null, new Object[0]);
                Class<?> dataStoreClass = Class.forName("me.ryanhamshire.GriefPrevention.DataStore");
                Method getDataStoreMethod = gpClass.getMethod("dataStore", new Class[0]);
                Object dataStore = getDataStoreMethod.invoke(gpInstance, new Object[0]);
                Method getClaimsMethod = dataStoreClass.getMethod("getClaims", new Class[0]);
                Collection allClaims = (Collection)getClaimsMethod.invoke(dataStore, new Object[0]);
                for (Object claim : allClaims) {
                    try {
                        Method getLesserBoundaryCornerMethod = claim.getClass().getMethod("getLesserBoundaryCorner", new Class[0]);
                        Location corner1 = (Location)getLesserBoundaryCornerMethod.invoke(claim, new Object[0]);
                        if (!corner1.getWorld().equals((Object)world)) continue;
                        Method getGreaterBoundaryCornerMethod = claim.getClass().getMethod("getGreaterBoundaryCorner", new Class[0]);
                        Location corner2 = (Location)getGreaterBoundaryCornerMethod.invoke(claim, new Object[0]);
                        Method getOwnerIDMethod = claim.getClass().getMethod("getOwnerID", new Class[0]);
                        UUID ownerUUID = (UUID)getOwnerIDMethod.invoke(claim, new Object[0]);
                        String ownerName = Bukkit.getOfflinePlayer((UUID)ownerUUID).getName();
                        if (ownerName == null) {
                            ownerName = "Unknown";
                        }
                        Location center = new Location(world, (corner1.getX() + corner2.getX()) / 2.0, (corner1.getY() + corner2.getY()) / 2.0, (corner1.getZ() + corner2.getZ()) / 2.0);
                        claims.add(new ClaimInfo(ownerUUID, ownerName, center));
                    }
                    catch (Exception e) {
                        this.plugin.getRTPLogger().debug("NEARCLAIM", "Error processing GP claim: " + e.getMessage());
                    }
                }
            }
            catch (Exception e) {
                this.plugin.getRTPLogger().error("NEARCLAIM", "Error accessing GriefPrevention API: " + e.getMessage());
            }
            return claims;
        }
    }

    private static class LandsProvider
    implements ClaimProvider {
        private final JustRTP plugin;

        public LandsProvider(JustRTP plugin) {
            this.plugin = plugin;
        }

        @Override
        public String getName() {
            return "Lands";
        }

        @Override
        public List<ClaimInfo> getAllClaims(World world) {
            ArrayList<ClaimInfo> claims = new ArrayList<ClaimInfo>();
            try {
                Plugin landsPlugin = Bukkit.getPluginManager().getPlugin("Lands");
                if (landsPlugin == null) {
                    return claims;
                }
                Class<?> apiClass = Class.forName("me.angeschossen.lands.api.integration.LandsIntegration");
                Method getInstanceMethod = apiClass.getMethod("of", Plugin.class);
                Object apiInstance = getInstanceMethod.invoke(null, new Object[]{this.plugin});
                Method getLandsMethod = apiClass.getMethod("getLands", new Class[0]);
                Collection allLands = (Collection)getLandsMethod.invoke(apiInstance, new Object[0]);
                for (Object land : allLands) {
                    try {
                        Method getSpawnMethod;
                        Location center;
                        Method getWorldMethod = land.getClass().getMethod("getWorld", new Class[0]);
                        World landWorld = (World)getWorldMethod.invoke(land, new Object[0]);
                        if (!landWorld.equals((Object)world)) continue;
                        Method getOwnerUIDMethod = land.getClass().getMethod("getOwnerUID", new Class[0]);
                        UUID ownerUUID = (UUID)getOwnerUIDMethod.invoke(land, new Object[0]);
                        String ownerName = Bukkit.getOfflinePlayer((UUID)ownerUUID).getName();
                        if (ownerName == null) {
                            ownerName = "Unknown";
                        }
                        if ((center = (Location)(getSpawnMethod = land.getClass().getMethod("getSpawn", new Class[0])).invoke(land, new Object[0])) == null) continue;
                        claims.add(new ClaimInfo(ownerUUID, ownerName, center));
                    }
                    catch (Exception e) {
                        this.plugin.getRTPLogger().debug("NEARCLAIM", "Error processing Lands claim: " + e.getMessage());
                    }
                }
            }
            catch (Exception e) {
                this.plugin.getRTPLogger().error("NEARCLAIM", "Error accessing Lands API: " + e.getMessage());
            }
            return claims;
        }
    }

    private static class WorldGuardProvider
    implements ClaimProvider {
        private final JustRTP plugin;

        public WorldGuardProvider(JustRTP plugin) {
            this.plugin = plugin;
        }

        @Override
        public String getName() {
            return "WorldGuard";
        }

        @Override
        public List<ClaimInfo> getAllClaims(World world) {
            ArrayList<ClaimInfo> claims = new ArrayList<ClaimInfo>();
            try {
                Plugin wgPlugin = Bukkit.getPluginManager().getPlugin("WorldGuard");
                if (wgPlugin == null) {
                    return claims;
                }
                Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard");
                Method getInstanceMethod = worldGuardClass.getMethod("getInstance", new Class[0]);
                Object worldGuardInstance = getInstanceMethod.invoke(null, new Object[0]);

                Method getPlatformMethod = worldGuardClass.getMethod("getPlatform", new Class[0]);
                Object platform = getPlatformMethod.invoke(worldGuardInstance, new Object[0]);

                Class<?> platformClass = Class.forName("com.sk89q.worldguard.WorldGuard").getSuperclass();
                Method getRegionContainerMethod = platform.getClass().getMethod("getRegionContainer", new Class[0]);
                Object regionContainer = getRegionContainerMethod.invoke(platform, new Object[0]);

                Class<?> bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                Method adaptWorldMethod = bukkitAdapterClass.getMethod("adapt", World.class);
                Object weWorld = adaptWorldMethod.invoke(null, new Object[]{world});

                Method getRegionManagerMethod = regionContainer.getClass().getMethod("get", Class.forName("com.sk89q.worldedit.world.World"));
                Object regionManager = getRegionManagerMethod.invoke(regionContainer, new Object[]{weWorld});

                if (regionManager == null) {
                    return claims;
                }

                Method getRegionsMethod = regionManager.getClass().getMethod("getRegions", new Class[0]);
                Map regions = (Map)getRegionsMethod.invoke(regionManager, new Object[0]);

                for (Object region : regions.values()) {
                    try {
                        Method getOwnersMethod = region.getClass().getMethod("getOwners", new Class[0]);
                        Object owners = getOwnersMethod.invoke(region, new Object[0]);

                        Method getUniqueIdsMethod = owners.getClass().getMethod("getUniqueIds", new Class[0]);
                        Collection ownerUUIDs = (Collection)getUniqueIdsMethod.invoke(owners, new Object[0]);

                        if (ownerUUIDs.isEmpty()) {
                            continue;
                        }

                        UUID ownerUUID = (UUID)ownerUUIDs.iterator().next();
                        String ownerName = Bukkit.getOfflinePlayer(ownerUUID).getName();
                        if (ownerName == null) {
                            ownerName = "Unknown";
                        }

                        Method getMinimumPointMethod = region.getClass().getMethod("getMinimumPoint", new Class[0]);
                        Object minPoint = getMinimumPointMethod.invoke(region, new Object[0]);

                        Method getMaximumPointMethod = region.getClass().getMethod("getMaximumPoint", new Class[0]);
                        Object maxPoint = getMaximumPointMethod.invoke(region, new Object[0]);

                        Method getXMethod = minPoint.getClass().getMethod("getX", new Class[0]);
                        Method getYMethod = minPoint.getClass().getMethod("getY", new Class[0]);
                        Method getZMethod = minPoint.getClass().getMethod("getZ", new Class[0]);

                        double minX = ((Number)getXMethod.invoke(minPoint, new Object[0])).doubleValue();
                        double minY = ((Number)getYMethod.invoke(minPoint, new Object[0])).doubleValue();
                        double minZ = ((Number)getZMethod.invoke(minPoint, new Object[0])).doubleValue();

                        double maxX = ((Number)getXMethod.invoke(maxPoint, new Object[0])).doubleValue();
                        double maxY = ((Number)getYMethod.invoke(maxPoint, new Object[0])).doubleValue();
                        double maxZ = ((Number)getZMethod.invoke(maxPoint, new Object[0])).doubleValue();

                        Location center = new Location(world, (minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
                        claims.add(new ClaimInfo(ownerUUID, ownerName, center));
                    }
                    catch (Exception e) {
                        this.plugin.getRTPLogger().debug("NEARCLAIM", "Error processing WorldGuard region: " + e.getMessage());
                    }
                }
            }
            catch (Exception e) {
                this.plugin.getRTPLogger().error("NEARCLAIM", "Error accessing WorldGuard API: " + e.getMessage());
            }
            return claims;
        }
    }

    private static class ClaimInfo {
        private final UUID ownerUUID;
        private final String ownerName;
        private final Location center;

        public ClaimInfo(UUID ownerUUID, String ownerName, Location center) {
            this.ownerUUID = ownerUUID;
            this.ownerName = ownerName;
            this.center = center;
        }

        public UUID getOwnerUUID() {
            return this.ownerUUID;
        }

        public String getOwnerName() {
            return this.ownerName;
        }

        public Location getCenter() {
            return this.center;
        }
    }
}
