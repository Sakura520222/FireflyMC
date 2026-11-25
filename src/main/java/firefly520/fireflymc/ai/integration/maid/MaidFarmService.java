package firefly520.fireflymc.ai.integration.maid;

import com.google.gson.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.blueprint.ConstructionWorldEvents;
import firefly520.fireflymc.ai.blueprint.WorldMutationTracker;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.MaterialInventoryService;
import firefly520.fireflymc.ai.material.MaterialLedger;
import firefly520.fireflymc.ai.material.NativeSaveBarrier;
import firefly520.fireflymc.ai.material.NativeSaveObserver;
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
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

/** Finite native farming: actual navigation/check rate, one approved cell, real seeds and actual output receipts. */
public final class MaidFarmService implements AutoCloseable {
    private record Witness(MaidFarmPlan plan, MaidFarmNative.State nativeState, Object connection, WorldMutationTracker tracker, WorldMutationTracker.Lease world) {}
    private record Work(Actor actor, TaskControl control, Witness witness, MaidCarrierBridge.Lease lease, CompletableFuture<Void> ready) {}
    private record RecoveryWitness(MaidFarmReconcile plan, MaidFarmPlan original, MaidFarmNative.RecoveryState state, Object connection, WorldMutationTracker.Lease world) {}
    private record RecoveryIdentity(UUID task, UUID operation, String hash, long privacyEpoch) {}
    private final MinecraftServer server;
    private final MaterialInventoryService inventory;
    private final VirtualProcessingService dispatcher;
    private final MaterialLedger materials;
    private final Map<UUID, MaterialLedger.NativeScopeLease> resourceLeases = new ConcurrentHashMap<>();
    private final MaidFarmNative nativeFarm;
    private final LongSupplier generation;
    private final UUID executionEpoch = UUID.randomUUID();
    private final Map<String, MaidFarmJournal> journals = new ConcurrentHashMap<>();
    private final Map<UUID, Witness> witnesses = new ConcurrentHashMap<>();
    private final Map<UUID, Work> active = new ConcurrentHashMap<>();
    private final ArrayBlockingQueue<Work> waits = new ArrayBlockingQueue<>(64);
    private final Set<UUID> executing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, RecoveryWitness> recoveryWitnesses = new ConcurrentHashMap<>();
    private final Map<String, RecoveryIdentity> recoveryIdentities = new ConcurrentHashMap<>();
    private final Set<String> acceptedRecoveries = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> recoveryProofs = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> activeRecoveries = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public MaidFarmService(MinecraftServer server, MaterialInventoryService inventory, InstalledPermissionAdapters permissions,
                           VirtualProcessingService dispatcher, LongSupplier generation, MaterialLedger materials) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        this.server = server; this.inventory = inventory; this.dispatcher = dispatcher; this.generation = generation;
        this.materials = Objects.requireNonNull(materials);
        var nativeControl = new MaidControlNative(inventory.maids(), new MaidEntityPermission(server,
                firefly520.fireflymc.ai.AiServerService::authenticated, permissions), inventory.nativeSaves());
        nativeFarm = new MaidFarmNative(inventory, permissions, nativeControl);
    }
    public synchronized void prepare(Workspace workspace) throws IOException {
        if (closed) throw new IllegalStateException("AI_STOPPING");
        if (!journals.containsKey(workspace.owner())) {
            MaidFarmJournal repo;
            try { repo = new MaidFarmJournal(workspace); }
            catch (IOException | RuntimeException invalid) { materials.quarantineNativeScopeRestoration(); throw invalid; }
            var journalOwner = new Actor(workspace.owner(), true, false, 0);
            for (var op : repo.snapshot(journalOwner)) if (!op.settled()) {
                var lease = materials.restoreNativeScope(UUID.fromString(workspace.owner()), op.plan().id(), List.of(backend(op.plan())));
                resourceLeases.put(op.plan().id(), lease);
            }
            var events = workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events();
            for (var event : events) if (event.kind().equals("maid_farm_reconcile_preview") && event.data() != null) {
                try {
                    UUID id = UUID.fromString(event.data().get("reviewId").getAsString()); String hash = event.data().get("reviewHash").getAsString(); Path file = workspace.artifact(id);
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4 * 1024 * 1024) continue;
                    var review = AiJson.GSON.fromJson(Files.readString(file), MaidFarmReconcile.class);
                    review.requireStorageIdentity(workspace, new Actor(workspace.owner(), true, false, 0));
                    if (id.equals(review.id()) && hash.equals(review.hash()) && Objects.equals(event.taskId(), review.taskId())) indexRecovery(review);
                } catch (RuntimeException invalid) { /* Invalid historical identities cannot clear a task. */ }
            }
            for (var event : events) if (event.kind().equals("maid_farm_evidence") && event.data() != null && event.data().has("reason")) {
                String reason = event.data().get("reason").getAsString(), prefix = "CURRENT_STATE_RECONCILE_PENDING:";
                if (reason.startsWith(prefix)) {
                    var known = recoveryIdentities.get(workspace.owner() + ":" + reason.substring(prefix.length()));
                    if (known != null && Objects.equals(known.task(), event.taskId())) acceptedRecoveries.add(workspace.owner() + ":" + known.hash());
                }
            }
            journals.put(workspace.owner(), repo);
        }
    }
    private MaidFarmJournal journal(Workspace workspace, Actor actor) throws IOException {
        actor.requireAccess(workspace.owner()); var value = journals.get(workspace.owner());
        if (value != null) return value; prepare(workspace); return journals.get(workspace.owner());
    }
    public boolean resourcesSettled(Workspace workspace, Actor actor) throws IOException { return journal(workspace, actor).settled(actor)
            && resourceLeases.values().stream().noneMatch(lease -> lease.owner().toString().equals(actor.owner())); }
    public boolean taskSettled(Workspace workspace, Actor actor, UUID task) throws IOException { var repo = journal(workspace, actor); return repo.taskSettled(actor, task)
            && repo.snapshot(actor).stream().filter(op -> op.plan().taskId().equals(task)).noneMatch(op -> resourceLeases.containsKey(op.plan().id())); }
    public String exportJournal(Workspace workspace, Actor actor) throws IOException { return journal(workspace, actor).export(actor); }
    public void disconnected(UUID owner) {
        for (var entry : active.entrySet()) if (entry.getValue().actor().owner().equals(owner.toString()) && active.remove(entry.getKey(), entry.getValue())) {
            entry.getValue().control().stop("PLAYER_OFFLINE"); entry.getValue().ready().completeExceptionally(new TaskControl.Stopped("PLAYER_OFFLINE")); cleanup(entry.getValue());
        }
    }
    public void forgetErased(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner()); var old = journals.get(workspace.owner()); if (old == null) return;
        if (resourceLeases.values().stream().anyMatch(lease -> lease.owner().toString().equals(actor.owner()))) throw new IllegalStateException("MAID_FARM_RESOURCE_SCOPE_HELD");
        long epoch = workspace.conversation().privacyEpoch(); var ids = old.erasedOperationIds(actor, epoch);
        if (!journals.remove(workspace.owner(), old)) throw new IllegalStateException("MAID_FARM_PRIVACY_CACHE_CHANGED");
        for (var entry : witnesses.entrySet()) if (entry.getValue().plan().owner().equals(workspace.owner()) && entry.getValue().plan().privacyEpoch() < epoch
                && witnesses.remove(entry.getKey(), entry.getValue())) server.execute(entry.getValue().world()::close);
        for (var entry : recoveryWitnesses.entrySet()) if (entry.getValue().plan().owner().equals(workspace.owner()) && entry.getValue().plan().privacyEpoch() < epoch
                && recoveryWitnesses.remove(entry.getKey(), entry.getValue())) { server.execute(entry.getValue().world()::close); recoveryProofs.remove(entry.getKey()); }
        recoveryIdentities.entrySet().removeIf(entry -> entry.getKey().startsWith(workspace.owner() + ":") && entry.getValue().privacyEpoch() < epoch);
        activeRecoveries.keySet().removeAll(ids); active.keySet().removeAll(ids);
    }
    public boolean ownsOperation(Workspace workspace, Actor actor, UUID id) {
        actor.requireAccess(workspace.owner()); var repo = journals.get(workspace.owner()); return repo != null && repo.published(actor, id) != null;
    }
    public boolean operationSettled(Workspace workspace, Actor actor, UUID id) throws IOException { return journal(workspace, actor).operationSettled(actor, id) && !resourceLeases.containsKey(id); }
    public Optional<Boolean> settledCall(Workspace workspace, Actor actor, UUID task, ModelMessage.ToolCall call) {
        if (!Set.of("maid_farm_execute", "maid_farm_reconcile").contains(call.name())) return Optional.empty();
        try {
            actor.requireAccess(workspace.owner()); var repo = journals.get(workspace.owner()); if (repo == null) return Optional.of(false);
            var args = AiJson.object(call.arguments(), 65536, 16); UUID operation;
            if (call.name().equals("maid_farm_execute")) {
                if (!args.keySet().equals(Set.of("planId", "planHash"))) return Optional.of(false); operation = UUID.fromString(text(args, "planId"));
                var op = repo.published(actor, operation);
                if (op == null || !op.plan().hash().equals(text(args, "planHash"))) return Optional.of(false);
            } else {
                if (!args.keySet().equals(Set.of("reviewId", "reviewHash"))) return Optional.of(false);
                var known = recoveryIdentities.get(workspace.owner() + ":" + UUID.fromString(text(args, "reviewId")));
                if (known == null || !known.task().equals(task) || !known.hash().equals(text(args, "reviewHash"))
                        || !acceptedRecoveries.contains(workspace.owner() + ":" + known.hash())) return Optional.of(false);
                operation = known.operation();
            }
            var op = repo.published(actor, operation); if (op == null) return Optional.of(false); var plan = op.plan();
            return Optional.of(plan.owner().equals(workspace.owner()) && plan.taskId().equals(task) && plan.serverInstanceId().equals(workspace.metadata().serverInstanceId())
                    && plan.conversationId().equals(workspace.primaryConversationId()) && plan.privacyEpoch() == workspace.conversation().privacyEpoch() && repo.operationSettled(actor, operation));
        } catch (RuntimeException invalid) { return Optional.of(false); }
    }
    private void indexRecovery(MaidFarmReconcile review) {
        var value = new RecoveryIdentity(review.taskId(), review.operationId(), review.hash(), review.privacyEpoch());
        recoveryIdentities.put(review.owner() + ":" + review.id(), value); recoveryIdentities.put(review.owner() + ":" + review.hash(), value);
    }
    public List<JsonObject> recoveryRows(Workspace workspace, Actor actor, UUID task) throws IOException {
        return journal(workspace, actor).snapshot(actor).stream().filter(op -> op.plan().taskId().equals(task)).map(op -> {
            var row = new JsonObject(); row.addProperty("operationId", op.plan().id().toString()); row.addProperty("taskId", task.toString());
            row.addProperty("kind", "MAID_FARM"); row.addProperty("state", op.state().name()); row.addProperty("reason", op.reason()); row.addProperty("settled", op.settled());
            row.add("actions", AiJson.GSON.toJsonTree(!op.settled() && op.evidence() != null && op.evidence().verified() ? List.of("MAID_RECONCILE") : List.of()));
            if (!op.settled() && (op.evidence() == null || !op.evidence().verified())) row.addProperty("reasonText", "原生额外副作用尚未覆盖，不能仅凭当前数量或通用继续清除。");
            return row;
        }).toList();
    }
    public JsonObject recoveryResources(Workspace workspace, Actor actor, UUID id) throws IOException {
        var op = journal(workspace, actor).get(actor, id); var data = status(op);
        data.addProperty("operationId", id.toString()); data.addProperty("taskId", op.plan().taskId().toString()); data.addProperty("digest", AiJson.digest(AiJson.canonical(op)));
        return data;
    }
    public MaidFarmReconcile recoveryPreview(AgentTool.Context ctx, UUID id) throws Exception {
        requireContext(ctx); var repo = journal(ctx.workspace(), ctx.actor()); var op = repo.get(ctx.actor(), id);
        if (!op.plan().taskId().equals(ctx.taskId())) throw new SecurityException("MAID_FARM_TASK_MISMATCH");
        if (op.settled() || op.evidence() == null || !op.evidence().verified()) throw new IllegalStateException("MAID_FARM_UNKNOWN_EFFECT_REVIEW_REQUIRED");
        var witness = dispatcher.inspectOwned(ctx, () -> {
            for (var entry : recoveryWitnesses.entrySet()) if (entry.getValue().plan().expiresAt() <= System.currentTimeMillis()
                    && !activeRecoveries.containsValue(entry.getKey()) && recoveryWitnesses.remove(entry.getKey(), entry.getValue())) entry.getValue().world().close();
            var player = inventory.requirePlayer(ctx.actor()); var current = nativeFarm.recovery(player, ctx.actor(), op.plan());
            UUID reviewId = UUID.randomUUID();
            var review = new MaidFarmReconcile(1, reviewId, ctx.actor().owner(), ctx.taskId(), ctx.workspace().metadata().serverInstanceId(),
                    ctx.workspace().primaryConversationId(), ctx.workspace().conversation().privacyEpoch(), executionEpoch, System.currentTimeMillis() + 120000,
                    id, op.revision(), AiJson.digest(AiJson.canonical(op)), current.maid().state(), current.backpackId(), current.items(), current.hand(), current.offHand(),
                    MaidFarmNative.block(current.crop()), MaidFarmNative.block(current.soil()), "").signed();
            var world = ConstructionWorldEvents.tracker(server).watch(reviewId, player.serverLevel(), List.of(op.plan().cropPos().asLong(), op.plan().soilPos().asLong()));
            return new RecoveryWitness(review, op.plan(), current, player.connection, world);
        });
        var review = witness.plan();
        boolean stored = false;
        try {
        synchronized (ctx.workspace().conversation()) {
            review.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch);
            if (!repo.get(ctx.actor(), id).equals(op)) throw new IllegalStateException("MAID_FARM_RECONCILE_PREVIEW_CHANGED");
            if (recoveryWitnesses.size() >= 128) throw new IllegalStateException("MAID_FARM_RECOVERY_LIMIT");
            AiJson.write(ctx.workspace().artifact(review.id()), review); var data = new JsonObject();
            data.addProperty("reviewId", review.id().toString()); data.addProperty("reviewHash", review.hash()); data.addProperty("operationId", id.toString());
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "maid_farm_reconcile_preview", null, data, null)));
            recoveryWitnesses.put(review.id(), witness); indexRecovery(review);
            stored = true;
        }
        return review;
        } finally { if (!stored) server.execute(witness.world()::close); }
    }
    public MaidFarmReconcile loadReconciliation(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        actor.requireAccess(workspace.owner()); var witness = recoveryWitnesses.get(id);
        if (witness == null || !witness.plan().hash().equals(hash)) throw new IllegalStateException("MAID_FARM_RECONCILE_NEW_PREVIEW_REQUIRED");
        var plan = witness.plan(); plan.requireIdentity(workspace, actor, executionEpoch);
        Path file = workspace.artifact(id);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4 * 1024 * 1024
                || !plan.equals(AiJson.GSON.fromJson(Files.readString(file), MaidFarmReconcile.class))) throw new SecurityException("MAID_FARM_RECONCILE_CHANGED");
        return plan;
    }
    public void validateReconciliationVersion(Workspace workspace, Actor actor, MaidFarmReconcile review) throws IOException {
        review.requireIdentity(workspace, actor, executionEpoch); var op = journal(workspace, actor).get(actor, review.operationId());
        if (op.settled() || op.revision() != review.operationRevision() || !AiJson.digest(AiJson.canonical(op)).equals(review.operationDigest()))
            throw new IllegalStateException("MAID_FARM_RECONCILE_PREVIEW_CHANGED");
    }
    public String reconciliationReview(MaidFarmReconcile review) {
        return "只核对已观察且影响范围完整的原生农作操作当前世界/本人女仆库存并取得新的canonical保存凭据。"
                + "不执行收获/播种，不补发差额、不重抽掉落、不补称原目标成功；原任务需按现状重新规划。\n" + AiJson.GSON.toJson(review);
    }
    public AgentTool.Result reconcile(AgentTool.Context ctx, MaidFarmReconcile review) throws Exception {
        requireContext(ctx); review.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch);
        if (!review.taskId().equals(ctx.taskId()) || System.currentTimeMillis() >= review.expiresAt()) throw new IllegalStateException("MAID_FARM_RECONCILE_NEW_PREVIEW_REQUIRED");
        validateReconciliationVersion(ctx.workspace(), ctx.actor(), review); var repo = journal(ctx.workspace(), ctx.actor()); var witness = recoveryWitnesses.get(review.id());
        dispatcher.inspectOwned(ctx, () -> { validateRecoveryNative(ctx, witness); return true; });
        repo.beginReconcile(ctx.actor(), review); acceptedRecoveries.add(ctx.actor().owner() + ":" + review.hash()); activeRecoveries.put(review.operationId(), review.id());
        dispatcher.commitObserved(ctx, () -> {
            var current = validateRecoveryNative(ctx, witness); var player = inventory.requirePlayer(ctx.actor()); var stores = new ArrayList<CompletableFuture<NativeSaveBarrier.Receipt>>();
            stores.add(CompletableFuture.completedFuture(MaidFarmSaveWitness.observe(current.maid().entity(), review, inventory.nativeSaves())));
            var targets = new ArrayList<NativeSaveObserver.Target>(); targets.add(NativeSaveObserver.entity(current.maid().entity()));
            targets.add(NativeSaveObserver.chunk(player.serverLevel(), player.serverLevel().getChunkAt(witness.original().cropPos())));
            targets.add(NativeSaveObserver.player(player));
            targets.add(NativeSaveObserver.Target.file(player.getStats(), server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_STATS_DIR).resolve(player.getStringUUID() + ".json")));
            targets.add(NativeSaveObserver.Target.file(player.getAdvancements(), server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(player.getStringUUID() + ".json")));
            stores.add(CompletableFuture.completedFuture(inventory.nativeSaves().changed(targets))); return NativeSaveBarrier.combine(stores);
        }, receipt -> inventory.nativeSaves().record(review.id(), receipt, proof -> { repo.currentProof(ctx.actor(), review, proof); recoveryProofs.put(review.id(), proof); }));
        return finishReconcile(ctx, review);
    }
    private MaidFarmNative.RecoveryState validateRecoveryNative(AgentTool.Context ctx, RecoveryWitness witness) throws Exception {
        if (witness == null) throw new IllegalStateException("MAID_FARM_RECONCILE_NEW_PREVIEW_REQUIRED");
        witness.world().check(); var player = inventory.requirePlayer(ctx.actor());
        if (player.connection != witness.connection()) throw new SecurityException("MAID_FARM_CONNECTION_CHANGED");
        var current = nativeFarm.recovery(player, ctx.actor(), witness.original()); var expected = witness.plan();
        if (current.maid().entity() != witness.state().maid().entity() || current.maid().task() != witness.state().maid().task()
                || current.maid().revision() != witness.state().maid().revision() || !current.maid().state().equals(expected.maid())
                || !current.backpackId().equals(expected.backpackId()) || !current.items().equals(expected.inventory())
                || !current.hand().equals(expected.hand()) || !current.offHand().equals(expected.offHand())
                || !MaidFarmNative.block(current.crop()).equals(expected.crop()) || !MaidFarmNative.block(current.soil()).equals(expected.soil()))
            throw new IllegalStateException("MAID_FARM_CURRENT_STATE_CHANGED");
        return current;
    }
    private AgentTool.Result finishReconcile(AgentTool.Context ctx, MaidFarmReconcile review) throws Exception {
        requireContext(ctx); review.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch); var repo = journal(ctx.workspace(), ctx.actor()); repo.requireReconcile(ctx.actor(), review);
        if (!review.id().equals(activeRecoveries.get(review.operationId()))) throw new IllegalStateException("MAID_FARM_RECONCILE_SUPERSEDED");
        var ready = inventory.nativeSaves().awaitJournalled(review.id());
        if (!ready.toCompletableFuture().isDone()) {
            var data = new JsonObject(); data.addProperty("operationId", review.operationId().toString()); data.addProperty("reviewId", review.id().toString());
            throw new AgentTool.Deferred("WAITING_MAID_FARM_CURRENT_SAVE", data, ready, fresh -> finishReconcile(fresh, review));
        }
        String proof = recoveryProofs.get(review.id()); if (proof == null) throw new IllegalStateException("MAID_FARM_CURRENT_PROOF_REQUIRED");
        dispatcher.inspectOwned(ctx, () -> { validateRecoveryNative(ctx, recoveryWitnesses.get(review.id())); return true; });
        var op = repo.reconciled(ctx.actor(), review, proof); var witness = recoveryWitnesses.remove(review.id());
        releaseResourceIfSettled(ctx.actor(), op);
        if (witness != null) server.execute(witness.world()::close); activeRecoveries.remove(review.operationId(), review.id()); recoveryProofs.remove(review.id());
        return result(true, "RECONCILED_CURRENT_STATE", "已按新保存证明核对当前世界和本人女仆库存；未重放、补偿或补称原目标成功。", status(op));
    }
    public void register(ToolRegistry tools) {
        tool(tools, "maid_farm_plan", "规划本人已绑定女仆的一格原生收获或播种。需要独立授权的女仆普通背包sourceId；保留真实种子、工作日程、寻路和原生检查间隔，非MATERIALS_ONLY加工。当前白名单为原版小麦/胡萝卜/马铃薯/甜菜、空手与普通背包，无自动化饰品。",
                "{\"kind\":{\"type\":\"string\",\"enum\":[\"HARVEST\",\"PLANT\"]},\"x\":{\"type\":\"integer\"},\"y\":{\"type\":\"integer\"},\"z\":{\"type\":\"integer\"},\"sourceId\":{\"type\":\"string\"},\"seedSlot\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":35}}",
                List.of("kind", "x", "y", "z", "sourceId"), AgentTool.Risk.READ_ONLY, (ctx, args) -> {
                    var kind = MaidFarmPlan.Kind.valueOf(text(args, "kind"));
                    var plan = plan(ctx, kind, new BlockPos(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt()),
                            UUID.fromString(text(args, "sourceId")), kind == MaidFarmPlan.Kind.HARVEST ? -1 : args.get("seedSlot").getAsInt());
                    return result(true, "", "已固定原生作物动作与真实库存版本；尚未收获或扣种子。", view(plan));
                });
        tool(tools, "maid_farm_execute", "执行固定审批的一格女仆原生农作动作；先原生寻路并遵守原检查间隔，再运行真实MaidFarmPlantTask，逐操作检查主人、独立背包授权、领地和实际方块/种子/产物。未知回调不重放。",
                "{\"planId\":{\"type\":\"string\"},\"planHash\":{\"type\":\"string\"}}", List.of("planId", "planHash"), AgentTool.Risk.WORLD_CHANGE,
                (ctx, args) -> execute(ctx, load(ctx.workspace(), ctx.actor(), UUID.fromString(text(args, "planId")), text(args, "planHash"))));
        tool(tools, "maid_farm_status", "读取本人女仆农作动作的实际历史和保存凭据；不把当时的库存快照当成当前仍持有的物品。",
                "{\"operationId\":{\"type\":\"string\"}}", List.of("operationId"), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> result(true, "", "本人原生农作动作的历史回执。", status(journal(ctx.workspace(), ctx.actor()).get(ctx.actor(), UUID.fromString(text(args, "operationId"))))));
        tool(tools, "maid_farm_reconcile", "按新审批核对已完整观察的原农作影响格和本人女仆当前真实库存，等待新canonical保存；不重放、不补种、不补发差额、不宣称旧目标成功。未知额外副作用不接受此入口。",
                "{\"reviewId\":{\"type\":\"string\"},\"reviewHash\":{\"type\":\"string\"}}", List.of("reviewId", "reviewHash"), AgentTool.Risk.PLAYER_STATE,
                (ctx, args) -> reconcile(ctx, loadReconciliation(ctx.workspace(), ctx.actor(), UUID.fromString(text(args, "reviewId")), text(args, "reviewHash"))));
    }
    public MaidFarmPlan plan(AgentTool.Context ctx, MaidFarmPlan.Kind kind, BlockPos crop, UUID sourceId, int seedSlot) throws Exception {
        requireContext(ctx); var repo = journal(ctx.workspace(), ctx.actor()); if (!repo.settled(ctx.actor())) throw new IllegalStateException("MAID_FARM_REVIEW_REQUIRED");
        var witness = dispatcher.inspectOwned(ctx, () -> {
            for (var entry : witnesses.entrySet()) if (entry.getValue().plan().expiresAt() <= System.currentTimeMillis() && !active.containsKey(entry.getKey())
                    && witnesses.remove(entry.getKey(), entry.getValue())) entry.getValue().world().close();
            var player = inventory.requirePlayer(ctx.actor()); var state = nativeFarm.inspect(player, ctx.actor(), kind, crop, sourceId, seedSlot, false);
            var plan = new MaidFarmPlan(1, UUID.randomUUID(), ctx.actor().owner(), ctx.taskId(), ctx.workspace().metadata().serverInstanceId(), ctx.workspace().primaryConversationId(),
                    ctx.workspace().conversation().privacyEpoch(), executionEpoch, generation.getAsLong(), System.currentTimeMillis() + 120000,
                    kind, crop.getX(), crop.getY(), crop.getZ(), sourceId, state.grant().revision(), state.grant().source().digest(), seedSlot,
                    state.maid().state(), MaidFarmNative.block(state.crop()), MaidFarmNative.block(state.soil()), state.items(), state.hand(), state.offHand(), "").signed();
            var tracker = ConstructionWorldEvents.tracker(server);
            return new Witness(plan, state, player.connection, tracker, tracker.watch(plan.id(), player.serverLevel(), List.of(crop.asLong(), crop.below().asLong())));
        });
        var plan = witness.plan();
        boolean stored = false;
        try {
        if (AiJson.GSON.toJson(plan).length() > 1024 * 1024) throw new IllegalArgumentException("MAID_FARM_PLAN_TOO_LARGE");
        synchronized (ctx.workspace().conversation()) {
            requireContext(ctx); plan.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch); requireGeneration(plan);
            if (witnesses.size() >= 256) throw new IllegalStateException("MAID_FARM_PREVIEW_LIMIT");
            AiJson.write(ctx.workspace().artifact(plan.id()), plan);
            var data = new JsonObject(); data.addProperty("planId", plan.id().toString()); data.addProperty("planHash", plan.hash());
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "maid_farm_plan", null, data, null)));
            witnesses.put(plan.id(), witness);
            stored = true;
        }
        return plan;
        } finally { if (!stored) server.execute(witness.world()::close); }
    }
    public MaidFarmPlan load(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) {
            if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("MAID_FARM_PLAN_NOT_FOUND");
            boolean indexed = workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events().stream().anyMatch(event -> event.kind().equals("maid_farm_plan")
                    && event.data() != null && id.toString().equals(event.data().get("planId").getAsString()) && hash.equals(event.data().get("planHash").getAsString()));
            Path file = workspace.artifact(id);
            if (!indexed || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 4 * 1024 * 1024) throw new IllegalArgumentException("MAID_FARM_PLAN_NOT_FOUND");
            var plan = AiJson.GSON.fromJson(Files.readString(file), MaidFarmPlan.class);
            if (plan == null || !plan.id().equals(id) || !plan.hash().equals(hash)) throw new SecurityException("MAID_FARM_PLAN_CHANGED");
            plan.requireIdentity(workspace, actor, executionEpoch); requireGeneration(plan);
            var witness = witnesses.get(id);
            if (witness == null || !witness.plan().equals(plan) || System.currentTimeMillis() >= plan.expiresAt()) throw new IllegalStateException("MAID_FARM_NEW_PREVIEW_REQUIRED");
            return witness.plan();
        }
    }
    public String review(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        var plan = load(workspace, actor, id, hash);
        return "一格真实女仆原生农作：保留工作时段、原生寻路和检查间隔；播种扣本人已授权普通背包中的一份真实种子，收获只记录真实原生掉落，不重抽。"
                + "本操作不豁免日程、冷却、工具或其他费用，不改变女仆原任务；未知副作用先核对，不能直接重放。\n" + AiJson.GSON.toJson(view(plan));
    }
    public AgentTool.Result execute(AgentTool.Context ctx, MaidFarmPlan plan) throws Exception {
        requireContext(ctx); plan.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch);
        if (!plan.taskId().equals(ctx.taskId())) throw new SecurityException("MAID_FARM_TASK_MISMATCH");
        var repo = journal(ctx.workspace(), ctx.actor());
        if (repo.published(ctx.actor(), plan.id()) != null) return result(false, "MAID_FARM_ALREADY_RECORDED", "操作已有记录，未重复采集、播种或取料。", status(repo.get(ctx.actor(), plan.id())));
        var witness = witnesses.get(plan.id());
        if (witness == null || witness.plan() != plan || System.currentTimeMillis() >= plan.expiresAt()) throw new IllegalStateException("MAID_FARM_NEW_PREVIEW_REQUIRED");
        requireGeneration(plan); repo.prepare(ctx.actor(), plan);
        return continuePrepared(ctx, plan);
    }
    private AgentTool.Result continuePrepared(AgentTool.Context ctx, MaidFarmPlan plan) throws Exception {
        requireContext(ctx); plan.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch); requireGeneration(plan);
        if (!plan.taskId().equals(ctx.taskId())) throw new SecurityException("MAID_FARM_TASK_MISMATCH");
        var repo = journal(ctx.workspace(), ctx.actor());
        if (!executing.add(plan.id())) throw new IllegalStateException("MAID_FARM_ALREADY_EXECUTING");
        boolean waitForNative = false;
        try {
            var witness = witnesses.get(plan.id()); if (witness == null || witness.plan() != plan) throw new IllegalStateException("MAID_FARM_NEW_PREVIEW_REQUIRED");
            resourceLeases.put(plan.id(), materials.acquireNativeScope(ctx.actor(), plan.id(), List.of(backend(plan))));
            var work = active.get(plan.id());
            if (work == null) {
                work = dispatcher.inspectOwned(ctx, () -> {
                    witness.world().check();
                    var player = inventory.requirePlayer(ctx.actor()); var current = nativeFarm.inspect(player, ctx.actor(), plan.kind(), plan.cropPos(), plan.sourceId(), plan.seedSlot(), false);
                    if (player.connection != witness.connection()) throw new SecurityException("MAID_FARM_CONNECTION_CHANGED");
                    MaidFarmNative.requireSame(plan, witness.nativeState(), current);
                    var lease = inventory.maids().acquireOperation(player, player.getUUID(), plan.id());
                    if (lease.lease() == null) throw new IllegalStateException(lease.reason());
                    return new Work(ctx.actor(), ctx.control(), witness, lease.lease(), new CompletableFuture<>());
                });
                active.put(plan.id(), work);
                if (!waits.offer(work)) { cleanup(work); throw new IllegalStateException("MAID_FARM_NATIVE_WAIT_QUEUE_FULL"); }
            }
            if (!work.ready().isDone()) {
                waitForNative = true; var data = new JsonObject(); data.addProperty("operationId", plan.id().toString()); data.addProperty("planHash", plan.hash());
                data.addProperty("phase", "WAITING_NATIVE_NAVIGATION_AND_RATE");
                throw new AgentTool.Deferred("WAITING_NATIVE_MAID_FARM", data, work.ready(), fresh -> continuePrepared(fresh, plan));
            }
            work.ready().get();
            var accepted = work;
            repo.begin(ctx.actor(), plan.id());
            try {
                dispatcher.commitObserved(ctx, () -> {
                    if (closed) return new MaidFarmNative.Outcome(false, false, false, null, null, List.of(),
                            firefly520.fireflymc.ai.material.MaterialStack.EMPTY, firefly520.fireflymc.ai.material.MaterialStack.EMPTY, Set.of(), "AI_STOPPING", null);
                    try { requireResource(ctx.actor(), plan.id()); }
                    catch (RuntimeException unavailable) { return new MaidFarmNative.Outcome(false, false, false, null, null, List.of(),
                            firefly520.fireflymc.ai.material.MaterialStack.EMPTY, firefly520.fireflymc.ai.material.MaterialStack.EMPTY, Set.of(), safe(unavailable), null); }
                    requireGeneration(plan); var player = inventory.requirePlayer(ctx.actor());
                    witness.world().check();
                    if (player.connection != witness.connection()) return new MaidFarmNative.Outcome(false, false, false, null, null, List.of(),
                            firefly520.fireflymc.ai.material.MaterialStack.EMPTY, firefly520.fireflymc.ai.material.MaterialStack.EMPTY, Set.of(), "MAID_FARM_CONNECTION_CHANGED", null);
                    var lease = inventory.maids().validateOperation(player, player.getUUID(), accepted.lease());
                    if (lease.lease() == null) return new MaidFarmNative.Outcome(false, false, false, null, null, List.of(),
                            firefly520.fireflymc.ai.material.MaterialStack.EMPTY, firefly520.fireflymc.ai.material.MaterialStack.EMPTY, Set.of(), lease.reason(), null);
                    try (var own = witness.tracker().own(witness.world(), player.serverLevel(), List.of(plan.cropPos().asLong()))) {
                        return nativeFarm.apply(player, ctx.actor(), plan, witness.nativeState());
                    }
                }, effect -> {
                    releaseResourceIfSettled(ctx.actor(), repo.observed(ctx.actor(), plan.id(), effect));
                    if (effect.entered() && effect.save() != null) inventory.nativeSaves().record(plan.id(), effect.save(), proof -> {
                        repo.saved(ctx.actor(), plan.id(), proof); releaseResourceIfSettled(ctx.actor(), repo.get(ctx.actor(), plan.id()));
                    });
                });
            } catch (CommittedCall.Rejected rejected) {
                repo.rejectBeforeNative(ctx.actor(), plan.id(), "REJECTED_BEFORE_NATIVE_INVOCATION");
                if (rejected.getCause() instanceof TaskControl.Stopped stopped) throw stopped;
                throw rejected;
            }
            return complete(ctx, plan.id());
        } catch (AgentTool.Deferred deferred) { throw deferred; }
        catch (Exception failure) {
            var op = repo.get(ctx.actor(), plan.id());
            if (op.state() == MaidFarmJournal.State.PREPARED) op = repo.rejectBeforeNative(ctx.actor(), plan.id(), safe(failure));
            else if (!op.settled() && op.state() != MaidFarmJournal.State.RECOVERY_REQUIRED && op.state() != MaidFarmJournal.State.OBSERVED)
                op = repo.unknown(ctx.actor(), plan.id(), safe(failure));
            releaseResourceIfSettled(ctx.actor(), op);
            return result(false, op.settled() ? safe(failure) : "RECOVERY_REQUIRED", "原生农作未确认完成；实际回执保留，未重放或补造种子/产物。", status(op));
        } finally {
            executing.remove(plan.id());
            if (!waitForNative) { var work = active.remove(plan.id()); if (work != null) cleanup(work); }
        }
    }
    private AgentTool.Result complete(AgentTool.Context ctx, UUID id) throws Exception {
        requireContext(ctx); var repo = journal(ctx.workspace(), ctx.actor()); var op = repo.get(ctx.actor(), id);
        if (!op.plan().taskId().equals(ctx.taskId())) throw new SecurityException("MAID_FARM_TASK_MISMATCH");
        if (op.state() == MaidFarmJournal.State.OBSERVED) {
            var ready = inventory.nativeSaves().awaitJournalled(id);
            if (!ready.toCompletableFuture().isDone()) {
                var data = new JsonObject(); data.addProperty("operationId", id.toString()); data.addProperty("phase", "WAITING_NATIVE_FARM_SAVE");
                throw new AgentTool.Deferred("WAITING_NATIVE_MAID_FARM_SAVE", data, ready, fresh -> complete(fresh, id));
            }
            op = repo.get(ctx.actor(), id);
        }
        boolean success = op.state() == MaidFarmJournal.State.SAVED && op.evidence().achieved();
        releaseResourceIfSettled(ctx.actor(), op);
        return result(success, success ? "" : op.state() == MaidFarmJournal.State.REJECTED || op.state() == MaidFarmJournal.State.SAVED ? op.reason() : "RECOVERY_REQUIRED",
                success ? "一格真实原生农作已核验并有保存凭据；历史回执记录当时方块、真实种子和实际产物，没有费用豁免。"
                        : "原生农作尚未确认完成；真实状态和产物记录保留，不能重复调用假称成功。", status(op));
    }
    public void tick() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        long end = System.nanoTime() + 1_000_000;
        var visited = new HashSet<UUID>();
        for (int i = 0; i < 2 && System.nanoTime() < end; i++) {
            var work = waits.poll(); if (work == null) return;
            if (!visited.add(work.witness().plan().id())) { waits.offer(work); break; }
            if (work.ready().isDone()) continue;
            try {
                if (closed) throw new TaskControl.Stopped("AI_STOPPING");
                work.control().check(); var plan = work.witness().plan(); requireGeneration(plan);
                work.witness().world().check();
                var player = inventory.requirePlayer(work.actor());
                var lease = inventory.maids().validateOperation(player, player.getUUID(), work.lease());
                if (lease.lease() == null) throw new IllegalStateException(lease.reason());
                var current = nativeFarm.inspect(player, work.actor(), plan.kind(), plan.cropPos(), plan.sourceId(), plan.seedSlot(), false);
                MaidFarmNative.requireSame(plan, work.witness().nativeState(), current);
                if (!nativeFarm.reached(current, plan.soilPos())) nativeFarm.navigate(current, plan.soilPos());
                else if (nativeFarm.rateReady(current, player.serverLevel())) { work.ready().complete(null); continue; }
                if (!waits.offer(work)) throw new IllegalStateException("MAID_FARM_NATIVE_WAIT_QUEUE_FULL");
            } catch (Exception | LinkageError failure) {
                work.ready().completeExceptionally(failure); active.remove(work.witness().plan().id(), work); cleanup(work);
            }
        }
    }
    private void cleanup(Work work) {
        var repo = journals.get(work.actor().owner());
        if (repo != null) { var op = repo.published(work.actor(), work.witness().plan().id()); if (op != null) releaseResourceIfSettled(work.actor(), op); }
        server.execute(() -> {
            work.witness().world().close();
            nativeFarm.stopNavigation(work.witness().nativeState(), work.witness().plan().soilPos(), UUID.fromString(work.actor().owner()));
            try { inventory.maids().releaseRecordedOperation(UUID.fromString(work.actor().owner()), work.lease()); }
            catch (RuntimeException disconnected) { /* Existing disconnect cleanup invalidates this owner lease. */ }
        });
    }
    private static String backend(MaidFarmPlan plan) { return "maid/" + plan.maid().maidId() + "/backpack"; }
    private void requireResource(Actor actor, UUID operation) {
        var lease = resourceLeases.get(operation);
        if (lease == null) throw new IllegalStateException("MAID_FARM_RESOURCE_LEASE_CHANGED");
        materials.requireNativeScope(actor, lease);
    }
    private void releaseResourceIfSettled(Actor actor, MaidFarmJournal.Operation operation) {
        if (!operation.settled()) return; var lease = resourceLeases.get(operation.plan().id());
        if (lease != null && resourceLeases.remove(operation.plan().id(), lease)) {
            try { materials.releaseNativeScope(actor, lease); }
            catch (RuntimeException failure) { resourceLeases.putIfAbsent(operation.plan().id(), lease); throw failure; }
        }
    }
    @Override public void close() {
        closed = true; Work work;
        while ((work = waits.poll()) != null) { work.ready().completeExceptionally(new TaskControl.Stopped("AI_STOPPING")); cleanup(work); }
        for (var accepted : active.values()) { accepted.control().stop("AI_STOPPING"); cleanup(accepted); }
        for (var witness : witnesses.values()) server.execute(witness.world()::close);
        for (var witness : recoveryWitnesses.values()) server.execute(witness.world()::close);
        recoveryWitnesses.clear(); recoveryProofs.clear(); activeRecoveries.clear();
        active.clear(); witnesses.clear();
    }
    private void requireContext(AgentTool.Context ctx) {
        if (closed) throw new IllegalStateException("AI_STOPPING");
        ctx.actor().requireAccess(ctx.workspace().owner()); ctx.control().check(); if (ctx.taskId() == null) throw new IllegalArgumentException("MAID_FARM_TASK_REQUIRED");
    }
    private void requireGeneration(MaidFarmPlan plan) { if (plan.generation() != generation.getAsLong()) throw new IllegalStateException("MAID_FARM_RESOURCES_RELOADED"); }
    public static JsonObject view(MaidFarmPlan plan) {
        var data = new JsonObject(); data.addProperty("planId", plan.id().toString()); data.addProperty("planHash", plan.hash()); data.addProperty("maidId", plan.maid().maidId().toString());
        data.addProperty("kind", plan.kind().name()); data.addProperty("x", plan.x()); data.addProperty("y", plan.y()); data.addProperty("z", plan.z());
        data.addProperty("sourceId", plan.sourceId().toString()); data.addProperty("sourceRevision", plan.sourceRevision()); data.addProperty("seedSlot", plan.seedSlot());
        data.add("crop", AiJson.GSON.toJsonTree(plan.crop())); data.add("soil", AiJson.GSON.toJsonTree(plan.soil()));
        if (plan.seedSlot() >= 0) data.add("realSeed", AiJson.GSON.toJsonTree(VirtualProcessingService.publicMaterial(plan.inventory().get(plan.seedSlot()).withCount(1))));
        data.addProperty("policy", "NATIVE_MAID_GAMEPLAY"); data.addProperty("processingCostWaiversApply", false); return data;
    }
    private static JsonObject status(MaidFarmJournal.Operation op) {
        var data = AiJson.GSON.toJsonTree(op).getAsJsonObject(); data.addProperty("settled", op.settled()); data.addProperty("historicalSnapshot", true);
        data.addProperty("processingCostWaiversApply", false); return data;
    }
    @FunctionalInterface private interface Action { AgentTool.Result run(AgentTool.Context ctx, JsonObject arguments) throws Exception; }
    private void tool(ToolRegistry tools, String name, String description, String properties, List<String> required, AgentTool.Risk risk, Action action) {
        var schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", JsonParser.parseString(properties));
        schema.add("required", AiJson.GSON.toJsonTree(required)); schema.addProperty("additionalProperties", false);
        tools.register(new DefinedTool(new AgentTool.Descriptor(name, description, schema, risk, risk == AgentTool.Risk.READ_ONLY ? "equipment.own.read" : "maid.farm.own",
                0, AgentTool.ExecutionThread.IO, 120000, true, ""), (ctx, args) -> { requireContext(ctx); return action.run(ctx, args); }));
    }
    private static String text(JsonObject args, String key) { return args.has(key) ? args.get(key).getAsString() : ""; }
    private static String safe(Throwable failure) { String code = failure.getMessage(); return code != null && code.matches("[A-Z0-9_]{1,100}") ? code : "MAID_FARM_NATIVE_FAILURE"; }
    private static AgentTool.Result result(boolean success, String code, String summary, JsonObject data) { return new AgentTool.Result(success, code, summary, data, "server-native-maid-farm-1.5.3", "", false); }
}
