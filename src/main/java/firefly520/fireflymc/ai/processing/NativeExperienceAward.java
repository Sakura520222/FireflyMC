package firefly520.fireflymc.ai.processing;

import firefly520.fireflymc.ai.material.NativeSaveBarrier;
import firefly520.fireflymc.ai.material.NativeSaveObserver;
import firefly520.fireflymc.ai.storage.AiJson;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.phys.Vec3;

/** Exact 1.21.1 award writes, including count increments on pre-existing orbs. Never scans the world. */
public final class NativeExperienceAward implements AutoCloseable {
    public record Observation(UUID entity, int beforeCount, int afterCount, int value, boolean spawned) {}
    private record Merge(ExperienceOrb orb, int count, int value) {}
    private static final ThreadLocal<NativeExperienceAward> ACTIVE = new ThreadLocal<>();
    private final ServerPlayer owner;
    private final int expected;
    private final IdentityHashMap<ExperienceOrb, Boolean> changed = new IdentityHashMap<>();
    private final List<Observation> observations = new ArrayList<>();
    private Merge merge;
    private ExperienceOrb candidate;
    private boolean failed, finished;
    private long actual;

    private NativeExperienceAward(ServerPlayer owner, int expected) {
        if (!owner.server.isDedicatedServer() || !owner.server.isSameThread() || ACTIVE.get() != null || expected < 1)
            throw new IllegalStateException("NATIVE_XP_SCOPE_INVALID");
        int remaining = expected, parts = 0;
        while (remaining > 0) {
            remaining -= ExperienceOrb.getExperienceValue(remaining);
            if (++parts > 128) throw new IllegalStateException("NATIVE_XP_AWARD_BUDGET");
        }
        this.owner = owner; this.expected = expected;
        NativeSaveObserver.forServer(owner.server).requireCapacity();
        ACTIVE.set(this);
    }
    public static NativeExperienceAward begin(ServerPlayer owner, int expected) { return new NativeExperienceAward(owner, expected); }
    public static void beforeMerge(ExperienceOrb orb, int count) {
        var scope = ACTIVE.get(); if (scope == null) return;
        if (scope.merge != null || orb.getClass() != ExperienceOrb.class || orb.level() != scope.owner.serverLevel() || orb.isRemoved() || count < 1
                || scope.owner.serverLevel().getEntity(orb.getUUID()) != orb) scope.failed = true;
        scope.merge = new Merge(orb, count, orb.getValue());
    }
    public static void afterMerge(ServerLevel level, int value, boolean merged) {
        var scope = ACTIVE.get(); if (scope == null) return;
        var before = scope.merge; scope.merge = null;
        if (!merged) { if (before != null) scope.failed = true; return; }
        if (before == null) { scope.failed = true; return; }
        var tag = new CompoundTag(); before.orb().addAdditionalSaveData(tag);
        int count = tag.getInt("Count");
        if (level != scope.owner.serverLevel() || before.value() != value || before.orb().getValue() != value
                || count != before.count() + 1 || tag.getShort("Age") != 0 || before.orb().isRemoved()) scope.failed = true;
        scope.changed.put(before.orb(), true);
        scope.observations.add(new Observation(before.orb().getUUID(), before.count(), count, value, false));
        scope.actual = Math.addExact(scope.actual, value);
    }
    /** The original add path, listeners, cancellation and return value remain authoritative. */
    public static boolean spawn(ServerLevel level, Entity entity, BooleanSupplier original) {
        var scope = ACTIVE.get(); if (scope == null) return original.getAsBoolean();
        if (scope.candidate != null || level != scope.owner.serverLevel() || entity.getClass() != ExperienceOrb.class) {
            scope.failed = true; return original.getAsBoolean();
        }
        scope.candidate = (ExperienceOrb) entity;
        int expectedValue = scope.candidate.getValue();
        try {
            boolean added = original.getAsBoolean();
            var orb = scope.candidate;
            var tag = new CompoundTag(); orb.addAdditionalSaveData(tag);
            int count = tag.getInt("Count"), value = orb.getValue();
            if (!added || level.getEntity(orb.getUUID()) != orb || orb.isRemoved() || count != 1 || value <= 0 || value != expectedValue) scope.failed = true;
            if (added) {
                scope.changed.put(orb, true);
                scope.observations.add(new Observation(orb.getUUID(), 0, count, value, true));
                scope.actual = Math.addExact(scope.actual, Math.multiplyExact((long) count, value));
            }
            return added;
        } finally { scope.candidate = null; }
    }
    public static boolean isExpectedCandidate(Entity entity) {
        var scope = ACTIVE.get(); return scope != null && scope.candidate == entity && !scope.failed;
    }
    public NativeSaveBarrier.Receipt finish() {
        if (finished || ACTIVE.get() != this) throw new IllegalStateException("NATIVE_XP_SCOPE_CLOSED");
        finished = true;
        if (failed || merge != null || candidate != null || actual != expected || changed.isEmpty())
            throw new IllegalStateException("NATIVE_XP_EFFECT_UNCONFIRMED");
        var receipt = NativeSaveObserver.forServer(owner.server).changedOrUnconfirmed(changed.keySet().stream().map(NativeSaveObserver::entity).toList());
        if (receipt == null) throw new IllegalStateException("NATIVE_XP_SAVE_UNCONFIRMED");
        NativeExperienceAwardLifecycle.enlist(changed.keySet());
        return receipt;
    }
    public String observations() { return AiJson.canonical(observations); }
    @Override public void close() { if (ACTIVE.get() == this) ACTIVE.remove(); }
}
