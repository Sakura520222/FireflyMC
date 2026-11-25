package firefly520.fireflymc.ai.integration.exploration;

import com.google.gson.*;
import com.mojang.datafixers.util.Pair;
import firefly520.fireflymc.ai.blueprint.NativeConstructionSave;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import static firefly520.fireflymc.ai.integration.exploration.CompassSearchJournal.*;

/** Authenticated native compass searches. No command, teleport, material-cost waiver or automatic replay exists. */
public final class CompassSearchService implements AutoCloseable {
    private static final class PreparationNeeded extends RuntimeException {
        final ChunkPos chunk;
        PreparationNeeded(ChunkPos chunk) { super("COMPASS_PREPARATION_PENDING", null, false, false); this.chunk = chunk; }
    }
    private static final String NODE = "fireflymc.ai.exploration.search";
    private final MinecraftServer server;
    private final Predicate<ServerPlayer> authenticated;
    private final InstalledPermissionAdapters permissions;
    private final VirtualProcessingService dispatcher;
    private final LongSupplier generation;
    private final BooleanSupplier enabled;
    private final UUID epoch = UUID.randomUUID();
    private final NativeSaveObserver saves;
    private final Map<String, CompassSearchJournal> journals = new ConcurrentHashMap<>();
    private final Map<String, CompassPreparationJournal> preparationJournals = new ConcurrentHashMap<>();
    private final Map<UUID, CompassPreparationPlan> preparationPlans = new ConcurrentHashMap<>();
    private final CompassPreparationGate preparationGate = new CompassPreparationGate();
    private NativeCompassPreparation nativePreparation;
    private volatile java.util.function.Predicate<String> capabilityPolicy = ignored -> true;
    public void capabilityPolicy(java.util.function.Predicate<String> policy) { capabilityPolicy = Objects.requireNonNull(policy); }
    private final Map<CompassSearchPlan.Kind, CompassNativeAccess> adapters = new EnumMap<>(CompassSearchPlan.Kind.class);
    private final Map<UUID, Binding> bindings = new ConcurrentHashMap<>();
    private final Map<UUID, RuntimeJob> jobs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor evidence = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256),
            Thread.ofPlatform().daemon(true).name("fireflymc-compass-evidence").factory(), new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed, broken;
    private final CompassTurnScheduler turns = new CompassTurnScheduler();
    private record Binding(CompassSearchPlan plan, ServerPlayer player, ServerLevel level, ItemStack stack, CompassNativeAccess access) {}
    private record StartEvidence(boolean entered, boolean completed, String reason, Observation after, int levelsAfter, NativeSaveBarrier.Receipt receipt) {}
    public CompassSearchService(MinecraftServer server, Predicate<ServerPlayer> authenticated, InstalledPermissionAdapters permissions,
                                VirtualProcessingService dispatcher, LongSupplier generation, BooleanSupplier enabled) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        this.server = server; this.authenticated = authenticated; this.permissions = permissions; this.dispatcher = dispatcher; this.generation = generation;
        this.enabled = Objects.requireNonNull(enabled);
        saves = NativeSaveObserver.forServer(server);
    }
    public CompassSearchJournal prepare(Workspace workspace) throws IOException {
        var known = journals.get(workspace.owner()); if (known != null) return known;
        synchronized (journals) {
            known = journals.get(workspace.owner()); if (known != null) return known;
            if (closed) throw new IllegalStateException("AI_STOPPING");
            try {
                var preparation = new CompassPreparationJournal(workspace);
                var actor = new Actor(workspace.owner(), true, false, 0);
                for (var op : preparation.snapshot(actor)) if (!op.settled()) preparationGate.restoreUnknown(op.owner(), op.id());
                preparationJournals.put(workspace.owner(), preparation);
                var created = new CompassSearchJournal(workspace); journals.put(workspace.owner(), created); return created;
            }
            catch (IOException | RuntimeException failure) { broken = true; throw failure; }
        }
    }
    private CompassSearchJournal journal(AgentTool.Context ctx) throws IOException { ctx.actor().requireAccess(ctx.workspace().owner()); return prepare(ctx.workspace()); }
    private ServerPlayer player(Actor actor) {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (!enabled.getAsBoolean()) throw new TaskControl.Stopped("AI_DISABLED");
        var player = server.getPlayerList().getPlayer(UUID.fromString(actor.owner()));
        if (player == null) throw new TaskControl.Stopped("PLAYER_OFFLINE");
        if (!authenticated.test(player)) throw new SecurityException("AUTH_REQUIRED");
        actor.requireAccess(player.getUUID().toString());
        var allowed = permissions.checkBooleanPermission(player, NODE, true);
        if (!allowed.allowed()) throw new SecurityException(allowed.code());
        return player;
    }
    private CompassNativeAccess adapter(CompassSearchPlan.Kind kind) throws ReflectiveOperationException {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        var value = adapters.get(kind);
        if (value == null) {
            try { value = new CompassNativeAccess(kind); adapters.put(kind, value); }
            catch (LinkageError unavailable) { throw new IllegalStateException("COMPASS_INTERFACE_UNAVAILABLE", unavailable); }
        }
        return value;
    }
    private NativeCompassPreparation preparationAdapter() throws ReflectiveOperationException {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (nativePreparation == null) nativePreparation = new NativeCompassPreparation(server, saves);
        return nativePreparation;
    }
    private void requirePreparationPermission(ServerPlayer player) {
        if (!capabilityPolicy.test("compass_prepared_search_execute")) throw new IllegalStateException("COMPASS_PREPARATION_DISABLED");
        var permission = permissions.checkBooleanPermission(player, "fireflymc.ai.exploration.prepare", true);
        if (!permission.allowed()) throw new SecurityException(permission.code());
    }
    public CompassPreparationPlan planPrepared(AgentTool.Context ctx, String target, String hand, CompassSearchBudget requested,
                                              CompassPreparationBudget budget) throws Exception {
        dispatcher.inspectOwned(ctx, () -> { requirePreparationPermission(player(ctx.actor())); preparationAdapter(); return true; });
        var search = plan(ctx, CompassSearchPlan.Kind.STRUCTURE, target, hand, requested);
        var prepared = new CompassPreparationPlan(UUID.randomUUID(), search, budget, "").signed();
        synchronized (ctx.workspace().conversation()) {
            ctx.control().check(); prepared.requireIdentity(ctx.workspace(), ctx.actor(), epoch, generation.getAsLong(), System.currentTimeMillis());
            preparationPlans.values().removeIf(value -> value.search().expiresAt() <= System.currentTimeMillis());
            if (preparationPlans.size() >= 256) throw new IllegalStateException("COMPASS_PREPARATION_PREVIEW_LIMIT");
            AiJson.write(ctx.workspace().artifact(prepared.id()), prepared);
            var manifest = new JsonObject(); manifest.addProperty("preparationId", prepared.id().toString()); manifest.addProperty("hash", prepared.hash());
            manifest.addProperty("searchId", search.id().toString()); manifest.addProperty("searchHash", search.hash());
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "compass_prepared_search_plan", null, manifest, null)));
            preparationPlans.put(prepared.id(), prepared);
        }
        return prepared;
    }
    public CompassPreparationPlan loadPrepared(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) {
            boolean indexed = workspace.conversation().getSnapshot(0).events().stream().anyMatch(event -> event.kind().equals("compass_prepared_search_plan")
                    && event.data() != null && event.data().has("preparationId") && id.toString().equals(event.data().get("preparationId").getAsString())
                    && event.data().has("hash") && hash.equals(event.data().get("hash").getAsString()));
            var file = workspace.artifact(id);
            if (!indexed || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 32768) throw new IllegalArgumentException("COMPASS_PREPARATION_PLAN_NOT_FOUND");
            var loaded = AiJson.GSON.fromJson(Files.readString(file), CompassPreparationPlan.class);
            if (loaded == null || !loaded.id().equals(id) || !loaded.hash().equals(hash)) throw new SecurityException("COMPASS_PREPARATION_PLAN_CHANGED");
            loaded.requireIdentity(workspace, actor, epoch, generation.getAsLong(), System.currentTimeMillis());
            var original = preparationPlans.get(id);
            if (!loaded.equals(original)) throw new IllegalStateException("COMPASS_PREPARATION_PREVIEW_EXPIRED");
            load(workspace, actor, loaded.search().id(), loaded.search().hash()); return original;
        }
    }
    public String preparedReview(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        var prepared = loadPrepared(workspace, actor, id, hash); var search = prepared.search();
        return "显式批准有界原生结构搜索及未加载候选准备。\n真实手持：" + search.itemId() + " / " + search.hand()
                + "；目标：" + search.targetId() + "；维度：" + search.dimension()
                + "\n起点水平每轴±" + search.budget().radiusBlocks() + "方块、最多" + search.budget().maxChecks() + "次检查、总计" + search.budget().maxMillis() + "ms。"
                + "\n允许准备最多" + prepared.budget().maximumChunks() + "个实际原生候选，仅STRUCTURE_STARTS阶段，不请求FULL地形；服务器同时最多1个在途准备。"
                + "\n准备期限" + prepared.budget().maximumMillis() + "ms；每次重新校验本人权限、连接、罗盘、配置、范围及预算。"
                + "\n开始搜索按原生扣" + search.expectedXpLevels() + "级，成功损耗真实罗盘；整个搜索只启动/收费一次。取消不能撤回已派发生成，不自动退款、重派、传送或猜测未命中。"
                + "\n已派发准备必须记录真实终态和canonical保存；未知或取消后的未决效果需要新预览复核。坐标隐藏：" + !search.coordinatesVisible()
                + "\n原搜索摘要：" + search.hash() + "\n新增批准摘要：" + prepared.hash();
    }
    public CompassSearchPlan plan(AgentTool.Context ctx, CompassSearchPlan.Kind kind, String target, String hand, CompassSearchBudget requested) throws Exception {
        ctx.actor().requireAccess(ctx.workspace().owner()); if (ctx.taskId() == null) throw new IllegalArgumentException("COMPASS_TASK_REQUIRED");
        var plan = dispatcher.inspectOwned(ctx, () -> {
            var player = player(ctx.actor()); var access = adapter(kind); var stack = player.getItemInHand(InteractionHand.valueOf(hand));
            if (!access.accepts(stack)) throw new IllegalArgumentException("COMPASS_REAL_HELD_ITEM_REQUIRED");
            var targetId = ResourceLocation.parse(target); access.requireTarget(player, targetId); var nativeConfig = access.configuration(player, targetId);
            var observed = access.observe(player, stack);
            if (observed.nativeState().equals("UNKNOWN_NATIVE_STATE")) throw new IllegalStateException("COMPASS_NATIVE_STATE_INVALID");
            if (observed.broken()) throw new IllegalStateException("COMPASS_BROKEN");
            if (player.getCooldowns().isOnCooldown(stack.getItem())) throw new IllegalStateException("COMPASS_COOLDOWN");
            if (access.busy(stack)) throw new IllegalStateException("COMPASS_NATIVE_SEARCH_BUSY");
            if (player.experienceLevel < nativeConfig.costLevels()) throw new IllegalStateException("COMPASS_XP_LEVELS_REQUIRED");
            var pos = player.blockPosition();
            var budget = new CompassSearchBudget(Math.min(requested.radiusBlocks(), nativeConfig.radius()),
                    Math.min(requested.maxChecks(), nativeConfig.maxSamples()), requested.maxMillis());
            var value = new CompassSearchPlan(1, UUID.randomUUID(), ctx.actor().owner(), ctx.workspace().metadata().serverInstanceId(),
                    ctx.workspace().primaryConversationId(), ctx.workspace().conversation().privacyEpoch(), epoch, ctx.taskId(), generation.getAsLong(),
                    System.currentTimeMillis() + 120000, kind, targetId.toString(), hand, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), observed.itemFingerprint(),
                    player.serverLevel().dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(), nativeConfig.fingerprint(), nativeConfig.costLevels(), nativeConfig.visible(), budget, "").signed();
            long now = System.currentTimeMillis(); bindings.values().removeIf(binding -> binding.plan().expiresAt() <= now);
            bindings.put(value.id(), new Binding(value, player, player.serverLevel(), stack, access));
            if (bindings.size() > 256) { bindings.remove(value.id()); throw new IllegalStateException("COMPASS_PREVIEW_LIMIT"); }
            return value;
        });
        synchronized (ctx.workspace().conversation()) {
            ctx.control().check(); plan.requireIdentity(ctx.workspace(), ctx.actor(), epoch, generation.getAsLong(), System.currentTimeMillis());
            if (Files.exists(ctx.workspace().artifact(plan.id()), LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("COMPASS_PREVIEW_ALREADY_EXISTS");
            AiJson.write(ctx.workspace().artifact(plan.id()), plan);
            var manifest = new JsonObject(); manifest.addProperty("planId", plan.id().toString()); manifest.addProperty("hash", plan.hash());
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "compass_search_plan", null, manifest, null)));
        }
        return plan;
    }
    public CompassSearchPlan load(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) {
            boolean indexed = workspace.conversation().getSnapshot(0).events().stream().anyMatch(event -> event.kind().equals("compass_search_plan")
                    && event.data() != null && event.data().has("planId") && id.toString().equals(event.data().get("planId").getAsString())
                    && event.data().has("hash") && hash.equals(event.data().get("hash").getAsString()));
            var path = workspace.artifact(id);
            if (!indexed || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 32768) throw new IllegalArgumentException("COMPASS_PLAN_NOT_FOUND");
            var loaded = AiJson.GSON.fromJson(Files.readString(path), CompassSearchPlan.class);
            if (loaded == null || !id.equals(loaded.id()) || !hash.equals(loaded.hash())) throw new SecurityException("COMPASS_PLAN_CHANGED");
            loaded.requireIdentity(workspace, actor, epoch, generation.getAsLong(), System.currentTimeMillis());
            var binding = bindings.get(id);
            if (binding == null || !binding.plan().equals(loaded)) throw new IllegalStateException("COMPASS_PREVIEW_RUNTIME_EXPIRED");
            return binding.plan();
        }
    }
    public String review(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        var plan = load(workspace, actor, id, hash);
        return "原生罗盘受限搜索：" + plan.kind() + " " + plan.targetId() + "\n真实手持：" + plan.itemId() + " / " + plan.hand()
                + "\n起点：" + plan.dimension() + " " + plan.originX() + " " + plan.originY() + " " + plan.originZ()
                + "\n范围：起点水平每轴 ±" + plan.budget().radiusBlocks() + " 方块；至多 " + plan.budget().maxChecks() + " 次候选/噪声检查，"
                + plan.budget().maxMillis() + " ms。分 Tick、公平调度，单次原生调用不可抢占。\n"
                + (plan.kind() == CompassSearchPlan.Kind.STRUCTURE ? "结构候选仅限当前已加载区块；遇到未加载候选停止并报告未完成，不生成新区块。\n"
                    : "使用真实生成器噪声生物群系采样，不生成区块；结果不代表地形或通行已验证。\n")
                + "原生开始费用：" + plan.expectedXpLevels() + " 级；成功时按原配置损耗真实罗盘。取消、超时或未命中不自动退还已经发生的原生费用。"
                + "\n原生坐标显示：" + plan.coordinatesVisible() + "；保持隐藏设置。无传送、无命令、无自动重放。\n计划摘要：" + plan.hash();
    }
    public AgentTool.Result execute(AgentTool.Context ctx, CompassSearchPlan plan) throws Exception {
        return execute(ctx, plan, null);
    }
    public AgentTool.Result executePrepared(AgentTool.Context ctx, CompassPreparationPlan prepared) throws Exception {
        prepared.requireIdentity(ctx.workspace(), ctx.actor(), epoch, generation.getAsLong(), System.currentTimeMillis());
        dispatcher.inspectOwned(ctx, () -> { requirePreparationPermission(player(ctx.actor())); preparationAdapter(); return true; });
        return execute(ctx, prepared.search(), prepared);
    }
    private AgentTool.Result execute(AgentTool.Context ctx, CompassSearchPlan plan, CompassPreparationPlan prepared) throws Exception {
        if (closed || broken) throw new IllegalStateException("AI_STOPPING");
        plan.requireIdentity(ctx.workspace(), ctx.actor(), epoch, generation.getAsLong(), System.currentTimeMillis());
        if (!Objects.equals(plan.taskId(), ctx.taskId())) throw new SecurityException("COMPASS_TASK_MISMATCH");
        var journal = journal(ctx);
        if (journal.find(ctx.actor(), plan.id()).isPresent()) return result(false, "COMPASS_OPERATION_ALREADY_RECORDED", "已有记录，未重搜、未重复扣经验。", viewWithPreparation(ctx.workspace(), ctx.actor(), journal.get(ctx.actor(), plan.id())));
        RuntimeJob job;
        synchronized (jobs) {
            if (closed || broken) throw new IllegalStateException("AI_STOPPING");
            if (jobs.values().stream().filter(value -> !value.terminalQueued).count() >= 4
                    || jobs.values().stream().anyMatch(value -> !value.terminalQueued && value.plan.owner().equals(ctx.actor().owner())))
                throw new IllegalStateException("COMPASS_SEARCH_QUOTA");
            var binding = bindings.get(plan.id()); if (binding == null || binding.plan() != plan) throw new SecurityException("COMPASS_PLAN_CHANGED");
            job = new RuntimeJob(ctx, binding, journal, prepared); jobs.put(plan.id(), job);
        }
        try {
            var before = dispatcher.inspectOwned(ctx, () -> { job.live(true); return job.binding.access().observe(job.binding.player(), job.binding.stack()); });
            int levels = dispatcher.inspectOwned(ctx, () -> job.binding.player().experienceLevel);
            job.last = before; job.levelsAfterStart = levels;
            journal.create(ctx.actor(), plan, before, levels); job.journalCreated = true;
            if (prepared != null) preparationJournals.get(ctx.actor().owner()).create(ctx.actor(), prepared);
            dispatcher.commitOwned(ctx, job::begin, actual -> {
                job.recordStart(actual);
                // This shared dispatcher returns only the native effect to commitOwned callers;
                // exploration has its own durable journal rather than a materials ledger operation.
                return null;
            });
        } catch (CommittedCall.Rejected rejected) {
            var op = journal.find(ctx.actor(), plan.id());
            if (op.isPresent()) {
                if (prepared != null) preparationJournals.get(ctx.actor().owner()).finishAdmission(ctx.actor(), plan.id());
                journal.finished(ctx.actor(), plan.id(), State.STOPPED, "REJECTED_BEFORE_NATIVE_START", false, true, job.last, job.levelsAfterStart, job.emptyResult());
                job.startRecorded.complete(null); job.done.complete(null);
            }
            jobs.remove(plan.id()); if (rejected.getCause() instanceof TaskControl.Stopped stopped) throw stopped;
            return finishKnown(ctx, plan.id());
        } catch (Exception failure) {
            job.startRecorded.complete(null);
            if (journal.find(ctx.actor(), plan.id()).isEmpty()) { jobs.remove(plan.id()); job.done.complete(null); }
            else { journal.requireRecovery(ctx.actor(), plan.id(), "NATIVE_START_ACKNOWLEDGEMENT_UNCERTAIN"); job.done.complete(null); }
            throw failure;
        }
        return waitForKnown(ctx, job.plan.id(), job.done);
    }
    private AgentTool.Result waitForKnown(AgentTool.Context ctx, UUID id, CompletionStage<?> ready) throws Exception {
        if (ready.toCompletableFuture().isDone()) return finishKnown(ctx, id);
        var checkpoint = new JsonObject(); checkpoint.addProperty("operationId", id.toString()); checkpoint.addProperty("phase", "NATIVE_COMPASS_SEARCH");
        throw new AgentTool.Deferred("NATIVE_COMPASS_SEARCH", checkpoint, ready, fresh -> finishKnown(fresh, id));
    }
    private AgentTool.Result finishKnown(AgentTool.Context ctx, UUID id) throws Exception {
        var journal = journal(ctx); var op = journal.get(ctx.actor(), id);
        if (!Objects.equals(op.taskId(), ctx.taskId())) throw new SecurityException("COMPASS_TASK_MISMATCH");
        if (!op.terminalKnown() || op.state() == State.RECOVERY_REQUIRED)
            return result(false, "RECOVERY_REQUIRED", "搜索结果或保存义务需复核；没有重搜、退款或伪造坐标。", viewWithPreparation(ctx.workspace(), ctx.actor(), op));
        if (!op.saved()) {
            var ready = saves.awaitJournalled(id);
            if (ready.toCompletableFuture().isDone()) {
                journal.requireRecovery(ctx.actor(), id, "NATIVE_SAVE_NOT_CONFIRMED");
                return result(false, "RECOVERY_REQUIRED", "原生结果已记录，但保存凭据未确认。", viewWithPreparation(ctx.workspace(), ctx.actor(), journal.get(ctx.actor(), id)));
            }
            var checkpoint = new JsonObject(); checkpoint.addProperty("operationId", id.toString()); checkpoint.addProperty("phase", "WAITING_COMPASS_NATIVE_SAVE");
            throw new AgentTool.Deferred("WAITING_COMPASS_NATIVE_SAVE", checkpoint, ready.handle((ignored, failure) -> null), fresh -> finishKnown(fresh, id));
        }
        var preparation = preparationJournals.get(ctx.actor().owner()).find(ctx.actor(), id);
        if (preparation.isPresent() && !preparation.get().settled()) {
            var ready = saves.awaitJournalled(id);
            if (!ready.toCompletableFuture().isDone()) {
                var checkpoint = new JsonObject(); checkpoint.addProperty("operationId", id.toString()); checkpoint.addProperty("phase", "WAITING_COMPASS_PREPARATION_SAVE");
                throw new AgentTool.Deferred("WAITING_COMPASS_PREPARATION_SAVE", checkpoint, ready.handle((ignored, failure) -> null), fresh -> finishKnown(fresh, id));
            }
            journal.requireRecovery(ctx.actor(), id, "COMPASS_PREPARATION_RECONCILIATION_REQUIRED");
            return result(false, "RECOVERY_REQUIRED", "已派发的结构准备需要固定版本复核；未重派、退款或撤回生成。", viewWithPreparation(ctx.workspace(), ctx.actor(), journal.get(ctx.actor(), id)));
        }
        if (preparation.isPresent()) preparationGate.retire(ctx.actor(), id);
        jobs.remove(id);
        return result(op.state() == State.FOUND, op.state() == State.FOUND ? "" : op.reason(),
                op.state() == State.FOUND ? op.result().coordinatesVisible() ? "原生罗盘已找到目标，并核验本次结果与保存凭据。" : "原生罗盘已找到目标；按原设置隐藏坐标。"
                        : "本次受限搜索已停止或未命中；不是全范围不存在的证明，费用与罗盘变化见回执。", viewWithPreparation(ctx.workspace(), ctx.actor(), op));
    }
    private boolean claimTurn(RuntimeJob job) {
        return turns.claim(server.getTickCount(), job.plan.id(), jobs.values().stream()
                .filter(value -> value.ready && !value.terminalQueued).map(value -> value.plan.id()).toList());
    }
    public void tick() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        for (var job : List.copyOf(jobs.values())) if (job.ready && !job.terminalQueued) {
            try { job.control.check(); job.meter.checkTime(); if (closed) throw new TaskControl.Stopped("SHUTDOWN"); job.tryPreparation(); }
            catch (RuntimeException failure) { job.stop(safe(failure), true); }
        }
        for (var entry : List.copyOf(jobs.entrySet())) if (entry.getValue().terminalQueued && entry.getValue().done.isDone() && entry.getValue().saveConfirmed) {
            jobs.remove(entry.getKey(), entry.getValue()); bindings.remove(entry.getKey());
        }
    }
    private final class RuntimeJob implements NativeCompassSearchScope.Job {
        final AgentTool.Context context;
        final CompassSearchPlan plan;
        final Binding binding;
        final CompassSearchJournal journal;
        final TaskControl control;
        final CompassSearchBudget.Meter meter;
        final Set<Object> workers = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<NativeSaveBarrier.Receipt> receipts = new ArrayList<>();
        final List<CompletableFuture<Optional<NativeSaveBarrier.Receipt>>> preparationReceipts = new ArrayList<>();
        final Map<Long, net.minecraft.world.level.chunk.ChunkAccess> preparedChunks = new HashMap<>();
        final Map<Long, Pair<ChunkPos, Structure>> structureMatches = new HashMap<>();
        final CompassPreparationPlan preparation;
        final CompassPreparationBudget.Deadline preparationDeadline;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        final CompletableFuture<Void> startRecorded = new CompletableFuture<>();
        volatile boolean ready, terminalQueued, journalCreated, nativeCaptured, saveConfirmed;
        volatile String stopRequested;
        Observation last;
        int levelsAfterStart;
        BlockPos lastBiome, matchedPosition;
        Object samplingWorker;
        ChunkPos matchedChunk;
        Structure matchedStructure;
        ChunkPos desiredPreparation;
        PendingPreparation pendingPreparation;
        final class PendingPreparation {
            final ChunkPos chunk; final UUID attempt = UUID.randomUUID(); final CompassPreparationGate.Lease lease;
            final CompletableFuture<Optional<NativeSaveBarrier.Receipt>> save = new CompletableFuture<>();
            NativeCompassPreparation.Request nativeRequest;
            PendingPreparation(ChunkPos chunk, CompassPreparationGate.Lease lease) { this.chunk = chunk; this.lease = lease; }
        }
        RuntimeJob(AgentTool.Context context, Binding binding, CompassSearchJournal journal, CompassPreparationPlan preparation) {
            this.context = context; this.binding = binding; plan = binding.plan(); this.journal = journal; control = context.control();
            this.preparation = preparation; preparationDeadline = preparation == null ? null : preparation.budget().start(System::nanoTime);
            meter = new CompassSearchBudget.Meter(plan.budget(), plan.originX(), plan.originZ(), System::nanoTime);
        }
        void live(boolean initial) throws Exception {
            control.check(); if (closed || broken) throw new TaskControl.Stopped("SHUTDOWN");
            var current = player(context.actor());
            if (preparation != null) requirePreparationPermission(current);
            if (current != binding.player() || current.serverLevel() != binding.level() || ctxPrivacyChanged()) throw new IllegalStateException("COMPASS_IDENTITY_OR_DIMENSION_CHANGED");
            if (generation.getAsLong() != plan.registryGeneration()) throw new IllegalStateException("COMPASS_REGISTRIES_RELOADED");
            var held = current.getItemInHand(InteractionHand.valueOf(plan.hand()));
            if (held != binding.stack()) throw new IllegalStateException("COMPASS_HAND_BINDING_CHANGED");
            var now = binding.access().observe(current, held);
            if (!now.itemFingerprint().equals(initial ? plan.itemFingerprint() : last.itemFingerprint())) throw new IllegalStateException("COMPASS_COMPONENTS_CHANGED");
            if (!initial && !binding.access().stillRegistered(held, workers)) throw new IllegalStateException("COMPASS_NATIVE_SEARCH_REPLACED");
            var cfg = binding.access().configuration(current, ResourceLocation.parse(plan.targetId()));
            if (!cfg.fingerprint().equals(plan.configFingerprint()) || cfg.costLevels() != plan.expectedXpLevels()) throw new IllegalStateException("COMPASS_CONFIG_CHANGED");
            if (initial) {
                plan.requireIdentity(context.workspace(), context.actor(), epoch, generation.getAsLong(), System.currentTimeMillis());
                if (!current.blockPosition().equals(new BlockPos(plan.originX(), plan.originY(), plan.originZ()))) throw new IllegalStateException("COMPASS_ORIGIN_CHANGED");
                binding.access().requireTarget(current, ResourceLocation.parse(plan.targetId()));
                if (now.broken()) throw new IllegalStateException("COMPASS_BROKEN");
                if (current.getCooldowns().isOnCooldown(held.getItem())) throw new IllegalStateException("COMPASS_COOLDOWN");
                if (binding.access().busy(held)) throw new IllegalStateException("COMPASS_NATIVE_SEARCH_BUSY");
                if (current.experienceLevel < plan.expectedXpLevels()) throw new IllegalStateException("COMPASS_XP_LEVELS_REQUIRED");
            }
        }
        boolean ctxPrivacyChanged() { return plan.privacyEpoch() != context.workspace().conversation().privacyEpoch(); }
        StartEvidence begin() {
            boolean entered = false; NativeSaveBarrier.Receipt receipt = null; Observation after = last; String reason = ""; boolean completed = false;
            try {
                live(true);
                try (var scope = NativeConstructionSave.beginEntities(binding.player(), List.of())) {
                    entered = true;
                    try {
                        NativeCompassSearchScope.run(this, () -> invoke(() -> binding.access().start(binding.player(), binding.stack(), ResourceLocation.parse(plan.targetId()))));
                        for (var worker : binding.access().workers(binding.stack())) if (binding.access().ownsWorker(worker, binding.player(), binding.stack())) started(worker);
                        after = binding.access().observe(binding.player(), binding.stack()); levelsAfterStart = binding.player().experienceLevel;
                        if (workers.isEmpty() && after.nativeState().equals("SEARCHING")) throw new IllegalStateException("COMPASS_NATIVE_WORKER_NOT_CAPTURED");
                        completed = true;
                    } catch (RuntimeException | ReflectiveOperationException failure) { reason = safe(failure); }
                    receipt = scope.finish(); receipts.add(receipt);
                }
            } catch (Exception | LinkageError failure) { if (reason.isEmpty()) reason = safe(failure); }
            last = after; levelsAfterStart = binding.player().experienceLevel; nativeCaptured = true;
            return new StartEvidence(entered, completed, reason, after, binding.player().experienceLevel, receipt);
        }
        void recordStart(StartEvidence actual) throws Exception {
            if (!actual.entered()) {
                terminalQueued = true;
                if (preparation != null) preparationJournals.get(context.actor().owner()).finishAdmission(context.actor(), plan.id());
                journal.finished(context.actor(), plan.id(), State.STOPPED, actual.reason(), false, true, actual.after(), levelsAfterStart, emptyResult());
                startRecorded.complete(null); done.complete(null); return;
            }
            journal.started(context.actor(), plan.id(), actual.after(), actual.levelsAfter());
            if (actual.receipt() != null) {
                saves.record(plan.id(), actual.receipt(), proof -> journal.nativeSaved(context.actor(), plan.id(), false, proof));
            }
            startRecorded.complete(null);
            if (terminalQueued) return;
            if (!actual.completed() || actual.receipt() == null) {
                server.execute(() -> stop(actual.reason().isEmpty() ? "COMPASS_NATIVE_START_UNCERTAIN" : actual.reason(), actual.receipt() != null));
            } else if (closed || stopRequested != null) server.execute(() -> stop(stopRequested == null ? "SHUTDOWN" : stopRequested, true));
            else if (actual.after().nativeState().equals("NOT_FOUND")) server.execute(() -> finish(State.NOT_FOUND, "NATIVE_COMPASS_NOT_FOUND", emptyResult(), true));
            else if (!actual.after().nativeState().equals("SEARCHING")) server.execute(() -> stop("COMPASS_NATIVE_START_STATE_CHANGED", false));
            else ready = true;
        }
        @Override public void started(Object worker) {
            try {
                if (!binding.access().ownsWorker(worker, binding.player(), binding.stack())) throw new IllegalStateException("COMPASS_FOREIGN_WORKER");
                binding.access().requireWorkerArguments(worker, plan);
                if (workers.size() >= 64 && !workers.contains(worker)) throw new IllegalStateException("COMPASS_NATIVE_WORKER_LIMIT");
                workers.add(worker);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("COMPASS_WORKER_BINDING_FAILED", failure); }
        }
        @Override public boolean work(Object worker, BooleanSupplier original) {
            if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
            if (!ready || terminalQueued) return false;
            if (desiredPreparation != null || pendingPreparation != null) return false;
            try { live(false); meter.checkTime(); }
            catch (Exception failure) { stop(safe(failure), !"COMPASS_HAND_BINDING_CHANGED".equals(safe(failure))); return false; }
            if (!claimTurn(this)) return false;
            meter.beginSlice(); boolean keep = false;
            String stopped = ""; boolean known = true;
            samplingWorker = worker;
            PreparationNeeded needed = null;
            boolean yieldedSlice = false;
            try (var scope = NativeConstructionSave.beginEntities(binding.player(), List.of())) {
                // Preparing the native save witness can cold-load observer machinery. It is
                // bounded by the total task deadline, but has not executed a search candidate.
                // Start the per-candidate slice immediately before the native worker instead.
                CompassNativeAccess.Cursor cursor = preparation == null ? null : binding.access().cursor(worker);
                int checksBeforeCandidate = meter.checks();
                meter.candidateExecutionStarts();
                try {
                    boolean[] value = {false}; NativeCompassSearchScope.run(this, () -> value[0] = original.getAsBoolean()); keep = value[0];
                } catch (PreparationNeeded waiting) {
                    if (cursor == null) throw new IllegalStateException("COMPASS_PREPARATION_NOT_APPROVED");
                    if (!binding.access().candidateUncommitted(cursor, worker) || meter.checks() != checksBeforeCandidate + 1)
                        throw new IllegalStateException("COMPASS_WORKER_CANDIDATE_CONTINUATION_UNPROVEN");
                    meter.releaseUnexaminedCheck(checksBeforeCandidate);
                    binding.access().restoreCursor(cursor, worker); needed = waiting;
                } catch (CompassSearchBudget.Stopped limit) {
                    if (cursor != null && "COMPASS_SLICE_LIMIT".equals(limit.getMessage())) {
                        // Only the cooperative Tick allowance is exhausted. The exact unexamined
                        // native candidate is retried next Tick; total time/check limits stay fixed.
                        if (!binding.access().candidateUncommitted(cursor, worker) || meter.checks() != checksBeforeCandidate)
                            throw new IllegalStateException("COMPASS_WORKER_CANDIDATE_CONTINUATION_UNPROVEN");
                        binding.access().restoreCursor(cursor, worker); yieldedSlice = true;
                    } else stopped = safe(limit);
                } catch (RuntimeException | LinkageError failure) { stopped = safe(failure); }
                last = binding.access().observe(binding.player(), binding.stack());
                receipts.add(scope.finish());
            } catch (Exception | LinkageError failure) { stopped = safe(failure); known = false; }
            finally { meter.endSlice(); samplingWorker = null; }
            if (!stopped.isEmpty()) { stop(stopped, known); return false; }
            if (yieldedSlice) return false;
            if (needed != null) { desiredPreparation = needed.chunk; tryPreparation(); return false; }
            try {
                if (last.nativeState().equals("FOUND")) { finish(State.FOUND, "", verify(), true); return false; }
                if (last.nativeState().equals("NOT_FOUND")) { finish(State.NOT_FOUND, "NATIVE_COMPASS_NOT_FOUND", emptyResult(), true); return false; }
                if (!last.nativeState().equals("SEARCHING")) { stop("COMPASS_NATIVE_SEARCH_REPLACED", true); return false; }
            } catch (Exception failure) { stop(safe(failure), false); return false; }
            return keep && !terminalQueued;
        }
        @Override public void biomeCheck(int blockX, int blockY, int blockZ) {
            control.check();
            try {
                var sample = binding.access().samplePosition(samplingWorker, blockY);
                meter.beforeCheck(sample.getX(), sample.getZ()); lastBiome = sample;
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("COMPASS_SAMPLE_POSITION_UNAVAILABLE", failure); }
        }
        @Override public void structureCheck(Object worker, ChunkPos candidate) {
            control.check(); meter.beforeCheck(candidate.getMiddleBlockX(), candidate.getMiddleBlockZ());
            if (binding.level().getChunkSource().getChunkNow(candidate.x, candidate.z) == null && !preparedChunks.containsKey(candidate.toLong())) {
                if (preparation == null) throw new CompassSearchBudget.Stopped("COMPASS_UNLOADED_CHUNK_REQUIRED");
                preparationDeadline.beforeDispatch(); throw new PreparationNeeded(candidate);
            }
        }
        @Override public net.minecraft.world.level.chunk.ChunkAccess structureChunk(ServerLevel level, int x, int z,
                                                                                 net.minecraft.world.level.chunk.status.ChunkStatus status) {
            if (level != binding.level()) throw new SecurityException("COMPASS_PREPARATION_DIMENSION_CHANGED");
            var loaded = level.getChunkSource().getChunkNow(x, z);
            var value = loaded != null ? loaded : preparedChunks.get(ChunkPos.asLong(x, z));
            if (value == null || !value.getPersistedStatus().isOrAfter(status)) throw new IllegalStateException("COMPASS_PREPARED_CHUNK_MISSING");
            return value;
        }
        @Override public void structureMatch(ChunkPos chunk, Pair<BlockPos, Structure> found) {
            matchedChunk = chunk; matchedPosition = found.getFirst().immutable(); matchedStructure = found.getSecond();
            if (structureMatches.size() >= 1024) throw new IllegalStateException("COMPASS_MATCH_LIMIT");
            structureMatches.put(new BlockPos(matchedPosition.getX(), 0, matchedPosition.getZ()).asLong(), Pair.of(chunk, matchedStructure));
        }
        void tryPreparation() {
            if (preparation == null || desiredPreparation == null || pendingPreparation != null || terminalQueued) return;
            try {
                live(false); preparationDeadline.beforeDispatch();
                var lease = preparationGate.acquire(context.actor(), plan.id(), plan.dimension(), desiredPreparation.toLong(), preparation.budget());
                if (lease.isEmpty()) return;
                var pending = new PendingPreparation(desiredPreparation, lease.get()); pendingPreparation = pending; desiredPreparation = null;
                preparationReceipts.add(pending.save);
                evidence.execute(() -> {
                    try {
                        var repo = preparationJournals.get(context.actor().owner());
                        repo.intend(context.actor(), plan.id(), pending.attempt, pending.chunk.x, pending.chunk.z);
                        repo.dispatched(context.actor(), plan.id(), pending.attempt);
                        server.execute(() -> dispatchPreparation(pending));
                    } catch (Exception failed) {
                        server.execute(() -> preparationNotEntered(pending, "COMPASS_PREPARATION_INTENT_FAILED"));
                    }
                });
            } catch (Exception failed) { stop(safe(failed), true); }
        }
        void dispatchPreparation(PendingPreparation pending) {
            boolean entering = false;
            try {
                if (terminalQueued) throw new TaskControl.Stopped("COMPASS_SEARCH_STOPPED");
                live(false); preparationDeadline.beforeDispatch(); meter.checkTime();
                requirePreparationPermission(binding.player()); preparationGate.dispatched(context.actor(), pending.lease);
                entering = true;
                pending.nativeRequest = preparationAdapter().dispatch(binding.level(), pending.chunk);
                pending.nativeRequest.future().whenComplete((value, failure) -> server.execute(() -> completePreparation(pending, failure)));
            } catch (Exception | LinkageError failed) {
                if (!entering) preparationNotEntered(pending, safe(failed));
                else preparationUnknown(pending, "COMPASS_PREPARATION_DISPATCH_UNCERTAIN");
            }
        }
        void preparationNotEntered(PendingPreparation pending, String reason) {
            try { preparationGate.cancel(context.actor(), pending.lease); }
            catch (RuntimeException stale) { broken = true; }
            if (pendingPreparation == pending) pendingPreparation = null;
            try { evidence.execute(() -> {
                try {
                    var repo = preparationJournals.get(context.actor().owner());
                    var op = repo.get(context.actor(), plan.id());
                    if (op.candidates().stream().anyMatch(candidate -> candidate.attempt().equals(pending.attempt)))
                        repo.notEntered(context.actor(), plan.id(), pending.attempt, reason);
                    pending.save.complete(Optional.empty());
                } catch (Exception failed) { broken = true; pending.save.completeExceptionally(failed); }
            }); } catch (RejectedExecutionException failed) { broken = true; pending.save.completeExceptionally(failed); }
            if (!terminalQueued) stop(reason, true);
        }
        void completePreparation(PendingPreparation pending, Throwable failure) {
            try {
                if (failure != null) throw new IllegalStateException("COMPASS_PREPARATION_NATIVE_FUTURE_FAILED", failure);
                var completed = preparationAdapter().completed(pending.nativeRequest);
                preparationGate.nativeCompleted(context.actor(), pending.lease);
                preparedChunks.put(pending.chunk.toLong(), completed.chunk());
                if (pendingPreparation == pending) pendingPreparation = null;
                evidence.execute(() -> {
                    try {
                        var repo = preparationJournals.get(context.actor().owner()); repo.ready(context.actor(), plan.id(), pending.attempt, completed.saveReason());
                        saves.record(plan.id(), completed.save(), proof -> repo.nativeSaved(context.actor(), plan.id(), pending.attempt, proof));
                        pending.save.complete(Optional.of(completed.save()));
                    } catch (Exception failed) { broken = true; pending.save.completeExceptionally(failed); }
                });
            } catch (Exception | LinkageError failed) { preparationUnknown(pending, "COMPASS_PREPARATION_NATIVE_RESULT_UNCERTAIN"); }
        }
        void preparationUnknown(PendingPreparation pending, String reason) {
            try {
                preparationGate.restoreUnknown(context.actor().owner(), plan.id());
                if (pending.nativeRequest != null && pending.nativeRequest.future().isDone()) {
                    preparationAdapter().releaseAfterFailedFuture(pending.nativeRequest);
                    preparationGate.nativeCompleted(context.actor(), pending.lease);
                }
            } catch (Exception ignored) { broken = true; }
            pending.save.completeExceptionally(new IllegalStateException(reason));
            try { evidence.execute(() -> {
                try { preparationJournals.get(context.actor().owner()).stopAdmission(context.actor(), plan.id(), reason); }
                catch (Exception failed) { broken = true; }
            }); } catch (RejectedExecutionException failed) { broken = true; }
            stop(reason, false);
        }
        ResultEvidence verify() throws Exception {
            if (!binding.access().target(binding.stack()).equals(plan.targetId())) throw new IllegalStateException("COMPASS_NATIVE_TARGET_CHANGED");
            var found = binding.access().found(binding.stack()); boolean verified;
            Integer sampleY = null;
            if (plan.kind() == CompassSearchPlan.Kind.BIOME) {
                if (lastBiome == null) throw new IllegalStateException("COMPASS_NATIVE_SAMPLE_MISSING");
                var biome = binding.level().getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(QuartPos.fromBlock(found.getX()), QuartPos.fromBlock(lastBiome.getY()),
                        QuartPos.fromBlock(found.getZ()), binding.level().getChunkSource().randomState().sampler());
                verified = biome.unwrapKey().map(key -> key.location().toString().equals(plan.targetId())).orElse(false); sampleY = lastBiome.getY();
            } else {
                var match = structureMatches.get(new BlockPos(found.getX(), 0, found.getZ()).asLong());
                if (match == null)
                    throw new IllegalStateException("COMPASS_NATIVE_MATCH_MISSING");
                var chunk = structureChunk(binding.level(), match.getFirst().x, match.getFirst().z, net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS);
                var target = binding.player().registryAccess().registryOrThrow(Registries.STRUCTURE).get(ResourceLocation.parse(plan.targetId()));
                var start = binding.level().structureManager().getStartForStructure(SectionPos.bottomOf(chunk), target, chunk);
                verified = target == match.getSecond() && start != null && start.isValid();
            }
            if (!verified) throw new IllegalStateException("COMPASS_NATIVE_RESULT_NOT_VERIFIED");
            boolean show = plan.coordinatesVisible() && last.coordinatesVisible();
            return new ResultEvidence(true, show, show ? found.getX() : null, show ? found.getZ() : null, show ? sampleY : null,
                    plan.kind() == CompassSearchPlan.Kind.BIOME ? "SERVER_GENERATOR_BIOME_SAMPLE" : preparation == null ? "SERVER_LOADED_STRUCTURE_START" : "SERVER_BOUNDED_NATIVE_STRUCTURE_START", meter.checks(), meter.frames(), meter.maximumSliceNanos(), meter.sliceOverruns());
        }
        ResultEvidence emptyResult() { return new ResultEvidence(false, false, null, null, null, "NO_FULL_RANGE_ABSENCE_CLAIM", meter.checks(), meter.frames(), meter.maximumSliceNanos(), meter.sliceOverruns()); }
        void stop(String reason, boolean known) {
            if (terminalQueued) return;
            if (pendingPreparation != null) try { preparationGate.cancel(context.actor(), pendingPreparation.lease); }
            catch (RuntimeException changed) { known = false; }
            if (!journalCreated || !nativeCaptured) { stopRequested = reason; control.stop(reason); return; }
            if (binding.player().getItemInHand(InteractionHand.valueOf(plan.hand())) != binding.stack()) known = false;
            if (binding.player().getItemInHand(InteractionHand.valueOf(plan.hand())) == binding.stack()) {
                try (var scope = NativeConstructionSave.beginEntities(binding.player(), List.of())) {
                    if (binding.access().fingerprint(binding.player(), binding.stack()).equals(last.itemFingerprint()) && last.nativeState().equals("SEARCHING")) {
                        NativeCompassSearchScope.run(this, () -> invoke(() -> binding.access().inactive(binding.player(), binding.stack())));
                        last = binding.access().observe(binding.player(), binding.stack());
                    }
                    receipts.add(scope.finish());
                } catch (Exception | LinkageError unknown) { known = false; }
            }
            finish(known ? State.STOPPED : State.RECOVERY_REQUIRED, reason, emptyResult(), known);
        }
        void finish(State state, String reason, ResultEvidence result, boolean known) {
            if (terminalQueued) return; terminalQueued = true; ready = false;
            boolean stoppedAll = true;
            for (var worker : List.copyOf(workers)) try { binding.access().stopWorker(worker); }
            catch (ReflectiveOperationException | RuntimeException failure) { known = false; stoppedAll = false; }
            try { binding.access().clearOnlyOwnedManager(binding.stack(), workers); }
            catch (ReflectiveOperationException | RuntimeException failure) { known = false; stoppedAll = false; }
            // A failed native stop retains a false-returning guard; removing it could run an unbounded orphan.
            if (stoppedAll) NativeCompassSearchScope.unbind(this);
            var after = last; boolean covered = known && (receipts.size() > 0); var receiptsSnapshot = List.copyOf(receipts);
            var preparationSnapshot = List.copyOf(preparationReceipts);
            State finalState = covered ? state : State.RECOVERY_REQUIRED;
            try {
                evidence.execute(() -> {
                    try {
                        startRecorded.get(10000, TimeUnit.MILLISECONDS);
                        if (preparation != null) {
                            var repo = preparationJournals.get(context.actor().owner());
                            if (state == State.FOUND || state == State.NOT_FOUND) repo.finishAdmission(context.actor(), plan.id());
                            else if (!repo.stopAdmission(context.actor(), plan.id(), reason).settled())
                                journal.requireRecovery(context.actor(), plan.id(), "COMPASS_PREPARATION_SIDE_EFFECTS_PENDING");
                        }
                        journal.finished(context.actor(), plan.id(), finalState, reason, true, covered, after, levelsAfterStart, result);
                        if (covered) saves.record(plan.id(), combinePrepared(receiptsSnapshot, preparationSnapshot), proof -> {
                            journal.nativeSaved(context.actor(), plan.id(), true, proof); saveConfirmed = true;
                        });
                        done.complete(null);
                    } catch (Exception failure) { broken = true; done.completeExceptionally(failure); }
                });
            } catch (RejectedExecutionException unavailable) { broken = true; done.completeExceptionally(unavailable); }
        }
    }
    private static NativeSaveBarrier.Receipt combine(List<NativeSaveBarrier.Receipt> receipts) {
        var stages = receipts.stream().map(CompletableFuture::completedFuture).toList();
        while (stages.size() > 128) {
            var next = new ArrayList<CompletableFuture<NativeSaveBarrier.Receipt>>();
            for (int offset = 0; offset < stages.size(); offset += 128) next.add(CompletableFuture.completedFuture(NativeSaveBarrier.combine(stages.subList(offset, Math.min(stages.size(), offset + 128)))));
            stages = List.copyOf(next);
        }
        return NativeSaveBarrier.combine(stages);
    }
    private static NativeSaveBarrier.Receipt combinePrepared(List<NativeSaveBarrier.Receipt> base,
            List<CompletableFuture<Optional<NativeSaveBarrier.Receipt>>> preparations) {
        if (preparations.isEmpty()) return combine(base);
        var prepared = CompletableFuture.allOf(preparations.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            var all = new ArrayList<>(base); for (var future : preparations) future.join().ifPresent(all::add); return combine(all);
        });
        return new NativeSaveBarrier.Receipt(UUID.randomUUID(), prepared.thenCompose(NativeSaveBarrier.Receipt::saved));
    }
    public boolean requiresRecovery(Workspace workspace, UUID task) {
        try {
            var journal = journals.get(workspace.owner());
            var actor = new Actor(workspace.owner(), true, false, 0); var preparations = preparationJournals.get(workspace.owner());
            return broken || journal == null || preparations == null || !journal.settledForTask(actor, task) || !preparations.taskSettled(actor, task);
        }
        catch (Exception failure) { return true; }
    }
    public boolean resourcesSettled(Workspace workspace, Actor actor) {
        try {
            var journal = journals.get(workspace.owner());
            var preparations = preparationJournals.get(workspace.owner());
            return !broken && journal != null && preparations != null && journal.settled(actor) && preparations.settled(actor)
                    && jobs.values().stream().filter(job -> job.plan.owner().equals(actor.owner())).allMatch(job -> job.terminalQueued && job.done.isDone());
        }
        catch (Exception failure) { return false; }
    }
    /** Called only after confirmed erasure advanced the primary epoch. No files or native game state are deleted. */
    public void forgetErased(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner()); var journal = journals.get(workspace.owner());
        var preparations = preparationJournals.get(workspace.owner());
        if (journal == null || preparations == null || !journal.canForgetAfterErasure(actor) || !preparations.canForgetAfterErasure(actor) || jobs.values().stream()
                .anyMatch(job -> job.plan.owner().equals(actor.owner()) && (!job.terminalQueued || !job.done.isDone())))
            throw new IllegalStateException("COMPASS_ERASURE_NOT_SETTLED");
        long oldEpoch = journal.privacyEpoch();
        if (!journals.remove(workspace.owner(), journal)) throw new IllegalStateException("COMPASS_ERASURE_CACHE_CHANGED");
        preparationJournals.remove(workspace.owner(), preparations);
        preparationPlans.values().removeIf(value -> value.search().owner().equals(actor.owner()) && value.search().privacyEpoch() == oldEpoch);
        bindings.values().removeIf(binding -> binding.plan().owner().equals(actor.owner()) && binding.plan().privacyEpoch() == oldEpoch);
        jobs.values().removeIf(job -> job.plan.owner().equals(actor.owner()) && job.plan.privacyEpoch() == oldEpoch);
    }
    /** Published state plus the immutable primary-timeline manifest; safe while a verifier holds the primary monitor. */
    public boolean settledCall(Workspace workspace, Actor actor, UUID task, ModelMessage.ToolCall call) {
        try {
            actor.requireAccess(workspace.owner());
            var journal = journals.get(workspace.owner()); if (broken || journal == null) return false;
            var preparations = preparationJournals.get(workspace.owner()); if (preparations == null || !preparations.taskSettled(actor, task)) return false;
            if (!call.name().equals("compass_prepared_search_execute")) return journal.settledCall(actor, task, call);
            var args = AiJson.object(call.arguments(), 65536, 24);
            if (!args.keySet().equals(Set.of("preparationId", "preparationHash"))) return false;
            String id = args.get("preparationId").getAsString(), hash = args.get("preparationHash").getAsString();
            for (var event : workspace.conversation().getSnapshot(0).events()) if (task.equals(event.taskId()) && event.kind().equals("compass_prepared_search_plan") && event.data() != null) {
                var data = event.data();
                if (id.equals(data.get("preparationId").getAsString()) && hash.equals(data.get("hash").getAsString())) {
                    UUID searchId = UUID.fromString(data.get("searchId").getAsString()); var preparation = preparations.find(actor, searchId);
                    return preparation.isPresent() && preparation.get().settled() && hash.equals(preparation.get().preparationHash())
                            && journal.snapshot(actor).stream().anyMatch(op -> op.id().equals(searchId) && op.taskId().equals(task)
                            && op.planHash().equals(data.get("searchHash").getAsString()) && op.terminal() && op.saved());
                }
            }
            return false;
        } catch (RuntimeException unknown) { return false; }
    }
    public List<Operation> operations(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner()); var journal = journals.get(workspace.owner());
        if (broken || journal == null) throw new IllegalStateException("COMPASS_JOURNAL_NOT_PREPARED"); return journal.snapshot(actor);
    }
    public boolean operationSettled(Workspace workspace, Actor actor, UUID id) {
        var preparations = preparationJournals.get(workspace.owner());
        return preparations != null && preparations.find(actor, id).map(CompassPreparationJournal.Operation::settled).orElse(true)
                && operations(workspace, actor).stream().filter(op -> op.id().equals(id)).findFirst().map(op -> op.terminal() && op.saved()).orElse(false);
    }
    public JsonObject recoveryResources(Workspace workspace, Actor actor, UUID id) {
        var op = operations(workspace, actor).stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("COMPASS_OPERATION_NOT_FOUND"));
        var data = viewWithPreparation(workspace, actor, op);
        boolean prepared = preparationJournals.get(workspace.owner()).find(actor, id).isPresent();
        data.add("nativeJournal", CompassPrivacyProjection.search(op, coordinatesVisible(op, prepared))); return data;
    }
    public List<JsonObject> recoveryRows(Workspace workspace, Actor actor, UUID task) {
        var result = new ArrayList<JsonObject>();
        for (var op : operations(workspace, actor)) if (op.taskId().equals(task)) {
            var row = viewWithPreparation(workspace, actor, op); row.addProperty("kind", "COMPASS"); row.addProperty("saved", op.saved()); var actions = new JsonArray();
            var preparations = preparationJournals.get(workspace.owner()).find(actor, op.id());
            if (op.state() == State.RECOVERY_REQUIRED && op.terminalKnown() && op.saved() && preparations.map(CompassSearchService::preparationReconciliable).orElse(true)) actions.add("COMPASS_RECONCILE");
            row.add("actions", actions);
            row.addProperty("reasonText", op.state() == State.RECOVERY_REQUIRED && !op.terminalKnown()
                    ? "原生执行结果未知，不能清除、重搜或退款；保留原日志，需进一步对账。"
                    : !op.saved() ? "等待真实玩家/统计/进度等原生文件保存凭据；不能凭数量或已有坐标确认。"
                    : "可复核收费后的已知终态及保存，不重放搜索、不退款，不自动宣称原任务目标全部完成。");
            result.add(row);
        }
        return List.copyOf(result);
    }
    public CompassReconciliation recoveryPreview(AgentTool.Context ctx, UUID id) throws Exception {
        var op = operations(ctx.workspace(), ctx.actor()).stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("COMPASS_OPERATION_NOT_FOUND"));
        if (!op.taskId().equals(ctx.taskId())) throw new SecurityException("COMPASS_TASK_MISMATCH");
        var review = new CompassReconciliation(1, UUID.randomUUID(), id, ctx.actor().owner(), op.taskId(), ctx.workspace().metadata().serverInstanceId(),
                ctx.workspace().primaryConversationId(), ctx.workspace().conversation().privacyEpoch(), epoch, op,
                preparationJournals.get(ctx.actor().owner()).find(ctx.actor(), id).orElse(null), "").signed();
        validateReconciliationVersion(ctx.workspace(), ctx.actor(), review);
        synchronized (ctx.workspace().conversation()) {
            ctx.control().check(); review.requireIdentity(ctx.workspace(), ctx.actor(), epoch);
            if (Files.exists(ctx.workspace().artifact(review.id()), LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("COMPASS_RECONCILIATION_EXISTS");
            AiJson.write(ctx.workspace().artifact(review.id()), review);
            var manifest = new JsonObject(); manifest.addProperty("reviewId", review.id().toString()); manifest.addProperty("hash", review.hash());
            manifest.addProperty("operationId", id.toString()); manifest.addProperty("originalPlanHash", op.planHash()); manifest.addProperty("operationRevision", op.revision());
            ctx.workspace().conversation().append(List.of(new Draft(op.taskId(), "compass_reconcile_preview", null, manifest, null)));
        }
        return review;
    }
    public CompassReconciliation loadReconciliation(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        actor.requireAccess(workspace.owner());
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("COMPASS_RECONCILIATION_HASH_INVALID");
        synchronized (workspace.conversation()) {
            boolean indexed = workspace.conversation().getSnapshot(0).events().stream().anyMatch(event -> event.kind().equals("compass_reconcile_preview")
                    && event.data() != null && event.data().has("reviewId") && event.data().has("hash")
                    && id.toString().equals(event.data().get("reviewId").getAsString()) && hash.equals(event.data().get("hash").getAsString()));
            var path = workspace.artifact(id);
            if (!indexed || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 65536) throw new IllegalArgumentException("COMPASS_RECONCILIATION_NOT_FOUND");
            var review = AiJson.GSON.fromJson(Files.readString(path), CompassReconciliation.class);
            if (review == null || !review.id().equals(id) || !review.hash().equals(hash)) throw new SecurityException("COMPASS_RECONCILIATION_CHANGED");
            review.requireIdentity(workspace, actor, epoch); return review;
        }
    }
    public void validateReconciliationVersion(Workspace workspace, Actor actor, CompassReconciliation review) {
        review.requireIdentity(workspace, actor, epoch);
        var op = operations(workspace, actor).stream().filter(value -> value.id().equals(review.operationId())).findFirst().orElseThrow(() -> new IllegalArgumentException("COMPASS_OPERATION_NOT_FOUND"));
        review.requireCurrent(op);
        var preparation = preparationJournals.get(workspace.owner()).find(actor, op.id()).orElse(null);
        if (!Objects.equals(review.preparation(), preparation)) throw new IllegalStateException("COMPASS_PREPARATION_RECONCILIATION_CHANGED");
        if (preparation != null && !preparationReconciliable(preparation)) throw new IllegalStateException("COMPASS_PREPARATION_POSITIVE_RECONCILIATION_REQUIRED");
    }
    public String reconciliationReview(CompassReconciliation review) {
        var op = review.evidence();
        return "复核本人原生罗盘的已知终态：" + op.terminalState() + "\n原操作：" + op.id() + "\n实际原生开始前后等级：" + op.levelsBefore() + " → " + op.levelsAfterStart()
                + "\n实际罗盘损耗计数：" + op.before().damage() + " → " + op.after().damage()
                + (op.entered() ? "\n已收到覆盖实际原生执行的正向保存凭据。" : "\n原生派发已被可靠拒绝，无原生写入保存义务，未伪造磁盘凭据。")
                + "批准仅恢复原回执终态；不运行搜索、不扣第二次费用、不退款或猜测坐标。"
                + (review.preparation() == null ? "" : "\n实际结构准备及canonical保存快照：" + AiJson.GSON.toJson(CompassPrivacyProjection.preparation(review.preparation(), coordinatesVisible(op, true))) + "；只收束已知历史，不再生成区块。")
                + "\n未知执行不适用此入口；完整业务目标仍由原任务核验。\n" + AiJson.GSON.toJson(CompassPrivacyProjection.result(view(op), coordinatesVisible(op, review.preparation() != null))) + "\n预览摘要：" + review.hash();
    }
    public AgentTool.Result reconcile(AgentTool.Context ctx, CompassReconciliation review) throws IOException {
        if (!Objects.equals(ctx.taskId(), review.taskId())) throw new SecurityException("COMPASS_TASK_MISMATCH");
        validateReconciliationVersion(ctx.workspace(), ctx.actor(), review);
        if (review.preparation() != null && !review.preparation().settled()) {
            preparationJournals.get(ctx.actor().owner()).reconcile(ctx.actor(), review.operationId(), review.preparation().revision(), AiJson.digest(AiJson.canonical(review.preparation())));
            try { preparationGate.reconciled(ctx.actor(), review.operationId()); } catch (IllegalStateException absent) { /* A known late completion may have released its live concurrency lease. */ }
        }
        var op = prepare(ctx.workspace()).acknowledgeSavedTerminal(ctx.actor(), review.operationId(), review.operationRevision());
        if (review.preparation() != null) preparationGate.retire(ctx.actor(), review.operationId());
        return result(true, "", "已按已知原生终态与保存凭据恢复账本；未重搜、退款或改变罗盘。", viewWithPreparation(ctx.workspace(), ctx.actor(), op));
    }
    public String exportJournal(Workspace workspace, Actor actor) throws IOException {
        prepare(workspace); var events = JsonParser.parseString(journals.get(workspace.owner()).export(actor)).getAsJsonArray();
        for (var value : events) {
            var event = value.getAsJsonObject(); var operation = AiJson.GSON.fromJson(event.get("data"), Operation.class);
            var latest = journals.get(workspace.owner()).snapshot(actor).stream().filter(search -> search.id().equals(operation.id())).findFirst().orElse(null);
            boolean prepared = preparationJournals.get(workspace.owner()).find(actor, operation.id()).isPresent();
            event.add("data", CompassPrivacyProjection.search(operation, coordinatesVisible(latest, prepared)));
        }
        return AiJson.GSON.toJson(events);
    }
    /** Server controllers/validation only; never routed directly to model, workbench or ZIP export. */
    public List<CompassPreparationJournal.Operation> preparationOperations(Workspace workspace, Actor actor) throws IOException {
        prepare(workspace); return preparationJournals.get(workspace.owner()).snapshot(actor);
    }
    /** Full native evidence for trusted server controllers only; client exports always use the projection below. */
    public List<firefly520.fireflymc.ai.conversation.ConversationStore.Event> preparationHistory(Workspace workspace, Actor actor) throws IOException {
        prepare(workspace); return preparationJournals.get(workspace.owner()).history(actor);
    }
    public String exportPreparationJournal(Workspace workspace, Actor actor) throws IOException {
        prepare(workspace); var events = JsonParser.parseString(preparationJournals.get(workspace.owner()).export(actor)).getAsJsonArray();
        for (var value : events) {
            var event = value.getAsJsonObject(); var operation = AiJson.GSON.fromJson(event.get("data"), CompassPreparationJournal.Operation.class);
            var original = journals.get(workspace.owner()).snapshot(actor).stream().filter(search -> search.id().equals(operation.id())).findFirst().orElse(null);
            event.add("data", CompassPrivacyProjection.preparation(operation, coordinatesVisible(original, true)));
        }
        return AiJson.GSON.toJson(events);
    }
    private static boolean preparationReconciliable(CompassPreparationJournal.Operation operation) {
        return operation.admissionClosed() && operation.candidates().stream().allMatch(candidate -> candidate.settled() || candidate.nativeReady() && !candidate.saveProof().isEmpty());
    }
    private JsonObject viewWithPreparation(Workspace workspace, Actor actor, Operation operation) {
        var preparations = preparationJournals.get(workspace.owner());
        var preparation = preparations == null ? Optional.<CompassPreparationJournal.Operation>empty() : preparations.find(actor, operation.id());
        boolean visible = coordinatesVisible(operation, preparation.isPresent());
        var data = CompassPrivacyProjection.result(view(operation), visible);
        preparation.ifPresent(value -> {
            data.addProperty("newChunksAllowed", true); data.add("preparation", CompassPrivacyProjection.preparation(value, visible));
            data.addProperty("preparationSettled", value.settled());
        }); return data;
    }
    private static boolean coordinatesVisible(Operation operation, boolean prepared) {
        return CompassPrivacyProjection.coordinatesVisible(operation) && (!prepared || CompassNativeAccess.structureCoordinatesConfigured());
    }
    public Operation acknowledgeSavedTerminal(Workspace workspace, Actor actor, UUID id, long revision) throws IOException {
        prepare(workspace);
        if (preparationJournals.get(workspace.owner()).find(actor, id).filter(value -> !value.settled()).isPresent())
            throw new IllegalStateException("COMPASS_PREPARATION_RECONCILIATION_REQUIRED");
        return prepare(workspace).acknowledgeSavedTerminal(actor, id, revision);
    }
    public void register(ToolRegistry registry) {
        tool(registry, "compass_prepared_search_plan", "用真实手持探险者指南针规划独立获批的有界结构搜索。明确允许最多8个未加载原生候选的STRUCTURE_STARTS准备，默认普通搜索不生成。此处不搜索、不生成、不扣费；之后必须批准新增完整摘要。",
                "{\"targetId\":{\"type\":\"string\"},\"hand\":{\"type\":\"string\",\"enum\":[\"MAIN_HAND\",\"OFF_HAND\"]},\"radiusBlocks\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2048},\"maxChecks\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1024},\"maxMillis\":{\"type\":\"integer\",\"minimum\":1000,\"maximum\":30000},\"maximumPreparedChunks\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":8}}",
                List.of("targetId", "hand", "maximumPreparedChunks"), AgentTool.Risk.READ_ONLY, (ctx, args) -> {
                    long millis = integer(args, "maxMillis", 15000);
                    var plan = planPrepared(ctx, text(args, "targetId"), text(args, "hand"),
                            new CompassSearchBudget(integer(args, "radiusBlocks", 512), integer(args, "maxChecks", 256), millis),
                            new CompassPreparationBudget(integer(args, "maximumPreparedChunks", 0), millis));
                    var data = new JsonObject(); data.addProperty("preparationId", plan.id().toString()); data.addProperty("preparationHash", plan.hash());
                    data.addProperty("searchId", plan.search().id().toString()); data.addProperty("approvalPreview", preparedReview(ctx.workspace(), ctx.actor(), plan.id(), plan.hash()));
                    return result(true, "", "已固定有界候选准备、真实费用和新审批摘要；尚未启动。", data);
                });
        tool(registry, "compass_prepared_search_execute", "执行工作台单独批准的固定有界结构搜索及至多8个候选准备。仅STRUCTURE_STARTS、全服1个在途；保留一次原生收费、冷却和成功损耗；派发取消不能撤回已开始生成，未知/未保存先对账，不重搜或退款。",
                "{\"preparationId\":{\"type\":\"string\"},\"preparationHash\":{\"type\":\"string\"}}", List.of("preparationId", "preparationHash"), AgentTool.Risk.WORLD_CHANGE,
                (ctx, args) -> executePrepared(ctx, loadPrepared(ctx.workspace(), ctx.actor(), UUID.fromString(text(args, "preparationId")), text(args, "preparationHash"))));
        tool(registry, "compass_search_plan", "用本人真实手持自然罗盘/探险者指南针规划固定目标搜索。保留原生经验与成功损耗；固定范围/次数/时限；结构仅当前已加载候选，绝不生成新区块。先预览再批准。",
                "{\"kind\":{\"type\":\"string\",\"enum\":[\"BIOME\",\"STRUCTURE\"]},\"targetId\":{\"type\":\"string\"},\"hand\":{\"type\":\"string\",\"enum\":[\"MAIN_HAND\",\"OFF_HAND\"]},\"radiusBlocks\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2048},\"maxChecks\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1024},\"maxMillis\":{\"type\":\"integer\",\"minimum\":1000,\"maximum\":30000}}",
                List.of("kind", "targetId", "hand"), AgentTool.Risk.READ_ONLY, (ctx, args) -> {
                    var plan = plan(ctx, CompassSearchPlan.Kind.valueOf(text(args, "kind")), text(args, "targetId"), text(args, "hand"),
                            new CompassSearchBudget(integer(args, "radiusBlocks", 512), integer(args, "maxChecks", 256), integer(args, "maxMillis", 15000)));
                    var data = new JsonObject(); data.addProperty("planId", plan.id().toString()); data.addProperty("planHash", plan.hash());
                    data.addProperty("approvalPreview", review(ctx.workspace(), ctx.actor(), plan.id(), plan.hash()));
                    return result(true, "", "已固定真实罗盘与原生费用、范围及预算；尚未启动或扣费。", data);
                });
        tool(registry, "compass_search_execute", "执行已批准的固定罗盘搜索；真实经验与损耗不豁免，分Tick有界，关闭工作台继续；取消/离线/手持组件或配置改变时停止，不自动重放或退款。",
                "{\"planId\":{\"type\":\"string\"},\"planHash\":{\"type\":\"string\"}}", List.of("planId", "planHash"), AgentTool.Risk.PLAYER_STATE,
                (ctx, args) -> execute(ctx, load(ctx.workspace(), ctx.actor(), UUID.fromString(text(args, "planId")), text(args, "planHash"))));
        tool(registry, "compass_search_status", "读取本人实际搜索/费用/保存状态；隐藏坐标保持隐藏，未完成不作全范围未命中保证，不启动搜索。",
                "{\"operationId\":{\"type\":\"string\"}}", List.of("operationId"), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> result(true, "", "本人罗盘搜索回执。", viewWithPreparation(ctx.workspace(), ctx.actor(), journal(ctx).get(ctx.actor(), UUID.fromString(text(args, "operationId"))))));
        tool(registry, "compass_search_reconcile", "只复核本人已知原生终态和完整保存凭据；未知执行不能清除。不重搜、扣费、退款、改变罗盘或宣称完整目标完成。",
                "{\"reviewId\":{\"type\":\"string\"},\"reviewHash\":{\"type\":\"string\"}}", List.of("reviewId", "reviewHash"), AgentTool.Risk.PLAYER_STATE,
                (ctx, args) -> reconcile(ctx, loadReconciliation(ctx.workspace(), ctx.actor(), UUID.fromString(text(args, "reviewId")), text(args, "reviewHash"))));
    }
    public static JsonObject view(Operation op) {
        var data = new JsonObject(); data.addProperty("operationId", op.id().toString()); data.addProperty("taskId", op.taskId().toString());
        data.addProperty("state", op.state().name()); data.addProperty("reason", op.reason()); data.addProperty("revision", op.revision());
        data.addProperty("nativeEntered", op.entered()); data.addProperty("nativeSaveConfirmed", op.saved()); data.addProperty("terminalKnown", op.terminalKnown());
        data.addProperty("nativeEntryUnknown", op.state() == State.RECOVERY_REQUIRED && !op.terminalKnown() && !op.entered());
        data.addProperty("nativeCompletionUnknown", op.state() == State.RECOVERY_REQUIRED && !op.terminalKnown());
        if (op.state() == State.RECOVERY_REQUIRED && !op.terminalKnown()) data.addProperty("evidenceNote", "费用与物品字段仅为最后已确认回执；未知阶段不能由此推断没有执行或没有扣费。");
        data.addProperty("levelsBefore", op.levelsBefore()); data.addProperty("levelsAfterNativeStart", op.levelsAfterStart());
        data.addProperty("nativeDamageBefore", op.before().damage()); data.addProperty("nativeDamageAfter", op.after().damage());
        data.addProperty("compassState", op.after().nativeState()); data.addProperty("costPolicy", "NATIVE_COMPASS_COSTS");
        data.addProperty("newChunksAllowed", false); data.addProperty("automaticReplay", false);
        if (op.result() != null) {
            var result = AiJson.GSON.toJsonTree(op.result()).getAsJsonObject();
            if (!op.result().coordinatesVisible()) { result.remove("x"); result.remove("z"); result.remove("sampleY"); }
            data.add("result", result);
        }
        return data;
    }
    private interface Action { AgentTool.Result run(AgentTool.Context context, JsonObject arguments) throws Exception; }
    private void tool(ToolRegistry registry, String name, String description, String properties, List<String> required, AgentTool.Risk risk, Action action) {
        var schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", JsonParser.parseString(properties));
        schema.add("required", AiJson.GSON.toJsonTree(required)); schema.addProperty("additionalProperties", false);
        registry.register(new DefinedTool(new AgentTool.Descriptor(name, description, schema, risk, "player.own", 0, AgentTool.ExecutionThread.IO, 120000, true, ""), (ctx, args) -> {
            ctx.actor().requireAccess(ctx.workspace().owner()); ctx.control().check();
            try { return action.run(ctx, args); }
            catch (AgentTool.Deferred deferred) { throw deferred; }
            catch (TaskControl.Stopped stopped) { throw stopped; }
            catch (Exception failure) { return result(false, safe(failure), "搜索未完成：" + safe(failure) + "；没有伪造发现、退款或自动重放。", new JsonObject()); }
        }));
    }
    @Override public void close() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        List<RuntimeJob> pending;
        synchronized (jobs) { closed = true; pending = List.copyOf(jobs.values()); }
        for (var job : pending) if (!job.terminalQueued) job.stop("SHUTDOWN", job.nativeCaptured);
    }
    public boolean awaitStopped(long millis) throws InterruptedException {
        if (!closed) return false;
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        for (var job : List.copyOf(jobs.values())) {
            long left = end - System.nanoTime(); if (left <= 0) return false;
            try { job.done.get(left, TimeUnit.NANOSECONDS); }
            catch (ExecutionException recordedFailure) { /* The broken journal remains behind its recovery gate. */ }
            catch (TimeoutException waiting) { return false; }
            for (var preparation : List.copyOf(job.preparationReceipts)) {
                left = end - System.nanoTime(); if (left <= 0) return false;
                try { preparation.get(left, TimeUnit.NANOSECONDS); }
                catch (ExecutionException unknownNativePreparation) { /* The persistent recovery fence stays in place. */ }
                catch (TimeoutException waiting) { return false; }
            }
        }
        evidence.shutdown(); return evidence.awaitTermination(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS);
    }
    private static String text(JsonObject args, String name) { return args.get(name).getAsString(); }
    private static int integer(JsonObject args, String name, int fallback) { return args.has(name) ? args.get(name).getAsBigDecimal().intValueExact() : fallback; }
    private static String safe(Throwable failure) {
        String message = failure.getMessage(); return message != null && message.matches("[A-Z][A-Z0-9_]{1,100}") ? message : "COMPASS_NATIVE_FAILURE";
    }
    private static AgentTool.Result result(boolean success, String code, String summary, JsonObject data) {
        return new AgentTool.Result(success, code, summary, data, "owned-native-compass-search-and-journal", "", false);
    }
    private interface NativeAction { void run() throws Exception; }
    private static void invoke(NativeAction action) {
        try { action.run(); } catch (RuntimeException failure) { throw failure; } catch (Exception failure) { throw new IllegalStateException("COMPASS_NATIVE_INVOCATION_FAILED", failure); }
    }
}
