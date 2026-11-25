package firefly520.fireflymc.ai.recovery;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Lifecycle tests use a ledger reservation with no native mutation; they do not claim game acceptance. */
class RecoveryWorkbenchServiceTest {
    @TempDir Path directory;
    @FunctionalInterface interface Behavior { AgentTool.Result run(AgentTool.Context context) throws Exception; }
    final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0);
    final UUID session = UUID.randomUUID();
    class Fixture implements AutoCloseable {
        final WorkspaceManager manager;
        final WorkspaceManager.Workspace workspace;
        final MaterialLedger ledger;
        final AgentTask task;
        final UUID operation = UUID.randomUUID();
        final ToolRegistry tools = new ToolRegistry();
        final CompletableFuture<Void> settled = new CompletableFuture<>();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
        final RecoveryWorkbenchService service;
        Fixture(Behavior behavior) throws Exception { this(behavior, null); }
        Fixture(Behavior behavior, RecoveryWorkbenchService.Dispatch dispatch) throws Exception {
            manager = new WorkspaceManager(directory); workspace = manager.getWorkspace(owner); ledger = new MaterialLedger(directory);
            task = AgentTask.create(owner.owner(), UUID.randomUUID(), "existing task").update(AgentTask.State.RECOVERY_REQUIRED, "RESTART_UNCERTAIN");
            workspace.conversation().append(List.of(Draft.checkpoint(task)));
            var stock = new MaterialLedger.Stock("chest", 1, "backend", "pool", 0,
                    new MaterialStack("{\"id\":\"minecraft:stone\"}", 2), 1, 2, 2);
            ledger.reserve(owner, task.id(), operation, AiJson.digest("fixed plan"), List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 1)));
            tools.register(new DefinedTool(new AgentTool.Descriptor("processing_return", "test reservation return", new JsonObject(),
                    AgentTool.Risk.PLAYER_STATE, "materials.own", 0, AgentTool.ExecutionThread.IO, 120000, true, ""),
                    (ctx, args) -> { calls.incrementAndGet(); return behavior.run(ctx); }));
            RecoveryWorkbenchService.Dispatch direct = (tool, context, args, approval, call) -> {
                try { return CompletableFuture.completedFuture(tool.execute(context, args)); }
                catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
            };
            service = new RecoveryWorkbenchService(ledger, null, null, tools, dispatch == null ? direct : dispatch,
                    id -> CompletableFuture.completedFuture(null), id -> owner, name -> true,
                    (workspace, task) -> settled.complete(null), clock::get);
        }
        JsonObject preview() throws Exception {
            return service.preview(workspace, owner, session, task.id(), RecoveryWorkbenchService.Action.MATERIAL_RETURN, operation, null);
        }
        JsonObject confirm(JsonObject preview) throws Exception {
            return service.confirm(workspace, owner, session, UUID.fromString(preview.get("token").getAsString()), preview.get("digest").getAsString());
        }
        JsonObject result() throws Exception {
            settled.get(5, TimeUnit.SECONDS);
            return workspace.conversation().latestEvent("workbench_recovery_result").orElseThrow().data();
        }
        @Override public void close() throws Exception {
            service.close(); service.finalizePendingClose(); assertTrue(service.awaitStopped(5000)); manager.close();
        }
    }
    @Test void acceptedDeferredActionResumesThroughFreshContextForTheOriginalTaskWithoutModelMessages() throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>(), entered = new CompletableFuture<>();
        AtomicReference<AgentTool.Context> initial = new AtomicReference<>();
        AtomicInteger resumed = new AtomicInteger();
        try (var f = new Fixture(ctx -> {
            initial.set(ctx); entered.complete(null);
            throw new AgentTool.Deferred("SAVE_PENDING", new JsonObject(), ready, fresh -> {
                assertEquals(ctx.taskId(), fresh.taskId()); assertNotSame(ctx, fresh); assertNotSame(ctx.control(), fresh.control());
                resumed.incrementAndGet(); return AgentTool.Result.ok("ledger-only continuation");
            });
        })) {
            var preview = f.preview(); assertTrue(f.confirm(preview).get("queued").getAsBoolean());
            entered.get(3, TimeUnit.SECONDS); assertFalse(f.settled.isDone());
            f.clock.addAndGet(180000); ready.complete(null);
            assertEquals("CUSTODY_OR_SAVE_PENDING", f.result().get("status").getAsString());
            assertEquals(1, f.calls.get()); assertEquals(1, resumed.get());
            assertEquals(f.task.id(), initial.get().taskId()); assertEquals(1, f.workspace.conversation().tasks().size());
            assertTrue(f.workspace.conversation().getSnapshot(0).events().stream().noneMatch(event -> event.message() != null || "tool_result".equals(event.kind())));
            assertThrows(IllegalStateException.class, () -> f.confirm(preview));
        }
    }
    @Test void disconnectWhileDeferredStopsFutureNativeWorkButStillRecordsTheActionReceipt() throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>(), entered = new CompletableFuture<>(); AtomicInteger resumed = new AtomicInteger();
        try (var f = new Fixture(ctx -> {
            entered.complete(null); throw new AgentTool.Deferred("SAVE_PENDING", new JsonObject(), ready,
                    fresh -> { resumed.incrementAndGet(); return AgentTool.Result.ok("must not run"); });
        })) {
            f.confirm(f.preview()); entered.get(3, TimeUnit.SECONDS);
            f.service.disconnect(owner.owner(), session);
            assertEquals("PLAYER_OFFLINE", f.result().get("status").getAsString());
            ready.complete(null); assertEquals(0, resumed.get());
            assertEquals(MaterialLedger.State.RESERVED, f.ledger.get(owner, f.operation).state());
        }
    }
    @Test void acceptedLateReceiptSurvivesShutdownAndTheMainThreadCloseOnlySignals() throws Exception {
        CompletableFuture<AgentTool.Result> accepted = new CompletableFuture<>(); CompletableFuture<AgentTool.Context> dispatched = new CompletableFuture<>();
        try (var f = new Fixture(ctx -> AgentTool.Result.ok("unused"), (tool, ctx, args, approval, call) -> {
            dispatched.complete(ctx); return accepted;
        })) {
            f.confirm(f.preview()); AgentTool.Context context = dispatched.get(3, TimeUnit.SECONDS);
            f.service.close(); assertEquals("AI_STOPPING", context.control().stopReason()); assertFalse(f.settled.isDone());
            f.ledger.cancel(owner, f.operation); accepted.complete(AgentTool.Result.ok("accepted ledger result"));
            JsonObject result = f.result(); assertEquals("AI_STOPPING", result.get("status").getAsString());
            assertTrue(result.getAsJsonObject("result").get("success").getAsBoolean());
            assertEquals("CANCELLED", result.getAsJsonObject("materialEvidence").get("state").getAsString());
        }
    }
    @Test void staleResourcesWrongOwnerAndUnknownTaskCannotAuthorizeRecovery() throws Exception {
        try (var f = new Fixture(ctx -> AgentTool.Result.ok("unused"))) {
            var view = f.service.view(f.workspace, owner, f.task.id(), 0).getAsJsonArray("operations").get(0).getAsJsonObject();
            assertEquals("ITEMS", view.get("kind").getAsString(), "The client enables material actions only for the advertised ITEMS kind");
            assertTrue(view.getAsJsonArray("actions").asList().stream().anyMatch(value -> value.getAsString().equals("MATERIAL_RETURN")));
            var preview = f.preview(); f.ledger.cancel(owner, f.operation);
            assertThrows(IllegalStateException.class, () -> f.confirm(preview)); assertEquals(0, f.calls.get());
            Actor foreign = Actor.player(UUID.randomUUID(), true, true, 4);
            assertThrows(SecurityException.class, () -> f.service.view(f.workspace, foreign, f.task.id(), 0));
            assertThrows(SecurityException.class, () -> f.service.view(f.workspace, owner, UUID.randomUUID(), 0));
        }
    }
    @Test void trustedWorldJournalOwnershipBlocksTheGenericMaterialPath() throws Exception {
        try (var f = new Fixture(ctx -> AgentTool.Result.ok("unused"))) {
            f.service.worldOperations((workspace, id) -> id.equals(f.operation));
            JsonObject row = f.service.view(f.workspace, owner, f.task.id(), 0).getAsJsonArray("operations").get(0).getAsJsonObject();
            assertTrue(row.getAsJsonArray("actions").isEmpty()); assertEquals("WORLD", row.get("kind").getAsString());
            assertThrows(IllegalStateException.class, f::preview);
        }
    }
    @Test void aFollowupRecoveryTaskNeedsItsOwnCommittedReferenceToTheOlderOwnedOperation() throws Exception {
        try (var f = new Fixture(ctx -> AgentTool.Result.ok("receipt only"))) {
            var followup = AgentTask.create(owner.owner(), UUID.randomUUID(), "return old custody")
                    .update(AgentTask.State.RECOVERY_REQUIRED, "RETURN_REQUIRES_REVIEW");
            f.workspace.conversation().append(List.of(Draft.checkpoint(followup)));
            assertThrows(SecurityException.class, () -> f.service.preview(f.workspace, owner, session, followup.id(),
                    RecoveryWorkbenchService.Action.MATERIAL_RETURN, f.operation, null));
            var call = new firefly520.fireflymc.ai.model.ModelMessage.ToolCall("committed-old-return", "processing_return",
                    "{\"operationId\":\"" + f.operation + "\"}");
            var assistant = new firefly520.fireflymc.ai.model.ModelMessage("assistant", "", List.of(call), null, null);
            f.workspace.conversation().append(List.of(Draft.message(followup.id(), assistant, null)));
            JsonObject preview = f.service.preview(f.workspace, owner, session, followup.id(),
                    RecoveryWorkbenchService.Action.MATERIAL_RETURN, f.operation, null);
            assertEquals(followup.id().toString(), preview.get("taskId").getAsString());
            assertTrue(f.confirm(preview).get("queued").getAsBoolean()); f.result();
            assertEquals(followup.id(), f.workspace.conversation().latestEvent("workbench_recovery_result").orElseThrow().taskId());
        }
    }
}
