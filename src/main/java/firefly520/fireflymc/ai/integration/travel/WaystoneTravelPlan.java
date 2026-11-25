package firefly520.fireflymc.ai.integration.travel;

import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** Immutable native route/fee contract. It is neither a coordinate teleport nor a fee exemption. */
public record WaystoneTravelPlan(int version, UUID id, UUID serverInstanceId, UUID conversationId,
        String owner, UUID taskId, long privacyEpoch, UUID executionEpoch, Stone source, Stone target,
        List<Long> destinationChunks, List<String> flags, String requirements, List<String> feeDescription,
        boolean affordableAtPreview, String hash) {
    public static final String ADAPTER = "waystones@21.1.37/balm@21.0.63:ordinary-activated-source-v1";
    public record Stone(UUID id, String dimension, int x, int y, int z, String type, String name, String blockState) {
        public Stone {
            Objects.requireNonNull(id);
            if (!resource(dimension) || !resource(type) || name == null || name.length() > 256 || blockState == null
                    || blockState.isBlank() || blockState.length() > 4096 || Math.abs((long) x) > 30_000_000
                    || Math.abs((long) z) > 30_000_000 || y < -2048 || y > 2048) throw new IllegalArgumentException("WAYSTONE_IDENTITY_INVALID");
        }
    }
    public WaystoneTravelPlan {
        Objects.requireNonNull(id); Objects.requireNonNull(serverInstanceId); Objects.requireNonNull(conversationId);
        UUID.fromString(owner); Objects.requireNonNull(taskId); Objects.requireNonNull(executionEpoch);
        Objects.requireNonNull(source); Objects.requireNonNull(target);
        destinationChunks = List.copyOf(destinationChunks); flags = List.copyOf(flags); feeDescription = List.copyOf(feeDescription);
        if (version != 1 || privacyEpoch < 0 || source.id().equals(target.id()) || destinationChunks.isEmpty()
                || destinationChunks.size() > 3 || new HashSet<>(destinationChunks).size() != destinationChunks.size()
                || flags.size() > 32 || flags.stream().anyMatch(flag -> !resource(flag))
                || requirements == null || requirements.length() > 65536 || feeDescription.size() > 32
                || feeDescription.stream().anyMatch(text -> text == null || text.length() > 1024)
                || hash == null || !hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("WAYSTONE_TRAVEL_PLAN_INVALID");
        AiJson.object(requirements, 65536, 24);
    }
    public WaystoneTravelPlan signed() { return withHash(AiJson.digest(ADAPTER + ":" + AiJson.canonical(withHash("")))); }
    public boolean intact() { return hash.equals(AiJson.digest(ADAPTER + ":" + AiJson.canonical(withHash("")))); }
    private WaystoneTravelPlan withHash(String value) {
        return new WaystoneTravelPlan(version, id, serverInstanceId, conversationId, owner, taskId, privacyEpoch,
                executionEpoch, source, target, destinationChunks, flags, requirements, feeDescription, affordableAtPreview, value);
    }
    public void requireIdentity(Workspace workspace, Actor actor, UUID currentEpoch) {
        requireStorageIdentity(workspace, actor);
        if (!executionEpoch.equals(currentEpoch)) throw new SecurityException("WAYSTONE_TRAVEL_PLAN_CHANGED");
    }
    public void requireStorageIdentity(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner()); actor.requireAccess(owner);
        if (!intact() || !serverInstanceId.equals(workspace.metadata().serverInstanceId())
                || !conversationId.equals(workspace.primaryConversationId()) || privacyEpoch != workspace.conversation().privacyEpoch())
            throw new SecurityException("WAYSTONE_TRAVEL_PLAN_CHANGED");
    }
    private static boolean resource(String value) { return value != null && value.length() <= 512 && value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"); }
}
