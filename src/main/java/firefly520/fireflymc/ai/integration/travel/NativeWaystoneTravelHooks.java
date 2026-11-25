package firefly520.fireflymc.ai.integration.travel;

import java.util.*;
import java.lang.ref.*;
import net.minecraft.world.level.ChunkPos;

/** Refusal-only hooks for exact native contexts; normal Waystones travel never enters this map. */
public final class NativeWaystoneTravelHooks {
    public interface Guard {
        Object beforeFees(Object destination);
        boolean beforeEntity(Object event);
        void refuse(String reason);
        Set<ChunkPos> chunks();
        Object failedResult();
        default List<?> protectedPreparationTasks() { return List.of(); }
    }
    private static final ReferenceQueue<Object> RELEASED = new ReferenceQueue<>();
    private static final class Identity extends WeakReference<Object> {
        final int hash;
        Identity(Object value, ReferenceQueue<Object> queue) { super(value, queue); hash = System.identityHashCode(value); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object value) { return this == value || value instanceof Identity other && get() != null && get() == other.get(); }
    }
    // Closed tombstone values hold no service/server/context reference; unfinished future keys stay guarded.
    private static final Map<Identity, Guard> ACTIVE = new HashMap<>();
    private record Closed(Object failedResult) implements Guard {
        public Object beforeFees(Object destination) { return failedResult; }
        public boolean beforeEntity(Object event) { return false; }
        public void refuse(String reason) {}
        public Set<ChunkPos> chunks() { return Set.of(); }
    }
    private NativeWaystoneTravelHooks() {}
    static void register(Object context, Guard guard) {
        synchronized (ACTIVE) {
            clean(); var key = new Identity(context, null);
            if (ACTIVE.values().stream().filter(value -> !(value instanceof Closed)).count() >= 8 || ACTIVE.containsKey(key)) throw new IllegalStateException("WAYSTONE_TRAVEL_CONCURRENCY_LIMIT");
            ACTIVE.put(new Identity(context, RELEASED), guard);
        }
    }
    static void remove(Object context) { synchronized (ACTIVE) { clean(); ACTIVE.remove(new Identity(context, null)); } }
    static void closeContext(Object context) {
        synchronized (ACTIVE) {
            clean(); var key = new Identity(context, null); var current = ACTIVE.get(key);
            if (current != null) ACTIVE.put(key, new Closed(Objects.requireNonNull(current.failedResult())));
        }
    }
    private static void clean() { for (Reference<?> key; (key = RELEASED.poll()) != null;) ACTIVE.remove(key); }
    private static Guard guard(Object context) { synchronized (ACTIVE) { clean(); return ACTIVE.get(new Identity(context, null)); } }
    public static Object beforeFees(Object context, Object destination) {
        var guard = guard(context); if (guard == null) return null;
        try { return guard.beforeFees(destination); }
        catch (Throwable failure) { guard.refuse("WAYSTONE_FEE_GUARD_FAILED"); return guard.failedResult(); }
    }
    public static Optional<?> entityOverride(Object event) {
        try {
            Object context = event.getClass().getMethod("getContext").invoke(event);
            var guard = guard(context);
            if (guard != null && !guard.beforeEntity(event)) {
                event.getClass().getMethod("setCanceled", boolean.class).invoke(event, true);
                return Optional.empty();
            }
            return (Optional<?>) event.getClass().getMethod("getOverrideResult").invoke(event);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("WAYSTONE_ENTITY_EVENT_INTERFACE_CHANGED", failure); }
    }
    public static List<?> preparationTasks(Object event) {
        try {
            Object context = event.getClass().getMethod("getContext").invoke(event);
            List<?> tasks = (List<?>) event.getClass().getMethod("getPreparationTasks").invoke(event);
            var guard = guard(context);
            if (guard == null) return tasks;
            if (!tasks.isEmpty()) { guard.refuse("WAYSTONE_PREPARATION_TASK_UNSUPPORTED"); return List.of(); }
            return guard.protectedPreparationTasks();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("WAYSTONE_PREPARATION_INTERFACE_CHANGED", failure); }
    }
    public static Set<?> preparationChunks(Object event) {
        try {
            Object context = event.getClass().getMethod("getContext").invoke(event);
            Set<?> chunks = (Set<?>) event.getClass().getMethod("getChunkPositions").invoke(event);
            var guard = guard(context);
            if (guard == null) return chunks;
            if (chunks.isEmpty() || chunks.size() > 3 || chunks.stream().anyMatch(chunk -> !(chunk instanceof ChunkPos pos) || !guard.chunks().contains(pos))) {
                guard.refuse("WAYSTONE_PREPARATION_SCOPE_CHANGED"); return Set.of();
            }
            return chunks;
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("WAYSTONE_PREPARATION_INTERFACE_CHANGED", failure); }
    }
}
