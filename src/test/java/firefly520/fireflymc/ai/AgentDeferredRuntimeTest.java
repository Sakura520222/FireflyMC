package firefly520.fireflymc.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import firefly520.fireflymc.ai.agent.AgentRuntime;
import firefly520.fireflymc.ai.context.ContextCompactor;
import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.model.ModelGateway;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.security.SecretFilter;
import firefly520.fireflymc.ai.security.ToolPolicyEngine;
import firefly520.fireflymc.ai.task.AgentTask;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.task.TaskScheduler;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.tools.ToolRegistry;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class AgentDeferredRuntimeTest {
    @TempDir Path directory;
    private final Actor owner = Actor.player(UUID.randomUUID(), true, true, 0);
    private final Actor other = Actor.player(UUID.randomUUID(), true, true, 0);

    private static void until(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) fail("Timed out waiting for deferred lifecycle");
            Thread.sleep(5);
        }
    }

    private final class Harness implements AgentRuntime.Environment, AutoCloseable {
        final WorkspaceManager manager = new WorkspaceManager(directory);
        final TaskScheduler scheduler = new TaskScheduler(1, 8, 4);
        final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1));
        final ToolRegistry tools = new ToolRegistry();
        final Map<String, Actor> actors = new ConcurrentHashMap<>();
        final CompletableFuture<Void> readiness = new CompletableFuture<>();
        final AtomicInteger extractions = new AtomicInteger();
        final AtomicInteger continuations = new AtomicInteger();
        final List<String> approvals = new CopyOnWriteArrayList<>();
        final AgentRuntime runtime;
        volatile TaskControl originalControl;
        volatile boolean enabled = true;
        volatile boolean rejectDispatch;
        volatile boolean repeatDeferred;
        volatile boolean twoCalls;
        volatile boolean verifiedSettlement;
        final long toolBudget;

        Harness() throws Exception { this(1000); }
        Harness(long toolBudget) throws Exception {
            this.toolBudget = toolBudget;
            actors.put(owner.owner(), owner);
            actors.put(other.owner(), other);
            tools.register(new AgentTool() {
                @Override public Descriptor descriptor() {
                    return new Descriptor("deferred", "wait for native save", JsonParser.parseString(
                            "{\"type\":\"object\",\"properties\":{}}").getAsJsonObject(), Risk.WORLD_CHANGE,
                            "materials.own", 0, ExecutionThread.IO, toolBudget, true, "");
                }
                @Override public Result execute(Context context, JsonObject arguments) throws Exception {
                    originalControl = context.control();
                    extractions.incrementAndGet();
                    if (repeatDeferred) Thread.sleep(70);
                    JsonObject checkpoint = new JsonObject();
                    checkpoint.addProperty("operationId", "owned-operation");
                    throw new Deferred("WAITING_INPUT_SAVE", checkpoint, readiness, fresh -> {
                        assertSame(originalControl, fresh.control(), "The accepted native invocation keeps its cancellation authority across IO slices");
                        fresh.control().check();
                        assertEquals(owner.owner(), fresh.actor().owner());
                        continuations.incrementAndGet();
                        if (repeatDeferred) {
                            Thread.sleep(70);
                            throw new Deferred("WAITING_OUTPUT_SAVE", checkpoint,
                                    CompletableFuture.completedFuture(null), ignored -> Result.ok("should not run"));
                        }
                        return Result.ok("saved input converted exactly once");
                    });
                }
            });
            ModelGateway gateway = (request, control) -> {
                if (request.messages().stream().anyMatch(m -> m.text().contains("reader"))
                        || request.messages().stream().anyMatch(m -> "tool".equals(m.role())))
                    return new ModelGateway.Reply(ModelMessage.text("assistant", "verified"), 10, 10);
                var first = new ModelMessage.ToolCall("accepted-call", "deferred", "{}");
                return new ModelGateway.Reply(new ModelMessage("assistant", "", twoCalls ? List.of(first,
                        new ModelMessage.ToolCall("different-call", "deferred", "{}")) : List.of(first), null, null), 10, 10);
            };
            runtime = new AgentRuntime(manager, scheduler, gateway, tools,
                    new ContextCompactor(new ContextCompactor.Settings(32000, 512, 1024, 512, .75, 2), "test"),
                    new AgentRuntime.Limits(8, 8, 100000, 30000, 512, true, false),
                    this, new SecretFilter(List.of()));
        }
        @Override public Actor actor(String owner) { return actors.get(owner); }
        @Override public boolean enabled(String name) { return enabled; }
        @Override public boolean recoverySettled(Workspace workspace, UUID task) { return verifiedSettlement; }
        @Override public void changed(Workspace workspace, AgentTask task, String notice) {}
        @Override public CompletableFuture<AgentTool.Result> execute(AgentTool tool, AgentTool.Context context,
                JsonObject args, String approval, ModelMessage.ToolCall call) {
            CompletableFuture<AgentTool.Result> result = new CompletableFuture<>();
            approvals.add(approval);
            try {
                io.execute(() -> {
                    try {
                        Actor fresh = actor(context.actor().owner());
                        var verdict = new ToolPolicyEngine().check(fresh, context.workspace().owner(),
                                tool.descriptor(), enabled, call, approval);
                        if (rejectDispatch || verdict.decision() != ToolPolicyEngine.Decision.ALLOW) {
                            JsonObject dispatch = new JsonObject();
                            dispatch.addProperty("dispatch", "REJECTED_BEFORE_EXECUTION");
                            result.complete(new AgentTool.Result(false, rejectDispatch ? "TOOL_QUEUE_FULL" : verdict.reason(),
                                    "dispatch rejected", dispatch, "", "", false));
                        } else result.complete(tool.execute(new AgentTool.Context(context.workspace(), fresh,
                                context.control(), context.taskId(), context.modelResultBytes()), args));
                    } catch (Exception failure) { result.completeExceptionally(failure); }
                });
            } catch (RejectedExecutionException failure) { result.completeExceptionally(failure); }
            return result;
        }
        Workspace workspace() throws Exception { return manager.getWorkspace(owner); }
        AgentTask task(UUID id) throws Exception { return workspace().conversation().task(id); }
        AgentTask await(UUID id, AgentTask.State state) throws Exception {
            ConversationStore store = workspace().conversation();
            until(() -> store.task(id).state() == state);
            return store.task(id);
        }
        UUID waiting() throws Exception {
            var task = runtime.submit(owner, UUID.randomUUID(), "process my fixed plan");
            await(task.id(), AgentTask.State.WAITING_APPROVAL);
            runtime.control(owner, task.id(), "approve");
            await(task.id(), AgentTask.State.WAITING_EXTERNAL);
            until(() -> !scheduler.isBusy(owner.owner()) && io.getActiveCount() == 0);
            return task.id();
        }
        long eventCount(UUID id, String kind) throws Exception {
            return workspace().conversation().getSnapshot(0).events().stream()
                    .filter(e -> id.equals(e.taskId()) && kind.equals(e.kind())).count();
        }
        void assertClosed(UUID id) throws Exception {
            assertEquals(1, eventCount(id, "tool_start"));
            assertEquals(1, eventCount(id, "tool_result"));
            assertEquals(1, task(id).toolCalls());
            assertEquals(task(id).pending().size(), task(id).nextCall());
        }
        @Override public void close() throws Exception {
            runtime.close();
            assertTrue(runtime.awaitStopped(10000));
            runtime.finalizePendingClose();
            io.shutdownNow();
            assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));
            manager.close();
        }
    }

    @Test void onlyVerifiedSettlementEndsRecoveryAndReleasesTheQueueWithoutReplayingTheCall() throws Exception {
        try (var h = new Harness()) {
            UUID id = h.waiting(); h.runtime.control(owner, id, "cancel"); h.await(id, AgentTask.State.RECOVERY_REQUIRED);
            var queued = h.runtime.submit(owner, UUID.randomUUID(), "reader queued behind recovery");
            assertThrows(IllegalStateException.class, () -> h.runtime.acknowledgeRecovery(owner, id));
            assertEquals(AgentTask.State.QUEUED, h.task(queued.id()).state());
            h.readiness.complete(null); h.verifiedSettlement = true;
            assertThrows(IllegalArgumentException.class, () -> h.runtime.acknowledgeRecovery(other, id));
            assertEquals(AgentTask.State.FAILED, h.runtime.acknowledgeRecovery(owner, id).state());
            h.await(queued.id(), AgentTask.State.COMPLETED);
            assertEquals(1, h.extractions.get()); assertEquals(0, h.continuations.get()); h.assertClosed(id);
            assertEquals("RECOVERY_SETTLED_REPLAN_REQUIRED", h.task(id).reason());
        }
    }

    @Test void waitReleasesBothWorkersPreservesApprovalAndCompletesTheSameCallOnce() throws Exception {
        try (var h = new Harness()) {
            UUID id = h.waiting();
            long elapsed = h.task(id).elapsedMillis();
            assertNull(h.originalControl.stopReason());
            assertDoesNotThrow(h.originalControl::check);
            assertTrue(h.io.submit(() -> true).get(2, TimeUnit.SECONDS));
            var queued = h.runtime.submit(owner, UUID.randomUUID(), "reader queued behind accepted transaction");
            var reader = h.runtime.submit(other, UUID.randomUUID(), "reader");
            ConversationStore otherStore = h.manager.getWorkspace(other).conversation();
            until(() -> otherStore.task(reader.id()).state() == AgentTask.State.COMPLETED);
            assertEquals(AgentTask.State.WAITING_EXTERNAL, h.task(id).state());
            assertEquals(AgentTask.State.QUEUED, h.task(queued.id()).state());
            assertEquals(elapsed, h.task(id).elapsedMillis());
            assertEquals(0, h.task(id).toolCalls());
            assertFalse(h.task(id).approvalDigest().isBlank());
            h.readiness.complete(null);
            assertFalse(h.readiness.complete(null));
            h.await(id, AgentTask.State.COMPLETED);
            h.await(queued.id(), AgentTask.State.COMPLETED);
            h.assertClosed(id);
            assertEquals(1, h.extractions.get());
            assertEquals(1, h.continuations.get());
            assertEquals(2, h.approvals.size());
            assertEquals(h.approvals.getFirst(), h.approvals.getLast());
            assertEquals(1, h.eventCount(id, "approval"));
            assertEquals(1, h.eventCount(id, "tool_wait_external"));
            assertEquals("TOOL_TRANSACTION_FINISHED", h.originalControl.stopReason());
        }
    }

    @Test void retainedNativeControlSurvivesWorkerExitAndReadinessNeverWaitsForTheJournalLock() throws Exception {
        try (var h = new Harness()) {
            UUID id = h.waiting();
            // The native job still owns this exact context after both IO workers have returned.
            assertDoesNotThrow(h.originalControl::check);
            synchronized (h.workspace().conversation()) {
                CompletableFuture.runAsync(() -> h.readiness.complete(null)).get(2, TimeUnit.SECONDS);
                assertNull(h.originalControl.stopReason(), "A native completion must only enqueue IO; it cannot retire before the receipt is saved");
            }
            h.await(id, AgentTask.State.COMPLETED);
            h.assertClosed(id);
            assertEquals("TOOL_TRANSACTION_FINISHED", h.originalControl.stopReason());
            assertEquals(1, h.continuations.get());
        }
    }

    @Test void cancellingAnotherStartedTaskCannotLiftAnOwnersUnresolvedRecoveryBarrier() throws Exception {
        try (var h = new Harness()) {
            UUID recovery = h.waiting(); h.runtime.control(owner, recovery, "cancel"); h.await(recovery, AgentTask.State.RECOVERY_REQUIRED);
            var paused = AgentTask.create(owner.owner(), UUID.randomUUID(), "earlier paused task")
                    .progress(0, 0, 0, 0, List.of(), 0, "", true, true).update(AgentTask.State.PAUSED, "USER_PAUSED");
            h.workspace().conversation().append(List.of(ConversationStore.Draft.checkpoint(paused)));
            var reader = h.runtime.submit(owner, UUID.randomUUID(), "reader queued behind another recovery");
            h.runtime.control(owner, paused.id(), "cancel");
            assertTrue(h.scheduler.isBlocked(owner.owner()));
            assertEquals(AgentTask.State.QUEUED, h.task(reader.id()).state());
            assertEquals(AgentTask.State.RECOVERY_REQUIRED, h.task(recovery).state());
            h.verifiedSettlement = true; h.runtime.acknowledgeRecovery(owner, recovery);
            h.await(reader.id(), AgentTask.State.COMPLETED);
            assertEquals(0, h.continuations.get()); h.assertClosed(recovery);
        }
    }

    @Test void cancellationAndPauseCloseAcceptedOutcomeWithoutDispatchOrOrdinaryResume() throws Exception {
        for (String action : List.of("cancel", "pause")) {
            // Each harness must use a distinct manager lock/root.
            directory = directory.resolve(action);
            try (var h = new Harness()) {
                UUID id = h.waiting();
                h.runtime.control(owner, id, action);
                assertEquals(action.equals("cancel") ? "CANCELLED" : "USER_PAUSED", h.originalControl.stopReason());
                h.await(id, AgentTask.State.RECOVERY_REQUIRED);
                h.readiness.complete(null);
                h.assertClosed(id);
                assertEquals(0, h.continuations.get());
                assertThrows(IllegalStateException.class, () -> h.runtime.control(owner, id, "resume"));
                String log = h.workspace().conversation().getSnapshot(0).events().stream()
                        .filter(e -> "tool_result".equals(e.kind())).findFirst().orElseThrow().data().toString();
                assertTrue(log.contains("UNKNOWN_REQUIRES_RECONCILIATION"));
                assertTrue(log.contains("owned-operation"));
            }
        }
    }

    @Test void lostAuthenticationDisabledToolAndOfflineNeverResumeAnApprovedEffect() throws Exception {
        for (String change : List.of("auth", "disabled", "offline")) {
            directory = directory.resolve(change);
            try (var h = new Harness()) {
                UUID id = h.waiting();
                if (change.equals("auth")) h.actors.put(owner.owner(), Actor.player(UUID.fromString(owner.owner()), false, true, 0));
                if (change.equals("disabled")) h.enabled = false;
                if (change.equals("offline")) {
                    h.actors.put(owner.owner(), Actor.player(UUID.fromString(owner.owner()), true, false, 0));
                    h.runtime.disconnected(owner.owner());
                } else h.readiness.complete(null);
                h.await(id, AgentTask.State.RECOVERY_REQUIRED);
                h.readiness.complete(null);
                h.assertClosed(id);
                assertEquals(0, h.continuations.get());
            }
        }
    }

    @Test void failedReadinessAndDispatchRejectionKeepUnknownAcceptedReceipt() throws Exception {
        for (boolean reject : List.of(false, true)) {
            directory = directory.resolve(Boolean.toString(reject));
            try (var h = new Harness()) {
                UUID id = h.waiting();
                h.rejectDispatch = reject;
                if (reject) h.readiness.complete(null);
                else h.readiness.completeExceptionally(new IllegalStateException("native ledger writer failed"));
                h.await(id, AgentTask.State.RECOVERY_REQUIRED);
                h.assertClosed(id);
                assertEquals(0, h.continuations.get());
                assertEquals(reject ? "TOOL_QUEUE_FULL" : "EXTERNAL_READINESS_FAILED", h.task(id).reason());
            }
        }
    }

    @Test void shutdownClosesPendingResultAndLateReadinessCannotResume() throws Exception {
        try (var h = new Harness()) {
            UUID id = h.waiting();
            h.runtime.close();
            assertEquals("SHUTDOWN", h.originalControl.stopReason());
            assertTrue(h.runtime.awaitStopped(10000));
            assertEquals(AgentTask.State.WAITING_EXTERNAL, h.task(id).state());
            h.runtime.finalizePendingClose();
            h.await(id, AgentTask.State.RECOVERY_REQUIRED);
            h.readiness.complete(null);
            h.assertClosed(id);
            assertEquals(0, h.continuations.get());
            assertEquals("SHUTDOWN", h.task(id).reason());
        }
    }

    @Test void restartWithoutTrustedContinuationBackfillsAndRetainsRecoveryBarrier() throws Exception {
        try (var h = new Harness()) {
            UUID id = h.waiting();
            try (var replacementScheduler = new TaskScheduler(1, 8, 4)) {
                AgentRuntime replacement = new AgentRuntime(h.manager, replacementScheduler,
                        (request, control) -> { fail("restart must not invoke model"); return null; }, h.tools,
                        new ContextCompactor(new ContextCompactor.Settings(32000, 512, 1024, 512, .75, 2), "test"),
                        new AgentRuntime.Limits(8, 8, 100000, 30000, 512, true, false), h, new SecretFilter(List.of()));
                replacement.recover();
                h.await(id, AgentTask.State.RECOVERY_REQUIRED);
                h.assertClosed(id);
                assertEquals("RESTART_EXECUTION_UNCERTAIN", h.task(id).reason());
                assertThrows(IllegalStateException.class, () -> replacement.control(owner, id, "resume"));
                replacement.close();
                assertTrue(replacement.awaitStopped(10000));
                replacement.finalizePendingClose();
            }
        }
    }

    @Test void repeatedDeferralDoesNotResetTheAcceptedCallsActiveToolBudget() throws Exception {
        try (var h = new Harness(120)) {
            h.repeatDeferred = true;
            UUID id = h.waiting();
            h.readiness.complete(null);
            h.await(id, AgentTask.State.RECOVERY_REQUIRED);
            h.assertClosed(id);
            assertEquals(1, h.extractions.get());
            assertEquals(1, h.continuations.get());
        }
    }

    @Test void checkpointIsAnImmutableBoundedSnapshot() {
        JsonObject source = new JsonObject(); source.addProperty("operationId", "original");
        var deferred = new AgentTool.Deferred("WAITING_INPUT_SAVE", source,
                new CompletableFuture<>(), fresh -> AgentTool.Result.ok("done"));
        source.addProperty("operationId", "mutated");
        deferred.checkpoint().addProperty("operationId", "also-mutated");
        assertEquals("original", deferred.checkpoint().get("operationId").getAsString());
        source.addProperty("huge", "x".repeat(4097));
        assertThrows(IllegalArgumentException.class, () -> new AgentTool.Deferred("WAITING_INPUT_SAVE", source,
                new CompletableFuture<>(), fresh -> AgentTool.Result.ok("done")));
    }

    @Test void originalApprovalDoesNotAuthorizeTheNextCallInTheSameTransaction() throws Exception {
        try (var h = new Harness()) {
            h.twoCalls = true;
            UUID id = h.waiting();
            h.readiness.complete(null);
            h.await(id, AgentTask.State.WAITING_APPROVAL);
            assertEquals(1, h.extractions.get());
            assertEquals(1, h.continuations.get());
            assertEquals(1, h.task(id).nextCall());
            assertEquals("", h.task(id).approvalDigest());
            assertEquals(1, h.eventCount(id, "tool_start"));
            assertEquals(1, h.eventCount(id, "tool_result"));
            assertEquals(1, h.eventCount(id, "approval"));
            h.runtime.control(owner, id, "cancel");
            h.await(id, AgentTask.State.CANCELLED);
            assertEquals(2, h.eventCount(id, "tool_result"));
            assertEquals(1, h.extractions.get());
        }
    }
}
