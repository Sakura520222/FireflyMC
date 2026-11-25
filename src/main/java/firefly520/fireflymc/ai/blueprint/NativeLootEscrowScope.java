package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.material.MaterialStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import java.util.*;

/**
 * Custody of actual, post-event native loot for an approved virtual worker. Nothing is credited
 * until the caller journals the returned stacks and proves the corresponding world removal.
 * Cancelled drop events never reach this hook; all other native after-break callbacks still run.
 */
public final class NativeLootEscrowScope implements AutoCloseable {
    private static final ThreadLocal<NativeLootEscrowScope> ACTIVE = new ThreadLocal<>();
    private final ServerPlayer owner;
    private final Set<BlockPos> positions;
    private final List<RemovalJournal.Drop> held = new ArrayList<>();
    private final Set<UUID> identities = new HashSet<>();
    private String unsupported = "";
    private NativeLootEscrowScope(ServerPlayer owner, Collection<BlockPos> positions) {
        if (!owner.server.isSameThread() || owner.isFakePlayer() || ACTIVE.get() != null || positions.isEmpty() || positions.size() > 128)
            throw new IllegalStateException("NATIVE_LOOT_SCOPE_INVALID");
        this.owner = owner; this.positions = positions.stream().map(BlockPos::immutable).collect(java.util.stream.Collectors.toUnmodifiableSet());
        ACTIVE.set(this);
    }
    public static NativeLootEscrowScope begin(ServerPlayer owner, Collection<BlockPos> positions) { return new NativeLootEscrowScope(owner, positions); }
    public List<RemovalJournal.Drop> held() { return List.copyOf(held); }
    public String unsupportedReason() { return unsupported; }
    /** A dedicated adapter calls this only after the complete native EntityJoinLevelEvent dispatch. */
    static boolean captureDirect(ServerPlayer owner, BlockPos position, ItemEntity entity) {
        var scope = ACTIVE.get();
        if (scope == null || scope.owner != owner || !scope.positions.contains(position)) return false;
        if (entity.getClass() != ItemEntity.class || entity.isRemoved() || entity.level() != owner.serverLevel() || scope.held.size() >= 128
                || scope.identities.contains(entity.getUUID()) || entity.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(position)) > 4) {
            scope.unsupported = "NATIVE_DIRECT_LOOT_UNVERIFIED"; return false;
        }
        try {
            var stack = ItemSnapshotCodec.capture(entity.getItem(), owner.registryAccess());
            if (!stack.empty()) scope.held.add(new RemovalJournal.Drop(entity.getUUID(), stack));
            scope.identities.add(entity.getUUID()); entity.getItem().setCount(0); entity.discard(); return true;
        } catch (RuntimeException failed) { scope.unsupported = "NATIVE_DIRECT_LOOT_UNVERIFIED"; return false; }
    }
    /** The returned empty list means custody, never a fictitious successful entity spawn. */
    public static List<ItemEntity> capture(BlockDropsEvent event, List<ItemEntity> drops) {
        var scope = ACTIVE.get(); if (scope == null) return drops;
        if (event.getLevel() != scope.owner.serverLevel() || !scope.positions.contains(event.getPos())
                || event.getBreaker() != null && event.getBreaker() != scope.owner) {
            scope.unsupported = "LOOT_OUTSIDE_APPROVED_ACTOR_OR_REGION"; return drops;
        }
        if (event.isCanceled()) return drops;
        // Experience has its own native orb/mending semantics; no unverified virtual XP refund.
        if (event.getDroppedExperience() != 0) { scope.unsupported = "NATIVE_LOOT_EXPERIENCE_ADAPTER_REQUIRED"; return drops; }
        if (scope.held.size() + drops.size() > 128) { scope.unsupported = "NATIVE_LOOT_CUSTODY_LIMIT"; return drops; }
        var snapshots = new ArrayList<RemovalJournal.Drop>();
        var entities = new HashSet<UUID>(); var stacks = Collections.newSetFromMap(new IdentityHashMap<ItemStack, Boolean>());
        try {
            for (var entity : drops) {
                if (entity == null || entity.isRemoved() || entity.level() != scope.owner.serverLevel()
                        || scope.owner.serverLevel().getEntity(entity.getUUID()) != null || !entities.add(entity.getUUID())
                        || scope.identities.contains(entity.getUUID()) || !stacks.add(entity.getItem())
                        || entity.position().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(event.getPos())) > 4)
                    throw new IllegalStateException("NATIVE_LOOT_IDENTITY_UNSUPPORTED");
                MaterialStack stack = ItemSnapshotCodec.capture(entity.getItem(), scope.owner.registryAccess());
                if (!stack.empty()) snapshots.add(new RemovalJournal.Drop(entity.getUUID(), stack));
            }
        } catch (RuntimeException | LinkageError unverified) { scope.unsupported = "NATIVE_LOOT_CUSTODY_UNVERIFIED"; return drops; }
        // Validate the entire batch first. Empty the original unspawned wrappers when custody moves,
        // so an event listener retaining a reference cannot later spawn the same native material.
        scope.held.addAll(snapshots); scope.identities.addAll(entities);
        for (var entity : drops) { entity.getItem().setCount(0); entity.discard(); }
        return List.of();
    }
    @Override public void close() { if (ACTIVE.get() == this) ACTIVE.remove(); }
}
