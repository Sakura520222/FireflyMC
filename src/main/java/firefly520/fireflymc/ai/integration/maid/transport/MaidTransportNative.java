package firefly520.fireflymc.ai.integration.maid.transport;

import firefly520.fireflymc.ai.integration.maid.*;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.TaskControl;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.*;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.*;

/** Exact installed API. All inventory authority comes from MaterialInventoryService, never native proximity alone. */
final class MaidTransportNative {
    static final String PREFIX = "com.github.tartaricacid.touhoulittlemaid.";
    record Handle(ServerPlayer player, Object connection, LivingEntity maid, ItemStack bauble, int baubleSlot,
            BlockPos target, boolean toChest, boolean blacklist, List<Boolean> excluded, IItemHandler filter,
            IItemHandler nativeMaid, IItemHandler nativeChest, IItemHandler source, IItemHandler destination,
            MaterialInventoryService.MaidTransportView maidView, MaterialInventoryService.MaidTransportView blockView,
            long nativeRevision, long bindingRevision, Object behavior, Method helper) {}
    record Simulation(List<MaterialStack> source, List<MaterialStack> destination, List<Integer> matching,
                      List<MaidTransportPlan.Move> moves, List<MaterialStack> afterSource, List<MaterialStack> afterDestination) {}
    record Transfer(boolean entered, boolean verified, boolean achieved, String reason, List<MaterialStack> source,
                    List<MaterialStack> destination, List<MaterialStack> custody) {
        Transfer { source = List.copyOf(source); destination = List.copyOf(destination); custody = List.copyOf(custody); }
    }
    private final MaterialInventoryService inventory;
    private final Class<?> wireless, behavior;
    private final Method bindingPos, exportMode, blacklistMode, filters, exclusions;
    private final DataComponentType<CompoundTag> filterTag;
    @SuppressWarnings("unchecked")
    MaidTransportNative(MaterialInventoryService inventory) throws ReflectiveOperationException {
        this.inventory = inventory;
        String version = ModList.get().getModContainerById("touhou_little_maid").map(value -> value.getModInfo().getVersion().toString()).orElse("");
        if (!version.equals("1.5.3-neoforge+mc1.21.1")) throw new IllegalStateException("MAID_TRANSPORT_VERSION_UNSUPPORTED");
        wireless = Class.forName(PREFIX + "item.ItemWirelessIO"); behavior = Class.forName(PREFIX + "item.bauble.WirelessIOBauble");
        if (!MaidTransportHook.class.isAssignableFrom(behavior)) throw new IllegalStateException("MAID_TRANSPORT_HOOK_UNAVAILABLE");
        if (!NativeMaidTransportScope.ContainerDirtyHook.class.isAssignableFrom(BlockEntity.class))
            throw new IllegalStateException("MAID_TRANSPORT_SAVE_HOOK_UNAVAILABLE");
        bindingPos = wireless.getMethod("getBindingPos", ItemStack.class); exportMode = wireless.getMethod("isMaidToChest", ItemStack.class);
        blacklistMode = wireless.getMethod("isBlacklist", ItemStack.class); exclusions = wireless.getMethod("getSlotConfig", ItemStack.class);
        filters = wireless.getMethod("getFilterList", HolderLookup.Provider.class, ItemStack.class);
        Object holder = Class.forName(PREFIX + "init.InitDataComponent").getField("FILTER_LIST_TAG").get(null);
        filterTag = (DataComponentType<CompoundTag>) ((Supplier<?>) holder).get();
    }
    @SuppressWarnings("unchecked")
    Handle capture(Actor actor, UUID maidId, MaidCarrierBridge.Lease lease, UUID maidGrant, long maidRevision,
            UUID blockGrant, long blockRevision) throws ReflectiveOperationException {
        var player = inventory.requirePlayer(actor);
        var bound = inventory.maids().validateBinding(player, player.getUUID());
        if (bound.binding() == null || !bound.binding().maidId().equals(maidId)) throw new SecurityException("MAID_TRANSPORT_BINDING_CHANGED");
        var maid = inventory.maids().backpackSource(player, player.getUUID(), maidId);
        if (Boolean.TRUE.equals(maid.entity().getClass().getField("guiOpening").get(maid.entity()))) throw new IllegalStateException("MAID_TRANSPORT_GUI_OPEN");
        var worn = (IItemHandler) invoke(maid.entity().getClass().getMethod("getMaidBauble"), maid.entity());
        if (worn.getSlots() != 30) throw new IllegalStateException("MAID_TRANSPORT_BAUBLE_LAYOUT_CHANGED");
        ItemStack stack = null; int selected = -1;
        for (int slot = 0; slot < worn.getSlots(); slot++) {
            var value = worn.getStackInSlot(slot);
            if (!value.isEmpty() && value.getItem().getClass() == wireless) {
                if (stack != null || value.getCount() != 1) throw new IllegalStateException("MAID_TRANSPORT_SINGLE_WORN_BAUBLE_REQUIRED");
                stack = value; selected = slot;
            }
        }
        if (stack == null) throw new IllegalStateException("MAID_TRANSPORT_REAL_WORN_BAUBLE_REQUIRED");
        // Native ItemStackHandler.deserializeNBT trusts the tag's Size and allocates it. Bound
        // that real native field before calling getFilterList, then isolate all nested components.
        requireFiniteFilterTag(stack.get(filterTag));
        if (ItemSnapshotCodec.capture(stack, player.registryAccess()).itemJson().length() > 262144)
            throw new IllegalStateException("MAID_TRANSPORT_BAUBLE_COMPONENT_LIMIT");
        var target = (BlockPos) invoke(bindingPos, null, stack);
        if (target == null) throw new IllegalStateException("MAID_TRANSPORT_REAL_BINDING_REQUIRED");
        var grant = inventory.grants(actor).require(actor, blockGrant, blockRevision, !(boolean) invoke(exportMode, null, stack));
        if (grant.source().kind() != MaterialSourceGrants.Kind.BLOCK) throw new SecurityException("MAID_TRANSPORT_BLOCK_GRANT_REQUIRED");
        var locator = grant.source().locator().split("\\|", -1);
        if (locator.length != 4 || !locator[1].equals(maid.dimension()) || Long.parseLong(locator[2]) != target.asLong())
            throw new SecurityException("MAID_TRANSPORT_BOUND_CONTAINER_GRANT_REQUIRED");
        var level = player.serverLevel();
        if (!level.hasChunkAt(target) || !level.getWorldBorder().isWithinBounds(target)) throw new IllegalStateException("MAID_TRANSPORT_TARGET_UNLOADED");
        var block = level.getBlockEntity(target);
        // These exact native ChestManager types have finite audited vanilla physical containers.
        if (block == null || !Set.of(ChestBlockEntity.class, TrappedChestBlockEntity.class, BarrelBlockEntity.class).contains(block.getClass()))
            throw new IllegalStateException("MAID_TRANSPORT_NATIVE_CONTAINER_UNSUPPORTED");
        if (block instanceof ChestBlockEntity && ChestBlockEntity.getOpenCount(level, target) > 0)
            throw new IllegalStateException("MAID_TRANSPORT_NATIVE_CONTAINER_OPEN");
        float radius = ((Number) invoke(maid.entity().getClass().getMethod("getRestrictRadius"), maid.entity())).floatValue();
        if (!Float.isFinite(radius) || radius < 0 || maid.entity().blockPosition().distSqr(target) > (double) radius * radius)
            throw new SecurityException("MAID_TRANSPORT_OUTSIDE_NATIVE_RANGE");
        boolean toChest = (boolean) invoke(exportMode, null, stack), blacklist = (boolean) invoke(blacklistMode, null, stack);
        var original = (List<Boolean>) invoke(exclusions, null, stack);
        if (original == null || original.size() != 38 || original.stream().anyMatch(Objects::isNull)
                || !original.get(36) || !original.get(37)) throw new SecurityException("MAID_TRANSPORT_EXCLUDE_BOTH_HANDS_IN_NATIVE_BAUBLE");
        var excluded = new ArrayList<>(original); excluded.set(maid.slots(), original.get(36)); excluded.set(maid.slots() + 1, original.get(37));
        var nativeMaid = (IItemHandler) invoke(maid.entity().getClass().getMethod("getAvailableInv", boolean.class), maid.entity(), false);
        var nativeChest = level.getCapability(Capabilities.ItemHandler.BLOCK, target, (Direction) null);
        if (nativeChest == null) throw new IllegalStateException("MAID_TRANSPORT_NATIVE_CAPABILITY_MISSING");
        var maidView = inventory.maidTransportView(actor, maidId, lease, maidGrant, maidRevision, toChest);
        var blockView = inventory.maidTransportView(actor, maidId, lease, blockGrant, blockRevision, !toChest);
        if (!Collections.disjoint(maidView.pools(), blockView.pools()) || !Collections.disjoint(maidView.backends(), blockView.backends()))
            throw new SecurityException("MAID_TRANSPORT_SOURCE_DESTINATION_ALIAS");
        var safeMaid = maidView.bindNative(nativeMaid); var safeChest = blockView.bindNative(nativeChest);
        Object instance = behavior.getConstructor().newInstance();
        Method method = behavior.getDeclaredMethod(toChest ? "maidToChest" : "chestToMaid", IItemHandler.class, IItemHandler.class,
                boolean.class, IItemHandler.class, List.class);
        if (!method.trySetAccessible()) throw new IllegalAccessException("MAID_TRANSPORT_NATIVE_HELPER_UNAVAILABLE");
        var filter = (IItemHandler) invoke(filters, null, player.registryAccess(), stack);
        if (filter.getSlots() != 9) throw new IllegalStateException("MAID_TRANSPORT_FILTER_LIMIT");
        return new Handle(player, player.connection, maid.entity(), stack, selected, target.immutable(), toChest, blacklist,
                List.copyOf(excluded), filter, nativeMaid, nativeChest, toChest ? safeMaid : safeChest,
                toChest ? safeChest : safeMaid, maidView, blockView, MaidNativeRevision.revision(maid.entity()), bound.binding().revision(), instance, method);
    }
    static void requireFiniteFilterTag(CompoundTag tag) {
        if (tag == null) return;
        if (tag.contains("Size") && (!tag.contains("Size", Tag.TAG_INT) || tag.getInt("Size") != 9)
                || tag.contains("Items") && !tag.contains("Items", Tag.TAG_LIST)) throw new IllegalArgumentException("MAID_TRANSPORT_FILTER_LAYOUT_INVALID");
        var items = tag.getList("Items", Tag.TAG_COMPOUND);
        if (items.size() > 9) throw new IllegalArgumentException("MAID_TRANSPORT_FILTER_LAYOUT_INVALID");
        var slots = new HashSet<Integer>();
        for (int i = 0; i < items.size(); i++) {
            var item = items.getCompound(i); int slot = item.getInt("Slot");
            if (!item.contains("Slot", Tag.TAG_INT) || slot < 0 || slot >= 9 || !slots.add(slot))
                throw new IllegalArgumentException("MAID_TRANSPORT_FILTER_LAYOUT_INVALID");
        }
    }
    static List<MaterialStack> snapshot(IItemHandler handler, ServerPlayer player) {
        if (handler.getSlots() < 1 || handler.getSlots() > 130) throw new IllegalStateException("MAID_TRANSPORT_SLOT_LIMIT");
        var result = new ArrayList<MaterialStack>(); int bytes = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            var stack = ItemSnapshotCodec.capture(handler.getStackInSlot(slot), player.registryAccess());
            bytes = Math.addExact(bytes, stack.itemJson().length()); if (bytes > 262144) throw new IllegalStateException("MAID_TRANSPORT_SNAPSHOT_LIMIT");
            result.add(stack);
        }
        return List.copyOf(result);
    }
    static Simulation simulate(Handle handle) {
        var player = handle.player(); var source = new Shadow(handle.source(), player); var destination = new Shadow(handle.destination(), player);
        var beforeSource = snapshot(source, player); var beforeDestination = snapshot(destination, player);
        var matching = new ArrayList<Integer>(); var pending = new ArrayList<MaidTransportPlan.Placement>();
        var moves = new ArrayList<MaidTransportPlan.Move>();
        for (int slot = 0; slot < source.getSlots(); slot++) {
            if (handle.toChest() && excluded(handle.excluded(), slot)) continue;
            var stack = source.getStackInSlot(slot); if (stack.isEmpty()) continue;
            boolean matched = false;
            for (int filter = 0; filter < handle.filter().getSlots(); filter++)
                if (ItemStack.isSameItem(stack, handle.filter().getStackInSlot(filter))) { matched = true; break; }
            if (matched != handle.blacklist()) matching.add(slot);
        }
        destination.inserted = (slot, stack) -> pending.add(new MaidTransportPlan.Placement(slot, stack.getCount()));
        source.extracted = (slot, stack) -> {
            if (!stack.isEmpty()) moves.add(new MaidTransportPlan.Move(slot, ItemSnapshotCodec.capture(stack, player.registryAccess()), List.copyOf(pending)));
            else if (!pending.isEmpty()) throw new IllegalStateException("MAID_TRANSPORT_NATIVE_SHADOW_MISMATCH");
            pending.clear();
        };
        NativeMaidTransportScope.virtual(() -> {
            try { invoke(handle.helper(), handle.behavior(), source, destination, handle.blacklist(), handle.filter(), handle.excluded()); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("MAID_TRANSPORT_NATIVE_HELPER_FAILED", failure); }
        });
        if (!pending.isEmpty()) throw new IllegalStateException("MAID_TRANSPORT_NATIVE_SHADOW_MISMATCH");
        var afterSource = snapshot(source, player); var afterDestination = snapshot(destination, player);
        MaidTransportConservation.require(beforeSource, beforeDestination, afterSource, afterDestination, List.of());
        return new Simulation(beforeSource, beforeDestination, List.copyOf(matching), List.copyOf(moves), afterSource, afterDestination);
    }
    static Transfer transfer(Handle handle, MaidTransportPlan plan, TaskControl control, Runnable revalidate) {
        var player = handle.player(); var source = handle.source(); var destination = handle.destination();
        var expectedSource = new ArrayList<>(plan.source()); var expectedDestination = new ArrayList<>(plan.destination());
        var custody = new ArrayList<MaterialStack>(); boolean entered = false; String reason = "";
        long started = System.nanoTime();
        try {
            for (var move : plan.moves()) {
                control.check();
                if (System.nanoTime() - started >= 2_000_000 && entered) throw new IllegalStateException("MAID_TRANSPORT_TICK_BUDGET");
                revalidate.run();
                if (!snapshot(source, player).equals(expectedSource) || !snapshot(destination, player).equals(expectedDestination))
                    throw new IllegalStateException("MAID_TRANSPORT_INVENTORY_CHANGED");
                ItemStack product = ItemSnapshotCodec.restore(move.stack(), player.registryAccess());
                if (!ItemSnapshotCodec.capture(source.extractItem(move.sourceSlot(), product.getCount(), true), player.registryAccess()).equals(move.stack()))
                    throw new IllegalStateException("MAID_TRANSPORT_EXTRACTION_CHANGED");
                for (var placement : move.placements()) {
                    var part = product.copyWithCount(placement.count());
                    if (!destination.insertItem(placement.slot(), part, true).isEmpty()) throw new IllegalStateException("MAID_TRANSPORT_DESTINATION_CHANGED");
                }
                control.check(); entered = true;
                ItemStack taken = source.extractItem(move.sourceSlot(), product.getCount(), false);
                MaterialStack actual = ItemSnapshotCodec.capture(taken, player.registryAccess());
                // Retain the actual return even when a later source witness cannot be read.
                // Unverified custody is recorded but can never enter the return tool.
                if (!actual.empty()) custody.add(actual);
                var previous = expectedSource.get(move.sourceSlot());
                if (actual.empty() || !actual.itemJson().equals(move.stack().itemJson()) || actual.count() > move.stack().count())
                    throw new IllegalStateException("MAID_TRANSPORT_EXTRACTION_UNCERTAIN");
                expectedSource.set(move.sourceSlot(), previous.withCount(previous.count() - actual.count()));
                if (!snapshot(source, player).equals(expectedSource)) throw new IllegalStateException("MAID_TRANSPORT_EXTRACTION_UNCERTAIN");
                if (!actual.equals(move.stack())) throw new IllegalStateException("MAID_TRANSPORT_PARTIAL_EXTRACTION_CUSTODY_HELD");
                for (var placement : move.placements()) {
                    control.check(); revalidate.run();
                    var part = taken.copyWithCount(placement.count());
                    var remainder = destination.insertItem(placement.slot(), part, false);
                    int accepted = part.getCount() - remainder.getCount();
                    if (accepted < 0 || accepted > part.getCount() || !remainder.isEmpty() && !ItemStack.isSameItemSameComponents(part, remainder))
                        throw new IllegalStateException("MAID_TRANSPORT_INSERTION_UNCERTAIN");
                    var before = expectedDestination.get(placement.slot());
                    var expected = before.empty() ? actual.withCount(accepted) : before.withCount(before.count() + accepted);
                    expectedDestination.set(placement.slot(), expected);
                    if (!snapshot(destination, player).equals(expectedDestination)) throw new IllegalStateException("MAID_TRANSPORT_INSERTION_UNCERTAIN");
                    var held = custody.removeLast(); if (held.count() > accepted) custody.add(held.withCount(held.count() - accepted));
                    if (accepted != placement.count()) throw new IllegalStateException("MAID_TRANSPORT_PARTIAL_INSERTION_CUSTODY_HELD");
                }
            }
        } catch (RuntimeException failure) { reason = safe(failure); }
        List<MaterialStack> afterSource, afterDestination;
        try { afterSource = snapshot(source, player); afterDestination = snapshot(destination, player); }
        catch (RuntimeException changed) { return new Transfer(entered, false, false, reason.isEmpty() ? safe(changed) : reason, List.of(), List.of(), custody); }
        boolean verified;
        try { MaidTransportConservation.require(plan.source(), plan.destination(), afterSource, afterDestination, custody); verified = !reason.contains("UNCERTAIN"); }
        catch (RuntimeException uncertain) { verified = false; if (reason.isEmpty()) reason = safe(uncertain); }
        boolean achieved = verified && custody.isEmpty() && afterSource.equals(plan.expectedSource()) && afterDestination.equals(plan.expectedDestination());
        if (!achieved && reason.isEmpty()) reason = "MAID_TRANSPORT_NATIVE_NO_MOVE";
        return new Transfer(entered, verified, achieved && !plan.moves().isEmpty(), reason, afterSource, afterDestination, custody);
    }
    static String fingerprint(Handle handle) { return AiJson.digest(AiJson.canonical(ItemSnapshotCodec.capture(handle.bauble(), handle.player().registryAccess()))); }
    static boolean excluded(List<Boolean> list, int slot) { return slot < list.size() && list.get(slot); }
    static Object invoke(Method method, Object owner, Object... args) throws ReflectiveOperationException {
        try { return method.invoke(owner, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new ReflectiveOperationException("MAID_TRANSPORT_NATIVE_API_FAILED", failure.getCause());
        }
    }
    static String safe(Throwable failure) {
        String reason = failure.getMessage(); return reason != null && reason.matches("[A-Z][A-Z0-9_]{1,100}") ? reason : "MAID_TRANSPORT_NATIVE_FAILURE";
    }
    @FunctionalInterface private interface Observed { void apply(int slot, ItemStack stack); }
    private static final class Shadow extends ItemStackHandler {
        private final IItemHandler authority;
        Observed inserted = (slot, stack) -> {}, extracted = (slot, stack) -> {};
        Shadow(IItemHandler authority, ServerPlayer player) {
            super(authority.getSlots()); this.authority = authority;
            for (int slot = 0; slot < getSlots(); slot++) setStackInSlot(slot, RecipeInputIsolation.copy(authority.getStackInSlot(slot), player.registryAccess()));
        }
        @Override public int getSlotLimit(int slot) { return authority.getSlotLimit(slot); }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return authority.isItemValid(slot, stack); }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            var remainder = super.insertItem(slot, stack, simulate);
            if (!simulate && stack.getCount() > remainder.getCount()) inserted.apply(slot, stack.copyWithCount(stack.getCount() - remainder.getCount()));
            return remainder;
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            var result = super.extractItem(slot, amount, simulate); if (!simulate) extracted.apply(slot, result.copy()); return result;
        }
    }
}
