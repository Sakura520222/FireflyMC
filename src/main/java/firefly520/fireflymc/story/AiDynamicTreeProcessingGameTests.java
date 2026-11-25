package firefly520.fireflymc.story;

import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.processing.advanced.AdvancedCraftingAdapter;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Uses loaded tree-pack recipes, not JSON display outputs or synthetic seed recipes. */
@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiDynamicTreeProcessingGameTests {
    @GameTestGenerator
    public static Collection<TestFunction> installedTrees() {
        if (!ModList.get().isLoaded("dynamictrees")) return List.of();
        var tests = new ArrayList<TestFunction>();
        add(tests, "dynamic_seed_conversion_real_container", AiDynamicTreeProcessingGameTests::seedConversion);
        add(tests, "dynamic_mega_seed_real_materials", AiDynamicTreeProcessingGameTests::megaSeed);
        return tests;
    }
    private static void add(List<TestFunction> tests, String name, Consumer<GameTestHelper> body) {
        tests.add(new TestFunction("ai-dynamic-trees", "fireflymc." + name, "fireflymc:story_empty", 100, 0, true, body));
    }
    private static ItemStack item(String id) {
        return BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id)).orElseThrow().getDefaultInstance();
    }
    private static RecipeHolder<?> recipe(GameTestHelper helper, String id) {
        return helper.getLevel().getRecipeManager().byKey(ResourceLocation.parse(id)).orElseThrow();
    }
    private static ServerPlayer player(GameTestHelper helper) {
        return new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "AiTreeTest"), ClientInformation.createDefault());
    }
    private static List<ItemStack> grid(ItemStack... items) {
        var result = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
        for (int i = 0; i < items.length; i++) result.set(i, items[i]);
        return result;
    }
    @SuppressWarnings("unchecked")
    private static ItemStack nativeResult(GameTestHelper helper, RecipeHolder<?> holder, List<ItemStack> grid) {
        var input = CraftingInput.ofPositioned(3, 3, grid.stream().map(ItemStack::copy).toList()).input();
        var recipe = (Recipe<CraftingInput>) holder.value();
        helper.assertTrue(recipe.matches(input, helper.getLevel()), "The actual installed native configuration accepts this recipe");
        return recipe.assemble(input, helper.getLevel().registryAccess());
    }
    private static void seedConversion(GameTestHelper helper) {
        var holder = recipe(helper, "dynamictrees:seed_conversion"); var owner = player(helper);
        var input = grid(new ItemStack(Items.OAK_SAPLING), item("dynamictrees:dirt_bucket"));
        var expected = nativeResult(helper, holder, input);
        var result = AdvancedCraftingAdapter.verify(holder, input, owner);
        helper.assertTrue(ItemStack.matches(expected, result.outputs().getFirst())
                && expected.is(item("dynamictrees:oak_seed").getItem()), "Native species conversion output is retained");
        helper.assertValueEqual(result.consumedBySlot(), List.of(1, 1, 0, 0, 0, 0, 0, 0, 0), "Both sapling and real dirt bucket are consumed");
        var nativeInput = CraftingInput.ofPositioned(3, 3, input.stream().map(ItemStack::copy).toList()).input();
        @SuppressWarnings("unchecked") var nativeRecipe = (Recipe<CraftingInput>) holder.value();
        var nativeRemainders = nativeRecipe.getRemainingItems(nativeInput);
        helper.assertTrue(ItemStack.matches(result.remaindersBySlot().get(1), nativeRemainders.get(1)),
                "Native container semantics are retained; a missing native bucket remainder is never invented");
        helper.assertTrue(result.remaindersBySlot().stream().mapToInt(ItemStack::getCount).sum()
                == nativeRemainders.stream().mapToInt(ItemStack::getCount).sum(), "No extra container or fuel refund is introduced");
        helper.assertTrue(input.getFirst().is(Items.OAK_SAPLING) && input.get(1).is(item("dynamictrees:dirt_bucket").getItem()), "Preview does not mutate inputs");
        reject(helper, () -> AdvancedCraftingAdapter.verify(holder, grid(new ItemStack(Items.OAK_SAPLING)), owner));
        var reverse = AdvancedCraftingAdapter.verify(holder, grid(expected.copyWithCount(1), item("dynamictrees:dirt_bucket")), owner);
        helper.assertTrue(reverse.outputs().getFirst().is(Items.OAK_SAPLING), "Native reverse conversion requires another real dirt bucket");
        helper.succeed();
    }
    private static void megaSeed(GameTestHelper helper) {
        var holder = recipe(helper, "dynamictrees:mega_seed"); var owner = player(helper);
        var input = grid(item("dynamictrees:spruce_seed"), item("dynamictrees:spruce_seed"),
                item("dynamictrees:spruce_seed"), item("dynamictrees:spruce_seed"));
        var expected = nativeResult(helper, holder, input);
        var result = AdvancedCraftingAdapter.verify(holder, input, owner);
        helper.assertTrue(ItemStack.matches(expected, result.outputs().getFirst())
                && expected.is(item("dynamictrees:mega_spruce_seed").getItem()), "Four real native seeds yield the configured mega species");
        helper.assertValueEqual(result.consumedBySlot(), List.of(1, 1, 1, 1, 0, 0, 0, 0, 0), "No missing seed is manufactured");
        helper.assertTrue(result.remaindersBySlot().stream().allMatch(ItemStack::isEmpty), "No fuel or uninvolved container is returned");
        input.set(3, item("dynamictrees:oak_seed"));
        reject(helper, () -> AdvancedCraftingAdapter.verify(holder, input, owner));
        helper.assertTrue(owner.experienceLevel == 0 && owner.getInventory().isEmpty(), "No device, XP charge or inventory delivery is hidden in verification");
        helper.succeed();
    }
    private static void reject(GameTestHelper helper, Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalStateException expected) { rejected = "DYNAMIC_CRAFTING_INPUT_MISMATCH".equals(expected.getMessage()); }
        helper.assertTrue(rejected, "Native mismatching materials are rejected");
    }
}
