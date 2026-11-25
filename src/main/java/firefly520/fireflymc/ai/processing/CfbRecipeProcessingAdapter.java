package firefly520.fireflymc.ai.processing;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.neoforged.fml.ModList;
import java.util.*;

/** CFB's explicit single-input recipes. Empty generic ingredient lists never imply free production. */
public final class CfbRecipeProcessingAdapter {
    private static final String ROOT = "net.blay09.mods.cookingforblockheads.recipe.";
    private CfbRecipeProcessingAdapter() {}
    public static boolean supports(Recipe<?> recipe) {
        if (recipe == null || !Set.of(ROOT + "OvenRecipe", ROOT + "ToasterRecipe").contains(recipe.getClass().getName())) return false;
        String name = recipe.getClass().getSimpleName().equals("OvenRecipe") ? "oven" : "toaster";
        return ResourceLocation.fromNamespaceAndPath("cookingforblockheads", name).equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))
                && ModList.get().getModContainerById("cookingforblockheads")
                .map(mod -> mod.getModInfo().getVersion().toString().equals("21.1.24")).orElse(false);
    }
    public static boolean matches(RecipeHolder<?> holder, ItemStack input, ServerLevel level) {
        require(holder, level);
        @SuppressWarnings("unchecked") Recipe<SingleRecipeInput> recipe = (Recipe<SingleRecipeInput>) holder.value();
        return !input.isEmpty() && recipe.matches(new SingleRecipeInput(RecipeInputIsolation.copy(input.copyWithCount(1), level.registryAccess())), level);
    }
    public static VerifiedTransformation verify(RecipeHolder<?> holder, ItemStack input, ServerLevel level) {
        require(holder, level);
        ItemStack before = RecipeInputIsolation.copy(input, level.registryAccess());
        ItemStack working = RecipeInputIsolation.copy(before, level.registryAccess());
        @SuppressWarnings("unchecked") Recipe<SingleRecipeInput> recipe = (Recipe<SingleRecipeInput>) holder.value();
        var supplied = new SingleRecipeInput(working);
        if (before.isEmpty() || !recipe.matches(supplied, level)) throw new IllegalArgumentException("CFB_INPUT_MISMATCH");
        ItemStack output = RecipeInputIsolation.copy(recipe.assemble(supplied, level.registryAccess()), level.registryAccess());
        if (output.isEmpty() || output.getCount() > output.getMaxStackSize()) throw new IllegalArgumentException("CFB_EMPTY_OR_INVALID_OUTPUT");
        if (before.getCount() != working.getCount() || !ItemStack.isSameItemSameComponents(before, working))
            throw new IllegalStateException("CFB_INPUT_MUTATED");
        var delta = CommonModMaterialRules.single(before, output, level.registryAccess());
        boolean oven = recipe.getClass().getSimpleName().equals("OvenRecipe");
        return new VerifiedTransformation(holder.id(), BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()),
                BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()), "cookingforblockheads:" + (oven ? "oven" : "toaster") + "@21.1.24",
                delta.inputs(), delta.consumed(), delta.outputs(), delta.remainders(), new VerifiedTransformation.ExperienceReward(0, 1),
                new VerifiedTransformation.CallbackPlan(oven ? "CFB_VIRTUAL_OVEN" : "CFB_TOASTER", oven ? List.of("item_on_crafted_by", "neoforge_smelted_event") : List.of(),
                        List.of("OvenResultSlot#checkTakeAchievements", "NeoForgeCookingForBlockheads:OvenItemSmeltedEvent forwards PlayerEvent.ItemSmeltedEvent"), true),
                VerifiedTransformation.SamplingEvidence.deterministic(), Map.of("stationRequired", "false", "waitTicks", "0",
                        "callbackProfile", oven ? "virtual_player_smelted_no_station_position" : "native_toaster_no_crafting_callback"), level.registryAccess());
    }
    private static void require(RecipeHolder<?> holder, ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (holder == null || !supports(holder.value())) throw new IllegalArgumentException("UNSUPPORTED_PROCESSING_ADAPTER");
        if (level.getRecipeManager().byKey(holder.id()).map(RecipeHolder::value).orElse(null) != holder.value())
            throw new IllegalStateException("PROCESSING_RECIPE_STALE");
        if (!BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()).equals(BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer())))
            throw new IllegalStateException("CFB_SERIALIZER_MISMATCH");
    }
}
