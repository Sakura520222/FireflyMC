package firefly520.fireflymc.ai.task;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A durable intent's one native call. Cancellation can reject an unclaimed invocation; after
 * dispatch, its evidence is collected before the caller observes cancellation at the next checkpoint.
 */
public final class CommittedCall<T> {
    private enum Phase { QUEUED, CLAIMED, REJECTED, FINISHED }
    public static final class Rejected extends Exception {
        public Rejected(Throwable reason) { super("REJECTED_BEFORE_NATIVE_INVOCATION", reason); }
    }
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.QUEUED);
    private final CompletableFuture<T> result = new CompletableFuture<>();
    /** Observers should enqueue durable recording on a bounded IO executor, never write files on the game thread. */
    public void whenSettled(java.util.function.BiConsumer<T, Throwable> observer) { result.whenComplete(observer); }
    public void run(TaskControl control, Callable<T> action) {
        if (!phase.compareAndSet(Phase.QUEUED, Phase.CLAIMED)) return;
        try { control.check(); }
        catch (RuntimeException stopped) { phase.set(Phase.REJECTED); result.completeExceptionally(new Rejected(stopped)); return; }
        try {
            T value = action.call(); phase.set(Phase.FINISHED); result.complete(value);
        } catch (Exception | LinkageError failure) { phase.set(Phase.FINISHED); result.completeExceptionally(failure); }
    }
    public boolean rejectBeforeClaim(Throwable reason) {
        if (!phase.compareAndSet(Phase.QUEUED, Phase.REJECTED)) return false;
        result.completeExceptionally(new Rejected(reason)); return true;
    }
    public T awaitEvidence(long maximumMillis) throws Exception {
        if (maximumMillis < 1) throw new IllegalArgumentException("COMMIT_WAIT_LIMIT");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maximumMillis);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        var timeout = new TimeoutException("NATIVE_EVIDENCE_TIMEOUT");
                        if (rejectBeforeClaim(timeout)) throw new Rejected(timeout);
                        throw timeout; // May already have mutated; retain the durable intent for reconciliation.
                    }
                    return result.get(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException stopping) {
                    interrupted = true;
                    if (rejectBeforeClaim(stopping)) throw new Rejected(stopping);
                    // The accepted native operation still owns real material. Preserve its returned evidence.
                } catch (ExecutionException failed) {
                    if (failed.getCause() instanceof Exception exception) throw exception;
                    throw failed;
                } catch (TimeoutException timeout) {
                    if (rejectBeforeClaim(timeout)) throw new Rejected(timeout);
                    throw timeout;
                }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
