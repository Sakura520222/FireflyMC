package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.AgentTask;
import firefly520.fireflymc.ai.workspace.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BuildJournalTest {
    @TempDir Path directory;
    private final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
    private final BlueprintDocument.Position pos = new BlueprintDocument.Position(2, 64, 3);
    private final BlueprintDocument.State air = new BlueprintDocument.State("minecraft:air", Map.of());
    private final BlueprintDocument.State stone = new BlueprintDocument.State("minecraft:stone", Map.of());
    private final MaterialStack item = new MaterialStack("{\"id\":\"minecraft:stone\"}", 1);
    private BuildJournal.Step prepare(WorkspaceManager.Workspace workspace, BuildJournal journal) throws Exception {
        var task = AgentTask.create(actor.owner(), UUID.randomUUID(), "build");
        workspace.conversation().append(List.of(Draft.checkpoint(task)));
        return journal.prepare(actor, UUID.randomUUID(), task.id(), UUID.randomUUID(), AiJson.digest("plan"), UUID.randomUUID(), 0,
                "minecraft:overworld", List.of(new BuildJournal.Cell(pos, air, stone)), item);
    }
    @Test void lateNativeEvidenceIsRetainedOnceWithoutClaimingRecoverySucceeded() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var journal = new BuildJournal(workspace, UUID.randomUUID());
            var step = prepare(workspace, journal); journal.begin(actor, step.operationId());
            journal.uncertain(actor, step.operationId(), "WAITER_STOPPED");
            var evidence = new BuildJournal.Observation(true, true, false, "", MaterialStack.EMPTY, List.of(), List.of(new BuildJournal.ObservedCell(pos, stone)));
            var recorded = journal.observed(actor, step.operationId(), evidence);
            assertEquals(BuildJournal.State.RECOVERY_REQUIRED, recorded.state()); assertEquals(evidence, recorded.observation());
            assertThrows(IllegalStateException.class, () -> journal.observed(actor, step.operationId(), evidence));
        }
    }
    @Test void acceptedNativeStepRequiresMaterialReceiptAndFreshWorldVerification() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var journal = new BuildJournal(workspace, UUID.randomUUID());
            var step = prepare(workspace, journal); journal.begin(actor, step.operationId());
            var actual = List.of(new BuildJournal.ObservedCell(pos, stone));
            journal.observed(actor, step.operationId(), new BuildJournal.Observation(true, true, false, "", MaterialStack.EMPTY, List.of(), actual));
            assertEquals(BuildJournal.State.OBSERVED, journal.get(actor, step.operationId()).state());
            assertThrows(IllegalArgumentException.class, () -> journal.verified(actor, step.operationId(), actual, ""));
            String proof = AiJson.digest("trusted native receipt"); journal.verified(actor, step.operationId(), actual, proof);
            assertTrue(journal.undoCandidate(actor, step.operationId(), actual, proof));
            assertFalse(journal.undoCandidate(actor, step.operationId(), List.of(new BuildJournal.ObservedCell(pos, air)), proof));
            assertFalse(journal.undoCandidate(actor, step.operationId(), actual, AiJson.digest("another receipt")));
            assertThrows(IllegalStateException.class, () -> journal.begin(actor, step.operationId()));
        }
    }
    @Test void nativeIntentSurvivesRestartWithoutReplayOrPrivacyErasureEvenIfTaskWasCancelled() throws Exception {
        UUID id;
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var journal = new BuildJournal(workspace, UUID.randomUUID());
            var step = prepare(workspace, journal); id = step.operationId(); journal.begin(actor, id);
            workspace.conversation().append(List.of(Draft.checkpoint(workspace.conversation().task(step.taskId()).update(AgentTask.State.CANCELLED, "test"))));
        }
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var journal = new BuildJournal(workspace, UUID.randomUUID()); journal.recover(actor);
            assertEquals(BuildJournal.State.RECOVERY_REQUIRED, journal.get(actor, id).state());
            assertThrows(IllegalStateException.class, () -> journal.begin(actor, id));
            var privacy = new WorkspacePrivacyService(System::currentTimeMillis, ignored -> true);
            assertEquals("BUILD_RECONCILIATION_REQUIRED", assertThrows(IllegalStateException.class, () -> privacy.preview(actor, workspace)).getMessage());
            assertThrows(SecurityException.class, () -> journal.get(Actor.player(UUID.randomUUID(), true, true, 0), id));
        }
    }
    @Test void unexpectedCallbackItemsAndWorldChangesRemainExplicitlyUncertain() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var journal = new BuildJournal(workspace, UUID.randomUUID());
            var step = prepare(workspace, journal); journal.begin(actor, step.operationId());
            var extra = new MaterialStack("{\"id\":\"minecraft:diamond\"}", 1);
            var observation = new BuildJournal.Observation(true, true, false, "", MaterialStack.EMPTY, List.of(extra), List.of(new BuildJournal.ObservedCell(pos, stone)));
            var result = journal.observed(actor, step.operationId(), observation);
            assertEquals(BuildJournal.State.RECOVERY_REQUIRED, result.state()); assertEquals(List.of(extra), result.observation().otherItems());
            assertThrows(IllegalStateException.class, () -> journal.verified(actor, step.operationId(), observation.cells(), AiJson.digest("proof")));
        }
    }
    @Test void nativeCancellationKeepsTheActualHeldInputAndCannotBeMisreportedAsSuccess() throws Exception {
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(actor); var journal = new BuildJournal(workspace, UUID.randomUUID());
            var step = prepare(workspace, journal); journal.begin(actor, step.operationId());
            var result = journal.observed(actor, step.operationId(), new BuildJournal.Observation(false, true, false, "CANCELLED", item,
                    List.of(), List.of(new BuildJournal.ObservedCell(pos, air))));
            assertEquals(BuildJournal.State.REJECTED, result.state()); assertEquals(item, result.observation().remaining());
            assertFalse(BuildJournal.hasUnresolvedWorldEffects(workspace));
        }
    }
    @Test void undoRequiresAccountedNativeHarvestAndKeepsTheOriginalReceiptAcrossRestart() throws Exception {
        UUID id; String placementReceipt = AiJson.digest("original placement receipt");
        try (var manager = new WorkspaceManager(directory)) {
            var ws = manager.getWorkspace(actor); UUID epoch = UUID.randomUUID(); var journal = new BuildJournal(ws, epoch);
            var step = prepare(ws, journal); id = step.operationId(); journal.begin(actor, id);
            var observed = List.of(new BuildJournal.ObservedCell(pos, stone));
            journal.observed(actor, id, new BuildJournal.Observation(true, true, false, "", MaterialStack.EMPTY, List.of(), observed));
            journal.verified(actor, id, observed, placementReceipt);
            var removal = new RemovalJournal(ws, epoch); UUID removeId = UUID.randomUUID();
            var input = new RemovalJournal.Input(removeId, step.taskId(), UUID.randomUUID(), AiJson.digest("undo-plan"), "minecraft:overworld",
                    RemovalJournal.Mode.UNDO, List.of(id), List.of(new BuildJournal.Cell(pos, stone, air)), MaterialStack.EMPTY);
            removal.prepare(actor, input); var start = removal.begin(actor, removeId, RemovalJournal.Action.START);
            removal.observed(actor, removeId, start.attempt(), new RemovalJournal.Evidence("READY", "", 1, MaterialStack.EMPTY, 0, observed, List.of()));
            var attempt = removal.begin(actor, removeId, RemovalJournal.Action.REMOVE);
            var removed = removal.observed(actor, removeId, attempt.attempt(), new RemovalJournal.Evidence("REMOVED", RemovalJournal.CUSTODY_EVIDENCE,
                    1, MaterialStack.EMPTY, .005f, List.of(new BuildJournal.ObservedCell(pos, air)), List.of()));
            assertThrows(IllegalStateException.class, () -> journal.undone(actor, id, removed));
            removal.accountHarvest(actor, removeId, (value, receipt) -> null);
            var accounted = removal.get(actor, removeId); var undone = journal.undone(actor, id, accounted);
            assertEquals(BuildJournal.State.UNDONE, undone.state()); assertEquals(placementReceipt, undone.receipt()); assertTrue(undone.settled());
            assertEquals(undone, journal.undone(actor, id, accounted));
            assertFalse(journal.undoCandidate(actor, id, observed, placementReceipt));
            assertThrows(SecurityException.class, () -> journal.undone(Actor.player(UUID.randomUUID(), true, false, 0), id, accounted));
        }
        try (var manager = new WorkspaceManager(directory)) {
            var journal = new BuildJournal(manager.getWorkspace(actor), UUID.randomUUID()); journal.recover(actor);
            assertEquals(BuildJournal.State.UNDONE, journal.get(actor, id).state()); assertEquals(placementReceipt, journal.get(actor, id).receipt());
        }
    }
}
