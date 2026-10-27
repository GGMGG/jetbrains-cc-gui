package com.github.claudecodegui.bridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Keeps observed descendants owned even when their parent exits or is unregistered. */
final class ManagedProcessTree {
    private static final ScheduledThreadPoolExecutor MONITOR = createMonitor();

    private final Process process;
    private final Map<Long, ProcessHandle> descendants = new ConcurrentHashMap<>();
    private final Runnable onReleased;
    private ScheduledFuture<?> monitoring;
    private boolean releaseRequested;
    private boolean released;
    private boolean observationFailed;

    ManagedProcessTree(Process process, Runnable onReleased) {
        this.process = process;
        this.onReleased = onReleased;
        captureDescendants();
        monitoring = MONITOR.scheduleWithFixedDelay(this::poll, 25L, 25L, TimeUnit.MILLISECONDS);
        try {
            process.onExit().thenRun(this::poll);
        } catch (UnsupportedOperationException ignored) {
            // The bounded monitor also supports Process implementations without onExit.
        }
    }

    private static ScheduledThreadPoolExecutor createMonitor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "ai-process-tree-monitor");
            thread.setDaemon(true);
            thread.setContextClassLoader(null);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setKeepAliveTime(1L, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private void captureDescendants() {
        try {
            if (process.isAlive()) {
                try (var children = process.descendants()) {
                    children.forEach(child -> descendants.put(child.pid(), child));
                }
            }
            // A reparented child can still start tools after the original parent exits.
            for (ProcessHandle child : descendants.values()) {
                if (child.isAlive()) {
                    try (var children = child.descendants()) {
                        children.forEach(descendant -> descendants.put(descendant.pid(), descendant));
                    }
                }
            }
        } catch (UnsupportedOperationException ignored) {
            // In-memory Process test doubles have no native process handle.
        } catch (RuntimeException error) {
            // A failed OS observation cannot establish that a process tree has exited.
            observationFailed = true;
        }
    }

    synchronized boolean hasLiveProcesses() {
        captureDescendants();
        return observationFailed || process.isAlive() || descendants.values().stream().anyMatch(ProcessHandle::isAlive);
    }

    void releaseAfterExit() {
        synchronized (this) {
            releaseRequested = true;
        }
        poll();
    }

    private void poll() {
        boolean notify = false;
        synchronized (this) {
            if (released) {
                return;
            }
            if (!hasLiveProcesses() && releaseRequested) {
                released = true;
                if (monitoring != null) {
                    monitoring.cancel(false);
                }
                notify = true;
            }
        }
        if (notify) {
            onReleased.run();
        }
    }

    /** A timeout retains the permit; terminating the parent alone never proves quiescence. */
    boolean terminate(long timeoutMillis) {
        synchronized (this) {
            captureDescendants();
            for (ProcessHandle child : descendants.values()) {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (hasLiveProcesses() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        poll();
        return !hasLiveProcesses();
    }
}
