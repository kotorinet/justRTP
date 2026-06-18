package eu.kotori.justRTP.utils;

import eu.kotori.justRTP.JustRTP;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

public class RTPZone {
    private final String id;
    private final String worldName;
    private final ZoneRegion region;
    private final int interval;
    private final List<String> targets;
    private final int minRadius;
    private final int maxRadius;
    private final int minSpreadDistance;
    private final int maxSpreadDistance;
    private Location hologramLocation;
    private int hologramViewDistance;
    private ZoneParticleStyle particleStyle = ZoneParticleStyle.NONE;
    private final String configPath;

    public RTPZone(String id, ConfigurationSection section) {
        this.id = id;
        this.configPath = section.getCurrentPath();
        this.worldName = section.getString("world");
        if (worldName == null || Bukkit.getWorld(worldName) == null) {
            throw new IllegalArgumentException("Invalid or missing world name for zone '" + id + "'.");
        }

        ZoneShape shape = ZoneShape.fromString(section.getString("shape"));
        if (section.getString("shape") == null && section.contains("pos1") && section.contains("pos2")) {
            shape = ZoneShape.CUBOID;
        }

        switch (shape) {
            case CYLINDER:
                this.region = CylinderRegion.load(section);
                break;
            case BLOCKS:
                this.region = BlocksRegion.load(section);
                break;
            case CUBOID:
            default:
                this.region = CuboidRegion.load(section);
                break;
        }

        this.interval = section.getInt("interval", 30);

        this.targets = new ArrayList<>();
        if (section.isList("target")) {
            this.targets.addAll(section.getStringList("target"));
        } else {
            String targetStr = section.getString("target");
            if (targetStr != null) {
                if (targetStr.contains(",")) {
                    for (String t : targetStr.split(",")) {
                        String trimmed = t.trim();
                        if (!trimmed.isEmpty()) {
                            this.targets.add(trimmed);
                        }
                    }
                } else {
                    this.targets.add(targetStr);
                }
            }
        }

        if (this.targets.isEmpty()) {
            throw new IllegalArgumentException("Missing target world/server for zone '" + id + "'.");
        }

        this.minRadius = section.getInt("min-radius", 100);
        this.maxRadius = section.getInt("max-radius", 1000);
        this.minSpreadDistance = section.getInt("min-spread-distance",
                JustRTP.getInstance().getConfig().getInt("zone_teleport_settings.min_spread_distance", 5));
        this.maxSpreadDistance = section.getInt("max-spread-distance",
                JustRTP.getInstance().getConfig().getInt("zone_teleport_settings.max_spread_distance", 15));

        if (section.isConfigurationSection("hologram")) {
            this.hologramLocation = section.getLocation("hologram.location");
            this.hologramViewDistance = section.getInt("hologram.view-distance", 64);
        }

        String particleStyleStr = section.getString("particle-style");
        if (particleStyleStr == null) {
            try {
                JustRTP inst = JustRTP.getInstance();
                if (inst != null && inst.getZoneParticleManager() != null) {
                    particleStyleStr = inst.getZoneParticleManager().getDefaultStyle();
                }
            } catch (Exception ignored) {
            }
        }
        this.particleStyle = ZoneParticleStyle.fromString(particleStyleStr);
    }

    public RTPZone(String id, String worldName, ZoneRegion region, List<String> targets,
                   int interval, int minRadius, int maxRadius,
                   int minSpreadDistance, int maxSpreadDistance,
                   Location hologramLocation, int hologramViewDistance, String configPath) {
        this.id = id;
        this.worldName = worldName;
        this.region = region;
        this.targets = new ArrayList<>(targets);
        this.interval = interval;
        this.minRadius = minRadius;
        this.maxRadius = maxRadius;
        this.minSpreadDistance = minSpreadDistance;
        this.maxSpreadDistance = maxSpreadDistance;
        this.hologramLocation = hologramLocation;
        this.hologramViewDistance = hologramViewDistance;
        this.configPath = configPath;
    }

    public void serialize(ConfigurationSection section) {
        section.set("world", worldName);
        region.serialize(section);
        section.set("interval", interval);
        section.set("target", targets);
        section.set("min-radius", minRadius);
        section.set("max-radius", maxRadius);
        section.set("min-spread-distance", minSpreadDistance);
        section.set("max-spread-distance", maxSpreadDistance);
        if (hologramLocation != null) {
            section.set("hologram.location", hologramLocation);
            section.set("hologram.view-distance", hologramViewDistance);
        } else {
            section.set("hologram", null);
        }
        if (particleStyle != null && particleStyle != ZoneParticleStyle.NONE) {
            section.set("particle-style", particleStyle.name());
        } else {
            section.set("particle-style", null);
        }
    }

    public ZoneParticleStyle getParticleStyle() {
        return particleStyle != null ? particleStyle : ZoneParticleStyle.NONE;
    }

    public void setParticleStyle(ZoneParticleStyle style) {
        this.particleStyle = style != null ? style : ZoneParticleStyle.NONE;
    }

    public boolean contains(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        if (!loc.getWorld().getName().equals(worldName)) return false;
        return region.contains(loc);
    }

    public Location getCenterLocation() {
        return region.getCenter();
    }

    public ZoneRegion getRegion() {
        return region;
    }

    public ZoneShape getShape() {
        return region.getShape();
    }

    public String getId() {
        return id;
    }

    public int getInterval() {
        return interval;
    }

    public List<String> getTargets() {
        return targets;
    }

    public int getMinRadius() {
        return minRadius;
    }

    public int getMaxRadius() {
        return maxRadius;
    }

    public int getMinSpreadDistance() {
        return minSpreadDistance;
    }

    public int getMaxSpreadDistance() {
        return maxSpreadDistance;
    }

    public String getOnEnterEffectsPath() {
        return configPath + ".effects.on_enter";
    }

    public String getOnLeaveEffectsPath() {
        return configPath + ".effects.on_leave";
    }

    public String getWaitingEffectsPath() {
        return configPath + ".effects.waiting";
    }

    public String getTeleportEffectsPath() {
        return configPath + ".effects.teleport";
    }

    public Location getHologramLocation() {
        return hologramLocation;
    }

    public int getHologramViewDistance() {
        return hologramViewDistance;
    }

    public void setHologramData(Location location, int viewDistance) {
        this.hologramLocation = location;
        this.hologramViewDistance = viewDistance;
    }
}
