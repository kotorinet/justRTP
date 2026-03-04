package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.*;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.EulerAngle;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SpectatorSwitchManager implements Listener {
    private final JustRTP plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, SpectatorSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sessionCooldowns = new ConcurrentHashMap<>();

    public SpectatorSwitchManager(JustRTP plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("spectator_switch.enabled", false);
    }

    public void openSpectatorSwitch(Player player) {
        if (!isEnabled()) {
            plugin.getLocaleManager().sendMessage(player, "spectator_switch.feature_disabled");
            return;
        }

        if (!player.hasPermission("justrtp.command.rtp.spectator")) {
            plugin.getLocaleManager().sendMessage(player, "command.no_permission");
            return;
        }

        long cooldown = sessionCooldowns.getOrDefault(player.getUniqueId(), 0L);
        long now = System.currentTimeMillis();
        if (cooldown > now) {
            long remaining = (cooldown - now) / 1000;
            plugin.getLocaleManager().sendMessage(player, "spectator_switch.cooldown",
                    Placeholder.unparsed("time", String.valueOf(remaining)));
            return;
        }

        if (activeSessions.containsKey(player.getUniqueId())) {
            closeSpectatorSwitch(player);
            return;
        }

        ConfigurationSection config = plugin.getConfig().getConfigurationSection("spectator_switch");
        if (config == null) {
            plugin.getRTPLogger().warn("SPECTATOR", "Spectator Switch configuration not found!");
            return;
        }

        SpectatorSession session = new SpectatorSession(player);
        
        Location playerLoc = player.getLocation();
        Location centerLoc = playerLoc.clone().add(0, 3, 0);

        ConfigurationSection worldsSection = config.getConfigurationSection("worlds");
        if (worldsSection == null || worldsSection.getKeys(false).isEmpty()) {
            plugin.getLocaleManager().sendMessage(player, "spectator_switch.no_worlds");
            return;
        }

        List<String> worldKeys = new ArrayList<>(worldsSection.getKeys(false));
        int worldCount = worldKeys.size();
        double radius = config.getDouble("display_radius", 3.0);
        double angleStep = 360.0 / worldCount;

        plugin.getFoliaScheduler().runAtEntity(player, () -> {
            GameMode originalMode = player.getGameMode();
            session.originalGameMode = originalMode;
            session.originalLocation = playerLoc.clone();
            session.originalFlySpeed = player.getFlySpeed();
            session.originalWalkSpeed = player.getWalkSpeed();
            session.originalAllowFlight = player.getAllowFlight();
            session.originalFlying = player.isFlying();
            
            player.setGameMode(GameMode.ADVENTURE);
            
            player.setAllowFlight(true);
            player.setFlying(true);
            
            player.setFlySpeed(0.0f);
            player.setWalkSpeed(0.0f);
            
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 2, false, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 255, false, false, false));
            player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, Integer.MAX_VALUE, 250, false, false, false));

            for (int i = 0; i < worldCount; i++) {
                String worldKey = worldKeys.get(i);
                ConfigurationSection worldConfig = worldsSection.getConfigurationSection(worldKey);
                if (worldConfig == null) continue;

                String worldName = worldConfig.getString("world_name", worldKey);
                World world = Bukkit.getWorld(worldName);
                
                if (world == null || !plugin.getRtpService().isRtpEnabled(world)) {
                    continue;
                }

                if (!player.hasPermission("justrtp.command.rtp.world") && 
                    !player.hasPermission("justrtp.command.rtp.world." + worldName)) {
                    continue;
                }

                double angle = Math.toRadians(i * angleStep);
                double x = centerLoc.getX() + radius * Math.cos(angle);
                double z = centerLoc.getZ() + radius * Math.sin(angle);
                Location headLoc = new Location(centerLoc.getWorld(), x, centerLoc.getY(), z);
                headLoc.setYaw((float) Math.toDegrees(angle + 180));

                ArmorStand stand = (ArmorStand) centerLoc.getWorld().spawnEntity(headLoc, EntityType.ARMOR_STAND);
                stand.setVisible(false);
                stand.setGravity(false);
                stand.setInvulnerable(true);
                stand.setMarker(true);
                stand.setSmall(true);
                stand.setCustomNameVisible(true);
                
                String displayName = worldConfig.getString("display_name", worldName);
                stand.customName(mm.deserialize(displayName));

                String skullTexture = worldConfig.getString("skull_texture", "");
                String skullOwner = worldConfig.getString("skull_owner", "");
                
                ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
                
                if (!skullOwner.isEmpty()) {
                    OfflinePlayer skullPlayer = Bukkit.getOfflinePlayer(skullOwner);
                    skullMeta.setOwningPlayer(skullPlayer);
                } else if (!skullTexture.isEmpty()) {
                }
                
                skull.setItemMeta(skullMeta);
                stand.getEquipment().setHelmet(skull);
                stand.setHeadPose(new EulerAngle(0, 0, 0));

                session.entities.add(stand);
                session.worldMapping.put(stand.getUniqueId(), worldName);
            }

            activeSessions.put(player.getUniqueId(), session);
            
            plugin.getLocaleManager().sendMessage(player, "spectator_switch.opened");
            
            int cooldownSeconds = config.getInt("open_cooldown", 2);
            if (cooldownSeconds > 0) {
                sessionCooldowns.put(player.getUniqueId(), now + (cooldownSeconds * 1000L));
            }

            int autoCloseSeconds = config.getInt("auto_close_seconds", 30);
            if (autoCloseSeconds > 0) {
                plugin.getFoliaScheduler().runLater(() -> {
                    if (activeSessions.containsKey(player.getUniqueId())) {
                        closeSpectatorSwitch(player);
                        plugin.getLocaleManager().sendMessage(player, "spectator_switch.timeout");
                    }
                }, autoCloseSeconds * 20L);
            }

            plugin.getRTPLogger().debug("SPECTATOR", 
                "Opened spectator switch for " + player.getName() + " with " + session.entities.size() + " worlds");
        });
    }

    public void closeSpectatorSwitch(Player player) {
        SpectatorSession session = activeSessions.remove(player.getUniqueId());
        if (session == null) return;

        plugin.getFoliaScheduler().runAtEntity(player, () -> {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
            player.removePotionEffect(PotionEffectType.SLOWNESS);
            player.removePotionEffect(PotionEffectType.JUMP_BOOST);
            
            player.setFlySpeed(session.originalFlySpeed);
            player.setWalkSpeed(session.originalWalkSpeed);
            
            for (ArmorStand entity : session.entities) {
                if (entity != null && entity.isValid()) {
                    entity.remove();
                }
            }

            if (session.originalGameMode != null) {
                player.setGameMode(session.originalGameMode);
            }
            
            player.setAllowFlight(session.originalAllowFlight);
            if (!session.originalFlying && player.isFlying()) {
                player.setFlying(false);
            }

            if (session.originalLocation != null && 
                session.originalLocation.getWorld() != null &&
                player.getWorld().equals(session.originalLocation.getWorld())) {
                player.teleportAsync(session.originalLocation);
            }

            plugin.getRTPLogger().debug("SPECTATOR", "Closed spectator switch for " + player.getName());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityInteract(PlayerInteractAtEntityEvent event) {
        Player player = event.getPlayer();
        SpectatorSession session = activeSessions.get(player.getUniqueId());
        
        if (session == null) return;
        if (!(event.getRightClicked() instanceof ArmorStand)) return;

        ArmorStand stand = (ArmorStand) event.getRightClicked();
        String worldName = session.worldMapping.get(stand.getUniqueId());
        
        if (worldName == null) return;

        event.setCancelled(true);

        World targetWorld = Bukkit.getWorld(worldName);
        if (targetWorld == null) {
            plugin.getLocaleManager().sendMessage(player, "command.world_not_found",
                    Placeholder.unparsed("world", worldName));
            closeSpectatorSwitch(player);
            return;
        }

        closeSpectatorSwitch(player);

        plugin.getFoliaScheduler().runAtEntity(player, () -> {
            plugin.getRTPLogger().debug("SPECTATOR", 
                "Player " + player.getName() + " selected world: " + worldName);
            
            plugin.getServer().dispatchCommand(player, "rtp " + worldName);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        closeSpectatorSwitch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            return;
        }
        
        SpectatorSession session = activeSessions.get(event.getPlayer().getUniqueId());
        if (session != null && event.getTo() != null) {
            if (!event.getTo().getWorld().equals(session.originalLocation.getWorld())) {
                closeSpectatorSwitch(event.getPlayer());
            }
        }
    }

    public void reload() {
        for (UUID uuid : new HashSet<>(activeSessions.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                closeSpectatorSwitch(player);
            }
        }
        activeSessions.clear();
        sessionCooldowns.clear();
        plugin.getRTPLogger().info("SPECTATOR", "Spectator Switch Manager reloaded");
    }

    public void cleanup() {
        for (UUID uuid : activeSessions.keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                closeSpectatorSwitch(player);
            }
        }
        activeSessions.clear();
        sessionCooldowns.clear();
    }

    private static class SpectatorSession {
        final Player player;
        final List<ArmorStand> entities = new ArrayList<>();
        final Map<UUID, String> worldMapping = new HashMap<>();
        GameMode originalGameMode;
        Location originalLocation;
        float originalFlySpeed;
        float originalWalkSpeed;
        boolean originalAllowFlight;
        boolean originalFlying;

        SpectatorSession(Player player) {
            this.player = player;
        }
    }
}
