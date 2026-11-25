package firefly520.fireflymc.ai.recovery;

import com.google.gson.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.material.MaterialLedger;
import firefly520.fireflymc.ai.processing.FluidProcessingService;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;
import java.util.function.*;

/** A resource receipt never clears unrelated or untracked side effects in the same task. */
public final class RecoveryTaskEvidence {
    private static final Set<String> RESOURCE_TOOLS = Set.of("processing_execute", "advanced_processing_execute",
            "special_processing_execute", "special_entity_execute", "fluid_processing_execute", "processing_return",
            "processing_reconcile", "fluid_processing_reconcile", "fluid_processing_return", "fluid_processing_resume");
    private RecoveryTaskEvidence() {}
    public static boolean allSettled(Workspace workspace, Actor owner, UUID task, MaterialLedger materials, FluidProcessingService mixed) {
        return allSettled(workspace, owner, task, materials, mixed, call -> Optional.empty());
    }
    public static boolean allSettled(Workspace workspace, Actor owner, UUID task, MaterialLedger materials, FluidProcessingService mixed,
                                     Function<ModelMessage.ToolCall, Optional<Boolean>> nativeEffects) {
        try {
            owner.requireAccess(workspace.owner());
            if (materials == null || mixed == null || !materials.settledForTask(owner, task) || !mixed.settledForTask(workspace, owner, task)
                    || mixed.requiresRecovery(workspace, task) || firefly520.fireflymc.ai.blueprint.BuildJournal.hasUnresolvedWorldEffects(workspace)) return false;
            if (new firefly520.fireflymc.ai.blueprint.RemovalJournal(workspace, UUID.randomUUID()).list(owner).stream()
                    .anyMatch(step -> step.input().taskId().equals(task) && !step.settled())) return false;
            return supportedEffectsSettled(workspace, task, id -> {
                var op = materials.find(owner, id);
                return op.isPresent() && op.get().settled() && op.get().saved()
                        && (!mixed.ownsOperation(workspace, owner, id) || mixed.operationSettled(workspace, owner, id));
            }, args -> {
                try { return mixed.loadReturn(workspace, owner, UUID.fromString(args.get("reviewId").getAsString()), args.get("reviewHash").getAsString()).operationId(); }
                catch (Exception notReturn) {
                    try { return mixed.resumeOperationReference(workspace, owner, UUID.fromString(args.get("reviewId").getAsString()), args.get("reviewHash").getAsString()); }
                    catch (Exception invalid) { return null; }
                }
            }, nativeEffects);
        } catch (Exception invalidEvidence) { return false; }
    }
    public static boolean supportedEffectsSettled(Workspace workspace, UUID task,
            Predicate<UUID> settledOperation, Function<JsonObject, UUID> fluidReturnOperation) {
        return supportedEffectsSettled(workspace, task, settledOperation, fluidReturnOperation, call -> Optional.empty());
    }
    public static boolean supportedEffectsSettled(Workspace workspace, UUID task,
            Predicate<UUID> settledOperation, Function<JsonObject, UUID> fluidReturnOperation,
            Function<ModelMessage.ToolCall, Optional<Boolean>> nativeEffects) {
        var events = workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events();
        var calls = new HashMap<String, ModelMessage.ToolCall>(); var results = new HashMap<String, JsonObject>();
        for (var event : events) if (task.equals(event.taskId())) {
            if (event.message() != null) for (var call : event.message().calls()) calls.put(call.id(), call);
            if (event.kind().equals("tool_result") && event.data().has("fullResult")) {
                try { results.put(event.toolTransactionId(), AiJson.object(event.data().get("fullResult").getAsString(), 16 * 1024 * 1024, 64)); }
                catch (RuntimeException invalid) { return false; }
            }
        }
        boolean reviewed = false;
        for (var event : events) if (task.equals(event.taskId()) && event.kind().equals("tool_start")) {
            String risk = event.data().has("risk") ? event.data().get("risk").getAsString() : "UNKNOWN";
            if (Set.of("READ_ONLY", "MEMORY_WRITE").contains(risk)) continue;
            reviewed = true;
            var result = results.get(event.toolTransactionId());
            if (result != null && result.has("observations") && result.get("observations").isJsonObject()) {
                var observations = result.getAsJsonObject("observations");
                if (observations.has("dispatch") && observations.get("dispatch").getAsString().equals("REJECTED_BEFORE_EXECUTION")) continue;
            }
            var call = calls.get(event.toolTransactionId());
            if (call == null || !call.name().equals(event.data().get("name").getAsString())) return false;
            if (!RESOURCE_TOOLS.contains(call.name())) {
                try { if (!nativeEffects.apply(call).orElse(false)) return false; else continue; }
                catch (RuntimeException invalid) { return false; }
            }
            try {
                var args = AiJson.object(call.arguments(), 65536, 24);
                UUID id = Set.of("fluid_processing_return", "fluid_processing_resume").contains(call.name()) ? fluidReturnOperation.apply(args)
                        : UUID.fromString(args.get(args.has("planId") ? "planId" : "operationId").getAsString());
                if (id == null || !settledOperation.test(id)) return false;
            } catch (RuntimeException invalidOrMissing) { return false; }
        }
        return reviewed;
    }
}
