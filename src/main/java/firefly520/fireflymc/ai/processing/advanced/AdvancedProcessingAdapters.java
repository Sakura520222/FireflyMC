package firefly520.fireflymc.ai.processing.advanced;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/** Exact installed-version dispatch. Permissions, concrete inventory allocation and durable effects remain the caller's responsibility. */
public final class AdvancedProcessingAdapters {
    private AdvancedProcessingAdapters() {}
    public static boolean supports(Recipe<?> recipe) {
        return ApothicInfusionAdapter.supports(recipe) || ApotheosisSalvageAdapter.supports(recipe)
                || ApotheosisGemCuttingAdapter.supports(recipe) || ApotheosisSmithingAdapter.supports(recipe)
                || ApotheosisReforgingAdapter.supports(recipe) || ApothicSpawnerAdapter.supports(recipe)
                || PsiTrickAdapter.supports(recipe) || AdvancedCraftingAdapter.supports(recipe) || StorageDynamicRecipes.supports(recipe)
                || BuildingDynamicRecipes.supports(recipe);
    }
    public static List<AdvancedInput> inputRequirements(RecipeHolder<?> holder, List<ItemStack> selected,
                                                       ServerPlayer player, int choice) {
        Recipe<?> recipe = holder.value();
        if (ApothicInfusionAdapter.supports(recipe)) return ApothicInfusionAdapter.inputRequirements(holder, player);
        if (ApotheosisSalvageAdapter.supports(recipe)) return ApotheosisSalvageAdapter.inputRequirements(holder, player.serverLevel());
        if (ApotheosisGemCuttingAdapter.supports(recipe)) return ApotheosisGemCuttingAdapter.inputRequirements(holder, selected, player.serverLevel());
        if (ApotheosisSmithingAdapter.supports(recipe)) return ApotheosisSmithingAdapter.inputRequirements(holder, selected, player);
        if (ApotheosisReforgingAdapter.supports(recipe)) return ApotheosisReforgingAdapter.inputRequirements(holder, player, choice);
        if (ApothicSpawnerAdapter.supports(recipe)) return ApothicSpawnerAdapter.inputRequirements(holder, player.serverLevel());
        if (PsiTrickAdapter.supports(recipe)) return PsiTrickAdapter.inputRequirements(holder, player);
        if (AdvancedCraftingAdapter.supports(recipe)) return AdvancedCraftingAdapter.inputRequirements(holder, selected, player);
        if (StorageDynamicRecipes.supports(recipe)) return StorageDynamicRecipes.inputRequirements(holder, selected, player);
        if (BuildingDynamicRecipes.supports(recipe)) return BuildingDynamicRecipes.inputRequirements(holder, selected, player);
        throw AdvancedReflection.failure("UNSUPPORTED_PROCESSING_ADAPTER");
    }
    public static AdvancedTransformation verify(RecipeHolder<?> holder, List<ItemStack> inputs, ServerPlayer player,
                                                int choice, int initializationSeed) {
        Recipe<?> recipe = holder.value();
        if (ApothicInfusionAdapter.supports(recipe)) return ApothicInfusionAdapter.verify(holder, inputs, player);
        if (ApotheosisSalvageAdapter.supports(recipe)) return ApotheosisSalvageAdapter.verify(holder, single(inputs), player.serverLevel());
        if (ApotheosisGemCuttingAdapter.supports(recipe)) return ApotheosisGemCuttingAdapter.verify(holder, inputs, player);
        if (ApotheosisSmithingAdapter.supports(recipe)) return ApotheosisSmithingAdapter.verify(holder, inputs, player);
        if (ApotheosisReforgingAdapter.supports(recipe)) return ApotheosisReforgingAdapter.verify(holder, inputs, player, choice, initializationSeed);
        if (ApothicSpawnerAdapter.supports(recipe)) return ApothicSpawnerAdapter.verify(holder, inputs, player);
        if (PsiTrickAdapter.supports(recipe)) return PsiTrickAdapter.verify(holder, single(inputs), player);
        if (AdvancedCraftingAdapter.supports(recipe)) return AdvancedCraftingAdapter.verify(holder, inputs, player);
        if (StorageDynamicRecipes.supports(recipe)) return StorageDynamicRecipes.verify(holder, inputs, player);
        if (BuildingDynamicRecipes.supports(recipe)) return BuildingDynamicRecipes.verify(holder, inputs, player);
        throw AdvancedReflection.failure("UNSUPPORTED_PROCESSING_ADAPTER");
    }
    public static boolean requiresFreshSampling(Recipe<?> recipe) { return ApotheosisSalvageAdapter.supports(recipe); }
    public static AdvancedTransformation sample(RecipeHolder<?> holder, List<ItemStack> inputs, ServerPlayer player, long seed) {
        if (ApotheosisSalvageAdapter.supports(holder.value())) return ApotheosisSalvageAdapter.sample(holder, single(inputs), player.serverLevel(), seed);
        throw AdvancedReflection.failure("PROCESSING_FRESH_RANDOM_SAMPLE_NOT_APPLICABLE");
    }
    private static ItemStack single(List<ItemStack> input) {
        if (input.size() != 1) throw AdvancedReflection.failure("ADVANCED_SINGLE_INPUT_REQUIRED");
        return input.getFirst();
    }
}
