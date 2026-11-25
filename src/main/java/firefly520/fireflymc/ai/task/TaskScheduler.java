package firefly520.fireflymc.ai.task;

import java.util.*;
import java.util.concurrent.*;

/** Bounded fair owner queue. One worker per workspace; waiting approvals release global IO slots. */
public final class TaskScheduler implements AutoCloseable {
    private final ExecutorService workers;
    private final int concurrency;
    private final int perOwnerLimit;
    private final int globalLimit;
    private final LinkedHashMap<String, LinkedHashMap<UUID, Runnable>> queues = new LinkedHashMap<>();
    private final Set<String> busy = new HashSet<>();
    private final Set<String> blocked = new HashSet<>();
    private boolean closed;

    public TaskScheduler(int concurrency, int perOwnerLimit, int globalLimit) {
        if (concurrency < 1 || perOwnerLimit < 1 || globalLimit < concurrency)
            throw new IllegalArgumentException("scheduler limits");
        this.concurrency = concurrency;
        this.perOwnerLimit = perOwnerLimit;
        this.globalLimit = globalLimit;
        workers = Executors.newFixedThreadPool(concurrency, Thread.ofPlatform()
                .daemon(true).name("fireflymc-ai-", 0).factory());
    }

    public synchronized void submit(String owner, UUID task, Runnable work, boolean priority) {
        submit(owner, task, work, priority, true);
    }
    public synchronized void submit(String owner, UUID task, Runnable work, boolean priority, boolean unblockOwner) {
        if (closed) throw new RejectedExecutionException("AI_STOPPING");
        var queue = queues.computeIfAbsent(owner, ignored -> new LinkedHashMap<>());
        if (queue.containsKey(task)) {
            if (priority && unblockOwner) blocked.remove(owner);
            dispatch();
            return;
        }
        int count = queues.values().stream().mapToInt(Map::size).sum() + busy.size();
        if (!priority && (queue.size() + (busy.contains(owner) ? 1 : 0) >= perOwnerLimit || count >= globalLimit))
            throw new RejectedExecutionException("QUEUE_FULL");
        if (priority) {
            var replacement = new LinkedHashMap<UUID, Runnable>();
            replacement.put(task, work);
            replacement.putAll(queue);
            queues.put(owner, replacement);
            if (unblockOwner) blocked.remove(owner);
        } else queue.put(task, work);
        dispatch();
    }

    public synchronized void block(String owner) { blocked.add(owner); }

    public synchronized void cancelQueued(String owner, UUID task) {
        var queue = queues.get(owner);
        if (queue != null) {
            queue.remove(task);
            if (queue.isEmpty() && !busy.contains(owner)) queues.remove(owner);
        }
    }

    private void dispatch() {
        if (closed) return;
        while (busy.size() < concurrency) {
            String next = queues.keySet().stream().filter(owner -> !busy.contains(owner)
                    && !blocked.contains(owner) && !queues.get(owner).isEmpty()).findFirst().orElse(null);
            if (next == null) return;
            var queue = queues.remove(next);
            Runnable work = queue.remove(queue.keySet().iterator().next());
            queues.put(next, queue); // Round-robin after one task, not a whole player's queue.
            busy.add(next);
            workers.execute(() -> {
                try { work.run(); }
                finally {
                    synchronized (TaskScheduler.this) {
                        busy.remove(next);
                        var remaining = queues.remove(next);
                        if (remaining != null && !remaining.isEmpty()) queues.put(next, remaining);
                        dispatch();
                    }
                }
            });
        }
    }

    public synchronized boolean isBusy(String owner) { return busy.contains(owner); }
    public synchronized boolean isBlocked(String owner) { return blocked.contains(owner); }
    public synchronized int queued() { return queues.values().stream().mapToInt(Map::size).sum(); }
    /** Suspended accepted calls are separately capped; their one-shot wakeups remain bounded. */
    public int externalWaitLimit() { return globalLimit; }

    @Override public synchronized void close() {
        closed = true;
        queues.clear();
        workers.shutdown();
    }

    public boolean awaitTermination(long milliseconds) throws InterruptedException {
        return workers.awaitTermination(milliseconds, TimeUnit.MILLISECONDS);
    }
}
