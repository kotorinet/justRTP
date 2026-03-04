package eu.kotori.justRTP.managers;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import eu.kotori.justRTP.JustRTP;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PacketHologramManager implements Listener {

    private final JustRTP plugin;
    private FileConfiguration hologramsConfig;
    private FileConfiguration displayEntitiesConfig;
    private final File displayEntitiesFile;

    private final Map<String, PacketHologram> activeHolograms = new ConcurrentHashMap<>();
    private final Map<String, Boolean> hologramCreationLocks = new ConcurrentHashMap<>();

    private final Map<UUID, Set<String>> playerVisibleHolograms = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> hologramViewers = new ConcurrentHashMap<>();

    private final AtomicInteger entityIdCounter = new AtomicInteger(100000);

    private volatile boolean packetEventsAvailable = false;

    private static class PacketHologram {
        final String zoneId;
        final Location location;
        final List<HologramLine> lines;
        final int viewDistance;
        final AtomicLong particleTick = new AtomicLong(0);

        PacketHologram(String zoneId, Location location, List<HologramLine> lines, int viewDistance) {
            this.zoneId = zoneId;
            this.location = location.clone();
            this.lines = lines;
            this.viewDistance = viewDistance;
        }
    }

    private static class HologramLine {
        final int entityId;
        final Location location;
        Component text;

        HologramLine(int entityId, Location location, Component text) {
            this.entityId = entityId;
            this.location = location.clone();
            this.text = text;
        }
    }

    public PacketHologramManager(JustRTP plugin) {
        this.plugin = plugin;
        this.displayEntitiesFile = new File(plugin.getDataFolder(), "display_entities.yml");
        this.packetEventsAvailable = true;

        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void initialize() {
        File configFile = new File(plugin.getDataFolder(), "holograms.yml");
        if (!configFile.exists()) {
            plugin.saveResource("holograms.yml", false);
        }
        hologramsConfig = YamlConfiguration.loadConfiguration(configFile);
        loadDisplayEntities();

        if (packetEventsAvailable) {
            startParticleTask();
        }
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
        synchronized (displayEntitiesFile) {
            try {
                displayEntitiesConfig.save(displayEntitiesFile);
            } catch (IOException e) {
                plugin.getLogger().severe("Could not save display_entities.yml!");
                e.printStackTrace();
            }
        }
    }

    public boolean isPacketEventsAvailable() {
        return packetEventsAvailable;
    }

    public boolean isHologramActive(String zoneId) {
        return activeHolograms.containsKey(zoneId.toLowerCase());
    }

    public boolean isBeingCreated(String zoneId) {
        return hologramCreationLocks.containsKey(zoneId.toLowerCase());
    }

    public double getDefaultYOffset() {
        return hologramsConfig.getDouble("hologram-settings.y-offset", 2.5);
    }

    public void createOrUpdateHologram(String zoneId, Location location, int viewDistance) {
        if (!packetEventsAvailable) {
            plugin.getHologramManager().createOrUpdateHologram(zoneId, location, viewDistance);
            return;
        }

        if (hologramCreationLocks.putIfAbsent(zoneId.toLowerCase(), true) != null) {
            plugin.getRTPLogger().debug("HOLOGRAM",
                    "Packet hologram creation/update for " + zoneId + " is already in progress.");

            return;
        }

        io.papermc.lib.PaperLib.getChunkAtAsync(location).thenAccept(chunk -> {
            plugin.getFoliaScheduler().runAtLocation(location, () -> {
                try {
                    removeHologram(zoneId);

                    List<String> configLines = hologramsConfig.getStringList("hologram-settings.lines");
                    double lineSpacing = hologramsConfig.getDouble("hologram-settings.line-spacing", 0.35);
                    Location textLocation = location.clone();

                    List<HologramLine> lines = new ArrayList<>();
                    List<String> entityIds = new ArrayList<>();

                    for (String line : configLines) {
                        if (line.isEmpty()) {
                            textLocation.subtract(0, lineSpacing, 0);
                            continue;
                        }

                        int entityId = entityIdCounter.getAndIncrement();
                        Component text = MiniMessage.miniMessage().deserialize(line,
                                Placeholder.unparsed("zone_id", zoneId),
                                Placeholder.unparsed("time", ""));

                        HologramLine hologramLine = new HologramLine(entityId, textLocation.clone(), text);
                        lines.add(hologramLine);
                        entityIds.add(String.valueOf(entityId));
                        textLocation.subtract(0, lineSpacing, 0);
                    }

                    PacketHologram hologram = new PacketHologram(zoneId, location, lines, viewDistance);
                    activeHolograms.put(zoneId.toLowerCase(), hologram);

                    plugin.getRTPLogger().debug("HOLOGRAM",
                            "Created packet hologram for zone " + zoneId + " with " + lines.size() + " lines");

                    sendHologramToNearbyPlayers(hologram);

                    plugin.getFoliaScheduler().runLater(() -> {
                        sendHologramToNearbyPlayers(hologram);
                    }, 1L);

                    displayEntitiesConfig.set("zones." + zoneId.toLowerCase(), entityIds);
                    saveDisplayEntities();

                } finally {
                    hologramCreationLocks.remove(zoneId.toLowerCase());
                }
            });
        }).exceptionally(throwable -> {
            plugin.getLogger()
                    .warning("Failed to load chunk for hologram at " + location + ": " + throwable.getMessage());
            hologramCreationLocks.remove(zoneId.toLowerCase());
            return null;
        });
    }

    public void updateHologramTime(String zoneId, int time) {
        if (!packetEventsAvailable) {
            plugin.getHologramManager().updateHologramTime(zoneId, time);
            return;
        }

        PacketHologram hologram = activeHolograms.get(zoneId.toLowerCase());
        if (hologram == null) {
            plugin.getRTPLogger().debug("HOLOGRAM",
                    "Cannot update hologram time for zone " + zoneId + " - hologram not found");

            return;
        }

        String timeString = eu.kotori.justRTP.utils.TimeUtils.formatDuration(time);

        List<String> configLines = hologramsConfig.getStringList("hologram-settings.lines");

        int lineIndex = 0;

        for (String line : configLines) {
            if (!line.isEmpty() && lineIndex < hologram.lines.size()) {
                HologramLine hologramLine = hologram.lines.get(lineIndex);

                synchronized (hologramLine) {
                    hologramLine.text = MiniMessage.miniMessage().deserialize(line,
                            Placeholder.unparsed("zone_id", zoneId),
                            Placeholder.unparsed("time", timeString));
                }

                sendTextUpdateToViewers(hologram, hologramLine);
                lineIndex++;
            }
        }

        spawnParticlesForViewers(hologram);
    }

    public void updateHologramProgress(String zoneId) {
        if (!packetEventsAvailable) {
            plugin.getHologramManager().updateHologramProgress(zoneId);
            return;
        }

        PacketHologram hologram = activeHolograms.get(zoneId.toLowerCase());
        if (hologram == null) {
            plugin.getRTPLogger().debug("HOLOGRAM",
                    "Cannot update hologram progress for zone " + zoneId + " - hologram not found");

            return;
        }

        String progressText = "Teleport in progress";

        List<String> configLines = hologramsConfig.getStringList("hologram-settings.lines");

        int lineIndex = 0;

        for (String line : configLines) {
            if (!line.isEmpty() && lineIndex < hologram.lines.size()) {
                HologramLine hologramLine = hologram.lines.get(lineIndex);

                synchronized (hologramLine) {
                    hologramLine.text = MiniMessage.miniMessage().deserialize(line,
                            Placeholder.unparsed("zone_id", zoneId),
                            Placeholder.unparsed("time", progressText));
                }

                sendTextUpdateToViewers(hologram, hologramLine);
                lineIndex++;
            }
        }

        spawnParticlesForViewers(hologram);
    }

    private void sendHologramToNearbyPlayers(PacketHologram hologram) {
        if (!packetEventsAvailable)
            return;

        for (Player player : hologram.location.getWorld().getPlayers()) {
            if (player.getLocation().distance(hologram.location) <= hologram.viewDistance) {
                showHologramToPlayer(hologram, player);
            }
        }
    }

    private void showHologramToPlayer(PacketHologram hologram, Player player) {
        if (!packetEventsAvailable || player == null || !player.isOnline())
            return;

        try {
            Set<String> visibleHolograms = playerVisibleHolograms.computeIfAbsent(player.getUniqueId(),
                    k -> ConcurrentHashMap.newKeySet());
            Set<UUID> viewers = hologramViewers.computeIfAbsent(hologram.zoneId, k -> ConcurrentHashMap.newKeySet());

            synchronized (visibleHolograms) {
                if (visibleHolograms.add(hologram.zoneId)) {
                    viewers.add(player.getUniqueId());

                    plugin.getFoliaScheduler().runAtEntity(player, () -> {
                        sendHologramPacketsWithRetry(hologram, player, 0);
                    });
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to show hologram to player " + player.getName() + ": " + e.getMessage());
        }
    }

    private void sendHologramPacketsWithRetry(PacketHologram hologram, Player player, int attempt) {
        if (!player.isOnline() || attempt >= 3) {
            if (attempt >= 3) {
                plugin.getLogger().warning("Failed to show hologram " + hologram.zoneId +
                        " to player " + player.getName() + " after 3 attempts");
            }
            return;
        }

        try {
            for (HologramLine line : hologram.lines) {
                WrapperPlayServerSpawnEntity spawnPacket = new WrapperPlayServerSpawnEntity(
                        line.entityId,
                        Optional.of(UUID.randomUUID()),
                        EntityTypes.ARMOR_STAND,
                        new Vector3d(line.location.getX(), line.location.getY(), line.location.getZ()),
                        0f, 0f, 0f, 0, Optional.empty());

                PacketEvents.getAPI().getPlayerManager().sendPacket(player, spawnPacket);

                WrapperPlayServerEntityMetadata metadataPacket = new WrapperPlayServerEntityMetadata(
                        line.entityId,
                        createArmorStandMetadata(line.text));

                PacketEvents.getAPI().getPlayerManager().sendPacket(player, metadataPacket);
            }

            if (attempt > 0) {
                plugin.getRTPLogger().debug("HOLOGRAM", "Successfully sent hologram " + hologram.zoneId +
                        " packets to player " + player.getName() + " on attempt " + (attempt + 1));

            }

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to show hologram packets to player " + player.getName() +
                    " (attempt " + (attempt + 1) + "/3): " + e.getMessage());

            plugin.getFoliaScheduler().runAtEntityLater(player, () -> {
                sendHologramPacketsWithRetry(hologram, player, attempt + 1);
            }, 20L);
        }
    }

    private void hideHologramFromPlayer(PacketHologram hologram, Player player) {
        if (!packetEventsAvailable || player == null)
            return;

        try {
            Set<String> visibleHolograms = playerVisibleHolograms.get(player.getUniqueId());
            Set<UUID> viewers = hologramViewers.get(hologram.zoneId);

            if (visibleHolograms != null) {
                synchronized (visibleHolograms) {
                    if (visibleHolograms.remove(hologram.zoneId)) {
                        if (viewers != null) {
                            viewers.remove(player.getUniqueId());
                        }

                        if (player.isOnline()) {
                            plugin.getFoliaScheduler().runAtEntity(player, () -> {
                                try {
                                    int[] entityIds = hologram.lines.stream().mapToInt(line -> line.entityId).toArray();
                                    WrapperPlayServerDestroyEntities destroyPacket = new WrapperPlayServerDestroyEntities(
                                            entityIds);
                                    PacketEvents.getAPI().getPlayerManager().sendPacket(player, destroyPacket);
                                } catch (Exception e) {
                                    plugin.getLogger().warning("Failed to hide hologram packets from player "
                                            + player.getName() + ": " + e.getMessage());
                                }
                            });
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger()
                    .warning("Failed to hide hologram from player " + player.getName() + ": " + e.getMessage());
        }
    }

    private List<com.github.retrooper.packetevents.protocol.entity.data.EntityData> createArmorStandMetadata(
            Component text) {
        List<com.github.retrooper.packetevents.protocol.entity.data.EntityData> metadata = new ArrayList<>();

        try {
            metadata.add(new com.github.retrooper.packetevents.protocol.entity.data.EntityData(
                    0,
                    com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.BYTE,
                    (byte) 0x20));

            metadata.add(new com.github.retrooper.packetevents.protocol.entity.data.EntityData(
                    2,
                    com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.OPTIONAL_ADV_COMPONENT,
                    Optional.of(text)));

            metadata.add(new com.github.retrooper.packetevents.protocol.entity.data.EntityData(
                    3,
                    com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.BOOLEAN,
                    true));

            metadata.add(new com.github.retrooper.packetevents.protocol.entity.data.EntityData(
                    15,
                    com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.BYTE,
                    (byte) 0x11));

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to create armor stand metadata: " + e.getMessage());
            plugin.getRTPLogger().debug("HOLOGRAM",
                    "Metadata error details: " + e.getClass().getName() + " - " + e.getMessage());

            e.printStackTrace();

            try {
                String plainText = LegacyComponentSerializer.legacySection().serialize(text);
                plugin.getRTPLogger().debug("HOLOGRAM", "Attempting fallback with legacy text: " + plainText);

                metadata.clear();
                metadata.add(new com.github.retrooper.packetevents.protocol.entity.data.EntityData(
                        0, com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.BYTE, (byte) 0x20));
                metadata.add(new com.github.retrooper.packetevents.protocol.entity.data.EntityData(
                        2,
                        com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.OPTIONAL_ADV_COMPONENT,
                        Optional.of(Component.text(plainText))));
                metadata.add(new com.github.retrooper.packetevents.protocol.entity.data.EntityData(
                        3, com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.BOOLEAN, true));
                metadata.add(new com.github.retrooper.packetevents.protocol.entity.data.EntityData(
                        15, com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes.BYTE, (byte) 0x11));

                plugin.getRTPLogger().debug("HOLOGRAM", "Fallback metadata created successfully");

            } catch (Exception fallbackError) {
                plugin.getLogger().severe("Failed to create fallback metadata: " + fallbackError.getMessage());
                fallbackError.printStackTrace();
            }
        }

        return metadata;
    }

    private void sendTextUpdateToViewers(PacketHologram hologram, HologramLine line) {
        if (!packetEventsAvailable)
            return;

        Set<UUID> viewers = hologramViewers.get(hologram.zoneId);
        if (viewers == null)
            return;

        WrapperPlayServerEntityMetadata metadataPacket = new WrapperPlayServerEntityMetadata(
                line.entityId,
                createArmorStandMetadata(line.text));

        for (UUID playerId : viewers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                PacketEvents.getAPI().getPlayerManager().sendPacket(player, metadataPacket);
            }
        }
    }

    private void spawnParticlesForViewers(PacketHologram hologram) {
        if (!packetEventsAvailable)
            return;

        ConfigurationSection particleSection = hologramsConfig.getConfigurationSection("hologram-settings.particles");
        if (particleSection == null || !particleSection.getBoolean("enabled"))
            return;

        Set<UUID> viewers = hologramViewers.get(hologram.zoneId);
        if (viewers == null)
            return;

        try {
            int count = particleSection.getInt("count", 2);
            if (count <= 0)
                return;
            double radius = particleSection.getDouble("radius", 0.8);
            double angle = (hologram.particleTick.getAndIncrement() * 0.1) % (2 * Math.PI);

            for (int i = 0; i < count; i++) {
                double currentAngle = angle + (2 * Math.PI * i / count);
                double x = hologram.location.getX() + radius * Math.cos(currentAngle);
                double z = hologram.location.getZ() + radius * Math.sin(currentAngle);

                org.bukkit.Location particleLoc = new org.bukkit.Location(
                        hologram.location.getWorld(), x, hologram.location.getY(), z);

                for (UUID playerId : viewers) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null && player.isOnline()) {
                        try {
                            org.bukkit.Particle particle = getParticle("ENCHANT");
                            if (particle != null) {
                                player.spawnParticle(particle, particleLoc, 1, 0, 0, 0, 0);
                            }
                        } catch (Exception ex) {
                            org.bukkit.Particle particle = getParticle("ENCHANT");
                            if (particle != null) {
                                hologram.location.getWorld().spawnParticle(particle, particleLoc, 1, 0, 0, 0, 0);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {

        }
    }

    public void removeHologram(String zoneId) {
        String normalizedZoneId = zoneId.toLowerCase();

        plugin.getRTPLogger().debug("HOLOGRAM", "Removing packet hologram for zone: " + zoneId);

        PacketHologram hologram = activeHolograms.remove(normalizedZoneId);

        if (hologram != null) {
            Set<UUID> viewers = hologramViewers.remove(hologram.zoneId);
            if (viewers != null) {
                Set<UUID> viewersSnapshot = new HashSet<>(viewers);

                for (UUID playerId : viewersSnapshot) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null && player.isOnline()) {
                        hideHologramFromPlayer(hologram, player);
                    }

                    Set<String> visibleHolograms = playerVisibleHolograms.get(playerId);
                    if (visibleHolograms != null) {
                        visibleHolograms.remove(hologram.zoneId);

                        if (visibleHolograms.isEmpty()) {
                            playerVisibleHolograms.remove(playerId, visibleHolograms);
                        }
                    }
                }

                viewers.clear();
            }

            plugin.getRTPLogger().debug("HOLOGRAM", "✓ Removed packet hologram for zone: " + zoneId + " (hidden from " +
                    (viewers != null ? viewers.size() : 0) + " players)");

        } else {
            plugin.getRTPLogger().debug("HOLOGRAM", "No active packet hologram found for zone: " + zoneId);

        }

        displayEntitiesConfig.set("zones." + normalizedZoneId, null);
        saveDisplayEntities();

        plugin.getLogger().info("Packet hologram removal completed for zone: " + zoneId);
    }

    public void cleanupAllHolograms() {
        plugin.getRTPLogger().debug("HOLOGRAM", "Cleaning up all packet holograms...");

        for (PacketHologram hologram : activeHolograms.values()) {
            Set<UUID> viewers = hologramViewers.get(hologram.zoneId);
            if (viewers != null) {
                for (UUID playerId : viewers) {
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null && player.isOnline()) {
                        hideHologramFromPlayer(hologram, player);
                    }
                }
            }
        }

        activeHolograms.clear();
        hologramViewers.clear();
        playerVisibleHolograms.clear();

        displayEntitiesConfig.set("zones", null);
        saveDisplayEntities();

        plugin.getRTPLogger().debug("HOLOGRAM", "Packet hologram cleanup complete.");

    }

    private void startParticleTask() {
        plugin.getFoliaScheduler().runTimer(() -> {
            for (PacketHologram hologram : activeHolograms.values()) {
                spawnParticlesForViewers(hologram);
            }
        }, 1L, 10L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!packetEventsAvailable)
            return;

        Player player = event.getPlayer();

        plugin.getFoliaScheduler().runAtEntityLater(player, () -> {
            if (!player.isOnline())
                return;

            for (PacketHologram hologram : activeHolograms.values()) {
                try {
                    if (player.getWorld().equals(hologram.location.getWorld()) &&
                            player.getLocation().distance(hologram.location) <= hologram.viewDistance) {
                        showHologramToPlayer(hologram, player);
                    }
                } catch (Exception e) {
                    plugin.getRTPLogger().debug("HOLOGRAM",
                            "Error showing hologram " + hologram.zoneId + " to joining player: " + e.getMessage());

                }
            }
        }, 20L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!packetEventsAvailable)
            return;

        UUID playerId = event.getPlayer().getUniqueId();

        Set<String> visibleHolograms = playerVisibleHolograms.remove(playerId);

        if (visibleHolograms != null) {
            Set<String> hologramsSnapshot = new HashSet<>(visibleHolograms);

            for (String zoneId : hologramsSnapshot) {
                Set<UUID> viewers = hologramViewers.get(zoneId);
                if (viewers != null) {
                    viewers.remove(playerId);

                    if (viewers.isEmpty()) {
                        hologramViewers.remove(zoneId, viewers);
                    }
                }
            }

            visibleHolograms.clear();
        }
    }

    public void updateHologramVisibility(Player player) {
        if (!packetEventsAvailable || player == null || !player.isOnline())
            return;

        UUID playerId = player.getUniqueId();

        Set<String> currentlyVisible = playerVisibleHolograms.get(playerId);
        if (currentlyVisible == null) {
            currentlyVisible = ConcurrentHashMap.newKeySet();
            Set<String> existing = playerVisibleHolograms.putIfAbsent(playerId, currentlyVisible);
            if (existing != null) {
                currentlyVisible = existing;
            }
        }

        Set<String> currentlyVisibleSnapshot = new HashSet<>(currentlyVisible);
        Set<String> shouldBeVisible = new HashSet<>();

        for (PacketHologram hologram : new ArrayList<>(activeHolograms.values())) {
            try {
                if (player.getWorld().equals(hologram.location.getWorld()) &&
                        player.getLocation().distance(hologram.location) <= hologram.viewDistance) {
                    shouldBeVisible.add(hologram.zoneId);
                }
            } catch (Exception e) {
                plugin.getRTPLogger().debug("HOLOGRAM", "Error calculating hologram visibility: " + e.getMessage());

            }
        }

        for (String zoneId : shouldBeVisible) {
            if (!currentlyVisibleSnapshot.contains(zoneId)) {
                PacketHologram hologram = activeHolograms.get(zoneId);
                if (hologram != null) {
                    showHologramToPlayer(hologram, player);
                }
            }
        }

        for (String zoneId : currentlyVisibleSnapshot) {
            if (!shouldBeVisible.contains(zoneId)) {
                PacketHologram hologram = activeHolograms.get(zoneId);
                if (hologram != null) {
                    hideHologramFromPlayer(hologram, player);
                }
            }
        }
    }

    private org.bukkit.Particle getParticle(String name) {
        try {
            return org.bukkit.Registry.PARTICLE_TYPE.get(org.bukkit.NamespacedKey.minecraft(name.toLowerCase()));
        } catch (Throwable ignored) {
        }
        try {
            Class<org.bukkit.Particle> particleClass = org.bukkit.Particle.class;
            java.lang.reflect.Method valuesMethod = particleClass.getMethod("values");
            Object result = valuesMethod.invoke(null);
            if (result instanceof org.bukkit.Particle[]) {
                org.bukkit.Particle[] allParticles = (org.bukkit.Particle[]) result;
                java.lang.reflect.Method nameMethod = particleClass.getMethod("name");
                for (int i = 0; i < allParticles.length; i++) {
                    org.bukkit.Particle p = allParticles[i];
                    if (p != null) {
                        String enumName = (String) nameMethod.invoke(p);
                        if (enumName != null
                                && (enumName.equalsIgnoreCase(name) || enumName.equalsIgnoreCase("ENCHANTING_TABLE")
                                        || enumName.equalsIgnoreCase("ENCHANTMENT_TABLE"))) {
                            return p;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}