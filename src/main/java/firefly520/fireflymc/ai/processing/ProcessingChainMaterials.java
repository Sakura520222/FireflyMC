package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.material.MaterialStack;
import java.util.*;

/** Replays material accounting only; native recipe verification remains a separate mandatory gate. */
public final class ProcessingChainMaterials {
    private ProcessingChainMaterials() {}
    public static List<MaterialStack> replay(List<MaterialStack> physical, List<CraftingPlanner.Step> steps) {
        var held = totals(physical);
        for (var step : steps) {
            for (var input : step.inputs()) {
                if (input.empty()) continue;
                int before = held.getOrDefault(input.itemJson(), 0);
                if (before < input.count()) throw new IllegalStateException("CHAIN_INPUT_NOT_OWNED");
                if (before == input.count()) held.remove(input.itemJson()); else held.put(input.itemJson(), before - input.count());
            }
            for (var output : step.products()) {
                if (output.empty()) throw new IllegalStateException("CHAIN_OUTPUT_INVALID");
                held.merge(output.itemJson(), output.count(), Math::addExact);
            }
        }
        return held.entrySet().stream().map(e -> new MaterialStack(e.getKey(), e.getValue())).toList();
    }
    public static Map<String, Integer> totals(List<MaterialStack> stacks) {
        var result = new TreeMap<String, Integer>();
        for (var stack : stacks) if (!stack.empty()) result.merge(stack.itemJson(), stack.count(), Math::addExact);
        return result;
    }
}
