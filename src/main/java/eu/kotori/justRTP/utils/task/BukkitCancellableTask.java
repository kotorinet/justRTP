package eu.kotori.justRTP.utils.task;

import org.bukkit.scheduler.BukkitTask;

public class BukkitCancellableTask implements CancellableTask {
    private final BukkitTask task;

    public BukkitCancellableTask(BukkitTask task) {
        this.task = task;
    }

    @Override
    public void cancel() {
        task.cancel();
    }

    @Override
    public boolean isCancelled() {
        return task.isCancelled();
    }
}
