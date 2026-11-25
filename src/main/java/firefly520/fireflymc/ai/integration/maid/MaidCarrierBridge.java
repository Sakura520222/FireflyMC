package firefly520.fireflymc.ai.integration.maid;

import static firefly520.fireflymc.ai.integration.equipment.ReadOnlyNativeAccess.require;

import firefly520.fireflymc.ai.integration.equipment.OwnedPlayerAccess;
import firefly520.fireflymc.ai.integration.equipment.ReadOnlyNativeAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;

/**
 * Binds an existing owned maid as an optional companion carrier. The logical workbench companion
 * remains independent. This bridge never spawns, teleports, equips, changes native tasks, chats to
 * another AI, or executes world actions. Its leases arbitrate FireflyMC operations only.
 */
public final class MaidCarrierBridge {
    private static final String MAID = "com.github.tartaricacid.touhoulittlemaid.";
    private static final String ENTITY = MAID + "entity.passive.EntityMaid";
    private static final String VERSION = "1.5.3-neoforge+mc1.21.1";
    private static final String EVIDENCE = "ev_344bd5be90e87b35324405c3901b72074ef856cd37a3905c6817fb48c19653e2";
    private static final Map<String, Integer> BACKPACK_SLOTS = Map.of(
            "EmptyBackpack", 6, "SmallBackpack", 12, "MiddleBackpack", 24, "BigBackpack", 36,
            "CraftingTableBackpack", 18, "EnderChestBackpack", 6, "FurnaceBackpack", 18, "TankBackpack", 18);
    private final OwnedPlayerAccess access;
    private final ReadOnlyNativeAccess nativeAccess = new ReadOnlyNativeAccess();
    private final CarrierBindingRegistry registry = new CarrierBindingRegistry();

    public record Binding(UUID owner, UUID maidId, String dimension, String nativeTaskId, long revision) {}
    public record Lease(UUID leaseId, UUID owner, UUID maidId, UUID operationId, long bindingRevision) {
        public boolean worldActionAuthorized() { return false; }
        public boolean nativeAiExclusivelyLocked() { return false; }
    }
    public record InventorySlot(int slot, String backendId, ReadOnlyNativeAccess.ItemObservation item) {
        public boolean consumptionAuthorized() { return false; }
    }
    public record MaidView(UUID maidId, String dimension, BlockPos position, String nativeTaskId,
                           boolean nativeIdle, boolean combat, boolean seated, String backpackType,
                           String inventoryState, List<InventorySlot> inventory) {
        public MaidView {
            position = position.immutable();
            inventory = List.copyOf(inventory);
        }
        public boolean behaviorVerified() { return false; }
    }
    public record Result(String reason, ReadOnlyNativeAccess.AdapterStatus adapter,
                         MaidView maid, Binding binding, Lease lease) {
        public boolean behaviorVerified() { return false; }
        public boolean workbenchRequiresCarrier() { return false; }
    }
    /** Trusted native handle, never a wire payload or an authorization by itself. */
    public record BackpackSource(LivingEntity entity, UUID owner, UUID maidId, String dimension,
                                 String nativeTaskId, String backpackType, int slots, IItemHandler inventory) {
        public String revisionFingerprint() {
            return ReadOnlyNativeAccess.digest(owner + "\n" + maidId + "\n" + dimension + "\n"
                    + nativeTaskId + "\n" + backpackType + "\n" + slots);
        }
    }
    private record NativeMaid(LivingEntity entity, CarrierBindingRegistry.Observation observation) {}

    public MaidCarrierBridge(MinecraftServer server, Predicate<ServerPlayer> authenticated) {
        access = new OwnedPlayerAccess(server, authenticated);
    }

    /**
     * Called for an independently confirmed material-source grant, not by carrier binding.
     * Native work/combat changes stop borrowing. The server-thread transfer is the only mutation;
     * this does not pretend to lock native AI across ticks or switch its task to idle.
     */
    public BackpackSource backpackSource(ServerPlayer player, UUID workspaceOwner, UUID maidId) {
        access.require(player, workspaceOwner);
        var status = profile();
        require(status.state() == ReadOnlyNativeAccess.State.STATIC_API_MATCH, status.reason());
        try {
            NativeMaid maid = resolve(player, maidId);
            var state = maid.observation();
            require(state.idle() && !state.combat() && !state.seated(), "NATIVE_MAID_BUSY");
            Object backpack = nativeAccess.query(maid.entity(), ENTITY, "getMaidBackpackType",
                    nativeAccess.type(MAID + "api.backpack.IMaidBackpack"));
            String type = backpack == null ? "" : backpack.getClass().getName();
            Integer count = BACKPACK_SLOTS.get(type.startsWith(MAID + "entity.backpack.")
                    ? type.substring((MAID + "entity.backpack.").length()) : "");
            require(count != null, "UNREVIEWED_MAID_BACKPACK");
            Object handler = nativeAccess.query(maid.entity(), ENTITY, "getAvailableBackpackInv", CombinedInvWrapper.class);
            require(handler instanceof IItemHandler && handler.getClass().getName().equals(MAID + "inventory.handler.MaidInvWrapper"),
                    "MAID_INVENTORY_API_MISMATCH");
            IItemHandler inventory = (IItemHandler) handler;
            require(inventory.getSlots() == count, "MAID_BACKPACK_LAYOUT_CHANGED");
            return new BackpackSource(maid.entity(), workspaceOwner, maidId, state.dimension(),
                    state.taskId(), type, count, inventory);
        } catch (ReadOnlyNativeAccess.ReadRefused failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new ReadOnlyNativeAccess.ReadRefused("MAID_BACKPACK_API_MISMATCH");
        }
    }

    /** An exact selected UUID only: no nearby-entity scan, chunk loading, or other owner's inventory read. */
    public Result inspectOwned(ServerPlayer player, UUID workspaceOwner, UUID maidId) {
        return inspectOwned(player, workspaceOwner, maidId, true);
    }
    public Result inspectOwnedState(ServerPlayer player, UUID workspaceOwner, UUID maidId) {
        return inspectOwned(player, workspaceOwner, maidId, false);
    }
    private Result inspectOwned(ServerPlayer player, UUID workspaceOwner, UUID maidId, boolean inventory) {
        String refused = access.refusal(player, workspaceOwner);
        if (refused != null) return result(refused, absent(), null, null, null);
        var status = profile();
        if (status.state() != ReadOnlyNativeAccess.State.STATIC_API_MATCH) return result(status.reason(), status, null, null, null);
        try {
            NativeMaid maid = resolve(player, maidId);
            return result("OWN_MAID_OBSERVED", status.with(ReadOnlyNativeAccess.State.OBSERVED, "EXISTING_OWN_MAID"),
                    inventory ? view(maid) : header(maid), registry.binding(workspaceOwner), null);
        } catch (ReadOnlyNativeAccess.ReadRefused refusal) {
            return result(refusal.getMessage(), status.with(ReadOnlyNativeAccess.State.REFUSED, refusal.getMessage()), null, null, null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return result("MAID_READ_API_MISMATCH", status.with(ReadOnlyNativeAccess.State.API_MISMATCH, "MAID_READ_API_MISMATCH"),
                    null, null, null);
        }
    }

    /** Invoke only for an explicit own-workbench carrier selection; this changes FireflyMC session state only. */
    public Result bindOwned(ServerPlayer player, UUID workspaceOwner, UUID maidId) {
        String refused = access.refusal(player, workspaceOwner);
        if (refused != null) return result(refused, absent(), null, null, null);
        var status = profile();
        if (status.state() != ReadOnlyNativeAccess.State.STATIC_API_MATCH) return result(status.reason(), status, null, null, null);
        try {
            NativeMaid maid = resolve(player, maidId);
            Binding binding = registry.bind(workspaceOwner, maid.observation());
            return result("CARRIER_BOUND_READ_ONLY", status.with(ReadOnlyNativeAccess.State.OBSERVED, "OWNERSHIP_OBSERVED"),
                    header(maid), binding, null);
        } catch (ReadOnlyNativeAccess.ReadRefused refusal) {
            return result(refusal.getMessage(), status.with(ReadOnlyNativeAccess.State.REFUSED, refusal.getMessage()), null, null, null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return result("MAID_BIND_API_MISMATCH", status.with(ReadOnlyNativeAccess.State.API_MISMATCH, "MAID_BIND_API_MISMATCH"),
                    null, null, null);
        }
    }

    public Result validateBinding(ServerPlayer player, UUID workspaceOwner) {
        String refused = access.refusal(player, workspaceOwner);
        if (refused != null) return result(refused, absent(), null, null, null);
        Binding binding = registry.binding(workspaceOwner);
        if (binding == null) return result("CARRIER_NOT_BOUND", profile(), null, null, null);
        var status = profile();
        if (status.state() != ReadOnlyNativeAccess.State.STATIC_API_MATCH) {
            registry.invalidate(workspaceOwner);
            return result(status.reason(), status, null, null, null);
        }
        try {
            NativeMaid maid = resolve(player, binding.maidId());
            String changed = registry.validate(workspaceOwner, maid.observation());
            return result(changed == null ? "CARRIER_BINDING_CURRENT" : changed,
                    status.with(ReadOnlyNativeAccess.State.OBSERVED, "OWNERSHIP_OBSERVED"),
                    changed == null ? header(maid) : null, changed == null ? binding : null, null);
        } catch (ReadOnlyNativeAccess.ReadRefused refusal) {
            registry.invalidate(workspaceOwner);
            return result(refusal.getMessage(), status.with(ReadOnlyNativeAccess.State.REFUSED, refusal.getMessage()), null, null, null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            registry.invalidate(workspaceOwner);
            return result("MAID_BINDING_INVALIDATED", status.with(ReadOnlyNativeAccess.State.API_MISMATCH, "MAID_READ_API_MISMATCH"),
                    null, null, null);
        }
    }

    /**
     * Reserves this carrier against other FireflyMC operations while the observed native task is
     * idle. It does not stop native AI or grant equipment, materials, land, or world permissions.
     */
    public Result acquireOperation(ServerPlayer player, UUID workspaceOwner, UUID operationId) {
        return acquireOperation(player, workspaceOwner, operationId, false);
    }

    /** Mode changes still need their separate native-control approval and entity permission. */
    public Result acquireControlOperation(ServerPlayer player, UUID workspaceOwner, UUID operationId) {
        return acquireOperation(player, workspaceOwner, operationId, true);
    }

    private Result acquireOperation(ServerPlayer player, UUID workspaceOwner, UUID operationId, boolean control) {
        Result current = validateBinding(player, workspaceOwner);
        if (current.binding() == null) return current;
        try {
            NativeMaid maid = resolve(player, current.binding().maidId());
            Lease lease = control ? registry.acquireControl(workspaceOwner, operationId, maid.observation())
                    : registry.acquire(workspaceOwner, operationId, maid.observation());
            return result("FIREFLY_OPERATION_LEASE_ONLY", current.adapter(), header(maid), current.binding(), lease);
        } catch (ReadOnlyNativeAccess.ReadRefused refusal) {
            return result(refusal.getMessage(), current.adapter(), null, current.binding(), null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            registry.invalidate(workspaceOwner);
            return result("MAID_LEASE_FAILED", current.adapter(), null, null, null);
        }
    }

    /** Must run immediately before each future operation; a prior lease is never an authorization cache. */
    public Result validateOperation(ServerPlayer player, UUID workspaceOwner, Lease lease) {
        String refused = access.refusal(player, workspaceOwner);
        if (refused != null) return result(refused, absent(), null, null, null);
        var status = profile();
        if (status.state() != ReadOnlyNativeAccess.State.STATIC_API_MATCH) {
            registry.invalidate(workspaceOwner);
            return result(status.reason(), status, null, null, null);
        }
        try {
            require(lease != null && workspaceOwner.equals(lease.owner()), "CARRIER_LEASE_MISMATCH");
            NativeMaid maid = resolve(player, lease.maidId());
            String changed = registry.validateLease(workspaceOwner, lease, maid.observation());
            return result(changed == null ? "FIREFLY_OPERATION_LEASE_CURRENT" : changed,
                    status.with(ReadOnlyNativeAccess.State.OBSERVED, "OWNERSHIP_OBSERVED"),
                    changed == null ? header(maid) : null, registry.binding(workspaceOwner), changed == null ? lease : null);
        } catch (ReadOnlyNativeAccess.ReadRefused refusal) {
            registry.invalidate(workspaceOwner);
            return result(refusal.getMessage(), status.with(ReadOnlyNativeAccess.State.REFUSED, refusal.getMessage()), null, null, null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            registry.invalidate(workspaceOwner);
            return result("MAID_LEASE_INVALIDATED", status.with(ReadOnlyNativeAccess.State.API_MISMATCH, "MAID_READ_API_MISMATCH"),
                    null, null, null);
        }
    }

    public boolean releaseOperation(ServerPlayer player, UUID workspaceOwner, Lease lease) {
        access.require(player, workspaceOwner);
        return registry.release(workspaceOwner, lease);
    }
    /** Trusted lifecycle cleanup only. Releasing this exact session token grants no native authority. */
    public boolean releaseRecordedOperation(UUID workspaceOwner, Lease lease) {
        access.requireServerThread(); return registry.release(workspaceOwner, lease);
    }

    public void unbindOwn(ServerPlayer player, UUID workspaceOwner) {
        access.require(player, workspaceOwner);
        registry.invalidate(workspaceOwner);
    }

    /** Event cleanup only. Persist a desired carrier UUID in its own workspace outside this session bridge. */
    public void disconnected(UUID owner) {
        access.requireServerThread();
        registry.invalidate(Objects.requireNonNull(owner));
    }

    private NativeMaid resolve(ServerPlayer player, UUID id) throws ReflectiveOperationException {
        require(id != null, "MAID_ID_REQUIRED");
        Entity entity = player.serverLevel().getEntity(id);
        require(entity != null && !entity.isRemoved() && entity.isAlive(), "MAID_NOT_LOADED");
        require(entity.getClass().getName().equals(ENTITY) && entity instanceof TamableAnimal, "UNSUPPORTED_MAID_ENTITY");
        TamableAnimal maid = (TamableAnimal) entity;
        // Owner checks precede task, position, inventory and any addon data observations.
        require(maid.isTame() && maid.isOwnedBy(player) && player.getUUID().equals(maid.getOwnerUUID()), "MAID_OWNER_MISMATCH");
        require(maid.level() == player.serverLevel() && maid.distanceToSqr(player) <= 64 * 64, "MAID_OUTSIDE_ACCESS_RANGE");
        Object task = nativeAccess.query(maid, ENTITY, "getTask", nativeAccess.type(MAID + "api.task.IMaidTask"));
        require(task != null, "MAID_TASK_UNAVAILABLE");
        String taskId = ((ResourceLocation) nativeAccess.query(task, MAID + "api.task.IMaidTask",
                "getUid", ResourceLocation.class)).toString();
        Object idle = nativeAccess.query(null, MAID + "entity.task.TaskManager", "getIdleTask",
                nativeAccess.type(MAID + "api.task.IMaidTask"));
        boolean combat = nativeAccess.query(maid, ENTITY, "getTarget", LivingEntity.class) != null;
        boolean seated = (boolean) nativeAccess.query(maid, ENTITY, "isMaidInSittingPose", boolean.class);
        return new NativeMaid(maid, new CarrierBindingRegistry.Observation(player.getUUID(), maid.getUUID(),
                player.serverLevel().dimension().location().toString(), taskId, true, task == idle, combat, seated));
    }

    private MaidView header(NativeMaid maid) {
        var state = maid.observation();
        return new MaidView(state.maid(), state.dimension(), maid.entity().blockPosition(), state.taskId(),
                state.idle(), state.combat(), state.seated(), "", "NOT_REQUESTED", List.of());
    }

    private MaidView view(NativeMaid maid) throws ReflectiveOperationException {
        var state = maid.observation();
        Object backpack = nativeAccess.query(maid.entity(), ENTITY, "getMaidBackpackType",
                nativeAccess.type(MAID + "api.backpack.IMaidBackpack"));
        String type = backpack == null ? "" : backpack.getClass().getName();
        Integer expected = BACKPACK_SLOTS.get(type.startsWith(MAID + "entity.backpack.")
                ? type.substring((MAID + "entity.backpack.").length()) : "");
        if (expected == null)
            return new MaidView(state.maid(), state.dimension(), maid.entity().blockPosition(), state.taskId(),
                    state.idle(), state.combat(), state.seated(), type, "UNREVIEWED_MAID_BACKPACK", List.of());
        Object handler = nativeAccess.query(maid.entity(), ENTITY, "getAvailableBackpackInv", CombinedInvWrapper.class);
        require(handler instanceof IItemHandler && handler.getClass().getName().equals(MAID + "inventory.handler.MaidInvWrapper"),
                "MAID_INVENTORY_API_MISMATCH");
        IItemHandler inventory = (IItemHandler) handler;
        require(inventory.getSlots() == expected, "MAID_BACKPACK_LAYOUT_CHANGED");
        List<InventorySlot> rows = new ArrayList<>();
        for (int slot = 0; slot < expected; slot++) {
            rows.add(new InventorySlot(slot, "maid/" + state.maid() + "/backpack/slot/" + slot,
                    ReadOnlyNativeAccess.item(inventory.getStackInSlot(slot))));
        }
        return new MaidView(state.maid(), state.dimension(), maid.entity().blockPosition(), state.taskId(),
                state.idle(), state.combat(), state.seated(), type, "OWN_BACKPACK_RANGE_OBSERVED", rows);
    }

    private ReadOnlyNativeAccess.AdapterStatus profile() {
        var status = nativeAccess.version("touhou_little_maid", VERSION, VERSION, EVIDENCE);
        if (status.state() != ReadOnlyNativeAccess.State.STATIC_API_MATCH) return status;
        for (String[] addon : List.of(new String[] {"touhou_little_maid_spell", "1.8.4-neoforge"},
                new String[] {"touhou_little_maid_vanilla_companion", "1.1.0"})) {
            var actual = nativeAccess.version(addon[0], addon[1], null, "");
            if (actual.state() != ReadOnlyNativeAccess.State.ABSENT
                    && actual.state() != ReadOnlyNativeAccess.State.STATIC_API_MATCH)
                return status.with(ReadOnlyNativeAccess.State.UNSUPPORTED_VERSION, "UNSUPPORTED_MAID_ADDON_VERSION");
        }
        return status;
    }

    private static ReadOnlyNativeAccess.AdapterStatus absent() {
        return new ReadOnlyNativeAccess.AdapterStatus("touhou_little_maid", "", ReadOnlyNativeAccess.State.REFUSED,
                "PLAYER_GATE_NOT_PASSED", EVIDENCE);
    }

    private static Result result(String reason, ReadOnlyNativeAccess.AdapterStatus status,
                                 MaidView maid, Binding binding, Lease lease) {
        return new Result(reason, status, maid, binding, lease);
    }
}
