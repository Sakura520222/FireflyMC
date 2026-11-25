package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.material.ItemSnapshotCodec;
import firefly520.fireflymc.ai.material.MaterialStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Exact batch arithmetic for verified outputs and explicitly proven identical reusable materials. */
public final class ProcessingBatchProducts {
    private ProcessingBatchProducts() {}
    public static List<MaterialStack> aggregate(VerifiedTransformation result, int crafts, HolderLookup.Provider registries) {
        if (crafts < 1 || crafts > 16) throw new IllegalArgumentException("PROCESSING_BATCH_LIMIT");
        boolean batch = "true".equals(result.contextRequirements().get("batchAlreadyApplied"));
        if (batch && !Integer.toString(crafts).equals(result.contextRequirements().get("logicalOperations")))
            throw new IllegalArgumentException("PROCESSING_BATCH_MISMATCH");
        var combined = new LinkedHashMap<String, MaterialStack>();
        for (ItemStack value : result.outputs()) if (!value.isEmpty()) add(combined, value, batch ? 1 : crafts, registries);
        String declaration = result.contextRequirements().getOrDefault("reusableGridSlots", "");
        Set<String> reusable = declaration.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(declaration.split(",")));
        var remainders = result.remaindersBySlot(); var inputs = result.inputSnapshots();
        for (int slot = 0; slot < remainders.size(); slot++) if (!remainders.get(slot).isEmpty()) {
            ItemStack returned = remainders.get(slot);
            boolean retained = reusable.contains(Integer.toString(slot));
            if (retained && (inputs.get(slot).getCount() != returned.getCount()
                    || result.consumedBySlot().get(slot) != returned.getCount()
                    || !ItemStack.isSameItemSameComponents(inputs.get(slot), returned)))
                throw new IllegalStateException("REUSABLE_MATERIAL_NOT_IDENTICAL");
            add(combined, returned, retained || batch ? 1 : crafts, registries);
        }
        return List.copyOf(combined.values());
    }
    private static void add(Map<String, MaterialStack> combined, ItemStack stack, int scale, HolderLookup.Provider registries) {
        var encoded = ItemSnapshotCodec.capture(stack, registries);
        combined.merge(encoded.itemJson(), encoded.withCount(Math.multiplyExact(encoded.count(), scale)),
                (a, b) -> a.withCount(Math.addExact(a.count(), b.count())));
    }
}
