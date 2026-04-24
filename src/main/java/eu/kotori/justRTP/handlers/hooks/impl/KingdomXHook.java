package eu.kotori.justRTP.handlers.hooks.impl;

import eu.kotori.justRTP.handlers.hooks.RegionHook;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

public class KingdomXHook implements RegionHook {

    private static Class<?> landClass;
    private static Method getLandMethod;
    private static Method isClaimedMethod;

    static {
        try {
            landClass = Class.forName("org.kingdoms.constants.land.Land");
            getLandMethod = landClass.getMethod("getLand", Location.class);
            isClaimedMethod = landClass.getMethod("isClaimed");
        } catch (Exception e) {
            Plugin kingdomsX = Bukkit.getPluginManager().getPlugin("KingdomsX");
            if (kingdomsX != null) {
                kingdomsX.getLogger().warning("Could not initialize KingdomsX hook: " + e.getMessage());
            }
        }
    }

    @Override
    public boolean isLocationSafe(Location location) {
        if (landClass == null || getLandMethod == null || isClaimedMethod == null) {
            return true;
        }

        try {
            Object land = getLandMethod.invoke(null, location);

            if (land == null) {
                return true;
            }

            Boolean isClaimed = (Boolean) isClaimedMethod.invoke(land);

            return isClaimed == null || !isClaimed;

        } catch (Exception e) {
            Plugin kingdomsX = Bukkit.getPluginManager().getPlugin("KingdomsX");
            if (kingdomsX != null) {
                kingdomsX.getLogger().warning("Error checking KingdomsX land claim: " + e.getMessage());
            }
            return true;
        }
    }
}
