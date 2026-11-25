package firefly520.fireflymc.ai.material;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NativeSaveBarrierTest {
    @Test void olderSnapshotNeverAcknowledgesALaterMutation() {
        var barrier = new NativeSaveBarrier(); Object player = new Object();
        var first = barrier.changed(List.of(player)); var old = barrier.snapshot(player);
        var second = barrier.changed(List.of(player));
        barrier.written(old, "native first write and force succeeded");
        assertTrue(first.saved().isDone()); assertFalse(second.saved().isDone());
        barrier.written(barrier.snapshot(player), "native second write and force succeeded");
        assertTrue(second.saved().isDone()); assertEquals(0, barrier.pendingCount());
    }
    @Test void everyPhysicalStoreMustSaveAndEqualsDoesNotSubstituteIdentity() {
        var barrier = new NativeSaveBarrier();
        Object player = new String("same value"), uuidStorage = new String("same value");
        var receipt = barrier.changed(List.of(player, uuidStorage));
        barrier.written(barrier.snapshot(player), "player file"); assertFalse(receipt.saved().isDone());
        barrier.written(barrier.snapshot(new String("same value")), "unrelated file"); assertFalse(receipt.saved().isDone());
        barrier.written(barrier.snapshot(uuidStorage), "actual SavedData");
        assertTrue(receipt.saved().join().matches("[0-9a-f]{64}"));
    }
    @Test void failureAndDirtyFalseAreNotWriteAcknowledgements() {
        var barrier = new NativeSaveBarrier(); Object target = new Object();
        var receipt = barrier.changed(List.of(target)); var snapshot = barrier.snapshot(target);
        barrier.written(snapshot, ""); assertFalse(receipt.saved().isDone());
        barrier.close(); assertTrue(receipt.saved().isCompletedExceptionally());
        barrier.written(snapshot, "late completion after shutdown"); assertTrue(receipt.saved().isCompletedExceptionally());
    }
    @Test void snapshotMustBeTakenAfterMutationAndMayAcknowledgeSeveralEarlierEffects() {
        var barrier = new NativeSaveBarrier(); Object target = new Object();
        var before = barrier.snapshot(target);
        var first = barrier.changed(List.of(target)); var second = barrier.changed(List.of(target));
        barrier.written(before, "pre-effect save");
        assertFalse(first.saved().isDone()); assertFalse(second.saved().isDone());
        barrier.written(barrier.snapshot(target), "coalesced native save");
        assertTrue(first.saved().isDone()); assertTrue(second.saved().isDone());
    }
    @Test void duplicateSaveDoesNotRecompleteOrRetainNativeTargets() {
        var barrier = new NativeSaveBarrier(); Object target = new Object();
        var receipt = barrier.changed(List.of(target, target)); var snapshot = barrier.snapshot(target);
        barrier.written(snapshot, "first"); String proof = receipt.saved().join();
        barrier.written(snapshot, "repeated callback");
        assertEquals(proof, receipt.saved().join()); assertTrue(barrier.snapshot(target).empty());
    }
    @Test void compoundHostAndContentsCapacityIsCheckedBeforeMutation() {
        var barrier = new NativeSaveBarrier(); var existing = new Object(); barrier.changed(List.of(existing));
        for (int i = 1; i < 8191; i++) barrier.changed(List.of(new Object()));
        assertDoesNotThrow(() -> barrier.requireCapacity(List.of(existing, new Object())));
        assertThrows(IllegalStateException.class, () -> barrier.requireCapacity(List.of(new Object(), new Object())));
        assertEquals(8191, barrier.pendingCount()); barrier.close();
    }
    @Test void deferredNativeEffectMustRunAndSaveBeforeCombinedReceiptCompletes() {
        var barrier = new NativeSaveBarrier(); Object chunk = new Object(), poi = new Object();
        var first = barrier.changed(List.of(chunk));
        var later = new java.util.concurrent.CompletableFuture<NativeSaveBarrier.Receipt>();
        var combined = NativeSaveBarrier.combine(List.of(java.util.concurrent.CompletableFuture.completedFuture(first), later));
        barrier.written(barrier.snapshot(chunk), "chunk saved"); assertFalse(combined.saved().isDone());
        var second = barrier.changed(List.of(poi)); later.complete(second); assertFalse(combined.saved().isDone());
        barrier.written(barrier.snapshot(poi), "poi saved");
        assertTrue(combined.saved().join().matches("[0-9a-f]{64}"));
        assertNotEquals(first.saved().join(), combined.saved().join());
    }
    @Test void failedDeferredNativeEffectNeverBecomesACombinedSuccess() {
        var barrier = new NativeSaveBarrier(); Object player = new Object();
        var first = barrier.changed(List.of(player));
        var later = new java.util.concurrent.CompletableFuture<NativeSaveBarrier.Receipt>();
        var combined = NativeSaveBarrier.combine(List.of(java.util.concurrent.CompletableFuture.completedFuture(first), later));
        later.completeExceptionally(new IllegalStateException("native POI failed"));
        barrier.written(barrier.snapshot(player), "player saved");
        assertTrue(combined.saved().isCompletedExceptionally());
    }
}
