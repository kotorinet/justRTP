package eu.kotori.justRTP.handlers;

import eu.kotori.justRTP.JustRTP;
import eu.kotori.justRTP.commands.RTPCommand;
import eu.kotori.justRTP.utils.FoliaScheduler;
import io.papermc.lib.PaperLib;
import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public class PlayerListener implements Listener {
    private final JustRTP plugin;

    public PlayerListener(JustRTP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        if (plugin.getDelayManager().isDelayed(player.getUniqueId())) {
            if (event.getFrom().distanceSquared(event.getTo()) > 0.001) {
                if (plugin.getConfig().getBoolean("delay_settings.cancel_on_move", true)) {
                    plugin.getDelayManager().cancelDelay(player);
                }
            }
        }

        if (event.hasChangedBlock()) {
            plugin.getRtpZoneManager().handlePlayerMove(player, event.getTo());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (plugin.getConfig().getBoolean("first_join_rtp.enabled") && !player.hasPlayedBefore()) {
            handleFirstJoinRtp(player);
        }

        if (plugin.getConfigManager().getProxyEnabled()) {
            handleProxyJoin(player);
        }

        plugin.getFoliaScheduler().runAtEntityLater(player, () -> {
            if (player.isOnline()) {
                plugin.getRtpZoneManager().handlePlayerMove(player, player.getLocation());
            }
        }, 20L);
    }

    private void handleProxyJoin(Player player) {
        if (plugin.getDatabaseManager() == null || !plugin.getDatabaseManager().isConnected())
            return;
        plugin.getRTPLogger().debug("JOIN",
                "Player " + player.getName() + " joined. Checking for pending proxy RTP requests.");

        plugin.getDatabaseManager().getTeleportRequest(player.getUniqueId()).thenAccept(requestOpt -> {
            requestOpt.ifPresent(request -> {
                String status = request.status();
                plugin.getRTPLogger().debug("JOIN",
                        "Found request for " + player.getName() + " with status: " + status + " and type: "
                                + request.requestType());

                if (("COMPLETE".equals(status) || "IN_TRANSFER".equals(status)) && request.location() != null) {
                    if (!player.isOnline()) {
                        plugin.getRTPLogger().debug("JOIN",
                                "Player " + player.getName() + " went offline before teleport, removing request");
                        plugin.getDatabaseManager().removeTeleportRequest(player.getUniqueId());
                        return;
                    }

                    plugin.getDatabaseManager().confirmTeleportTransfer(player.getUniqueId(), request.targetServer())
                            .thenRun(() -> {
                                plugin.getRTPLogger().debug("JOIN",
                                        "Transfer confirmed for " + player.getName() + ", proceeding with teleport");

                                CompletableFuture<Location> locationFuture;
                                if (request.requestType().startsWith("GROUP")) {
                                    plugin.getRTPLogger().debug("JOIN",
                                            "Player is part of a group teleport. Spreading around target.");
                                    locationFuture = getSpreadLocation(request.location());
                                } else {
                                    locationFuture = CompletableFuture.completedFuture(request.location());
                                }

                                locationFuture.thenAccept(targetLocation -> {
                                    if (targetLocation != null && player.isOnline()) {
                                        plugin.getRTPLogger().debug("JOIN",
                                                "Teleporting " + player.getName() + " to cross-server location: "
                                                        + targetLocation);

                                        if (targetLocation.getWorld() == null) {
                                            plugin.getLogger().severe("Target world is null for " + player.getName()
                                                    + ", cannot teleport");
                                            plugin.getDatabaseManager().removeTeleportRequest(player.getUniqueId());
                                            return;
                                        }

                                        if (targetLocation.getWorld().getEnvironment() == World.Environment.NETHER) {
                                            double y = targetLocation.getY();
                                            if (y >= 126.0) {
                                                plugin.getLogger().severe(
                                                        "╔════════════════════════════════════════════════════════════╗");
                                                plugin.getLogger().severe(
                                                        "║  EMERGENCY: Cross-server nether Y >= 126 BLOCKED!         ║");
                                                plugin.getLogger().severe("║  Player: " + player.getName()
                                                        + "                          ║");
                                                plugin.getLogger().severe("║  Location: Y=" + y + " (head at Y="
                                                        + (y + 1) + ")          ║");
                                                plugin.getLogger().severe("║  Type: " + request.requestType()
                                                        + "                       ║");
                                                plugin.getLogger().severe(
                                                        "║  This should NEVER happen - all layers failed!            ║");
                                                plugin.getLogger().severe(
                                                        "╚════════════════════════════════════════════════════════════╝");

                                                if (player.isOnline()) {
                                                    plugin.getLocaleManager().sendMessage(player, "teleport.failed");
                                                }
                                                plugin.getDatabaseManager().removeTeleportRequest(player.getUniqueId());
                                                return;
                                            }
                                            plugin.getRTPLogger().debug("JOIN",
                                                    "[CROSS-SERVER NETHER FINAL] ✓ Verified Y=" + y + " < 126 for "
                                                            + player.getName());
                                        }

                                        preloadChunksAroundLocation(targetLocation).thenRun(() -> {
                                            if (!player.isOnline()) {
                                                plugin.getDatabaseManager().removeTeleportRequest(player.getUniqueId());
                                                return;
                                            }

                                            plugin.getFoliaScheduler().runAtEntity(player, () -> {
                                                if (!player.isOnline()) {
                                                    plugin.getDatabaseManager()
                                                            .removeTeleportRequest(player.getUniqueId());
                                                    return;
                                                }

                                                PaperLib.teleportAsync(player, targetLocation).thenAccept(success -> {
                                                    if (success && player.isOnline()) {
                                                        plugin.getRTPLogger().debug("JOIN",
                                                                "Successfully teleported " + player.getName()
                                                                        + " to cross-server location");

                                                        plugin.getFoliaScheduler().runAtEntity(player, () -> {
                                                            try {
                                                                if (player.isOnline()) {
                                                                    if (FoliaScheduler.isFolia()) {
                                                                        plugin.getFoliaScheduler()
                                                                                .runAtEntityLater(player, () -> {
                                                                                    if (player.isOnline()) {
                                                                                        refreshPlayerChunks(player,
                                                                                                targetLocation);
                                                                                    }
                                                                                }, 5L);
                                                                    }
                                                                    plugin.getEffectsManager()
                                                                            .applyPostTeleportEffects(player);
                                                                    plugin.getLocaleManager().sendMessage(player,
                                                                            "teleport.success");
                                                                }
                                                            } catch (Exception e) {
                                                                plugin.getLogger().warning(
                                                                        "Error applying post-teleport effects for "
                                                                                + player.getName() + ": "
                                                                                + e.getMessage());
                                                            }
                                                        });
                                                    } else if (!success) {
                                                        plugin.getLogger().warning("Failed to teleport "
                                                                + player.getName() + " to cross-server location");
                                                        if (player.isOnline()) {
                                                            plugin.getLocaleManager().sendMessage(player,
                                                                    "teleport.failed");
                                                        }
                                                    }

                                                    plugin.getDatabaseManager()
                                                            .removeTeleportRequest(player.getUniqueId());
                                                }).exceptionally(throwable -> {
                                                    plugin.getLogger().severe("Exception during teleport for "
                                                            + player.getName() + ": " + throwable.getMessage());
                                                    if (player.isOnline()) {
                                                        plugin.getLocaleManager().sendMessage(player,
                                                                "teleport.failed");
                                                    }
                                                    plugin.getDatabaseManager()
                                                            .removeTeleportRequest(player.getUniqueId());
                                                    return null;
                                                });
                                            });
                                        });
                                    } else {
                                        plugin.getRTPLogger().debug("JOIN",
                                                "Target location invalid or player offline, removing request");
                                        plugin.getDatabaseManager().removeTeleportRequest(player.getUniqueId());
                                    }
                                }).exceptionally(throwable -> {
                                    plugin.getLogger().severe("Error getting spread location for " + player.getName()
                                            + ": " + throwable.getMessage());
                                    plugin.getDatabaseManager().removeTeleportRequest(player.getUniqueId());
                                    return null;
                                });
                            })
                            .exceptionally(throwable -> {
                                plugin.getLogger().severe("Failed to confirm transfer for " + player.getName() + ": "
                                        + throwable.getMessage());
                                plugin.getDatabaseManager().removeTeleportRequest(player.getUniqueId());
                                return null;
                            });

                } else if ("PENDING".equals(status) || "PROCESSING".equals(status)) {
                    plugin.getRTPLogger().debug("JOIN",
                            "Request for " + player.getName() + " is still " + status + ", keeping it for processing");

                } else if ("FAILED".equals(status)) {
                    plugin.getRTPLogger().debug("JOIN",
                            "Request for " + player.getName() + " has FAILED status, removing");
                    plugin.getDatabaseManager().removeTeleportRequest(player.getUniqueId());

                } else {
                    plugin.getLogger()
                            .warning("Unknown request status '" + status + "' for " + player.getName() + ", removing");
                    plugin.getDatabaseManager().removeTeleportRequest(player.getUniqueId());
                }
            });
        });
    }

    private CompletableFuture<Location> getSpreadLocation(Location center) {
        CompletableFuture<Location> future = new CompletableFuture<>();
        double minSpread = plugin.getConfig().getDouble("zone_teleport_settings.min_spread_distance", 5);
        double maxSpread = plugin.getConfig().getDouble("zone_teleport_settings.max_spread_distance", 15);
        double angle = ThreadLocalRandom.current().nextDouble(2 * Math.PI);
        double spread = ThreadLocalRandom.current().nextDouble(minSpread, maxSpread);
        double offsetX = spread * Math.cos(angle);
        double offsetZ = spread * Math.sin(angle);

        Location target = center.clone().add(offsetX, 0, offsetZ);

        plugin.getFoliaScheduler().runAtLocation(target, () -> {
            World targetWorld = target.getWorld();
            if (targetWorld != null) {
                if (targetWorld.getEnvironment() == World.Environment.NETHER) {
                    Location finalLoc = target.clone();
                    finalLoc.add(0.5, 0, 0.5);

                    if (finalLoc.getY() >= 126.0) {
                        plugin.getLogger().severe("╔════════════════════════════════════════════════════════════╗");
                        plugin.getLogger().severe("║  CRITICAL: Cross-server spread Y >= 126 in NETHER!        ║");
                        plugin.getLogger().severe("║  Center Y: " + center.getY() + ", Spread Y: " + finalLoc.getY()
                                + "                    ║");
                        plugin.getLogger().severe("║  Using center location instead of spread!                 ║");
                        plugin.getLogger().severe("╚════════════════════════════════════════════════════════════╝");
                        future.complete(center);
                        return;
                    }

                    plugin.getRTPLogger().debug("JOIN",
                            "[CROSS-SERVER NETHER] Spread location: Y=" + finalLoc.getY() + " (kept from center Y="
                                    + center.getY() + ")");
                    future.complete(finalLoc);
                } else {
                    Location finalLoc = targetWorld.getHighestBlockAt(target).getLocation().add(0.5, 1.5, 0.5);
                    plugin.getRTPLogger().debug("JOIN", "[CROSS-SERVER NORMAL] Spread location: Y=" + finalLoc.getY());
                    future.complete(finalLoc);
                }
            } else {
                plugin.getLogger().warning("[CROSS-SERVER] Target world is null, using center location");
                future.complete(center);
            }
        });

        return future;
    }

    private void handleFirstJoinRtp(Player player) {
        plugin.getFoliaScheduler().runLater(() -> {
            if (!player.isOnline())
                return;
            String worldName = plugin.getConfig().getString("first_join_rtp.target_world");
            World world = plugin.getServer().getWorld(worldName);
            if (world != null) {
                plugin.getTeleportQueueManager().requestTeleport(player, world, java.util.Optional.empty(),
                        java.util.Optional.empty());
            } else {
                plugin.getLogger().warning("Invalid world specified for first_join_rtp: " + worldName);
            }
        }, 20L);
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!plugin.getConfig().getBoolean("delay_settings.cancel_on_combat", true))
            return;
        if (event.getEntity() instanceof Player victim && plugin.getDelayManager().isDelayed(victim.getUniqueId())) {
            plugin.getDelayManager().cancelDelay(victim);
        }
        if (event instanceof EntityDamageByEntityEvent damageByEntityEvent) {
            if (damageByEntityEvent.getDamager() instanceof Player damager
                    && plugin.getDelayManager().isDelayed(damager.getUniqueId())) {
                plugin.getDelayManager().cancelDelay(damager);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.getRTPLogger().debug("QUIT", "Player " + player.getName() + " disconnecting, cleaning up resources");

        plugin.getDelayManager().cancelDelay(player);
        plugin.getEffectsManager().removeBossBar(player);
        plugin.getZoneSetupManager().cancelSetup(player);
        plugin.getRtpZoneManager().handlePlayerQuit(player);
        plugin.getMatchmakingManager().handlePlayerQuit(player);
        plugin.getCrossServerManager().cancelQueueTimer(player.getUniqueId());

        handlePlayerDisconnect(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerKick(PlayerKickEvent event) {
        Player player = event.getPlayer();
        String reason = event.reason() != null ? PlainTextComponentSerializer.plainText().serialize(event.reason())
                : "Unknown";
        plugin.getRTPLogger().debug("QUIT",
                "Player " + player.getName() + " kicked (" + reason + "), cleaning up resources");

        plugin.getDelayManager().cancelDelay(player);
        plugin.getEffectsManager().removeBossBar(player);
        plugin.getZoneSetupManager().cancelSetup(player);
        plugin.getRtpZoneManager().handlePlayerQuit(player);
        plugin.getMatchmakingManager().handlePlayerQuit(player);
        plugin.getCrossServerManager().cancelQueueTimer(player.getUniqueId());

        handlePlayerDisconnect(player);
    }

    private void handlePlayerDisconnect(Player player) {
        if (!plugin.getConfigManager().getProxyEnabled())
            return;
        if (plugin.getDatabaseManager() == null || !plugin.getDatabaseManager().isConnected())
            return;

        plugin.getDatabaseManager().getTeleportRequest(player.getUniqueId()).thenAccept(requestOpt -> {
            requestOpt.ifPresent(request -> {
                String status = request.status();
                plugin.getRTPLogger().debug("QUIT",
                        "Player " + player.getName() + " disconnected with request status: " + status);

                if ("IN_TRANSFER".equals(status)) {
                    plugin.getRTPLogger().debug("QUIT", "Player " + player.getName()
                            + " disconnected during IN_TRANSFER (expected - being sent to target server)");
                    plugin.getRTPLogger().debug("QUIT",
                            "Transfer will be confirmed when player joins target server, or marked FAILED by cleanup if stuck >3min");

                } else if ("PENDING".equals(status) || "PROCESSING".equals(status)) {
                    plugin.getRTPLogger().debug("QUIT",
                            "Player disconnected with " + status + " request, cleanup will handle it");

                } else if ("COMPLETE".equals(status)) {
                    plugin.getRTPLogger().debug("QUIT", "Player " + player.getName()
                            + " disconnected with COMPLETE status before transfer started, marking as FAILED");
                    plugin.getDatabaseManager().failTeleportRequest(player.getUniqueId());
                }
            });
        }).exceptionally(throwable -> {
            plugin.getLogger()
                    .severe("Error checking disconnect status for " + player.getName() + ": " + throwable.getMessage());
            return null;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPostRespawn(PlayerPostRespawnEvent event) {
        if (!plugin.getConfig().getBoolean("respawn_rtp.enabled", false))
            return;
        Player player = event.getPlayer();
        World world = player.getWorld();
        List<String> enabledWorlds = plugin.getConfig().getStringList("respawn_rtp.worlds");

        if (enabledWorlds.isEmpty() || enabledWorlds.contains(world.getName())) {
            plugin.getFoliaScheduler().runAtEntityLater(player, () -> {
                if (!player.isOnline()) return;
                RTPCommand rtpCommand = (RTPCommand) plugin.getCommand("justrtp").getExecutor();
                boolean bypassDelay = plugin.getConfig().getBoolean("respawn_rtp.bypass_delay", true);
                rtpCommand.processRtpRequest(player, player, new String[0], bypassDelay);
            }, 2L);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (plugin.getZoneSetupManager().isWand(event.getItem())) {
            event.setCancelled(true);
            plugin.getZoneSetupManager().handleWandInteraction(event);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        ItemStack item = event.getCurrentItem();
        if (plugin.getZoneSetupManager().isWand(item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!plugin.getZoneSetupManager().isInSetupMode(event.getPlayer())) {
            return;
        }
        try {
            if (plugin.getZoneSetupManager().isWand(event.getItemDrop().getItemStack())) {
                event.setCancelled(true);
            }
        } catch (IllegalStateException ignored) {

            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        plugin.getFoliaScheduler().runAtEntityLater(player, () -> {
            if (!player.isOnline()) return;
            plugin.getHologramManager().refreshHologramsForPlayer(player);
        }, 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        boolean crossWorld = !from.getWorld().equals(to.getWorld());
        boolean longDistance = !crossWorld && from.distanceSquared(to) > 4096;

        if (crossWorld || longDistance) {
            plugin.getFoliaScheduler().runAtEntityLater(player, () -> {
                if (!player.isOnline()) return;
                plugin.getHologramManager().refreshHologramsForPlayer(player);
            }, 10L);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncChatEvent event) {
        if (plugin.getZoneSetupManager().isInSetupMode(event.getPlayer())) {
            event.setCancelled(true);
            String message = PlainTextComponentSerializer.plainText().serialize(event.message());
            plugin.getFoliaScheduler().runAtEntity(event.getPlayer(),
                    () -> plugin.getZoneSetupManager().handleChatInput(event.getPlayer(), message));
        }
    }

    private CompletableFuture<Void> preloadChunksAroundLocation(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }

        int centerChunkX = location.getBlockX() >> 4;
        int centerChunkZ = location.getBlockZ() >> 4;

        int radius = FoliaScheduler.isFolia() ? 2 : 1;
        int gridSize = (radius * 2 + 1) * (radius * 2 + 1);

        CompletableFuture<?>[] chunkFutures = new CompletableFuture[gridSize];
        int index = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;

                chunkFutures[index++] = PaperLib.getChunkAtAsync(world, chunkX, chunkZ, false)
                        .thenApply(chunk -> {
                            if (FoliaScheduler.isFolia() && chunk != null) {
                                try {
                                    chunk.addPluginChunkTicket(plugin);
                                    plugin.getFoliaScheduler().runLater(() -> {
                                        try {
                                            chunk.removePluginChunkTicket(plugin);
                                        } catch (Exception ignored) {
                                        }
                                    }, 40L);
                                } catch (Exception ignored) {
                                }
                            }
                            return chunk;
                        });
            }
        }

        return CompletableFuture.allOf(chunkFutures);
    }

    private void refreshPlayerChunks(Player player, Location location) {
        if (!player.isOnline() || location.getWorld() == null)
            return;

        World world = location.getWorld();
        int centerChunkX = location.getBlockX() >> 4;
        int centerChunkZ = location.getBlockZ() >> 4;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                try {
                    int chunkX = centerChunkX + dx;
                    int chunkZ = centerChunkZ + dz;

                    if (world.isChunkLoaded(chunkX, chunkZ)) {
                        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                        chunk.getEntities();
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }
}