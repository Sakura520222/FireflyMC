package firefly520.fireflymc.ai.blueprint.recovery;

import com.google.gson.*;
import firefly520.fireflymc.ai.blueprint.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.recovery.RecoveryModule;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Predicate;

/** Own-workbench historical settlement and separately approved return of actual unspent custody. */
public final class WorldRecoveryService implements RecoveryModule {
    public enum Phase { SAVED_HISTORY, UNUSED_INPUTS, RETURN_CUSTODY, RETURN_RECEIPTS }
    public record Review(int schema, UUID id, UUID serverInstanceId, UUID conversationId, long privacyEpoch,
                         String owner, UUID taskId, UUID operationId, Phase phase, long materialRevision,
                         String materialDigest, String worldDigest, SavedPlacementProofs.Review history,
                         MaterialLedger.RecoveryReview inputs, String hash) {
        boolean intact() { return hash != null && hash.equals(AiJson.digest(AiJson.canonical(unsigned(this)))); }
    }
    private record Settlement(UUID taskId, UUID operationId, String planHash, String worldDigest, String proof) {}
    private record Basis(Phase phase, SavedPlacementProofs.Review history, MaterialLedger.RecoveryReview inputs) {}
    private record PlanRef(UUID task, String hash) {}
    private record Index(Map<UUID, Review> reviews, Set<UUID> accepted, Map<UUID, Settlement> settlements, Map<UUID, PlanRef> plans, long seen) {}
    private static final class Scope {
        final Workspace workspace; final long epoch; final BuildJournal world;
        volatile Index index = new Index(Map.of(), Set.of(), Map.of(), Map.of(), 0);
        Scope(Workspace workspace) { this.workspace = workspace; epoch = workspace.conversation().privacyEpoch(); world = new BuildJournal(workspace, UUID.randomUUID()); }
    }
    private final MaterialLedger materials;
    @FunctionalInterface interface OwnerCheck { void require(AgentTool.Context context, UUID operation) throws Exception; }
    @FunctionalInterface interface Delivery { MaterialLedger.Operation deliver(AgentTool.Context context, MaterialLedger.Operation operation) throws Exception; }
    @FunctionalInterface interface PlanRetirement { void retire(AgentTool.Context context, UUID operation) throws Exception; }
    private final OwnerCheck ownerCheck;
    private final Delivery delivery;
    private final Function<UUID, CompletionStage<Void>> awaitSaved;
    private final PlanRetirement retirement;
    private final Predicate<UUID> idle;
    private final Map<String, Scope> scopes = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

    public WorldRecoveryService(MaterialLedger materials, MaterialInventoryService inventory,
                                VirtualProcessingService dispatcher, Predicate<UUID> idle, java.util.function.BiConsumer<Actor, UUID> retire) {
        this(materials, (ctx, operation) -> {
            dispatcher.inspectOwned(ctx, () -> {
                inventory.requirePlayer(ctx.actor());
                if (!idle.test(operation)) throw new IllegalStateException("BUILD_NATIVE_WORK_STILL_ACTIVE");
                return true;
            });
        }, dispatcher::deliver, inventory.nativeSaves()::awaitJournalled, idle, (ctx, operation) -> {
            dispatcher.inspectOwned(ctx, () -> { inventory.requirePlayer(ctx.actor()); retire.accept(ctx.actor(), operation); return true; });
        });
    }
    WorldRecoveryService(MaterialLedger materials, OwnerCheck ownerCheck, Delivery delivery,
                         Function<UUID, CompletionStage<Void>> awaitSaved, Predicate<UUID> idle, PlanRetirement retirement) {
        this.materials = Objects.requireNonNull(materials); this.ownerCheck = Objects.requireNonNull(ownerCheck);
        this.delivery = Objects.requireNonNull(delivery); this.awaitSaved = Objects.requireNonNull(awaitSaved); this.idle = Objects.requireNonNull(idle);
        this.retirement = Objects.requireNonNull(retirement);
    }
    public void prepare(Workspace workspace) {
        var scope = scopes.compute(workspace.owner(), (owner, prior) -> prior != null && prior.workspace == workspace
                && prior.epoch == workspace.conversation().privacyEpoch() ? prior : new Scope(workspace));
        refresh(scope, new Actor(workspace.owner(), true, false, 0));
    }
    private Scope scope(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner()); var value = scopes.get(actor.owner());
        if (value == null || value.workspace != workspace || value.epoch != workspace.conversation().privacyEpoch())
            throw new IllegalStateException("BUILD_RECOVERY_SCOPE_NOT_PREPARED");
        return value;
    }
    private void refresh(Scope scope, Actor actor) {
        actor.requireAccess(scope.workspace.owner());
        synchronized (scope.workspace.conversation()) {
            if (scope.epoch != scope.workspace.conversation().privacyEpoch()) throw new IllegalStateException("BUILD_PRIVACY_CHANGED");
            var before = scope.index; var snapshot = scope.workspace.conversation().getSnapshot(Math.max(before.seen(), scope.epoch));
            var reviews = new LinkedHashMap<>(before.reviews()); var accepted = new HashSet<>(before.accepted());
            var settlements = new HashMap<>(before.settlements());
            var plans = new HashMap<>(before.plans());
            for (var event : snapshot.events()) {
                if (event.kind().equals("construction_plan")) {
                    var id = UUID.fromString(event.data().get("planId").getAsString()); var hash = event.data().get("hash").getAsString();
                    if (event.taskId() == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalStateException("BUILD_PLAN_INDEX_INVALID");
                    plans.put(id, new PlanRef(event.taskId(), hash));
                } else if (event.kind().equals("build_recovery_review")) {
                    var review = AiJson.GSON.fromJson(event.data(), Review.class); identity(scope, actor, review);
                    reviews.put(review.id(), review);
                } else if (event.kind().equals("build_recovery_accept")) accepted.add(UUID.fromString(event.data().get("reviewId").getAsString()));
                else if (event.kind().equals("build_history_settled")) {
                    var settled = AiJson.GSON.fromJson(event.data(), Settlement.class);
                    if (!Objects.equals(event.taskId(), settled.taskId()) || !settled.proof().matches("[0-9a-f]{64}"))
                        throw new IllegalStateException("BUILD_RECOVERY_JOURNAL_INVALID");
                    settlements.put(settled.operationId(), settled);
                }
            }
            scope.index = new Index(Map.copyOf(reviews), Set.copyOf(accepted), Map.copyOf(settlements), Map.copyOf(plans), snapshot.revision());
        }
    }
    private MaterialLedger.Operation owned(Scope scope, Actor actor, UUID operation) {
        var op = materials.get(actor, operation);
        var plan = scope.index.plans().get(operation);
        boolean indexed = plan != null && plan.task().equals(op.taskId()) && plan.hash().equals(op.planHash());
        if (!indexed) throw new IllegalArgumentException("BUILD_RECOVERY_OPERATION_NOT_FOUND");
        return op;
    }
    private List<BuildJournal.Step> steps(Scope scope, Actor actor, UUID id) {
        return scope.world.list(actor).stream().filter(step -> step.materialOperationId().equals(id)).toList();
    }
    private static String digest(Object value) { return AiJson.digest(AiJson.canonical(value)); }
    private static Review unsigned(Review r) {
        return new Review(r.schema(), r.id(), r.serverInstanceId(), r.conversationId(), r.privacyEpoch(), r.owner(), r.taskId(),
                r.operationId(), r.phase(), r.materialRevision(), r.materialDigest(), r.worldDigest(), r.history(), r.inputs(), "");
    }
    private static void identity(Scope scope, Actor actor, Review r) {
        actor.requireAccess(r.owner());
        if (r.schema() != 1 || !r.intact() || !r.serverInstanceId().equals(scope.workspace.metadata().serverInstanceId())
                || !r.conversationId().equals(scope.workspace.primaryConversationId()) || r.privacyEpoch() != scope.epoch)
            throw new SecurityException("BUILD_RECOVERY_IDENTITY_CHANGED");
    }
    private void requireNativeOwner(AgentTool.Context ctx, UUID operation) throws Exception {
        ctx.actor().requireAccess(ctx.workspace().owner()); ctx.control().check();
        ownerCheck.require(ctx, operation);
    }
    public Review previewReview(AgentTool.Context ctx, UUID operation) throws Exception {
        requireNativeOwner(ctx, operation); var scope = scope(ctx.workspace(), ctx.actor()); refresh(scope, ctx.actor());
        var op = owned(scope, ctx.actor(), operation); if (!op.taskId().equals(ctx.taskId())) throw new SecurityException("BUILD_RECOVERY_TASK_CHANGED");
        var steps = steps(scope, ctx.actor(), operation); var basis = basis(scope, ctx.actor(), op, steps);
        var unsigned = new Review(1, UUID.randomUUID(), ctx.workspace().metadata().serverInstanceId(), ctx.workspace().primaryConversationId(), scope.epoch,
                ctx.actor().owner(), op.taskId(), operation, basis.phase(), op.revision(), digest(op), digest(steps), basis.history(), basis.inputs(), "");
        var review = new Review(unsigned.schema(), unsigned.id(), unsigned.serverInstanceId(), unsigned.conversationId(), unsigned.privacyEpoch(), unsigned.owner(),
                unsigned.taskId(), unsigned.operationId(), unsigned.phase(), unsigned.materialRevision(), unsigned.materialDigest(), unsigned.worldDigest(),
                unsigned.history(), unsigned.inputs(), digest(unsigned));
        ctx.workspace().conversation().append(List.of(new Draft(op.taskId(), "build_recovery_review", null, AiJson.GSON.toJsonTree(review).getAsJsonObject(), review.id().toString())));
        refresh(scope, ctx.actor()); return review;
    }
    private Basis basis(Scope scope, Actor actor, MaterialLedger.Operation op, List<BuildJournal.Step> steps) {
        var marker = scope.index.settlements().get(op.operationId());
        Phase phase; SavedPlacementProofs.Review history = null; MaterialLedger.RecoveryReview inputs = null;
        boolean settledHistory = marker != null && marker.taskId().equals(op.taskId()) && marker.planHash().equals(op.planHash())
                && marker.worldDigest().equals(digest(steps)) && steps.stream().allMatch(BuildJournal.Step::settled);
        if (settledHistory && op.uncertain()) {
            inputs = materials.recoveryReview(actor, op.operationId());
            if (!Set.of(MaterialLedger.State.CALLBACK_DONE, MaterialLedger.State.COMPLETED, MaterialLedger.State.CANCELLED,
                    MaterialLedger.State.ESCROWED, MaterialLedger.State.RESERVED).contains(inputs.restorePhase()))
                throw new IllegalStateException("BUILD_RETURN_PHASE_UNCONFIRMED");
            phase = Phase.RETURN_RECEIPTS;
        } else if (settledHistory
                && !op.uncertain() && op.intent() == null && op.saved()
                && Set.of(MaterialLedger.State.ESCROWED, MaterialLedger.State.CALLBACK_DONE, MaterialLedger.State.RESERVED).contains(op.state())) phase = Phase.RETURN_CUSTODY;
        else if (!op.worldDebits().isEmpty()) { history = SavedPlacementProofs.inspect(actor, op, steps); phase = Phase.SAVED_HISTORY; }
        else {
            if (op.intent() != null || !op.externalDebits().isEmpty() || !op.sampledOutputs().isEmpty() || !op.products().isEmpty()
                    || steps.stream().anyMatch(step -> step.state() != BuildJournal.State.PREPARED && step.state() != BuildJournal.State.REJECTED || step.observation() != null))
                throw new IllegalStateException("BUILD_RECOVERY_NATIVE_OUTCOME_UNKNOWN");
            if (op.uncertain()) inputs = materials.recoveryReview(actor, op.operationId());
            else if (!op.saved() || !Set.of(MaterialLedger.State.RESERVED, MaterialLedger.State.ESCROWED).contains(op.state()))
                throw new IllegalStateException("BUILD_RECOVERY_INPUT_PROOF_REQUIRED");
            phase = Phase.UNUSED_INPUTS;
        }
        return new Basis(phase, history, inputs);
    }
    public Review load(Workspace workspace, Actor actor, UUID id, String hash) {
        var scope = scope(workspace, actor); refresh(scope, actor); var review = scope.index.reviews().get(id);
        if (review == null || !review.hash().equals(hash)) throw new IllegalArgumentException("BUILD_RECOVERY_REVIEW_NOT_FOUND");
        identity(scope, actor, review); return review;
    }
    public void validate(Workspace workspace, Actor actor, Review review) {
        var scope = scope(workspace, actor); identity(scope, actor, review);
        var op = owned(scope, actor, review.operationId());
        if (scope.index.accepted().contains(review.id()) || op.revision() != review.materialRevision()
                || !digest(op).equals(review.materialDigest()) || !digest(steps(scope, actor, op.operationId())).equals(review.worldDigest()))
            throw new IllegalStateException("BUILD_RECOVERY_REVIEW_CHANGED");
    }
    public String reviewText(Workspace workspace, Actor actor, Review review) {
        validate(workspace, actor, review); var op = owned(scope(workspace, actor), actor, review.operationId());
        return (review.phase() == Phase.RETURN_CUSTODY
                ? "只将当前真实剩余托管归还本人背包；满仓时保留未交付部分，原生保存确认后才结算。不会补回已施工消耗的材料。"
                : "只核对原执行时已记录且真正保存的结果，终止从未派发的剩余步骤。不放置、拆除、补发或重放旧操作，不授予跨重启撤销所有权。余料另行预览批准返还。")
                + "\n已记录世界步骤：" + steps(scope(workspace, actor), actor, op.operationId()).size()
                + "；剩余真实托管：" + op.escrow().stream().mapToLong(MaterialStack::count).sum()
                + "；待交付：" + op.remainingProducts().stream().mapToLong(MaterialStack::count).sum() + "。";
    }
    public AgentTool.Result execute(AgentTool.Context ctx, Review review) throws Exception {
        requireNativeOwner(ctx, review.operationId()); var scope = scope(ctx.workspace(), ctx.actor()); refresh(scope, ctx.actor());
        if (!Objects.equals(ctx.taskId(), review.taskId()) || !busy.add(review.operationId())) throw new IllegalStateException("BUILD_RECOVERY_ACTION_BUSY_OR_TASK_CHANGED");
        try {
            validate(ctx.workspace(), ctx.actor(), review); var accepted = new JsonObject(); accepted.addProperty("reviewId", review.id().toString());
            accepted.addProperty("reviewHash", review.hash()); accepted.addProperty("operationId", review.operationId().toString());
            ctx.workspace().conversation().append(List.of(new Draft(review.taskId(), "build_recovery_accept", null, accepted, review.id().toString())));
            refresh(scope, ctx.actor()); retirement.retire(ctx, review.operationId());
            var op = owned(scope, ctx.actor(), review.operationId());
            if (review.phase() == Phase.SAVED_HISTORY) {
                scope.world.reconcileSaved(ctx.actor(), review.history(), op);
                var current = SavedPlacementProofs.inspect(ctx.actor(), op, steps(scope, ctx.actor(), op.operationId()));
                op = materials.reconcileSavedWorld(ctx.actor(), op.operationId(), current, steps(scope, ctx.actor(), op.operationId()));
                if (op.state() == MaterialLedger.State.ESCROWED && op.escrow().stream().allMatch(MaterialStack::empty)) op = materials.worldCompleted(ctx.actor(), op.operationId());
            } else if (review.phase() == Phase.UNUSED_INPUTS || review.phase() == Phase.RETURN_RECEIPTS) {
                if (review.inputs() != null) op = materials.reconcile(ctx.actor(), op.operationId(), review.inputs().revision(), review.inputs().reviewHash());
                if (review.phase() == Phase.UNUSED_INPUTS) scope.world.rejectUnstarted(ctx.actor(), op.operationId());
            } else {
                op = materials.cancel(ctx.actor(), op.operationId());
                if (op.state() == MaterialLedger.State.CALLBACK_DONE) op = delivery.deliver(ctx, op);
                return finishReturn(ctx, review, op);
            }
            var settlement = new Settlement(op.taskId(), op.operationId(), op.planHash(), digest(steps(scope, ctx.actor(), op.operationId())), review.hash());
            ctx.workspace().conversation().append(List.of(new Draft(op.taskId(), "build_history_settled", null, AiJson.GSON.toJsonTree(settlement).getAsJsonObject(), review.id().toString())));
            refresh(scope, ctx.actor()); return result(ctx, op, "已核对保存的施工历史；未重放世界修改。剩余托管须另行批准返还。");
        } finally { busy.remove(review.operationId()); }
    }
    private AgentTool.Result finishReturn(AgentTool.Context ctx, Review review, MaterialLedger.Operation op) throws Exception {
        if (!op.saved()) throw new AgentTool.Deferred("BUILD_RETURN_NATIVE_SAVE_PENDING", args(review), awaitSaved.apply(op.operationId()), fresh -> {
            identity(scope(fresh.workspace(), fresh.actor()), fresh.actor(), review);
            if (!review.taskId().equals(fresh.taskId())) throw new SecurityException("BUILD_RECOVERY_TASK_CHANGED");
            return returnResult(fresh, materials.get(fresh.actor(), review.operationId()), "真实余料交付与原生保存已重新核对。");
        });
        return returnResult(ctx, op, "已归还可确认的真实余料；没有返还已施工消耗的原料。满仓或未确定的部分仍保留托管。");
    }
    private AgentTool.Result returnResult(AgentTool.Context ctx, MaterialLedger.Operation op, String text) throws Exception {
        var result = result(ctx, op, text);
        if (op.uncertain() || op.settled() && op.saved()) return result;
        return new AgentTool.Result(false, "CUSTODY_REMAINS", text, result.observations(), result.evidence(), "", false);
    }
    private AgentTool.Result result(AgentTool.Context ctx, MaterialLedger.Operation op, String text) throws Exception {
        var data = evidence(ctx.workspace(), ctx.actor(), op.operationId());
        return new AgentTool.Result(!op.uncertain(), op.uncertain() ? "RECOVERY_REQUIRED" : "", text, data, "saved-build-history:" + op.operationId(), "", false);
    }
    private static JsonObject args(Review review) { var data = new JsonObject(); data.addProperty("reviewId", review.id().toString()); data.addProperty("reviewHash", review.hash()); return data; }
    public void register(ToolRegistry tools) {
        var properties = JsonParser.parseString("{\"reviewId\":{\"type\":\"string\"},\"reviewHash\":{\"type\":\"string\"}}");
        var schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", properties);
        schema.add("required", AiJson.GSON.toJsonTree(List.of("reviewId", "reviewHash"))); schema.addProperty("additionalProperties", false);
        tools.register(new DefinedTool(new AgentTool.Descriptor("build_recovery_execute", "执行本人工作台生成的新施工历史/真实余料复核。只接受固定reviewId/reviewHash；不重放世界修改、不授予旧撤销权。",
                schema, AgentTool.Risk.PLAYER_STATE, "construction.own", 0, AgentTool.ExecutionThread.IO, 120000, true, ""),
                (ctx, arguments) -> execute(ctx, load(ctx.workspace(), ctx.actor(), UUID.fromString(arguments.get("reviewId").getAsString()), arguments.get("reviewHash").getAsString()))));
    }
    @Override public List<JsonObject> rows(Workspace workspace, Actor actor, UUID task) {
        var scope = scope(workspace, actor); refresh(scope, actor); var rows = new ArrayList<JsonObject>();
        for (var op : materials.list(actor)) if (op.taskId().equals(task)) {
            try { owned(scope, actor, op.operationId()); } catch (IllegalArgumentException unrelated) { continue; }
            if (op.settled() && op.saved() && steps(scope, actor, op.operationId()).stream().allMatch(BuildJournal.Step::settled)) continue;
            var row = new JsonObject(); row.addProperty("taskId", task.toString()); row.addProperty("operationId", op.operationId().toString());
            row.addProperty("kind", "BUILD"); row.addProperty("state", op.state().name()); row.addProperty("reason", "SAVED_HISTORY_REVIEW_REQUIRED");
            row.addProperty("reasonText", "从真实原生回执复核历史；无法确定的副作用保持待核验，不能普通继续重放。");
            var actions = new JsonArray();
            try {
                if (!idle.test(op.operationId())) throw new IllegalStateException("BUILD_NATIVE_WORK_STILL_ACTIVE");
                basis(scope, actor, op, steps(scope, actor, op.operationId())); actions.add("BUILD_RECONCILE");
            } catch (RuntimeException unknown) {
                String reason = unknown.getMessage(); row.addProperty("reason", reason != null && reason.matches("[A-Z0-9_]{1,128}") ? reason : "WORLD_NATIVE_OUTCOME_UNKNOWN");
            }
            row.add("actions", actions); rows.add(row);
        }
        return List.copyOf(rows);
    }
    @Override public Preview preview(AgentTool.Context ctx, UUID operation) throws Exception {
        var review = previewReview(ctx, operation); return new Preview("build_recovery_execute", args(review), reviewText(ctx.workspace(), ctx.actor(), review));
    }
    @Override public JsonObject evidence(Workspace workspace, Actor actor, UUID id) {
        var scope = scope(workspace, actor); refresh(scope, actor); var op = owned(scope, actor, id); var world = steps(scope, actor, id);
        var data = new JsonObject(); data.addProperty("taskId", op.taskId().toString()); data.addProperty("operationId", id.toString());
        data.addProperty("materialRevision", op.revision()); data.addProperty("materialDigest", digest(op)); data.addProperty("worldDigest", digest(world));
        data.addProperty("state", op.state().name()); data.addProperty("nativeSaved", op.saved()); data.addProperty("worldSteps", world.size());
        data.addProperty("remainingCustody", op.escrow().stream().mapToLong(MaterialStack::count).sum());
        data.addProperty("undelivered", op.remainingProducts().stream().mapToLong(MaterialStack::count).sum());
        return data;
    }
    @Override public void validatePreview(Workspace workspace, Actor actor, UUID task, UUID operation, JsonObject arguments) {
        if (!arguments.keySet().equals(Set.of("reviewId", "reviewHash"))) throw new IllegalArgumentException("BUILD_RECOVERY_ARGUMENTS_CHANGED");
        var review = load(workspace, actor, UUID.fromString(arguments.get("reviewId").getAsString()), arguments.get("reviewHash").getAsString());
        if (!review.taskId().equals(task) || !review.operationId().equals(operation)) throw new SecurityException("BUILD_RECOVERY_TASK_CHANGED");
        validate(workspace, actor, review);
    }
    @Override public boolean settled(Workspace workspace, Actor actor, UUID operation) {
        var scope = scope(workspace, actor); var op = owned(scope, actor, operation);
        return op.settled() && op.saved() && steps(scope, actor, operation).stream().allMatch(BuildJournal.Step::settled);
    }
    @Override public String settledStatus() { return "SAVED_BUILD_HISTORY_AND_CUSTODY_SETTLED"; }
    @Override public Optional<Boolean> settledCall(Workspace workspace, Actor actor, UUID task, ModelMessage.ToolCall call) {
        if (!Set.of("build_execute", "build_recovery_execute").contains(call.name())) return Optional.empty();
        try {
            var scope = scope(workspace, actor); var args = AiJson.object(call.arguments(), 65536, 24); UUID id;
            if (call.name().equals("build_execute")) {
                if (!args.keySet().equals(Set.of("planId", "planHash"))) return Optional.of(false);
                id = UUID.fromString(args.get("planId").getAsString()); var marker = scope.index.settlements().get(id);
                if (marker == null || !marker.taskId().equals(task) || !marker.planHash().equals(args.get("planHash").getAsString())
                        || !marker.worldDigest().equals(digest(steps(scope, actor, id)))) return Optional.of(false);
            } else {
                if (!args.keySet().equals(Set.of("reviewId", "reviewHash"))) return Optional.of(false);
                var review = scope.index.reviews().get(UUID.fromString(args.get("reviewId").getAsString()));
                if (review == null || !review.taskId().equals(task) || !review.hash().equals(args.get("reviewHash").getAsString())
                        || !scope.index.accepted().contains(review.id())) return Optional.of(false);
                id = review.operationId();
            }
            return Optional.of(settled(workspace, actor, id));
        } catch (RuntimeException invalid) { return Optional.of(false); }
    }
}
