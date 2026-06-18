package eu.kotori.justRTP.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

public class CylinderRegion implements ZoneRegion {
    private final String worldName;
    private final double centerX;
    private final double centerZ;
    private final double centerY;
    private final int radius;
    private final int minY;
    private final int maxY;
    private final double radiusSquared;

    public CylinderRegion(Location center, int radius, int minY, int maxY) {
        if (center.getWorld() == null) {
            throw new IllegalArgumentException("Center location has no world.");
        }
        if (radius <= 0) {
            throw new IllegalArgumentException("Cylinder radius must be positive.");
        }
        if (maxY < minY) {
            throw new IllegalArgumentException("Cylinder max-y must be >= min-y.");
        }
        this.worldName = center.getWorld().getName();
        this.centerX = center.getBlockX() + 0.5;
        this.centerZ = center.getBlockZ() + 0.5;
        this.centerY = center.getBlockY() + 0.5;
        this.radius = radius;
        this.minY = minY;
        this.maxY = maxY;
        this.radiusSquared = (double) radius * radius;
    }

    public static CylinderRegion load(ConfigurationSection section) {
        Location center = section.getLocation("center");
        if (center == null) {
            throw new IllegalArgumentException("Missing center for cylinder zone.");
        }
        int radius = section.getInt("radius", 0);
        if (radius <= 0) {
            throw new IllegalArgumentException("Missing or invalid radius for cylinder zone.");
        }
        World world = center.getWorld();
        int defaultMin = world != null ? world.getMinHeight() : -64;
        int defaultMax = world != null ? world.getMaxHeight() - 1 : 319;
        int minY = section.getInt("min-y", defaultMin);
        int maxY = section.getInt("max-y", defaultMax);
        return new CylinderRegion(center, radius, minY, maxY);
    }

    @Override
    public ZoneShape getShape() {
        return ZoneShape.CYLINDER;
    }

    @Override
    public String getWorldName() {
        return worldName;
    }

    @Override
    public boolean contains(Location location) {
        if (location == null || location.getWorld() == null) return false;
        if (!location.getWorld().getName().equals(worldName)) return false;
        if (location.getBlockY() < minY || location.getBlockY() > maxY) return false;
        double dx = location.getX() - centerX;
        double dz = location.getZ() - centerZ;
        return (dx * dx + dz * dz) <= radiusSquared;
    }

    @Override
    public Location getCenter() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, centerX, centerY, centerZ);
    }

    @Override
    public void serialize(ConfigurationSection section) {
        section.set("shape", ZoneShape.CYLINDER.name());
        World world = Bukkit.getWorld(worldName);
        if (world != null) {
            section.set("center", new Location(world, centerX - 0.5, centerY - 0.5, centerZ - 0.5));
        }
        section.set("radius", radius);
        section.set("min-y", minY);
        section.set("max-y", maxY);
        section.set("pos1", null);
        section.set("pos2", null);
        section.set("blocks", null);
    }

    public int getRadius() {
        return radius;
    }

    public int getMinY() {
        return minY;
    }

    public int getMaxY() {
        return maxY;
    }
}
