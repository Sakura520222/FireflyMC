package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Journal protocol fixtures do not substitute for Minecraft's actual canonical-save evidence. */
class ProcessingBeforeDeliverySavesTest {
    @TempDir Path directory;
    private final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0);
    private final UUID task = UUID.randomUUID();
    private final MaterialStack input = new MaterialStack("{\"id\":\"minecraft:shulker_box\"}", 1);
    private final MaterialStack output = new MaterialStack("{\"id\":\"minecraft:chest\"}", 1);

    private MaterialLedger.Operation produced(MaterialLedger ledger) throws Exception {
        var stock = new MaterialLedger.Stock("player", 1, "player:" + owner.owner(), "slot:0", 0, input, 1, 1, 1);
        var op = ledger.reserve(owner, task, UUID.randomUUID(), AiJson.digest("native-production-plan"), List.of(stock),
                List.of(new MaterialLedger.Need(stock.key(), 1)));
        UUID id = op.operationId();
        ledger.beginExtraction(owner, id, 0, input, 1, "extract");
        ledger.extracted(owner, id, input, 0);
        ledger.nativeSaved(owner, id, "extract", "extract", AiJson.digest("unit-input-journal-proof"));
        ledger.beginNativeProduction(owner, id, "assemble");
        ledger.callbackPartEvidence(owner, id, "assemble", 0, true, true, "", Set.of("player:" + owner.owner()));
        return ledger.nativeProductionEvidence(owner, id, "assemble", List.of(output));
    }

    private void saveBacking(MaterialLedger ledger, UUID id) throws Exception {
        ledger.nativeSaved(owner, id, "callback-part", MaterialLedger.callbackPartWitness("assemble", 0),
                AiJson.digest("unit-shared-data-journal-proof"));
    }

    private AgentTool.Context context(WorkspaceManager manager, TaskControl control) throws Exception {
        return new AgentTool.Context(manager.getWorkspace(owner), owner, control, task);
    }

    @Test void backingProofAndItsJournalWriterMustBothFinishBeforeTheOnlyDeliveryContinuation() throws Exception {
        try (var manager = new WorkspaceManager(directory.resolve("workspace"))) {
            var ledger = new MaterialLedger(directory.resolve("ledger")); var op = produced(ledger); UUID id = op.operationId();
            var writer = new CompletableFuture<Void>(); var delivered = new AtomicInteger();
            var gate = new ProcessingBeforeDeliverySaves(ledger, ignored -> writer);
            var deferred = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(AgentTool.Deferred.class,
                    () -> gate.await(context(manager, new TaskControl(120000)), id, fresh -> {
                        assertTrue(MaterialLedger.nativeProductionBackingSaved(ledger.get(owner, id)));
                        ledger.beginDelivery(owner, id, 0, 1, "delivery", MaterialStack.EMPTY, Set.of("slot:1"));
                        ledger.delivered(owner, id, 1, true); delivered.incrementAndGet();
                        return AgentTool.Result.ok("actual output delivered");
                    })));
            assertEquals("WAITING_NATIVE_PRODUCTION_SAVE", deferred.reason());
            assertEquals(0, delivered.get()); assertEquals(List.of(0), ledger.get(owner, id).delivered());
            assertFalse(ledger.get(owner, id).saved());
            var early = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(IllegalStateException.class,
                    () -> deferred.continuation().resume(context(manager, new TaskControl(10000)))));
            assertEquals("DELIVERY_NATIVE_JOURNAL_NOT_READY", early.getMessage());
            assertEquals(op, ledger.get(owner, id));
            saveBacking(ledger, id);
            assertTrue(ledger.get(owner, id).saved());
            assertFalse(deferred.readiness().toCompletableFuture().isDone(), "Canonical proof cannot replace its unfinished durable writer");
            assertEquals(0, delivered.get()); writer.complete(null);
            assertTrue(deferred.continuation().resume(context(manager, new TaskControl(10000))).success());
            var completed = ledger.get(owner, id);
            assertEquals(1, delivered.get()); assertEquals(List.of(1), completed.delivered());
            assertEquals(MaterialLedger.State.COMPLETED, completed.state()); assertFalse(completed.saved(), "Delivery still needs its own native save");
            assertTrue(completed.escrow().isEmpty()); assertEquals(List.of(output), completed.sampledOutputs());
            assertThrows(IllegalStateException.class, () -> deferred.continuation().resume(context(manager, new TaskControl(10000))));
            assertEquals(completed, ledger.get(owner, id)); assertEquals(1, delivered.get());
        }
    }

    @Test void journalFailureOrMissingBackingProofQuarantinesTheActualOutputWithoutDelivery() throws Exception {
        try (var manager = new WorkspaceManager(directory.resolve("workspace"))) {
            for (boolean failedWriter : List.of(false, true)) {
                var ledger = new MaterialLedger(directory.resolve("ledger-" + failedWriter)); var op = produced(ledger); UUID id = op.operationId();
                var delivered = new AtomicInteger(); var writer = new CompletableFuture<Void>();
                var deferred = assertThrows(AgentTool.Deferred.class,
                        () -> new ProcessingBeforeDeliverySaves(ledger, ignored -> writer).await(context(manager, new TaskControl(10000)), id,
                                fresh -> { delivered.incrementAndGet(); return AgentTool.Result.ok("must not run"); }));
                if (failedWriter) {
                    saveBacking(ledger, id); writer.completeExceptionally(new java.io.IOException("durable journal failed"));
                } else writer.complete(null);
                assertDoesNotThrow(() -> deferred.readiness().toCompletableFuture().join());
                var failure = assertThrows(IllegalStateException.class,
                        () -> deferred.continuation().resume(context(manager, new TaskControl(10000))));
                assertEquals(failedWriter ? "DELIVERY_NATIVE_SAVE_JOURNAL_FAILED" : "DELIVERY_NATIVE_SAVE_PROOF_UNAVAILABLE", failure.getMessage());
                var held = ledger.get(owner, id);
                assertTrue(held.uncertain()); assertEquals(0, delivered.get()); assertEquals(List.of(0), held.delivered());
                assertEquals(List.of(output), held.products()); assertTrue(held.escrow().isEmpty()); assertNull(held.intent());
            }
        }
    }

    @Test void cancelledWaiterAndAnotherTaskCannotBorrowTheContinuationOrCancelTheWriter() throws Exception {
        try (var manager = new WorkspaceManager(directory.resolve("workspace"))) {
            var ledger = new MaterialLedger(directory.resolve("ledger")); var op = produced(ledger); UUID id = op.operationId();
            var writer = new CompletableFuture<Void>(); var delivered = new AtomicInteger();
            var deferred = assertThrows(AgentTool.Deferred.class,
                    () -> new ProcessingBeforeDeliverySaves(ledger, ignored -> writer).await(context(manager, new TaskControl(10000)), id,
                            fresh -> { delivered.incrementAndGet(); return AgentTool.Result.ok("must not run"); }));
            var wrongTask = new AgentTool.Context(manager.getWorkspace(owner), owner, new TaskControl(10000), UUID.randomUUID());
            assertThrows(SecurityException.class, () -> deferred.continuation().resume(wrongTask));
            for (String reason : List.of("CANCELLED", "USER_PAUSED", "TIMEOUT", "SHUTDOWN")) {
                var stopped = new TaskControl(10000); stopped.stop(reason);
                assertThrows(TaskControl.Stopped.class, () -> deferred.continuation().resume(context(manager, stopped)));
            }
            assertThrows(TaskControl.Stopped.class, () -> deferred.continuation().resume(context(manager, new TaskControl(0))));
            assertFalse(writer.isCancelled()); assertEquals(op, ledger.get(owner, id)); assertEquals(0, delivered.get());
            saveBacking(ledger, id); writer.complete(null);
            assertTrue(deferred.readiness().toCompletableFuture().isDone()); assertTrue(ledger.get(owner, id).saved());
            assertEquals(0, delivered.get(), "Late canonical evidence records custody without restarting a stopped task");
            assertEquals(MaterialLedger.State.CALLBACK_DONE, ledger.get(owner, id).state());
        }
    }
}
