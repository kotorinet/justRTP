package eu.kotori.justRTP.utils;

import eu.kotori.justRTP.JustRTP;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

public class RTPZone {
    private final String id;
    private final String worldName;
    private final Cuboid cuboid;
    private final int interval;
    private final List<String> targets;
    private final int minRadius;
    private final int maxRadius;
    private final int minSpreadDistance;
    private final int maxSpreadDistance;
    private Location hologramLocation;
    private int hologramViewDistance;
    private final String configPath;

    public RTPZone(String id, ConfigurationSection section) {
        this.id = id;
        this.configPath = section.getCurrentPath();
        this.worldName = section.getString("world");
        if (worldName == null || Bukkit.getWorld(worldName) == null) {
            throw new IllegalArgumentException("Invalid or missing world name for zone '" + id + "'.");
        }
        Location pos1 = section.getLocation("pos1");
        Location pos2 = section.getLocation("pos2");
        if (pos1 == null || pos2 == null) {
            throw new IllegalArgumentException("Missing position 1 or 2 for zone '" + id + "'.");
        }
        this.cuboid = new Cuboid(pos1, pos2);
        this.interval = section.getInt("interval", 30);

        this.targets = new java.util.ArrayList<>();
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
    }

    public void serialize(ConfigurationSection section) {
        section.set("world", worldName);
        section.set("pos1", cuboid.getLowerNE());
        section.set("pos2", cuboid.getUpperSW());
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
    }

    public boolean contains(Location loc) {
        return loc.getWorld().getName().equals(worldName) && cuboid.contains(loc);
    }

    public Location getCenterLocation() {
        return cuboid.getCenter();
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