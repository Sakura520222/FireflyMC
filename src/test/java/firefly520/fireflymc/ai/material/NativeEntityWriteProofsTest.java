package firefly520.fireflymc.ai.material;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production identity index against actual barrier receipts and out-of-order writes. */
class NativeEntityWriteProofsTest {
    @TempDir Path directory;
    @Test void oldNullAndIntermediateLiveWritesCannotAcknowledgeALaterDeletion() {
        var barrier = new NativeSaveBarrier(); var index = new NativeEntityWriteProofs<NativeSaveBarrier.Snapshot>(8);
        Object liveOrb = new Object(), liveNbt = new Object(), deletion = new Object(), emptyEntityNbt = new Object();
        var liveReceipt = barrier.changed(List.of(liveOrb));
        assertTrue(index.submitted(liveNbt, directory, 4, List.of(barrier.snapshot(liveOrb))));
        var deletionReceipt = barrier.changed(List.of(deletion));
        assertTrue(index.submitted(emptyEntityNbt, directory, 4, List.of(barrier.snapshot(deletion))));
        // An old null write was queued before the award and runs after deletion eligibility.
        index.written(null, directory, 4).forEach(snapshot -> barrier.written(snapshot, "older-null-write"));
        assertFalse(deletionReceipt.saved().isDone());
        // A later queued live payload can recreate the orb; it cannot close the deletion either.
        index.written(liveNbt, directory, 4).forEach(snapshot -> barrier.written(snapshot, "intermediate-live-write"));
        assertTrue(liveReceipt.saved().isDone()); assertFalse(deletionReceipt.saved().isDone());
        index.written(emptyEntityNbt, directory, 4).forEach(snapshot -> barrier.written(snapshot, "exact-post-deletion-native-write-and-flush"));
        assertTrue(deletionReceipt.saved().isDone()); assertEquals(0, barrier.pendingCount());
    }
    @Test void coalescingToAnotherPayloadNeverBorrowsTheSubmittedDeletionSnapshot() {
        var barrier = new NativeSaveBarrier(); var index = new NativeEntityWriteProofs<NativeSaveBarrier.Snapshot>(8);
        Object marker = new Object(), intendedEmpty = new Object(), coalescedLive = new Object();
        var receipt = barrier.changed(List.of(marker));
        assertTrue(index.submitted(intendedEmpty, directory, 8, List.of(barrier.snapshot(marker))));
        index.written(coalescedLive, directory, 8).forEach(snapshot -> barrier.written(snapshot, "shared-future-completed-for-other-payload"));
        assertFalse(receipt.saved().isDone());
        Object freshEmpty = new Object();
        assertTrue(index.submitted(freshEmpty, directory, 8, List.of(barrier.snapshot(marker))));
        index.written(freshEmpty, directory, 8).forEach(snapshot -> barrier.written(snapshot, "fresh-exact-null-replacement-written"));
        assertTrue(receipt.saved().isDone());
    }
    @Test void payloadReuseAndAnOlderSnapshotCannotAcknowledgeNewerNativeEffects() {
        var barrier = new NativeSaveBarrier(); var index = new NativeEntityWriteProofs<NativeSaveBarrier.Snapshot>(8);
        Object marker = new Object(), oldPayload = new Object();
        var old = barrier.changed(List.of(marker));
        assertTrue(index.submitted(oldPayload, directory, 16, List.of(barrier.snapshot(marker))));
        var newer = barrier.changed(List.of(marker));
        assertFalse(index.submitted(oldPayload, directory, 16, List.of(barrier.snapshot(marker))));
        index.written(oldPayload, directory, 16).forEach(snapshot -> barrier.written(snapshot, "old-snapshot-only"));
        assertTrue(old.saved().isDone()); assertFalse(newer.saved().isDone());
        Object fresh = new Object(); assertTrue(index.submitted(fresh, directory, 16, List.of(barrier.snapshot(marker))));
        index.written(fresh, directory, 16).forEach(snapshot -> barrier.written(snapshot, "fresh-snapshot"));
        assertTrue(newer.saved().isDone());
    }
    @Test void canonicalFolderChunkAndPayloadIdentityAreAllRequiredAndTheIndexIsBounded() {
        var index = new NativeEntityWriteProofs<String>(1); Object payload = new Object();
        assertFalse(index.submitted(null, directory, 1, List.of("not-a-null-token")));
        assertTrue(index.submitted(payload, directory, 1, List.of("exact")));
        assertTrue(index.written(new Object(), directory, 1).isEmpty());
        assertTrue(index.written(payload, directory.resolve("backup"), 1).isEmpty());
        assertTrue(index.written(payload, directory, 2).isEmpty());
        assertEquals(List.of("exact"), index.written(payload, directory.resolve("child/.."), 1));
        assertFalse(index.submitted(new Object(), directory, 2, List.of("backlog")));
        index.removeIf("exact"::equals);
        assertTrue(index.submitted(new Object(), directory, 2, List.of("released")));
    }
}
