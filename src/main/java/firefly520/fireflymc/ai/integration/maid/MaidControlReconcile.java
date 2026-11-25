package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.Objects;
import java.util.UUID;

/** Fresh current-state verification only. It never authorizes reissuing the original native setter. */
public record MaidControlReconcile(int version, UUID id, String owner, UUID taskId, UUID serverInstanceId,
        UUID conversationId, long privacyEpoch, UUID executionEpoch, long expiresAt, UUID operationId,
        long operationRevision, String operationDigest, MaidControlPlan.NativeState observed, String hash) {
    public MaidControlReconcile {
        Objects.requireNonNull(id); UUID.fromString(owner); Objects.requireNonNull(taskId); Objects.requireNonNull(serverInstanceId);
        Objects.requireNonNull(conversationId); Objects.requireNonNull(executionEpoch); Objects.requireNonNull(operationId); Objects.requireNonNull(observed);
        if (version != 1 || privacyEpoch < 0 || expiresAt < 1 || operationRevision < 1 || id.equals(operationId)
                || operationDigest == null || !operationDigest.matches("[0-9a-f]{64}") || hash == null
                || !hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("MAID_RECONCILE_INVALID");
    }
    public MaidControlReconcile signed() { return withHash(AiJson.digest(AiJson.canonical(withHash("")))); }
    public boolean intact() { return hash.equals(AiJson.digest(AiJson.canonical(withHash("")))); }
    private MaidControlReconcile withHash(String value) {
        return new MaidControlReconcile(version, id, owner, taskId, serverInstanceId, conversationId, privacyEpoch, executionEpoch,
                expiresAt, operationId, operationRevision, operationDigest, observed, value);
    }
    public void requireIdentity(Workspace workspace, Actor actor, UUID epoch) {
        requireStorageIdentity(workspace, actor);
        if (!executionEpoch.equals(epoch)) throw new IllegalStateException("MAID_RECONCILE_NEW_PREVIEW_REQUIRED");
    }
    public void requireStorageIdentity(Workspace workspace, Actor actor) {
        actor.requireAccess(owner); actor.requireAccess(workspace.owner());
        if (!intact() || !serverInstanceId.equals(workspace.metadata().serverInstanceId())
                || !conversationId.equals(workspace.primaryConversationId()) || privacyEpoch != workspace.conversation().privacyEpoch())
            throw new SecurityException("MAID_RECONCILE_IDENTITY_CHANGED");
    }
}
