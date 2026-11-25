package firefly520.fireflymc.validation;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AiServerService;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

/** Executes real continuations with production save dispatch by default; explicit fault fixtures may force a save. */
final class ValidationDeferredCalls {
    private ValidationDeferredCalls() {}
    static AgentTool.Result execute(MinecraftServer server, MaterialLedger ledger, MaterialInventoryService inventory,
            AgentTool tool, AgentTool.Context context, JsonObject arguments, Consumer<AgentTool.Context> freshSlice,
            Consumer<String> passed) throws Exception {
        return execute(server, ledger, inventory, tool, context, arguments, freshSlice, passed, true);
    }
    static AgentTool.Result execute(MinecraftServer server, MaterialLedger ledger, MaterialInventoryService inventory,
            AgentTool tool, AgentTool.Context context, JsonObject arguments, Consumer<AgentTool.Context> freshSlice,
            Consumer<String> passed, boolean saveOutputs) throws Exception {
        var call = new ModelMessage.ToolCall(UUID.randomUUID().toString(), tool.descriptor().name(), arguments.toString());
        String approval = ToolPolicyEngine.digest(context.actor().owner(), call);
        var bridge = new GameToolBridge(server, owner -> context.actor(), ignored -> true, () -> false, Runnable::run);
        AgentTool current = tool; AgentTool.Context slice = context; AgentTool.Result result;
        for (int attempt = 0; ; attempt++) {
            if (attempt > 256) throw new IllegalStateException("VALIDATION_DEFERRED_PHASE_LIMIT");
            try { result = bridge.execute(current, slice, arguments, approval, call).get(30, TimeUnit.SECONDS); break; }
            catch (ExecutionException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof TaskControl.Stopped stopped) {
                    result = MaterialStopReceipt.capture(ledger, slice, arguments, stopped); break;
                }
                if (!(cause instanceof AgentTool.Deferred deferred)) {
                    if (cause instanceof Exception error) throw error;
                    throw failure;
                }
                UUID operation = UUID.fromString(deferred.checkpoint().get("operationId").getAsString());
                var held = ledger.get(context.actor(), operation);
                boolean input = deferred.reason().equals("WAITING_INPUT_SAVE");
                if (input) {
                    if (held.state() != MaterialLedger.State.ESCROWED || !held.products().isEmpty() || !held.externalDebits().isEmpty()
                            || !held.worldDebits().isEmpty() || held.intent() != null)
                        throw new IllegalStateException("DEFERRED_INPUT_PHASE_NOT_REAL_ESCROW");
                    passed.accept(tool.descriptor().name() + ":real-inputs-held-without-products-callback-or-entity-before-save");
                } else if (!Set.of("WAITING_FINAL_NATIVE_SAVE", "WAITING_WORLD_NATIVE_SAVE", "WAITING_NATIVE_PRODUCTION_SAVE").contains(deferred.reason()))
                    throw new IllegalStateException("VALIDATION_UNRECOGNIZED_SAVE_PHASE:" + deferred.reason());
                if (Boolean.getBoolean("fireflymc.validation.forceFixtureSaves")) save(server);
                try { context.control().await(deferred.readiness().toCompletableFuture(), 30000); }
                catch (TaskControl.Stopped stopped) { result = MaterialStopReceipt.capture(ledger, slice, arguments, stopped); break; }
                if (input && !ledger.get(context.actor(), operation).inputsSaved()) throw new IllegalStateException("DEFERRED_INPUT_PROOF_MISSING");
                slice = new AgentTool.Context(context.workspace(), context.actor(), context.control(), context.taskId(), context.modelResultBytes());
                freshSlice.accept(slice);
                var continuation = deferred.continuation();
                current = new DefinedTool(tool.descriptor(), (fresh, ignored) -> continuation.resume(fresh));
                passed.accept(tool.descriptor().name() + ":same-approved-call-after-" + deferred.reason());
            }
        }
        UUID operation = null;
        try { operation = UUID.fromString(arguments.get(arguments.has("planId") ? "planId" : "operationId").getAsString()); }
        catch (RuntimeException noOperation) { /* Read-only planning does not create custody. */ }
        if (saveOutputs && operation != null && ledger.find(context.actor(), operation).isPresent()) {
            if (Boolean.getBoolean("fireflymc.validation.forceFixtureSaves")) save(server);
            context.control().await(inventory.nativeSaves().awaitJournalled(operation).toCompletableFuture(), 30000);
            var actual = ledger.get(context.actor(), operation);
            if (result.success() && !actual.saved()) throw new IllegalStateException("SUCCESS_WITHOUT_NATIVE_SAVE_PROOF");
            if (actual.saved()) passed.accept(tool.descriptor().name() + ":actual-native-input-callback-output-save-receipts");
        }
        return result;
    }
    static void save(MinecraftServer server) throws Exception {
        if (!server.isDedicatedServer() || server.isSameThread()) throw new IllegalStateException("VALIDATION_SAVE_WORKER_REQUIRED");
        var saved = new CompletableFuture<Void>();
        server.execute(() -> {
            try {
                server.getPlayerList().saveAll();
                for (var player : server.getPlayerList().getPlayers()) { player.getStats().save(); player.getAdvancements().save(); }
                for (var level : server.getAllLevels()) level.save(null, true, false);
                saved.complete(null);
            } catch (Throwable failure) { saved.completeExceptionally(failure); }
        });
        saved.get(30, TimeUnit.SECONDS);
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
    }
}
