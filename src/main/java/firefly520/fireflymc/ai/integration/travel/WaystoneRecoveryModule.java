package firefly520.fireflymc.ai.integration.travel;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.recovery.RecoveryModule;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** Direct private workbench recovery remains usable while the owner's model queue is fenced. */
public final class WaystoneRecoveryModule implements RecoveryModule {
    private final WaystoneTravelService service;
    public WaystoneRecoveryModule(WaystoneTravelService service) { this.service = Objects.requireNonNull(service); }
    @Override public List<JsonObject> rows(Workspace workspace, Actor actor, UUID taskId) throws Exception { return service.recoveryRows(workspace, actor, taskId); }
    @Override public Preview preview(AgentTool.Context context, UUID operationId) throws Exception {
        var value = service.recoveryPreview(context.workspace(), context.actor(), operationId);
        requireTask(service.recoveryResources(context.workspace(), context.actor(), operationId), context.taskId());
        var args = new JsonObject(); args.addProperty("operationId", operationId.toString()); args.addProperty("revision", value.revision()); args.addProperty("reviewHash", value.reviewHash());
        return new Preview("waystone_travel_reconcile", args, service.reviewRecovery(context.workspace(), context.actor(), operationId, value.revision(), value.reviewHash()));
    }
    @Override public JsonObject evidence(Workspace workspace, Actor actor, UUID operationId) throws Exception { return service.recoveryResources(workspace, actor, operationId); }
    @Override public void validatePreview(Workspace workspace, Actor actor, UUID taskId, UUID operationId, JsonObject args) throws Exception {
        exact(args, Set.of("operationId", "revision", "reviewHash"));
        if (!operationId.equals(UUID.fromString(string(args, "operationId")))) throw new SecurityException("WAYSTONE_RECOVERY_OPERATION_CHANGED");
        requireTask(service.recoveryResources(workspace, actor, operationId), taskId);
        service.validateRecovery(workspace, actor, operationId, integer(args, "revision"), string(args, "reviewHash"));
    }
    @Override public boolean settled(Workspace workspace, Actor actor, UUID operationId) { return service.operationSettled(workspace, actor, operationId); }
    @Override public String settledStatus() { return "TRAVEL_NATIVE_RESULT_RECONCILED"; }
    @Override public Optional<Boolean> settledCall(Workspace workspace, Actor actor, UUID taskId, ModelMessage.ToolCall call) {
        if (!Set.of("waystone_travel_execute", "waystone_target_prepare_execute", "waystone_travel_reconcile").contains(call.name())) return Optional.empty();
        try {
            actor.requireAccess(workspace.owner()); var args = AiJson.object(call.arguments(), 65536, 24); UUID id; String expectedHash;
            if (call.name().equals("waystone_travel_execute")) {
                exact(args, Set.of("planId", "planHash")); id = UUID.fromString(string(args, "planId")); expectedHash = string(args, "planHash");
                // Historical artifacts bind evidence only. No old execution epoch or native context is reused.
                var plan = service.publishedArtifact(workspace, actor, id);
                if (!plan.taskId().equals(taskId) || !plan.hash().equals(expectedHash) || !plan.kind().equals("TRAVEL")) return Optional.of(false);
            } else if (call.name().equals("waystone_target_prepare_execute")) {
                exact(args, Set.of("preparationId", "preparationHash")); id = UUID.fromString(string(args, "preparationId")); expectedHash = string(args, "preparationHash");
                var plan = service.publishedArtifact(workspace, actor, id);
                if (!plan.taskId().equals(taskId) || !plan.hash().equals(expectedHash) || !plan.kind().equals("PREPARATION")) return Optional.of(false);
            } else {
                exact(args, Set.of("operationId", "revision", "reviewHash")); id = UUID.fromString(string(args, "operationId"));
                var op = service.publishedOperations(workspace, actor).stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
                if (!op.taskId().equals(taskId)) return Optional.of(false); var nativeResult = op.nativeResult();
                if (!nativeResult.has("acceptedReviewRevision") || !nativeResult.has("acceptedReviewHash")
                        || nativeResult.get("acceptedReviewRevision").getAsLong() != integer(args, "revision")
                        || !nativeResult.get("acceptedReviewHash").getAsString().equals(string(args, "reviewHash"))) return Optional.of(false);
                return Optional.of(service.operationSettled(workspace, actor, id));
            }
            var op = service.publishedOperations(workspace, actor).stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
            if (!op.taskId().equals(taskId) || !expectedHash.equals(op.planHash()) || call.name().equals("waystone_target_prepare_execute") != op.preparation()) return Optional.of(false);
            return Optional.of(service.operationSettled(workspace, actor, id));
        } catch (Exception | LinkageError invalid) { return Optional.of(false); }
    }
    private static void requireTask(JsonObject evidence, UUID expected) {
        if (expected == null || !evidence.has("taskId") || !expected.toString().equals(evidence.get("taskId").getAsString())) throw new SecurityException("WAYSTONE_TASK_MISMATCH");
    }
    private static void exact(JsonObject args, Set<String> names) { if (!args.keySet().equals(names)) throw new IllegalArgumentException("WAYSTONE_ORIGINAL_ARGUMENTS_CHANGED"); }
    private static String string(JsonObject args, String key) {
        if (!args.has(key) || !args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException("WAYSTONE_ARGUMENT_TYPE");
        return args.get(key).getAsString();
    }
    private static long integer(JsonObject args, String key) {
        if (!args.has(key) || !args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException("WAYSTONE_ARGUMENT_TYPE");
        long value = args.get(key).getAsBigDecimal().longValueExact(); if (value < 1) throw new IllegalArgumentException("WAYSTONE_ARGUMENT_RANGE"); return value;
    }
}
