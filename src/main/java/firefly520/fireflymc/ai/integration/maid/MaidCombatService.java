package firefly520.fireflymc.ai.integration.maid;

import com.google.gson.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.CommittedCall;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import net.minecraft.server.MinecraftServer;

/** Bounded real native melee, with one immutable approval and an IO journal before every strike. */
public final class MaidCombatService implements AutoCloseable {
    private record Witness(MaidCombatPlan plan, MaidMeleeKernel.State state, Object connection) {}
    private record RecoveryWitness(MaidCombatReconcile review, Object connection) {}
    private static final class Work {
        final Actor actor; volatile TaskControl control; final Witness witness; final MaidCarrierBridge.Lease lease; final long startedTick;
        volatile MaidMeleeKernel.State state; volatile CompletableFuture<Void> ready = new CompletableFuture<>();
        volatile long lastHitTick = Long.MIN_VALUE; volatile boolean cleaned;
        Work(Actor actor, TaskControl control, Witness witness, MaidCarrierBridge.Lease lease, long startedTick) {
            this.actor = actor; this.control = control; this.witness = witness; this.lease = lease; this.startedTick = startedTick; state = witness.state();
        }
    }
    private record RecoveryEffect(MaidMeleeKernel.Snapshot current, NativeSaveBarrier.Receipt save) {}
    private final MinecraftServer server;
    private final MaterialInventoryService inventory;
    private final VirtualProcessingService dispatcher;
    private final MaterialLedger materials;
    private final MaidMeleeKernel kernel;
    private final LongSupplier generation;
    private final UUID executionEpoch = UUID.randomUUID();
    private final Map<String, MaidCombatJournal> journals = new ConcurrentHashMap<>();
    private final Map<UUID, Witness> witnesses = new ConcurrentHashMap<>();
    private final Map<UUID, Work> active = new ConcurrentHashMap<>();
    private final Set<UUID> executing = ConcurrentHashMap.newKeySet();
    private final ArrayBlockingQueue<Work> waits = new ArrayBlockingQueue<>(64);
    private final Map<UUID, MaterialLedger.NativeScopeLease> resourceLeases = new ConcurrentHashMap<>();
    private final Map<UUID, RecoveryWitness> recoveries = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> recoveryWaits = new ConcurrentHashMap<>();
    private final ExecutorService stopReceipts = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256), Thread.ofPlatform().daemon().name("fireflymc-maid-combat-stop").factory(), new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;
    private volatile java.util.function.Predicate<String> capabilityPolicy = name -> true;
    public void capabilityPolicy(java.util.function.Predicate<String> enabled) { capabilityPolicy = Objects.requireNonNull(enabled); }
    public MaidCombatService(MinecraftServer server, MaterialInventoryService inventory, InstalledPermissionAdapters permissions,
            VirtualProcessingService dispatcher, LongSupplier generation, MaterialLedger materials) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        this.server = server; this.inventory = inventory; this.dispatcher = dispatcher; this.generation = generation; this.materials = Objects.requireNonNull(materials);
        kernel = new MaidMeleeKernel(server, inventory, permissions, new MaidControlNative(inventory.maids(),
                new MaidEntityPermission(server, firefly520.fireflymc.ai.AiServerService::authenticated, permissions), inventory.nativeSaves()));
    }
    public synchronized void prepare(Workspace workspace) throws IOException {
        if (closed) throw new IllegalStateException("AI_STOPPING");
        if (journals.containsKey(workspace.owner())) return;
        MaidCombatJournal repo;
        try { repo = new MaidCombatJournal(workspace); }
        catch (IOException | RuntimeException invalid) { materials.quarantineNativeScopeRestoration(); throw invalid; }
        var actor = new Actor(workspace.owner(), true, false, 0);
        for (var op : repo.snapshot(actor)) if (!op.settled()) resourceLeases.put(op.plan().id(),
                materials.restoreNativeScope(UUID.fromString(workspace.owner()), op.plan().id(), backends(op.plan())));
        journals.put(workspace.owner(), repo);
    }
    private MaidCombatJournal journal(Workspace workspace, Actor actor) throws IOException {
        actor.requireAccess(workspace.owner()); var repo = journals.get(workspace.owner()); if (repo != null) return repo;
        prepare(workspace); return journals.get(workspace.owner());
    }
    public void register(ToolRegistry tools) {
        tool(tools, "maid_combat_plan", "为本人绑定且空闲的女仆预览一个已加载敌对目标的有限原生近战。保留真实攻击速度/冷却、装备耐久及其他战斗费用；当前只支持无附魔原版剑、空副手/饰品、无横扫。地面掉落和经验按原生规则保留，不虚拟入账。",
                "{\"targetId\":{\"type\":\"string\"},\"maximumHits\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":16},\"maximumTicks\":{\"type\":\"integer\",\"minimum\":20,\"maximum\":2400}}",
                List.of("targetId", "maximumHits", "maximumTicks"), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> result(true, "", "原生单体近战计划已固定，尚未攻击。", view(plan(ctx, UUID.fromString(text(args, "targetId")), args.get("maximumHits").getAsInt(), args.get("maximumTicks").getAsLong()))));
        tool(tools, "maid_combat_execute", "执行审批的有界女仆原生单体近战，逐击检查主人/目标/任务仲裁/权限/装备，遵守原生寻路、可见实体记忆和冷却；逐击持久记录真实结果。取消后不重放攻击或补发奖励。",
                "{\"planId\":{\"type\":\"string\"},\"planHash\":{\"type\":\"string\"}}", List.of("planId", "planHash"), AgentTool.Risk.WORLD_CHANGE,
                (ctx, args) -> execute(ctx, load(ctx.workspace(), ctx.actor(), UUID.fromString(text(args, "planId")), text(args, "planHash"))));
        tool(tools, "maid_combat_status", "读取本人女仆真实战斗的逐击历史与保存凭据。历史地面掉落/经验观察不等于当前物品所有权或已交付给玩家。",
                "{\"operationId\":{\"type\":\"string\"}}", List.of("operationId"), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> result(true, "", "本人女仆战斗的历史回执。", status(journal(ctx.workspace(), ctx.actor()).get(ctx.actor(), UUID.fromString(text(args, "operationId"))))));
        tool(tools, "maid_combat_reconcile", "按新审批接受已完整观察的战斗当前状态并取得新原生保存凭据；不攻击、不补发掉落/经验、不恢复耐久，不宣称原战斗目标成功。未知副作用不能用此入口清除。",
                "{\"reviewId\":{\"type\":\"string\"},\"reviewHash\":{\"type\":\"string\"}}", List.of("reviewId", "reviewHash"), AgentTool.Risk.PLAYER_STATE,
                (ctx, args) -> reconcile(ctx, loadReconciliation(ctx.workspace(), ctx.actor(), UUID.fromString(text(args, "reviewId")), text(args, "reviewHash"))));
    }
    public MaidCombatPlan plan(AgentTool.Context ctx, UUID target, int maximumHits, long maximumTicks) throws Exception {
        requireContext(ctx); if (!capabilityPolicy.test("maid_combat_execute")) throw new IllegalStateException("MAID_COMBAT_DISABLED"); var repo = journal(ctx.workspace(), ctx.actor());
        if (!repo.settled(ctx.actor())) throw new IllegalStateException("MAID_COMBAT_REVIEW_REQUIRED");
        var witness = dispatcher.inspectOwned(ctx, () -> {
            var player = inventory.requirePlayer(ctx.actor()); var state = kernel.inspect(player, ctx.actor(), target);
            var plan = new MaidCombatPlan(1, UUID.randomUUID(), ctx.actor().owner(), ctx.taskId(), ctx.workspace().metadata().serverInstanceId(),
                    ctx.workspace().primaryConversationId(), ctx.workspace().conversation().privacyEpoch(), executionEpoch, generation.getAsLong(),
                    System.currentTimeMillis() + 120000, maximumHits, maximumTicks, state.before(), "").signed();
            return new Witness(plan, state, player.connection);
        });
        synchronized (ctx.workspace().conversation()) {
            requireContext(ctx); witnesses.entrySet().removeIf(entry -> entry.getValue().plan().expiresAt() <= System.currentTimeMillis() && !active.containsKey(entry.getKey()));
            if (witnesses.size() >= 256) throw new IllegalStateException("MAID_COMBAT_PREVIEW_LIMIT");
            witness.plan().requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch); requireGeneration(witness.plan());
            AiJson.write(ctx.workspace().artifact(witness.plan().id()), witness.plan());
            var data = new JsonObject(); data.addProperty("planId", witness.plan().id().toString()); data.addProperty("planHash", witness.plan().hash());
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "maid_combat_plan", null, data, null)));
            witnesses.put(witness.plan().id(), witness); return witness.plan();
        }
    }
    public MaidCombatPlan load(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) {
            if (!indexed(workspace, "maid_combat_plan", "planId", id, "planHash", hash)) throw new IllegalArgumentException("MAID_COMBAT_PLAN_NOT_FOUND");
            var plan = artifact(workspace, id, MaidCombatPlan.class);
            if (plan == null || !plan.id().equals(id) || !plan.hash().equals(hash)) throw new SecurityException("MAID_COMBAT_PLAN_CHANGED");
            plan.requireIdentity(workspace, actor, executionEpoch); requireGeneration(plan);
            var witness = witnesses.get(id);
            if (witness == null || !witness.plan().equals(plan) || System.currentTimeMillis() >= plan.expiresAt()) throw new IllegalStateException("MAID_COMBAT_NEW_PREVIEW_REQUIRED");
            return witness.plan();
        }
    }
    public String review(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        return "本人绑定女仆对固定敌对目标执行有限原生单体近战。逐击保留攻击冷却、装备损耗与实际战斗费用；不会恢复生命、清除冷却或复制武器。"
                + "真实掉落与经验留在原生世界，历史观察不是自动归仓或已交付；预算用尽、取消或遇任务竞争即停止，未知效果需恢复核对。\n" + AiJson.GSON.toJson(view(load(workspace, actor, id, hash)));
    }
    public AgentTool.Result execute(AgentTool.Context ctx, MaidCombatPlan plan) throws Exception {
        requireContext(ctx); plan.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch); requireGeneration(plan);
        if (!plan.taskId().equals(ctx.taskId())) throw new SecurityException("MAID_COMBAT_TASK_MISMATCH");
        var repo = journal(ctx.workspace(), ctx.actor());
        if (repo.published(ctx.actor(), plan.id()) != null) return result(false, "MAID_COMBAT_ALREADY_RECORDED", "操作已有持久记录，未重复攻击或生成奖励。", status(repo.get(ctx.actor(), plan.id())));
        var witness = witnesses.get(plan.id());
        if (witness == null || witness.plan() != plan || System.currentTimeMillis() >= plan.expiresAt()) throw new IllegalStateException("MAID_COMBAT_NEW_PREVIEW_REQUIRED");
        repo.prepare(ctx.actor(), plan);
        try {
            resourceLeases.put(plan.id(), materials.acquireNativeScope(ctx.actor(), plan.id(), backends(plan)));
            var work = dispatcher.inspectOwned(ctx, () -> {
                var player = inventory.requirePlayer(ctx.actor()); if (player.connection != witness.connection()) throw new SecurityException("MAID_COMBAT_CONNECTION_CHANGED");
                kernel.validate(player, witness.state());
                var lease = inventory.maids().acquireOperation(player, player.getUUID(), plan.id());
                if (lease.lease() == null) throw new IllegalStateException(lease.reason());
                return new Work(ctx.actor(), ctx.control(), witness, lease.lease(), player.serverLevel().getGameTime());
            });
            active.put(plan.id(), work); enqueue(work);
        } catch (Exception | LinkageError failure) {
            var abandoned = active.remove(plan.id()); if (abandoned != null) cleanup(abandoned);
            releaseIfSettled(ctx.actor(), repo.finish(ctx.actor(), plan.id(), safe(failure))); throw failure;
        }
        return proceed(ctx, plan.id());
    }
    private AgentTool.Result proceed(AgentTool.Context ctx, UUID id) throws Exception {
        var repo = journal(ctx.workspace(), ctx.actor()); var op = repo.get(ctx.actor(), id); var plan = op.plan();
        plan.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch);
        if (!plan.taskId().equals(ctx.taskId())) throw new SecurityException("MAID_COMBAT_TASK_MISMATCH");
        if (!executing.add(id)) throw new IllegalStateException("MAID_COMBAT_ALREADY_EXECUTING");
        boolean waiting = false;
        try {
            requireContext(ctx); requireGeneration(plan);
            if (op.finished() || op.settled() || op.state() == MaidCombatJournal.State.RECOVERY_REQUIRED) return complete(ctx, id);
            var work = active.get(id); if (work == null) throw new IllegalStateException("MAID_COMBAT_NATIVE_WORK_LOST");
            work.control = ctx.control();
            if (!work.ready.isDone()) { waiting = true; throw deferred(id, "WAITING_NATIVE_MAID_COMBAT", work.ready, fresh -> proceed(fresh, id)); }
            work.ready.get();
            work.state = dispatcher.inspectOwned(ctx, () -> {
                var player = inventory.requirePlayer(ctx.actor()); requireWork(player, work); return kernel.refresh(player, work.state);
            });
            int index = repo.beginHit(ctx.actor(), id, work.state.before());
            try {
                dispatcher.commitObserved(ctx, () -> {
                    net.minecraft.server.level.ServerPlayer player;
                    try { player = inventory.requirePlayer(ctx.actor()); requireWork(player, work); }
                    catch (Exception | LinkageError rejected) {
                        return new MaidMeleeKernel.Outcome(false, false, false, false, null, null, null, List.of(), List.of(), safe(rejected), null);
                    }
                    var effect = kernel.hit(player, work.state, id); work.lastHitTick = player.serverLevel().getGameTime();
                    if (effect.verified() && effect.after() != null) work.state = kernel.advance(work.state, effect.after());
                    return effect;
                }, effect -> {
                    repo.observed(ctx.actor(), id, index, effect);
                    if (effect.entered() && effect.verified() && effect.save() != null) inventory.nativeSaves().record(strikeId(id, index), effect.save(), proof ->
                            releaseIfSettled(ctx.actor(), repo.saved(ctx.actor(), id, index, proof)));
                    if (work.cleaned) releaseIfSettled(ctx.actor(), repo.finish(ctx.actor(), id, "NATIVE_COMBAT_STOPPED"));
                });
            } catch (CommittedCall.Rejected rejected) {
                // The dispatcher proves that this particular invocation never reached the server thread.
                repo.notDispatched(ctx.actor(), id, index, "REJECTED_BEFORE_NATIVE_INVOCATION"); throw rejected;
            }
            op = repo.get(ctx.actor(), id);
            if (op.state() == MaidCombatJournal.State.RECOVERY_REQUIRED) return complete(ctx, id);
            var hit = op.hits().get(index);
            boolean broken = hit.after() != null && hit.after().hand().empty();
            if (hit.killed() || !hit.entered() || !hit.attempted() || broken || op.hits().size() >= plan.maximumHits()) {
                repo.finish(ctx.actor(), id, hit.killed() ? "TARGET_KILLED" : !hit.entered() || !hit.attempted() ? hit.reason() : broken ? "NATIVE_WEAPON_BROKEN" : "NATIVE_STRIKE_BUDGET_EXHAUSTED");
                return complete(ctx, id);
            }
            work.ready = new CompletableFuture<>(); enqueue(work); waiting = true;
            throw deferred(id, "WAITING_NATIVE_MAID_COMBAT", work.ready, fresh -> proceed(fresh, id));
        } catch (AgentTool.Deferred deferred) { throw deferred; }
        catch (Exception | LinkageError failure) {
            op = repo.get(ctx.actor(), id);
            if (!op.settled() && op.state() != MaidCombatJournal.State.RECOVERY_REQUIRED) {
                op = op.state() == MaidCombatJournal.State.HIT_INTENT ? repo.unknown(ctx.actor(), id, safe(failure)) : repo.finish(ctx.actor(), id, safe(failure));
            }
            releaseIfSettled(ctx.actor(), op);
            return result(false, op.settled() ? safe(failure) : "RECOVERY_REQUIRED", "原生战斗已停止；真实历史保留，未重复攻击、恢复费用或补发奖励。", status(op));
        } finally {
            executing.remove(id);
            if (!waiting) { var work = active.remove(id); if (work != null) cleanup(work); }
        }
    }
    private AgentTool.Result complete(AgentTool.Context ctx, UUID id) throws Exception {
        var repo = journal(ctx.workspace(), ctx.actor()); var op = repo.get(ctx.actor(), id);
        if (!op.plan().taskId().equals(ctx.taskId())) throw new SecurityException("MAID_COMBAT_TASK_MISMATCH");
        if (op.state() == MaidCombatJournal.State.OBSERVED && op.finished()) {
            var futures = new ArrayList<CompletableFuture<Void>>();
            for (var hit : op.hits()) if (hit.entered() && hit.verified() && hit.nativeProof().isEmpty()) futures.add(inventory.nativeSaves().awaitJournalled(strikeId(id, hit.index())).toCompletableFuture());
            var ready = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
            if (!ready.isDone()) throw deferred(id, "WAITING_NATIVE_MAID_COMBAT_SAVE", ready, fresh -> complete(fresh, id));
            try { ready.get(); }
            catch (ExecutionException unavailable) { repo.unknown(ctx.actor(), id, "MAID_COMBAT_NATIVE_SAVE_UNCONFIRMED"); }
            op = repo.get(ctx.actor(), id);
        }
        releaseIfSettled(ctx.actor(), op);
        boolean success = op.killed();
        return result(success, success ? "" : op.settled() ? op.reason() : "RECOVERY_REQUIRED",
                success ? "固定目标已在有限真实原生近战中死亡，逐击实际状态与保存凭据已记录；地面战利品和经验仍遵守原生玩法。"
                        : op.settled() ? "有界战斗已停止并保存；未声称目标已击败，未自动补发或归仓奖励。" : "战斗存在未闭合的原生保存或效果义务；不能直接重放。", status(op));
    }
    public void tick() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        long end = System.nanoTime() + 1_000_000; var visited = new HashSet<UUID>();
        for (int i = 0; i < 2 && System.nanoTime() < end; i++) {
            var work = waits.poll(); if (work == null) return;
            if (!visited.add(work.witness.plan().id())) { waits.offer(work); return; }
            if (work.ready.isDone() || work.cleaned) continue;
            try {
                checkWorkControl(work.control); var player = inventory.requirePlayer(work.actor); requireWork(player, work);
                if (player.serverLevel().getGameTime() - work.startedTick >= work.witness.plan().maximumTicks()) throw new IllegalStateException("MAID_COMBAT_TIME_BUDGET_EXHAUSTED");
                if (player.serverLevel().getGameTime() > work.lastHitTick && kernel.ready(player, work.state)) { work.ready.complete(null); continue; }
                kernel.navigate(player, work.state); enqueue(work);
            } catch (Exception | LinkageError failure) {
                work.ready.completeExceptionally(failure);
                if (failure instanceof TaskControl.Stopped) stopWork(work.witness.plan().id(), work, safe(failure));
            }
        }
    }
    private void requireWork(net.minecraft.server.level.ServerPlayer player, Work work) {
        if (closed || work.cleaned) throw new IllegalStateException("AI_STOPPING"); checkWorkControl(work.control); requireGeneration(work.witness.plan());
        if (!capabilityPolicy.test("maid_combat_execute")) throw new IllegalStateException("MAID_COMBAT_DISABLED");
        if (player.serverLevel().getGameTime() - work.startedTick >= work.witness.plan().maximumTicks()) throw new IllegalStateException("MAID_COMBAT_TIME_BUDGET_EXHAUSTED");
        if (player.connection != work.witness.connection()) throw new SecurityException("MAID_COMBAT_CONNECTION_CHANGED");
        var lease = inventory.maids().validateOperation(player, player.getUUID(), work.lease);
        if (lease.lease() == null) throw new IllegalStateException(lease.reason());
        var resource = resourceLeases.get(work.witness.plan().id());
        if (resource == null) throw new IllegalStateException("MAID_COMBAT_RESOURCE_LEASE_CHANGED");
        materials.requireNativeScope(work.actor, resource, backends(work.witness.plan()));
    }
    private void enqueue(Work work) { if (!waits.offer(work)) throw new IllegalStateException("MAID_COMBAT_NATIVE_WAIT_QUEUE_FULL"); }
    static void checkWorkControl(TaskControl control) {
        // Runtime retains the accepted control while a native operation is deferred.
        // Any stop invalidates that operation, regardless of the caller's reason string.
        control.check();
    }
    private void cleanup(Work work) {
        if (work.cleaned) return; work.cleaned = true; waits.remove(work);
        server.execute(() -> {
            kernel.stopNavigation(work.state, UUID.fromString(work.actor.owner()));
            try { inventory.maids().releaseRecordedOperation(UUID.fromString(work.actor.owner()), work.lease); }
            catch (RuntimeException disconnected) { /* Native binding disconnect cleanup may already have invalidated it. */ }
        });
    }
    private static Collection<String> backends(MaidCombatPlan plan) { return List.of("maid/" + plan.maidId() + "/backpack"); }
    private void releaseIfSettled(Actor actor, MaidCombatJournal.Operation op) {
        if (!op.settled()) return; var lease = resourceLeases.get(op.plan().id());
        if (lease != null && resourceLeases.remove(op.plan().id(), lease)) {
            try { materials.releaseNativeScope(actor, lease); }
            catch (RuntimeException failure) { resourceLeases.putIfAbsent(op.plan().id(), lease); throw failure; }
        }
    }
    public List<JsonObject> recoveryRows(Workspace workspace, Actor actor, UUID task) throws IOException {
        return journal(workspace, actor).snapshot(actor).stream().filter(op -> !op.settled() && (task == null || task.equals(op.plan().taskId()))).map(op -> {
            var row = new JsonObject(); row.addProperty("kind", "MAID_COMBAT"); row.addProperty("operationId", op.plan().id().toString()); row.addProperty("taskId", op.plan().taskId().toString());
            row.addProperty("state", op.state().name()); row.addProperty("reason", op.reason()); row.addProperty("hitCount", op.hits().size());
            row.addProperty("savedHits", op.hits().stream().filter(hit -> !hit.nativeProof().isEmpty()).count());
            row.addProperty("description", "女仆原生战斗的历史击次、地面战利品/经验与原生保存义务。接受当前状态不会重放或补发奖励。");
            var actions = new JsonArray(); if (MaidCombatJournal.reviewable(op) && !active.containsKey(op.plan().id())) actions.add("MAID_RECONCILE");
            row.add("actions", actions); row.addProperty("reasonText", actions.isEmpty() ? "原生效果尚未完整观察，不能按当前状态推断并清除义务。" : "可预览当前状态核对；原战斗目标不重新认定成功，奖励和费用不会补造。"); return row;
        }).toList();
    }
    public JsonObject recoveryResources(Workspace workspace, Actor actor, UUID id) throws IOException {
        var op = journal(workspace, actor).get(actor, id); var value = status(op); value.addProperty("operationId", id.toString()); value.addProperty("taskId", op.plan().taskId().toString());
        value.addProperty("digest", AiJson.digest(AiJson.canonical(op))); return value;
    }
    public MaidCombatReconcile recoveryPreview(AgentTool.Context ctx, UUID id) throws Exception {
        requireContext(ctx); var repo = journal(ctx.workspace(), ctx.actor()); var op = repo.get(ctx.actor(), id);
        if (!op.plan().taskId().equals(ctx.taskId())) throw new SecurityException("MAID_COMBAT_TASK_MISMATCH");
        if (active.containsKey(id) || op.settled() || !MaidCombatJournal.reviewable(op))
            throw new IllegalStateException("MAID_COMBAT_UNKNOWN_EFFECT_REVIEW_REQUIRED");
        var witness = dispatcher.inspectOwned(ctx, () -> {
            var player = inventory.requirePlayer(ctx.actor()); var current = kernel.recovery(player, op.plan().maidId(), op.plan().targetId());
            var review = new MaidCombatReconcile(1, UUID.randomUUID(), ctx.actor().owner(), ctx.taskId(), ctx.workspace().metadata().serverInstanceId(),
                    ctx.workspace().primaryConversationId(), ctx.workspace().conversation().privacyEpoch(), executionEpoch, System.currentTimeMillis() + 120000,
                    id, op.revision(), AiJson.digest(AiJson.canonical(op)), current, "").signed();
            return new RecoveryWitness(review, player.connection);
        });
        synchronized (ctx.workspace().conversation()) {
            validateReconciliationVersion(ctx.workspace(), ctx.actor(), witness.review());
            recoveries.entrySet().removeIf(entry -> entry.getValue().review().expiresAt() <= System.currentTimeMillis() && !recoveryWaits.containsKey(entry.getKey()));
            if (recoveries.size() >= 128) throw new IllegalStateException("MAID_COMBAT_RECOVERY_LIMIT");
            AiJson.write(ctx.workspace().artifact(witness.review().id()), witness.review()); var data = new JsonObject();
            data.addProperty("reviewId", witness.review().id().toString()); data.addProperty("reviewHash", witness.review().hash()); data.addProperty("operationId", id.toString());
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "maid_combat_reconcile_preview", null, data, null)));
            recoveries.put(witness.review().id(), witness); return witness.review();
        }
    }
    public MaidCombatReconcile loadReconciliation(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) {
            if (!indexed(workspace, "maid_combat_reconcile_preview", "reviewId", id, "reviewHash", hash)) throw new IllegalArgumentException("MAID_COMBAT_RECONCILE_NOT_FOUND");
            var review = artifact(workspace, id, MaidCombatReconcile.class); var witness = recoveries.get(id);
            if (review == null || !review.id().equals(id) || !review.hash().equals(hash) || witness == null || !witness.review().equals(review)) throw new SecurityException("MAID_COMBAT_RECONCILE_CHANGED");
            review.requireIdentity(workspace, actor, executionEpoch); if (review.expiresAt() <= System.currentTimeMillis()) throw new IllegalStateException("MAID_COMBAT_RECOVERY_PREVIEW_EXPIRED");
            return witness.review();
        }
    }
    public void validateReconciliationVersion(Workspace workspace, Actor actor, MaidCombatReconcile review) throws IOException {
        review.requireIdentity(workspace, actor, executionEpoch); var op = journal(workspace, actor).published(actor, review.operationId());
        if (op == null || op.settled() || op.revision() != review.operationRevision() || !AiJson.digest(AiJson.canonical(op)).equals(review.operationDigest()))
            throw new IllegalStateException("MAID_COMBAT_RECONCILE_PREVIEW_CHANGED");
    }
    public String reconciliationReview(MaidCombatReconcile review) {
        return "接受已完整观察的女仆战斗当前状态：重新核对本人女仆的装备、背包与当前目标观察，并等候实际保存。"
                + "地面掉落/经验可能已经拾取、合并或消失，其历史观察仍保留且不算交付；不会重放击次、复制物品、补发经验、恢复耐久或声称旧目标成功。\n" + AiJson.GSON.toJson(review);
    }
    private AgentTool.Result reconcile(AgentTool.Context ctx, MaidCombatReconcile review) throws Exception {
        requireContext(ctx); review.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch);
        if (!ctx.taskId().equals(review.taskId())) throw new SecurityException("MAID_COMBAT_TASK_MISMATCH");
        var repo = journal(ctx.workspace(), ctx.actor());
        var ready = recoveryWaits.get(review.id());
        if (ready == null) {
            validateReconciliationVersion(ctx.workspace(), ctx.actor(), review); var witness = recoveries.get(review.id());
            if (witness == null || witness.review() != review) throw new IllegalStateException("MAID_COMBAT_RECOVERY_NEW_PREVIEW_REQUIRED");
            var accepted = new CompletableFuture<Void>();
            if (recoveryWaits.putIfAbsent(review.id(), accepted) != null) throw new IllegalStateException("MAID_COMBAT_RECOVERY_ALREADY_EXECUTING");
            try {
                dispatcher.commitObserved(ctx, () -> {
                    var player = inventory.requirePlayer(ctx.actor());
                    if (player.connection != witness.connection()) throw new SecurityException("MAID_COMBAT_CONNECTION_CHANGED");
                    var current = kernel.recovery(player, review.current().maid().maidId(), repo.get(ctx.actor(), review.operationId()).plan().targetId());
                    if (!current.equals(review.current())) throw new IllegalStateException("MAID_COMBAT_RECOVERY_CURRENT_STATE_CHANGED");
                    var entity = player.serverLevel().getEntity(current.maid().maidId());
                    var receipts = new ArrayList<CompletableFuture<NativeSaveBarrier.Receipt>>();
                    receipts.add(CompletableFuture.completedFuture(MaidCombatSaveWitness.observe(entity, player.getUUID(), current, inventory.nativeSaves())));
                    if (current.target() != null) receipts.add(CompletableFuture.completedFuture(MaidCombatEntityProof.observe(player.serverLevel().getEntity(current.target().id()), review.id(), inventory.nativeSaves())));
                    return new RecoveryEffect(current, NativeSaveBarrier.combine(receipts));
                }, effect -> inventory.nativeSaves().record(review.id(), effect.save(), proof -> {
                    try { releaseIfSettled(ctx.actor(), repo.reconciled(ctx.actor(), review, proof)); accepted.complete(null); }
                    catch (IOException | RuntimeException failure) { accepted.completeExceptionally(failure); throw failure; }
                }));
            } catch (Exception | LinkageError failure) { accepted.completeExceptionally(failure); throw failure; }
            ready = accepted;
        }
        if (!ready.isDone()) throw deferred(review.operationId(), "WAITING_NATIVE_MAID_COMBAT_RECONCILE_SAVE", ready, fresh -> reconcile(fresh, review));
        ready.get(); var op = repo.get(ctx.actor(), review.operationId());
        return result(op.settled(), op.settled() ? "" : "RECOVERY_REQUIRED", "已按审批接受当前状态；历史战斗没有重放，奖励和费用未被补造，旧目标未重新认定成功。", status(op));
    }
    public boolean resourcesSettled(Workspace workspace, Actor actor) throws IOException { return journal(workspace, actor).settled(actor) && resourceLeases.values().stream().noneMatch(lease -> lease.owner().toString().equals(actor.owner())); }
    public boolean taskSettled(Workspace workspace, Actor actor, UUID task) throws IOException { return journal(workspace, actor).taskSettled(actor, task) && journal(workspace, actor).snapshot(actor).stream().filter(op -> op.plan().taskId().equals(task)).noneMatch(op -> resourceLeases.containsKey(op.plan().id())); }
    public boolean operationSettled(Workspace workspace, Actor actor, UUID id) throws IOException { return journal(workspace, actor).operationSettled(actor, id) && !resourceLeases.containsKey(id); }
    public boolean ownsOperation(Workspace workspace, Actor actor, UUID id) { actor.requireAccess(workspace.owner()); var repo = journals.get(workspace.owner()); return repo != null && repo.published(actor, id) != null; }
    public String exportJournal(Workspace workspace, Actor actor) throws IOException { return journal(workspace, actor).export(actor); }
    public void forgetErased(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner()); var old = journals.get(workspace.owner()); if (old == null) return;
        if (!old.forgettable(actor, workspace.conversation().privacyEpoch()) || resourceLeases.values().stream().anyMatch(lease -> lease.owner().toString().equals(actor.owner())))
            throw new IllegalStateException("MAID_COMBAT_PRIVACY_SETTLEMENT_REQUIRED");
        if (!journals.remove(workspace.owner(), old)) throw new IllegalStateException("MAID_COMBAT_PRIVACY_CACHE_CHANGED");
        witnesses.entrySet().removeIf(entry -> entry.getValue().plan().owner().equals(actor.owner()));
        recoveries.entrySet().removeIf(entry -> entry.getValue().review().owner().equals(actor.owner()));
    }
    public Optional<Boolean> settledCall(Workspace workspace, Actor actor, UUID task, ModelMessage.ToolCall call) {
        if (!Set.of("maid_combat_execute", "maid_combat_reconcile").contains(call.name())) return Optional.empty();
        try {
            var args = AiJson.object(call.arguments(), 65536, 16); var repo = journals.get(workspace.owner()); if (repo == null) return Optional.of(false);
            UUID operation;
            if (call.name().equals("maid_combat_execute")) {
                if (!args.keySet().equals(Set.of("planId", "planHash"))) return Optional.of(false);
                operation = UUID.fromString(text(args, "planId")); var op = repo.published(actor, operation);
                if (op == null || !op.plan().hash().equals(text(args, "planHash"))) return Optional.of(false);
            } else {
                if (!args.keySet().equals(Set.of("reviewId", "reviewHash"))) return Optional.of(false);
                UUID reviewId = UUID.fromString(text(args, "reviewId"));
                if (!indexed(workspace, "maid_combat_reconcile_preview", "reviewId", reviewId, "reviewHash", text(args, "reviewHash"))) return Optional.of(false);
                var review = artifact(workspace, reviewId, MaidCombatReconcile.class);
                if (review == null || !review.intact() || !review.taskId().equals(task) || !review.owner().equals(actor.owner())) return Optional.of(false);
                operation = review.operationId(); var op = repo.published(actor, operation);
                if (op == null || op.state() != MaidCombatJournal.State.RECONCILED_CURRENT_STATE || !op.reason().endsWith(":" + review.hash())) return Optional.of(false);
            }
            var op = repo.published(actor, operation); if (op == null || !op.plan().taskId().equals(task)) return Optional.of(false);
            op.plan().requireStorageIdentity(workspace, actor); return Optional.of(operationSettled(workspace, actor, operation));
        } catch (IOException | RuntimeException invalid) { return Optional.of(false); }
    }
    public void disconnected(UUID owner) {
        for (var entry : active.entrySet()) if (entry.getValue().actor.owner().equals(owner.toString())) {
            stopWork(entry.getKey(), entry.getValue(), "PLAYER_OFFLINE");
        }
    }
    /** Called by the trusted runtime state publication, including when no model worker is active. */
    public void taskChanged(Workspace workspace, firefly520.fireflymc.ai.task.AgentTask task) {
        if (!Set.of("PAUSED", "CANCELLED", "FAILED", "RECOVERY_REQUIRED", "COMPLETED").contains(task.state().name())) return;
        for (var entry : active.entrySet()) if (entry.getValue().actor.owner().equals(workspace.owner())
                && entry.getValue().witness.plan().taskId().equals(task.id())) stopWork(entry.getKey(), entry.getValue(), "TASK_" + task.state().name());
    }
    private void stopWork(UUID id, Work work, String reason) {
        work.control.stop(reason); work.ready.completeExceptionally(new TaskControl.Stopped(reason));
        if (!active.remove(id, work)) return;
        cleanup(work);
        try {
            stopReceipts.execute(() -> {
                var repo = journals.get(work.actor.owner()); if (repo == null) return;
                try {
                    var op = repo.get(work.actor, id);
                    // A committed callback may still be recording. Never close an outstanding intent as no-dispatch.
                    if (op.state() == MaidCombatJournal.State.HIT_INTENT) return;
                    releaseIfSettled(work.actor, repo.finish(work.actor, id, reason));
                } catch (IOException | RuntimeException unavailable) {
                    firefly520.fireflymc.FireflyMCMod.LOGGER.warn("[AI4] Maid combat stop requires recovery for operation {} ({})", id, unavailable.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException stopping) { /* Existing journal remains an obligation and is fenced after restart. */ }
    }
    @Override public void close() {
        closed = true;
        for (var entry : active.entrySet()) stopWork(entry.getKey(), entry.getValue(), "AI_STOPPING");
        stopReceipts.shutdown();
        active.clear(); waits.clear(); witnesses.clear(); recoveries.clear(); recoveryWaits.clear();
    }
    public boolean awaitStopped(long milliseconds) throws InterruptedException { return stopReceipts.awaitTermination(milliseconds, TimeUnit.MILLISECONDS); }
    private void requireContext(AgentTool.Context ctx) { if (closed) throw new IllegalStateException("AI_STOPPING"); ctx.actor().requireAccess(ctx.workspace().owner()); ctx.control().check(); if (ctx.taskId() == null) throw new IllegalArgumentException("MAID_COMBAT_TASK_REQUIRED"); }
    private void requireGeneration(MaidCombatPlan plan) { if (plan.generation() != generation.getAsLong()) throw new IllegalStateException("MAID_COMBAT_RESOURCES_RELOADED"); }
    private static UUID strikeId(UUID id, int index) { return UUID.nameUUIDFromBytes((id + ":maid-native-hit:" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static AgentTool.Deferred deferred(UUID id, String phase, CompletionStage<?> readiness, AgentTool.Continuation continuation) { var data = new JsonObject(); data.addProperty("operationId", id.toString()); data.addProperty("phase", phase); return new AgentTool.Deferred(phase, data, readiness, continuation); }
    public static JsonObject view(MaidCombatPlan plan) {
        var data = new JsonObject(); data.addProperty("planId", plan.id().toString()); data.addProperty("planHash", plan.hash());
        data.addProperty("maidId", plan.maidId().toString()); data.addProperty("targetId", plan.targetId().toString()); data.addProperty("targetType", plan.before().target().type());
        data.add("targetBefore", AiJson.GSON.toJsonTree(plan.before().target())); data.add("realWeapon", AiJson.GSON.toJsonTree(VirtualProcessingService.publicMaterial(plan.before().hand())));
        data.addProperty("maidHealth", plan.before().maidHealth()); data.addProperty("maidAbsorption", plan.before().maidAbsorption());
        data.addProperty("maximumHits", plan.maximumHits()); data.addProperty("maximumTicks", plan.maximumTicks()); data.addProperty("policy", "NATIVE_MAID_SINGLE_TARGET_MELEE");
        data.addProperty("supportedTargets", "vanilla zombie/husk/drowned/skeleton/stray/spider/cave_spider; other target effects require an adapter");
        data.addProperty("processingCostWaiversApply", false); data.addProperty("rewardsStayInNativeWorld", true); return data;
    }
    private static JsonObject status(MaidCombatJournal.Operation op) { var data = AiJson.GSON.toJsonTree(op).getAsJsonObject(); data.addProperty("settled", op.settled()); data.addProperty("targetDefeatedAndSaved", op.killed()); data.addProperty("historicalSnapshot", true); data.addProperty("groundDropsAreNotDeliveryReceipts", true); data.addProperty("processingCostWaiversApply", false); return data; }
    private static <T> T artifact(Workspace workspace, UUID id, Class<T> type) throws IOException { Path file = workspace.artifact(id); if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4 * 1024 * 1024) throw new IllegalArgumentException("MAID_COMBAT_ARTIFACT_NOT_FOUND"); return AiJson.GSON.fromJson(Files.readString(file), type); }
    private static boolean indexed(Workspace workspace, String kind, String idKey, UUID id, String hashKey, String hash) { if (hash == null || !hash.matches("[0-9a-f]{64}")) return false; return workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events().stream().anyMatch(event -> event.kind().equals(kind) && event.data() != null && event.data().has(idKey) && event.data().has(hashKey) && id.toString().equals(text(event.data(), idKey)) && hash.equals(text(event.data(), hashKey))); }
    private static String text(JsonObject args, String key) { return args.has(key) ? args.get(key).getAsString() : ""; }
    private static String safe(Throwable failure) { String value = failure.getMessage(); if (failure instanceof ExecutionException && failure.getCause() != null) return safe(failure.getCause()); return value != null && value.matches("[A-Z0-9_]{1,100}") ? value : "MAID_COMBAT_NATIVE_FAILURE"; }
    private static AgentTool.Result result(boolean success, String error, String summary, JsonObject data) { return new AgentTool.Result(success, error, summary, data, "", "", false); }
    @FunctionalInterface private interface Action { AgentTool.Result run(AgentTool.Context ctx, JsonObject arguments) throws Exception; }
    private void tool(ToolRegistry tools, String name, String description, String properties, List<String> required, AgentTool.Risk risk, Action action) {
        var schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", JsonParser.parseString(properties)); schema.add("required", AiJson.GSON.toJsonTree(required)); schema.addProperty("additionalProperties", false);
        tools.register(new DefinedTool(new AgentTool.Descriptor(name, description, schema, risk, risk == AgentTool.Risk.READ_ONLY ? "equipment.own.read" : "maid.combat.own", 0,
                AgentTool.ExecutionThread.IO, 120000, true, ""), (ctx, args) -> { requireContext(ctx); return action.run(ctx, args); }));
    }
}
