package firefly520.fireflymc.ai.material;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Main-thread fair dispatch only. Completion of a submission is never a durability receipt. */
public final class NativeSaveDispatchQueue<T> implements AutoCloseable {
    public static final int MAX_TARGETS = 8192;
    public static final long SOFT_TICK_NANOS = 2_000_000;
    public enum StoreKind { PLAYER_NBT, STATS_JSON, ADVANCEMENTS_JSON, CHUNK_NBT, POI_COLUMN, SAVED_DATA, ENTITY_CHUNK, ENTITY_LOAD, OTHER }
    public record KindMetrics(long calls, long maximumNanos, long totalNanos, long overruns) { }
    public record Work(String group, Callable<CompletionStage<?>> action, String reason, boolean saveAttempt, StoreKind kind) {
        public static Work waiting(String reason) { return new Work("", null, reason, false, StoreKind.OTHER); }
        public static Work save(String group, Callable<CompletionStage<?>> action) {
            return save(StoreKind.OTHER, group, action);
        }
        public static Work save(StoreKind kind, String group, Callable<CompletionStage<?>> action) {
            return new Work(Objects.requireNonNull(group), Objects.requireNonNull(action), "", true, Objects.requireNonNull(kind));
        }
        public static Work prepare(String group, Callable<CompletionStage<?>> action) {
            return new Work(Objects.requireNonNull(group), Objects.requireNonNull(action), "WAITING_NATIVE_ENTITY_STORE_LOAD", false, StoreKind.ENTITY_LOAD);
        }
    }
    public record Metrics(int queuedTargets, int inFlightStores, long calls, long inspections, long failures,
                          long maximumCallNanos, long maximumTickNanos, long overrunTicks, String lastReason,
                          Map<StoreKind, KindMetrics> byStoreKind) {
        public Metrics { byStoreKind = Map.copyOf(byStoreKind); }
    }
    private static final class Row<T> {
        final Object identity; T target; long nextTick; int attempts, preparations;
        Row(Object identity, T target) { this.identity = identity; this.target = target; }
    }
    private final IdentityHashMap<Object, Row<T>> indexed = new IdentityHashMap<>();
    private final ArrayDeque<Row<T>> ready = new ArrayDeque<>();
    private final Map<String, CompletableFuture<?>> inFlight = new HashMap<>();
    private final Map<StoreKind, KindMetrics> byStoreKind = new EnumMap<>(StoreKind.class);
    private boolean closed;
    private long lastTick = Long.MIN_VALUE, calls, inspections, failures, maximumCall, maximumTick, overruns;
    private String lastReason = "";
    private volatile Metrics metrics = new Metrics(0, 0, 0, 0, 0, 0, 0, 0, "", Map.of());

    public void requireCapacity(Collection<?> identities) {
        if (closed) throw new IllegalStateException("NATIVE_SAVE_DISPATCH_STOPPED");
        var distinct = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (Object identity : identities) if (!indexed.containsKey(identity)) distinct.add(identity);
        if (indexed.size() + distinct.size() > MAX_TARGETS) throw new IllegalStateException("NATIVE_SAVE_DISPATCH_BACKLOG");
    }
    public void offer(Object identity, T target) {
        Objects.requireNonNull(identity); Objects.requireNonNull(target); requireCapacity(List.of(identity));
        Row<T> row = indexed.get(identity);
        if (row == null) { row = new Row<>(identity, target); indexed.put(identity, row); ready.addLast(row); }
        else { row.target = target; row.attempts = 0; row.preparations = 0; }
        publish();
    }
    /** At most one non-preemptible native save and sixteen bounded resolutions per real server Tick. */
    public void tick(long tick, LongSupplier clock, Predicate<T> pending, Function<T, Work> resolve) {
        if (closed || tick == lastTick) return;
        lastTick = tick; long started = clock.getAsLong();
        inFlight.entrySet().removeIf(row -> {
            if (!row.getValue().isDone()) return false;
            if (row.getValue().isCompletedExceptionally()) { failures++; lastReason = "NATIVE_SAVE_NATIVE_WRITE_FAILED"; }
            return true;
        });
        int count = Math.min(16, ready.size());
        try {
            for (int i = 0; i < count && clock.getAsLong() - started < SOFT_TICK_NANOS; i++) {
                Row<T> row = ready.removeFirst();
                if (!pending.test(row.target)) { indexed.remove(row.identity); continue; }
                ready.addLast(row);
                if (tick < row.nextTick) continue;
                if (row.attempts >= 3) { lastReason = "NATIVE_SAVE_DISPATCH_RETRIES_EXHAUSTED"; continue; }
                inspections++;
                Work work;
                try { work = resolve.apply(row.target); }
                catch (RuntimeException | LinkageError unavailable) { lastReason = safe(unavailable); row.nextTick = tick + 20; continue; }
                if (work.action() == null) { lastReason = work.reason(); row.nextTick = tick + 20; continue; }
                if (!work.saveAttempt() && row.preparations >= 3) {
                    lastReason = "NATIVE_SAVE_DISPATCH_PREPARATION_STALLED"; row.nextTick = tick + 200; continue;
                }
                if (inFlight.containsKey(work.group())) { row.nextTick = tick + 1; continue; }
                if (clock.getAsLong() - started >= SOFT_TICK_NANOS) break;
                long callStarted = clock.getAsLong(); calls++; if (work.saveAttempt()) row.attempts++; else row.preparations++;
                try {
                    CompletionStage<?> physical = Objects.requireNonNull(work.action().call());
                    inFlight.put(work.group(), physical.toCompletableFuture());
                    lastReason = work.reason().isEmpty() ? "WAITING_CANONICAL_NATIVE_WRITE" : work.reason();
                } catch (Exception | LinkageError rejected) { failures++; lastReason = safe(rejected); }
                finally {
                    long elapsed = Math.max(0, clock.getAsLong() - callStarted); maximumCall = Math.max(maximumCall, elapsed);
                    var prior = byStoreKind.getOrDefault(work.kind(), new KindMetrics(0, 0, 0, 0));
                    byStoreKind.put(work.kind(), new KindMetrics(prior.calls() + 1, Math.max(prior.maximumNanos(), elapsed),
                            prior.totalNanos() + elapsed, prior.overruns() + (elapsed > SOFT_TICK_NANOS ? 1 : 0)));
                    row.nextTick = tick + 20;
                }
                break;
            }
        } finally {
            long elapsed = Math.max(0, clock.getAsLong() - started); maximumTick = Math.max(maximumTick, elapsed);
            if (elapsed > SOFT_TICK_NANOS) overruns++;
            publish();
        }
    }
    private static String safe(Throwable failure) {
        String reason = failure.getMessage(); return reason != null && reason.matches("[A-Z][A-Z0-9_]{1,100}") ? reason : "NATIVE_SAVE_DISPATCH_FAILED";
    }
    private void publish() { metrics = new Metrics(indexed.size(), inFlight.size(), calls, inspections, failures, maximumCall, maximumTick, overruns, lastReason, byStoreKind); }
    public Metrics metrics() { return metrics; }
    @Override public void close() { closed = true; indexed.clear(); ready.clear(); inFlight.clear(); publish(); }
}
