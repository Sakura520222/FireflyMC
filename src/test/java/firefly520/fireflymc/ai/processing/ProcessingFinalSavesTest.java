package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Protocol fixtures exercise real journal writes; they are not Minecraft canonical-save evidence. */
class ProcessingFinalSavesTest {
    @TempDir Path directory;
    private final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0);
    private final UUID task = UUID.randomUUID();
    private final MaterialStack input = new MaterialStack("{\"id\":\"minecraft:stone\"}", 2);
    private final MaterialStack output = new MaterialStack("{\"id\":\"minecraft:stone_slab\"}", 3);

    private MaterialLedger.Operation completed(MaterialLedger ledger, boolean delivered) throws Exception {
        var stock = new MaterialLedger.Stock("player", 1, "player:" + owner.owner(), "slot-0", 0, input, 1, 2, 2);
        var op = ledger.reserve(owner, task, UUID.randomUUID(), AiJson.digest("fixed-final-plan"), List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 2)));
        ledger.beginExtraction(owner, op.operationId(), 0, input, 2, "extract");
        ledger.extracted(owner, op.operationId(), input, 0);
        ledger.nativeSaved(owner, op.operationId(), "extract", "extract", AiJson.digest("unit-input-journal-proof"));
        ledger.produceSampled(owner, op.operationId(), List.of(output), "unit-fixed-seed-31");
        ledger.beginCallback(owner, op.operationId(), "callback", 1);
        ledger.callbackPartEvidence(owner, op.operationId(), "callback", 0, true, true, "", Set.of("player:" + owner.owner()));
        ledger.callbackEvidence(owner, op.operationId(), "callback");
        if (delivered) {
            ledger.beginDelivery(owner, op.operationId(), 0, 3, "delivery", MaterialStack.EMPTY);
            ledger.delivered(owner, op.operationId(), 3, true);
        }
        return ledger.get(owner, op.operationId());
    }
    private AgentTool.Context context(WorkspaceManager manager, TaskControl control) throws Exception {
        return new AgentTool.Context(manager.getWorkspace(owner), owner, control, task);
    }
    private void saveCallback(MaterialLedger ledger, UUID id) throws Exception {
        ledger.nativeSaved(owner, id, "callback-part", MaterialLedger.callbackPartWitness("callback", 0), AiJson.digest("unit-callback-journal-proof"));
    }
    private void saveDelivery(MaterialLedger ledger, UUID id) throws Exception {
        ledger.nativeSaved(owner, id, "deliver", "delivery", AiJson.digest("unit-delivery-journal-proof"));
    }

    @Test void terminalDeliveryWaitsForAllProofsAndTheDurableWriterThenReturnsOneSuccessWithoutNativeReplay() throws Exception {
        try (var manager = new WorkspaceManager(directory.resolve("workspace"))) {
            var ledger = new MaterialLedger(directory.resolve("ledger")); var op = completed(ledger, true);
            var writer = new CompletableFuture<Void>(); var returned = new AtomicInteger();
            var gate = new ProcessingFinalSaves(ledger, ignored -> writer);
            var deferred = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(AgentTool.Deferred.class,
                    () -> gate.complete(context(manager, new TaskControl(120000)), op.operationId(), current -> {
                        returned.incrementAndGet(); assertTrue(current.saved()); return AgentTool.Result.ok("actual receipt");
                    })));
            assertEquals("WAITING_FINAL_NATIVE_SAVE", deferred.reason()); assertEquals(0, returned.get());
            assertEquals(MaterialLedger.State.COMPLETED, ledger.get(owner, op.operationId()).state());
            assertEquals(List.of(3), ledger.get(owner, op.operationId()).delivered());
            saveCallback(ledger, op.operationId()); assertFalse(ledger.get(owner, op.operationId()).saved());
            saveDelivery(ledger, op.operationId()); assertTrue(ledger.get(owner, op.operationId()).saved());
            assertFalse(deferred.readiness().toCompletableFuture().isDone(), "Native proof alone must not substitute for its unfinished journal writer");
            long revision = ledger.get(owner, op.operationId()).revision(); writer.complete(null);
            assertTrue(deferred.continuation().resume(context(manager, new TaskControl(10000))).success());
            assertEquals(1, returned.get()); assertEquals(revision, ledger.get(owner, op.operationId()).revision());
            assertEquals("unit-fixed-seed-31", ledger.get(owner, op.operationId()).samplingEvidence());
            assertThrows(IllegalStateException.class, () -> deferred.continuation().resume(context(manager, new TaskControl(10000))));
            assertEquals(1, returned.get()); assertEquals(List.of(3), ledger.get(owner, op.operationId()).delivered());
        }
    }

    @Test void completedReadinessWithMissingProofCannotTurnCompletedStateIntoSuccessfulPersistence() throws Exception {
        try (var manager = new WorkspaceManager(directory.resolve("workspace"))) {
            var ledger = new MaterialLedger(directory.resolve("ledger")); var op = completed(ledger, true); var returned = new AtomicInteger();
            var result = new ProcessingFinalSaves(ledger, ignored -> CompletableFuture.completedFuture(null))
                    .complete(context(manager, new TaskControl(10000)), op.operationId(), current -> { returned.incrementAndGet(); return AgentTool.Result.ok("bad"); });
            assertFalse(result.success()); assertEquals("RECOVERY_REQUIRED", result.errorCode()); assertEquals(0, returned.get());
            assertTrue(ledger.get(owner, op.operationId()).uncertain());
            assertEquals("FINAL_NATIVE_SAVE_PROOF_UNAVAILABLE", ledger.get(owner, op.operationId()).reason());
            assertEquals(List.of(3), ledger.get(owner, op.operationId()).delivered());
        }
    }

    @Test void journalFailurePreservesActualDeliveryAndReturnsRecoveryInsteadOfASecondDelivery() throws Exception {
        try (var manager = new WorkspaceManager(directory.resolve("workspace"))) {
            var ledger = new MaterialLedger(directory.resolve("ledger")); var op = completed(ledger, true); var writer = new CompletableFuture<Void>();
            var returned = new AtomicInteger();
            var deferred = assertThrows(AgentTool.Deferred.class, () -> new ProcessingFinalSaves(ledger, ignored -> writer)
                    .complete(context(manager, new TaskControl(10000)), op.operationId(), current -> { returned.incrementAndGet(); return AgentTool.Result.ok("bad"); }));
            writer.completeExceptionally(new java.io.IOException("real writer failed"));
            assertDoesNotThrow(() -> deferred.readiness().toCompletableFuture().join());
            var result = deferred.continuation().resume(context(manager, new TaskControl(10000)));
            assertFalse(result.success()); assertEquals("RECOVERY_REQUIRED", result.errorCode()); assertEquals(0, returned.get());
            assertEquals(List.of(3), ledger.get(owner, op.operationId()).delivered()); assertTrue(ledger.get(owner, op.operationId()).uncertain());
        }
    }

    @Test void cancellationAndTimeoutDoNotCancelTheWriterOrReplayAnyActualOutput() throws Exception {
        try (var manager = new WorkspaceManager(directory.resolve("workspace"))) {
            var ledger = new MaterialLedger(directory.resolve("ledger")); var op = completed(ledger, true); var writer = new CompletableFuture<Void>();
            var returned = new AtomicInteger();
            var deferred = assertThrows(AgentTool.Deferred.class, () -> new ProcessingFinalSaves(ledger, ignored -> writer)
                    .complete(context(manager, new TaskControl(10000)), op.operationId(), current -> { returned.incrementAndGet(); return AgentTool.Result.ok("bad"); }));
            for (String reason : List.of("CANCELLED", "USER_PAUSED", "TIMEOUT", "SHUTDOWN")) {
                var control = new TaskControl(10000); control.stop(reason);
                assertThrows(TaskControl.Stopped.class, () -> deferred.continuation().resume(context(manager, control)));
            }
            assertThrows(TaskControl.Stopped.class, () -> deferred.continuation().resume(context(manager, new TaskControl(0))));
            assertFalse(writer.isCancelled()); assertEquals(0, returned.get()); assertEquals(op, ledger.get(owner, op.operationId()));
            saveCallback(ledger, op.operationId()); saveDelivery(ledger, op.operationId()); writer.complete(null);
            assertTrue(ledger.get(owner, op.operationId()).saved()); assertEquals(List.of(3), ledger.get(owner, op.operationId()).delivered());
            assertEquals(0, returned.get(), "Late real evidence is retained without resuming a cancelled invocation");
        }
    }

    @Test void anotherTaskCannotBorrowTheAcceptedFinalContinuationAndPartialCustodyDoesNotInventAWritingObligation() throws Exception {
        try (var manager = new WorkspaceManager(directory.resolve("workspace"))) {
            var ledger = new MaterialLedger(directory.resolve("ledger")); var op = completed(ledger, true); var writer = new CompletableFuture<Void>();
            var deferred = assertThrows(AgentTool.Deferred.class, () -> new ProcessingFinalSaves(ledger, ignored -> writer)
                    .complete(context(manager, new TaskControl(10000)), op.operationId(), current -> AgentTool.Result.ok("bad")));
            var wrongTask = new AgentTool.Context(manager.getWorkspace(owner), owner, new TaskControl(10000), UUID.randomUUID());
            assertThrows(SecurityException.class, () -> deferred.continuation().resume(wrongTask));
            var otherLedger = new MaterialLedger(directory.resolve("partial-ledger")); var partial = completed(otherLedger, false);
            var result = new ProcessingFinalSaves(otherLedger, ignored -> { fail("Partial custody is not a completed physical delivery"); return null; })
                    .complete(context(manager, new TaskControl(10000)), partial.operationId(), current -> AgentTool.Result.error("OUTPUT_FULL", "retained custody"));
            assertFalse(result.success()); assertEquals("OUTPUT_FULL", result.errorCode());
            assertEquals(MaterialLedger.State.CALLBACK_DONE, otherLedger.get(owner, partial.operationId()).state());
            assertEquals(List.of(0), otherLedger.get(owner, partial.operationId()).delivered());
        }
    }
}
