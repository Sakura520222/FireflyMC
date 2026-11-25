package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.Objects;
import java.util.UUID;

/** An approval changes one existing owned maid's native mode; it never proves arrival or world work. */
public record MaidControlPlan(int version, UUID id, String owner, UUID taskId, UUID serverInstanceId,
        UUID conversationId, long privacyEpoch, UUID executionEpoch, long expiresAt,
        Mode mode, NativeState before, String hash) {
    public enum Mode { FOLLOW, STAY }
    public record NativeState(UUID maidId, String dimension, String taskId, String taskClass, long bindingRevision,
            boolean home, boolean seated, boolean pickup, boolean rideable, String schedule,
            int restrictX, int restrictY, int restrictZ, float restrictRadius,
            boolean guiOpening, boolean combat, boolean sleeping, boolean passenger, int passengers, boolean brainMoving) {
        public NativeState {
            Objects.requireNonNull(maidId); Objects.requireNonNull(dimension); Objects.requireNonNull(taskId);
            Objects.requireNonNull(taskClass); Objects.requireNonNull(schedule);
            if (bindingRevision < 0 || !Float.isFinite(restrictRadius) || passengers < 0)
                throw new IllegalArgumentException("MAID_NATIVE_STATE_INVALID");
        }
        public boolean busy() { return guiOpening || combat || sleeping || passenger || passengers > 0; }
        public boolean modeObserved(Mode mode) { return mode == Mode.STAY ? seated : !seated && !home && brainMoving; }
    }
    public MaidControlPlan {
        Objects.requireNonNull(id); Objects.requireNonNull(taskId); Objects.requireNonNull(serverInstanceId);
        Objects.requireNonNull(conversationId); Objects.requireNonNull(executionEpoch); Objects.requireNonNull(mode); Objects.requireNonNull(before);
        UUID.fromString(owner);
        if (version != 1 || privacyEpoch < 0 || expiresAt < 1 || before.busy() || before.bindingRevision() < 1 || hash == null
                || !hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("MAID_CONTROL_PLAN_INVALID");
    }
    public MaidControlPlan signed() { return withHash(AiJson.digest(AiJson.canonical(withHash("")))); }
    public boolean intact() { return hash.equals(AiJson.digest(AiJson.canonical(withHash("")))); }
    private MaidControlPlan withHash(String value) {
        return new MaidControlPlan(version, id, owner, taskId, serverInstanceId, conversationId, privacyEpoch,
                executionEpoch, expiresAt, mode, before, value);
    }
    public void requireIdentity(Workspace workspace, Actor actor, UUID epoch) {
        requireStorageIdentity(workspace, actor);
        if (!executionEpoch.equals(epoch)) throw new IllegalStateException("MAID_CONTROL_NEW_PREVIEW_REQUIRED");
    }
    public void requireStorageIdentity(Workspace workspace, Actor actor) {
        actor.requireAccess(owner); actor.requireAccess(workspace.owner());
        if (!intact() || !serverInstanceId.equals(workspace.metadata().serverInstanceId())
                || !conversationId.equals(workspace.primaryConversationId()) || privacyEpoch != workspace.conversation().privacyEpoch())
            throw new SecurityException("MAID_CONTROL_IDENTITY_CHANGED");
    }
}
