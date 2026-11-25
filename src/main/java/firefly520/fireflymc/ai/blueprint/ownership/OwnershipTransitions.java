package firefly520.fireflymc.ai.blueprint.ownership;

import java.util.*;

/** Pure causal witness. A state comparison alone never creates a transition. */
public final class OwnershipTransitions {
    public record Transition(String before, String after) {}
    private final Map<Long, String> expected;
    private final Map<Long, Transition> observed = new LinkedHashMap<>();
    private boolean uncertain;
    public OwnershipTransitions(Map<Long, String> expected) {
        if (expected.isEmpty() || expected.size() > OwnershipChunkState.MAX_CLAIMS)
            throw new IllegalArgumentException("OWNERSHIP_FOOTPRINT_INVALID");
        this.expected = Map.copyOf(expected);
    }
    public void changed(long position, String before, String after) {
        if (Objects.equals(before, after)) return;
        var previous = observed.get(position);
        // Repeated writes to the same target, including ABA inside a callback, need a more
        // specific adapter. A final matching BlockState does not prove who owns the last write.
        if (previous != null) { uncertain = true; return; }
        String wanted = previous == null ? expected.get(position) : previous.after();
        if (wanted == null || !wanted.equals(before)) { uncertain = true; return; }
        observed.put(position, new Transition(previous == null ? before : previous.before(), after));
    }
    public void unobserved() { uncertain = true; }
    public Map<Long, Transition> require(Map<Long, String> after) {
        if (uncertain || !after.keySet().equals(expected.keySet()))
            throw new IllegalStateException("OWNERSHIP_NATIVE_FOOTPRINT_UNCERTAIN");
        var result = new LinkedHashMap<Long, Transition>();
        for (var entry : expected.entrySet()) {
            var transition = observed.get(entry.getKey()); String target = after.get(entry.getKey());
            if (entry.getValue().equals(target)) {
                // No-op targets receive no claim. An observed ABA cannot be silently called a no-op.
                if (transition != null) throw new IllegalStateException("OWNERSHIP_NATIVE_ABA_UNCERTAIN");
            } else {
                if (transition == null || !transition.before().equals(entry.getValue()) || !transition.after().equals(target))
                    throw new IllegalStateException("OWNERSHIP_NATIVE_TRANSITION_MISSING");
                result.put(entry.getKey(), transition);
            }
        }
        return Map.copyOf(result);
    }
}
