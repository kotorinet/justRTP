package eu.kotori.justRTP.utils;

import eu.kotori.justRTP.JustRTP;
import io.papermc.lib.PaperLib;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.concurrent.CompletableFuture;

public class SafetyValidator {

    public static CompletableFuture<Boolean> isLocationAbsolutelySafeAsync(Location location) {
        if (location == null || location.getWorld() == null) {
            return CompletableFuture.completedFuture(false);
        }

        return PaperLib.getChunkAtAsync(location)
                .thenApply(chunk -> {
                    if (chunk == null)
                        return false;
                    return isLocationSafeInternal(location, chunk);
                })
                .exceptionally(ex -> {
                    JustRTP.getInstance().getLogger().warning("Error checking safety async: " + ex.getMessage());
                    return false;
                });
    }

    @Deprecated
    public static boolean isLocationAbsolutelySafe(Location location) {
        if (location == null || location.getWorld() == null)
            return false;
        try {
            if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                return false;
            }
            return isLocationSafeInternal(location, location.getChunk());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isLocationSafeInternal(Location location, Chunk chunk) {
        World world = location.getWorld();
        World.Environment env = world.getEnvironment();

        switch (env) {
            case NETHER:
                return isNetherLocationSafe(location, chunk);
            case THE_END:
                return isEndLocationSafe(location, chunk);
            case NORMAL:
            default:
                return isOverworldLocationSafe(location, chunk);
        }
    }

    private static boolean isNetherLocationSafe(Location location, Chunk chunk) {
        int x = location.getBlockX() & 15;
        int z = location.getBlockZ() & 15;
        double y = location.getY();

        if (y >= 126.0 || (y + 1.0) >= 127.0)
            return false;
        if (y < 5)
            return false;

        Block groundBlock = chunk.getBlock(x, (int) y - 1, z);
        Block feetBlock = chunk.getBlock(x, (int) y, z);
        Block headBlock = chunk.getBlock(x, (int) y + 1, z);

        if (!groundBlock.getType().isSolid())
            return false;
        if (isDangerousBlock(groundBlock.getType()))
            return false;
        if (feetBlock.getType().isSolid() || headBlock.getType().isSolid())
            return false;
        if (hasLavaNearby(location, chunk))
            return false;

        if (chunk.getWorld().getEnvironment() == World.Environment.NETHER) {
            Block ceiling = chunk.getBlock(x, 127, z);
            if (ceiling.getType() == Material.BEDROCK && y >= 120)
                return false;
        }

        return true;
    }

    private static boolean isEndLocationSafe(Location location, Chunk chunk) {
        int x = location.getBlockX() & 15;
        int z = location.getBlockZ() & 15;
        double y = location.getY();

        if (y < 10 || y > 120)
            return false;

        Block groundBlock = chunk.getBlock(x, (int) y - 1, z);
        if (!groundBlock.getType().isSolid())
            return false;

        Material groundType = groundBlock.getType();
        if (groundType != Material.END_STONE &&
                groundType != Material.OBSIDIAN &&
                !groundType.isSolid()) {
            if (groundType != Material.END_STONE && groundType != Material.OBSIDIAN)
                return false;
        }

        Block feetBlock = chunk.getBlock(x, (int) y, z);
        Block headBlock = chunk.getBlock(x, (int) y + 1, z);

        if (feetBlock.getType().isSolid() || headBlock.getType().isSolid())
            return false;

        boolean hasGroundBelow = false;
        int blockY = (int) y;
        for (int checkY = blockY - 1; checkY > Math.max(0, blockY - 10); checkY--) {
            Block checkBlock = chunk.getBlock(x, checkY, z);
            if (checkBlock.getType().isSolid()) {
                hasGroundBelow = true;
                break;
            }
        }
        if (!hasGroundBelow)
            return false;

        return true;
    }

    private static boolean isOverworldLocationSafe(Location location, Chunk chunk) {
        int x = location.getBlockX() & 15;
        int z = location.getBlockZ() & 15;
        double y = location.getY();
        World world = chunk.getWorld();

        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();

        if (y < minHeight + 5)
            return false;
        if (y > maxHeight - 10)
            return false;
        if (y >= 255)
            return false;

        Block groundBlock = chunk.getBlock(x, (int) y - 1, z);
        Block feetBlock = chunk.getBlock(x, (int) y, z);
        Block headBlock = chunk.getBlock(x, (int) y + 1, z);

        if (!groundBlock.getType().isSolid())
            return false;
        if (isDangerousBlock(groundBlock.getType()))
            return false;

        if (feetBlock.getType().isSolid() || headBlock.getType().isSolid())
            return false;
        if (feetBlock.isLiquid() || headBlock.isLiquid() || groundBlock.isLiquid())
            return false;

        if (hasLavaNearby(location, chunk))
            return false;

        return true;
    }

    private static boolean isDangerousBlock(Material material) {
        switch (material) {
            case LAVA:
            case MAGMA_BLOCK:
            case FIRE:
            case SOUL_FIRE:
            case CAMPFIRE:
            case SOUL_CAMPFIRE:
            case CACTUS:
            case SWEET_BERRY_BUSH:
            case POWDER_SNOW:
            case WITHER_ROSE:
                return true;
            default:
                return false;
        }
    }

    private static boolean hasLavaNearby(Location location, Chunk chunk) {
        int cx = location.getBlockX() & 15;
        int cy = location.getBlockY();
        int cz = location.getBlockZ() & 15;

        for (int xOff = -1; xOff <= 1; xOff++) {
            for (int yOff = -1; yOff <= 1; yOff++) {
                for (int zOff = -1; zOff <= 1; zOff++) {
                    int nx = cx + xOff;
                    int nz = cz + zOff;
                    if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16) {
                        Block block = chunk.getBlock(nx, cy + yOff, nz);
                        if (block.getType() == Material.LAVA)
                            return true;
                    }
                }
            }
        }
        return false;
    }

    public static String getUnsafeReason(Location location) {
        return "Unsafe location (async check failed)";
    }
}
