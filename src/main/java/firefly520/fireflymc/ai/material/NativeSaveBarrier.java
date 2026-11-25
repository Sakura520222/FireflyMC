package firefly520.fireflymc.ai.material;

import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Identity-based acknowledgements of fixed serialized snapshots, independent of Minecraft. */
public final class NativeSaveBarrier {
    public record Receipt(UUID id, CompletableFuture<String> saved) {}
    public record Snapshot(List<Entry> entries) {
        public Snapshot { entries = List.copyOf(entries); }
        public boolean empty() { return entries.isEmpty(); }
    }
    public record Entry(UUID receipt, Object target) {}
    /** Includes native effects dispatched later on the server queue; none may be skipped. */
    public static Receipt combine(Collection<? extends CompletionStage<Receipt>> stages) {
        if (stages.isEmpty() || stages.size() > 128) throw new IllegalArgumentException("NATIVE_SAVE_TARGET_REQUIRED");
        UUID id = UUID.randomUUID();
        var saves = stages.stream().map(stage -> stage.thenCompose(Receipt::saved).toCompletableFuture()).toList();
        var proof = CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new)).thenApply(ignored ->
                AiJson.digest(id + ":" + saves.stream().map(CompletableFuture::join).sorted().toList()));
        return new Receipt(id, proof);
    }
    private record Pending(Receipt receipt, Set<Object> remaining, Map<Object, String> evidence) {}
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private final Map<Object, Set<UUID>> targets = new IdentityHashMap<>();
    private boolean closed;

    public synchronized void requireCapacity() {
        if (closed || pending.size() >= 16384 || targets.size() >= 8192)
            throw new IllegalStateException("NATIVE_SAVE_BACKLOG");
    }
    public synchronized void requireCapacity(Collection<?> stores) {
        requireCapacity();
        var additions = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (Object store : stores) if (!targets.containsKey(store)) additions.add(store);
        if (targets.size() + additions.size() > 8192) throw new IllegalStateException("NATIVE_SAVE_BACKLOG");
    }
    /** Register only after the native call, on the same main-thread slice. */
    public synchronized Receipt changed(Collection<?> stores) {
        requireCapacity(stores);
        if (stores.isEmpty() || stores.size() > 128 || stores.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("NATIVE_SAVE_TARGET_REQUIRED");
        Set<Object> remaining = Collections.newSetFromMap(new IdentityHashMap<>());
        remaining.addAll(stores);
        var receipt = new Receipt(UUID.randomUUID(), new CompletableFuture<>());
        pending.put(receipt.id(), new Pending(receipt, remaining, new IdentityHashMap<>()));
        remaining.forEach(target -> targets.computeIfAbsent(target, key -> new HashSet<>()).add(receipt.id()));
        return receipt;
    }
    public synchronized Snapshot snapshot(Object target) {
        return new Snapshot(targets.getOrDefault(target, Set.of()).stream().map(id -> new Entry(id, target)).toList());
    }
    /** Acknowledging an older serialized snapshot cannot acknowledge later mutations. */
    public void written(Snapshot snapshot, String nativeEvidence) {
        var finished = new LinkedHashMap<Receipt, String>();
        synchronized (this) {
            if (closed || nativeEvidence == null || nativeEvidence.isBlank()) return;
            for (Entry entry : snapshot.entries()) {
                Pending row = pending.get(entry.receipt());
                if (row == null || !row.remaining().remove(entry.target())) continue;
                row.evidence().put(entry.target(), nativeEvidence);
                var ids = targets.get(entry.target());
                if (ids != null) { ids.remove(entry.receipt()); if (ids.isEmpty()) targets.remove(entry.target()); }
                if (row.remaining().isEmpty()) {
                    pending.remove(entry.receipt());
                    finished.put(row.receipt(), AiJson.digest(row.receipt().id() + ":" + row.evidence().values().stream().sorted().toList()));
                }
            }
        }
        // Never run storage callbacks while holding the tracker lock.
        finished.forEach((receipt, proof) -> receipt.saved().complete(proof));
    }
    public synchronized int pendingCount() { return pending.size(); }
    public void close() {
        List<Receipt> unfinished;
        synchronized (this) {
            closed = true; unfinished = pending.values().stream().map(Pending::receipt).toList();
            pending.clear(); targets.clear();
        }
        unfinished.forEach(row -> row.saved().completeExceptionally(new IllegalStateException("NATIVE_SAVE_NOT_CONFIRMED")));
    }
}
