package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.material.NativeSaveBarrier;
import firefly520.fireflymc.ai.material.NativeSaveObserver;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;

/** Mode-specific native tag evidence. Only a small inspected field whitelist is read; no AI settings/history. */
public final class MaidControlSaveWitness {
    private static final Map<Entity, List<Witness>> ACTIVE = new IdentityHashMap<>();
    private static final Map<UUID, Registration> KNOWN = new LinkedHashMap<>();
    private static final Set<UUID> CLOSING_SCOPES = new HashSet<>();
    private record Witness(Object identity, UUID owner, MaidControlPlan.NativeState expected) {}
    private record Registration(UUID operation, UUID scope, boolean reconciliation, Entity entity, Witness witness,
                                NativeSaveObserver saves, NativeSaveBarrier.Receipt receipt, String destination, MinecraftServer server) {}
    private MaidControlSaveWitness() {}

    public static NativeSaveBarrier.Receipt observe(Entity entity, UUID owner, MaidControlPlan.NativeState expected,
                                                   NativeSaveObserver saves, UUID operation, UUID scope) {
        return observe(entity, owner, expected, saves, operation, scope, false);
    }
    static NativeSaveBarrier.Receipt observeReconciliation(Entity entity, UUID owner, MaidControlPlan.NativeState expected,
                                                           NativeSaveObserver saves, UUID operation, UUID scope) {
        return observe(entity, owner, expected, saves, operation, scope, true);
    }
    private static NativeSaveBarrier.Receipt observe(Entity entity, UUID owner, MaidControlPlan.NativeState expected,
                                                    NativeSaveObserver saves, UUID operation, UUID scope, boolean reconciliation) {
        if (!(entity.level() instanceof ServerLevel level) || !level.getServer().isDedicatedServer() || !level.getServer().isSameThread()
                || !entity.getUUID().equals(expected.maidId()) || !level.dimension().location().toString().equals(expected.dimension()))
            throw new IllegalStateException("MAID_MODE_SAVE_WITNESS_CONTEXT");
        var identity = new Object(); var target = NativeSaveObserver.entity(entity);
        var witness = new Witness(identity, owner, expected);
        synchronized (ACTIVE) {
            if (KNOWN.size() >= 2048) KNOWN.entrySet().removeIf(entry -> !entry.getValue().reconciliation() && entry.getValue().receipt().saved().isDone());
            if (ACTIVE.values().stream().mapToInt(List::size).sum() >= 256 || KNOWN.size() >= 4096) throw new IllegalStateException("MAID_MODE_SAVE_WITNESS_LIMIT");
        }
        // Main-thread registrations are sequential; never acquire the observer while holding
        // this registry, because a canonical writer can complete a receipt in the other order.
        var receipt = saves.changedEntityWitness(identity, entity);
        synchronized (ACTIVE) {
            KNOWN.put(receipt.id(), new Registration(operation, scope, reconciliation, entity, witness, saves, receipt, target.destination(), level.getServer()));
            ACTIVE.computeIfAbsent(entity, ignored -> new ArrayList<>()).add(witness);
        }
        receipt.saved().whenComplete((proof, failure) -> {
            synchronized (ACTIVE) {
                var pending = ACTIVE.get(entity);
                if (pending != null) { pending.remove(witness); if (pending.isEmpty()) ACTIVE.remove(entity); }
                if (CLOSING_SCOPES.contains(scope)) { KNOWN.remove(receipt.id()); forgetClosedScope(scope); }
            }
        });
        return receipt;
    }
    /** Runs only after the approved current-state resolution is durable; never changes the entity. */
    static boolean supersede(UUID originalOperation, UUID currentReview, UUID currentScope,
                             NativeSaveBarrier.Receipt currentReceipt, UUID owner, UUID maid) {
        Registration replacement; List<Registration> pending;
        synchronized (ACTIVE) {
            replacement = KNOWN.get(currentReceipt.id());
            if (replacement == null || !replacement.reconciliation() || !replacement.operation().equals(currentReview)
                    || !replacement.scope().equals(currentScope) || !replacement.witness().owner().equals(owner)
                    || !replacement.witness().expected().maidId().equals(maid) || !currentReceipt.saved().isDone()
                    || currentReceipt.saved().isCompletedExceptionally()) throw new IllegalStateException("MAID_RECONCILE_HANDOFF_SOURCE_INVALID");
            pending = KNOWN.values().stream().filter(reg -> !reg.reconciliation() && reg.operation().equals(originalOperation)
                    && reg.witness().owner().equals(owner) && reg.witness().expected().maidId().equals(maid)
                    && reg.saves() == replacement.saves() && reg.server() == replacement.server()
                    && reg.destination().equals(replacement.destination()) && !reg.receipt().saved().isDone()).toList();
        }
        if (!(replacement.entity().level() instanceof ServerLevel level) || !level.getServer().isSameThread())
            throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        for (var old : pending) replacement.saves().handoff(replacement.saves().suspendEntity(old.witness().identity()), currentReceipt,
                "approved-maid-current-state-resolution:" + originalOperation + ":" + currentReview + ":" + owner + ":" + maid);
        return !pending.isEmpty();
    }
    static void forgetCompleted(NativeSaveBarrier.Receipt receipt) {
        synchronized (ACTIVE) {
            var existing = KNOWN.get(receipt.id());
            if (existing != null && existing.receipt().saved().isDone()) { KNOWN.remove(receipt.id()); forgetClosedScope(existing.scope()); }
        }
    }
    static void forgetErasedOwner(UUID scope, UUID owner) {
        synchronized (ACTIVE) {
            KNOWN.entrySet().removeIf(entry -> entry.getValue().scope().equals(scope) && entry.getValue().witness().owner().equals(owner)
                    && entry.getValue().receipt().saved().isDone());
            forgetClosedScope(scope);
        }
    }
    static void closeScope(UUID scope) {
        synchronized (ACTIVE) {
            KNOWN.entrySet().removeIf(entry -> entry.getValue().scope().equals(scope) && entry.getValue().receipt().saved().isDone());
            if (KNOWN.values().stream().anyMatch(reg -> reg.scope().equals(scope))) CLOSING_SCOPES.add(scope);
            else CLOSING_SCOPES.remove(scope);
        }
    }
    private static void forgetClosedScope(UUID scope) {
        if (KNOWN.values().stream().noneMatch(reg -> reg.scope().equals(scope))) CLOSING_SCOPES.remove(scope);
    }
    /** Called after the complete native Entity.saveWithoutId payload has been written by all vanilla/TLM code. */
    public static void serialized(Entity entity, CompoundTag tag) {
        List<Witness> pending;
        synchronized (ACTIVE) { var current = ACTIVE.get(entity); if (current == null) return; pending = List.copyOf(current); }
        if (!(entity.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        for (var witness : pending) if (matches(tag, witness.owner(), witness.expected()))
            NativeSaveObserver.serializedAdditional(witness.identity(), tag);
    }
    static boolean matches(CompoundTag tag, UUID owner, MaidControlPlan.NativeState expected) {
        if (tag == null || !tag.hasUUID("UUID") || !tag.hasUUID("Owner")
                || !tag.getUUID("UUID").equals(expected.maidId()) || !tag.getUUID("Owner").equals(owner)) return false;
        for (String name : List.of("Sitting", "MaidIsHome", "MaidIsPickup", "MaidIsRideable")) if (!tag.contains(name, Tag.TAG_BYTE)) return false;
        return tag.getBoolean("Sitting") == expected.seated() && tag.getBoolean("MaidIsHome") == expected.home()
                && tag.getBoolean("MaidIsPickup") == expected.pickup() && tag.getBoolean("MaidIsRideable") == expected.rideable()
                && tag.contains("MaidTask", Tag.TAG_STRING) && tag.getString("MaidTask").equals(expected.taskId())
                && tag.contains("MaidScheduleMode", Tag.TAG_STRING) && tag.getString("MaidScheduleMode").equals(expected.schedule());
    }
}
