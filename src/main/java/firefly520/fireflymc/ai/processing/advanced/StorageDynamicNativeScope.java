package firefly520.fireflymc.ai.processing.advanced;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Scoped native interception; ordinary storage access and UUID allocation are unchanged. */
public final class StorageDynamicNativeScope implements AutoCloseable {
    private static final ThreadLocal<StorageDynamicNativeScope> ACTIVE = new ThreadLocal<>();
    private final Thread thread = Thread.currentThread();
    private final UUID operation;
    private final Object store;
    private final boolean preview;
    private final Set<UUID> allowed;
    private int allocations;
    private final Set<Object> detachedStacks = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean collecting = true;
    private boolean closed;
    private StorageDynamicNativeScope(UUID operation, Object store, boolean preview, Collection<UUID> sources) {
        if (ACTIVE.get() != null) throw new IllegalStateException("STORAGE_DYNAMIC_SCOPE_NESTED");
        this.operation = Objects.requireNonNull(operation); this.store = Objects.requireNonNull(store); this.preview = preview;
        var ids = new HashSet<>(sources); ids.add(reserved(operation));
        if (ids.size() > 10) throw new IllegalArgumentException("STORAGE_DYNAMIC_SCOPE_LIMIT");
        allowed = Set.copyOf(ids); ACTIVE.set(this);
    }
    static StorageDynamicNativeScope open(UUID operation, Object store, boolean preview, Collection<UUID> sources) {
        return new StorageDynamicNativeScope(operation, store, preview, sources);
    }
    public static UUID reserved(UUID operation) {
        return UUID.nameUUIDFromBytes(("fireflymc:storage-dynamic:canonical:" + operation + ":0").getBytes(StandardCharsets.UTF_8));
    }
    public static Object previewStore() {
        var scope = ACTIVE.get(); return scope != null && scope.preview ? scope.store : null;
    }
    /** An internal read may inspect only an already selected entry in the current native scope. */
    static Object storeForRead(UUID id) {
        var scope = ACTIVE.get();
        if (scope == null || scope.closed || id == null || !scope.allowed.contains(id))
            throw new SecurityException("STORAGE_DYNAMIC_UUID_OUTSIDE_APPROVED_SLICE");
        return scope.store;
    }
    public static void requireEntry(Object store, UUID id) {
        var scope = ACTIVE.get();
        if (scope != null && (scope.closed || scope.store != store || !scope.allowed.contains(id)))
            throw new SecurityException("STORAGE_DYNAMIC_UUID_OUTSIDE_APPROVED_SLICE");
    }
    /** null means normal gameplay; a scoped operation owns exactly one fresh native storage identity. */
    public static UUID allocatedUuid() {
        var scope = ACTIVE.get(); if (scope == null) return null;
        if (scope.closed || ++scope.allocations > 1) throw new IllegalStateException("STORAGE_DYNAMIC_UUID_BUDGET");
        return reserved(scope.operation);
    }
    public static void observeStack(Object stack) {
        var scope = ACTIVE.get();
        if (scope != null && scope.collecting) {
            if (scope.detachedStacks.size() >= 64 && !scope.detachedStacks.contains(stack))
                throw new IllegalStateException("STORAGE_DYNAMIC_WRAPPER_LIMIT");
            scope.detachedStacks.add(stack);
        }
    }
    Set<Object> detachedStacks() { return Set.copyOf(detachedStacks); }
    void endAssembly() { collecting = false; }
    @Override public void close() {
        if (closed) return;
        if (Thread.currentThread() != thread || ACTIVE.get() != this) throw new IllegalStateException("STORAGE_DYNAMIC_SCOPE_CHANGED");
        closed = true; ACTIVE.remove();
    }
}
