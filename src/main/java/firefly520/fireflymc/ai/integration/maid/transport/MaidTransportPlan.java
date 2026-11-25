package firefly520.fireflymc.ai.integration.maid.transport;

import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;

/** One finite native wireless-IO pass, approved against exact inventories and the worn bauble. */
public record MaidTransportPlan(int version, UUID id, String owner, UUID taskId, UUID serverInstanceId,
        UUID conversationId, long privacyEpoch, UUID executionEpoch, long generation, long expiresAt,
        UUID maidId, long maidRevision, long bindingRevision, String dimension, int x, int y, int z,
        UUID maidGrant, long maidGrantRevision, UUID blockGrant, long blockGrantRevision,
        boolean maidToChest, String baubleFingerprint, int baubleSlot, boolean blacklist,
        List<Boolean> excludedSlots, List<MaterialStack> filters, List<MaterialStack> source,
        List<MaterialStack> destination, List<Integer> matchingSlots, List<Move> moves,
        List<MaterialStack> expectedSource, List<MaterialStack> expectedDestination, List<String> backendIds, String hash) {
    public record Placement(int slot, int count) {
        public Placement { if (slot < 0 || slot >= 130 || count < 1) throw new IllegalArgumentException("MAID_TRANSPORT_PLACEMENT_INVALID"); }
    }
    public record Move(int sourceSlot, MaterialStack stack, List<Placement> placements) {
        public Move {
            placements = List.copyOf(placements);
            if (sourceSlot < 0 || sourceSlot >= 130 || stack == null || stack.empty() || placements.isEmpty()
                    || placements.size() > 128 || placements.stream().mapToLong(Placement::count).sum() != stack.count())
                throw new IllegalArgumentException("MAID_TRANSPORT_MOVE_INVALID");
        }
    }
    public MaidTransportPlan {
        Objects.requireNonNull(id); Objects.requireNonNull(taskId); Objects.requireNonNull(serverInstanceId);
        Objects.requireNonNull(conversationId); Objects.requireNonNull(executionEpoch); Objects.requireNonNull(maidId);
        Objects.requireNonNull(maidGrant); Objects.requireNonNull(blockGrant); Objects.requireNonNull(dimension);
        UUID.fromString(owner); excludedSlots = List.copyOf(excludedSlots); filters = List.copyOf(filters);
        source = List.copyOf(source); destination = List.copyOf(destination); matchingSlots = List.copyOf(matchingSlots);
        moves = List.copyOf(moves); expectedSource = List.copyOf(expectedSource); expectedDestination = List.copyOf(expectedDestination);
        backendIds = List.copyOf(backendIds);
        if (version != 1 || privacyEpoch < 0 || generation < 0 || expiresAt < 1 || maidRevision < 0 || bindingRevision < 1
                || maidGrantRevision < 1 || blockGrantRevision < 1 || maidGrant.equals(blockGrant) || baubleSlot < 0 || baubleSlot >= 30
                || source.isEmpty() || destination.isEmpty() || source.size() > 130 || destination.size() > 130
                || expectedSource.size() != source.size() || expectedDestination.size() != destination.size()
                || matchingSlots.size() > 128 || matchingSlots.stream().distinct().count() != matchingSlots.size() || moves.size() > 128
                || filters.size() > 128 || excludedSlots.size() > 130 || backendIds.isEmpty() || backendIds.size() > 32
                || backendIds.stream().anyMatch(scope -> scope == null || scope.isBlank() || scope.length() > 1024)
                || backendIds.stream().distinct().count() != backendIds.size() || !baubleFingerprint.matches("[0-9a-f]{64}")
                || hash == null || !hash.isEmpty() && !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("MAID_TRANSPORT_PLAN_INVALID");
        MaidTransportConservation.require(source, destination, expectedSource, expectedDestination, List.of());
    }
    public MaidTransportPlan signed() { return withHash(AiJson.digest(AiJson.canonical(withHash("")))); }
    public boolean intact() { return hash.equals(AiJson.digest(AiJson.canonical(withHash("")))); }
    private MaidTransportPlan withHash(String value) {
        return new MaidTransportPlan(version, id, owner, taskId, serverInstanceId, conversationId, privacyEpoch, executionEpoch, generation,
                expiresAt, maidId, maidRevision, bindingRevision, dimension, x, y, z, maidGrant, maidGrantRevision, blockGrant,
                blockGrantRevision, maidToChest, baubleFingerprint, baubleSlot, blacklist, excludedSlots, filters, source, destination,
                matchingSlots, moves, expectedSource, expectedDestination, backendIds, value);
    }
    public void requireIdentity(Workspace workspace, Actor actor, UUID epoch, long currentGeneration) {
        requireStoredIdentity(workspace, actor);
        if (!executionEpoch.equals(epoch) || generation != currentGeneration || System.currentTimeMillis() >= expiresAt)
            throw new IllegalStateException("MAID_TRANSPORT_NEW_PREVIEW_REQUIRED");
    }
    public void requireStoredIdentity(Workspace workspace, Actor actor) {
        actor.requireAccess(owner); actor.requireAccess(workspace.owner());
        if (!intact() || !serverInstanceId.equals(workspace.metadata().serverInstanceId()) || !conversationId.equals(workspace.primaryConversationId())
                || privacyEpoch != workspace.conversation().privacyEpoch()) throw new SecurityException("MAID_TRANSPORT_IDENTITY_CHANGED");
    }
}
