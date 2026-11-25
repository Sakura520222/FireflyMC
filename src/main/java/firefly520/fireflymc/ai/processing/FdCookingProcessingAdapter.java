package firefly520.fireflymc.ai.processing;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.wrapper.RecipeWrapper;
import java.lang.reflect.*;
import java.util.*;

/** Farmer's Delight 1.3.2 pot semantics; namespace-independent for MND recipes using this exact implementation. */
public final class FdCookingProcessingAdapter {
    public static final String CLASS = "vectorwing.farmersdelight.common.crafting.CookingPotRecipe";
    private static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath("farmersdelight", "cooking");
    private FdCookingProcessingAdapter() {}
    public static boolean supports(Recipe<?> recipe) {
        return recipe != null && recipe.getClass().getName().equals(CLASS)
                && TYPE.equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))
                && ModList.get().getModContainerById("farmersdelight")
                .map(mod -> mod.getModInfo().getVersion().toString().equals("1.3.2")).orElse(false);
    }
    public static ItemStack container(RecipeHolder<?> holder, ServerLevel level) {
        require(holder, level);
        return RecipeInputIsolation.copy((ItemStack) call(holder.value(), "getOutputContainer", ItemStack.class), level.registryAccess());
    }
    public static VerifiedTransformation verify(RecipeHolder<?> holder, List<ItemStack> supplied, ServerLevel level) {
        require(holder, level);
        if (supplied.size() != 7) throw new IllegalArgumentException("FD_COOKING_INPUT_SIZE");
        var inputs = RecipeInputIsolation.copyAll(supplied, level.registryAccess());
        var handler = new ItemStackHandler(6);
        for (int i = 0; i < 6; i++) handler.setStackInSlot(i, RecipeInputIsolation.copy(inputs.get(i), level.registryAccess()));
        var wrapped = new RecipeWrapper(handler);
        @SuppressWarnings("unchecked") Recipe<RecipeWrapper> recipe = (Recipe<RecipeWrapper>) holder.value();
        if (!recipe.matches(wrapped, level)) throw new IllegalArgumentException("FD_COOKING_INPUT_MISMATCH");
        ItemStack output = RecipeInputIsolation.copy(recipe.assemble(wrapped, level.registryAccess()), level.registryAccess());
        if (output.isEmpty() || output.getCount() > output.getMaxStackSize()) throw new IllegalArgumentException("FD_COOKING_OUTPUT_INVALID");
        for (int i = 0; i < 6; i++) if (handler.getStackInSlot(i).getCount() != inputs.get(i).getCount()
                || !ItemStack.isSameItemSameComponents(handler.getStackInSlot(i), inputs.get(i)))
            throw new IllegalStateException("FD_COOKING_INPUT_MUTATED");
        var delta = CommonModMaterialRules.cooking(inputs.subList(0, 6), List.of(inputs.get(6)), output, container(holder, level), overrides(), level.registryAccess());
        float xp = ((Number) call(holder.value(), "getExperience", float.class)).floatValue();
        return new VerifiedTransformation(holder.id(), TYPE, BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer()),
                "farmersdelight:cooking@1.3.2", delta.inputs(), delta.consumed(), delta.outputs(), delta.remainders(),
                new VerifiedTransformation.ExperienceReward(xp, 1), new VerifiedTransformation.CallbackPlan("FD_COOKING_RESULT",
                List.of("item_on_crafted_by", "award_selected_recipe_and_advancement", "accumulated_recipe_experience"),
                List.of("CookingPotBlockEntity#processCooking", "CookingPotBlockEntity#useStoredContainersOnMeal", "CookingPotResultSlot#checkTakeAchievements"), true),
                VerifiedTransformation.SamplingEvidence.deterministic(), Map.of("stationRequired", "false", "containerPerOutputUnit", "true"), level.registryAccess());
    }
    @SuppressWarnings("unchecked")
    private static Map<Item, Item> overrides() {
        try {
            Class<?> type = Class.forName("vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity", false, FdCookingProcessingAdapter.class.getClassLoader());
            Field field = type.getField("INGREDIENT_REMAINDER_OVERRIDES");
            if (!Modifier.isStatic(field.getModifiers()) || !Map.class.isAssignableFrom(field.getType())) throw new NoSuchFieldException();
            Map<?, ?> source = (Map<?, ?>) field.get(null);
            if (source.size() > 128 || source.entrySet().stream().anyMatch(e -> !(e.getKey() instanceof Item) || !(e.getValue() instanceof Item)))
                throw new IllegalStateException("FD_REMAINDER_OVERRIDE_INVALID");
            return Map.copyOf((Map<Item, Item>) source);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("FD_COOKING_API_MISMATCH"); }
    }
    private static Object call(Object recipe, String name, Class<?> returns) {
        try {
            Method method = recipe.getClass().getMethod(name);
            if (method.getReturnType() != returns || Modifier.isStatic(method.getModifiers())) throw new NoSuchMethodException();
            return method.invoke(recipe);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("FD_COOKING_API_MISMATCH"); }
    }
    private static void require(RecipeHolder<?> holder, ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (holder == null || !supports(holder.value())) throw new IllegalArgumentException("UNSUPPORTED_PROCESSING_ADAPTER");
        if (level.getRecipeManager().byKey(holder.id()).map(RecipeHolder::value).orElse(null) != holder.value())
            throw new IllegalStateException("PROCESSING_RECIPE_STALE");
        if (!TYPE.equals(BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer())))
            throw new IllegalStateException("FD_COOKING_SERIALIZER_MISMATCH");
    }
}
