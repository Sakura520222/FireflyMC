package firefly520.fireflymc.ai.integration.maid.transport;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.recovery.RecoveryModule;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** Workbench known-outcome reconciliation, even while the model scheduler is recovery fenced. */
public final class MaidTransportRecoveryModule implements RecoveryModule {
    private final MaidTransportService service;
    public MaidTransportRecoveryModule(MaidTransportService service) { this.service = Objects.requireNonNull(service); }
    @Override public List<JsonObject> rows(Workspace workspace, Actor actor, UUID task) { return service.recoveryRows(workspace, actor, task); }
    @Override public Preview preview(AgentTool.Context ctx, UUID operation) throws Exception {
        if (service.hasReturnableCustody(ctx.workspace(), ctx.actor(), operation)) {
            var plan = service.returnPreview(ctx, operation, 0); var args = new JsonObject();
            args.addProperty("returnId", plan.id().toString()); args.addProperty("returnHash", plan.hash());
            return new Preview("maid_transport_return", args, service.returnReview(ctx.workspace(), ctx.actor(), plan));
        }
        var review = service.recoveryPreview(ctx, operation); var args = new JsonObject();
        args.addProperty("reviewId", review.id().toString()); args.addProperty("reviewHash", review.hash());
        return new Preview("maid_transport_reconcile", args, service.reconciliationReview(ctx.workspace(), ctx.actor(), review));
    }
    @Override public JsonObject evidence(Workspace workspace, Actor actor, UUID operation) { return service.recoveryResources(workspace, actor, operation); }
    @Override public void validatePreview(Workspace workspace, Actor actor, UUID task, UUID operation, JsonObject args) {
        if (args.keySet().equals(Set.of("returnId", "returnHash"))) {
            var plan = service.loadReturn(workspace, actor, UUID.fromString(args.get("returnId").getAsString()), args.get("returnHash").getAsString());
            if (!plan.taskId().equals(task) || !plan.operationId().equals(operation)) throw new SecurityException("MAID_TRANSPORT_RECOVERY_IDENTITY_CHANGED");
            return;
        }
        if (!args.keySet().equals(Set.of("reviewId", "reviewHash"))) throw new SecurityException("MAID_TRANSPORT_RECOVERY_ARGUMENTS_CHANGED");
        var review = service.loadReconciliation(workspace, actor, UUID.fromString(args.get("reviewId").getAsString()), args.get("reviewHash").getAsString());
        if (!review.taskId().equals(task) || !review.operationId().equals(operation)) throw new SecurityException("MAID_TRANSPORT_RECOVERY_IDENTITY_CHANGED");
    }
    @Override public boolean settled(Workspace workspace, Actor actor, UUID operation) { return service.operationSettled(workspace, actor, operation); }
    @Override public String settledStatus() { return "SAVED"; }
    @Override public Optional<Boolean> settledCall(Workspace workspace, Actor actor, UUID task, ModelMessage.ToolCall call) {
        return service.settledCall(workspace, actor, task, call);
    }
}
