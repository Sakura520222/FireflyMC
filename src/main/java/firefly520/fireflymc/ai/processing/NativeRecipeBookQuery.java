package firefly520.fireflymc.ai.processing;

import com.google.gson.*;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import java.util.*;

/** Uses the same ingredient/count matcher as the vanilla recipe book. No crafting or inventory writes. */
public final class NativeRecipeBookQuery {
    private final StackedContents contents = new StackedContents();
    public NativeRecipeBookQuery(List<ItemStack> available) {
        for (var stack : available) {
            int prior = contents.contents.get(StackedContents.getStackingIndex(stack));
            contents.accountStack(stack, Math.min(stack.getCount(), Integer.MAX_VALUE - prior));
        }
    }
    public static boolean supports(Recipe<?> recipe) {
        return Set.of(ShapedRecipe.class, ShapelessRecipe.class, SmeltingRecipe.class, BlastingRecipe.class,
                SmokingRecipe.class, CampfireCookingRecipe.class, StonecutterRecipe.class).contains(recipe.getClass())
                && recipe.getIngredients().stream().allMatch(Ingredient::isSimple)
                && (!(recipe instanceof CraftingRecipe) || recipe.canCraftInDimensions(3, 3));
    }
    public Optional<JsonObject> craftable(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        if (!supports(holder.value())) throw new IllegalArgumentException("NATIVE_RECIPE_BOOK_UNSUPPORTED");
        var picked = new IntArrayList();
        if (!contents.canCraft(holder.value(), picked)) return Optional.empty();
        ItemStack output = holder.value().getResultItem(registries);
        if (output.isEmpty()) return Optional.empty();
        var result = new JsonObject();
        result.addProperty("recipeId", holder.id().toString());
        result.addProperty("recipeType", BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()).toString());
        result.addProperty("displayName", displayName(output));
        result.addProperty("outputId", BuiltInRegistries.ITEM.getKey(output.getItem()).toString());
        result.addProperty("outputCount", output.getCount());
        result.addProperty("status", "NATIVE_RECIPE_BOOK_CRAFTABLE");
        result.addProperty("matcher", "minecraft:StackedContents.canCraft");
        var counts = new LinkedHashMap<Integer, Integer>();
        for (int index : picked) if (index != 0) counts.merge(index, 1, Integer::sum);
        var inputs = new JsonArray();
        counts.forEach((index, count) -> {
            var stack = StackedContents.fromStackingIndex(index); var row = new JsonObject(); var item = new JsonObject();
            item.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            item.addProperty("name", displayName(stack)); item.addProperty("count", count);
            row.add("item", item); inputs.add(row);
        });
        result.add("inputs", inputs);
        return Optional.of(result);
    }
    public static String displayName(ItemStack stack) {
        String translated = firefly520.fireflymc.util.ServerLanguageLoader.translateComponent(stack.getHoverName());
        return translated.equals(stack.getDescriptionId()) ? stack.getHoverName().getString() : translated;
    }
}
