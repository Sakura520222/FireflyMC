package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.AgentTask;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Durable proof/state protocol only. Actual native placement remains covered by dedicated scenarios. */
class SavedPlacementProofsTest {
    @TempDir Path directory;
    final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
    final MaterialStack stone = new MaterialStack("{\"id\":\"minecraft:stone\",\"count\":1}", 1);
    private final class Fixture implements AutoCloseable {
        final WorkspaceManager manager = new WorkspaceManager(directory);
        final WorkspaceManager.Workspace workspace = manager.getWorkspace(actor);
        final AgentTask task = AgentTask.create(actor.owner(), UUID.randomUUID(), "placement");
        final UUID operation = UUID.randomUUID(), taskId = task.id(), stepId = UUID.randomUUID(), epoch = UUID.randomUUID();
        final String hash = AiJson.digest("fixed placement");
        final MaterialLedger ledger = new MaterialLedger(directory);
        final BuildJournal journal = new BuildJournal(workspace, epoch);
        final BuildJournal.Cell cell = new BuildJournal.Cell(new BlueprintDocument.Position(1, 64, 1),
                new BlueprintDocument.State("minecraft:air", Map.of()), new BlueprintDocument.State("minecraft:stone", Map.of()));
        Fixture(boolean saved, boolean uncertain) throws Exception {
            workspace.conversation().append(List.of(Draft.checkpoint(task)));
            var stock = new MaterialLedger.Stock("player", 0, "player:" + actor.owner(), "player:" + actor.owner() + ":slot:0", 0,
                    stone.withCount(2), 1, 2, 2);
            ledger.reserve(actor, taskId, operation, hash, List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 2)));
            String extraction = UUID.randomUUID().toString();
            ledger.beginExtraction(actor, operation, 0, stone.withCount(2), 2, extraction); ledger.extracted(actor, operation, stone.withCount(2), 0);
            ledger.nativeSaved(actor, operation, "extract", extraction, AiJson.digest("actual input file receipt fixture"));
            journal.prepare(actor, stepId, taskId, operation, hash, operation, 0, "minecraft:overworld", List.of(cell), stone);
            ledger.beginWorldUse(actor, operation, stepId, 0); journal.begin(actor, stepId);
            var observed = new BuildJournal.Observation(true, true, uncertain, uncertain ? "UNADAPTED_SIDE_EFFECT" : "", MaterialStack.EMPTY,
                    List.of(), List.of(new BuildJournal.ObservedCell(cell.position(), cell.target())));
            journal.observed(actor, stepId, observed);
            String receipt = AiJson.digest(epoch + ":" + operation + ":" + stepId + ":" + AiJson.canonical(observed));
            ledger.worldUsed(actor, operation, stepId, MaterialStack.EMPTY, true, receipt);
            if (saved) ledger.nativeSaved(actor, operation, "world", stepId.toString(), AiJson.digest("actual chunk and POI receipt fixture"));
            ledger.markUncertain(actor, operation, "CANCEL_AFTER_ACCEPTED_PLACEMENT"); journal.uncertain(actor, stepId, "LATE_WORLD_EVIDENCE_RECORDED");
        }
        SavedPlacementProofs.Review review() { return SavedPlacementProofs.inspect(actor, ledger.get(actor, operation), journal.list(actor)); }
        @Override public void close() throws Exception { manager.close(); }
    }
    @Test void positiveHistoricalReceiptsSettleTheStepButNeverRestoreUndoOwnershipOrRefundSpentInput() throws Exception {
        try (var f = new Fixture(true, false)) {
            var material = f.ledger.get(actor, f.operation); var review = f.review(); assertTrue(review.intact());
            assertEquals(1, review.steps().getFirst().consumed());
            f.journal.reconcileSaved(actor, review, material);
            var step = f.journal.get(actor, f.stepId); assertEquals(BuildJournal.State.RECONCILED, step.state());
            assertFalse(f.journal.undoCandidate(actor, step.operationId(), step.observation().cells(), step.receipt()));
            var fresh = f.review();
            var settled = f.ledger.reconcileSavedWorld(actor, f.operation, fresh, f.journal.list(actor));
            assertEquals(MaterialLedger.State.ESCROWED, settled.state()); assertEquals(List.of(stone), settled.escrow().stream().filter(stack -> !stack.empty()).toList());
            var returned = f.ledger.cancel(actor, f.operation);
            assertEquals(List.of(stone), returned.products()); assertEquals(1, returned.worldDebits().getFirst().count());
            assertEquals(2, returned.claims().getFirst().extracted());
        }
    }
    @Test void historicalReconciliationRetiresOnlyNeverDispatchedStepsAndKeepsRealRemainingMaterial() throws Exception {
        try (var f = new Fixture(true, false)) {
            UUID unused = UUID.randomUUID();
            f.journal.prepare(actor, unused, f.taskId, f.operation, f.hash, f.operation, 0, "minecraft:overworld",
                    List.of(new BuildJournal.Cell(new BlueprintDocument.Position(2, 64, 1), f.cell.before(), f.cell.target())), stone);
            f.journal.reconcileSaved(actor, f.review(), f.ledger.get(actor, f.operation));
            var retired = f.journal.get(actor, unused);
            assertEquals(BuildJournal.State.REJECTED, retired.state());
            assertNull(retired.observation());
            assertEquals("UNSTARTED_WORLD_STEP_CANCELLED_NO_NATIVE_REPLAY", retired.reason());
            var remaining = f.ledger.reconcileSavedWorld(actor, f.operation, f.review(), f.journal.list(actor));
            assertEquals(MaterialLedger.State.ESCROWED, remaining.state());
            assertEquals(1, remaining.escrow().stream().mapToInt(MaterialStack::count).sum());
            assertEquals(1, remaining.worldDebits().stream().mapToInt(MaterialLedger.WorldDebit::count).sum());
            assertTrue(f.journal.list(actor).stream().allMatch(BuildJournal.Step::settled));
        }
    }
    @Test void matchingRecordedBlocksWithoutCanonicalWorldProofCannotClearTheBarrier() throws Exception {
        try (var f = new Fixture(false, false)) {
            assertEquals(f.cell.target(), f.journal.get(actor, f.stepId).observation().cells().getFirst().state());
            assertEquals("WORLD_NATIVE_SAVE_PROOF_REQUIRED", assertThrows(IllegalStateException.class, f::review).getMessage());
            assertEquals(BuildJournal.State.RECOVERY_REQUIRED, f.journal.get(actor, f.stepId).state());
        }
    }
    @Test void unknownNativeSideEffectsCannotBeClearedEvenWithARealisticFileProof() throws Exception {
        try (var f = new Fixture(true, true)) {
            assertEquals("WORLD_NATIVE_OUTCOME_UNKNOWN", assertThrows(IllegalStateException.class, f::review).getMessage());
            assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, f.ledger.get(actor, f.operation).state());
        }
    }
    @Test void changedRevisionWrongOwnerOrMissingStepsCannotReuseHistoricalApproval() throws Exception {
        try (var f = new Fixture(true, false)) {
            var review = f.review();
            assertThrows(SecurityException.class, () -> SavedPlacementProofs.inspect(Actor.player(UUID.randomUUID(), true, true, 4), f.ledger.get(actor, f.operation), f.journal.list(actor)));
            assertThrows(IllegalStateException.class, () -> SavedPlacementProofs.inspect(actor, f.ledger.get(actor, f.operation), List.of()));
            f.ledger.markUncertain(actor, f.operation, "ANOTHER_REVIEW");
            assertThrows(IllegalStateException.class, () -> review.requireCurrent(actor, f.ledger.get(actor, f.operation), f.journal.list(actor)));
        }
    }
}
