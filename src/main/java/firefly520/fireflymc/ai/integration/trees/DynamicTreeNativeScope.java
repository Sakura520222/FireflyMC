package firefly520.fireflymc.ai.integration.trees;

import firefly520.fireflymc.ai.blueprint.NativePlacementKernel;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.ticks.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.bus.api.EventPriority;

/** Explicit handoff of this operation's native deferred work, never of manual player work. */
public final class DynamicTreeNativeScope implements AutoCloseable {
    public static final String OPERATION_TAG = "fireflymc_ai4_tree_operation";
    private static final ThreadLocal<DynamicTreeNativeScope> ACTIVE = new ThreadLocal<>();
    private static final Map<net.minecraft.server.MinecraftServer, Set<String>> SETTLED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<net.minecraft.server.MinecraftServer, Map<UUID, String>> OUTPUTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean listening;
    private final UUID operation;
    private final ServerPlayer owner;
    private final BlockPos anchor;
    private final BlockState original;
    private final BlockPos root;
    private final BlockState rootState;
    private final Set<BlockPos> approved;
    private final NativePlacementKernel.Guard guard;
    private final List<Object> deferred = new ArrayList<>();
    private final List<Entity> joins = new ArrayList<>();
    public record RootTick(UUID id, ScheduledTick<Block> tick, BlockState before) { }
    private final List<RootTick> rootTicks = new ArrayList<>();
    private String failure = "";

    private DynamicTreeNativeScope(UUID operation, ServerPlayer owner, BlockPos anchor, BlockState original, BlockPos root, BlockState rootState,
                                   Set<BlockPos> approved, NativePlacementKernel.Guard guard) {
        DynamicTreeAccess.thread(owner);
        if (ACTIVE.get() != null) throw new IllegalStateException("TREE_NATIVE_SCOPE_NESTED");
        install(); this.operation = operation; this.owner = owner; this.anchor = anchor; this.original = original;
        this.root = root.immutable(); this.rootState = rootState;
        this.approved = approved; this.guard = guard; ACTIVE.set(this);
    }
    public static DynamicTreeNativeScope begin(UUID operation, ServerPlayer owner, BlockPos anchor, BlockState original, BlockPos root, BlockState rootState,
                                               Set<BlockPos> approved, NativePlacementKernel.Guard guard) {
        return new DynamicTreeNativeScope(operation, owner, anchor, original, root, rootState, approved, guard);
    }
    public static boolean hooksAvailable() { return DynamicTreeHooks.available(); }
    /** Handoff only the actual same-world native one-tick root update caused inside this callback. */
    public static boolean captureRootTick(Object scheduler, ScheduledTick<?> requested) {
        var scope = ACTIVE.get(); if (scope == null || scheduler != scope.owner.serverLevel().getBlockTicks()) return false;
        if (!requested.pos().equals(scope.root) || requested.type() != scope.rootState.getBlock()) return false;
        var level = scope.owner.serverLevel();
        try {
            if (!DynamicTreeAccess.type("block.soil.SoilBlock").isInstance(requested.type())
                    || !scope.approved.contains(scope.root) || !level.getBlockState(scope.root).equals(scope.rootState)
                    || requested.triggerTick() != Math.addExact(level.getGameTime(), 1L) || requested.priority() != TickPriority.NORMAL)
                throw new SecurityException("TREE_ROOT_SCHEDULED_TICK_CHANGED");
            scope.guard.check(scope.owner, scope.root);
            // Native LevelChunkTicks deduplicates by type identity + position, retaining the
            // first scheduled request. Preserve that rule inside a single callback.
            if (scope.rootTicks.isEmpty()) {
                @SuppressWarnings("unchecked") var exact = (ScheduledTick<Block>) requested;
                scope.rootTicks.add(new RootTick(UUID.randomUUID(), exact, scope.rootState));
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            scope.failure = "TREE_ROOT_TICK_HANDOFF_UNCERTAIN";
            throw new SecurityException(scope.failure, failure);
        }
    }
    /** Actual DT collision candidates, before handleMotion mutates any candidate's velocity/health. */
    public static void beforeFallingCollisions(Entity tree, List<? extends net.minecraft.world.entity.LivingEntity> targets) {
        if (!pending(tree)) return;
        var scope = ACTIVE.get();
        if (scope == null || !allowsTick(tree) || targets == null || targets.size() > 256)
            throw new SecurityException("TREE_FALLING_COLLISION_SCOPE_REQUIRED");
        for (var target : targets) {
            if (target != scope.owner || target.level() != tree.level() || !target.isAlive()) {
                scope.failure = "TREE_UNAPPROVED_LIVING_COLLISION";
                throw new SecurityException(scope.failure);
            }
            // The fixed plan explicitly discloses this owner's normal tree-damage risk.
            // No immunity, artificial invulnerability or forced velocity/health is installed.
            scope.guard.check(scope.owner, target.blockPosition());
        }
    }
    public static void settled(net.minecraft.server.MinecraftServer server, UUID id) {
        synchronized (SETTLED) { SETTLED.computeIfAbsent(server, ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(id.toString()); }
        synchronized (OUTPUTS) { var outputs = OUTPUTS.get(server); if (outputs != null) outputs.values().removeIf(value -> value.equals(id.toString())); }
    }
    /** Exact durable native UUIDs restore only quarantine, including a lost final tag-clear ACK. */
    public static void restoreOutputs(net.minecraft.server.MinecraftServer server, UUID operation, Collection<UUID> entities) {
        synchronized (OUTPUTS) {
            var outputs = OUTPUTS.computeIfAbsent(server, ignored -> new java.util.concurrent.ConcurrentHashMap<>());
            if (outputs.size() + entities.size() > 131072) throw new IllegalStateException("TREE_OUTPUT_QUARANTINE_LIMIT");
            for (UUID id : entities) {
                String previous = outputs.putIfAbsent(id, operation.toString());
                if (previous != null && !previous.equals(operation.toString())) throw new IllegalStateException("TREE_OUTPUT_IDENTITY_COLLISION");
            }
        }
    }
    public static String operation(Entity entity) {
        var outputs = OUTPUTS.get(entity.getServer()); String indexed = outputs == null ? null : outputs.get(entity.getUUID());
        return indexed == null ? entity.getPersistentData().getString(OPERATION_TAG) : indexed;
    }
    public static boolean pending(Entity entity) {
        if (entity.level().isClientSide) return false;
        String operation = operation(entity); if (operation.isEmpty()) return false;
        var completed = SETTLED.get(entity.getServer());
        return completed == null || !completed.contains(operation);
    }
    /** The native onDestroyedByPlayer return is false even after accepting a FutureBreak. */
    public static boolean captureDeferred(Object nativeJob) {
        var scope = ACTIVE.get(); if (scope == null) return false;
        try {
            Class<?> type = nativeJob.getClass();
            if (!type.getName().equals("com.dtteam.dynamictrees.systems.FutureBreak")
                    || type.getField("entity").get(nativeJob) != scope.owner || type.getField("level").get(nativeJob) != scope.owner.serverLevel()
                    || !scope.anchor.equals(type.getField("pos").get(nativeJob)) || !scope.original.equals(type.getField("state").get(nativeJob))
                    || type.getField("ticks").getInt(nativeJob) != 0 || !scope.deferred.isEmpty()) {
                scope.failure = "TREE_UNEXPECTED_DEFERRED_ACTION"; return true;
            }
            scope.deferred.add(nativeJob); return true;
        } catch (ReflectiveOperationException | RuntimeException invalid) { scope.failure = "TREE_DEFERRED_CONTRACT_CHANGED"; return true; }
    }
    /** Called by the existing global before-write hook, in addition to its footprint check. */
    public static void beforeChange(net.minecraft.server.level.ServerLevel level, BlockPos pos) {
        var scope = ACTIVE.get(); if (scope == null) return;
        if (level != scope.owner.serverLevel() || !scope.approved.contains(pos)) {
            scope.failure = "TREE_UNAPPROVED_NATIVE_WRITE"; throw new SecurityException(scope.failure);
        }
        scope.guard.check(scope.owner, pos);
    }
    /** Persisted pending trees remain frozen after restart until a specific recovery reconciles them. */
    public static boolean allowsTick(Entity tree) {
        if (!pending(tree)) return true;
        var scope = ACTIVE.get();
        return scope != null && tree.level() == scope.owner.serverLevel()
                && scope.operation.toString().equals(operation(tree));
    }
    public static boolean allowsPickup(ItemEntity item, net.minecraft.world.entity.player.Player player) {
        if (!pending(item)) return true;
        var scope = ACTIVE.get(); return scope != null && scope.owner == player && allowsTick(item);
    }
    public static boolean isExpectedCandidate(Entity entity) {
        var scope = ACTIVE.get(); return scope != null && scope.joins.stream().anyMatch(candidate -> candidate == entity);
    }
    private static synchronized void install() {
        if (listening) return;
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, DynamicTreeNativeScope::joining);
        listening = true;
    }
    private static void joining(EntityJoinLevelEvent event) {
        var scope = ACTIVE.get(); if (scope == null || event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        boolean expected = entity.getClass().getName().equals(DynamicTreeAccess.FALLING_TREE) || entity.getClass() == ItemEntity.class;
        if (!expected || event.getLevel() != scope.owner.serverLevel() || scope.joins.size() >= 1024 || !scope.approved.contains(entity.blockPosition())) {
            event.setCanceled(true); scope.failure = "TREE_UNAPPROVED_NATIVE_ENTITY"; return;
        }
        scope.guard.check(scope.owner, entity.blockPosition());
        entity.getPersistentData().putString(OPERATION_TAG, scope.operation.toString());
        restoreOutputs(scope.owner.server, scope.operation, List.of(entity.getUUID()));
        scope.joins.add(entity);
    }
    public List<Object> deferred() { return List.copyOf(deferred); }
    public List<RootTick> rootTicks() { return List.copyOf(rootTicks); }
    public List<Entity> joined() { return List.copyOf(joins); }
    public String failure() { return failure; }
    @Override public void close() { if (ACTIVE.get() == this) ACTIVE.remove(); }
}
