package firefly520.fireflymc.ai.blueprint;

import java.lang.reflect.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** One actual KleeSlabs callback, with a bound virtual hit and truthful unspawned-loot custody. */
public final class NativeKleeSlabScope implements AutoCloseable {
    private static final ThreadLocal<NativeKleeSlabScope> ACTIVE = new ThreadLocal<>();
    private record Api(Method player, Method pos, Method level, Method state, Method cancelled) {}
    private static Api api;
    private final ServerPlayer owner;
    private final BlockPos anchor;
    private final BlockState before, remaining;
    private final Runnable guard;
    private Object event;
    private ItemEntity candidate;
    private int addDepth;
    private boolean attempted, joined, cancelled, captured, wrote, completed, failed;

    private NativeKleeSlabScope(ServerPlayer owner, BlockPos anchor, BlockState before, BlockState remaining, Runnable guard) {
        if (!owner.server.isSameThread() || ACTIVE.get() != null) throw new IllegalStateException("KLEE_SCOPE_INVALID");
        this.owner = owner; this.anchor = anchor.immutable(); this.before = before; this.remaining = remaining; this.guard = guard;
        NativeRemovalProfiles.require(owner, anchor, before, remaining); bind(); ACTIVE.set(this);
    }
    public static NativeKleeSlabScope begin(ServerPlayer owner, BlockPos anchor, BlockState before, BlockState remaining, Runnable guard) {
        return new NativeKleeSlabScope(owner, anchor, before, remaining, guard);
    }
    private static void bind() {
        if (api != null) return;
        try {
            Class<?> type = Class.forName("net.blay09.mods.balm.api.event.BreakBlockEvent");
            api = new Api(type.getMethod("getPlayer"), type.getMethod("getPos"), type.getMethod("getLevel"), type.getMethod("getState"), type.getMethod("isCanceled"));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("KLEE_EVENT_CONTRACT_UNAVAILABLE", failure); }
    }
    private static Object read(Method method, Object object, Object... args) {
        try { return method.invoke(object, args); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("KLEE_EVENT_CONTRACT_FAILED", failure); }
    }
    /** False skips only this AI invocation when an earlier same-priority Balm protection handler denied it. */
    public static boolean enter(Object event) {
        var scope = ACTIVE.get(); if (scope == null) return true;
        if (scope.event != null || read(api.player(), event) != scope.owner || read(api.level(), event) != scope.owner.serverLevel()
                || !scope.anchor.equals(read(api.pos(), event)) || !scope.before.equals(read(api.state(), event))) {
            scope.fail(); throw new SecurityException("KLEE_CALLBACK_OUTSIDE_APPROVED_OPERATION");
        }
        if (Boolean.TRUE.equals(read(api.cancelled(), event))) return false;
        scope.guard.run(); scope.event = event; return true;
    }
    public static BlockHitResult hit(LivingEntity entity) {
        var scope = ACTIVE.get();
        if (scope != null && scope.event != null && entity == scope.owner)
            return new BlockHitResult(new Vec3(scope.anchor.getX() + .5, scope.anchor.getY()
                    + (scope.remaining.getValue(net.minecraft.world.level.block.SlabBlock.TYPE) == net.minecraft.world.level.block.state.properties.SlabType.TOP ? .25 : .75),
                    scope.anchor.getZ() + .5), Direction.UP, scope.anchor, false);
        return null;
    }
    /** Always invokes the real entity-add path and preserves its actual boolean return. */
    public static boolean spawn(Level level, Entity entity, java.util.function.BooleanSupplier original) {
        var scope = ACTIVE.get(); if (scope == null) return original.getAsBoolean();
        if (scope.event == null || scope.attempted || level != scope.owner.serverLevel() || entity.getClass() != ItemEntity.class) {
            scope.fail(); return original.getAsBoolean();
        }
        scope.attempted = true; scope.candidate = (ItemEntity) entity;
        try {
            boolean added = original.getAsBoolean();
            if (added || !scope.joined || !scope.cancelled && !scope.captured) scope.fail();
            return added;
        } finally { scope.candidate = null; }
    }
    public static void enteringAdd(Object entity, boolean existing) {
        var scope = ACTIVE.get(); if (scope == null) return;
        if (entity != scope.candidate) { scope.fail(); return; }
        scope.addDepth++; if (scope.addDepth != 1 || existing) scope.fail();
    }
    public static void leavingAdd(Object entity) {
        var scope = ACTIVE.get(); if (scope != null && entity == scope.candidate && scope.addDepth > 0) scope.addDepth--;
    }
    public static void joined(EntityJoinLevelEvent event) {
        var scope = ACTIVE.get(); if (scope == null || event.getEntity() != scope.candidate) return;
        if (scope.addDepth != 1 || scope.joined) { scope.fail(); return; }
        scope.joined = true; scope.cancelled = event.isCanceled();
    }
    /** Called after every entity listener, before native UUID/section registration. */
    public static boolean capture(Object entity, boolean knownUuid) {
        var scope = ACTIVE.get(); if (scope == null || entity != scope.candidate) return false;
        if (scope.failed || scope.addDepth != 1 || !scope.joined || scope.cancelled || knownUuid
                || !NativeLootEscrowScope.captureDirect(scope.owner, scope.anchor, scope.candidate)) { scope.fail(); return false; }
        scope.captured = true; return true;
    }
    public static boolean isExpectedCandidate(Entity entity) {
        var scope = ACTIVE.get(); return scope != null && scope.candidate == entity && scope.addDepth == 1 && !scope.failed;
    }
    public static boolean isCandidate(Object entity) { var scope = ACTIVE.get(); return scope != null && scope.candidate == entity; }
    /** Delegates the Mod's exact native write; it never invents an additional world operation. */
    public static boolean write(Level level, BlockPos position, BlockState target, int flags, java.util.function.BooleanSupplier original) {
        var scope = ACTIVE.get();
        if (scope != null) {
            if (scope.event == null || scope.wrote || level != scope.owner.serverLevel() || !scope.anchor.equals(position) || !scope.remaining.equals(target) || flags != 3) {
                scope.fail(); throw new SecurityException("KLEE_REMAINING_STATE_CHANGED");
            }
            scope.guard.run();
        }
        boolean wrote = original.getAsBoolean();
        if (scope != null) { scope.wrote = wrote; if (!wrote || !level.getBlockState(position).equals(scope.remaining)) scope.fail(); }
        return wrote;
    }
    public static void finished(Object event) {
        var scope = ACTIVE.get(); if (scope == null) return;
        scope.completed = scope.event == event && scope.wrote && Boolean.TRUE.equals(read(api.cancelled(), event))
                && scope.owner.serverLevel().getBlockState(scope.anchor).equals(scope.remaining);
        if (!scope.completed) scope.fail();
    }
    /** A later listener cannot turn the approved half-step into an additional vanilla whole-block break. */
    public static void outerResult(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        var scope = ACTIVE.get();
        if (scope == null || event.getPlayer() != scope.owner || !event.getPos().equals(scope.anchor) || event.getLevel() != scope.owner.serverLevel()) return;
        if (!event.isCanceled()) { scope.fail(); event.setCanceled(true); }
    }
    public boolean verified() { return !failed && completed && (!attempted || joined && (cancelled || captured)); }
    private void fail() { failed = true; NativeConstructionSave.unadaptedRemoval(); }
    @Override public void close() { if (ACTIVE.get() == this) ACTIVE.remove(); }
}
