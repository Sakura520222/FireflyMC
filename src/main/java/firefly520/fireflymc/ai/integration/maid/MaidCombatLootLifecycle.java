package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.blueprint.NativeConstructionSave;
import firefly520.fireflymc.ai.material.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;

/** Follows real native reward stores through pickup and item merge. It never moves or grants anything. */
public final class MaidCombatLootLifecycle {
    public interface ItemHook {}
    public interface MaidHook {}
    private static final Map<ItemEntity, UUID> TRACKED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<Entity> ACTIVE = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    public record Pickup(Entity source, Entity recipient, MaterialStack item, int experienceCount, NativeSaveObserver saves,
            NativeSaveObserver.WriteSnapshot pending, NativeConstructionSave nativeScope) {}
    public record Merge(ItemEntity first, ItemEntity second, MaterialStack firstBefore, MaterialStack secondBefore, UUID owner,
            NativeSaveObserver saves, NativeSaveObserver.WriteSnapshot firstPending, NativeSaveObserver.WriteSnapshot secondPending,
            NativeConstructionSave nativeScope) {}
    private MaidCombatLootLifecycle() {}
    static void enlist(ItemEntity drop, UUID owner) { TRACKED.put(drop, owner); }
    private static NativeSaveObserver pending(Entity source) {
        if (!(source.level() instanceof ServerLevel level) || !level.getServer().isDedicatedServer() || !level.getServer().isSameThread()
                || source.isRemoved() || level.getEntity(source.getUUID()) != source || ACTIVE.contains(source)) return null;
        var saves = NativeSaveObserver.forServer(level.getServer()); return saves.pending(source) ? saves : null;
    }
    private static ServerPlayer nativeOwner(Entity recipient) {
        if (recipient instanceof ServerPlayer player) return player;
        if (recipient instanceof TamableAnimal tame && tame.getOwner() instanceof ServerPlayer player) return player;
        return null;
    }
    public static Pickup beforePickup(Entity source, Entity recipient) {
        NativeConstructionSave scope = null;
        NativeSaveObserver saves = null;
        try {
            saves = pending(source); if (saves == null) return null;
            if (recipient.level() != source.level() || source.getClass() != ItemEntity.class && source.getClass() != ExperienceOrb.class)
                return unobservedPickup(source, recipient, saves);
            var player = nativeOwner(recipient); if (player == null || player.level() != source.level()) return unobservedPickup(source, recipient, saves);
            MaterialStack item = source instanceof ItemEntity drop ? ItemSnapshotCodec.capture(drop.getItem(), ((ServerLevel) source.level()).registryAccess()) : MaterialStack.EMPTY;
            int count = source instanceof ExperienceOrb orb ? experienceCount(orb) : 0;
            // An existing AI scope owns nested callbacks. If it cannot be observed independently,
            // leave the original reward receipt pending instead of fabricating a receiver proof.
            scope = NativeConstructionSave.begin(player);
            var held = saves.suspendEntity(source); if (held.empty()) { scope.close(); return null; }
            ACTIVE.add(source); return new Pickup(source, recipient, item, count, saves, held, scope);
        } catch (RuntimeException | LinkageError unavailable) {
            if (scope != null) scope.close(); return saves == null ? null : unobservedPickup(source, recipient, saves);
        }
    }
    private static Pickup unobservedPickup(Entity source, Entity recipient, NativeSaveObserver saves) {
        try {
            var pending = saves.suspendEntity(source); if (pending.empty()) return null;
            ACTIVE.add(source); return new Pickup(source, recipient, MaterialStack.EMPTY, 0, saves, pending, null);
        } catch (RuntimeException unavailable) { return null; }
    }
    public static void afterPickup(Pickup scope) {
        if (scope == null) return;
        try {
            if (scope.nativeScope() == null) return;
            if (scope.source().level() != scope.recipient().level() || scope.recipient().isRemoved()) return;
            boolean changed;
            if (scope.source() instanceof ItemEntity drop) {
                var after = ItemSnapshotCodec.capture(drop.getItem(), ((ServerLevel) drop.level()).registryAccess());
                changed = drop.isRemoved() || !after.equals(scope.item());
                if (!drop.isRemoved() && !validRemainder(scope.item(), after)) return;
            } else changed = scope.source().isRemoved() || experienceCount((ExperienceOrb) scope.source()) != scope.experienceCount();
            if (!changed) { if (scope.nativeScope().unsupportedEffect().isEmpty()) scope.saves().unchanged(scope.pending()); return; }
            var receipts = new ArrayList<CompletableFuture<NativeSaveBarrier.Receipt>>();
            receipts.add(CompletableFuture.completedFuture(scope.nativeScope().finish()));
            receipts.add(CompletableFuture.completedFuture(scope.saves().changed(List.of(scope.recipient() instanceof ServerPlayer player
                    ? NativeSaveObserver.player(player) : NativeSaveObserver.entity(scope.recipient())))));
            receipts.add(CompletableFuture.completedFuture(scope.source().isRemoved() ? scope.saves().entityRemoved(scope.source())
                    : scope.saves().changed(List.of(NativeSaveObserver.entity(scope.source())))));
            scope.saves().handoff(scope.pending(), NativeSaveBarrier.combine(receipts), "actual-native-reward-pickup:" + scope.source().getUUID() + ":" + scope.recipient().getUUID());
        } catch (RuntimeException | LinkageError unavailable) { /* Unknown callbacks retain their original suspended obligations. */ }
        finally { ACTIVE.remove(scope.source()); if (scope.nativeScope() != null) scope.nativeScope().close(); }
    }
    public static Merge beforeMerge(ItemEntity first, ItemEntity second) {
        NativeConstructionSave scope = null;
        try {
            if (first == second || first.getClass() != ItemEntity.class || second.getClass() != ItemEntity.class || first.level() != second.level()
                    || ACTIVE.contains(first) || ACTIVE.contains(second)) return null;
            var one = pending(first); var two = pending(second); var saves = one == null ? two : one; if (saves == null) return null;
            UUID owner = TRACKED.get(first); if (owner == null) owner = TRACKED.get(second); if (owner == null) return null;
            var level = (ServerLevel) first.level(); var player = level.getServer().getPlayerList().getPlayer(owner);
            if (player == null || player.level() != level) return null;
            var before = ItemSnapshotCodec.capture(first.getItem(), level.registryAccess()); var other = ItemSnapshotCodec.capture(second.getItem(), level.registryAccess());
            scope = NativeConstructionSave.begin(player); var firstHeld = saves.suspendEntity(first); var secondHeld = saves.suspendEntity(second);
            if (firstHeld.empty() && secondHeld.empty()) { scope.close(); return null; }
            ACTIVE.add(first); ACTIVE.add(second); return new Merge(first, second, before, other, owner, saves, firstHeld, secondHeld, scope);
        } catch (RuntimeException | LinkageError unavailable) { if (scope != null) scope.close(); return null; }
    }
    public static void afterMerge(Merge scope) {
        if (scope == null) return;
        try {
            var level = (ServerLevel) scope.first().level(); if (scope.second().level() != level) return;
            var first = scope.first().isRemoved() ? MaterialStack.EMPTY : ItemSnapshotCodec.capture(scope.first().getItem(), level.registryAccess());
            var second = scope.second().isRemoved() ? MaterialStack.EMPTY : ItemSnapshotCodec.capture(scope.second().getItem(), level.registryAccess());
            if (!scope.first().isRemoved() && !scope.second().isRemoved() && first.equals(scope.firstBefore()) && second.equals(scope.secondBefore())) {
                if (scope.nativeScope().unsupportedEffect().isEmpty()) { scope.saves().unchanged(scope.firstPending()); scope.saves().unchanged(scope.secondPending()); } return;
            }
            if (!validMerge(scope.firstBefore(), scope.secondBefore(), first, second)) return;
            var receipts = new ArrayList<CompletableFuture<NativeSaveBarrier.Receipt>>();
            receipts.add(CompletableFuture.completedFuture(scope.nativeScope().finish()));
            for (var entity : List.of(scope.first(), scope.second())) {
                receipts.add(CompletableFuture.completedFuture(entity.isRemoved() ? scope.saves().entityRemoved(entity) : scope.saves().changed(List.of(NativeSaveObserver.entity(entity)))));
                if (!entity.isRemoved()) TRACKED.put(entity, scope.owner());
            }
            var replacement = NativeSaveBarrier.combine(receipts); String observation = "actual-native-item-merge:" + scope.first().getUUID() + ":" + scope.second().getUUID();
            scope.saves().handoff(scope.firstPending(), replacement, observation); scope.saves().handoff(scope.secondPending(), replacement, observation);
        } catch (RuntimeException | LinkageError unavailable) { /* A disappearance alone never proves a conserved reward transfer. */ }
        finally { ACTIVE.remove(scope.first()); ACTIVE.remove(scope.second()); scope.nativeScope().close(); }
    }
    private static int experienceCount(ExperienceOrb orb) { var tag = new CompoundTag(); orb.addAdditionalSaveData(tag); return tag.getInt("Count"); }
    static boolean validRemainder(MaterialStack before, MaterialStack after) { return !before.empty() && after.count() >= 0 && after.count() <= before.count() && (after.empty() || before.withCount(after.count()).equals(after)); }
    static boolean validMerge(MaterialStack before, MaterialStack other, MaterialStack after, MaterialStack remainder) {
        if (before.empty() || other.empty() || !before.withCount(other.count()).equals(other)) return false;
        return before.count() <= 64 && other.count() <= 64 && after.count() <= 64 && remainder.count() <= 64
                && before.count() + other.count() == after.count() + remainder.count()
                && (after.empty() || before.withCount(after.count()).equals(after)) && (remainder.empty() || before.withCount(remainder.count()).equals(remainder));
    }
}
