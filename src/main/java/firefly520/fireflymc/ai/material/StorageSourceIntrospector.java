package firefly520.fireflymc.ai.material;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

/**
 * Recognizes the inspected installed storage builds without linking optional mods at startup.
 *
 * <p>This is not an authorization service. The caller must have an explicit source grant before
 * probing, check protection for every returned physical member before every operation, and
 * re-probe immediately before using a handler. Returned handlers are live, main-thread-only
 * native references; a successful probe is neither a reservation nor runtime acceptance.
 * Discovery never extracts, inserts, initializes a backpack UUID, changes upgrades, or writes NBT.
 */
public final class StorageSourceIntrospector {
    private static final String SD = "com.jaquadro.minecraft.storagedrawers.";
    private static final String CORE = "net.p3pp3rf1y.sophisticatedcore.";
    private static final String STORAGE = "net.p3pp3rf1y.sophisticatedstorage.";
    private static final String BP = "net.p3pp3rf1y.sophisticatedbackpacks.";
    private static final int MAX_SLOTS = 8192;
    private static final int MAX_MEMBERS = 128;
    private static final int MAX_UPGRADES = 64;
    private static final Set<String> SAFE_UPGRADE_ITEMS = Set.of(
            CORE + "upgrades.filter.FilterUpgradeItem", CORE + "upgrades.stack.StackUpgradeItem");
    private static final Set<String> SAFE_UPGRADE_WRAPPERS = Set.of(
            CORE + "upgrades.filter.FilterUpgradeWrapper", CORE + "upgrades.stack.StackUpgradeItem$Wrapper");
    private final Reflection reflection = new Reflection();

    public enum Status { STATIC_API_MATCH, REFUSED, UNSUPPORTED_SOURCE, UNSUPPORTED_VERSION, API_MISMATCH }

    public record Probe(Status status, String reason, String adapterId, String versionEvidence,
                        boolean behaviorVerified, List<SlotRef> slots) {
        public Probe {
            Objects.requireNonNull(status);
            Objects.requireNonNull(reason);
            Objects.requireNonNull(adapterId);
            Objects.requireNonNull(versionEvidence);
            if (behaviorVerified) throw new IllegalArgumentException("BEHAVIOR_NOT_VERIFIED");
            slots = List.copyOf(slots);
            if (status != Status.STATIC_API_MATCH && !slots.isEmpty())
                throw new IllegalArgumentException("REFUSED_PROBE_HAS_SLOTS");
        }
        public boolean callable() { return status == Status.STATIC_API_MATCH; }
        public boolean authorizationVerified() { return false; }
    }

    public record UpgradeEvidence(int slot, String wrapperType, boolean enabled, ItemStack snapshot) {
        public UpgradeEvidence { snapshot = snapshot.copy(); }
        @Override public ItemStack snapshot() { return snapshot.copy(); }
    }

    /** A complete physical fluid backend, not one independently drainable tank per displayed row. */
    public record FluidProbe(Status status, String reason, String backendId, String versionEvidence,
                             IFluidHandler handler, SourceEvidence evidence) {
        public boolean callable() { return status == Status.STATIC_API_MATCH; }
        public boolean authorizationVerified() { return false; }
        public boolean behaviorVerified() { return false; }
    }

    public FluidProbe probeItemBackpackFluids(ServerPlayer player, ItemHostSnapshot host) {
        String denied = playerGuard(player);
        if (denied != null) return fluidRefused(Status.REFUSED, denied, "");
        String version = "";
        try {
            require(host != null && host.owner.equals(player.getUUID()), "HOST_OWNER_MISMATCH");
            require(host.slot >= 0 && host.slot < boundedSlots(host.inventory), "HOST_SLOT_CHANGED");
            ItemStack current = host.inventory.getStackInSlot(host.slot);
            require(current == host.reference && sameStack(current, host.snapshot), "HOST_MOVED_OR_CHANGED");
            version = backpackVersions();
            UUID uuid = backpackPreflight(current);
            Object wrapper = existingBackpackWrapper(current);
            require(backpackStack(wrapper) == current, "BACKPACK_WRAPPER_HOST_MISMATCH");
            List<UpgradeEvidence> upgrades = safeUpgrades(wrapper, true);
            IFluidHandlerItem handler = current.getCapability(Capabilities.FluidHandler.ITEM);
            require(handler != null && handler.getClass().getName().equals(
                    BP + "backpack.wrapper.BackpackWrapper$FluidHandlerItemWrapper")
                    && handler.getContainer() == current, "EXTERNAL_FLUID_HANDLER_MISMATCH");
            // Verify the wrapper delegates to this exact initialized finite backend. A matching
            // count alone cannot distinguish a filtered/infinite/custom wrapper from this handler.
            Field delegate = handler.getClass().getDeclaredField("delegate");
            require(delegate.getType() == IFluidHandler.class && !Modifier.isStatic(delegate.getModifiers())
                    && delegate.trySetAccessible(), "FLUID_DELEGATE_SIGNATURE_MISMATCH");
            Object expected = ((java.util.Optional<?>) reflection.query(wrapper,
                    BP + "backpack.wrapper.BackpackWrapper", "getFluidHandler", java.util.Optional.class))
                    .orElseThrow(() -> new ProbeFailure(Status.REFUSED, "BACKPACK_HAS_NO_TANK"));
            require(delegate.get(handler) == expected, "FLUID_BACKEND_CHANGED");
            validateFluidBackend(player, wrapper, expected, handler);
            return new FluidProbe(Status.STATIC_API_MATCH, "", backpackBackend(uuid), version, handler,
                    evidence(null, current, uuid, host.locator, host.revision, version, upgrades));
        } catch (ProbeFailure failure) {
            return fluidRefused(failure.status, failure.getMessage(), version);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return fluidRefused(Status.API_MISMATCH, "FLUID_API_OR_STATE_MISMATCH", version);
        }
    }

    public FluidProbe probeBlockFluids(ServerPlayer player, ServerLevel level, BlockPos pos, Direction side) {
        String denied = actorGuard(player, level);
        if (denied != null) return fluidRefused(Status.REFUSED, denied, "");
        String version = "";
        try {
            require(pos != null && side != null && loaded(level, pos), "SOURCE_UNLOADED_OR_OUTSIDE_BOUNDS");
            BlockEntity source = level.getBlockEntity(pos);
            require(source != null && !source.isRemoved() && source.getClass().getName().equals(
                    BP + "backpack.BackpackBlockEntity"), "UNSUPPORTED_FLUID_SOURCE");
            version = backpackVersions();
            Object wrapper = reflection.query(source, BP + "backpack.BackpackBlockEntity",
                    "getBackpackWrapper", reflection.type(BP + "backpack.wrapper.IBackpackWrapper"));
            ItemStack host = backpackStack(wrapper);
            UUID uuid = backpackPreflight(host);
            require(existingBackpackWrapper(host) == wrapper, "BACKPACK_WRAPPER_NOT_INITIALIZED");
            List<UpgradeEvidence> upgrades = safeUpgrades(wrapper, true);
            IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
            require(handler != null, "EXTERNAL_FLUID_HANDLER_MISSING");
            Object expected = ((java.util.Optional<?>) reflection.query(wrapper,
                    BP + "backpack.wrapper.BackpackWrapper", "getFluidHandler", java.util.Optional.class))
                    .orElseThrow(() -> new ProbeFailure(Status.REFUSED, "BACKPACK_HAS_NO_TANK"));
            require(handler == expected, "FLUID_BACKEND_CHANGED");
            validateFluidBackend(player, wrapper, expected, handler);
            return new FluidProbe(Status.STATIC_API_MATCH, "", backpackBackend(uuid), version, handler,
                    evidence(source, host, uuid, blockBackend(level.dimension().location().toString(), pos),
                            0, version, upgrades));
        } catch (ProbeFailure failure) {
            return fluidRefused(failure.status, failure.getMessage(), version);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return fluidRefused(Status.API_MISMATCH, "FLUID_API_OR_STATE_MISMATCH", version);
        }
    }

    private void validateFluidBackend(ServerPlayer player, Object wrapper, Object backend, IFluidHandler handler)
            throws ReflectiveOperationException {
        require(backend.getClass().getName().equals(BP + "backpack.wrapper.BackpackFluidHandler"),
                "UNKNOWN_FLUID_BACKEND");
        Field owner = backend.getClass().getDeclaredField("backpackWrapper");
        require(owner.getType() == reflection.type(CORE + "api.IStorageWrapper")
                && !Modifier.isStatic(owner.getModifiers()) && owner.trySetAccessible()
                && owner.get(backend) == wrapper, "FLUID_BACKEND_OWNER_MISMATCH");
        // Distinct item hosts with the same UUID can retain independent, stale upgrade wrappers.
        // Verify against the shared native contents before accepting even a simulated balance.
        // Merely comparing this handler before/after its call would allow a stale wrapper to
        // overwrite another player's debit with its old amount.
        validateUpgradeCache(player, wrapper, "FLUID_BACKEND_CACHE_STALE");
        int tanks = handler.getTanks();
        require(tanks > 0 && tanks <= MAX_UPGRADES, "FLUID_TANK_LIMIT");
        for (int tank = 0; tank < tanks; tank++) {
            FluidStack fluid = handler.getFluidInTank(tank);
            int capacity = handler.getTankCapacity(tank);
            require(fluid != null && capacity > 0 && fluid.getAmount() >= 0 && fluid.getAmount() <= capacity,
                    "FLUID_TANK_STATE_INVALID");
        }
    }

    private static FluidProbe fluidRefused(Status status, String reason, String version) {
        return new FluidProbe(status, reason, "", version, null, null);
    }

    private void validateUpgradeCache(ServerPlayer player, Object wrapper, String reason) throws ReflectiveOperationException {
        Object upgrades = reflection.query(wrapper, CORE + "api.IStorageWrapper", "getUpgradeHandler",
                reflection.type(CORE + "upgrades.UpgradeHandler"));
        String key = (String) reflection.field(reflection.type(CORE + "upgrades.UpgradeHandler"),
                "UPGRADE_INVENTORY_TAG", String.class, true, false).get(null);
        validateNativeInventoryCache(player, upgrades, CORE + "upgrades.UpgradeHandler", key, reason);
    }

    private void validateBackpackItemCache(ServerPlayer player, Object wrapper, UUID uuid) throws ReflectiveOperationException {
        // Also proves the initialized handler points at the canonical SavedData UUID entry.
        backpackSaveStore(player, backpackStack(wrapper), backpackBackend(uuid));
        validateUpgradeCache(player, wrapper, "BACKPACK_UPGRADE_CACHE_STALE");
        Object raw = reflection.query(wrapper, CORE + "api.IStorageWrapper", "getInventoryHandler",
                reflection.type(CORE + "inventory.InventoryHandler"));
        validateNativeInventoryCache(player, raw, CORE + "inventory.InventoryHandler", "inventory", "BACKPACK_INVENTORY_CACHE_STALE");
    }

    private void validateNativeInventoryCache(ServerPlayer player, Object handler, String declaringType,
                                               String key, String reason) throws ReflectiveOperationException {
        Field field = reflection.type(declaringType).getDeclaredField("contentsNbt");
        require(field.getType() == net.minecraft.nbt.CompoundTag.class && !Modifier.isStatic(field.getModifiers())
                && field.trySetAccessible(), "NATIVE_INVENTORY_CONTENTS_SIGNATURE_MISMATCH");
        var shared = (net.minecraft.nbt.CompoundTag) field.get(handler);
        var live = (net.minecraft.nbt.CompoundTag) reflection.invoke(handler, "net.neoforged.neoforge.items.ItemStackHandler",
                "serializeNBT", net.minecraft.nbt.CompoundTag.class, new Class<?>[] { net.minecraft.core.HolderLookup.Provider.class }, player.registryAccess());
        require(shared != null && (shared.contains(key, net.minecraft.nbt.Tag.TAG_COMPOUND)
                ? sameNativeSlotEncoding(shared.getCompound(key), live)
                : !shared.contains(key) && nativeSlots(live).isEmpty()), reason);
    }

    /** ItemStackHandler reads slots by their explicit index; record order is not an inventory change. */
    static boolean sameNativeSlotEncoding(net.minecraft.nbt.CompoundTag left, net.minecraft.nbt.CompoundTag right) {
        return left.getInt("Size") == right.getInt("Size") && nativeSlots(left).equals(nativeSlots(right));
    }
    private static Map<Integer, net.minecraft.nbt.CompoundTag> nativeSlots(net.minecraft.nbt.CompoundTag data) {
        require(data.contains("Size", net.minecraft.nbt.Tag.TAG_INT) && data.getInt("Size") >= 0 && data.getInt("Size") <= MAX_SLOTS
                && data.contains("Items", net.minecraft.nbt.Tag.TAG_LIST) && data.getAllKeys().equals(Set.of("Size", "Items")),
                "NATIVE_INVENTORY_ENCODING_INVALID");
        var raw = (net.minecraft.nbt.ListTag) data.get("Items");
        require(raw != null && raw.size() <= MAX_SLOTS && (raw.isEmpty() || raw.getElementType() == net.minecraft.nbt.Tag.TAG_COMPOUND),
                "NATIVE_INVENTORY_ENCODING_INVALID");
        var slots = new HashMap<Integer, net.minecraft.nbt.CompoundTag>();
        for (int i = 0; i < raw.size(); i++) {
            var entry = raw.getCompound(i);
            require(entry.contains("Slot", net.minecraft.nbt.Tag.TAG_INT) && entry.getInt("Slot") >= 0
                    && entry.getInt("Slot") < data.getInt("Size") && slots.putIfAbsent(entry.getInt("Slot"), entry) == null,
                    "NATIVE_INVENTORY_SLOT_ENCODING_INVALID");
        }
        return slots;
    }

    /** Stable authorization shape. Only audited native fluid/render amounts may change under a grant. */
    public String fluidSourceFingerprint(ServerPlayer player, FluidProbe probe) {
        if (!probe.callable()) throw new IllegalStateException(probe.reason());
        String denied = playerGuard(player); if (denied != null) throw new IllegalStateException(denied);
        try {
            var data = new com.google.gson.JsonObject();
            ItemStack host = probe.evidence().hostSnapshot();
            host.remove(component(CORE + "init.ModCoreDataComponents", "RENDER_INFO_TAG"));
            data.addProperty("host", ItemSnapshotCodec.capture(host, player.registryAccess()).fingerprint());
            data.addProperty("version", probe.versionEvidence());
            var upgrades = new com.google.gson.JsonArray();
            for (var upgrade : probe.evidence().upgrades()) {
                var row = new com.google.gson.JsonObject();
                row.addProperty("slot", upgrade.slot()); row.addProperty("type", upgrade.wrapperType());
                row.addProperty("enabled", upgrade.enabled());
                ItemStack stack = upgrade.snapshot();
                if (upgrade.wrapperType().equals(CORE + "upgrades.tank.TankUpgradeWrapper"))
                    stack.remove(component(CORE + "init.ModCoreDataComponents", "FLUID_CONTENTS"));
                row.addProperty("stack", ItemSnapshotCodec.capture(stack, player.registryAccess()).fingerprint());
                upgrades.add(row);
            }
            data.add("upgrades", upgrades);
            var tanks = new com.google.gson.JsonArray();
            for (int i = 0; i < probe.handler().getTanks(); i++) tanks.add(probe.handler().getTankCapacity(i));
            data.add("capacities", tanks);
            return firefly520.fireflymc.ai.storage.AiJson.digest(data.toString());
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("FLUID_FINGERPRINT_API_MISMATCH", failure);
        }
    }

    /** Removes only audited presentation/fluid-balance fields from a detached authorization witness. */
    public ItemStack authorizationFingerprintStack(ServerPlayer player, ItemStack source, boolean host) {
        String denied = playerGuard(player); if (denied != null) throw new IllegalStateException(denied);
        ItemStack copy = source.copy();
        try {
            if (host) {
                require(source.getItem().getClass().getName().equals(BP + "backpack.BackpackItem"), "INVALID_BACKPACK_HOST");
                copy.remove(component(CORE + "init.ModCoreDataComponents", "RENDER_INFO_TAG"));
            } else if (source.getItem().getClass().getName().equals(CORE + "upgrades.tank.TankUpgradeItem")) {
                copy.remove(component(CORE + "init.ModCoreDataComponents", "FLUID_CONTENTS"));
            }
            return copy;
        } catch (ReflectiveOperationException | LinkageError failure) { throw new IllegalStateException("SOURCE_FINGERPRINT_API_MISMATCH", failure); }
    }

    public net.minecraft.world.level.saveddata.SavedData backpackFluidSaveStore(ServerPlayer player, FluidProbe probe) {
        var store = backpackSaveStore(player, probe.evidence().hostReference(), probe.backendId());
        try {
            Object wrapper = existingBackpackWrapper(probe.evidence().hostReference());
            Object upgrades = reflection.query(wrapper, CORE + "api.IStorageWrapper", "getUpgradeHandler",
                    reflection.type(CORE + "upgrades.UpgradeHandler"));
            Object inventory = reflection.query(wrapper, CORE + "api.IStorageWrapper", "getInventoryHandler",
                    reflection.type(CORE + "inventory.InventoryHandler"));
            Field upgradeNbt = reflection.type(CORE + "upgrades.UpgradeHandler").getDeclaredField("contentsNbt");
            Field inventoryNbt = reflection.type(CORE + "inventory.InventoryHandler").getDeclaredField("contentsNbt");
            Field owner = reflection.type(CORE + "upgrades.UpgradeHandler").getDeclaredField("storageWrapper");
            require(upgradeNbt.getType() == net.minecraft.nbt.CompoundTag.class
                    && inventoryNbt.getType() == net.minecraft.nbt.CompoundTag.class
                    && owner.getType() == reflection.type(CORE + "api.IStorageWrapper")
                    && !Modifier.isStatic(upgradeNbt.getModifiers()) && !Modifier.isStatic(inventoryNbt.getModifiers())
                    && !Modifier.isStatic(owner.getModifiers()) && upgradeNbt.trySetAccessible()
                    && inventoryNbt.trySetAccessible() && owner.trySetAccessible()
                    && owner.get(upgrades) == wrapper && upgradeNbt.get(upgrades) == inventoryNbt.get(inventory),
                    "FLUID_SAVE_CONTENTS_DETACHED");
            return store;
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("FLUID_SAVE_API_MISMATCH", failure);
        }
    }

    /**
     * Entity/host references are identity witnesses, not durable IDs or permission grants.
     * The caller must compare current stacks by item, components AND count, not ItemStack.equals.
     */
    public record SourceEvidence(BlockEntity memberEntity, ItemStack hostReference,
                                 ItemStack hostSnapshot, UUID contentsUuid, String hostLocator,
                                 long hostRevision, String versionEvidence,
                                 List<UpgradeEvidence> upgrades) {
        public SourceEvidence {
            hostSnapshot = hostSnapshot.copy();
            upgrades = List.copyOf(upgrades);
        }
        @Override public ItemStack hostSnapshot() { return hostSnapshot.copy(); }
    }

    /**
     * Multiple fractional views share poolId and poolUnits; never add their displayed counts.
     * A return may route among slots of this physical member. All of that member was safety-checked.
     * Snapshot counts describe physical storage, not permission or an output-filter verdict. The
     * caller must simulate extraction through this external handler before marking stock available.
     */
    public record SlotRef(String backendId, String poolId, int localSlot, IItemHandler handler,
                          int handlerSlot, long unitsPerItem, long poolUnits, ItemStack snapshot,
                          BlockPos memberPos, Direction side, SourceEvidence evidence) {
        public SlotRef {
            Objects.requireNonNull(backendId);
            Objects.requireNonNull(poolId);
            Objects.requireNonNull(handler);
            Objects.requireNonNull(evidence);
            if (localSlot < 0 || handlerSlot < 0 || unitsPerItem < 1 || poolUnits < 0)
                throw new IllegalArgumentException("INVALID_SLOT_REFERENCE");
            snapshot = snapshot.copy();
            memberPos = memberPos == null ? null : memberPos.immutable();
        }
        @Override public ItemStack snapshot() { return snapshot.copy(); }
    }

    /** Captured only from one caller-selected, already-authorized host slot. */
    public static final class ItemHostSnapshot {
        private final UUID owner;
        private final String locator;
        private final long revision;
        private final IItemHandler inventory;
        private final int slot;
        private final ItemStack reference;
        private final ItemStack snapshot;

        private ItemHostSnapshot(UUID owner, String locator, long revision, IItemHandler inventory,
                                 int slot, ItemStack reference) {
            this.owner = owner;
            this.locator = locator;
            this.revision = revision;
            this.inventory = inventory;
            this.slot = slot;
            this.reference = reference;
            this.snapshot = reference.copy();
        }
        public String locator() { return locator; }
        public long revision() { return revision; }
        public int slot() { return slot; }
        public ItemStack snapshot() { return snapshot.copy(); }
    }

    /**
     * The inventory's relationship to this player/equipment source is the caller's responsibility.
     * This method reads exactly the supplied slot; it does not search any inventory.
     */
    public ItemHostSnapshot captureItemHost(ServerPlayer player, String locator, long revision,
                                           IItemHandler inventory, int slot) {
        String denied = playerGuard(player);
        if (denied != null) throw new IllegalStateException(denied);
        if (locator == null || !locator.matches("[A-Za-z0-9_:/.-]{1,256}") || revision < 0
                || inventory == null || slot < 0 || slot >= boundedSlots(inventory))
            throw new IllegalArgumentException("INVALID_HOST_BINDING");
        ItemStack stack = inventory.getStackInSlot(slot);
        if (stack == null || stack.isEmpty() || stack.getCount() != 1)
            throw new IllegalArgumentException("INVALID_BACKPACK_HOST");
        return new ItemHostSnapshot(player.getUUID(), locator, revision, inventory, slot, stack);
    }

    public Probe probeBlock(ServerPlayer player, ServerLevel level, BlockPos pos, Direction side) {
        String denied = actorGuard(player, level);
        if (denied != null) return refused(denied, "none", "");
        if (pos == null || side == null) return refused("SOURCE_POSITION_AND_SIDE_REQUIRED", "none", "");
        if (!loaded(level, pos)) return refused("SOURCE_UNLOADED_OR_OUTSIDE_BOUNDS", "none", "");
        BlockEntity source = level.getBlockEntity(pos);
        if (source == null || source.isRemoved()) return refused("SOURCE_MISSING", "none", "");
        String name = source.getClass().getName();
        String adapter = name.startsWith(SD) ? "storage_drawers"
                : name.startsWith(STORAGE) ? "sophisticated_storage"
                : name.startsWith(BP) ? "sophisticated_backpacks" : "none";
        String version = "";
        try {
            if (adapter.equals("storage_drawers")) {
                version = version("storagedrawers", "13.11.4", "13.11.4");
                return matched(adapter, version, drawers(player, level, source, side, version));
            }
            if (adapter.equals("sophisticated_storage")) {
                version = version("sophisticatedcore", "1.4.77", "1.21.1-1.4.77.2173")
                        + "; " + version("sophisticatedstorage", "1.5.80", "1.21.1-1.5.80.1999");
                return matched(adapter, version, sophisticatedStorage(level, source, side, version));
            }
            if (adapter.equals("sophisticated_backpacks")) {
                version = backpackVersions();
                require(reflection.type(BP + "backpack.BackpackBlockEntity").isInstance(source),
                        "UNSUPPORTED_BACKPACK_BLOCK");
                Object wrapper = reflection.query(source, BP + "backpack.BackpackBlockEntity",
                        "getBackpackWrapper", reflection.type(BP + "backpack.wrapper.IBackpackWrapper"));
                ItemStack host = backpackStack(wrapper);
                UUID uuid = backpackPreflight(host);
                require(existingBackpackWrapper(host) == wrapper, "BACKPACK_WRAPPER_NOT_INITIALIZED");
                SourceEvidence evidence = evidence(source, host, uuid,
                        blockBackend(level.dimension().location().toString(), pos), 0, version, List.of());
                List<UpgradeEvidence> upgrades = safeUpgrades(wrapper);
                IItemHandler handler = external(level, source, side, CORE + "inventory.CachedFailedInsertInventoryHandler");
                validateBackpackItemCache(player, wrapper, uuid);
                return matched(adapter, version, sophisticatedSlots(wrapper, handler,
                        backpackBackend(uuid), source.getBlockPos(), side,
                        withUpgrades(evidence, upgrades)));
            }
            return new Probe(Status.UNSUPPORTED_SOURCE, "UNSUPPORTED_STORAGE_SOURCE", adapter, "", false, List.of());
        } catch (ProbeFailure failure) {
            return new Probe(failure.status, failure.getMessage(), adapter, version, false, List.of());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new Probe(Status.API_MISMATCH, "STORAGE_API_OR_STATE_MISMATCH", adapter, version, false, List.of());
        }
    }

    public Probe probeItemBackpack(ServerPlayer player, ItemHostSnapshot host) {
        String denied = playerGuard(player);
        if (denied != null) return refused(denied, "sophisticated_backpacks", "");
        String version = "";
        try {
            require(host != null && host.owner.equals(player.getUUID()), "HOST_OWNER_MISMATCH");
            require(host.slot >= 0 && host.slot < boundedSlots(host.inventory), "HOST_SLOT_CHANGED");
            ItemStack current = host.inventory.getStackInSlot(host.slot);
            require(current == host.reference && sameStack(current, host.snapshot), "HOST_MOVED_OR_CHANGED");
            version = backpackVersions();
            UUID uuid = backpackPreflight(current);
            Object wrapper = existingBackpackWrapper(current);
            require(backpackStack(wrapper) == current, "BACKPACK_WRAPPER_HOST_MISMATCH");
            List<UpgradeEvidence> upgrades = safeUpgrades(wrapper);
            IItemHandler handler = current.getCapability(Capabilities.ItemHandler.ITEM);
            require(handler != null, "EXTERNAL_ITEM_HANDLER_MISSING");
            validateBackpackItemCache(player, wrapper, uuid);
            SourceEvidence evidence = evidence(null, current, uuid, host.locator, host.revision, version, upgrades);
            return matched("sophisticated_backpacks", version,
                    sophisticatedSlots(wrapper, handler, backpackBackend(uuid), null, null, evidence));
        } catch (ProbeFailure failure) {
            return new Probe(failure.status, failure.getMessage(), "sophisticated_backpacks", version, false, List.of());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new Probe(Status.API_MISMATCH, "STORAGE_API_OR_STATE_MISMATCH",
                    "sophisticated_backpacks", version, false, List.of());
        }
    }

    /** Reads only the already assigned backend ID. It cannot allocate a UUID or open contents. */
    public java.util.Optional<UUID> existingBackpackIdentity(ServerPlayer player, ItemStack stack) {
        String denied = playerGuard(player);
        if (denied != null) throw new IllegalStateException(denied);
        if (stack == null || stack.isEmpty() || !stack.getItem().getClass().getName().equals(BP + "backpack.BackpackItem"))
            return java.util.Optional.empty();
        try {
            backpackVersions();
            if (stack.getCount() != 1) throw new IllegalStateException("INVALID_BACKPACK_HOST");
            Object uuid = stack.get(component(CORE + "init.ModCoreDataComponents", "STORAGE_UUID"));
            return uuid instanceof UUID id ? java.util.Optional.of(id) : java.util.Optional.empty();
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("BACKPACK_IDENTITY_API_MISMATCH");
        }
    }

    /** Exact initialized UUID map/handler identity, not host NBT or a get-or-create Mod helper. */
    public net.minecraft.world.level.saveddata.SavedData backpackSaveStore(ServerPlayer player, Object host, String backend) {
        String denied = playerGuard(player); if (denied != null) throw new IllegalStateException(denied);
        try {
            backpackVersions();
            Object wrapper;
            if (host instanceof ItemStack stack) wrapper = existingBackpackWrapper(stack);
            else if (reflection.type(BP + "backpack.BackpackBlockEntity").isInstance(host))
                wrapper = reflection.query(host, BP + "backpack.BackpackBlockEntity", "getBackpackWrapper", reflection.type(BP + "backpack.wrapper.IBackpackWrapper"));
            else throw new IllegalStateException("BACKPACK_SAVE_HOST_CHANGED");
            ItemStack stack = backpackStack(wrapper); UUID uuid = backpackPreflight(stack);
            require(backend.equals(backpackBackend(uuid)) && existingBackpackWrapper(stack) == wrapper, "BACKPACK_SAVE_IDENTITY_CHANGED");
            Object raw = reflection.query(wrapper, CORE + "api.IStorageWrapper", "getInventoryHandler", reflection.type(CORE + "inventory.InventoryHandler"));
            var store = ((firefly520.fireflymc.mixin.AiSavedDataCacheAccess) player.server.overworld().getDataStorage())
                    .fireflymc$existingStores().get("sophisticatedbackpacks");
            require(store != null && store.getClass() == reflection.type(BP + "backpack.BackpackStorage"), "BACKPACK_SAVE_STORE_MISSING");
            Field entries = reflection.type(BP + "backpack.BackpackStorage").getDeclaredField("backpackContents");
            Field contents = reflection.type(CORE + "inventory.InventoryHandler").getDeclaredField("contentsNbt");
            Field owner = reflection.type(CORE + "inventory.InventoryHandler").getDeclaredField("storageWrapper");
            require(entries.getType() == Map.class && contents.getType() == net.minecraft.nbt.CompoundTag.class
                    && owner.getType() == reflection.type(CORE + "api.IStorageWrapper")
                    && !Modifier.isStatic(entries.getModifiers()) && !Modifier.isStatic(contents.getModifiers())
                    && !Modifier.isStatic(owner.getModifiers()) && entries.trySetAccessible()
                    && contents.trySetAccessible() && owner.trySetAccessible(), "BACKPACK_SAVE_SIGNATURE_MISMATCH");
            require(owner.get(raw) == wrapper && ((Map<?, ?>) entries.get(store)).get(uuid) == contents.get(raw), "BACKPACK_SAVE_CONTENTS_DETACHED");
            return store;
        } catch (ReflectiveOperationException | LinkageError failure) { throw new IllegalStateException("BACKPACK_SAVE_API_MISMATCH", failure); }
    }

    private List<SlotRef> drawers(ServerPlayer player, ServerLevel level, BlockEntity source,
                                 Direction side, String version) throws ReflectiveOperationException {
        Class<?> memberType = reflection.type(SD + "block.tile.BlockEntityDrawers");
        if (memberType.isInstance(source)) return drawerMember(player, level, source, side, version);
        Class<?> controllerType = reflection.type(SD + "block.tile.BlockEntityController");
        Object controller = source;
        if (reflection.type(SD + "block.tile.BlockEntityControllerIO").isInstance(source)) {
            Object group = reflection.query(source, SD + "block.tile.BlockEntityControllerIO",
                    "getDrawerGroup", reflection.type(SD + "api.storage.IDrawerGroup"));
            controller = reflection.query(source, SD + "block.tile.BlockEntityControllerIO",
                    "getController", controllerType);
            require(group == controller && controller != null, "DRAWER_IO_DISCONNECTED");
        }
        require(controllerType.isInstance(controller), "UNSUPPORTED_DRAWER_BLOCK");
        BlockEntity controllerEntity = (BlockEntity) controller;
        require(loaded(level, controllerEntity.getBlockPos())
                && level.getBlockEntity(controllerEntity.getBlockPos()) == controllerEntity
                && !controllerEntity.isRemoved(), "DRAWER_CONTROLLER_CHANGED");
        require(reflection.bool(controller, SD + "api.storage.IDrawerGroup", "isGroupValid"),
                "DRAWER_GROUP_INVALID");
        IItemHandler rootHandler = external(level, source, side, SD + "capabilities.PlatformDrawerItemHandler");
        int[] accessible = drawerAccessible(controller);
        int drawerCount = reflection.integer(controller, SD + "api.storage.IDrawerGroup", "getDrawerCount");
        List<DrawerMapping> mapping = drawerMappings(boundedSlots(rootHandler), drawerCount, accessible);
        Field recordsField = reflection.field(controllerType, "drawerSlotList", List.class, false, true);
        Object value = recordsField.get(controller);
        require(value instanceof List<?> && ((List<?>) value).size() == drawerCount, "DRAWER_TOPOLOGY_CHANGED");
        List<?> records = new ArrayList<>((List<?>) value); // Native lists may contain holes: validate each selected one.
        Class<?> recordType = reflection.type(SD + "block.tile.BlockEntityController$SlotRecord");
        Field coord = reflection.field(recordType, "coord", BlockPos.class, false, false);
        Field group = reflection.field(recordType, "group", reflection.type(SD + "api.storage.IDrawerGroup"), false, false);
        Field local = reflection.field(recordType, "slot", int.class, false, false);
        Field index = reflection.field(recordType, "index", int.class, false, false);
        Map<BlockPos, List<SlotRef>> members = new LinkedHashMap<>();
        Map<String, SlotRef> result = new LinkedHashMap<>();
        for (DrawerMapping selected : mapping) {
            Object record = records.get(selected.drawerSlot());
            require(recordType.isInstance(record) && index.getInt(record) == selected.drawerSlot(),
                    "DRAWER_RECORD_MISMATCH");
            BlockPos memberPos = (BlockPos) coord.get(record);
            require(memberPos != null && loaded(level, memberPos), "DRAWER_MEMBER_UNLOADED");
            BlockEntity member = level.getBlockEntity(memberPos);
            require(member != null && !member.isRemoved() && memberType.isInstance(member), "DRAWER_MEMBER_CHANGED");
            Object memberGroup = reflection.query(member, SD + "block.tile.BlockEntityDrawers",
                    "getGroup", reflection.type(SD + "api.storage.IDrawerGroup"));
            Object exposedGroup = reflection.invoke(controller, SD + "block.tile.BlockEntityController",
                    "getGroupForDrawerSlot", reflection.type(SD + "api.storage.IDrawerGroup"),
                    new Class<?>[] { int.class }, selected.drawerSlot());
            int localSlot = local.getInt(record);
            require(group.get(record) == memberGroup && exposedGroup == memberGroup && localSlot >= 0,
                    "DRAWER_MEMBER_IDENTITY_MISMATCH");
            Object exposedDrawer = drawer(controller, selected.drawerSlot());
            require(drawer(memberGroup, localSlot) == exposedDrawer, "DRAWER_SLOT_IDENTITY_MISMATCH");
            List<SlotRef> memberSlots = members.get(memberPos);
            if (memberSlots == null) {
                require(members.size() < MAX_MEMBERS, "STORAGE_MEMBER_LIMIT");
                memberSlots = drawerMember(player, level, member, side, version);
                members.put(memberPos.immutable(), memberSlots);
            }
            SlotRef found = null;
            for (SlotRef slot : memberSlots) if (slot.localSlot() == localSlot) found = slot;
            require(found != null, "DRAWER_MEMBER_SLOT_UNAVAILABLE");
            result.putIfAbsent(found.backendId() + "/slot/" + localSlot, found);
        }
        return List.copyOf(result.values());
    }

    private List<SlotRef> drawerMember(ServerPlayer player, ServerLevel level, BlockEntity member,
                                      Direction side, String version) throws ReflectiveOperationException {
        require(reflection.bool(member, SD + "api.storage.IDrawerGroup", "isGroupValid"), "DRAWER_GROUP_INVALID");
        Object attributes = reflection.query(member, SD + "block.tile.BlockEntityDrawers",
                "getDrawerAttributes", reflection.type(SD + "api.storage.IDrawerAttributes"));
        checkDrawerAttributes(attributes);
        require((boolean) reflection.invoke(null, SD + "security.SecurityManager", "hasAccess", boolean.class,
                new Class<?>[] { Player.class, reflection.type(SD + "api.storage.attribute.IProtectable") },
                player, member), "DRAWER_NATIVE_ACCESS_DENIED");
        Object group = reflection.query(member, SD + "block.tile.BlockEntityDrawers",
                "getGroup", reflection.type(SD + "api.storage.IDrawerGroup"));
        require(group != null, "DRAWER_GROUP_MISSING");
        IItemHandler handler = external(level, member, side, SD + "capabilities.PlatformDrawerItemHandler");
        int count = reflection.integer(group, SD + "api.storage.IDrawerGroup", "getDrawerCount");
        List<DrawerMapping> mappings = drawerMappings(boundedSlots(handler), count, drawerAccessible(group));
        Class<?> fractionalType = reflection.type(SD + "api.storage.IFractionalDrawer");
        boolean fractional = reflection.type(SD + "block.tile.tiledata.FractionalDrawerGroup").isInstance(group);
        int smallestRate = 0;
        long pool = -1;
        List<DrawerView> views = new ArrayList<>();
        for (DrawerMapping mapping : mappings) {
            Object drawer = drawer(group, mapping.drawerSlot());
            require(drawer != null && reflection.bool(drawer, SD + "api.storage.IDrawer", "isEnabled")
                    && !reflection.bool(drawer, SD + "api.storage.IDrawer", "isMissing"), "DRAWER_SLOT_UNAVAILABLE");
            checkDrawerAttributes(reflection.query(drawer, SD + "api.storage.IDrawer", "getAttributes",
                    reflection.type(SD + "api.storage.IDrawerAttributes")));
            require(fractionalType.isInstance(drawer) == fractional, "UNKNOWN_DRAWER_STORAGE_SEMANTICS");
            ItemStack stack = handler.getStackInSlot(mapping.handlerSlot()).copy();
            int stored = reflection.integer(drawer, SD + "api.storage.IDrawer", "getStoredItemCount");
            require(stored >= 0 && stack.getCount() == stored, "DRAWER_COUNT_MISMATCH");
            int rate = 1;
            if (fractional) {
                rate = reflection.integer(drawer, SD + "api.storage.IFractionalDrawer", "getConversionRate");
                require(rate > 0, "DRAWER_COMPRESSION_UNINITIALIZED");
                if (reflection.bool(drawer, SD + "api.storage.IFractionalDrawer", "isSmallestUnit")) {
                    require(smallestRate == 0, "DRAWER_COMPRESSION_AMBIGUOUS");
                    smallestRate = rate;
                    pool = stored;
                }
            }
            views.add(new DrawerView(mapping, stack, rate));
        }
        if (fractional) {
            require(smallestRate > 0 && pool >= 0, "DRAWER_COMPRESSION_UNINITIALIZED");
            require(reflection.integer(group, SD + "block.tile.tiledata.FractionalDrawerGroup", "getPooledCount") == pool,
                    "DRAWER_POOL_MISMATCH");
        }
        String backend = blockBackend(level.dimension().location().toString(), member.getBlockPos());
        SourceEvidence evidence = evidence(member, ItemStack.EMPTY, null, backend, 0, version, List.of());
        List<SlotRef> result = new ArrayList<>();
        for (DrawerView view : views) {
            long weight = fractional ? fractionalUnits(smallestRate, view.rate()) : 1;
            require(!fractional || view.stack().getCount() == pool / weight, "DRAWER_POOL_VIEW_MISMATCH");
            result.add(new SlotRef(backend, poolIdentity(backend, view.mapping().drawerSlot(), fractional),
                    view.mapping().drawerSlot(), handler, view.mapping().handlerSlot(), weight,
                    fractional ? pool : view.stack().getCount(), view.stack(), member.getBlockPos(), side, evidence));
        }
        return List.copyOf(result);
    }

    private List<SlotRef> sophisticatedStorage(ServerLevel level, BlockEntity source, Direction side,
                                               String version) throws ReflectiveOperationException {
        if (reflection.type(STORAGE + "block.StorageBlockEntity").isInstance(source))
            return sophisticatedMember(level, source, side, version);
        require(reflection.type(STORAGE + "block.ControllerBlockEntity").isInstance(source), "UNSUPPORTED_STORAGE_BLOCK");
        IItemHandler controller = external(level, source, side, CORE + "inventory.CachedFailedInsertInventoryHandler");
        Object positionsValue = reflection.query(source, CORE + "controller.ControllerBlockEntityBase",
                "getStoragePositions", List.class);
        require(positionsValue instanceof List<?> && ((List<?>) positionsValue).size() <= MAX_MEMBERS,
                "STORAGE_MEMBER_LIMIT");
        List<?> positions = List.copyOf((List<?>) positionsValue);
        Set<BlockPos> seen = new HashSet<>();
        List<SlotRef> result = new ArrayList<>();
        int total = 0;
        for (int index = 0; index < positions.size(); index++) {
            require(positions.get(index) instanceof BlockPos, "STORAGE_TOPOLOGY_MISMATCH");
            BlockPos memberPos = (BlockPos) positions.get(index);
            require(seen.add(memberPos) && loaded(level, memberPos), "STORAGE_MEMBER_CHANGED");
            BlockEntity member = level.getBlockEntity(memberPos);
            require(member != null && !member.isRemoved()
                    && reflection.type(STORAGE + "block.StorageBlockEntity").isInstance(member), "STORAGE_MEMBER_CHANGED");
            Object parent = reflection.query(member, STORAGE + "block.StorageBlockEntity",
                    "getControllerPos", java.util.Optional.class);
            require(((java.util.Optional<?>) parent).filter(source.getBlockPos()::equals).isPresent(),
                    "STORAGE_CONTROLLER_BINDING_CHANGED");
            int expected = (int) reflection.invoke(source, CORE + "controller.ControllerBlockEntityBase",
                    "getSlots", int.class, new Class<?>[] { int.class }, index);
            List<SlotRef> memberSlots = sophisticatedMember(level, member, side, version);
            IItemHandler memberHandler = external(level, member, side, CORE + "inventory.CachedFailedInsertInventoryHandler");
            require(expected == boundedSlots(memberHandler), "STORAGE_CONTROLLER_SLOT_MISMATCH");
            total = Math.addExact(total, expected);
            require(total <= MAX_SLOTS, "STORAGE_SLOT_LIMIT");
            result.addAll(memberSlots);
        }
        require(total == boundedSlots(controller), "STORAGE_CONTROLLER_SLOT_MISMATCH");
        return List.copyOf(result);
    }

    private List<SlotRef> sophisticatedMember(ServerLevel level, BlockEntity member, Direction side,
                                              String version) throws ReflectiveOperationException {
        if (reflection.type(STORAGE + "block.WoodStorageBlockEntity").isInstance(member))
            require(!reflection.bool(member, STORAGE + "block.WoodStorageBlockEntity", "isPacked"), "STORAGE_PACKED");
        Object wrapper = reflection.query(member, STORAGE + "block.StorageBlockEntity",
                "getStorageWrapper", reflection.type(STORAGE + "block.StorageWrapper"));
        List<UpgradeEvidence> upgrades = safeUpgrades(wrapper);
        IItemHandler handler = external(level, member, side, CORE + "inventory.CachedFailedInsertInventoryHandler");
        String backend = blockBackend(level.dimension().location().toString(), member.getBlockPos());
        // getContentsUuid() on a block storage wrapper can create/save a UUID: never call it.
        SourceEvidence evidence = evidence(member, ItemStack.EMPTY, null, backend, 0, version, upgrades);
        return sophisticatedSlots(wrapper, handler, backend, member.getBlockPos(), side, evidence);
    }

    private List<UpgradeEvidence> safeUpgrades(Object wrapper) throws ReflectiveOperationException {
        // Item and fluid sources can share a backpack. Tank I/O must still be empty, and its
        // real fluid contents are never implicitly granted by an item-source permission.
        return safeUpgrades(wrapper, wrapper != null && wrapper.getClass().getName().equals(BP + "backpack.wrapper.BackpackWrapper"));
    }

    private List<UpgradeEvidence> safeUpgrades(Object wrapper, boolean fluidSource) throws ReflectiveOperationException {
        Object upgradeHandler = reflection.query(wrapper, CORE + "api.IStorageWrapper", "getUpgradeHandler",
                reflection.type(CORE + "upgrades.UpgradeHandler"));
        require(upgradeHandler instanceof IItemHandler, "UPGRADE_HANDLER_MISMATCH");
        IItemHandler inventory = (IItemHandler) upgradeHandler;
        int size = inventory.getSlots();
        require(size >= 0 && size <= MAX_UPGRADES, "UPGRADE_SLOT_LIMIT");
        Set<Integer> occupied = new HashSet<>();
        for (int slot = 0; slot < size; slot++) {
            ItemStack upgrade = inventory.getStackInSlot(slot);
            if (!upgrade.isEmpty()) {
                require(upgrade.getCount() == 1, "UPGRADE_COUNT_MISMATCH");
                String denied = upgradeItemRefusal(upgrade.getItem().getClass().getName());
                require(denied == null || fluidSource && upgrade.getItem().getClass().getName().equals(
                        CORE + "upgrades.tank.TankUpgradeItem"), denied);
                occupied.add(slot);
            }
        }
        Object wrappersValue = reflection.query(upgradeHandler, CORE + "upgrades.UpgradeHandler",
                "getSlotWrappers", Map.class);
        require(wrappersValue instanceof Map<?, ?>, "UPGRADE_WRAPPER_MISMATCH");
        Map<?, ?> wrappers = (Map<?, ?>) wrappersValue;
        require(wrappers.size() == occupied.size(), "UPGRADE_WRAPPER_MISMATCH");
        List<UpgradeEvidence> result = new ArrayList<>();
        for (int slot = 0; slot < size; slot++) {
            if (!occupied.contains(slot)) continue;
            Object upgrade = wrappers.get(slot);
            require(upgrade != null && (SAFE_UPGRADE_WRAPPERS.contains(upgrade.getClass().getName())
                    || fluidSource && upgrade.getClass().getName().equals(CORE + "upgrades.tank.TankUpgradeWrapper")),
                    "UNKNOWN_UPGRADE_WRAPPER");
            boolean enabled = reflection.bool(upgrade, CORE + "upgrades.IUpgradeWrapper", "isEnabled");
            ItemStack stack = (ItemStack) reflection.query(upgrade, CORE + "upgrades.IUpgradeWrapper",
                    "getUpgradeStack", ItemStack.class);
            require(sameStack(stack, inventory.getStackInSlot(slot)), "UPGRADE_STATE_CHANGED");
            if (fluidSource && upgrade.getClass().getName().equals(CORE + "upgrades.tank.TankUpgradeWrapper")) {
                require(enabled, "FLUID_TANK_DISABLED");
                Object io = reflection.query(upgrade, CORE + "upgrades.tank.TankUpgradeWrapper", "getInventory",
                        reflection.type(CORE + "upgrades.tank.TankUpgradeWrapper$TankComponentItemHandler"));
                require(io instanceof IItemHandler, "FLUID_CONTAINER_HANDLER_MISMATCH");
                IItemHandler containers = (IItemHandler) io;
                int slots = boundedSlots(containers);
                for (int i = 0; i < slots; i++) require(containers.getStackInSlot(i).isEmpty(),
                        "FLUID_CONTAINER_AUTOMATION_ACTIVE");
            }
            result.add(new UpgradeEvidence(slot, upgrade.getClass().getName(), enabled, stack));
        }
        return List.copyOf(result);
    }

    private List<SlotRef> sophisticatedSlots(Object wrapper, IItemHandler handler, String backend,
                                             BlockPos memberPos, Direction side, SourceEvidence evidence)
            throws ReflectiveOperationException {
        Object raw = reflection.query(wrapper, CORE + "api.IStorageWrapper", "getInventoryHandler",
                reflection.type(CORE + "inventory.InventoryHandler"));
        require(raw instanceof IItemHandler, "RAW_INVENTORY_MISMATCH");
        IItemHandler inventory = (IItemHandler) raw;
        int count = boundedSlots(inventory);
        Object partitioner = reflection.query(raw, CORE + "inventory.InventoryHandler", "getInventoryPartitioner",
                reflection.type(CORE + "inventory.InventoryPartitioner"));
        // Validate every native partition before accessing a potentially wrapped external inventory.
        for (int slot = 0; slot < count; slot++) {
            boolean infinite = (boolean) reflection.invoke(raw, CORE + "inventory.InventoryHandler",
                    "isInfinite", boolean.class, new Class<?>[] { int.class }, slot);
            Object part = reflection.invoke(partitioner, CORE + "inventory.InventoryPartitioner",
                    "getPartBySlot", reflection.type(CORE + "inventory.IInventoryPartHandler"),
                    new Class<?>[] { int.class }, slot);
            String denied = partitionRefusal(part == null ? "" : part.getClass().getName(), infinite);
            require(denied == null, denied);
        }
        Object expectedIo = reflection.query(wrapper, CORE + "api.IStorageWrapper",
                "getInventoryForInputOutput", reflection.type(CORE + "inventory.ITrackedContentsItemHandler"));
        require(expectedIo instanceof IItemHandler && boundedSlots((IItemHandler) expectedIo) == count
                && boundedSlots(handler) == count, "EXTERNAL_LAYOUT_MISMATCH");
        if (memberPos == null) require(handler == expectedIo, "ITEM_CAPABILITY_WRAPPER_MISMATCH");
        List<SlotRef> result = new ArrayList<>();
        for (int slot = 0; slot < count; slot++) {
            ItemStack current = handler.getStackInSlot(slot);
            require(current != null && sameStack(current, inventory.getStackInSlot(slot)), "EXTERNAL_CONTENTS_MISMATCH");
            boolean accessible = (boolean) reflection.invoke(raw, CORE + "inventory.InventoryHandler",
                    "isSlotAccessible", boolean.class, new Class<?>[] { int.class }, slot);
            if (!accessible) continue;
            result.add(new SlotRef(backend, poolIdentity(backend, slot, false), slot, handler, slot, 1,
                    current.getCount(), current, memberPos, side, evidence));
        }
        return List.copyOf(result);
    }

    private UUID backpackPreflight(ItemStack stack) throws ReflectiveOperationException {
        require(stack != null && !stack.isEmpty() && stack.getCount() == 1
                && reflection.type(BP + "backpack.BackpackItem").isInstance(stack.getItem()), "INVALID_BACKPACK_HOST");
        // Legacy migration and lazy extra-item unpacking can mutate or drop items during discovery.
        require(!stack.has(DataComponents.CUSTOM_DATA), "BACKPACK_LEGACY_OR_UNKNOWN_DATA");
        require(!stack.has(DataComponents.CONTAINER), "BACKPACK_PENDING_EXTRA_ITEMS");
        DataComponentType<?> loot = component(BP + "init.ModDataComponents", "LOOT_TABLE");
        require(!stack.has(loot), "BACKPACK_PENDING_LOOT");
        Object uuid = stack.get(component(CORE + "init.ModCoreDataComponents", "STORAGE_UUID"));
        require(uuid instanceof UUID, "BACKPACK_NOT_INITIALIZED");
        return (UUID) uuid;
    }

    private ItemStack backpackStack(Object wrapper) throws ReflectiveOperationException {
        require(wrapper != null && wrapper.getClass().getName().equals(BP + "backpack.wrapper.BackpackWrapper")
                && reflection.type(BP + "backpack.wrapper.IBackpackWrapper").isInstance(wrapper),
                "BACKPACK_WRAPPER_MISMATCH");
        return (ItemStack) reflection.query(wrapper, BP + "backpack.wrapper.IBackpackWrapper", "getBackpack", ItemStack.class);
    }

    private Object existingBackpackWrapper(ItemStack host) throws ReflectiveOperationException {
        Object optional = reflection.invoke(null, BP + "backpack.wrapper.BackpackWrapper",
                "fromExistingData", java.util.Optional.class, new Class<?>[] { ItemStack.class }, host);
        return ((java.util.Optional<?>) optional).orElseThrow(
                () -> new ProbeFailure(Status.REFUSED, "BACKPACK_WRAPPER_NOT_INITIALIZED"));
    }

    private DataComponentType<?> component(String owner, String name) throws ReflectiveOperationException {
        Field field = reflection.field(reflection.type(owner), name, Supplier.class, true, false);
        Object supplier = field.get(null);
        require(supplier instanceof Supplier<?>, "DATA_COMPONENT_SIGNATURE_MISMATCH");
        Object component = ((Supplier<?>) supplier).get();
        require(component instanceof DataComponentType<?>, "DATA_COMPONENT_SIGNATURE_MISMATCH");
        return (DataComponentType<?>) component;
    }

    private Object drawer(Object group, int slot) throws ReflectiveOperationException {
        return reflection.invoke(group, SD + "api.storage.IDrawerGroup", "getDrawer",
                reflection.type(SD + "api.storage.IDrawer"), new Class<?>[] { int.class }, slot);
    }

    private int[] drawerAccessible(Object group) throws ReflectiveOperationException {
        int[] result = (int[]) reflection.query(group, SD + "api.storage.IDrawerGroup", "getAccessibleDrawerSlots", int[].class);
        require(result != null && result.length <= MAX_SLOTS, "DRAWER_SLOT_LIMIT");
        return result.clone();
    }

    private void checkDrawerAttributes(Object attributes) throws ReflectiveOperationException {
        require(attributes != null, "DRAWER_ATTRIBUTES_MISSING");
        DrawerFlags flags = new DrawerFlags(
                reflection.bool(attributes, SD + "api.storage.IDrawerAttributes", "isVoid"),
                reflection.bool(attributes, SD + "api.storage.IDrawerAttributes", "isUnlimitedStorage"),
                reflection.bool(attributes, SD + "api.storage.IDrawerAttributes", "isUnlimitedVending"),
                reflection.bool(attributes, SD + "api.storage.IDrawerAttributes", "isSealed"),
                reflection.bool(attributes, SD + "api.storage.IDrawerAttributes", "isSuspended"),
                reflection.bool(attributes, SD + "api.storage.IDrawerAttributes", "isDictConvertible"),
                reflection.bool(attributes, SD + "api.storage.IDrawerAttributes", "isBalancedFill"));
        String denied = drawerRefusal(flags);
        require(denied == null, denied);
    }

    private static IItemHandler external(ServerLevel level, BlockEntity source, Direction side, String expectedClass) {
        require(side != null && loaded(level, source.getBlockPos()) && !source.isRemoved()
                && level.getBlockEntity(source.getBlockPos()) == source, "SOURCE_CHANGED");
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, source.getBlockPos(), side);
        require(handler != null, "EXTERNAL_ITEM_HANDLER_MISSING");
        require(handler.getClass().getName().equals(expectedClass), "UNKNOWN_EXTERNAL_ITEM_HANDLER");
        return handler;
    }

    private static String actorGuard(ServerPlayer player, ServerLevel level) {
        String denied = playerGuard(player);
        if (denied != null) return denied;
        if (level == null || level.getServer() != player.server || player.serverLevel() != level)
            return "WORLD_CONTEXT_MISMATCH";
        return null;
    }

    private static String playerGuard(ServerPlayer player) {
        if (player == null) return "PLAYER_NOT_ACTIVE";
        MinecraftServer server = player.server;
        if (!server.isSameThread()) return "SERVER_THREAD_REQUIRED";
        if (!server.isDedicatedServer()) return "AI_DEDICATED_SERVER_REQUIRED";
        if (player.isRemoved() || !player.isAlive() || server.getPlayerList().getPlayer(player.getUUID()) != player)
            return "PLAYER_NOT_ACTIVE";
        if (player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) return "SURVIVAL_REQUIRED";
        return null;
    }

    private static boolean loaded(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos) && level.getWorldBorder().isWithinBounds(pos) && level.hasChunkAt(pos);
    }

    private static String version(String modId, String metadata, String implementation) {
        ModList mods = ModList.get();
        require(mods != null && mods.isLoaded(modId), "REQUIRED_MOD_NOT_LOADED");
        String actual = mods.getModContainerById(modId).orElseThrow(
                () -> new ProbeFailure(Status.API_MISMATCH, "MOD_CONTAINER_MISSING")).getModInfo().getVersion().toString();
        var fileInfo = mods.getModFileById(modId);
        if (!metadata.equals(actual) || fileInfo == null || !(fileInfo.getFile() instanceof ModFile file)
                || !implementation.equals(file.getJarVersion().toString()))
            throw new ProbeFailure(Status.UNSUPPORTED_VERSION, "UNSUPPORTED_STORAGE_BUILD");
        // getJarVersion uses the loader's already-cached manifest value: no disk IO on the game thread.
        return modId + "@" + actual + "/manifest=" + implementation + "/runtimeSha256=UNVERIFIED";
    }

    private static String backpackVersions() {
        return version("sophisticatedcore", "1.4.77", "1.21.1-1.4.77.2173")
                + "; " + version("sophisticatedbackpacks", "3.25.71", "1.21.1-3.25.71.1997");
    }

    static long fractionalUnits(int smallestPublicRate, int variantPublicRate) {
        require(smallestPublicRate > 0 && variantPublicRate > 0 && variantPublicRate <= smallestPublicRate
                && smallestPublicRate % variantPublicRate == 0, "INVALID_FRACTIONAL_CONVERSION");
        return (long) smallestPublicRate / variantPublicRate;
    }

    static String blockBackend(String dimension, BlockPos pos) {
        return "block/" + dimension + "/" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    static String backpackBackend(UUID uuid) { return "sophisticatedbackpacks/contents/" + Objects.requireNonNull(uuid); }

    static String poolIdentity(String backend, int localSlot, boolean fractional) {
        require(localSlot >= 0, "INVALID_LOCAL_SLOT");
        return backend + (fractional ? "/fractional-pool" : "/slot/" + localSlot);
    }

    record DrawerMapping(int handlerSlot, int drawerSlot) {}
    private record DrawerView(DrawerMapping mapping, ItemStack stack, int rate) {}
    record DrawerFlags(boolean voiding, boolean unlimitedStorage, boolean unlimitedVending,
                       boolean sealed, boolean suspended, boolean dictionaryConversion, boolean balancedFill) {
        DrawerFlags(boolean voiding, boolean unlimitedStorage, boolean unlimitedVending,
                    boolean sealed, boolean suspended) {
            this(voiding, unlimitedStorage, unlimitedVending, sealed, suspended, false, false);
        }
    }

    static List<DrawerMapping> drawerMappings(int handlerSlots, int drawerCount, int[] accessible) {
        require(drawerCount >= 0 && drawerCount < MAX_SLOTS && accessible != null
                && accessible.length == drawerCount && handlerSlots == drawerCount + 1, "DRAWER_LAYOUT_MISMATCH");
        Set<Integer> unique = new HashSet<>();
        List<DrawerMapping> result = new ArrayList<>();
        for (int index = 0; index < accessible.length; index++) {
            int local = accessible[index];
            require(local >= 0 && local < drawerCount && unique.add(local), "DRAWER_SLOT_MAP_INVALID");
            result.add(new DrawerMapping(index + 1, local));
        }
        return List.copyOf(result);
    }

    static String drawerRefusal(DrawerFlags flags) {
        if (flags.suspended()) return "DRAWER_SUSPENDED";
        if (flags.sealed()) return "DRAWER_SEALED";
        if (flags.unlimitedVending()) return "DRAWER_UNLIMITED_VENDING";
        if (flags.unlimitedStorage()) return "DRAWER_UNLIMITED_STORAGE";
        if (flags.voiding()) return "DRAWER_VOID_UNSAFE";
        if (flags.dictionaryConversion()) return "DRAWER_DICTIONARY_CONVERSION";
        if (flags.balancedFill()) return "DRAWER_BALANCED_FILL";
        return null;
    }

    static String upgradeItemRefusal(String itemClass) {
        if (SAFE_UPGRADE_ITEMS.contains(itemClass)) return null;
        if (itemClass.startsWith(CORE + "upgrades.voiding.") || itemClass.startsWith(CORE + "upgrades.infinity."))
            return "DESTRUCTIVE_OR_INFINITE_UPGRADE";
        return "UNREVIEWED_STORAGE_UPGRADE";
    }

    static String partitionRefusal(String partClass, boolean infinite) {
        if (infinite) return "INFINITE_INVENTORY";
        return (CORE + "inventory.IInventoryPartHandler$Default").equals(partClass)
                ? null : "UNSUPPORTED_INVENTORY_PARTITION";
    }

    private static boolean sameStack(ItemStack left, ItemStack right) {
        return left != null && right != null && left.getCount() == right.getCount()
                && ItemStack.isSameItemSameComponents(left, right);
    }

    private static int boundedSlots(IItemHandler handler) {
        int count = handler.getSlots();
        require(count >= 0 && count <= MAX_SLOTS, "STORAGE_SLOT_LIMIT");
        return count;
    }

    private static SourceEvidence evidence(BlockEntity entity, ItemStack host, UUID uuid, String locator,
                                           long revision, String version, List<UpgradeEvidence> upgrades) {
        return new SourceEvidence(entity, host, host, uuid, locator, revision, version, upgrades);
    }

    private static SourceEvidence withUpgrades(SourceEvidence evidence, List<UpgradeEvidence> upgrades) {
        return new SourceEvidence(evidence.memberEntity(), evidence.hostReference(), evidence.hostSnapshot(),
                evidence.contentsUuid(), evidence.hostLocator(), evidence.hostRevision(), evidence.versionEvidence(), upgrades);
    }

    private static Probe matched(String adapter, String version, List<SlotRef> slots) {
        return new Probe(Status.STATIC_API_MATCH, "SOURCE_REQUIRES_CALLER_AUTHORIZATION", adapter, version, false, slots);
    }

    private static Probe refused(String code, String adapter, String version) {
        return new Probe(Status.REFUSED, code, adapter, version, false, List.of());
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new ProbeFailure(Status.REFUSED, Objects.requireNonNullElse(reason, "STORAGE_REFUSED"));
    }

    static final class ProbeFailure extends RuntimeException {
        final Status status;
        ProbeFailure(Status status, String reason) { super(reason); this.status = status; }
    }

    /** No inventory fields are written. Only the inspected controller list needs protected access. */
    private static final class Reflection {
        private final Map<String, Class<?>> classes = new HashMap<>();
        private final Map<String, Method> methods = new HashMap<>();
        private final Map<String, Field> fields = new HashMap<>();

        Class<?> type(String name) throws ClassNotFoundException {
            Class<?> type = classes.get(name);
            if (type == null) {
                type = Class.forName(name, false, StorageSourceIntrospector.class.getClassLoader());
                classes.put(name, type);
            }
            return type;
        }

        Object query(Object instance, String owner, String name, Class<?> result) throws ReflectiveOperationException {
            return invoke(instance, owner, name, result, new Class<?>[0]);
        }

        boolean bool(Object instance, String owner, String name) throws ReflectiveOperationException {
            return (boolean) query(instance, owner, name, boolean.class);
        }

        int integer(Object instance, String owner, String name) throws ReflectiveOperationException {
            return (int) query(instance, owner, name, int.class);
        }

        Object invoke(Object instance, String owner, String name, Class<?> result,
                      Class<?>[] parameters, Object... arguments) throws ReflectiveOperationException {
            String key = owner + "#" + name + java.util.Arrays.toString(parameters);
            Method method = methods.get(key);
            if (method == null) {
                method = type(owner).getMethod(name, parameters);
                if (method.getReturnType() != result || !Modifier.isPublic(method.getModifiers())
                        || Modifier.isStatic(method.getModifiers()) != (instance == null))
                    throw new NoSuchMethodException(key);
                methods.put(key, method);
            }
            if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers()) != (instance == null))
                throw new NoSuchMethodException(key);
            return method.invoke(instance, arguments);
        }

        Field field(Class<?> owner, String name, Class<?> type, boolean isStatic, boolean protectedRead)
                throws ReflectiveOperationException {
            String key = owner.getName() + "#" + name;
            Field field = fields.get(key);
            if (field == null) {
                field = protectedRead ? owner.getDeclaredField(name) : owner.getField(name);
                if (field.getType() != type || Modifier.isStatic(field.getModifiers()) != isStatic
                        || (protectedRead ? !Modifier.isProtected(field.getModifiers()) : !Modifier.isPublic(field.getModifiers())))
                    throw new NoSuchFieldException(key);
                if (protectedRead && !field.trySetAccessible()) throw new IllegalAccessException(key);
                fields.put(key, field);
            }
            return field;
        }
    }
}
