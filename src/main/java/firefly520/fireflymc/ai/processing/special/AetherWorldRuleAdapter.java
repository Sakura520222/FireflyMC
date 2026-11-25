package firefly520.fireflymc.ai.processing.special;

import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.material.MaterialStack;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** World rules are queried against a real target; they never enter an item production graph. */
public final class AetherWorldRuleAdapter {
    public enum Role { BLOCK_BAN, ITEM_BAN, WORLD_CONVERSION }
    public record Profile(String mod, String version, String id, Role role, String catalystItem) {}
    private static final Map<String, Profile> PROFILES = Map.ofEntries(
            entry("com.aetherteam.aether.recipe.recipes.ban.BlockBanRecipe", "aether", "1.5.10", "aether:block_placement_ban", Role.BLOCK_BAN, ""),
            entry("com.aetherteam.aether.recipe.recipes.ban.ItemBanRecipe", "aether", "1.5.10", "aether:item_placement_ban", Role.ITEM_BAN, ""),
            entry("com.aetherteam.aether.recipe.recipes.block.PlacementConversionRecipe", "aether", "1.5.10", "aether:placement_conversion", Role.WORLD_CONVERSION, ""),
            entry("com.aetherteam.aether.recipe.recipes.block.IcestoneFreezableRecipe", "aether", "1.5.10", "aether:icestone_freezable", Role.WORLD_CONVERSION, ""),
            entry("com.aetherteam.aether.recipe.recipes.block.AccessoryFreezableRecipe", "aether", "1.5.10", "aether:accessory_freezable", Role.WORLD_CONVERSION, ""),
            entry("com.aetherteam.aether.recipe.recipes.block.SwetBallRecipe", "aether", "1.5.10", "aether:swet_ball_conversion", Role.WORLD_CONVERSION, "aether:swet_ball"),
            entry("com.aetherteam.aether.recipe.recipes.block.AmbrosiumRecipe", "aether", "1.5.10", "aether:ambrosium_enchanting", Role.WORLD_CONVERSION, "aether:ambrosium_shard"),
            entry("io.github.razordevs.deep_aether.recipe.GoldenSwetBallRecipe", "deep_aether", "1.21.1-1.1.5.1", "deep_aether:golden_swet_ball_recipe", Role.WORLD_CONVERSION, "deep_aether:golden_swet_ball"),
            entry("io.github.razordevs.deep_aether.recipe.GlowingSporesRecipe", "deep_aether", "1.21.1-1.1.5.1", "deep_aether:glowing_spores_recipe", Role.WORLD_CONVERSION, "deep_aether:glowing_spores"));
    public record Query(ResourceLocation recipeId, Role role, String dimension, BlockPos target, BlockState actual,
                        boolean matches, BlockState proposed, Optional<ResourceLocation> function, String requiredCatalyst) {
        public Query { target = target.immutable(); }
        public boolean isItemProduction() { return false; }
    }
    public record Conversion(Query world, MaterialStack catalyst, int consumedCount) {}
    private AetherWorldRuleAdapter() {}

    private static Map.Entry<String, Profile> entry(String name, String mod, String version, String id, Role role, String catalyst) {
        return Map.entry(name, new Profile(mod, version, id, role, catalyst));
    }

    public static Profile profile(Recipe<?> recipe) {
        if (recipe == null) return null;
        Profile profile = PROFILES.get(recipe.getClass().getName());
        return profile != null && SpecialNative.version(profile.mod(), profile.version())
                && SpecialNative.id(profile.id()).equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))
                && SpecialNative.id(profile.id()).equals(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer())) ? profile : null;
    }

    /** No cancelable interaction event, function, block update or entity effect is fired by inspection. */
    public static Query inspect(RecipeHolder<?> holder, BlockPos target, BlockState proposedBlock, ItemStack proposedItem, ServerLevel level) {
        SpecialNative.current(holder, level);
        Profile profile = profile(holder.value());
        if (profile == null) throw new IllegalArgumentException("UNSUPPORTED_AETHER_WORLD_RULE");
        if (!level.hasChunkAt(target) || !level.getWorldBorder().isWithinBounds(target)) throw new IllegalArgumentException("AETHER_RULE_TARGET_UNAVAILABLE");
        BlockState actual = level.getBlockState(target);
        boolean matches;
        BlockState proposed = actual;
        Optional<ResourceLocation> function = Optional.empty();
        if (profile.role() == Role.WORLD_CONVERSION) {
            matches = (boolean) SpecialNative.call(holder.value(), "matches", boolean.class,
                    new Class<?>[]{Level.class, BlockPos.class, BlockState.class}, level, target, actual);
            proposed = (BlockState) SpecialNative.call(holder.value(), "getResultState", BlockState.class, new Class<?>[]{BlockState.class}, actual);
            Optional<?> value = (Optional<?>) SpecialNative.get(holder.value(), "getFunctionId", Optional.class);
            if (value.isPresent() && !(value.get() instanceof ResourceLocation)) throw new IllegalStateException("AETHER_RULE_FUNCTION_INVALID");
            function = value.map(ResourceLocation.class::cast);
        } else {
            Object input = profile.role() == Role.BLOCK_BAN ? proposedBlock : proposedItem;
            if (input == null) throw new IllegalArgumentException("AETHER_RULE_PROPOSED_INPUT_REQUIRED");
            matches = (boolean) SpecialNative.call(holder.value(), "matches", boolean.class,
                    new Class<?>[]{Level.class, BlockPos.class, Object.class}, level, target, input);
        }
        return new Query(holder.id(), profile.role(), level.dimension().location().toString(), target, actual, matches, proposed, function, profile.catalystItem());
    }

    /** A native item-use executor must recheck this fixed state and charge the supplied catalyst once. */
    public static Conversion planItemUse(RecipeHolder<?> holder, BlockPos target, ItemStack catalyst, ServerLevel level) {
        Query query = inspect(holder, target, null, null, level);
        if (query.role() != Role.WORLD_CONVERSION || !query.matches() || query.actual().equals(query.proposed())
                || query.requiredCatalyst().isEmpty()) throw new IllegalArgumentException("AETHER_RULE_NOT_ITEM_USE_CONVERSION");
        if (query.function().isPresent()) throw new IllegalArgumentException("AETHER_UNREVIEWED_FUNCTION_SIDE_EFFECT");
        if (catalyst == null || catalyst.isEmpty() || !BuiltInRegistries.ITEM.getKey(catalyst.getItem()).toString().equals(query.requiredCatalyst()))
            throw new IllegalArgumentException("AETHER_REAL_CATALYST_REQUIRED");
        if (level.getBlockEntity(target) != null) throw new IllegalArgumentException("AETHER_BLOCK_ENTITY_CONVERSION_UNREVIEWED");
        return new Conversion(query, ItemSnapshotCodec.capture(catalyst.copyWithCount(1), level.registryAccess()), 1);
    }
}
