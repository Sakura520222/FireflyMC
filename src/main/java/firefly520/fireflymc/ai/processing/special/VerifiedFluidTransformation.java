package firefly520.fireflymc.ai.processing.special;

import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.material.MaterialStack;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** A multi-resource contract. An item-only executor cannot execute this result. */
public record VerifiedFluidTransformation(ResourceLocation recipeId, ResourceLocation recipeType,
        ResourceLocation serializerId, String adapterVersion, List<MaterialStack> consumedItems,
        List<FluidMaterial> consumedFluids, List<MaterialStack> producedItems, List<FluidMaterial> producedFluids,
        boolean nativeAllowsPartialFill, List<String> evidence) {
    public VerifiedFluidTransformation {
        if (recipeId == null || recipeType == null || serializerId == null || adapterVersion == null
                || consumedItems.isEmpty() && consumedFluids.isEmpty()
                || producedItems.isEmpty() && producedFluids.isEmpty()
                || consumedItems.size() > 9 || consumedFluids.size() > 16 || producedItems.size() > 16 || producedFluids.size() > 16)
            throw new IllegalArgumentException("FLUID_TRANSFORMATION_INVALID");
        consumedItems = List.copyOf(consumedItems); consumedFluids = List.copyOf(consumedFluids);
        producedItems = List.copyOf(producedItems); producedFluids = List.copyOf(producedFluids); evidence = List.copyOf(evidence);
    }
    public String costPolicy() { return "MATERIALS_ONLY"; }
    public boolean reserveAllOutputs() { return true; }
    public boolean requiresFluidLedger() { return true; }
    public List<ItemStack> restoreItemOutputs(ServerLevel level) { return producedItems.stream().map(s -> ItemSnapshotCodec.restore(s, level.registryAccess())).toList(); }
}
