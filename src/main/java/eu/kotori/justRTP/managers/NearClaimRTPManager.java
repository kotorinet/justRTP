package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class NearClaimRTPManager {
    private final JustRTP plugin;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    
    private ClaimProvider claimProvider;
    private boolean enabled = false;
    
    public NearClaimRTPManager(JustRTP plugin) {
        this.plugin = plugin;
        reload();
    }
    
    public void reload() {
        enabled = plugin.getConfig().getBoolean("near_claim_rtp.enabled", false);
        
        if (!enabled) {
            plugin.getRTPLogger().debug("NEARCLAIM", "Near Claim RTP feature is disabled");
            return;
        }
        
        detectClaimProvider();
        
        if (claimProvider == null) {
            plugin.getRTPLogger().warn("NEARCLAIM", "Near Claim RTP enabled but no supported claim plugin found!");
            plugin.getRTPLogger().info("NEARCLAIM", "Supported plugins: SimpleClaim, GriefPrevention, Lands");
            enabled = false;
        } else {
            plugin.getRTPLogger().success("NEARCLAIM", "Near Claim RTP enabled with " + claimProvider.getName());
        }
    }
    
    private void detectClaimProvider() {
        Plugin simpleClaim = Bukkit.getPluginManager().getPlugin("SimpleClaim");
        if (simpleClaim != null && simpleClaim.isEnabled()) {
            claimProvider = new SimpleClaimProvider(plugin);
            return;
        }
        
        Plugin griefPrevention = Bukkit.getPluginManager().getPlugin("GriefPrevention");
        if (griefPrevention != null && griefPrevention.isEnabled()) {
            claimProvider = new GriefPreventionProvider(plugin);
            return;
        }
        
        Plugin lands = Bukkit.getPluginManager().getPlugin("Lands");
        if (lands != null && lands.isEnabled()) {
            claimProvider = new LandsProvider(plugin);
            return;
        }
        
        claimProvider = null;
    }
    
    public boolean isEnabled() {
        return enabled && claimProvider != null;
    }
    
    public void performNearClaimRTP(Player player) {
        if (!isEnabled()) {
            plugin.getLocaleManager().sendMessage(player, "near_claim_rtp.feature_disabled");
            return;
        }
        
        if (!player.hasPermission("justrtp.command.rtp.nearclaim")) {
            plugin.getLocaleManager().sendMessage(player, "command.no_permission");
            return;
        }
        
        long now = System.currentTimeMillis();
        if (cooldowns.containsKey(player.getUniqueId())) {
            long remaining = cooldowns.get(player.getUniqueId()) - now;
            if (remaining > 0) {
                plugin.getLocaleManager().sendMessage(player, "near_claim_rtp.cooldown",
                        Map.of("time", String.valueOf(remaining / 1000)));
                return;
            }
        }
        
        World targetWorld = getTargetWorld(player);
        if (targetWorld == null) {
            plugin.getLocaleManager().sendMessage(player, "near_claim_rtp.no_world");
            return;
        }
        
        int minDistance = plugin.getConfig().getInt("near_claim_rtp.min_distance", 50);
        int maxDistance = plugin.getConfig().getInt("near_claim_rtp.max_distance", 200);
        int maxAttempts = plugin.getConfig().getInt("near_claim_rtp.max_attempts", 10);
        boolean excludeSelf = plugin.getConfig().getBoolean("near_claim_rtp.exclude_own_claims", true);
        
        plugin.getLocaleManager().sendMessage(player, "near_claim_rtp.searching");
        
        plugin.getFoliaScheduler().runAsync(() -> {
            List<ClaimInfo> claims = claimProvider.getAllClaims(targetWorld);
            
            if (claims.isEmpty()) {
                plugin.getFoliaScheduler().runAtEntity(player, () -> {
                    plugin.getLocaleManager().sendMessage(player, "near_claim_rtp.no_claims");
                });
                return;
            }
            
            if (excludeSelf) {
                claims = claims.stream()
                        .filter(claim -> !claim.getOwnerUUID().equals(player.getUniqueId()))
                        .collect(Collectors.toList());
                
                if (claims.isEmpty()) {
                    plugin.getFoliaScheduler().runAtEntity(player, () -> {
                        plugin.getLocaleManager().sendMessage(player, "near_claim_rtp.no_other_claims");
                    });
                    return;
                }
            }
            
            ClaimInfo targetClaim = claims.get(new Random().nextInt(claims.size()));
            Location claimCenter = targetClaim.getCenter();
            
            plugin.getRTPLogger().debug("NEARCLAIM", "Selected claim owned by " + 
                    targetClaim.getOwnerName() + " at " + claimCenter);
            
            Location safeLocation = null;
            for (int attempt = 0; attempt < maxAttempts; attempt++) {
                Location candidate = generateLocationNearClaim(claimCenter, minDistance, maxDistance);
                
                if (plugin.getRtpService().isSafeForSpread(candidate)) {
                    safeLocation = candidate;
                    break;
                }
            }
            
            if (safeLocation == null) {
                plugin.getFoliaScheduler().runAtEntity(player, () -> {
                    plugin.getLocaleManager().sendMessage(player, "near_claim_rtp.no_safe_location");
                });
                return;
            }
            
            Location finalLocation = safeLocation;
            String ownerName = targetClaim.getOwnerName();
            
            plugin.getFoliaScheduler().runAtEntity(player, () -> {
                int cooldownSeconds = plugin.getConfig().getInt("near_claim_rtp.cooldown", 60);
                if (cooldownSeconds > 0) {
                    cooldowns.put(player.getUniqueId(), now + (cooldownSeconds * 1000L));
                }
                
                double cost = plugin.getConfig().getDouble("near_claim_rtp.cost", 0.0);
                if (cost > 0 && plugin.getVaultHook() != null && plugin.getVaultHook().hasEconomy()) {
                    if (plugin.getVaultHook().getBalance(player) < cost) {
                        plugin.getLocaleManager().sendMessage(player, "economy.insufficient_funds",
                                Map.of("cost", String.format("%.2f", cost)));
                        return;
                    }
                    plugin.getVaultHook().withdrawPlayer(player, cost);
                }
                
                player.teleportAsync(finalLocation).thenAccept(success -> {
                    if (success) {
                        plugin.getLocaleManager().sendMessage(player, "near_claim_rtp.success",
                                Map.of("owner", ownerName,
                                       "x", String.valueOf(finalLocation.getBlockX()),
                                       "y", String.valueOf(finalLocation.getBlockY()),
                                       "z", String.valueOf(finalLocation.getBlockZ())));
                        
                        plugin.getRTPLogger().debug("NEARCLAIM", 
                                player.getName() + " teleported near claim of " + ownerName);
                    } else {
                        plugin.getLocaleManager().sendMessage(player, "near_claim_rtp.teleport_failed");
                    }
                });
            });
        });
    }
    
    private World getTargetWorld(Player player) {
        String worldName = plugin.getConfig().getString("near_claim_rtp.target_world", "");
        
        if (worldName.isEmpty()) {
            return player.getWorld();
        }
        
        World world = Bukkit.getWorld(worldName);
        return world != null ? world : player.getWorld();
    }
    
    private Location generateLocationNearClaim(Location claimCenter, int minDistance, int maxDistance) {
        Random random = new Random();
        
        double angle = random.nextDouble() * 2 * Math.PI;
        
        int distance = minDistance + random.nextInt(maxDistance - minDistance + 1);
        
        double offsetX = Math.cos(angle) * distance;
        double offsetZ = Math.sin(angle) * distance;
        
        Location location = claimCenter.clone();
        location.add(offsetX, 0, offsetZ);
        
        location.setY(location.getWorld().getHighestBlockYAt(location) + 1);
        
        return location;
    }
    
    
    private interface ClaimProvider {
        String getName();
        List<ClaimInfo> getAllClaims(World world);
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
            return ownerUUID;
        }
        
        public String getOwnerName() {
            return ownerName;
        }
        
        public Location getCenter() {
            return center;
        }
    }
    
    
    private static class SimpleClaimProvider implements ClaimProvider {
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
            List<ClaimInfo> claims = new ArrayList<>();
            
            try {
                Plugin simpleClaimPlugin = Bukkit.getPluginManager().getPlugin("SimpleClaim");
                if (simpleClaimPlugin == null) return claims;
                
                Class<?> apiClass = Class.forName("me.xkuyax.simpleclaim.api.SimpleClaimAPI");
                Method getInstanceMethod = apiClass.getMethod("getInstance");
                Object apiInstance = getInstanceMethod.invoke(null);
                
                Method getAllClaimsMethod = apiClass.getMethod("getAllClaims");
                Collection<?> allClaims = (Collection<?>) getAllClaimsMethod.invoke(apiInstance);
                
                for (Object claim : allClaims) {
                    try {
                        Method getWorldMethod = claim.getClass().getMethod("getWorld");
                        World claimWorld = (World) getWorldMethod.invoke(claim);
                        
                        if (!claimWorld.equals(world)) continue;
                        
                        Method getOwnerMethod = claim.getClass().getMethod("getOwner");
                        UUID ownerUUID = (UUID) getOwnerMethod.invoke(claim);
                        
                        String ownerName = Bukkit.getOfflinePlayer(ownerUUID).getName();
                        if (ownerName == null) ownerName = "Unknown";
                        
                        Method getCenterMethod = claim.getClass().getMethod("getCenter");
                        Location center = (Location) getCenterMethod.invoke(claim);
                        
                        claims.add(new ClaimInfo(ownerUUID, ownerName, center));
                    } catch (Exception e) {
                        plugin.getRTPLogger().debug("NEARCLAIM", "Error processing claim: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                plugin.getRTPLogger().error("NEARCLAIM", "Error accessing SimpleClaim API: " + e.getMessage());
            }
            
            return claims;
        }
    }
    
    
    private static class GriefPreventionProvider implements ClaimProvider {
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
            List<ClaimInfo> claims = new ArrayList<>();
            
            try {
                Plugin gpPlugin = Bukkit.getPluginManager().getPlugin("GriefPrevention");
                if (gpPlugin == null) return claims;
                
                Class<?> gpClass = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
                Method getInstanceMethod = gpClass.getMethod("instance");
                Object gpInstance = getInstanceMethod.invoke(null);
                
                Class<?> dataStoreClass = Class.forName("me.ryanhamshire.GriefPrevention.DataStore");
                Method getDataStoreMethod = gpClass.getMethod("dataStore");
                Object dataStore = getDataStoreMethod.invoke(gpInstance);
                
                Method getClaimsMethod = dataStoreClass.getMethod("getClaims");
                Collection<?> allClaims = (Collection<?>) getClaimsMethod.invoke(dataStore);
                
                for (Object claim : allClaims) {
                    try {
                        Method getLesserBoundaryCornerMethod = claim.getClass().getMethod("getLesserBoundaryCorner");
                        Location corner1 = (Location) getLesserBoundaryCornerMethod.invoke(claim);
                        
                        if (!corner1.getWorld().equals(world)) continue;
                        
                        Method getGreaterBoundaryCornerMethod = claim.getClass().getMethod("getGreaterBoundaryCorner");
                        Location corner2 = (Location) getGreaterBoundaryCornerMethod.invoke(claim);
                        
                        Method getOwnerIDMethod = claim.getClass().getMethod("getOwnerID");
                        UUID ownerUUID = (UUID) getOwnerIDMethod.invoke(claim);
                        
                        String ownerName = Bukkit.getOfflinePlayer(ownerUUID).getName();
                        if (ownerName == null) ownerName = "Unknown";
                        
                        Location center = new Location(
                                world,
                                (corner1.getX() + corner2.getX()) / 2,
                                (corner1.getY() + corner2.getY()) / 2,
                                (corner1.getZ() + corner2.getZ()) / 2
                        );
                        
                        claims.add(new ClaimInfo(ownerUUID, ownerName, center));
                    } catch (Exception e) {
                        plugin.getRTPLogger().debug("NEARCLAIM", "Error processing GP claim: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                plugin.getRTPLogger().error("NEARCLAIM", "Error accessing GriefPrevention API: " + e.getMessage());
            }
            
            return claims;
        }
    }
    
    
    private static class LandsProvider implements ClaimProvider {
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
            List<ClaimInfo> claims = new ArrayList<>();
            
            try {
                Plugin landsPlugin = Bukkit.getPluginManager().getPlugin("Lands");
                if (landsPlugin == null) return claims;
                
                Class<?> apiClass = Class.forName("me.angeschossen.lands.api.integration.LandsIntegration");
                Method getInstanceMethod = apiClass.getMethod("of", Plugin.class);
                Object apiInstance = getInstanceMethod.invoke(null, plugin);
                
                Method getLandsMethod = apiClass.getMethod("getLands");
                Collection<?> allLands = (Collection<?>) getLandsMethod.invoke(apiInstance);
                
                for (Object land : allLands) {
                    try {
                        Method getWorldMethod = land.getClass().getMethod("getWorld");
                        World landWorld = (World) getWorldMethod.invoke(land);
                        
                        if (!landWorld.equals(world)) continue;
                        
                        Method getOwnerUIDMethod = land.getClass().getMethod("getOwnerUID");
                        UUID ownerUUID = (UUID) getOwnerUIDMethod.invoke(land);
                        
                        String ownerName = Bukkit.getOfflinePlayer(ownerUUID).getName();
                        if (ownerName == null) ownerName = "Unknown";
                        
                        Method getSpawnMethod = land.getClass().getMethod("getSpawn");
                        Location center = (Location) getSpawnMethod.invoke(land);
                        
                        if (center == null) continue;
                        
                        claims.add(new ClaimInfo(ownerUUID, ownerName, center));
                    } catch (Exception e) {
                        plugin.getRTPLogger().debug("NEARCLAIM", "Error processing Lands claim: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                plugin.getRTPLogger().error("NEARCLAIM", "Error accessing Lands API: " + e.getMessage());
            }
            
            return claims;
        }
    }
}
