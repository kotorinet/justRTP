package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.EulerAngle;

public class SpectatorSwitchManager
implements Listener {
    private final JustRTP plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Map<UUID, SpectatorSession> activeSessions = new ConcurrentHashMap<UUID, SpectatorSession>();
    private final Map<UUID, Long> sessionCooldowns = new ConcurrentHashMap<UUID, Long>();

    public SpectatorSwitchManager(JustRTP plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents((Listener)this, (Plugin)plugin);
    }

    public boolean isEnabled() {
        return this.plugin.getConfig().getBoolean("spectator_switch.enabled", false);
    }

    public void openSpectatorSwitch(Player player) {
        long now;
        if (!this.isEnabled()) {
            this.plugin.getLocaleManager().sendMessage((CommandSender)player, "spectator_switch.feature_disabled", new TagResolver[0]);
            return;
        }
        if (!player.hasPermission("justrtp.command.rtp.spectator")) {
            this.plugin.getLocaleManager().sendMessage((CommandSender)player, "command.no_permission", new TagResolver[0]);
            return;
        }
        long cooldown = this.sessionCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (cooldown > (now = System.currentTimeMillis())) {
            long remaining = (cooldown - now) / 1000L;
            this.plugin.getLocaleManager().sendMessage((CommandSender)player, "spectator_switch.cooldown", new TagResolver[]{Placeholder.unparsed((String)"time", (String)String.valueOf(remaining))});
            return;
        }
        if (this.activeSessions.containsKey(player.getUniqueId())) {
            this.closeSpectatorSwitch(player);
            return;
        }
        ConfigurationSection config = this.plugin.getConfig().getConfigurationSection("spectator_switch");
        if (config == null) {
            this.plugin.getRTPLogger().warn("SPECTATOR", "Spectator Switch configuration not found!");
            return;
        }
        SpectatorSession session = new SpectatorSession(player);
        Location playerLoc = player.getLocation();
        Location centerLoc = playerLoc.clone().add(0.0, 3.0, 0.0);
        ConfigurationSection worldsSection = config.getConfigurationSection("worlds");
        if (worldsSection == null || worldsSection.getKeys(false).isEmpty()) {
            this.plugin.getLocaleManager().sendMessage((CommandSender)player, "spectator_switch.no_worlds", new TagResolver[0]);
            return;
        }
        ArrayList worldKeys = new ArrayList(worldsSection.getKeys(false));
        int worldCount = worldKeys.size();
        double radius = config.getDouble("display_radius", 3.0);
        double angleStep = 360.0 / (double)worldCount;
        this.plugin.getFoliaScheduler().runAtEntity((Entity)player, () -> {
            int autoCloseSeconds;
            GameMode originalMode;
            session.originalGameMode = originalMode = player.getGameMode();
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
            for (int i = 0; i < worldCount; ++i) {
                String worldName;
                World world;
                String worldKey = (String)worldKeys.get(i);
                ConfigurationSection worldConfig = worldsSection.getConfigurationSection(worldKey);
                if (worldConfig == null || (world = Bukkit.getWorld((String)(worldName = worldConfig.getString("world_name", worldKey)))) == null || !this.plugin.getRtpService().isRtpEnabled(world) || !player.hasPermission("justrtp.command.rtp.world") && !player.hasPermission("justrtp.command.rtp.world." + worldName)) continue;
                double angle = Math.toRadians((double)i * angleStep);
                double x = centerLoc.getX() + radius * Math.cos(angle);
                double z = centerLoc.getZ() + radius * Math.sin(angle);
                Location headLoc = new Location(centerLoc.getWorld(), x, centerLoc.getY(), z);
                headLoc.setYaw((float)Math.toDegrees(angle + 180.0));
                ArmorStand stand = (ArmorStand)centerLoc.getWorld().spawnEntity(headLoc, EntityType.ARMOR_STAND);
                stand.setVisible(false);
                stand.setGravity(false);
                stand.setInvulnerable(true);
                stand.setMarker(true);
                stand.setSmall(true);
                stand.setCustomNameVisible(true);
                String displayName = worldConfig.getString("display_name", worldName);
                stand.customName(this.mm.deserialize(displayName));
                String skullTexture = worldConfig.getString("skull_texture", "");
                String skullOwner = worldConfig.getString("skull_owner", "");
                ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta skullMeta = (SkullMeta)skull.getItemMeta();
                if (!skullOwner.isEmpty()) {
                    OfflinePlayer skullPlayer = Bukkit.getOfflinePlayer((String)skullOwner);
                    skullMeta.setOwningPlayer(skullPlayer);
                } else if (!skullTexture.isEmpty()) {

                }
                skull.setItemMeta((ItemMeta)skullMeta);
                stand.getEquipment().setHelmet(skull);
                stand.setHeadPose(new EulerAngle(0.0, 0.0, 0.0));
                session.entities.add(stand);
                session.worldMapping.put(stand.getUniqueId(), worldName);
            }
            this.activeSessions.put(player.getUniqueId(), session);
            this.plugin.getLocaleManager().sendMessage((CommandSender)player, "spectator_switch.opened", new TagResolver[0]);
            int cooldownSeconds = config.getInt("open_cooldown", 2);
            if (cooldownSeconds > 0) {
                this.sessionCooldowns.put(player.getUniqueId(), now + (long)cooldownSeconds * 1000L);
            }
            if ((autoCloseSeconds = config.getInt("auto_close_seconds", 30)) > 0) {
                this.plugin.getFoliaScheduler().runLater(() -> {
                    if (this.activeSessions.containsKey(player.getUniqueId())) {
                        this.closeSpectatorSwitch(player);
                        this.plugin.getLocaleManager().sendMessage((CommandSender)player, "spectator_switch.timeout", new TagResolver[0]);
                    }
                }, (long)autoCloseSeconds * 20L);
            }
            this.plugin.getRTPLogger().debug("SPECTATOR", "Opened spectator switch for " + player.getName() + " with " + session.entities.size() + " worlds");
        });
    }

    public void closeSpectatorSwitch(Player player) {
        SpectatorSession session = this.activeSessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        this.plugin.getFoliaScheduler().runAtEntity((Entity)player, () -> {
            player.removePotionEffect(PotionEffectType.BLINDNESS);
            player.removePotionEffect(PotionEffectType.SLOWNESS);
            player.removePotionEffect(PotionEffectType.JUMP_BOOST);
            player.setFlySpeed(session.originalFlySpeed);
            player.setWalkSpeed(session.originalWalkSpeed);
            for (ArmorStand entity : session.entities) {
                if (entity == null || !entity.isValid()) continue;
                entity.remove();
            }
            if (session.originalGameMode != null) {
                player.setGameMode(session.originalGameMode);
            }
            player.setAllowFlight(session.originalAllowFlight);
            if (!session.originalFlying && player.isFlying()) {
                player.setFlying(false);
            }
            if (session.originalLocation != null && session.originalLocation.getWorld() != null && player.getWorld().equals((Object)session.originalLocation.getWorld())) {
                player.teleportAsync(session.originalLocation);
            }
            this.plugin.getRTPLogger().debug("SPECTATOR", "Closed spectator switch for " + player.getName());
        });
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onEntityInteract(PlayerInteractAtEntityEvent event) {
        Player player = event.getPlayer();
        SpectatorSession session = this.activeSessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (!(event.getRightClicked() instanceof ArmorStand)) {
            return;
        }
        ArmorStand stand = (ArmorStand)event.getRightClicked();
        String worldName = session.worldMapping.get(stand.getUniqueId());
        if (worldName == null) {
            return;
        }
        event.setCancelled(true);
        World targetWorld = Bukkit.getWorld((String)worldName);
        if (targetWorld == null) {
            this.plugin.getLocaleManager().sendMessage((CommandSender)player, "command.world_not_found", new TagResolver[]{Placeholder.unparsed((String)"world", (String)worldName)});
            this.closeSpectatorSwitch(player);
            return;
        }
        this.closeSpectatorSwitch(player);
        this.plugin.getFoliaScheduler().runAtEntity((Entity)player, () -> {
            this.plugin.getRTPLogger().debug("SPECTATOR", "Player " + player.getName() + " selected world: " + worldName);
            this.plugin.getServer().dispatchCommand((CommandSender)player, "rtp " + worldName);
        });
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        this.closeSpectatorSwitch(event.getPlayer());
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) {
            return;
        }
        SpectatorSession session = this.activeSessions.get(event.getPlayer().getUniqueId());
        if (session != null && event.getTo() != null && !event.getTo().getWorld().equals((Object)session.originalLocation.getWorld())) {
            this.closeSpectatorSwitch(event.getPlayer());
        }
    }

    public void reload() {
        for (UUID uuid : new HashSet<UUID>(this.activeSessions.keySet())) {
            Player player = Bukkit.getPlayer((UUID)uuid);
            if (player == null) continue;
            this.closeSpectatorSwitch(player);
        }
        this.activeSessions.clear();
        this.sessionCooldowns.clear();
        this.plugin.getRTPLogger().info("SPECTATOR", "Spectator Switch Manager reloaded");
    }

    public void cleanup() {
        for (UUID uuid : this.activeSessions.keySet()) {
            Player player = Bukkit.getPlayer((UUID)uuid);
            if (player == null || !player.isOnline()) continue;
            this.closeSpectatorSwitch(player);
        }
        this.activeSessions.clear();
        this.sessionCooldowns.clear();
    }

    private static class SpectatorSession {
        final Player player;
        final List<ArmorStand> entities = new ArrayList<ArmorStand>();
        final Map<UUID, String> worldMapping = new HashMap<UUID, String>();
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
