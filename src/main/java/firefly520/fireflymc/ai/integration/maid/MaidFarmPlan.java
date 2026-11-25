package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.material.MaterialStack;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.util.*;
import net.minecraft.core.BlockPos;

/** One fixed native crop action. Real seeds and native check-rate/schedule rules are retained. */
public record MaidFarmPlan(int version, UUID id, String owner, UUID taskId, UUID serverInstanceId, UUID conversationId,
        long privacyEpoch, UUID executionEpoch, long generation, long expiresAt, Kind kind, int x, int y, int z,
        UUID sourceId, long sourceRevision, String sourceDigest, int seedSlot, MaidControlPlan.NativeState maid,
        BlockView crop, BlockView soil, List<MaterialStack> inventory, MaterialStack mainHand, MaterialStack offHand, String hash) {
    public enum Kind { HARVEST, PLANT }
    public record BlockView(String id, Map<String, String> properties) {
        public BlockView { Objects.requireNonNull(id); properties = Map.copyOf(properties); }
    }
    public MaidFarmPlan {
        Objects.requireNonNull(id); UUID.fromString(owner); Objects.requireNonNull(taskId); Objects.requireNonNull(serverInstanceId);
        Objects.requireNonNull(conversationId); Objects.requireNonNull(executionEpoch); Objects.requireNonNull(kind); Objects.requireNonNull(sourceId);
        Objects.requireNonNull(maid); Objects.requireNonNull(crop); Objects.requireNonNull(soil); inventory = List.copyOf(inventory);
        Objects.requireNonNull(mainHand); Objects.requireNonNull(offHand);
        if (version != 1 || privacyEpoch < 0 || generation < 0 || expiresAt < 1 || sourceRevision < 1 || seedSlot < -1
                || seedSlot >= inventory.size() || inventory.isEmpty() || inventory.size() > 36 || maid.busy() || maid.seated() || !maid.brainMoving()
                || !sourceDigest.matches("[0-9a-f]{64}") || hash == null || !hash.isEmpty() && !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("MAID_FARM_PLAN_INVALID");
        if (kind == Kind.PLANT && seedSlot < 0 || kind == Kind.HARVEST && seedSlot != -1) throw new IllegalArgumentException("MAID_FARM_SEED_SLOT_INVALID");
    }
    public BlockPos cropPos() { return new BlockPos(x, y, z); }
    public BlockPos soilPos() { return cropPos().below(); }
    public MaidFarmPlan signed() { return withHash(AiJson.digest(AiJson.canonical(withHash("")))); }
    public boolean intact() { return hash.equals(AiJson.digest(AiJson.canonical(withHash("")))); }
    private MaidFarmPlan withHash(String value) {
        return new MaidFarmPlan(version, id, owner, taskId, serverInstanceId, conversationId, privacyEpoch, executionEpoch, generation, expiresAt,
                kind, x, y, z, sourceId, sourceRevision, sourceDigest, seedSlot, maid, crop, soil, inventory, mainHand, offHand, value);
    }
    public void requireIdentity(Workspace workspace, Actor actor, UUID epoch) {
        actor.requireAccess(owner); actor.requireAccess(workspace.owner());
        if (!intact() || !serverInstanceId.equals(workspace.metadata().serverInstanceId()) || !conversationId.equals(workspace.primaryConversationId())
                || privacyEpoch != workspace.conversation().privacyEpoch()) throw new SecurityException("MAID_FARM_IDENTITY_CHANGED");
        if (!executionEpoch.equals(epoch)) throw new IllegalStateException("MAID_FARM_NEW_PREVIEW_REQUIRED");
    }
}
