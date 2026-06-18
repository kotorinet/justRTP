package eu.kotori.justRTP.utils.task;

public interface CancellableTask {
    void cancel();

    boolean isCancelled();
}
