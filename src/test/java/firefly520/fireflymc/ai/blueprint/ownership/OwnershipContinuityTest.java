package firefly520.fireflymc.ai.blueprint.ownership;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnershipContinuityTest {
    private final UUID runtime = UUID.randomUUID();
    private final OwnershipToken.Owner owner = new OwnershipToken.Owner(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64));
    private OwnershipToken claim(OwnershipChunkState state, long position) {
        return state.claim(runtime, "minecraft:overworld", position, owner, UUID.randomUUID(), "air", "stone");
    }
    @Test void anActualEditThenStateRestorationCannotResurrectTheNonce() {
        var state = new OwnershipChunkState(); var token = claim(state, 1);
        state.require(token, runtime, "stone"); state.changed(1); state.changed(1);
        assertThrows(IllegalStateException.class, () -> state.require(token, runtime, "stone"));
        var replacement = claim(state, 1);
        assertNotEquals(token.claimNonce(), replacement.claimNonce()); assertTrue(replacement.generation() > token.generation());
    }
    @Test void sameRuntimeCanonicalReloadKeepsUnrevokedClaims() {
        var state = new OwnershipChunkState(); var token = claim(state, 1); var negatives = new OwnershipRevocations(16);
        var loaded = new OwnershipChunkState(); loaded.restoreWithinRuntime(state.snapshot(), true, false, runtime, negatives::revoked);
        loaded.require(token, runtime, "stone");
    }
    @Test void sameRuntimeNegativeIndexRejectsOnlyTheActuallyRevokedCell() {
        var state = new OwnershipChunkState(); var first = claim(state, 1); var second = claim(state, 2);
        var saved = state.snapshot(); var negatives = new OwnershipRevocations(16); negatives.claim(first.claimNonce());
        var loaded = new OwnershipChunkState(); loaded.restoreWithinRuntime(saved, true, false, runtime, negatives::revoked);
        assertThrows(IllegalStateException.class, () -> loaded.require(first, runtime, "stone"));
        loaded.require(second, runtime, "stone");
    }
    @Test void anotherProcessCannotRecreateAuthorityEvenWithIdenticalCanonicalBlocksAndMarker() {
        var state = new OwnershipChunkState(); var token = claim(state, 1); var loaded = new OwnershipChunkState();
        loaded.restoreWithinRuntime(state.snapshot(), true, false, UUID.randomUUID(), ignored -> false);
        assertFalse(loaded.hasClaims());
        assertThrows(IllegalStateException.class, () -> loaded.require(token, runtime, "stone"));
        assertEquals("OWNERSHIP_RESTART_CONTINUITY_UNPROVEN", loaded.snapshot().reason());
    }
    @Test void ordinaryRestoreWithoutAnExistingRuntimeNeverGrantsAuthority() {
        var state = new OwnershipChunkState(); claim(state, 1); var loaded = new OwnershipChunkState();
        loaded.restore(state.snapshot(), true, false); assertFalse(loaded.hasClaims());
    }
    @Test void alteredLoadOrUnverifiedPayloadNeverKeepsOldClaims() {
        var state = new OwnershipChunkState(); var token = claim(state, 1);
        for (boolean changed : List.of(false, true)) {
            var loaded = new OwnershipChunkState(); loaded.restoreWithinRuntime(state.snapshot(), changed, changed, runtime, ignored -> false);
            assertThrows(IllegalStateException.class, () -> loaded.require(token, runtime, "stone"));
        }
    }
    @Test void mutableReferenceEscapeCannotBeClearedByLoadingAnOlderCleanMarker() {
        var state = new OwnershipChunkState(); var token = claim(state, 1); var saved = state.snapshot();
        state.unknown("OWNERSHIP_MUTABLE_REFERENCE_ESCAPED"); state.restoreWithinRuntime(saved, true, false, runtime, ignored -> false);
        assertThrows(IllegalStateException.class, () -> state.require(token, runtime, "stone"));
        assertThrows(IllegalStateException.class, () -> claim(state, 2));
    }
    @Test void bulkMutationRotatesChunkNonceBeforeANewAuditedAction() {
        var state = new OwnershipChunkState(); var old = claim(state, 1); state.invalidateAll("OWNERSHIP_PALETTE_REPLACED");
        var fresh = claim(state, 1); assertNotEquals(old.chunkNonce(), fresh.chunkNonce()); state.require(fresh, runtime, "stone");
    }
    @Test void revocationIndexExhaustionNeverEvictsANegativeToAllowReplay() {
        var negatives = new OwnershipRevocations(1); var state = new OwnershipChunkState(); var token = claim(state, 1);
        negatives.claim(UUID.randomUUID()); negatives.claim(token.claimNonce());
        assertTrue(negatives.exhausted()); assertTrue(negatives.revoked(token));
        assertTrue(negatives.chunkRevoked(UUID.randomUUID()));
    }
    @Test void aMissingMarkerRevokesOnlyPreviouslyIssuedNoncesAtThatPhysicalLocation() {
        var state = new OwnershipChunkState(); var token = claim(state, 1); var negatives = new OwnershipRevocations(16);
        negatives.target("overworld:0:0", token.chunkNonce()); negatives.missingMarker("overworld:1:0");
        assertFalse(negatives.revoked(token)); negatives.missingMarker("overworld:0:0"); assertTrue(negatives.revoked(token));
        state.invalidateAll("fresh-native-action"); var fresh = claim(state, 1); negatives.target("overworld:0:0", fresh.chunkNonce());
        assertFalse(negatives.revoked(fresh));
        negatives.replacedSource("overworld:0:0", UUID.randomUUID()); assertTrue(negatives.revoked(fresh));
    }
    @Test void duplicatePositionsOrGenerationBeyondTheSavedRevisionAreRejected() {
        var state = new OwnershipChunkState(); var token = claim(state, 1); var loaded = new OwnershipChunkState();
        var duplicate = new OwnershipChunkState.Snapshot(token.chunkNonce(), token.generation(), List.of(token, token), false, "");
        assertThrows(IllegalArgumentException.class, () -> loaded.restoreWithinRuntime(duplicate, true, false, runtime, ignored -> false));
        var future = new OwnershipChunkState.Snapshot(token.chunkNonce(), 0, List.of(token), false, "");
        assertThrows(IllegalArgumentException.class, () -> loaded.restoreWithinRuntime(future, true, false, runtime, ignored -> false));
    }
}
