package eu.kotori.justRTP.utils;

import eu.kotori.justRTP.JustRTP;
import eu.kotori.justRTP.utils.task.BukkitCancellableTask;
import eu.kotori.justRTP.utils.task.CancellableTask;
import eu.kotori.justRTP.utils.task.FoliaCancellableTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

public class FoliaScheduler {
    private final JustRTP plugin;
    private static boolean IS_FOLIA = false;

    public FoliaScheduler(JustRTP plugin) {
        this.plugin = plugin;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            IS_FOLIA = true;
            plugin.getLogger().info("Folia detected. Using Folia-specific schedulers.");
        } catch (ClassNotFoundException e) {
            IS_FOLIA = false;
            plugin.getLogger().info("Folia not detected. Using standard Bukkit schedulers.");
        }
    }

    public static boolean isFolia() {
        return IS_FOLIA;
    }

    public void runNow(Runnable task) {
        if (IS_FOLIA) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void runAsync(Runnable task) {
        if (IS_FOLIA) {
            Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    public CancellableTask runLater(Runnable task, long delayTicks) {
        if (IS_FOLIA) {
            return new FoliaCancellableTask(
                    Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), delayTicks));
        } else {
            return new BukkitCancellableTask(Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks));
        }
    }

    public CancellableTask runTimer(Runnable task, long delayTicks, long periodTicks) {
        if (IS_FOLIA) {
            long foliaDelay = Math.max(1, delayTicks);
            return new FoliaCancellableTask(
                    Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> task.run(), foliaDelay, periodTicks));
        } else {
            return new BukkitCancellableTask(Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks));
        }
    }

    public void runAtLocation(Location location, Runnable task) {
        if (IS_FOLIA) {
            Bukkit.getRegionScheduler().execute(plugin, location, task);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public CancellableTask runTimerAtLocation(Location location, Runnable task, long delayTicks, long periodTicks) {
        if (IS_FOLIA) {
            long foliaDelay = Math.max(1, delayTicks);
            return new FoliaCancellableTask(Bukkit.getRegionScheduler().runAtFixedRate(plugin, location,
                    t -> task.run(), foliaDelay, periodTicks));
        } else {
            return new BukkitCancellableTask(Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks));
        }
    }

    public void runAtChunk(Chunk chunk, Runnable task) {
        if (IS_FOLIA) {
            Bukkit.getRegionScheduler().execute(plugin, chunk.getWorld(), chunk.getX(), chunk.getZ(), task);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public void runAtEntity(Entity entity, Runnable task) {
        if (IS_FOLIA) {
            entity.getScheduler().execute(plugin, task, null, 1L);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public CancellableTask runAtEntityLater(Entity entity, Runnable task, long delayTicks) {
        if (IS_FOLIA) {
            return new FoliaCancellableTask(
                    entity.getScheduler().runDelayed(plugin, t -> task.run(), null, delayTicks));
        } else {
            return new BukkitCancellableTask(Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks));
        }
    }

    public CancellableTask runTimerAtEntity(Entity entity, Runnable task, long delayTicks, long periodTicks) {
        if (IS_FOLIA) {
            long foliaDelay = Math.max(1, delayTicks);
            return new FoliaCancellableTask(
                    entity.getScheduler().runAtFixedRate(plugin, t -> task.run(), null, foliaDelay, periodTicks));
        } else {
            return new BukkitCancellableTask(Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks));
        }
    }
}