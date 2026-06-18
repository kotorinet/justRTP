package eu.kotori.justRTP.utils.task;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

public class FoliaCancellableTask implements CancellableTask {
    private final ScheduledTask task;

    public FoliaCancellableTask(ScheduledTask task) {
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
