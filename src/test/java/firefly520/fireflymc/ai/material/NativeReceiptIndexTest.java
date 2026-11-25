package firefly520.fireflymc.ai.material;

import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeReceiptIndexTest {
    @Test void readinessWaitsForEveryDurableWriterAndIsolatesOperations() {
        var index = new NativeReceiptIndex(); UUID operation = UUID.randomUUID(), other = UUID.randomUUID();
        var player = new CompletableFuture<Void>(); var contents = new CompletableFuture<Void>(); var stranger = new CompletableFuture<Void>();
        index.register(operation, player); index.register(operation, contents); index.register(other, stranger);
        var ready = index.awaitKnown(operation).toCompletableFuture();
        player.complete(null); assertFalse(ready.isDone()); contents.complete(null); assertTrue(ready.isDone());
        assertFalse(stranger.isDone()); assertEquals(1, index.pendingOperations());
        stranger.complete(null); assertEquals(0, index.pendingOperations());
    }
    @Test void nativeProofAloneDoesNotCompleteAnUnfinishedJournalWriter() {
        var index = new NativeReceiptIndex(); UUID operation = UUID.randomUUID();
        var nativeProof = new CompletableFuture<String>(); var journalWritten = new CompletableFuture<Void>();
        index.register(operation, nativeProof.thenCompose(proof -> journalWritten));
        var ready = index.awaitKnown(operation).toCompletableFuture(); nativeProof.complete("actual-file-proof");
        assertFalse(ready.isDone()); journalWritten.complete(null); assertDoesNotThrow(ready::join);
    }
    @Test void failedWritersRemainAFenceForLaterWaitersButDoNotAffectOtherOwners() {
        var index = new NativeReceiptIndex(); UUID operation = UUID.randomUUID(); var write = new CompletableFuture<Void>();
        index.register(operation, write); var old = index.awaitKnown(operation).toCompletableFuture();
        write.completeExceptionally(new java.io.IOException("disk write failed"));
        assertThrows(CompletionException.class, old::join);
        assertThrows(CompletionException.class, () -> index.awaitKnown(operation).toCompletableFuture().join());
        assertDoesNotThrow(() -> index.awaitKnown(UUID.randomUUID()).toCompletableFuture().join());
    }
    @Test void cancellingAWaiterDoesNotCancelNativeProofOrJournalRecording() {
        var index = new NativeReceiptIndex(); UUID operation = UUID.randomUUID(); var write = new CompletableFuture<Void>();
        index.register(operation, write); index.awaitKnown(operation).toCompletableFuture().cancel(false);
        assertFalse(write.isCancelled()); write.complete(null);
        assertDoesNotThrow(() -> index.awaitKnown(operation).toCompletableFuture().join());
    }
    @Test void completedWritesAreReleasedAndShutdownNeverClaimsNewReadiness() {
        var index = new NativeReceiptIndex();
        for (int i = 0; i < 20000; i++) index.register(UUID.randomUUID(), CompletableFuture.completedFuture(null));
        assertEquals(0, index.pendingOperations()); index.close();
        assertThrows(CompletionException.class, () -> index.awaitKnown(UUID.randomUUID()).toCompletableFuture().join());
        assertThrows(IllegalStateException.class, () -> index.register(UUID.randomUUID(), new CompletableFuture<>()));
    }
}
