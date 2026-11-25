package firefly520.fireflymc.ai.blueprint.ownership;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnershipTransitionsTest {
    @Test void matchingAfterStateWithoutANativeTransitionDoesNotCreateOwnership() {
        var witness = new OwnershipTransitions(Map.of(1L, "air"));
        assertThrows(IllegalStateException.class, () -> witness.require(Map.of(1L, "stone")));
    }
    @Test void exactlyObservedTransitionCanCreateAClaim() {
        var witness = new OwnershipTransitions(Map.of(1L, "air", 2L, "stone")); witness.changed(1L, "air", "stone");
        var changes = witness.require(Map.of(1L, "stone", 2L, "stone"));
        assertEquals(Map.of(1L, new OwnershipTransitions.Transition("air", "stone")), changes);
    }
    @Test void unexpectedTargetOrUnknownStorageWriteCannotBeHiddenByFinalState() {
        var unexpected = new OwnershipTransitions(Map.of(1L, "air")); unexpected.changed(2L, "air", "stone");
        assertThrows(IllegalStateException.class, () -> unexpected.require(Map.of(1L, "air")));
        var raw = new OwnershipTransitions(Map.of(1L, "air")); raw.changed(1L, "air", "stone"); raw.unobserved();
        assertThrows(IllegalStateException.class, () -> raw.require(Map.of(1L, "stone")));
    }
    @Test void repeatedNativeWritesAndAbaNeedASpecificAdapter() {
        var witness = new OwnershipTransitions(Map.of(1L, "air"));
        witness.changed(1L, "air", "stone"); witness.changed(1L, "stone", "air"); witness.changed(1L, "air", "stone");
        assertThrows(IllegalStateException.class, () -> witness.require(Map.of(1L, "stone")));
    }
    @Test void noOpTargetsGetNoOwnershipAndFootprintCannotExpand() {
        var witness = new OwnershipTransitions(Map.of(1L, "stone"));
        assertTrue(witness.require(Map.of(1L, "stone")).isEmpty());
        assertThrows(IllegalStateException.class, () -> witness.require(Map.of(1L, "stone", 2L, "stone")));
    }
}
