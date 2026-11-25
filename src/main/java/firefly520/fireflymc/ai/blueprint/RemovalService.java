package firefly520.fireflymc.ai.blueprint;

import com.google.gson.*;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Reviewed survival demolition. Native stages own their result recording even when a waiter stops. */
public final class RemovalService implements AutoCloseable {
    private static final BlueprintDocument.State AIR = new BlueprintDocument.State("minecraft:air", Map.of());
    private static final class Live {
        final Workspace workspace;
        final long privacy, region, created = System.nanoTime();
        final WorldMutationTracker.Lease world;
        final String owner;
        final ConstructionPlan source;
        volatile boolean executing, completed;
        boolean changed;
        volatile RemovalPlan plan;
        MaterialStack hand;
        NativeRemovalKernel.Session session;
        UUID step;
        Live(Workspace workspace, WorldMutationTracker.Lease world, MaterialStack hand, ConstructionPlan source) {
            this.workspace = workspace; this.world = world; this.hand = hand; owner = workspace.owner();
            this.source = source;
            privacy = workspace.conversation().privacyEpoch(); region = workspace.conversation().regionSequence();
        }
        void check() {
            if (workspace.conversation().privacyEpoch() != privacy || workspace.conversation().regionSequence() != region)
                throw new IllegalStateException("REMOVAL_REVIEW_CHANGED");
            world.check();
        }
    }
    private record Effect(RemovalJournal.Evidence evidence, NativeSaveBarrier.Receipt save, CompletableFuture<Void> applied,
                          ConstructionService.OwnershipUpdate ownership) {}
    private final MinecraftServer server;
    private final MaterialInventoryService inventory;
    private final MaterialLedger ledger;
    private final VirtualProcessingService processing;
    private final InstalledPermissionAdapters permissions;
    private final LongSupplier generation;
    private final boolean remote;
    private final ConstructionService construction;
    private final WorldMutationTracker mutations;
    private final UUID epoch;
    private final Map<UUID, Live> live = new ConcurrentHashMap<>();

    RemovalService(MinecraftServer server, MaterialInventoryService inventory, MaterialLedger ledger, VirtualProcessingService processing,
                   InstalledPermissionAdapters permissions, LongSupplier generation, boolean remote, ConstructionService construction,
                   WorldMutationTracker mutations, UUID epoch) {
        this.server = server; this.inventory = inventory; this.ledger = ledger; this.processing = processing; this.permissions = permissions;
        this.generation = generation; this.remote = remote; this.construction = construction; this.mutations = mutations; this.epoch = epoch;
    }
    public void register(ToolRegistry tools) {
        tool(tools, "build_demolition_plan", "调查本人已确认选区内指定方块，生成固定拆除预览。positions 为绝对坐标；按真实工具、采集时间和原生掉落规则执行，不免费返还原建筑材料。门/高植物两格一并预览；含数据、流体及未适配邻接机制明确拒绝。",
                "{\"positions\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":512,\"items\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"minItems\":3,\"maxItems\":3}},\"slabPart\":{\"type\":\"string\",\"enum\":[\"whole\",\"upper\",\"lower\"],\"description\":\"默认 whole 拆整格；upper/lower 仅拆水平双层台阶对应半块，需本服原生 KleeSlabs 模式允许\"}}", List.of("positions"), AgentTool.Risk.READ_ONLY, this::plan);
        String fields = "{\"planId\":{\"type\":\"string\"},\"planHash\":{\"type\":\"string\"}}";
        tool(tools, "build_demolition_execute", "执行工作台批准的固定拆除计划；真实玩家工具、耗时、保护和原生掉落托管。远程须独立虚拟施工权限；暂停可续办，未知结果先对账，不能用此工具恢复不确定操作。",
                fields, List.of("planId", "planHash"), AgentTool.Risk.WORLD_CHANGE, (ctx, args) -> execute(ctx, args, RemovalJournal.Mode.DEMOLITION));
        tool(tools, "build_undo_plan", "为本人原施工计划生成安全撤销预览。planId/planHash 指原施工计划；仅接受仍有完整原生放置及材料凭据、未被他人改动的当前运行期步骤。撤销仍按真实工具和掉落规则，不保证原材料全额返还。",
                fields, List.of("planId", "planHash"), AgentTool.Risk.READ_ONLY, this::undoPlan);
        tool(tools, "build_undo_execute", "执行工作台批准的安全撤销计划；planId/planHash 指 build_undo_plan 返回的新计划。逐步核验原任务归属、原生采集、保护和掉落，不重放不确定操作。",
                fields, List.of("planId", "planHash"), AgentTool.Risk.WORLD_CHANGE, (ctx, args) -> execute(ctx, args, RemovalJournal.Mode.UNDO));
        tool(tools, "build_demolition_status", "查询本人拆除计划的真实进度、托管和恢复状态。", fields, List.of("planId", "planHash"), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> result(true, "", "本人拆除日志。", status(ctx, load(ctx.workspace(), ctx.actor(), args))));
    }
    public RemovalPlan load(Workspace workspace, Actor actor, JsonObject args) throws Exception {
        return new RemovalRepository(workspace).load(actor, UUID.fromString(args.get("planId").getAsString()), args.get("planHash").getAsString());
    }
    private AgentTool.Result plan(AgentTool.Context ctx, JsonObject args) throws Exception {
        if (ctx.taskId() == null) throw new IllegalStateException("REMOVAL_TASK_REQUIRED");
        var targets = new LinkedHashSet<BlueprintDocument.Position>();
        for (var value : args.getAsJsonArray("positions")) {
            var p = value.getAsJsonArray(); if (p.size() != 3) throw new IllegalArgumentException("REMOVAL_POSITION_INVALID");
            targets.add(new BlueprintDocument.Position(p.get(0).getAsBigDecimal().intValueExact(), p.get(1).getAsBigDecimal().intValueExact(), p.get(2).getAsBigDecimal().intValueExact()));
        }
        String part = args.has("slabPart") ? args.get("slabPart").getAsString() : "whole";
        if (!Set.of("whole", "upper", "lower").contains(part)) throw new IllegalArgumentException("REMOVAL_SLAB_PART_INVALID");
        return planTargets(ctx, targets, null, List.of(), part);
    }
    private AgentTool.Result undoPlan(AgentTool.Context ctx, JsonObject args) throws Exception {
        var source = construction.load(ctx.workspace(), ctx.actor(), UUID.fromString(args.get("planId").getAsString()), args.get("planHash").getAsString());
        var journal = new BuildJournal(ctx.workspace(), epoch); journal.recover(ctx.actor());
        var records = journal.list(ctx.actor()).stream().filter(s -> s.planId().equals(source.id())).toList();
        var material = ledger.get(ctx.actor(), source.id());
        if (records.isEmpty() || material.uncertain() || material.intent() != null || records.stream().anyMatch(s -> s.state() != BuildJournal.State.VERIFIED
                || !s.executionEpoch().equals(epoch) || !s.planHash().equals(source.hash()) || material.worldDebits().stream().noneMatch(d -> d.stepId().equals(s.operationId())
                && d.count() == 1 && d.receipt().equals(s.receipt())))) throw new IllegalStateException("UNDO_VERIFIED_SOURCE_REQUIRED");
        var targets = new LinkedHashSet<BlueprintDocument.Position>(); records.forEach(s -> s.cells().stream().filter(c -> c.target() != null).forEach(c -> targets.add(c.position())));
        if (source.initialWorld().stream().anyMatch(c -> targets.contains(c.position()) && !RemovalTransitions.air(c.before())
                && (c.target() == null || !RemovalTransitions.halfSlab(c.target(), c.before()))))
            throw new IllegalStateException("UNDO_ORIGINAL_NONAIR_RESTORATION_ADAPTER_REQUIRED");
        return planTargets(ctx, targets, source, records, "whole");
    }
    private AgentTool.Result planTargets(AgentTool.Context ctx, LinkedHashSet<BlueprintDocument.Position> targets, ConstructionPlan source, List<BuildJournal.Step> sourceSteps, String part) throws Exception {
        if (ctx.taskId() == null) throw new IllegalStateException("REMOVAL_TASK_REQUIRED");
        var region = new BuildRegionStore(ctx.workspace()).current(ctx.actor()).filter(BuildRegionStore.Selection::active).orElse(null);
        if (source == null && region == null) throw new IllegalStateException("REMOVAL_REGION_REQUIRED");
        if (region != null && !region.confirmed()) throw new IllegalStateException("REMOVAL_REGION_NOT_CONFIRMED");
        String dimension = source == null ? region.dimension() : source.dimension();
        if (region != null && !region.dimension().equals(dimension)) throw new IllegalStateException("REMOVAL_REGION_DIMENSION_CHANGED");
        if (targets.isEmpty() || targets.size() > 512) throw new IllegalArgumentException("REMOVAL_TARGET_LIMIT");
        for (var p : List.copyOf(targets)) {
            var other = construction.inspect(ctx.control(), () -> {
                var player = player(ctx.actor(), dimension); guard(player, p, true); var state = player.serverLevel().getBlockState(pos(p));
                if (state.getBlock().getClass() == DoorBlock.class || state.getBlock().getClass() == DoublePlantBlock.class)
                    return position(pos(p).relative(state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN));
                return p;
            }); targets.add(other);
        }
        if (targets.size() > 512 || region != null && targets.stream().anyMatch(p -> !region.bounds().contains(p))) throw new SecurityException("REMOVAL_OUTSIDE_SELECTED_REGION");
        var footprint = new LinkedHashSet<>(targets); for (var p : targets) for (Direction d : Direction.values()) footprint.add(position(pos(p).relative(d)));
        UUID id = UUID.randomUUID(); long currentGeneration = generation.getAsLong();
        var lease = construction.inspect(ctx.control(), () -> {
            var player = player(ctx.actor(), dimension);
            try (var chain = NativeChainScope.begin(player)) { /* Resolve the installed native chain contract before approving any removal. */ }
            if (live.values().stream().filter(v -> v.owner.equals(ctx.actor().owner()) && !v.completed).count() >= 4) throw new IllegalStateException("REMOVAL_PENDING_PLAN_LIMIT");
            var world = source == null ? mutations.watch(id, player.serverLevel(), footprint.stream().map(p -> pos(p).asLong()).toList()) : construction.reserveUndo(ctx.actor(), source, id);
            var value = new Live(ctx.workspace(), world, ItemSnapshotCodec.capture(player.getMainHandItem(), player.registryAccess()), source); live.put(id, value); return value;
        });
        boolean saved = false;
        try {
            var observed = new LinkedHashMap<BlueprintDocument.Position, BlueprintDocument.State>(); var all = List.copyOf(footprint);
            for (int begin = 0; begin < all.size(); begin += 16) {
                var slice = all.subList(begin, Math.min(begin + 16, all.size()));
                observed.putAll(construction.inspect(ctx.control(), () -> {
                    var player = player(ctx.actor(), dimension); lease.check(); var rows = new LinkedHashMap<BlueprintDocument.Position, BlueprintDocument.State>();
                    for (var p : slice) {
                        guard(player, p, targets.contains(p)); var state = player.serverLevel().getBlockState(pos(p));
                        requireProfile(player, p, state, targets.contains(p)); rows.put(p, BlueprintBlockStateCodec.describe(state));
                    }
                    return rows;
                }));
            }
            var sourcePredecessors = new HashMap<BlueprintDocument.Position, BlueprintDocument.State>();
            if (source != null) {
                var expected = new HashMap<BlueprintDocument.Position, BlueprintDocument.State>(); source.initialWorld().forEach(c -> expected.put(c.position(), c.before()));
                var verified = sourceSteps.stream().map(BuildJournal.Step::operationId).collect(java.util.stream.Collectors.toSet());
                for (var placement : source.placements()) if (verified.contains(placement.operationId())) placement.footprint().stream().filter(c -> c.target() != null).forEach(c -> {
                    expected.put(c.position(), c.target()); sourcePredecessors.put(c.position(), c.before());
                });
                if (observed.entrySet().stream().anyMatch(e -> !e.getValue().equals(expected.get(e.getKey())))) throw new IllegalStateException("UNDO_SOURCE_WORLD_CHANGED");
                construction.inspect(ctx.control(), () -> { construction.requireUndoOwnership(ctx.actor(), source, id, targets); return true; });
            }
            var finalTargets = new HashMap<BlueprintDocument.Position, BlueprintDocument.State>();
            if (source != null) source.initialWorld().stream().filter(c -> targets.contains(c.position())).forEach(c -> finalTargets.put(c.position(), c.before()));
            else for (var p : targets) finalTargets.put(p, part.equals("whole") ? AIR : construction.inspect(ctx.control(), () ->
                    NativeRemovalProfiles.remaining(player(ctx.actor(), dimension), pos(p), BlueprintBlockStateCodec.resolve(observed.get(p)),
                            part.equals("upper") ? net.minecraft.world.level.block.state.properties.SlabType.BOTTOM : net.minecraft.world.level.block.state.properties.SlabType.TOP)
                            .map(BlueprintBlockStateCodec::describe).orElseThrow(() -> new IllegalStateException("REMOVAL_PART_SELECTION_REQUIRES_ACTIVE_DOUBLE_SLAB"))));
            var ordered = targets.stream().sorted(Comparator.comparingInt(BlueprintDocument.Position::y).reversed()
                    .thenComparingInt(BlueprintDocument.Position::x).thenComparingInt(BlueprintDocument.Position::z)).toList();
            var projected = new LinkedHashMap<>(observed); var grouped = new HashSet<BlueprintDocument.Position>(); var steps = new ArrayList<RemovalPlan.Step>();
            for (var p : ordered) {
                if (grouped.contains(p) || projected.get(p).blockId().equals("minecraft:air")) continue;
                var group = construction.inspect(ctx.control(), () -> {
                    lease.check(); var state = BlueprintBlockStateCodec.resolve(projected.get(p)); var cells = new LinkedHashSet<BlueprintDocument.Position>(); cells.add(p);
                    if (state.getBlock().getClass() == DoorBlock.class || state.getBlock().getClass() == DoublePlantBlock.class) {
                        var other = position(pos(p).relative(state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER ? Direction.UP : Direction.DOWN));
                        var otherState = BlueprintBlockStateCodec.resolve(projected.get(other));
                        if (!otherState.is(state.getBlock()) || otherState.getValue(DoublePlantBlock.HALF) == state.getValue(DoublePlantBlock.HALF)) throw new IllegalStateException("REMOVAL_MULTIBLOCK_CHANGED");
                        cells.add(other);
                    }
                    return cells;
                });
                grouped.addAll(group); var local = new LinkedHashSet<>(group); for (var target : group) for (Direction d : Direction.values()) local.add(position(pos(target).relative(d)));
                var anchor = group.stream().min(Comparator.comparingInt(BlueprintDocument.Position::y)).orElseThrow();
                var partial = construction.inspect(ctx.control(), () -> {
                    lease.check();
                    if (group.size() == 1 && !RemovalTransitions.air(finalTargets.get(anchor))) {
                        NativeRemovalProfiles.require(player(ctx.actor(), dimension), pos(anchor), BlueprintBlockStateCodec.resolve(projected.get(anchor)), BlueprintBlockStateCodec.resolve(finalTargets.get(anchor)));
                        return Optional.<BlueprintDocument.State>empty();
                    }
                    if (group.size() != 1) return Optional.<BlueprintDocument.State>empty();
                    var predecessor = sourcePredecessors.get(anchor);
                    var keep = predecessor != null && RemovalTransitions.halfSlab(projected.get(anchor), predecessor)
                            && "top".equals(predecessor.properties().get("type")) ? net.minecraft.world.level.block.state.properties.SlabType.TOP
                            : net.minecraft.world.level.block.state.properties.SlabType.BOTTOM;
                    return NativeRemovalProfiles.remaining(player(ctx.actor(), dimension), pos(anchor), BlueprintBlockStateCodec.resolve(projected.get(anchor)), keep)
                            .map(BlueprintBlockStateCodec::describe);
                });
                if (partial.isPresent()) {
                    var first = local.stream().map(q -> new BuildJournal.Cell(q, projected.get(q), q.equals(anchor) ? partial.get() : null)).toList();
                    steps.add(new RemovalPlan.Step(UUID.randomUUID(), anchor, first, List.of())); projected.put(anchor, partial.get());
                }
                var cells = local.stream().map(q -> new BuildJournal.Cell(q, projected.get(q), group.contains(q) ? finalTargets.get(q) : null)).toList();
                var origins = sourceSteps.stream().filter(s -> s.cells().stream().anyMatch(c -> c.target() != null && group.contains(c.position()))).map(BuildJournal.Step::operationId).toList();
                steps.add(new RemovalPlan.Step(UUID.randomUUID(), anchor, cells, origins)); group.forEach(q -> projected.put(q, finalTargets.get(q)));
                if (steps.size() > 512) throw new IllegalStateException("REMOVAL_SECTION_REQUIRED_MAX_512_OPERATIONS");
            }
            if (steps.isEmpty()) return result(true, "", "指定位置均为空气，无需拆除。", new JsonObject());
            int handSlot = construction.inspect(ctx.control(), () -> { lease.check(); return player(ctx.actor(), dimension).getInventory().selected; });
            var plan = new RemovalPlan(1, id, ctx.workspace().metadata().serverInstanceId(), ctx.workspace().primaryConversationId(), ctx.actor().owner(), ctx.taskId(), epoch,
                    dimension, currentGeneration, source == null ? RemovalJournal.Mode.DEMOLITION : RemovalJournal.Mode.UNDO, source == null ? null : source.id(), source == null ? "" : source.hash(),
                    region == null ? 0 : region.revision(), region == null ? "" : region.hash(), lease.hand, handSlot,
                    observed.entrySet().stream().map(e -> new BuildJournal.Cell(e.getKey(), e.getValue(), finalTargets.get(e.getKey()))).toList(), steps, "").signed();
            construction.inspect(ctx.control(), () -> { check(ctx.actor(), plan); return true; });
            var currentRegion = new BuildRegionStore(ctx.workspace()).current(ctx.actor()).filter(BuildRegionStore.Selection::active).orElse(null);
            if (!Objects.equals(currentRegion, region)) throw new IllegalStateException("REMOVAL_REVIEW_CHANGED");
            new RemovalRepository(ctx.workspace()).save(ctx.actor(), plan); lease.plan = plan; saved = true;
            return result(true, "", "拆除计划已固定，须经工作台审阅批准后执行。", view(plan));
        } finally { if (!saved) server.execute(() -> release(id)); }
    }
    private AgentTool.Result execute(AgentTool.Context ctx, JsonObject args, RemovalJournal.Mode mode) throws Exception {
        var plan = load(ctx.workspace(), ctx.actor(), args); if (!plan.taskId().equals(ctx.taskId())) throw new SecurityException("REMOVAL_TASK_MISMATCH");
        if (plan.mode() != mode) throw new IllegalArgumentException("REMOVAL_MODE_MISMATCH");
        var journal = new RemovalJournal(ctx.workspace(), epoch); journal.recover(ctx.actor());
        var lease = construction.inspect(ctx.control(), () -> { check(ctx.actor(), plan); var value = live.get(plan.id());
            if (value.executing) throw new IllegalStateException("REMOVAL_ALREADY_RUNNING"); value.executing = true; return value; });
        try {
            for (var step : plan.steps()) {
                ctx.control().check(); lease.check();
                var previous = journal.list(ctx.actor()).stream().filter(row -> row.input().operationId().equals(step.id())).findFirst();
                if (previous.isPresent() && Set.of(RemovalJournal.State.REMOVED, RemovalJournal.State.ACCOUNTED).contains(previous.get().state())) {
                    if (!deliver(ctx, plan, journal, step)) return result(false, "WAITING_RESOURCES", "掉落物保留在托管中，输出空间不足；未开始下一格。", status(ctx, plan));
                    continue;
                }
                if (previous.isEmpty()) {
                    ledger.requireHarvestCapacity(ctx.actor());
                    var hand = construction.inspect(ctx.control(), () -> { check(ctx.actor(), plan); return lease.hand; });
                    journal.prepare(ctx.actor(), new RemovalJournal.Input(step.id(), plan.taskId(), plan.id(), plan.hash(), plan.dimension(), plan.mode(), step.sourceSteps(), step.cells(), hand));
                }
                var recorded = journal.get(ctx.actor(), step.id());
                if (recorded.state() == RemovalJournal.State.PREPARED) stage(ctx, plan, step, journal, RemovalJournal.Action.START);
                else if (!Set.of(RemovalJournal.State.MINING, RemovalJournal.State.READY).contains(recorded.state())) throw new IllegalStateException("REMOVAL_RECONCILIATION_OR_REPLAN_REQUIRED");
                while (true) {
                    ctx.control().check(); recorded = journal.get(ctx.actor(), step.id());
                    if (recorded.state() == RemovalJournal.State.READY) stage(ctx, plan, step, journal, RemovalJournal.Action.REMOVE);
                    else if (recorded.state() == RemovalJournal.State.MINING) {
                        try {
                            processing.commitOwned(ctx, () -> nativeStage(ctx.actor(), plan, step, null), effect -> {
                                if (effect.evidence().nativeState().equals("READY")) journal.ready(ctx.actor(), step.id(), effect.evidence());
                                else if (!effect.evidence().nativeState().equals("MINING")) ledger.quarantineRemoval(ctx.actor(), journal.uncertain(ctx.actor(), step.id(), "REMOVAL_PROGRESS_UNCERTAIN", effect.evidence()));
                                return null;
                            });
                        } catch (CommittedCall.Rejected rejected) {
                            if (rejected.getCause() instanceof Exception known) throw known;
                            throw rejected;
                        }
                    } else break;
                }
                recorded = journal.get(ctx.actor(), step.id());
                if (!Set.of(RemovalJournal.State.REMOVED, RemovalJournal.State.ACCOUNTED).contains(recorded.state()))
                    throw new IllegalStateException(recorded.state() == RemovalJournal.State.RECOVERY_REQUIRED ? "RECOVERY_REQUIRED" : "REMOVAL_NATIVE_REJECTED");
                if (!deliver(ctx, plan, journal, step)) return result(false, "WAITING_RESOURCES", "掉落物保留在托管中，输出空间不足；未开始下一格。", status(ctx, plan));
            }
            return finishSavedRemoval(ctx, plan);
        } catch (AgentTool.Deferred deferred) { throw deferred; }
        catch (Exception failure) {
            String stop = ctx.control().stopReason();
            if (stop == null && failure instanceof TaskControl.Stopped) stop = failure.getMessage();
            if (Set.of("CANCELLED", "DENIED", "TIMEOUT", "SHUTDOWN").contains(stop == null ? "" : stop)) {
                abort(ctx, plan, journal, lease); lease.completed = true;
            }
            return result(false, RemovalJournal.requiresRecovery(ctx.workspace(), plan.taskId()) ? "RECOVERY_REQUIRED" : "CANCELLED".equals(stop) ? "CANCELLED" : failure instanceof TaskControl.Stopped ? "PROCESSING_PAUSED" : safe(failure),
                    "已停止后续拆除，原生结果与托管保留；未重放已完成步骤。", status(ctx, plan));
        } finally { lease.executing = false; }
    }
    private void stage(AgentTool.Context ctx, RemovalPlan plan, RemovalPlan.Step step, RemovalJournal journal, RemovalJournal.Action action) throws Exception {
        var pending = journal.begin(ctx.actor(), step.id(), action);
        try {
            processing.commitOwned(ctx, () -> nativeStage(ctx.actor(), plan, step, action), effect -> {
                boolean realEvidenceRecorded = false;
                try {
                    // Deferred POI changes must be applied before this stage can count as observed success.
                    try { effect.applied().get(10, TimeUnit.SECONDS); }
                    catch (Exception failed) { ledger.quarantineRemoval(ctx.actor(), journal.uncertain(ctx.actor(), step.id(), "REMOVAL_NATIVE_UPDATE_UNCONFIRMED", effect.evidence())); throw failed; }
                    var recorded = journal.observed(ctx.actor(), step.id(), pending.attempt(), effect.evidence());
                    if (recorded.state() == RemovalJournal.State.RECOVERY_REQUIRED) ledger.quarantineRemoval(ctx.actor(), recorded);
                    MaterialLedger.Operation accounted = recorded.state() == RemovalJournal.State.REMOVED ? account(ctx, plan, journal, step) : null;
                    if (recorded.saves().stream().anyMatch(save -> save.attempt().equals(pending.attempt())))
                        inventory.nativeSaves().record(step.id(), effect.save(), proof -> {
                            var saved = journal.nativeSaved(ctx.actor(), step.id(), pending.attempt(), proof);
                            if (saved.saved() && saved.state() == RemovalJournal.State.ACCOUNTED) ledger.harvestSaved(ctx.actor(), saved);
                        });
                    realEvidenceRecorded = true;
                    return accounted;
                } finally { construction.recordOwnership(ctx, effect.ownership(), realEvidenceRecorded); }
            });
        } catch (CommittedCall.Rejected rejected) {
            journal.notDispatched(ctx.actor(), step.id(), pending.attempt());
            if (rejected.getCause() instanceof Exception known) throw known;
            throw rejected;
        } catch (Exception failure) {
            var latest = journal.get(ctx.actor(), step.id());
            if (latest.intent() != null) ledger.quarantineRemoval(ctx.actor(), journal.uncertain(ctx.actor(), step.id(), "REMOVAL_NATIVE_OUTCOME_PENDING"));
            throw failure;
        }
    }
    private Effect nativeStage(Actor actor, RemovalPlan plan, RemovalPlan.Step step, RemovalJournal.Action action) throws CommittedCall.Rejected {
        ServerPlayer player; Live lease; var before = new LinkedHashMap<BlockPos, BlockState>(); var targets = new LinkedHashSet<BlockPos>();
        var expected = new LinkedHashMap<BlockPos, BlockState>();
        ConstructionService.OwnedUndo preparedOwnership = null;
        try {
            player = check(actor, plan); lease = live.get(plan.id());
            inventory.nativeSaves().requireCapacity();
            for (var cell : step.cells()) {
                guard(player, cell.position(), cell.target() != null); var state = BlueprintBlockStateCodec.resolve(cell.before());
                if (!player.serverLevel().getBlockState(pos(cell.position())).equals(state)) throw new IllegalStateException("REMOVAL_WORLD_CHANGED");
                requireProfile(player, cell.position(), state, cell.target() != null);
                before.put(pos(cell.position()), state);
                if (cell.target() != null) {
                    var target = BlueprintBlockStateCodec.resolve(cell.target()); NativeRemovalProfiles.require(player, pos(cell.position()), state, target);
                    targets.add(pos(cell.position())); expected.put(pos(cell.position()), target);
                }
            }
            if (action == RemovalJournal.Action.REMOVE && plan.mode() == RemovalJournal.Mode.UNDO) {
                if (lease.source == null) throw new IllegalStateException("OWNERSHIP_UNDO_SOURCE_REQUIRED");
                preparedOwnership = construction.beginOwnedUndo(actor, lease.source, plan.id(), step.id(), plan.taskId(), lease.privacy, before, expected);
            }
        } catch (RuntimeException knownBeforeNativeStage) { throw new CommittedCall.Rejected(knownBeforeNativeStage); }
        var ownership = preparedOwnership;
        try (ownership; var world = mutations.own(lease.world, player.serverLevel(), action == RemovalJournal.Action.REMOVE ? targets.stream().map(BlockPos::asLong).toList() : List.of());
             var saves = NativeConstructionSave.begin(player); var loot = NativeLootEscrowScope.begin(player, targets)) {
            NativeRemovalKernel.Observation observation;
            if (action == RemovalJournal.Action.START) {
                if (lease.session != null) throw new IllegalStateException("REMOVAL_NATIVE_SESSION_ALREADY_EXISTS");
                lease.step = step.id(); lease.session = NativeRemovalKernel.start(player,
                        new NativeRemovalKernel.Request(before, expected, pos(step.anchor()), Direction.UP, lease.hand),
                        (owner, position) -> { check(actor, plan, false); if (!before.containsKey(position)) throw new SecurityException("REMOVAL_OUTSIDE_APPROVED_FOOTPRINT");
                            guard(owner, position(position), targets.contains(position)); });
                observation = lease.session.observation();
            } else {
                if (lease.session == null || !step.id().equals(lease.step)) throw new IllegalStateException("REMOVAL_SESSION_REVALIDATION_REQUIRED");
                observation = action == RemovalJournal.Action.REMOVE ? NativeRemovalKernel.remove(lease.session)
                        : action == RemovalJournal.Action.ABORT ? NativeRemovalKernel.cancel(lease.session) : NativeRemovalKernel.advance(lease.session);
            }
            String problem = world.unexpected() ? "REMOVAL_UNAPPROVED_NEIGHBOR_EFFECT" : !saves.unsupportedEffect().isEmpty() ? saves.unsupportedEffect() : loot.unsupportedReason();
            if (action != RemovalJournal.Action.REMOVE && !loot.held().isEmpty()) problem = "REMOVAL_UNEXPECTED_EARLY_LOOT";
            var evidence = new RemovalJournal.Evidence(problem.isEmpty() ? observation.state().name() : "RECOVERY_REQUIRED",
                    problem.isEmpty() ? observation.state() == NativeRemovalKernel.State.REMOVED ? RemovalJournal.CUSTODY_EVIDENCE : observation.reason() : problem,
                    observation.progress(), observation.hand(), observation.exhaustion(), observation.world().entrySet().stream()
                    .map(row -> new BuildJournal.ObservedCell(position(row.getKey()), BlueprintBlockStateCodec.describe(row.getValue()))).toList(), loot.held());
            lease.hand = observation.hand();
            if (observation.state() == NativeRemovalKernel.State.REMOVED || !problem.isEmpty()) lease.changed = true;
            if (!Set.of("MINING", "READY").contains(evidence.nativeState())) { lease.session = null; lease.step = null; }
            // Ownership metadata never changes the real native result, tool, exhaustion or loot.
            var ownershipUpdate = ownership == null ? null : ownership.observed(problem.isEmpty() && observation.state() == NativeRemovalKernel.State.REMOVED,
                    problem.isEmpty() ? observation.reason() : problem);
            NativeSaveBarrier.Receipt receipt = null;
            if (action != null && problem.isEmpty()) {
                try { receipt = saves.finish(); }
                catch (RuntimeException unavailable) { firefly520.fireflymc.FireflyMCMod.LOGGER.error("[AI4] Removal native save observation unavailable ({})", unavailable.getClass().getSimpleName()); }
            }
            return new Effect(evidence, receipt, saves.applied(), ownershipUpdate);
        }
    }
    private boolean deliver(AgentTool.Context ctx, RemovalPlan plan, RemovalJournal journal, RemovalPlan.Step step) throws Exception {
        var op = account(ctx, plan, journal, step);
        if (op.state() != MaterialLedger.State.COMPLETED) op = processing.deliver(ctx, op, plan.handSlot());
        if (op.state() != MaterialLedger.State.COMPLETED) return false;
        NativeWorldCompletionSaves.await(ctx, plan.id(), List.of(step.id()), id -> inventory.nativeSaves().awaitJournalled(id),
                fresh -> removalSaved(fresh, plan, List.of(step)), fresh -> markMissingRemovalSave(fresh, plan, List.of(step)),
                fresh -> execute(fresh, request(plan), plan.mode()));
        return true;
    }
    private AgentTool.Result finishSavedRemoval(AgentTool.Context ctx, RemovalPlan plan) throws Exception {
        ctx.control().check(); ctx.actor().requireAccess(plan.owner());
        if (!Objects.equals(plan.taskId(), ctx.taskId())) throw new SecurityException("REMOVAL_TASK_MISMATCH");
        NativeWorldCompletionSaves.await(ctx, plan.id(), plan.steps().stream().map(RemovalPlan.Step::id).toList(),
                id -> inventory.nativeSaves().awaitJournalled(id), fresh -> removalSaved(fresh, plan, plan.steps()),
                fresh -> markMissingRemovalSave(fresh, plan, plan.steps()), fresh -> finishSavedRemoval(fresh, plan));
        boolean current = true;
        var targets = plan.initialWorld().stream().filter(cell -> cell.target() != null).toList();
        for (int first = 0; first < targets.size(); first += 16) {
            var slice = targets.subList(first, Math.min(targets.size(), first + 16));
            current &= construction.inspect(ctx.control(), () -> {
                var player = inventory.requirePlayer(ctx.actor()); var level = player.serverLevel();
                if (!level.dimension().location().toString().equals(plan.dimension())) return false;
                for (var cell : slice) {
                    var p = pos(cell.position());
                    if (!level.hasChunkAt(p) || level.isOutsideBuildHeight(p) || !level.getWorldBorder().isWithinBounds(p)
                            || !BlueprintBlockStateCodec.describe(level.getBlockState(p)).equals(cell.target())) return false;
                }
                return true;
            });
        }
        var lease = live.get(plan.id()); if (lease != null) lease.completed = true;
        var data = status(ctx, plan); data.addProperty("currentTargetsVerified", current); data.addProperty("nativeSaveConfirmed", true);
        if (!current) return result(false, "REMOVAL_WORLD_CHANGED_AFTER_NATIVE_RESULT",
                "原生拆除、真实掉落交付及canonical保存已确认；现场目标已有后续变化或暂不可核验，未覆盖或重放。", data);
        boolean preservedHalf = targets.stream().anyMatch(cell -> !RemovalTransitions.air(cell.target()));
        return result(true, "", preservedHalf ? "已移除获批半块并保留其余半块；真实掉落交付、世界/工具/原料及回调的canonical保存已核验。"
                : "获批拆除及真实掉落交付已完成；现场目标、世界/工具/物品与回调的canonical保存均已核验。", data);
    }
    private boolean removalSaved(AgentTool.Context ctx, RemovalPlan plan, List<RemovalPlan.Step> expected) {
        var journal = new RemovalJournal(ctx.workspace(), epoch);
        for (var planned : expected) {
            var step = journal.get(ctx.actor(), planned.id()); var op = ledger.get(ctx.actor(), planned.id());
            if (!step.input().planId().equals(plan.id()) || !step.input().planHash().equals(plan.hash())
                    || !step.input().taskId().equals(plan.taskId()) || step.state() != RemovalJournal.State.ACCOUNTED || !step.saved()
                    || op.state() != MaterialLedger.State.COMPLETED || !op.saved() || op.intent() != null
                    || !op.taskId().equals(plan.taskId()) || !op.samplingEvidence().equals("native_block_harvest:" + RemovalJournal.harvestReceipt(step))) return false;
        }
        return true;
    }
    private void markMissingRemovalSave(AgentTool.Context ctx, RemovalPlan plan, List<RemovalPlan.Step> steps) throws Exception {
        var journal = new RemovalJournal(ctx.workspace(), epoch);
        for (var step : steps) {
            var existing = ledger.find(ctx.actor(), step.id()); if (existing.isEmpty()) continue;
            var op = existing.get(); var recorded = journal.get(ctx.actor(), step.id());
            if (!op.saved() || op.uncertain() || op.intent() != null || op.state() != MaterialLedger.State.COMPLETED
                    || !recorded.saved() || recorded.state() != RemovalJournal.State.ACCOUNTED
                    || !recorded.input().planId().equals(plan.id()) || !recorded.input().planHash().equals(plan.hash())
                    || !recorded.input().taskId().equals(plan.taskId()) || !op.taskId().equals(plan.taskId())
                    || !op.samplingEvidence().equals("native_block_harvest:" + RemovalJournal.harvestReceipt(recorded))
                    || inventory.nativeSaves().awaitJournalled(step.id()).toCompletableFuture().isCompletedExceptionally())
                ledger.markUncertain(ctx.actor(), step.id(), "REMOVAL_FINAL_NATIVE_SAVE_PROOF_UNAVAILABLE");
        }
    }
    private static JsonObject request(RemovalPlan plan) {
        var data = new JsonObject(); data.addProperty("planId", plan.id().toString()); data.addProperty("planHash", plan.hash()); return data;
    }
    private MaterialLedger.Operation account(AgentTool.Context ctx, RemovalPlan plan, RemovalJournal journal, RemovalPlan.Step step) throws Exception {
        var op = ledger.adoptHarvest(ctx.actor(), journal, step.id()); var source = journal.get(ctx.actor(), step.id());
        if (plan.mode() == RemovalJournal.Mode.UNDO) {
            var builds = new BuildJournal(ctx.workspace(), epoch);
            for (UUID id : step.sourceSteps()) builds.undone(ctx.actor(), id, source);
        }
        if (source.saved()) ledger.harvestSaved(ctx.actor(), source);
        return op;
    }
    private void abort(AgentTool.Context ctx, RemovalPlan plan, RemovalJournal journal, Live lease) throws Exception {
        if (lease.step == null) return;
        var step = plan.steps().stream().filter(s -> s.id().equals(lease.step)).findFirst().orElseThrow();
        var recorded = journal.get(ctx.actor(), step.id());
        if (!Set.of(RemovalJournal.State.MINING, RemovalJournal.State.READY).contains(recorded.state()) || recorded.intent() != null) return;
        boolean interrupted = Thread.interrupted();
        try { stage(new AgentTool.Context(ctx.workspace(), ctx.actor(), new TaskControl(10000), ctx.taskId()), plan, step, journal, RemovalJournal.Action.ABORT); }
        catch (Exception failure) { ledger.quarantineRemoval(ctx.actor(), journal.uncertain(ctx.actor(), step.id(), "REMOVAL_ABORT_UNCONFIRMED")); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    /** A later workbench cancellation must also close a previously paused, in-memory mining session. */
    public void cancelTask(Workspace workspace, Actor actor, UUID task) throws Exception {
        actor.requireAccess(workspace.owner());
        var stopped = construction.inspect(new TaskControl(10000), () -> {
            var selected = new ArrayList<Live>();
            for (var lease : live.values()) if (lease.owner.equals(actor.owner()) && lease.workspace == workspace && lease.plan != null
                    && lease.plan.taskId().equals(task) && !lease.executing) { lease.executing = true; selected.add(lease); }
            return selected;
        });
        for (var lease : stopped) {
            try { abort(new AgentTool.Context(workspace, actor, new TaskControl(10000), task), lease.plan, new RemovalJournal(workspace, epoch), lease); }
            finally { lease.completed = true; lease.executing = false; }
        }
    }
    private ServerPlayer check(Actor actor, RemovalPlan plan) {
        return check(actor, plan, true);
    }
    private ServerPlayer check(Actor actor, RemovalPlan plan, boolean checkHand) {
        if (!plan.executionEpoch().equals(epoch) || plan.registryGeneration() != generation.getAsLong()) throw new IllegalStateException("REMOVAL_RELOAD_REVIEW_REQUIRED");
        var lease = live.get(plan.id()); if (lease == null || !lease.owner.equals(actor.owner())) throw new IllegalStateException("REMOVAL_PREVIEW_EXPIRED");
        lease.check(); var player = player(actor, plan.dimension());
        if (lease.source != null) construction.checkUndo(actor, lease.source, plan.id());
        if (player.getInventory().selected != plan.handSlot() || checkHand && !ItemSnapshotCodec.capture(player.getMainHandItem(), player.registryAccess()).equals(lease.hand))
            throw new IllegalStateException("REMOVAL_TOOL_CHANGED");
        return player;
    }
    private ServerPlayer player(Actor actor, String dimension) {
        if (ledger.recoveryBlocksPlayer(actor.owner())) throw new IllegalStateException("REMOVAL_PLAYER_RECONCILIATION_REQUIRED");
        var player = inventory.requirePlayer(actor);
        if (!player.serverLevel().dimension().location().toString().equals(dimension) || player.hurtTime > 0) throw new IllegalStateException("REMOVAL_OWNER_CONTEXT_CHANGED");
        return player;
    }
    private void guard(ServerPlayer player, BlueprintDocument.Position p, boolean target) {
        var level = player.serverLevel(); var pos = pos(p);
        if (!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) throw new IllegalStateException("REMOVAL_WORLD_UNAVAILABLE");
        if (!target) return;
        if (ledger.recoveryBlocksWorld(level.dimension().location().toString(), p.x(), p.y(), p.z())) throw new IllegalStateException("REMOVAL_WORLD_RECONCILIATION_REQUIRED");
        if (!player.canInteractWithBlock(pos, 0) && !permissions.checkBooleanPermission(player, "fireflymc.ai.remote_build", remote).allowed()) throw new SecurityException("VIRTUAL_CONSTRUCTION_PERMISSION_REQUIRED");
        if (!level.mayInteract(player, pos) || !permissions.checkBreak(player, level, pos, Direction.UP).allowed()) throw new SecurityException("REMOVAL_PROTECTED");
    }
    private static void requireProfile(ServerPlayer player, BlueprintDocument.Position p, BlockState state, boolean target) {
        if (state.isAir()) return;
        if (!state.getFluidState().isEmpty() || player.serverLevel().getBlockEntity(pos(p)) != null) throw new IllegalStateException("REMOVAL_DATA_OR_FLUID_ADAPTER_REQUIRED");
        if (target) NativeRemovalProfiles.remaining(player, pos(p), state); else NativePlacementProfiles.require(state);
        if (!target && !Set.of(Block.class, RotatedPillarBlock.class, SlabBlock.class, Blocks.GLASS.getClass(), StainedGlassBlock.class,
                TintedGlassBlock.class, CraftingTableBlock.class, SmithingTableBlock.class).contains(state.getBlock().getClass()))
            throw new IllegalStateException("REMOVAL_NEIGHBOR_ADAPTER_REQUIRED");
        if (target && state.getDestroySpeed(player.serverLevel(), pos(p)) < 0) throw new IllegalStateException("REMOVAL_UNBREAKABLE");
    }
    public String review(Workspace workspace, Actor actor, RemovalPlan plan) throws Exception {
        var expected = new HashMap<BlueprintDocument.Position, BlueprintDocument.State>(); plan.initialWorld().forEach(c -> expected.put(c.position(), c.before()));
        for (var step : new RemovalJournal(workspace, epoch).list(actor)) if (step.input().planId().equals(plan.id())) {
            if (!step.input().planHash().equals(plan.hash()) || step.intent() != null || step.state() == RemovalJournal.State.RECOVERY_REQUIRED)
                throw new IllegalStateException("REMOVAL_RECONCILIATION_REQUIRED");
            if (Set.of(RemovalJournal.State.REMOVED, RemovalJournal.State.ACCOUNTED).contains(step.state())) {
                RemovalJournal.harvestReceipt(step); step.input().cells().stream().filter(c -> c.target() != null).forEach(c -> expected.put(c.position(), c.target()));
            }
        }
        for (int offset = 0; offset < plan.initialWorld().size(); offset += 16) {
            var slice = plan.initialWorld().subList(offset, Math.min(offset + 16, plan.initialWorld().size()));
            construction.inspect(new TaskControl(30000), () -> {
                var player = check(actor, plan);
                for (var cell : slice) { guard(player, cell.position(), cell.target() != null);
                    if (!player.serverLevel().getBlockState(pos(cell.position())).equals(BlueprintBlockStateCodec.resolve(expected.get(cell.position())))) throw new IllegalStateException("REMOVAL_WORLD_CHANGED"); }
                return true;
            });
        }
        var text = new StringBuilder(plan.mode() == RemovalJournal.Mode.UNDO ? "安全撤销审批\n原施工计划：" + plan.sourcePlanId() + "\n" : "生存拆除审批\n");
        text.append("本人位置不移动；远程需虚拟施工代理权限，每格保留领地保护。\n工具、饥饿及采集时间按原生规则支付；不是免费返还材料。\n主手：")
                .append(plan.hand().empty() ? "空手" : AiJson.object(plan.hand().itemJson(), 1_048_576, 32).get("id").getAsString())
                .append("（快捷栏第 ").append(plan.handSlot() + 1).append(" 格）\n维度：").append(plan.dimension()).append("\n拆除清单：\n");
        plan.initialWorld().stream().filter(c -> c.target() != null && !c.before().blockId().equals("minecraft:air"))
                .forEach(c -> text.append(c.position()).append(" ").append(c.before().blockId()).append(" ").append(c.before().properties())
                        .append(" → ").append(c.target().blockId()).append(" ").append(c.target().properties()).append("\n"));
        long halves = plan.steps().stream().filter(s -> RemovalTransitions.partialOnly(s.cells())).count();
        if (halves > 0) {
            text.append("KleeSlabs 原生半砖步骤：\n");
            for (var step : plan.steps()) if (RemovalTransitions.partialOnly(step.cells())) {
                var cell = step.cells().stream().filter(c -> c.target() != null).findFirst().orElseThrow();
                boolean whole = plan.initialWorld().stream().anyMatch(c -> c.position().equals(cell.position()) && c.target() != null && RemovalTransitions.air(c.target()));
                text.append(cell.position()).append(whole ? " 两阶段：" : " 单阶段：").append(cell.target().properties().get("type").equals("bottom") ? "移除上半，保留下半" : "移除下半，保留上半")
                        .append(whole ? "；随后拆除剩余部分。\n" : "；保留部分不再拆除。\n");
            }
            text.append("每阶段分别核验与托管；取消保留实际中间状态。\n");
        }
        return text.append("原生掉落事件的真实结果先托管后交付；满仓停止后续步骤。无可靠适配的副作用须先对账。\n计划摘要：").append(plan.hash()).toString();
    }
    private static JsonObject view(RemovalPlan plan) {
        var data = new JsonObject(); data.addProperty("planId", plan.id().toString()); data.addProperty("planHash", plan.hash()); data.addProperty("dimension", plan.dimension());
        data.addProperty("steps", plan.steps().size()); data.addProperty("mode", plan.mode().name()); data.addProperty("regionHash", plan.regionHash());
        data.addProperty("partialSlabOperations", plan.steps().stream().filter(s -> RemovalTransitions.partialOnly(s.cells())).count());
        var targets = plan.initialWorld().stream().filter(c -> c.target() != null).toList();
        data.add("targets", AiJson.GSON.toJsonTree(targets.stream().limit(32).toList())); data.addProperty("targetCount", targets.size());
        data.addProperty("targetsTruncated", targets.size() > 32); return data;
    }
    private JsonObject status(AgentTool.Context ctx, RemovalPlan plan) {
        var data = view(plan); var rows = new RemovalJournal(ctx.workspace(), epoch).list(ctx.actor()).stream().filter(s -> s.input().planId().equals(plan.id())).toList();
        var completed = rows.stream().filter(s -> Set.of(RemovalJournal.State.REMOVED, RemovalJournal.State.ACCOUNTED).contains(s.state())).toList();
        data.addProperty("completedOperations", completed.size());
        data.addProperty("removedBlocks", completed.stream().flatMap(s -> s.input().cells().stream()).filter(c -> c.target() != null && RemovalTransitions.air(c.target())).map(BuildJournal.Cell::position).distinct().count());
        var observed = new LinkedHashMap<BlueprintDocument.Position, BuildJournal.ObservedCell>();
        for (var step : completed) for (var cell : step.input().cells()) if (cell.target() != null)
            step.evidence().world().stream().filter(c -> c.position().equals(cell.position())).findFirst().ifPresent(c -> observed.put(c.position(), c));
        data.add("verifiedTargets", AiJson.GSON.toJsonTree(observed.values().stream().limit(32).toList()));
        data.addProperty("verifiedTargetsTruncated", observed.size() > 32); data.addProperty("verificationScope", "native-operation-receipt");
        data.addProperty("recoveryRequired", rows.stream().anyMatch(s -> s.intent() != null || s.state() == RemovalJournal.State.RECOVERY_REQUIRED));
        data.addProperty("nativeSavesPending", rows.stream().filter(s -> !s.saved()).count());
        data.add("operations", AiJson.GSON.toJsonTree(rows.stream().map(s -> Map.of("operationId", s.input().operationId(), "state", s.state(), "reason", s.reason())).toList())); return data;
    }
    void tick() {
        for (var entry : List.copyOf(live.entrySet())) if (!entry.getValue().executing && (entry.getValue().completed || entry.getValue().session == null
                && System.nanoTime() - entry.getValue().created > TimeUnit.MINUTES.toNanos(30))) release(entry.getKey());
    }
    private void release(UUID id) {
        var value = live.remove(id); if (value == null) return;
        value.session = null; value.step = null;
        if (value.source == null) value.world.close(); else construction.releaseUndo(value.source, id, value.changed);
    }
    @Override public void close() { for (UUID id : List.copyOf(live.keySet())) release(id); }
    private static BlockPos pos(BlueprintDocument.Position p) { return new BlockPos(p.x(), p.y(), p.z()); }
    private static BlueprintDocument.Position position(BlockPos p) { return new BlueprintDocument.Position(p.getX(), p.getY(), p.getZ()); }
    private static String safe(Exception e) { return e.getMessage() != null && e.getMessage().matches("[A-Za-z0-9_:/.-]{1,160}") ? e.getMessage() : e.getClass().getSimpleName(); }
    private static AgentTool.Result result(boolean ok, String code, String summary, JsonObject data) { return new AgentTool.Result(ok, code, summary, data, "server-removal-journal", "", false); }
    private void tool(ToolRegistry tools, String name, String description, String fields, List<String> required, AgentTool.Risk risk, DefinedTool.Action action) {
        var schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", JsonParser.parseString(fields));
        schema.add("required", AiJson.GSON.toJsonTree(required)); schema.addProperty("additionalProperties", false);
        tools.register(new DefinedTool(new AgentTool.Descriptor(name, description, schema, risk, "construction.own", 0, AgentTool.ExecutionThread.IO, 300000, true, ""),
                (ctx, args) -> { try { return action.run(ctx, args); } catch (TaskControl.Stopped stopped) { throw stopped; }
                    catch (Exception failed) { return AgentTool.Result.error(safe(failed), "拆除未完成：" + safe(failed)); } }));
    }
}
