package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** Fixed target, native equipment and bounded strike budget. Gameplay costs are never waived. */
public record MaidCombatPlan(int version, UUID id, String owner, UUID taskId, UUID serverInstanceId, UUID conversationId,
        long privacyEpoch, UUID executionEpoch, long generation, long expiresAt, int maximumHits, long maximumTicks,
        MaidMeleeKernel.Snapshot before, String hash) {
    public MaidCombatPlan {
        Objects.requireNonNull(id); UUID.fromString(owner); Objects.requireNonNull(taskId); Objects.requireNonNull(serverInstanceId);
        Objects.requireNonNull(conversationId); Objects.requireNonNull(executionEpoch); Objects.requireNonNull(before);
        if (version != 1 || privacyEpoch < 0 || generation < 0 || expiresAt < 1 || maximumHits < 1 || maximumHits > 16
                || maximumTicks < 20 || maximumTicks > 2400 || before.target() == null || !before.target().alive()
                || before.maid().busy() || before.maid().seated() || !before.maid().brainMoving()
                || hash == null || !hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("MAID_COMBAT_PLAN_INVALID");
    }
    public UUID maidId() { return before.maid().maidId(); }
    public UUID targetId() { return before.target().id(); }
    public MaidCombatPlan signed() { return withHash(AiJson.digest(AiJson.canonical(withHash("")))); }
    public boolean intact() { return hash.equals(AiJson.digest(AiJson.canonical(withHash("")))); }
    private MaidCombatPlan withHash(String value) {
        return new MaidCombatPlan(version, id, owner, taskId, serverInstanceId, conversationId, privacyEpoch, executionEpoch,
                generation, expiresAt, maximumHits, maximumTicks, before, value);
    }
    public void requireStorageIdentity(Workspace workspace, Actor actor) {
        actor.requireAccess(owner); actor.requireAccess(workspace.owner());
        if (!intact() || !serverInstanceId.equals(workspace.metadata().serverInstanceId()) || !conversationId.equals(workspace.primaryConversationId())
                || privacyEpoch != workspace.conversation().privacyEpoch()) throw new SecurityException("MAID_COMBAT_IDENTITY_CHANGED");
    }
    public void requireIdentity(Workspace workspace, Actor actor, UUID epoch) {
        requireStorageIdentity(workspace, actor);
        if (!executionEpoch.equals(epoch)) throw new IllegalStateException("MAID_COMBAT_NEW_PREVIEW_REQUIRED");
    }
}
