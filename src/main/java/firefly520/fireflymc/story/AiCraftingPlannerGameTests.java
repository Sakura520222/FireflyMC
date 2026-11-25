package firefly520.fireflymc.story;

import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.processing.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Uses the loaded vanilla holders, native matching/assemble/remainders and component codecs. */
@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiCraftingPlannerGameTests {
    @GameTest(template = "story_empty", batch = "ai-crafting-planner", timeoutTicks = 80)
    public static void twoLogsSupplyPickaxeAndEverySurplusItem(GameTestHelper helper) throws Exception {
        var catalog = new NativeCatalog(helper, List.of("oak_planks", "stick", "wooden_pickaxe"));
        var logs = snapshot(helper, new ItemStack(Items.OAK_LOG, 2));
        var plan = CraftingPlanner.solve(List.of(new CraftingPlanner.Stock("logs", logs, 2, "logs", 1)), Map.of("logs", 2L),
                List.of(snapshot(helper, new ItemStack(Items.WOODEN_PICKAXE))), 1, catalog, CraftingPlanner.Limits.standard());
        helper.assertValueEqual(plan.physicalInputs(), Map.of("logs", 2), "Only two actual logs supply the whole chain");
        helper.assertValueEqual(plan.steps().size(), 4, "Two plank batches, sticks and a pickaxe");
        var expected = List.of(snapshot(helper, new ItemStack(Items.OAK_PLANKS, 3)), snapshot(helper, new ItemStack(Items.STICK, 2)),
                snapshot(helper, new ItemStack(Items.WOODEN_PICKAXE)));
        helper.assertValueEqual(ProcessingChainMaterials.totals(plan.products()), ProcessingChainMaterials.totals(expected), "Every surplus is returned");
        helper.assertValueEqual(ProcessingChainMaterials.totals(ProcessingChainMaterials.replay(List.of(logs), plan.steps())),
                ProcessingChainMaterials.totals(expected), "Exact input/output replay independently conserves the graph");
        helper.succeed();
    }
    @GameTest(template = "story_empty", batch = "ai-crafting-planner", timeoutTicks = 80)
    public static void actualCakeRecipeReturnsOnlyItsThreeRealMilkBuckets(GameTestHelper helper) throws Exception {
        var catalog = new NativeCatalog(helper, List.of("cake"));
        var inputs = List.of(new ItemStack(Items.MILK_BUCKET, 3), new ItemStack(Items.SUGAR, 2), new ItemStack(Items.EGG), new ItemStack(Items.WHEAT, 3));
        var stocks = new ArrayList<CraftingPlanner.Stock>(); var pools = new HashMap<String, Long>();
        for (int i = 0; i < inputs.size(); i++) {
            String key = "slot-" + i; ItemStack stack = inputs.get(i);
            stocks.add(new CraftingPlanner.Stock(key, snapshot(helper, stack), stack.getCount(), key, 1)); pools.put(key, (long) stack.getCount());
        }
        var plan = CraftingPlanner.solve(stocks, pools, List.of(snapshot(helper, new ItemStack(Items.CAKE))), 1, catalog, CraftingPlanner.Limits.standard());
        var expected = List.of(snapshot(helper, new ItemStack(Items.CAKE)), snapshot(helper, new ItemStack(Items.BUCKET, 3)));
        helper.assertValueEqual(ProcessingChainMaterials.totals(plan.products()), ProcessingChainMaterials.totals(expected), "Only three actually supplied milk buckets return");
        helper.assertValueEqual(plan.physicalInputs().values().stream().mapToInt(Integer::intValue).sum(), 9, "All nine ingredients are real materials");
        helper.succeed();
    }
    private static MaterialStack snapshot(GameTestHelper helper, ItemStack stack) { return ItemSnapshotCodec.capture(stack, helper.getLevel().registryAccess()); }
    private static final class NativeCatalog implements CraftingPlanner.Catalog {
        private final GameTestHelper helper;
        private final List<RecipeHolder<?>> holders;
        NativeCatalog(GameTestHelper helper, List<String> ids) {
            this.helper = helper;
            holders = ids.stream().<RecipeHolder<?>>map(id -> helper.getLevel().getRecipeManager().byKey(ResourceLocation.withDefaultNamespace(id)).orElseThrow()).toList();
        }
        @Override public List<CraftingPlanner.Route> producers(MaterialStack wanted) {
            var item = ItemSnapshotCodec.restore(wanted, helper.getLevel().registryAccess()).getItem();
            var routes = new ArrayList<CraftingPlanner.Route>();
            for (var holder : holders) {
                var recipe = holder.value();
                if (!recipe.getResultItem(helper.getLevel().registryAccess()).is(item)) continue;
                var ingredients = new ArrayList<CraftingPlanner.Ingredient>();
                for (int i = 0; i < recipe.getIngredients().size(); i++) {
                    Ingredient ingredient = recipe.getIngredients().get(i);
                    if (ingredient.isEmpty()) continue;
                    int slot = recipe instanceof ShapedRecipe shaped ? i / shaped.getWidth() * 3 + i % shaped.getWidth() : i;
                    ingredients.add(new CraftingPlanner.Ingredient(slot, 1, Arrays.stream(ingredient.getItems())
                            .map(stack -> snapshot(helper, stack.copyWithCount(1))).distinct().toList()));
                }
                routes.add(new CraftingPlanner.Route(holder.id().toString(), 9, ingredients));
            }
            return routes;
        }
        @Override public List<MaterialStack> verify(CraftingPlanner.Route route, List<MaterialStack> inputs) {
            var holder = helper.getLevel().getRecipeManager().byKey(ResourceLocation.parse(route.recipeId())).orElseThrow();
            var result = VanillaProcessingKernel.verifyCrafting(holder, inputs.stream().map(s -> ItemSnapshotCodec.restore(s, helper.getLevel().registryAccess())).toList(), helper.getLevel());
            return ProcessingBatchProducts.aggregate(result, 1, helper.getLevel().registryAccess());
        }
        @Override public void check() {}
    }
}
