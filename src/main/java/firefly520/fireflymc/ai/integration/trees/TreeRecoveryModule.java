package firefly520.fireflymc.ai.integration.trees;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.recovery.RecoveryModule;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** Deterministic own-workbench recovery of proven history; unknown effects never acquire a button. */
public final class TreeRecoveryModule implements RecoveryModule {
    private final TreeHarvestService service;
    public TreeRecoveryModule(TreeHarvestService service) { this.service = Objects.requireNonNull(service); }
    @Override public List<JsonObject> rows(Workspace workspace, Actor actor, UUID task) throws Exception { return service.recoveryRows(workspace, actor, task); }
    @Override public Preview preview(AgentTool.Context ctx, UUID operation) throws Exception {
        var review = service.recoveryPreview(ctx, operation); var args = new JsonObject();
        args.addProperty("reviewId", review.id().toString()); args.addProperty("reviewHash", review.hash());
        return new Preview("tree_harvest_reconcile", args, service.reconciliationReview(review));
    }
    @Override public JsonObject evidence(Workspace workspace, Actor actor, UUID operation) throws Exception { return service.recoveryResources(workspace, actor, operation); }
    @Override public void validatePreview(Workspace workspace, Actor actor, UUID task, UUID operation, JsonObject args) throws Exception {
        if (!args.keySet().equals(Set.of("reviewId", "reviewHash"))) throw new IllegalArgumentException("TREE_RECONCILIATION_ARGUMENTS_CHANGED");
        var review = service.loadReconciliation(workspace, actor, UUID.fromString(args.get("reviewId").getAsString()), args.get("reviewHash").getAsString());
        if (!review.taskId().equals(task) || !review.operationId().equals(operation)) throw new SecurityException("TREE_TASK_MISMATCH");
        service.validateReconciliationVersion(workspace, actor, review);
    }
    @Override public boolean settled(Workspace workspace, Actor actor, UUID operation) throws Exception { return service.operationSettled(workspace, actor, operation); }
    @Override public String settledStatus() { return "RECONCILED_SAVED_TREE_RESULT"; }
    @Override public Optional<Boolean> settledCall(Workspace workspace, Actor actor, UUID task, ModelMessage.ToolCall call) throws Exception { return service.settledCall(workspace, actor, task, call); }
}
