package firefly520.fireflymc.ai.blueprint;

import java.lang.ref.WeakReference;
import java.util.*;

/** Bounded read-only identity observations. No entry represents construction, undo or inventory-transfer authority. */
final class FunctionalObjectBindings<T> {
    record Scope(UUID server, UUID conversation, String owner, long privacy, UUID plan, String planHash, String dimension) {
        Scope {
            Objects.requireNonNull(server); Objects.requireNonNull(conversation); Objects.requireNonNull(plan);
            UUID.fromString(owner);
            if (privacy < 0 || planHash == null || !planHash.matches("[0-9a-f]{64}") || dimension == null
                    || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("FUNCTION_IDENTITY_SCOPE_INVALID");
        }
    }
    private final int maximumPlans, maximumObjects;
    private final LinkedHashMap<Scope, Map<Long, WeakReference<T>>> bindings = new LinkedHashMap<>();
    private int objects;

    FunctionalObjectBindings(int maximumPlans, int maximumObjects) {
        if (maximumPlans < 1 || maximumObjects < 1) throw new IllegalArgumentException("FUNCTION_IDENTITY_LIMIT_INVALID");
        this.maximumPlans = maximumPlans; this.maximumObjects = maximumObjects;
    }
    synchronized void bind(Scope scope, long position, T object) {
        Objects.requireNonNull(scope); Objects.requireNonNull(object);
        var known = bindings.get(scope);
        if (known != null && known.containsKey(position)) {
            // An unexpected replacement never inherits an older observation, even with equal state.
            var reference = known.get(position);
            if (reference.get() != object) reference.clear();
            return;
        }
        while (!bindings.isEmpty() && (objects >= maximumObjects || known == null && bindings.size() >= maximumPlans)) {
            var oldest = bindings.entrySet().iterator(); var removed = oldest.next(); objects -= removed.getValue().size(); oldest.remove();
            known = bindings.get(scope);
        }
        if (known == null) { known = new HashMap<>(); bindings.put(scope, known); }
        known.put(position, new WeakReference<>(object)); objects++;
    }
    synchronized T get(Scope scope, long position) {
        var known = bindings.get(scope); if (known == null) return null;
        var reference = known.get(position); return reference == null ? null : reference.get();
    }
    synchronized void forgetOwner(String owner) {
        var iterator = bindings.entrySet().iterator();
        while (iterator.hasNext()) {
            var row = iterator.next();
            if (row.getKey().owner().equals(owner)) { objects -= row.getValue().size(); iterator.remove(); }
        }
    }
    synchronized void clear() { bindings.clear(); objects = 0; }
}
