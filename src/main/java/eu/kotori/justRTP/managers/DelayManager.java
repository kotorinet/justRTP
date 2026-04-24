package eu.kotori.justRTP.managers;

import eu.kotori.justRTP.JustRTP;
import eu.kotori.justRTP.utils.task.CancellableTask;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import eu.kotori.justRTP.utils.TimeUtils;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DelayManager {
    private final JustRTP plugin;
    private final Map<UUID, CancellableTask> delayedTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Location> initialLocations = new ConcurrentHashMap<>();

    public DelayManager(JustRTP plugin) {
        this.plugin = plugin;
    }

    public void startDelay(Player player, Runnable onDelayFinish, int seconds) {
        if (seconds <= 0) {
            onDelayFinish.run();
            return;
        }

        initialLocations.put(player.getUniqueId(), player.getLocation());
        plugin.getAnimationManager().playDelayAnimation(player, seconds);

        plugin.getLocaleManager().sendMessage(player, "teleport.delay",
                Placeholder.unparsed("time", TimeUtils.formatDuration(seconds)));
        plugin.getEffectsManager().sendDelayActionBar(player, seconds);
        plugin.getEffectsManager().playDelaySound(player, seconds, seconds);

        java.util.concurrent.atomic.AtomicInteger countdown = new java.util.concurrent.atomic.AtomicInteger(seconds);

        CancellableTask task = plugin.getFoliaScheduler().runTimerAtEntity(player, () -> {
            int remaining = countdown.decrementAndGet();

            if (remaining <= 0) {
                CancellableTask removedTask = delayedTasks.remove(player.getUniqueId());
                if (removedTask != null) {
                    removedTask.cancel();
                }

                initialLocations.remove(player.getUniqueId());
                plugin.getAnimationManager().stopDelayAnimation(player);
                plugin.getEffectsManager().removeTransitionEffects(player);

                onDelayFinish.run();
                return;
            }

            plugin.getEffectsManager().sendDelayActionBar(player, remaining);
            plugin.getEffectsManager().playDelaySound(player, remaining, seconds);

        }, 20L, 20L);

        delayedTasks.put(player.getUniqueId(), task);

        plugin.getEffectsManager().applyPreTeleportEffects(player, seconds);
    }

    private void cancelTask(UUID uuid) {
        CancellableTask task = delayedTasks.get(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    public boolean isDelayed(UUID uuid) {
        return delayedTasks.containsKey(uuid);
    }

    public Location getInitialLocation(UUID uuid) {
        return initialLocations.get(uuid);
    }

    public void cancelDelay(Player player) {
        CancellableTask task = delayedTasks.remove(player.getUniqueId());
        initialLocations.remove(player.getUniqueId());
        if (task != null) {
            if (!task.isCancelled()) {
                task.cancel();
            }
            plugin.getEffectsManager().removeTransitionEffects(player);
            plugin.getAnimationManager().stopDelayAnimation(player);
            plugin.getLocaleManager().sendMessage(player, "teleport.cancelled");
        }
    }
}