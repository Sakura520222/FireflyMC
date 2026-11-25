package firefly520.fireflymc.ai.processing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * Computes one operation against an exact, currently loaded vanilla recipe implementation.
 *
 * <p>This is a material transformation only: no inventory access, fuel, equipment, waiting,
 * experience payment, reward delivery, menu, player callback or world mutation is performed.
 * The caller must authorize sources, reserve materials and commit/recover the returned plan.
 * Recipe/tag generation and player/world progress gates remain the caller's responsibility.</p>
 */
public final class VanillaProcessingKernel {
    private VanillaProcessingKernel() {}

    public enum Kind { CRAFTING, STONECUTTING, SMITHING, THERMAL }

    public enum CallbackCategory {
        CRAFTING_RESULT_SLOT, STONECUTTER_RESULT_SLOT, SMITHING_RESULT_SLOT, FURNACE_RESULT_SLOT
    }

    public enum RemainderPolicy {
        SELECTED_CRAFTING_RECIPE,
        NONE_IN_NATIVE_CONSUMPTION
    }

    /**
     * No callback has run. The commit layer must bind the actor, preserve this recipe selection,
     * and journal callback phases rather than replaying them blindly after a crash.
     */
    public record CallbackPlan(
            CallbackCategory category,
            int craftedItemCount,
            boolean onCraftedBy,
            boolean awardRecipeAndAdvancement,
            boolean neoForgeCraftingEvent,
            boolean neoForgeSmeltedEvent,
            List<String> evidence) {
        public CallbackPlan {
            evidence = List.copyOf(evidence);
        }
    }

    /** Furnace rewards accumulate per completed recipe operation, then round stochastically once. */
    public record ExperienceReward(float perOperation, int operationCount) {
        public ExperienceReward {
            if (!Float.isFinite(perOperation) || perOperation < 0 || operationCount != 1) {
                throw failure("PROCESSING_RESULT_INVALID");
            }
        }
    }

    /**
     * Row-major compact input slots map back to the original nine slots. Mirrored arrangements
     * are kept as supplied; no second mirror or ingredient reordering is applied.
     */
    public record CraftingMapping(
            int width, int height, int left, int top, List<Integer> compactToGrid) {
        public CraftingMapping {
            compactToGrid = List.copyOf(compactToGrid);
        }
    }

    /**
     * An immutable calculation snapshot. Stack getters return newly isolated values, so modifying
     * a returned stack or one of its mutable components cannot change this snapshot.
     */
    public static final class VerifiedTransformation
            extends firefly520.fireflymc.ai.processing.VerifiedTransformation {
        private final Kind kind;
        private final Optional<CraftingMapping> craftingMapping;
        private final RemainderPolicy remainderPolicy;
        private final VanillaProcessingKernel.ExperienceReward experience;
        private final VanillaProcessingKernel.CallbackPlan callbacks;

        private VerifiedTransformation(
                Binding binding, List<ItemStack> inputs, ItemStack output,
                List<ItemStack> remainders, Optional<CraftingMapping> craftingMapping,
                float experience, ServerLevel level) {
            super(binding.holder.id(), binding.typeId, binding.serializerId,
                    "vanilla:" + binding.profile.name().toLowerCase(java.util.Locale.ROOT),
                    inputs, inputs.stream().map(stack -> stack.isEmpty() ? 0 : 1).toList(),
                    List.of(output), remainders,
                    new firefly520.fireflymc.ai.processing.VerifiedTransformation.ExperienceReward(experience, 1),
                    commonCallbacks(binding.profile.kind, output.getCount()),
                    SamplingEvidence.deterministic(), Map.of("stationRequired", "false", "waitTicks", "0"),
                    level.registryAccess());
            kind = binding.profile.kind;
            this.craftingMapping = craftingMapping;
            remainderPolicy = kind == Kind.CRAFTING
                    ? RemainderPolicy.SELECTED_CRAFTING_RECIPE : RemainderPolicy.NONE_IN_NATIVE_CONSUMPTION;
            this.experience = new VanillaProcessingKernel.ExperienceReward(experience, 1);
            callbacks = VanillaProcessingKernel.callbackPlan(kind, output.getCount());
        }

        public Kind kind() { return kind; }
        public Optional<CraftingMapping> craftingMapping() { return craftingMapping; }
        public RemainderPolicy remainderPolicy() { return remainderPolicy; }
        public VanillaProcessingKernel.ExperienceReward experienceReward() { return experience; }
        public VanillaProcessingKernel.CallbackPlan callbacks() { return callbacks; }
    }

    /** Class equality is intentional: mod subclasses and dynamic recipes need dedicated adapters. */
    public static boolean supports(Recipe<?> recipe) {
        Profile profile = profile(recipe);
        return profile != null && recipe.getType() == profile.type;
    }

    /** The nine input positions always describe a three-by-three grid, including empty slots. */
    public static VerifiedTransformation verifyCrafting(
            RecipeHolder<?> holder, List<ItemStack> grid, ServerLevel level) {
        return safely(() -> {
            Binding binding = bind(holder, level, Kind.CRAFTING);
            if (grid == null || grid.size() != 9) throw failure("PROCESSING_INPUT_SIZE");
            List<ItemStack> before = isolateInputs(grid, level);
            List<ItemStack> working = RecipeInputIsolation.copyAll(before, level.registryAccess());
            CraftingInput.Positioned positioned = CraftingInput.ofPositioned(3, 3, working);
            CraftingInput input = positioned.input();
            CraftingRecipe recipe = (CraftingRecipe) holder.value();
            if (!recipe.canCraftInDimensions(3, 3) || !recipe.matches(input, level)) {
                throw failure("PROCESSING_INPUT_MISMATCH");
            }
            ItemStack output = validateOutput(recipe.assemble(input, level.registryAccess()), level);
            // Never ask RecipeManager to find a second recipe for the remaining items.
            List<ItemStack> compactRemainders = recipe.getRemainingItems(input);
            if (compactRemainders.size() != input.size()) throw failure("PROCESSING_RESULT_INVALID");
            List<ItemStack> remainders = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
            List<Integer> mapping = new ArrayList<>(input.size());
            for (int y = 0; y < input.height(); y++) {
                for (int x = 0; x < input.width(); x++) {
                    int compactSlot = x + y * input.width();
                    int gridSlot = x + positioned.left() + (y + positioned.top()) * 3;
                    mapping.add(gridSlot);
                    ItemStack remainder = compactRemainders.get(compactSlot);
                    if (remainder == null || before.get(gridSlot).isEmpty() && !remainder.isEmpty()) {
                        throw failure("PROCESSING_RESULT_INVALID");
                    }
                    remainders.set(gridSlot, validateRemainder(remainder, level));
                }
            }
            verifyUnchanged(before, working);
            return new VerifiedTransformation(binding, before, output, remainders,
                    Optional.of(new CraftingMapping(input.width(), input.height(),
                            positioned.left(), positioned.top(), mapping)), 0, level);
        });
    }

    /** Stonecutting, smelting, smoking and blasting consume one actual input per operation. */
    public static VerifiedTransformation verifySingle(
            RecipeHolder<?> holder, SingleRecipeInput supplied, ServerLevel level) {
        return safely(() -> {
            Binding binding = bind(holder, level, Kind.STONECUTTING, Kind.THERMAL);
            if (supplied == null) throw failure("PROCESSING_INPUT_INVALID");
            List<ItemStack> before = isolateInputs(List.of(supplied.item()), level);
            List<ItemStack> working = RecipeInputIsolation.copyAll(before, level.registryAccess());
            SingleRecipeInput input = new SingleRecipeInput(working.getFirst());
            @SuppressWarnings("unchecked")
            Recipe<SingleRecipeInput> recipe = (Recipe<SingleRecipeInput>) holder.value();
            if (!recipe.matches(input, level)) throw failure("PROCESSING_INPUT_MISMATCH");
            ItemStack output = validateOutput(recipe.assemble(input, level.registryAccess()), level);
            verifyUnchanged(before, working);
            float experience = binding.profile.kind == Kind.THERMAL
                    ? ((AbstractCookingRecipe) recipe).getExperience() : 0;
            // Native stonecutter/furnace consumption does not use Recipe.getRemainingItems.
            // No fuel slot exists here, including the wet-sponge + real-bucket interaction.
            return new VerifiedTransformation(binding, before, output, List.of(ItemStack.EMPTY),
                    Optional.empty(), experience, level);
        });
    }

    /** Template, base and addition retain their original slot roles and exact component state. */
    public static VerifiedTransformation verifySmithing(
            RecipeHolder<?> holder, SmithingRecipeInput supplied, ServerLevel level) {
        return safely(() -> {
            Binding binding = bind(holder, level, Kind.SMITHING);
            if (supplied == null) throw failure("PROCESSING_INPUT_INVALID");
            List<ItemStack> before = isolateInputs(
                    List.of(supplied.template(), supplied.base(), supplied.addition()), level);
            List<ItemStack> working = RecipeInputIsolation.copyAll(before, level.registryAccess());
            SmithingRecipeInput input = new SmithingRecipeInput(working.get(0), working.get(1), working.get(2));
            SmithingRecipe recipe = (SmithingRecipe) holder.value();
            if (!recipe.matches(input, level)) throw failure("PROCESSING_INPUT_MISMATCH");
            ItemStack output = validateOutput(recipe.assemble(input, level.registryAccess()), level);
            verifyUnchanged(before, working);
            // SmithingMenu shrinks each occupied input; it does not return crafting containers.
            return new VerifiedTransformation(binding, before, output,
                    Collections.nCopies(3, ItemStack.EMPTY), Optional.empty(), 0, level);
        });
    }

    private static Binding bind(RecipeHolder<?> holder, ServerLevel level, Kind... accepted) {
        if (holder == null || level == null) throw failure("PROCESSING_INPUT_INVALID");
        if (!level.getServer().isSameThread()) throw failure("PROCESSING_NOT_SERVER_THREAD");
        Recipe<?> recipe = holder.value();
        Profile profile = profile(recipe);
        if (profile == null || recipe.getType() != profile.type) {
            throw failure("UNSUPPORTED_PROCESSING_ADAPTER");
        }
        boolean acceptedKind = false;
        for (Kind kind : accepted) acceptedKind |= profile.kind == kind;
        if (!acceptedKind) throw failure("UNSUPPORTED_PROCESSING_ADAPTER");
        RecipeHolder<?> current = level.getRecipeManager().byKey(holder.id())
                .orElseThrow(() -> failure("PROCESSING_RECIPE_STALE"));
        if (current.value() != recipe) throw failure("PROCESSING_RECIPE_STALE");
        if (recipe.isIncomplete()) throw failure("PROCESSING_RECIPE_INVALID");
        ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        ResourceLocation serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        if (typeId == null || serializerId == null) throw failure("PROCESSING_RECIPE_INVALID");
        return new Binding(holder, profile, typeId, serializerId);
    }

    private static List<ItemStack> isolateInputs(List<ItemStack> inputs, ServerLevel level) {
        List<ItemStack> isolated = RecipeInputIsolation.copyAll(inputs, level.registryAccess());
        for (ItemStack stack : isolated) {
            if (!stack.isEmpty() && !stack.isItemEnabled(level.enabledFeatures())) {
                throw failure("PROCESSING_INPUT_DISABLED");
            }
        }
        return isolated;
    }

    private static ItemStack validateOutput(ItemStack output, ServerLevel level) {
        if (output == null || output.isEmpty()) throw failure("PROCESSING_RESULT_EMPTY");
        return validateRemainder(output, level);
    }

    private static ItemStack validateRemainder(ItemStack stack, ServerLevel level) {
        if (stack == null) throw failure("PROCESSING_RESULT_INVALID");
        if (stack.isEmpty()) return ItemStack.EMPTY;
        if (stack.getCount() < 1 || stack.getCount() > stack.getMaxStackSize()) {
            throw failure("PROCESSING_RESULT_INVALID");
        }
        if (!stack.isItemEnabled(level.enabledFeatures())) throw failure("PROCESSING_RESULT_DISABLED");
        return RecipeInputIsolation.copy(stack, level.registryAccess());
    }

    private static void verifyUnchanged(List<ItemStack> before, List<ItemStack> after) {
        for (int slot = 0; slot < before.size(); slot++) {
            ItemStack source = before.get(slot);
            ItemStack result = after.get(slot);
            if (source.getCount() != result.getCount()
                    || !ItemStack.isSameItemSameComponents(source, result)) {
                throw failure("UNSUPPORTED_PROCESSING_ADAPTER");
            }
        }
    }

    private static Profile profile(Recipe<?> recipe) {
        if (recipe == null) return null;
        Class<?> type = recipe.getClass();
        if (type == ShapedRecipe.class || type == ShapelessRecipe.class) return Profile.CRAFTING;
        if (type == StonecutterRecipe.class) return Profile.STONECUTTING;
        if (type == SmithingTransformRecipe.class || type == SmithingTrimRecipe.class) return Profile.SMITHING;
        if (type == SmeltingRecipe.class) return Profile.SMELTING;
        if (type == SmokingRecipe.class) return Profile.SMOKING;
        if (type == BlastingRecipe.class) return Profile.BLASTING;
        return null;
    }

    private static CallbackPlan callbackPlan(Kind kind, int count) {
        return switch (kind) {
            case CRAFTING -> new CallbackPlan(CallbackCategory.CRAFTING_RESULT_SLOT, count, true, true,
                    true, false, List.of("Recipe#getRemainingItems", "ResultSlot#onTake",
                            "ResultSlot#checkTakeAchievements", "CommonHooks#setCraftingPlayer"));
            case STONECUTTING -> new CallbackPlan(CallbackCategory.STONECUTTER_RESULT_SLOT, count, true, true,
                    false, false, List.of("StonecutterMenu.resultSlot#onTake"));
            case SMITHING -> new CallbackPlan(CallbackCategory.SMITHING_RESULT_SLOT, count, true, true,
                    false, false, List.of("SmithingMenu#onTake", "SmithingMenu#shrinkStackInSlot"));
            case THERMAL -> new CallbackPlan(CallbackCategory.FURNACE_RESULT_SLOT, count, true, true,
                    false, true, List.of("AbstractFurnaceBlockEntity#burn", "FurnaceResultSlot#checkTakeAchievements",
                            "AbstractFurnaceBlockEntity#awardUsedRecipesAndPopExperience",
                            "AbstractFurnaceBlockEntity#createExperience"));
        };
    }

    private static firefly520.fireflymc.ai.processing.VerifiedTransformation.CallbackPlan commonCallbacks(
            Kind kind, int count) {
        CallbackPlan nativePlan = callbackPlan(kind, count);
        List<String> stages = new ArrayList<>();
        if (nativePlan.onCraftedBy()) stages.add("item_on_crafted_by");
        if (nativePlan.awardRecipeAndAdvancement()) stages.add("award_selected_recipe_and_advancement");
        if (nativePlan.neoForgeCraftingEvent()) stages.add("neoforge_crafting_event");
        if (nativePlan.neoForgeSmeltedEvent()) stages.add("neoforge_smelted_event");
        if (kind == Kind.THERMAL) stages.add("accumulated_recipe_experience");
        return new firefly520.fireflymc.ai.processing.VerifiedTransformation.CallbackPlan(
                nativePlan.category().name(), stages, nativePlan.evidence(), false);
    }

    private static <T> T safely(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (ProcessingException failure) {
            throw failure;
        } catch (RecipeInputIsolation.IsolationException failure) {
            throw failure(failure.code());
        } catch (RuntimeException | StackOverflowError unsafeRecipe) {
            throw failure("PROCESSING_RECIPE_EXCEPTION");
        }
    }

    private static ProcessingException failure(String code) {
        return new ProcessingException(code);
    }

    /** A stable safe code only; raw ingredient/component/codec diagnostics are not retained. */
    public static final class ProcessingException extends IllegalArgumentException {
        private ProcessingException(String code) { super(code); }
        public String code() { return getMessage(); }
    }

    private enum Profile {
        CRAFTING(Kind.CRAFTING, RecipeType.CRAFTING),
        STONECUTTING(Kind.STONECUTTING, RecipeType.STONECUTTING),
        SMITHING(Kind.SMITHING, RecipeType.SMITHING),
        SMELTING(Kind.THERMAL, RecipeType.SMELTING),
        SMOKING(Kind.THERMAL, RecipeType.SMOKING),
        BLASTING(Kind.THERMAL, RecipeType.BLASTING);

        final Kind kind;
        final RecipeType<?> type;
        Profile(Kind kind, RecipeType<?> type) {
            this.kind = kind;
            this.type = type;
        }
    }

    private record Binding(
            RecipeHolder<?> holder, Profile profile, ResourceLocation typeId, ResourceLocation serializerId) {}
}
