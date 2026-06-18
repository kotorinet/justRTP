package eu.kotori.justRTP.handlers;

import eu.kotori.justRTP.JustRTP;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

public class WorldListener implements Listener {
    private final JustRTP plugin;

    public WorldListener(JustRTP plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        plugin.getRtpService().loadConfigValues();
        plugin.getLocationCacheManager().initialize();
    }
}
