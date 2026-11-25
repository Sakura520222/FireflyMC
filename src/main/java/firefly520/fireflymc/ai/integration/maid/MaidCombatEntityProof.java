package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.material.NativeSaveBarrier;
import firefly520.fireflymc.ai.material.NativeSaveObserver;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** Physical target write or exact post-removal entity-region write, never a later absence inference. */
public final class MaidCombatEntityProof {
    private static final int MAX_PENDING = 256;
    private static final Map<Entity, List<Pending>> ACTIVE = new IdentityHashMap<>();
    private static final Set<Entity> REMOVING = Collections.newSetFromMap(new IdentityHashMap<>());
    private record Pending(UUID operation, NativeSaveObserver observer, NativeSaveBarrier.Receipt receipt) {}
    private record Transfer(NativeSaveObserver observer, NativeSaveObserver.WriteSnapshot snapshot, List<Pending> pending) {}
    private record Batch(Entity entity, List<Transfer> transfers, List<Pending> pending) {}
    /** Opaque identity from the exact native remove call; callers cannot construct a positive save proof. */
    public record Removal(Object pending, Entity.RemovalReason reason) {}
    private MaidCombatEntityProof() {}
    static NativeSaveBarrier.Receipt observe(Entity target, UUID operation, NativeSaveObserver observer) {
        if (!(target.level() instanceof ServerLevel level) || !level.getServer().isSameThread() || !level.getServer().isDedicatedServer())
            throw new IllegalStateException("MAID_COMBAT_TARGET_SAVE_CONTEXT");
        if (target.isRemoved()) return observer.entityRemoved(target);
        synchronized (ACTIVE) {
            if (ACTIVE.values().stream().mapToInt(List::size).sum() >= MAX_PENDING)
                throw new IllegalStateException("MAID_COMBAT_TARGET_SAVE_BACKLOG");
        }
        // Observations are created on the single server thread. Receipt completion may run on
        // a writer thread, so never acquire the observer while holding this registry's monitor.
        var receipt = observer.changed(List.of(NativeSaveObserver.entity(target)));
        var pending = new Pending(operation, observer, receipt);
        synchronized (ACTIVE) { ACTIVE.computeIfAbsent(target, ignored -> new ArrayList<>()).add(pending); }
        receipt.saved().whenComplete((proof, failure) -> forget(target, List.of(pending)));
        return receipt;
    }
    public static Removal before(Entity entity, Entity.RemovalReason reason) {
        if (!(entity.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return null;
        List<Pending> pending;
        synchronized (ACTIVE) {
            var current = ACTIVE.get(entity);
            if (current == null || current.isEmpty() || !REMOVING.add(entity)) return null;
            pending = List.copyOf(current);
        }
        var grouped = new IdentityHashMap<NativeSaveObserver, List<Pending>>();
        for (var value : pending) grouped.computeIfAbsent(value.observer(), ignored -> new ArrayList<>()).add(value);
        var transfers = new ArrayList<Transfer>();
        try {
            // A single suspension includes every still-unsaved hit on this entity. Suspending once
            // per hit would make the later snapshots empty and could strand the earlier receipts.
            for (var entry : grouped.entrySet())
                transfers.add(new Transfer(entry.getKey(), entry.getKey().suspendEntity(entity), List.copyOf(entry.getValue())));
            return new Removal(new Batch(entity, List.copyOf(transfers), pending), reason);
        } catch (RuntimeException unavailable) {
            for (var transfer : transfers) transfer.observer().unchanged(transfer.snapshot());
            fail(entity, pending, unavailable);
            synchronized (ACTIVE) { REMOVING.remove(entity); }
            return null;
        }
    }
    public static void after(Entity entity, Removal removal) {
        if (removal == null || !(removal.pending() instanceof Batch batch) || batch.entity() != entity) return;
        try {
            if (!entity.isRemoved() || removal.reason() == Entity.RemovalReason.CHANGED_DIMENSION || removal.reason() == Entity.RemovalReason.UNLOADED_TO_CHUNK) {
                for (var transfer : batch.transfers()) transfer.observer().unchanged(transfer.snapshot());
                return;
            }
            for (var transfer : batch.transfers()) {
                var deletion = transfer.observer().entityRemoved(entity);
                String operations = transfer.pending().stream().map(value -> value.operation().toString()).distinct().sorted()
                        .collect(java.util.stream.Collectors.joining(","));
                transfer.observer().handoff(transfer.snapshot(), deletion,
                        "actual-maid-combat-target-removal:" + operations + ":" + entity.getUUID() + ":" + removal.reason());
                deletion.saved().whenComplete((proof, failure) -> {
                    if (failure != null) for (var value : transfer.pending()) value.receipt().saved().completeExceptionally(failure);
                });
            }
            // The handoff now owns the pending dependencies. Do not keep a removed world/entity
            // alive in this static registry while the asynchronous canonical write is outstanding.
            forget(entity, batch.pending());
        } catch (RuntimeException unavailable) {
            fail(entity, batch.pending(), unavailable);
        } finally {
            synchronized (ACTIVE) { REMOVING.remove(entity); }
        }
    }
    private static void fail(Entity entity, List<Pending> pending, Throwable failure) {
        for (var value : pending) value.receipt().saved().completeExceptionally(failure);
        forget(entity, pending);
    }
    private static void forget(Entity entity, List<Pending> pending) {
        synchronized (ACTIVE) {
            var current = ACTIVE.get(entity);
            if (current == null) return;
            current.removeAll(pending);
            if (current.isEmpty()) ACTIVE.remove(entity);
        }
    }
}
