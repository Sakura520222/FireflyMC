package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.material.MaterialLedger;
import firefly520.fireflymc.ai.material.MaterialStack;
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

class ProcessingInputSavesTest {
    @TempDir Path directory;
    final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0);
    final UUID task = UUID.randomUUID();
    final MaterialStack stone = new MaterialStack("{\"id\":\"minecraft:stone\"}", 2);

    private MaterialLedger.Operation escrow(MaterialLedger ledger) throws Exception {
        var stock = new MaterialLedger.Stock("player", 1, "player:" + owner.owner(), "own-slot", 0, stone, 1, 2, 2);
        var op = ledger.reserve(owner, task, UUID.randomUUID(), AiJson.digest("fixed-plan"), List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 2)));
        ledger.beginExtraction(owner, op.operationId(), 0, stone, 2, "first", 1);
        ledger.extracted(owner, op.operationId(), stone.withCount(1), 1);
        ledger.beginExtraction(owner, op.operationId(), 0, stone.withCount(1), 1, "second", 1);
        return ledger.extracted(owner, op.operationId(), stone.withCount(1), 0);
    }
    private AgentTool.Context context(WorkspaceManager manager, TaskControl control) throws Exception {
        return new AgentTool.Context(manager.getWorkspace(owner), owner, control, task);
    }
    private void save(MaterialLedger ledger, UUID id, String witness) throws Exception {
        ledger.nativeSaved(owner, id, "extract", witness, AiJson.digest("actual-native-save:" + witness));
    }

    @Test void waitingRetainsOnlyRealEscrowReleasesTheCallerAndSamplesExactlyOnceWithFreshControl() throws Exception {
        try (var manager = new WorkspaceManager(directory.resolve("workspace"))) {
            var ledger = new MaterialLedger(directory.resolve("ledger")); var op = escrow(ledger);
            var ready = new CompletableFuture<Void>(); var sampled = new AtomicInteger();
            var barrier = new ProcessingInputSaves(ledger, ignored -> ready);
            var original = new TaskControl(120000);
            var deferred = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(AgentTool.Deferred.class,
                    () -> barrier.await(context(manager, original), op.operationId(), fresh -> {
                        fresh.control().check(); sampled.incrementAndGet();
                        ledger.produceSampled(fresh.actor(), op.operationId(), List.of(stone.withCount(3)), "persisted-seed:42");
                        return AgentTool.Result.ok("one fixed sample");
                    })));
            assertEquals("WAITING_INPUT_SAVE", deferred.reason());
            assertEquals(MaterialLedger.State.ESCROWED, ledger.get(owner, op.operationId()).state());
            assertEquals(2, ledger.get(owner, op.operationId()).escrow().stream().mapToInt(MaterialStack::count).sum());
            assertTrue(ledger.get(owner, op.operationId()).products().isEmpty()); assertEquals(0, sampled.get());
            save(ledger, op.operationId(), "first"); assertFalse(deferred.readiness().toCompletableFuture().isDone());
            save(ledger, op.operationId(), "second"); original.stop("TASK_SLICE_FINISHED"); ready.complete(null);
            assertTrue(deferred.continuation().resume(context(manager, new TaskControl(10000))).success());
            assertEquals(1, sampled.get());
            assertEquals("persisted-seed:42", ledger.get(owner, op.operationId()).samplingEvidence());
            assertThrows(IllegalStateException.class, () -> deferred.continuation().resume(context(manager, new TaskControl(10000))));
            assertEquals(1, sampled.get());
        }
    }
    @Test void completedReadinessWithoutEveryInputProofFencesInsteadOfSpinningOrRunningTheBody() throws Exception {
        try (var manager = new WorkspaceManager(directory.resolve("workspace"))) {
            var ledger = new MaterialLedger(directory.resolve("ledger")); var op = escrow(ledger); save(ledger, op.operationId(), "first");
            var body = new AtomicInteger();
            var barrier = new ProcessingInputSaves(ledger, ignored -> CompletableFuture.completedFuture(null));
            var failure = assertThrows(IllegalStateException.class, () -> barrier.await(context(manager, new TaskControl(10000)), op.operationId(), fresh -> {
                body.incrementAndGet(); return AgentTool.Result.ok("must not run");
            }));
            assertEquals("RECOVERY_REQUIRED", failure.getMessage()); assertEquals(0, body.get());
            var retained = ledger.get(owner, op.operationId()); assertTrue(retained.uncertain());
            assertEquals("INPUT_NATIVE_SAVE_PROOF_UNAVAILABLE", retained.reason()); assertTrue(retained.products().isEmpty());
        }
    }
    @Test void failedJournalWriterWakesOnlyToFenceTheMissingProof() throws Exception {
        try (var manager = new WorkspaceManager(directory.resolve("workspace"))) {
            var ledger = new MaterialLedger(directory.resolve("ledger")); var op = escrow(ledger);
            var ready = new CompletableFuture<Void>(); var body = new AtomicInteger();
            var deferred = assertThrows(AgentTool.Deferred.class, () -> new ProcessingInputSaves(ledger, ignored -> ready)
                    .await(context(manager, new TaskControl(10000)), op.operationId(), fresh -> { body.incrementAndGet(); return AgentTool.Result.ok("bad"); }));
            ready.completeExceptionally(new java.io.IOException("journal failed"));
            assertDoesNotThrow(() -> deferred.readiness().toCompletableFuture().join());
            assertThrows(IllegalStateException.class, () -> deferred.continuation().resume(context(manager, new TaskControl(10000))));
            assertEquals(0, body.get()); assertTrue(ledger.get(owner, op.operationId()).uncertain());
        }
    }
    @Test void positiveProofsNeedNoWaitAndDoNotInvokeTheContinuationInline() throws Exception {
        try (var manager = new WorkspaceManager(directory.resolve("workspace"))) {
            var ledger = new MaterialLedger(directory.resolve("ledger")); var op = escrow(ledger);
            save(ledger, op.operationId(), "first"); save(ledger, op.operationId(), "second");
            new ProcessingInputSaves(ledger, ignored -> { fail("already saved inputs must not subscribe"); return null; })
                    .await(context(manager, new TaskControl(10000)), op.operationId(), fresh -> { fail("caller continues inline"); return null; });
            assertEquals(MaterialLedger.State.ESCROWED, ledger.get(owner, op.operationId()).state());
        }
    }
    @Test void stoppedFreshControlsNeverRunTheBodyOrCancelTheAcceptedNativeWriter() throws Exception {
        try (var manager = new WorkspaceManager(directory.resolve("workspace"))) {
            var ledger = new MaterialLedger(directory.resolve("ledger")); var op = escrow(ledger);
            var ready = new CompletableFuture<Void>(); var body = new AtomicInteger();
            var deferred = assertThrows(AgentTool.Deferred.class, () -> new ProcessingInputSaves(ledger, ignored -> ready)
                    .await(context(manager, new TaskControl(10000)), op.operationId(), fresh -> { body.incrementAndGet(); return AgentTool.Result.ok("bad"); }));
            for (String reason : List.of("CANCELLED", "USER_PAUSED", "SHUTDOWN")) {
                var stopped = new TaskControl(10000); stopped.stop(reason);
                assertThrows(TaskControl.Stopped.class, () -> deferred.continuation().resume(context(manager, stopped)));
            }
            assertThrows(TaskControl.Stopped.class, () -> deferred.continuation().resume(context(manager, new TaskControl(0))));
            assertEquals(0, body.get()); assertFalse(ready.isCancelled());
            assertEquals(op, ledger.get(owner, op.operationId()));
        }
    }
    @Test void changedTaskAndHistoricalRecoveryCannotBorrowTheDeferredBody() throws Exception {
        try (var manager = new WorkspaceManager(directory.resolve("workspace"))) {
            var ledger = new MaterialLedger(directory.resolve("ledger")); var op = escrow(ledger); var ready = new CompletableFuture<Void>();
            var deferred = assertThrows(AgentTool.Deferred.class, () -> new ProcessingInputSaves(ledger, ignored -> ready)
                    .await(context(manager, new TaskControl(10000)), op.operationId(), fresh -> { fail("unapproved context"); return null; }));
            var wrong = new AgentTool.Context(manager.getWorkspace(owner), owner, new TaskControl(10000), UUID.randomUUID());
            assertThrows(SecurityException.class, () -> deferred.continuation().resume(wrong));
            var restarted = new MaterialLedger(directory.resolve("ledger")); assertTrue(restarted.get(owner, op.operationId()).uncertain());
            assertThrows(IllegalStateException.class, () -> new ProcessingInputSaves(restarted, ignored -> ready)
                    .await(context(manager, new TaskControl(10000)), op.operationId(), fresh -> null));
        }
    }
}
