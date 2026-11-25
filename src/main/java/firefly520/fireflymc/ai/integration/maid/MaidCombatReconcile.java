package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** New approval accepts current owned state only; historical hits, drops and unknown custody stay visible. */
public record MaidCombatReconcile(int version, UUID id, String owner, UUID taskId, UUID serverInstanceId, UUID conversationId,
        long privacyEpoch, UUID executionEpoch, long expiresAt, UUID operationId, long operationRevision, String operationDigest,
        MaidMeleeKernel.Snapshot current, String hash) {
    public MaidCombatReconcile {
        Objects.requireNonNull(id); UUID.fromString(owner); Objects.requireNonNull(taskId); Objects.requireNonNull(serverInstanceId);
        Objects.requireNonNull(conversationId); Objects.requireNonNull(executionEpoch); Objects.requireNonNull(operationId); Objects.requireNonNull(current);
        if (version != 1 || privacyEpoch < 0 || expiresAt < 1 || operationRevision < 1 || !operationDigest.matches("[0-9a-f]{64}")
                || hash == null || !hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("MAID_COMBAT_RECONCILE_INVALID");
    }
    public MaidCombatReconcile signed() { return withHash(AiJson.digest(AiJson.canonical(withHash("")))); }
    public boolean intact() { return hash.equals(AiJson.digest(AiJson.canonical(withHash("")))); }
    private MaidCombatReconcile withHash(String value) { return new MaidCombatReconcile(version, id, owner, taskId, serverInstanceId, conversationId, privacyEpoch,
            executionEpoch, expiresAt, operationId, operationRevision, operationDigest, current, value); }
    public void requireIdentity(Workspace workspace, Actor actor, UUID epoch) {
        actor.requireAccess(owner); actor.requireAccess(workspace.owner());
        if (!intact() || !serverInstanceId.equals(workspace.metadata().serverInstanceId()) || !conversationId.equals(workspace.primaryConversationId())
                || privacyEpoch != workspace.conversation().privacyEpoch() || !executionEpoch.equals(epoch)) throw new SecurityException("MAID_COMBAT_RECONCILE_IDENTITY_CHANGED");
    }
}
