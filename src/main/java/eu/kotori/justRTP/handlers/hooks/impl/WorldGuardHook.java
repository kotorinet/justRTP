package eu.kotori.justRTP.handlers.hooks.impl;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import eu.kotori.justRTP.handlers.hooks.RegionHook;
import org.bukkit.Location;
public class WorldGuardHook implements RegionHook {
    @Override
    public boolean isLocationSafe(Location location) {
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        RegionManager regions = container.get(BukkitAdapter.adapt(location.getWorld()));
        if (regions == null) return true;
        return regions.getApplicableRegions(BukkitAdapter.asBlockVector(location)).size() == 0;
    }
}
