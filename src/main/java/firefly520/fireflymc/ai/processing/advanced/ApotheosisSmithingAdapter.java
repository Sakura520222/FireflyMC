package firefly520.fireflymc.ai.processing.advanced;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation.SamplingEvidence;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.neoforged.neoforge.common.crafting.SizedIngredient;

/** Reactive smithing's actual material effects are calculated before commitment, never hidden in onCraft. */
public final class ApotheosisSmithingAdapter {
    private static final String ROOT = "dev.shadowsoffire.apotheosis.";
    private static final Set<String> CLASSES = Set.of("util.SizedUpgradeRecipe", "socket.WithdrawalRecipe", "socket.SocketingRecipe",
            "socket.AddSocketsRecipe", "recipe.MaliceRecipe", "recipe.SupremacyRecipe", "affix.UnnamingRecipe");
    private ApotheosisSmithingAdapter() {}
    public static boolean supports(Recipe<?> recipe) {
        return recipe != null && recipe.getType() == RecipeType.SMITHING && recipe.getClass().getName().startsWith(ROOT)
                && CLASSES.contains(recipe.getClass().getName().substring(ROOT.length())) && AdvancedReflection.version("apotheosis", "8.6.0");
    }

    public static List<AdvancedInput> inputRequirements(RecipeHolder<?> holder, List<ItemStack> selected, ServerPlayer player) {
        AdvancedTransformation verified = verify(holder, selected, player);
        List<AdvancedInput> requirements = new ArrayList<>();
        for (int slot = 0; slot < 3; slot++) if (!selected.get(slot).isEmpty()) {
            ItemStack expected = AdvancedReflection.copy(selected.get(slot).copyWithCount(1), player.registryAccess());
            requirements.add(AdvancedInput.one(slot, slot == 1 ? "real_equipment" : "smithing_material",
                    verified.consumedBySlot().get(slot), stack -> ItemStack.isSameItem(stack, expected)
                            && AdvancedReflection.same(stack.copyWithCount(1), expected, player.registryAccess())));
        }
        return List.copyOf(requirements);
    }

    @SuppressWarnings("unchecked")
    public static AdvancedTransformation verify(RecipeHolder<?> holder, List<ItemStack> supplied, ServerPlayer player) {
        AdvancedReflection.current(holder, player.serverLevel());
        if (!supports(holder.value())) throw AdvancedReflection.failure("UNSUPPORTED_PROCESSING_ADAPTER");
        if (supplied.size() != 3 || supplied.get(1).getCount() != 1) throw AdvancedReflection.failure("APOTHEOSIS_SMITHING_UNIT_BASE_REQUIRED");
        var inputs = RecipeInputIsolation.copyAll(supplied, player.registryAccess());
        var nativeInputs = RecipeInputIsolation.copyAll(inputs, player.registryAccess());
        var input = new SmithingRecipeInput(nativeInputs.get(0), nativeInputs.get(1), nativeInputs.get(2));
        Recipe<SmithingRecipeInput> recipe = (Recipe<SmithingRecipeInput>) holder.value();
        if (!recipe.getClass().getSimpleName().equals("SizedUpgradeRecipe") && !inputs.getFirst().isEmpty())
            throw AdvancedReflection.failure("APOTHEOSIS_SMITHING_UNUSED_TEMPLATE");
        if (!recipe.matches(input, player.serverLevel())) throw AdvancedReflection.failure("APOTHEOSIS_SMITHING_INPUT_MISMATCH");
        ItemStack output = AdvancedReflection.copy(recipe.assemble(input, player.registryAccess()), player.registryAccess());
        if (output.isEmpty()) throw AdvancedReflection.failure("APOTHEOSIS_SMITHING_OUTPUT_EMPTY");
        for (int slot = 0; slot < 3; slot++) if (!AdvancedReflection.same(inputs.get(slot), nativeInputs.get(slot), player.registryAccess()))
            throw AdvancedReflection.failure("APOTHEOSIS_SMITHING_INPUT_MUTATED");
        List<Integer> consumed = new ArrayList<>(inputs.stream().map(stack -> stack.isEmpty() ? 0 : 1).toList());
        List<ItemStack> outputs = new ArrayList<>();
        Map<String, String> context = new HashMap<>(); context.put("owner", player.getUUID().toString());
        int previousSeed = player.getPersistentData().getInt(ApotheosisReforgingAdapter.SEED_KEY);
        String name = recipe.getClass().getSimpleName();
        if (name.equals("SizedUpgradeRecipe")) {
            SizedIngredient addition = (SizedIngredient) AdvancedReflection.call(recipe, "addition");
            if (!addition.test(inputs.get(2))) throw AdvancedReflection.failure("SIZED_UPGRADE_MATERIAL_MISSING");
            consumed.set(2, addition.count());
        } else if (name.equals("WithdrawalRecipe")) {
            Object gems = AdvancedReflection.invoke(AdvancedReflection.type(ROOT + "socket.SocketHelper"), null, "getGems",
                    new Class<?>[]{ItemStack.class}, inputs.get(1));
            if (!(gems instanceof Iterable<?> iterable)) throw AdvancedReflection.failure("SOCKET_GEM_API_MISMATCH");
            for (Object gem : iterable) {
                ItemStack stack = AdvancedReflection.copy((ItemStack) AdvancedReflection.call(gem, "gemStack"), player.registryAccess());
                if (!stack.isEmpty()) outputs.add(stack);
                if (outputs.size() > 64) throw AdvancedReflection.failure("SOCKET_GEM_LIMIT");
            }
            context.put("withdrawnGemStacks", Integer.toString(outputs.size()));
        } else if (name.equals("MaliceRecipe")) {
            malice(output, previousSeed);
            context.put("previousSeed", Integer.toString(previousSeed));
            context.put("malicePolicy", "native-two-distinct-affixes-v1");
        }
        outputs.addFirst(output);
        final ItemStack craftedOutput = AdvancedReflection.copy(output, player.registryAccess());
        boolean malice = name.equals("MaliceRecipe");
        return new AdvancedTransformation(holder, "apotheosis:reactive_smithing@8.6.0", inputs, consumed, outputs,
                AdvancedReflection.emptySlots(3), malice ? new SamplingEvidence(true, "native-malice-xoroshiro-v1", previousSeed, 0, List.of()) : SamplingEvidence.deterministic(),
                context, AdvancedTransformation.references(holder), List.of("smithing_crafted", malice ? "advance_native_reforge_seed" : "recipe_award"), actor -> {
                    if (malice) ApotheosisReforgingAdapter.advanceSeed(actor, previousSeed);
                    // The substantive native onCraft effects have already been included above. Replaying them here would double charge or drop gems.
                    ItemStack callbackOutput = AdvancedReflection.copy(craftedOutput, actor.registryAccess());
                    callbackOutput.onCraftedBy(actor.serverLevel(), actor, callbackOutput.getCount());
                    if (!AdvancedReflection.same(callbackOutput, craftedOutput, actor.registryAccess())) throw AdvancedReflection.failure("PROCESSING_CALLBACK_OUTPUT_UNCERTAIN");
                    if (!recipe.isSpecial()) { actor.awardRecipes(List.of(holder)); actor.triggerRecipeCrafted(holder, inputs); }
                }, player.registryAccess());
    }

    /** Extracted material-only part of AffixHelper.applyMalice; native builder owns the component transformation. */
    @SuppressWarnings("unchecked")
    private static void malice(ItemStack stack, int seed) {
        Class<?> helper = AdvancedReflection.type(ROOT + "affix.AffixHelper");
        Map<?, ?> affixes = (Map<?, ?>) AdvancedReflection.invoke(helper, null, "getAffixes", new Class<?>[]{ItemStack.class}, stack);
        if (affixes.size() < 2 || affixes.size() > 64) throw AdvancedReflection.failure("MALICE_AFFIX_COUNT_INVALID");
        DataComponentType<Object> type = (DataComponentType<Object>) AdvancedReflection.field(ROOT + "Apoth$Components", "AFFIXES");
        Object value = stack.get(type);
        if (value == null) throw AdvancedReflection.failure("MALICE_AFFIX_COMPONENT_MISSING");
        Object builder = AdvancedReflection.call(value, "toBuilder");
        List<?> keys = new ArrayList<>(affixes.keySet());
        var random = new XoroshiroRandomSource(seed);
        int first = random.nextInt(keys.size()), second = first;
        int attempts = 0;
        while (second == first) {
            if (++attempts > 1024) throw AdvancedReflection.failure("MALICE_RANDOM_BUDGET");
            second = random.nextInt(keys.size());
        }
        Class<?> dynamicHolder = AdvancedReflection.type("dev.shadowsoffire.placebo.reload.DynamicHolder");
        AdvancedReflection.call(builder, "upgrade", new Class<?>[]{dynamicHolder, float.class}, keys.get(first), 1.5f);
        AdvancedReflection.call(builder, "remove", new Class<?>[]{dynamicHolder}, keys.get(second));
        Object changed = AdvancedReflection.call(builder, "build");
        AdvancedReflection.invoke(helper, null, "setAffixes", new Class<?>[]{ItemStack.class, AdvancedReflection.type(ROOT + "affix.ItemAffixes")}, stack, changed);
        stack.set((DataComponentType<Boolean>) AdvancedReflection.field(ROOT + "Apoth$Components", "TOUCHED_BY_MALICE"), true);
        stack.remove((DataComponentType<?>) AdvancedReflection.field(ROOT + "Apoth$Components", "MALICE_MARKER"));
    }
}
