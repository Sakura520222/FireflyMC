package firefly520.fireflymc.ai.integration.exploration;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.recovery.RecoveryModule;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** Deterministic own-workbench entry; known positive receipts cannot be replaced by a generic continue. */
public final class CompassRecoveryModule implements RecoveryModule {
    private final CompassSearchService service;
    public CompassRecoveryModule(CompassSearchService service) { this.service = Objects.requireNonNull(service); }
    @Override public List<JsonObject> rows(Workspace workspace, Actor actor, UUID taskId) { return service.recoveryRows(workspace, actor, taskId); }
    @Override public Preview preview(AgentTool.Context context, UUID operationId) throws Exception {
        var review = service.recoveryPreview(context, operationId); var args = new JsonObject();
        args.addProperty("reviewId", review.id().toString()); args.addProperty("reviewHash", review.hash());
        return new Preview("compass_search_reconcile", args, service.reconciliationReview(review));
    }
    @Override public JsonObject evidence(Workspace workspace, Actor actor, UUID operationId) { return service.recoveryResources(workspace, actor, operationId); }
    @Override public void validatePreview(Workspace workspace, Actor actor, UUID taskId, UUID operationId, JsonObject args) throws Exception {
        if (!args.keySet().equals(Set.of("reviewId", "reviewHash"))) throw new IllegalArgumentException("COMPASS_RECOVERY_ARGUMENTS_CHANGED");
        var review = service.loadReconciliation(workspace, actor, UUID.fromString(args.get("reviewId").getAsString()), args.get("reviewHash").getAsString());
        if (!review.taskId().equals(taskId) || !review.operationId().equals(operationId)) throw new SecurityException("COMPASS_TASK_MISMATCH");
        service.validateReconciliationVersion(workspace, actor, review);
    }
    @Override public boolean settled(Workspace workspace, Actor actor, UUID operationId) { return service.operationSettled(workspace, actor, operationId); }
    @Override public String settledStatus() { return "RECONCILED_SAVED_COMPASS_RESULT"; }
    @Override public Optional<Boolean> settledCall(Workspace workspace, Actor actor, UUID taskId, ModelMessage.ToolCall call) {
        return Set.of("compass_search_execute", "compass_prepared_search_execute", "compass_search_reconcile").contains(call.name())
                ? Optional.of(service.settledCall(workspace, actor, taskId, call)) : Optional.empty();
    }
}
