package firefly520.fireflymc.ai.processing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Bounded material calculations shared by the optional-mod adapters.
 * These methods do not establish recipe validity, mod availability or execution authorization.
 */
public final class CommonModMaterialRules {
    public static final int MAX_CANDIDATES = 4096;
    public static final int MAX_RANDOM_DRAWS = 4096;

    private CommonModMaterialRules() {}

    public static final class MaterialDelta {
        private final List<ItemStack> inputs;
        private final List<Integer> consumed;
        private final List<ItemStack> outputs;
        private final List<ItemStack> remainders;
        private final HolderLookup.Provider registries;

        private MaterialDelta(List<ItemStack> inputs, List<Integer> consumed, List<ItemStack> outputs,
                List<ItemStack> remainders, HolderLookup.Provider registries) {
            this.inputs = RecipeInputIsolation.copyAll(inputs, registries);
            this.consumed = List.copyOf(consumed);
            this.outputs = RecipeInputIsolation.copyAll(outputs, registries);
            this.remainders = RecipeInputIsolation.copyAll(remainders, registries);
            this.registries = registries;
        }

        public List<ItemStack> inputs() { return RecipeInputIsolation.copyAll(inputs, registries); }
        public List<Integer> consumed() { return consumed; }
        public List<ItemStack> outputs() { return RecipeInputIsolation.copyAll(outputs, registries); }
        public List<ItemStack> remainders() { return RecipeInputIsolation.copyAll(remainders, registries); }
    }

    public static MaterialDelta chipped(ItemStack source, int count, List<ItemStack> candidates,
            ItemStack requested, HolderLookup.Provider registries) {
        ItemStack input = RecipeInputIsolation.copy(source, registries);
        ItemStack target = RecipeInputIsolation.copy(requested, registries);
        if (input.isEmpty() || count < 1 || count > input.getCount()) throw failure("PROCESSING_MATERIALS_MISSING");
        if (candidates == null || candidates.size() > MAX_CANDIDATES) throw failure("PROCESSING_RECIPE_LIMIT");
        ItemStack selected = candidates.stream().filter(candidate -> candidate != null && !candidate.isEmpty())
                .filter(candidate -> ItemStack.isSameItemSameComponents(candidate, target)).findFirst()
                .orElseThrow(() -> failure("PROCESSING_TARGET_NOT_IN_RECIPE"));
        List<ItemStack> outputs = splitOutput(selected, count, registries);
        return new MaterialDelta(List.of(input), List.of(count), outputs, List.of(ItemStack.EMPTY), registries);
    }

    /** Ingredient slots occupy 0..5; explicitly selected output-container sources follow them. */
    public static MaterialDelta cooking(List<ItemStack> ingredients, List<ItemStack> containers,
            ItemStack output, ItemStack outputContainer, Map<Item, Item> remainderOverrides,
            HolderLookup.Provider registries) {
        if (ingredients == null || ingredients.size() != 6 || containers == null) {
            throw failure("PROCESSING_INPUT_SIZE");
        }
        List<ItemStack> combined = new ArrayList<>(ingredients);
        combined.addAll(containers);
        List<ItemStack> inputs = RecipeInputIsolation.copyAll(combined, registries);
        if (output == null || output.isEmpty() || outputContainer == null || remainderOverrides == null) {
            throw failure("PROCESSING_RESULT_INVALID");
        }
        List<Integer> consumed = new ArrayList<>(Collections.nCopies(inputs.size(), 0));
        List<ItemStack> remainders = new ArrayList<>(Collections.nCopies(inputs.size(), ItemStack.EMPTY));
        for (int slot = 0; slot < 6; slot++) {
            ItemStack ingredient = inputs.get(slot);
            if (ingredient.isEmpty()) continue;
            consumed.set(slot, 1);
            if (ingredient.hasCraftingRemainingItem()) {
                remainders.set(slot, RecipeInputIsolation.copy(ingredient.getCraftingRemainingItem(), registries));
            } else {
                Item replacement = remainderOverrides.get(ingredient.getItem());
                if (replacement != null) {
                    remainders.set(slot, RecipeInputIsolation.copy(replacement.getDefaultInstance(), registries));
                }
            }
        }
        int needed = outputContainer.isEmpty() ? 0 : output.getCount();
        for (int slot = 6; slot < inputs.size(); slot++) {
            ItemStack container = inputs.get(slot);
            if (container.isEmpty()) continue;
            if (outputContainer.isEmpty() || !ItemStack.isSameItem(container, outputContainer)) {
                throw failure("PROCESSING_CONTAINER_MISMATCH");
            }
            int take = Math.min(needed, container.getCount());
            consumed.set(slot, take);
            needed -= take;
        }
        if (needed != 0) throw failure("PROCESSING_CONTAINERS_MISSING");
        return new MaterialDelta(inputs, consumed, splitOutput(output, output.getCount(), registries),
                remainders, registries);
    }

    /** FD's exact base algorithm for fortune=0: each result unit succeeds when nextFloat <= chance. */
    public static List<ItemStack> rollCutting(
            List<VerifiedTransformation.ProbabilityOutput> distribution, long seed,
            HolderLookup.Provider registries) {
        if (distribution == null || distribution.isEmpty() || distribution.size() > 64) {
            throw failure("PROCESSING_DISTRIBUTION_INVALID");
        }
        int draws = 0;
        for (var output : distribution) {
            draws = Math.addExact(draws, output.trials());
            if (draws > MAX_RANDOM_DRAWS) throw failure("PROCESSING_RECIPE_LIMIT");
        }
        RandomSource random = RandomSource.create(seed);
        List<ItemStack> results = new ArrayList<>();
        for (var entry : distribution) {
            int count = entry.trials();
            for (int unit = 0; unit < entry.trials(); unit++) {
                if (random.nextFloat() > entry.chance()) count--;
            }
            if (count > 0) results.addAll(splitOutput(entry.template(), count, registries));
        }
        return List.copyOf(results);
    }

    public static MaterialDelta cutting(ItemStack source, List<ItemStack> observedOutputs,
            HolderLookup.Provider registries) {
        ItemStack input = RecipeInputIsolation.copy(source, registries);
        if (input.isEmpty()) throw failure("PROCESSING_MATERIALS_MISSING");
        List<ItemStack> outputs = new ArrayList<>();
        for (ItemStack stack : observedOutputs) {
            if (stack == null) throw failure("PROCESSING_RESULT_INVALID");
            if (!stack.isEmpty()) outputs.addAll(splitOutput(stack, stack.getCount(), registries));
        }
        // CuttingBoardBlockEntity extracts one input and does not query crafting remainders.
        return new MaterialDelta(List.of(input), List.of(1), outputs, List.of(ItemStack.EMPTY), registries);
    }

    public static MaterialDelta single(ItemStack source, ItemStack output, HolderLookup.Provider registries) {
        if (output == null || output.isEmpty()) throw failure("PROCESSING_RESULT_EMPTY");
        return cutting(source, List.of(output), registries);
    }

    public static List<ItemStack> splitOutput(ItemStack template, int count, HolderLookup.Provider registries) {
        if (template == null || template.isEmpty() || count < 1 || count > MAX_RANDOM_DRAWS
                || template.getMaxStackSize() < 1) {
            throw failure("PROCESSING_RESULT_INVALID");
        }
        List<ItemStack> result = new ArrayList<>();
        int remaining = count;
        while (remaining > 0) {
            if (result.size() >= RecipeInputIsolation.MAX_INPUTS) throw failure("PROCESSING_RECIPE_LIMIT");
            int size = Math.min(remaining, template.getMaxStackSize());
            result.add(RecipeInputIsolation.copy(template.copyWithCount(size), registries));
            remaining -= size;
        }
        return List.copyOf(result);
    }

    private static RulesException failure(String code) { return new RulesException(code); }

    public static final class RulesException extends IllegalArgumentException {
        private RulesException(String code) { super(code); }
        public String code() { return getMessage(); }
    }
}
