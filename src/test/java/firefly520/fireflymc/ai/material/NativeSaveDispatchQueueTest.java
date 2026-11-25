package firefly520.fireflymc.ai.material;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSaveDispatchQueueTest {
    @Test void identityDeduplicationAndNativeInFlightFenceDoNotBlockOtherStores() {
        var queue = new NativeSaveDispatchQueue<String>();
        Object a = new Object(), b = new Object(), c = new Object();
        queue.offer(a, "first"); queue.offer(a, "first-updated"); queue.offer(b, "second"); queue.offer(c, "other");
        assertEquals(3, queue.metrics().queuedTargets());
        var pendingWrite = new CompletableFuture<Void>(); var calls = new ArrayList<String>();
        java.util.function.Function<String, NativeSaveDispatchQueue.Work> resolver = value -> NativeSaveDispatchQueue.Work.save(
                value.equals("other") ? "other-store" : "shared-store", () -> {
                    calls.add(value); return value.equals("first-updated") ? pendingWrite : CompletableFuture.completedFuture(null);
                });
        queue.tick(1, () -> 0, value -> true, resolver);
        queue.tick(1, () -> 0, value -> true, resolver);
        assertEquals(List.of("first-updated"), calls);
        queue.tick(2, () -> 0, value -> true, resolver);
        assertEquals(List.of("first-updated", "other"), calls);
        queue.tick(3, () -> 0, value -> true, resolver);
        assertEquals(2, calls.size());
        pendingWrite.complete(null); queue.tick(4, () -> 0, value -> true, resolver);
        assertEquals(List.of("first-updated", "other", "second"), calls);
    }
    @Test void knownDurabilityIsTheOnlyReasonToDropAQueuedTargetAndUnknownTargetsStayBounded() {
        var queue = new NativeSaveDispatchQueue<String>(); var pending = new HashSet<String>();
        for (int i = 0; i < 20; i++) { String id = "unknown-" + i; pending.add(id); queue.offer(new Object(), id); }
        queue.tick(1, () -> 0, pending::contains, value -> NativeSaveDispatchQueue.Work.waiting("NATIVE_SAVE_TARGET_UNSUPPORTED"));
        assertEquals(16, queue.metrics().inspections()); assertEquals(20, queue.metrics().queuedTargets());
        queue.tick(2, () -> 0, pending::contains, value -> NativeSaveDispatchQueue.Work.waiting("NATIVE_SAVE_TARGET_UNSUPPORTED"));
        assertEquals(20, queue.metrics().inspections());
        pending.clear(); queue.tick(3, () -> 0, pending::contains, value -> { fail("completed target resolved"); return null; });
        queue.tick(4, () -> 0, pending::contains, value -> { fail("completed target resolved"); return null; });
        assertEquals(0, queue.metrics().queuedTargets());
    }
    @Test void aNonPreemptibleNativeCallReportsActualOverrunAndStopsThisTick() {
        var queue = new NativeSaveDispatchQueue<String>(); var clock = new AtomicLong(); var calls = new AtomicInteger();
        queue.offer(new Object(), "large-native-save"); queue.offer(new Object(), "next");
        queue.tick(1, clock::get, value -> true, value -> NativeSaveDispatchQueue.Work.save(value, () -> {
            calls.incrementAndGet(); clock.addAndGet(7_000_000); return CompletableFuture.completedFuture(null);
        }));
        assertEquals(1, calls.get()); assertEquals(7_000_000, queue.metrics().maximumCallNanos());
        assertEquals(1, queue.metrics().overrunTicks());
        var kind = queue.metrics().byStoreKind().get(NativeSaveDispatchQueue.StoreKind.OTHER);
        assertEquals(1, kind.calls()); assertEquals(7_000_000, kind.maximumNanos()); assertEquals(1, kind.overruns());
    }
    @Test void failedOrUnacknowledgedWritesHaveFiniteRetriesAndNewEffectsCanRequestANewSave() {
        var queue = new NativeSaveDispatchQueue<String>(); var key = new Object(); var calls = new AtomicInteger();
        queue.offer(key, "store");
        java.util.function.Function<String, NativeSaveDispatchQueue.Work> resolver = value -> NativeSaveDispatchQueue.Work.save(value, () -> {
            calls.incrementAndGet(); return CompletableFuture.failedFuture(new IllegalStateException("REAL_WRITE_FAILED"));
        });
        for (int tick : new int[]{1, 21, 41, 61, 81}) queue.tick(tick, () -> 0, value -> true, resolver);
        assertEquals(3, calls.get()); assertEquals(1, queue.metrics().queuedTargets());
        assertEquals("NATIVE_SAVE_DISPATCH_RETRIES_EXHAUSTED", queue.metrics().lastReason());
        queue.offer(key, "store"); queue.tick(82, () -> 0, value -> true, resolver);
        assertEquals(4, calls.get());
    }
    @Test void nativeSubmissionCompletionNeverAcknowledgesDurabilityAndNeverRunsIOOnTheCaller() {
        var barrier = new NativeSaveBarrier(); var receipt = barrier.changed(List.of(new Object()));
        var called = new AtomicBoolean(); Runnable queued; CompletionStage<Void> completion;
        try (var scope = NativeSaveSubmissionScope.begin()) {
            queued = NativeSaveSubmissionScope.submitted(() -> called.set(true)); completion = scope.completion();
        }
        assertFalse(called.get()); assertFalse(completion.toCompletableFuture().isDone());
        queued.run(); assertTrue(completion.toCompletableFuture().isDone());
        assertFalse(receipt.saved().isDone());
    }
    @Test void aRejectedNativeRunnableSubmissionCannotLeaveAnInfiniteInFlightFence() {
        Runnable queued; CompletionStage<Void> completion;
        try (var scope = NativeSaveSubmissionScope.begin()) {
            queued = NativeSaveSubmissionScope.submitted(() -> fail("rejected work ran"));
            NativeSaveSubmissionScope.rejected(queued, new RejectedExecutionException("native executor stopped"));
            completion = scope.completion();
        }
        assertTrue(completion.toCompletableFuture().isCompletedExceptionally());
    }
    @Test void nativeStorePreparationDoesNotInventAWriteOrSpendTheSaveRetryBudget() {
        var queue = new NativeSaveDispatchQueue<String>(); var ready = new AtomicBoolean(); var writes = new AtomicInteger();
        queue.offer(new Object(), "entity-store");
        java.util.function.Function<String, NativeSaveDispatchQueue.Work> resolver = value -> ready.get()
                ? NativeSaveDispatchQueue.Work.save(value, () -> { writes.incrementAndGet(); return CompletableFuture.completedFuture(null); })
                : NativeSaveDispatchQueue.Work.prepare(value, () -> CompletableFuture.completedFuture(null));
        for (int tick : new int[]{1, 21, 41, 61}) queue.tick(tick, () -> 0, value -> true, resolver);
        assertEquals(3, queue.metrics().calls()); assertEquals(0, writes.get());
        assertEquals("NATIVE_SAVE_DISPATCH_PREPARATION_STALLED", queue.metrics().lastReason());
        ready.set(true); queue.tick(261, () -> 0, value -> true, resolver);
        assertEquals(1, writes.get()); assertEquals(1, queue.metrics().queuedTargets());
    }
}
