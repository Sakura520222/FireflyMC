package firefly520.fireflymc.ai.material;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/** Exact native payload identity and canonical destination bind a fixed, post-transition snapshot. */
final class NativeEntityWriteProofs<T> {
    private record Bound<T>(Path folder, long position, List<T> captures) {}
    private static final class Key extends WeakReference<Object> {
        private final int hash;
        Key(Object payload, ReferenceQueue<Object> queue) { super(payload, queue); hash = System.identityHashCode(payload); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Key key && get() != null && get() == key.get();
        }
    }
    private final int capacity;
    private final ReferenceQueue<Object> released = new ReferenceQueue<>();
    private final Map<Key, Bound<T>> submissions = new HashMap<>();
    NativeEntityWriteProofs(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("NATIVE_ENTITY_WRITE_CAPACITY"); this.capacity = capacity;
    }
    /** Null has no identity, and reusing an already submitted mutable payload cannot bind a newer epoch. */
    synchronized boolean submitted(Object payload, Path folder, long position, Collection<T> captures) {
        clean();
        if (payload == null || captures.isEmpty() || submissions.containsKey(new Key(payload, null)) || submissions.size() >= capacity) return false;
        submissions.put(new Key(payload, released), new Bound<>(canonical(folder), position, List.copyOf(captures))); return true;
    }
    synchronized List<T> written(Object actualPayload, Path actualFolder, long actualPosition) {
        clean(); if (actualPayload == null) return List.of();
        var bound = submissions.get(new Key(actualPayload, null));
        return bound != null && bound.position() == actualPosition && bound.folder().equals(canonical(actualFolder))
                ? bound.captures() : List.of();
    }
    synchronized void removeIf(Predicate<T> predicate) {
        clean();
        submissions.replaceAll((key, bound) -> new Bound<>(bound.folder(), bound.position(),
                bound.captures().stream().filter(capture -> !predicate.test(capture)).toList()));
        submissions.entrySet().removeIf(entry -> entry.getValue().captures().isEmpty());
    }
    private static Path canonical(Path folder) { return folder.toAbsolutePath().normalize(); }
    private void clean() {
        Reference<?> reference; while ((reference = released.poll()) != null) submissions.remove(reference);
    }
}
