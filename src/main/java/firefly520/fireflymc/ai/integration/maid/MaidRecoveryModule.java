package firefly520.fireflymc.ai.integration.maid;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.recovery.RecoveryModule;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** Direct own-workbench recovery for mode obligations, including while the model scheduler is fenced. */
public final class MaidRecoveryModule implements RecoveryModule {
    private final MaidControlService service;
    private final MaidFarmService farm;
    public MaidRecoveryModule(MaidControlService service) { this(service, null); }
    public MaidRecoveryModule(MaidControlService service, MaidFarmService farm) { this.service = Objects.requireNonNull(service); this.farm = farm; }

    @Override public List<JsonObject> rows(Workspace workspace, Actor actor, UUID taskId) throws Exception {
        var rows = new ArrayList<>(service.recoveryRows(workspace, actor, taskId)); if (farm != null) rows.addAll(farm.recoveryRows(workspace, actor, taskId)); return List.copyOf(rows);
    }
    @Override public Preview preview(AgentTool.Context context, UUID operationId) throws Exception {
        if (farmOperation(context.workspace(), context.actor(), operationId)) {
            var plan = farm.recoveryPreview(context, operationId); var args = new JsonObject();
            args.addProperty("reviewId", plan.id().toString()); args.addProperty("reviewHash", plan.hash());
            return new Preview("maid_farm_reconcile", args, farm.reconciliationReview(plan));
        }
        var plan = service.recoveryPreview(context, operationId); var args = new JsonObject();
        args.addProperty("reviewId", plan.id().toString()); args.addProperty("reviewHash", plan.hash());
        return new Preview("maid_control_reconcile", args, service.reconciliationReview(plan));
    }
    @Override public JsonObject evidence(Workspace workspace, Actor actor, UUID operationId) throws Exception {
        return farmOperation(workspace, actor, operationId) ? farm.recoveryResources(workspace, actor, operationId) : service.recoveryResources(workspace, actor, operationId);
    }
    @Override public void validatePreview(Workspace workspace, Actor actor, UUID taskId, UUID operationId, JsonObject arguments) throws Exception {
        requireKeys(arguments, Set.of("reviewId", "reviewHash"));
        if (farmOperation(workspace, actor, operationId)) {
            var plan = farm.loadReconciliation(workspace, actor, uuid(arguments, "reviewId"), hash(arguments, "reviewHash"));
            if (!plan.taskId().equals(taskId) || !plan.operationId().equals(operationId)) throw new SecurityException("MAID_FARM_TASK_MISMATCH");
            farm.validateReconciliationVersion(workspace, actor, plan); return;
        }
        var plan = service.loadReconciliation(workspace, actor, uuid(arguments, "reviewId"), hash(arguments, "reviewHash"));
        if (!plan.taskId().equals(taskId) || !plan.operationId().equals(operationId)) throw new SecurityException("MAID_CONTROL_TASK_MISMATCH");
        var evidence = service.recoveryResources(workspace, actor, operationId);
        if (!taskId.toString().equals(evidence.get("taskId").getAsString())) throw new SecurityException("MAID_CONTROL_TASK_MISMATCH");
        service.validateReconciliationVersion(workspace, actor, plan);
    }
    @Override public boolean settled(Workspace workspace, Actor actor, UUID operationId) throws Exception {
        return farmOperation(workspace, actor, operationId) ? farm.operationSettled(workspace, actor, operationId) : service.operationSettled(workspace, actor, operationId);
    }
    @Override public String settledStatus() { return "RECONCILED_CURRENT_STATE"; }
    @Override public Optional<Boolean> settledCall(Workspace workspace, Actor actor, UUID taskId, ModelMessage.ToolCall call) {
        if (farm != null) { var result = farm.settledCall(workspace, actor, taskId, call); if (result.isPresent()) return result; }
        return service.settledPublishedCall(workspace, actor, taskId, call);
    }
    private boolean farmOperation(Workspace workspace, Actor actor, UUID id) {
        boolean mode = service.ownsOperation(workspace, actor, id), world = farm != null && farm.ownsOperation(workspace, actor, id);
        if (mode && world) throw new IllegalStateException("MAID_RECOVERY_OPERATION_AMBIGUOUS"); return world;
    }
    private static void requireKeys(JsonObject args, Set<String> expected) {
        if (!args.keySet().equals(expected)) throw new IllegalArgumentException("MAID_RECOVERY_ARGUMENTS_CHANGED");
    }
    private static UUID uuid(JsonObject args, String key) { return UUID.fromString(args.get(key).getAsString()); }
    private static String hash(JsonObject args, String key) {
        String hash = args.get(key).getAsString(); if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("MAID_RECOVERY_HASH_INVALID"); return hash;
    }
}
