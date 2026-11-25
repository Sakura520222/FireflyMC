package firefly520.fireflymc.story;

import firefly520.fireflymc.ai.processing.ChippedProcessingAdapter;
import firefly520.fireflymc.ai.processing.FdCookingProcessingAdapter;
import firefly520.fireflymc.ai.processing.FdCuttingProcessingAdapter;
import firefly520.fireflymc.ai.processing.FdDoughProcessingAdapter;
import firefly520.fireflymc.ai.processing.FdServingProcessingAdapter;
import firefly520.fireflymc.ai.processing.CfbRecipeProcessingAdapter;
import firefly520.fireflymc.ai.processing.CfbToastMechanism;
import firefly520.fireflymc.ai.processing.ProcessingBatchProducts;
import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.processing.CommonModMaterialRules;
import firefly520.fireflymc.ai.processing.VerifiedTransformation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.*;
import java.util.*;

/** Optional tests are registered only for actually loaded target mods; absence never becomes a passing test. */
@GameTestHolder("fireflymc")
@PrefixGameTestTemplate(false)
public final class AiOptionalProcessingGameTests {
    @GameTestGenerator
    public static Collection<TestFunction> loadedAdapters() {
        var tests = new ArrayList<TestFunction>();
        if (ModList.get().isLoaded("chipped")) {
            tests.add(new TestFunction("ai-chipped", "fireflymc.chipped_variant_components", "fireflymc:story_empty", 80, 0, true,
                    AiOptionalProcessingGameTests::chippedVariant));
            tests.add(new TestFunction("ai-chipped", "fireflymc.chipped_rejects_foreign_target", "fireflymc:story_empty", 80, 0, true,
                    AiOptionalProcessingGameTests::chippedRejects));
        }
        if (ModList.get().isLoaded("farmersdelight")) {
            tests.add(new TestFunction("ai-fd", "fireflymc.fd_cooking_containers", "fireflymc:story_empty", 80, 0, true,
                    AiOptionalProcessingGameTests::fdCooking));
            tests.add(new TestFunction("ai-fd", "fireflymc.fd_missing_container", "fireflymc:story_empty", 80, 0, true,
                    AiOptionalProcessingGameTests::fdMissingContainer));
            tests.add(new TestFunction("ai-fd", "fireflymc.fd_probability_algorithm", "fireflymc:story_empty", 80, 0, true,
                    AiOptionalProcessingGameTests::fdProbability));
            tests.add(new TestFunction("ai-fd", "fireflymc.fd_multi_serving_fixture", "fireflymc:story_empty", 80, 0, true,
                    AiOptionalProcessingGameTests::fdMultiServingFixture));
            tests.add(new TestFunction("ai-fd", "fireflymc.fd_cutting_native_outputs", "fireflymc:story_empty", 80, 0, true,
                    AiOptionalProcessingGameTests::fdCuttingOutputs));
            tests.add(new TestFunction("ai-fd", "fireflymc.fd_cutting_replay_seed", "fireflymc:story_empty", 80, 0, true,
                    AiOptionalProcessingGameTests::fdCuttingSeed));
            tests.add(new TestFunction("ai-fd", "fireflymc.fd_dough_reuses_real_water", "fireflymc:story_empty", 80, 0, true,
                    AiOptionalProcessingGameTests::fdDough));
            tests.add(new TestFunction("ai-fd", "fireflymc.fd_serving_detached_pot", "fireflymc:story_empty", 80, 0, true,
                    AiOptionalProcessingGameTests::fdServing));
            tests.add(new TestFunction("ai-fd", "fireflymc.fd_serving_requires_real_food", "fireflymc:story_empty", 80, 0, true,
                    AiOptionalProcessingGameTests::fdServingMissing));
        }
        if (ModList.get().isLoaded("mynethersdelight")) {
            tests.add(new TestFunction("ai-mnd", "fireflymc.mnd_cooking_native_holder", "fireflymc:story_empty", 80, 0, true,
                    AiOptionalProcessingGameTests::mndCooking));
            tests.add(new TestFunction("ai-mnd", "fireflymc.mnd_cutting_blaze_outputs", "fireflymc:story_empty", 80, 0, true,
                    AiOptionalProcessingGameTests::mndCutting));
        }
        if (ModList.get().isLoaded("cookingforblockheads")) {
            tests.add(new TestFunction("ai-cfb", "fireflymc.cfb_explicit_recipe_materials", "fireflymc:story_empty", 80, 0, true,
                    AiOptionalProcessingGameTests::cfbRecipe));
            tests.add(new TestFunction("ai-cfb", "fireflymc.cfb_native_toast_components", "fireflymc:story_empty", 80, 0, true,
                    AiOptionalProcessingGameTests::cfbToast));
        }
        return tests;
    }
    private record Choice(RecipeHolder<?> recipe, ItemStack input, ItemStack target) {}
    private static Choice choice(GameTestHelper helper) {
        for (var recipe : helper.getLevel().getRecipeManager().getRecipes()) if (ChippedProcessingAdapter.supports(recipe.value())) {
            for (Item item : List.of(Items.STONE, Items.OAK_PLANKS, Items.DIRT)) {
                ItemStack input = new ItemStack(item, 5);
                var candidates = ChippedProcessingAdapter.candidates(recipe, input, helper.getLevel());
                for (var target : candidates) if (!target.is(item)) return new Choice(recipe, input, target);
            }
        }
        throw new IllegalStateException("ACTUAL_CHIPPED_RECIPE_NOT_FOUND");
    }
    private static void chippedVariant(GameTestHelper helper) {
        Choice selected = choice(helper);
        selected.input().set(DataComponents.CUSTOM_NAME, Component.literal("source component stays with source"));
        String target = BuiltInRegistries.ITEM.getKey(selected.target().getItem()).toString();
        var result = ChippedProcessingAdapter.verify(selected.recipe(), selected.input(), target, helper.getLevel());
        helper.assertValueEqual(result.consumedBySlot(), List.of(1), "Exactly one real source unit is required");
        helper.assertValueEqual(result.outputs().getFirst().getCount(), 1, "Only the chosen native candidate is produced");
        helper.assertTrue(ItemStack.isSameItemSameComponents(result.outputs().getFirst(), selected.target()), "Native target components are retained");
        helper.assertTrue(!result.outputs().getFirst().has(DataComponents.CUSTOM_NAME), "Source custom name is not copied into unrelated native target");
        helper.assertValueEqual(selected.input().getCount(), 5, "Preview did not mutate source inventory");
        helper.assertTrue(result.callbackPlan().requiredStages().isEmpty() && result.experience().perOperation() == 0,
                "Native Chipped conversion does not invent crafting callbacks or XP");
        helper.succeed();
    }
    private static void chippedRejects(GameTestHelper helper) {
        Choice selected = choice(helper);
        boolean rejected = false;
        try { ChippedProcessingAdapter.verify(selected.recipe(), selected.input(), "minecraft:diamond", helper.getLevel()); }
        catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "An unrelated requested item cannot be produced from the candidate set");
        helper.assertValueEqual(selected.input().getCount(), 5, "Rejected conversion leaves input unchanged");
        helper.succeed();
    }
    private record Cooking(RecipeHolder<?> holder, List<ItemStack> inputs, int outputCount) {}
    private static Cooking cooking(GameTestHelper helper) {
        for (var holder : helper.getLevel().getRecipeManager().getRecipes()) if (FdCookingProcessingAdapter.supports(holder.value())) {
            var recipe = holder.value();
            ItemStack container = FdCookingProcessingAdapter.container(holder, helper.getLevel());
            int count = recipe.getResultItem(helper.getLevel().registryAccess()).getCount();
            if (container.isEmpty() || count < 1 || recipe.getIngredients().size() > 6) continue;
            var input = new ArrayList<>(Collections.nCopies(7, ItemStack.EMPTY));
            boolean valid = true;
            for (int i = 0; i < recipe.getIngredients().size(); i++) {
                var values = recipe.getIngredients().get(i).getItems();
                if (values.length == 0) { valid = false; break; }
                input.set(i, values[0].copyWithCount(1));
            }
            if (!valid) continue;
            input.set(6, container.copyWithCount(count));
            return new Cooking(holder, input, count);
        }
        throw new IllegalStateException("ACTUAL_FD_COOKING_RECIPE_NOT_FOUND");
    }
    private static void fdCooking(GameTestHelper helper) {
        Cooking recipe = cooking(helper);
        var result = FdCookingProcessingAdapter.verify(recipe.holder(), recipe.inputs(), helper.getLevel());
        helper.assertValueEqual(result.consumedBySlot().get(6), recipe.outputCount(), "Every output unit consumes a real bowl/bottle");
        helper.assertValueEqual(result.outputs().getFirst().getCount(), recipe.outputCount(), "Output count is the actual native recipe count");
        for (int slot = 0; slot < 6; slot++) helper.assertValueEqual(result.consumedBySlot().get(slot),
                recipe.inputs().get(slot).isEmpty() ? 0 : 1, "Only one unit from each actual ingredient position is consumed");
        helper.assertValueEqual(recipe.inputs().get(6).getCount(), recipe.outputCount(), "Preview does not take real containers");
        helper.assertValueEqual(result.costPolicy(), "MATERIALS_ONLY", "No pot, fuel, waiting, level or auxiliary tool is required");
        helper.succeed();
    }
    private static void fdMissingContainer(GameTestHelper helper) {
        Cooking recipe = cooking(helper);
        recipe.inputs().get(6).shrink(1);
        boolean rejected = false;
        try { FdCookingProcessingAdapter.verify(recipe.holder(), recipe.inputs(), helper.getLevel()); }
        catch (IllegalArgumentException expected) { rejected = expected.getMessage().equals("PROCESSING_CONTAINERS_MISSING"); }
        helper.assertTrue(rejected, "Cooking may not invent missing output containers");
        helper.succeed();
    }
    private static void fdProbability(GameTestHelper helper) {
        try {
            Class<?> type = Class.forName("vectorwing.farmersdelight.common.crafting.ingredient.ChanceResult");
            ItemStack template = new ItemStack(Items.YELLOW_DYE, 2);
            Object nativeRule = type.getConstructor(ItemStack.class, float.class).newInstance(template, .5f);
            var roll = type.getMethod("rollOutput", net.minecraft.util.RandomSource.class, int.class);
            var distribution = List.of(new VerifiedTransformation.ProbabilityOutput(0, template, .5f, helper.getLevel().registryAccess()));
            Set<Integer> observed = new HashSet<>();
            for (long seed = 0; seed < 96; seed++) {
                long spread = seed * 0x9e3779b97f4a7c15L ^ 0x5eedfacadeL;
                ItemStack actual = (ItemStack) roll.invoke(nativeRule, net.minecraft.util.RandomSource.create(spread), 0);
                int calculated = CommonModMaterialRules.rollCutting(distribution, spread, helper.getLevel().registryAccess()).stream()
                        .mapToInt(ItemStack::getCount).sum();
                helper.assertValueEqual(calculated, actual.getCount(), "Per-unit probability exactly matches the loaded FD implementation");
                observed.add(actual.getCount());
            }
            helper.assertTrue(observed.containsAll(List.of(0, 1, 2)), "The native two-unit rule can produce zero, one or two units");
            helper.succeed();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("ACTUAL_FD_CHANCE_API_MISMATCH", failure); }
    }
    private static void fdMultiServingFixture(GameTestHelper helper) {
        Cooking source = cooking(helper);
        var manager = helper.getLevel().getRecipeManager();
        var original = List.copyOf(manager.getRecipes());
        var ops = helper.getLevel().registryAccess().createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
        // Exercise the real installed serializer with an explicitly synthetic four-serving test
        // recipe. The production RecipeManager is restored in the same server-thread invocation.
        var fixture = net.minecraft.world.item.crafting.Recipe.CODEC.encodeStart(ops, source.holder().value()).getOrThrow().getAsJsonObject();
        fixture.getAsJsonObject("result").addProperty("count", 4);
        var recipe = net.minecraft.world.item.crafting.Recipe.CODEC.parse(ops, fixture).getOrThrow();
        var holder = new RecipeHolder<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("fireflymc", "test_fd_four_servings"), recipe);
        var replacement = new ArrayList<RecipeHolder<?>>(original); replacement.add(holder);
        try {
            manager.replaceRecipes(replacement);
            var inputs = source.inputs();
            inputs.set(6, FdCookingProcessingAdapter.container(holder, helper.getLevel()).copyWithCount(4));
            var verified = FdCookingProcessingAdapter.verify(holder, inputs, helper.getLevel());
            helper.assertValueEqual(verified.outputs().getFirst().getCount(), 4, "Fixture output is decoded by the actual FD serializer");
            helper.assertValueEqual(verified.consumedBySlot().get(6), 4, "Four portions require four real containers");
            helper.assertValueEqual(inputs.get(6).getCount(), 4, "Calculation does not mutate supplied containers");
        } finally { manager.replaceRecipes(original); }
        helper.succeed();
    }
    private static RecipeHolder<?> cutting(GameTestHelper helper, Item item) {
        return helper.getLevel().getRecipeManager().getRecipes().stream().filter(h -> FdCuttingProcessingAdapter.supports(h.value()))
                .filter(h -> FdCuttingProcessingAdapter.matches(h, new ItemStack(item), helper.getLevel())).findFirst()
                .orElseThrow(() -> new IllegalStateException("ACTUAL_FD_CUTTING_RECIPE_NOT_FOUND"));
    }
    private static void fdCuttingOutputs(GameTestHelper helper) {
        var holder = cutting(helper, Items.COD);
        ItemStack source = new ItemStack(Items.COD, 3);
        var preview = FdCuttingProcessingAdapter.preview(holder, source, helper.getLevel());
        var actual = FdCuttingProcessingAdapter.sample(holder, source, helper.getLevel(), 42);
        helper.assertTrue(!preview.samplingEvidence().sampled() && actual.samplingEvidence().sampled(), "A preview is not an observed roll");
        helper.assertValueEqual(actual.outputs().stream().mapToInt(ItemStack::getCount).sum(), 3, "Native cod cutting returns slices and bone meal");
        helper.assertTrue(actual.outputs().stream().anyMatch(stack -> stack.is(Items.BONE_MEAL)), "The byproduct is not lost by using assemble's first result");
        helper.assertValueEqual(actual.consumedBySlot(), List.of(1), "One real fish is consumed");
        helper.assertValueEqual(actual.contextRequirements().get("fortune"), "0", "Virtual tool never invents Fortune");
        helper.assertValueEqual(source.getCount(), 3, "No input inventory or actual tool is changed by calculation");
        helper.succeed();
    }
    private static void fdCuttingSeed(GameTestHelper helper) {
        var holder = cutting(helper, Items.GRAVEL);
        var a = FdCuttingProcessingAdapter.sample(holder, new ItemStack(Items.GRAVEL), helper.getLevel(), 518927);
        var b = FdCuttingProcessingAdapter.sample(holder, new ItemStack(Items.GRAVEL), helper.getLevel(), 518927);
        var registries = helper.getLevel().registryAccess();
        helper.assertValueEqual(a.outputs().stream().map(s -> ItemSnapshotCodec.capture(s, registries)).toList(),
                b.outputs().stream().map(s -> ItemSnapshotCodec.capture(s, registries)).toList(), "Persisted seed reproduces the native evidence without a new roll");
        helper.assertTrue(a.outputs().stream().anyMatch(s -> s.is(Items.GRAVEL) && s.getCount() == 1), "The native returned gravel remains an actual output");
        helper.assertValueEqual(a.experience().perOperation(), 0f, "Cutting does not invent XP rewards");
        helper.succeed();
    }
    private static void fdDough(GameTestHelper helper) {
        var holder = helper.getLevel().getRecipeManager().getRecipes().stream().filter(h -> FdDoughProcessingAdapter.supports(h.value()))
                .findFirst().orElseThrow(() -> new IllegalStateException("ACTUAL_FD_DOUGH_RECIPE_NOT_FOUND"));
        var input = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
        input.set(0, new ItemStack(Items.WHEAT));
        var water = new ItemStack(Items.WATER_BUCKET); water.set(DataComponents.CUSTOM_NAME, Component.literal("real water container"));
        input.set(1, water);
        var verified = FdDoughProcessingAdapter.verify(holder, input, helper.getLevel());
        var products = ProcessingBatchProducts.aggregate(verified, 16, helper.getLevel().registryAccess());
        var stacks = products.stream().map(s -> ItemSnapshotCodec.restore(s, helper.getLevel().registryAccess())).toList();
        helper.assertValueEqual(stacks.stream().filter(s -> s.is(Items.WATER_BUCKET)).mapToInt(ItemStack::getCount).sum(), 1,
                "Sixteen operations retain one real reusable bucket, not sixteen invented buckets");
        helper.assertTrue(stacks.stream().anyMatch(s -> s.getCount() == 16 && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals("farmersdelight:wheat_dough")),
                "Each actual wheat unit produces one native dough output");
        helper.assertTrue(stacks.stream().filter(s -> s.is(Items.WATER_BUCKET)).allMatch(s -> ItemStack.isSameItemSameComponents(s, water)),
                "Returned water retains the actual input components");
        helper.assertValueEqual(water.getCount(), 1, "Calculation leaves the source untouched");
        helper.succeed();
    }
    @SuppressWarnings("unchecked")
    private static ItemStack filledPot(GameTestHelper helper) {
        try {
            var block = BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("farmersdelight", "cooking_pot"));
            Class<?> type = Class.forName("vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity");
            var pot = (net.minecraft.world.level.block.entity.BlockEntity) type
                    .getConstructor(net.minecraft.core.BlockPos.class, net.minecraft.world.level.block.state.BlockState.class)
                    .newInstance(helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1)), block.defaultBlockState());
            pot.setLevel(helper.getLevel());
            var inventory = (net.neoforged.neoforge.items.ItemStackHandler) type.getMethod("getInventory").invoke(pot);
            inventory.setStackInSlot(6, new ItemStack(Items.MUSHROOM_STEW, 8));
            ItemStack fixture = (ItemStack) type.getMethod("getAsItem").invoke(pot);
            // The real cooking operation also records the recipe's container override. A vanilla
            // stew's eating bowl is not its crafting remainder, so supply that explicit fixture component.
            Object component = ((java.util.function.Supplier<?>) Class.forName("vectorwing.farmersdelight.common.registry.ModDataComponents")
                    .getField("CONTAINER").get(null)).get();
            Object wrapped = Class.forName("vectorwing.farmersdelight.common.item.component.ItemStackWrapper")
                    .getConstructor(ItemStack.class).newInstance(new ItemStack(Items.BOWL));
            fixture.set((net.minecraft.core.component.DataComponentType<Object>) component, wrapped);
            return fixture;
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("ACTUAL_FD_POT_FIXTURE_FAILED", failure); }
    }
    private static RecipeHolder<?> serving(GameTestHelper helper) {
        return helper.getLevel().getRecipeManager().getRecipes().stream().filter(h -> FdServingProcessingAdapter.supports(h.value()))
                .findFirst().orElseThrow(() -> new IllegalStateException("ACTUAL_FD_SERVING_RECIPE_NOT_FOUND"));
    }
    private static void fdServing(GameTestHelper helper) {
        var pot = filledPot(helper);
        var grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY)); grid.set(0, pot); grid.set(1, new ItemStack(Items.BOWL, 3));
        var result = FdServingProcessingAdapter.verify(serving(helper), grid, 3, helper.getLevel());
        var products = ProcessingBatchProducts.aggregate(result, 3, helper.getLevel().registryAccess());
        int portions = products.stream().map(s -> ItemSnapshotCodec.restore(s, helper.getLevel().registryAccess()))
                .filter(s -> s.is(Items.MUSHROOM_STEW)).mapToInt(ItemStack::getCount).sum();
        helper.assertValueEqual(portions, 3, "An aggregated serving batch is not multiplied twice");
        helper.assertValueEqual(FdServingProcessingAdapter.contents(result.remaindersBySlot().get(0), helper.getLevel()).meal().count(), 5,
                "Exactly three portions leave the returned real pot");
        helper.assertValueEqual(FdServingProcessingAdapter.contents(pot, helper.getLevel()).meal().count(), 8,
                "Preview never mutates the original nested food component");
        helper.assertValueEqual(result.consumedBySlot().get(0), 1, "One filled pot is a real input");
        helper.assertValueEqual(result.consumedBySlot().get(1), 3, "Three real bowls are required");
        helper.assertValueEqual(result.steps().size(), 3, "Callback evidence preserves each individual serving");
        helper.succeed();
    }
    private static void fdServingMissing(GameTestHelper helper) {
        var pot = filledPot(helper);
        var grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY)); grid.set(0, pot); grid.set(1, new ItemStack(Items.BOWL, 9));
        boolean refused = false;
        try { FdServingProcessingAdapter.verify(serving(helper), grid, 9, helper.getLevel()); }
        catch (IllegalArgumentException expected) { refused = expected.getMessage().equals("FD_SERVING_FOOD_MISSING"); }
        helper.assertTrue(refused, "A waived cooking device cannot be used to invent stored food");
        helper.assertValueEqual(FdServingProcessingAdapter.contents(pot, helper.getLevel()).meal().count(), 8, "Refusal preserves original food");
        helper.succeed();
    }
    private static void mndCooking(GameTestHelper helper) {
        for (var holder : helper.getLevel().getRecipeManager().getRecipes()) {
            if (!holder.id().getNamespace().equals("mynethersdelight") || !FdCookingProcessingAdapter.supports(holder.value())) continue;
            var recipe = holder.value(); var inputs = new ArrayList<>(Collections.nCopies(7, ItemStack.EMPTY));
            boolean valid = recipe.getIngredients().size() <= 6;
            for (int slot = 0; valid && slot < recipe.getIngredients().size(); slot++) {
                var candidates = recipe.getIngredients().get(slot).getItems();
                if (candidates.length == 0) { valid = false; break; }
                inputs.set(slot, candidates[0].copyWithCount(1));
            }
            if (!valid) continue;
            var container = FdCookingProcessingAdapter.container(holder, helper.getLevel());
            if (!container.isEmpty()) inputs.set(6, container.copyWithCount(recipe.getResultItem(helper.getLevel().registryAccess()).getCount()));
            var actual = FdCookingProcessingAdapter.verify(holder, inputs, helper.getLevel());
            helper.assertTrue(!actual.outputs().isEmpty(), "An actually loaded MND recipe produces its declared native output");
            helper.assertValueEqual(actual.recipeId(), holder.id(), "Addon namespace is retained in the authoritative reference");
            helper.succeed(); return;
        }
        throw new IllegalStateException("ACTUAL_MND_COOKING_RECIPE_NOT_FOUND");
    }
    private static void mndCutting(GameTestHelper helper) {
        var holder = helper.getLevel().getRecipeManager().getRecipes().stream()
                .filter(h -> h.id().getNamespace().equals("mynethersdelight") && FdCuttingProcessingAdapter.supports(h.value()))
                .filter(h -> FdCuttingProcessingAdapter.matches(h, new ItemStack(Items.BLAZE_ROD), helper.getLevel())).findFirst()
                .orElseThrow(() -> new IllegalStateException("ACTUAL_MND_BLAZE_RECIPE_NOT_FOUND"));
        for (int attempt = 0; attempt < 16; attempt++) {
            var result = FdCuttingProcessingAdapter.sample(holder, new ItemStack(Items.BLAZE_ROD), helper.getLevel(), attempt * 0x9e3779b97f4a7c15L);
            int powder = result.outputs().stream().filter(s -> s.is(Items.BLAZE_POWDER)).mapToInt(ItemStack::getCount).sum();
            helper.assertTrue(powder >= 3 && powder <= 4, "Native MND blaze cutting retains its guaranteed and probabilistic outputs");
            helper.assertValueEqual(result.consumedBySlot(), List.of(1), "Each operation consumes one real blaze rod");
        }
        helper.succeed();
    }
    private static void cfbRecipe(GameTestHelper helper) {
        var manager = helper.getLevel().getRecipeManager(); var original = List.copyOf(manager.getRecipes());
        try {
            Class<?> type = Class.forName("net.blay09.mods.cookingforblockheads.recipe.OvenRecipe");
            var recipe = (net.minecraft.world.item.crafting.Recipe<?>) type.getConstructor(net.minecraft.world.item.crafting.Ingredient.class, ItemStack.class)
                    .newInstance(net.minecraft.world.item.crafting.Ingredient.of(Items.WATER_BUCKET), new ItemStack(Items.BREAD));
            var holder = new RecipeHolder<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("fireflymc", "test_cfb_oven"), recipe);
            var values = new ArrayList<RecipeHolder<?>>(original); values.add(holder); manager.replaceRecipes(values);
            helper.assertTrue(recipe.getIngredients().isEmpty(), "CFB has no generic ingredient-list override");
            var result = CfbRecipeProcessingAdapter.verify(holder, new ItemStack(Items.WATER_BUCKET), helper.getLevel());
            helper.assertValueEqual(result.consumedBySlot(), List.of(1), "The actual matches predicate still requires a real ingredient");
            helper.assertTrue(result.remaindersBySlot().stream().allMatch(ItemStack::isEmpty), "Native oven input consumption does not invent a generic bucket remainder");
            boolean rejected = false;
            try { CfbRecipeProcessingAdapter.verify(holder, ItemStack.EMPTY, helper.getLevel()); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "An empty generic ingredient list never allows free output");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("ACTUAL_CFB_RECIPE_API_MISMATCH", failure); }
        finally { manager.replaceRecipes(original); }
        helper.succeed();
    }
    private static void cfbToast(GameTestHelper helper) {
        Object active = null; java.lang.reflect.Field allow = null; boolean previous = false;
        try {
            Class<?> config = Class.forName("net.blay09.mods.cookingforblockheads.CookingForBlockheadsConfig");
            active = config.getMethod("getActive").invoke(null);
            allow = active.getClass().getField("allowVeryToastedBread"); previous = allow.getBoolean(active);
            var block = BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cookingforblockheads", "toaster"));
            Class<?> toaster = Class.forName("net.blay09.mods.cookingforblockheads.block.entity.ToasterBlockEntity");
            Object nativeObject = toaster.getConstructor(net.minecraft.core.BlockPos.class, net.minecraft.world.level.block.state.BlockState.class)
                    .newInstance(helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1)), block.defaultBlockState());
            var nativeToast = toaster.getDeclaredMethod("toastBread", ItemStack.class);
            if (!nativeToast.trySetAccessible()) throw new IllegalStateException("NATIVE_TOAST_METHOD_UNAVAILABLE");
            ItemStack input = new ItemStack(Items.BREAD); input.set(DataComponents.CUSTOM_NAME, Component.literal("named bread"));
            var registry = helper.getLevel().registryAccess();
            var first = CfbToastMechanism.verify(input, helper.getLevel());
            ItemStack expected = (ItemStack) nativeToast.invoke(nativeObject, input.copy());
            helper.assertValueEqual(ItemSnapshotCodec.capture(first.outputs().getFirst(), registry), ItemSnapshotCodec.capture(expected, registry),
                    "First toast exactly matches the installed native component transformation");
            helper.assertTrue(!first.isRecipe() && first.backingRecipes().isEmpty(), "The fallback does not invent a recipe ID");
            allow.setBoolean(active, true);
            var second = CfbToastMechanism.verify(first.outputs().getFirst(), helper.getLevel());
            ItemStack expectedSecond = (ItemStack) nativeToast.invoke(nativeObject, first.outputs().getFirst());
            helper.assertValueEqual(ItemSnapshotCodec.capture(second.outputs().getFirst(), registry), ItemSnapshotCodec.capture(expectedSecond, registry),
                    "Allowed re-toasting matches the native charcoal/name branch");
            allow.setBoolean(active, false);
            boolean refused = false;
            try { CfbToastMechanism.verify(first.outputs().getFirst(), helper.getLevel()); }
            catch (IllegalStateException expectedFailure) { refused = expectedFailure.getMessage().equals("ALREADY_TOASTED"); }
            helper.assertTrue(refused, "A disabled config branch is not bypassed by the fee waiver");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("ACTUAL_CFB_TOAST_API_MISMATCH", failure); }
        finally {
            if (active != null && allow != null) try { allow.setBoolean(active, previous); }
            catch (IllegalAccessException failure) { throw new IllegalStateException("TEST_CONFIG_RESTORE_FAILED", failure); }
        }
        helper.succeed();
    }
}
