package eu.kotori.justRTP.utils;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

public class CuboidRegion implements ZoneRegion {
    private final Cuboid cuboid;
    private final Location pos1;
    private final Location pos2;
    private final String worldName;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public CuboidRegion(Location pos1, Location pos2) {
        this.pos1 = pos1;
        this.pos2 = pos2;
        this.cuboid = new Cuboid(pos1, pos2);
        this.worldName = pos1.getWorld().getName();
        this.minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        this.minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
        this.minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        this.maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
        this.maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
        this.maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
    }

    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }

    public static CuboidRegion load(ConfigurationSection section) {
        Location pos1 = section.getLocation("pos1");
        Location pos2 = section.getLocation("pos2");
        if (pos1 == null || pos2 == null) {
            throw new IllegalArgumentException("Missing pos1 or pos2 for cuboid zone.");
        }
        return new CuboidRegion(pos1, pos2);
    }

    @Override
    public ZoneShape getShape() {
        return ZoneShape.CUBOID;
    }

    @Override
    public String getWorldName() {
        return worldName;
    }

    @Override
    public boolean contains(Location location) {
        return cuboid.contains(location);
    }

    @Override
    public Location getCenter() {
        return cuboid.getCenter();
    }

    @Override
    public void serialize(ConfigurationSection section) {
        section.set("shape", ZoneShape.CUBOID.name());
        section.set("pos1", pos1);
        section.set("pos2", pos2);
        section.set("center", null);
        section.set("radius", null);
        section.set("min-y", null);
        section.set("max-y", null);
        section.set("blocks", null);
    }
}
