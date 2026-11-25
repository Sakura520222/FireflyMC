package firefly520.fireflymc.ai.recovery;

import com.google.gson.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.processing.*;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Deterministic private recovery entry point; it never submits a model request or changes task identity. */
public final class RecoveryWorkbenchService implements AutoCloseable {
    public enum Action { MATERIAL_RECONCILE, MIXED_RECONCILE, MATERIAL_RETURN, MIXED_RETURN, MIXED_RESUME, TRAVEL_RECONCILE, MAID_RECONCILE, COMPASS_RECONCILE, MAID_TRANSPORT_RECONCILE, BUILD_RECONCILE, TREE_RECONCILE }
    @FunctionalInterface interface Dispatch {
        CompletableFuture<AgentTool.Result> execute(AgentTool tool, AgentTool.Context context, JsonObject arguments,
                                                     String approval, ModelMessage.ToolCall call);
    }
    private final MaterialLedger materials;
    private final FluidProcessingService mixed;
    private final VirtualProcessingService processing;
    private final ToolRegistry tools;
    private final Dispatch bridge;
    private final Function<UUID, CompletionStage<Void>> awaitSaved;
    private final Function<String, Actor> currentActor;
    private final Predicate<String> enabled;
    private final BiConsumer<Workspace, UUID> settlement;
    private final LongSupplier clock;
    private final RecoveryPreviewStore previews;
    private final ThreadPoolExecutor lifecycle = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128), runnable -> { Thread thread = new Thread(runnable, "firefly-ai-recovery"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.AbortPolicy());
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final Set<String> busyOperations = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile boolean finalizing;
    private volatile BiPredicate<Workspace, UUID> worldOperations = (workspace, id) -> false;
    private volatile BiFunction<Workspace, UUID, List<JsonObject>> additionalRows = (workspace, task) -> List.of();
    private final Map<Action, RecoveryModule> modules = new ConcurrentHashMap<>();

    public RecoveryWorkbenchService(MaterialLedger materials, FluidProcessingService mixed, VirtualProcessingService processing,
            ToolRegistry tools, GameToolBridge bridge, NativeSaveObserver saves, Function<String, Actor> currentActor,
            Predicate<String> enabled, BiConsumer<Workspace, UUID> settlement) {
        this(materials, mixed, processing, tools, bridge::execute, saves::awaitJournalled, currentActor, enabled,
                settlement, System::currentTimeMillis);
    }
    RecoveryWorkbenchService(MaterialLedger materials, FluidProcessingService mixed, VirtualProcessingService processing,
            ToolRegistry tools, Dispatch bridge, Function<UUID, CompletionStage<Void>> awaitSaved,
            Function<String, Actor> currentActor, Predicate<String> enabled, BiConsumer<Workspace, UUID> settlement, LongSupplier clock) {
        this.materials = Objects.requireNonNull(materials); this.mixed = mixed; this.processing = processing;
        this.tools = Objects.requireNonNull(tools); this.bridge = Objects.requireNonNull(bridge);
        this.awaitSaved = Objects.requireNonNull(awaitSaved); this.currentActor = Objects.requireNonNull(currentActor);
        this.enabled = Objects.requireNonNull(enabled); this.settlement = Objects.requireNonNull(settlement);
        this.clock = Objects.requireNonNull(clock); previews = new RecoveryPreviewStore(clock);
    }
    /** Trusted wiring only: journals determine ownership, never a client flag. */
    public void worldOperations(BiPredicate<Workspace, UUID> predicate) { worldOperations = Objects.requireNonNull(predicate); }
    public void additionalRows(BiFunction<Workspace, UUID, List<JsonObject>> rows) { additionalRows = Objects.requireNonNull(rows); }
    public void registerModule(Action action, RecoveryModule module) {
        if (!moduleAction(action) || modules.putIfAbsent(action, Objects.requireNonNull(module)) != null)
            throw new IllegalArgumentException("RECOVERY_MODULE_REGISTRATION");
    }
    private static boolean moduleAction(Action action) { return action == Action.TRAVEL_RECONCILE || action == Action.MAID_RECONCILE || action == Action.COMPASS_RECONCILE || action == Action.MAID_TRANSPORT_RECONCILE || action == Action.BUILD_RECONCILE || action == Action.TREE_RECONCILE; }
    private RecoveryModule module(Action action) {
        var value = modules.get(action); if (value == null) throw new IllegalStateException("RECOVERY_MODULE_UNAVAILABLE"); return value;
    }
    public Optional<Boolean> settledModuleCall(Workspace workspace, Actor actor, UUID taskId, ModelMessage.ToolCall call) {
        Optional<Boolean> found = Optional.empty();
        try {
            actor.requireAccess(workspace.owner());
            for (var module : modules.values()) {
                var result = module.settledCall(workspace, actor, taskId, call);
                if (result.isPresent()) { if (found.isPresent()) return Optional.of(false); found = result; }
            }
            return found;
        } catch (Exception invalid) { return Optional.of(false); }
    }
    private static void moduleTask(JsonObject evidence, UUID taskId) {
        if (!evidence.has("taskId") || !taskId.toString().equals(evidence.get("taskId").getAsString()))
            throw new SecurityException("RECOVERY_TASK_OPERATION_MISMATCH");
    }

    public JsonObject view(Workspace workspace, Actor actor, UUID taskId, int offset) throws Exception {
        actor.requireAccess(workspace.owner()); var task = requireTask(workspace, actor, taskId, false);
        var all = new ArrayList<JsonObject>(); var joint = mixedOperations(workspace, actor); var jointIds = new HashSet<UUID>();
        for (var op : joint) {
            UUID id = op.definition().id(); jointIds.add(id);
            if (!linked(workspace, actor, taskId, id, op.definition().taskId())) continue;
            var row = row(id, "MIXED", taskId, op.state().name(), op.reason());
            if (op.state() == MixedResourceJournal.State.RECOVERY_REQUIRED) {
                try { mixed.recoveryReview(workspace, actor, id); addAction(row, Action.MIXED_RECONCILE); }
                catch (RuntimeException insufficient) { row.addProperty("reason", safe(insufficient)); }
            } else if (Set.of(MixedResourceJournal.State.INPUTS_PENDING, MixedResourceJournal.State.COMMITTED,
                    MixedResourceJournal.State.RETURN_INTENT, MixedResourceJournal.State.RETURNING).contains(op.state())) addAction(row, Action.MIXED_RETURN);
            if (op.definition().taskId().equals(taskId) && mixed.resumeAvailable(workspace, actor, id)) addAction(row, Action.MIXED_RESUME);
            all.add(row);
        }
        for (var op : materials.list(actor)) {
            if (jointIds.contains(op.operationId()) || !linked(workspace, actor, taskId, op.operationId(), op.taskId())) continue;
            boolean world = worldOwned(workspace, op);
            var row = row(op.operationId(), world ? "WORLD" : "ITEMS", taskId, op.state().name(),
                    world ? "WORLD_RECOVERY_VERIFIER_UNAVAILABLE" : op.reason());
            row.addProperty("saved", op.saved());
            if (!world && op.uncertain()) {
                try { materials.recoveryReview(actor, op.operationId()); addAction(row, Action.MATERIAL_RECONCILE); }
                catch (RuntimeException insufficient) { row.addProperty("reason", safe(insufficient)); }
            } else if (!world && materialReturnable(op)) addAction(row, Action.MATERIAL_RETURN);
            if (!op.saved()) row.addProperty("reasonText", "等待真实原生保存凭证；不能凭数量认定完成。");
            all.add(row);
        }
        all.addAll(additionalRows.apply(workspace, taskId));
        for (var provider : modules.entrySet()) for (var row : provider.getValue().rows(workspace, actor, taskId)) {
            moduleTask(row, taskId);
            if (provider.getKey() == Action.BUILD_RECONCILE) all.removeIf(previous ->
                    previous.get("operationId").equals(row.get("operationId"))
                            && Set.of("WORLD", "BUILD").contains(previous.get("kind").getAsString()));
            all.add(row.deepCopy());
        }
        if (task.state() != AgentTask.State.RECOVERY_REQUIRED) for (var row : all) {
            row.add("actions", new JsonArray()); row.addProperty("reasonText", "该任务当前不处于待核验状态；使用任务的普通继续或取消入口。");
        }
        all.sort(Comparator.comparing(row -> row.get("operationId").getAsString()));
        if (offset < 0 || offset > all.size()) throw new IllegalArgumentException("RECOVERY_OFFSET");
        int end = Math.min(offset + 8, all.size()); JsonObject result = new JsonObject();
        result.addProperty("taskId", taskId.toString()); result.add("operations", AiJson.GSON.toJsonTree(all.subList(offset, end)));
        result.addProperty("nextOffset", end); result.addProperty("total", all.size());
        JsonArray sources = new JsonArray();
        if (mixed != null) for (var grant : mixed.sources(workspace, actor)) {
            if (!grant.source().insert()) continue;
            JsonObject source = new JsonObject(); source.addProperty("id", grant.id().toString());
            source.addProperty("label", grant.source().description()); source.addProperty("revision", grant.revision());
            source.addProperty("revoked", grant.revoked()); sources.add(source);
        }
        result.add("targetSources", sources);
        result.addProperty("note", "仅按原生保存凭证复核；未知世界操作及回调继续待核验。空列表不表示任务已安全解除恢复。");
        if (result.toString().length() > 22000) throw new IllegalStateException("RECOVERY_VIEW_TOO_LARGE");
        return result;
    }
    public JsonObject preview(Workspace workspace, Actor actor, UUID session, UUID taskId, Action action,
                              UUID operationId, UUID targetSourceId) throws Exception {
        requireOpen(); requireTask(workspace, actor, taskId, true);
        if (action != Action.MIXED_RETURN && action != Action.MIXED_RESUME && targetSourceId != null) throw new IllegalArgumentException("RECOVERY_TARGET_NOT_ALLOWED");
        JsonObject args = new JsonObject(); String name; String text;
        switch (action) {
            case MATERIAL_RECONCILE -> {
                var op = material(workspace, actor, taskId, operationId);
                var review = materials.recoveryReview(actor, operationId);
                args.addProperty("operationId", operationId.toString()); args.addProperty("revision", review.revision());
                args.addProperty("reviewHash", review.reviewHash()); name = "processing_reconcile";
                text = processing == null ? "按真实保存凭证恢复账本阶段，不重放任何原生操作。\n" + AiJson.GSON.toJson(review)
                        : processing.recoveryReviewText(actor, operationId, review.revision(), review.reviewHash());
            }
            case MATERIAL_RETURN -> {
                var op = material(workspace, actor, taskId, operationId);
                if (!materialReturnable(op)) throw new IllegalStateException("RECOVERY_RETURN_PHASE_UNSUPPORTED");
                args.addProperty("operationId", operationId.toString()); name = "processing_return";
                text = "取消剩余取料，只将当前真实托管归还本人背包。不能返还已消耗原料，不运行配方或回调。\n"
                        + "背包满时保留未交付托管；交付后须等待原生保存凭证才算完成。\n" + publicMaterial(op);
            }
            case MIXED_RECONCILE -> {
                joint(workspace, actor, taskId, operationId); var review = mixed.recoveryReview(workspace, actor, operationId);
                args.addProperty("operationId", operationId.toString()); args.addProperty("revision", review.revision());
                args.addProperty("reviewHash", review.reviewHash()); name = "fluid_processing_reconcile";
                text = mixed.recoveryReview(workspace, actor, operationId, review.revision(), review.reviewHash());
            }
            case MIXED_RETURN -> {
                joint(workspace, actor, taskId, operationId);
                if (targetSourceId != null && mixed.sources(workspace, actor).stream().noneMatch(g -> g.id().equals(targetSourceId)
                        && !g.revoked() && g.source().insert())) throw new SecurityException("FLUID_SOURCE_NOT_AUTHORIZED");
                var plan = mixed.returnPreview(new AgentTool.Context(workspace, actor, new TaskControl(120000), taskId), operationId, targetSourceId);
                args.addProperty("reviewId", plan.id().toString()); args.addProperty("reviewHash", plan.hash());
                name = "fluid_processing_return"; text = mixed.returnReview(plan);
            }
            case MIXED_RESUME -> {
                var operation = joint(workspace, actor, taskId, operationId);
                if (!operation.definition().taskId().equals(taskId)) throw new IllegalStateException("FLUID_RESUME_ORIGINAL_TASK_REQUIRED");
                var plan = mixed.resumePreview(new AgentTool.Context(workspace, actor, new TaskControl(120000), taskId), operationId, targetSourceId);
                args.addProperty("reviewId", plan.id().toString()); args.addProperty("reviewHash", plan.hash());
                name = "fluid_processing_resume"; text = mixed.resumeReview(plan);
            }
            case TRAVEL_RECONCILE, MAID_RECONCILE, COMPASS_RECONCILE, MAID_TRANSPORT_RECONCILE, BUILD_RECONCILE, TREE_RECONCILE -> {
                var module = module(action); moduleTask(module.evidence(workspace, actor, operationId), taskId);
                var preview = module.preview(new AgentTool.Context(workspace, actor, new TaskControl(120000), taskId), operationId);
                args = preview.arguments(); name = preview.toolName(); text = preview.text();
            }
            default -> throw new IllegalArgumentException("RECOVERY_ACTION");
        }
        AgentTool tool = requireTool(name); checkPolicy(workspace, actor, tool, args);
        if (text.length() > 18000) throw new IllegalStateException("RECOVERY_PREVIEW_TOO_LARGE");
        var preview = previews.create(scope(workspace, actor, session), taskId, operationId, action.name(),
                resources(workspace, actor, taskId, action, operationId, args), name, args, text);
        JsonObject result = new JsonObject(); result.addProperty("token", preview.token().toString());
        result.addProperty("digest", preview.digest()); result.addProperty("text", preview.text());
        result.addProperty("title", title(action)); result.addProperty("expiresAt", preview.expiresAt());
        result.addProperty("taskId", taskId.toString()); result.addProperty("operationId", operationId.toString());
        result.addProperty("action", action.name()); return result;
    }
    /** No native work or waits here: acceptance only enqueues the existing, exactly approved action. */
    public synchronized JsonObject confirm(Workspace workspace, Actor actor, UUID session, UUID token, String digest) throws Exception {
        requireOpen(); var scope = scope(workspace, actor, session); var pending = previews.pending(scope, token, digest);
        requireTask(workspace, actor, pending.taskId(), true); Action action = Action.valueOf(pending.action());
        String key = workspace.owner() + ":" + pending.operationId();
        if (jobs.size() >= 32 || !busyOperations.add(key)) throw new IllegalStateException("RECOVERY_ACTION_BUSY");
        try {
            AgentTool tool = requireTool(pending.toolName()); checkPolicy(workspace, currentActor.apply(actor.owner()), tool, pending.arguments());
            var approved = previews.consume(scope, token, digest, resources(workspace, actor, pending.taskId(), action, pending.operationId(), pending.arguments()));
            var job = new Job(workspace, approved, tool, key); jobs.put(job.id, job);
            try { lifecycle.execute(() -> start(job)); }
            catch (RejectedExecutionException rejected) { jobs.remove(job.id); throw rejected; }
            JsonObject result = new JsonObject(); result.addProperty("queued", true); result.addProperty("actionId", job.id.toString());
            result.addProperty("taskId", approved.taskId().toString()); result.addProperty("operationId", approved.operationId().toString()); return result;
        } catch (Exception failed) { busyOperations.remove(key); throw failed; }
    }
    private void start(Job job) {
        if (job.finished.get()) return;
        try { startReceipt(job); validate(job, true); dispatch(job, null); }
        catch (Exception failure) { finish(job, null, failure); }
    }
    private void startReceipt(Job job) throws IOException {
        synchronized (job.receiptLock) {
            if (job.startRecorded) return;
            append(job, "workbench_recovery_start", "QUEUED", null, ""); job.startRecorded = true;
        }
    }
    private void dispatch(Job job, AgentTool.Continuation continuation) {
        if (job.finished.get()) return;
        try {
            validate(job, continuation == null);
            TaskControl control;
            synchronized (job) {
                if (job.stop != null) throw new TaskControl.Stopped(job.stop);
                control = new TaskControl(job.tool.descriptor().maxExecutionMillis()); job.control = control;
                job.phase = Phase.IN_FLIGHT;
            }
            AgentTool guarded = new AgentTool() {
                @Override public Descriptor descriptor() { return job.tool.descriptor(); }
                @Override public Result execute(Context context, JsonObject arguments) throws Exception {
                    validate(job, continuation == null); context.control().check();
                    return continuation == null ? job.tool.execute(context, arguments) : continuation.resume(context);
                }
            };
            var future = bridge.execute(guarded, new AgentTool.Context(job.workspace, currentActor.apply(job.preview.scope().owner()),
                    control, job.preview.taskId()), job.preview.arguments(), ToolPolicyEngine.digest(job.workspace.owner(), job.call), job.call);
            // Completion/readiness callbacks only schedule lifecycle work; they never touch journals or Minecraft.
            future.whenComplete((result, failure) -> schedule(job, () -> received(job, result, failure)));
        } catch (Exception failure) { job.phase = Phase.PROCESSING; finish(job, null, failure); }
    }
    private void received(Job job, AgentTool.Result result, Throwable failure) {
        job.phase = Phase.PROCESSING;
        Throwable actual = unwrap(failure);
        if (actual instanceof AgentTool.Deferred deferred) {
            try {
                append(job, "workbench_recovery_wait", deferred.reason(), null, "");
                job.phase = Phase.WAITING;
                deferred.readiness().whenComplete((ignored, waitFailure) -> schedule(job, () -> {
                    if (job.finished.get()) lateEvidence(job, waitFailure);
                    else if (waitFailure != null) finish(job, null, unwrap(waitFailure)); else dispatch(job, deferred.continuation());
                }));
                if (job.stop != null) stoppedIfWaiting(job);
            } catch (Exception recording) { finish(job, null, recording); }
            return;
        }
        if (actual != null) { finish(job, result, actual); return; }
        job.lastResult = result;
        try {
            Action action = Action.valueOf(job.preview.action());
            CompletionStage<Void> ready = null;
            if (action == Action.MATERIAL_RETURN) {
                var op = materials.get(trusted(job), job.preview.operationId());
                if (!op.saved()) ready = awaitSaved.apply(op.operationId());
            } else if (action == Action.MIXED_RETURN || action == Action.MIXED_RESUME) ready = mixed.awaitKnown(job.workspace, trusted(job), job.preview.operationId());
            if (ready != null) {
                append(job, "workbench_recovery_wait", "NATIVE_SAVE_PENDING", result, "");
                job.phase = Phase.WAITING;
                ready.whenComplete((ignored, saveFailure) -> schedule(job, () -> settled(job, saveFailure)));
                if (job.stop != null) stoppedIfWaiting(job);
            } else settled(job, null);
        } catch (Exception inspection) { finish(job, result, inspection); }
    }
    /** Pure evidence/ledger finalization also runs for stopped jobs so late accepted effects remain recorded. */
    private void settled(Job job, Throwable failure) {
        if (job.finished.get()) { lateEvidence(job, failure); return; }
        job.phase = Phase.PROCESSING;
        try {
            if (failure != null) { finish(job, job.lastResult, unwrap(failure)); return; }
            if (Action.valueOf(job.preview.action()) == Action.MIXED_RETURN)
                mixed.continueReturn(job.workspace, trusted(job), job.preview.operationId());
            finish(job, job.lastResult, null);
        } catch (Exception inspection) { finish(job, job.lastResult, inspection); }
    }
    private void lateEvidence(Job job, Throwable failure) {
        try {
            if (job.workspace.conversation().privacyEpoch() != job.preview.scope().privacyEpoch()) return;
            if (failure == null && Action.valueOf(job.preview.action()) == Action.MIXED_RETURN)
                mixed.continueReturn(job.workspace, trusted(job), job.preview.operationId());
            append(job, "workbench_recovery_evidence", failure == null ? "LATE_NATIVE_EVIDENCE_RECORDED" : safe(unwrap(failure)),
                    job.lastResult, failure == null ? "" : safe(unwrap(failure)));
            settlement.accept(job.workspace, job.preview.taskId());
        } catch (Exception stillBlocked) { /* The underlying native journal remains the durable authority. */ }
    }
    private void stoppedIfWaiting(Job job) {
        if (job.stop != null && (job.phase == Phase.WAITING || job.phase == Phase.QUEUED))
            finish(job, job.lastResult, new TaskControl.Stopped(job.stop));
    }
    private void validate(Job job, boolean originalResources) throws Exception {
        if (closed) throw new TaskControl.Stopped("AI_STOPPING");
        if (job.stop != null) throw new TaskControl.Stopped(job.stop);
        Actor actor = currentActor.apply(job.workspace.owner()); actor.requireAccess(job.workspace.owner());
        if (!actor.online()) throw new TaskControl.Stopped("PLAYER_OFFLINE");
        if (!scope(job.workspace, actor, job.preview.scope().session()).equals(job.preview.scope())) throw new SecurityException("RECOVERY_IDENTITY_CHANGED");
        requireTask(job.workspace, actor, job.preview.taskId(), true);
        if (originalResources && clock.getAsLong() >= job.preview.expiresAt()) throw new IllegalStateException("RECOVERY_PREVIEW_EXPIRED");
        if (tools.get(job.preview.toolName()) != job.tool) throw new IllegalStateException("RECOVERY_TOOL_CHANGED");
        checkPolicy(job.workspace, actor, job.tool, job.preview.arguments());
        if (originalResources && !job.preview.resourceDigest().equals(resources(job.workspace, actor, job.preview.taskId(),
                Action.valueOf(job.preview.action()), job.preview.operationId(), job.preview.arguments()))) throw new IllegalStateException("RECOVERY_REVIEW_CHANGED");
    }
    private void finish(Job job, AgentTool.Result result, Throwable failure) {
        if (!job.finished.compareAndSet(false, true)) return;
        if (job.workspace.conversation().privacyEpoch() != job.preview.scope().privacyEpoch()) {
            jobs.remove(job.id); busyOperations.remove(job.key); return;
        }
        try {
            startReceipt(job);
            boolean returned = false; Action action = Action.valueOf(job.preview.action()); Actor actor = trusted(job);
            if (action == Action.MATERIAL_RETURN) { var op = materials.get(actor, job.preview.operationId()); returned = op.settled() && op.saved(); }
            if (action == Action.MIXED_RETURN) {
                var op = mixed.recoveryOperations(job.workspace, actor).stream().filter(o -> o.definition().id().equals(job.preview.operationId())).findFirst().orElseThrow();
                returned = op.state() == MixedResourceJournal.State.RETURNED && op.fluidDeliveriesSaved()
                        && mixed.operationSettled(job.workspace, actor, job.preview.operationId());
            }
            if (action == Action.MIXED_RESUME) returned = mixed.operationSettled(job.workspace, actor, job.preview.operationId());
            if (moduleAction(action)) returned = module(action).settled(job.workspace, actor, job.preview.operationId());
            String status = failure != null ? safe(failure) : job.stop != null ? job.stop : returned
                    ? moduleAction(action) ? module(action).settledStatus() : action == Action.MIXED_RESUME ? "RESUMED_AND_SAVED" : "RETURNED_AND_SAVED"
                    : (action == Action.MATERIAL_RECONCILE || action == Action.MIXED_RECONCILE) && result != null && result.success()
                    ? "RECONCILED_CUSTODY_ONLY" : "CUSTODY_OR_SAVE_PENDING";
            append(job, "workbench_recovery_result", status, result, failure == null ? "" : safe(failure));
        } catch (Exception recording) {
            // A failed durable write must keep the operation busy and the task fenced, never fabricate success.
            job.recordingFailure = recording; job.finished.set(false); return;
        }
        jobs.remove(job.id); busyOperations.remove(job.key);
        // Only the runtime, with every world journal and original tool receipt, may clear its task barrier.
        try { settlement.accept(job.workspace, job.preview.taskId()); }
        catch (RuntimeException stillBlocked) { /* The durable receipt remains available for the next audit. */ }
    }
    private void append(Job job, String kind, String status, AgentTool.Result result, String error) throws IOException {
        JsonObject data = new JsonObject(); data.addProperty("actionId", job.id.toString());
        data.addProperty("taskId", job.preview.taskId().toString()); data.addProperty("operationId", job.preview.operationId().toString());
        data.addProperty("action", job.preview.action()); data.addProperty("status", status); data.addProperty("errorCode", error);
        data.addProperty("private", true); data.addProperty("text", title(Action.valueOf(job.preview.action())) + "：" + status);
        if (result != null) data.add("result", AiJson.GSON.toJsonTree(result));
        Action action = Action.valueOf(job.preview.action());
        if (moduleAction(action)) try {
            var evidence = module(action).evidence(job.workspace, trusted(job), job.preview.operationId());
            moduleTask(evidence, job.preview.taskId()); data.add("nativeOperationEvidence", evidence);
        } catch (Exception failed) { throw new IOException("RECOVERY_MODULE_EVIDENCE_UNAVAILABLE", failed); }
        var item = materials.find(trusted(job), job.preview.operationId());
        if (item.isPresent()) data.add("materialEvidence", AiJson.GSON.toJsonTree(item.get()));
        if (mixed != null) mixed.recoveryOperations(job.workspace, trusted(job)).stream()
                .filter(op -> op.definition().id().equals(job.preview.operationId())).findFirst()
                .ifPresent(op -> data.add("mixedMaterialEvidence", AiJson.GSON.toJsonTree(op)));
        synchronized (job.workspace.conversation()) {
            // Explicit private erasure must not be undone by an already accepted late callback.
            if (job.workspace.conversation().privacyEpoch() != job.preview.scope().privacyEpoch()) return;
            job.workspace.conversation().append(List.of(new Draft(job.preview.taskId(), kind, null, data, job.id.toString())));
        }
    }
    private void schedule(Job job, Runnable action) {
        try { lifecycle.execute(action); }
        catch (RejectedExecutionException stopped) { job.stop("AI_STOPPING"); }
    }
    public void disconnect(String owner, UUID session) {
        previews.invalidate(owner, session); jobs.values().stream().filter(job -> job.workspace.owner().equals(owner)
                && job.preview.scope().session().equals(session)).forEach(job -> {
                    job.stop("PLAYER_OFFLINE"); schedule(job, () -> stoppedIfWaiting(job));
                });
    }
    public void cancelTask(String owner, UUID taskId, String reason) {
        jobs.values().stream().filter(job -> job.workspace.owner().equals(owner) && job.preview.taskId().equals(taskId))
                .forEach(job -> { job.stop(reason); schedule(job, () -> stoppedIfWaiting(job)); });
    }
    @Override public void close() { closed = true; previews.clear(); jobs.values().forEach(job -> job.stop("AI_STOPPING")); }
    /** IO shutdown phase, after native dispatch and save journals have drained. close() itself never waits. */
    public synchronized void finalizePendingClose() {
        if (finalizing) return; close(); finalizing = true;
        lifecycle.execute(() -> { jobs.values().forEach(job -> finish(job, job.lastResult, new TaskControl.Stopped("AI_STOPPING"))); lifecycle.shutdown(); });
    }
    public void awaitStopped() throws InterruptedException {
        if (!awaitStopped(30000)) throw new IllegalStateException("RECOVERY_SHUTDOWN_NOT_DRAINED");
    }
    public boolean awaitStopped(long milliseconds) throws InterruptedException {
        if (!finalizing) throw new IllegalStateException("RECOVERY_CLOSE_NOT_FINALIZED");
        if (!lifecycle.awaitTermination(Math.max(0, milliseconds), TimeUnit.MILLISECONDS)) return false;
        if (!jobs.isEmpty()) throw new IllegalStateException("RECOVERY_RECEIPT_NOT_PERSISTED");
        return true;
    }
    private Actor trusted(Job job) { return new Actor(job.workspace.owner(), true, false, 0); }
    private void requireOpen() { if (closed) throw new IllegalStateException("AI_STOPPING"); }
    private AgentTask requireTask(Workspace workspace, Actor actor, UUID id, boolean active) {
        actor.requireAccess(workspace.owner()); var task = workspace.conversation().task(Objects.requireNonNull(id));
        if (task == null || !task.owner().equals(actor.owner())) throw new SecurityException("RECOVERY_TASK_NOT_FOUND");
        if (active && (task.state() != AgentTask.State.RECOVERY_REQUIRED || !actor.online()))
            throw new IllegalStateException(actor.online() ? "RECOVERY_TASK_CHANGED" : "PLAYER_OFFLINE");
        return task;
    }
    private RecoveryPreviewStore.Scope scope(Workspace workspace, Actor actor, UUID session) {
        actor.requireAccess(workspace.owner()); return new RecoveryPreviewStore.Scope(actor.owner(), workspace.metadata().serverInstanceId(),
                workspace.primaryConversationId(), workspace.conversation().privacyEpoch(), session);
    }
    private List<MixedResourceJournal.Operation> mixedOperations(Workspace workspace, Actor actor) throws IOException {
        return mixed == null ? List.of() : mixed.recoveryOperations(workspace, actor);
    }
    private MaterialLedger.Operation material(Workspace workspace, Actor actor, UUID task, UUID id) throws Exception {
        var op = materials.get(actor, id);
        if (worldOwned(workspace, op) || mixedOperations(workspace, actor).stream().anyMatch(j -> j.definition().id().equals(id)))
            throw new IllegalStateException("RECOVERY_REQUIRES_OWNING_JOURNAL");
        if (!linked(workspace, actor, task, id, op.taskId())) throw new SecurityException("RECOVERY_TASK_OPERATION_MISMATCH");
        return op;
    }
    private MixedResourceJournal.Operation joint(Workspace workspace, Actor actor, UUID task, UUID id) throws Exception {
        var op = mixedOperations(workspace, actor).stream().filter(j -> j.definition().id().equals(id)).findFirst()
                .orElseThrow(() -> new SecurityException("MIXED_OPERATION_NOT_FOUND"));
        if (!linked(workspace, actor, task, id, op.definition().taskId())) throw new SecurityException("RECOVERY_TASK_OPERATION_MISMATCH");
        return op;
    }
    private boolean worldOwned(Workspace workspace, MaterialLedger.Operation op) {
        return worldOperations.test(workspace, op.operationId()) || !op.worldDebits().isEmpty()
                || op.externalDebits().stream().anyMatch(debit -> !debit.known() || debit.kind().equals("native_block_harvest"))
                || op.samplingEvidence().startsWith("native_block_harvest:") || op.samplingEvidence().startsWith("uncertain_native_removal:");
    }
    private static boolean materialReturnable(MaterialLedger.Operation op) {
        return !op.uncertain() && op.intent() == null && op.saved()
                && Set.of(MaterialLedger.State.RESERVED, MaterialLedger.State.ESCROWED, MaterialLedger.State.PRODUCED, MaterialLedger.State.CALLBACK_DONE).contains(op.state());
    }
    private String resources(Workspace workspace, Actor actor, UUID task, Action action, UUID id, JsonObject arguments) throws Exception {
        JsonObject body = new JsonObject(); body.addProperty("action", action.name()); body.addProperty("taskId", task.toString());
        if (moduleAction(action)) {
            var module = module(action); var evidence = module.evidence(workspace, actor, id); moduleTask(evidence, task);
            module.validatePreview(workspace, actor, task, id, arguments); body.add("nativeOperation", evidence);
        } else if (action == Action.MATERIAL_RECONCILE || action == Action.MATERIAL_RETURN) {
            var op = material(workspace, actor, task, id); body.add("operation", AiJson.GSON.toJsonTree(op));
            if (action == Action.MATERIAL_RECONCILE) body.add("review", AiJson.GSON.toJsonTree(materials.recoveryReview(actor, id)));
            else if (!materialReturnable(op)) throw new IllegalStateException("RECOVERY_RETURN_PHASE_UNSUPPORTED");
        } else {
            body.add("operation", AiJson.GSON.toJsonTree(joint(workspace, actor, task, id)));
            if (action == Action.MIXED_RECONCILE) body.add("review", AiJson.GSON.toJsonTree(mixed.recoveryReview(workspace, actor, id)));
            else if (action == Action.MIXED_RETURN) {
                var plan = mixed.loadReturn(workspace, actor, UUID.fromString(arguments.get("reviewId").getAsString()), arguments.get("reviewHash").getAsString());
                if (!plan.operationId().equals(id)) throw new SecurityException("RECOVERY_RETURN_OPERATION_CHANGED");
                mixed.validateReturn(workspace, actor, plan);
                body.add("returnPlan", AiJson.GSON.toJsonTree(plan));
            } else {
                var plan = mixed.loadResume(workspace, actor, UUID.fromString(arguments.get("reviewId").getAsString()), arguments.get("reviewHash").getAsString());
                if (!plan.operationId().equals(id)) throw new SecurityException("RECOVERY_RESUME_OPERATION_CHANGED");
                mixed.validateResumeVersion(workspace, actor, plan); body.add("resumePlan", AiJson.GSON.toJsonTree(plan));
            }
            body.add("linkedItem", AiJson.GSON.toJsonTree(materials.find(actor, id).orElse(null)));
            body.add("sourceGrants", AiJson.GSON.toJsonTree(mixed.sources(workspace, actor)));
        }
        return AiJson.digest(AiJson.canonical(body));
    }
    private boolean linked(Workspace workspace, Actor actor, UUID taskId, UUID id, UUID nativeTaskId) {
        if (taskId.equals(nativeTaskId)) return true;
        var task = workspace.conversation().task(taskId);
        if (task != null && task.pending().stream().anyMatch(call -> references(workspace, actor, call, id))) return true;
        for (var event : workspace.conversation().getSnapshot(0).events()) {
            if (!taskId.equals(event.taskId())) continue;
            if (event.message() != null && "assistant".equals(event.message().role())
                    && event.message().calls().stream().anyMatch(call -> references(workspace, actor, call, id))) return true;
            if ("tool_result".equals(event.kind()) && event.data() != null && event.data().has("fullResult")) try {
                JsonObject result = JsonParser.parseString(event.data().get("fullResult").getAsString()).getAsJsonObject();
                JsonObject observations = result.getAsJsonObject("observations");
                if (observations != null) for (String field : List.of("materialEvidence", "mixedMaterialEvidence")) {
                    JsonObject evidence = observations.getAsJsonObject(field);
                    if (evidence != null && evidence.has("operationId") && id.toString().equals(evidence.get("operationId").getAsString())) return true;
                }
            } catch (RuntimeException malformed) { /* Unparseable data cannot grant authority. */ }
        }
        return false;
    }
    private boolean references(Workspace workspace, Actor actor, ModelMessage.ToolCall call, UUID id) {
        if (!Set.of("processing_execute", "processing_return", "processing_reconcile", "fluid_processing_execute",
                "fluid_processing_return", "fluid_processing_reconcile", "fluid_processing_resume").contains(call.name())) return false;
        try {
            JsonObject args = AiJson.object(call.arguments(), 32768, 16);
            for (String field : List.of("operationId", "planId")) if (args.has(field) && id.toString().equals(args.get(field).getAsString())) return true;
            if (mixed == null || !args.has("reviewId") || !args.has("reviewHash")) return false;
            UUID review = UUID.fromString(args.get("reviewId").getAsString()); String hash = args.get("reviewHash").getAsString();
            return call.name().equals("fluid_processing_return") ? mixed.loadReturn(workspace, actor, review, hash).operationId().equals(id)
                    : call.name().equals("fluid_processing_resume") && mixed.resumeOperationReference(workspace, actor, review, hash).equals(id);
        } catch (Exception invalid) { return false; }
    }
    private AgentTool requireTool(String name) {
        var tool = tools.get(name); if (tool == null || !tool.descriptor().name().equals(name)) throw new IllegalStateException("RECOVERY_TOOL_UNAVAILABLE");
        return tool;
    }
    private void checkPolicy(Workspace workspace, Actor actor, AgentTool tool, JsonObject args) {
        var call = new ModelMessage.ToolCall("recovery-policy", tool.descriptor().name(), args.toString());
        var verdict = new ToolPolicyEngine().check(actor, workspace.owner(), tool.descriptor(), enabled.test(call.name()), call,
                ToolPolicyEngine.digest(workspace.owner(), call));
        if (verdict.decision() != ToolPolicyEngine.Decision.ALLOW) throw new IllegalStateException(verdict.reason());
    }
    private static JsonObject row(UUID id, String kind, UUID task, String state, String reason) {
        JsonObject row = new JsonObject(); row.addProperty("operationId", id.toString()); row.addProperty("taskId", task.toString());
        row.addProperty("kind", kind); row.addProperty("state", state); row.addProperty("reason", reason); row.add("actions", new JsonArray()); return row;
    }
    private static void addAction(JsonObject row, Action action) { row.getAsJsonArray("actions").add(action.name()); }
    private static String publicMaterial(MaterialLedger.Operation op) {
        JsonObject body = new JsonObject(); body.addProperty("operationId", op.operationId().toString()); body.addProperty("state", op.state().name());
        body.add("escrow", AiJson.GSON.toJsonTree(op.escrow().stream().map(RecoveryWorkbenchService::publicStack).toList()));
        body.add("undelivered", AiJson.GSON.toJsonTree(op.remainingProducts().stream().map(RecoveryWorkbenchService::publicStack).toList()));
        return body.toString();
    }
    private static JsonObject publicStack(MaterialStack stack) {
        JsonObject data = new JsonObject(); data.addProperty("count", stack.count());
        if (stack.empty()) { data.addProperty("empty", true); return data; }
        data.add("id", AiJson.object(stack.itemJson(), 1_048_576, 32).get("id"));
        data.addProperty("componentsDigest", stack.fingerprint()); return data;
    }
    private static String title(Action action) { return switch (action) {
        case MATERIAL_RECONCILE -> "复核物品账本"; case MIXED_RECONCILE -> "复核联合物料账本";
        case MATERIAL_RETURN -> "归还已知物品托管"; case MIXED_RETURN -> "归还已知联合物料托管";
        case MIXED_RESUME -> "预览托管与缺料并继续加工";
        case TRAVEL_RECONCILE -> "复核实际旅行结果"; case MAID_RECONCILE -> "核对女仆当前状态";
        case COMPASS_RECONCILE -> "核对实际罗盘搜索结果";
        case MAID_TRANSPORT_RECONCILE -> "核对女仆搬运与真实托管";
        case BUILD_RECONCILE -> "核对已保存的施工历史与真实余料";
        case TREE_RECONCILE -> "核对真实树木采集与保存结果";
    }; }
    private static Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
    private static String safe(Throwable failure) {
        String message = failure.getMessage(); return message != null && message.matches("[A-Z0-9_]{1,96}") ? message : "RECOVERY_REQUIRES_REVIEW";
    }
    private enum Phase { QUEUED, IN_FLIGHT, PROCESSING, WAITING }
    private static final class Job {
        final UUID id = UUID.randomUUID(); final Workspace workspace; final RecoveryPreviewStore.Preview preview;
        final AgentTool tool; final String key; final ModelMessage.ToolCall call; final AtomicBoolean finished = new AtomicBoolean();
        final Object receiptLock = new Object();
        volatile TaskControl control; volatile String stop; volatile AgentTool.Result lastResult; volatile Exception recordingFailure;
        volatile Phase phase = Phase.QUEUED;
        boolean startRecorded;
        Job(Workspace workspace, RecoveryPreviewStore.Preview preview, AgentTool tool, String key) {
            this.workspace = workspace; this.preview = preview; this.tool = tool; this.key = key;
            call = new ModelMessage.ToolCall(id.toString(), preview.toolName(), preview.arguments().toString());
        }
        synchronized void stop(String reason) { if (stop == null) stop = reason; if (control != null) control.stop(stop); }
    }
}
