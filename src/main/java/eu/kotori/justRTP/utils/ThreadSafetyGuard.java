package eu.kotori.justRTP.utils;

import eu.kotori.justRTP.JustRTP;
import org.bukkit.Bukkit;

public class ThreadSafetyGuard {
    private final JustRTP plugin;
    private final boolean strictMode;

    public ThreadSafetyGuard(JustRTP plugin) {
        this.plugin = plugin;
        this.strictMode = true;
    }

    public boolean isMainThread() {
        if (FoliaScheduler.isFolia()) {
            String threadName = Thread.currentThread().getName();
            return threadName.contains("RegionScheduler") ||
                    threadName.contains("GlobalRegionScheduler") ||
                    threadName.contains("Tick-");
        } else {
            return Bukkit.isPrimaryThread();
        }
    }

    public void assertNotMainThread(String operation) {
        if (isMainThread()) {
            String error = String.format(
                    "[THREAD SAFETY VIOLATION] %s called on MAIN THREAD! " +
                            "This operation MUST run asynchronously. Thread: %s",
                    operation,
                    Thread.currentThread().getName());

            if (strictMode) {
                plugin.getLogger().severe(error);
                plugin.getLogger().severe("Stack trace:");
                StackTraceElement[] trace = Thread.currentThread().getStackTrace();
                for (int i = 2; i < Math.min(10, trace.length); i++) {
                    plugin.getLogger().severe("  at " + trace[i]);
                }
                throw new IllegalStateException(error);
            } else {
                plugin.getLogger().warning(error);
            }
        }
    }

    public void assertAsyncDatabase(String methodName) {
        assertNotMainThread("Database." + methodName + "()");
    }

    public void assertAsyncRedis(String methodName) {
        assertNotMainThread("Redis." + methodName + "()");
    }

    public void logAsyncExecution(String operation) {
        plugin.getRTPLogger().debug("THREAD", String.format(
                "[ASYNC-OK] %s running on async thread: %s",
                operation,
                Thread.currentThread().getName()));
    }

    public String getThreadInfo() {
        Thread t = Thread.currentThread();
        return String.format(
                "Thread[name=%s, id=%d, main=%b]",
                t.getName(),
                t.threadId(),
                isMainThread());
    }
}
