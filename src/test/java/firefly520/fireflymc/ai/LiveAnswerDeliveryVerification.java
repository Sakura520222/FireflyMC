package firefly520.fireflymc.ai;

import com.google.gson.*;
import firefly520.fireflymc.ai.agent.AgentRuntime;
import firefly520.fireflymc.ai.context.ContextCompactor;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.model.*;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in real-provider test of the production AgentRuntime, using synthetic inventory evidence only. */
public final class LiveAnswerDeliveryVerification {
    private static final String MODEL = "glm-5.3-flash";
    private static final AtomicInteger BATCH_CALLS = new AtomicInteger();
    private static JsonObject ledger, credential;
    private static Path report;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Explicit isolated credential, report and refreshed pricing paths required");
        credential = AiJson.object(Files.readString(Path.of(args[0])), 65536, 16); report = Path.of(args[1]);
        if (credential.get("budgetUsd").getAsDouble() > 20 || credential.get("requestLimit").getAsInt() > 32)
            throw new IllegalStateException("LIVE_BUDGET_EXCEEDS_AUTHORIZATION");
        var prices = AiJson.object(Files.readString(Path.of(args[2])), 8 * 1024 * 1024, 32);
        var price = prices.getAsJsonArray("data").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(row -> MODEL.equals(row.get("model_name").getAsString())).findFirst().orElseThrow();
        double input = price.get("model_ratio").getAsDouble() * 2, output = input * price.get("completion_ratio").getAsDouble();
        if (price.get("quota_type").getAsInt() != 0 || input > 2 || output > 8) throw new IllegalStateException("LIVE_PRICING_REVIEW_REQUIRED");
        ledger = AiJson.object(Files.readString(report), 1024 * 1024, 32);
        if (!ledger.has("answerDeliveryRuns")) ledger.add("answerDeliveryRuns", new JsonArray());
        boolean passed = true;
        for (var scenario : List.of("chat_fresh_query", "chat_saved_report", "responses_saved_report")) {
            System.out.println("AI4_ANSWER_DELIVERY starting " + scenario);
            JsonObject run = run(scenario); ledger.getAsJsonArray("answerDeliveryRuns").add(run); AiJson.write(report, ledger);
            System.out.println("AI4_ANSWER_DELIVERY " + scenario + " " + run.get("status").getAsString());
            passed &= run.get("status").getAsString().equals("PASSED");
        }
        if (!passed) throw new IllegalStateException("Live answer delivery incomplete; inspect redacted synthetic-data report");
    }

    private static JsonObject run(String scenario) throws Exception {
        long started = System.nanoTime(); var run = new JsonObject(); run.addProperty("scenario", scenario);
        run.addProperty("scope", "Real provider + production AgentRuntime/ToolRegistry/ConversationStore/readers; synthetic inventory, no Minecraft or real player data.");
        boolean stored = scenario.endsWith("saved_report"); var queryCalls = new AtomicInteger();
        Path root = Files.createTempDirectory(report.toAbsolutePath().getParent(), "answer-delivery-");
        var actor = Actor.player(UUID.randomUUID(), true, true, 0);
        try (var manager = new WorkspaceManager(root)) {
            var workspace = manager.getWorkspace(actor); var registry = new ToolRegistry(); WorkspaceTools.register(registry);
            var result = inventoryResult(!stored);
            var schema = AiJson.object("""
                    {"type":"object","properties":{"query":{"type":"string"},"snapshotId":{"type":"string"},
                    "recipeGeneration":{"type":"integer"},"offset":{"type":"integer","minimum":0}},"additionalProperties":false}
                    """, 2048, 8);
            registry.register(new DefinedTool(new AgentTool.Descriptor("crafting_discover", "查看当前材料能制作什么，返回具体物品、一次产量和真实原料；只读查询，不扣料。",
                    schema, AgentTool.Risk.READ_ONLY, "materials.own", 0, AgentTool.ExecutionThread.IO, 5000, false, ""),
                    (ctx, arguments) -> { queryCalls.incrementAndGet(); return result; }));
            for (int i = 0; i < 80; i++) registry.register(new DefinedTool(new AgentTool.Descriptor("unrelated_capability_" + i,
                    "Unrelated capability. " + "Bounded catalog fixture. ".repeat(15), schema, AgentTool.Risk.READ_ONLY,
                    "workspace.read", 0, AgentTool.ExecutionThread.IO, 1000, false, ""),
                    (ctx, arguments) -> AgentTool.Result.error("OUTSIDE_FIXTURE_SCOPE", "本验证不执行该能力。")));
            registry.registerDiscovery(ignored -> true);
            if (stored) {
                var old = AgentTask.create(actor.owner(), UUID.randomUUID(), "查看当前材料能制作什么");
                UUID artifact = workspace.saveArtifact(result); String full = AiJson.GSON.toJson(result);
                var pointer = new JsonObject(); pointer.addProperty("success", true); pointer.addProperty("artifactId", artifact.toString());
                pointer.addProperty("summary", "已核验材料，完整清单需读取产物。");
                var journal = new JsonObject(); journal.addProperty("name", "crafting_discover"); journal.addProperty("success", true);
                journal.addProperty("errorCode", ""); journal.addProperty("artifactId", artifact.toString()); journal.addProperty("fullResult", full);
                workspace.conversation().append(List.of(Draft.message(old.id(), ModelMessage.text("user", old.prompt()), null),
                        Draft.message(old.id(), new ModelMessage("assistant", "", List.of(new ModelMessage.ToolCall("old-query", "crafting_discover", "{}")), null, null), "old-query"),
                        Draft.message(old.id(), ModelMessage.result("old-query", pointer.toString()), "old-query"),
                        new Draft(old.id(), "tool_result", null, journal, "old-query"),
                        Draft.message(old.id(), ModelMessage.text("assistant", "清单已保存，回复继续再告诉你。"), null),
                        Draft.checkpoint(old.update(AgentTask.State.COMPLETED, ""))));
            }
            ModelProtocol protocol = scenario.startsWith("responses") ? new ResponsesAdapter() : new ChatCompletionsAdapter();
            var http = new HttpModelGateway(credential.get("endpoint").getAsString().replaceAll("/+$", "") + "/v1",
                    credential.get("apiKey").getAsString(), MODEL, protocol, 90000, 0);
            ModelGateway charged = new ModelGateway() {
                @Override public Reply complete(Request request, TaskControl control) throws Exception { return complete(request, control, ignored -> { }); }
                @Override public Reply complete(Request request, TaskControl control, java.util.function.Consumer<ModelEvent> listener) throws Exception {
                    if (ContextCompactor.estimate(request) > 32000 || request.maxOutputTokens() > 2048) throw new IllegalStateException("LIVE_REQUEST_SIZE_LIMIT");
                    int requests = ledger.get("requests").getAsInt(); double reserved = ledger.get("reservedUsd").getAsDouble();
                    if (BATCH_CALLS.get() >= 8 || requests >= credential.get("requestLimit").getAsInt()
                            || reserved + .10 > credential.get("budgetUsd").getAsDouble()) throw new IllegalStateException("LIVE_TEST_BUDGET_EXHAUSTED");
                    BATCH_CALLS.incrementAndGet(); ledger.addProperty("requests", requests + 1); ledger.addProperty("reservedUsd", reserved + .10);
                    AiJson.write(report, ledger);
                    var reply = http.complete(request, control, listener);
                    ledger.addProperty("inputTokens", ledger.get("inputTokens").getAsLong() + reply.inputTokens());
                    ledger.addProperty("outputTokens", ledger.get("outputTokens").getAsLong() + reply.outputTokens());
                    ledger.addProperty("conservativeUsageEstimateUsd", ledger.get("inputTokens").getAsLong() * 2.0 / 1000000
                            + ledger.get("outputTokens").getAsLong() * 8.0 / 1000000);
                    AiJson.write(report, ledger);
                    return reply;
                }
            };
            var environment = new AgentRuntime.Environment() {
                @Override public Actor actor(String owner) { return actor; }
                @Override public boolean enabled(String tool) { return true; }
                @Override public CompletableFuture<AgentTool.Result> execute(AgentTool tool, AgentTool.Context ctx, JsonObject args, String approval, ModelMessage.ToolCall call) {
                    try { return CompletableFuture.completedFuture(tool.execute(ctx, args)); }
                    catch (Exception failed) { return CompletableFuture.failedFuture(failed); }
                }
                @Override public void changed(WorkspaceManager.Workspace ws, AgentTask task, String notice) { }
            };
            try (var runtime = new AgentRuntime(manager, new TaskScheduler(1, 8, 8), charged, registry,
                    new ContextCompactor(new ContextCompactor.Settings(32768, 2048, 4096, 1024, .8, 2), MODEL),
                    new AgentRuntime.Limits(12, 12, 240000, 240000, 2048, true, false, true), environment,
                    new SecretFilter(List.of(credential.get("apiKey").getAsString())))) {
                var task = runtime.submit(actor, UUID.randomUUID(), stored ? "把刚才未交付的制作清单读出来，直接告诉我具体物品、一次产量和原料，不重新盘点。"
                        : "看一下我当前的材料都能做什么，直接告诉我具体物品、一次产量和原料。");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(250);
                while (!workspace.conversation().task(task.id()).state().terminal() && System.nanoTime() < deadline) Thread.sleep(25);
                var done = workspace.conversation().task(task.id());
                if (!done.state().terminal()) runtime.control(actor, task.id(), "cancel");
                var events = workspace.conversation().getSnapshot(0).events().stream().filter(event -> task.id().equals(event.taskId())).toList();
                String answer = events.stream().filter(event -> event.message() != null && event.message().role().equals("assistant") && event.message().calls().isEmpty())
                        .map(event -> event.message().text()).reduce((a, b) -> b).orElse("");
                long reads = events.stream().filter(event -> event.kind().equals("tool_result") && event.data().get("name").getAsString().equals("artifact_read")
                        && event.data().get("success").getAsBoolean()).count();
                boolean concrete = List.of("木棍", "工作台", "木板").stream().allMatch(answer::contains);
                boolean oneInput = events.stream().filter(event -> event.message() != null && event.message().role().equals("user")).count() == 1;
                boolean pass = done.state() == AgentTask.State.COMPLETED && concrete && oneInput && (stored ? reads > 0 && queryCalls.get() == 0 : queryCalls.get() == 1);
                run.addProperty("status", pass ? "PASSED" : "FAILED"); run.addProperty("state", done.state().name()); run.addProperty("reason", done.reason());
                run.addProperty("concreteItemAnswer", concrete); run.addProperty("oneUserMessage", oneInput);
                run.addProperty("queryExecutions", queryCalls.get()); run.addProperty("artifactReadPages", reads);
                run.addProperty("answer", answer); run.addProperty("steps", done.steps());
                run.add("tools", AiJson.GSON.toJsonTree(events.stream().filter(event -> event.kind().equals("tool_result")).map(event -> event.data().get("name").getAsString()).toList()));
            }
        } catch (Exception failed) {
            run.addProperty("status", "FAILED"); run.addProperty("code", failed instanceof ModelGateway.Failure ? failed.getMessage() : failed.getClass().getSimpleName());
        }
        run.addProperty("elapsedMillis", (System.nanoTime() - started) / 1000000); run.addProperty("batchCalls", BATCH_CALLS.get());
        return run;
    }

    private static AgentTool.Result inventoryResult(boolean verbose) {
        var rows = new JsonArray(); String[] names = {"木棍", "工作台", "橡木木板"};
        String[] ids = {"minecraft:stick", "minecraft:crafting_table", "minecraft:oak_planks"};
        int[] amounts = {4, 1, 4}, inputs = {2, 4, 1};
        for (int i = 0; i < names.length; i++) {
            var row = new JsonObject(); row.addProperty("displayName", names[i]); row.addProperty("recipeId", ids[i]); row.addProperty("outputId", ids[i]);
            row.addProperty("productSemantics", "DETERMINISTIC_PRODUCTS_AND_REMAINDERS");
            var product = new JsonObject(); product.addProperty("id", ids[i]); product.addProperty("count", amounts[i]);
            var products = new JsonArray(); products.add(product); row.add("productsIncludingRemainders", products);
            var input = new JsonObject(); var material = new JsonObject(); material.addProperty("id", i == 2 ? "minecraft:oak_log" : "minecraft:oak_planks");
            material.addProperty("count", inputs[i]); input.add("item", material); var ingredients = new JsonArray(); ingredients.add(input); row.add("inputs", ingredients);
            row.add("probabilityOutputs", new JsonArray()); rows.add(row);
        }
        var data = new JsonObject(); data.add("craftable", rows); data.addProperty("capturedAt", System.currentTimeMillis());
        data.addProperty("capturedAtIso", java.time.Instant.ofEpochMilli(data.get("capturedAt").getAsLong()).toString());
        data.addProperty("snapshotId", UUID.randomUUID().toString()); data.addProperty("recipeGeneration", 1); data.addProperty("nextOffset", 3);
        data.addProperty("hasMore", false); data.addProperty("completeModpackCatalog", false); data.addProperty("independentAlternatives", true);
        if (verbose) data.addProperty("diagnosticDetails", "isolated immutable inventory evidence; ".repeat(180));
        return new AgentTool.Result(true, "", "已核验一次加工的独立备选，未扣料。", data, "synthetic-native-result-contract", "", false);
    }
}
