package firefly520.fireflymc.ai.blueprint;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldMutationTrackerTest {
    @Test void anotherPlayerEditInvalidatesEvenWhenTheBlockIsRestored() {
        var tracker = new WorldMutationTracker(); var world = new Object();
        var lease = tracker.watch(UUID.randomUUID(), world, List.of(1L));
        tracker.changed(world, 1); tracker.changed(world, 1);
        assertThrows(IllegalStateException.class, lease::check);
    }
    @Test void ownApprovedWriteInvalidatesOverlappingOtherApprovalButNotItself() {
        var tracker = new WorldMutationTracker(); var world = new Object();
        var first = tracker.watch(UUID.randomUUID(), world, List.of(1L, 2L));
        var second = tracker.watch(UUID.randomUUID(), world, List.of(1L));
        try (var scope = tracker.own(first, world, List.of(1L))) {
            tracker.changed(world, 1); assertFalse(scope.unexpected());
        }
        assertDoesNotThrow(first::check); assertThrows(IllegalStateException.class, second::check);
        tracker.changed(new Object(), 1); assertDoesNotThrow(first::check);
    }
    @Test void unexpectedNeighborMutationAndNestedScopesCannotBeSilentlyAccepted() {
        var tracker = new WorldMutationTracker(); var world = new Object();
        var lease = tracker.watch(UUID.randomUUID(), world, List.of(1L, 2L));
        try (var scope = tracker.own(lease, world, List.of(1L))) {
            assertThrows(IllegalStateException.class, () -> tracker.own(lease, world, List.of(1L)));
            assertThrows(SecurityException.class, () -> tracker.beforeChange(world, 99));
            tracker.changed(world, 99); assertTrue(scope.unexpected());
        }
        assertThrows(IllegalStateException.class, lease::check);
        lease.close(); tracker.changed(world, 1);
    }
}
