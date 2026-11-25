package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.blueprint.ConstructionVerification.Function;
import firefly520.fireflymc.ai.blueprint.ConstructionVerification.FunctionalState;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import java.lang.invoke.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.LockCode;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;

/** Only observes registered native objects. It neither fuels a machine nor grants its inventory as a material source. */
final class ConstructionFunctionalVerifier {
    @FunctionalInterface interface ReadGuard { void check(BlockPos position); }
    private static final Set<Class<?>> MANUAL = Set.of(CraftingTableBlock.class, SmithingTableBlock.class, StonecutterBlock.class);
    private static final Set<Class<?>> FURNACES = Set.of(FurnaceBlockEntity.class, BlastFurnaceBlockEntity.class, SmokerBlockEntity.class);
    private static volatile MethodHandle lockKey;
    private ConstructionFunctionalVerifier() {}

    static boolean sameStructure(BlockState expected, BlockState actual) {
        if (!expected.is(actual.getBlock())) return false;
        if (actual.getBlock() instanceof AbstractFurnaceBlock && actual.hasProperty(AbstractFurnaceBlock.LIT))
            actual = actual.setValue(AbstractFurnaceBlock.LIT, expected.getValue(AbstractFurnaceBlock.LIT));
        if (actual.is(Blocks.BARREL)) actual = actual.setValue(BarrelBlock.OPEN, expected.getValue(BarrelBlock.OPEN));
        return expected.equals(actual);
    }

    static Optional<Function> inspect(ServerPlayer owner, BlockPos position, BlockState state,
            BlockEntity acceptedNativeObject, InstalledPermissionAdapters permissions, ReadGuard read) {
        if (!owner.server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        var level = owner.serverLevel(); var block = state.getBlock();
        var point = new BlueprintDocument.Position(position.getX(), position.getY(), position.getZ());
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
        var data = new LinkedHashMap<String, String>();
        if (MANUAL.contains(block.getClass())) {
            boolean ready = state.canSurvive(level, position) && state.getMenuProvider(level, position) != null;
            return Optional.of(function(point, id, "NATIVE_MANUAL_WORKSTATION", ready ? FunctionalState.READY : FunctionalState.WAITING_ACTIVATION,
                    List.of(ready ? "INTERACTION_AVAILABLE_ACTUAL_RECIPE_AND_PLAYER_COSTS_STILL_REQUIRED" : "NATIVE_INTERACTION_UNAVAILABLE"), data));
        }
        if (block instanceof EnchantingTableBlock) {
            float power = 0;
            try {
                for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS) {
                    read.check(position.offset(offset)); read.check(position.offset(offset.getX() / 2, offset.getY(), offset.getZ() / 2));
                    if (EnchantingTableBlock.isValidBookShelf(level, position, offset))
                        power += level.getBlockState(position.offset(offset)).getEnchantPowerBonus(level, position.offset(offset));
                }
            } catch (RuntimeException unavailable) {
                return Optional.of(function(point, id, "NATIVE_ENCHANTMENT_WORKSTATION", FunctionalState.UNVERIFIED,
                        List.of("ENCHANTMENT_SURROUNDINGS_NOT_LOADED_OR_ACCESSIBLE"), data));
            }
            if (!Float.isFinite(power) || power < 0) return Optional.of(function(point, id, "NATIVE_ENCHANTMENT_WORKSTATION",
                    FunctionalState.UNVERIFIED, List.of("NATIVE_ENCHANTMENT_POWER_INVALID"), data));
            data.put("observedNativeShelfPower", Float.toString(power));
            boolean accessible = state.getMenuProvider(level, position) != null && state.canSurvive(level, position);
            return Optional.of(function(point, id, "NATIVE_ENCHANTMENT_WORKSTATION", accessible ? FunctionalState.READY : FunctionalState.WAITING_ACTIVATION,
                    List.of(accessible ? "MANUAL_INTERACTION_READY_ENCHANTMENT_ITEM_LAPIS_LEVEL_AND_MOD_RULES_STILL_APPLY" : "NATIVE_ENCHANTMENT_INTERACTION_UNAVAILABLE"), data));
        }
        if (!(block instanceof AbstractFurnaceBlock) && !(block instanceof ChestBlock) && !(block instanceof BarrelBlock)) {
            if (state.hasBlockEntity()) return Optional.of(function(point, id, "SPECIAL_FUNCTION", FunctionalState.UNVERIFIED,
                    List.of("FUNCTION_ADAPTER_REQUIRED"), data));
            return Optional.empty();
        }
        var entity = level.getBlockEntity(position);
        if (entity == null || entity != acceptedNativeObject || entity.isRemoved() || entity.getLevel() != level) {
            if (block instanceof AbstractFurnaceBlock) data.put("publicLitState", Boolean.toString(state.getValue(AbstractFurnaceBlock.LIT)));
            return Optional.of(function(point, id, "NATIVE_MACHINE", FunctionalState.UNVERIFIED,
                    List.of("NO_CURRENT_NATIVE_IDENTITY_FROM_THIS_ACCEPTED_PLACEMENT_INVENTORY_NOT_READ"), data));
        }
        if (!level.mayInteract(owner, position) || !permissions.checkContainer(owner, level, position, Direction.UP).allowed())
            return Optional.of(function(point, id, "NATIVE_MACHINE", FunctionalState.UNVERIFIED,
                    List.of("CURRENT_CONTAINER_PROTECTION_DENIED_INVENTORY_NOT_READ"), data));
        data.put("identityEvidence", "SAME_LIVE_NATIVE_OBJECT_FROM_ACCEPTED_PLACEMENT");
        try {
            if (entity instanceof BaseContainerBlockEntity container && !unlocks(container, owner))
                return Optional.of(function(point, id, "NATIVE_MACHINE", FunctionalState.WAITING_ACTIVATION,
                        List.of("NATIVE_CONTAINER_LOCK_NOT_SATISFIED"), data));
            if (entity instanceof AbstractFurnaceBlockEntity furnace) {
                if (!FURNACES.contains(entity.getClass())) throw new IllegalStateException("BUILD_FURNACE_ADAPTER_REQUIRED");
                var observed = NativeFurnaceReadiness.inspect(level, furnace); var reasons = new ArrayList<String>();
                data.put("hasInput", Boolean.toString(observed.hasInput())); data.put("recipeMatched", Boolean.toString(observed.recipeMatched()));
                data.put("nativeOutputFits", Boolean.toString(observed.outputFits())); data.put("nativeFuelBurnTicks", Integer.toString(observed.fuelBurnTicks()));
                data.put("nativeRemainingLitTicks", Integer.toString(observed.litTicks())); data.put("contentsDigest", observed.sourceDigest());
                data.put("costPolicy", "NATIVE_WORLD_MACHINE_COSTS");
                if (!observed.hasInput()) reasons.add("NATIVE_INPUT_REQUIRED");
                else if (!observed.recipeMatched()) reasons.add("NATIVE_MATCHING_RECIPE_REQUIRED");
                if (observed.recipeMatched() && !observed.outputFits()) reasons.add("NATIVE_OUTPUT_SPACE_REQUIRED");
                if (observed.litTicks() == 0 && observed.fuelBurnTicks() == 0) reasons.add("NATIVE_FUEL_REQUIRED");
                if (reasons.isEmpty()) reasons.add(observed.litTicks() > 0 ? "NATIVE_PROCESSING_CONDITIONS_OBSERVED" : "NATIVE_CONDITIONS_READY_NOT_STARTED_BY_AI");
                boolean ready = observed.hasInput() && observed.recipeMatched() && observed.outputFits()
                        && (observed.litTicks() > 0 || observed.fuelBurnTicks() > 0);
                return Optional.of(function(point, id, "NATIVE_THERMAL_MACHINE", ready ? FunctionalState.READY : FunctionalState.WAITING_ACTIVATION, reasons, data));
            }
            if (block instanceof ChestBlock chest) {
                for (Direction side : Direction.Plane.HORIZONTAL) read.check(position.relative(side));
                read.check(position.above());
                boolean ready = ChestBlock.getContainer(chest, state, level, position, false) != null;
                return Optional.of(function(point, id, "NATIVE_CONTAINER_CONNECTION", ready ? FunctionalState.READY : FunctionalState.WAITING_ACTIVATION,
                        List.of(ready ? "NATIVE_CONNECTION_ACCESSIBLE_NO_MATERIAL_SOURCE_AUTHORIZATION_GRANTED" : "NATIVE_CONTAINER_BLOCKED_OR_CONNECTION_INCOMPLETE"), data));
            }
            return Optional.of(function(point, id, "NATIVE_CONTAINER_CONNECTION", FunctionalState.READY,
                    List.of("NATIVE_CONTAINER_INITIALIZED_NO_MATERIAL_SOURCE_AUTHORIZATION_GRANTED"), data));
        } catch (RuntimeException | LinkageError failure) {
            String code = failure.getMessage();
            return Optional.of(function(point, id, "NATIVE_MACHINE", FunctionalState.UNVERIFIED,
                    List.of(code != null && code.matches("[A-Z0-9_]{1,120}") ? code : "FUNCTION_NATIVE_CONTRACT_UNAVAILABLE"), data));
        }
    }
    private static boolean unlocks(BaseContainerBlockEntity entity, ServerPlayer owner) {
        try {
            var getter = lockKey;
            if (getter == null) lockKey = getter = MethodHandles.privateLookupIn(BaseContainerBlockEntity.class, MethodHandles.lookup())
                    .findGetter(BaseContainerBlockEntity.class, "lockKey", LockCode.class);
            return ((LockCode) getter.invoke(entity)).unlocksWith(owner.getMainHandItem());
        } catch (Throwable unavailable) { throw new IllegalStateException("BUILD_NATIVE_LOCK_CHECK_UNAVAILABLE", unavailable); }
    }
    private static Function function(BlueprintDocument.Position point, String id, String mechanism, FunctionalState state,
                                     List<String> reasons, Map<String, String> observed) {
        return new Function(point, id, mechanism, state, reasons, observed);
    }
}
