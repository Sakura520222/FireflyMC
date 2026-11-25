package firefly520.fireflymc.ai.processing;

import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Read-only material calculation produced by trusted server adapters, before inventory commitment.
 * A successful calculation is neither source authorization nor evidence of optional-mod runtime QA.
 */
public class VerifiedTransformation {
    public record RecipeReference(ResourceLocation id, ResourceLocation type) {}

    public record ExperienceReward(float perOperation, int operationCount) {
        public ExperienceReward {
            if (!Float.isFinite(perOperation) || perOperation < 0 || operationCount < 1) {
                throw new IllegalArgumentException("PROCESSING_RESULT_INVALID");
            }
        }
    }

    /** Names describe deferred stages; no native menu or event has been executed by this plan. */
    public record CallbackPlan(
            String category, List<String> requiredStages, List<String> evidence,
            boolean requiresVirtualContext) {
        public CallbackPlan {
            requiredStages = List.copyOf(requiredStages);
            evidence = List.copyOf(evidence);
        }
    }

    /** A per-unit distribution template. Its stack accessor never exposes the retained component graph. */
    public static final class ProbabilityOutput {
        private final int index;
        private final ItemStack template;
        private final float chance;
        private final HolderLookup.Provider registries;

        public ProbabilityOutput(int index, ItemStack template, float chance, HolderLookup.Provider registries) {
            if (index < 0 || template == null || template.isEmpty() || !Float.isFinite(chance)
                    || template.getCount() < 1 || template.getCount() > template.getMaxStackSize()) {
                throw new IllegalArgumentException("PROCESSING_DISTRIBUTION_INVALID");
            }
            this.index = index;
            this.template = RecipeInputIsolation.copy(template, registries);
            this.chance = chance;
            this.registries = registries;
        }

        public int index() { return index; }
        public ItemStack template() { return RecipeInputIsolation.copy(template, registries); }
        public int trials() { return template.getCount(); }
        public float chance() { return chance; }
    }

    /**
     * Publish the sample and consumption of real escrow in one durable transition. An exported
     * result must never coexist with refundable inputs. Recovery uses the recorded outcome;
     * it must not invoke an adapter again with a new seed.
     */
    public record SamplingEvidence(
            boolean sampled, String algorithm, long seed, int draws, List<ProbabilityOutput> distribution) {
        public SamplingEvidence {
            if (draws < 0) throw new IllegalArgumentException("PROCESSING_DISTRIBUTION_INVALID");
            distribution = List.copyOf(distribution);
        }

        public static SamplingEvidence deterministic() {
            return new SamplingEvidence(false, "deterministic", 0, 0, List.of());
        }
    }

    private final ResourceLocation recipeId;
    private final ResourceLocation recipeType;
    private final ResourceLocation serializerId;
    private final String mechanism;
    private final List<ItemStack> inputs;
    private final List<Integer> consumedBySlot;
    private final List<ItemStack> outputs;
    private final List<ItemStack> remainders;
    private final ExperienceReward experience;
    private final CallbackPlan callbacks;
    private final SamplingEvidence sampling;
    private final Map<String, String> contextRequirements;
    private final HolderLookup.Provider registries;

    protected VerifiedTransformation(
            ResourceLocation recipeId, ResourceLocation recipeType, ResourceLocation serializerId,
            String mechanism, List<ItemStack> inputs, List<Integer> consumedBySlot,
            List<ItemStack> outputs, List<ItemStack> remainders, ExperienceReward experience,
            CallbackPlan callbacks, SamplingEvidence sampling, Map<String, String> contextRequirements,
            HolderLookup.Provider registries) {
        if ((recipeId == null ? recipeType != null || serializerId != null : recipeType == null || serializerId == null) || mechanism == null
                || experience == null || callbacks == null || sampling == null
                || inputs.size() != consumedBySlot.size() || inputs.size() != remainders.size()) {
            throw new IllegalArgumentException("PROCESSING_RESULT_INVALID");
        }
        this.registries = registries;
        this.inputs = RecipeInputIsolation.copyAll(inputs, registries);
        this.consumedBySlot = List.copyOf(consumedBySlot);
        for (int slot = 0; slot < inputs.size(); slot++) {
            int count = consumedBySlot.get(slot);
            if (count < 0 || count > inputs.get(slot).getCount()) {
                throw new IllegalArgumentException("PROCESSING_RESULT_INVALID");
            }
        }
        this.outputs = RecipeInputIsolation.copyAll(outputs, registries);
        this.remainders = RecipeInputIsolation.copyAll(remainders, registries);
        this.recipeId = recipeId;
        this.recipeType = recipeType;
        this.serializerId = serializerId;
        this.mechanism = mechanism;
        this.experience = experience;
        this.callbacks = callbacks;
        this.sampling = sampling;
        this.contextRequirements = Map.copyOf(contextRequirements);
    }

    public ResourceLocation recipeId() { return recipeId; }
    public ResourceLocation recipeType() { return recipeType; }
    public ResourceLocation serializerId() { return serializerId; }
    public List<RecipeReference> backingRecipes() { return recipeId == null ? List.of() : List.of(new RecipeReference(recipeId, recipeType)); }
    public boolean isRecipe() { return recipeId != null; }
    public String mechanism() { return mechanism; }
    public String costPolicy() { return "MATERIALS_ONLY"; }
    public List<Integer> consumedBySlot() { return consumedBySlot; }
    public List<ItemStack> inputSnapshots() { return RecipeInputIsolation.copyAll(inputs, registries); }
    public List<ItemStack> outputs() { return RecipeInputIsolation.copyAll(outputs, registries); }
    public List<ItemStack> remaindersBySlot() { return RecipeInputIsolation.copyAll(remainders, registries); }
    public ExperienceReward experience() { return experience; }
    public CallbackPlan callbackPlan() { return callbacks; }
    public SamplingEvidence samplingEvidence() { return sampling; }
    public Map<String, String> contextRequirements() { return contextRequirements; }
}
