package firefly520.fireflymc.ai.processing;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.Tags;
import java.util.*;

/** FD dough uses a real reusable water container. The original water stack, including components, is returned. */
public final class FdDoughProcessingAdapter {
    private static final String CLASS = "vectorwing.farmersdelight.common.crafting.DoughRecipe";
    private static final ResourceLocation SERIALIZER = ResourceLocation.fromNamespaceAndPath("farmersdelight", "dough");
    private FdDoughProcessingAdapter() {}
    public static boolean supports(Recipe<?> recipe) {
        return recipe != null && recipe.getClass().getName().equals(CLASS) && recipe.getType() == RecipeType.CRAFTING
                && ModList.get().getModContainerById("farmersdelight")
                .map(mod -> mod.getModInfo().getVersion().toString().equals("1.3.2")).orElse(false);
    }
    public static VerifiedTransformation verify(RecipeHolder<?> holder, List<ItemStack> grid, ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (holder == null || !supports(holder.value()) || !SERIALIZER.equals(BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer())))
            throw new IllegalArgumentException("UNSUPPORTED_PROCESSING_ADAPTER");
        if (level.getRecipeManager().byKey(holder.id()).map(RecipeHolder::value).orElse(null) != holder.value())
            throw new IllegalStateException("PROCESSING_RECIPE_STALE");
        if (grid.size() != 9 || grid.stream().anyMatch(stack -> !stack.isEmpty() && stack.getCount() != 1))
            throw new IllegalArgumentException("FD_DOUGH_UNIT_INPUT_REQUIRED");
        var before = RecipeInputIsolation.copyAll(grid, level.registryAccess());
        var working = RecipeInputIsolation.copyAll(before, level.registryAccess());
        var positioned = CraftingInput.ofPositioned(3, 3, working);
        var recipe = (CraftingRecipe) holder.value();
        if (!recipe.canCraftInDimensions(3, 3) || !recipe.matches(positioned.input(), level))
            throw new IllegalArgumentException("FD_DOUGH_INPUT_MISMATCH");
        var output = RecipeInputIsolation.copy(recipe.assemble(positioned.input(), level.registryAccess()), level.registryAccess());
        if (output.isEmpty()) throw new IllegalStateException("FD_DOUGH_OUTPUT_INVALID");
        var compact = recipe.getRemainingItems(positioned.input());
        if (compact.size() != positioned.input().size()) throw new IllegalStateException("FD_DOUGH_REMAINDER_INVALID");
        var remaining = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
        var reusable = new ArrayList<String>();
        for (int y = 0; y < positioned.input().height(); y++) for (int x = 0; x < positioned.input().width(); x++) {
            int original = x + positioned.left() + (y + positioned.top()) * 3;
            var returned = RecipeInputIsolation.copy(compact.get(x + y * positioned.input().width()), level.registryAccess());
            remaining.set(original, returned);
            if (before.get(original).is(Tags.Items.BUCKETS_WATER)) {
                if (returned.getCount() != 1 || !ItemStack.isSameItemSameComponents(returned, before.get(original)))
                    throw new IllegalStateException("FD_DOUGH_WATER_RETURN_CHANGED");
                reusable.add(Integer.toString(original));
            } else if (!returned.isEmpty()) throw new IllegalStateException("FD_DOUGH_UNDECLARED_REMAINDER");
        }
        return new VerifiedTransformation(holder.id(), BuiltInRegistries.RECIPE_TYPE.getKey(RecipeType.CRAFTING), SERIALIZER,
                "farmersdelight:dough@1.3.2", before, before.stream().map(s -> s.isEmpty() ? 0 : 1).toList(), List.of(output), remaining,
                new VerifiedTransformation.ExperienceReward(0, 1), new VerifiedTransformation.CallbackPlan("CRAFTING_RESULT_SLOT",
                List.of("item_on_crafted_by", "neoforge_crafting_event"), List.of("DoughRecipe#getRemainingItems:returns the same real water stack"), false),
                VerifiedTransformation.SamplingEvidence.deterministic(), Map.of("stationRequired", "false", "reusableGridSlots", String.join(",", reusable)), level.registryAccess());
    }
}
