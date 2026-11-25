package firefly520.fireflymc.ai.blueprint.recovery;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.blueprint.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual private journal/controller transitions. Native receipts are protocol fixtures, not game evidence. */
class WorldRecoveryServiceTest {
    @TempDir Path directory;
    final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
    final MaterialStack stone = new MaterialStack("{\"id\":\"minecraft:stone\",\"count\":1}", 1);
    final class Fixture implements AutoCloseable {
        final WorkspaceManager manager = new WorkspaceManager(directory);
        final WorkspaceManager.Workspace workspace = manager.getWorkspace(actor);
        final MaterialLedger ledger = new MaterialLedger(directory);
        final AgentTask task = AgentTask.create(actor.owner(), UUID.randomUUID(), "build");
        final UUID operation = UUID.randomUUID(), step = UUID.randomUUID(), epoch = UUID.randomUUID();
        final String hash = AiJson.digest("approved build");
        final BuildJournal world = new BuildJournal(workspace, epoch);
        final WorldRecoveryService service;
        int deliveries, retirements;
        Fixture(boolean known) throws Exception {
            var plan = new JsonObject(); plan.addProperty("planId", operation.toString()); plan.addProperty("hash", hash);
            workspace.conversation().append(List.of(Draft.checkpoint(task), new Draft(task.id(), "construction_plan", null, plan, null)));
            var stock = new MaterialLedger.Stock("player", 0, "player:" + actor.owner(), "player:" + actor.owner() + ":slot:0", 0, stone.withCount(2), 1, 2, 2);
            ledger.reserve(actor, task.id(), operation, hash, List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 2)));
            ledger.beginExtraction(actor, operation, 0, stone.withCount(2), 2, "actual-input");
            ledger.extracted(actor, operation, stone.withCount(2), 0);
            ledger.nativeSaved(actor, operation, "extract", "actual-input", AiJson.digest("input-save protocol fixture"));
            var cell = new BuildJournal.Cell(new BlueprintDocument.Position(1, 64, 1), new BlueprintDocument.State("minecraft:air", Map.of()), new BlueprintDocument.State("minecraft:stone", Map.of()));
            world.prepare(actor, step, task.id(), operation, hash, operation, 0, "minecraft:overworld", List.of(cell), stone);
            world.begin(actor, step); ledger.beginWorldUse(actor, operation, step, 0);
            var observed = new BuildJournal.Observation(true, true, !known, known ? "" : "UNKNOWN_CALLBACK", MaterialStack.EMPTY,
                    List.of(), List.of(new BuildJournal.ObservedCell(cell.position(), cell.target())));
            world.observed(actor, step, observed);
            ledger.worldUsed(actor, operation, step, MaterialStack.EMPTY, true, AiJson.digest(epoch + ":" + operation + ":" + step + ":" + AiJson.canonical(observed)));
            ledger.nativeSaved(actor, operation, "world", step.toString(), AiJson.digest("world-save protocol fixture"));
            ledger.markUncertain(actor, operation, "STOPPED_AFTER_KNOWN_EFFECT"); world.uncertain(actor, step, "STOPPED_AFTER_KNOWN_EFFECT");
            service = new WorldRecoveryService(ledger, (ctx, id) -> {
                if (!ctx.actor().online()) throw new IllegalStateException("PLAYER_OFFLINE");
            }, (ctx, op) -> { deliveries++; return op; }, id -> CompletableFuture.completedFuture(null), id -> true,
                    (ctx, op) -> { if (retirements == 0) assertEquals(BuildJournal.State.RECOVERY_REQUIRED, world.get(actor, step).state()); retirements++; });
            service.prepare(workspace);
        }
        AgentTool.Context context() { return new AgentTool.Context(workspace, actor, new TaskControl(10000), task.id()); }
        @Override public void close() throws Exception { manager.close(); }
    }
    @Test void historySettlementNeedsFreshSeparateReturnApprovalAndFullInventoryKeepsOnlyActualUnspentMaterial() throws Exception {
        try (var f = new Fixture(true)) {
            var review = f.service.previewReview(f.context(), f.operation);
            assertEquals(WorldRecoveryService.Phase.SAVED_HISTORY, review.phase());
            assertTrue(f.service.execute(f.context(), review).success());
            assertEquals(0, f.deliveries);
            assertEquals(1, f.retirements);
            assertEquals(MaterialLedger.State.ESCROWED, f.ledger.get(actor, f.operation).state());
            assertEquals(BuildJournal.State.RECONCILED, f.world.get(actor, f.step).state());
            assertFalse(f.service.settled(f.workspace, actor, f.operation));
            assertThrows(IllegalStateException.class, () -> f.service.execute(f.context(), review));
            var returned = f.service.previewReview(f.context(), f.operation);
            assertEquals(WorldRecoveryService.Phase.RETURN_CUSTODY, returned.phase());
            var result = f.service.execute(f.context(), returned);
            assertFalse(result.success()); assertEquals("CUSTODY_REMAINS", result.errorCode());
            var op = f.ledger.get(actor, f.operation);
            assertEquals(1, op.remainingProducts().stream().mapToInt(MaterialStack::count).sum());
            assertEquals(1, op.worldDebits().stream().mapToInt(MaterialLedger.WorldDebit::count).sum());
            assertEquals(1, f.deliveries);
            var args = new JsonObject(); args.addProperty("planId", f.operation.toString()); args.addProperty("planHash", f.hash);
            assertEquals(Optional.of(false), f.service.settledCall(f.workspace, actor, f.task.id(), new ModelMessage.ToolCall("build", "build_execute", args.toString())));
        }
    }
    @Test void anotherPlayerStaleMaterialRevisionAndUnknownNativeEffectsCannotClearTheWorldBarrier() throws Exception {
        try (var f = new Fixture(true)) {
            var review = f.service.previewReview(f.context(), f.operation);
            assertThrows(SecurityException.class, () -> f.service.validate(f.workspace, Actor.player(UUID.randomUUID(), true, true, 0), review));
            f.ledger.markUncertain(actor, f.operation, "NEW_EVIDENCE_VERSION");
            assertThrows(IllegalStateException.class, () -> f.service.execute(f.context(), review));
            assertEquals(BuildJournal.State.RECOVERY_REQUIRED, f.world.get(actor, f.step).state());
            assertEquals(0, f.deliveries);
            assertEquals(0, f.retirements);
        }
    }
    @Test void unknownCallbackHasNoExecutableRecoveryButtonEvenWhenNativeFilesWereSaved() throws Exception {
        try (var f = new Fixture(false)) {
            var rows = f.service.rows(f.workspace, actor, f.task.id());
            assertEquals(1, rows.size()); assertTrue(rows.getFirst().getAsJsonArray("actions").isEmpty());
            assertThrows(IllegalStateException.class, () -> f.service.previewReview(f.context(), f.operation));
            assertEquals(MaterialLedger.State.RECOVERY_REQUIRED, f.ledger.get(actor, f.operation).state());
            assertEquals(0, f.deliveries);
        }
    }
}
