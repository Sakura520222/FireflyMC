package firefly520.fireflymc.ai.material;

import java.util.*;
import java.util.concurrent.*;

/** Readiness means that the native proof writer finished, rather than serialization alone. */
public final class NativeReceiptIndex implements AutoCloseable {
    private final Map<UUID, Set<CompletableFuture<Void>>> pending = new HashMap<>();
    private final Map<UUID, Throwable> failures = new HashMap<>();
    private boolean closed, overflow;

    public void register(UUID operation, CompletionStage<Void> persisted) {
        Objects.requireNonNull(operation); Objects.requireNonNull(persisted);
        CompletableFuture<Void> future = persisted.toCompletableFuture();
        synchronized (this) {
            if (closed || overflow || pending.size() + failures.size() >= 16384 && !pending.containsKey(operation)) {
                overflow = true; throw new IllegalStateException("NATIVE_JOURNAL_READINESS_UNAVAILABLE");
            }
            pending.computeIfAbsent(operation, ignored -> new HashSet<>()).add(future);
        }
        future.whenComplete((ignored, error) -> {
            synchronized (this) {
                var rows = pending.get(operation);
                if (rows != null) { rows.remove(future); if (rows.isEmpty()) pending.remove(operation); }
                if (error != null) failures.putIfAbsent(operation, error);
            }
        });
    }
    /** Snapshot only the writes already registered for this operation. Never requests a native save. */
    public synchronized CompletionStage<Void> awaitKnown(UUID operation) {
        if (closed || overflow) return CompletableFuture.failedFuture(new IllegalStateException("NATIVE_JOURNAL_READINESS_UNAVAILABLE"));
        if (failures.containsKey(operation)) return CompletableFuture.failedFuture(new IllegalStateException("NATIVE_SAVE_JOURNAL_FAILED", failures.get(operation)));
        return CompletableFuture.allOf(pending.getOrDefault(operation, Set.of()).toArray(CompletableFuture[]::new)).minimalCompletionStage();
    }
    public synchronized int pendingOperations() { return pending.size(); }
    public synchronized int pendingFor(UUID operation) { return pending.getOrDefault(operation, Set.of()).size(); }
    @Override public synchronized void close() { closed = true; }
}
