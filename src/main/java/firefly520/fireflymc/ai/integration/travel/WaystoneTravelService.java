package firefly520.fireflymc.ai.integration.travel;

import com.google.gson.*;
import com.mojang.datafixers.util.Either;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.NativeSaveObserver;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.Function;
import net.minecraft.core.*;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;

/** Dedicated-only approved native travel. Async completion owns its evidence after the caller stops. */
public final class WaystoneTravelService implements AutoCloseable {
    private static final TicketType<UUID> TARGET_PREPARATION = TicketType.<UUID>create("fireflymc_waystone_target", Comparator.<UUID>naturalOrder(), 2400);
    private final MinecraftServer server;
    private final Predicate<ServerPlayer> authenticated;
    private final InstalledPermissionAdapters permissions;
    private final VirtualProcessingService dispatcher;
    private final NativeSaveObserver saves;
    private final NativeWaystoneBridge nativeBridge;
    private final Supplier<? extends CompletionStage<?>> preparationCheckpoint;
    private final UUID executionEpoch = UUID.randomUUID();
    private final Map<String, WaystoneTravelJournal> journals = new ConcurrentHashMap<>();
    record ArtifactIdentity(UUID id, String kind, String owner, UUID taskId, UUID serverId, UUID conversationId, long epoch, String hash) {
        boolean matches(Workspace workspace) { return owner.equals(workspace.owner()) && serverId.equals(workspace.metadata().serverInstanceId())
                && conversationId.equals(workspace.primaryConversationId()) && epoch == workspace.conversation().privacyEpoch(); }
    }
    private final Map<String, Map<UUID, ArtifactIdentity>> artifactIdentities = new ConcurrentHashMap<>();
    private final Map<UUID, Live> pending = new ConcurrentHashMap<>();
    private final Map<UUID, PreparationLive> pendingPreparations = new ConcurrentHashMap<>();
    private final Map<String, TargetLease> targetLeases = new HashMap<>(); // main-thread only; one small lease per owner
    private record TargetLease(UUID task, UUID target, UUID ticket, ServerLevel level, List<Long> chunks, long expires) {}
    private final ExecutorService evidence = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128),
            Thread.ofPlatform().daemon(true).name("fireflymc-travel-evidence").factory(), new ThreadPoolExecutor.AbortPolicy());
    private final AcceptedTravelEvidence acceptedEvidence = new AcceptedTravelEvidence();
    private volatile boolean closed, broken;

    public WaystoneTravelService(MinecraftServer server, Predicate<ServerPlayer> authenticated, InstalledPermissionAdapters permissions,
            VirtualProcessingService dispatcher, NativeSaveObserver saves) {
        this(server, authenticated, permissions, dispatcher, saves, () -> CompletableFuture.completedFuture(null));
    }
    /** The checkpoint only controls readiness; the same real main-thread authorization always follows. */
    public WaystoneTravelService(MinecraftServer server, Predicate<ServerPlayer> authenticated, InstalledPermissionAdapters permissions,
            VirtualProcessingService dispatcher, NativeSaveObserver saves, Supplier<? extends CompletionStage<?>> preparationCheckpoint) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        this.server = server; this.authenticated = authenticated; this.permissions = permissions;
        this.dispatcher = dispatcher; this.saves = saves; nativeBridge = new NativeWaystoneBridge(server);
        this.preparationCheckpoint = Objects.requireNonNull(preparationCheckpoint);
    }
    public synchronized WaystoneTravelJournal prepare(Workspace workspace) throws IOException {
        if (broken) throw new IllegalStateException("WAYSTONE_SERVICE_UNAVAILABLE");
        var found = journals.get(workspace.owner()); if (found != null) {
            if (!found.matches(workspace)) throw new SecurityException("WAYSTONE_WORKSPACE_IDENTITY_CHANGED");
            if (found.epoch() != workspace.conversation().privacyEpoch()) throw new IllegalStateException("WAYSTONE_PRIVACY_RESET_REQUIRED");
            return found;
        }
        if (closed) throw new IllegalStateException("WAYSTONE_SERVICE_UNAVAILABLE");
        try {
            var journal = new WaystoneTravelJournal(workspace); var owner = new Actor(workspace.owner(), true, false, 0);
            for (var op : journal.snapshot(owner)) {
                if (op.preparation()) publish(workspace, storedPreparation(workspace, owner, op.id(), op.planHash()));
                else publish(workspace, storedPlan(workspace, owner, op.id(), op.planHash()));
            }
            journals.put(workspace.owner(), journal); return journal;
        }
        catch (IOException | RuntimeException failure) { broken = true; throw failure; }
    }
    public void register(ToolRegistry tools) {
        tool(tools, "waystone_target_prepare_plan", "为本人原生已激活但未加载的普通目标生成仅准备区块的新审批。只读原生激活元数据和实际近处来源，固定原生最多3个区块；不读目标方块、不加载、不报价、不收费、不传送。准备之后必须重新生成真实费用旅行计划并另行批准。",
                "{\"sourceId\":{\"type\":\"string\"},\"targetId\":{\"type\":\"string\"}}", List.of("sourceId", "targetId"), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> result(true, "", "已固定仅准备目标区块的范围；尚未加载，未批准旅行或费用。", preparationView(preparationPlan(ctx, UUID.fromString(text(args, "sourceId")), UUID.fromString(text(args, "targetId"))))));
        tool(tools, "waystone_target_prepare_execute", "按固定审批仅加载指定最多3个已经生成且磁盘为FULL的区块；先流式检查Status，缺失/未完成生成则拒绝，不生成新区。只调用原生准备，不传送、不执行费用API、不查询容器。重鉴权后执行，加载后核对目标UUID和保护；FULL级临时保留最多120秒，之后另批准费用旅行。",
                "{\"preparationId\":{\"type\":\"string\"},\"preparationHash\":{\"type\":\"string\"}}", List.of("preparationId", "preparationHash"), AgentTool.Risk.WORLD_CHANGE,
                (ctx, args) -> executePreparation(ctx, loadPreparation(ctx.workspace(), ctx.actor(), UUID.fromString(text(args, "preparationId")), text(args, "preparationHash"))));
        tool(tools, "waystone_travel_plan", "规划从本人已激活且当前在8格内的普通指路石到本人已激活的原生可选目标。核对UUID、领地与原费用/冷却，不移动、不收费、不暗中加载。若目标未加载，先用waystone_target_prepare_plan/execute经独立审批准备已有区块，再回本工具生成真实费用新审批。仅本人普通石碑路线；物品、宠物、乘客、传送板/门需独立适配。",
                "{\"sourceId\":{\"type\":\"string\"},\"targetId\":{\"type\":\"string\"}}", List.of("sourceId", "targetId"), AgentTool.Risk.READ_ONLY,
                this::planningResult);
        tool(tools, "waystone_travel_execute", "执行固定版本的普通指路石旅行审批。原生扣除经验/物品并执行冷却；加工MATERIALS_ONLY不适用于旅行。重新检查认证、原生激活/来源距离、领地、费用、方块UUID及落点范围，等待异步真实结果与保存。重复operationId、未知或可能已收费的失败不能重试或假退款。",
                "{\"planId\":{\"type\":\"string\"},\"planHash\":{\"type\":\"string\"}}", List.of("planId", "planHash"), AgentTool.Risk.PLAYER_STATE,
                (ctx, args) -> execute(ctx, load(ctx.workspace(), ctx.actor(), UUID.fromString(text(args, "planId")), text(args, "planHash"))));
        tool(tools, "waystone_travel_status", "读取本人旅行操作实际原生结果、费用进入状态、前后快照与保存凭据。不会重放旅行、退款或清除不确定状态。",
                "{\"operationId\":{\"type\":\"string\"}}", List.of("operationId"), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> result(true, "", "本人旅行账本的实际记录。", AiJson.GSON.toJsonTree(prepare(ctx.workspace()).get(ctx.actor(), UUID.fromString(text(args, "operationId")))).getAsJsonObject()));
        tool(tools, "waystone_travel_recovery_preview", "仅在完整原生结果与真实保存凭据已到达时生成本人旅行复核。未知结果不提供可清除预览，费用不退款，不重放旅行。",
                "{\"operationId\":{\"type\":\"string\"}}", List.of("operationId"), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> result(true, "", "已固定实际旅行结果复核；接受结果仍须本人批准。", AiJson.GSON.toJsonTree(prepare(ctx.workspace()).recoveryReview(ctx.actor(), UUID.fromString(text(args, "operationId")))).getAsJsonObject()));
        tool(tools, "waystone_travel_reconcile", "接受经过新批准且有完整原生/保存证据的旅行结果。成功按实际完成结算；已收费失败按实际失败结算，不退款、不传送、不重试。未知结果永远不能通过此工具清除。",
                "{\"operationId\":{\"type\":\"string\"},\"revision\":{\"type\":\"integer\",\"minimum\":1},\"reviewHash\":{\"type\":\"string\"}}", List.of("operationId", "revision", "reviewHash"), AgentTool.Risk.PLAYER_STATE,
                (ctx, args) -> reconcile(ctx, UUID.fromString(text(args, "operationId")), args.get("revision").getAsLong(), text(args, "reviewHash")));
    }
    public WaystoneTravelPlan plan(AgentTool.Context ctx, UUID source, UUID target) throws Exception {
        requireContext(ctx); prepare(ctx.workspace());
        var facts = dispatcher.inspectOwned(ctx, () -> { var player = player(ctx.actor()); var route = nativeBridge.route(player, source, target); checkPermissions(player, route.from(), route.to()); return route; });
        var plan = new WaystoneTravelPlan(1, UUID.randomUUID(), ctx.workspace().metadata().serverInstanceId(), ctx.workspace().primaryConversationId(),
                ctx.actor().owner(), ctx.taskId(), ctx.workspace().conversation().privacyEpoch(), executionEpoch, facts.from(), facts.to(), facts.chunks(),
                facts.flags(), facts.requirement(), facts.description(), facts.affordable(), "").signed();
        synchronized (ctx.workspace().conversation()) {
            requireContext(ctx); plan.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch);
            if (Files.exists(ctx.workspace().artifact(plan.id()), LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("WAYSTONE_PLAN_ALREADY_EXISTS");
            AiJson.write(ctx.workspace().artifact(plan.id()), plan); var manifest = new JsonObject();
            manifest.addProperty("planId", plan.id().toString()); manifest.addProperty("planHash", plan.hash());
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "waystone_travel_plan", null, manifest, null)));
            publish(ctx.workspace(), plan);
        }
        return plan;
    }
    private AgentTool.Result planningResult(AgentTool.Context ctx, JsonObject args) throws Exception {
        UUID source = UUID.fromString(text(args, "sourceId")), target = UUID.fromString(text(args, "targetId"));
        try { return result(true, "", "已核验原生来源、目标和费用；旅行仍需工作台批准。", view(plan(ctx, source, target))); }
        catch (Exception failure) {
            if (!code(failure).equals("WAYSTONE_DESTINATION_NOT_LOADED")) throw failure;
            var data = new JsonObject(); data.addProperty("sourceId", source.toString()); data.addProperty("targetId", target.toString());
            data.addProperty("preparationPlanningTool", "waystone_target_prepare_plan"); data.addProperty("preparationExecutionTool", "waystone_target_prepare_execute");
            data.addProperty("maximumNativeDestinationChunks", 3); data.addProperty("separateTravelApprovalRequired", true); data.addProperty("travelDispatched", false);
            return result(false, "WAYSTONE_TARGET_PREPARATION_REQUIRED", "目标尚未加载，未报价、收费或移动。先规划并批准有界原生目标准备，再生成真实费用旅行计划并另行批准。", data);
        }
    }
    public WaystoneTravelPlan load(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        var plan = storedPlan(workspace, actor, id, hash); plan.requireIdentity(workspace, actor, executionEpoch); return plan;
    }
    WaystoneTravelPlan storedPlan(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) {
            boolean indexed = hash != null && hash.matches("[0-9a-f]{64}") && workspace.conversation().getSnapshot(0).events().stream().anyMatch(event ->
                    event.kind().equals("waystone_travel_plan") && event.data() != null && event.data().has("planId") && event.data().has("planHash")
                    && id.toString().equals(event.data().get("planId").getAsString()) && hash.equals(event.data().get("planHash").getAsString()));
            var path = workspace.artifact(id);
            if (!indexed || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 262144) throw new IllegalArgumentException("WAYSTONE_PLAN_NOT_FOUND");
            var plan = AiJson.GSON.fromJson(Files.readString(path), WaystoneTravelPlan.class);
            if (plan == null || !id.equals(plan.id()) || !hash.equals(plan.hash())) throw new SecurityException("WAYSTONE_TRAVEL_PLAN_CHANGED");
            if (workspace.conversation().getSnapshot(0).events().stream().noneMatch(event -> event.kind().equals("waystone_travel_plan")
                    && plan.taskId().equals(event.taskId()) && event.data() != null && event.data().has("planId") && event.data().has("planHash")
                    && id.toString().equals(event.data().get("planId").getAsString()) && hash.equals(event.data().get("planHash").getAsString())))
                throw new SecurityException("WAYSTONE_PLAN_TASK_CHANGED");
            plan.requireStorageIdentity(workspace, actor); return plan;
        }
    }
    public String review(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        return "仅本人旅行；保留原 Mod 费用、冷却及保护，不适用加工费用豁免。可能已收费的失败不会自动重试或退款。\n" + AiJson.GSON.toJson(view(load(workspace, actor, id, hash)));
    }
    public AgentTool.Result execute(AgentTool.Context ctx, WaystoneTravelPlan plan) throws Exception {
        requireContext(ctx); plan.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch);
        if (!plan.taskId().equals(ctx.taskId())) throw new SecurityException("WAYSTONE_TASK_MISMATCH");
        var journal = prepare(ctx.workspace());
        var duplicate = journal.snapshot(ctx.actor()).stream().filter(op -> op.id().equals(plan.id())).findFirst();
        if (duplicate.isPresent()) return result(false, "WAYSTONE_TRAVEL_ALREADY_RECORDED", "原操作已有记录，未重新收费或旅行。", AiJson.GSON.toJsonTree(duplicate.get()).getAsJsonObject());
        var before = dispatcher.inspectOwned(ctx, () -> { var p = player(ctx.actor()); validateRoute(plan, nativeBridge.route(p, plan.source().id(), plan.target().id())); checkPermissions(p, plan.source(), plan.target()); return nativeBridge.playerSnapshot(p); });
        journal.begin(ctx.actor(), plan, before);
        var live = new Live(ctx, plan, journal, before); synchronized (pending) {
                if (pending.size() + pendingPreparations.size() >= 4) { journal.rejectedBeforeDispatch(ctx.actor(), plan.id(), "WAYSTONE_CONCURRENCY_LIMIT"); throw new IllegalStateException("WAYSTONE_CONCURRENCY_LIMIT"); }
            pending.put(plan.id(), live);
        }
        var accepted = new CommittedCall<Live>();
        accepted.whenSettled((value, failure) -> {
            if (failure != null) {
                if (failure instanceof CommittedCall.Rejected || !live.nativeInvoked) recordRefusal(live, code(failure));
                else observeOnMain(live, null, failure);
            }
        });
        try {
            dispatcher.inspectOwned(ctx, () -> { accepted.run(ctx.control(), () -> launch(live)); return true; });
        } catch (Exception stopped) {
            accepted.rejectBeforeClaim(stopped);
            if (stopped instanceof TaskControl.Stopped interrupted) throw interrupted;
            throw stopped;
        }
        return awaitOutcome(ctx, plan.id());
    }
    private Live launch(Live live) throws Exception {
        var p = player(live.actor); var route = nativeBridge.route(p, live.plan.source().id(), live.plan.target().id());
        validateRoute(live.plan, route); checkPermissions(p, live.plan.source(), live.plan.target());
        if (!route.affordable()) throw new IllegalStateException("WAYSTONE_NATIVE_REQUIREMENTS_NOT_MET");
        saves.requireCapacity(playerStores(p));
        live.context = route.context(); live.failed = Objects.requireNonNull(nativeBridge.refusedResult(), "WAYSTONE_NATIVE_CANCELLATION_UNAVAILABLE");
        live.cancelledPreparation = nativeBridge.cancelledPreparation();
        live.playerAtFees = p;
        NativeWaystoneTravelHooks.register(live.context, live); live.nativeInvoked = true;
        var future = nativeBridge.start(live.context); live.nativeFuture = future;
        future.whenComplete((result, failure) -> observeOnMain(live, result, failure));
        return live;
    }
    private AgentTool.Result awaitOutcome(AgentTool.Context ctx, UUID id) throws Exception {
        requireContext(ctx); var journal = prepare(ctx.workspace()); var op = journal.get(ctx.actor(), id);
        if (!op.taskId().equals(ctx.taskId())) throw new SecurityException("WAYSTONE_TASK_MISMATCH");
        if (op.state() == WaystoneTravelJournal.State.DISPATCHING) {
            var live = pending.get(id); if (live == null) return result(false, "RECOVERY_REQUIRED", "原生旅行结果尚未确认，不能重试。", AiJson.GSON.toJsonTree(op).getAsJsonObject());
            throw deferred("WAYSTONE_NATIVE_PENDING", id, live.recorded, fresh -> awaitOutcome(fresh, id));
        }
        if (op.state() == WaystoneTravelJournal.State.RETURNED) {
            throw deferred("WAITING_WAYSTONE_SAVE", id, saves.awaitJournalled(id), fresh -> awaitOutcome(fresh, id));
        }
        return result(op.state() == WaystoneTravelJournal.State.COMPLETED, op.state() == WaystoneTravelJournal.State.COMPLETED ? ""
                : op.state() == WaystoneTravelJournal.State.REFUSED ? "WAYSTONE_NATIVE_REFUSED" : "RECOVERY_REQUIRED",
                op.state() == WaystoneTravelJournal.State.COMPLETED ? "原生指路石旅行已完成，实际费用、落点和玩家保存凭据已记录。"
                : op.state() == WaystoneTravelJournal.State.REFUSED ? "旅行已在实际收费前拒绝，没有重试或退款。" : "原生结果需核对；可能已发生费用或移动，未重试、退款或后台传送。",
                AiJson.GSON.toJsonTree(op).getAsJsonObject());
    }
    private AgentTool.Deferred deferred(String reason, UUID id, CompletionStage<?> ready, AgentTool.Continuation continuation) {
        var data = new JsonObject(); data.addProperty("operationId", id.toString()); data.addProperty("phase", reason);
        return new AgentTool.Deferred(reason, data, ready.handle((value, failure) -> null), continuation);
    }
    private void recordRefusal(Live live, String reason) {
        if (!live.observed.compareAndSet(false, true)) return;
        queueEvidence(live, () -> live.journal.rejectedBeforeDispatch(live.actor, live.plan.id(), reason));
    }
    private void observeOnMain(Live live, Object result, Throwable failure) {
        Runnable action = () -> {
            if (!live.observed.compareAndSet(false, true)) return;
            try {
                ServerPlayer p = server.getPlayerList().getPlayer(UUID.fromString(live.actor.owner()));
                if (p == null) p = live.playerAtFees;
                if (p == null) throw new IllegalStateException("WAYSTONE_OWNER_UNAVAILABLE_AFTER_NATIVE_RETURN");
                var after = nativeBridge.playerSnapshot(p);
                var observed = nativeBridge.outcome(result, p, live.plan);
                if (live.beforeFeesSnapshot != null) observed.data().add("immediatelyBeforeNativeFees", live.beforeFeesSnapshot.deepCopy());
                String reason = !live.reason.isEmpty() ? live.reason : failure != null ? code(failure) : observed.reason();
                boolean noEffects = !live.feesEntered && failure == null && observed.known() && after.equals(live.originalBefore);
                var receipt = noEffects ? null : saves.changedOrUnconfirmed(playerStores(p));
                queueEvidence(live, () -> {
                    live.journal.observed(live.actor, live.plan.id(), after, observed.data(), live.feesEntered,
                            observed.success(), failure == null && observed.known() && (noEffects || receipt != null)
                                    && (!live.feesEntered || observed.data().getAsJsonArray("entities").size() == 1), reason);
                    saves.record(live.plan.id(), receipt, proof -> live.journal.nativeSaved(live.actor, live.plan.id(), proof));
                });
            } catch (Exception | LinkageError unavailable) {
                queueEvidence(live, () -> live.journal.requireRecovery(live.actor, live.plan.id(), "WAYSTONE_NATIVE_EVIDENCE_UNAVAILABLE"));
            } finally {
                if (live.context != null) NativeWaystoneTravelHooks.closeContext(live.context);
                releaseTargetLease(live.actor.owner(), live.plan.target().id());
            }
        };
        if (server.isSameThread()) action.run(); else server.execute(action);
    }
    @FunctionalInterface private interface Evidence { void write() throws Exception; }
    private void queueEvidence(Live live, Evidence writer) {
        try {
            acceptedEvidence.submit(evidence, () -> {
                try { writer.write(); live.recorded.complete(null); }
                catch (Exception failure) { broken = true; live.recorded.completeExceptionally(failure); }
                finally { pending.remove(live.plan.id(), live); }
            });
        } catch (RejectedExecutionException stopped) { broken = true; live.recorded.completeExceptionally(stopped); }
    }
    private final class Live implements NativeWaystoneTravelHooks.Guard {
        final Workspace workspace; final Actor actor; final TaskControl control; final WaystoneTravelPlan plan; final WaystoneTravelJournal journal;
        final JsonObject originalBefore;
        final AtomicBoolean observed = new AtomicBoolean(); final CompletableFuture<Void> recorded = new CompletableFuture<>();
        volatile Object context, failed, cancelledPreparation; volatile ServerPlayer playerAtFees; volatile boolean nativeInvoked, feesEntered;
        volatile String reason = ""; volatile CompletableFuture<Object> nativeFuture;
        volatile JsonObject beforeFeesSnapshot;
        Live(AgentTool.Context ctx, WaystoneTravelPlan plan, WaystoneTravelJournal journal, JsonObject before) {
            workspace = ctx.workspace(); actor = ctx.actor(); control = ctx.control(); this.plan = plan; this.journal = journal; originalBefore = before.deepCopy();
        }
        public Object beforeFees(Object destination) {
            try {
                control.check(); if (closed || broken || !reason.isEmpty()) return failed;
                var p = player(actor); playerAtFees = p; checkPermissions(p, plan.source(), plan.target());
                plan.requireIdentity(workspace, actor, executionEpoch); nativeBridge.verifyStones(p, plan);
                validateRoute(plan, nativeBridge.route(p, plan.source().id(), plan.target().id())); nativeBridge.noAttachedContext(context);
                if (!nativeBridge.currentRequirement(context, p).equals(plan.requirements()) || !nativeBridge.heldRequirement(context, p).equals(plan.requirements()))
                    throw new IllegalStateException("WAYSTONE_REQUIREMENTS_CHANGED");
                nativeBridge.requireLanding(destination, plan); checkLanding(p, destination);
                beforeFeesSnapshot = nativeBridge.playerSnapshot(p); feesEntered = true; return null;
            } catch (Exception | LinkageError refused) { refuse(code(refused)); return failed; }
        }
        public boolean beforeEntity(Object event) {
            try {
                control.check(); if (closed || broken || !reason.isEmpty()) return false;
                var p = player(actor); var moved = (Entity) NativeWaystoneBridge.invoke(event, "getEntity", new Class<?>[0]);
                if (moved != p) throw new SecurityException("WAYSTONE_ADDITIONAL_ENTITIES_NEED_ADAPTER");
                NativeWaystoneBridge.noAttachedEntities(p);
                nativeBridge.verifyStones(p, plan);
                var level = (ServerLevel) NativeWaystoneBridge.invoke(event, "getTargetLevel", new Class<?>[0]);
                var pos = (Vec3) NativeWaystoneBridge.invoke(event, "getTargetPosition", new Class<?>[0]);
                nativeBridge.requireLanding(level, pos, plan);
                var permission = permissions.checkTravelDestination(p, level, BlockPos.containing(pos));
                if (!permission.allowed()) throw new SecurityException(permission.code());
                return true;
            } catch (Exception | LinkageError refused) { refuse(code(refused)); return false; }
        }
        public void refuse(String value) { if (reason.isEmpty()) reason = value; }
        public Set<ChunkPos> chunks() { var result = new HashSet<ChunkPos>(); plan.destinationChunks().forEach(value -> result.add(new ChunkPos(value))); return Set.copyOf(result); }
        public Object failedResult() { return failed; }
        public List<?> protectedPreparationTasks() {
            Function<Object, CompletableFuture<Object>> authorization = previous -> {
                if (previous instanceof Either<?, ?> either && either.right().isPresent()) return CompletableFuture.completedFuture(previous);
                var ready = new CompletableFuture<Object>();
                try { preparationCheckpoint.get().whenComplete((ignored, failure) -> server.execute(() -> {
                    try {
                        control.check(); if (closed || broken || failure != null || !reason.isEmpty()) { ready.complete(cancelledPreparation); return; }
                        var p = player(actor); plan.requireIdentity(workspace, actor, executionEpoch); nativeBridge.verifyStones(p, plan);
                        checkPermissions(p, plan.source(), plan.target()); ready.complete(previous);
                    } catch (Exception | LinkageError refused) { refuse(code(refused)); ready.complete(cancelledPreparation); }
                })); }
                catch (RuntimeException refused) { refuse(code(refused)); ready.complete(cancelledPreparation); }
                return ready;
            };
            return List.of(authorization);
        }
    }
    private void validateRoute(WaystoneTravelPlan plan, NativeWaystoneBridge.Route route) {
        if (!route.from().equals(plan.source()) || !route.to().equals(plan.target()) || !route.chunks().equals(plan.destinationChunks())
                || !route.flags().equals(plan.flags()) || !route.requirement().equals(plan.requirements())) throw new IllegalStateException("WAYSTONE_ROUTE_OR_REQUIREMENTS_CHANGED");
    }
    private List<NativeSaveObserver.Target> playerStores(ServerPlayer player) {
        return List.of(NativeSaveObserver.player(player),
                NativeSaveObserver.Target.file(player.getStats(), server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(player.getStringUUID() + ".json")),
                NativeSaveObserver.Target.file(player.getAdvancements(), server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR).resolve(player.getStringUUID() + ".json")));
    }
    public WaystoneTargetPreparation preparationPlan(AgentTool.Context ctx, UUID source, UUID target) throws Exception {
        requireContext(ctx);
        var facts = dispatcher.inspectOwned(ctx, () -> {
            var p = player(ctx.actor()); var value = nativeBridge.preparation(p, source, target); checkSourcePermission(p, value.from()); return value;
        });
        prepare(ctx.workspace());
        var plan = new WaystoneTargetPreparation(1, UUID.randomUUID(), ctx.workspace().metadata().serverInstanceId(), ctx.workspace().primaryConversationId(),
                ctx.actor().owner(), ctx.taskId(), ctx.workspace().conversation().privacyEpoch(), executionEpoch, facts.from(), facts.to(), facts.chunks(), "EXISTING_FULL_ONLY", "").signed();
        synchronized (ctx.workspace().conversation()) {
            requireContext(ctx); plan.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch);
            if (Files.exists(ctx.workspace().artifact(plan.id()), LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("WAYSTONE_PREPARATION_ALREADY_EXISTS");
            AiJson.write(ctx.workspace().artifact(plan.id()), plan); var index = new JsonObject(); index.addProperty("preparationId", plan.id().toString()); index.addProperty("preparationHash", plan.hash());
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "waystone_target_preparation", null, index, null)));
            publish(ctx.workspace(), plan);
        }
        return plan;
    }
    public WaystoneTargetPreparation loadPreparation(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        var plan = storedPreparation(workspace, actor, id, hash); plan.requireIdentity(workspace, actor, executionEpoch); return plan;
    }
    WaystoneTargetPreparation storedPreparation(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) {
            boolean indexed = hash != null && hash.matches("[0-9a-f]{64}") && workspace.conversation().getSnapshot(0).events().stream().anyMatch(event ->
                    event.kind().equals("waystone_target_preparation") && event.data() != null && event.data().has("preparationId") && event.data().has("preparationHash")
                    && id.toString().equals(event.data().get("preparationId").getAsString()) && hash.equals(event.data().get("preparationHash").getAsString()));
            var path = workspace.artifact(id);
            if (!indexed || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 131072) throw new IllegalArgumentException("WAYSTONE_PREPARATION_NOT_FOUND");
            var plan = AiJson.GSON.fromJson(Files.readString(path), WaystoneTargetPreparation.class);
            if (plan == null || !id.equals(plan.id()) || !hash.equals(plan.hash())) throw new SecurityException("WAYSTONE_PREPARATION_CHANGED");
            if (workspace.conversation().getSnapshot(0).events().stream().noneMatch(event -> event.kind().equals("waystone_target_preparation")
                    && plan.taskId().equals(event.taskId()) && event.data() != null && event.data().has("preparationId") && event.data().has("preparationHash")
                    && id.toString().equals(event.data().get("preparationId").getAsString()) && hash.equals(event.data().get("preparationHash").getAsString())))
                throw new SecurityException("WAYSTONE_PREPARATION_TASK_CHANGED");
            plan.requireStorageIdentity(workspace, actor); return plan;
        }
    }
    public String reviewPreparation(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        return "仅加载已知目标的原生最多3个已有FULL区块；磁盘缺失/尚未生成则拒绝，不生成新区、不收费、不移动玩家、不查询容器。临时FULL保留120秒；旅行费用和移动必须另行新预览批准。\n"
                + AiJson.GSON.toJson(preparationView(loadPreparation(workspace, actor, id, hash)));
    }
    public AgentTool.Result executePreparation(AgentTool.Context ctx, WaystoneTargetPreparation plan) throws Exception {
        requireContext(ctx); plan.requireIdentity(ctx.workspace(), ctx.actor(), executionEpoch);
        if (!plan.taskId().equals(ctx.taskId())) throw new SecurityException("WAYSTONE_TASK_MISMATCH");
        var journal = prepare(ctx.workspace());
        if (journal.snapshot(ctx.actor()).stream().anyMatch(op -> op.id().equals(plan.id())))
            return result(false, "WAYSTONE_PREPARATION_ALREADY_RECORDED", "该准备操作已有记录，未重放加载。", AiJson.GSON.toJsonTree(journal.get(ctx.actor(), plan.id())).getAsJsonObject());
        dispatcher.inspectOwned(ctx, () -> { var p = player(ctx.actor()); validatePreparation(plan, nativeBridge.preparation(p, plan.source().id(), plan.target().id())); checkSourcePermission(p, plan.source()); return true; });
        journal.beginPreparation(ctx.actor(), plan); var live = new PreparationLive(ctx, plan, journal);
        synchronized (pending) {
            if (pending.size() + pendingPreparations.size() >= 4) { journal.rejectedBeforeDispatch(ctx.actor(), plan.id(), "WAYSTONE_CONCURRENCY_LIMIT"); throw new IllegalStateException("WAYSTONE_CONCURRENCY_LIMIT"); }
            pendingPreparations.put(plan.id(), live);
        }
        var accepted = new CommittedCall<PreparationLive>();
        accepted.whenSettled((value, failure) -> {
            if (failure != null) {
                if (failure instanceof CommittedCall.Rejected || !live.invoked) recordPreparationRefusal(live, code(failure));
                else observePreparation(live, null, failure);
            }
        });
        try { dispatcher.inspectOwned(ctx, () -> { accepted.run(ctx.control(), () -> launchPreparation(live)); return true; }); }
        catch (Exception failure) { accepted.rejectBeforeClaim(failure); throw failure; }
        return awaitPreparation(ctx, plan.id());
    }
    private PreparationLive launchPreparation(PreparationLive live) throws Exception {
        var p = player(live.actor); var facts = nativeBridge.preparation(p, live.plan.source().id(), live.plan.target().id());
        validatePreparation(live.plan, facts); checkSourcePermission(p, live.plan.source());
        var level = targetLevel(live.plan.target().dimension()); var scans = new ArrayList<CompletableFuture<Void>>();
        for (long packed : live.plan.chunks()) {
            var fields = new CollectFields(new FieldSelector(StringTag.TYPE, "Status"));
            scans.add(level.getChunkSource().chunkMap.chunkScanner().scanChunk(new ChunkPos(packed), fields).thenRun(() -> {
                if (!(fields.getResult() instanceof CompoundTag data) || !"minecraft:full".equals(data.getString("Status")))
                    throw new IllegalStateException("WAYSTONE_EXISTING_FULL_CHUNK_REQUIRED");
            }));
        }
        CompletableFuture.allOf(scans.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            if (failure != null) recordPreparationRefusal(live, code(failure));
            else {
                try { acceptedEvidence.submit(evidence, () -> {
                    try {
                        live.journal.preparationLoadingIntent(live.actor, live.plan.id());
                        server.execute(() -> {
                            try { loadVerifiedPreparation(live); }
                            catch (Exception | LinkageError stopped) { if (live.invoked) observePreparation(live, null, stopped); else recordPreparationRefusal(live, code(stopped)); }
                        });
                    } catch (Exception stopped) { recordPreparationRefusal(live, code(stopped)); }
                }); } catch (RejectedExecutionException stopped) { live.recorded.completeExceptionally(stopped); broken = true; }
            }
        });
        return live;
    }
    private void loadVerifiedPreparation(PreparationLive live) throws Exception {
        live.control.check(); if (closed || broken) throw new TaskControl.Stopped("SHUTDOWN");
        var p = player(live.actor); live.plan.requireIdentity(live.workspace, live.actor, executionEpoch);
        var facts = nativeBridge.preparation(p, live.plan.source().id(), live.plan.target().id()); validatePreparation(live.plan, facts); checkSourcePermission(p, live.plan.source());
        if (!targetLeases.containsKey(live.actor.owner()) && targetLeases.size() >= 4) throw new IllegalStateException("WAYSTONE_PREPARATION_LEASE_LIMIT");
        live.context = facts.context(); live.failed = Objects.requireNonNull(nativeBridge.refusedResult());
        NativeWaystoneTravelHooks.register(live.context, live);
        live.invoked = true; retainTarget(live.actor.owner(), live.plan);
        nativeBridge.prepareTarget(live.context, facts.target()).whenComplete((value, failure) -> observePreparation(live, value, failure));
    }
    private void recordPreparationRefusal(PreparationLive live, String reason) {
        if (!live.observed.compareAndSet(false, true)) return;
        preparationEvidence(live, () -> live.journal.rejectedBeforeDispatch(live.actor, live.plan.id(), reason));
    }
    private void observePreparation(PreparationLive live, Object value, Throwable failure) {
        Runnable action = () -> {
            if (!live.observed.compareAndSet(false, true)) return;
            try {
                var after = new JsonObject(); after.add("approvedChunks", AiJson.GSON.toJsonTree(live.plan.chunks()));
                after.addProperty("nativeLoadingReturned", failure == null && value instanceof Either<?, ?>);
                boolean loaded = failure == null && value instanceof Either<?, ?> either && either.right().isEmpty();
                boolean target = loaded && nativeBridge.loadedTargetMatches(live.plan.target()); after.addProperty("targetUuidVerified", target);
                boolean ready = false; String reason = !live.reason.isEmpty() ? live.reason : failure != null ? code(failure) : !loaded ? "WAYSTONE_NATIVE_PREPARATION_REFUSED" : "";
                try {
                    live.control.check(); var p = player(live.actor); live.plan.requireIdentity(live.workspace, live.actor, executionEpoch);
                    validatePreparation(live.plan, nativeBridge.preparation(p, live.plan.source().id(), live.plan.target().id()));
                    checkSourcePermission(p, live.plan.source());
                    var targetLevel = targetLevel(live.plan.target().dimension());
                    var permission = permissions.checkTravelDestination(p, targetLevel, new BlockPos(live.plan.target().x(), live.plan.target().y(), live.plan.target().z()));
                    if (!permission.allowed()) throw new SecurityException(permission.code());
                    ready = loaded && target && reason.isEmpty() && !closed;
                } catch (Exception | LinkageError stopped) { reason = code(stopped); }
                after.addProperty("readyForFreshTravelPlan", ready); after.addProperty("noTravelDispatched", true);
                boolean finalReady = ready; String finalReason = reason;
                if (failure != null) preparationEvidence(live, () -> live.journal.requireRecovery(live.actor, live.plan.id(), "WAYSTONE_PREPARATION_RESULT_UNKNOWN"));
                else preparationEvidence(live, () -> live.journal.preparationObserved(live.actor, live.plan.id(), after, finalReady, finalReason));
                if (!ready) releaseTargetLease(live.actor.owner(), live.plan.target().id());
            } catch (Exception | LinkageError unavailable) {
                preparationEvidence(live, () -> live.journal.requireRecovery(live.actor, live.plan.id(), "WAYSTONE_PREPARATION_EVIDENCE_UNAVAILABLE"));
            } finally { if (live.context != null) NativeWaystoneTravelHooks.closeContext(live.context); }
        };
        if (server.isSameThread()) action.run(); else server.execute(action);
    }
    private void preparationEvidence(PreparationLive live, Evidence writer) {
        try { acceptedEvidence.submit(evidence, () -> { try { writer.write(); live.recorded.complete(null); } catch (Exception failure) { broken = true; live.recorded.completeExceptionally(failure); } finally { pendingPreparations.remove(live.plan.id(), live); } }); }
        catch (RejectedExecutionException stopped) { broken = true; live.recorded.completeExceptionally(stopped); }
    }
    private AgentTool.Result awaitPreparation(AgentTool.Context ctx, UUID id) throws Exception {
        requireContext(ctx); var op = prepare(ctx.workspace()).get(ctx.actor(), id);
        if (!op.preparation() || !Objects.equals(ctx.taskId(), op.taskId())) throw new SecurityException("WAYSTONE_TASK_MISMATCH");
        if (op.state() == WaystoneTravelJournal.State.DISPATCHING) {
            var live = pendingPreparations.get(id);
            if (live != null) throw deferred("WAYSTONE_TARGET_PREPARATION_PENDING", id, live.recorded, fresh -> awaitPreparation(fresh, id));
        }
        return result(op.state() == WaystoneTravelJournal.State.PREPARED, op.state() == WaystoneTravelJournal.State.PREPARED ? ""
                : op.state() == WaystoneTravelJournal.State.REFUSED ? "WAYSTONE_PREPARATION_REFUSED" : "RECOVERY_REQUIRED",
                op.state() == WaystoneTravelJournal.State.PREPARED ? "原生目标准备与UUID/保护核对已完成；未收费、未旅行。请重新生成当前真实费用的旅行计划并另行批准。临时就绪可失效，不代表永久区块保存。"
                : "目标准备未就绪；未派发旅行、费用或容器访问。已有加载结果保持原生状态，未重放。", AiJson.GSON.toJsonTree(op).getAsJsonObject());
    }
    private final class PreparationLive implements NativeWaystoneTravelHooks.Guard {
        final Workspace workspace; final Actor actor; final TaskControl control; final WaystoneTargetPreparation plan; final WaystoneTravelJournal journal;
        final CompletableFuture<Void> recorded = new CompletableFuture<>(); final AtomicBoolean observed = new AtomicBoolean();
        volatile Object context, failed; volatile boolean invoked; volatile String reason = "";
        PreparationLive(AgentTool.Context ctx, WaystoneTargetPreparation plan, WaystoneTravelJournal journal) { workspace = ctx.workspace(); actor = ctx.actor(); control = ctx.control(); this.plan = plan; this.journal = journal; }
        public Object beforeFees(Object destination) { refuse("WAYSTONE_PREPARATION_CANNOT_DISPATCH_TRAVEL"); return failed; }
        public boolean beforeEntity(Object event) { refuse("WAYSTONE_PREPARATION_CANNOT_DISPATCH_TRAVEL"); return false; }
        public void refuse(String value) { if (reason.isEmpty()) reason = value; }
        public Object failedResult() { return failed; }
        public Set<ChunkPos> chunks() { var result = new HashSet<ChunkPos>(); plan.chunks().forEach(value -> result.add(new ChunkPos(value))); return Set.copyOf(result); }
    }
    private void validatePreparation(WaystoneTargetPreparation plan, NativeWaystoneBridge.Preparation facts) {
        if (!plan.source().equals(facts.from()) || !plan.target().equals(facts.to()) || !plan.chunks().equals(facts.chunks())) throw new IllegalStateException("WAYSTONE_PREPARATION_CHANGED");
    }
    private void checkSourcePermission(ServerPlayer p, WaystoneTravelPlan.Stone source) {
        if (!permissions.checkBooleanPermission(p, "fireflymc.ai.travel.waystones", true).allowed()) throw new SecurityException("WAYSTONE_TRAVEL_PERMISSION_DENIED");
        var allowed = permissions.checkContainer(p, p.serverLevel(), new BlockPos(source.x(), source.y(), source.z()), Direction.UP);
        if (!allowed.allowed()) throw new SecurityException(allowed.code());
    }
    private ServerLevel targetLevel(String dimension) {
        for (var level : server.getAllLevels()) if (level.dimension().location().toString().equals(dimension)) return level;
        throw new IllegalStateException("WAYSTONE_DIMENSION_UNAVAILABLE");
    }
    private void retainTarget(String owner, WaystoneTargetPreparation plan) {
        releaseTargetLease(owner, null);
        if (targetLeases.size() >= 4) throw new IllegalStateException("WAYSTONE_PREPARATION_LEASE_LIMIT");
        var level = targetLevel(plan.target().dimension());
        var lease = new TargetLease(plan.taskId(), plan.target().id(), plan.id(), level, plan.chunks(), System.nanoTime() + TimeUnit.SECONDS.toNanos(120));
        targetLeases.put(owner, lease);
        // Distance zero retains only FULL chunks, not block/entity ticking or a permanent force-load.
        for (long packed : lease.chunks()) level.getChunkSource().addRegionTicket(TARGET_PREPARATION, new ChunkPos(packed), 0, lease.ticket());
    }
    private void releaseTargetLease(String owner, UUID target) {
        var lease = targetLeases.get(owner); if (lease == null || target != null && !lease.target().equals(target)) return;
        targetLeases.remove(owner);
        for (long packed : lease.chunks()) lease.level().getChunkSource().removeRegionTicket(TARGET_PREPARATION, new ChunkPos(packed), 0, lease.ticket());
    }
    public void tick() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        for (var entry : List.copyOf(targetLeases.entrySet())) {
            var p = server.getPlayerList().getPlayer(UUID.fromString(entry.getKey()));
            if (closed || System.nanoTime() >= entry.getValue().expires() || p == null || !authenticated.test(p)) releaseTargetLease(entry.getKey(), null);
        }
    }
    public void cancelTask(String owner, UUID task) {
        pending.values().stream().filter(live -> live.actor.owner().equals(owner) && live.plan.taskId().equals(task)).forEach(live -> live.control.stop("CANCELLED"));
        pendingPreparations.values().stream().filter(live -> live.actor.owner().equals(owner) && live.plan.taskId().equals(task)).forEach(live -> live.control.stop("CANCELLED"));
        Runnable release = () -> { var lease = targetLeases.get(owner); if (lease != null && lease.task().equals(task)) releaseTargetLease(owner, null); };
        if (server.isSameThread()) release.run(); else server.execute(release);
    }
    private JsonObject preparationView(WaystoneTargetPreparation plan) {
        var data = AiJson.GSON.toJsonTree(plan).getAsJsonObject(); data.addProperty("preparationId", plan.id().toString()); data.addProperty("preparationHash", plan.hash());
        data.addProperty("scope", "BOUNDED_NATIVE_LOADING_ONLY_NO_TRAVEL_OR_FEES"); data.addProperty("maximumTemporaryLeaseSeconds", 120); return data;
    }
    private void checkLanding(ServerPlayer player, Object destination) throws ReflectiveOperationException {
        var level = (ServerLevel) NativeWaystoneBridge.invoke(destination, "level", new Class<?>[0]);
        var pos = (Vec3) NativeWaystoneBridge.invoke(destination, "location", new Class<?>[0]);
        var allowed = permissions.checkTravelDestination(player, level, BlockPos.containing(pos));
        if (!allowed.allowed()) throw new SecurityException(allowed.code());
    }
    private void checkPermissions(ServerPlayer player, WaystoneTravelPlan.Stone source, WaystoneTravelPlan.Stone target) {
        if (!permissions.checkBooleanPermission(player, "fireflymc.ai.travel.waystones", true).allowed()) throw new SecurityException("WAYSTONE_TRAVEL_PERMISSION_DENIED");
        for (var stone : List.of(source, target)) {
            var level = server.getAllLevels().iterator(); ServerLevel found = null;
            while (level.hasNext()) { var value = level.next(); if (value.dimension().location().toString().equals(stone.dimension())) { found = value; break; } }
            if (found == null) throw new IllegalStateException("WAYSTONE_DIMENSION_UNAVAILABLE");
            var pos = new BlockPos(stone.x(), stone.y(), stone.z());
            var allowed = stone == source ? permissions.checkContainer(player, found, pos, Direction.UP)
                    : permissions.checkTravelDestination(player, found, pos);
            if (!allowed.allowed()) throw new SecurityException(allowed.code());
        }
    }
    private ServerPlayer player(Actor actor) {
        if (!server.isSameThread() || !server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_SERVER_THREAD_REQUIRED");
        var p = server.getPlayerList().getPlayer(UUID.fromString(actor.owner()));
        if (p == null || !authenticated.test(p)) throw new SecurityException("PLAYER_NOT_AUTHENTICATED"); actor.requireAccess(p.getUUID().toString()); return p;
    }
    private void requireContext(AgentTool.Context ctx) {
        ctx.actor().requireAccess(ctx.workspace().owner()); ctx.control().check();
        if (ctx.taskId() == null || closed || broken) throw new IllegalStateException("WAYSTONE_SERVICE_UNAVAILABLE");
    }
    public boolean requiresRecovery(Workspace workspace, Actor actor, UUID task) {
        try { return publishedOperations(workspace, actor).stream().anyMatch(op -> op.taskId().equals(task) && !op.settled()); }
        catch (RuntimeException unavailable) { return true; }
    }
    public boolean settledForTask(Workspace workspace, Actor actor, UUID task) {
        try { return publishedOperations(workspace, actor).stream().filter(op -> op.taskId().equals(task)).allMatch(op -> op.settled() && op.saved()); }
        catch (RuntimeException unavailable) { return false; }
    }
    public List<WaystoneTravelJournal.Operation> operations(Workspace workspace, Actor actor) throws IOException { return prepare(workspace).snapshot(actor); }
    public List<JsonObject> recoveryRows(Workspace workspace, Actor actor, UUID taskId) throws IOException {
        var journal = prepare(workspace); var rows = new ArrayList<JsonObject>();
        for (var op : journal.snapshot(actor)) if (op.taskId().equals(taskId)) {
            var row = new JsonObject(); row.addProperty("kind", "TRAVEL"); row.addProperty("operationId", op.id().toString());
            row.addProperty("taskId", op.taskId().toString()); row.addProperty("state", op.state().name()); row.addProperty("reason", op.reason());
            row.addProperty("operationKind", op.preparation() ? "TARGET_PREPARATION" : "ORDINARY_WAYSTONE_TRAVEL");
            row.addProperty("preparationOnly", op.preparation()); row.addProperty("nativeSaveRequired", !op.preparation());
            row.addProperty("nativeSaveConfirmed", !op.preparation() && op.saved()); row.addProperty("settled", op.settled() && op.saved());
            row.addProperty("nativeTravelSucceeded", !op.preparation() && op.nativeSuccess()); row.addProperty("feesEntered", op.feesEntered());
            row.addProperty("revision", op.revision());
            var actions = new JsonArray();
            try { journal.recoveryReview(actor, op.id()); actions.add("TRAVEL_RECONCILE"); }
            catch (IllegalStateException unavailable) { row.addProperty("recoveryReadOnly", !op.settled()); }
            row.add("actions", actions);
            row.addProperty("scope", op.preparation() ? "本次原生临时区块准备；不是旅行成功或永久区块保存，当前是否仍加载需重新核验。"
                    : "本人普通指路石原生结果；可能已收费的失败不重试、不退款，未知结果不能清除。");
            rows.add(row);
        }
        return List.copyOf(rows);
    }
    public JsonObject recoveryResources(Workspace workspace, Actor actor, UUID id) throws IOException {
        var journal = prepare(workspace); synchronized (journal) { var op = journal.get(actor, id); var data = new JsonObject();
        data.addProperty("kind", "TRAVEL"); data.addProperty("version", op.version()); data.addProperty("taskId", op.taskId().toString());
        data.addProperty("operationId", op.id().toString()); data.addProperty("revision", op.revision()); data.addProperty("planHash", op.planHash());
        data.addProperty("operationKind", op.preparation() ? "TARGET_PREPARATION" : "ORDINARY_WAYSTONE_TRAVEL");
        data.addProperty("nativeSaveRequired", !op.preparation()); data.add("operation", AiJson.GSON.toJsonTree(op));
        data.addProperty("completeOperationHistoryDigest", journal.operationHistoryDigest(actor, id));
        return data; }
    }
    public boolean operationSettled(Workspace workspace, Actor actor, UUID id) {
        try { return publishedOperations(workspace, actor).stream().filter(op -> op.id().equals(id)).anyMatch(op -> op.settled() && op.saved()); }
        catch (RuntimeException unavailable) { return false; }
    }
    public WaystoneTravelJournal.RecoveryReview recoveryPreview(Workspace workspace, Actor actor, UUID id) throws IOException {
        return prepare(workspace).recoveryReview(actor, id);
    }
    public void validateRecovery(Workspace workspace, Actor actor, UUID id, long revision, String hash) throws IOException {
        var current = recoveryPreview(workspace, actor, id);
        if (current.revision() != revision || !current.reviewHash().equals(hash)) throw new IllegalStateException("WAYSTONE_RECOVERY_REVIEW_CHANGED");
    }
    public AgentTool.Result reconcile(AgentTool.Context ctx, UUID id, long revision, String hash) throws IOException {
        requireContext(ctx); var journal = prepare(ctx.workspace());
        if (!Objects.equals(ctx.taskId(), journal.get(ctx.actor(), id).taskId())) throw new SecurityException("WAYSTONE_TASK_MISMATCH");
        var accepted = journal.reconcile(ctx.actor(), id, revision, hash);
        return result(true, "", "仅按真实凭据接受了原生结果，没有重放或退款。", AiJson.GSON.toJsonTree(accepted).getAsJsonObject());
    }
    public boolean resourcesSettled(Workspace workspace, Actor actor) {
        try { return publishedOperations(workspace, actor).stream().allMatch(op -> op.settled() && op.saved()); }
        catch (RuntimeException unavailable) { return false; }
    }
    public String exportJournal(Workspace workspace, Actor actor) throws IOException { return prepare(workspace).exportJournal(actor); }
    private void publish(Workspace workspace, WaystoneTravelPlan plan) {
        publish(workspace, new ArtifactIdentity(plan.id(), "TRAVEL", plan.owner(), plan.taskId(), plan.serverInstanceId(), plan.conversationId(), plan.privacyEpoch(), plan.hash()));
    }
    private void publish(Workspace workspace, WaystoneTargetPreparation plan) {
        publish(workspace, new ArtifactIdentity(plan.id(), "PREPARATION", plan.owner(), plan.taskId(), plan.serverInstanceId(), plan.conversationId(), plan.privacyEpoch(), plan.hash()));
    }
    private void publish(Workspace workspace, ArtifactIdentity reference) {
        if (!reference.matches(workspace)) throw new SecurityException("WAYSTONE_ARTIFACT_IDENTITY_CHANGED");
        artifactIdentities.compute(workspace.owner(), (owner, current) -> {
            var next = new HashMap<UUID, ArtifactIdentity>(); if (current != null) current.values().stream().filter(value -> value.matches(workspace)).forEach(value -> next.put(value.id(), value));
            var previous = next.putIfAbsent(reference.id(), reference);
            if (previous != null && !previous.equals(reference)) throw new SecurityException("WAYSTONE_ARTIFACT_IDENTITY_CHANGED");
            return Map.copyOf(next);
        });
    }
    /** No monitor or filesystem: Runtime may call this while owning the primary-conversation lock. */
    List<WaystoneTravelJournal.Operation> publishedOperations(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner()); if (broken) throw new IllegalStateException("WAYSTONE_JOURNAL_REQUIRES_REVIEW");
        var journal = journals.get(workspace.owner());
        if (journal == null || !journal.matches(workspace)) throw new IllegalStateException("WAYSTONE_WORKSPACE_NOT_PREPARED");
        return journal.snapshot(actor);
    }
    ArtifactIdentity publishedArtifact(Workspace workspace, Actor actor, UUID id) {
        actor.requireAccess(workspace.owner()); var value = artifactIdentities.getOrDefault(workspace.owner(), Map.of()).get(id);
        if (value == null || !value.matches(workspace)) throw new IllegalArgumentException("WAYSTONE_ARTIFACT_NOT_PREPARED"); return value;
    }
    /** Erasure is performed by the private workspace service; this only forgets already-settled runtime caches. */
    public void forgetErased(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner()); var journal = journals.get(workspace.owner());
        if (journal == null) {
            if (!artifactIdentities.getOrDefault(workspace.owner(), Map.of()).isEmpty()) throw new IllegalStateException("WAYSTONE_UNSETTLED_OBLIGATIONS");
            return;
        }
        if (!journal.matches(workspace) || workspace.conversation().privacyEpoch() <= journal.epoch()) throw new IllegalStateException("WAYSTONE_ERASURE_NOT_CONFIRMED");
        if (journal.publishedObligations().stream().anyMatch(op -> !op.settled() || !op.saved())
                || pending.values().stream().anyMatch(live -> live.actor.owner().equals(workspace.owner()))
                || pendingPreparations.values().stream().anyMatch(live -> live.actor.owner().equals(workspace.owner()))) throw new IllegalStateException("WAYSTONE_UNSETTLED_OBLIGATIONS");
        journals.remove(workspace.owner(), journal); artifactIdentities.remove(workspace.owner());
        Runnable release = () -> releaseTargetLease(workspace.owner(), null); if (server.isSameThread()) release.run(); else server.execute(release);
    }
    public String reviewRecovery(Workspace workspace, Actor actor, UUID id, long revision, String hash) throws IOException {
        var journal = prepare(workspace); var preview = journal.recoveryReview(actor, id);
        if (preview.revision() != revision || !preview.reviewHash().equals(hash)) throw new IllegalStateException("WAYSTONE_RECOVERY_REVIEW_CHANGED");
        return "按已保存的完整原生结果结算本条旅行；已发生费用保留。不再次传送、不退款、不接受未知副作用。\n"
                + AiJson.GSON.toJson(journal.get(actor, id));
    }
    private JsonObject view(WaystoneTravelPlan plan) { var data = AiJson.GSON.toJsonTree(plan).getAsJsonObject(); data.addProperty("planId", plan.id().toString()); data.addProperty("planHash", plan.hash()); data.addProperty("costPolicy", "NATIVE_TRAVEL_COSTS_AND_COOLDOWNS"); return data; }
    private static AgentTool.Result result(boolean ok, String code, String summary, JsonObject data) { return new AgentTool.Result(ok, code, summary, data, WaystoneTravelPlan.ADAPTER, "", false); }
    private static String text(JsonObject object, String name) { return object.get(name).getAsString(); }
    private static String code(Throwable failure) { while (failure.getCause() != null && failure.getCause() != failure) failure = failure.getCause(); var text = failure.getMessage(); return text != null && text.matches("[A-Z0-9_]{1,96}") ? text : "WAYSTONE_NATIVE_FAILURE"; }
    @FunctionalInterface private interface Call { AgentTool.Result run(AgentTool.Context ctx, JsonObject args) throws Exception; }
    private void tool(ToolRegistry tools, String name, String description, String properties, List<String> required, AgentTool.Risk risk, Call call) {
        var schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", JsonParser.parseString(properties)); schema.add("required", AiJson.GSON.toJsonTree(required)); schema.addProperty("additionalProperties", false);
        tools.register(new DefinedTool(new AgentTool.Descriptor(name, description, schema, risk, "travel.own", 0, AgentTool.ExecutionThread.IO, 120000, true, ""), call::run));
    }
    @Override public void close() {
        closed = true; pending.values().forEach(live -> live.control.stop("SHUTDOWN")); pendingPreparations.values().forEach(live -> live.control.stop("SHUTDOWN"));
        pending.values().forEach(live -> { if (live.context != null) NativeWaystoneTravelHooks.closeContext(live.context); });
        pendingPreparations.values().forEach(live -> { if (live.context != null) NativeWaystoneTravelHooks.closeContext(live.context); });
        if (server.isSameThread()) { for (var owner : List.copyOf(targetLeases.keySet())) releaseTargetLease(owner, null); }
        else server.execute(() -> { for (var owner : List.copyOf(targetLeases.keySet())) releaseTargetLease(owner, null); });
    }
    public void finalizePendingClose() throws IOException, InterruptedException {
        close();
        if (!awaitStopped(10000)) throw new IOException("WAYSTONE_ACCEPTED_EVIDENCE_NOT_DRAINED");
        for (var live : List.copyOf(pending.values())) if (live.journal.get(live.actor, live.plan.id()).after().isEmpty())
            live.journal.requireRecovery(live.actor, live.plan.id(), "SHUTDOWN_NATIVE_TRAVEL_UNCONFIRMED");
        for (var live : List.copyOf(pendingPreparations.values())) if (live.journal.get(live.actor, live.plan.id()).after().isEmpty())
            live.journal.requireRecovery(live.actor, live.plan.id(), "SHUTDOWN_TARGET_PREPARATION_UNCONFIRMED");
        evidence.shutdown(); if (!evidence.awaitTermination(10000, TimeUnit.MILLISECONDS)) throw new IOException("WAYSTONE_EVIDENCE_NOT_DRAINED");
    }
    /** Background-only bounded wait for known IO writers, never for native work needing another tick. */
    public boolean awaitStopped(long millis) throws InterruptedException { return acceptedEvidence.await(millis); }
}
