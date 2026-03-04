package eu.kotori.justRTP.handlers.hooks.impl;

import eu.kotori.justRTP.handlers.hooks.RegionHook;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

public class SimpleClaimSystemHook implements RegionHook {

    private static Class<?> simpleClaimSystemClass;
    private static Class<?> claimMainClass;
    private static Method getMainMethod;
    private static Method getClaimMethod;

    static {
        try {
            simpleClaimSystemClass = Class.forName("fr.xyness.SCS.SimpleClaimSystem");
            claimMainClass = Class.forName("fr.xyness.SCS.ClaimMain");
            getMainMethod = simpleClaimSystemClass.getMethod("getMain");
            getClaimMethod = claimMainClass.getMethod("getClaim", Chunk.class);
        } catch (Exception e) {
            Plugin scs = Bukkit.getPluginManager().getPlugin("SimpleClaimSystem");
            if (scs != null) {
                scs.getLogger().info("SimpleClaimSystem detected but API reflection failed: " + e.getMessage());
                scs.getLogger().info("This is normal - JustRTP will try alternative API access methods");
            }
        }
    }

    @Override
    public boolean isLocationSafe(Location location) {
        if (simpleClaimSystemClass == null || getMainMethod == null || getClaimMethod == null) {
            return true;
        }

        try {
            Chunk chunk = location.getChunk();

            Plugin scsPlugin = Bukkit.getPluginManager().getPlugin("SimpleClaimSystem");
            if (scsPlugin == null || !scsPlugin.isEnabled()) {
                return true;
            }

            Object claimMain = getMainMethod.invoke(scsPlugin);
            if (claimMain == null) {
                return true;
            }

            Object claim = getClaimMethod.invoke(claimMain, chunk);

            return claim == null;

        } catch (Exception e) {
            Plugin scs = Bukkit.getPluginManager().getPlugin("SimpleClaimSystem");
            if (scs != null) {
                scs.getLogger().warning("Error checking SimpleClaimSystem chunk claim: " + e.getMessage());
            }
            return true;
        }
    }
}
