package firefly520.fireflymc.ai.integration.maid.transport;

import firefly520.fireflymc.ai.material.MaterialStack;
import java.util.*;

/** Exact component identity and count, including real in-flight custody; no recipe/energy waiver. */
public final class MaidTransportConservation {
    private MaidTransportConservation() {}
    public static void require(List<MaterialStack> sourceBefore, List<MaterialStack> destinationBefore,
            List<MaterialStack> sourceAfter, List<MaterialStack> destinationAfter, List<MaterialStack> custody) {
        var before = totals(sourceBefore, destinationBefore); var after = totals(sourceAfter, destinationAfter, custody);
        if (!before.equals(after)) throw new IllegalStateException("MAID_TRANSPORT_COMPONENT_CONSERVATION_FAILED");
    }
    @SafeVarargs private static Map<String, Long> totals(List<MaterialStack>... groups) {
        var result = new TreeMap<String, Long>();
        for (var group : groups) for (var stack : group) if (!stack.empty()) result.merge(stack.itemJson(), (long) stack.count(), Math::addExact);
        return result;
    }
}
