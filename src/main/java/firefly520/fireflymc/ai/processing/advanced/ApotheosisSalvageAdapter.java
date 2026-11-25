package firefly520.fireflymc.ai.processing.advanced;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import firefly520.fireflymc.ai.processing.CommonModMaterialRules;
import firefly520.fireflymc.ai.processing.VerifiedTransformation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

/** All native matching salvage rules contribute, in native order, with independent inclusive uniform draws. */
public final class ApotheosisSalvageAdapter {
    private static final String ROOT = "dev.shadowsoffire.apotheosis.affix.salvaging.";
    private ApotheosisSalvageAdapter() {}
    public static boolean supports(Recipe<?> recipe) {
        return recipe != null && recipe.getClass().getName().equals(ROOT + "SalvagingRecipe")
                && AdvancedReflection.version("apotheosis", "8.6.0")
                && AdvancedReflection.id("apotheosis:salvaging").equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()));
    }
    public static List<AdvancedInput> inputRequirements(RecipeHolder<?> holder, ServerLevel level) {
        require(holder, level);
        Ingredient ingredient = (Ingredient) AdvancedReflection.call(holder.value(), "getInput");
        return List.of(AdvancedInput.one(0, "salvaged_real_item", 1, ingredient::test));
    }
    public static AdvancedTransformation verify(RecipeHolder<?> selected, ItemStack input, ServerLevel level) {
        return calculate(selected, input, level, 0, false);
    }
    public static AdvancedTransformation sample(RecipeHolder<?> selected, ItemStack input, ServerLevel level, long seed) {
        return calculate(selected, input, level, seed, true);
    }

    @SuppressWarnings("unchecked")
    private static AdvancedTransformation calculate(RecipeHolder<?> selected, ItemStack supplied, ServerLevel level, long seed, boolean sample) {
        require(selected, level);
        if (supplied.isEmpty() || supplied.getCount() != 1) throw AdvancedReflection.failure("SALVAGING_UNIT_INPUT_REQUIRED");
        ItemStack input = AdvancedReflection.copy(supplied, level.registryAccess());
        Class<?> menu = AdvancedReflection.type(ROOT + "SalvagingMenu");
        List<RecipeHolder<?>> matches = (List<RecipeHolder<?>>) AdvancedReflection.invoke(menu, null, "findMatch",
                new Class<?>[]{Level.class, ItemStack.class}, level, input);
        if (matches.isEmpty() || matches.size() > 64 || matches.stream().noneMatch(holder -> holder.value() == selected.value()))
            throw AdvancedReflection.failure("SALVAGING_RULE_SET_INVALID");
        List<ItemStack> outputs = new ArrayList<>();
        List<VerifiedTransformation.RecipeReference> references = new ArrayList<>();
        Map<String, String> context = new LinkedHashMap<>();
        context.put("distribution", "independent-inclusive-uniform");
        context.put("nativeRuleOrder", "RecipeManager.getRecipesFor");
        RandomSource random = RandomSource.create(seed);
        int draws = 0, upperUnits = 0;
        for (RecipeHolder<?> holder : matches) {
            require(holder, level);
            references.addAll(AdvancedTransformation.references(holder));
            List<?> data = (List<?>) AdvancedReflection.call(holder.value(), "getOutputs");
            if (data.size() > 64) throw AdvancedReflection.failure("SALVAGING_OUTPUT_LIMIT");
            for (Object outputData : data) {
                int[] range = (int[]) AdvancedReflection.invoke(menu, null, "getSalvageCounts",
                        new Class<?>[]{AdvancedReflection.type(ROOT + "SalvagingRecipe$OutputData"), ItemStack.class}, outputData, input);
                if (range.length != 2 || range[0] < 0 || range[1] < range[0] || (upperUnits += range[1]) > 512)
                    throw AdvancedReflection.failure("SALVAGING_OUTPUT_LIMIT");
                context.put("uniform." + draws, range[0] + ":" + range[1]);
                int count = sample ? ((Number) AdvancedReflection.invoke(menu, null, "getSalvageCount",
                        new Class<?>[]{AdvancedReflection.type(ROOT + "SalvagingRecipe$OutputData"), ItemStack.class, RandomSource.class}, outputData, input, random)).intValue() : range[1];
                if (count < range[0] || count > range[1]) throw AdvancedReflection.failure("SALVAGING_NATIVE_RANGE_MISMATCH");
                ItemStack template = (ItemStack) AdvancedReflection.call(outputData, "stack");
                if (count > 0) outputs.addAll(CommonModMaterialRules.splitOutput(template, count, level.registryAccess()));
                if (outputs.size() > 81) throw AdvancedReflection.failure("SALVAGING_OUTPUT_LIMIT");
                draws++;
            }
        }
        if (!ItemStack.matches(input, supplied)) throw AdvancedReflection.failure("SALVAGING_INPUT_MUTATED");
        // A probability distribution cannot be represented as independent Bernoulli stack units; bounds are explicit context.
        return new AdvancedTransformation(selected, "apotheosis:salvaging@8.6.0", List.of(supplied), List.of(1), outputs,
                List.of(ItemStack.EMPTY), new VerifiedTransformation.SamplingEvidence(sample, "native-salvage-inclusive-uniform-v1", seed, draws, List.of()),
                context, references, List.of(), null, level.registryAccess());
    }
    private static void require(RecipeHolder<?> holder, ServerLevel level) {
        AdvancedReflection.current(holder, level);
        if (!supports(holder.value())) throw AdvancedReflection.failure("UNSUPPORTED_PROCESSING_ADAPTER");
    }
}
