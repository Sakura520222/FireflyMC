package firefly520.fireflymc.story;

import firefly520.fireflymc.ai.processing.VanillaProcessingKernel;
import firefly520.fireflymc.ai.processing.VanillaProcessingKernel.CallbackCategory;
import firefly520.fireflymc.ai.processing.VanillaProcessingKernel.VerifiedTransformation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.RepairItemRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Uses loaded recipes and concrete MC inputs; never changes the recipe manager or any inventory. */
@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiVanillaProcessingGameTests {
    private AiVanillaProcessingGameTests() {}

    @GameTest(template = "story_empty", batch = "ai-vanilla-processing", timeoutTicks = 40)
    public static void mirroredShapeAndCroppedGridKeepOriginalSlotMapping(GameTestHelper helper) {
        List<ItemStack> axeGrid = grid();
        for (int slot : List.of(1, 2, 5)) axeGrid.set(slot, new ItemStack(Items.IRON_INGOT, 6));
        for (int slot : List.of(4, 7)) axeGrid.set(slot, new ItemStack(Items.STICK, 9));
        var axe = find(helper, RecipeType.CRAFTING, CraftingInput.of(3, 3, axeGrid),
                ShapedRecipe.class, Items.IRON_AXE);
        VerifiedTransformation result = VanillaProcessingKernel.verifyCrafting(axe, axeGrid, helper.getLevel());
        helper.assertTrue(result.outputs().getFirst().is(Items.IRON_AXE), "The mirrored axe recipe assembles");
        helper.assertValueEqual(result.consumedBySlot(), List.of(0, 1, 1, 0, 1, 1, 0, 1, 0),
                "Only the five occupied original grid positions are consumed");
        var mapping = result.craftingMapping().orElseThrow();
        helper.assertValueEqual(mapping.left(), 1, "The compact shape starts one column to the right");
        helper.assertValueEqual(mapping.compactToGrid(), List.of(1, 2, 4, 5, 7, 8),
                "Empty cells inside the mirrored pattern retain their grid positions");
        helper.assertValueEqual(axeGrid.get(5).getCount(), 6, "Calculation does not consume actual ingredients");

        List<ItemStack> stickGrid = grid();
        stickGrid.set(5, new ItemStack(Items.OAK_PLANKS, 8));
        stickGrid.set(8, new ItemStack(Items.OAK_PLANKS, 8));
        var stick = find(helper, RecipeType.CRAFTING, CraftingInput.of(3, 3, stickGrid),
                ShapedRecipe.class, Items.STICK);
        var cropped = VanillaProcessingKernel.verifyCrafting(stick, stickGrid, helper.getLevel());
        var croppedMapping = cropped.craftingMapping().orElseThrow();
        helper.assertValueEqual(croppedMapping.left(), 2, "Rightmost source column is retained");
        helper.assertValueEqual(croppedMapping.top(), 1, "The trimmed top row retains its offset");
        helper.assertValueEqual(croppedMapping.compactToGrid(), List.of(5, 8), "Both offsets map back correctly");
        helper.assertValueEqual(cropped.outputs().getFirst().getCount(), 4, "Vanilla output batch is preserved");
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "ai-vanilla-processing", timeoutTicks = 40)
    public static void selectedCraftingRecipeReturnsItsContainersAndBatch(GameTestHelper helper) {
        List<ItemStack> cakeGrid = List.of(
                new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET),
                new ItemStack(Items.SUGAR, 3), new ItemStack(Items.EGG, 2), new ItemStack(Items.SUGAR, 3),
                new ItemStack(Items.WHEAT, 5), new ItemStack(Items.WHEAT, 5), new ItemStack(Items.WHEAT, 5));
        var cake = find(helper, RecipeType.CRAFTING, CraftingInput.of(3, 3, cakeGrid),
                ShapedRecipe.class, Items.CAKE);
        var result = VanillaProcessingKernel.verifyCrafting(cake, cakeGrid, helper.getLevel());
        helper.assertValueEqual(result.recipeId(), cake.id(), "The selected holder is retained for commit");
        helper.assertValueEqual(result.consumedBySlot(), Collections.nCopies(9, 1),
                "Exactly one material is consumed from each occupied grid slot");
        List<ItemStack> remainders = result.remaindersBySlot();
        for (int slot = 0; slot < 9; slot++) {
            helper.assertTrue(slot < 3 ? remainders.get(slot).is(Items.BUCKET) : remainders.get(slot).isEmpty(),
                    "Bucket remainders are assigned to their original milk slots");
        }
        helper.assertValueEqual(result.callbacks().category(), CallbackCategory.CRAFTING_RESULT_SLOT,
                "Crafting callback category is recorded without running it");
        helper.assertTrue(result.callbacks().neoForgeCraftingEvent(), "The future crafting event is explicit");
        helper.assertTrue(cakeGrid.getFirst().is(Items.MILK_BUCKET), "Source milk is not replaced by the preview");

        List<ItemStack> planksGrid = grid();
        planksGrid.set(8, new ItemStack(Items.OAK_LOG, 12));
        var planks = find(helper, RecipeType.CRAFTING, CraftingInput.of(3, 3, planksGrid),
                ShapelessRecipe.class, Items.OAK_PLANKS);
        var planksResult = VanillaProcessingKernel.verifyCrafting(planks, planksGrid, helper.getLevel());
        helper.assertValueEqual(planksResult.outputs().getFirst().getCount(), 4, "Shapeless batch count is native");
        helper.assertValueEqual(planksResult.consumedBySlot().get(8), 1, "Only one of the twelve logs is budgeted");
        helper.assertValueEqual(planksGrid.get(8).getCount(), 12, "All source logs remain in the caller's snapshot");
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "ai-vanilla-processing", timeoutTicks = 40)
    public static void overlappingStonecuttingRecipesUseTheRequestedHolder(GameTestHelper helper) {
        ItemStack stone = new ItemStack(Items.STONE, 16);
        SingleRecipeInput input = new SingleRecipeInput(stone);
        var slabs = find(helper, RecipeType.STONECUTTING, input, StonecutterRecipe.class, Items.STONE_SLAB);
        var stairs = find(helper, RecipeType.STONECUTTING, input, StonecutterRecipe.class, Items.STONE_STAIRS);
        helper.assertTrue(!slabs.id().equals(stairs.id()), "Two loaded recipes match the same actual input");
        var slabResult = VanillaProcessingKernel.verifySingle(slabs, input, helper.getLevel());
        var stairResult = VanillaProcessingKernel.verifySingle(stairs, input, helper.getLevel());
        helper.assertTrue(slabResult.outputs().getFirst().is(Items.STONE_SLAB), "Selected slabs are produced");
        helper.assertTrue(stairResult.outputs().getFirst().is(Items.STONE_STAIRS), "Selected stairs are produced");
        helper.assertValueEqual(slabResult.outputs().getFirst().getCount(), 2, "Slab recipe output count is preserved");
        helper.assertValueEqual(stairResult.outputs().getFirst().getCount(), 1, "Stair recipe output count is preserved");
        helper.assertValueEqual(stairResult.recipeId(), stairs.id(), "No global first match replaces the selected holder");
        helper.assertValueEqual(stone.getCount(), 16, "Both previews leave source quantity unchanged");
        helper.assertTrue(slabResult.remaindersBySlot().getFirst().isEmpty(), "Stonecutting has no crafting-container return");
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "ai-vanilla-processing", timeoutTicks = 40)
    public static void smithingPreservesDamagedInputAndResultSnapshotsAreReadOnly(GameTestHelper helper) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.setDamageValue(93);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("old blade"));
        sword.enchant(helper.getLevel().holderOrThrow(Enchantments.UNBREAKING), 2);
        SmithingRecipeInput input = new SmithingRecipeInput(
                new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), sword, new ItemStack(Items.NETHERITE_INGOT));
        var holder = find(helper, RecipeType.SMITHING, input, SmithingTransformRecipe.class, Items.NETHERITE_SWORD);
        var result = VanillaProcessingKernel.verifySmithing(holder, input, helper.getLevel());
        ItemStack output = result.outputs().getFirst();
        helper.assertTrue(output.is(Items.NETHERITE_SWORD), "Actual smithing recipe transforms the base item");
        helper.assertValueEqual(output.getDamageValue(), 93, "Native absolute damage is preserved");
        helper.assertValueEqual(output.get(DataComponents.ENCHANTMENTS), sword.get(DataComponents.ENCHANTMENTS),
                "Registry-backed enchantments survive the native base transformation");
        helper.assertValueEqual(result.consumedBySlot(), List.of(1, 1, 1), "Template, base and addition are all real inputs");
        helper.assertTrue(result.remaindersBySlot().stream().allMatch(ItemStack::isEmpty),
                "Native smithing does not run generic crafting remainders");
        ((MutableComponent) output.get(DataComponents.CUSTOM_NAME)).append(" changed externally");
        output.setDamageValue(0);
        helper.assertValueEqual(sword.get(DataComponents.CUSTOM_NAME).getString(), "old blade",
                "Returned result components cannot alter the original equipment");
        helper.assertValueEqual(sword.getDamageValue(), 93, "Preview never repairs or consumes the real base");
        helper.assertValueEqual(result.outputs().getFirst().get(DataComponents.CUSTOM_NAME).getString(), "old blade",
                "Editing a returned component cannot alter the verified transformation");
        helper.assertValueEqual(result.outputs().getFirst().getDamageValue(), 93,
                "Editing returned durability cannot alter the verified transformation");
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "ai-vanilla-processing", timeoutTicks = 40)
    public static void identicalSmithingTrimDoesNotClaimAnotherOutput(GameTestHelper helper) {
        SmithingRecipeInput input = new SmithingRecipeInput(
                new ItemStack(Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE),
                new ItemStack(Items.IRON_CHESTPLATE), new ItemStack(Items.EMERALD));
        var holder = find(helper, RecipeType.SMITHING, input, SmithingTrimRecipe.class, Items.IRON_CHESTPLATE);
        var result = VanillaProcessingKernel.verifySmithing(holder, input, helper.getLevel());
        ItemStack trimmed = result.outputs().getFirst();
        helper.assertTrue(trimmed.has(DataComponents.TRIM), "Actual registry material and pattern are applied");
        SmithingRecipeInput unchanged = new SmithingRecipeInput(input.template(), trimmed, input.addition());
        expectFailure(helper, "PROCESSING_RESULT_EMPTY",
                () -> VanillaProcessingKernel.verifySmithing(holder, unchanged, helper.getLevel()));
        helper.assertTrue(!input.base().has(DataComponents.TRIM), "Only the isolated result receives the trim");
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "ai-vanilla-processing", timeoutTicks = 40)
    public static void thermalRecipesReturnRewardsWithoutInventingFuelContainers(GameTestHelper helper) {
        checkThermal(helper, RecipeType.SMELTING, SmeltingRecipe.class, Items.IRON_ORE, Items.IRON_INGOT);
        checkThermal(helper, RecipeType.BLASTING, BlastingRecipe.class, Items.IRON_ORE, Items.IRON_INGOT);
        checkThermal(helper, RecipeType.SMOKING, SmokingRecipe.class, Items.BEEF, Items.COOKED_BEEF);
        var sponge = find(helper, RecipeType.SMELTING, new SingleRecipeInput(new ItemStack(Items.WET_SPONGE)),
                SmeltingRecipe.class, Items.SPONGE);
        var dried = VanillaProcessingKernel.verifySingle(
                sponge, new SingleRecipeInput(new ItemStack(Items.WET_SPONGE)), helper.getLevel());
        helper.assertTrue(dried.outputs().getFirst().is(Items.SPONGE), "Wet sponge still dries without fuel");
        helper.assertTrue(dried.remaindersBySlot().stream().allMatch(ItemStack::isEmpty),
                "No real bucket entered a fuel slot, so neither an empty nor a water bucket is returned");
        helper.succeed();
    }

    @GameTest(template = "story_empty", batch = "ai-vanilla-processing", timeoutTicks = 40)
    public static void dynamicRecipesAndStaleRecipeObjectsAreRejected(GameTestHelper helper) {
        var repair = helper.getLevel().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
                .filter(holder -> holder.value().getClass() == RepairItemRecipe.class).findFirst()
                .orElseThrow(() -> new AssertionError("Loaded vanilla repair recipe is required"));
        helper.assertTrue(!VanillaProcessingKernel.supports(repair.value()),
                "A dynamic recipe is not trusted solely because its type is crafting");
        expectFailure(helper, "UNSUPPORTED_PROCESSING_ADAPTER",
                () -> VanillaProcessingKernel.verifyCrafting(repair, grid(), helper.getLevel()));

        List<ItemStack> input = grid();
        input.set(0, new ItemStack(Items.OAK_LOG));
        var loaded = find(helper, RecipeType.CRAFTING, CraftingInput.of(3, 3, input),
                ShapelessRecipe.class, Items.OAK_PLANKS);
        ShapelessRecipe original = (ShapelessRecipe) loaded.value();
        ShapelessRecipe differentInstance = new ShapelessRecipe(original.getGroup(), original.category(),
                original.getResultItem(helper.getLevel().registryAccess()).copy(), original.getIngredients());
        var stale = new RecipeHolder<>(loaded.id(), differentInstance);
        expectFailure(helper, "PROCESSING_RECIPE_STALE",
                () -> VanillaProcessingKernel.verifyCrafting(stale, input, helper.getLevel()));
        helper.assertTrue(helper.getLevel().getRecipeManager().byKey(loaded.id()).orElseThrow().value() == original,
                "The test and kernel never replace the server recipe manager");
        helper.succeed();
    }

    private static List<ItemStack> grid() {
        return new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
    }

    private static <I extends RecipeInput, R extends Recipe<I>> RecipeHolder<R> find(
            GameTestHelper helper, RecipeType<R> type, I input, Class<?> implementation, Item output) {
        return helper.getLevel().getRecipeManager().getAllRecipesFor(type).stream()
                .filter(holder -> holder.value().getClass() == implementation)
                .filter(holder -> holder.value().matches(input, helper.getLevel()))
                .filter(holder -> holder.value().getResultItem(helper.getLevel().registryAccess()).is(output))
                .findFirst().orElseThrow(() -> new AssertionError("Required loaded vanilla recipe was not found"));
    }

    private static <R extends AbstractCookingRecipe> void checkThermal(
            GameTestHelper helper, RecipeType<R> type, Class<?> implementation, Item inputItem, Item outputItem) {
        ItemStack source = new ItemStack(inputItem, 7);
        SingleRecipeInput input = new SingleRecipeInput(source);
        var holder = find(helper, type, input, implementation, outputItem);
        var result = VanillaProcessingKernel.verifySingle(holder, input, helper.getLevel());
        helper.assertTrue(result.outputs().getFirst().is(outputItem), "The selected thermal output is preserved");
        helper.assertValueEqual(result.recipeType(), BuiltInRegistries.RECIPE_TYPE.getKey(type),
                "Thermal type comes from the current registered recipe");
        helper.assertValueEqual(result.consumedBySlot(), List.of(1), "One material is budgeted without fuel inputs");
        helper.assertValueEqual(result.experienceReward().perOperation(), holder.value().getExperience(),
                "Normal XP is reported per completed operation, not silently awarded or multiplied by result count");
        helper.assertValueEqual(result.callbacks().category(), CallbackCategory.FURNACE_RESULT_SLOT,
                "The commit layer receives the appropriate callback category");
        helper.assertTrue(result.callbacks().neoForgeSmeltedEvent(), "The pending smelted event is explicit");
        helper.assertValueEqual(source.getCount(), 7, "Thermal preview cannot take real materials");
    }

    private static void expectFailure(GameTestHelper helper, String code, Runnable operation) {
        try {
            operation.run();
            helper.fail("Expected " + code);
        } catch (VanillaProcessingKernel.ProcessingException failure) {
            helper.assertValueEqual(failure.code(), code, "Failure exposes only its stable code");
            helper.assertTrue(failure.getCause() == null, "Private recipe diagnostics are not exposed");
        }
    }
}
