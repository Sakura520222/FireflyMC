package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.material.MaterialStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.neoforged.fml.ModList;
import java.lang.reflect.*;
import java.util.*;

/** Serving transforms a real filled pot; it never creates the food or the pot as waived equipment. */
public final class FdServingProcessingAdapter {
    private static final String CLASS = "vectorwing.farmersdelight.common.crafting.FoodServingRecipe";
    private static final ResourceLocation SERIALIZER = ResourceLocation.fromNamespaceAndPath("farmersdelight", "food_serving");
    public record Contents(MaterialStack meal, MaterialStack container) {}
    public record Step(List<MaterialStack> inputs, List<MaterialStack> outputs) {
        public Step { inputs = List.copyOf(inputs); outputs = List.copyOf(outputs); }
    }
    public static final class Batch extends VerifiedTransformation {
        private final List<Step> steps;
        private Batch(RecipeHolder<?> holder, List<ItemStack> inputs, List<ItemStack> outputs, List<ItemStack> remainders,
                      int count, List<Step> steps, ServerLevel level) {
            super(holder.id(), BuiltInRegistries.RECIPE_TYPE.getKey(RecipeType.CRAFTING), SERIALIZER, "farmersdelight:food_serving@1.3.2",
                    inputs, java.util.stream.IntStream.range(0, 9).map(i -> i == 0 ? 1 : i == 1 ? count : 0).boxed().toList(),
                    outputs, remainders, new ExperienceReward(0, 1), new CallbackPlan("FD_SERVING_BATCH",
                            List.of("item_on_crafted_by", "neoforge_crafting_event"), List.of("FoodServingRecipe#getRemainingItems:takeServingFromItem exactly once per unit"), true),
                    SamplingEvidence.deterministic(), Map.of("stationRequired", "false", "batchAlreadyApplied", "true",
                            "logicalOperations", Integer.toString(count)), level.registryAccess());
            this.steps = List.copyOf(steps);
        }
        public List<Step> steps() { return steps; }
    }
    private FdServingProcessingAdapter() {}
    public static boolean supports(Recipe<?> recipe) {
        return recipe != null && recipe.getClass().getName().equals(CLASS) && recipe.getType() == RecipeType.CRAFTING
                && ModList.get().getModContainerById("farmersdelight")
                .map(mod -> mod.getModInfo().getVersion().toString().equals("1.3.2")).orElse(false);
    }
    public static Contents contents(ItemStack source, ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (!BuiltInRegistries.ITEM.getKey(source.getItem()).toString().equals("farmersdelight:cooking_pot"))
            return new Contents(MaterialStack.EMPTY, MaterialStack.EMPTY);
        ItemStack isolated = RecipeInputIsolation.copy(source.copyWithCount(1), level.registryAccess());
        try {
            Class<?> pot = Class.forName("vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity", false,
                    FdServingProcessingAdapter.class.getClassLoader());
            Method meal = pot.getMethod("getMealFromItem", ItemStack.class), container = pot.getMethod("getContainerFromItem", ItemStack.class);
            if (meal.getReturnType() != ItemStack.class || container.getReturnType() != ItemStack.class
                    || !Modifier.isStatic(meal.getModifiers()) || !Modifier.isStatic(container.getModifiers())) throw new NoSuchMethodException();
            return new Contents(ItemSnapshotCodec.capture((ItemStack) meal.invoke(null, isolated), level.registryAccess()),
                    ItemSnapshotCodec.capture((ItemStack) container.invoke(null, isolated), level.registryAccess()));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("FD_SERVING_API_MISMATCH"); }
    }
    public static Batch verify(RecipeHolder<?> holder, List<ItemStack> grid, int count, ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (holder == null || !supports(holder.value()) || !SERIALIZER.equals(BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer())))
            throw new IllegalArgumentException("UNSUPPORTED_PROCESSING_ADAPTER");
        if (level.getRecipeManager().byKey(holder.id()).map(RecipeHolder::value).orElse(null) != holder.value())
            throw new IllegalStateException("PROCESSING_RECIPE_STALE");
        if (count < 1 || count > 16 || grid.size() != 9 || grid.get(0).getCount() != 1 || grid.get(1).getCount() != count
                || grid.subList(2, 9).stream().anyMatch(s -> !s.isEmpty())) throw new IllegalArgumentException("FD_SERVING_INPUT_SIZE");
        var original = RecipeInputIsolation.copyAll(grid, level.registryAccess());
        Contents start = contents(original.get(0), level);
        if (start.meal().count() < count || start.container().empty()) throw new IllegalArgumentException("FD_SERVING_FOOD_MISSING");
        var recipe = (CraftingRecipe) holder.value();
        ItemStack pot = RecipeInputIsolation.copy(original.get(0), level.registryAccess());
        var outputs = new ArrayList<ItemStack>(); var steps = new ArrayList<Step>();
        for (int attempt = 0; attempt < count; attempt++) {
            var unit = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
            unit.set(0, RecipeInputIsolation.copy(pot, level.registryAccess()));
            unit.set(1, RecipeInputIsolation.copy(original.get(1).copyWithCount(1), level.registryAccess()));
            var before = RecipeInputIsolation.copyAll(unit, level.registryAccess());
            var positioned = CraftingInput.ofPositioned(3, 3, unit);
            if (!recipe.matches(positioned.input(), level)) throw new IllegalArgumentException("FD_SERVING_INPUT_MISMATCH");
            ItemStack meal = RecipeInputIsolation.copy(recipe.assemble(positioned.input(), level.registryAccess()), level.registryAccess());
            if (meal.isEmpty() || meal.getCount() != 1) throw new IllegalStateException("FD_SERVING_OUTPUT_INVALID");
            var remaining = recipe.getRemainingItems(positioned.input()); // Mutates only the isolated unit pot.
            if (remaining.size() != 2 || remaining.getFirst().getCount() != 1) throw new IllegalStateException("FD_SERVING_REMAINDER_INVALID");
            pot = RecipeInputIsolation.copy(remaining.getFirst(), level.registryAccess());
            if (contents(pot, level).meal().count() != start.meal().count() - attempt - 1)
                throw new IllegalStateException("FD_SERVING_COUNT_UNCERTAIN");
            outputs.add(meal);
            if (!remaining.get(1).isEmpty()) outputs.add(RecipeInputIsolation.copy(remaining.get(1), level.registryAccess()));
            steps.add(new Step(before.stream().map(s -> ItemSnapshotCodec.capture(s, level.registryAccess())).toList(),
                    List.of(ItemSnapshotCodec.capture(meal, level.registryAccess()))));
        }
        var remainders = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY)); remainders.set(0, pot);
        return new Batch(holder, original, outputs, remainders, count, steps, level);
    }
}
