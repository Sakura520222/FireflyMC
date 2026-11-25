package firefly520.fireflymc.ai.integration.maid;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.recovery.RecoveryModule;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** Adds combat to the existing MAID_RECONCILE workbench route without another conversation or command. */
public final class MaidCombatRecoveryModule implements RecoveryModule {
    private final MaidCombatService service;
    private final RecoveryModule existing;
    public MaidCombatRecoveryModule(MaidCombatService service, RecoveryModule existing) {
        this.service = Objects.requireNonNull(service); this.existing = Objects.requireNonNull(existing);
    }
    @Override public List<JsonObject> rows(Workspace workspace, Actor actor, UUID task) throws Exception {
        var rows = new ArrayList<>(existing.rows(workspace, actor, task)); rows.addAll(service.recoveryRows(workspace, actor, task)); return List.copyOf(rows);
    }
    @Override public Preview preview(AgentTool.Context ctx, UUID operation) throws Exception {
        if (!service.ownsOperation(ctx.workspace(), ctx.actor(), operation)) return existing.preview(ctx, operation);
        var review = service.recoveryPreview(ctx, operation); var args = new JsonObject();
        args.addProperty("reviewId", review.id().toString()); args.addProperty("reviewHash", review.hash());
        return new Preview("maid_combat_reconcile", args, service.reconciliationReview(review));
    }
    @Override public JsonObject evidence(Workspace workspace, Actor actor, UUID operation) throws Exception {
        return service.ownsOperation(workspace, actor, operation) ? service.recoveryResources(workspace, actor, operation) : existing.evidence(workspace, actor, operation);
    }
    @Override public void validatePreview(Workspace workspace, Actor actor, UUID task, UUID operation, JsonObject args) throws Exception {
        if (!service.ownsOperation(workspace, actor, operation)) { existing.validatePreview(workspace, actor, task, operation, args); return; }
        if (!args.keySet().equals(Set.of("reviewId", "reviewHash"))) throw new SecurityException("MAID_COMBAT_RECOVERY_ARGUMENTS_CHANGED");
        var review = service.loadReconciliation(workspace, actor, UUID.fromString(args.get("reviewId").getAsString()), args.get("reviewHash").getAsString());
        if (!review.taskId().equals(task) || !review.operationId().equals(operation)) throw new SecurityException("MAID_COMBAT_RECOVERY_IDENTITY_CHANGED");
        service.validateReconciliationVersion(workspace, actor, review);
    }
    @Override public boolean settled(Workspace workspace, Actor actor, UUID operation) throws Exception {
        return service.ownsOperation(workspace, actor, operation) ? service.operationSettled(workspace, actor, operation) : existing.settled(workspace, actor, operation);
    }
    @Override public String settledStatus() { return "RECONCILED_CURRENT_STATE"; }
    @Override public Optional<Boolean> settledCall(Workspace workspace, Actor actor, UUID task, ModelMessage.ToolCall call) throws Exception {
        var result = service.settledCall(workspace, actor, task, call); return result.isPresent() ? result : existing.settledCall(workspace, actor, task, call);
    }
}
