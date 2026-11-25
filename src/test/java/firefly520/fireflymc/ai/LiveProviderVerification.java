package firefly520.fireflymc.ai;

import com.google.gson.*;
import firefly520.fireflymc.ai.context.ContextCompactor;
import firefly520.fireflymc.ai.model.*;
import firefly520.fireflymc.ai.security.ToolPolicyEngine;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.TaskControl;
import java.nio.file.*;
import java.util.*;

/**
 * Explicit opt-in live contract runner, not a default JUnit test. Uses only newly supplied isolated
 * credentials and harmless actual integer calculations. Never prints credentials or provider bodies.
 */
public final class LiveProviderVerification {
    private static final String MODEL = "glm-5.3-flash";
    private static final double INPUT_CEILING_PER_MILLION = 2.0;
    private static final double OUTPUT_CEILING_PER_MILLION = 8.0;
    private LiveProviderVerification() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4) throw new IllegalArgumentException("Explicit isolated credential and report paths required");
        boolean streaming = args.length == 4 && Boolean.parseBoolean(args[3]);
        Path credentialsPath = Path.of(args[0]).toAbsolutePath().normalize();
        Path report = Path.of(args[1]).toAbsolutePath().normalize();
        JsonObject credential = AiJson.object(Files.readString(credentialsPath), 65536, 16);
        if (credential.get("budgetUsd").getAsDouble() > 20 || credential.get("requestLimit").getAsInt() > 32)
            throw new IllegalStateException("LIVE_TEST_BUDGET_EXCEEDS_AUTHORIZATION");
        Path pricingPath = credentialsPath.getParent().getParent().resolve("public/pricing.json");
        JsonObject pricing = AiJson.object(Files.readString(pricingPath), 8 * 1024 * 1024, 32);
        JsonObject price = pricing.getAsJsonArray("data").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(row -> MODEL.equals(row.get("model_name").getAsString())).findFirst().orElseThrow();
        // The provider's public New API pricing UI uses $2/M input as its ratio base.
        // These deliberately higher ceilings bound this small verification batch, not actual billing.
        double input = price.get("model_ratio").getAsDouble() * 2;
        double output = input * price.get("completion_ratio").getAsDouble();
        if (price.get("quota_type").getAsInt() != 0 || input > INPUT_CEILING_PER_MILLION
                || output > OUTPUT_CEILING_PER_MILLION) throw new IllegalStateException("LIVE_PRICING_REVIEW_REQUIRED");
        JsonObject ledger = Files.exists(report) ? AiJson.object(Files.readString(report), 65536, 24) : new JsonObject();
        if (!ledger.has("requests")) ledger.addProperty("requests", 0);
        if (!ledger.has("reservedUsd")) ledger.addProperty("reservedUsd", 0);
        if (!ledger.has("inputTokens")) ledger.addProperty("inputTokens", 0);
        if (!ledger.has("outputTokens")) ledger.addProperty("outputTokens", 0);
        if (!ledger.has("runs")) ledger.add("runs", new JsonArray());
        ledger.addProperty("model", MODEL);
        ledger.addProperty("pricingDigest", AiJson.digest(Files.readString(pricingPath)));
        ledger.addProperty("budgetUsd", credential.get("budgetUsd").getAsDouble());
        boolean passed = true;
        for (ModelProtocol protocol : List.of(new ChatCompletionsAdapter(), new ResponsesAdapter())) {
            if (args.length >= 3 && !args[2].isBlank() && !protocol.path().contains(args[2])) continue;
            JsonObject run = new JsonObject();
            run.addProperty("protocol", protocol.path());
            run.addProperty("streaming", streaming);
            run.add("steps", new JsonArray());
            long started = System.nanoTime();
            try {
                String endpoint = credential.get("endpoint").getAsString().replaceAll("/+$", "") + "/v1";
                var gateway = new HttpModelGateway(endpoint, credential.get("apiKey").getAsString(), MODEL, protocol, 120000, 0);
                List<ModelMessage> history = new ArrayList<>(List.of(
                        ModelMessage.text("system", "Use only the offered arithmetic function. After each result continue the calculation. Never invent a tool result."),
                        ModelMessage.text("user", "First multiply 7 by 4 using the tool; then add 5 to the returned result using the next tool. Finally answer only the resulting integer.")));
                int executions = 0, reasoning = 0, rounds = 0;
                var deltas = new java.util.concurrent.atomic.AtomicInteger();
                var acknowledgements = new java.util.concurrent.atomic.AtomicInteger();
                boolean complete = false;
                for (int round = 0; round < 4; round++) {
                    if (executions == 1) history.add(ModelMessage.text("user",
                            "The tool returned 28. Now call the offered add function with a=28 and b=5. This is a protocol verification; do not answer without calling it."));
                    if (executions == 2) history.add(ModelMessage.text("user", "Both tool results are now available. Answer only the final integer: no additional steps."));
                    JsonObject schema = AiJson.object("""
                            {"type":"object","properties":{"a":{"type":"integer","minimum":0,"maximum":100},
                            "b":{"type":"integer","minimum":0,"maximum":100}},"required":["a","b"],"additionalProperties":false}
                            """, 2048, 8);
                    String function = executions == 0 ? "multiply" : "add";
                    for (String parameter : List.of("a", "b")) {
                        int value = parameter.equals("a") ? (executions == 0 ? 7 : 28) : (executions == 0 ? 4 : 5);
                        var property = schema.getAsJsonObject("properties").getAsJsonObject(parameter);
                        property.addProperty("minimum", value); property.addProperty("maximum", value);
                    }
                    List<ModelGateway.ToolDefinition> tools = executions >= 2 ? List.of() : List.of(
                            new ModelGateway.ToolDefinition(function, "Compute the " + function + " of two integers.", schema));
                    var request = new ModelGateway.Request(history, tools, protocol instanceof ResponsesAdapter ? 4096 : 1024,
                            false, executions >= 2 ? "none" : "required", streaming);
                    if (ContextCompactor.estimate(request) > 24000) throw new IllegalStateException("LIVE_INPUT_LIMIT");
                    int sent = ledger.get("requests").getAsInt();
                    double reserved = ledger.get("reservedUsd").getAsDouble();
                    if (sent >= credential.get("requestLimit").getAsInt() || reserved + .10 > credential.get("budgetUsd").getAsDouble())
                        throw new IllegalStateException("LIVE_TEST_BUDGET_EXHAUSTED");
                    ledger.addProperty("requests", sent + 1);
                    ledger.addProperty("reservedUsd", reserved + .10);
                    AiJson.write(report, ledger); // Reserve before transport; ambiguous failures remain charged.
                    var reply = gateway.complete(request, new TaskControl(150000), event -> {
                        if (event instanceof ModelEvent.TextDelta) deltas.incrementAndGet();
                        if (event instanceof ModelEvent.ToolResultAck) acknowledgements.incrementAndGet();
                    });
                    rounds++;
                    JsonObject trace = new JsonObject();
                    trace.addProperty("round", rounds);
                    trace.addProperty("executionsBeforeRound", executions);
                    trace.add("returnedToolNames", AiJson.GSON.toJsonTree(reply.message().calls().stream().map(ModelMessage.ToolCall::name).toList()));
                    trace.addProperty("finalMatchesExpected", reply.message().text().trim().equals("33"));
                    trace.addProperty("assistantCharacters", reply.message().text().length());
                    run.getAsJsonArray("steps").add(trace);
                    ledger.addProperty("inputTokens", ledger.get("inputTokens").getAsLong() + reply.inputTokens());
                    ledger.addProperty("outputTokens", ledger.get("outputTokens").getAsLong() + reply.outputTokens());
                    reasoning += (int) reply.message().responseItems().asList().stream()
                            .filter(e -> e.isJsonObject() && e.getAsJsonObject().has("type")
                                    && "reasoning".equals(e.getAsJsonObject().get("type").getAsString())).count();
                    history.add(reply.message());
                    if (reply.message().calls().isEmpty()) {
                        if (executions < 2 || !reply.message().text().trim().equals("33"))
                            throw new IllegalStateException("LIVE_TOOL_CHAIN_NOT_VERIFIED");
                        complete = true;
                        break;
                    }
                    for (var call : reply.message().calls()) {
                        if (executions >= 2 || !call.name().equals(function)) throw new IllegalStateException("LIVE_UNEXPECTED_TOOL");
                        var arguments = ToolPolicyEngine.arguments(call, schema);
                        int a = arguments.get("a").getAsInt(), b = arguments.get("b").getAsInt();
                        int result = "multiply".equals(function) ? Math.multiplyExact(a, b) : Math.addExact(a, b);
                        trace.add("validatedArithmetic", AiJson.GSON.toJsonTree(Map.of("a", a, "b", b, "result", result)));
                        if (result != (executions == 0 ? 28 : 33)) throw new IllegalStateException("LIVE_INCORRECT_CALCULATION");
                        history.add(ModelMessage.result(call.id(), "{\"result\":" + result + "}"));
                        executions++;
                    }
                }
                if (!complete) throw new IllegalStateException("LIVE_NO_FINAL");
                if (streaming && (deltas.get() == 0 || acknowledgements.get() < 2))
                    throw new IllegalStateException("LIVE_STREAM_EVENTS_NOT_VERIFIED");
                run.addProperty("status", "PASSED");
                run.addProperty("rounds", rounds);
                run.addProperty("executedArithmeticTools", executions);
                run.addProperty("reasoningItemsReplayed", reasoning);
                run.addProperty("textDeltaEvents", deltas.get());
                run.addProperty("toolResultAcknowledgements", acknowledgements.get());
            } catch (Exception e) {
                passed = false;
                run.addProperty("status", "FAILED");
                run.addProperty("code", e instanceof ModelGateway.Failure ? e.getMessage()
                        : e instanceof IllegalStateException ? e.getMessage() : e.getClass().getSimpleName());
                if (e instanceof ModelGateway.Failure failure)
                    run.add("diagnostics", AiJson.GSON.toJsonTree(failure.diagnostics()));
            }
            run.addProperty("elapsedMillis", (System.nanoTime() - started) / 1_000_000);
            ledger.getAsJsonArray("runs").add(run);
            ledger.addProperty("conservativeUsageEstimateUsd", ledger.get("inputTokens").getAsLong() * INPUT_CEILING_PER_MILLION / 1_000_000
                    + ledger.get("outputTokens").getAsLong() * OUTPUT_CEILING_PER_MILLION / 1_000_000);
            AiJson.write(report, ledger);
            System.out.println(protocol.path() + ": " + run.get("status").getAsString());
        }
        if (!passed) throw new IllegalStateException("Live verification incomplete; inspect redacted report");
    }
}
