package firefly520.fireflymc.ai.blueprint;

import com.google.gson.*;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.blueprint.ownership.*;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Approved native survival placement. IO journaling and finite main-thread steps have separate lifetimes. */
public final class ConstructionService implements AutoCloseable {
    private record Work<T>(TaskControl control, Callable<T> action, CompletableFuture<T> future) {}
    private record Layout(List<BuildJournal.Cell> cells, PlacementPose pose) {}
    private record Placed(NativePlacementKernel.Outcome nativeResult, BuildJournal.Observation evidence,
                          NativeSaveBarrier.Receipt save, CompletableFuture<Void> applied, OwnershipUpdate ownership) {}
    record OwnershipUpdate(BuildOwnershipEvent event, BuildOwnershipChain chain, boolean pending) {}
    private record OwnershipPrepared(BuildOwnershipChain chain, NativeOwnership.Scope scope, String reason) {}
    private record NativeCompletion(long generation, boolean saved) { }
    private static final class Live {
        final String owner, dimension;
        final BlueprintRepository.ReviewLease review;
        final WorldMutationTracker.Lease world;
        final long created = System.nanoTime();
        volatile boolean executing;
        volatile boolean completed;
        final java.util.concurrent.atomic.AtomicReference<NativeCompletion> nativeCompletion =
                new java.util.concurrent.atomic.AtomicReference<>(new NativeCompletion(0, false));
        UUID undoPlan;
        volatile BuildOwnershipChain ownership;
        Live(String owner, String dimension, BlueprintRepository.ReviewLease review, WorldMutationTracker.Lease world) {
            this.owner = owner; this.dimension = dimension; this.review = review; this.world = world;
        }
        void check() { review.check(); world.check(); }
    }
    private final MinecraftServer server;
    private final MaterialInventoryService inventory;
    private final MaterialLedger ledger;
    private final VirtualProcessingService processing;
    private final InstalledPermissionAdapters permissions;
    private final LongSupplier generation;
    private final boolean serverProxyGrant;
    private final UUID epoch = UUID.randomUUID();
    private final WorldMutationTracker mutations = new WorldMutationTracker();
    private final Map<UUID, Live> live = new ConcurrentHashMap<>();
    private final Map<UUID, String> invalidated = new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<Work<?>> queue = new ArrayBlockingQueue<>(128);
    private volatile boolean closed;
    private final RemovalService removal;
    private final FunctionalObjectBindings<net.minecraft.world.level.block.entity.BlockEntity> functionalObjects =
            new FunctionalObjectBindings<>(256, 8192);
    public ConstructionService(MinecraftServer server, MaterialInventoryService inventory, MaterialLedger ledger,
                               VirtualProcessingService processing, InstalledPermissionAdapters permissions,
                               LongSupplier generation, boolean serverProxyGrant) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_SERVER_REQUIRED");
        this.server = server; this.inventory = inventory; this.ledger = ledger; this.processing = processing;
        this.permissions = permissions; this.generation = generation; this.serverProxyGrant = serverProxyGrant;
        ConstructionWorldEvents.install(server, mutations);
        try {
            NativeOwnership.install(server, epoch);
            removal = new RemovalService(server, inventory, ledger, processing, permissions, generation, serverProxyGrant, this, mutations, epoch);
        } catch (RuntimeException failure) {
            NativeOwnership.remove(server, epoch); ConstructionWorldEvents.remove(server, mutations); throw failure;
        }
    }
    public void register(ToolRegistry tools) {
        removal.register(tools);
        tool(tools, "build_plan", "为本人固定蓝图版本核验远程生存施工计划：区域、原生朝向/多格物料、真实授权库存和逐格保护。只规划，不放置。支持空位/匹配干半台阶，以及在原有真实源水内保水的受审原版含水方块；新增/排出流体和其他专属副作用需专门适配。成功后用 build_execute 请求工作台批准。",
                "{\"blueprintId\":{\"type\":\"string\"},\"revision\":{\"type\":\"integer\",\"minimum\":1},\"blueprintHash\":{\"type\":\"string\"}}",
                List.of("blueprintId", "revision", "blueprintHash"), AgentTool.Risk.READ_ONLY, this::plan);
        tool(tools, "build_execute", "执行工作台明确批准的固定施工计划（planId/planHash）。独立虚拟施工代理权限、真实逐件扣料、原生放置、领地保护与服务器核验；不传送本人，不覆盖第三人新方块。重复操作不会重放，部分完成或不确定结果如实报告。",
                "{\"planId\":{\"type\":\"string\"},\"planHash\":{\"type\":\"string\"}}", List.of("planId", "planHash"), AgentTool.Risk.WORLD_CHANGE, this::execute);
        tool(tools, "build_status", "读取本人施工计划和已核验步骤；只报告真实日志，不改变世界或恢复不确定操作。",
                "{\"planId\":{\"type\":\"string\"},\"planHash\":{\"type\":\"string\"}}", List.of("planId", "planHash"), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> {
                    var plan = load(ctx.workspace(), ctx.actor(), UUID.fromString(args.get("planId").getAsString()), args.get("planHash").getAsString());
                    return result(true, "", "本人施工日志。", status(ctx, plan));
                });
        tool(tools, "build_verify", "重新只读核验本人固定施工计划的真实结构和功能条件。分别报告结构完成/部分完成及功能待激活/已核验/未知；真实机器保留燃料、能量和玩家使用规则，不自动补给或运行，不读取未获本计划原生身份绑定的容器库存。",
                "{\"planId\":{\"type\":\"string\"},\"planHash\":{\"type\":\"string\"}}", List.of("planId", "planHash"), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> {
                    var plan = load(ctx.workspace(), ctx.actor(), UUID.fromString(args.get("planId").getAsString()), args.get("planHash").getAsString());
                    return verificationResult(ctx, plan, verify(ctx, plan));
                });
    }
    public ConstructionPlan load(Workspace workspace, Actor actor, UUID id, String hash) throws Exception {
        return new ConstructionRepository(workspace).load(actor, id, hash);
    }
    public RemovalService removal() { return removal; }
    /** Privacy erasure only; this cache contains no executable plan or transferable inventory grant. */
    public void forgetFunctionalBindings(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner()); functionalObjects.forgetOwner(actor.owner());
    }
    private static FunctionalObjectBindings.Scope functionalScope(ConstructionPlan plan, long privacy) {
        return new FunctionalObjectBindings.Scope(plan.serverInstanceId(), plan.conversationId(), plan.owner(), privacy,
                plan.id(), plan.hash(), plan.dimension());
    }
    /** Recovery may inspect durable history only after the original native dispatcher has stopped. */
    public boolean idleForRecovery(UUID plan) {
        var value = live.get(plan); return value == null || !value.executing && value.undoPlan == null;
    }
    /** Revoke the old plan before a recovery approval changes its journals or releases remaining material. */
    public void retireForRecovery(Actor actor, UUID plan) {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        actor.requireAccess(actor.owner()); var value = live.get(plan);
        if (value != null && !value.owner.equals(actor.owner())) throw new SecurityException("BUILD_PLAN_OWNER_MISMATCH");
        if (!idleForRecovery(plan)) throw new IllegalStateException("BUILD_NATIVE_WORK_STILL_ACTIVE");
        if (invalidated.size() >= 256) invalidated.remove(invalidated.keySet().iterator().next());
        invalidated.put(plan, "BUILD_HISTORY_SETTLED_REPLAN_REQUIRED");
        if (value != null && live.remove(plan, value)) value.world.close();
    }
    <T> T inspect(TaskControl control, Callable<T> action) throws Exception { return onMain(control, action); }
    WorldMutationTracker.Lease reserveUndo(Actor actor, ConstructionPlan plan, UUID undo) {
        check(actor, plan); var lease = live.get(plan.id());
        if (!lease.nativeCompletion.get().saved()) throw new IllegalStateException("BUILD_NATIVE_SAVE_PENDING");
        if (lease.executing || lease.undoPlan != null) throw new IllegalStateException("BUILD_SOURCE_BUSY");
        lease.undoPlan = undo; return lease.world;
    }
    void checkUndo(Actor actor, ConstructionPlan plan, UUID undo) {
        var lease = live.get(plan.id());
        if (lease == null || !Objects.equals(lease.undoPlan, undo) || !lease.owner.equals(actor.owner()) || !plan.executionEpoch().equals(epoch)
                || plan.registryGeneration() != generation.getAsLong()) throw new IllegalStateException("UNDO_SOURCE_CHANGED");
        lease.check();
    }
    void releaseUndo(ConstructionPlan plan, UUID undo, boolean changed) {
        var lease = live.get(plan.id());
        if (lease != null && Objects.equals(lease.undoPlan, undo)) {
            lease.undoPlan = null;
            if (changed) { invalidated.put(plan.id(), "BUILD_HAS_BEEN_UNDONE"); release(plan.id()); }
        }
    }
    private BuildOwnershipChain ownership(ConstructionPlan plan) {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        var value = live.get(plan.id());
        if (value == null) throw new IllegalStateException("OWNERSHIP_SOURCE_NOT_LIVE");
        if (value.ownership == null) value.ownership = new BuildOwnershipChain(epoch, plan.serverInstanceId(), UUID.fromString(plan.owner()),
                plan.taskId(), plan.id(), plan.hash(), plan.dimension());
        return value.ownership;
    }
    /** Runtime cache only: never acquire the primary conversation's IO lock on the server thread. */
    void requireUndoOwnership(Actor actor, ConstructionPlan source, UUID undo, Collection<BlueprintDocument.Position> positions) {
        checkUndo(actor, source, undo);
        var chain = ownership(source); var keys = new LinkedHashSet<Long>(); positions.forEach(p -> keys.add(nativePos(p).asLong()));
        var player = player(actor, source.dimension());
        for (var token : chain.requireCurrent(keys)) NativeOwnership.require(player.serverLevel(), token);
    }
    OwnedUndo beginOwnedUndo(Actor actor, ConstructionPlan source, UUID undo, UUID action, UUID requestTask, long privacy,
            Map<BlockPos, BlockState> before, Map<BlockPos, BlockState> after) {
        checkUndo(actor, source, undo); var chain = ownership(source); var player = player(actor, source.dimension());
        var targetsBefore = new LinkedHashMap<BlockPos, BlockState>(); after.keySet().forEach(pos -> targetsBefore.put(pos, before.get(pos)));
        var permit = chain.prepareUndo(action, stateNames(targetsBefore), stateNames(after));
        for (var token : permit.tokens()) NativeOwnership.require(player.serverLevel(), token);
        return new OwnedUndo(source, requestTask, privacy, chain, permit, NativeOwnership.begin(player.serverLevel(), targetsBefore), player.serverLevel(), after);
    }
    final class OwnedUndo implements AutoCloseable {
        private final ConstructionPlan source;
        private final UUID requestTask;
        private final long privacy;
        private final BuildOwnershipChain chain;
        private final BuildOwnershipChain.Permit permit;
        private final NativeOwnership.Scope scope;
        private final ServerLevel level;
        private final Map<BlockPos, BlockState> expected;
        private boolean completed;
        private OwnedUndo(ConstructionPlan source, UUID requestTask, long privacy, BuildOwnershipChain chain, BuildOwnershipChain.Permit permit,
                NativeOwnership.Scope scope, ServerLevel level, Map<BlockPos, BlockState> expected) {
            this.source = source; this.requestTask = requestTask; this.privacy = privacy; this.chain = chain; this.permit = permit;
            this.scope = scope; this.level = level; this.expected = Map.copyOf(expected);
        }
        OwnershipUpdate observed(boolean success, String nativeReason) {
            List<OwnershipToken> tokens = List.of(), discarded = List.of(); String reason = ""; boolean pending = false;
            try {
                if (!success) throw new IllegalStateException(nativeReason == null || nativeReason.isEmpty() ? "OWNERSHIP_NATIVE_UNDO_NOT_COMPLETED" : nativeReason);
                tokens = scope.finish(chain.owner(permit.actionId()), expected);
                discarded = chain.completeUndo(permit, tokens); pending = true;
                for (var token : discarded) NativeOwnership.retire(level, token);
            } catch (RuntimeException | LinkageError unavailable) {
                reason = ownershipReason(unavailable); chain.fail(reason);
                discardOwnershipTokens(source, tokens);
            }
            completed = true;
            return new OwnershipUpdate(ownershipEvent(source, requestTask, permit.actionId(), "UNDO", privacy, tokens,
                    discarded.stream().map(OwnershipToken::position).toList(), reason), chain, pending);
        }
        @Override public void close() {
            scope.close();
            if (!completed) chain.fail("OWNERSHIP_NATIVE_UNDO_UNCONFIRMED");
        }
    }
    private OwnershipPrepared prepareOwnership(Actor actor, ConstructionPlan plan, Map<BlockPos, BlockState> before, Map<BlockPos, BlockState> targets) {
        var chain = ownership(plan); var level = player(actor, plan.dimension()).serverLevel();
        var changedBefore = new LinkedHashMap<BlockPos, BlockState>(); targets.keySet().forEach(pos -> changedBefore.put(pos, before.get(pos)));
        try {
            for (var token : chain.prePlacement(stateNames(changedBefore))) NativeOwnership.require(level, token);
            return new OwnershipPrepared(chain, NativeOwnership.begin(level, changedBefore), "");
        } catch (RuntimeException | LinkageError unavailable) {
            String reason = ownershipReason(unavailable); chain.fail(reason); return new OwnershipPrepared(chain, null, reason);
        }
    }
    private OwnershipUpdate placedOwnership(ConstructionPlan plan, ConstructionPlan.Placement step, long privacy, OwnershipPrepared prepared,
            Map<BlockPos, BlockState> before, Map<BlockPos, BlockState> targets, NativePlacementKernel.Outcome result) {
        List<OwnershipToken> tokens = List.of(); String reason = prepared.reason(); boolean pending = false;
        if (result.success() && prepared.scope() != null) try {
            tokens = prepared.scope().finish(prepared.chain().owner(step.operationId()), targets);
            var transitions = new LinkedHashMap<Long, OwnershipTransitions.Transition>();
            for (var entry : targets.entrySet()) if (!before.get(entry.getKey()).equals(entry.getValue())) transitions.put(entry.getKey().asLong(),
                    new OwnershipTransitions.Transition(NativeOwnership.describe(before.get(entry.getKey())), NativeOwnership.describe(entry.getValue())));
            prepared.chain().placement(step.operationId(), transitions, tokens); pending = true;
        } catch (RuntimeException | LinkageError unavailable) {
            reason = ownershipReason(unavailable); prepared.chain().fail(reason); discardOwnershipTokens(plan, tokens);
        }
        if (!result.success()) reason = "OWNERSHIP_NATIVE_PLACEMENT_NOT_SUCCEEDED";
        return new OwnershipUpdate(ownershipEvent(plan, plan.taskId(), step.operationId(), "PLACE", privacy, tokens, List.of(), reason), prepared.chain(), pending);
    }
    private static Map<Long, String> stateNames(Map<BlockPos, BlockState> states) {
        var result = new LinkedHashMap<Long, String>(); states.forEach((pos, state) -> result.put(pos.asLong(), NativeOwnership.describe(state))); return result;
    }
    private void discardOwnershipTokens(ConstructionPlan source, List<OwnershipToken> tokens) {
        var player = server.getPlayerList().getPlayer(UUID.fromString(source.owner())); if (player == null) return;
        for (var token : tokens) try { NativeOwnership.retire(player.serverLevel(), token); }
        catch (RuntimeException | LinkageError ignored) { /* Only metadata cleanup; actual world/material outcome stays recorded. */ }
    }
    private static BuildOwnershipEvent ownershipEvent(ConstructionPlan source, UUID requestTask, UUID action, String kind, long privacy,
            List<OwnershipToken> tokens, List<Long> retired, String reason) {
        return new BuildOwnershipEvent(1, source.executionEpoch(), source.serverInstanceId(), source.conversationId(), source.owner(), source.taskId(), source.id(),
                source.hash(), source.dimension(), requestTask, action, kind, privacy, tokens, retired, reason);
    }
    /** Independent optional metadata failure must never prevent actual material/world evidence from being filled. */
    void recordOwnership(AgentTool.Context ctx, OwnershipUpdate update, boolean realEvidenceRecorded) {
        if (update == null) return;
        boolean saved = false; var event = update.event();
        if (!realEvidenceRecorded && event.reason().isEmpty()) {
            event = event.withReason("OWNERSHIP_REAL_EVIDENCE_RECORDING_UNAVAILABLE");
            if (update.pending()) update.chain().fail(event.reason());
        }
        try { event.append(ctx.workspace(), ctx.actor()); saved = realEvidenceRecorded && event.reason().isEmpty(); }
        catch (Exception unavailable) {
            if (update.pending()) update.chain().fail("OWNERSHIP_JOURNAL_UNAVAILABLE");
            firefly520.fireflymc.FireflyMCMod.LOGGER.error("[AI4] Build ownership history unavailable ({})", unavailable.getClass().getSimpleName());
        } finally {
            if (update.pending()) try { update.chain().markRecorded(event.actionId(), saved); }
            catch (RuntimeException unavailable) {
                update.chain().fail("OWNERSHIP_CACHE_RECORDING_UNAVAILABLE");
                firefly520.fireflymc.FireflyMCMod.LOGGER.error("[AI4] Build ownership cache unavailable ({})", unavailable.getClass().getSimpleName());
            }
        }
    }
    private static String ownershipReason(Throwable failure) {
        String value = failure.getMessage(); return value != null && value.matches("[A-Za-z0-9_.:/-]{1,160}") ? value : failure.getClass().getSimpleName();
    }
    private AgentTool.Result plan(AgentTool.Context ctx, JsonObject args) throws Exception {
        if (ctx.taskId() == null) throw new IllegalStateException("BUILD_TASK_REQUIRED");
        var repository = new BlueprintRepository(ctx.workspace());
        var saved = repository.read(ctx.actor(), UUID.fromString(args.get("blueprintId").getAsString()),
                args.get("revision").getAsLong(), args.get("blueprintHash").getAsString());
        new BuildRegionProposalStore(ctx.workspace()).requireConfirmedBlueprint(ctx.actor(), saved.entry());
        new BuildRegionStore(ctx.workspace()).requireContains(ctx.actor(), saved.entry().dimension(), saved.document());
        String reviewStamp = repository.reviewStamp(ctx.actor(), saved.entry().id(), saved.entry().hash());
        var review = repository.reviewLease(ctx.actor(), saved.entry().id(), saved.entry().hash(), reviewStamp);
        var desired = new LinkedHashMap<BlueprintDocument.Position, BlueprintDocument.Block>();
        for (var block : saved.document().blocks()) {
            var pos = saved.document().origin().add(block.position()); desired.put(pos, new BlueprintDocument.Block(pos, block.state()));
        }
        if (desired.size() > 2048) throw new IllegalArgumentException("BUILD_SECTION_REQUIRED_MAX_2048_CELLS");
        var positions = new LinkedHashSet<>(desired.keySet());
        for (var pos : desired.keySet()) for (Direction direction : Direction.values()) positions.add(position(nativePos(pos).relative(direction)));
        if (positions.size() > 16384) throw new IllegalArgumentException("BUILD_SURVEY_LIMIT");
        UUID id = UUID.randomUUID(); long currentGeneration = generation.getAsLong();
        Live lease = onMain(ctx.control(), () -> {
            ServerPlayer player = player(ctx.actor(), saved.entry().dimension()); review.check();
            if (live.values().stream().filter(value -> value.owner.equals(ctx.actor().owner()) && !value.completed).count() >= 4)
                throw new IllegalStateException("BUILD_PENDING_PLAN_LIMIT");
            var value = new Live(ctx.actor().owner(), saved.entry().dimension(), review,
                    mutations.watch(id, player.serverLevel(), positions.stream().map(p -> nativePos(p).asLong()).toList()));
            live.put(id, value); return value;
        });
        boolean persisted = false;
        try {
            var observed = new LinkedHashMap<BlueprintDocument.Position, BlockState>(); var all = List.copyOf(positions);
            for (int first = 0; first < all.size(); first += 16) {
                int begin = first, end = Math.min(all.size(), first + 16);
                observed.putAll(onMain(ctx.control(), () -> {
                    ServerPlayer player = player(ctx.actor(), lease.dimension); lease.check();
                    var slice = new LinkedHashMap<BlueprintDocument.Position, BlockState>();
                    for (var pos : all.subList(begin, end)) {
                        BlockPos nativePos = nativePos(pos); readGuard(player, nativePos);
                        BlockState before = player.serverLevel().getBlockState(nativePos);
                        if (desired.containsKey(pos) && !before.equals(BlueprintBlockStateCodec.resolve(desired.get(pos).state()))) {
                            placeGuard(player, nativePos);
                            if (player.serverLevel().getBlockEntity(nativePos) != null) throw new IllegalStateException("BUILD_DATA_BLOCK_CONFLICT");
                        }
                        slice.put(pos, before);
                    }
                    return slice;
                }));
            }
            var groups = new ArrayList<PlacementMaterialResolver.Group>();
            for (var target : desired.values()) {
                var group = onMain(ctx.control(), () -> { lease.check(); return PlacementMaterialResolver.resolve(target, desired); });
                if (group.isPresent()) groups.add(group.get());
                else if (target.state().blockId().equals("minecraft:air") && !observed.get(target.position()).isAir())
                    throw new IllegalStateException("BUILD_DEMOLITION_REVIEW_REQUIRED");
            }
            groups.sort(Comparator.comparingInt((PlacementMaterialResolver.Group group) -> group.anchor().y())
                    .thenComparingInt(group -> group.anchor().x()).thenComparingInt(group -> group.anchor().z()));
            var scan = onMain(ctx.control(), () -> { player(ctx.actor(), lease.dimension); lease.check(); return inventory.scan(ctx.actor()); });
            var stock = ctx.control().await(scan, 30000).rows();
            var pools = new HashMap<>(ledger.availablePools(stock)); var used = new LinkedHashMap<String, Integer>();
            var sources = new LinkedHashMap<String, MaterialLedger.Stock>();
            var projected = new LinkedHashMap<>(observed); var steps = new ArrayList<ConstructionPlan.Placement>();
            for (var group : groups) {
                ctx.control().check(); lease.check();
                int count = onMain(ctx.control(), () -> { lease.check(); return PlacementMaterialResolver.requiredItems(group, observed); });
                if (count == 0) continue;
                for (int unit = 0; unit < count; unit++) {
                    MaterialLedger.Stock chosen = stock.stream().filter(row -> row.stack().itemJson().length() < 65536 && itemId(row.stack()).equals(group.itemId())
                                    && safePlacementItem(row.stack()) && row.extractableCount() > used.getOrDefault(row.key(), 0)
                                    && pools.getOrDefault(row.poolId(), 0L) >= row.unitsPerItem()).findFirst()
                            .orElseThrow(() -> new IllegalStateException("BUILD_MISSING_MATERIAL:" + group.itemId()));
                    sources.put(chosen.key(), chosen); used.merge(chosen.key(), 1, Math::addExact);
                    pools.compute(chosen.poolId(), (key, value) -> value - chosen.unitsPerItem());
                    final int pass = unit;
                    Layout layout = onMain(ctx.control(), () -> {
                    lease.check(); player(ctx.actor(), lease.dimension);
                    var targets = new LinkedHashMap<BlueprintDocument.Position, BlockState>();
                    for (var cell : group.targets()) {
                        var state = BlueprintBlockStateCodec.resolve(cell.state()); NativePlacementProfiles.require(state); targets.put(cell.position(), state);
                    }
                    if (group.kind() == PlacementMaterialResolver.Kind.DOUBLE_SLAB && count == 2 && pass == 0) {
                        var state = targets.get(group.anchor()); targets.put(group.anchor(), state.setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                    }
                    var footprint = new LinkedHashSet<>(targets.keySet());
                    for (var pos : targets.keySet()) for (Direction direction : Direction.values()) footprint.add(position(nativePos(pos).relative(direction)));
                    var cells = new ArrayList<BuildJournal.Cell>();
                    for (var pos : footprint) {
                        BlockState before = projected.get(pos), target = targets.get(pos);
                        if (before == null) throw new IllegalStateException("BUILD_FOOTPRINT_NOT_SURVEYED");
                        if (target != null) PlacementWaterPolicy.requireReachable(before, target);
                        if (target != null && !before.isAir() && !(before.getBlock() instanceof SlabBlock && before.is(target.getBlock())
                                && before.getValue(SlabBlock.TYPE) != SlabType.DOUBLE && target.getValue(SlabBlock.TYPE) == SlabType.DOUBLE)
                                && !PlacementWaterPolicy.preservesSource(before, target))
                            throw new IllegalStateException("BUILD_DEMOLITION_REVIEW_REQUIRED");
                        cells.add(new BuildJournal.Cell(pos, BlueprintBlockStateCodec.describe(before), target == null ? null : BlueprintBlockStateCodec.describe(target)));
                    }
                    BlockState target = targets.get(group.anchor());
                    var pose = PlacementPose.forState(target, projected.get(group.anchor()));
                    projected.putAll(targets); return new Layout(cells, pose);
                    });
                    var pose = layout.pose();
                    steps.add(new ConstructionPlan.Placement(UUID.randomUUID(), group.anchor(), layout.cells(), pose.face().name(),
                            pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch(), chosen.stack().withCount(1)));
                    if (steps.size() > 512) throw new IllegalStateException("BUILD_SECTION_REQUIRED_MAX_512_OPERATIONS");
                }
            }
            if (steps.isEmpty()) {
                var satisfied = new JsonObject(); satisfied.addProperty("blueprintId", saved.entry().id().toString());
                satisfied.addProperty("blueprintRevision", saved.entry().revision()); satisfied.addProperty("blueprintHash", saved.entry().hash());
                satisfied.addProperty("structureState", "STRUCTURE_COMPLETE"); satisfied.addProperty("verifiedTargets", desired.size());
                satisfied.addProperty("functionality", "UNVERIFIED"); satisfied.addProperty("functionalInventoryRead", false);
                satisfied.addProperty("placements", 0); satisfied.addProperty("consumedMaterialUnits", 0);
                return result(true, "STRUCTURE_ALREADY_SATISFIED_FUNCTION_UNVERIFIED",
                        "服务端当前蓝图结构已满足，无需消耗材料或施工；功能条件尚未核验，未读取既有容器库存，也未宣称机器可运行。", satisfied);
            }
            var cells = new ArrayList<BuildJournal.Cell>(); var rows = List.copyOf(observed.entrySet());
            for (int first = 0; first < rows.size(); first += 16) {
                int begin = first, end = Math.min(rows.size(), first + 16);
                cells.addAll(onMain(ctx.control(), () -> rows.subList(begin, end).stream().map(row -> new BuildJournal.Cell(row.getKey(),
                        BlueprintBlockStateCodec.describe(row.getValue()), desired.containsKey(row.getKey())
                                ? BlueprintBlockStateCodec.describe(BlueprintBlockStateCodec.resolve(desired.get(row.getKey()).state())) : null)).toList()));
            }
            var needs = used.entrySet().stream().map(row -> new MaterialLedger.Need(row.getKey(), row.getValue())).toList();
            var physical = List.copyOf(sources.values());
            var plan = new ConstructionPlan(1, id, ctx.workspace().metadata().serverInstanceId(), ctx.workspace().primaryConversationId(), ctx.actor().owner(),
                    ctx.taskId(), epoch, saved.entry().id(), saved.entry().revision(), saved.entry().hash(), reviewStamp, lease.dimension, currentGeneration,
                    cells, steps, physical, needs, AiJson.digest(AiJson.canonical(cells)), ConstructionPlan.materialDigest(physical, needs), "").signed();
            onMain(ctx.control(), () -> { check(ctx.actor(), plan); return true; });
            new ConstructionRepository(ctx.workspace()).save(ctx.actor(), plan); persisted = true;
            return result(true, "", "施工计划已固定；须在工作台审阅并批准 build_execute 后才扣料。", view(plan));
        } finally {
            if (!persisted) server.execute(() -> release(id));
        }
    }
    private AgentTool.Result execute(AgentTool.Context ctx, JsonObject args) throws Exception {
        ConstructionPlan plan = load(ctx.workspace(), ctx.actor(), UUID.fromString(args.get("planId").getAsString()), args.get("planHash").getAsString());
        if (!Objects.equals(plan.taskId(), ctx.taskId())) throw new SecurityException("BUILD_TASK_MISMATCH");
        var journal = new BuildJournal(ctx.workspace(), epoch); journal.recover(ctx.actor());
        var previous = ledger.find(ctx.actor(), plan.id());
        if (previous.isPresent() && previous.get().state() == MaterialLedger.State.COMPLETED && !previous.get().saved())
            return finishSavedConstruction(ctx, plan);
        if (previous.isPresent() && previous.get().state() != MaterialLedger.State.RESERVED && previous.get().state() != MaterialLedger.State.ESCROWED)
            return result(false, previous.get().uncertain() ? "RECOVERY_REQUIRED" : "BUILD_ALREADY_RECORDED", "此施工已有材料记录，未重复扣料或放置；请查看实际进度与托管。", status(ctx, plan));
        int completed = 0;
        var expected = new LinkedHashMap<BlueprintDocument.Position, BlueprintDocument.State>(); plan.initialWorld().forEach(cell -> expected.put(cell.position(), cell.before()));
        var records = new HashMap<UUID, BuildJournal.Step>();
        journal.list(ctx.actor()).stream().filter(step -> step.planId().equals(plan.id())).forEach(step -> records.put(step.operationId(), step));
        for (var step : plan.placements()) {
            var record = records.get(step.operationId());
            if (record == null) break;
            if (record.state() != BuildJournal.State.VERIFIED || !record.planHash().equals(plan.hash()) || !record.executionEpoch().equals(epoch)
                    || previous.isEmpty() || previous.get().worldDebits().stream().noneMatch(debit -> debit.stepId().equals(step.operationId())
                    && debit.count() == 1 && debit.receipt().equals(record.receipt())))
                return result(false, "BUILD_RECONCILIATION_OR_REPLAN_REQUIRED", "已有步骤不能安全重放，需核对实际变化。", status(ctx, plan));
            for (var cell : step.footprint()) if (cell.target() != null) expected.put(cell.position(), cell.target());
            completed++;
        }
        if (records.size() != completed) return result(false, "BUILD_NON_PREFIX_REQUIRES_RECONCILIATION", "施工记录不构成连续已核验前缀，已停止。", status(ctx, plan));
        awaitWorld(ctx, plan, plan.initialWorld().stream().map(cell -> new BuildJournal.Cell(cell.position(), expected.get(cell.position()), cell.target())).toList(), false);
        Live lease = live.get(plan.id()); lease.executing = true;
        MaterialLedger.Operation op = previous.isPresent() ? previous.get() : ledger.reserve(ctx.actor(), plan.taskId(), plan.id(), plan.hash(), plan.sources(), plan.needs());
        try {
            op = processing.extractReserved(ctx, op, () -> check(ctx.actor(), plan));
            if (op.uncertain()) return result(false, "RECOVERY_REQUIRED", "取料结果不确定，未开始施工。", status(ctx, plan));
            processing.awaitInputSaves(ctx, plan.id(), fresh -> {
                var resumed = new JsonObject(); resumed.addProperty("planId", plan.id().toString()); resumed.addProperty("planHash", plan.hash());
                return execute(fresh, resumed);
            });
            for (var input : op.escrow()) if (inventory.protectedByOwner(ctx.actor(), input)) throw new SecurityException("MATERIAL_PROTECTED_BY_OWNER");
            for (var step : plan.placements().subList(completed, plan.placements().size())) {
                ctx.control().check(); lease.check();
                op = ledger.get(ctx.actor(), plan.id()); int index = -1;
                for (int i = 0; i < op.escrow().size(); i++) if (!op.escrow().get(i).empty() && op.escrow().get(i).itemJson().equals(step.material().itemJson())) { index = i; break; }
                if (index < 0) throw new IllegalStateException("BUILD_ESCROW_MISSING");
                journal.prepare(ctx.actor(), step.operationId(), plan.taskId(), plan.id(), plan.hash(), plan.id(), index, plan.dimension(), step.footprint(), step.material());
                ledger.beginWorldUse(ctx.actor(), plan.id(), step.operationId(), index);
                lease.nativeCompletion.updateAndGet(previousSave -> new NativeCompletion(previousSave.generation() + 1, false));
                journal.begin(ctx.actor(), step.operationId());
                try {
                    long ownershipPrivacy = ctx.workspace().conversation().privacyEpoch();
                    var effect = processing.commitOwned(ctx, () -> place(ctx.actor(), plan, step, ownershipPrivacy),
                            accepted -> recordPlacement(ctx, plan, step, journal, accepted));
                    if (!effect.nativeResult().success()) throw new IllegalStateException(effect.nativeResult().uncertain() ? "RECOVERY_REQUIRED" : effect.nativeResult().code());
                    try { awaitApplied(effect.applied()); }
                    catch (Exception failedUpdate) {
                        journal.uncertain(ctx.actor(), step.operationId(), "BUILD_NATIVE_UPDATE_UNCONFIRMED");
                        ledger.markUncertain(ctx.actor(), plan.id(), "BUILD_NATIVE_UPDATE_UNCONFIRMED");
                        throw failedUpdate;
                    }
                    var recorded = ledger.get(ctx.actor(), plan.id());
                    if (recorded.uncertain()) throw new IllegalStateException("RECOVERY_REQUIRED");
                    String receipt = recorded.worldDebits().stream().filter(debit -> debit.stepId().equals(step.operationId())).findFirst().orElseThrow().receipt();
                    journal.verified(ctx.actor(), step.operationId(), effect.evidence().cells(), receipt);
                } catch (CommittedCall.Rejected rejected) {
                    var unchanged = step.footprint().stream().map(cell -> new BuildJournal.ObservedCell(cell.position(), cell.before())).toList();
                    journal.observed(ctx.actor(), step.operationId(), new BuildJournal.Observation(false, false, false, "NOT_DISPATCHED", step.material(), List.of(), unchanged));
                    ledger.rejectionEvidence(ctx.actor(), plan.id(), step.operationId().toString(), "world", "CANCELLED_BEFORE_NATIVE_PLACEMENT");
                    if (ledger.get(ctx.actor(), plan.id()).saved()) lease.nativeCompletion.updateAndGet(saved -> new NativeCompletion(saved.generation(), true));
                    if (rejected.getCause() instanceof TaskControl.Stopped stopped) throw stopped;
                    throw rejected;
                }
            }
            awaitWorld(ctx, plan, plan.initialWorld(), true);
            ledger.worldCompleted(ctx.actor(), plan.id());
            lease.completed = true;
            return finishSavedConstruction(ctx, plan);
        } catch (AgentTool.Deferred deferred) { throw deferred; }
        catch (Exception failure) {
            var saved = ledger.get(ctx.actor(), plan.id());
            if (saved.intent() != null) {
                ledger.markUncertain(ctx.actor(), plan.id(), "BUILD_INTERRUPTED_DURING_NATIVE_INTENT");
                for (var step : journal.list(ctx.actor())) if (step.planId().equals(plan.id()) && step.state() == BuildJournal.State.DISPATCHING)
                    journal.uncertain(ctx.actor(), step.operationId(), "BUILD_NATIVE_OUTCOME_PENDING");
            }
            if (failure instanceof TaskControl.Stopped)
                return result(false, ledger.get(ctx.actor(), plan.id()).uncertain() ? "RECOVERY_REQUIRED" : "PROCESSING_PAUSED",
                        "已停止后续施工；已接受步骤的真实结果和未用托管材料已持久记录。", status(ctx, plan));
            return result(false, ledger.get(ctx.actor(), plan.id()).uncertain() ? "RECOVERY_REQUIRED" : "PARTIALLY_BUILT",
                    "施工已停止：" + safe(failure) + "。已完成部分与未用托管材料均保留，未重放操作。", status(ctx, plan));
        } finally { lease.executing = false; }
    }
    /** Final resumption reads only this accepted operation; it never re-enters extraction or placement. */
    private AgentTool.Result finishSavedConstruction(AgentTool.Context ctx, ConstructionPlan plan) throws Exception {
        ctx.control().check(); ctx.actor().requireAccess(plan.owner());
        if (!Objects.equals(ctx.taskId(), plan.taskId())) throw new SecurityException("BUILD_TASK_MISMATCH");
        var op = ledger.get(ctx.actor(), plan.id());
        if (op.uncertain() || op.state() != MaterialLedger.State.COMPLETED || op.intent() != null)
            return result(false, "RECOVERY_REQUIRED", "原生施工尚不能收束；保留已接受事实和原材料记录，没有重放放置。", status(ctx, plan));
        NativeWorldCompletionSaves.await(ctx, plan.id(), List.of(plan.id()), id -> inventory.nativeSaves().awaitJournalled(id),
                fresh -> constructionSaved(fresh, plan),
                fresh -> ledger.markUncertain(fresh.actor(), plan.id(), "BUILD_FINAL_NATIVE_SAVE_PROOF_UNAVAILABLE"),
                fresh -> finishSavedConstruction(fresh, plan));
        var source = live.get(plan.id());
        if (source != null) source.nativeCompletion.updateAndGet(saved -> new NativeCompletion(saved.generation(), true));
        try { return verificationResult(ctx, plan, verify(ctx, plan)); }
        catch (Exception unavailable) {
            var data = status(ctx, plan); data.addProperty("structureState", "STRUCTURE_COMPLETE");
            data.addProperty("functionality", "UNVERIFIED"); data.addProperty("functionalVerificationReason", safe(unavailable));
            return result(false, "STRUCTURE_COMPLETE_FUNCTION_UNVERIFIED", "真实材料、原生放置和canonical保存均已确认；功能检查尚未完成，未宣称机器可运行。", data);
        }
    }
    private boolean constructionSaved(AgentTool.Context ctx, ConstructionPlan plan) {
        var op = ledger.get(ctx.actor(), plan.id());
        if (op.state() != MaterialLedger.State.COMPLETED || op.intent() != null || !op.saved() || !op.planHash().equals(plan.hash())
                || !op.taskId().equals(plan.taskId())) return false;
        var steps = new BuildJournal(ctx.workspace(), epoch).list(ctx.actor()).stream().filter(step -> step.planId().equals(plan.id())).toList();
        if (steps.size() != plan.placements().size()) return false;
        return plan.placements().stream().allMatch(placement -> steps.stream().anyMatch(step -> step.operationId().equals(placement.operationId())
                && step.state() == BuildJournal.State.VERIFIED && step.planHash().equals(plan.hash())
                && op.worldDebits().stream().anyMatch(debit -> debit.stepId().equals(step.operationId()) && debit.count() == 1 && debit.receipt().equals(step.receipt()))
                && op.nativeSaves().stream().anyMatch(save -> save.kind().equals("world") && save.witness().equals(step.operationId().toString())
                && !save.receipt().isEmpty())));
    }
    private MaterialLedger.Operation recordPlacement(AgentTool.Context ctx, ConstructionPlan plan, ConstructionPlan.Placement step, BuildJournal journal, Placed accepted) throws Exception {
        var outcome = accepted.nativeResult(); var observation = accepted.evidence(); Exception issue = null; MaterialLedger.Operation recorded = null;
        try { journal.observed(ctx.actor(), step.operationId(), observation); }
        catch (Exception unavailable) { issue = unavailable; }
        String receipt = AiJson.digest(epoch + ":" + plan.id() + ":" + step.operationId() + ":" + AiJson.canonical(observation));
        // Even failure of a separate conversation event cannot skip a known physical consumption.
        try { recorded = ledger.worldEvidence(ctx.actor(), plan.id(), step.operationId(), outcome.remaining(), !outcome.uncertain(), receipt); }
        catch (Exception unavailable) { if (issue == null) issue = unavailable; else issue.addSuppressed(unavailable); }
        try { inventory.nativeSaves().record(plan.id(), accepted.save(), proof -> {
            ledger.nativeSaved(ctx.actor(), plan.id(), "world", step.operationId().toString(), proof);
            var source = live.get(plan.id());
            if (source != null) {
                var expected = source.nativeCompletion.get();
                var current = ledger.get(ctx.actor(), plan.id());
                if (current.saved() && current.intent() == null && !current.uncertain())
                    source.nativeCompletion.compareAndSet(expected, new NativeCompletion(expected.generation(), true));
            }
        }); }
        catch (Exception unavailable) { if (issue == null) issue = unavailable; else issue.addSuppressed(unavailable); }
        recordOwnership(ctx, accepted.ownership(), issue == null);
        if (issue != null) throw issue;
        return recorded;
    }
    private Placed place(Actor actor, ConstructionPlan plan, ConstructionPlan.Placement step, long ownershipPrivacy) {
        ServerPlayer owner = check(actor, plan); var lease = live.get(plan.id());
        var before = new LinkedHashMap<BlockPos, BlockState>(); var targets = new LinkedHashMap<BlockPos, BlockState>();
        for (var cell : step.footprint()) {
            before.put(nativePos(cell.position()), BlueprintBlockStateCodec.resolve(cell.before()));
            if (cell.target() != null) targets.put(nativePos(cell.position()), BlueprintBlockStateCodec.resolve(cell.target()));
        }
        BlockPos anchor = nativePos(step.anchor());
        var hit = new BlockHitResult(new Vec3(anchor.getX() + step.hitX(), anchor.getY() + step.hitY(), anchor.getZ() + step.hitZ()),
                Direction.valueOf(step.face()), anchor, false);
        var preparedOwnership = prepareOwnership(actor, plan, before, targets);
        try (var ownershipScope = preparedOwnership.scope();
             var scope = mutations.own(lease.world, owner.serverLevel(), targets.keySet().stream().map(BlockPos::asLong).toList());
             var nativeSave = NativeConstructionSave.begin(owner)) {
            var outcome = NativePlacementKernel.place(owner, new NativePlacementKernel.Request(before, targets, hit, step.yaw(), step.pitch(), step.material()),
                    (player, pos) -> {
                        check(actor, plan); if (!before.containsKey(pos)) throw new SecurityException("BUILD_OUTSIDE_APPROVED_REGION");
                        if (targets.containsKey(pos)) placeGuard(player, pos); else readGuard(player, pos);
                    });
            if (scope.unexpected()) outcome = new NativePlacementKernel.Outcome(false, outcome.mutationEntered(), true, "BUILD_NEIGHBOR_EFFECT_UNCERTAIN",
                    outcome.remaining(), outcome.otherItems(), outcome.observed(), outcome.nativeFootprint());
            if (outcome.success()) {
                try {
                    // The proxy has non-persistent advancement/stat handlers. Credit the real owner exactly
                    // once inside this already-journaled native transaction, never by replaying the proxy.
                    var item = ItemSnapshotCodec.restore(step.material(), owner.registryAccess());
                    owner.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(item.getItem()));
                    net.minecraft.advancements.CriteriaTriggers.PLACED_BLOCK.trigger(owner, anchor, item);
                } catch (RuntimeException | LinkageError callbackFailure) {
                    outcome = new NativePlacementKernel.Outcome(false, true, true, "BUILD_OWNER_CALLBACK_UNCERTAIN",
                            outcome.remaining(), outcome.otherItems(), outcome.observed(), outcome.nativeFootprint());
                }
            }
            // Owner advancement/reward callbacks run in this same guarded transaction too.
            // A listener can catch the guard exception itself; re-check the recorded footprint.
            if (scope.unexpected()) outcome = new NativePlacementKernel.Outcome(false, outcome.mutationEntered(), true, "BUILD_NEIGHBOR_EFFECT_UNCERTAIN",
                    outcome.remaining(), outcome.otherItems(), outcome.observed(), outcome.nativeFootprint());
            if (!nativeSave.unsupportedEffect().isEmpty()) outcome = new NativePlacementKernel.Outcome(false, outcome.mutationEntered(), true, nativeSave.unsupportedEffect(),
                    outcome.remaining(), outcome.otherItems(), outcome.observed(), outcome.nativeFootprint());
            if (outcome.success()) {
                try { NativePlacementComponents.requireEmptyNativeContainers(owner.serverLevel(), targets,
                        ItemSnapshotCodec.restore(step.material(), owner.registryAccess())); }
                catch (RuntimeException | LinkageError unknown) {
                    outcome = new NativePlacementKernel.Outcome(false, true, true, "BUILD_DEFAULT_CONTAINER_CALLBACK_UNCERTAIN",
                            outcome.remaining(), outcome.otherItems(), outcome.observed(), outcome.nativeFootprint());
                }
            }
            if (outcome.success()) for (var target : targets.entrySet()) {
                var original = before.get(target.getKey()); var entity = owner.serverLevel().getBlockEntity(target.getKey());
                if (original != null && original.isAir() && entity != null && !entity.isRemoved())
                    functionalObjects.bind(functionalScope(plan, ownershipPrivacy), target.getKey().asLong(), entity);
            }
            var ownership = placedOwnership(plan, step, ownershipPrivacy, preparedOwnership, before, targets, outcome);
            NativeSaveBarrier.Receipt save = null;
            if (!outcome.uncertain() && outcome.mutationEntered()) {
                try { save = nativeSave.finish(); }
                catch (RuntimeException failure) {
                    firefly520.fireflymc.FireflyMCMod.LOGGER.error("[AI4] Construction native save observation unavailable ({})", failure.getClass().getSimpleName());
                }
            }
            return new Placed(outcome, observation(outcome), save, nativeSave.applied(), ownership);
        }
    }
    private static void awaitApplied(CompletableFuture<Void> applied) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); boolean interrupted = false;
        try {
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("BUILD_NATIVE_UPDATE_TIMEOUT");
                try { applied.get(remaining, TimeUnit.NANOSECONDS); return; }
                catch (InterruptedException stopped) { interrupted = true; } // Accepted native work still owns its evidence.
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private void awaitWorld(AgentTool.Context ctx, ConstructionPlan plan, List<BuildJournal.Cell> cells, boolean after) throws Exception {
        for (int first = 0; first < cells.size(); first += 16) {
            int begin = first, end = Math.min(cells.size(), first + 16);
            onMain(ctx.control(), () -> {
                ServerPlayer player = check(ctx.actor(), plan);
                for (var cell : cells.subList(begin, end)) {
                    BlockPos pos = nativePos(cell.position()); readGuard(player, pos);
                    var wanted = after && cell.target() != null ? cell.target() : cell.before();
                    if (!player.serverLevel().getBlockState(pos).equals(BlueprintBlockStateCodec.resolve(wanted)))
                        throw new IllegalStateException("BUILD_WORLD_CHANGED_REVIEW_REQUIRED");
                    if (cell.target() != null && !cell.before().equals(cell.target())) placeGuard(player, pos);
                }
                return true;
            });
        }
    }
    private ServerPlayer check(Actor actor, ConstructionPlan plan) {
        if (!plan.executionEpoch().equals(epoch)) throw new IllegalStateException("BUILD_RESTART_REVIEW_REQUIRED");
        if (generation.getAsLong() != plan.registryGeneration()) throw new IllegalStateException("BUILD_REGISTRY_RELOADED");
        Live lease = live.get(plan.id());
        if (lease == null || !lease.owner.equals(actor.owner())) throw new IllegalStateException(invalidated.getOrDefault(plan.id(), "BUILD_PREVIEW_EXPIRED"));
        if (lease.undoPlan != null) throw new IllegalStateException("BUILD_UNDO_REVIEW_ACTIVE");
        lease.check(); return player(actor, plan.dimension());
    }
    private ConstructionVerification verify(AgentTool.Context ctx, ConstructionPlan plan) throws Exception {
        ctx.actor().requireAccess(plan.owner());
        long verificationPrivacy = ctx.workspace().conversation().privacyEpoch();
        var functionalIdentity = functionalScope(plan, verificationPrivacy);
        var cells = plan.initialWorld().stream().filter(cell -> cell.target() != null).toList();
        var scope = new LinkedHashSet<BlockPos>(); plan.initialWorld().forEach(cell -> scope.add(nativePos(cell.position())));
        boolean scopeLimited = false;
        for (var cell : cells) if (cell.target().blockId().equals("minecraft:enchanting_table")) {
            BlockPos p = nativePos(cell.position());
            for (int x = -2; x <= 2; x++) for (int y = 0; y <= 1; y++) for (int z = -2; z <= 2; z++) {
                if (scope.size() < 16384) scope.add(p.offset(x, y, z)); else if (!scope.contains(p.offset(x, y, z))) scopeLimited = true;
            }
        }
        var witness = onMain(ctx.control(), () -> {
            var owner = player(ctx.actor(), plan.dimension());
            return mutations.watch(UUID.randomUUID(), owner.serverLevel(), scope.stream().map(BlockPos::asLong).toList());
        });
        int verified = 0, unobserved = 0; boolean truncated = scopeLimited;
        var differences = new ArrayList<ConstructionVerification.Difference>();
        var functions = new ArrayList<ConstructionVerification.Function>();
        try {
            for (int first = 0; first < cells.size(); first += 16) {
                var slice = cells.subList(first, Math.min(cells.size(), first + 16));
                var current = onMain(ctx.control(), () -> {
                    var owner = player(ctx.actor(), plan.dimension()); witness.check();
                    int matching = 0, unknown = 0;
                    var changes = new ArrayList<ConstructionVerification.Difference>();
                    var findings = new ArrayList<ConstructionVerification.Function>();
                    for (var cell : slice) {
                        var pos = nativePos(cell.position()); BlockState actual;
                        try { readGuard(owner, pos); actual = owner.serverLevel().getBlockState(pos); }
                        catch (RuntimeException unavailable) {
                            unknown++; changes.add(new ConstructionVerification.Difference(cell.position(), cell.target(), null, "WORLD_NOT_CURRENTLY_LOADED_OR_ACCESSIBLE")); continue;
                        }
                        var wanted = BlueprintBlockStateCodec.resolve(cell.target());
                        if (!ConstructionFunctionalVerifier.sameStructure(wanted, actual)) {
                            changes.add(new ConstructionVerification.Difference(cell.position(), cell.target(), BlueprintBlockStateCodec.describe(actual), "TARGET_STATE_MISMATCH")); continue;
                        }
                        matching++;
                        ConstructionFunctionalVerifier.inspect(owner, pos, actual,
                                functionalObjects.get(functionalIdentity, pos.asLong()), permissions, requested -> {
                                    if (!scope.contains(requested)) throw new IllegalStateException("BUILD_FUNCTION_READ_SCOPE_LIMIT");
                                    readGuard(owner, requested);
                                }).ifPresent(findings::add);
                    }
                    witness.check(); return new VerificationSlice(matching, unknown, changes, findings);
                });
                verified += current.verified(); unobserved += current.unobserved();
                for (var difference : current.differences()) if (differences.size() < 64) differences.add(difference); else truncated = true;
                for (var function : current.functions()) if (functions.size() < 128) functions.add(function); else truncated = true;
            }
            long tick = onMain(ctx.control(), () -> { player(ctx.actor(), plan.dimension()); witness.check(); return (long) server.getTickCount(); });
            var report = new ConstructionVerification(1, plan.id(), plan.hash(), plan.dimension(), generation.getAsLong(), System.currentTimeMillis(), tick,
                    cells.size(), verified, unobserved, differences, functions, truncated);
            if (ctx.workspace().conversation().privacyEpoch() != verificationPrivacy) throw new IllegalStateException("BUILD_PRIVACY_CHANGED");
            var data = AiJson.GSON.toJsonTree(report).getAsJsonObject(); data.addProperty("reportDigest", report.digest());
            ctx.workspace().conversation().append(List.of(new firefly520.fireflymc.ai.conversation.ConversationStore.Draft(ctx.taskId(), "build_verification", null, data, null)));
            return report;
        } finally { server.execute(witness::close); }
    }
    private record VerificationSlice(int verified, int unobserved, List<ConstructionVerification.Difference> differences,
                                     List<ConstructionVerification.Function> functions) { }
    private AgentTool.Result verificationResult(AgentTool.Context ctx, ConstructionPlan plan, ConstructionVerification report) {
        var data = status(ctx, plan); data.add("verification", AiJson.GSON.toJsonTree(report));
        data.addProperty("verificationDigest", report.digest()); data.addProperty("verificationIsHistorical", false);
        data.addProperty("structureState", report.structureState()); data.addProperty("functionality", report.functionality().name());
        var original = ledger.find(ctx.actor(), plan.id());
        if (original.isPresent() && (original.get().uncertain() || !original.get().saved()))
            return result(false, original.get().uncertain() ? "RECOVERY_REQUIRED" : "NATIVE_SAVE_PENDING",
                    "已记录当前结构与功能的现场观察；原施工的canonical保存尚未全部确认，不能据此宣称施工交付完成。", data);
        boolean complete = report.structureComplete() && Set.of(ConstructionVerification.FunctionalState.NOT_APPLICABLE,
                ConstructionVerification.FunctionalState.READY).contains(report.functionality());
        String code = complete ? "" : !report.structureComplete() ? report.structureState() : report.functionality() == ConstructionVerification.FunctionalState.WAITING_ACTIVATION
                ? "STRUCTURE_COMPLETE_FUNCTION_PENDING" : "STRUCTURE_COMPLETE_FUNCTION_UNVERIFIED";
        return result(complete, code, report.summary(), data);
    }
    private ServerPlayer player(Actor actor, String dimension) {
        ServerPlayer player = inventory.requirePlayer(actor);
        if (!player.serverLevel().dimension().location().toString().equals(dimension)) throw new IllegalStateException("BUILD_DIMENSION_CHANGED");
        if (player.hurtTime > 0) throw new IllegalStateException("BUILD_OWNER_UNDER_ATTACK");
        return player;
    }
    private static void readGuard(ServerPlayer owner, BlockPos pos) {
        var level = owner.serverLevel();
        if (!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos))
            throw new IllegalStateException("BUILD_REGION_UNLOADED_OR_OUTSIDE_WORLD");
    }
    private void placeGuard(ServerPlayer owner, BlockPos pos) {
        readGuard(owner, pos);
        if (ledger.recoveryBlocksWorld(owner.serverLevel().dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ()))
            throw new IllegalStateException("BUILD_WORLD_RECONCILIATION_REQUIRED");
        if (!owner.canInteractWithBlock(pos, 0) && !permissions.checkBooleanPermission(owner, "fireflymc.ai.remote_build", serverProxyGrant).allowed())
            throw new SecurityException("VIRTUAL_CONSTRUCTION_PERMISSION_REQUIRED");
        if (!owner.serverLevel().mayInteract(owner, pos) || !permissions.checkPlace(owner, owner.serverLevel(), pos).allowed())
            throw new SecurityException("BUILD_PROTECTED");
    }
    public String review(Workspace workspace, Actor actor, ConstructionPlan plan) throws Exception {
        awaitWorld(new AgentTool.Context(workspace, actor, new TaskControl(30000), plan.taskId()), plan, plan.initialWorld(), false);
        var text = new StringBuilder("远程生存施工审批\n本人位置不移动；虚拟代理仍受领地与原生放置事件保护。\n蓝图版本：")
                .append(plan.blueprintRevision()).append("\n维度：").append(plan.dimension()).append("\n原生放置次数：").append(plan.placements().size())
                .append("\n拆除/覆盖数据方块：0\n材料：\n");
        for (var need : plan.needs()) {
            var stock = plan.sources().stream().filter(row -> row.key().equals(need.stockKey())).findFirst().orElseThrow();
            text.append("• ").append(itemId(stock.stack())).append(" × ").append(need.count()).append("（").append(stock.sourceId()).append("）\n");
        }
        long preservedWater = plan.placements().stream().flatMap(step -> step.footprint().stream()).filter(cell -> cell.target() != null
                && "true".equals(cell.target().properties().get("waterlogged")) && cell.before().blockId().equals("minecraft:water")).count();
        if (preservedWater > 0) text.append("原位源水保留：").append(preservedWater).append(" 格；不新增流体、不扣水桶。含水及其原生流体刻没有通用安全撤销授权。\n");
        text.append("施工后只读核验结构及本计划实际新建、原生身份仍匹配的功能块；不自动授予库存取料权限，不补燃料/能量、不启动机器。真实机器仍按原生使用规则。\n");
        return text.append("\n操作区域：").append(bounds(plan)).append("\n世界或蓝图改变后审批失效；不确定结果先对账。\n计划摘要：").append(plan.hash()).toString();
    }
    public static JsonObject view(ConstructionPlan plan) {
        var data = new JsonObject(); data.addProperty("planId", plan.id().toString()); data.addProperty("planHash", plan.hash());
        data.addProperty("blueprintId", plan.blueprintId().toString()); data.addProperty("blueprintRevision", plan.blueprintRevision());
        data.addProperty("placements", plan.placements().size()); data.addProperty("dimension", plan.dimension()); data.addProperty("region", bounds(plan));
        data.add("materials", AiJson.GSON.toJsonTree(plan.needs().stream().map(need -> Map.of("item", itemId(plan.sources().stream()
                .filter(row -> row.key().equals(need.stockKey())).findFirst().orElseThrow().stack()), "count", need.count())).toList()));
        return data;
    }
    private JsonObject status(AgentTool.Context ctx, ConstructionPlan plan) {
        var data = view(plan); var steps = new BuildJournal(ctx.workspace(), epoch).list(ctx.actor()).stream().filter(step -> step.planId().equals(plan.id())).toList();
        var current = live.get(plan.id()); String ownershipReason = current == null || current.ownership == null ? "OWNERSHIP_NOT_RECORDED" : current.ownership.reason();
        data.addProperty("undoOwnershipAvailable", ownershipReason.isEmpty()); data.addProperty("ownershipReason", ownershipReason);
        data.addProperty("verifiedOperations", steps.stream().filter(step -> step.state() == BuildJournal.State.VERIFIED).count());
        data.addProperty("recoveryRequired", steps.stream().anyMatch(step -> step.state() == BuildJournal.State.RECOVERY_REQUIRED));
        ledger.find(ctx.actor(), plan.id()).ifPresent(op -> {
            data.addProperty("materialState", op.state().name()); data.addProperty("heldUnits", op.escrow().stream().mapToInt(MaterialStack::count).sum());
            data.addProperty("consumedUnits", op.worldDebits().stream().mapToInt(MaterialLedger.WorldDebit::count).sum());
            data.addProperty("nativeSaveConfirmed", op.saved());
            data.addProperty("pendingNativeSaves", op.nativeSaves().stream().filter(save -> save.receipt().isEmpty()).count());
        });
        ctx.workspace().conversation().getSnapshot(0).events().stream().filter(event -> event.kind().equals("build_verification")
                        && event.data().has("planId") && plan.id().toString().equals(event.data().get("planId").getAsString())
                        && plan.hash().equals(event.data().get("planHash").getAsString())).reduce((first, second) -> second).ifPresent(event -> {
            data.add("verification", event.data()); data.addProperty("verificationIsHistorical", true);
        });
        return data;
    }
    private static String bounds(ConstructionPlan plan) {
        var positions = plan.placements().stream().flatMap(step -> step.footprint().stream()).filter(cell -> cell.target() != null).map(BuildJournal.Cell::position).toList();
        return positions.stream().mapToInt(BlueprintDocument.Position::x).min().orElseThrow() + "," + positions.stream().mapToInt(BlueprintDocument.Position::y).min().orElseThrow()
                + "," + positions.stream().mapToInt(BlueprintDocument.Position::z).min().orElseThrow() + " → "
                + positions.stream().mapToInt(BlueprintDocument.Position::x).max().orElseThrow() + "," + positions.stream().mapToInt(BlueprintDocument.Position::y).max().orElseThrow()
                + "," + positions.stream().mapToInt(BlueprintDocument.Position::z).max().orElseThrow();
    }
    private static BuildJournal.Observation observation(NativePlacementKernel.Outcome outcome) {
        return new BuildJournal.Observation(outcome.success(), outcome.mutationEntered(), outcome.uncertain(), outcome.code(), outcome.remaining(), outcome.otherItems(),
                outcome.observed().entrySet().stream().map(row -> new BuildJournal.ObservedCell(position(row.getKey()), BlueprintBlockStateCodec.describe(row.getValue()))).toList());
    }
    private static boolean safePlacementItem(MaterialStack stack) {
        JsonObject data = AiJson.object(stack.itemJson(), 65536, 32);
        if (!data.has("components")) return true;
        var components = data.getAsJsonObject("components");
        return Collections.disjoint(components.keySet(), Set.of("minecraft:block_entity_data", "minecraft:container", "minecraft:custom_name", "minecraft:enchantments"));
    }
    private static String itemId(MaterialStack stack) { return AiJson.object(stack.itemJson(), 1_048_576, 32).get("id").getAsString(); }
    private static BlockPos nativePos(BlueprintDocument.Position pos) { return new BlockPos(pos.x(), pos.y(), pos.z()); }
    private static BlueprintDocument.Position position(BlockPos pos) { return new BlueprintDocument.Position(pos.getX(), pos.getY(), pos.getZ()); }
    private static String safe(Exception failure) { String value = failure.getMessage(); return value != null && value.matches("[A-Za-z0-9_.:/-]{1,160}") ? value : failure.getClass().getSimpleName(); }
    private static AgentTool.Result result(boolean success, String code, String summary, JsonObject data) {
        return new AgentTool.Result(success, code, summary, data, "server-construction-journal", "", false);
    }
    private <T> T onMain(TaskControl control, Callable<T> action) throws Exception {
        if (closed) throw new IllegalStateException("AI_STOPPING"); var future = new CompletableFuture<T>();
        if (!queue.offer(new Work<>(control, action, future))) throw new IllegalStateException("BUILD_QUEUE_FULL");
        return control.await(future, 10000);
    }
    public void tick() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        removal.tick();
        live.entrySet().removeIf(row -> {
            var value = row.getValue(); if (value.executing || value.undoPlan != null) return false;
            String reason = ""; try { value.check(); } catch (IllegalStateException changed) { reason = changed.getMessage(); }
            if (!reason.isEmpty() || System.nanoTime() - value.created > TimeUnit.MINUTES.toNanos(30)) {
                if (invalidated.size() >= 256) invalidated.remove(invalidated.keySet().iterator().next());
                invalidated.put(row.getKey(), reason.isEmpty() ? "BUILD_PREVIEW_EXPIRED" : reason);
                value.world.close(); return true;
            }
            return false;
        });
        long end = System.nanoTime() + (server.getAverageTickTimeNanos() > 45_000_000 ? 500_000 : 1_500_000);
        for (int i = 0; i < 2 && System.nanoTime() < end; i++) { var work = queue.poll(); if (work == null) break; run(work); }
    }
    private static <T> void run(Work<T> work) {
        if (work.future().isCancelled()) return;
        try { work.control().check(); work.future().complete(work.action().call()); } catch (Exception failure) { work.future().completeExceptionally(failure); }
    }
    private void release(UUID id) { var value = live.remove(id); if (value != null) value.world.close(); }
    @Override public void close() {
        removal.close();
        closed = true; Work<?> work; while ((work = queue.poll()) != null) work.future().completeExceptionally(new IllegalStateException("AI_STOPPING"));
        ConstructionWorldEvents.remove(server, mutations); mutations.close(); live.clear(); invalidated.clear();
        functionalObjects.clear();
        NativeOwnership.remove(server, epoch);
    }
    private void tool(ToolRegistry tools, String name, String description, String properties, List<String> required, AgentTool.Risk risk, DefinedTool.Action action) {
        var schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", JsonParser.parseString(properties));
        schema.add("required", AiJson.GSON.toJsonTree(required)); schema.addProperty("additionalProperties", false);
        tools.register(new DefinedTool(new AgentTool.Descriptor(name, description, schema, risk, "construction.own", 0, AgentTool.ExecutionThread.IO, 300000, true, ""),
                (ctx, args) -> { try { return action.run(ctx, args); } catch (AgentTool.Deferred deferred) { throw deferred; } catch (TaskControl.Stopped stopped) {
                    if (!risk.requiresApproval()) throw stopped;
                    return MaterialStopReceipt.capture(ledger, ctx, args, stopped);
                }
                    catch (Exception failure) { return AgentTool.Result.error(safe(failure), "施工操作未完成：" + safe(failure)); } }));
    }
}
