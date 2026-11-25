package firefly520.fireflymc.ai.blueprint.ownership;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Causal-cache protocol only: supplied fresh witnesses stand in for the separately tested native scope. */
class BuildOwnershipChainTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final String HASH = "c".repeat(64);
    private static final class Fixture {
        final UUID runtime = UUID.randomUUID(), server = UUID.randomUUID(), player = UUID.randomUUID();
        final UUID task = UUID.randomUUID(), plan = UUID.randomUUID(), chunk = UUID.randomUUID();
        final BuildOwnershipChain chain = new BuildOwnershipChain(runtime, server, player, task, plan, HASH, DIMENSION);
        long generation;
        OwnershipToken proof(UUID action, long position, String before, String after) {
            return new OwnershipToken(1, runtime, chunk, DIMENSION, position, ++generation, UUID.randomUUID(), chain.owner(action), before, after);
        }
        List<OwnershipToken> placed(UUID action, Map<Long, OwnershipTransitions.Transition> changes) {
            var tokens = changes.entrySet().stream().map(entry -> proof(action, entry.getKey(), entry.getValue().before(), entry.getValue().after())).toList();
            chain.placement(action, changes, tokens); chain.markRecorded(action, true); return tokens;
        }
        OwnershipToken placed(long position, String before, String after) {
            return placed(UUID.randomUUID(), Map.of(position, change(before, after))).getFirst();
        }
    }
    private static OwnershipTransitions.Transition change(String before, String after) {
        return new OwnershipTransitions.Transition(before, after);
    }

    @Test void slabInverseContinuesWithANewNativeProofThenUndoesTheOriginalBottom() {
        var f = new Fixture(); var first = f.placed(1, "air", "bottom");
        assertEquals(List.of(first), f.chain.prePlacement(Map.of(1L, "bottom")));
        var second = f.placed(1, "bottom", "double");
        var undoSecond = UUID.randomUUID(); var upper = f.chain.prepareUndo(undoSecond, Map.of(1L, "double"), Map.of(1L, "bottom"));
        assertEquals(List.of(second), upper.tokens());
        var retained = f.proof(undoSecond, 1, "double", "bottom");
        assertTrue(f.chain.completeUndo(upper, List.of(retained)).isEmpty());
        assertEquals("OWNERSHIP_EVIDENCE_PENDING", f.chain.reason());
        f.chain.markRecorded(undoSecond, true);
        assertEquals(List.of(retained), f.chain.requireCurrent(Set.of(1L)));
        assertNotEquals(first, retained);
        var undoFirst = UUID.randomUUID(); var lower = f.chain.prepareUndo(undoFirst, Map.of(1L, "bottom"), Map.of(1L, "air"));
        assertEquals(List.of(retained), lower.tokens());
        var removed = f.proof(undoFirst, 1, "bottom", "air");
        assertEquals(List.of(removed), f.chain.completeUndo(lower, List.of(removed)));
        f.chain.markRecorded(undoFirst, true);
        assertThrows(IllegalStateException.class, () -> f.chain.requireCurrent(Set.of(1L)));
    }

    @Test void oneActuallyWitnessedWholeRemovalMayConsumeBothContiguousSlabContributions() {
        var f = new Fixture(); f.placed(1, "air", "bottom"); f.placed(1, "bottom", "double");
        var action = UUID.randomUUID(); var permit = f.chain.prepareUndo(action, Map.of(1L, "double"), Map.of(1L, "air"));
        var inverse = f.proof(action, 1, "double", "air");
        assertEquals(List.of(inverse), f.chain.completeUndo(permit, List.of(inverse)));
        f.chain.markRecorded(action, true);
        assertThrows(IllegalStateException.class, () -> f.chain.requireCurrent(Set.of(1L)));
    }

    @Test void multiCellSourceGroupsRequireEveryHalfAndEveryFreshInverseWitness() {
        var f = new Fixture(); var placement = UUID.randomUUID();
        var tokens = f.placed(placement, Map.of(1L, change("air", "door_lower"), 2L, change("air", "door_upper")));
        assertThrows(IllegalStateException.class, () -> f.chain.prepareUndo(UUID.randomUUID(), Map.of(1L, "door_lower"), Map.of(1L, "air")));
        var action = UUID.randomUUID(); var permit = f.chain.prepareUndo(action,
                Map.of(1L, "door_lower", 2L, "door_upper"), Map.of(1L, "air", 2L, "air"));
        var lower = f.proof(action, 1, "door_lower", "air"); var upper = f.proof(action, 2, "door_upper", "air");
        assertThrows(IllegalStateException.class, () -> f.chain.completeUndo(permit, List.of(lower)));
        assertEquals(new HashSet<>(tokens), new HashSet<>(f.chain.requireCurrent(Set.of(1L, 2L))));
        assertEquals(List.of(lower, upper), f.chain.completeUndo(permit, List.of(lower, upper)));
    }

    @Test void deeperMultiCellGroupsStayWholeAcrossANativeInverseContinuation() {
        var f = new Fixture();
        f.placed(UUID.randomUUID(), Map.of(1L, change("air", "lower"), 2L, change("air", "upper")));
        f.placed(UUID.randomUUID(), Map.of(1L, change("lower", "lower_2"), 2L, change("upper", "upper_2")));
        assertThrows(IllegalStateException.class, () -> f.chain.prepareUndo(UUID.randomUUID(), Map.of(1L, "lower_2"), Map.of(1L, "lower")));
        var action = UUID.randomUUID(); var permit = f.chain.prepareUndo(action,
                Map.of(1L, "lower_2", 2L, "upper_2"), Map.of(1L, "lower", 2L, "upper"));
        var inverse = List.of(f.proof(action, 1, "lower_2", "lower"), f.proof(action, 2, "upper_2", "upper"));
        assertTrue(f.chain.completeUndo(permit, inverse).isEmpty()); f.chain.markRecorded(action, true);
        assertThrows(IllegalStateException.class, () -> f.chain.prepareUndo(UUID.randomUUID(), Map.of(2L, "upper"), Map.of(2L, "air")));
        assertEquals(inverse, f.chain.prepareUndo(UUID.randomUUID(), Map.of(1L, "lower", 2L, "upper"), Map.of(1L, "air", 2L, "air")).tokens());
    }

    @Test void equalFinalStatesAndAnotherPlayersTokenCannotCreateACausalContribution() {
        var f = new Fixture(); var action = UUID.randomUUID(); var transition = Map.of(1L, change("air", "stone"));
        assertThrows(IllegalStateException.class, () -> f.chain.placement(action, transition, List.of()));
        var foreignOwner = new OwnershipToken.Owner(f.server, UUID.randomUUID(), f.task, f.plan, action, HASH);
        var foreign = new OwnershipToken(1, f.runtime, f.chunk, DIMENSION, 1, 1, UUID.randomUUID(), foreignOwner, "air", "stone");
        assertThrows(IllegalStateException.class, () -> f.chain.placement(action, transition, List.of(foreign)));
        assertThrows(IllegalStateException.class, () -> f.chain.requireCurrent(Set.of(1L)));
        assertTrue(f.chain.prePlacement(Map.of(1L, "air")).isEmpty());
    }

    @Test void pendingMetadataBlocksFurtherOwnershipWorkAndARecordingFailureIsPermanent() {
        var f = new Fixture(); var action = UUID.randomUUID(); var token = f.proof(action, 1, "air", "stone");
        f.chain.placement(action, Map.of(1L, change("air", "stone")), List.of(token));
        assertEquals("OWNERSHIP_EVIDENCE_PENDING", f.chain.reason());
        assertThrows(IllegalStateException.class, () -> f.chain.requireCurrent(Set.of(1L)));
        assertThrows(IllegalStateException.class, () -> f.chain.prePlacement(Map.of(1L, "stone")));
        assertThrows(IllegalStateException.class, () -> f.chain.prepareUndo(UUID.randomUUID(), Map.of(1L, "stone"), Map.of(1L, "air")));
        f.chain.markRecorded(action, false); f.chain.markRecorded(action, true); f.chain.fail("OTHER_REASON");
        assertEquals("OWNERSHIP_JOURNAL_UNAVAILABLE", f.chain.reason());
        assertThrows(IllegalStateException.class, () -> f.chain.requireCurrent(Set.of(1L)));
    }

    @Test void anInverseCannotResurrectTheOriginalTokenOrReuseItsNonce() {
        var f = new Fixture(); var original = f.placed(1, "air", "stone"); var action = UUID.randomUUID();
        var permit = f.chain.prepareUndo(action, Map.of(1L, "stone"), Map.of(1L, "air"));
        assertThrows(IllegalStateException.class, () -> f.chain.completeUndo(permit, List.of(original)));
        var reused = new OwnershipToken(1, f.runtime, f.chunk, DIMENSION, 1, ++f.generation, original.claimNonce(), f.chain.owner(action), "stone", "air");
        assertThrows(IllegalStateException.class, () -> f.chain.completeUndo(permit, List.of(reused)));
        assertEquals(List.of(original), f.chain.requireCurrent(Set.of(1L)));
        var fresh = f.proof(action, 1, "stone", "air");
        assertEquals(List.of(fresh), f.chain.completeUndo(permit, List.of(fresh)));
    }

    @Test void staleOrForeignPermitsCannotConsumeCurrentSourceContributions() {
        var f = new Fixture(); var original = f.placed(1, "air", "stone"); var action = UUID.randomUUID();
        var permit = f.chain.prepareUndo(action, Map.of(1L, "stone"), Map.of(1L, "air"));
        f.placed(2, "air", "stone"); var fresh = f.proof(action, 1, "stone", "air");
        assertThrows(IllegalStateException.class, () -> f.chain.completeUndo(permit, List.of(fresh)));
        var other = new BuildOwnershipChain(f.runtime, f.server, f.player, f.task, f.plan, HASH, DIMENSION);
        var sourceAction = UUID.randomUUID(); var proof = f.proof(sourceAction, 1, "air", "stone");
        other.placement(sourceAction, Map.of(1L, change("air", "stone")), List.of(proof)); other.markRecorded(sourceAction, true);
        assertThrows(IllegalStateException.class, () -> other.completeUndo(permit, List.of(fresh)));
        assertEquals(List.of(original), f.chain.requireCurrent(Set.of(1L)));
    }

    @Test void missingContributorsWrongBeforeAndUnseenBaseStatesAreRejected() {
        var f = new Fixture(); var token = f.placed(1, "air", "stone");
        assertThrows(IllegalStateException.class, () -> f.chain.requireCurrent(Set.of(1L, 2L)));
        assertThrows(IllegalStateException.class, () -> f.chain.prePlacement(Map.of(1L, "diamond")));
        assertThrows(IllegalStateException.class, () -> f.chain.prepareUndo(UUID.randomUUID(), Map.of(1L, "stone"), Map.of(1L, "diamond")));
        assertEquals(List.of(token), f.chain.requireCurrent(Set.of(1L)));
    }

    @Test void aNewOverlappingProofMustAdvanceGenerationAndBindTheSameRuntimeDimensionAndPlan() {
        var f = new Fixture(); var original = f.placed(1, "air", "bottom"); var action = UUID.randomUUID();
        var changes = Map.of(1L, change("bottom", "double"));
        var staleGeneration = new OwnershipToken(1, f.runtime, f.chunk, DIMENSION, 1, original.generation(), UUID.randomUUID(), f.chain.owner(action), "bottom", "double");
        var wrongRuntime = new OwnershipToken(1, UUID.randomUUID(), f.chunk, DIMENSION, 1, ++f.generation, UUID.randomUUID(), f.chain.owner(action), "bottom", "double");
        var wrongDimension = new OwnershipToken(1, f.runtime, f.chunk, "minecraft:the_nether", 1, ++f.generation, UUID.randomUUID(), f.chain.owner(action), "bottom", "double");
        var otherPlan = new OwnershipToken.Owner(f.server, f.player, f.task, UUID.randomUUID(), action, HASH);
        var wrongPlan = new OwnershipToken(1, f.runtime, f.chunk, DIMENSION, 1, ++f.generation, UUID.randomUUID(), otherPlan, "bottom", "double");
        for (var invalid : List.of(staleGeneration, wrongRuntime, wrongDimension, wrongPlan))
            assertThrows(IllegalStateException.class, () -> f.chain.placement(action, changes, List.of(invalid)));
        assertEquals(List.of(original), f.chain.requireCurrent(Set.of(1L)));
    }

    @Test void cacheAndPermitSnapshotsDoNotRetainCallerOwnedMutableCollections() {
        var f = new Fixture(); var action = UUID.randomUUID();
        var changes = new HashMap<Long, OwnershipTransitions.Transition>(); changes.put(1L, change("air", "stone"));
        var proofs = new ArrayList<>(List.of(f.proof(action, 1, "air", "stone")));
        f.chain.placement(action, changes, proofs); f.chain.markRecorded(action, true); changes.clear(); proofs.clear();
        var before = new HashMap<Long, String>(); before.put(1L, "stone");
        var after = new HashMap<Long, String>(); after.put(1L, "air"); var inverseAction = UUID.randomUUID();
        var permit = f.chain.prepareUndo(inverseAction, before, after); before.clear(); after.clear();
        assertEquals(Map.of(1L, "stone"), permit.before()); assertEquals(Map.of(1L, "air"), permit.after());
        assertThrows(UnsupportedOperationException.class, () -> permit.tokens().clear());
        assertThrows(UnsupportedOperationException.class, () -> permit.after().clear());
        var inverse = f.proof(inverseAction, 1, "stone", "air");
        assertEquals(List.of(inverse), f.chain.completeUndo(permit, List.of(inverse)));
    }

    @Test void anActionReplayOrNoOpCannotExpandTheSourcePlanAuthority() {
        var f = new Fixture(); var action = UUID.randomUUID();
        var existing = f.placed(action, Map.of(1L, change("air", "stone"))).getFirst();
        var replay = f.proof(action, 2, "air", "stone");
        assertThrows(IllegalStateException.class, () -> f.chain.placement(action, Map.of(2L, change("air", "stone")), List.of(replay)));
        assertThrows(IllegalArgumentException.class, () -> f.chain.placement(UUID.randomUUID(), Map.of(2L, change("stone", "stone")), List.of()));
        assertEquals(List.of(existing), f.chain.requireCurrent(Set.of(1L)));
        assertThrows(IllegalStateException.class, () -> f.chain.requireCurrent(Set.of(2L)));
    }
}
