package firefly520.fireflymc.ai.integration.trees;

import com.google.gson.*;
import firefly520.fireflymc.ai.blueprint.*;
import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.task.CommittedCall;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.level.GameType;

/** Approved native tree harvests share world watches and physical-player leases with all other AI work. */
public final class TreeHarvestService implements AutoCloseable {
    private final MinecraftServer server;
    private final MaterialInventoryService inventory;
    private final MaterialLedger materials;
    private final InstalledPermissionAdapters permissions;
    private final VirtualProcessingService dispatcher;
    private final LongSupplier generation;
    private final BooleanSupplier enabled;
    private final boolean serverProxyGrant;
    private final UUID epoch = UUID.randomUUID();
    private final DynamicTreeAccess access = new DynamicTreeAccess();
    private final Map<String, TreeHarvestJournal> journals = new ConcurrentHashMap<>();
    private final Map<UUID, Binding> bindings = new ConcurrentHashMap<>();
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final Map<UUID, MaterialLedger.NativeScopeLease> resources = new ConcurrentHashMap<>();
    private final Map<UUID, MaterialLedger.NativeWorldScopeLease> worldResources = new ConcurrentHashMap<>();
    private final Map<UUID, TreeHarvestReconciliation> reconciliations = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4),
            Thread.ofPlatform().daemon(true).name("fireflymc-tree-harvest").factory(), new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed, broken;
    private record Binding(TreeHarvestPlan plan, Workspace workspace, ServerPlayer owner, DynamicTreeAccess.Survey survey,
                           WorldMutationTracker.Lease world, boolean remoteStart, boolean confirmedRegion) { }
    private static final class Job {
        final AgentTool.Context context; final Binding binding; final TreeHarvestJournal journal;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final List<CompletableFuture<?>> saves = new ArrayList<>();
        volatile boolean cancel;
        volatile CompletableFuture<Void> next = new CompletableFuture<>();
        TreeHarvestNative nativeJob;
        Job(AgentTool.Context context, Binding binding, TreeHarvestJournal journal) { this.context = context; this.binding = binding; this.journal = journal; }
    }
    public TreeHarvestService(MinecraftServer server, MaterialInventoryService inventory, MaterialLedger materials,
                              InstalledPermissionAdapters permissions, VirtualProcessingService dispatcher,
                              LongSupplier generation, BooleanSupplier enabled) {
        this(server, inventory, materials, permissions, dispatcher, generation, enabled, false);
    }
    public TreeHarvestService(MinecraftServer server, MaterialInventoryService inventory, MaterialLedger materials,
                              InstalledPermissionAdapters permissions, VirtualProcessingService dispatcher,
                              LongSupplier generation, BooleanSupplier enabled, boolean serverProxyGrant) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        this.server = server; this.inventory = inventory; this.materials = materials; this.permissions = permissions;
        this.dispatcher = dispatcher; this.generation = generation; this.enabled = enabled; this.serverProxyGrant = serverProxyGrant;
    }
    public synchronized TreeHarvestJournal prepare(Workspace workspace) throws IOException {
        var previous = journals.get(workspace.owner()); if (previous != null) return previous;
        if (closed) throw new IllegalStateException("AI_STOPPING");
        try {
            var created = new TreeHarvestJournal(workspace); var actor = new Actor(workspace.owner(), true, false, 0);
            for (var op : created.snapshot(actor)) {
                if (!op.settled()) {
                    resources.put(op.plan().id(), materials.restoreNativeScope(UUID.fromString(workspace.owner()), op.plan().id(), List.of(backend(workspace.owner()))));
                    worldResources.put(op.plan().id(), materials.restoreNativeWorldScope(UUID.fromString(workspace.owner()), op.plan().id(), op.plan().dimension(), cells(op.plan())));
                    if (op.observation() != null) {
                        var ids = new ArrayList<>(op.observation().fallingEntities()); op.observation().drops().forEach(drop -> ids.add(drop.entityId()));
                        DynamicTreeNativeScope.restoreOutputs(server, op.plan().id(), ids);
                    }
                }
                else DynamicTreeNativeScope.settled(server, op.plan().id());
            }
            journals.put(workspace.owner(), created); return created;
        } catch (IOException | RuntimeException invalid) { broken = true; materials.quarantineNativeScopeRestoration(); throw invalid; }
    }
    public TreeHarvestPlan plan(AgentTool.Context ctx, BlockPos anchor, Direction face) throws Exception {
        return plan(ctx, anchor, face, false);
    }
    public TreeHarvestPlan plan(AgentTool.Context ctx, BlockPos anchor, Direction face, boolean useSelectedRegion) throws Exception {
        ctx.actor().requireAccess(ctx.workspace().owner()); if (ctx.taskId() == null) throw new IllegalArgumentException("TREE_TASK_REQUIRED");
        final BuildRegionStore.Selection selection; final long selectedPrivacy, selectedSequence;
        synchronized (ctx.workspace().conversation()) {
            selection = new BuildRegionStore(ctx.workspace()).current(ctx.actor()).orElse(null);
            selectedPrivacy = ctx.workspace().conversation().privacyEpoch(); selectedSequence = ctx.workspace().conversation().regionSequence();
        }
        var value = dispatcher.inspectOwned(ctx, () -> {
            ServerPlayer owner = inventory.requirePlayer(ctx.actor());
            if (ctx.workspace().conversation().privacyEpoch() != selectedPrivacy || ctx.workspace().conversation().regionSequence() != selectedSequence)
                throw new IllegalStateException("TREE_SELECTION_CHANGED");
            boolean remoteStart = !owner.canInteractWithBlock(anchor, 0);
            boolean constrained = remoteStart || useSelectedRegion;
            if (constrained && (selection == null || !selection.confirmed() || !selection.complete())) throw new IllegalStateException("TREE_CONFIRMED_REGION_REQUIRED_FOR_REMOTE_OR_EXPLICIT_SELECTION");
            if (constrained && !selection.dimension().equals(owner.serverLevel().dimension().location().toString())) throw new IllegalStateException("TREE_DIMENSION_CHANGED");
            guardStart(owner, anchor, remoteStart, constrained);
            var survey = access.survey(owner, anchor, constrained ? selection.bounds() : DynamicTreeAccess.candidateBounds(anchor));
            guard(owner, anchor);
            DynamicTreeAccess.requireSafeFallingSweep(owner, survey, anchor);
            var held = ItemSnapshotCodec.capture(owner.getMainHandItem(), owner.registryAccess());
            if (held.empty()) throw new IllegalStateException("TREE_REAL_TOOL_REQUIRED");
            UUID id = UUID.randomUUID();
            var plan = new TreeHarvestPlan(1, id, ctx.actor().owner(), ctx.workspace().metadata().serverInstanceId(), ctx.workspace().primaryConversationId(),
                    selectedPrivacy, epoch, ctx.taskId(), generation.getAsLong(), selectedSequence,
                    System.currentTimeMillis() + 120000, owner.serverLevel().dimension().location().toString(), TreeHarvestNative.position(anchor), face.name(), survey.species(), survey.family(), survey.branchClass(),
                    TreeHarvestNative.position(survey.root()), survey.branches().size(), held, survey.configuration(), survey.footprint().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).map(row -> new TreeHarvestNative.Cell(TreeHarvestNative.position(row.getKey()), BlueprintBlockStateCodec.describe(row.getValue()))).toList(), "").signed();
            if (bindings.size() >= 64) throw new IllegalStateException("TREE_PREVIEW_LIMIT");
            var watch = ConstructionWorldEvents.tracker(server).watch(id, owner.serverLevel(), survey.footprint().keySet().stream().map(BlockPos::asLong).toList());
            bindings.put(id, new Binding(plan, ctx.workspace(), owner, survey, watch, remoteStart, constrained)); return plan;
        });
        var binding = bindings.get(value.id()); var positions = new ArrayList<>(binding.survey().footprint().keySet());
        for (int first = 0; first < positions.size(); first += 16) {
            int begin = first, end = Math.min(positions.size(), first + 16);
            dispatcher.inspectOwned(ctx, () -> {
                binding.world().check();
                for (BlockPos pos : positions.subList(begin, end)) guard(binding.owner(), pos);
                return null;
            });
        }
        synchronized (ctx.workspace().conversation()) {
            ctx.control().check(); value.requireIdentity(ctx.workspace(), ctx.actor(), epoch, generation.getAsLong(), System.currentTimeMillis());
            ctx.workspace().conversation().append(List.of(new ConversationStore.Draft(ctx.taskId(), "tree_harvest_plan", null, AiJson.GSON.toJsonTree(value).getAsJsonObject(), value.id().toString())));
        }
        return value;
    }
    public TreeHarvestPlan load(Workspace workspace, Actor actor, UUID id, String hash) {
        actor.requireAccess(workspace.owner()); var binding = bindings.get(id);
        if (binding == null || binding.workspace() != workspace || !binding.plan().hash().equals(hash)) throw new IllegalStateException("TREE_PREVIEW_EXPIRED_OR_CHANGED");
        binding.plan().requireIdentity(workspace, actor, epoch, generation.getAsLong(), System.currentTimeMillis()); return binding.plan();
    }
    public String review(Workspace workspace, Actor actor, UUID id, String hash) {
        var plan = load(workspace, actor, id, hash);
        return "原生动态树采集审批\n树种：" + plan.species() + "；家族：" + plan.family() + "；原生网络节点：" + plan.nativeNodes()
                + "\n起手方式：" + (bindings.get(id).remoteStart() ? "已确认区域内的独立远程起手授权" : "本人实际原生交互距离内起手；树冠展开不额外要求远程授权")
                + "\n调查来源：" + (bindings.get(id).confirmedRegion() ? "已有本人确认区域约束" : "AI有界只读候选，下面完整范围仅由本次固定计划审批授权")
                + "\n维度：" + plan.dimension() + "；起点：" + plan.anchor() + "；完整影响包络：" + plan.footprint().size() + " 格。"
                + "\n逐格重新核验保护、真实工具/生存挖掘时间/耐久和饥饿；按本服真实原生树网络、掉落表与倒树动画执行。"
                + "\n原配置开启倒树伤害时，本人仍承担原生碰撞、击退与伤害风险；只批准本人风险。原生碰撞列表中出现其他活体，先停止该阶段并对账，不向其应用击退或伤害。"
                + "\n掉落先生成真实世界物品并记录原生UUID，只有本人实际走到原版碰撞拾取范围才调用原生拾取，遵守拾取延迟/事件；距离不足或满仓余物保留世界并报告尚未拾完，不补发物料。发生倒树副作用后的取消、断线或不确定结果先对账。"
                + "\n批准的完整坐标/原状态：\n" + AiJson.GSON.toJson(plan.footprint()) + "\n计划摘要：" + plan.hash();
    }
    public AgentTool.Result execute(AgentTool.Context ctx, TreeHarvestPlan plan) throws Exception {
        plan.requireIdentity(ctx.workspace(), ctx.actor(), epoch, generation.getAsLong(), System.currentTimeMillis());
        if (!Objects.equals(ctx.taskId(), plan.taskId())) throw new SecurityException("TREE_TASK_MISMATCH");
        var repo = prepare(ctx.workspace());
        if (repo.find(ctx.actor(), plan.id()).isPresent()) return finish(ctx, plan.id());
        var binding = bindings.get(plan.id()); if (binding == null || binding.plan() != plan) throw new SecurityException("TREE_PLAN_REPLACED");
        Job job;
        synchronized (jobs) {
            if (closed || broken || !jobs.isEmpty()) throw new IllegalStateException("TREE_NATIVE_CAPACITY_BUSY");
            var lease = materials.acquireNativeScope(ctx.actor(), plan.id(), List.of(backend(ctx.actor().owner()))); resources.put(plan.id(), lease);
            try {
                worldResources.put(plan.id(), materials.acquireNativeWorldScope(ctx.actor(), plan.id(), plan.dimension(), cells(plan)));
                repo.create(ctx.actor(), plan); job = new Job(ctx, binding, repo); jobs.put(plan.id(), job);
            } catch (Exception failed) {
                var world = worldResources.remove(plan.id()); if (world != null) materials.releaseNativeWorldScope(ctx.actor(), world);
                materials.releaseNativeScope(ctx.actor(), lease); resources.remove(plan.id()); throw failed;
            }
        }
        try { workers.execute(() -> run(job)); }
        catch (RejectedExecutionException unavailable) { repo.uncertain(ctx.actor(), plan.id(), "TREE_WORKER_NOT_DISPATCHED_REVIEW_REQUIRED"); job.done.complete(null); }
        return finish(ctx, plan.id());
    }
    private void run(Job job) {
        var ctx = job.context; var plan = job.binding.plan();
        try {
            job.nativeJob = dispatcher.inspectOwned(ctx, () -> {
                live(job);
                if (!ItemSnapshotCodec.capture(job.binding.owner().getMainHandItem(), job.binding.owner().registryAccess()).equals(plan.tool()))
                    throw new IllegalStateException("TREE_APPROVED_TOOL_CHANGED");
                return new TreeHarvestNative(plan.id(), job.binding.owner(), DynamicTreeAccess.nativePos(plan.anchor()), Direction.valueOf(plan.face()),
                        job.binding.survey(), (owner, pos) -> guardOwned(job, owner, pos), job.binding.world());
            });
            int stages = 0;
            while (!job.nativeJob.terminal()) {
                if (++stages > 2400) throw new IllegalStateException("TREE_NATIVE_STAGE_LIMIT");
                var gate = job.next; ctx.control().await(gate, 10000); job.next = new CompletableFuture<>();
                boolean cancel = job.cancel || closed;
                UUID attempt = job.journal.intent(ctx.actor(), plan.id(), job.nativeJob.nextRootIntent());
                var observed = dispatcher.commitObserved(ctx, () -> { live(job); return job.nativeJob.step(cancel); }, evidence -> record(job, attempt, evidence));
                if (observed.state() == TreeHarvestNative.State.RECOVERY_REQUIRED) break;
            }
            releaseIfSettled(job);
        } catch (Exception | LinkageError failed) {
            if (!job.binding.world().invalidatingOrigin().isEmpty())
                firefly520.fireflymc.FireflyMCMod.LOGGER.warn("[AI4] Tree world lease lost its causal scope; operation={}, nativeOrigin={}",
                        plan.id(), job.binding.world().invalidatingOrigin());
            if (failed instanceof CommittedCall.Rejected || failed instanceof TaskControl.Stopped) {
                try {
                    var prior = job.journal.get(ctx.actor(), plan.id());
                    if (failed instanceof CommittedCall.Rejected && prior.intent() != null)
                        prior = job.journal.notDispatched(ctx.actor(), plan.id(), prior.intent());
                    if (prior.intent() == null && job.nativeJob != null && !job.nativeJob.terminal()
                            && prior.state() != TreeHarvestNative.State.RECOVERY_REQUIRED) {
                        var cleanup = new AgentTool.Context(ctx.workspace(), ctx.actor(), new TaskControl(10000), ctx.taskId());
                        UUID abort = job.journal.intent(ctx.actor(), plan.id());
                        dispatcher.commitObserved(cleanup, () -> { live(job); return job.nativeJob.step(true); }, evidence -> record(job, abort, evidence));
                        releaseIfSettled(job); return;
                    }
                } catch (Exception | LinkageError abortUncertain) { /* A denied or uncertain ABORT remains quarantined below. */ }
            }
            try { job.journal.uncertain(ctx.actor(), plan.id(), TreeHarvestNative.safe(failed)); }
            catch (IOException | RuntimeException lost) { broken = true; }
        } finally {
            job.done.complete(null);
            try { if (job.journal.get(ctx.actor(), plan.id()).state() == TreeHarvestNative.State.RECOVERY_REQUIRED) jobs.remove(plan.id(), job); }
            catch (RuntimeException lost) { broken = true; }
        }
    }
    private void record(Job job, UUID attempt, TreeHarvestNative.Evidence evidence) throws IOException {
        var ctx = job.context; UUID id = job.binding.plan().id();
        job.journal.observed(ctx.actor(), id, attempt, evidence);
        if (evidence.save() != null) {
            var saved = evidence.applied().thenCompose(ignored -> evidence.save().saved()).thenAccept(proof -> {
                try { job.journal.saved(ctx.actor(), id, attempt, proof); releaseIfSettled(job); }
                catch (IOException | RuntimeException failed) { broken = true; }
            });
            synchronized (job.saves) { job.saves.add(saved); }
        }
    }
    private void live(Job job) {
        if (broken || !enabled.getAsBoolean()) throw new IllegalStateException("TREE_SERVICE_UNAVAILABLE");
        var plan = job.binding.plan();
        if (generation.getAsLong() != plan.registryGeneration() || job.context.workspace().conversation().privacyEpoch() != plan.privacyEpoch()
                || job.context.workspace().conversation().regionSequence() != plan.regionSequence()) throw new IllegalStateException("TREE_PLAN_CONTEXT_CHANGED");
        if (inventory.requirePlayer(job.context.actor()) != job.binding.owner()) throw new IllegalStateException("TREE_OWNER_REPLACED");
        if (!job.cancel && !closed && (job.nativeJob == null || !job.nativeJob.nativeEntered()))
            guardStart(job.binding.owner(), DynamicTreeAccess.nativePos(plan.anchor()), job.binding.remoteStart(), job.binding.confirmedRegion());
        var resource = resources.get(plan.id()); if (resource == null) throw new IllegalStateException("TREE_RESOURCE_LEASE_MISSING");
        materials.requireNativeScope(job.context.actor(), resource); job.binding.world().check();
        var world = worldResources.get(plan.id()); if (world == null) throw new IllegalStateException("TREE_WORLD_RESOURCE_LEASE_MISSING");
        materials.requireNativeWorldScope(job.context.actor(), world, DynamicTreeAccess.nativePos(plan.anchor()).asLong());
    }
    private void guard(ServerPlayer owner, BlockPos pos) {
        guardCommon(owner, pos);
        if (materials.recoveryBlocksWorld(owner.serverLevel().dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ()))
            throw new SecurityException("TREE_WORLD_RESOURCE_BUSY");
    }
    private void guardOwned(Job job, ServerPlayer owner, BlockPos pos) {
        guardCommon(owner, pos); var world = worldResources.get(job.binding.plan().id());
        if (world == null || !world.dimension().equals(owner.serverLevel().dimension().location().toString())) throw new SecurityException("TREE_WORLD_RESOURCE_LEASE_MISSING");
        materials.requireNativeWorldScope(job.context.actor(), world, pos.asLong());
    }
    private void guardCommon(ServerPlayer owner, BlockPos pos) {
        DynamicTreeAccess.thread(owner); var level = owner.serverLevel();
        if (owner.isFakePlayer() || !enabled.getAsBoolean() || owner.gameMode.getGameModeForPlayer() != GameType.SURVIVAL || owner.getAbilities().instabuild)
            throw new SecurityException("TREE_SURVIVAL_OWNER_REQUIRED");
        if (!permissions.checkBooleanPermission(owner, "fireflymc.ai.harvest_tree", true).allowed()) throw new SecurityException("TREE_PERMISSION_DENIED");
        if (!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) throw new IllegalStateException("TREE_WORLD_UNLOADED_OR_OUTSIDE");
        var entity = level.getBlockEntity(pos);
        if ((entity != null && !DynamicTreeAccess.nativeSpeciesStore(level.getBlockState(pos), entity)) || !level.getFluidState(pos).isEmpty())
            throw new IllegalStateException("TREE_NATIVE_SPECIAL_BLOCK_UNSUPPORTED");
        if (!level.mayInteract(owner, pos)
                || owner.blockActionRestricted(level, pos, GameType.SURVIVAL) || !permissions.checkBreak(owner, level, pos, Direction.UP).allowed()) throw new SecurityException("TREE_BLOCK_PROTECTED");
    }
    private void guardStart(ServerPlayer owner, BlockPos anchor, boolean remoteStart, boolean confirmedRegion) {
        guardCommon(owner, anchor);
        if (owner.canInteractWithBlock(anchor, 0)) return;
        if (!remoteStart || !confirmedRegion) throw new SecurityException("TREE_REMOTE_START_REQUIRES_CONFIRMED_REGION");
        if (!permissions.checkBooleanPermission(owner, "fireflymc.ai.remote_build", serverProxyGrant).allowed()) throw new SecurityException("TREE_REMOTE_PERMISSION_REQUIRED");
    }
    private AgentTool.Result finish(AgentTool.Context ctx, UUID id) throws Exception {
        var repo = prepare(ctx.workspace()); var op = repo.get(ctx.actor(), id);
        if (!op.plan().taskId().equals(ctx.taskId())) throw new SecurityException("TREE_TASK_MISMATCH");
        Job job = jobs.get(id);
        if (job != null && !job.done.isDone()) throw deferred(id, job.done);
        if (job != null && op.terminal() && !op.settled()) {
            CompletableFuture<?> waiting; synchronized (job.saves) { waiting = CompletableFuture.allOf(job.saves.toArray(CompletableFuture[]::new)); }
            if (!waiting.isDone()) throw deferred(id, waiting);
        }
        var data = status(op);
        return new AgentTool.Result(op.state() == TreeHarvestNative.State.COMPLETED && op.settled(), !op.settled() ? "RECOVERY_REQUIRED" : op.state() == TreeHarvestNative.State.WORLD_REMAINDER ? "TREE_REAL_PICKUP_REQUIRED" : "",
                "原生树采集状态：" + op.state() + "；掉落按实际世界实体记录，未重放或补发。", data, "native-dynamic-trees-journal", "", false);
    }
    private AgentTool.Deferred deferred(UUID id, CompletionStage<?> waiting) {
        var data = new JsonObject(); data.addProperty("operationId", id.toString());
        return new AgentTool.Deferred("NATIVE_TREE_HARVEST", data, waiting, ctx -> finish(ctx, id));
    }
    private void releaseIfSettled(Job job) {
        var actor = job.context.actor(); var plan = job.binding.plan();
        if (!job.journal.get(actor, plan.id()).settled()) return;
        DynamicTreeNativeScope.settled(server, plan.id());
        var lease = resources.remove(plan.id()); if (lease != null) materials.releaseNativeScope(actor, lease);
        var world = worldResources.remove(plan.id()); if (world != null) materials.releaseNativeWorldScope(actor, world);
        server.execute(() -> { job.binding.world().close(); bindings.remove(plan.id()); jobs.remove(plan.id(), job); });
    }
    public void tick() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        for (Job job : jobs.values()) job.next.complete(null);
        for (var binding : List.copyOf(bindings.values())) if (!jobs.containsKey(binding.plan().id()) && (binding.plan().expiresAt() < System.currentTimeMillis() || !binding.world().valid())) {
            binding.world().close(); bindings.remove(binding.plan().id(), binding);
        }
    }
    public void cancelTask(Workspace workspace, Actor actor, UUID task) {
        actor.requireAccess(workspace.owner()); for (Job job : jobs.values()) if (job.context.workspace() == workspace && job.binding.plan().taskId().equals(task)) { job.cancel = true; job.next.complete(null); }
    }
    public void disconnected(UUID owner) { for (Job job : jobs.values()) if (job.binding.plan().owner().equals(owner.toString())) { job.cancel = true; job.next.complete(null); } }
    public boolean resourcesSettled(Workspace workspace, Actor actor) throws IOException { return !broken && prepare(workspace).settled(actor)
            && resources.values().stream().noneMatch(value -> value.owner().toString().equals(actor.owner()))
            && worldResources.values().stream().noneMatch(value -> value.owner().toString().equals(actor.owner())); }
    public boolean taskSettled(Workspace workspace, Actor actor, UUID task) throws IOException {
        var repo = prepare(workspace); return !broken && repo.taskSettled(actor, task)
                && repo.snapshot(actor).stream().filter(op -> op.plan().taskId().equals(task)).noneMatch(op -> resources.containsKey(op.plan().id()) || worldResources.containsKey(op.plan().id()));
    }
    public void forgetErased(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner()); var repo = journals.get(workspace.owner()); if (repo == null) return;
        if (!repo.canForgetAfterErasure(actor) || resources.values().stream().anyMatch(value -> value.owner().toString().equals(actor.owner()))
                || worldResources.values().stream().anyMatch(value -> value.owner().toString().equals(actor.owner()))) throw new IllegalStateException("TREE_RESOURCES_NOT_SETTLED");
        if (!journals.remove(workspace.owner(), repo)) throw new IllegalStateException("TREE_PRIVACY_CACHE_CHANGED");
        reconciliations.values().removeIf(review -> review.owner().equals(workspace.owner()) && review.privacyEpoch() < workspace.conversation().privacyEpoch());
        server.execute(() -> {
            for (var binding : List.copyOf(bindings.values())) if (binding.workspace() == workspace && binding.plan().privacyEpoch() < workspace.conversation().privacyEpoch()
                    && bindings.remove(binding.plan().id(), binding)) binding.world().close();
        });
    }
    public String exportJournal(Workspace workspace, Actor actor) throws IOException { return prepare(workspace).export(actor); }
    public Optional<Boolean> settledCall(Workspace workspace, Actor actor, UUID task, ModelMessage.ToolCall call) {
        if (!Set.of("tree_harvest_execute", "tree_harvest_reconcile").contains(call.name())) return Optional.empty();
        try {
            var args = AiJson.object(call.arguments(), 65536, 24); var journal = journals.get(workspace.owner());
            if (journal == null || broken) return Optional.of(false);
            if (call.name().equals("tree_harvest_reconcile")) {
                if (!args.keySet().equals(Set.of("reviewId", "reviewHash"))) return Optional.of(false);
                String id = args.get("reviewId").getAsString(), hash = args.get("reviewHash").getAsString();
                for (var event : workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events()) if (event.kind().equals("tree_reconcile_preview")
                        && task.equals(event.taskId()) && event.data() != null && id.equals(event.data().get("id").getAsString()) && hash.equals(event.data().get("hash").getAsString())) {
                    var review = AiJson.GSON.fromJson(event.data(), TreeHarvestReconciliation.class);
                    return Optional.of(journal.snapshot(actor).stream().anyMatch(op -> op.plan().taskId().equals(task) && op.plan().id().equals(review.operationId())
                            && op.revision() > review.operationRevision() && op.reason().equals("TREE_RECONCILED_SAVED_RESULT:" + review.hash()) && op.settled()));
                }
                return Optional.of(false);
            }
            if (!args.keySet().equals(Set.of("planId", "planHash"))) return Optional.of(false);
            UUID id = UUID.fromString(args.get("planId").getAsString());
            return Optional.of(journal.snapshot(actor).stream().anyMatch(op -> op.plan().id().equals(id) && op.plan().taskId().equals(task)
                    && op.plan().hash().equals(args.get("planHash").getAsString()) && op.settled()));
        } catch (RuntimeException invalid) { return Optional.of(false); }
    }
    public boolean operationSettled(Workspace workspace, Actor actor, UUID id) throws IOException {
        var op = prepare(workspace).get(actor, id); return !broken && op.settled() && !resources.containsKey(id) && !worldResources.containsKey(id);
    }
    public List<JsonObject> recoveryRows(Workspace workspace, Actor actor, UUID task) throws IOException {
        var result = new ArrayList<JsonObject>();
        for (var op : prepare(workspace).snapshot(actor)) if (op.plan().taskId().equals(task)) {
            var row = status(op); row.addProperty("kind", "TREE_HARVEST"); row.addProperty("saved", op.savedKnownTerminal()); var actions = new JsonArray();
            Job active = jobs.get(op.plan().id());
            if (op.state() == TreeHarvestNative.State.RECOVERY_REQUIRED && op.savedKnownTerminal() && (active == null || active.done.isDone())) actions.add("TREE_RECONCILE");
            row.add("actions", actions); row.addProperty("reasonText", !op.savedKnownTerminal()
                    ? "缺少完整已知终态或每阶段canonical保存凭据；保留物理库存/世界/实体隔离，不重砍、不补发。"
                    : "可进一步检查已保存结果和真实余物；新审批仅收束已知历史，不重放砍伐或拾取，原目标仍须核验。"); result.add(row);
        }
        return List.copyOf(result);
    }
    public JsonObject recoveryResources(Workspace workspace, Actor actor, UUID id) throws IOException {
        var op = prepare(workspace).get(actor, id); var data = status(op); data.add("nativeJournal", AiJson.GSON.toJsonTree(op)); return data;
    }
    public TreeHarvestReconciliation recoveryPreview(AgentTool.Context ctx, UUID id) throws Exception {
        var op = prepare(ctx.workspace()).get(ctx.actor(), id);
        if (!op.plan().taskId().equals(ctx.taskId())) throw new SecurityException("TREE_TASK_MISMATCH");
        if (op.state() != TreeHarvestNative.State.RECOVERY_REQUIRED || !op.savedKnownTerminal()) throw new IllegalStateException("TREE_POSITIVE_SAVED_RESULT_REQUIRED");
        var residuals = dispatcher.inspectOwned(ctx, () -> inspectRecovery(ctx.actor(), op));
        var review = new TreeHarvestReconciliation(1, UUID.randomUUID(), id, ctx.actor().owner(), ctx.taskId(), ctx.workspace().metadata().serverInstanceId(),
                ctx.workspace().primaryConversationId(), ctx.workspace().conversation().privacyEpoch(), epoch, op.revision(), AiJson.digest(AiJson.canonical(op)),
                System.currentTimeMillis() + 120000, residuals, "").signed();
        synchronized (ctx.workspace().conversation()) {
            ctx.control().check(); validateReconciliationVersion(ctx.workspace(), ctx.actor(), review);
            if (reconciliations.size() >= 128) reconciliations.values().removeIf(value -> value.expiresAt() < System.currentTimeMillis());
            if (reconciliations.size() >= 128) throw new IllegalStateException("TREE_RECOVERY_PREVIEW_LIMIT");
            ctx.workspace().conversation().append(List.of(new ConversationStore.Draft(ctx.taskId(), "tree_reconcile_preview", null, AiJson.GSON.toJsonTree(review).getAsJsonObject(), review.id().toString())));
            reconciliations.put(review.id(), review);
        }
        return review;
    }
    private List<TreeHarvestReconciliation.Residual> inspectRecovery(Actor actor, TreeHarvestJournal.Operation op) {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (closed || broken || !enabled.getAsBoolean()) throw new IllegalStateException("TREE_SERVICE_UNAVAILABLE");
        var running = jobs.get(op.plan().id()); if (running != null && !running.done.isDone()) throw new IllegalStateException("TREE_NATIVE_WORK_STILL_ACTIVE");
        ServerPlayer owner = inventory.requirePlayer(actor); var level = owner.serverLevel();
        if (!level.dimension().location().toString().equals(op.plan().dimension()) || !op.savedKnownTerminal()) throw new IllegalStateException("TREE_RECOVERY_DIMENSION_OR_EVIDENCE_CHANGED");
        var levels = new ArrayList<ServerLevel>(); for (var candidate : server.getAllLevels()) { if (levels.size() >= 256) throw new IllegalStateException("TREE_RECOVERY_DIMENSION_LIMIT"); levels.add(candidate); }
        for (UUID id : op.observation().fallingEntities()) for (var candidate : levels)
            if (candidate.getEntity(id) != null) throw new IllegalStateException("TREE_NATIVE_FALLING_ENTITY_STILL_PRESENT");
        var result = new ArrayList<TreeHarvestReconciliation.Residual>(); var approved = new HashSet<>(cells(op.plan()));
        for (var drop : op.observation().drops()) {
            int accepted = op.observation().pickups().stream().filter(pickup -> pickup.entityId().equals(drop.entityId())).mapToInt(TreeHarvestNative.Pickup::accepted).sum();
            int remaining = Math.subtractExact(drop.stack().count(), accepted);
            if (remaining < 0) throw new IllegalStateException("TREE_RECOVERY_DROP_CONSERVATION_INVALID");
            net.minecraft.world.entity.Entity found = null;
            for (var candidate : levels) if (candidate.getEntity(drop.entityId()) != null) {
                if (found != null) throw new IllegalStateException("TREE_RECOVERY_ENTITY_DUPLICATE"); found = candidate.getEntity(drop.entityId());
            }
            if (remaining == 0) { if (found != null) throw new IllegalStateException("TREE_RECOVERY_REMOVED_DROP_PRESENT"); continue; }
            if (!(found instanceof net.minecraft.world.entity.item.ItemEntity item) || item.getClass() != net.minecraft.world.entity.item.ItemEntity.class || item.level() != level
                    || !op.plan().id().toString().equals(DynamicTreeNativeScope.operation(item))
                    || !approved.contains(item.blockPosition().asLong()) || !ItemSnapshotCodec.capture(item.getItem(), owner.registryAccess()).equals(drop.stack().withCount(remaining)))
                throw new IllegalStateException("TREE_FROZEN_DROP_RECONCILIATION_REQUIRED");
            guardCommon(owner, item.blockPosition());
            result.add(new TreeHarvestReconciliation.Residual(item.getUUID(), ItemSnapshotCodec.capture(item.getItem(), owner.registryAccess()), item.getX(), item.getY(), item.getZ()));
        }
        result.sort(Comparator.comparing(TreeHarvestReconciliation.Residual::entityId)); return List.copyOf(result);
    }
    public TreeHarvestReconciliation loadReconciliation(Workspace workspace, Actor actor, UUID id, String hash) {
        actor.requireAccess(workspace.owner()); var review = reconciliations.get(id);
        if (review == null || !review.hash().equals(hash)) throw new IllegalStateException("TREE_RECONCILIATION_PREVIEW_CHANGED");
        review.requireIdentity(workspace, actor, epoch); return review;
    }
    public void validateReconciliationVersion(Workspace workspace, Actor actor, TreeHarvestReconciliation review) throws IOException {
        review.requireIdentity(workspace, actor, epoch); review.requireCurrent(prepare(workspace).get(actor, review.operationId()));
    }
    public String reconciliationReview(TreeHarvestReconciliation review) {
        return "复核本人原生树采集的完整已知终态与每个已接受阶段的canonical保存凭据。"
                + "\n已核验没有仍在运行/未收束的倒树；真实余物已按原生UUID、组件、数量和当前位置逐项核验：\n" + AiJson.GSON.toJson(review.residuals())
                + "\n批准只收束历史并释放原库存/世界/余物隔离；不砍树、不重放FutureBreak、不拾取或补发物品、不把世界余物计入本人库存。"
                + "\n原目标仍由原任务核验；未知原生阶段或缺保存不适用此入口。\n复核摘要：" + review.hash();
    }
    public AgentTool.Result reconcile(AgentTool.Context ctx, TreeHarvestReconciliation review) throws Exception {
        if (!Objects.equals(ctx.taskId(), review.taskId())) throw new SecurityException("TREE_TASK_MISMATCH");
        validateReconciliationVersion(ctx.workspace(), ctx.actor(), review); var repo = prepare(ctx.workspace()); var original = repo.get(ctx.actor(), review.operationId());
        var now = dispatcher.inspectOwned(ctx, () -> inspectRecovery(ctx.actor(), original));
        if (!now.equals(review.residuals())) throw new IllegalStateException("TREE_RECOVERY_RESIDUALS_CHANGED");
        var accepted = repo.acknowledgeSavedTerminal(ctx.actor(), review.operationId(), review.operationRevision(), review.hash());
        DynamicTreeNativeScope.settled(server, accepted.plan().id());
        var resource = resources.remove(accepted.plan().id()); if (resource != null) materials.releaseNativeScope(ctx.actor(), resource);
        var world = worldResources.remove(accepted.plan().id()); if (world != null) materials.releaseNativeWorldScope(ctx.actor(), world);
        server.execute(() -> { var binding = bindings.remove(accepted.plan().id()); if (binding != null) binding.world().close(); });
        return result(true, "", "已依据已知终态与正向原生保存凭据收束历史；未重砍、重拾或补发，世界余物仍未计入库存。", status(accepted));
    }
    public JsonObject adapterEvidence() {
        var data = new JsonObject(); data.addProperty("mod", "dynamictrees@1.7.2;dynamictreesplus@1.3.2");
        try { DynamicTreeAccess.requireVersion(); data.addProperty("state", "NATIVE_CONTRACT_AVAILABLE"); }
        catch (RuntimeException invalid) { data.addProperty("state", "UNSUPPORTED_OR_UNAVAILABLE"); data.addProperty("reason", TreeHarvestNative.safe(invalid)); }
        data.addProperty("runtimeVerified", false); return data;
    }
    public void register(ToolRegistry tools) {
        tool(tools, "tree_harvest_plan", "调查附近可实际触及树根的真实动态树种、根和完整网络/树冠/邻接影响范围，自动生成有界只读候选，无须手工两角点。默认树plan独立范围，不受旧施工选区硬卡；useSelectedRegion=true才显式复用已有确认选区。完整footprint由本计划工作台审批确认，读取不授权。远程起手强制确认区域及独立权限。只规划，不砍伐或抽掉落。",
                "{\"position\":{\"type\":\"array\",\"minItems\":3,\"maxItems\":3,\"items\":{\"type\":\"integer\"}},\"face\":{\"type\":\"string\",\"enum\":[\"DOWN\",\"UP\",\"NORTH\",\"SOUTH\",\"EAST\",\"WEST\"]},\"useSelectedRegion\":{\"type\":\"boolean\",\"default\":false}}", List.of("position"), AgentTool.Risk.READ_ONLY, (ctx, args) -> {
                    var point = args.getAsJsonArray("position"); var plan = plan(ctx, new BlockPos(point.get(0).getAsInt(), point.get(1).getAsInt(), point.get(2).getAsInt()), Direction.valueOf(args.has("face") ? args.get("face").getAsString() : "UP"), args.has("useSelectedRegion") && args.get("useSelectedRegion").getAsBoolean());
                    var data = AiJson.GSON.toJsonTree(plan).getAsJsonObject(); data.remove("footprint"); data.addProperty("approvedCells", plan.footprint().size());
                    return result(true, "", "已调查原生树种与完整范围，等待工作台固定审批。", data);
                });
        tool(tools, "tree_harvest_execute", "按工作台已批准的固定整树计划执行真实生存挖掘、原生FutureBreak和倒树掉落。false原生返回不重试；取消或不确定副作用先对账。",
                "{\"planId\":{\"type\":\"string\"},\"planHash\":{\"type\":\"string\"}}", List.of("planId", "planHash"), AgentTool.Risk.WORLD_CHANGE,
                (ctx, args) -> execute(ctx, load(ctx.workspace(), ctx.actor(), UUID.fromString(args.get("planId").getAsString()), args.get("planHash").getAsString())));
        tool(tools, "tree_harvest_status", "读取本人原生树采集的稳定operationId、实际工具/饥饿/世界变更/倒树与掉落实体UUID、保存及恢复状态。",
                "{\"operationId\":{\"type\":\"string\"}}", List.of("operationId"), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> result(true, "", "本人原生树采集日志。", status(prepare(ctx.workspace()).get(ctx.actor(), UUID.fromString(args.get("operationId").getAsString())))));
        tool(tools, "tree_harvest_reconcile", "依据新的固定审批复核已知原生树采集终态、完整canonical保存和真实余物。只收束历史，不重砍/重放FutureBreak、不拾取或补发。",
                "{\"reviewId\":{\"type\":\"string\"},\"reviewHash\":{\"type\":\"string\"}}", List.of("reviewId", "reviewHash"), AgentTool.Risk.WORLD_CHANGE,
                (ctx, args) -> reconcile(ctx, loadReconciliation(ctx.workspace(), ctx.actor(), UUID.fromString(args.get("reviewId").getAsString()), args.get("reviewHash").getAsString())));
    }
    private void tool(ToolRegistry tools, String name, String description, String properties, List<String> required, AgentTool.Risk risk, DefinedTool.Action action) {
        var schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", JsonParser.parseString(properties));
        schema.add("required", AiJson.GSON.toJsonTree(required)); schema.addProperty("additionalProperties", false);
        tools.register(new DefinedTool(new AgentTool.Descriptor(name, description, schema, risk, "player.own", 0, AgentTool.ExecutionThread.IO, 120000, true, ""), action));
    }
    private static JsonObject status(TreeHarvestJournal.Operation op) { var data = AiJson.GSON.toJsonTree(op).getAsJsonObject(); data.remove("plan"); data.addProperty("operationId", op.plan().id().toString()); data.addProperty("taskId", op.plan().taskId().toString()); data.addProperty("planHash", op.plan().hash()); data.addProperty("settled", op.settled()); return data; }
    private static String backend(String owner) { return "player:" + owner; }
    private static List<Long> cells(TreeHarvestPlan plan) { return plan.footprint().stream().map(cell -> DynamicTreeAccess.nativePos(cell.position()).asLong()).toList(); }
    private static AgentTool.Result result(boolean success, String code, String summary, JsonObject data) { return new AgentTool.Result(success, code, summary, data, "native-dynamic-trees-journal", "", false); }
    @Override public void close() { closed = true; for (Job job : jobs.values()) { job.cancel = true; job.next.complete(null); } workers.shutdown(); }
    public boolean awaitStopped(long millis) throws InterruptedException { return workers.awaitTermination(millis, TimeUnit.MILLISECONDS); }
}
