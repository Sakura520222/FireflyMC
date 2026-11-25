package firefly520.fireflymc.ai.material;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSerializationCapturesTest {
    @Test void subtypeAndBaseStoresShareOnePayloadInEitherOrderWithoutLosingOrDuplicatingEvidence() {
        for (boolean reverse : List.of(false, true)) {
            var captures = new NativeSerializationCaptures<String>(); Object observer = new Object(), base = new Object(), subtype = new Object();
            UUID baseReceipt = UUID.randomUUID(), subtypeReceipt = UUID.randomUUID();
            if (reverse) captures.add(observer, base, "entity-region", List.of(baseReceipt), "base");
            captures.add(observer, subtype, "entity-region", List.of(subtypeReceipt), "subtype");
            captures.add(observer, base, "entity-region", List.of(baseReceipt), "base");
            captures.add(observer, subtype, "entity-region", List.of(subtypeReceipt), "subtype-again");
            assertEquals(Set.of("base", "subtype"), Set.copyOf(captures.evidence()));
        }
    }
    @Test void aNewReceiptCannotReplaceAnOldTagEpochAndTheConflictCannotBeRecaptured() {
        Object observer = new Object(), target = new Object(), independent = new Object(); UUID old = UUID.randomUUID(), fresh = UUID.randomUUID();
        var oldTag = new NativeSerializationCaptures<String>();
        oldTag.add(observer, target, "canonical", List.of(old), "old");
        oldTag.add(observer, independent, "canonical", List.of(UUID.randomUUID()), "independent-subtype");
        oldTag.add(observer, target, "canonical", List.of(old, fresh), "newer-snapshot-of-old-tag");
        oldTag.add(observer, target, "canonical", List.of(fresh), "attempt-to-recapture-after-conflict");
        oldTag.add(observer, target, "canonical", List.of(old), "old-attempt-after-conflict");
        assertEquals(List.of("independent-subtype"), oldTag.evidence());
        var freshTag = new NativeSerializationCaptures<String>();
        freshTag.add(observer, target, "canonical", List.of(old, fresh), "real-fresh-serialization");
        assertEquals(List.of("real-fresh-serialization"), freshTag.evidence());
    }
    @Test void destinationChangesAndObserverNamespacesNeverBorrowProofFromEachOther() {
        Object a = new Object(), b = new Object(), target = new Object(); UUID receipt = UUID.randomUUID();
        var captures = new NativeSerializationCaptures<String>();
        captures.add(a, target, "old-dimension/entities", List.of(receipt), "a-old");
        captures.add(b, target, "other-server/entities", List.of(receipt), "b");
        captures.add(a, target, "new-dimension/entities", List.of(receipt), "a-moved");
        assertEquals(List.of("b"), captures.evidence());
        captures.removeObserver(a); assertEquals(List.of("b"), captures.evidence());
        captures.removeObserver(b); assertTrue(captures.empty());
    }
    @Test void pendingReceiptSetsAreOrderIndependentButAnAcknowledgedOldReceiptDoesNotRetagThePayload() {
        Object observer = new Object(), target = new Object(); UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var captures = new NativeSerializationCaptures<String>();
        captures.add(observer, target, "canonical", List.of(a, b), "first");
        captures.add(observer, target, "canonical", List.of(b, a), "same"); assertEquals(List.of("first"), captures.evidence());
        captures.add(observer, target, "canonical", List.of(b), "old-write-completed-between-hooks");
        assertTrue(captures.evidence().isEmpty());
    }
}
