package firefly520.fireflymc.ai.material;

import java.util.*;
import java.util.concurrent.*;

/** Tracks native IO submission lifetimes for scheduling. It never acknowledges a save barrier. */
public final class NativeSaveSubmissionScope implements AutoCloseable {
    public interface Hook { }
    private static final ThreadLocal<NativeSaveSubmissionScope> ACTIVE = new ThreadLocal<>();
    private final List<CompletableFuture<?>> submitted = new ArrayList<>();
    private NativeSaveSubmissionScope() {
        if (ACTIVE.get() != null) throw new IllegalStateException("NATIVE_SAVE_DISPATCH_NESTED");
        ACTIVE.set(this);
    }
    public static NativeSaveSubmissionScope begin() { return new NativeSaveSubmissionScope(); }
    public static void submitted(CompletionStage<?> future) {
        var scope = ACTIVE.get(); if (scope != null) scope.submitted.add(Objects.requireNonNull(future).toCompletableFuture());
    }
    private static final class Submitted implements Runnable {
        final Runnable original; final CompletableFuture<Void> completed;
        Submitted(Runnable original, CompletableFuture<Void> completed) { this.original = original; this.completed = completed; }
        @Override public void run() {
            try { original.run(); completed.complete(null); }
            catch (Throwable failure) { completed.completeExceptionally(failure); throw failure; }
        }
    }
    public static Runnable submitted(Runnable original) {
        var scope = ACTIVE.get(); if (scope == null) return original;
        var completed = new CompletableFuture<Void>(); scope.submitted.add(completed);
        return new Submitted(original, completed);
    }
    public static void rejected(Runnable submitted, Throwable failure) {
        if (submitted instanceof Submitted wrapped) wrapped.completed.completeExceptionally(failure);
    }
    public CompletionStage<Void> completion() { return CompletableFuture.allOf(submitted.toArray(CompletableFuture[]::new)); }
    @Override public void close() { if (ACTIVE.get() == this) ACTIVE.remove(); }
}
