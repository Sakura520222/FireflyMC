package firefly520.fireflymc.ai.integration.maid;

import com.mojang.datafixers.util.Pair;
import firefly520.fireflymc.ai.blueprint.NativeConstructionSave;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.integration.equipment.ReadOnlyNativeAccess;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemNameBlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;

/** Finite actions through the real MaidFarmPlantTask, with its real final checks and original inventory. */
final class MaidFarmNative {
    private static final String ROOT = "com.github.tartaricacid.touhoulittlemaid.";
    private static final String ENTITY = ROOT + "entity.passive.EntityMaid";
    private static final String TASK = ROOT + "api.task.IMaidTask";
    private static final String FARM = ROOT + "api.task.IFarmTask";
    private static final Set<String> CROPS = Set.of("minecraft:wheat", "minecraft:carrots", "minecraft:potatoes", "minecraft:beetroots");
    private static final Set<String> ORDINARY_BACKPACKS = Set.of("EmptyBackpack", "SmallBackpack", "MiddleBackpack", "BigBackpack");
    private final MaterialInventoryService inventory;
    private final InstalledPermissionAdapters permissions;
    private final MaidControlNative control;
    private final ReadOnlyNativeAccess api = new ReadOnlyNativeAccess();
    private final Map<Object, Object> rates = new WeakHashMap<>();
    private Method nativeRate;

    record State(MaidControlNative.Handle maid, Object farm, MaidCarrierBridge.BackpackSource source,
                 MaterialSourceGrants.Grant grant, BlockState crop, BlockState soil,
                 List<MaterialStack> items, MaterialStack hand, MaterialStack offHand) {}
    record RecoveryState(MaidControlNative.Handle maid, String backpackId, List<MaterialStack> items, MaterialStack hand,
                         MaterialStack offHand, BlockState crop, BlockState soil) {}
    record Outcome(boolean entered, boolean verified, boolean achieved, MaidFarmPlan.BlockView crop, MaidFarmPlan.BlockView soil,
                   List<MaterialStack> inventory, MaterialStack hand, MaterialStack offHand,
                   Set<BlockPos> changedPositions, String reason, NativeSaveBarrier.Receipt save) {}

    MaidFarmNative(MaterialInventoryService inventory, InstalledPermissionAdapters permissions, MaidControlNative control) {
        this.inventory = inventory; this.permissions = permissions; this.control = control;
    }
    State inspect(ServerPlayer player, Actor actor, MaidFarmPlan.Kind kind, BlockPos cropPos, UUID sourceId, int seedSlot) throws Exception {
        return inspect(player, actor, kind, cropPos, sourceId, seedSlot, true);
    }
    State inspect(ServerPlayer player, Actor actor, MaidFarmPlan.Kind kind, BlockPos cropPos, UUID sourceId, int seedSlot, boolean requireReach) throws Exception {
        var maid = control.capture(player, player.getUUID());
        if (maid.entity().isNoAi() || !maid.state().brainMoving() || maid.state().seated()) throw new IllegalStateException("MAID_NATIVE_WORK_NOT_MOVING");
        if (!maid.entity().getBrain().isActive(Activity.WORK)) throw new IllegalStateException("MAID_NATIVE_SCHEDULE_NOT_WORKING");
        if (!permissions.checkBooleanPermission(player, "fireflymc.ai.maid.farm", true).allowed()) throw new SecurityException("MAID_FARM_PERMISSION_DENIED");
        var grant = inventory.grants(actor).list(actor).stream().filter(value -> value.id().equals(sourceId)).findFirst()
                .orElseThrow(() -> new SecurityException("SOURCE_AUTHORIZATION_CHANGED"));
        inventory.grants(actor).require(actor, grant.id(), grant.revision(), kind == MaidFarmPlan.Kind.PLANT);
        var selected = inventory.selectMaidBackpack(actor, maid.entity().getUUID());
        if (grant.source().kind() != MaterialSourceGrants.Kind.MAID_BACKPACK || !grant.source().equals(selected.candidate()))
            throw new SecurityException("MAID_FARM_SOURCE_CHANGED");
        var source = inventory.maids().backpackSource(player, player.getUUID(), maid.entity().getUUID());
        String backpack = source.backpackType().substring(source.backpackType().lastIndexOf('.') + 1);
        if (!ORDINARY_BACKPACKS.contains(backpack)) throw new IllegalStateException("MAID_FARM_AUTOMATING_BACKPACK_UNSUPPORTED");
        IItemHandler baubles = (IItemHandler) api.query(maid.entity(), ENTITY, "getMaidBauble", api.type(ROOT + "inventory.handler.BaubleItemHandler"));
        for (int slot = 0; slot < baubles.getSlots(); slot++) if (!baubles.getStackInSlot(slot).isEmpty()) throw new IllegalStateException("MAID_FARM_BAUBLE_AUTOMATION_REQUIRES_ADAPTER");
        var level = player.serverLevel(); BlockPos soilPos = cropPos.below();
        var verdict = kind == MaidFarmPlan.Kind.HARVEST ? permissions.checkBreak(player, level, cropPos, Direction.UP) : permissions.checkPlace(player, level, cropPos);
        if (!verdict.allowed()) throw new SecurityException(verdict.code());
        if (!level.hasChunkAt(soilPos) || !level.getFluidState(cropPos).isEmpty()) throw new IllegalStateException("MAID_FARM_WORLD_CONTEXT_CHANGED");
        BlockState crop = level.getBlockState(cropPos), soil = level.getBlockState(soilPos);
        if (!soil.is(Blocks.FARMLAND) || crop.hasBlockEntity() || soil.hasBlockEntity()) throw new IllegalStateException("MAID_FARM_BLOCK_UNSUPPORTED");
        Object farm = farm();
        double reach = (double) api.query(farm, FARM, "getCloseEnoughDist", double.class);
        if (requireReach && maid.entity().distanceToSqr(new BlockPosTracker(soilPos).currentPosition()) > reach * reach)
            throw new IllegalStateException("MAID_FARM_NATIVE_REACH_REQUIRED");
        float range = maid.state().restrictRadius();
        if (range <= 0 || maid.entity().blockPosition().distSqr(soilPos) > (double) range * range)
            throw new IllegalStateException("MAID_FARM_NATIVE_SEARCH_RANGE_REQUIRED");
        if (!(boolean) api.call(maid.entity(), ENTITY, "isWithinRestriction", boolean.class, new Class<?>[] { BlockPos.class }, soilPos))
            throw new SecurityException("MAID_FARM_OUTSIDE_NATIVE_RESTRICTION");
        if (!maid.entity().getMainHandItem().isEmpty() || !maid.entity().getOffhandItem().isEmpty())
            throw new IllegalStateException("MAID_FARM_HELD_ITEM_REQUIRES_ADAPTER");
        var cropHandlers = (Map<?, ?>) api.query(null, ROOT + "entity.task.crop.SpecialCropManager", "getBlockCropHandlers", Map.class);
        if (cropHandlers.containsKey(crop.getBlock())) throw new IllegalStateException("MAID_FARM_SPECIAL_CROP_UNSUPPORTED");
        if (kind == MaidFarmPlan.Kind.HARVEST) {
            if (!CROPS.contains(BuiltInRegistries.BLOCK.getKey(crop.getBlock()).toString()) || !(crop.getBlock() instanceof CropBlock cropBlock)
                    || !cropBlock.isMaxAge(crop)) throw new IllegalStateException("MAID_FARM_MATURE_VANILLA_CROP_REQUIRED");
            int empty = 0; for (int slot = 0; slot < source.inventory().getSlots(); slot++) if (source.inventory().getStackInSlot(slot).isEmpty()) empty++;
            if (empty < 3) throw new IllegalStateException("MAID_FARM_OUTPUT_SPACE_REQUIRED");
        } else {
            if (!crop.isAir() || seedSlot < 0 || seedSlot >= source.inventory().getSlots()) throw new IllegalStateException("MAID_FARM_PLANT_CONTEXT_CHANGED");
            var seeds = (Map<?, ?>) api.query(null, ROOT + "entity.task.crop.SpecialCropManager", "getItemSeedHandlers", Map.class);
            int actualSlot = -1;
            for (int slot = 0; slot < source.inventory().getSlots(); slot++) {
                ItemStack item = source.inventory().getStackInSlot(slot);
                if (!item.isEmpty() && (boolean) api.call(farm, FARM, "isSeed", boolean.class, new Class<?>[] {ItemStack.class}, item)
                        && (boolean) api.call(farm, FARM, "canPlant", boolean.class,
                            new Class<?>[] {api.type(ENTITY), BlockPos.class, BlockState.class, ItemStack.class}, maid.entity(), soilPos, soil, item)) {
                    actualSlot = slot;
                    if (!(item.getItem() instanceof ItemNameBlockItem seed) || seeds.containsKey(item.getItem())
                            || !CROPS.contains(BuiltInRegistries.BLOCK.getKey(seed.getBlock()).toString())) throw new IllegalStateException("MAID_FARM_SEED_UNSUPPORTED");
                    break;
                }
            }
            if (actualSlot != seedSlot) throw new IllegalStateException("MAID_FARM_NATIVE_SEED_ORDER_CHANGED");
        }
        return new State(maid, farm, source, grant, crop, soil, snapshot(source.inventory(), player),
                ItemSnapshotCodec.capture(maid.entity().getMainHandItem(), player.registryAccess()), ItemSnapshotCodec.capture(maid.entity().getOffhandItem(), player.registryAccess()));
    }
    boolean reached(State state, BlockPos soil) throws Exception {
        double reach = (double) api.query(state.farm(), FARM, "getCloseEnoughDist", double.class);
        return state.maid().entity().distanceToSqr(new BlockPosTracker(soil).currentPosition()) <= reach * reach;
    }
    void navigate(State state, BlockPos soil) {
        net.minecraft.world.entity.ai.behavior.BehaviorUtils.setWalkAndLookTargetMemories(state.maid().entity(), soil, .6f, 0);
    }
    void stopNavigation(State expected, BlockPos soil, UUID owner) {
        var maid = expected.maid().entity();
        if (maid.isRemoved() || !maid.isAlive() || !owner.equals(maid.getOwnerUUID())) return;
        try {
            if (MaidNativeRevision.revision(maid) != expected.maid().revision()
                    || api.query(maid, ENTITY, "getTask", api.type(TASK)) != expected.maid().task()) return;
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) { return; }
        var target = maid.getBrain().getMemory(MemoryModuleType.WALK_TARGET);
        if (target.isPresent() && target.get().getTarget().currentBlockPosition().equals(soil)) {
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET); maid.getNavigation().stop();
        }
    }
    private Object farm() throws Exception {
        var found = (Optional<?>) api.call(null, ROOT + "entity.task.TaskManager", "findTask", Optional.class,
                new Class<?>[] {ResourceLocation.class}, ResourceLocation.parse("touhou_little_maid:farm"));
        var task = found.orElseThrow(() -> new IllegalStateException("MAID_FARM_NATIVE_TASK_NOT_REGISTERED"));
        if (!task.getClass().getName().equals(ROOT + "entity.task.TaskNormalFarm")) throw new IllegalStateException("MAID_FARM_NATIVE_TASK_REPLACED");
        return task;
    }
    RecoveryState recovery(ServerPlayer player, Actor actor, MaidFarmPlan original) throws Exception {
        var maid = control.inspectRecovery(player, player.getUUID(), original.maid().maidId());
        new MaidEntityPermission(player.server, firefly520.fireflymc.ai.AiServerService::authenticated, permissions)
                .require(player, player.getUUID(), maid.entity());
        if (!player.serverLevel().dimension().location().toString().equals(original.maid().dimension())
                || !player.serverLevel().hasChunkAt(original.cropPos()) || !player.serverLevel().hasChunkAt(original.soilPos()))
            throw new IllegalStateException("MAID_FARM_RECOVERY_WORLD_UNLOADED");
        Object backpack = api.query(maid.entity(), ENTITY, "getMaidBackpackType", api.type(ROOT + "api.backpack.IMaidBackpack"));
        String name = backpack.getClass().getName();
        if (!name.startsWith(ROOT + "entity.backpack.") || !ORDINARY_BACKPACKS.contains(name.substring((ROOT + "entity.backpack.").length())))
            throw new IllegalStateException("MAID_FARM_RECOVERY_BACKPACK_UNSUPPORTED");
        String id = ((ResourceLocation) api.query(backpack, ROOT + "api.backpack.IMaidBackpack", "getId", ResourceLocation.class)).toString();
        var handler = (IItemHandler) api.query(maid.entity(), ENTITY, "getAvailableBackpackInv", CombinedInvWrapper.class);
        if (!handler.getClass().getName().equals(ROOT + "inventory.handler.MaidInvWrapper") || handler.getSlots() < 1 || handler.getSlots() > 36)
            throw new IllegalStateException("MAID_FARM_RECOVERY_INVENTORY_API_CHANGED");
        return new RecoveryState(maid, id, snapshot(handler, player), ItemSnapshotCodec.capture(maid.entity().getMainHandItem(), player.registryAccess()),
                ItemSnapshotCodec.capture(maid.entity().getOffhandItem(), player.registryAccess()), player.serverLevel().getBlockState(original.cropPos()), player.serverLevel().getBlockState(original.soilPos()));
    }
    boolean rateReady(State state, ServerLevel level) throws Exception {
        Object rate = rates.get(state.maid().entity());
        if (rate == null) {
            rate = api.type(ROOT + "entity.ai.brain.task.MaidFarmMoveTask").getConstructor(api.type(FARM), float.class).newInstance(state.farm(), .6f);
            rates.put(state.maid().entity(), rate);
        }
        if (nativeRate == null) {
            nativeRate = api.type(ROOT + "entity.ai.brain.task.MaidCheckRateTask").getDeclaredMethod("checkExtraStartConditions", ServerLevel.class, api.type(ENTITY));
            if (!nativeRate.trySetAccessible()) throw new IllegalStateException("MAID_FARM_NATIVE_RATE_API_UNAVAILABLE");
        }
        return (boolean) nativeRate.invoke(rate, level, state.maid().entity());
    }
    Outcome apply(ServerPlayer player, Actor actor, MaidFarmPlan plan, State expected) {
        NativeSaveBarrier.Receipt receipt = null; boolean entered = false;
        var audit = MaidFarmSavedDataScope.begin(); NativeConstructionSave observedScope = null; State observedState = null;
        try (audit; var scope = NativeConstructionSave.begin(player)) {
            observedScope = scope;
            if (plan.kind() == MaidFarmPlan.Kind.HARVEST) {
                scope.allowRandomSequences(player.serverLevel());
            }
            var current = inspect(player, actor, plan.kind(), plan.cropPos(), plan.sourceId(), plan.seedSlot());
            observedState = current;
            requireSame(plan, expected, current);
            if (!(boolean) api.call(current.farm(), TASK, "isEnable", boolean.class, new Class<?>[] {api.type(ENTITY)}, current.maid().entity()))
                return failed(false, current, "MAID_FARM_NATIVE_TASK_DISABLED", null, Set.of());
            Event event = (Event) api.type(ROOT + "api.event.MaidTaskEnableEvent").getConstructor(api.type(TASK), api.type(ENTITY))
                    .newInstance(current.farm(), current.maid().entity());
            NeoForge.EVENT_BUS.post(event);
            if (((ICancellableEvent) event).isCanceled()) return failed(false, current, "MAID_FARM_NATIVE_ENABLE_DENIED", null, Set.of());
            requireSame(plan, expected, inspect(player, actor, plan.kind(), plan.cropPos(), plan.sourceId(), plan.seedSlot()));
            Object supplier = api.type(ROOT + "init.InitEntities").getField("TARGET_POS").get(null);
            @SuppressWarnings("unchecked") MemoryModuleType<net.minecraft.world.entity.ai.behavior.PositionTracker> memory = (MemoryModuleType<net.minecraft.world.entity.ai.behavior.PositionTracker>) ((Supplier<?>) supplier).get();
            var brain = current.maid().entity().getBrain();
            if (brain.getMemory(memory).isPresent()) throw new IllegalStateException("MAID_NATIVE_TARGET_BUSY");
            var task = (Behavior<?>) api.type(ROOT + "entity.ai.brain.task.MaidFarmPlantTask").getConstructor(api.type(FARM)).newInstance(current.farm());
            brain.setMemory(memory, new BlockPosTracker(plan.soilPos()));
            entered = true;
            @SuppressWarnings("unchecked") var behavior = (Behavior<net.minecraft.world.entity.LivingEntity>) task;
            boolean started;
            try { started = behavior.tryStart(player.serverLevel(), current.maid().entity(), player.serverLevel().getGameTime()); }
            finally { brain.eraseMemory(memory); }
            behavior.doStop(player.serverLevel(), current.maid().entity(), player.serverLevel().getGameTime());
            var afterItems = snapshot(current.source().inventory(), player); var crop = player.serverLevel().getBlockState(plan.cropPos());
            var soil = player.serverLevel().getBlockState(plan.soilPos()); var positions = scope.changedPositions();
            var stores = new ArrayList<CompletableFuture<NativeSaveBarrier.Receipt>>();
            stores.add(CompletableFuture.completedFuture(scope.finish()));
            stores.add(CompletableFuture.completedFuture(inventory.nativeSaves().changed(List.of(NativeSaveObserver.entity(current.maid().entity())))));
            receipt = NativeSaveBarrier.combine(stores);
            boolean noProgress = positions.stream().allMatch(pos -> pos.equals(plan.cropPos())) && block(crop).equals(plan.crop()) && block(soil).equals(plan.soil()) && current.items().equals(afterItems)
                    && current.maid().entity().getMainHandItem().isEmpty() && current.maid().entity().getOffhandItem().isEmpty();
            boolean valid = started && positions.equals(Set.of(plan.cropPos())) && block(soil).equals(plan.soil())
                    && current.maid().entity().getMainHandItem().isEmpty() && current.maid().entity().getOffhandItem().isEmpty();
            if (plan.kind() == MaidFarmPlan.Kind.HARVEST) {
                valid &= crop.getBlock() == current.crop().getBlock() && crop.getBlock() instanceof CropBlock cropBlock && cropBlock.getAge(crop) == 0
                        && nonNegativeDifference(current.items(), afterItems);
            } else {
                var beforeSeed = current.source().inventory();
                var selectedBefore = current.items().get(plan.seedSlot());
                var expectedItems = new ArrayList<>(current.items()); expectedItems.set(plan.seedSlot(), selectedBefore.withCount(selectedBefore.count() - 1));
                ItemStack seedStack = ItemSnapshotCodec.restore(selectedBefore, player.registryAccess());
                valid &= seedStack.getItem() instanceof ItemNameBlockItem seed && crop.getBlock() == seed.getBlock()
                        && crop.getBlock() instanceof CropBlock cropBlock && cropBlock.getAge(crop) == 0 && expectedItems.equals(afterItems);
            }
            return new Outcome(true, valid || noProgress, valid, block(crop), block(soil), afterItems, ItemSnapshotCodec.capture(current.maid().entity().getMainHandItem(), player.registryAccess()),
                    ItemSnapshotCodec.capture(current.maid().entity().getOffhandItem(), player.registryAccess()), positions,
                    valid ? "" : noProgress ? "MAID_FARM_NATIVE_NO_PROGRESS" : "MAID_FARM_NATIVE_EFFECT_REQUIRES_REVIEW", receipt);
        } catch (Exception | LinkageError failure) {
            String reason = code(failure); if (!audit.descriptions().isEmpty()) reason += ";savedData=" + String.join(" | ", audit.descriptions());
            var changed = observedScope == null ? Set.<BlockPos>of() : observedScope.changedPositions();
            if (observedState != null && UUID.fromString(actor.owner()).equals(observedState.maid().entity().getOwnerUUID())
                    && observedState.maid().entity().level() == player.serverLevel())
                return new Outcome(entered, false, false, block(player.serverLevel().getBlockState(plan.cropPos())), block(player.serverLevel().getBlockState(plan.soilPos())),
                        snapshot(observedState.source().inventory(), player), ItemSnapshotCodec.capture(observedState.maid().entity().getMainHandItem(), player.registryAccess()),
                        ItemSnapshotCodec.capture(observedState.maid().entity().getOffhandItem(), player.registryAccess()), changed, reason, receipt);
            return new Outcome(entered, false, false, null, null, List.of(), MaterialStack.EMPTY, MaterialStack.EMPTY, changed, reason, receipt);
        }
    }
    static void requireSame(MaidFarmPlan plan, State expected, State current) {
        if (expected.maid().entity() != current.maid().entity() || expected.maid().task() != current.maid().task() || expected.maid().revision() != current.maid().revision()
                || expected.farm() != current.farm()
                || !plan.maid().equals(current.maid().state()) || !block(current.crop()).equals(plan.crop()) || !block(current.soil()).equals(plan.soil())
                || !plan.inventory().equals(current.items()) || !plan.mainHand().equals(current.hand()) || !plan.offHand().equals(current.offHand())
                || current.grant().revision() != plan.sourceRevision() || !current.grant().source().digest().equals(plan.sourceDigest()))
            throw new IllegalStateException("MAID_FARM_PREVIEW_CHANGED");
    }
    private static Outcome failed(boolean entered, State state, String reason, NativeSaveBarrier.Receipt save, Set<BlockPos> changed) {
        return new Outcome(entered, false, false, block(state.crop()), block(state.soil()), state.items(), state.hand(), state.offHand(), changed, reason, save);
    }
    static MaidFarmPlan.BlockView block(BlockState state) {
        var values = new TreeMap<String, String>();
        state.getValues().forEach((property, value) -> values.put(property.getName(), propertyValue(property, value)));
        return new MaidFarmPlan.BlockView(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), values);
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static String propertyValue(Property property, Comparable value) { return property.getName(value); }
    static List<MaterialStack> snapshot(IItemHandler source, ServerPlayer player) {
        var result = new ArrayList<MaterialStack>(); for (int slot = 0; slot < source.getSlots(); slot++) result.add(ItemSnapshotCodec.capture(source.getStackInSlot(slot), player.registryAccess()));
        return List.copyOf(result);
    }
    static Map<String, Long> totals(List<MaterialStack> stacks) {
        var values = new TreeMap<String, Long>(); stacks.stream().filter(stack -> !stack.empty()).forEach(stack -> values.merge(stack.itemJson(), (long) stack.count(), Math::addExact)); return values;
    }
    private static boolean nonNegativeDifference(List<MaterialStack> before, List<MaterialStack> after) {
        var counts = totals(after); return totals(before).entrySet().stream().allMatch(entry -> counts.getOrDefault(entry.getKey(), 0L) >= entry.getValue());
    }
    private static String code(Throwable failure) { String message = failure.getMessage(); return message != null && message.matches("[A-Z0-9_]{1,100}") ? message : "MAID_FARM_NATIVE_FAILURE"; }
}
