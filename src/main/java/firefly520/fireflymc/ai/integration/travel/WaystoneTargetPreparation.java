package firefly520.fireflymc.ai.integration.travel;

import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** Approval for only the native small destination-chunk preparation, never a travel/fee approval. */
public record WaystoneTargetPreparation(int version, UUID id, UUID serverInstanceId, UUID conversationId,
        String owner, UUID taskId, long privacyEpoch, UUID executionEpoch,
        WaystoneTravelPlan.Stone source, Target target, List<Long> chunks, String loaderPolicy, String hash) {
    public record Target(UUID id, String dimension, int x, int y, int z, String type, String name) {
        public Target { Objects.requireNonNull(id); if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || !"waystones:waystone".equals(type) || name == null || name.length() > 256 || Math.abs((long) x) > 30_000_000
                || Math.abs((long) z) > 30_000_000 || y < -2048 || y > 2048) throw new IllegalArgumentException("WAYSTONE_PREPARATION_TARGET_INVALID"); }
    }
    public WaystoneTargetPreparation {
        Objects.requireNonNull(id); Objects.requireNonNull(serverInstanceId); Objects.requireNonNull(conversationId); UUID.fromString(owner);
        Objects.requireNonNull(taskId); Objects.requireNonNull(executionEpoch); Objects.requireNonNull(source); Objects.requireNonNull(target);
        chunks = List.copyOf(chunks);
        if (version != 1 || privacyEpoch < 0 || !"EXISTING_FULL_ONLY".equals(loaderPolicy) || source.id().equals(target.id()) || !chunks.equals(NativeWaystoneBridge.destinationChunks(
                new WaystoneTravelPlan.Stone(target.id(), target.dimension(), target.x(), target.y(), target.z(), target.type(), target.name(), "METADATA_ONLY_NOT_A_BLOCK_OBSERVATION")))
                || hash == null || !hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("WAYSTONE_PREPARATION_INVALID");
    }
    public WaystoneTargetPreparation signed() { return withHash(AiJson.digest("native-waystone-preparation-v1:" + AiJson.canonical(withHash("")))); }
    public boolean intact() { return hash.equals(AiJson.digest("native-waystone-preparation-v1:" + AiJson.canonical(withHash("")))); }
    private WaystoneTargetPreparation withHash(String value) { return new WaystoneTargetPreparation(version, id, serverInstanceId, conversationId,
            owner, taskId, privacyEpoch, executionEpoch, source, target, chunks, loaderPolicy, value); }
    public void requireIdentity(Workspace workspace, Actor actor, UUID epoch) {
        requireStorageIdentity(workspace, actor);
        if (!executionEpoch.equals(epoch)) throw new SecurityException("WAYSTONE_PREPARATION_CHANGED");
    }
    public void requireStorageIdentity(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner()); actor.requireAccess(owner);
        if (!intact() || !serverInstanceId.equals(workspace.metadata().serverInstanceId()) || !conversationId.equals(workspace.primaryConversationId())
                || privacyEpoch != workspace.conversation().privacyEpoch()) throw new SecurityException("WAYSTONE_PREPARATION_CHANGED");
    }
}
