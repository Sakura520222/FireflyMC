package firefly520.fireflymc.ai.integration.trees;

import firefly520.fireflymc.ai.blueprint.*;
import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** Approval binds the entire native impact envelope, held real tool, tree identity and selected region. */
public record TreeHarvestPlan(int version, UUID id, String owner, UUID server, UUID conversation, long privacyEpoch,
                              UUID executionEpoch, UUID taskId, long registryGeneration, long regionSequence, long expiresAt,
                              String dimension, BlueprintDocument.Position anchor, String face, String species, String family,
                              String branchClass, BlueprintDocument.Position root, int nativeNodes, MaterialStack tool,
                              String configuration, List<TreeHarvestNative.Cell> footprint, String hash) {
    public TreeHarvestPlan {
        footprint = List.copyOf(footprint);
        if (version != 1 || id == null || owner == null || server == null || conversation == null || executionEpoch == null || taskId == null
                || anchor == null || root == null || footprint.isEmpty() || footprint.size() > 16384 || nativeNodes < 1 || nativeNodes > 4096
                || tool == null || tool.empty() || configuration == null || !configuration.matches("[0-9a-f]{64}") || hash == null
                || footprint.stream().map(TreeHarvestNative.Cell::position).distinct().count() != footprint.size()) throw new IllegalArgumentException("TREE_PLAN_INVALID");
    }
    public TreeHarvestPlan signed() { return withHash(AiJson.digest(AiJson.canonical(withHash("")))); }
    public void requireIdentity(Workspace workspace, Actor actor, UUID epoch, long generation, long now) {
        actor.requireAccess(owner); actor.requireAccess(workspace.owner());
        if (!server.equals(workspace.metadata().serverInstanceId()) || !conversation.equals(workspace.primaryConversationId())
                || privacyEpoch != workspace.conversation().privacyEpoch() || !executionEpoch.equals(epoch) || registryGeneration != generation
                || regionSequence != workspace.conversation().regionSequence() || now > expiresAt
                || !hash.matches("[0-9a-f]{64}") || !signed().hash().equals(hash)) throw new IllegalStateException("TREE_PLAN_CHANGED_OR_EXPIRED");
    }
    private TreeHarvestPlan withHash(String value) {
        return new TreeHarvestPlan(version, id, owner, server, conversation, privacyEpoch, executionEpoch, taskId, registryGeneration,
                regionSequence, expiresAt, dimension, anchor, face, species, family, branchClass, root, nativeNodes, tool, configuration, footprint, value);
    }
}
