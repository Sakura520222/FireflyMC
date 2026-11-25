package firefly520.fireflymc.ai.agent;

import com.google.gson.*;
import firefly520.fireflymc.ai.context.ContextCompactor;
import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.model.*;
import firefly520.fireflymc.ai.security.*;
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

/** Exercises the real registry/runtime/persistent readers with an intentionally premature model answer. */
class AgentAnswerDeliveryTest {
    @TempDir Path directory;
    private final Actor actor = Actor.player(UUID.randomUUID(), true, true, 0);
    private static final JsonObject EMPTY = JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}").getAsJsonObject();
    private static ModelGateway.Reply text(String value) { return new ModelGateway.Reply(ModelMessage.text("assistant", value), 10, 10); }
    private static ModelGateway.Reply call(int id, String name, JsonObject arguments) {
        return new ModelGateway.Reply(new ModelMessage("assistant", "", List.of(
                new ModelMessage.ToolCall("call-" + id, name, arguments.toString())), null, null), 10, 10);
    }
    private static JsonObject discover(String name) {
        var result = new JsonObject(); var names = new JsonArray(); names.add(name); result.add("names", names); return result;
    }
    private static JsonObject lastResult(ModelGateway.Request request) {
        return request.messages().stream().filter(message -> message.role().equals("tool")).reduce((a, b) -> b)
                .map(message -> JsonParser.parseString(message.text()).getAsJsonObject()).orElse(new JsonObject());
    }
    private final class Harness implements AutoCloseable, AgentRuntime.Environment {
        final WorkspaceManager manager = new WorkspaceManager(directory);
        final ToolRegistry registry = new ToolRegistry();
        final TaskScheduler scheduler = new TaskScheduler(2, 8, 32);
        final AgentRuntime runtime;
        final AtomicInteger executions = new AtomicInteger();
        Harness(ModelGateway gateway, String name, AgentTool.Result result) throws Exception {
            WorkspaceTools.register(registry);
            for (int i = 0; i < 80; i++) registry.register(new DefinedTool(new AgentTool.Descriptor(
                    "unrelated_" + i, "other capability " + "x".repeat(220), EMPTY, AgentTool.Risk.READ_ONLY,
                    "workspace.read", 0, AgentTool.ExecutionThread.IO, 1000, false, ""), (ctx, args) -> AgentTool.Result.ok("unused")));
            registry.register(new DefinedTool(new AgentTool.Descriptor(name, "query actual owned test evidence", EMPTY,
                    AgentTool.Risk.READ_ONLY, "workspace.read", 0, AgentTool.ExecutionThread.IO, 1000, false, ""),
                    (ctx, args) -> { executions.incrementAndGet(); return result; }));
            registry.registerDiscovery(ignored -> true);
            runtime = new AgentRuntime(manager, scheduler, gateway, registry,
                    new ContextCompactor(new ContextCompactor.Settings(32000, 512, 1024, 512, .8, 2), "test"),
                    new AgentRuntime.Limits(64, 64, 2000000, 60000, 512, true, false), this, new SecretFilter(List.of()));
        }
        @Override public Actor actor(String owner) { return actor; }
        @Override public boolean enabled(String tool) { return true; }
        @Override public CompletableFuture<AgentTool.Result> execute(AgentTool tool, AgentTool.Context context,
                JsonObject args, String approval, ModelMessage.ToolCall call) {
            try { return CompletableFuture.completedFuture(tool.execute(context, args)); }
            catch (Exception failed) { return CompletableFuture.failedFuture(failed); }
        }
        @Override public void changed(WorkspaceManager.Workspace workspace, AgentTask task, String notice) { }
        AgentTask execute(String prompt) throws Exception {
            var task = runtime.submit(actor, UUID.randomUUID(), prompt); var store = manager.getWorkspace(actor).conversation();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (!store.task(task.id()).state().terminal() && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(store.task(task.id()).state().terminal(), store.task(task.id()).toString());
            return store.task(task.id());
        }
        ConversationStore.Snapshot snapshot() throws Exception { return manager.getWorkspace(actor).conversation().getSnapshot(0); }
        @Override public void close() throws Exception { runtime.close(); assertTrue(runtime.awaitStopped(10000)); manager.close(); }
    }

    @Test void oneUserMessageDiscoversQueriesReadsAndDeliversConcreteItemsDespitePrematurePleaseContinue() throws Exception {
        var result = craftables(); var rounds = new AtomicInteger(); var artifact = new AtomicReference<String>();
        var offset = new AtomicInteger(); var collected = new StringBuilder(); var seen = new HashSet<String>();
        try (var harness = new Harness((request, control) -> {
            if (request.messages().getFirst().text().contains("只输出 JSON 摘要")) return text("{}");
            int round = rounds.getAndIncrement();
            assertTrue(request.tools().stream().anyMatch(tool -> tool.name().equals("artifact_read")), "readers cannot disappear in a large tool catalog");
            if (round == 0) return call(round, "tools_discover", discover("crafting_discover"));
            if (round == 1) {
                assertTrue(request.tools().stream().anyMatch(tool -> tool.name().equals("crafting_discover")));
                return call(round, "crafting_discover", new JsonObject());
            }
            var last = lastResult(request);
            if (round == 2) {
                assertTrue(last.get("readRequired").getAsBoolean());
                assertFalse(last.getAsJsonObject("observations").getAsJsonArray("craftable").isEmpty(), "facts, not just an artifact address");
                assertEquals("2026-10-28T08:00:00Z", last.getAsJsonObject("observations").get("capturedAtIso").getAsString());
                artifact.set(last.get("artifactId").getAsString());
                return text("清单已经存起来了，请回复继续。");
            }
            var message = request.messages().stream().filter(value -> value.role().equals("tool")).reduce((a, b) -> b).orElseThrow();
            var page = last.has("observations") ? last.getAsJsonObject("observations") : new JsonObject();
            if (page.has("text") && seen.add(message.toolCallId())) {
                collected.append(page.get("text").getAsString()); offset.set(page.get("nextOffset").getAsInt());
                if (offset.get() == page.get("totalCharacters").getAsInt()) {
                    var full = JsonParser.parseString(collected.toString()).getAsJsonObject();
                    assertEquals(12, full.getAsJsonObject("observations").getAsJsonArray("craftable").size());
                    return text("当前可制作：木棍×4、工作台×1。每项是独立备选，原料和余料已核验，尚未扣料。");
                }
            }
            var args = new JsonObject(); args.addProperty("artifactId", artifact.get()); args.addProperty("offset", offset.get());
            return call(round, "artifact_read", args);
        }, "crafting_discover", result)) {
            var done = harness.execute("看一下我当前的材料都能做什么");
            assertEquals(AgentTask.State.COMPLETED, done.state()); assertEquals(1, harness.executions.get());
            var snapshot = harness.snapshot(); assertEquals(1, snapshot.tasks().size());
            assertEquals(1, snapshot.events().stream().filter(event -> event.message() != null && event.message().role().equals("user")).count());
            assertTrue(snapshot.events().stream().anyMatch(event -> event.kind().equals("answer_delivery_retry")));
            var answers = snapshot.events().stream().filter(event -> event.message() != null && event.message().role().equals("assistant"))
                    .map(event -> event.message().text()).toList();
            assertFalse(answers.stream().anyMatch(answer -> answer.contains("请回复继续")));
            assertTrue(answers.stream().anyMatch(answer -> answer.contains("木棍×4") && answer.contains("工作台×1")));
            assertEquals(AiJson.GSON.toJson(result), collected.toString(), "complete result consumption is lossless");
        }
    }

    @Test void modelThatKeepsDeferringDeliveryCannotTurnSuccessfulQueryIntoCompletedTask() throws Exception {
        var rounds = new AtomicInteger();
        try (var harness = new Harness((request, control) -> {
            int round = rounds.getAndIncrement();
            if (round == 0) return call(round, "tools_discover", discover("query_data"));
            if (round == 1) return call(round, "query_data", new JsonObject());
            return text("任务已完成，结果存在服务器，下条消息再给你。");
        }, "query_data", AgentTool.Result.ok("actual-query-result:" + "x".repeat(12000)))) {
            var done = harness.execute("直接给我结果");
            assertEquals(AgentTask.State.FAILED, done.state()); assertEquals("RESULT_NOT_DELIVERED", done.reason());
            assertEquals(5, rounds.get()); assertEquals(1, harness.executions.get());
            assertTrue(harness.snapshot().events().stream().anyMatch(event -> event.kind().equals("tool_result")
                    && event.data().get("fullResult").getAsString().contains("actual-query-result:")));
            assertFalse(harness.snapshot().events().stream().anyMatch(event -> event.message() != null
                    && event.message().text().contains("下条消息再给你")));
        }
    }

    @Test void listingCapabilitiesExplicitlyDoesNotRequireAnUnrequestedGameplayAction() throws Exception {
        var rounds = new AtomicInteger();
        try (var harness = new Harness((request, control) -> {
            if (rounds.getAndIncrement() == 0) {
                var args = discover("query_data"); args.addProperty("intent", "list"); return call(0, "tools_discover", args);
            }
            return text("可以查询本人数据；本次只介绍能力。");
        }, "query_data", AgentTool.Result.ok("unused"))) {
            assertEquals(AgentTask.State.COMPLETED, harness.execute("介绍一下查询能力").state());
            assertEquals(0, harness.executions.get());
        }
    }

    @Test void outputLengthFailureRetriesOneConciseAnswerWithinTheSameDeadlineWithoutReplayingTheQuery() throws Exception {
        var rounds = new AtomicInteger(); var deadline = new AtomicReference<TaskControl>();
        try (var harness = new Harness((request, control) -> {
            assertEquals(512, request.maxOutputTokens(), "do not silently raise configured output costs");
            if (deadline.get() == null) deadline.set(control); else assertSame(deadline.get(), control);
            int round = rounds.getAndIncrement();
            if (round == 0) return call(round, "tools_discover", discover("query_data"));
            if (round == 1) return call(round, "query_data", new JsonObject());
            if (round == 2) throw new ModelGateway.Failure("MODEL_INCOMPLETE", Map.of("reason", "max_output_tokens"));
            assertTrue(request.messages().getLast().text().contains("最多12行"));
            return text("已核验的具体查询结果：完成两个步骤，尚余一个步骤。");
        }, "query_data", AgentTool.Result.ok("完成两个步骤，尚余一个步骤"))) {
            assertEquals(AgentTask.State.COMPLETED, harness.execute("当前代办进度").state());
            assertEquals(1, harness.executions.get()); assertEquals(4, rounds.get());
            assertEquals(1, harness.snapshot().events().stream().filter(event -> event.kind().equals("model_output_retry")).count());
        }
    }

    @Test void repeatedOutputFailureStopsAfterOneRetry() throws Exception {
        var calls = new AtomicInteger();
        try (var harness = new Harness((request, control) -> {
            calls.incrementAndGet(); throw new ModelGateway.Failure("MODEL_INCOMPLETE", Map.of("reason", "max_output_tokens"));
        }, "query_data", AgentTool.Result.ok("unused"))) {
            assertEquals(AgentTask.State.FAILED, harness.execute("给出简短结果").state());
            assertEquals(2, calls.get()); assertEquals(0, harness.executions.get());
        }
    }

    @Test void aFilteredResponseDoesNotTriggerTheOutputLengthRetry() throws Exception {
        var requests = new AtomicInteger();
        try (var harness = new Harness((request, control) -> {
            requests.incrementAndGet(); throw new ModelGateway.Failure("MODEL_INCOMPLETE", Map.of("reason", "content_filter"));
        }, "query_data", AgentTool.Result.ok("unused"))) {
            assertEquals(AgentTask.State.FAILED, harness.execute("仅记录过滤响应").state());
            assertEquals(1, requests.get()); assertEquals(0, harness.executions.get());
        }
    }

    @Test void nativeCatalogIsImmediatelyAvailableAndItsCompleteReportIsDeliveredWithoutArtifactReadRounds() throws Exception {
        var data = craftables().observations().deepCopy(); data.addProperty("complete", true); data.addProperty("craftableCount", 120);
        String report = "FIRST: 橡木木板×4\n" + "完整清单内容\n".repeat(5000) + "LAST: 白桦木板×4";
        data.addProperty("displayReport", report);
        var result = new AgentTool.Result(true, "", "清单已生成", data, "native-recipe-book", "", false);
        var rounds = new AtomicInteger();
        try (var harness = new Harness((request, control) -> {
            int round = rounds.getAndIncrement();
            if (round == 0) {
                assertTrue(request.tools().stream().anyMatch(tool -> tool.name().equals("crafting_discover")), "native query is available on the first round");
                return call(round, "crafting_discover", new JsonObject());
            }
            var projected = lastResult(request); assertFalse(projected.get("readRequired").getAsBoolean());
            assertTrue(projected.getAsJsonObject("observations").get("reportDelivered").getAsBoolean());
            return text("可以直接制作这些物品。");
        }, "crafting_discover", result)) {
            assertEquals(AgentTask.State.COMPLETED, harness.execute("我现在能直接做什么").state());
            assertEquals(1, harness.executions.get()); assertEquals(2, rounds.get());
            var reports = harness.snapshot().events().stream().filter(event -> event.kind().equals("task_notice")
                    && event.data().has("reportType") && event.data().get("reportType").getAsString().equals("craftable_catalog")).toList();
            assertEquals(1, reports.size()); assertEquals(report, reports.getFirst().data().get("text").getAsString());
            assertTrue(reports.getFirst().data().get("essential").getAsBoolean());
            assertTrue(harness.snapshot().events().stream().filter(event -> event.kind().equals("tool_result"))
                    .allMatch(event -> event.data().get("name").getAsString().equals("crafting_discover")));
        }
    }

    private static AgentTool.Result craftables() {
        var data = new JsonObject(); var rows = new JsonArray();
        for (int i = 0; i < 12; i++) {
            var row = new JsonObject(); row.addProperty("recipeId", "minecraft:fixture_" + i);
            row.addProperty("displayName", i == 0 ? "木棍" : i == 1 ? "工作台" : "测试配方" + i);
            row.addProperty("outputId", i == 0 ? "minecraft:stick" : "minecraft:crafting_table");
            row.addProperty("productSemantics", "DETERMINISTIC_PRODUCTS_AND_REMAINDERS");
            var inputs = new JsonArray();
            for (int j = 0; j < 8; j++) {
                var input = new JsonObject(); input.addProperty("sourceKey", "owned-test-slot-" + j);
                var item = new JsonObject(); item.addProperty("id", "minecraft:oak_planks"); item.addProperty("count", 1);
                input.add("item", item); inputs.add(input);
            }
            row.add("inputs", inputs); var products = new JsonArray(); var product = new JsonObject();
            product.addProperty("id", row.get("outputId").getAsString()); product.addProperty("count", i == 0 ? 4 : 1);
            products.add(product); row.add("productsIncludingRemainders", products); row.add("probabilityOutputs", new JsonArray()); rows.add(row);
        }
        data.add("craftable", rows); data.addProperty("hasMore", false); data.addProperty("nextOffset", 512);
        data.addProperty("snapshotId", UUID.randomUUID().toString()); data.addProperty("recipeGeneration", 1);
        data.addProperty("capturedAt", 1000); data.addProperty("independentAlternatives", true);
        data.addProperty("capturedAtIso", "2026-10-28T08:00:00Z");
        return new AgentTool.Result(true, "", "真实配方查询的合约测试夹具。", data, "test-fixture", "", false);
    }
}
