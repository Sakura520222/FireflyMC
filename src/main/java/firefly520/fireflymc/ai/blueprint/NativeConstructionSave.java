package firefly520.fireflymc.ai.blueprint;

import firefly520.fireflymc.ai.material.NativeSaveBarrier;
import firefly520.fireflymc.ai.material.NativeSaveObserver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Native save coverage for the audited placement families, including deferred POI updates. */
public final class NativeConstructionSave implements AutoCloseable {
    private static final ThreadLocal<NativeConstructionSave> ACTIVE = new ThreadLocal<>();
    private static boolean listening;
    private final ServerPlayer owner;
    private final NativeSaveObserver saves;
    private final Set<BlockPos> changed = new LinkedHashSet<>();
    private final List<CompletableFuture<NativeSaveBarrier.Receipt>> poi = new ArrayList<>();
    private final Set<net.minecraft.world.entity.Entity> expectedJoins = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<net.minecraft.world.entity.Entity> observedJoins = Collections.newSetFromMap(new IdentityHashMap<>());
    private record LoadedStore(ServerLevel level, String id, net.minecraft.world.level.saveddata.SavedData store) {
        boolean current() {
            return ((firefly520.fireflymc.mixin.AiSavedDataCacheAccess) level.getDataStorage()).fireflymc$existingStores().get(id) == store;
        }
    }
    private final Map<Object, LoadedStore> allowedStores = new IdentityHashMap<>();
    private final Set<Object> changedStores = Collections.newSetFromMap(new IdentityHashMap<>());
    private final boolean entityOnly;
    private boolean finished;
    private String unsupportedEffect = "";
    private NativeConstructionSave(ServerPlayer owner) {
        this(owner, List.of(), false);
    }
    private NativeConstructionSave(ServerPlayer owner, Collection<? extends net.minecraft.world.entity.Entity> expected, boolean entityOnly) {
        if (!owner.server.isDedicatedServer() || !owner.server.isSameThread() || ACTIVE.get() != null)
            throw new IllegalStateException("NATIVE_BUILD_SAVE_SCOPE_INVALID");
        this.owner = owner; this.entityOnly = entityOnly;
        if (expected.size() > 128 || expected.stream().anyMatch(entity -> entity instanceof net.minecraft.world.entity.player.Player || entity.level() != owner.serverLevel()))
            throw new IllegalArgumentException("NATIVE_ENTITY_SAVE_SCOPE_INVALID");
        expectedJoins.addAll(expected);
        saves = NativeSaveObserver.forServer(owner.server); saves.requireCapacity();
        if (!listening) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, false, NativeConstructionSave::entityJoined);
            listening = true;
        }
        ACTIVE.set(this);
    }
    public static NativeConstructionSave begin(ServerPlayer owner) { return new NativeConstructionSave(owner); }
    public static NativeConstructionSave beginEntities(ServerPlayer owner, Collection<? extends net.minecraft.world.entity.Entity> expected) {
        return new NativeConstructionSave(owner, expected, true);
    }
    public static void blockChanged(ServerLevel level, BlockPos position) {
        firefly520.fireflymc.ai.processing.NativeExperienceAwardLifecycle.nativeBlockChanged(level);
        var scope = ACTIVE.get();
        if (scope != null) {
            if (scope.entityOnly) { scope.unsupportedEffect = "ENTITY_ADDITIONAL_BLOCK_EFFECT"; return; }
            if (scope.owner.serverLevel() == level) scope.changed.add(position.immutable());
            else scope.unsupportedEffect = "BUILD_ADDITIONAL_DIMENSION_EFFECT";
        }
    }
    public static void unadaptedSavedData() {
        firefly520.fireflymc.ai.processing.NativeExperienceAwardLifecycle.unadaptedNativeEffect();
        var scope = ACTIVE.get(); if (scope != null) scope.unsupportedEffect = "BUILD_ADDITIONAL_SAVED_DATA_EFFECT";
    }
    /** Trusted adapters declare an already-loaded native store before entering its audited callback. */
    public void allowLoadedSavedData(ServerLevel level, String id, net.minecraft.world.level.saveddata.SavedData store) {
        if (!owner.server.isSameThread() || ACTIVE.get() != this || finished || level.getServer() != owner.server)
            throw new IllegalStateException("NATIVE_BUILD_SAVE_SCOPE_INVALID");
        if (id == null || !id.matches("[a-z0-9_-]{1,96}") || store == null)
            throw new IllegalArgumentException("NATIVE_SAVED_DATA_ID_INVALID");
        var binding = new LoadedStore(level, id, store);
        if (!binding.current()) throw new IllegalStateException("NATIVE_SAVED_DATA_NOT_LOADED");
        var previous = allowedStores.get(store);
        if (previous != null && (previous.level() != level || !previous.id().equals(id)))
            throw new IllegalStateException("NATIVE_SAVED_DATA_TARGET_MOVED");
        if (previous == null && allowedStores.size() >= 8) throw new IllegalStateException("NATIVE_SAVED_DATA_SCOPE_LIMIT");
        allowedStores.put(store, binding);
    }
    public static void allowCurrentLoadedSavedData(ServerLevel canonicalLevel, String id,
                                                   net.minecraft.world.level.saveddata.SavedData store) {
        var scope = ACTIVE.get();
        if (scope == null) throw new IllegalStateException("NATIVE_BUILD_SAVE_SCOPE_REQUIRED");
        scope.allowLoadedSavedData(canonicalLevel, id, store);
    }
    /** The actual native sequence object may be shared by dimensions; find its existing canonical store. */
    public void allowRandomSequences(ServerLevel level) {
        if (level != owner.serverLevel() || !owner.server.isSameThread() || ACTIVE.get() != this || finished)
            throw new IllegalStateException("NATIVE_BUILD_SAVE_SCOPE_INVALID");
        var random = level.getRandomSequences();
        if (random.getClass() != net.minecraft.world.RandomSequences.class) throw new IllegalStateException("NATIVE_RANDOM_SEQUENCE_TYPE_UNSUPPORTED");
        ServerLevel canonical = null; int checked = 0;
        for (var candidate : owner.server.getAllLevels()) {
            if (++checked > 256) throw new IllegalStateException("NATIVE_RANDOM_SEQUENCE_STORE_LIMIT");
            var existing = ((firefly520.fireflymc.mixin.AiSavedDataCacheAccess) candidate.getDataStorage()).fireflymc$existingStores();
            if (existing.get("random_sequences") == random) {
                if (canonical != null && canonical != candidate) throw new IllegalStateException("NATIVE_RANDOM_SEQUENCE_STORE_AMBIGUOUS");
                canonical = candidate;
            }
        }
        if (canonical == null) throw new IllegalStateException("NATIVE_RANDOM_SEQUENCE_STORE_NOT_LOADED");
        allowLoadedSavedData(canonical, "random_sequences", random);
    }
    /** Observes identity only; it never creates, loads, dirties or saves a store. */
    public static void savedDataChanged(Object store) {
        firefly520.fireflymc.ai.processing.NativeExperienceAwardLifecycle.unadaptedNativeEffect();
        var scope = ACTIVE.get();
        if (scope == null) return;
        var binding = scope.allowedStores.get(store);
        if (binding == null || !binding.current()) scope.unsupportedEffect = "BUILD_ADDITIONAL_SAVED_DATA_EFFECT";
        else scope.changedStores.add(store);
    }
    private static void entityJoined(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        var scope = ACTIVE.get();
        if (scope != null && scope.expectedJoins.contains(event.getEntity())) { scope.observedJoins.add(event.getEntity()); return; }
        if (NativeKleeSlabScope.isExpectedCandidate(event.getEntity())) return;
        if (firefly520.fireflymc.ai.processing.NativeExperienceAward.isExpectedCandidate(event.getEntity())) return;
        if (firefly520.fireflymc.ai.integration.maid.MaidCombatScope.isExpectedCandidate(event.getEntity())) return;
        if (firefly520.fireflymc.ai.integration.trees.DynamicTreeNativeScope.isExpectedCandidate(event.getEntity())) return;
        unadaptedEntity();
    }
    public static void unadaptedEntity() {
        firefly520.fireflymc.ai.processing.NativeExperienceAwardLifecycle.unadaptedNativeEffect();
        var scope = ACTIVE.get();
        if (scope != null) scope.unsupportedEffect = "BUILD_ADDITIONAL_ENTITY_EFFECT";
    }
    public static void unadaptedRemoval() {
        firefly520.fireflymc.ai.processing.NativeExperienceAwardLifecycle.unadaptedNativeEffect();
        var scope = ACTIVE.get(); if (scope != null) scope.unsupportedEffect = "REMOVAL_ADDITIONAL_NATIVE_EFFECT";
    }
    public String unsupportedEffect() { return unsupportedEffect; }
    /** Actual immutable positions observed by this main-thread native effect scope. */
    public Set<BlockPos> changedPositions() {
        if (!owner.server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        return Set.copyOf(changed);
    }
    /** Execution evidence, separate from later disk-save evidence. */
    public CompletableFuture<Void> applied() { return CompletableFuture.allOf(poi.toArray(CompletableFuture[]::new)); }
    /** Called after vanilla has submitted its remove/add tasks, preserving their queue order. */
    public static void poiChanged(ServerLevel level, BlockPos position, BlockState previous, BlockState current) {
        var scope = ACTIVE.get(); if (scope == null || scope.owner.serverLevel() != level) return;
        var expected = PoiTypes.forState(current);
        if (Objects.equals(PoiTypes.forState(previous), expected)) return;
        var pos = position.immutable();
        var pending = new CompletableFuture<NativeSaveBarrier.Receipt>(); scope.poi.add(pending);
        level.getServer().execute(() -> {
            try {
                // A failed/deferred native update cannot be inferred from the block chunk alone.
                if (!level.getPoiManager().getType(pos).equals(expected)) throw new IllegalStateException("NATIVE_BUILD_POI_UPDATE_UNCONFIRMED");
                pending.complete(scope.saves.changed(List.of(NativeSaveObserver.poi(level, pos))));
            } catch (RuntimeException failure) { pending.completeExceptionally(failure); }
        });
    }
    public NativeSaveBarrier.Receipt finish() {
        if (finished || ACTIVE.get() != this) throw new IllegalStateException("NATIVE_BUILD_SAVE_SCOPE_CLOSED");
        if (!unsupportedEffect.isEmpty()) throw new IllegalStateException(unsupportedEffect);
        finished = true;
        var level = owner.serverLevel(); var targets = new ArrayList<NativeSaveObserver.Target>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var pos : changed) {
            var chunk = level.getChunkAt(pos);
            if (seen.add(chunk)) targets.add(NativeSaveObserver.chunk(level, chunk));
            var entity = level.getBlockEntity(pos);
            if (entity != null && seen.add(entity)) targets.add(NativeSaveObserver.blockEntity(level, entity));
        }
        for (var entity : observedJoins) {
            if (entity.isRemoved() || entity.level() != level || level.getEntity(entity.getUUID()) != entity)
                throw new IllegalStateException("NATIVE_ENTITY_CHANGED_BEFORE_SAVE_WITNESS");
            targets.add(NativeSaveObserver.entity(entity));
        }
        for (var store : changedStores) {
            var binding = allowedStores.get(store);
            if (binding == null || binding.level().getServer() != owner.server || !binding.current())
                throw new IllegalStateException("NATIVE_SAVED_DATA_CHANGED_BEFORE_SAVE_WITNESS");
            targets.add(NativeSaveObserver.Target.file(store,
                    NativeSaveObserver.dimensionFolder(binding.level()).resolve("data").resolve(binding.id() + ".dat")));
        }
        targets.add(NativeSaveObserver.player(owner));
        targets.add(NativeSaveObserver.Target.file(owner.getStats(), owner.server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(owner.getStringUUID() + ".json")));
        targets.add(NativeSaveObserver.Target.file(owner.getAdvancements(), owner.server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(owner.getStringUUID() + ".json")));
        var stores = new ArrayList<>(poi);
        stores.add(CompletableFuture.completedFuture(saves.changed(targets)));
        return NativeSaveBarrier.combine(stores);
    }
    @Override public void close() { if (ACTIVE.get() == this) ACTIVE.remove(); }
}
