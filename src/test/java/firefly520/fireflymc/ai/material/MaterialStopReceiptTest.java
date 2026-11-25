package firefly520.fireflymc.ai.material;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MaterialStopReceiptTest {
    @TempDir Path root;
    final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
    final MaterialStack input = new MaterialStack("{\"id\":\"minecraft:stone\",\"private\":\"private-book\"}", 8);
    @Test void stoppedBeforeReservationRecordsNoNativeOperationAndCreatesNothing() throws Exception {
        try (var manager = new WorkspaceManager(root.resolve("workspaces"))) {
            var ledger = new MaterialLedger(root.resolve("ledger")); UUID id = UUID.randomUUID();
            var result = MaterialStopReceipt.capture(ledger, context(manager), args(id), new TaskControl.Stopped("USER_PAUSED"));
            assertEquals("PROCESSING_PAUSED", result.errorCode()); assertTrue(ledger.list(actor).isEmpty());
            assertEquals("NO_NATIVE_OPERATION_RECORDED", result.observations().get("dispatch").getAsString());
        }
    }
    @Test void partialKnownExtractionIsReportedWithoutExposingPrivateComponentsOrReturningIt() throws Exception {
        try (var manager = new WorkspaceManager(root.resolve("workspaces"))) {
            var ledger = new MaterialLedger(root.resolve("ledger")); var op = reserve(ledger);
            ledger.beginExtraction(actor, op.operationId(), 0, input, 8, "native-revision");
            var saved = ledger.extracted(actor, op.operationId(), input.withCount(2), 6);
            var result = MaterialStopReceipt.capture(ledger, context(manager), args(op.operationId()), new TaskControl.Stopped("CANCELLED"));
            assertEquals("PROCESSING_PAUSED", result.errorCode()); assertEquals(2, result.observations().get("escrowRecordedUnits").getAsInt());
            assertFalse(result.toString().contains("private-book")); assertEquals(saved, ledger.get(actor, op.operationId()));
        }
    }
    @Test void pendingNativeIntentCannotBecomeSafeThroughStopOrAnotherOwner() throws Exception {
        try (var manager = new WorkspaceManager(root.resolve("workspaces"))) {
            var ledger = new MaterialLedger(root.resolve("ledger")); var op = reserve(ledger);
            var pending = ledger.beginExtraction(actor, op.operationId(), 0, input, 8, "native-revision");
            var result = MaterialStopReceipt.capture(ledger, context(manager), args(op.operationId()), new TaskControl.Stopped("CANCELLED"));
            assertEquals("RECOVERY_REQUIRED", result.errorCode()); assertTrue(result.observations().get("recoveryRequired").getAsBoolean());
            assertEquals(pending, ledger.get(actor, op.operationId()));
            var other = Actor.player(UUID.randomUUID(), true, true, 4);
            assertThrows(SecurityException.class, () -> MaterialStopReceipt.capture(ledger,
                    new AgentTool.Context(manager.getWorkspace(other), other, new TaskControl(30000)), args(op.operationId()), new TaskControl.Stopped("CANCELLED")));
        }
    }
    private MaterialLedger.Operation reserve(MaterialLedger ledger) throws Exception {
        var stock = new MaterialLedger.Stock("own", 1, "backend", "pool", 0, input, 1, 8, 8);
        return ledger.reserve(actor, UUID.randomUUID(), UUID.randomUUID(), AiJson.digest("fixed"), List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 4)));
    }
    private AgentTool.Context context(WorkspaceManager manager) throws Exception { return new AgentTool.Context(manager.getWorkspace(actor), actor, new TaskControl(30000)); }
    private JsonObject args(UUID id) { var args = new JsonObject(); args.addProperty("planId", id.toString()); return args; }
}
