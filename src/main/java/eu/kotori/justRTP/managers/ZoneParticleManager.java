package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import eu.kotori.justRTP.utils.BlocksRegion;
import eu.kotori.justRTP.utils.CuboidRegion;
import eu.kotori.justRTP.utils.CylinderRegion;
import eu.kotori.justRTP.utils.FoliaScheduler;
import eu.kotori.justRTP.utils.RTPZone;
import eu.kotori.justRTP.utils.ZoneParticleStyle;
import eu.kotori.justRTP.utils.ZoneRegion;
import eu.kotori.justRTP.utils.ZoneShape;
import eu.kotori.justRTP.utils.task.CancellableTask;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ZoneParticleManager {

    private final JustRTP plugin;
    private final Map<String, CancellableTask> zoneTasks = new ConcurrentHashMap<>();
    private final Map<UUID, CancellableTask> setupTasks = new ConcurrentHashMap<>();

    private FileConfiguration config;
    private File configFile;

    private int updateInterval = 4;
    private double renderDistanceSq = 96.0 * 96.0;
    private int maxParticlesPerTick = 400;
    private String defaultStyle = "OUTLINE";
    private boolean setupPreviewEnabled = true;
    private int setupUpdateInterval = 3;
    private String setupStyle = "OUTLINE";
    private boolean setupPrivate = true;

    public ZoneParticleManager(JustRTP plugin) {
        this.plugin = plugin;
    }

    public void load() {
        configFile = new File(plugin.getDataFolder(), "zone_particles.yml");
        if (!configFile.exists()) {
            plugin.saveResource("zone_particles.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(configFile);
        updateInterval = Math.max(1, config.getInt("update_interval", 4));
        double rd = Math.max(8.0, config.getDouble("render_distance", 96.0));
        renderDistanceSq = rd * rd;
        maxParticlesPerTick = Math.max(50, config.getInt("max_particles_per_tick", 400));
        defaultStyle = config.getString("default_style", "OUTLINE");
        setupPreviewEnabled = config.getBoolean("setup_preview.enabled", true);
        setupUpdateInterval = Math.max(1, config.getInt("setup_preview.update_interval", 3));
        setupStyle = config.getString("setup_preview.style", "OUTLINE");
        setupPrivate = config.getBoolean("setup_preview.private", true);
    }

    public void reload() {
        stopAll();
        load();
    }

    public String getDefaultStyle() {
        return defaultStyle;
    }

    public void startZoneRendering(RTPZone zone) {
        stopZoneRendering(zone.getId());
        if (zone.getParticleStyle() == ZoneParticleStyle.NONE) return;
        Location center = zone.getCenterLocation();
        if (center == null || center.getWorld() == null) return;

        AtomicLong tick = new AtomicLong(0);
        CancellableTask task = plugin.getFoliaScheduler().runTimerAtLocation(center, () -> {
            try {
                renderZoneTick(zone, tick.getAndIncrement());
            } catch (Throwable t) {
                plugin.getRTPLogger().debug("PARTICLES",
                        "Render error for zone " + zone.getId() + ": " + t.getMessage());
            }
        }, 1L, updateInterval);
        zoneTasks.put(zone.getId().toLowerCase(), task);
        plugin.getRTPLogger().debug("PARTICLES", "Started rendering zone '" + zone.getId()
                + "' style=" + zone.getParticleStyle().name()
                + " at " + center.getWorld().getName()
                + " " + center.getBlockX() + "," + center.getBlockY() + "," + center.getBlockZ());
    }

    public void stopZoneRendering(String zoneId) {
        CancellableTask task = zoneTasks.remove(zoneId.toLowerCase());
        if (task != null) task.cancel();
    }

    public void startSetupPreview(Player player, java.util.function.Supplier<ZoneRegion> regionSupplier) {
        if (!setupPreviewEnabled) return;
        stopSetupPreview(player);

        ZoneParticleStyle style = ZoneParticleStyle.fromString(setupStyle);
        if (style == ZoneParticleStyle.NONE) return;

        AtomicLong tick = new AtomicLong(0);
        CancellableTask repeatingTask = plugin.getFoliaScheduler().runTimerAtLocation(
                player.getLocation(), () -> {
                    if (!player.isOnline()) {
                        stopSetupPreview(player);
                        return;
                    }
                    ZoneRegion region = regionSupplier.get();
                    if (region == null) return;
                    World world = Bukkit.getWorld(region.getWorldName());
                    if (world == null) return;
                    List<Player> viewers = setupPrivate ? List.of(player) : null;
                    renderStyle(world, region, style, viewers, tick.getAndIncrement());
                }, 1L, setupUpdateInterval);
        setupTasks.put(player.getUniqueId(), repeatingTask);
    }

    public void stopSetupPreview(Player player) {
        CancellableTask task = setupTasks.remove(player.getUniqueId());
        if (task != null) task.cancel();
    }

    public void stopAll() {
        zoneTasks.values().forEach(t -> { if (t != null) t.cancel(); });
        zoneTasks.clear();
        setupTasks.values().forEach(t -> { if (t != null) t.cancel(); });
        setupTasks.clear();
    }

    private void renderZoneTick(RTPZone zone, long tick) {
        World world = Bukkit.getWorld(zone.getRegion().getWorldName());
        if (world == null) return;
        Location center = zone.getCenterLocation();
        if (center == null) return;

        boolean anyNearby = false;
        for (Player p : world.getPlayers()) {
            double dx = p.getLocation().getX() - center.getX();
            double dz = p.getLocation().getZ() - center.getZ();
            if ((dx * dx + dz * dz) <= renderDistanceSq) {
                anyNearby = true;
                break;
            }
        }
        if (!anyNearby) return;

        renderStyle(world, zone.getRegion(), zone.getParticleStyle(), null, tick);
    }

    private void renderStyle(World world, ZoneRegion region, ZoneParticleStyle style,
                             List<Player> viewers, long tick) {
        if (style == ZoneParticleStyle.NONE) return;
        ConfigurationSection styleCfg = config.getConfigurationSection("styles." + style.name());
        if (styleCfg == null) return;

        RenderContext ctx = new RenderContext(world, viewers, maxParticlesPerTick);

        switch (style) {
            case OUTLINE:
                renderOutline(ctx, region, styleCfg);
                break;
            case BEAM:
                renderBeam(ctx, region, styleCfg, tick);
                break;
            case SWIRL:
                renderSwirl(ctx, region, styleCfg, tick);
                break;
            case PULSE:
                renderPulse(ctx, region, styleCfg, tick);
                break;
            case DUST_WALL:
                renderDustWall(ctx, region, styleCfg);
                break;
            default:
                break;
        }

        flushSpawns(ctx);
    }

    private void flushSpawns(RenderContext ctx) {
        if (ctx.spawns.isEmpty()) return;

        if (!FoliaScheduler.isFolia()) {
            for (SpawnSpec s : ctx.spawns) {
                emit(ctx.world, ctx.viewers, s);
            }
            return;
        }

        Map<Long, List<SpawnSpec>> byChunk = new HashMap<>();
        for (SpawnSpec s : ctx.spawns) {
            int cx = ((int) Math.floor(s.x)) >> 4;
            int cz = ((int) Math.floor(s.z)) >> 4;
            long key = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
            byChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        for (List<SpawnSpec> chunkSpawns : byChunk.values()) {
            if (chunkSpawns.isEmpty()) continue;
            SpawnSpec first = chunkSpawns.get(0);
            Location anchor = new Location(ctx.world, first.x, first.y, first.z);
            try {
                plugin.getFoliaScheduler().runAtLocation(anchor, () -> {
                    for (SpawnSpec s : chunkSpawns) {
                        emit(ctx.world, ctx.viewers, s);
                    }
                });
            } catch (Throwable ignored) {
            }
        }
    }

    private void emit(World world, List<Player> viewers, SpawnSpec s) {
        try {
            if (viewers != null && !viewers.isEmpty()) {
                for (Player p : viewers) {
                    if (p == null || !p.isOnline()) continue;
                    if (s.data != null) {
                        p.spawnParticle(s.particle, s.x, s.y, s.z, s.count, s.offX, s.offY, s.offZ, s.speed, s.data);
                    } else {
                        p.spawnParticle(s.particle, s.x, s.y, s.z, s.count, s.offX, s.offY, s.offZ, s.speed);
                    }
                }
            } else {
                if (s.data != null) {
                    world.spawnParticle(s.particle, s.x, s.y, s.z, s.count, s.offX, s.offY, s.offZ, s.speed, s.data);
                } else {
                    world.spawnParticle(s.particle, s.x, s.y, s.z, s.count, s.offX, s.offY, s.offZ, s.speed);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void renderOutline(RenderContext ctx, ZoneRegion region, ConfigurationSection cfg) {
        Particle particle = parseParticle(cfg.getString("particle", "END_ROD"));
        double step = Math.max(0.5, cfg.getDouble("edge_step", 1.0));
        int count = cfg.getInt("count", 1);
        double speed = cfg.getDouble("speed", 0.0);

        switch (region.getShape()) {
            case CUBOID: {
                CuboidRegion c = (CuboidRegion) region;
                double[] b = boundedCuboid(c, region.getCenter());
                drawCuboidEdges(ctx, b, step, particle, count, speed);
                break;
            }
            case CYLINDER: {
                CylinderRegion cyl = (CylinderRegion) region;
                int segments = Math.min(64, Math.max(12, cfg.getInt("ring_segments", 32)));
                int struts = Math.min(12, Math.max(4, cfg.getInt("cylinder_struts", 8)));
                drawCylinderOutline(ctx, cyl, segments, struts, particle, count, speed);
                break;
            }
            case BLOCKS: {
                BlocksRegion br = (BlocksRegion) region;
                int markerParticles = Math.min(8, Math.max(2, cfg.getInt("blocks_marker_particles", 4)));
                for (int[] pos : br.getBlocks()) {
                    if (!ctx.canSpawn()) break;
                    drawBlockMarker(ctx, pos, markerParticles, particle, count, speed);
                }
                break;
            }
        }
    }

    private void renderBeam(RenderContext ctx, ZoneRegion region, ConfigurationSection cfg, long tick) {
        Particle particle = parseParticle(cfg.getString("particle", "FIREWORK"));
        double height = Math.max(2.0, cfg.getDouble("beam_height", 6.0));
        int particlesPerBeam = Math.min(20, Math.max(3, cfg.getInt("particles_per_beam", 8)));
        int count = cfg.getInt("count", 1);
        double speed = cfg.getDouble("speed", 0.02);

        List<double[]> beamOrigins = new ArrayList<>();
        Location center = region.getCenter();
        switch (region.getShape()) {
            case CUBOID: {
                double[] b = boundedCuboid((CuboidRegion) region, center);
                beamOrigins.add(new double[]{b[0], b[1], b[2]});
                beamOrigins.add(new double[]{b[3], b[1], b[2]});
                beamOrigins.add(new double[]{b[0], b[1], b[5]});
                beamOrigins.add(new double[]{b[3], b[1], b[5]});
                beamOrigins.add(new double[]{(b[0] + b[3]) / 2, b[1], (b[2] + b[5]) / 2});
                break;
            }
            case CYLINDER: {
                CylinderRegion cyl = (CylinderRegion) region;
                int beams = Math.min(16, Math.max(4, cfg.getInt("cylinder_beam_count", 8)));
                Location ctr = cyl.getCenter();
                if (ctr == null) break;
                double yBase = clampedAnchorY(cyl.getMinY(), cyl.getMaxY(), ctr.getY());
                for (int i = 0; i < beams; i++) {
                    double angle = (Math.PI * 2 * i) / beams;
                    double x = ctr.getX() + Math.cos(angle) * cyl.getRadius();
                    double z = ctr.getZ() + Math.sin(angle) * cyl.getRadius();
                    beamOrigins.add(new double[]{x, yBase, z});
                }
                break;
            }
            case BLOCKS: {
                BlocksRegion br = (BlocksRegion) region;
                for (int[] pos : br.getBlocks()) {
                    beamOrigins.add(new double[]{pos[0] + 0.5, pos[1] + 1.0, pos[2] + 0.5});
                    if (beamOrigins.size() > 32) break;
                }
                break;
            }
        }

        double phase = (tick * 0.25) % 1.0;
        for (double[] origin : beamOrigins) {
            for (int i = 0; i < particlesPerBeam; i++) {
                if (!ctx.canSpawn()) return;
                double y = origin[1] + ((i + phase) * height / particlesPerBeam);
                ctx.add(new SpawnSpec(particle, origin[0], y, origin[2], count, 0.05, 0.0, 0.05, speed, null));
            }
        }
    }

    private void renderSwirl(RenderContext ctx, ZoneRegion region, ConfigurationSection cfg, long tick) {
        Color color = parseColor(cfg.getString("color", "#20B2AA"));
        float dustSize = (float) cfg.getDouble("dust_size", 1.2);
        Particle.DustOptions dust = new Particle.DustOptions(color, dustSize);
        double rotSpeed = cfg.getDouble("rotation_speed", 0.18);
        int helixPoints = Math.min(48, Math.max(8, cfg.getInt("helix_points", 24)));
        int layers = Math.min(6, Math.max(1, cfg.getInt("helix_layers", 3)));
        double layerHeight = cfg.getDouble("layer_height", 0.8);
        int count = cfg.getInt("count", 1);

        Location center = region.getCenter();
        if (center == null) return;

        if (region.getShape() == ZoneShape.BLOCKS) {
            BlocksRegion br = (BlocksRegion) region;
            int idx = 0;
            for (int[] pos : br.getBlocks()) {
                if (!ctx.canSpawn()) return;
                double angle = tick * rotSpeed + (idx++ * 0.5);
                double r = 0.8;
                double x = pos[0] + 0.5 + Math.cos(angle) * r;
                double y = pos[1] + 1.0 + Math.sin(angle * 1.5) * 0.4;
                double z = pos[2] + 0.5 + Math.sin(angle) * r;
                ctx.add(new SpawnSpec(Particle.DUST, x, y, z, count, 0.0, 0.0, 0.0, 0.0, dust));
            }
            return;
        }

        double radius;
        double yBase;
        double yRange;
        switch (region.getShape()) {
            case CUBOID: {
                double[] b = boundedCuboid((CuboidRegion) region, center);
                radius = Math.max(1.5, Math.max(Math.abs(b[3] - b[0]), Math.abs(b[5] - b[2])) / 2.0 + 0.5);
                yBase = b[1];
                yRange = Math.max(2.0, b[4] - b[1]);
                break;
            }
            case CYLINDER: {
                CylinderRegion cyl = (CylinderRegion) region;
                radius = Math.max(1.5, cyl.getRadius() + 0.3);
                double anchor = clampedAnchorY(cyl.getMinY(), cyl.getMaxY(), center.getY());
                yBase = anchor - 4;
                yRange = 8.0;
                break;
            }
            default:
                return;
        }

        for (int layer = 0; layer < layers; layer++) {
            double yOffset = layer * layerHeight;
            for (int i = 0; i < helixPoints; i++) {
                if (!ctx.canSpawn()) return;
                double t = (double) i / helixPoints;
                double angle = (tick * rotSpeed) + (Math.PI * 2 * t) + (layer * 0.5);
                double y = yBase + (((tick * 0.05) + t * yRange + yOffset) % yRange);
                double x = center.getX() + Math.cos(angle) * radius;
                double z = center.getZ() + Math.sin(angle) * radius;
                ctx.add(new SpawnSpec(Particle.DUST, x, y, z, count, 0.0, 0.0, 0.0, 0.0, dust));
            }
        }
    }

    private void renderPulse(RenderContext ctx, ZoneRegion region, ConfigurationSection cfg, long tick) {
        Color from = parseColor(cfg.getString("color_from", "#20B2AA"));
        Color to = parseColor(cfg.getString("color_to", "#7FFFD4"));
        float dustSize = (float) cfg.getDouble("dust_size", 1.0);
        Particle.DustTransition dust = new Particle.DustTransition(from, to, dustSize);
        int pulseCount = Math.min(4, Math.max(1, cfg.getInt("pulse_count", 2)));
        int ringPoints = Math.min(48, Math.max(12, cfg.getInt("ring_points", 32)));
        double pulseSpeed = Math.max(0.05, cfg.getDouble("pulse_speed", 0.4));
        int count = cfg.getInt("count", 1);

        Location center = region.getCenter();
        if (center == null) return;

        if (region.getShape() == ZoneShape.BLOCKS) {
            BlocksRegion br = (BlocksRegion) region;
            for (int[] pos : br.getBlocks()) {
                if (!ctx.canSpawn()) return;
                double phase = (tick * pulseSpeed) % 30.0;
                double radius = 0.2 + (phase * 0.04);
                if (radius > 1.6) continue;
                int pts = Math.max(8, ringPoints / 4);
                for (int i = 0; i < pts; i++) {
                    if (!ctx.canSpawn()) return;
                    double a = (Math.PI * 2 * i) / pts;
                    double x = pos[0] + 0.5 + Math.cos(a) * radius;
                    double z = pos[2] + 0.5 + Math.sin(a) * radius;
                    ctx.add(new SpawnSpec(Particle.DUST_COLOR_TRANSITION, x, pos[1] + 1.1, z,
                            count, 0.0, 0.0, 0.0, 0.0, dust));
                }
            }
            return;
        }

        double maxRadius;
        double y;
        switch (region.getShape()) {
            case CUBOID: {
                double[] b = boundedCuboid((CuboidRegion) region, center);
                maxRadius = Math.max(2.0, Math.max(Math.abs(b[3] - b[0]), Math.abs(b[5] - b[2])) / 2.0);
                y = b[1] + 0.2;
                break;
            }
            case CYLINDER: {
                CylinderRegion cyl = (CylinderRegion) region;
                maxRadius = Math.max(2.0, cyl.getRadius());
                y = clampedAnchorY(cyl.getMinY(), cyl.getMaxY(), center.getY()) + 0.2;
                break;
            }
            default:
                return;
        }

        for (int p = 0; p < pulseCount; p++) {
            if (!ctx.canSpawn()) return;
            double phase = ((tick * pulseSpeed) + (p * (maxRadius / pulseCount))) % maxRadius;
            for (int i = 0; i < ringPoints; i++) {
                if (!ctx.canSpawn()) return;
                double a = (Math.PI * 2 * i) / ringPoints;
                double x = center.getX() + Math.cos(a) * phase;
                double z = center.getZ() + Math.sin(a) * phase;
                ctx.add(new SpawnSpec(Particle.DUST_COLOR_TRANSITION, x, y, z,
                        count, 0.0, 0.0, 0.0, 0.0, dust));
            }
        }
    }

    private void renderDustWall(RenderContext ctx, ZoneRegion region, ConfigurationSection cfg) {
        Color color = parseColor(cfg.getString("color", "#FF8C00"));
        float dustSize = (float) cfg.getDouble("dust_size", 0.8);
        Particle.DustOptions dust = new Particle.DustOptions(color, dustSize);
        double heightStep = Math.max(1.0, cfg.getDouble("height_step", 1.5));
        double horizontalStep = Math.max(1.0, cfg.getDouble("horizontal_step", 1.5));
        int count = cfg.getInt("count", 1);

        switch (region.getShape()) {
            case CUBOID: {
                CuboidRegion cub = (CuboidRegion) region;
                Location center = cub.getCenter();
                double[] b = boundedCuboid(cub, center);
                for (double y = b[1]; y <= b[4]; y += heightStep) {
                    for (double x = b[0]; x <= b[3]; x += horizontalStep) {
                        if (!ctx.canSpawn()) return;
                        ctx.add(new SpawnSpec(Particle.DUST, x, y, b[2], count, 0, 0, 0, 0, dust));
                        ctx.add(new SpawnSpec(Particle.DUST, x, y, b[5], count, 0, 0, 0, 0, dust));
                    }
                    for (double z = b[2]; z <= b[5]; z += horizontalStep) {
                        if (!ctx.canSpawn()) return;
                        ctx.add(new SpawnSpec(Particle.DUST, b[0], y, z, count, 0, 0, 0, 0, dust));
                        ctx.add(new SpawnSpec(Particle.DUST, b[3], y, z, count, 0, 0, 0, 0, dust));
                    }
                }
                break;
            }
            case CYLINDER: {
                CylinderRegion cyl = (CylinderRegion) region;
                Location ctr = cyl.getCenter();
                if (ctr == null) return;
                int segments = Math.min(48, Math.max(12, (int) (Math.PI * 2 * cyl.getRadius() / horizontalStep)));
                double anchor = clampedAnchorY(cyl.getMinY(), cyl.getMaxY(), ctr.getY());
                double yMin = anchor - 6;
                double yMax = anchor + 6;
                yMin = Math.max(yMin, cyl.getMinY());
                yMax = Math.min(yMax, cyl.getMaxY());
                for (double y = yMin; y <= yMax; y += heightStep) {
                    for (int i = 0; i < segments; i++) {
                        if (!ctx.canSpawn()) return;
                        double a = (Math.PI * 2 * i) / segments;
                        double x = ctr.getX() + Math.cos(a) * cyl.getRadius();
                        double z = ctr.getZ() + Math.sin(a) * cyl.getRadius();
                        ctx.add(new SpawnSpec(Particle.DUST, x, y, z, count, 0, 0, 0, 0, dust));
                    }
                }
                break;
            }
            case BLOCKS: {
                BlocksRegion br = (BlocksRegion) region;
                for (int[] pos : br.getBlocks()) {
                    if (!ctx.canSpawn()) return;
                    for (double dy = 0.5; dy <= 2.5; dy += heightStep) {
                        if (!ctx.canSpawn()) return;
                        ctx.add(new SpawnSpec(Particle.DUST, pos[0] + 0.5, pos[1] + dy, pos[2] + 0.5,
                                count, 0.2, 0.0, 0.2, 0.0, dust));
                    }
                }
                break;
            }
        }
    }

    private void drawCuboidEdges(RenderContext ctx, double[] b, double step,
                                 Particle particle, int count, double speed) {
        double minX = b[0], minY = b[1], minZ = b[2];
        double maxX = b[3], maxY = b[4], maxZ = b[5];

        for (double x = minX; x <= maxX; x += step) {
            if (!ctx.canSpawn()) return;
            ctx.add(new SpawnSpec(particle, x, minY, minZ, count, 0, 0, 0, speed, null));
            ctx.add(new SpawnSpec(particle, x, minY, maxZ, count, 0, 0, 0, speed, null));
            ctx.add(new SpawnSpec(particle, x, maxY, minZ, count, 0, 0, 0, speed, null));
            ctx.add(new SpawnSpec(particle, x, maxY, maxZ, count, 0, 0, 0, speed, null));
        }
        for (double z = minZ; z <= maxZ; z += step) {
            if (!ctx.canSpawn()) return;
            ctx.add(new SpawnSpec(particle, minX, minY, z, count, 0, 0, 0, speed, null));
            ctx.add(new SpawnSpec(particle, maxX, minY, z, count, 0, 0, 0, speed, null));
            ctx.add(new SpawnSpec(particle, minX, maxY, z, count, 0, 0, 0, speed, null));
            ctx.add(new SpawnSpec(particle, maxX, maxY, z, count, 0, 0, 0, speed, null));
        }
        for (double y = minY; y <= maxY; y += step) {
            if (!ctx.canSpawn()) return;
            ctx.add(new SpawnSpec(particle, minX, y, minZ, count, 0, 0, 0, speed, null));
            ctx.add(new SpawnSpec(particle, maxX, y, minZ, count, 0, 0, 0, speed, null));
            ctx.add(new SpawnSpec(particle, minX, y, maxZ, count, 0, 0, 0, speed, null));
            ctx.add(new SpawnSpec(particle, maxX, y, maxZ, count, 0, 0, 0, speed, null));
        }
    }

    private void drawCylinderOutline(RenderContext ctx, CylinderRegion cyl, int segments, int struts,
                                     Particle particle, int count, double speed) {
        Location ctr = cyl.getCenter();
        if (ctr == null) return;
        double cx = ctr.getX();
        double cz = ctr.getZ();
        double anchor = clampedAnchorY(cyl.getMinY(), cyl.getMaxY(), ctr.getY());
        double minY = Math.max(cyl.getMinY(), anchor - 4);
        double maxY = Math.min(cyl.getMaxY(), anchor + 4);
        if (maxY <= minY) maxY = minY + 1;
        double radius = cyl.getRadius();

        for (int i = 0; i < segments; i++) {
            if (!ctx.canSpawn()) return;
            double a = (Math.PI * 2 * i) / segments;
            double x = cx + Math.cos(a) * radius;
            double z = cz + Math.sin(a) * radius;
            ctx.add(new SpawnSpec(particle, x, minY, z, count, 0, 0, 0, speed, null));
            ctx.add(new SpawnSpec(particle, x, maxY, z, count, 0, 0, 0, speed, null));
        }
        int strutSteps = Math.max(2, Math.min(8, (int) (maxY - minY)));
        for (int s = 0; s < struts; s++) {
            if (!ctx.canSpawn()) return;
            double a = (Math.PI * 2 * s) / struts;
            double x = cx + Math.cos(a) * radius;
            double z = cz + Math.sin(a) * radius;
            for (int j = 0; j <= strutSteps; j++) {
                if (!ctx.canSpawn()) return;
                double y = minY + ((maxY - minY) * j / strutSteps);
                ctx.add(new SpawnSpec(particle, x, y, z, count, 0, 0, 0, speed, null));
            }
        }
    }

    private void drawBlockMarker(RenderContext ctx, int[] pos, int markerParticles,
                                 Particle particle, int count, double speed) {
        double cx = pos[0] + 0.5;
        double cy = pos[1] + 1.05;
        double cz = pos[2] + 0.5;
        ctx.add(new SpawnSpec(particle, cx, cy, cz, count, 0, 0, 0, speed, null));
        for (int i = 0; i < markerParticles; i++) {
            if (!ctx.canSpawn()) return;
            double a = (Math.PI * 2 * i) / markerParticles;
            ctx.add(new SpawnSpec(particle, cx + Math.cos(a) * 0.45, cy, cz + Math.sin(a) * 0.45,
                    count, 0, 0, 0, speed, null));
        }
    }

    private double clampedAnchorY(int minY, int maxY, double prefer) {
        if (maxY - minY <= 16) {
            return (minY + maxY) / 2.0;
        }
        return Math.max(minY + 1, Math.min(maxY - 1, prefer));
    }

    private double[] boundedCuboid(CuboidRegion region, Location anchor) {
        double minX = region.getMinX();
        double minY = region.getMinY();
        double minZ = region.getMinZ();
        double maxX = region.getMaxX() + 1.0;
        double maxY = region.getMaxY() + 1.0;
        double maxZ = region.getMaxZ() + 1.0;

        if (maxY - minY > 32 && anchor != null) {
            double a = anchor.getY();
            minY = Math.max(minY, a - 8);
            maxY = Math.min(maxY, a + 8);
        }
        if (maxX - minX > 96 && anchor != null) {
            double a = anchor.getX();
            minX = Math.max(minX, a - 32);
            maxX = Math.min(maxX, a + 32);
        }
        if (maxZ - minZ > 96 && anchor != null) {
            double a = anchor.getZ();
            minZ = Math.max(minZ, a - 32);
            maxZ = Math.min(maxZ, a + 32);
        }
        return new double[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    private Particle parseParticle(String name) {
        if (name == null) return Particle.END_ROD;
        try {
            return Particle.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Particle.END_ROD;
        }
    }

    private Color parseColor(String hex) {
        if (hex == null) return Color.fromRGB(32, 178, 170);
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            int rgb = Integer.parseInt(s, 16);
            return Color.fromRGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        } catch (NumberFormatException e) {
            return Color.fromRGB(32, 178, 170);
        }
    }

    private static final class RenderContext {
        final World world;
        final List<Player> viewers;
        final int budget;
        final List<SpawnSpec> spawns;

        RenderContext(World world, List<Player> viewers, int budget) {
            this.world = world;
            this.viewers = viewers;
            this.budget = budget;
            this.spawns = new ArrayList<>(Math.min(budget, 256));
        }

        boolean canSpawn() {
            return spawns.size() < budget;
        }

        void add(SpawnSpec s) {
            if (s == null) return;
            if (spawns.size() < budget) spawns.add(s);
        }
    }

    private static final class SpawnSpec {
        final Particle particle;
        final double x, y, z;
        final int count;
        final double offX, offY, offZ;
        final double speed;
        final Object data;

        SpawnSpec(Particle particle, double x, double y, double z, int count,
                  double offX, double offY, double offZ, double speed, Object data) {
            this.particle = particle;
            this.x = x;
            this.y = y;
            this.z = z;
            this.count = count;
            this.offX = offX;
            this.offY = offY;
            this.offZ = offZ;
            this.speed = speed;
            this.data = data;
        }
    }
}
