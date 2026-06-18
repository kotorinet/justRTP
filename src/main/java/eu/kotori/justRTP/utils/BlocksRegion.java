package eu.kotori.justRTP.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BlocksRegion implements ZoneRegion {
    private final String worldName;
    private final Set<Long> blockKeys;
    private final List<int[]> blocks;

    public BlocksRegion(String worldName, Collection<int[]> positions) {
        if (worldName == null) throw new IllegalArgumentException("Blocks region requires a world.");
        if (positions == null || positions.isEmpty()) {
            throw new IllegalArgumentException("Blocks region needs at least one block.");
        }
        this.worldName = worldName;
        this.blockKeys = new HashSet<>(positions.size() * 2);
        this.blocks = new ArrayList<>(positions.size());
        for (int[] pos : positions) {
            if (pos == null || pos.length != 3) continue;
            addInternal(pos[0], pos[1], pos[2]);
        }
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("Blocks region needs at least one valid block.");
        }
    }

    public static BlocksRegion load(ConfigurationSection section) {
        String worldName = section.getString("world");
        List<String> raw = section.getStringList("blocks");
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("Blocks zone has no blocks listed.");
        }
        List<int[]> positions = new ArrayList<>(raw.size());
        for (String entry : raw) {
            String[] parts = entry.split(",");
            if (parts.length != 3) continue;
            try {
                int x = Integer.parseInt(parts[0].trim());
                int y = Integer.parseInt(parts[1].trim());
                int z = Integer.parseInt(parts[2].trim());
                positions.add(new int[]{x, y, z});
            } catch (NumberFormatException ignored) {
            }
        }
        return new BlocksRegion(worldName, positions);
    }

    private void addInternal(int x, int y, int z) {
        long key = pack(x, y, z);
        if (blockKeys.add(key)) {
            blocks.add(new int[]{x, y, z});
        }
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((long) (y & 0xFFF));
    }

    @Override
    public ZoneShape getShape() {
        return ZoneShape.BLOCKS;
    }

    @Override
    public String getWorldName() {
        return worldName;
    }

    @Override
    public boolean contains(Location location) {
        if (location == null || location.getWorld() == null) return false;
        if (!location.getWorld().getName().equals(worldName)) return false;
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        if (blockKeys.contains(pack(x, y, z))) return true;
        return blockKeys.contains(pack(x, y - 1, z));
    }

    @Override
    public Location getCenter() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        long sx = 0, sy = 0, sz = 0;
        for (int[] pos : blocks) {
            sx += pos[0];
            sy += pos[1];
            sz += pos[2];
        }
        int count = blocks.size();
        return new Location(world, sx / (double) count + 0.5, sy / (double) count + 0.5, sz / (double) count + 0.5);
    }

    @Override
    public void serialize(ConfigurationSection section) {
        section.set("shape", ZoneShape.BLOCKS.name());
        List<String> encoded = new ArrayList<>(blocks.size());
        for (int[] pos : blocks) {
            encoded.add(pos[0] + "," + pos[1] + "," + pos[2]);
        }
        section.set("blocks", encoded);
        section.set("pos1", null);
        section.set("pos2", null);
        section.set("center", null);
        section.set("radius", null);
        section.set("min-y", null);
        section.set("max-y", null);
    }

    public int size() {
        return blocks.size();
    }

    public List<int[]> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }
}
