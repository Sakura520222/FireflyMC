package firefly520.fireflymc.ai.integration.travel;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Drains accepted immutable IO work; uncompleted Minecraft futures deliberately do not belong here. */
final class AcceptedTravelEvidence {
    private int writing;
    void submit(Executor executor, Runnable action) {
        synchronized (this) { writing++; }
        var finished = new AtomicBoolean();
        Runnable complete = () -> {
            if (finished.compareAndSet(false, true)) synchronized (this) { writing--; notifyAll(); }
        };
        try { executor.execute(() -> { try { action.run(); } finally { complete.run(); } }); }
        catch (RuntimeException failure) { complete.run(); throw failure; }
    }
    synchronized boolean await(long millis) throws InterruptedException {
        if (millis < 0) throw new IllegalArgumentException("TRAVEL_WAIT_LIMIT");
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (writing != 0) {
            long left = end - System.nanoTime(); if (left <= 0) return false;
            TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        return true;
    }
}
