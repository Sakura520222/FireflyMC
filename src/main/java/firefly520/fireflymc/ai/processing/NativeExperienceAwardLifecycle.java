package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.material.NativeSaveBarrier;
import firefly520.fireflymc.ai.material.NativeSaveObserver;
import firefly520.fireflymc.ai.blueprint.NativeConstructionSave;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.Player;

/** Follows only enlisted AI award stores through unmodified native pickup, merge and removal. */
public final class NativeExperienceAwardLifecycle {
    private static final Map<ExperienceOrb, Boolean> TRACKED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ExperienceOrb, Object> ACTIVE = Collections.synchronizedMap(new IdentityHashMap<>());
    public static final class Guard { private boolean failed; }
    public record Pickup(ExperienceOrb orb, ServerPlayer player, int count, int value,
                         NativeSaveObserver observer, NativeSaveObserver.WriteSnapshot pending, NativeConstructionSave nativeScope) {}
    public record Merge(ExperienceOrb survivor, ExperienceOrb donor, int survivorCount, int donorCount, int value,
                        NativeSaveObserver observer, NativeSaveObserver.WriteSnapshot survivorPending,
                        NativeSaveObserver.WriteSnapshot donorPending, Guard guard) {}
    public record Removal(ExperienceOrb orb, NativeSaveObserver observer, NativeSaveObserver.WriteSnapshot pending, Guard guard) {}
    private NativeExperienceAwardLifecycle() {}

    public static void enlist(Collection<ExperienceOrb> orbs) {
        for (var orb : orbs) if (orb.level() instanceof ServerLevel level && level.getServer().isDedicatedServer()
                && level.getServer().isSameThread() && !orb.isRemoved()) TRACKED.put(orb, true);
    }
    private static NativeSaveObserver observer(ExperienceOrb orb) {
        if (!TRACKED.containsKey(orb) || !(orb.level() instanceof ServerLevel level)
                || !level.getServer().isDedicatedServer() || !level.getServer().isSameThread()) return null;
        var observer = NativeSaveObserver.forServer(level.getServer());
        if (!observer.pending(orb)) { TRACKED.remove(orb); return null; }
        return observer;
    }
    private static int count(ExperienceOrb orb) {
        var tag = new CompoundTag(); orb.addAdditionalSaveData(tag); return tag.getInt("Count");
    }
    public static Pickup beforePickup(ExperienceOrb orb, Player player) {
        var observer = observer(orb);
        if (observer == null || !(player instanceof ServerPlayer recipient) || recipient.serverLevel() != orb.level()
                || ACTIVE.containsKey(orb)) return null;
        var pending = observer.suspendEntity(orb); if (pending.empty()) return null;
        NativeConstructionSave nativeScope = null;
        try { if (orb.getClass() == ExperienceOrb.class) nativeScope = NativeConstructionSave.begin(recipient); }
        catch (RuntimeException unavailable) { /* Unknown nested stores must remain fenced. */ }
        var scope = new Pickup(orb, recipient, count(orb), orb.getValue(), observer, pending, nativeScope);
        ACTIVE.put(orb, scope); return scope;
    }
    public static void afterPickup(Pickup scope) {
        if (scope == null) return; ACTIVE.remove(scope.orb());
        try {
            if (scope.nativeScope() == null) return;
            int after = count(scope.orb());
            if (after == scope.count() && !scope.orb().isRemoved() && scope.orb().getValue() == scope.value()) {
                if (scope.nativeScope().unsupportedEffect().isEmpty() && scope.player().serverLevel() == scope.orb().level())
                    scope.observer().unchanged(scope.pending());
                return;
            }
            if (!validPickup(scope.count(), after, scope.orb().isRemoved()) || scope.orb().getValue() != scope.value()
                    || scope.player().serverLevel() != scope.orb().level()) return;
            var callbackStores = scope.nativeScope().finish();
            var stores = new ArrayList<CompletableFuture<NativeSaveBarrier.Receipt>>();
            stores.add(CompletableFuture.completedFuture(callbackStores));
            if (!scope.orb().isRemoved()) stores.add(CompletableFuture.completedFuture(scope.observer().changed(List.of(NativeSaveObserver.entity(scope.orb())))));
            if (scope.orb().isRemoved()) stores.add(CompletableFuture.completedFuture(scope.observer().entityRemoved(scope.orb())));
            var replacement = NativeSaveBarrier.combine(stores);
            scope.observer().handoff(scope.pending(), replacement, "pickup:" + scope.orb().getUUID() + ":" + scope.player().getUUID()
                    + ":" + scope.count() + "->" + after + ":" + scope.value());
        } catch (RuntimeException unavailable) { /* The suspended original remains unconfirmed. Gameplay is unchanged. */ }
        finally { if (scope.nativeScope() != null) scope.nativeScope().close(); }
    }
    public static Merge beforeMerge(ExperienceOrb survivor, ExperienceOrb donor) {
        var first = observer(survivor); var second = observer(donor); var observer = first == null ? second : first;
        if (observer == null || survivor == donor || survivor.level() != donor.level()
                || ACTIVE.containsKey(survivor) || ACTIVE.containsKey(donor)) return null;
        var survivorPending = observer.suspendEntity(survivor); var donorPending = observer.suspendEntity(donor);
        if (survivorPending.empty() && donorPending.empty()) return null;
        var scope = new Merge(survivor, donor, count(survivor), count(donor), survivor.getValue(), observer,
                survivorPending, donorPending, new Guard());
        scope.guard().failed = survivor.getClass() != ExperienceOrb.class || donor.getClass() != ExperienceOrb.class;
        ACTIVE.put(survivor, scope); ACTIVE.put(donor, scope); return scope;
    }
    public static void afterMerge(Merge scope) {
        if (scope == null) return; ACTIVE.remove(scope.survivor()); ACTIVE.remove(scope.donor());
        if (scope.guard().failed || scope.survivor().isRemoved() || !scope.donor().isRemoved() || scope.survivor().getValue() != scope.value()
                || scope.donor().getValue() != scope.value() || !validMerge(scope.survivorCount(), scope.donorCount(), count(scope.survivor()))) return;
        try {
            var survivor = scope.observer().changed(List.of(NativeSaveObserver.entity(scope.survivor())));
            var deletion = scope.observer().entityRemoved(scope.donor());
            var replacement = NativeSaveBarrier.combine(List.of(CompletableFuture.completedFuture(survivor), CompletableFuture.completedFuture(deletion)));
            TRACKED.put(scope.survivor(), true);
            String observation = "merge:" + scope.donor().getUUID() + "->" + scope.survivor().getUUID() + ":" + scope.donorCount() + ":" + scope.survivorCount();
            scope.observer().handoff(scope.survivorPending(), replacement, observation);
            scope.observer().handoff(scope.donorPending(), replacement, observation);
        } catch (RuntimeException unavailable) { /* Exact stores remain unconfirmed; never infer disappearance. */ }
    }
    public static Removal beforeRemoval(ExperienceOrb orb, net.minecraft.world.entity.Entity.RemovalReason reason) {
        if (orb.isRemoved() || !reason.shouldDestroy() || ACTIVE.containsKey(orb)) return null;
        var observer = observer(orb); if (observer == null) return null;
        var pending = observer.suspendEntity(orb); if (pending.empty()) return null;
        var scope = new Removal(orb, observer, pending, new Guard());
        ACTIVE.put(orb, scope); return scope;
    }
    public static void afterRemoval(Removal scope) {
        if (scope == null) return; ACTIVE.remove(scope.orb());
        if (scope.guard().failed || !scope.orb().isRemoved()) return;
        try {
            scope.observer().handoff(scope.pending(), scope.observer().entityRemoved(scope.orb()), "native-remove:" + scope.orb().getUUID());
        } catch (RuntimeException unavailable) { /* No fabricated refund or save acknowledgement. */ }
    }
    static boolean validPickup(int before, int after, boolean removed) {
        return before > 0 && after == before - 1 && removed == (after == 0);
    }
    static boolean validMerge(int survivor, int donor, int after) {
        return survivor > 0 && donor > 0 && (long) survivor + donor == after;
    }
    /** Forwarded by existing native mutation hooks; these methods never change world behavior. */
    public static void unadaptedNativeEffect() {
        synchronized (ACTIVE) {
            for (var scope : ACTIVE.values()) {
                if (scope instanceof Merge merge && onNativeThread(merge.survivor())) merge.guard().failed = true;
                if (scope instanceof Removal removal && onNativeThread(removal.orb())) removal.guard().failed = true;
            }
        }
    }
    public static void nativeBlockChanged(ServerLevel level) {
        if (!level.getServer().isSameThread()) return;
        synchronized (ACTIVE) {
            for (var scope : ACTIVE.values()) {
                if (scope instanceof Pickup pickup && pickup.nativeScope() != null && pickup.player().serverLevel() != level)
                    NativeConstructionSave.unadaptedSavedData();
                if (scope instanceof Merge merge && onNativeThread(merge.survivor())) merge.guard().failed = true;
                if (scope instanceof Removal removal && onNativeThread(removal.orb())) removal.guard().failed = true;
            }
        }
    }
    private static boolean onNativeThread(ExperienceOrb orb) {
        return orb.level() instanceof ServerLevel level && level.getServer().isSameThread();
    }
}
