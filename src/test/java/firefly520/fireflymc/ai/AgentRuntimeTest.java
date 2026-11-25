package firefly520.fireflymc.ai;

import com.google.gson.*;
import firefly520.fireflymc.ai.agent.AgentRuntime;
import firefly520.fireflymc.ai.context.ContextCompactor;
import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.model.*;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeTest {
    @TempDir Path directory;
    private final Actor a = Actor.player(UUID.randomUUID(), true, true, 4);
    private final Actor b = Actor.player(UUID.randomUUID(), true, true, 0);

    private static ModelGateway.Reply text(String text) {
        return new ModelGateway.Reply(ModelMessage.text("assistant", text), 10, 10);
    }
    private static ModelGateway.Reply calls(String... ids) {
        return new ModelGateway.Reply(new ModelMessage("assistant", "", Arrays.stream(ids)
                .map(id -> new ModelMessage.ToolCall(id, "echo", "{}")).toList(), null, null), 10, 10);
    }
    private static void until(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) fail("Timed out waiting for task state");
            Thread.sleep(10);
        }
    }

    private final class Harness implements AutoCloseable, AgentRuntime.Environment {
        final WorkspaceManager manager;
        final ToolRegistry tools = new ToolRegistry();
        final TaskScheduler scheduler = new TaskScheduler(2, 8, 32);
        final Map<String, Actor> actors = new ConcurrentHashMap<>();
        final List<String> notices = new CopyOnWriteArrayList<>();
        final AtomicInteger executions = new AtomicInteger();
        final AgentRuntime runtime;
        volatile boolean enabled = true;
        volatile CompletableFuture<AgentTool.Result> heldTool;
        volatile TaskControl lastToolControl;
        volatile boolean nativeRecoveryOnExecute;
        volatile UUID nativeRecoveryTask;

        Harness(ModelGateway gateway, AgentTool.Risk risk, int required, int maxSteps, int maxCalls,
                long milliseconds, long tokens) throws Exception {
            manager = new WorkspaceManager(directory);
            actors.put(a.owner(), a);
            actors.put(b.owner(), b);
            tools.register(new AgentTool() {
                @Override public Descriptor descriptor() {
                    JsonObject schema = JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}").getAsJsonObject();
                    return new Descriptor("echo", "test evidence", schema, risk, "test", required,
                            ExecutionThread.IO, 1000, risk != Risk.READ_ONLY, "");
                }
                @Override public Result execute(Context context, JsonObject args) {
                    executions.incrementAndGet();
                    return Result.ok("evidence-owner:" + context.actor().owner());
                }
            });
            WorkspaceTools.register(tools);
            QuestionStore.register(tools);
            runtime = new AgentRuntime(manager, scheduler, gateway, tools,
                    new ContextCompactor(new ContextCompactor.Settings(32000, 512, 1024, 512, .75, 2), "test"),
                    new AgentRuntime.Limits(maxSteps, maxCalls, tokens, milliseconds, 512, true, false),
                    this, new SecretFilter(List.of("SECRET_NOT_TO_LEAK")));
        }
        @Override public Actor actor(String owner) { return actors.get(owner); }
        @Override public boolean enabled(String tool) { return enabled; }
        @Override public CompletableFuture<AgentTool.Result> execute(AgentTool tool, AgentTool.Context context,
                JsonObject args, String approval, ModelMessage.ToolCall call) {
            lastToolControl = context.control();
            if (nativeRecoveryOnExecute) nativeRecoveryTask = context.taskId();
            if (heldTool != null && tool.descriptor().name().equals("echo")) return heldTool;
            try { return CompletableFuture.completedFuture(tool.execute(context, args)); }
            catch (Exception e) { return CompletableFuture.failedFuture(e); }
        }
        @Override public boolean requiresRecovery(Workspace workspace, UUID taskId) { return taskId.equals(nativeRecoveryTask); }
        @Override public AgentTool.Result auditToolResult(Workspace workspace, AgentTask task, ModelMessage.ToolCall call, AgentTool.Result result) {
            if (!task.id().equals(nativeRecoveryTask)) return result;
            var evidence = new JsonObject(); evidence.addProperty("nativeEffectPending", true);
            return new AgentTool.Result(false, "RECOVERY_REQUIRED", "Native receipt remains unresolved", evidence, "owned-native-journal", "", false);
        }
        @Override public void changed(Workspace workspace, AgentTask task, String notice) { notices.add(notice); }
        AgentTask submit(Actor actor, String text) throws Exception {
            return runtime.submit(actor, UUID.randomUUID(), text);
        }
        Workspace workspace(Actor actor) throws Exception { return manager.getWorkspace(actor); }
        AgentTask await(Actor actor, UUID id, AgentTask.State state) throws Exception {
            var store = workspace(actor).conversation();
            until(() -> store.task(id).state() == state);
            return store.task(id);
        }
        @Override public void close() throws Exception {
            runtime.close();
            assertTrue(runtime.awaitStopped(10000));
            manager.close();
        }
    }

    @Test void twoPlayersInterleaveMultipleToolRoundsWithoutHistoryOrResultLeakage() throws Exception {
        CountDownLatch firstRequests = new CountDownLatch(2);
        ModelGateway gateway = (request, control) -> {
            long results = request.messages().stream().filter(m -> m.role().equals("tool")).count();
            if (results == 0) {
                firstRequests.countDown();
                assertTrue(firstRequests.await(5, TimeUnit.SECONDS));
                return calls("first", "second");
            }
            if (results == 2) return calls("third");
            assertEquals(3, results);
            return text("verified chain");
        };
        try (var h = new Harness(gateway, AgentTool.Risk.READ_ONLY, 0, 8, 10, 30000, 100000)) {
            var ta = h.submit(a, "alpha-only");
            var tb = h.submit(b, "beta-only");
            h.await(a, ta.id(), AgentTask.State.COMPLETED);
            h.await(b, tb.id(), AgentTask.State.COMPLETED);
            assertEquals(6, h.executions.get());
            for (Actor actor : List.of(a, b)) {
                String history = firefly520.fireflymc.ai.storage.AiJson.GSON.toJson(h.workspace(actor).conversation().getSnapshot(0));
                assertTrue(history.contains(actor.owner()));
                assertFalse(history.contains(actor == a ? b.owner() : a.owner()));
                assertFalse(history.contains(actor == a ? "beta-only" : "alpha-only"));
                assertPaired(h.workspace(actor).conversation());
            }
        }
    }
    @Test void recordedNativeRecoveryOverridesModelCompletionAndCannotBeClearedWithOrdinaryControls() throws Exception {
        try (var h = new Harness((request, control) -> {
            if (request.messages().stream().anyMatch(message -> message.text().contains("independent-beta"))) return text("beta ready");
            if (request.messages().stream().noneMatch(message -> message.role().equals("tool"))) return calls("native-call");
            return text("model claims completion despite the native receipt");
        }, AgentTool.Risk.WORLD_CHANGE, 0, 8, 10, 30000, 100000)) {
            h.nativeRecoveryOnExecute = true; var task = h.submit(a, "native-alpha");
            h.await(a, task.id(), AgentTask.State.WAITING_APPROVAL); h.runtime.control(a, task.id(), "approve");
            h.await(a, task.id(), AgentTask.State.RECOVERY_REQUIRED);
            var history = h.workspace(a).conversation().getSnapshot(0);
            var recorded = history.events().stream().filter(event -> event.kind().equals("tool_result")).findFirst().orElseThrow();
            assertFalse(recorded.data().get("success").getAsBoolean());
            assertTrue(recorded.data().get("fullResult").getAsString().contains("nativeEffectPending"));
            assertTrue(history.events().stream().anyMatch(event -> event.kind().equals("task_notice") && event.data().get("essential").getAsBoolean()));
            assertThrows(IllegalStateException.class, () -> h.runtime.control(a, task.id(), "resume"));
            h.runtime.control(a, task.id(), "cancel");
            assertEquals(AgentTask.State.RECOVERY_REQUIRED, h.workspace(a).conversation().task(task.id()).state());
            h.nativeRecoveryOnExecute = false;
            var other = h.submit(b, "independent-beta"); h.await(b, other.id(), AgentTask.State.COMPLETED);
            assertPaired(h.workspace(a).conversation()); assertEquals(1, h.executions.get());
        }
    }

    @Test void pauseKeepsAcceptedEffectReceiptAndResumesWithoutReplayingThatCall() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (var h = new Harness((request, control) -> requests.incrementAndGet() == 1 ? calls("placed-prefix") : text("known prefix retained"),
                AgentTool.Risk.WORLD_CHANGE, 0, 8, 8, 30000, 100000)) {
            var receipt = new CompletableFuture<AgentTool.Result>(); h.heldTool = receipt;
            var task = h.submit(a, "pause known prefix"); h.await(a, task.id(), AgentTask.State.WAITING_APPROVAL);
            h.runtime.control(a, task.id(), "approve"); until(() -> h.lastToolControl != null);
            h.runtime.control(a, task.id(), "pause"); until(() -> "USER_PAUSED".equals(h.lastToolControl.stopReason()));
            assertFalse(receipt.isCancelled(), "The accepted effect must retain its evidence promise");
            JsonObject evidence = new JsonObject(); evidence.addProperty("verifiedPlacements", 1); evidence.addProperty("heldUnits", 2);
            receipt.complete(new AgentTool.Result(false, "PROCESSING_PAUSED", "one placement verified, two held", evidence, "native-receipt", "one block", false));
            h.await(a, task.id(), AgentTask.State.PAUSED); until(() -> !h.scheduler.isBusy(a.owner()));
            var output = h.workspace(a).conversation().getSnapshot(0).events().stream().filter(event -> event.kind().equals("tool_result")).findFirst().orElseThrow();
            assertTrue(output.data().get("fullResult").getAsString().contains("verifiedPlacements"));
            assertEquals(1, h.workspace(a).conversation().task(task.id()).nextCall());
            h.runtime.control(a, task.id(), "resume"); h.await(a, task.id(), AgentTask.State.COMPLETED);
            assertEquals(2, requests.get()); assertPaired(h.workspace(a).conversation());
            assertEquals(1, h.workspace(a).conversation().getSnapshot(0).events().stream().filter(event -> event.kind().equals("tool_start")).count());
        }
    }

    @Test void cancellationRecordsLateKnownSuccessAndClosesRemainingCallsWithoutExecutingThem() throws Exception {
        try (var h = new Harness((request, control) -> calls("accepted", "must-not-dispatch"),
                AgentTool.Risk.WORLD_CHANGE, 0, 8, 8, 30000, 100000)) {
            var receipt = new CompletableFuture<AgentTool.Result>(); h.heldTool = receipt;
            var task = h.submit(a, "cancel after native acceptance"); h.await(a, task.id(), AgentTask.State.WAITING_APPROVAL);
            h.runtime.control(a, task.id(), "approve"); until(() -> h.lastToolControl != null);
            h.runtime.control(a, task.id(), "cancel"); receipt.complete(AgentTool.Result.ok("actual accepted native effect recorded"));
            h.await(a, task.id(), AgentTask.State.CANCELLED); until(() -> !h.scheduler.isBusy(a.owner()));
            var history = h.workspace(a).conversation().getSnapshot(0);
            var outputs = history.events().stream().filter(event -> event.kind().equals("tool_result")).toList();
            assertEquals(2, outputs.size()); assertTrue(outputs.getFirst().data().get("success").getAsBoolean());
            assertEquals("CANCELLED", outputs.getLast().data().get("errorCode").getAsString());
            assertEquals(1, history.events().stream().filter(event -> event.kind().equals("tool_start")).count());
            assertPaired(h.workspace(a).conversation());
        }
    }

    @Test void missingAcceptedReceiptStillRequiresReconciliationAndCannotResume() throws Exception {
        try (var h = new Harness((request, control) -> calls("accepted-unknown"),
                AgentTool.Risk.WORLD_CHANGE, 0, 8, 8, 30000, 100000)) {
            var receipt = new CompletableFuture<AgentTool.Result>(); h.heldTool = receipt;
            var task = h.submit(a, "cancel with missing receipt"); h.await(a, task.id(), AgentTask.State.WAITING_APPROVAL);
            h.runtime.control(a, task.id(), "approve"); until(() -> h.lastToolControl != null);
            h.runtime.control(a, task.id(), "cancel"); h.await(a, task.id(), AgentTask.State.RECOVERY_REQUIRED);
            assertFalse(receipt.isCancelled()); assertThrows(IllegalStateException.class, () -> h.runtime.control(a, task.id(), "resume"));
            receipt.complete(AgentTool.Result.ok("late receipt alone cannot resume"));
            assertEquals(AgentTask.State.RECOVERY_REQUIRED, h.workspace(a).conversation().task(task.id()).state());
            assertPaired(h.workspace(a).conversation());
        }
    }

    @Test void blockingQuestionReleasesWorkerAndAnswerContinuesTheSameToolTransactionWithoutGrantingApproval() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        String answerText = "石头；" + "只是测试，没有具体需求。".repeat(60);
        try (var h = new Harness((request, control) -> {
            if (request.messages().stream().anyMatch(m -> "beta".equals(m.text()))) return text("beta complete");
            if (modelCalls.incrementAndGet() == 1) return new ModelGateway.Reply(new ModelMessage("assistant", "",
                    List.of(new ModelMessage.ToolCall("question", "task_ask",
                                    "{\"prompt\":\"请选择材料\",\"choices\":[\"石头\",\"木头\"],\"blocking\":true}"),
                            new ModelMessage.ToolCall("write", "echo", "{}")), null, null), 10, 10);
            assertEquals("user", request.messages().getLast().role());
            assertTrue(request.messages().getLast().text().contains(answerText), "The complete answer must follow the closed tool transaction");
            return text("verified after explicit approval");
        }, AgentTool.Risk.WORLD_CHANGE, 0, 8, 10, 30000, 200000)) {
            var task = h.submit(a, "alpha");
            h.await(a, task.id(), AgentTask.State.WAITING_ANSWER_BLOCKING);
            assertEquals(0, h.executions.get());
            until(() -> !h.scheduler.isBusy(a.owner()));
            var other = h.submit(b, "beta");
            h.await(b, other.id(), AgentTask.State.COMPLETED);
            var question = new QuestionStore(h.workspace(a)).list(a, task.id()).getFirst();
            assertThrows(SecurityException.class, () -> h.runtime.answer(b, question.id(), question.digest(), "石头", UUID.randomUUID()));
            h.runtime.control(a, task.id(), "pause");
            assertThrows(IllegalStateException.class, () -> h.runtime.control(a, task.id(), "resume"));
            h.runtime.answer(a, question.id(), question.digest(), answerText, UUID.randomUUID());
            assertEquals(AgentTask.State.PAUSED, h.workspace(a).conversation().task(task.id()).state());
            h.runtime.control(a, task.id(), "resume");
            h.await(a, task.id(), AgentTask.State.WAITING_APPROVAL);
            assertEquals(0, h.executions.get());
            h.runtime.control(a, task.id(), "approve");
            h.await(a, task.id(), AgentTask.State.COMPLETED);
            assertEquals(1, h.executions.get());
            var answerInput = h.workspace(a).conversation().getSnapshot(0).events().stream()
                    .filter(e -> e.kind().equals("question_answer_input")).toList();
            assertEquals(1, answerInput.size());
            assertEquals(task.id(), answerInput.getFirst().taskId());
            assertEquals(question.id().toString(), answerInput.getFirst().data().get("id").getAsString());
            assertPaired(h.workspace(a).conversation());
        }
    }

    @Test void scopedQuestionStopsOnlyItsRealStepAndAnswerStillDoesNotGrantWorldApproval() throws Exception {
        AtomicInteger rounds = new AtomicInteger(), worldChanges = new AtomicInteger();
        UUID operation = UUID.randomUUID(); String fixedContext = firefly520.fireflymc.ai.storage.AiJson.digest("fixed-plan-r3");
        try (var h = new Harness((request, control) -> {
            if (rounds.incrementAndGet() == 1) return new ModelGateway.Reply(new ModelMessage("assistant", "", List.of(
                    new ModelMessage.ToolCall("propose", "scoped_question", "{}"),
                    new ModelMessage.ToolCall("independent", "echo", "{}"),
                    new ModelMessage.ToolCall("dependent", "guarded_change", "{\"operationId\":\"" + operation + "\"}")), null, null), 10, 10);
            return text("verified");
        }, AgentTool.Risk.READ_ONLY, 0, 8, 10, 30000, 200000)) {
            var empty = JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}").getAsJsonObject();
            h.tools.register(new DefinedTool(new AgentTool.Descriptor("scoped_question", "test", empty, AgentTool.Risk.READ_ONLY,
                    "test", 0, AgentTool.ExecutionThread.IO, 1000, false, ""), (ctx, args) -> {
                String step = QuestionCoordinator.toolStep("guarded_change", operation);
                new QuestionStore(ctx.workspace()).createForSteps(ctx.actor(), ctx.taskId(), "采用固定方案？", List.of("采用"), step, List.of(step), fixedContext);
                return AgentTool.Result.ok("fixed step question");
            }));
            var schema = JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"operationId\":{\"type\":\"string\"}},\"required\":[\"operationId\"]}").getAsJsonObject();
            h.tools.register(new DefinedTool(new AgentTool.Descriptor("guarded_change", "test", schema, AgentTool.Risk.WORLD_CHANGE,
                    "test", 0, AgentTool.ExecutionThread.IO, 1000, false, ""), (ctx, args) -> {
                new QuestionStore(ctx.workspace()).requireBeforeStep(ctx.actor(), ctx.taskId(), QuestionCoordinator.toolStep("guarded_change", operation), fixedContext);
                worldChanges.incrementAndGet(); return AgentTool.Result.ok("actual change");
            }));
            var task = h.submit(a, "branch work");
            var waiting = h.await(a, task.id(), AgentTask.State.WAITING_ANSWER_BLOCKING);
            assertEquals(2, waiting.nextCall()); assertEquals(1, h.executions.get()); assertEquals(0, worldChanges.get());
            var question = new QuestionStore(h.workspace(a)).list(a, task.id()).getFirst();
            h.runtime.answer(a, question.id(), question.digest(), "采用", UUID.randomUUID());
            h.await(a, task.id(), AgentTask.State.WAITING_APPROVAL); assertEquals(0, worldChanges.get());
            h.runtime.control(a, task.id(), "approve"); h.await(a, task.id(), AgentTask.State.COMPLETED);
            assertEquals(1, worldChanges.get()); assertEquals(1, h.executions.get()); assertPaired(h.workspace(a).conversation());
        }
    }

    @Test void advisoryQuestionDoesNotStopApprovedWorldWorkAndALateAnswerNeverReplaysIt() throws Exception {
        AtomicInteger rounds = new AtomicInteger();
        try (var h = new Harness((request, control) -> rounds.incrementAndGet() == 1
                ? new ModelGateway.Reply(new ModelMessage("assistant", "", List.of(
                        new ModelMessage.ToolCall("advice", "task_ask", "{\"prompt\":\"完成后要明细吗？\",\"blocking\":false}"),
                        new ModelMessage.ToolCall("approved-world", "echo", "{}")), null, null), 10, 10)
                : text("world verified"), AgentTool.Risk.WORLD_CHANGE, 0, 8, 10, 30000, 200000)) {
            var task = h.submit(a, "build"); h.await(a, task.id(), AgentTask.State.WAITING_APPROVAL);
            var question = new QuestionStore(h.workspace(a)).list(a, task.id()).getFirst();
            assertEquals(QuestionCoordinator.BlockingScope.ADVISORY, question.blockingScope());
            h.runtime.control(a, task.id(), "approve"); h.await(a, task.id(), AgentTask.State.COMPLETED);
            assertEquals(1, h.executions.get());
            h.runtime.answer(a, question.id(), question.digest(), "要明细", UUID.randomUUID());
            assertEquals(AgentTask.State.COMPLETED, h.workspace(a).conversation().task(task.id()).state());
            assertEquals(2, rounds.get()); assertEquals(1, h.executions.get()); assertPaired(h.workspace(a).conversation());
        }
    }

    @Test void answeringImmediatelyDuringToolReturnDoesNotLoseTheAnswerOrLeaveTaskWaiting() throws Exception {
        AtomicInteger rounds = new AtomicInteger();
        try (var h = new Harness((r, c) -> {
            if (rounds.incrementAndGet() == 1) return new ModelGateway.Reply(new ModelMessage("assistant", "", List.of(
                    new ModelMessage.ToolCall("ask-fast", "immediate_question", "{}"),
                    new ModelMessage.ToolCall("after-answer", "echo", "{}")), null, null), 10, 10);
            assertEquals("user", r.messages().getLast().role());
            assertTrue(r.messages().getLast().text().contains("蓝色"));
            assertEquals("after-answer", r.messages().get(r.messages().size() - 2).toolCallId());
            assertEquals("ask-fast", r.messages().get(r.messages().size() - 3).toolCallId());
            return text("continued");
        }, AgentTool.Risk.READ_ONLY, 0, 8, 10, 30000, 200000)) {
            var schema = JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}").getAsJsonObject();
            h.tools.register(new DefinedTool(new AgentTool.Descriptor("immediate_question", "test", schema,
                    AgentTool.Risk.READ_ONLY, "workspace.read", 0, AgentTool.ExecutionThread.IO, 1000, false, ""),
                    (ctx, args) -> {
                        var question = new QuestionStore(ctx.workspace()).create(ctx.actor(), ctx.taskId(), "颜色？", List.of(), true);
                        h.runtime.answer(ctx.actor(), question.id(), question.digest(), "蓝色", UUID.randomUUID());
                        return AgentTool.Result.ok("asked");
                    }));
            var task = h.submit(a, "question race");
            h.await(a, task.id(), AgentTask.State.COMPLETED);
            assertPaired(h.workspace(a).conversation());
            assertEquals(2, rounds.get());
        }
    }

    @Test void answerDuringModelRequestSupersedesItsOldReplyBeforeAnyCallsOrCompletionAreAccepted() throws Exception {
        AtomicInteger rounds = new AtomicInteger();
        CountDownLatch staleRequestStarted = new CountDownLatch(1);
        CompletableFuture<ModelGateway.Reply> staleReply = new CompletableFuture<>();
        String answerText = "只是测试，没有具体需求";
        try (var h = new Harness((request, control) -> {
            int round = rounds.incrementAndGet();
            if (round == 1) return new ModelGateway.Reply(new ModelMessage("assistant", "", List.of(
                    new ModelMessage.ToolCall("ask", "task_ask", "{\"prompt\":\"想做什么？\",\"blocking\":false}")), null, null), 10, 10);
            if (round == 2) {
                staleRequestStarted.countDown();
                return control.await(staleReply, 10000);
            }
            assertEquals("user", request.messages().getLast().role());
            assertTrue(request.messages().getLast().text().contains(answerText));
            return text("收到，仅测试，不执行操作。");
        }, AgentTool.Risk.READ_ONLY, 0, 8, 10, 30000, 200000)) {
            var task = h.submit(a, "111");
            assertTrue(staleRequestStarted.await(5, TimeUnit.SECONDS));
            var question = new QuestionStore(h.workspace(a)).list(a, task.id()).getFirst();
            UUID requestId = UUID.randomUUID();
            h.runtime.answer(a, question.id(), question.digest(), answerText, requestId);
            h.runtime.answer(a, question.id(), question.digest(), answerText, requestId);
            staleReply.complete(new ModelGateway.Reply(new ModelMessage("assistant", "已提问，等待回答", List.of(
                    new ModelMessage.ToolCall("must-not-execute", "echo", "{}")), null, null), 10, 10));
            h.await(a, task.id(), AgentTask.State.COMPLETED);
            var events = h.workspace(a).conversation().getSnapshot(0).events();
            assertEquals(3, rounds.get());
            assertEquals(0, h.executions.get());
            assertEquals(1, events.stream().filter(e -> e.kind().equals("question_answer")).count());
            assertEquals(1, events.stream().filter(e -> e.kind().equals("question_answer_input")).count());
            assertEquals(1, events.stream().filter(e -> e.kind().equals("model_response_superseded")).count());
            assertFalse(events.stream().anyMatch(e -> e.message() != null && e.message().text().contains("已提问，等待回答")));
            assertPaired(h.workspace(a).conversation());
        }
    }

    @Test void oneOwnerSerializesModelsAndCancellingQueuedTaskDoesNotInterruptCurrentTransaction() throws Exception {
        CompletableFuture<ModelGateway.Reply> held = new CompletableFuture<>();
        AtomicInteger modelCalls = new AtomicInteger();
        try (var h = new Harness((request, control) -> {
            if (modelCalls.incrementAndGet() == 1) return control.await(held, 10000);
            return text("final");
        }, AgentTool.Risk.READ_ONLY, 0, 8, 10, 30000, 100000)) {
            var first = h.submit(a, "first");
            var second = h.submit(a, "second");
            until(() -> modelCalls.get() == 1);
            h.runtime.control(a, second.id(), "cancel");
            assertEquals(1, modelCalls.get());
            held.complete(calls("call"));
            h.await(a, first.id(), AgentTask.State.COMPLETED);
            assertEquals(AgentTask.State.CANCELLED, h.workspace(a).conversation().task(second.id()).state());
            assertPaired(h.workspace(a).conversation());
            assertEquals(2, modelCalls.get());
        }
    }

    @Test void approvalIsOwnerBoundArgumentBoundAndRecheckedAfterAuthenticationLoss() throws Exception {
        AtomicInteger model = new AtomicInteger();
        try (var h = new Harness((r, c) -> model.incrementAndGet() == 1 ? calls("admin") : text("reported"),
                AgentTool.Risk.WORLD_CHANGE, 0, 8, 10, 30000, 100000)) {
            var task = h.submit(a, "construction task");
            h.await(a, task.id(), AgentTask.State.WAITING_APPROVAL);
            assertEquals(0, h.executions.get());
            assertThrows(SecurityException.class, () -> h.runtime.control(b, task.id(), "approve"));
            assertEquals(AgentTask.State.WAITING_APPROVAL, h.workspace(a).conversation().task(task.id()).state());
            h.actors.put(a.owner(), Actor.player(UUID.fromString(a.owner()), false, true, 4));
            assertThrows(SecurityException.class, () -> h.runtime.control(h.actor(a.owner()), task.id(), "approve"));
            h.actors.put(a.owner(), a);
            h.runtime.control(a, task.id(), "approve");
            h.await(a, task.id(), AgentTask.State.COMPLETED);
            assertEquals(1, h.executions.get());
            assertPaired(h.workspace(a).conversation());
        }
    }

    @Test void exhaustedBudgetBackfillsEveryCallAndRequestsToolFreeFinalSummary() throws Exception {
        AtomicInteger model = new AtomicInteger();
        try (var h = new Harness((request, control) -> {
            if (model.incrementAndGet() == 1) return calls("a", "b", "c");
            assertTrue(request.tools().isEmpty());
            assertEquals(3, request.messages().stream().filter(m -> m.role().equals("tool")).count());
            return text("partial completion");
        }, AgentTool.Risk.READ_ONLY, 0, 1, 1, 30000, 100000)) {
            var task = h.submit(a, "limited");
            h.await(a, task.id(), AgentTask.State.FAILED);
            assertEquals(1, h.executions.get());
            assertEquals(2, model.get());
            assertPaired(h.workspace(a).conversation());
            // The durable terminal checkpoint precedes Environment.changed; notification is asynchronous to this test.
            until(() -> h.notices.stream().anyMatch(n -> n.contains("BUDGET_EXHAUSTED")));
            assertEquals(1, h.workspace(a).conversation().getSnapshot(0).events().stream()
                    .filter(e -> e.message() != null && e.message().text().equals("partial completion")).count());
        }
    }

    @Test void cancellationInterruptsModelWithoutErasingHistoryAndNextTaskStillWorks() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<ModelGateway.Reply> never = new CompletableFuture<>();
        try (var h = new Harness((request, control) -> {
            if (calls.incrementAndGet() == 1) return control.await(never, 10000);
            return text("next works");
        }, AgentTool.Risk.READ_ONLY, 0, 5, 5, 30000, 100000)) {
            var task = h.submit(a, "cancel-me");
            until(() -> calls.get() == 1);
            h.runtime.control(a, task.id(), "cancel");
            h.await(a, task.id(), AgentTask.State.CANCELLED);
            assertTrue(never.isCancelled());
            var next = h.submit(a, "next");
            h.await(a, next.id(), AgentTask.State.COMPLETED);
            assertFalse(h.workspace(a).conversation().search("cancel-me", 10).isEmpty());
        }
    }

    @Test void durationAndTokenLimitsProduceExplicitPersistedFinals() throws Exception {
        try (var h = new Harness((r, c) -> c.await(new CompletableFuture<>(), 10000),
                AgentTool.Risk.READ_ONLY, 0, 5, 5, 100, 100000)) {
            var task = h.submit(a, "time limit");
            h.await(a, task.id(), AgentTask.State.FAILED);
            assertTrue(h.notices.stream().anyMatch(n -> n.contains("TIMEOUT")));
        }
        // Same world reopens with preserved history; no credential or game environment involved.
        try (var h = new Harness((r, c) -> { fail("Token budget should prevent provider call"); return null; },
                AgentTool.Risk.READ_ONLY, 0, 5, 5, 30000, 1024)) {
            var task = h.submit(b, "tokens");
            h.await(b, task.id(), AgentTask.State.FAILED);
            until(() -> h.notices.stream().anyMatch(n -> n.contains("TOKEN_BUDGET_EXHAUSTED")));
        }
    }

    @Test void offlinePlayerActionPausesAndRevalidatesOnReconnect() throws Exception {
        AtomicInteger model = new AtomicInteger();
        try (var h = new Harness((r, c) -> model.incrementAndGet() == 1 ? calls("write") : text("finished"),
                AgentTool.Risk.PLAYER_STATE, 0, 8, 10, 30000, 100000)) {
            h.actors.put(a.owner(), new Actor(a.owner(), true, false, 4));
            var task = h.submit(a, "offline");
            h.await(a, task.id(), AgentTask.State.PAUSED);
            assertEquals(0, h.executions.get());
            h.actors.put(a.owner(), a);
            h.runtime.resumeTasks(a);
            h.await(a, task.id(), AgentTask.State.WAITING_APPROVAL);
            h.runtime.control(a, task.id(), "approve");
            h.await(a, task.id(), AgentTask.State.COMPLETED);
            assertEquals(1, h.executions.get());
        }
    }

    @Test void restartDoesNotReplayUncertainSideEffectAndRevokesPendingApproval() throws Exception {
        UUID id, conversation;
        try (var manager = new WorkspaceManager(directory)) {
            var workspace = manager.getWorkspace(a);
            conversation = workspace.primaryConversationId();
            var call = new ModelMessage.ToolCall("uncertain", "echo", "{}");
            var task = AgentTask.create(a.owner(), UUID.randomUUID(), "already-dispatched")
                    .progress(1, 0, 10, 10, List.of(call), 0, "old-approval", true, true)
                    .update(AgentTask.State.EXECUTING, "");
            id = task.id();
            JsonObject started = new JsonObject();
            started.addProperty("risk", "WORLD_CHANGE");
            workspace.conversation().append(List.of(Draft.message(id,
                    new ModelMessage("assistant", "", List.of(call), null, null), "tx"),
                    Draft.checkpoint(task), new Draft(id, "tool_start", null, started, call.id())));
        }
        try (var h = new Harness((r, c) -> { fail("Must not replay uncertain execution"); return null; },
                AgentTool.Risk.WORLD_CHANGE, 0, 5, 5, 30000, 100000)) {
            h.runtime.recover();
            assertEquals(conversation, h.workspace(a).primaryConversationId());
            assertEquals(AgentTask.State.RECOVERY_REQUIRED, h.workspace(a).conversation().task(id).state());
            assertEquals(0, h.executions.get());
            assertThrows(IllegalStateException.class, () -> h.runtime.control(a, id, "resume"));
            assertPaired(h.workspace(a).conversation());
        }
        // A second process start sees the recovery checkpoint after pending calls have been closed.
        // Neither ordinary cancellation nor another restart can make that uncertainty resumable.
        for (int restart = 0; restart < 2; restart++) {
            try (var h = new Harness((r, c) -> { fail("Recovery barrier must survive"); return null; },
                    AgentTool.Risk.WORLD_CHANGE, 0, 5, 5, 30000, 100000)) {
                h.runtime.prepareWorkspace(a);
                assertEquals(AgentTask.State.RECOVERY_REQUIRED, h.workspace(a).conversation().task(id).state());
                assertEquals(AgentTask.State.RECOVERY_REQUIRED, h.runtime.control(a, id, "cancel").state());
                assertThrows(IllegalStateException.class, () -> h.runtime.control(a, id, "resume"));
                assertEquals(conversation, h.workspace(a).primaryConversationId());
                assertEquals(0, h.executions.get());
                assertPaired(h.workspace(a).conversation());
            }
        }
    }

    @Test void authenticatedOwnerRecoveryDoesNotLoadAnotherPlayersDamagedWorkspace() throws Exception {
        Path damaged;
        try (var manager = new WorkspaceManager(directory)) {
            damaged = manager.getWorkspace(b).directory().resolve("workspace.json");
            manager.getWorkspace(a);
        }
        java.nio.file.Files.writeString(damaged, "{\"version\":1,\"owner\":\"broken\"}");
        try (var h = new Harness((r, c) -> text("own workspace still works"),
                AgentTool.Risk.READ_ONLY, 0, 5, 5, 30000, 100000)) {
            var task = h.submit(a, "independent recovery");
            h.await(a, task.id(), AgentTask.State.COMPLETED);
            assertThrows(java.io.IOException.class, () -> h.runtime.prepareWorkspace(b));
            assertTrue(h.workspace(a).conversation().search("own workspace still works", 10).size() > 0);
        }
    }

    @Test void offlineDispatchRejectionBackfillsOnceAndPausesUntilReconnect() throws Exception {
        AtomicInteger rounds = new AtomicInteger();
        try (var h = new Harness((r, c) -> {
            if (rounds.incrementAndGet() == 1) return calls("offline-race");
            assertEquals(1, r.messages().stream().filter(m -> "offline-race".equals(m.toolCallId())).count());
            return text("reported unavailable operation");
        }, AgentTool.Risk.READ_ONLY, 0, 5, 5, 30000, 100000)) {
            var observations = new JsonObject();
            observations.addProperty("dispatch", "REJECTED_BEFORE_EXECUTION");
            h.heldTool = CompletableFuture.completedFuture(new AgentTool.Result(false, "PLAYER_OFFLINE",
                    "player disconnected before main-thread dispatch", observations, "", "", false));
            var task = h.submit(a, "disconnect race");
            h.await(a, task.id(), AgentTask.State.PAUSED);
            assertEquals(1, rounds.get());
            assertEquals(0, h.executions.get());
            assertPaired(h.workspace(a).conversation());
            h.heldTool = null;
            h.runtime.resumeTasks(a);
            h.await(a, task.id(), AgentTask.State.COMPLETED);
            assertEquals(2, rounds.get());
            assertEquals(0, h.executions.get());
        }
    }

    @Test void invalidArgumentsAndDisabledToolsHaveResultsButNoExecution() throws Exception {
        AtomicInteger model = new AtomicInteger();
        try (var h = new Harness((request, control) -> {
            if (model.incrementAndGet() == 1) return new ModelGateway.Reply(
                    new ModelMessage("assistant", "", List.of(new ModelMessage.ToolCall("bad", "echo", "{")),
                            null, null), 1, 1);
            return text("reported failure");
        }, AgentTool.Risk.READ_ONLY, 0, 5, 5, 30000, 100000)) {
            var task = h.submit(a, "invalid");
            h.await(a, task.id(), AgentTask.State.COMPLETED);
            assertEquals(0, h.executions.get());
            assertTrue(h.workspace(a).conversation().search("INVALID_ARGUMENT", 10).size() > 0);
            assertPaired(h.workspace(a).conversation());
        }
    }

    @Test void requestIdDeduplicatesInputAndSecretsNeverEnterModelOrDurableHistory() throws Exception {
        try (var h = new Harness((request, control) -> {
            assertFalse(request.toString().contains("SECRET_NOT_TO_LEAK"));
            return text("SECRET_NOT_TO_LEAK");
        }, AgentTool.Risk.READ_ONLY, 0, 5, 5, 30000, 100000)) {
            UUID requestId = UUID.randomUUID();
            var first = h.runtime.submit(a, requestId, "secret SECRET_NOT_TO_LEAK");
            var duplicate = h.runtime.submit(a, requestId, "secret SECRET_NOT_TO_LEAK");
            assertEquals(first.id(), duplicate.id());
            h.await(a, first.id(), AgentTask.State.COMPLETED);
            assertEquals(1, h.workspace(a).conversation().tasks().size());
            assertTrue(h.workspace(a).conversation().search("SECRET_NOT_TO_LEAK", 10).isEmpty());
        }
    }

    @Test void toolTimeoutBackfillsTransactionAndNeverAcceptsLateCompletion() throws Exception {
        try (var h = new Harness((r, c) -> calls("slow"), AgentTool.Risk.READ_ONLY, 0, 5, 5, 30000, 100000)) {
            h.heldTool = new CompletableFuture<>();
            var task = h.submit(a, "slow tool");
            h.await(a, task.id(), AgentTask.State.FAILED);
            assertTrue(h.heldTool.isCancelled());
            assertThrows(TaskControl.Stopped.class, h.lastToolControl::check);
            assertFalse(h.heldTool.complete(AgentTool.Result.ok("late success")));
            assertPaired(h.workspace(a).conversation());
            assertTrue(h.notices.stream().anyMatch(n -> n.contains("TOOL_TIMEOUT")));
            assertTrue(h.workspace(a).conversation().search("late success", 10).isEmpty());
        }
    }

    @Test void disabledToolAndProtocolFailureAlwaysLeaveAnExplicitFinal() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var h = new Harness((r, c) -> {
            if (calls.incrementAndGet() == 1) return calls("disabled");
            throw new ModelGateway.Failure("PROTOCOL_ERROR");
        }, AgentTool.Risk.READ_ONLY, 0, 5, 5, 30000, 100000)) {
            h.enabled = false;
            var task = h.submit(a, "disabled tool");
            h.await(a, task.id(), AgentTask.State.FAILED);
            assertEquals(0, h.executions.get());
            assertFalse(h.workspace(a).conversation().search("TOOL_DISABLED", 10).isEmpty());
            assertTrue(h.notices.stream().anyMatch(n -> n.contains("PROTOCOL_ERROR")));
            assertPaired(h.workspace(a).conversation());
        }
    }

    @Test void compactionModelRequestsAreDurablyChargedWithoutInvalidatingTheSnapshot() throws Exception {
        AtomicInteger summaries = new AtomicInteger();
        try (var h = new Harness((r, c) -> {
            if (r.messages().getFirst().text().contains("只输出 JSON 摘要")) {
                summaries.incrementAndGet();
                return text("{}"); // Invalid summary falls back, but it still consumes the task's budget.
            }
            return text("final");
        }, AgentTool.Risk.READ_ONLY, 0, 5, 5, 30000, 100000)) {
            var workspace = h.workspace(a);
            for (int i = 0; i < 8; i++) {
                var old = AgentTask.create(a.owner(), UUID.randomUUID(), "old-" + i);
                workspace.conversation().append(List.of(Draft.message(old.id(),
                        ModelMessage.text("user", "x".repeat(6000)), null),
                        Draft.checkpoint(old.update(AgentTask.State.COMPLETED, ""))));
            }
            var task = h.submit(a, "compact");
            var done = h.await(a, task.id(), AgentTask.State.COMPLETED);
            assertEquals(1, summaries.get());
            assertTrue(done.tokens() >= 40);
            assertNotNull(ContextCompactor.readLatest(workspace));
        }
    }

    @Test void pagedUnicodeArtifactCanBeReadToTheEndWithoutProducingArtifactOfArtifactReferences() throws Exception {
        AtomicReference<UUID> artifact = new AtomicReference<>();
        StringBuilder recovered = new StringBuilder();
        Set<String> readCalls = new HashSet<>();
        AtomicInteger round = new AtomicInteger();
        AtomicInteger offset = new AtomicInteger(), total = new AtomicInteger(-1);
        try (var h = new Harness((request, control) -> {
            if (request.messages().getFirst().text().contains("只输出 JSON 摘要")) return text("{}");
            var last = request.messages().stream().filter(m -> "tool".equals(m.role())).reduce((a, b) -> b);
            if (last.isPresent() && readCalls.add(last.get().toolCallId())) {
                JsonObject result = JsonParser.parseString(last.get().text()).getAsJsonObject();
                assertFalse(result.has("artifactId"), "A page must not be replaced by another artifact reference");
                var page = result.getAsJsonObject("observations");
                assertNotNull(page, result.toString());
                recovered.append(page.get("text").getAsString());
                assertTrue(page.get("nextOffset").getAsInt() > offset.get());
                offset.set(page.get("nextOffset").getAsInt());
                total.set(page.get("totalCharacters").getAsInt());
            }
            if (offset.get() == total.get()) return text("complete artifact recovered");
            JsonObject args = new JsonObject();
            args.addProperty("artifactId", artifact.get().toString()); args.addProperty("offset", offset.get());
            return new ModelGateway.Reply(new ModelMessage("assistant", "", List.of(new ModelMessage.ToolCall(
                    "page-" + round.incrementAndGet(), "artifact_read", args.toString())), null, null), 10, 10);
        }, AgentTool.Risk.READ_ONLY, 0, 64, 64, 60000, 2000000)) {
            var workspace = h.workspace(a);
            artifact.set(workspace.saveArtifact(Map.of("body", "中文😀\\\"".repeat(1600))));
            String original = java.nio.file.Files.readString(workspace.artifact(artifact.get()));
            JsonObject committed = new JsonObject();
            committed.addProperty("artifactId", artifact.get().toString()); committed.addProperty("fullResult", original);
            workspace.conversation().append(List.of(new Draft(null, "tool_result", null, committed, "archived-result")));
            var task = h.submit(a, "read the entire private artifact");
            h.await(a, task.id(), AgentTask.State.COMPLETED);
            assertEquals(original, recovered.toString());
            try (var files = java.nio.file.Files.list(workspace.directory().resolve("artifacts"))) { assertEquals(1, files.count()); }
            assertPaired(workspace.conversation());
        }
    }

    @Test void oversizedProposedTransactionIsArchivedAndRejectedBeforeAnyCallIsAcceptedOrExecuted() throws Exception {
        try (var h = new Harness((request, control) -> calls(java.util.stream.IntStream.range(0, 64)
                .mapToObj(i -> "proposed-" + i).toArray(String[]::new)),
                AgentTool.Risk.READ_ONLY, 0, 10, 128, 30000, 200000)) {
            var task = h.submit(a, "bounded transaction");
            var done = h.await(a, task.id(), AgentTask.State.FAILED);
            assertEquals("TOOL_TRANSACTION_EXCEEDS_CONTEXT", done.reason());
            assertEquals(0, h.executions.get());
            assertEquals(0, done.toolCalls());
            var rejected = h.workspace(a).conversation().getSnapshot(0).events().stream()
                    .filter(e -> "model_response_rejected".equals(e.kind())).findFirst().orElseThrow();
            String original = java.nio.file.Files.readString(h.workspace(a).artifact(UUID.fromString(rejected.data().get("artifactId").getAsString())));
            assertEquals(64, JsonParser.parseString(original).getAsJsonObject().getAsJsonArray("calls").size());
            assertPaired(h.workspace(a).conversation());
        }
    }

    private static void assertPaired(ConversationStore store) {
        Set<String> calls = new HashSet<>();
        for (var event : store.getSnapshot(0).events()) {
            if (event.message() == null) continue;
            var message = event.message();
            if (!message.role().equals("tool")) assertTrue(calls.isEmpty(), "Tool transaction was interleaved");
            for (var call : message.calls()) assertTrue(calls.add(call.id()));
            if (message.toolCallId() != null) assertTrue(calls.remove(message.toolCallId()), "Orphan result");
        }
        assertTrue(calls.isEmpty());
    }
}
