package firefly520.fireflymc.ai.processing.special;

import firefly520.fireflymc.ai.material.MaterialStack;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

/** Native mechanisms without RecipeHolders have explicit IDs and independent sampling evidence. */
public record VerifiedFluidMechanism(ResourceLocation mechanismId, String adapterVersion,
        List<MaterialStack> consumedItems, List<FluidMaterial> requiredFluids, List<FluidMaterial> consumedFluids,
        List<MaterialStack> producedItems, List<FluidMaterial> producedFluids,
        boolean sampled, long seed, double successChance, Map<String, String> context) {
    public VerifiedFluidMechanism {
        if (mechanismId == null || adapterVersion == null || consumedItems.isEmpty()
                || requiredFluids.size() > 4 || consumedFluids.size() > 4 || producedFluids.size() > 4
                || !Double.isFinite(successChance) || successChance < 0 || successChance > 1)
            throw new IllegalArgumentException("FLUID_MECHANISM_INVALID");
        consumedItems = List.copyOf(consumedItems); requiredFluids = List.copyOf(requiredFluids);
        consumedFluids = List.copyOf(consumedFluids); producedItems = List.copyOf(producedItems); producedFluids = List.copyOf(producedFluids);
        context = Map.copyOf(context);
    }
    public String costPolicy() { return "MATERIALS_ONLY"; }
    public boolean isRecipe() { return false; }
    public boolean reserveAllOutputs() { return true; }
}
