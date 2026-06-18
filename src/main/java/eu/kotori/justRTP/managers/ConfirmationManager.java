package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ConfirmationManager {
    private final JustRTP plugin;
    private final Map<UUID, Runnable> pendingConfirmations = new ConcurrentHashMap<>();

    public ConfirmationManager(JustRTP plugin) {
        this.plugin = plugin;
    }

    public void addPendingConfirmation(Player player, Runnable action) {
        pendingConfirmations.put(player.getUniqueId(), action);

        plugin.getFoliaScheduler().runAtEntityLater(player, () -> {
            if (pendingConfirmations.containsKey(player.getUniqueId())) {
                pendingConfirmations.remove(player.getUniqueId());
                plugin.getLocaleManager().sendMessage(player, "teleport.cancelled");
            }
        }, 30 * 20L);
    }

    public boolean hasPendingConfirmation(Player player) {
        return pendingConfirmations.containsKey(player.getUniqueId());
    }

    public void confirm(Player player) {
        Runnable action = pendingConfirmations.remove(player.getUniqueId());
        if (action != null) {
            plugin.getLocaleManager().sendMessage(player, "economy.confirmed");
            action.run();
        } else {
            plugin.getLocaleManager().sendMessage(player, "economy.no_confirmation_pending");
        }
    }
}
