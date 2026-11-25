package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** Explicit current-state audit of a fully observed native crop footprint, never a callback replay/refund. */
public record MaidFarmReconcile(int version, UUID id, String owner, UUID taskId, UUID serverInstanceId, UUID conversationId,
        long privacyEpoch, UUID executionEpoch, long expiresAt, UUID operationId, long operationRevision, String operationDigest,
        MaidControlPlan.NativeState maid, String backpackId, List<MaterialStack> inventory, MaterialStack hand, MaterialStack offHand,
        MaidFarmPlan.BlockView crop, MaidFarmPlan.BlockView soil, String hash) {
    public MaidFarmReconcile {
        Objects.requireNonNull(id); UUID.fromString(owner); Objects.requireNonNull(taskId); Objects.requireNonNull(serverInstanceId);
        Objects.requireNonNull(conversationId); Objects.requireNonNull(executionEpoch); Objects.requireNonNull(operationId); Objects.requireNonNull(maid);
        Objects.requireNonNull(backpackId); inventory = List.copyOf(inventory); Objects.requireNonNull(hand); Objects.requireNonNull(offHand);
        Objects.requireNonNull(crop); Objects.requireNonNull(soil);
        if (version != 1 || privacyEpoch < 0 || expiresAt < 1 || operationRevision < 1 || id.equals(operationId) || inventory.isEmpty() || inventory.size() > 36
                || operationDigest == null || !operationDigest.matches("[0-9a-f]{64}") || hash == null || !hash.isEmpty() && !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("MAID_FARM_RECONCILE_INVALID");
    }
    public MaidFarmReconcile signed() { return withHash(AiJson.digest(AiJson.canonical(withHash("")))); }
    public boolean intact() { return hash.equals(AiJson.digest(AiJson.canonical(withHash("")))); }
    private MaidFarmReconcile withHash(String value) {
        return new MaidFarmReconcile(version, id, owner, taskId, serverInstanceId, conversationId, privacyEpoch, executionEpoch, expiresAt,
                operationId, operationRevision, operationDigest, maid, backpackId, inventory, hand, offHand, crop, soil, value);
    }
    public void requireStorageIdentity(Workspace workspace, Actor actor) {
        actor.requireAccess(owner); actor.requireAccess(workspace.owner());
        if (!intact() || !serverInstanceId.equals(workspace.metadata().serverInstanceId()) || !conversationId.equals(workspace.primaryConversationId())
                || privacyEpoch != workspace.conversation().privacyEpoch()) throw new SecurityException("MAID_FARM_RECONCILE_IDENTITY_CHANGED");
    }
    public void requireIdentity(Workspace workspace, Actor actor, UUID epoch) {
        requireStorageIdentity(workspace, actor); if (!executionEpoch.equals(epoch)) throw new IllegalStateException("MAID_FARM_RECONCILE_NEW_PREVIEW_REQUIRED");
    }
}
