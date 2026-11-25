package firefly520.fireflymc.ai.integration.maid.transport;

import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Journal fixtures are not native/game acceptance; real native transport has its separate scenario. */
class MaidTransportJournalTest {
    @TempDir Path directory;
    final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
    final MaterialStack apple = new MaterialStack("{\"id\":\"minecraft:apple\",\"count\":1}", 4);
    MaidTransportPlan plan(WorkspaceManager.Workspace workspace) {
        return new MaidTransportPlan(1, UUID.randomUUID(), actor.owner(), UUID.randomUUID(), workspace.metadata().serverInstanceId(), workspace.primaryConversationId(),
                workspace.conversation().privacyEpoch(), UUID.randomUUID(), 1, Long.MAX_VALUE, UUID.randomUUID(), 0, 1, "minecraft:overworld", 0, 64, 0,
                UUID.randomUUID(), 1, UUID.randomUUID(), 1, true, AiJson.digest("test-bauble"), 0, true, Collections.nCopies(38, true),
                List.of(MaterialStack.EMPTY), List.of(apple), List.of(MaterialStack.EMPTY), List.of(0),
                List.of(new MaidTransportPlan.Move(0, apple, List.of(new MaidTransportPlan.Placement(0, 4)))), List.of(MaterialStack.EMPTY), List.of(apple), List.of("block:minecraft:overworld:64:" + UUID.randomUUID()), "").signed();
    }
    @Test void nativeObservationNeedsPositiveSaveAndCannotBeReplayedOrSharedWithAnotherPlayer() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var plan = plan(workspace); var repo = new MaidTransportJournal(workspace);
            repo.intent(actor, plan); repo.armed(actor, plan.id());
            var evidence = new MaidTransportJournal.Evidence(true, true, "", plan.expectedSource(), plan.expectedDestination(), List.of(), 1000);
            var observed = repo.observed(actor, plan.id(), true, evidence); assertFalse(observed.settled());
            String digest = AiJson.digest(AiJson.canonical(evidence)); repo.saved(actor, plan.id(), AiJson.digest("synthetic-test-proof"), digest);
            assertTrue(repo.settled(actor)); assertTrue(repo.taskSettled(actor, plan.taskId()));
            assertThrows(IllegalStateException.class, () -> repo.intent(actor, plan));
            assertThrows(IllegalStateException.class, () -> repo.observed(actor, plan.id(), true, evidence));
            var other = Actor.player(UUID.randomUUID(), true, true, 4);
            assertThrows(SecurityException.class, () -> repo.snapshot(other));
            assertThrows(SecurityException.class, () -> repo.saved(other, plan.id(), AiJson.digest("foreign")));
            assertTrue(new MaidTransportJournal(workspace).settled(actor));
            var exported = AiJson.object(repo.exportJournal(actor), 2 * 1024 * 1024, 64);
            var versions = exported.getAsJsonArray("events").asList().stream().map(value -> value.getAsJsonObject().getAsJsonObject("data").get("state").getAsString()).toList();
            assertEquals(List.of("INTENT", "ARMED", "OBSERVED", "SAVED"), versions);
            workspace.conversation().erase(workspace.conversation().revision());
            var next = new MaidTransportJournal(workspace); var erasedExport = next.exportJournal(actor);
            var nextEpoch = AiJson.object(erasedExport, 2 * 1024 * 1024, 64);
            assertTrue(nextEpoch.getAsJsonArray("events").isEmpty()); assertFalse(erasedExport.contains(plan.id().toString()));
            assertTrue(nextEpoch.getAsJsonArray("excludedEpochDirectories").asList().stream().anyMatch(value -> value.getAsString().equals("epoch-0")));
            assertFalse(nextEpoch.get("excludedEpochContentRead").getAsBoolean());
        }
    }
    @Test void restartUnknownIntentCannotBeReconciledAndAPreDispatchCancellationCreatesNoSaveProof() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var first = plan(workspace); var repo = new MaidTransportJournal(workspace);
            repo.intent(actor, first); var restarted = new MaidTransportJournal(workspace);
            var unknown = restarted.get(actor, first.id()); assertEquals(MaidTransportJournal.State.RECOVERY_REQUIRED, unknown.state());
            assertFalse(unknown.reconcilable()); assertFalse(unknown.settled());
            assertThrows(IllegalStateException.class, () -> restarted.reconcile(actor, first.id(), unknown.revision(), AiJson.digest(AiJson.canonical(unknown))));
            var rejected = plan(workspace); restarted.intent(actor, rejected);
            var cancelled = restarted.observed(actor, rejected.id(), false, new MaidTransportJournal.Evidence(true, false, "CANCELLED", rejected.source(), rejected.destination(), List.of(), 0));
            assertTrue(cancelled.settled()); assertEquals("", cancelled.nativeProof());
            assertThrows(IllegalStateException.class, () -> restarted.saved(actor, rejected.id(), AiJson.digest("fake")));
        }
    }
    @Test void verifiedPartialExtractionKeepsExactCustodyAndOnlyANewSavedReturnCanCloseIt() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var plan = plan(workspace); var repo = new MaidTransportJournal(workspace); repo.intent(actor, plan);
            var evidence = new MaidTransportJournal.Evidence(true, false, "PARTIAL_EXTRACTION", List.of(apple.withCount(2)), plan.destination(), List.of(apple.withCount(2)), 500);
            repo.observed(actor, plan.id(), true, evidence); String oldDigest = AiJson.digest(AiJson.canonical(evidence));
            repo.saved(actor, plan.id(), AiJson.digest("old-native-save"), oldDigest);
            var held = repo.get(actor, plan.id()); assertFalse(held.settled()); assertFalse(held.reconcilable());
            UUID returnId = UUID.randomUUID(); repo.beginReturn(actor, plan.id(), held.revision(), AiJson.digest(AiJson.canonical(held)), returnId);
            assertThrows(IllegalStateException.class, () -> repo.beginReturn(actor, plan.id(), held.revision(), AiJson.digest(AiJson.canonical(held)), returnId));
            var returned = repo.returned(actor, plan.id(), returnId, 0, apple.withCount(2), 2, true, "RETURNED");
            assertFalse(returned.settled()); assertTrue(returned.evidence().custody().isEmpty()); assertEquals(List.of(apple.withCount(2)), returned.evidence().returned());
            assertThrows(IllegalStateException.class, () -> repo.saved(actor, plan.id(), AiJson.digest("late-old-save"), oldDigest));
            repo.saved(actor, plan.id(), AiJson.digest("new-delivery-save"), AiJson.digest(AiJson.canonical(returned.evidence())));
            assertTrue(repo.settled(actor)); assertFalse(repo.get(actor, plan.id()).evidence().achieved());
        }
    }
    @Test void identicalItemCountsWithDifferentComponentsOrUnaccountedCustodyDoNotConserve() {
        var named = new MaterialStack("{\"id\":\"minecraft:apple\",\"count\":1,\"components\":{\"minecraft:custom_name\":\"private\"}}", 4);
        assertThrows(IllegalStateException.class, () -> MaidTransportConservation.require(List.of(apple), List.of(), List.of(), List.of(named), List.of()));
        assertThrows(IllegalStateException.class, () -> MaidTransportConservation.require(List.of(apple), List.of(), List.of(apple.withCount(2)), List.of(), List.of()));
        assertDoesNotThrow(() -> MaidTransportConservation.require(List.of(apple), List.of(), List.of(apple.withCount(2)), List.of(), List.of(apple.withCount(2))));
        var oversizedFilter = new net.minecraft.nbt.CompoundTag(); oversizedFilter.putInt("Size", Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> MaidTransportNative.requireFiniteFilterTag(oversizedFilter));
        oversizedFilter.putInt("Size", 9); assertDoesNotThrow(() -> MaidTransportNative.requireFiniteFilterTag(oversizedFilter));
    }
}
