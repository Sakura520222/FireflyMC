package firefly520.fireflymc.ai.material;

import java.util.*;

/** Evidence for one exact tag identity. Conflicting receipt epochs poison that scope until tag GC. */
final class NativeSerializationCaptures<T> {
    private static final class Scope {
        final Object observer, target;
        Scope(Object observer, Object target) { this.observer = observer; this.target = target; }
        @Override public int hashCode() { return 31 * System.identityHashCode(observer) + System.identityHashCode(target); }
        @Override public boolean equals(Object other) { return other instanceof Scope scope && observer == scope.observer && target == scope.target; }
    }
    private record Value<T>(String destination, Set<UUID> receipts, T evidence) {}
    private final Map<Scope, Value<T>> values = new LinkedHashMap<>();
    private final Set<Scope> conflicts = new HashSet<>();
    private boolean overflow;

    void add(Object observer, Object target, String destination, Collection<UUID> receipts, T evidence) {
        var scope = new Scope(observer, target);
        if (overflow || conflicts.contains(scope)) return;
        if (observer == null || target == null || destination == null || receipts == null || receipts.isEmpty() || evidence == null) {
            values.remove(scope); conflicts.add(scope); return;
        }
        var ids = Set.copyOf(receipts); var previous = values.get(scope);
        if (previous != null) {
            if (!previous.destination().equals(destination) || !previous.receipts().equals(ids)) {
                values.remove(scope); conflicts.add(scope);
            }
            return;
        }
        if (values.size() + conflicts.size() >= 256 || ids.size() > 16384) {
            values.clear(); overflow = true; return;
        }
        values.put(scope, new Value<>(destination, ids, evidence));
    }
    List<T> evidence() { return overflow ? List.of() : values.values().stream().map(Value::evidence).toList(); }
    void removeObserver(Object observer) {
        values.keySet().removeIf(scope -> scope.observer == observer);
        conflicts.removeIf(scope -> scope.observer == observer);
    }
    boolean empty() { return !overflow && values.isEmpty() && conflicts.isEmpty(); }
}
