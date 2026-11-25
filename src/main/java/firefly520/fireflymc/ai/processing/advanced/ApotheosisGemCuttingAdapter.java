package firefly520.fireflymc.ai.processing.advanced;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation.SamplingEvidence;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Real base replacement plus exact native top/left/right deltas. Does not shrink every slot by one. */
public final class ApotheosisGemCuttingAdapter {
    private static final String ROOT = "dev.shadowsoffire.apotheosis.socket.gem.cutting.";
    private ApotheosisGemCuttingAdapter() {}
    public static boolean supports(Recipe<?> recipe) {
        return recipe != null && (recipe.getClass().getName().equals(ROOT + "BasicGemCuttingRecipe")
                || recipe.getClass().getName().equals(ROOT + "PurityUpgradeRecipe"))
                && AdvancedReflection.version("apotheosis", "8.6.0")
                && AdvancedReflection.id("apotheosis:gem_cutting").equals(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()));
    }

    /** Supply the selected base when resolving top: purity upgrades require the same actual gem and purity. */
    @SuppressWarnings("unchecked")
    public static List<AdvancedInput> inputRequirements(RecipeHolder<?> holder, List<ItemStack> selected, ServerLevel level) {
        require(holder, level);
        if (selected.size() != 4) throw AdvancedReflection.failure("GEM_CUTTING_FOUR_SLOTS_REQUIRED");
        RecipeInput context = input(RecipeInputIsolation.copyAll(selected, level.registryAccess()));
        List<AdvancedInput> result = new ArrayList<>();
        String[] methods = {"isValidBaseItem", "isValidTopItem", "isValidLeftItem", "isValidRightItem"};
        for (int slot = 0; slot < 4; slot++) {
            final String method = methods[slot];
            if (slot < 2 && (slot == 0 || holder.value().getClass().getSimpleName().equals("PurityUpgradeRecipe"))) {
                result.add(AdvancedInput.one(slot, slot == 0 ? "real_base" : "matching_real_gem", 1,
                        stack -> (boolean) AdvancedReflection.call(holder.value(), method,
                                new Class<?>[]{AdvancedReflection.type(ROOT + "GemCuttingRecipe$CuttingRecipeInput"), ItemStack.class}, context, stack)));
            } else {
                List<SizedIngredient> choices = (List<SizedIngredient>) AdvancedReflection.call(holder.value(), slot == 1 ? "top" : slot == 2 ? "left" : "right");
                if (choices.isEmpty() || choices.size() > 64) throw AdvancedReflection.failure("GEM_CUTTING_ALTERNATIVES_INVALID");
                // Native getMatch chooses the first matching sized option; verify derives the actual committed delta.
                result.add(new AdvancedInput(slot, "consumable_gem_material", choices.stream()
                        .map(choice -> new AdvancedInput.Option(choice.count(), choice.ingredient()::test)).toList()));
            }
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    public static AdvancedTransformation verify(RecipeHolder<?> holder, List<ItemStack> supplied, ServerLevel level) {
        require(holder, level);
        if (supplied.size() != 4 || supplied.getFirst().getCount() != 1) throw AdvancedReflection.failure("GEM_CUTTING_BASE_UNIT_REQUIRED");
        var inputs = RecipeInputIsolation.copyAll(supplied, level.registryAccess());
        RecipeInput nativeInput = input(RecipeInputIsolation.copyAll(inputs, level.registryAccess()));
        Recipe<RecipeInput> recipe = (Recipe<RecipeInput>) holder.value();
        if (!recipe.matches(nativeInput, level)) throw AdvancedReflection.failure("GEM_CUTTING_INPUT_MISMATCH");
        ItemStack output = AdvancedReflection.copy(recipe.assemble(nativeInput, level.registryAccess()), level.registryAccess());
        if (output.isEmpty()) throw AdvancedReflection.failure("GEM_CUTTING_RESULT_EMPTY");
        for (int i = 0; i < 4; i++) if (!ItemStack.matches(inputs.get(i), nativeInput.getItem(i)))
            throw AdvancedReflection.failure("GEM_CUTTING_PREVIEW_MUTATED_INPUT");
        AdvancedReflection.call(recipe, "decrementInputs", new Class<?>[]{AdvancedReflection.type(ROOT + "GemCuttingRecipe$CuttingRecipeInput"), Level.class}, nativeInput, level);
        List<Integer> consumed = new ArrayList<>(List.of(1));
        if (!ItemStack.matches(inputs.getFirst(), nativeInput.getItem(0))) throw AdvancedReflection.failure("GEM_CUTTING_BASE_DELTA_INVALID");
        for (int slot = 1; slot < 4; slot++) {
            ItemStack before = inputs.get(slot), after = nativeInput.getItem(slot);
            if (!after.isEmpty() && !ItemStack.isSameItemSameComponents(before, after) || after.getCount() > before.getCount())
                throw AdvancedReflection.failure("GEM_CUTTING_MATERIAL_DELTA_INVALID");
            consumed.add(before.getCount() - after.getCount());
        }
        return new AdvancedTransformation(holder, "apotheosis:gem_cutting@8.6.0", inputs, consumed, List.of(output),
                AdvancedReflection.emptySlots(4), SamplingEvidence.deterministic(), Map.of("baseReplacement", "true"),
                AdvancedTransformation.references(holder), List.of("native_gem_cutting_trigger"), player -> {
                    Object trigger = AdvancedReflection.field("dev.shadowsoffire.apotheosis.Apoth$Triggers", "GEM_CUTTING");
                    AdvancedReflection.call(trigger, "trigger", new Class<?>[]{ServerPlayer.class, ItemStack.class}, player,
                            AdvancedReflection.copy(output, player.registryAccess()));
                }, level.registryAccess());
    }
    public static AdvancedTransformation verify(RecipeHolder<?> holder, List<ItemStack> supplied, ServerPlayer player) {
        return verify(holder, supplied, player.serverLevel()).ownedBy(player);
    }
    private static RecipeInput input(List<ItemStack> stacks) {
        ItemStackHandler handler = new ItemStackHandler(4);
        for (int i = 0; i < 4; i++) handler.setStackInSlot(i, stacks.get(i));
        return (RecipeInput) AdvancedReflection.construct(ROOT + "GemCuttingRecipe$CuttingRecipeInput", new Class<?>[]{IItemHandler.class}, handler);
    }
    private static void require(RecipeHolder<?> holder, ServerLevel level) {
        AdvancedReflection.current(holder, level);
        if (!supports(holder.value())) throw AdvancedReflection.failure("UNSUPPORTED_PROCESSING_ADAPTER");
    }
}
