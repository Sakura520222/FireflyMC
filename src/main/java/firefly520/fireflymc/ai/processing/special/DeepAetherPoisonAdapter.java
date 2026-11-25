package firefly520.fireflymc.ai.processing.special;

import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

/** Reviewed ItemEntityMixin transformation, with a real nonconsumed poison medium as context. */
public final class DeepAetherPoisonAdapter {
    private static final String CLASS = "io.github.razordevs.deep_aether.recipe.poison.PoisonRecipe";
    public interface MediumGuard { void check(ServerPlayer player, BlockPos medium); }
    private DeepAetherPoisonAdapter() {}

    public static boolean supports(Recipe<?> recipe) {
        return recipe != null && recipe.getClass().getName().equals(CLASS)
                && SpecialNative.version("deep_aether", "1.21.1-1.1.5.1")
                && SpecialNative.id("deep_aether:poison_recipe").equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))
                && SpecialNative.id("deep_aether:poison_recipe").equals(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()));
    }

    /** Last matching holder wins in this exact installed ItemEntityMixin implementation. */
    public static RecipeHolder<?> selected(ItemStack input, ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (!SpecialNative.version("deep_aether", "1.21.1-1.1.5.1")) throw new IllegalArgumentException("UNSUPPORTED_PROCESSING_ADAPTER");
        var type = BuiltInRegistries.RECIPE_TYPE.get(SpecialNative.id("deep_aether:poison_recipe"));
        if (type == null) throw new IllegalArgumentException("POISON_RECIPE_TYPE_MISSING");
        @SuppressWarnings({"rawtypes", "unchecked"}) List<RecipeHolder<?>> recipes = (List) level.getRecipeManager().getAllRecipesFor((RecipeType) type);
        if (recipes.size() > 512) throw new IllegalArgumentException("POISON_RECIPE_SEARCH_LIMIT");
        RecipeHolder<?> selected = null;
        for (var candidate : recipes) if (supports(candidate.value()) && !candidate.value().getIngredients().isEmpty()) {
            var first = candidate.value().getIngredients().getFirst().getItems();
            if (first.length > 0 && input.is(first[0].getItem())) selected = candidate;
        }
        return selected;
    }

    public static VerifiedTransformation verify(RecipeHolder<?> holder, ItemStack supplied, BlockPos realMedium,
                                                 ServerPlayer player, MediumGuard guard) {
        ServerLevel level = player.serverLevel();
        SpecialNative.current(holder, level);
        if (guard == null) throw new IllegalArgumentException("POISON_MEDIUM_PERMISSION_REQUIRED");
        guard.check(player, realMedium);
        if (!level.hasChunkAt(realMedium) || !level.getBlockState(realMedium).getBlock().getClass().getName().equals("io.github.razordevs.deep_aether.block.natural.PoisonBlock"))
            throw new IllegalArgumentException("POISON_REAL_MEDIUM_REQUIRED");
        RecipeHolder<?> selection = supplied == null || supplied.isEmpty() ? null : selected(supplied, level);
        if (!supports(holder.value()) || selection == null || selection.value() != holder.value() || !selection.id().equals(holder.id()))
            throw new IllegalArgumentException("POISON_NATIVE_SELECTION_MISMATCH");
        ItemStack input = RecipeInputIsolation.copy(supplied.copyWithCount(1), level.registryAccess());
        ItemStack configured = (ItemStack) SpecialNative.get(holder.value(), "getResult", ItemStack.class);
        if (configured.isEmpty()) throw new IllegalArgumentException("POISON_OUTPUT_EMPTY");
        // Native code intentionally keeps neither result count/components nor input components.
        ItemStack output = new ItemStack(configured.getItem(), 1);
        return new Result(holder, input, output, realMedium, level);
    }

    public static void callback(ItemStack consumed, ServerPlayer player) {
        if (!player.serverLevel().getServer().isSameThread() || !SpecialNative.version("deep_aether", "1.21.1-1.1.5.1"))
            throw new IllegalStateException("POISON_CALLBACK_CONTEXT");
        Object trigger = ((java.util.function.Supplier<?>) SpecialNative.field("io.github.razordevs.deep_aether.advancement.DAAdvancementTriggers", "POISON_TRIGGER")).get();
        SpecialNative.call(trigger, "trigger", void.class, new Class<?>[]{ServerPlayer.class, ItemStack.class}, player,
                RecipeInputIsolation.copy(consumed, player.registryAccess()));
    }

    private static final class Result extends VerifiedTransformation {
        Result(RecipeHolder<?> holder, ItemStack input, ItemStack output, BlockPos medium, ServerLevel level) {
            super(holder.id(), SpecialNative.id("deep_aether:poison_recipe"), SpecialNative.id("deep_aether:poison_recipe"),
                    "deep_aether:poison_item@1.21.1-1.1.5.1", List.of(input), List.of(1), List.of(output), List.of(ItemStack.EMPTY),
                    new ExperienceReward(0, 1), new CallbackPlan("DEEP_AETHER_POISON", List.of("poison_trigger"),
                    List.of("ItemEntityMixin#deep_Aether$increaseTime", "PoisonBlock#entityInside"), true), SamplingEvidence.deterministic(),
                    Map.of("medium", level.dimension().location() + "/" + medium.asLong(), "mediumConsumedMb", "0",
                            "inputComponents", "NOT_INHERITED_NATIVE_RULE", "stationRequired", "false", "waitTicks", "0"), level.registryAccess());
        }
    }
}
