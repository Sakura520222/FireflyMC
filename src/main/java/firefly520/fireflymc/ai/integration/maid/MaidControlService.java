package firefly520.fireflymc.ai.integration.maid;

import com.google.gson.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.MaterialInventoryService;
import firefly520.fireflymc.ai.material.NativeSaveBarrier;
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
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;

/** Dedicated-only private native mode controls. World jobs have their own bounded action adapters. */
public final class MaidControlService implements AutoCloseable {
    private record Witness(MaidControlPlan plan, MaidControlNative.Handle handle, Object connection) {}
    private record ReconcileWitness(MaidControlReconcile plan, MaidControlNative.Handle handle, Object connection) {}
    private record ReconcileSave(NativeSaveBarrier.Receipt receipt, String proof) {}
    private record Work(TaskControl control, Callable<MaidControlNative.Outcome> action, CommittedCall<MaidControlNative.Outcome> call) {}
    private final MinecraftServer server;
    private final MaterialInventoryService inventory;
    private final VirtualProcessingService dispatcher;
    private final MaidControlNative nativeControl;
    private final UUID executionEpoch = UUID.randomUUID();
    private final Map<String, MaidControlJournal> journals = new ConcurrentHashMap<>();
    private final Map<String, MaidRecoveryIndex> recoveryIndexes = new ConcurrentHashMap<>();
    private final Map<UUID, Witness> witnesses = new ConcurrentHashMap<>();
    private final Map<UUID, MaidControlNative.Handle> acceptedHandles = new ConcurrentHashMap<>();
    private final Map<UUID, ReconcileWitness> reconcileWitnesses = new ConcurrentHashMap<>();
    private final Map<UUID, ReconcileSave> reconcileSaves = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> activeReconciliations = new ConcurrentHashMap<>();
    private final Set<UUID> executing = ConcurrentHashMap.newKeySet();
    private final ArrayBlockingQueue<Work> main = new ArrayBlockingQueue<>(64);
    private final AtomicInteger pending = new AtomicInteger();
    private final ThreadPoolExecutor evidence;
    private volatile boolean closed;

    public MaidControlService(MinecraftServer server, MaterialInventoryService inventory, InstalledPermissionAdapters permissions,
                              VirtualProcessingService dispatcher) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        this.server = server; this.inventory = inventory; this.dispatcher = dispatcher;
        evidence = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128),
                Thread.ofPlatform().daemon(true).name("fireflymc-maid-evidence").factory(), new ThreadPoolExecutor.AbortPolicy());
        nativeControl = new MaidControlNative(inventory.maids(), new MaidEntityPermission(server,
                firefly520.fireflymc.ai.AiServerService::authenticated, permissions), inventory.nativeSaves());
    }
    /** IO initialization after the normal dedicated-server and workspace authentication gates. */
    public synchronized void prepare(Workspace workspace) throws IOException {
        if (closed) throw new IllegalStateException("AI_STOPPING");
        if (!journals.containsKey(workspace.owner())) {
            var journal = new MaidControlJournal(workspace); var index = new MaidRecoveryIndex(workspace, journal);
            var events = workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events();
            for (var event : events) if (event.data() != null) {
                try {
                    if (event.kind().equals("maid_control_plan")) {
                        UUID id = UUID.fromString(event.data().get("planId").getAsString()); String hash = event.data().get("planHash").getAsString();
                        Path file = workspace.artifact(id);
                        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= 65536) {
                            var plan = AiJson.GSON.fromJson(Files.readString(file), MaidControlPlan.class);
                            if (plan != null && id.equals(plan.id()) && hash.equals(plan.hash()) && Objects.equals(event.taskId(), plan.taskId())) index.mode(plan);
                        }
                    } else if (event.kind().equals("maid_control_reconcile_preview")) {
                        UUID id = UUID.fromString(event.data().get("reviewId").getAsString()); String hash = event.data().get("reviewHash").getAsString();
                        Path file = workspace.artifact(id);
                        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= 65536) {
                            var plan = AiJson.GSON.fromJson(Files.readString(file), MaidControlReconcile.class);
                            if (plan != null && id.equals(plan.id()) && hash.equals(plan.hash()) && Objects.equals(event.taskId(), plan.taskId())) index.review(plan);
                        }
                    }
                } catch (RuntimeException invalidArtifact) { /* An invalid identity is not published and cannot clear an ACK. */ }
            }
            for (var event : events) if (event.kind().equals("maid_control_evidence") && event.data() != null && event.data().has("reason")) {
                String reason = event.data().get("reason").getAsString(); String prefix = "CURRENT_STATE_RECONCILE_PENDING:";
                if (reason.startsWith(prefix)) try { index.accepted(event.taskId(), UUID.fromString(event.data().get("id").getAsString()), reason.substring(prefix.length())); }
                catch (RuntimeException invalidEvidence) { /* Missing original acceptance remains unverified. */ }
            }
            recoveryIndexes.put(workspace.owner(), index); journals.put(workspace.owner(), journal);
        }
    }
    private MaidControlJournal journal(Workspace workspace, Actor actor) throws IOException {
        actor.requireAccess(workspace.owner()); var current = journals.get(workspace.owner());
        if (current != null) return current;
        prepare(workspace); return journals.get(workspace.owner());
    }
    public boolean requiresRecovery(Workspace workspace, Actor actor, UUID task) throws IOException {
        return journal(workspace, actor).requiresRecovery(actor, task);
    }
    public boolean resourcesSettled(Workspace workspace, Actor actor) throws IOException { return journal(workspace, actor).settled(actor); }
    public boolean operationSettled(Workspace workspace, Actor actor, UUID operation) throws IOException { return journal(workspace, actor).operationSettled(actor, operation); }
    public boolean ownsOperation(Workspace workspace, Actor actor, UUID operation) {
        actor.requireAccess(workspace.owner()); var repo = journals.get(workspace.owner()); return repo != null && repo.publishedOperation(actor, operation) != null;
    }
    public String exportJournal(Workspace workspace, Actor actor) throws IOException { return journal(workspace, actor).export(actor); }
    public void forgetErased(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner()); var old = journals.get(workspace.owner()); if (old == null) return;
        long epoch = workspace.conversation().privacyEpoch(); var operations = old.erasedOperationIds(actor, epoch);
        var index = recoveryIndexes.get(workspace.owner());
        if (!journals.remove(workspace.owner(), old)) throw new IllegalStateException("MAID_CONTROL_PRIVACY_CACHE_CHANGED");
        if (index != null) recoveryIndexes.remove(workspace.owner(), index);
        var oldReviews = reconcileWitnesses.entrySet().stream().filter(entry -> entry.getValue().plan().owner().equals(workspace.owner())
                && entry.getValue().plan().privacyEpoch() < epoch).map(Map.Entry::getKey).toList();
        witnesses.entrySet().removeIf(entry -> entry.getValue().plan().owner().equals(workspace.owner()) && entry.getValue().plan().privacyEpoch() < epoch);
        reconcileWitnesses.entrySet().removeIf(entry -> entry.getValue().plan().owner().equals(workspace.owner()) && entry.getValue().plan().privacyEpoch() < epoch);
        acceptedHandles.keySet().removeAll(operations);
        activeReconciliations.keySet().removeAll(operations);
        for (var review : oldReviews) {
            var saved = reconcileSaves.remove(review); if (saved != null) MaidControlSaveWitness.forgetCompleted(saved.receipt());
        }
        MaidControlSaveWitness.forgetErasedOwner(executionEpoch, UUID.fromString(workspace.owner()));
    }
    Optional<Boolean> settledPublishedCall(Workspace workspace, Actor actor, UUID task, ModelMessage.ToolCall call) {
        if (!Set.of("maid_control_execute", "maid_control_reconcile").contains(call.name())) return Optional.empty();
        var index = recoveryIndexes.get(workspace.owner()); return index == null ? Optional.of(false) : index.settledCall(workspace, actor, task, call);
    }
    public boolean taskSettled(Workspace workspace, Actor actor, UUID task) throws IOException {
        return !journal(workspace, actor).requiresRecovery(actor, task);
    }
    public void clearPrivateViews(String owner) {
        witnesses.entrySet().removeIf(entry -> entry.getValue().plan().owner().equals(owner));
        reconcileWitnesses.entrySet().removeIf(entry -> entry.getValue().plan().owner().equals(owner));
    }
    public void disconnected(UUID owner) { clearPrivateViews(owner.toString()); }

    private volatile java.util.function.Predicate<String> capabilityPolicy = name -> true;
    public void capabilityPolicy(java.util.function.Predicate<String> enabled) { capabilityPolicy = Objects.requireNonNull(enabled); }
    public void register(ToolRegistry tools) {
        tool(tools, "maid_control_capabilities", "查看已接入的女仆原生模式控制与尚未就绪任务类别；不创建女仆或启动第二个 AI 会话。",
                "{}", List.of(), AgentTool.Risk.READ_ONLY, (ctx, args) -> {
                    dispatcher.inspectOwned(ctx, () -> inventory.requirePlayer(ctx.actor()));
                    var data = new JsonObject(); data.add("nativeModes", AiJson.GSON.toJsonTree(List.of("FOLLOW", "STAY")));
                    data.addProperty("scope", "已绑定本人的空闲女仆；切换并保存模式不等于已到达或完成世界任务。");
                    var tasks = new JsonObject();
                    tasks.addProperty("harvest", tools.get("maid_farm_plan") != null && capabilityPolicy.test("maid_farm_execute")
                            ? "FIXED_VANILLA_CROP_PLANNING_REGISTERED" : "ACTION_ADAPTER_NOT_READY");
                    tasks.addProperty("transport", tools.get("maid_transport_plan") != null && capabilityPolicy.test("maid_transport_execute")
                            ? "FIXED_NATIVE_WIRELESS_PLANNING_REGISTERED" : "ACTION_ADAPTER_NOT_READY");
                    tasks.addProperty("combat", tools.get("maid_combat_plan") != null && capabilityPolicy.test("maid_combat_execute")
                            ? "BOUNDED_NATIVE_MELEE_PLANNING_REGISTERED" : "ACTION_ADAPTER_NOT_READY"); data.add("worldTasks", tasks);
                    data.addProperty("worldTaskScope", "农作仅白名单原版作物；无线搬运需真实佩戴/绑定与两个独立来源授权。注册状态不表示本次条件或版本已经就绪，先调用对应plan工具进行服务端现检。");
                    return result(true, "", "可审查的原生模式与任务状态。", data);
                });
        tool(tools, "maid_control_plan", "规划已绑定本人女仆的原生 FOLLOW 跟随或 STAY 坐下模式。保留拾取、乘骑和日程设置，不改变原生任务或豁免任何费用；需要单独工作台审批。",
                "{\"mode\":{\"type\":\"string\",\"enum\":[\"FOLLOW\",\"STAY\"]}}", List.of("mode"), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> result(true, "", "已固定女仆、原生状态和模式；尚未改变实体。", view(plan(ctx, MaidControlPlan.Mode.valueOf(text(args, "mode"))))));
        tool(tools, "maid_control_execute", "执行已批准的女仆原生模式版本；重验主人、绑定、领地、原生任务实例及当前模式，记录真实接受结果并等待实体保存。成功仅指模式接受并保存，不声称已到达或已完成采集/战斗。",
                "{\"planId\":{\"type\":\"string\"},\"planHash\":{\"type\":\"string\"}}", List.of("planId", "planHash"), AgentTool.Risk.PLAYER_STATE,
                (ctx, args) -> execute(ctx, load(ctx.workspace(), ctx.actor(), UUID.fromString(text(args, "planId")), text(args, "planHash"))));
        tool(tools, "maid_control_status", "读取本人女仆模式操作的历史实际回执与保存状态；不把历史状态当成女仆当前工作进度。",
                "{\"operationId\":{\"type\":\"string\"}}", List.of("operationId"), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> result(true, "", "本人原生模式操作的私有历史凭据。", status(journal(ctx.workspace(), ctx.actor()).get(ctx.actor(), UUID.fromString(text(args, "operationId"))))));
        tool(tools, "maid_control_reconcile", "按新的工作台批准核对原女仆当前模式并取得新的实体保存证明；不重放旧 setter、不声明旧模式成功。原任务须据此重新规划。",
                "{\"reviewId\":{\"type\":\"string\"},\"reviewHash\":{\"type\":\"string\"}}", List.of("reviewId", "reviewHash"), AgentTool.Risk.PLAYER_STATE,
                (ctx, args) -> reconcile(ctx, loadReconciliation(ctx.workspace(), ctx.actor(), UUID.fromString(text(args, "reviewId")), text(args, "reviewHash"))));
    }
    public List<JsonObject> recoveryRows(Workspace workspace, Actor actor, UUID taskId) throws IOException {
        return journal(workspace, actor).snapshot(actor).stream().filter(op -> op.taskId().equals(taskId)).map(op -> {
            var row = new JsonObject(); row.addProperty("operationId", op.id().toString()); row.addProperty("taskId", op.taskId().toString());
            row.addProperty("kind", "MAID_MODE"); row.addProperty("state", op.state().name()); row.addProperty("revision", op.revision());
            row.addProperty("reason", op.reason()); row.addProperty("maidId", op.before().maidId().toString()); row.addProperty("mode", op.mode().name());
            row.addProperty("settled", op.settled()); row.addProperty("saved", !op.nativeProof().isEmpty()); row.addProperty("reconcilable", !op.settled());
            row.add("actions", AiJson.GSON.toJsonTree(op.settled() ? List.of() : List.of("MAID_RECONCILE"))); return row;
        }).toList();
    }
    public JsonObject recoveryResources(Workspace workspace, Actor actor, UUID operationId) throws IOException {
        var operation = journal(workspace, actor).snapshot(actor).stream().filter(op -> op.id().equals(operationId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("MAID_CONTROL_OPERATION_NOT_FOUND"));
        var result = recoveryResources(operation);
        result.addProperty("serverInstanceId", workspace.metadata().serverInstanceId().toString());
        result.addProperty("conversationId", workspace.primaryConversationId().toString());
        result.addProperty("currentPrivacyEpoch", workspace.conversation().privacyEpoch());
        result.add("originalPlan", AiJson.GSON.toJsonTree(readStoredPlan(workspace, actor, operation.id(), operation.planHash())));
        return result;
    }
    private static JsonObject recoveryResources(MaidControlJournal.Operation op) {
        var row = status(op); row.addProperty("operationId", op.id().toString()); row.addProperty("taskId", op.taskId().toString());
        row.addProperty("digest", AiJson.digest(AiJson.canonical(op))); row.addProperty("originalModeSuccessClaimed", op.state() == MaidControlJournal.State.SAVED);
        return row;
    }
    public MaidControlReconcile recoveryPreview(AgentTool.Context ctx, UUID operationId) throws Exception {
        requireContext(ctx); var repo = journal(ctx.workspace(), ctx.actor()); var op = repo.get(ctx.actor(), operationId);
        if (!op.taskId().equals(ctx.taskId())) throw new SecurityException("MAID_CONTROL_TASK_MISMATCH");
        if (op.settled()) throw new IllegalStateException("MAID_CONTROL_ALREADY_SETTLED");
        var witness = dispatcher.inspectOwned(ctx, () -> {
            var player = inventory.requirePlayer(ctx.actor()); var current = nativeControl.inspectRecovery(player, player.getUUID(), op.before().maidId());
            var review = new MaidControlReconcile(1, UUID.randomUUID(), ctx.actor().owner(), ctx.taskId(), ctx.workspace().metadata().serverInstanceId(),
                    ctx.workspace().primaryConversationId(), ctx.workspace().conversation().privacyEpoch(), executionEpoch, System.currentTimeMillis() + 120000,
                    operationId, op.revision(), AiJson.digest(AiJson.canonical(op)), current.state(), "").signed();
            return new ReconcileWitness(review, current, player.connection);
        });
        var review = witness.plan();
        synchronized (ctx.workspace().conversation()) {
            requireContext(ctx); review.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch);
            if (!repo.get(ctx.actor(), operationId).equals(op)) throw new IllegalStateException("MAID_RECONCILE_PREVIEW_CHANGED");
            reconcileWitnesses.entrySet().removeIf(entry -> entry.getValue().plan().expiresAt() <= System.currentTimeMillis()
                    && !activeReconciliations.containsValue(entry.getKey()));
            if (reconcileWitnesses.size() >= 256) throw new IllegalStateException("MAID_CONTROL_PREVIEW_LIMIT");
            AiJson.write(ctx.workspace().artifact(review.id()), review);
            var data = new JsonObject(); data.addProperty("reviewId", review.id().toString()); data.addProperty("reviewHash", review.hash());
            data.addProperty("operationId", operationId.toString());
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "maid_control_reconcile_preview", null, data, null)));
            reconcileWitnesses.put(review.id(), witness);
            recoveryIndexes.get(ctx.workspace().owner()).review(review);
        }
        return review;
    }
    public MaidControlReconcile loadReconciliation(Workspace workspace, Actor actor, UUID reviewId, String hash) throws IOException {
        var loaded = readReconciliation(workspace, actor, reviewId, hash); loaded.requireIdentity(workspace, actor, executionEpoch);
        var witness = reconcileWitnesses.get(reviewId);
        if (witness == null || !witness.plan().equals(loaded)) throw new IllegalStateException("MAID_RECONCILE_NEW_PREVIEW_REQUIRED");
        if (System.currentTimeMillis() >= loaded.expiresAt()) throw new IllegalStateException("MAID_CONTROL_PREVIEW_EXPIRED");
        return witness.plan();
    }
    public UUID reconciliationOperationReference(Workspace workspace, Actor actor, UUID reviewId, String hash) throws IOException {
        return readReconciliation(workspace, actor, reviewId, hash).operationId();
    }
    static MaidControlReconcile readReconciliation(Workspace workspace, Actor actor, UUID reviewId, String hash) throws IOException {
        actor.requireAccess(workspace.owner());
        if (reviewId == null || hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("MAID_RECONCILE_NOT_FOUND");
        synchronized (workspace.conversation()) {
            boolean indexed = workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events().stream().anyMatch(event ->
                    event.kind().equals("maid_control_reconcile_preview") && event.data() != null && event.data().has("reviewId") && event.data().has("reviewHash")
                    && reviewId.toString().equals(event.data().get("reviewId").getAsString()) && hash.equals(event.data().get("reviewHash").getAsString()));
            Path file = workspace.artifact(reviewId);
            if (!indexed || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 65536) throw new IllegalArgumentException("MAID_RECONCILE_NOT_FOUND");
            var review = AiJson.GSON.fromJson(Files.readString(file), MaidControlReconcile.class);
            if (review == null || !reviewId.equals(review.id()) || !hash.equals(review.hash())) throw new SecurityException("MAID_RECONCILE_IDENTITY_CHANGED");
            review.requireStorageIdentity(workspace, actor); return review;
        }
    }
    public void validateReconciliationVersion(Workspace workspace, Actor actor, MaidControlReconcile review) throws IOException {
        review.requireIdentity(workspace, actor, executionEpoch);
        var witness = reconcileWitnesses.get(review.id());
        if (witness == null || witness.plan() != review) throw new IllegalStateException("MAID_RECONCILE_NEW_PREVIEW_REQUIRED");
        var op = journal(workspace, actor).get(actor, review.operationId());
        if (op.settled() || op.revision() != review.operationRevision() || !AiJson.digest(AiJson.canonical(op)).equals(review.operationDigest()))
            throw new IllegalStateException("MAID_RECONCILE_PREVIEW_CHANGED");
    }
    public String reconciliationReview(MaidControlReconcile review) {
        return "核对原女仆当前真实模式并等待新的原生实体保存。不会重放原模式 setter、移动玩家、自动复原人工修改或宣称旧模式成功。"
                + "保存和当前状态核验通过后，只收束原模式义务，原任务仍须按实际状态重新规划。\n" + AiJson.GSON.toJson(review);
    }
    public AgentTool.Result reconcile(AgentTool.Context ctx, MaidControlReconcile review) throws Exception {
        requireContext(ctx); review.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch);
        if (!review.taskId().equals(ctx.taskId())) throw new SecurityException("MAID_CONTROL_TASK_MISMATCH");
        if (!executing.add(review.operationId())) throw new IllegalStateException("MAID_CONTROL_ALREADY_EXECUTING");
        var repo = journal(ctx.workspace(), ctx.actor()); boolean deferred = false;
        try {
            if (System.currentTimeMillis() >= review.expiresAt()) throw new IllegalStateException("MAID_CONTROL_PREVIEW_EXPIRED");
            validateReconciliationVersion(ctx.workspace(), ctx.actor(), review);
            var witness = reconcileWitnesses.get(review.id());
            dispatcher.inspectOwned(ctx, () -> { validateReconcileNative(ctx, witness); return true; });
            repo.beginReconciliation(ctx.actor(), review); activeReconciliations.put(review.operationId(), review.id());
            recoveryIndexes.get(ctx.workspace().owner()).accepted(review.taskId(), review.operationId(), review.hash());
            dispatcher.commitObserved(ctx, () -> {
                var current = validateReconcileNative(ctx, witness);
                return MaidControlSaveWitness.observeReconciliation(current.entity(), UUID.fromString(ctx.actor().owner()), current.state(),
                        inventory.nativeSaves(), review.id(), executionEpoch);
            }, receipt -> {
                reconcileSaves.put(review.id(), new ReconcileSave(receipt, ""));
                inventory.nativeSaves().record(review.id(), receipt, proof -> {
                    repo.currentProof(ctx.actor(), review, proof); reconcileSaves.put(review.id(), new ReconcileSave(receipt, proof));
                });
            });
            return finishReconciliation(ctx, review);
        } catch (AgentTool.Deferred waiting) { deferred = true; throw waiting; }
        finally {
            executing.remove(review.operationId());
            if (!deferred) activeReconciliations.remove(review.operationId(), review.id());
        }
    }
    private MaidControlNative.Handle validateReconcileNative(AgentTool.Context ctx, ReconcileWitness witness) throws Exception {
        if (witness == null) throw new IllegalStateException("MAID_RECONCILE_NEW_PREVIEW_REQUIRED");
        var player = inventory.requirePlayer(ctx.actor());
        if (player.connection != witness.connection()) throw new SecurityException("MAID_CONTROL_CONNECTION_CHANGED");
        var observed = nativeControl.inspectRecovery(player, player.getUUID(), witness.plan().observed().maidId());
        if (observed.entity() != witness.handle().entity() || observed.task() != witness.handle().task() || observed.revision() != witness.handle().revision()
                || !observed.state().equals(witness.plan().observed())) throw new IllegalStateException("MAID_RECONCILE_NATIVE_STATE_CHANGED");
        return observed;
    }
    private AgentTool.Result finishReconciliation(AgentTool.Context ctx, MaidControlReconcile review) throws Exception {
        requireContext(ctx); review.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch);
        if (!review.taskId().equals(ctx.taskId()) || !review.id().equals(activeReconciliations.get(review.operationId())))
            throw new IllegalStateException("MAID_RECONCILE_SUPERSEDED");
        var repo = journal(ctx.workspace(), ctx.actor()); repo.requireReconciliation(ctx.actor(), review);
        var ready = inventory.nativeSaves().awaitJournalled(review.id());
        if (!ready.toCompletableFuture().isDone()) {
            var data = new JsonObject(); data.addProperty("operationId", review.operationId().toString()); data.addProperty("reviewId", review.id().toString());
            throw new AgentTool.Deferred("WAITING_MAID_RECONCILE_SAVE", data, ready, fresh -> finishReconciliation(fresh, review));
        }
        var saved = reconcileSaves.get(review.id());
        if (saved == null || !saved.proof().matches("[0-9a-f]{64}")) throw new IllegalStateException("MAID_RECONCILE_POSITIVE_PROOF_REQUIRED");
        dispatcher.inspectOwned(ctx, () -> { validateReconcileNative(ctx, reconcileWitnesses.get(review.id())); return true; });
        ctx.control().check(); repo.requireReconciliation(ctx.actor(), review);
        // This durable write must succeed before the old mode's save dependency may be superseded.
        var operation = repo.reconcileAndRetire(ctx.actor(), review, saved.proof(), committed -> {
            var cleanup = new CompletableFuture<Void>();
            server.execute(() -> {
                try {
                    MaidControlSaveWitness.supersede(review.operationId(), review.id(), executionEpoch, saved.receipt(),
                            UUID.fromString(ctx.actor().owner()), review.observed().maidId()); cleanup.complete(null);
                } catch (Exception failure) { cleanup.completeExceptionally(failure); }
            });
            // The actual resolution is already durable. Pure receipt cleanup outlives a stopped waiter.
            cleanup.get(10000, TimeUnit.MILLISECONDS);
        });
        activeReconciliations.remove(review.operationId(), review.id()); reconcileWitnesses.remove(review.id());
        reconcileSaves.remove(review.id()); MaidControlSaveWitness.forgetCompleted(saved.receipt());
        return result(true, "RECONCILED_CURRENT_STATE", "已按新保存凭据核对当前女仆状态；未重放旧模式，未宣称旧模式成功。原任务需重新规划。", status(operation));
    }
    public MaidControlPlan plan(AgentTool.Context ctx, MaidControlPlan.Mode mode) throws Exception {
        requireContext(ctx); var repo = journal(ctx.workspace(), ctx.actor());
        if (!repo.settled(ctx.actor())) throw new IllegalStateException("MAID_CONTROL_REVIEW_REQUIRED");
        var witness = dispatcher.inspectOwned(ctx, () -> {
            var player = inventory.requirePlayer(ctx.actor()); var handle = nativeControl.capture(player, player.getUUID());
            var plan = new MaidControlPlan(1, UUID.randomUUID(), ctx.actor().owner(), ctx.taskId(), ctx.workspace().metadata().serverInstanceId(),
                    ctx.workspace().primaryConversationId(), ctx.workspace().conversation().privacyEpoch(), executionEpoch,
                    System.currentTimeMillis() + 120000, mode, handle.state(), "").signed();
            return new Witness(plan, handle, player.connection);
        });
        var plan = witness.plan();
        synchronized (ctx.workspace().conversation()) {
            requireContext(ctx); plan.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch);
            AiJson.write(ctx.workspace().artifact(plan.id()), plan);
            var manifest = new JsonObject(); manifest.addProperty("planId", plan.id().toString()); manifest.addProperty("planHash", plan.hash());
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "maid_control_plan", null, manifest, null)));
            witnesses.entrySet().removeIf(entry -> entry.getValue().plan().expiresAt() <= System.currentTimeMillis());
            if (witnesses.size() >= 256) throw new IllegalStateException("MAID_CONTROL_PREVIEW_LIMIT");
            witnesses.put(plan.id(), witness);
            recoveryIndexes.get(ctx.workspace().owner()).mode(plan);
        }
        return plan;
    }
    public MaidControlPlan load(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        var loaded = readStoredPlan(workspace, actor, id, hash); loaded.requireIdentity(workspace, actor, executionEpoch);
        var witness = witnesses.get(id);
        if (witness == null || !witness.plan().equals(loaded)) throw new IllegalStateException("MAID_CONTROL_NEW_PREVIEW_REQUIRED");
        if (System.currentTimeMillis() >= loaded.expiresAt()) throw new IllegalStateException("MAID_CONTROL_PREVIEW_EXPIRED");
        return witness.plan();
    }
    private static MaidControlPlan readStoredPlan(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        actor.requireAccess(workspace.owner());
        if (id == null || hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("MAID_CONTROL_PLAN_NOT_FOUND");
        synchronized (workspace.conversation()) {
            var indexed = workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events().stream().anyMatch(event ->
                    event.kind().equals("maid_control_plan") && event.data() != null && event.data().has("planId") && event.data().has("planHash")
                    && id.toString().equals(event.data().get("planId").getAsString()) && hash.equals(event.data().get("planHash").getAsString()));
            Path path = workspace.artifact(id);
            if (!indexed || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 65536) throw new IllegalArgumentException("MAID_CONTROL_PLAN_NOT_FOUND");
            var loaded = AiJson.GSON.fromJson(Files.readString(path), MaidControlPlan.class);
            if (loaded == null || !loaded.id().equals(id) || !loaded.hash().equals(hash)) throw new SecurityException("MAID_CONTROL_PLAN_CHANGED");
            loaded.requireStorageIdentity(workspace, actor); return loaded;
        }
    }
    public String review(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        return "只切换并保存本人已绑定女仆的原生模式。跟随继续使用原生寻路/宠物传送机制，不移动玩家；坐下清理当前导航和攻击目标。"
                + "不修改日程、拾取或乘骑设置，不豁免资源、工具或冷却，不声明已到达或已完成世界任务。\n" + AiJson.GSON.toJson(view(load(workspace, actor, id, hash)));
    }
    public AgentTool.Result execute(AgentTool.Context ctx, MaidControlPlan plan) throws Exception {
        requireContext(ctx); plan.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch);
        if (!plan.taskId().equals(ctx.taskId())) throw new SecurityException("MAID_CONTROL_TASK_MISMATCH");
        var repo = journal(ctx.workspace(), ctx.actor());
        if (!executing.add(plan.id())) throw new IllegalStateException("MAID_CONTROL_ALREADY_EXECUTING");
        try {
            if (repo.find(ctx.actor(), plan.id()).isPresent()) return result(false, "MAID_CONTROL_ALREADY_RECORDED", "操作已有实际回执，未重复改变女仆。", status(repo.get(ctx.actor(), plan.id())));
            var witness = witnesses.get(plan.id());
            if (witness == null || witness.plan() != plan || System.currentTimeMillis() >= plan.expiresAt()) throw new IllegalStateException("MAID_CONTROL_NEW_PREVIEW_REQUIRED");
            dispatcher.inspectOwned(ctx, () -> {
                var player = inventory.requirePlayer(ctx.actor());
                if (player.connection != witness.connection()) throw new SecurityException("MAID_CONTROL_CONNECTION_CHANGED");
                MaidControlNative.requireSame(witness.handle(), nativeControl.capture(player, player.getUUID()), plan.before()); return true;
            });
            ctx.control().check(); repo.intent(ctx.actor(), plan);
            dispatch(ctx, plan, witness, repo);
            ctx.control().check(); return complete(ctx, plan.id());
        } catch (AgentTool.Deferred wait) { throw wait; }
        catch (TaskControl.Stopped stopped) {
            var op = repo.find(ctx.actor(), plan.id());
            if (op.isEmpty()) throw stopped;
            return result(false, op.get().settled() ? stopped.getMessage() : "RECOVERY_REQUIRED", "等待已停止；实际模式操作回执和晚到保存仍保留，未重放。", status(op.get()));
        } finally { executing.remove(plan.id()); }
    }
    private void dispatch(AgentTool.Context ctx, MaidControlPlan plan, Witness witness, MaidControlJournal repo) throws Exception {
        var call = new CommittedCall<MaidControlNative.Outcome>(); var recorded = new CompletableFuture<MaidControlJournal.Operation>();
        pending.incrementAndGet();
        call.whenSettled((effect, failure) -> {
            try { evidence.execute(() -> {
                try {
                    if (failure instanceof CommittedCall.Rejected) recorded.complete(repo.rejected(ctx.actor(), plan.id(), "REJECTED_BEFORE_NATIVE_INVOCATION"));
                    else if (failure != null) recorded.complete(repo.uncertain(ctx.actor(), plan.id(), "MAID_NATIVE_RETURN_UNCERTAIN"));
                    else {
                        var op = repo.observed(ctx.actor(), plan.id(), effect);
                        if (effect.entered() && effect.save() != null)
                            inventory.nativeSaves().record(plan.id(), effect.save(), proof -> repo.saved(ctx.actor(), plan.id(), proof));
                        recorded.complete(op);
                    }
                } catch (Exception recording) { recorded.completeExceptionally(recording); }
            }); } catch (RejectedExecutionException unavailable) { recorded.completeExceptionally(unavailable); }
            finally { if (pending.decrementAndGet() == 0 && closed) evidence.shutdown(); }
        });
        Callable<MaidControlNative.Outcome> action = () -> {
            var player = inventory.requirePlayer(ctx.actor());
            if (player.connection != witness.connection()) return new MaidControlNative.Outcome(false, false, false, null, "MAID_CONTROL_CONNECTION_CHANGED", null);
            var applied = nativeControl.apply(player, player.getUUID(), plan, witness.handle());
            if (applied.entered()) {
                var after = nativeControl.takeAfter(plan.id()); acceptedHandles.put(plan.id(), after == null ? witness.handle() : after);
            }
            return applied;
        };
        if (closed || !main.offer(new Work(ctx.control(), action, call))) call.rejectBeforeClaim(new IllegalStateException("MAID_CONTROL_QUEUE_UNAVAILABLE"));
        try { call.awaitEvidence(10000); }
        catch (CommittedCall.Rejected safe) { /* The accepted writer records the explicit rejection. */ }
        recorded.get(10000, TimeUnit.MILLISECONDS);
    }
    private AgentTool.Result complete(AgentTool.Context ctx, UUID id) throws Exception {
        requireContext(ctx); var repo = journal(ctx.workspace(), ctx.actor()); var op = repo.get(ctx.actor(), id);
        if (!op.taskId().equals(ctx.taskId())) throw new SecurityException("MAID_CONTROL_TASK_MISMATCH");
        if (op.state() == MaidControlJournal.State.RECONCILED_CURRENT_STATE)
            return result(false, "RECONCILED_CURRENT_STATE", "原操作已按新保存的当前状态收束，未重放或补称旧模式成功。", status(op));
        if (op.state() == MaidControlJournal.State.OBSERVED) {
            var ready = inventory.nativeSaves().awaitJournalled(id);
            if (!ready.toCompletableFuture().isDone()) {
                var checkpoint = new JsonObject(); checkpoint.addProperty("operationId", id.toString()); checkpoint.addProperty("phase", "WAITING_MAID_NATIVE_SAVE");
                throw new AgentTool.Deferred("WAITING_MAID_NATIVE_SAVE", checkpoint, ready, fresh -> complete(fresh, id));
            }
            op = repo.get(ctx.actor(), id);
            if (op.state() == MaidControlJournal.State.OBSERVED) op = repo.uncertain(ctx.actor(), id, "MAID_NATIVE_SAVE_PROOF_UNAVAILABLE");
        }
        if (op.state() == MaidControlJournal.State.SAVED || op.state() == MaidControlJournal.State.NO_CHANGE) {
            var expected = acceptedHandles.get(id); var saved = op;
            boolean current = dispatcher.inspectOwned(ctx, () -> {
                var player = inventory.requirePlayer(ctx.actor()); var observed = nativeControl.capture(player, player.getUUID());
                return observed.state().maidId().equals(saved.before().maidId()) && observed.state().modeObserved(saved.mode())
                        && (expected == null || observed.entity() == expected.entity() && observed.task() == expected.task() && observed.revision() == expected.revision());
            });
            acceptedHandles.remove(id); witnesses.remove(id);
            return result(current, current ? "" : "MAID_NATIVE_MODE_CHANGED", current
                    ? op.state() == MaidControlJournal.State.SAVED ? "女仆原生模式已接受并有实体保存凭据；尚未声明到达或世界任务完成。" : "服务端核验女仆已处于该原生模式，没有重复写入。"
                    : "原操作历史凭据已保存，但女仆当前模式已改变；没有覆盖新的人工或原生控制。", status(op));
        }
        return result(false, op.settled() ? op.reason() : "RECOVERY_REQUIRED", "女仆模式操作未确认完成；实际回执保留，不能重放。", status(op));
    }
    public void tick() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        Work work = main.poll(); if (work != null) work.call().run(work.control(), work.action());
    }
    @Override public void close() {
        closed = true; Work work;
        while ((work = main.poll()) != null) work.call().rejectBeforeClaim(new IllegalStateException("AI_STOPPING"));
        witnesses.clear(); acceptedHandles.clear(); reconcileWitnesses.clear(); reconcileSaves.clear(); activeReconciliations.clear();
        MaidControlSaveWitness.closeScope(executionEpoch); if (pending.get() == 0) evidence.shutdown();
    }
    public boolean awaitStopped(long millis) throws InterruptedException { return evidence.awaitTermination(millis, TimeUnit.MILLISECONDS); }
    private void requireContext(AgentTool.Context ctx) {
        if (closed) throw new IllegalStateException("AI_STOPPING");
        ctx.actor().requireAccess(ctx.workspace().owner()); ctx.control().check();
        if (ctx.taskId() == null) throw new IllegalArgumentException("MAID_CONTROL_TASK_REQUIRED");
    }
    public static JsonObject view(MaidControlPlan plan) {
        var data = new JsonObject(); data.addProperty("planId", plan.id().toString()); data.addProperty("planHash", plan.hash());
        data.addProperty("mode", plan.mode().name()); data.add("before", AiJson.GSON.toJsonTree(plan.before())); data.addProperty("expiresAt", plan.expiresAt());
        data.addProperty("arrivalOrWorldTaskCompleted", false); data.addProperty("processingCostWaiversApply", false); return data;
    }
    private static JsonObject status(MaidControlJournal.Operation op) {
        var data = AiJson.GSON.toJsonTree(op).getAsJsonObject(); data.addProperty("settledAndSaved", op.settled());
        data.addProperty("arrivalOrWorldTaskCompleted", false); data.addProperty("private", true); return data;
    }
    @FunctionalInterface private interface Action { AgentTool.Result run(AgentTool.Context ctx, JsonObject arguments) throws Exception; }
    private void tool(ToolRegistry registry, String name, String description, String properties, List<String> required, AgentTool.Risk risk, Action action) {
        var schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", JsonParser.parseString(properties));
        schema.add("required", AiJson.GSON.toJsonTree(required)); schema.addProperty("additionalProperties", false);
        registry.register(new DefinedTool(new AgentTool.Descriptor(name, description, schema, risk,
                risk == AgentTool.Risk.READ_ONLY ? "equipment.own.read" : "maid.control.own", 0, AgentTool.ExecutionThread.IO, 120000, true, ""),
                (ctx, args) -> { requireContext(ctx); return action.run(ctx, args); }));
    }
    private static String text(JsonObject args, String key) { return args.has(key) ? args.get(key).getAsString() : ""; }
    private static AgentTool.Result result(boolean success, String code, String summary, JsonObject data) {
        return new AgentTool.Result(success, code, summary, data, "server-native-maid-mode-1.5.3", "", false);
    }
}
