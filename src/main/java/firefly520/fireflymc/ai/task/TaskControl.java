package firefly520.fireflymc.ai.task;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Monotonic watchdog independent of wall clock and persisted elapsed-time accounting. */
public final class TaskControl {
    public static final class Stopped extends RuntimeException {
        public Stopped(String reason) { super(reason); }
    }
    private final AtomicReference<String> stop = new AtomicReference<>();
    private final long started = System.nanoTime();
    private final long durationNanos;

    public TaskControl(long remainingMillis) {
        durationNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, remainingMillis));
    }

    public void stop(String reason) {
        stop.updateAndGet(previous -> previous == null || "USER_PAUSED".equals(previous)
                && ("CANCELLED".equals(reason) || "DENIED".equals(reason)) ? reason : previous);
    }
    public String stopReason() { return stop.get(); }
    public long elapsedMillis() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    public long remainingMillis() {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(durationNanos - (System.nanoTime() - started)));
    }
    public void check() {
        if (stop.get() != null) throw new Stopped(stop.get());
        if (System.nanoTime() - started >= durationNanos) throw new Stopped("TIMEOUT");
        if (Thread.currentThread().isInterrupted()) throw new Stopped("SHUTDOWN");
    }

    public <T> T await(CompletableFuture<T> future, long maxMillis) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, maxMillis));
        try {
            while (true) {
                check();
                long remaining = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
                if (remaining <= 0) throw new TimeoutException("OPERATION_TIMEOUT");
                try {
                    return future.get(Math.min(50, Math.max(1, remaining)), TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // A short poll makes cancellation independent of provider latency.
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof Exception exception) throw exception;
                    throw e;
                }
            }
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }
}
