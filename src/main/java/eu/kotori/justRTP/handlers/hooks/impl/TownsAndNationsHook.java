package eu.kotori.justRTP.handlers.hooks.impl;

import eu.kotori.justRTP.handlers.hooks.RegionHook;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

public class TownsAndNationsHook implements RegionHook {

    private static Class<?> claimManagerClass;
    private static Method isChunkClaimedMethod;

    static {
        try {
            claimManagerClass = Class.forName("org.tan.TownsAndNations.DataClass.ClaimManager");
            isChunkClaimedMethod = claimManagerClass.getMethod("isChunkClaimed", Chunk.class);
        } catch (Exception e) {
            Plugin tan = Bukkit.getPluginManager().getPlugin("TownsAndNations");
            if (tan != null) {
                tan.getLogger().info("TownsAndNations detected but API reflection failed: " + e.getMessage());
                tan.getLogger().info("This is normal - JustRTP will try alternative API access methods");
            }
        }
    }

    @Override
    public boolean isLocationSafe(Location location) {
        if (claimManagerClass == null || isChunkClaimedMethod == null) {
            return true;
        }

        try {
            Chunk chunk = location.getChunk();

            Boolean isClaimed = (Boolean) isChunkClaimedMethod.invoke(null, chunk);

            return isClaimed == null || !isClaimed;

        } catch (Exception e) {
            Plugin tan = Bukkit.getPluginManager().getPlugin("TownsAndNations");
            if (tan != null) {
                tan.getLogger().warning("Error checking Towns and Nations chunk claim: " + e.getMessage());
            }
            return true;
        }
    }
}
