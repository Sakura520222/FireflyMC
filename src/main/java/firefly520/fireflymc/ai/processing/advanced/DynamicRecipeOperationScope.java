package firefly520.fireflymc.ai.processing.advanced;

import java.util.Objects;
import java.util.UUID;

/** Server operation identity only; opening it grants no inventory, world or native-store permission. */
public final class DynamicRecipeOperationScope {
    private static final ThreadLocal<Scope> ACTIVE = new ThreadLocal<>();
    private DynamicRecipeOperationScope() { }
    public static UUID current() { var scope = ACTIVE.get(); return scope == null ? null : scope.operation; }
    public static Scope open(UUID operationId) {
        Objects.requireNonNull(operationId);
        Scope previous = ACTIVE.get();
        if (previous != null && !previous.operation.equals(operationId))
            throw new IllegalStateException("DYNAMIC_OPERATION_SCOPE_CHANGED");
        var scope = new Scope(operationId, previous); ACTIVE.set(scope); return scope;
    }
    public static final class Scope implements AutoCloseable {
        private final UUID operation;
        private final Scope previous;
        private final Thread thread = Thread.currentThread();
        private boolean closed;
        private Scope(UUID operation, Scope previous) { this.operation = operation; this.previous = previous; }
        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != thread || ACTIVE.get() != this)
                throw new IllegalStateException("DYNAMIC_OPERATION_SCOPE_ORDER");
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
            closed = true;
        }
    }
}
