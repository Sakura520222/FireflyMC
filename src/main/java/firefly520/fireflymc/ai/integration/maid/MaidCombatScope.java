package firefly520.fireflymc.ai.integration.maid;

import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.processing.NativeExperienceAward;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** One real native hit: exact target damage, post-listener real ground loot, and actual XP awards. */
public final class MaidCombatScope implements AutoCloseable {
    public interface LootHook {}
    public interface ExperienceHook {}
    public interface RemovalHook {}
    public record Drop(UUID identity, MaterialStack stack) {}
    private static final ThreadLocal<MaidCombatScope> ACTIVE = new ThreadLocal<>();
    private static boolean listening;
    private final ServerPlayer owner;
    private final LivingEntity maid, target;
    private final MaidCombatPermission permissions;
    private final List<Drop> drops = new ArrayList<>();
    private final List<NativeSaveBarrier.Receipt> experience = new ArrayList<>();
    private final List<String> xpObservations = new ArrayList<>();
    private final Set<ItemEntity> groundLoot = Collections.newSetFromMap(new IdentityHashMap<>());
    private String failure = "";
    private boolean actualDamageAttempt;
    MaidCombatScope(ServerPlayer owner, LivingEntity maid, LivingEntity target, MaidCombatPermission permissions) {
        if (!owner.server.isDedicatedServer() || !owner.server.isSameThread() || ACTIVE.get() != null) throw new IllegalStateException("MAID_COMBAT_SCOPE_INVALID");
        this.owner = owner; this.maid = maid; this.target = target; this.permissions = permissions;
        if (!listening) { NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, MaidCombatScope::damage); listening = true; }
        ACTIVE.set(this);
    }
    private static void damage(LivingIncomingDamageEvent event) {
        var scope = ACTIVE.get(); if (scope == null) return;
        if (event.getEntity() == scope.maid && event.getSource().getEntity() == scope.target) return;
        if (event.getEntity() != scope.target) { scope.failure = "MAID_COMBAT_UNAPPROVED_DAMAGE_FOOTPRINT"; event.setCanceled(true); return; }
        try { scope.permissions.require(scope.owner, scope.maid, scope.target); scope.actualDamageAttempt = true; }
        catch (RuntimeException denied) { scope.failure = code(denied); event.setCanceled(true); }
    }
    /** Called after the complete native LivingDropsEvent dispatch, before vanilla spawns its collection. */
    public static void drops(LivingEntity entity, DamageSource source, Collection<ItemEntity> actual, boolean cancelled) {
        var scope = ACTIVE.get(); if (scope == null || cancelled) return;
        if (entity != scope.target || source == null || source.getEntity() != scope.maid) { scope.failure = "MAID_COMBAT_ADDITIONAL_ENTITY_DEATH"; return; }
        if (actual == null || actual.getClass() != ArrayList.class || actual.size() > 128 || !scope.drops.isEmpty()) {
            scope.failure = "MAID_COMBAT_LOOT_COLLECTION_UNSUPPORTED"; return;
        }
        var entities = new HashSet<UUID>(); var stacks = Collections.newSetFromMap(new IdentityHashMap<ItemStack, Boolean>());
        var copied = new ArrayList<Drop>();
        try {
            for (var drop : actual) {
                if (drop == null || drop.getClass() != ItemEntity.class || drop.isRemoved() || drop.level() != scope.owner.serverLevel()
                        || scope.owner.serverLevel().getEntity(drop.getUUID()) != null || !entities.add(drop.getUUID()) || !stacks.add(drop.getItem())
                        || drop.position().distanceToSqr(scope.target.position()) > 16) throw new IllegalStateException("MAID_COMBAT_LOOT_IDENTITY_UNCONFIRMED");
                var stack = ItemSnapshotCodec.capture(drop.getItem(), scope.owner.registryAccess());
                if (!stack.empty()) copied.add(new Drop(drop.getUUID(), stack));
            }
            scope.drops.addAll(copied);
            scope.groundLoot.addAll(actual);
        } catch (RuntimeException | LinkageError unverified) { scope.failure = code(unverified); }
    }
    /** Wraps the real native award amount; no synthetic XP or processing cost waiver is supplied. */
    public static void experience(ServerLevel level, Vec3 position, int amount, Runnable original) {
        var scope = ACTIVE.get();
        if (scope == null || amount <= 0) { original.run(); return; }
        if (level != scope.owner.serverLevel() || position.distanceToSqr(scope.target.position()) > 16) {
            scope.failure = "MAID_COMBAT_XP_OUTSIDE_TARGET"; original.run(); return;
        }
        NativeExperienceAward xp;
        try { xp = NativeExperienceAward.begin(scope.owner, amount); }
        catch (RuntimeException | LinkageError unavailable) { scope.failure = code(unavailable); original.run(); return; }
        try (xp) {
            original.run(); scope.experience.add(xp.finish());
        } catch (RuntimeException | LinkageError unknown) { scope.failure = code(unknown); throw unknown; }
        finally { scope.xpObservations.add(xp.observations()); }
    }
    List<Drop> loot() { return List.copyOf(drops); }
    List<NativeSaveBarrier.Receipt> experienceReceipts() { return List.copyOf(experience); }
    /** Only validated wrappers from this target's completed native drop event are accepted. */
    public static boolean isExpectedCandidate(net.minecraft.world.entity.Entity entity) {
        var scope = ACTIVE.get(); return scope != null && scope.groundLoot.contains(entity);
    }
    List<NativeSaveBarrier.Receipt> lootReceipts(NativeSaveObserver saves, UUID operation) {
        var targets = new ArrayList<NativeSaveObserver.Target>();
        for (var drop : groundLoot) {
            if (drop.isRemoved() || owner.serverLevel().getEntity(drop.getUUID()) != drop)
                throw new IllegalStateException("MAID_COMBAT_LOOT_SPAWN_UNCONFIRMED");
            var observed = drops.stream().filter(value -> value.identity().equals(drop.getUUID())).findFirst().orElseThrow();
            if (!ItemSnapshotCodec.capture(drop.getItem(), owner.registryAccess()).equals(observed.stack())) throw new IllegalStateException("MAID_COMBAT_LOOT_STATE_CHANGED_BEFORE_WITNESS");
            // A later pickup/merge/destruction is not a proof of the original ground stack.
            // Without a matching original serialization this stays pending for explicit review.
            targets.add(NativeSaveObserver.entity(drop));
            MaidCombatLootLifecycle.enlist(drop, owner.getUUID());
        }
        return targets.isEmpty() ? List.of() : List.of(saves.changed(targets));
    }
    List<String> experienceObservations() { return List.copyOf(xpObservations); }
    boolean attempted() { return actualDamageAttempt; }
    String failure() { return failure; }
    @Override public void close() { if (ACTIVE.get() == this) ACTIVE.remove(); }
    private static String code(Throwable error) { String message = error.getMessage(); return message != null && message.matches("[A-Z0-9_]{1,100}") ? message : "MAID_COMBAT_NATIVE_EFFECT_UNCONFIRMED"; }
}
