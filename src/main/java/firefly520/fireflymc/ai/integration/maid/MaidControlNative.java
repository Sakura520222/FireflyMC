package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.integration.equipment.ReadOnlyNativeAccess;
import firefly520.fireflymc.ai.material.NativeSaveBarrier;
import firefly520.fireflymc.ai.material.NativeSaveObserver;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

/** Exact server API for TLM 1.5.3; no packet fabrication, player movement, tool use or task-loop bypass. */
final class MaidControlNative {
    private static final String ROOT = "com.github.tartaricacid.touhoulittlemaid.";
    private static final String ENTITY = ROOT + "entity.passive.EntityMaid";
    private static final String TASK = ROOT + "api.task.IMaidTask";
    private static final String TASKS = ROOT + "entity.task.TaskManager";
    private static final String SCHEDULE = ROOT + "entity.ai.brain.MaidSchedule";
    private static final String CONFIG_PACKET = ROOT + "network.message.MaidConfigPackage";
    private final MaidCarrierBridge bridge;
    private final MaidEntityPermission permission;
    private final NativeSaveObserver saves;
    private final ReadOnlyNativeAccess api = new ReadOnlyNativeAccess();
    private Method nativeHome;
    private final java.util.Map<UUID, Handle> afterHandles = new java.util.HashMap<>();

    record Handle(TamableAnimal entity, Object task, MaidCarrierBridge.Binding binding, MaidControlPlan.NativeState state, long revision) {}
    record Outcome(boolean entered, boolean changed, boolean verified, MaidControlPlan.NativeState after,
                   String error, NativeSaveBarrier.Receipt save) {}

    MaidControlNative(MaidCarrierBridge bridge, MaidEntityPermission permission, NativeSaveObserver saves) {
        this.bridge = bridge; this.permission = permission; this.saves = saves;
    }
    Handle capture(ServerPlayer player, UUID owner) throws ReflectiveOperationException {
        var observed = bridge.validateBinding(player, owner);
        if (observed.binding() == null) throw new IllegalStateException(observed.reason());
        var binding = observed.binding();
        return capture(player, owner, binding.maidId(), binding, true);
    }
    Handle inspectRecovery(ServerPlayer player, UUID owner, UUID maidId) throws ReflectiveOperationException {
        var observed = bridge.inspectOwnedState(player, owner, maidId);
        if (observed.maid() == null) throw new IllegalStateException(observed.reason());
        return capture(player, owner, maidId, null, false);
    }
    private Handle capture(ServerPlayer player, UUID owner, UUID maidId, MaidCarrierBridge.Binding binding, boolean idleRequired) throws ReflectiveOperationException {
        var entity = player.serverLevel().getEntity(maidId);
        permission.requireControl(player, owner, entity);
        if (!(entity instanceof TamableAnimal maid) || !entity.getClass().getName().equals(ENTITY))
            throw new IllegalStateException("MAID_CONTROL_ENTITY_UNSUPPORTED");
        Object task = api.query(maid, ENTITY, "getTask", api.type(TASK));
        Object idle = api.query(null, TASKS, "getIdleTask", api.type(TASK));
        if (idleRequired && (task != idle || !task.getClass().getName().equals(ROOT + "entity.task.TaskIdle")))
            throw new IllegalStateException("MAID_CONTROL_NATIVE_TASK_BUSY_OR_REPLACED");
        Object schedule = api.query(maid, ENTITY, "getSchedule", api.type(SCHEDULE));
        if (!(schedule instanceof Enum<?> scheduleValue)) throw new IllegalStateException("MAID_SCHEDULE_API_MISMATCH");
        BlockPos center = (BlockPos) api.query(maid, ENTITY, "getRestrictCenter", BlockPos.class);
        var state = new MaidControlPlan.NativeState(maid.getUUID(), player.serverLevel().dimension().location().toString(),
                ((net.minecraft.resources.ResourceLocation) api.query(task, TASK, "getUid", net.minecraft.resources.ResourceLocation.class)).toString(),
                task.getClass().getName(), binding == null ? 0 : binding.revision(),
                bool(maid, "isHomeModeEnable"), bool(maid, "isMaidInSittingPose"), bool(maid, "isPickup"), bool(maid, "isRideable"),
                scheduleValue.name(), center.getX(), center.getY(), center.getZ(),
                (float) api.query(maid, ENTITY, "getRestrictRadius", float.class),
                api.type(ENTITY).getField("guiOpening").getBoolean(maid), maid.getTarget() != null,
                maid.isSleeping(), maid.isPassenger(), maid.getPassengers().size(), bool(maid, "canBrainMoving"));
        if (idleRequired && state.busy()) throw new IllegalStateException("MAID_NATIVE_CONTROL_INTERRUPTED");
        return new Handle(maid, task, binding, state, MaidNativeRevision.revision(maid));
    }
    Outcome apply(ServerPlayer player, UUID owner, MaidControlPlan plan, Handle expected) {
        boolean entered = false;
        boolean changed = false;
        NativeSaveBarrier.Receipt save = null;
        MaidCarrierBridge.Lease lease = null;
        try {
            var current = capture(player, owner);
            requireSame(expected, current, plan.before());
            var acquired = bridge.acquireControlOperation(player, owner, plan.id());
            if (acquired.lease() == null) throw new IllegalStateException(acquired.reason());
            lease = acquired.lease();
            if (current.state().modeObserved(plan.mode())) return new Outcome(false, false, true, current.state(), "", null);
            var target = NativeSaveObserver.entity(current.entity());
            saves.requireCapacity(List.of(target));
            // These mode setters have no material/energy exemption. Native movement, hunger,
            // cooldowns, schedule and addons continue through their ordinary AI tick.
            entered = true;
            if (plan.mode() == MaidControlPlan.Mode.FOLLOW) {
                disableHome(player, current.entity(), current.state());
                api.call(current.entity(), ENTITY, "setInSittingPose", void.class, new Class<?>[] {boolean.class}, false);
                current.entity().getNavigation().stop();
            } else {
                api.call(current.entity(), ENTITY, "setInSittingPose", void.class, new Class<?>[] {boolean.class}, true);
                current.entity().getNavigation().stop();
                current.entity().getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
                current.entity().setTarget(null);
            }
            changed = true;
            var after = capture(player, owner);
            if (after.entity() != current.entity() || after.task() != current.task() || !after.state().modeObserved(plan.mode()))
                return new Outcome(true, true, false, after.state(), "MAID_NATIVE_MODE_NOT_OBSERVED", saves.changedOrUnconfirmed(List.of(target)));
            save = MaidControlSaveWitness.observe(current.entity(), owner, after.state(), saves, plan.id(), plan.executionEpoch());
            afterHandles.put(plan.id(), after);
            return new Outcome(true, true, save != null, after.state(), save == null ? "MAID_NATIVE_SAVE_UNCOVERED" : "", save);
        } catch (Exception | LinkageError failure) {
            if (entered && save == null && expected.entity().level() == player.serverLevel())
                save = saves.changedOrUnconfirmed(List.of(NativeSaveObserver.entity(expected.entity())));
            return new Outcome(entered, changed, false, null, code(failure), save);
        } finally {
            if (lease != null) try { bridge.releaseRecordedOperation(owner, lease); }
            catch (RuntimeException interrupted) { /* Authentication cleanup must not discard accepted native evidence. */ }
        }
    }
    static void requireSame(Handle expected, Handle current, MaidControlPlan.NativeState approved) {
        if (expected.entity() != current.entity() || expected.task() != current.task() || expected.revision() != current.revision() || !approved.equals(current.state()))
            throw new IllegalStateException("MAID_CONTROL_PREVIEW_CHANGED");
    }
    Handle takeAfter(UUID operation) { return afterHandles.remove(operation); }
    private void disableHome(ServerPlayer player, TamableAnimal maid, MaidControlPlan.NativeState before) throws ReflectiveOperationException {
        if (!before.home()) return;
        Class<?> packet = api.type(CONFIG_PACKET), schedule = api.type(SCHEDULE);
        Object currentSchedule = api.query(maid, ENTITY, "getSchedule", schedule);
        Object request = packet.getConstructor(int.class, boolean.class, boolean.class, boolean.class, schedule)
                .newInstance(maid.getId(), false, before.pickup(), before.rideable(), currentSchedule);
        if (nativeHome == null) {
            nativeHome = packet.getDeclaredMethod("handleHome", packet, ServerPlayer.class, api.type(ENTITY));
            if (!nativeHome.trySetAccessible()) throw new IllegalStateException("MAID_HOME_NATIVE_API_INACCESSIBLE");
        }
        try { nativeHome.invoke(null, request, player, maid); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw failure;
        }
    }
    private boolean bool(Object maid, String method) throws ReflectiveOperationException {
        return (boolean) api.query(maid, ENTITY, method, boolean.class);
    }
    private static String code(Throwable failure) {
        String message = failure.getMessage();
        return message != null && message.matches("[A-Z0-9_]{1,100}") ? message : "MAID_NATIVE_CONTROL_FAILED";
    }
}
