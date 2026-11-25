package firefly520.fireflymc.ai.blueprint.ownership;

import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.util.*;

/** Permanent causal history; reading this event never recreates runtime undo authority. */
public record BuildOwnershipEvent(int schema, UUID runtimeSession, UUID serverInstanceId, UUID conversationId,
        String owner, UUID sourceTaskId, UUID sourcePlanId, String sourcePlanHash, String dimension,
        UUID actionTaskId, UUID actionId, String action, long privacyEpoch, List<OwnershipToken> tokens,
        List<Long> retiredPositions, String reason) {
    public BuildOwnershipEvent {
        Objects.requireNonNull(runtimeSession); Objects.requireNonNull(serverInstanceId); Objects.requireNonNull(conversationId);
        Objects.requireNonNull(sourceTaskId); Objects.requireNonNull(sourcePlanId); Objects.requireNonNull(actionTaskId); Objects.requireNonNull(actionId);
        UUID.fromString(owner); tokens = List.copyOf(tokens); retiredPositions = List.copyOf(retiredPositions);
        if (schema != 1 || privacyEpoch < 0 || !Set.of("PLACE", "UNDO").contains(action)
                || sourcePlanHash == null || !sourcePlanHash.matches("[0-9a-f]{64}")
                || dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || reason == null || !reason.matches("[A-Za-z0-9_.:/-]{0,160}") || tokens.size() > 128 || retiredPositions.size() > 128)
            throw new IllegalArgumentException("BUILD_OWNERSHIP_EVENT_INVALID");
        for (var token : tokens) {
            var identity = token.owner();
            if (!token.runtimeSession().equals(runtimeSession) || !token.dimension().equals(dimension)
                    || !identity.serverInstanceId().equals(serverInstanceId) || !identity.player().toString().equals(owner)
                    || !identity.taskId().equals(sourceTaskId) || !identity.operationId().equals(sourcePlanId)
                    || !identity.stepId().equals(actionId) || !identity.planHash().equals(sourcePlanHash))
                throw new IllegalArgumentException("BUILD_OWNERSHIP_TOKEN_IDENTITY_MISMATCH");
        }
    }
    public BuildOwnershipEvent withReason(String value) {
        return new BuildOwnershipEvent(schema, runtimeSession, serverInstanceId, conversationId, owner, sourceTaskId, sourcePlanId,
                sourcePlanHash, dimension, actionTaskId, actionId, action, privacyEpoch, tokens, retiredPositions, value);
    }
    /** Called on the existing evidence-IO executor, after recording the real world/material facts. */
    public void append(Workspace workspace, Actor actor) throws IOException {
        synchronized (workspace.conversation()) {
            actor.requireAccess(workspace.owner());
            if (!owner.equals(workspace.owner()) || !serverInstanceId.equals(workspace.metadata().serverInstanceId())
                    || !conversationId.equals(workspace.primaryConversationId()) || privacyEpoch != workspace.conversation().privacyEpoch())
                throw new IllegalStateException("BUILD_OWNERSHIP_WORKSPACE_CHANGED");
            workspace.conversation().append(List.of(new Draft(actionTaskId, "build_ownership", null, AiJson.GSON.toJsonTree(this).getAsJsonObject(), null)));
        }
    }
}
