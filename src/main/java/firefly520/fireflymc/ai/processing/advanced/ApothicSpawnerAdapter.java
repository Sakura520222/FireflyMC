package firefly520.fireflymc.ai.processing.advanced;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation.SamplingEvidence;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Offline transform of a real spawner item. Detached native tiles are never placed, ticked or attached to a Level. */
public final class ApothicSpawnerAdapter {
    private static final String ROOT = "dev.shadowsoffire.apothic_spawners.";
    private static final String TILE = ROOT + "block.ApothSpawnerTile";
    private ApothicSpawnerAdapter() {}

    public static boolean supports(Recipe<?> recipe) {
        return recipe != null && recipe.getClass().getName().equals(ROOT + "modifiers.SpawnerModifier")
                && AdvancedReflection.version("apothic_spawners", "1.4.0")
                && AdvancedReflection.id("apothic_spawners:spawner_modifier").equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()));
    }

    public static List<AdvancedInput> inputRequirements(RecipeHolder<?> holder, ServerLevel level) {
        require(holder, level);
        Ingredient main = (Ingredient) AdvancedReflection.call(holder.value(), "getMainhandInput");
        Ingredient off = (Ingredient) AdvancedReflection.call(holder.value(), "getOffhandInput");
        List<AdvancedInput> result = new ArrayList<>();
        result.add(AdvancedInput.one(0, "existing_spawner_target", 1, ApothicSpawnerAdapter::target));
        result.add(AdvancedInput.one(1, "spawner_modifier_material", 1, main::test));
        if (!off.isEmpty()) result.add(AdvancedInput.one(2, "real_offhand_material", 1, off::test));
        return List.copyOf(result);
    }

    public static boolean target(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem().getClass().getName().equals(ROOT + "block.ApothSpawnerItem");
    }

    public static AdvancedTransformation verify(RecipeHolder<?> holder, List<ItemStack> supplied, ServerLevel level) {
        require(holder, level);
        if (supplied.size() != 3 || supplied.getFirst().getCount() != 1 || !target(supplied.getFirst()))
            throw AdvancedReflection.failure("EXISTING_SPAWNER_ITEM_REQUIRED");
        var inputs = RecipeInputIsolation.copyAll(supplied, level.registryAccess());
        var original = inputs.getFirst().getOrDefault(DataComponents.BLOCK_ENTITY_DATA, CustomData.EMPTY).copyTag();
        if (original.contains("id") && !original.getString("id").equals("minecraft:mob_spawner"))
            throw AdvancedReflection.failure("SPAWNER_ITEM_BLOCK_ENTITY_ID_MISMATCH");
        BlockState state = ((BlockItem) inputs.getFirst().getItem()).getBlock().defaultBlockState();
        Object detached = tile(state, original, level.registryAccess());
        CompoundTag before = save(detached, level.registryAccess());
        requireOriginalFieldsPreserved(original, before);
        if (!(boolean) AdvancedReflection.call(holder.value(), "matches", new Class<?>[]{AdvancedReflection.type(TILE), ItemStack.class, ItemStack.class},
                detached, inputs.get(1), inputs.get(2))) throw AdvancedReflection.failure("SPAWNER_MODIFIER_MATERIAL_MISMATCH");
        if (!(boolean) AdvancedReflection.call(holder.value(), "apply", new Class<?>[]{AdvancedReflection.type(TILE)}, detached))
            throw AdvancedReflection.failure("SPAWNER_MODIFIER_NO_CHANGE");
        CompoundTag changed = save(detached, level.registryAccess());
        if (changed.equals(before)) throw AdvancedReflection.failure("SPAWNER_MODIFIER_UNVERIFIED_CHANGE");
        // Preserve unknown top-level item data. Known native fields are replaced with their verified post-state.
        CompoundTag merged = original.copy();
        for (String key : before.getAllKeys()) if (!changed.contains(key)) merged.remove(key);
        for (String key : changed.getAllKeys()) merged.put(key, changed.get(key).copy());
        merged.putBoolean("modified", true);
        merged.putString("id", "minecraft:mob_spawner");
        Object roundTrip = tile(state, merged, level.registryAccess());
        CompoundTag encodedAgain = save(roundTrip, level.registryAccess());
        CompoundTag expected = changed.copy(); expected.putBoolean("modified", true);
        if (!encodedAgain.equals(expected)) throw AdvancedReflection.failure("SPAWNER_STATE_ROUND_TRIP_MISMATCH");
        ItemStack output = AdvancedReflection.copy(inputs.getFirst(), level.registryAccess());
        output.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(merged));
        boolean consumeOff = (boolean) AdvancedReflection.call(holder.value(), "consumesOffhand");
        if (inputs.get(1).isEmpty() || consumeOff && inputs.get(2).isEmpty()) throw AdvancedReflection.failure("SPAWNER_MODIFIER_MATERIAL_MISSING");
        var remainders = AdvancedReflection.emptySlots(3);
        // A non-consumed quartz is still real reserved material and is returned exactly once through escrow.
        int offCount = inputs.get(2).isEmpty() ? 0 : 1;
        if (offCount != 0 && !consumeOff) remainders.set(2, AdvancedReflection.copy(inputs.get(2).copyWithCount(1), level.registryAccess()));
        return new AdvancedTransformation(holder, "apothic_spawners:item_modifier@1.4.0", inputs, List.of(1, 1, offCount),
                List.of(output), remainders, SamplingEvidence.deterministic(), Map.of("targetKind", "existing_item", "spawnerTicked", "false",
                "nonConsumedOffhandReturned", Boolean.toString(offCount != 0 && !consumeOff)), AdvancedTransformation.references(holder),
                List.of("native_spawner_modifier_trigger"), player -> {
                    Object trigger = AdvancedReflection.field(ROOT + "ASObjects", "MODIFIER_TRIGGER");
                    AdvancedReflection.call(trigger, "trigger", new Class<?>[]{ServerPlayer.class, AdvancedReflection.type(TILE),
                            AdvancedReflection.type(ROOT + "modifiers.SpawnerModifier")}, player, roundTrip, holder.value());
                }, level.registryAccess());
    }

    private static Object tile(BlockState state, CompoundTag data, HolderLookup.Provider registries) {
        Object tile = AdvancedReflection.construct(TILE, new Class<?>[]{BlockPos.class, BlockState.class}, BlockPos.ZERO, state);
        if (!(tile instanceof BlockEntity entity) || entity.getLevel() != null) throw AdvancedReflection.failure("SPAWNER_DETACHED_TARGET_REQUIRED");
        AdvancedReflection.call(tile, "loadAdditional", new Class<?>[]{CompoundTag.class, HolderLookup.Provider.class}, data.copy(), registries);
        if (entity.getLevel() != null) throw AdvancedReflection.failure("SPAWNER_DETACHED_TARGET_CHANGED");
        return tile;
    }
    public static AdvancedTransformation verify(RecipeHolder<?> holder, List<ItemStack> supplied, ServerPlayer player) {
        return verify(holder, supplied, player.serverLevel()).ownedBy(player);
    }
    private static CompoundTag save(Object tile, HolderLookup.Provider registries) {
        CompoundTag output = new CompoundTag();
        AdvancedReflection.call(tile, "saveAdditional", new Class<?>[]{CompoundTag.class, HolderLookup.Provider.class}, output, registries);
        return output;
    }
    private static void requireOriginalFieldsPreserved(CompoundTag original, CompoundTag encoded) {
        // Unknown stats or invalid entity data must not be silently discarded by the native load logger.
        for (String key : original.getAllKeys()) if (encoded.contains(key) && !original.get(key).equals(encoded.get(key)))
            throw AdvancedReflection.failure("SPAWNER_INPUT_ROUND_TRIP_MISMATCH");
        for (String key : List.of("stats", "SpawnData", "SpawnPotentials", "unstable_ticks", "captured_spawn_data"))
            if (original.contains(key) && !encoded.contains(key)) throw AdvancedReflection.failure("SPAWNER_INPUT_FIELD_DROPPED");
    }
    private static void require(RecipeHolder<?> holder, ServerLevel level) {
        AdvancedReflection.current(holder, level);
        if (!supports(holder.value())) throw AdvancedReflection.failure("UNSUPPORTED_PROCESSING_ADAPTER");
    }
}
