package eu.kotori.justRTP.managers;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import eu.kotori.justRTP.JustRTP;
import org.bukkit.entity.Player;

public class ProxyManager {

    private final JustRTP plugin;
    private final String BUNGEE_CORD_CHANNEL = "BungeeCord";

    public ProxyManager(JustRTP plugin) {
        this.plugin = plugin;
    }

    public boolean isProxyEnabled() {
        return plugin.getConfig().getBoolean("proxy.enabled", false);
    }

    public void sendPlayerToServer(Player player, String serverName) {
        if (!isProxyEnabled()) {
            plugin.getRTPLogger().debug("PROXY", "Attempted to send player " + player.getName() + " to server "
                    + serverName + ", but proxy feature is disabled.");
            return;
        }

        plugin.getRTPLogger().debug("PROXY", "Sending player " + player.getName() + " to proxy server '" + serverName
                + "' via " + BUNGEE_CORD_CHANNEL + " channel.");

        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(serverName);

        player.sendPluginMessage(plugin, BUNGEE_CORD_CHANNEL, out.toByteArray());
    }
}