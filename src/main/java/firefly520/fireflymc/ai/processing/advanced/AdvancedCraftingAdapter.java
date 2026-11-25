package firefly520.fireflymc.ai.processing.advanced;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import firefly520.fireflymc.ai.processing.RecipeInputIsolation;
import firefly520.fireflymc.ai.processing.VerifiedTransformation.SamplingEvidence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.EventHooks;

/** Whitelisted dynamic recipes; component-bearing equipment, seeds and containers remain real inputs. */
public final class AdvancedCraftingAdapter {
    private static final Set<String> PSI = Set.of("AssemblyScavengeRecipe", "BulletToDriveRecipe", "BulletUpgradeRecipe",
            "ColorizerChangeRecipe", "DriveDuplicateRecipe", "SensorAttachRecipe", "SensorRemoveRecipe");
    private AdvancedCraftingAdapter() {}
    public static boolean supports(Recipe<?> recipe) {
        if (recipe == null || recipe.getType() != RecipeType.CRAFTING) return false;
        return recipe.getClass().getName().equals("dev.shadowsoffire.apotheosis.recipe.PotionCharmRecipe")
                && AdvancedReflection.version("apotheosis", "8.6.0")
                || recipe.getClass().getPackageName().equals("vazkii.psi.common.crafting.recipe")
                && PSI.contains(recipe.getClass().getSimpleName()) && AdvancedReflection.version("psi", "1.21.1-108")
                || dynamicTrees(recipe);
    }

    private static boolean dynamicTrees(Recipe<?> recipe) {
        if (!AdvancedReflection.version("dynamictrees", "1.7.2")) return false;
        String serializer = switch (recipe.getClass().getName()) {
            case "com.dtteam.dynamictrees.recipe.SeedConversionRecipe" -> "dynamictrees:seed_conversion";
            case "com.dtteam.dynamictrees.recipe.MegaSeedRecipe" -> "dynamictrees:mega_seed";
            default -> "";
        };
        return !serializer.isEmpty() && serializer.equals(String.valueOf(
                net.minecraft.core.registries.BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer())));
    }

    /** Concrete-grid requirements are component-bound; unknown dynamic ingredients cannot be inferred from display data. */
    public static List<AdvancedInput> inputRequirements(RecipeHolder<?> holder, List<ItemStack> grid, ServerPlayer player) {
        verify(holder, grid, player);
        List<AdvancedInput> result = new ArrayList<>();
        for (int slot = 0; slot < grid.size(); slot++) if (!grid.get(slot).isEmpty()) {
            ItemStack expected = AdvancedReflection.copy(grid.get(slot).copyWithCount(1), player.registryAccess());
            result.add(AdvancedInput.one(slot, "dynamic_component_input", 1, stack -> ItemStack.isSameItem(stack, expected)
                    && AdvancedReflection.same(stack.copyWithCount(1), expected, player.registryAccess())));
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    public static AdvancedTransformation verify(RecipeHolder<?> holder, List<ItemStack> grid, ServerPlayer player) {
        AdvancedReflection.current(holder, player.serverLevel());
        if (!supports(holder.value())) throw AdvancedReflection.failure("UNSUPPORTED_PROCESSING_ADAPTER");
        if (grid.size() != 9 || grid.stream().anyMatch(stack -> stack.getCount() > 1)) throw AdvancedReflection.failure("DYNAMIC_CRAFTING_UNIT_GRID_REQUIRED");
        var supplied = RecipeInputIsolation.copyAll(grid, player.registryAccess());
        var positioned = CraftingInput.ofPositioned(3, 3, RecipeInputIsolation.copyAll(supplied, player.registryAccess()));
        var input = positioned.input();
        var recipe = (Recipe<CraftingInput>) holder.value();
        var previous = CommonHooks.getCraftingPlayer();
        CommonHooks.setCraftingPlayer(player);
        try {
            if (!recipe.matches(input, player.serverLevel())) throw AdvancedReflection.failure("DYNAMIC_CRAFTING_INPUT_MISMATCH");
            ItemStack output = AdvancedReflection.copy(recipe.assemble(input, player.registryAccess()), player.registryAccess());
            if (output.isEmpty()) throw AdvancedReflection.failure("DYNAMIC_CRAFTING_RESULT_EMPTY");
            var nativeRemainders = recipe.getRemainingItems(input);
            if (nativeRemainders.size() != input.size()) throw AdvancedReflection.failure("DYNAMIC_CRAFTING_REMAINDERS_INVALID");
            var remainders = AdvancedReflection.emptySlots(9);
            for (int y = 0; y < input.height(); y++) for (int x = 0; x < input.width(); x++) {
                int slot = (y + positioned.top()) * 3 + x + positioned.left();
                remainders.set(slot, AdvancedReflection.copy(nativeRemainders.get(y * input.width() + x), player.registryAccess()));
                if (!AdvancedReflection.same(input.getItem(x, y), supplied.get(slot), player.registryAccess())) throw AdvancedReflection.failure("DYNAMIC_CRAFTING_INPUT_MUTATED");
            }
            return new AdvancedTransformation(holder, dynamicTrees(recipe) ? "advanced:dynamic_tree_crafting-v1" : "advanced:component_crafting-v1", supplied,
                    supplied.stream().map(stack -> stack.isEmpty() ? 0 : 1).toList(), List.of(output), remainders,
                    SamplingEvidence.deterministic(), Map.of("owner", player.getUUID().toString(), "unitInputGrid", "true"),
                    AdvancedTransformation.references(holder), List.of("item_crafted", "neo_crafting_event", "recipe_award"),
                    actor -> crafted(holder, supplied, output, actor), player.registryAccess());
        } finally { CommonHooks.setCraftingPlayer(previous); }
    }

    static void crafted(RecipeHolder<?> holder, List<ItemStack> inputs, ItemStack output, ServerPlayer player) {
        var previous = CommonHooks.getCraftingPlayer();
        CommonHooks.setCraftingPlayer(player);
        try {
            ItemStack result = AdvancedReflection.copy(output, player.registryAccess());
            result.onCraftedBy(player.serverLevel(), player, result.getCount());
            EventHooks.firePlayerCraftingEvent(player, result, new SimpleContainer(RecipeInputIsolation.copyAll(inputs, player.registryAccess()).toArray(ItemStack[]::new)));
            if (!AdvancedReflection.same(result, output, player.registryAccess())) throw AdvancedReflection.failure("PROCESSING_CALLBACK_OUTPUT_UNCERTAIN");
            if (!holder.value().isSpecial()) { player.awardRecipes(List.of(holder)); player.triggerRecipeCrafted(holder, inputs); }
        } finally { CommonHooks.setCraftingPlayer(previous); }
    }
}
