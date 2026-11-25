package firefly520.fireflymc.ai.integration.trees;

import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** New approval for positive saved history only; never a native harvest or pickup instruction. */
public record TreeHarvestReconciliation(int version, UUID id, UUID operationId, String owner, UUID taskId,
                                         UUID server, UUID conversation, long privacyEpoch, UUID executionEpoch,
                                         long operationRevision, String operationDigest, long expiresAt,
                                         List<Residual> residuals, String hash) {
    public record Residual(UUID entityId, MaterialStack stack, double x, double y, double z) { }
    public TreeHarvestReconciliation {
        residuals = List.copyOf(residuals);
        if (version != 1 || id == null || operationId == null || owner == null || taskId == null || server == null || conversation == null || executionEpoch == null
                || operationRevision < 1 || operationDigest == null || !operationDigest.matches("[0-9a-f]{64}") || hash == null
                || residuals.size() > 1024 || residuals.stream().map(Residual::entityId).distinct().count() != residuals.size()) throw new IllegalArgumentException("TREE_RECONCILIATION_INVALID");
    }
    public TreeHarvestReconciliation signed() { return withHash(AiJson.digest(AiJson.canonical(withHash("")))); }
    public void requireIdentity(Workspace workspace, Actor actor, UUID epoch) {
        actor.requireAccess(owner); actor.requireAccess(workspace.owner());
        if (!server.equals(workspace.metadata().serverInstanceId()) || !conversation.equals(workspace.primaryConversationId()) || privacyEpoch != workspace.conversation().privacyEpoch()
                || !executionEpoch.equals(epoch) || System.currentTimeMillis() > expiresAt || !signed().hash().equals(hash)) throw new IllegalStateException("TREE_RECONCILIATION_CHANGED_OR_EXPIRED");
    }
    public void requireCurrent(TreeHarvestJournal.Operation operation) {
        if (!operationId.equals(operation.plan().id()) || !taskId.equals(operation.plan().taskId()) || operationRevision != operation.revision()
                || !operationDigest.equals(AiJson.digest(AiJson.canonical(operation))) || !operation.savedKnownTerminal()
                || operation.state() != TreeHarvestNative.State.RECOVERY_REQUIRED) throw new IllegalStateException("TREE_POSITIVE_SAVED_RESULT_REQUIRED");
    }
    private TreeHarvestReconciliation withHash(String value) { return new TreeHarvestReconciliation(version, id, operationId, owner, taskId, server, conversation,
            privacyEpoch, executionEpoch, operationRevision, operationDigest, expiresAt, residuals, value); }
}
