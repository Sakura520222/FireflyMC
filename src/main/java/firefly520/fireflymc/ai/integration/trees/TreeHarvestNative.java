package firefly520.fireflymc.ai.integration.trees;

import firefly520.fireflymc.ai.blueprint.*;
import firefly520.fireflymc.ai.material.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.*;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.*;
import net.neoforged.neoforge.common.util.TriState;

/** Once-only survival mining followed by the actual deferred DT callback and actual falling-tree ticks. */
public final class TreeHarvestNative {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    public enum State { PREPARED, MINING, READY, QUEUED, FALLING, COLLECTING, COMPLETED, WORLD_REMAINDER, CANCELLED, REJECTED, RECOVERY_REQUIRED }
    public record Drop(UUID entityId, MaterialStack stack, double x, double y, double z) { }
    public record Pickup(UUID entityId, MaterialStack before, MaterialStack remaining, int accepted, boolean removed) { }
    public record Cell(BlueprintDocument.Position position, BlueprintDocument.State state) { }
    public record RootTickRecord(UUID id, String type, BlueprintDocument.Position position, BlueprintDocument.State before,
                                 long dueGameTick, String priority, long order) { }
    public record Evidence(State state, String reason, int serverTick, double progress, MaterialStack hand, float exhaustion,
                           List<Cell> changed, List<UUID> fallingEntities, List<Drop> drops,
                           List<Pickup> pickups, List<RootTickRecord> scheduledRootTicks, List<UUID> completedRootTicks,
                           NativeSaveBarrier.Receipt save, CompletableFuture<Void> applied) {
        public Evidence { changed = List.copyOf(changed); fallingEntities = List.copyOf(fallingEntities); drops = List.copyOf(drops); pickups = List.copyOf(pickups);
            scheduledRootTicks = List.copyOf(scheduledRootTicks); completedRootTicks = List.copyOf(completedRootTicks); }
    }
    private final UUID operation;
    private final ServerPlayer owner;
    private final ServerLevel level;
    private final Object connection;
    private final int slot;
    private final BlockPos anchor;
    private final Direction face;
    private final DynamicTreeAccess.Survey survey;
    private final NativePlacementKernel.Guard guard;
    private final WorldMutationTracker tracker;
    private final WorldMutationTracker.Lease lease;
    private final Set<net.minecraft.world.level.ChunkPos> loadedEnvelope = new HashSet<>();
    private final MiningProgress progress = new MiningProgress();
    private final List<Entity> falling = new ArrayList<>();
    private final Set<Entity> removalObserved = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Drop> drops = new ArrayList<>();
    private final List<ItemEntity> items = new ArrayList<>();
    private final List<Pickup> pickups = new ArrayList<>();
    private final Map<BlockPos, BlockState> current = new LinkedHashMap<>();
    private MaterialStack hand;
    private Object deferred;
    private State state = State.PREPARED;
    private String reason = "";
    private int queuedTick, collectingTicks, lastTick = Integer.MIN_VALUE;
    private boolean nativeEntered;
    private final LinkedHashMap<UUID, DynamicTreeNativeScope.RootTick> rootPending = new LinkedHashMap<>();
    private final List<RootTickRecord> rootHistory = new ArrayList<>();
    private final List<UUID> completedRoots = new ArrayList<>();
    private volatile RootTickRecord nextRootIntent;

    public TreeHarvestNative(UUID operation, ServerPlayer owner, BlockPos anchor, Direction face, DynamicTreeAccess.Survey survey,
                             NativePlacementKernel.Guard guard, WorldMutationTracker.Lease lease) {
        DynamicTreeAccess.thread(owner); this.operation = operation; this.owner = owner; level = owner.serverLevel();
        connection = owner.connection; slot = owner.getInventory().selected; this.anchor = anchor.immutable(); this.face = face;
        this.survey = survey; this.guard = guard; this.lease = lease; tracker = ConstructionWorldEvents.tracker(owner.server);
        current.putAll(survey.footprint()); hand = ItemSnapshotCodec.capture(owner.getMainHandItem(), owner.registryAccess());
        for (BlockPos pos : survey.footprint().keySet()) {
            int x = pos.getX() >> 4, z = pos.getZ() >> 4;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) loadedEnvelope.add(new net.minecraft.world.level.ChunkPos(x + dx, z + dz));
        }
    }
    public State state() { return state; }
    public boolean nativeEntered() { return nativeEntered; }
    public RootTickRecord nextRootIntent() { return nextRootIntent; }
    public boolean terminal() { return Set.of(State.COMPLETED, State.WORLD_REMAINDER, State.CANCELLED, State.REJECTED, State.RECOVERY_REQUIRED).contains(state); }
    public Evidence step(boolean cancel) {
        DynamicTreeAccess.thread(owner);
        if (terminal()) throw new IllegalStateException("TREE_OPERATION_CLOSED");
        NativeSaveBarrier.Receipt receipt = null; CompletableFuture<Void> applied = CompletableFuture.completedFuture(null);
        var changed = new ArrayList<Cell>();
        try {
            preflight();
            if (!cancel && lastTick == owner.server.getTickCount()) throw new IllegalStateException("TREE_STEP_SAME_TICK");
            lastTick = owner.server.getTickCount();
            if (cancel && nativeEntered) { state = State.RECOVERY_REQUIRED; reason = "TREE_CANCEL_AFTER_NATIVE_EFFECT"; return evidence(changed, null, applied); }
            try (var writes = tracker.own(lease, level, survey.footprint().keySet().stream().map(BlockPos::asLong).toList());
                 var save = NativeConstructionSave.begin(owner);
                 var chains = NativeChainScope.begin(owner);
                 var scope = DynamicTreeNativeScope.begin(operation, owner, anchor, survey.footprint().get(anchor), survey.root(), current.get(survey.root()), survey.footprint().keySet(), guard)) {
                save.allowRandomSequences(level);
                State beforeState = state;
                MaterialStack beforeHand = hand; float beforeExhaustion = owner.getFoodData().getExhaustionLevel();
                var observer = NativeSaveObserver.forServer(owner.server);
                var suspended = new IdentityHashMap<Entity, NativeSaveObserver.WriteSnapshot>();
                if (state == State.FALLING || state == State.COLLECTING) {
                    for (Entity entity : falling) if (!entity.isRemoved()) suspended.put(entity, observer.suspendEntity(entity));
                    for (Entity entity : items) if (!entity.isRemoved()) suspended.put(entity, observer.suspendEntity(entity));
                }
                try {
                    if (cancel) {
                        deferred = null; state = State.CANCELLED; reason = "TREE_CANCELLED_BEFORE_NATIVE_FELLING";
                        CommonHooks.onLeftClickBlock(owner, anchor, face, Action.ABORT_DESTROY_BLOCK);
                    } else if (nextRootIntent != null) scheduledRootTick(nextRootIntent);
                    else if (state == State.PREPARED) start();
                    else if (state == State.MINING) mine();
                    else if (state == State.READY) queue(scope);
                    else if (state == State.QUEUED) fell();
                    else if (state == State.FALLING) fallingTick();
                    else if (state == State.COLLECTING) collect();
                } catch (ReflectiveOperationException | RuntimeException | LinkageError partial) {
                    // A before-impact denial can occur after the tree itself has moved. Preserve
                    // its actual partial state and ordinary native-save obligation; never turn
                    // a failed callback into permission to replay the deferred branch action.
                    LOGGER.warn("Native dynamic tree stage failed before completion; operation={}, stage={}", operation, beforeState, partial);
                    state = State.RECOVERY_REQUIRED; reason = safe(partial);
                }
                hand = ItemSnapshotCodec.capture(owner.getMainHandItem(), owner.registryAccess());
                acceptRootTicks(scope.rootTicks());
                if (!scope.failure().isEmpty()) { state = State.RECOVERY_REQUIRED; reason = scope.failure(); }
                var entities = new ArrayList<NativeSaveObserver.Target>();
                for (Entity entity : scope.joined()) {
                    if (entity.isRemoved() || entity.level() != level || level.getEntity(entity.getUUID()) != entity)
                        throw new IllegalStateException("TREE_NATIVE_ENTITY_JOIN_UNCONFIRMED");
                    if (entity.getClass().getName().equals(DynamicTreeAccess.FALLING_TREE)) {
                        if (!falling.contains(entity)) falling.add(entity);
                    } else if (entity instanceof ItemEntity item) {
                        items.add(item); drops.add(new Drop(item.getUUID(), ItemSnapshotCodec.capture(item.getItem(), owner.registryAccess()), item.getX(), item.getY(), item.getZ()));
                    }
                    entities.add(NativeSaveObserver.entity(entity));
                }
                for (Entity entity : suspended.keySet()) if (!entity.isRemoved()) entities.add(NativeSaveObserver.entity(entity));
                for (BlockPos pos : save.changedPositions()) {
                    var after = level.getBlockState(pos); current.put(pos, after);
                    changed.add(new Cell(position(pos), BlueprintBlockStateCodec.describe(after)));
                }
                if (Set.of(State.PREPARED, State.MINING, State.READY).contains(beforeState) && (!changed.isEmpty() || !scope.joined().isEmpty())) {
                    state = State.RECOVERY_REQUIRED; reason = "TREE_MINING_CALLBACK_UNAPPROVED_EFFECT";
                }
                if ((state == State.REJECTED || state == State.CANCELLED) && (!changed.isEmpty() || !scope.joined().isEmpty()
                        || !beforeHand.equals(hand) || beforeExhaustion != owner.getFoodData().getExhaustionLevel())) {
                    state = State.RECOVERY_REQUIRED; reason = "TREE_CANCEL_OR_REJECTION_WITH_NATIVE_EFFECTS";
                }
                if (writes.unexpected() || !save.unsupportedEffect().isEmpty()) throw new IllegalStateException("TREE_ADDITIONAL_NATIVE_EFFECT");
                if (state == State.QUEUED && nativeEntered && falling.stream().noneMatch(entity -> !entity.isRemoved()))
                    throw new IllegalStateException("TREE_NATIVE_FALLING_ENTITY_MISSING");
                if (state == State.QUEUED && nativeEntered) state = State.FALLING;
                if (state == State.FALLING && rootPending.isEmpty() && falling.stream().allMatch(Entity::isRemoved)) {
                    if (level.getBlockState(anchor).equals(survey.footprint().get(anchor))) throw new IllegalStateException("TREE_ANCHOR_NOT_HARVESTED");
                    state = items.isEmpty() ? State.COMPLETED : State.COLLECTING; reason = "TREE_NATIVE_DROPS_IN_WORLD";
                }
                // The final entity save includes marker removal. UUID-based memory quarantine still
                // blocks external ticks/pickup until its proof and the journal ACK are durable; on
                // restart an unfinished journal restores that quarantine from its exact native UUIDs.
                if (state == State.COMPLETED || state == State.WORLD_REMAINDER) for (ItemEntity item : items) if (!item.isRemoved()) {
                    if (!operation.toString().equals(item.getPersistentData().getString(DynamicTreeNativeScope.OPERATION_TAG)))
                        throw new IllegalStateException("TREE_OUTPUT_MARKER_CHANGED");
                    item.getPersistentData().remove(DynamicTreeNativeScope.OPERATION_TAG);
                }
                var receipts = new ArrayList<CompletableFuture<NativeSaveBarrier.Receipt>>();
                receipts.add(CompletableFuture.completedFuture(save.finish())); applied = save.applied();
                for (int first = 0; first < entities.size(); first += 128)
                    receipts.add(CompletableFuture.completedFuture(NativeSaveObserver.forServer(owner.server).changed(entities.subList(first, Math.min(entities.size(), first + 128)))));
                for (Entity entity : falling) if (entity.isRemoved() && removalObserved.add(entity))
                    receipts.add(CompletableFuture.completedFuture(NativeSaveObserver.forServer(owner.server).entityRemoved(entity)));
                for (Entity entity : items) if (entity.isRemoved() && removalObserved.add(entity))
                    receipts.add(CompletableFuture.completedFuture(NativeSaveObserver.forServer(owner.server).entityRemoved(entity)));
                receipt = combine(receipts);
                for (var entry : suspended.entrySet()) observer.handoff(entry.getValue(), receipt, "tree-stage:" + operation + ":" + lastTick + ":" + entry.getKey().getUUID());
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("Native dynamic tree preflight/save failed; operation={}, stage={}", operation, state, failure);
            state = State.RECOVERY_REQUIRED; reason = safe(failure);
        }
        return evidence(changed, receipt, applied);
    }
    private void start() {
        var event = CommonHooks.onLeftClickBlock(owner, anchor, face, Action.START_DESTROY_BLOCK);
        if (event.isCanceled()) { state = State.REJECTED; reason = "TREE_NATIVE_START_DENIED"; return; }
        preflight(); BlockState block = current.get(anchor);
        EnchantmentHelper.onHitBlock(level, owner.getMainHandItem(), owner, owner, EquipmentSlot.MAINHAND,
                Vec3.atCenterOf(anchor), block, broken -> owner.onEquippedItemBroken(broken, EquipmentSlot.MAINHAND));
        hand = ItemSnapshotCodec.capture(owner.getMainHandItem(), owner.registryAccess()); preflight();
        if (event.getUseBlock() != TriState.FALSE) block.attack(level, anchor, owner);
        mine();
    }
    private void acceptRootTicks(List<DynamicTreeNativeScope.RootTick> accepted) {
        for (var root : accepted) {
            boolean duplicate = rootPending.values().stream().anyMatch(prior -> prior.tick().type() == root.tick().type() && prior.tick().pos().equals(root.tick().pos()));
            if (duplicate) continue; // Same native type/position first-wins rule across slices.
            if (rootHistory.size() >= 8) throw new IllegalStateException("TREE_ROOT_TICK_LIMIT");
            rootPending.put(root.id(), root);
            rootHistory.add(new RootTickRecord(root.id(), net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(root.tick().type()).toString(),
                    position(root.tick().pos()), BlueprintBlockStateCodec.describe(root.before()), root.tick().triggerTick(), root.tick().priority().name(), root.tick().subTickOrder()));
        }
        refreshRootIntent();
    }
    private void refreshRootIntent() {
        nextRootIntent = rootHistory.stream().filter(root -> rootPending.containsKey(root.id()))
                .min(Comparator.comparingLong(RootTickRecord::dueGameTick).thenComparingLong(RootTickRecord::order)).orElse(null);
    }
    private void scheduledRootTick(RootTickRecord approved) {
        var root = rootPending.get(approved.id());
        if (root == null || !root.tick().pos().equals(survey.root()) || !current.get(survey.root()).equals(root.before()))
            throw new IllegalStateException("TREE_ROOT_TICK_IDENTITY_CHANGED");
        if (level.getGameTime() < root.tick().triggerTick()) return; // Never execute before the original native due time.
        // ServerLevel forwards this long to DistanceManager.inBlockTickingRange: it is a
        // ChunkPos key, not BlockPos's unrelated packed XYZ representation.
        if (!level.shouldTickBlocksAt(new net.minecraft.world.level.ChunkPos(root.tick().pos()).toLong())) return;
        guard.check(owner, root.tick().pos());
        if (!level.getBlockState(root.tick().pos()).equals(root.before()) || !root.before().is(root.tick().type())
                || level.getBlockTicks().hasScheduledTick(root.tick().pos(), root.tick().type())
                || level.getBlockTicks().willTickThisTick(root.tick().pos(), root.tick().type()))
            throw new IllegalStateException("TREE_ROOT_TICK_WORLD_OR_SCHEDULER_CHANGED");
        // Retire before entering native code. A thrown/uncertain callback can never be replayed.
        rootPending.remove(root.id()); refreshRootIntent();
        root.before().tick(level, root.tick().pos(), level.random);
        completedRoots.add(root.id());
    }
    private void mine() {
        progress.advance(owner.server.getTickCount(), current.get(anchor).getDestroyProgress(owner, level, anchor));
        state = progress.complete() ? State.READY : State.MINING;
    }
    private void queue(DynamicTreeNativeScope scope) {
        var stop = CommonHooks.onLeftClickBlock(owner, anchor, face, Action.STOP_DESTROY_BLOCK);
        if (stop.isCanceled()) { state = State.REJECTED; reason = "TREE_NATIVE_STOP_DENIED"; return; }
        preflight(); progress.recheck(current.get(anchor).getDestroyProgress(owner, level, anchor));
        if (!progress.complete()) { state = State.MINING; return; }
        boolean returned = owner.gameMode.destroyBlock(anchor);
        var jobs = scope.deferred();
        if (!returned && jobs.isEmpty() && scope.failure().isEmpty()
                && ItemSnapshotCodec.capture(owner.getMainHandItem(), owner.registryAccess()).equals(hand)
                && current.entrySet().stream().allMatch(row -> level.getBlockState(row.getKey()).equals(row.getValue()))) {
            state = State.REJECTED; reason = "TREE_NATIVE_BREAK_DENIED"; return;
        }
        // NeoForge ServerPlayerGameMode.destroyBlock returns true after the normal break
        // pipeline even when removeBlock/onDestroyedByPlayer returns false. DT deliberately
        // leaves the branch and queues FutureBreak; only that exact captured job proves it.
        if (!returned || jobs.size() != 1 || !scope.failure().isEmpty() || !level.getBlockState(anchor).equals(current.get(anchor))) {
            LOGGER.warn("Unexpected native dynamic-tree handoff: pipelineAccepted={}, captured={}, scopeFailure={}, anchorUnchanged={}",
                    returned, jobs.size(), scope.failure(), level.getBlockState(anchor).equals(current.get(anchor)));
            throw new IllegalStateException(jobs.isEmpty() && !returned ? "TREE_NATIVE_BREAK_DENIED_OR_CHANGED" : "TREE_DEFERRED_HANDOFF_UNCERTAIN");
        }
        deferred = jobs.getFirst(); queuedTick = owner.server.getTickCount(); state = State.QUEUED;
    }
    private void fell() throws ReflectiveOperationException {
        if (deferred == null || owner.server.getTickCount() <= queuedTick) throw new IllegalStateException("TREE_DEFERRED_IDENTITY_OR_TICK_CHANGED");
        DynamicTreeAccess.requireSafeFallingSweep(owner, survey, anchor);
        Object accepted = deferred; deferred = null; nativeEntered = true;
        Class<?> branch = DynamicTreeAccess.type("block.branch.BranchBlock"); var mode = branch.getField("destroyMode"); Object previous = mode.get(null);
        try {
            Object block = current.get(anchor).getBlock();
            DynamicTreeAccess.invokeVirtual(branch, "futureBreak", void.class, block,
                    new Class<?>[]{BlockState.class, Level.class, BlockPos.class, LivingEntity.class},
                    accepted.getClass().getField("state").get(accepted), level, anchor, owner);
        } finally { mode.set(null, previous); }
    }
    private void fallingTick() throws ReflectiveOperationException {
        DynamicTreeAccess.requireSafeFallingSweep(owner, survey, anchor);
        for (Entity entity : falling) if (!entity.isRemoved()) {
            if (entity.level() != level || level.getEntity(entity.getUUID()) != entity || entity.isPassenger() || entity.isVehicle())
                throw new IllegalStateException("TREE_FALLING_ENTITY_CHANGED");
            if (entity.tickCount > 1200) throw new IllegalStateException("TREE_FALLING_TIMEOUT");
            level.tickNonPassenger(entity);
        }
        tickItems(false);
    }
    private void collect() {
        tickItems(true); collectingTicks++;
        if (items.stream().allMatch(Entity::isRemoved) || collectingTicks >= 40) {
            state = items.stream().allMatch(Entity::isRemoved) ? State.COMPLETED : State.WORLD_REMAINDER;
            reason = state == State.COMPLETED ? "TREE_NATIVE_DROPS_PICKED_UP" : "TREE_DROPS_REQUIRE_REAL_PICKUP";
        }
    }
    private void tickItems(boolean pickup) {
        for (ItemEntity item : items) {
            if (removalObserved.contains(item)) continue;
            if (item.isRemoved() || item.level() != level || level.getEntity(item.getUUID()) != item)
                throw new IllegalStateException("TREE_DROP_DISAPPEARED_WITHOUT_NATIVE_WITNESS");
            guard.check(owner, item.blockPosition());
            var before = ItemSnapshotCodec.capture(item.getItem(), owner.registryAccess());
            level.tickNonPassenger(item);
            if (item.isRemoved()) throw new IllegalStateException("TREE_DROP_DESTROYED_BEFORE_PICKUP");
            guard.check(owner, item.blockPosition());
            // Player.aiStep in this exact 1.21.1 runtime checks bbox.inflate(1,.5,1).
            // playerTouch itself does not enforce that distance, so direct calls must do so first.
            if (pickup && !item.hasPickUpDelay() && !owner.isPassenger()
                    && owner.getBoundingBox().inflate(1, .5, 1).intersects(item.getBoundingBox())) {
                item.playerTouch(owner);
                var after = item.isRemoved() ? MaterialStack.EMPTY : ItemSnapshotCodec.capture(item.getItem(), owner.registryAccess());
                int accepted = before.count() - after.count();
                if (accepted < 0 || accepted > before.count() || !after.empty() && !before.itemJson().equals(after.itemJson()))
                    throw new IllegalStateException("TREE_NATIVE_PICKUP_OUTCOME_UNCERTAIN");
                if (accepted > 0) pickups.add(new Pickup(item.getUUID(), before, after, accepted, item.isRemoved()));
            }
        }
    }
    private void preflight() {
        DynamicTreeAccess.thread(owner); DynamicTreeAccess.requireVersion(); lease.check();
        if (owner.isFakePlayer() || owner.gameMode.getGameModeForPlayer() != GameType.SURVIVAL || owner.getAbilities().instabuild
                || !owner.isAlive() || owner.connection != connection || owner.serverLevel() != level
                || owner.server.getPlayerList().getPlayer(owner.getUUID()) != owner) throw new SecurityException("TREE_SURVIVAL_OWNER_REQUIRED");
        if (owner.getInventory().selected != slot || !ItemSnapshotCodec.capture(owner.getMainHandItem(), owner.registryAccess()).equals(hand))
            throw new IllegalStateException("TREE_TOOL_CHANGED");
        for (var chunk : loadedEnvelope) if (!level.hasChunk(chunk.x, chunk.z)) throw new IllegalStateException("TREE_WORLD_UNLOADED");
        guard.check(owner, anchor);
        if (!nativeEntered && !level.getBlockState(anchor).equals(current.get(anchor))) throw new IllegalStateException("TREE_WORLD_CHANGED");
        // The one global lease witnesses all foreign edits, including edit-then-restore. Rechecking
        // every protection callback for 16K cells each mining tick would stall the server; exact
        // permissions are checked again by beforeChange immediately before every actual write.
        try { if (!survey.configuration().equals(DynamicTreeAccess.configuration())) throw new IllegalStateException("TREE_CONFIGURATION_CHANGED"); }
        catch (ReflectiveOperationException invalid) { throw new IllegalStateException("TREE_CONFIGURATION_UNAVAILABLE", invalid); }
        if (!nativeEntered) try {
            DynamicTreeAccess.requireRootSpecies(owner, survey);
            var rootBlock = current.get(survey.root()).getBlock();
            if (level.getBlockTicks().hasScheduledTick(survey.root(), rootBlock) || level.getBlockTicks().willTickThisTick(survey.root(), rootBlock))
                throw new IllegalStateException("TREE_EXISTING_ROOT_TICK_REVIEW_REQUIRED");
        }
        catch (ReflectiveOperationException invalid) { throw new IllegalStateException("TREE_ROOT_SPECIES_UNAVAILABLE", invalid); }
    }
    private Evidence evidence(List<Cell> changed, NativeSaveBarrier.Receipt receipt, CompletableFuture<Void> applied) {
        return new Evidence(state, reason, owner.server.getTickCount(), progress.value(), ItemSnapshotCodec.capture(owner.getMainHandItem(), owner.registryAccess()),
                owner.getFoodData().getExhaustionLevel(), changed, falling.stream().map(Entity::getUUID).toList(), drops, pickups, rootHistory, completedRoots, receipt, applied);
    }
    private static NativeSaveBarrier.Receipt combine(List<CompletableFuture<NativeSaveBarrier.Receipt>> receipts) {
        if (receipts.size() <= 128) return NativeSaveBarrier.combine(receipts);
        var groups = new ArrayList<CompletableFuture<NativeSaveBarrier.Receipt>>();
        for (int first = 0; first < receipts.size(); first += 128)
            groups.add(CompletableFuture.completedFuture(NativeSaveBarrier.combine(receipts.subList(first, Math.min(receipts.size(), first + 128)))));
        return combine(groups);
    }
    static String safe(Throwable error) {
        while (error instanceof InvocationTargetException invocation && invocation.getCause() != null) error = invocation.getCause();
        String message = error.getMessage(); return message != null && message.matches("[A-Z0-9_]{1,120}") ? message : "TREE_NATIVE_CALLBACK_UNCERTAIN";
    }
    static BlueprintDocument.Position position(BlockPos pos) { return new BlueprintDocument.Position(pos.getX(), pos.getY(), pos.getZ()); }
}
