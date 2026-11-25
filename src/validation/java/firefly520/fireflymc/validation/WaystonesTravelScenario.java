package firefly520.fireflymc.validation;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.AiServerService;
import firefly520.fireflymc.ai.agent.AgentRuntime;
import firefly520.fireflymc.ai.context.ContextCompactor;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.integration.travel.*;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelGateway;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.model.ModelMessage.ToolCall;
import firefly520.fireflymc.ai.processing.*;
import firefly520.fireflymc.ai.recovery.*;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import net.minecraft.core.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.fml.ModList;

/** Real pinned native API, fees, callbacks and canonical player saves, in an isolated dedicated world. */
public final class WaystonesTravelScenario implements AutoCloseable {
    private enum Mode { NORMAL, CANCEL_PREPARE, DENY_AUTH_PREPARE, EXTRA_CHUNK, EXTRA_TASK, CANCEL_ENTITY, CHANGE_LANDING }
    private final MinecraftServer server;
    private final WorkspaceManager workspaces;
    private final NativeSaveObserver saves;
    private final MaterialLedger materials;
    private final MaterialInventoryService inventory;
    private final FluidInventoryService fluids;
    private final FluidProcessingService mixed;
    private final VirtualProcessingService dispatcher;
    private final WaystoneTravelService service;
    private final ToolRegistry tools = new ToolRegistry();
    private final ExecutorService io = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon(true).name("waystone-fixture-io-", 0).factory());
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<Session> sessions = new ArrayList<>();
    private final Set<UUID> denied = ConcurrentHashMap.newKeySet();
    private final List<String> passed = new ArrayList<>();
    private final AtomicReference<Throwable> hookFailure = new AtomicReference<>();
    private final JsonObject evidence = new JsonObject();
    private final CompletableFuture<JsonObject> result;
    private final Object nativeTeleports;
    private final List<?> originalRequirements;
    private final boolean originalCosts, originalCooldowns;
    private volatile Mode mode = Mode.NORMAL;
    private volatile Session active;
    private volatile TaskControl activeControl;
    private int preparationTaskCalls;
    private final int fixtureX = 4096 + Math.floorMod(UUID.randomUUID().hashCode(), 10000) * 1024;
    private int nextCase;

    public WaystonesTravelScenario(MinecraftServer server) throws Exception {
        if (!server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_TEST_REQUIRED");
        this.server = server; saves = NativeSaveObserver.forServer(server);
        var path = Files.createTempDirectory(server.getWorldPath(LevelResource.ROOT), "ai4-waystone-travel-");
        workspaces = new WorkspaceManager(path); materials = new MaterialLedger(path);
        var permissionAdapters = new InstalledPermissionAdapters(server);
        inventory = new MaterialInventoryService(server, this::authenticated, permissionAdapters);
        fluids = new FluidInventoryService(server, inventory, permissionAdapters);
        dispatcher = new VirtualProcessingService(server, inventory, materials, () -> 1);
        mixed = new FluidProcessingService(server, inventory, fluids, materials, new FluidLedger(path), dispatcher, () -> 1);
        service = new WaystoneTravelService(server, this::authenticated, permissionAdapters, dispatcher, saves); service.register(tools);
        var config = type("net.blay09.mods.waystones.config.WaystonesConfig").getMethod("getActive").invoke(null);
        nativeTeleports = config.getClass().getField("teleports").get(config);
        originalRequirements = (List<?>) nativeTeleports.getClass().getField("warpRequirements").get(nativeTeleports);
        originalCosts = nativeTeleports.getClass().getField("enableCosts").getBoolean(nativeTeleports);
        originalCooldowns = nativeTeleports.getClass().getField("enableCooldowns").getBoolean(nativeTeleports);
        nativeTeleports.getClass().getField("warpRequirements").set(nativeTeleports, List.of("add_xp_cost(5)", "add_cooldown(ai_validation, 60)"));
        nativeTeleports.getClass().getField("enableCosts").setBoolean(nativeTeleports, true);
        nativeTeleports.getClass().getField("enableCooldowns").setBoolean(nativeTeleports, true);
        installListeners();
        result = CompletableFuture.supplyAsync(() -> {
            var report = new JsonObject(); long started = System.nanoTime();
            try { run(); report.addProperty("status", "PASSED"); }
            catch (Throwable failure) { failure.printStackTrace(); report.addProperty("status", "FAILED"); report.addProperty("error", failure.toString()); }
            report.add("passedChecks", AiJson.GSON.toJsonTree(passed)); report.add("nativeEvidence", evidence);
            report.addProperty("durationMillis", (System.nanoTime() - started) / 1_000_000);
            report.addProperty("scope", "Actual Waystones 21.1.37/Balm 21.0.63, dedicated authenticated mock network players and native 5-XP-point/60-second controlled requirements; no real clients, model requests, cross-JVM or full-pack claim. Fixture blocks/configuration are created only in this isolated test world; original native config restored on close.");
            return report;
        }, action -> Thread.ofPlatform().daemon(true).name("waystone-native-validation").start(action));
    }
    private void run() throws Exception {
        var owner = main(() -> new Session(100)); var other = main(() -> new Session(100));
        var ctx = context(owner); var plan = service.plan(ctx, owner.sourceId(), owner.targetId());
        check(plan.requirements().contains("\"points\":5") && plan.requirements().contains("\"seconds\":60"), "native-config-resolves-actual-XP-and-cooldown-fees");
        var before = main(() -> owner.player.position()); int xp = main(() -> owner.player.totalExperience);
        check(main(() -> owner.player.position().equals(before) && owner.player.totalExperience == xp), "travel-preview-does-not-charge-or-move-owner");
        expect(() -> service.plan(context(other), owner.sourceId(), owner.targetId()), "WAYSTONE_NOT_ACTIVATED_BY_OWNER");
        var arguments = execution(plan);
        var denied = call(owner, ctx, "waystone_travel_execute", arguments, false);
        check(!denied.success() && denied.errorCode().equals("EXPLICIT_APPROVAL_REQUIRED") && service.operations(owner.workspace, owner.actor).isEmpty()
                && main(() -> owner.player.position().equals(before) && owner.player.totalExperience == xp), "native-travel-needs-fixed-own-approval-before-intent");
        active = owner; mode = Mode.NORMAL;
        var completed = call(owner, ctx, "waystone_travel_execute", arguments, true);
        check(completed.success(), "actual-native-waystone-travel-completes-after-canonical-player-save");
        check(main(() -> owner.player.totalExperience) == xp - 5 && main(() -> owner.player.position().distanceToSqr(before)) > 100,
                "native-XP-fee-is-really-paid-and-actual-player-moves");
        var operation = service.operations(owner.workspace, owner.actor).getFirst();
        check(operation.state() == WaystoneTravelJournal.State.COMPLETED && operation.saved() && operation.feesEntered(), "native-rich-result-and-real-save-complete-the-owned-journal");
        lockFreeOriginalReference(owner, ctx.taskId(), "waystone_travel_execute", arguments);
        controllerReopenReference(owner, ctx.taskId(), plan, arguments);
        var duplicate = call(owner, context(owner, plan.taskId()), "waystone_travel_execute", arguments, true);
        check(!duplicate.success() && duplicate.errorCode().equals("WAYSTONE_TRAVEL_ALREADY_RECORDED") && main(() -> owner.player.totalExperience) == xp - 5,
                "duplicate-operation-cannot-charge-or-teleport-again");
        var reverse = context(owner); var cooling = service.plan(reverse, owner.targetId(), owner.sourceId());
        check(!cooling.affordableAtPreview(), "native-cooldown-remains-active-after-AI-travel");
        check(!call(owner, reverse, "waystone_travel_execute", execution(cooling), true).success() && main(() -> owner.player.totalExperience) == xp - 5,
                "native-cooldown-refusal-does-not-charge-again");
        var poor = main(() -> new Session(0)); var poorCtx = context(poor); var poorPlan = service.plan(poorCtx, poor.sourceId(), poor.targetId());
        check(!poorPlan.affordableAtPreview() && !call(poor, poorCtx, "waystone_travel_execute", execution(poorPlan), true).success()
                && main(() -> poor.player.totalExperience) == 0, "native-fee-shortage-is-not-a-processing-cost-exemption");
        for (var failure : List.of(Mode.EXTRA_CHUNK, Mode.EXTRA_TASK, Mode.DENY_AUTH_PREPARE, Mode.CANCEL_PREPARE, Mode.CANCEL_ENTITY, Mode.CHANGE_LANDING)) rejectedNative(failure);
        unloadedTarget();
        crossDimension();
        nativeProtectionAndRank();
        closeDuringNativePreparation();
        sourceChange();
        evidence.addProperty("nativeFeePointsPerTravel", 5); evidence.addProperty("nativeCooldownSeconds", 60);
        evidence.addProperty("thirdPartyPreparationTasksExecuted", preparationTaskCalls);
        check(hookFailure.get() == null, "native-fixture-listeners-had-no-hidden-failures");
    }
    private void rejectedNative(Mode failure) throws Exception {
        var session = main(() -> new Session(100)); var ctx = context(session); var plan = service.plan(ctx, session.sourceId(), session.targetId());
        var before = main(() -> session.player.position()); int xp = main(() -> session.player.totalExperience);
        var extraChunk = new ChunkPos(session.destination.offset(1024, 0, 0));
        if (failure == Mode.EXTRA_CHUNK) check(main(() -> server.overworld().getChunkSource().getChunkNow(extraChunk.x, extraChunk.z)) == null,
                "unapproved-extra-chunk-starts-unloaded");
        active = session; activeControl = ctx.control(); mode = failure;
        ToolCall original = failure == Mode.CANCEL_ENTITY ? recordOriginalCall(session, ctx, execution(plan)) : null;
        try {
            try {
                if (original == null) call(session, ctx, "waystone_travel_execute", execution(plan), true);
                else callBound(session, ctx, original, execution(plan), true);
            }
            catch (TaskControl.Stopped stopped) { if (failure != Mode.CANCEL_PREPARE) throw stopped; }
        } finally { mode = Mode.NORMAL; denied.remove(session.player.getUUID()); activeControl = null; }
        await(() -> service.operations(session.workspace, session.actor).stream().anyMatch(op -> !op.after().isEmpty() || op.state() == WaystoneTravelJournal.State.REFUSED), "real-native-refusal-recorded-" + failure);
        var op = service.operations(session.workspace, session.actor).getFirst();
        check(main(() -> session.player.position().equals(before)), "native-refusal-never-moves-player-" + failure);
        if (failure == Mode.CANCEL_ENTITY || failure == Mode.CHANGE_LANDING) {
            check(op.state() == WaystoneTravelJournal.State.RECOVERY_REQUIRED && op.feesEntered() && main(() -> session.player.totalExperience) == xp - 5,
                    "post-fee-native-failure-keeps-real-charges-and-recovery-" + failure);
            if (failure == Mode.CANCEL_ENTITY) directWorkbenchRecovery(session, ctx.taskId(), plan.id(), xp - 5);
            else {
                save(); saves.awaitJournalled(plan.id()).toCompletableFuture().get(10, TimeUnit.SECONDS);
                var review = service.prepare(session.workspace).recoveryReview(session.actor, plan.id());
                var args = new JsonObject(); args.addProperty("operationId", plan.id().toString()); args.addProperty("revision", review.revision()); args.addProperty("reviewHash", review.reviewHash());
                check(call(session, context(session, ctx.taskId()), "waystone_travel_reconcile", args, true).success()
                        && service.prepare(session.workspace).get(session.actor, plan.id()).state() == WaystoneTravelJournal.State.FAILED
                        && main(() -> session.player.totalExperience) == xp - 5, "positive-failure-review-only-accepts-actual-result-no-refund-or-retry-" + failure);
            }
        } else {
            check(!op.feesEntered() && main(() -> session.player.totalExperience) == xp && op.state() == WaystoneTravelJournal.State.REFUSED,
                    "pre-fee-refusal-has-no-charge-or-replay-" + failure);
            if (failure == Mode.EXTRA_TASK) check(preparationTaskCalls == 0, "unapproved-third-party-preparation-task-never-runs");
            if (failure == Mode.EXTRA_CHUNK) check(main(() -> server.overworld().getChunkSource().getChunkNow(extraChunk.x, extraChunk.z)) == null,
                    "unapproved-extra-chunk-is-never-loaded-by-native-preparation");
        }
    }
    private ToolCall recordOriginalCall(Session session, AgentTool.Context ctx, JsonObject arguments) throws Exception {
        var call = new ToolCall(UUID.randomUUID().toString(), "waystone_travel_execute", arguments.toString());
        String digest = ToolPolicyEngine.digest(session.actor.owner(), call);
        var task = session.workspace.conversation().task(ctx.taskId()).progress(1, 0, 0, 0, List.of(call), 0, digest, true, true)
                .update(AgentTask.State.EXECUTING, "NATIVE_WAYSTONE_FIXTURE_DISPATCH");
        var start = new JsonObject(); start.addProperty("name", call.name()); start.addProperty("risk", "PLAYER_STATE"); start.addProperty("inputDigest", digest);
        session.workspace.conversation().append(List.of(Draft.message(task.id(), new ModelMessage("assistant", "", List.of(call), null, null), null),
                new Draft(task.id(), "tool_start", null, start, call.id()), Draft.checkpoint(task)));
        return call;
    }
    private void directWorkbenchRecovery(Session session, UUID taskId, UUID operationId, int expectedXp) throws Exception {
        var task = session.workspace.conversation().task(taskId).update(AgentTask.State.RECOVERY_REQUIRED, "NATIVE_WAYSTONE_CHARGED_FAILURE");
        session.workspace.conversation().append(List.of(Draft.checkpoint(task)));
        var module = new WaystoneRecoveryModule(service); var models = new AtomicInteger(); var replays = new AtomicInteger(); var acknowledged = new AtomicInteger();
        var callbackFailure = new AtomicReference<Throwable>();
        Function<String, Actor> resolver = id -> {
            try { return main(() -> { var p = server.getPlayerList().getPlayer(UUID.fromString(id)); return p == null ? new Actor(id, false, false, 0) : Actor.player(p.getUUID(), authenticated(p), true, 0); }); }
            catch (Exception failure) { throw new CompletionException(failure); }
        };
        Predicate<UUID> settledTask = id -> service.settledForTask(session.workspace, session.actor, id)
                && RecoveryTaskEvidence.allSettled(session.workspace, session.actor, id, materials, mixed,
                    call -> module.settledCall(session.workspace, session.actor, id, call));
        var environment = new AgentRuntime.Environment() {
            public Actor actor(String owner) { return resolver.apply(owner); }
            public boolean enabled(String name) { return tools.get(name) != null; }
            public boolean recoverySettled(WorkspaceManager.Workspace workspace, UUID id) { return workspace.owner().equals(session.actor.owner()) && settledTask.test(id); }
            public boolean requiresRecovery(WorkspaceManager.Workspace workspace, UUID id) { return service.requiresRecovery(workspace, new Actor(workspace.owner(), true, false, 0), id); }
            public CompletableFuture<AgentTool.Result> execute(AgentTool tool, AgentTool.Context context, JsonObject args, String approval, ToolCall call) {
                replays.incrementAndGet(); return CompletableFuture.failedFuture(new IllegalStateException("WORKBENCH_MUST_NOT_REPLAY_OR_REQUEST_MODEL"));
            }
            public void changed(WorkspaceManager.Workspace workspace, AgentTask value, String notice) {}
        };
        ModelGateway model = (request, control) -> { models.incrementAndGet(); throw new IllegalStateException("WORKBENCH_MUST_NOT_REQUEST_MODEL"); };
        var runtime = new AgentRuntime(workspaces, new TaskScheduler(1, 8, 16), model, tools,
                new ContextCompactor(new ContextCompactor.Settings(32000, 512, 1024, 512, .75, 2), "waystone-native-direct-recovery"),
                new AgentRuntime.Limits(8, 8, 200000, 120000, 512, true, false), environment, new SecretFilter(List.of()));
        var bridge = new GameToolBridge(server, resolver, name -> tools.get(name) != null, () -> false, io);
        var recovery = new RecoveryWorkbenchService(materials, mixed, dispatcher, tools, bridge, saves, resolver, name -> tools.get(name) != null,
                (workspace, id) -> {
                    if (!workspace.owner().equals(session.actor.owner()) || !settledTask.test(id)) return;
                    try { runtime.acknowledgeRecovery(resolver.apply(workspace.owner()), id); acknowledged.incrementAndGet(); }
                    catch (Exception failure) { callbackFailure.compareAndSet(null, failure); }
                });
        recovery.registerModule(RecoveryWorkbenchService.Action.TRAVEL_RECONCILE, module); UUID connection = UUID.randomUUID();
        try {
            var unsaved = recovery.view(session.workspace, session.actor, taskId, 0);
            check(unsaved.getAsJsonArray("operations").asList().stream().anyMatch(value -> value.getAsJsonObject().get("kind").getAsString().equals("TRAVEL")
                    && value.getAsJsonObject().getAsJsonArray("actions").isEmpty()), "direct-workbench-unsaved-charged-failure-has-no-clearing-action");
            expect(() -> runtime.acknowledgeRecovery(session.actor, taskId), "RECOVERY_EVIDENCE_INCOMPLETE");
            check(acknowledged.get() == 0 && models.get() == 0 && replays.get() == 0, "native-unsaved-result-cannot-ACK-or-wake-model");
            save(); saves.awaitJournalled(operationId).toCompletableFuture().get(10, TimeUnit.SECONDS);
            var view = recovery.view(session.workspace, session.actor, taskId, 0);
            check(view.getAsJsonArray("operations").asList().stream().anyMatch(value -> value.getAsJsonObject().getAsJsonArray("actions").asList().stream()
                    .anyMatch(action -> action.getAsString().equals("TRAVEL_RECONCILE"))), "real-native-player-stats-advancement-save-enables-direct-review");
            var preview = recovery.preview(session.workspace, session.actor, connection, taskId, RecoveryWorkbenchService.Action.TRAVEL_RECONCILE, operationId, null);
            UUID token = UUID.fromString(preview.get("token").getAsString()); String digest = preview.get("digest").getAsString();
            expect(() -> recovery.confirm(session.workspace, session.actor, UUID.randomUUID(), token, digest), "RECOVERY_PREVIEW_STALE");
            var queued = recovery.confirm(session.workspace, session.actor, connection, token, digest);
            expect(() -> recovery.confirm(session.workspace, session.actor, connection, token, digest), "RECOVERY_PREVIEW_STALE");
            check(queued.get("queued").getAsBoolean() && main(() -> session.player.totalExperience) == expectedXp,
                    "direct-workbench-confirm-only-queues-same-owned-native-failure-settlement");
            await(() -> acknowledged.get() == 1 || callbackFailure.get() != null, "direct-travel-review-reaches-real-runtime-positive-verifier");
            check(callbackFailure.get() == null && service.prepare(session.workspace).get(session.actor, operationId).state() == WaystoneTravelJournal.State.FAILED
                    && session.workspace.conversation().task(taskId).state() == AgentTask.State.FAILED && models.get() == 0 && replays.get() == 0
                    && main(() -> session.player.totalExperience) == expectedXp, "direct-travel-review-accepts-actual-failure-no-refund-or-original-tool-replay");
            check(session.workspace.conversation().getSnapshot(0).events().stream().anyMatch(event -> taskId.equals(event.taskId()) && event.kind().equals("tool_result")),
                    "actual-runtime-ACK-backfills-original-travel-tool-transaction");
        } finally {
            recovery.finalizePendingClose(); if (!recovery.awaitStopped(10000)) throw new IllegalStateException("TRAVEL_RECOVERY_SHUTDOWN_TIMEOUT");
            runtime.close(); if (!runtime.awaitStopped(10000)) throw new IllegalStateException("TRAVEL_RUNTIME_SHUTDOWN_TIMEOUT"); runtime.finalizePendingClose();
        }
    }
    private void sourceChange() throws Exception {
        var session = main(() -> new Session(100)); var ctx = context(session); var plan = service.plan(ctx, session.sourceId(), session.targetId());
        main(() -> { api().getMethod("deactivateWaystone", ServerPlayer.class, waystone()).invoke(null, session.player, session.target); return true; });
        expect(() -> call(session, ctx, "waystone_travel_execute", execution(plan), true), "WAYSTONE_NOT_ACTIVATED_BY_OWNER");
        check(main(() -> session.player.totalExperience) == 100, "native-deactivation-invalidates-fixed-preview-before-fee");
    }
    private void crossDimension() throws Exception {
        var session = main(() -> new Session(100, true)); var ctx = context(session);
        var plan = service.plan(ctx, session.sourceId(), session.targetId());
        check(plan.source().dimension().equals("minecraft:overworld") && plan.target().dimension().equals("minecraft:the_nether")
                && main(() -> session.player.serverLevel() == server.overworld()), "cross-dimension-preview-queries-real-target-without-moving-player");
        active = session; mode = Mode.NORMAL;
        var actual = call(session, ctx, "waystone_travel_execute", execution(plan), true);
        check(actual.success() && main(() -> session.player.serverLevel() == session.destinationLevel && session.player.totalExperience == 95),
                "actual-native-cross-dimension-waystone-travel-preserves-fee-and-real-player");
        check(service.prepare(session.workspace).get(session.actor, plan.id()).saved(), "native-cross-dimension-player-stats-advancement-saves-confirmed");
    }
    private void closeDuringNativePreparation() throws Exception {
        var session = main(() -> new Session(100)); var context = context(session); var gate = new CompletableFuture<Void>(); var entered = new CountDownLatch(1);
        var controller = main(() -> new WaystoneTravelService(server, this::authenticated, new InstalledPermissionAdapters(server), dispatcher, saves,
                () -> { entered.countDown(); return gate; }));
        var registry = new ToolRegistry(); controller.register(registry);
        try {
            var plan = controller.plan(context, session.sourceId(), session.targetId()); var args = execution(plan);
            var call = recordOriginalCall(session, context, args);
            var bridge = new GameToolBridge(server, id -> {
                try { return main(() -> { var p = server.getPlayerList().getPlayer(UUID.fromString(id)); return p == null ? new Actor(id, false, false, 0) : Actor.player(p.getUUID(), authenticated(p), true, 0); }); }
                catch (Exception failure) { throw new CompletionException(failure); }
            }, name -> registry.get(name) != null, () -> false, io);
            AgentTool.Deferred wait;
            try {
                var reply = bridge.execute(registry.get(call.name()), context, args, ToolPolicyEngine.digest(session.actor.owner(), call), call).get(15, TimeUnit.SECONDS);
                throw new IllegalStateException("NATIVE_PREPARATION_DID_NOT_WAIT:" + reply.errorCode());
            } catch (ExecutionException wrapped) {
                Throwable cause = wrapped; while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                if (!(cause instanceof AgentTool.Deferred deferred)) throw wrapped; wait = deferred;
            }
            check(entered.await(5, TimeUnit.SECONDS) && wait.reason().equals("WAYSTONE_NATIVE_PENDING") && !wait.readiness().toCompletableFuture().isDone(),
                    "real-native-Prepare-chain-remains-pending-on-controlled-authorization-checkpoint");
            var position = main(() -> session.player.position());
            main(() -> { controller.close(); return true; });
            check(controller.awaitStopped(250) && !wait.readiness().toCompletableFuture().isDone(),
                    "close-and-bounded-accepted-IO-wait-never-depend-on-an-unfinished-native-future");
            gate.complete(null); wait.readiness().toCompletableFuture().get(10, TimeUnit.SECONDS);
            var actual = controller.operations(session.workspace, session.actor).getFirst();
            check(actual.state() == WaystoneTravelJournal.State.REFUSED && !actual.feesEntered()
                    && main(() -> session.player.totalExperience == 100 && session.player.position().equals(position)),
                    "closed-native-context-keeps-refusal-guard-until-late-native-result-no-fee-or-movement");
            check(!actual.nativeResult().isEmpty() && actual.nativeResult().get("knownNativeOutcome").getAsBoolean(),
                    "late-real-native-refusal-is-durably-recorded-after-controller-close");
            check(controller.awaitStopped(1000), "late-native-refusal-accepted-writer-is-drained-before-privacy-action");
            acknowledgeClosedRefusal(controller, session, context.taskId(), call.id());
            expect(() -> { controller.forgetErased(session.workspace, session.actor); return true; }, "WAYSTONE_ERASURE_NOT_CONFIRMED");
            var journal = session.workspace.directory().resolve("travel/journal.jsonl");
            session.workspace.conversation().erase(session.workspace.conversation().revision()); controller.forgetErased(session.workspace, session.actor);
            check(Files.isRegularFile(journal), "forgetErased-forgets-only-settled-cache-and-preserves-existing-journal-file");
            var reopened = main(() -> new WaystoneTravelService(server, this::authenticated, new InstalledPermissionAdapters(server), dispatcher, saves));
            try { expect(() -> reopened.prepare(session.workspace), "WAYSTONE_JOURNAL_INVALID"); }
            finally { main(() -> { reopened.close(); return true; }); reopened.finalizePendingClose(); }
        } finally {
            gate.complete(null); main(() -> { controller.close(); return true; }); controller.finalizePendingClose();
        }
    }
    private void acknowledgeClosedRefusal(WaystoneTravelService controller, Session session, UUID taskId, String callId) throws Exception {
        var task = session.workspace.conversation().task(taskId).update(AgentTask.State.RECOVERY_REQUIRED, "CLOSED_NATIVE_REFUSAL_AWAITING_VERIFIED_TRANSACTION_CLOSE");
        session.workspace.conversation().append(List.of(Draft.checkpoint(task))); var module = new WaystoneRecoveryModule(controller); var models = new AtomicInteger();
        var environment = new AgentRuntime.Environment() {
            public Actor actor(String id) {
                try { return main(() -> { var p = server.getPlayerList().getPlayer(UUID.fromString(id)); return p == null ? new Actor(id, false, false, 0) : Actor.player(p.getUUID(), authenticated(p), true, 0); }); }
                catch (Exception failure) { throw new CompletionException(failure); }
            }
            public boolean enabled(String name) { return tools.get(name) != null; }
            public boolean recoverySettled(WorkspaceManager.Workspace workspace, UUID id) {
                return workspace.owner().equals(session.actor.owner()) && controller.settledForTask(workspace, session.actor, id)
                        && RecoveryTaskEvidence.allSettled(workspace, session.actor, id, materials, mixed, call -> module.settledCall(workspace, session.actor, id, call));
            }
            public CompletableFuture<AgentTool.Result> execute(AgentTool tool, AgentTool.Context context, JsonObject args, String approval, ToolCall call) {
                return CompletableFuture.failedFuture(new IllegalStateException("CLOSED_REFUSAL_MUST_NOT_REPLAY"));
            }
            public void changed(WorkspaceManager.Workspace workspace, AgentTask value, String notice) {}
        };
        ModelGateway model = (request, control) -> { models.incrementAndGet(); throw new IllegalStateException("CLOSED_REFUSAL_MUST_NOT_REQUEST_MODEL"); };
        var runtime = new AgentRuntime(workspaces, new TaskScheduler(1, 8, 16), model, tools,
                new ContextCompactor(new ContextCompactor.Settings(32000, 512, 1024, 512, .75, 2), "waystone-closed-native-refusal"),
                new AgentRuntime.Limits(8, 8, 200000, 120000, 512, true, false), environment, new SecretFilter(List.of()));
        try {
            runtime.acknowledgeRecovery(session.actor, taskId);
            check(session.workspace.conversation().task(taskId).state().terminal() && session.workspace.conversation().getSnapshot(0).events().stream()
                    .anyMatch(event -> taskId.equals(event.taskId()) && event.kind().equals("tool_result") && callId.equals(event.toolTransactionId())) && models.get() == 0,
                    "verified-late-refusal-closes-real-original-transaction-before-explicit-erasure");
        } finally { runtime.close(); if (!runtime.awaitStopped(10000)) throw new IllegalStateException("CLOSED_REFUSAL_RUNTIME_SHUTDOWN_TIMEOUT"); runtime.finalizePendingClose(); }
    }
    private void nativeProtectionAndRank() throws Exception {
        evidence.addProperty("actualOpacLoaded", ModList.get().isLoaded("openpartiesandclaims"));
        evidence.addProperty("actualFtbRanksLoaded", ModList.get().isLoaded("ftbranks"));
        if (ModList.get().isLoaded("openpartiesandclaims")) {
            var owner = main(() -> new Session(100, true)); var other = main(() -> new Session(100));
            boolean waypointDenied = main(() -> {
                var adapters = new InstalledPermissionAdapters(server); check(adapters.probe().protection().callable(), "actual-OPAC-interface-is-callable-for-native-travel");
                Class<?> api = type("xaero.pac.common.server.api.OpenPACServerAPI"), claimsType = type("xaero.pac.common.server.claims.api.IServerClaimsManagerAPI");
                Object opac = api.getMethod("get", MinecraftServer.class).invoke(null, server), claims = api.getMethod("getServerClaimsManager").invoke(opac);
                var pos = owner.destination.offset(2, 0, 2); var level = owner.destinationLevel; var dim = level.dimension().location();
                var claim = claimsType.getMethod("claim", ResourceLocation.class, UUID.class, int.class, int.class, int.class, boolean.class);
                var unclaim = claimsType.getMethod("unclaim", ResourceLocation.class, int.class, int.class);
                if (claimsType.getMethod("get", ResourceLocation.class, BlockPos.class).invoke(claims, dim, pos) != null) throw new IllegalStateException("TRAVEL_FIXTURE_CHUNK_ALREADY_CLAIMED");
                var previous = level.getBlockState(pos); level.setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
                try {
                    if (claim.invoke(claims, dim, other.player.getUUID(), 0, pos.getX() >> 4, pos.getZ() >> 4, false) == null) throw new IllegalStateException("TRAVEL_NATIVE_CLAIM_NOT_CREATED");
                    var denied = adapters.checkTravelDestination(owner.player, level, pos);
                    check(!denied.allowed() && denied.origin() == InstalledPermissionAdapters.Origin.PROTECTION_API && owner.player.serverLevel() == server.overworld(),
                            "real-OPAC-foreign-destination-claim-is-queried-cross-dimension-without-moving-player");
                    check(!adapters.checkContainer(owner.player, level, pos, Direction.UP).allowed(), "travel-destination-query-does-not-relax-ordinary-container-world-guard");
                    var nativeWaystone = adapters.checkTravelDestination(owner.player, level, owner.destination);
                    evidence.add("nativeOpacWaystoneRule", AiJson.GSON.toJsonTree(nativeWaystone));
                    // Waystones may be a public-use native exception. Respect the native answer rather than invent a stricter claim policy.
                    evidence.addProperty("nativeForeignWaystoneInteractionAllowed", nativeWaystone.allowed());
                    return !nativeWaystone.allowed();
                } finally { unclaim.invoke(claims, dim, pos.getX() >> 4, pos.getZ() >> 4); level.setBlockAndUpdate(pos, previous); }
            });
            if (waypointDenied) {
                var beforeClaim = context(owner); var route = service.plan(beforeClaim, owner.sourceId(), owner.targetId());
                Class<?> api = type("xaero.pac.common.server.api.OpenPACServerAPI"), claimsType = type("xaero.pac.common.server.claims.api.IServerClaimsManagerAPI");
                Object claims = main(() -> api.getMethod("getServerClaimsManager").invoke(api.getMethod("get", MinecraftServer.class).invoke(null, server)));
                var dimension = owner.destinationLevel.dimension().location(); var chunk = new ChunkPos(owner.destination);
                main(() -> { claimsType.getMethod("claim", ResourceLocation.class, UUID.class, int.class, int.class, int.class, boolean.class)
                        .invoke(claims, dimension, other.player.getUUID(), 0, chunk.x, chunk.z, false); return true; });
                try {
                    expect(() -> call(owner, beforeClaim, "waystone_travel_execute", execution(route), true), "CLAIM_DENIED");
                    check(service.operations(owner.workspace, owner.actor).isEmpty() && main(() -> owner.player.totalExperience == 100 && owner.player.serverLevel() == server.overworld()),
                            "new-foreign-target-claim-revokes-approved-cross-dimension-trip-before-intent-or-fee");
                } finally { main(() -> { claimsType.getMethod("unclaim", ResourceLocation.class, int.class, int.class).invoke(claims, dimension, chunk.x, chunk.z); return true; }); }
            }
            Object data = main(() -> type("xaero.pac.common.server.player.data.api.ServerPlayerDataAPI").getMethod("from", ServerPlayer.class).invoke(null, owner.player));
            var dataType = type("xaero.pac.common.server.player.data.ServerPlayerData");
            boolean before = main(() -> (boolean) type("xaero.pac.common.server.player.data.api.ServerPlayerDataAPI").getMethod("isClaimsAdminMode").invoke(data));
            try {
                main(() -> { dataType.getMethod("setClaimsAdminMode", boolean.class).invoke(data, true); return true; });
                expect(() -> service.plan(context(owner), owner.sourceId(), owner.targetId()), "CLAIMS_ADMIN_MODE_ACTIVE");
                check(main(() -> owner.player.totalExperience == 100 && owner.player.serverLevel() == server.overworld()), "active-OPAC-admin-mode-does-not-expand-AI-travel");
            } finally { main(() -> { dataType.getMethod("setClaimsAdminMode", boolean.class).invoke(data, before); return true; }); }
        }
        if (ModList.get().isLoaded("ftbranks")) {
            var owner = main(() -> new Session(100));
            var beforeRevocation = context(owner); var approvedRoute = service.plan(beforeRevocation, owner.sourceId(), owner.targetId());
            Class<?> api = type("dev.ftb.mods.ftbranks.api.FTBRanksAPI"), managerType = type("dev.ftb.mods.ftbranks.api.RankManager"),
                    rankType = type("dev.ftb.mods.ftbranks.api.Rank"), valueType = type("dev.ftb.mods.ftbranks.api.PermissionValue");
            String name = "ai_travel_" + UUID.randomUUID().toString().replace("-", "");
            Object manager = main(() -> api.getMethod("manager").invoke(null));
            Object rank = main(() -> managerType.getMethod("createRank", String.class, int.class, boolean.class).invoke(manager, name, 10000, false));
            try {
                main(() -> {
                    rankType.getMethod("add", GameProfile.class).invoke(rank, owner.player.getGameProfile());
                    rankType.getMethod("setPermission", String.class, valueType).invoke(rank, "fireflymc.ai.travel.waystones", valueType.getMethod("parse", String.class).invoke(null, "false")); return true;
                });
                expect(() -> service.plan(context(owner), owner.sourceId(), owner.targetId()), "WAYSTONE_TRAVEL_PERMISSION_DENIED");
                expect(() -> call(owner, beforeRevocation, "waystone_travel_execute", execution(approvedRoute), true), "WAYSTONE_TRAVEL_PERMISSION_DENIED");
                check(main(() -> owner.player.totalExperience) == 100 && service.operations(owner.workspace, owner.actor).isEmpty(), "native-rank-false-denies-travel-before-intent-or-fee");
            } finally {
                main(() -> { rankType.getMethod("remove", GameProfile.class).invoke(rank, owner.player.getGameProfile()); managerType.getMethod("deleteRank", String.class).invoke(manager, name); return true; });
            }
        }
    }
    private void unloadedTarget() throws Exception {
        var session = main(() -> new Session(100)); var ctx = context(session);
        main(() -> { session.releaseTargetFixtureTickets(); server.saveEverything(true, true, true); return true; });
        var chunk = new ChunkPos(session.destination);
        await(() -> main(() -> server.overworld().getChunkSource().getChunkNow(chunk.x, chunk.z)) == null,
                "known-native-target-really-unloads-before-preparation-test");
        expect(() -> service.plan(ctx, session.sourceId(), session.targetId()), "WAYSTONE_DESTINATION_NOT_LOADED");
        check(main(() -> server.overworld().getChunkSource().getChunkNow(chunk.x, chunk.z)) == null,
                "ordinary-travel-preview-never-implicitly-loads-an-unloaded-target");
        var prep = service.preparationPlan(ctx, session.sourceId(), session.targetId());
        check(prep.chunks().size() <= 3 && main(() -> server.overworld().getChunkSource().getChunkNow(chunk.x, chunk.z)) == null,
                "native-preparation-preview-is-metadata-only-with-exact-small-scope");
        var args = new JsonObject(); args.addProperty("preparationId", prep.id().toString()); args.addProperty("preparationHash", prep.hash());
        var denied = call(session, ctx, "waystone_target_prepare_execute", args, false);
        check(!denied.success() && denied.errorCode().equals("EXPLICIT_APPROVAL_REQUIRED")
                && main(() -> server.overworld().getChunkSource().getChunkNow(chunk.x, chunk.z)) == null,
                "unloaded-target-preparation-also-requires-fixed-approval");
        var position = main(() -> session.player.position()); int xp = main(() -> session.player.totalExperience);
        var prepared = call(session, ctx, "waystone_target_prepare_execute", args, true);
        check(prepared.success() && main(() -> server.overworld().getChunkSource().getChunkNow(chunk.x, chunk.z)) != null
                && main(() -> session.player.position().equals(position) && session.player.totalExperience == xp),
                "approved-native-preparation-loads-target-without-movement-or-fee");
        lockFreeOriginalReference(session, ctx.taskId(), "waystone_target_prepare_execute", args);
        var duplicate = call(session, context(session, ctx.taskId()), "waystone_target_prepare_execute", args, true);
        check(!duplicate.success() && duplicate.errorCode().equals("WAYSTONE_PREPARATION_ALREADY_RECORDED"), "prepared-operation-id-does-not-replay-native-loader");
        var travel = service.plan(ctx, session.sourceId(), session.targetId());
        check(main(() -> session.player.totalExperience) == xp && !call(session, ctx, "waystone_travel_execute", execution(travel), false).success(),
                "native-preparation-approval-is-not-a-travel-or-fee-approval");
        check(call(session, ctx, "waystone_travel_execute", execution(travel), true).success()
                && main(() -> session.player.totalExperience) == xp - 5, "prepared-known-target-then-travels-only-under-new-native-fee-approval");
    }
    private void lockFreeOriginalReference(Session session, UUID task, String tool, JsonObject arguments) throws Exception {
        var module = new WaystoneRecoveryModule(service); var call = new ToolCall("original-native-reference", tool, arguments.toString());
        var journal = service.prepare(session.workspace);
        synchronized (service) { synchronized (journal) {
            var check = CompletableFuture.supplyAsync(() -> {
                synchronized (session.workspace.conversation()) { return module.settledCall(session.workspace, session.actor, task, call); }
            }, io);
            check(check.get(2, TimeUnit.SECONDS).orElse(false), "original-native-reference-is-lock-free-under-primary-and-journal-monitors-" + tool);
        } }
        var changed = arguments.deepCopy(); changed.addProperty(tool.equals("waystone_travel_execute") ? "planHash" : "preparationHash", AiJson.digest("wrong original hash"));
        check(module.settledCall(session.workspace, session.actor, task, new ToolCall("changed", tool, changed.toString())).equals(Optional.of(false))
                && module.settledCall(session.workspace, session.actor, UUID.randomUUID(), call).equals(Optional.of(false))
                && module.settledCall(session.workspace, Actor.player(UUID.randomUUID(), true, true, 0), task, call).equals(Optional.of(false))
                && module.settledCall(session.workspace, session.actor, task, new ToolCall("unrelated", "environment_current", "{}")).isEmpty(),
                "native-reference-rejects-wrong-hash-task-owner-and-does-not-claim-unrelated-tools-" + tool);
    }
    private void controllerReopenReference(Session session, UUID task, WaystoneTravelPlan plan, JsonObject args) throws Exception {
        int xp = main(() -> session.player.totalExperience); var position = main(() -> session.player.position());
        var reopened = main(() -> new WaystoneTravelService(server, this::authenticated, new InstalledPermissionAdapters(server), dispatcher, saves));
        try {
            reopened.prepare(session.workspace); var module = new WaystoneRecoveryModule(reopened);
            check(module.settledCall(session.workspace, session.actor, task, new ToolCall("historical-native-call", "waystone_travel_execute", args.toString())).orElse(false),
                    "reopened-controller-verifies-original-saved-artifact-as-reference-without-reusing-old-execution-epoch");
            expect(() -> reopened.load(session.workspace, session.actor, plan.id(), plan.hash()), "WAYSTONE_TRAVEL_PLAN_CHANGED");
            check(main(() -> session.player.totalExperience == xp && session.player.position().equals(position)), "reopened-reference-verifier-never-recharges-or-reteleports");
        } finally { main(() -> { reopened.close(); return true; }); reopened.finalizePendingClose(); }
    }
    private AgentTool.Result call(Session session, AgentTool.Context original, String name, JsonObject args, boolean approved) throws Exception {
        return callBound(session, original, new ToolCall(UUID.randomUUID().toString(), name, args.toString()), args, approved);
    }
    private AgentTool.Result callBound(Session session, AgentTool.Context original, ToolCall call, JsonObject args, boolean approved) throws Exception {
        var tool = tools.get(call.name());
        var bridge = new GameToolBridge(server, id -> {
            try { return main(() -> { var p = server.getPlayerList().getPlayer(UUID.fromString(id)); return p == null ? new Actor(id, false, false, 0) : Actor.player(p.getUUID(), authenticated(p), true, 0); }); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }, candidate -> tools.get(candidate) != null, () -> false, io);
        AgentTool.Context ctx = original; AgentTool current = tool;
        for (int slice = 0; slice < 4; slice++) {
            try { return bridge.execute(current, ctx, args, approved ? ToolPolicyEngine.digest(session.actor.owner(), call) : "", call).get(15, TimeUnit.SECONDS); }
            catch (ExecutionException wrapped) {
                Throwable failure = wrapped; while (failure.getCause() != null && failure.getCause() != failure) failure = failure.getCause();
                if (failure instanceof TaskControl.Stopped stopped) throw stopped;
                if (!(failure instanceof AgentTool.Deferred wait)) { if (failure instanceof Exception error) throw error; throw wrapped; }
                if (wait.reason().equals("WAITING_WAYSTONE_SAVE")) {
                    check(!service.prepare(session.workspace).get(session.actor, UUID.fromString(wait.checkpoint().get("operationId").getAsString())).saved(), "native-return-alone-is-not-a-canonical-save");
                    save();
                }
                wait.readiness().toCompletableFuture().get(10, TimeUnit.SECONDS);
                var resume = wait.continuation(); current = new AgentTool() { public Descriptor descriptor() { return tool.descriptor(); } public Result execute(Context fresh, JsonObject ignored) throws Exception { return resume.resume(fresh); } };
                ctx = context(session, original.taskId());
            }
        }
        throw new IllegalStateException("WAYSTONE_NATIVE_WAIT_LIMIT");
    }
    private void installListeners() throws Exception {
        Object events = type("net.blay09.mods.balm.api.Balm").getMethod("getEvents").invoke(null);
        var onEvent = type("net.blay09.mods.balm.api.event.BalmEvents").getMethod("onEvent", Class.class, Consumer.class);
        onEvent.invoke(events, type("net.blay09.mods.waystones.api.event.WaystoneTeleportEvent$Prepare"), (Consumer<Object>) event -> {
            try {
                var session = active; if (session == null) return;
                Object context = event.getClass().getMethod("getContext").invoke(event);
                if (context.getClass().getMethod("getEntity").invoke(context) != session.player) return;
                if (mode == Mode.CANCEL_PREPARE) activeControl.stop("CANCELLED");
                if (mode == Mode.DENY_AUTH_PREPARE) denied.add(session.player.getUUID());
                if (mode == Mode.EXTRA_CHUNK) event.getClass().getMethod("addChunkPosition", ChunkPos.class).invoke(event, new ChunkPos(session.destination.offset(1024, 0, 0)));
                if (mode == Mode.EXTRA_TASK) event.getClass().getMethod("addPreparationTask", Function.class).invoke(event,
                        (Function<Object, CompletableFuture<Object>>) value -> { preparationTaskCalls++; return CompletableFuture.completedFuture(value); });
            } catch (Throwable failure) { hookFailure.compareAndSet(null, failure); }
        });
        onEvent.invoke(events, type("net.blay09.mods.waystones.api.event.WaystoneTeleportEntityEvent$Pre"), (Consumer<Object>) event -> {
            try {
                var session = active; if (session == null || event.getClass().getMethod("getEntity").invoke(event) != session.player) return;
                if (mode == Mode.CANCEL_ENTITY) event.getClass().getMethod("setCanceled", boolean.class).invoke(event, true);
                if (mode == Mode.CHANGE_LANDING) event.getClass().getMethod("setTargetPosition", Vec3.class).invoke(event, Vec3.atCenterOf(session.destination.offset(1024, 0, 0)));
            } catch (Throwable failure) { hookFailure.compareAndSet(null, failure); }
        });
    }
    private final class Session {
        final ServerPlayer player; final Actor actor; final WorkspaceManager.Workspace workspace; final Object source, target; final BlockPos origin, destination;
        final ServerLevel destinationLevel;
        final Set<ChunkPos> fixtureTickets = new HashSet<>();
        Session(int xp) throws Exception {
            this(xp, false);
        }
        Session(int xp, boolean crossDimension) throws Exception {
            UUID id = UUID.randomUUID(); var cookie = new CommonListenerCookie(new GameProfile(id, "wtravel_" + id.toString().substring(0, 8)), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); channels.add(new EmbeddedChannel(connection)); NetworkRegistry.configureMockConnection(connection);
            server.getPlayerList().placeNewPlayer(connection, player, cookie); ValidationHandshake.reply(player); ValidationHandshake.authenticateFixture(player);
            player.setGameMode(GameType.SURVIVAL); player.setNoGravity(true); player.giveExperiencePoints(xp);
            actor = Actor.player(id, true, true, 0); workspace = workspaces.getWorkspace(actor);
            inventory.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis)); inventory.bindPreferences(actor.owner(), new MaterialPreferences(workspace));
            fluids.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis, MaterialSourceGrants.Domain.FLUIDS));
            mixed.prepare(workspace);
            origin = new BlockPos(fixtureX + nextCase++ * 1024 + 8, -59, 8);
            destination = crossDimension ? new BlockPos(origin.getX() + 512, 64, 8) : origin.offset(512, 0, 0);
            destinationLevel = crossDimension ? Objects.requireNonNull(server.getLevel(Level.NETHER)) : server.overworld();
            for (BlockPos pos : List.of(origin, destination)) for (var offset : List.of(pos, pos.north(), pos.south(), pos.east(), pos.west())) {
                var level = pos == origin ? server.overworld() : destinationLevel;
                var chunk = new ChunkPos(offset);
                if (fixtureTickets.add(chunk)) level.getChunkSource().addRegionTicket(TicketType.FORCED, chunk, 0, chunk);
                level.getChunkAt(offset);
            }
            if (crossDimension) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
                destinationLevel.setBlockAndUpdate(destination.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y < 4; y++) destinationLevel.setBlockAndUpdate(destination.offset(x, y, z), Blocks.AIR.defaultBlockState());
            }
            Object style = type("net.blay09.mods.waystones.api.WaystoneStyle").getConstructor(ResourceLocation.class).newInstance(ResourceLocation.parse("waystones:waystone"));
            source = ((Optional<?>) api().getMethod("placeWaystone", Level.class, BlockPos.class, style.getClass()).invoke(null, server.overworld(), origin, style)).orElseThrow();
            target = ((Optional<?>) api().getMethod("placeWaystone", Level.class, BlockPos.class, style.getClass()).invoke(null, destinationLevel, destination, style)).orElseThrow();
            Object visibility = Enum.valueOf((Class) type("net.blay09.mods.waystones.api.WaystoneVisibility"), "ACTIVATION");
            for (Object stone : List.of(source, target)) {
                type("net.blay09.mods.waystones.api.MutableWaystone").getMethod("setOwnerUid", UUID.class).invoke(stone, id);
                type("net.blay09.mods.waystones.api.MutableWaystone").getMethod("setVisibility", visibility.getClass()).invoke(stone, visibility);
                api().getMethod("activateWaystone", ServerPlayer.class, waystone()).invoke(null, player, stone);
            }
            player.moveTo(origin.getX() + 1.5, origin.getY(), origin.getZ() + .5); sessions.add(this);
        }
        UUID sourceId() throws Exception { return (UUID) waystone().getMethod("getWaystoneUid").invoke(source); }
        UUID targetId() throws Exception { return (UUID) waystone().getMethod("getWaystoneUid").invoke(target); }
        void releaseTargetFixtureTickets() {
            for (var chunk : List.copyOf(fixtureTickets)) if (chunk.equals(new ChunkPos(destination))) {
                destinationLevel.getChunkSource().removeRegionTicket(TicketType.FORCED, chunk, 0, chunk); fixtureTickets.remove(chunk);
            }
        }
    }
    private AgentTool.Context context(Session session) throws Exception {
        var task = AgentTask.create(session.actor.owner(), UUID.randomUUID(), "Actual native Waystones travel validation"); session.workspace.conversation().append(List.of(Draft.checkpoint(task)));
        return context(session, task.id());
    }
    private AgentTool.Context context(Session session, UUID task) { return new AgentTool.Context(session.workspace, session.actor, new TaskControl(120000), task); }
    private static JsonObject execution(WaystoneTravelPlan plan) { var args = new JsonObject(); args.addProperty("planId", plan.id().toString()); args.addProperty("planHash", plan.hash()); return args; }
    private boolean authenticated(ServerPlayer player) { return AiServerService.authenticated(player) && !denied.contains(player.getUUID()); }
    private void save() throws Exception {
        main(() -> {
            server.getPlayerList().saveAll();
            for (var session : sessions) { session.player.getStats().save(); session.player.getAdvancements().save(); }
            return true;
        });
    }
    private <T> T main(Callable<T> action) throws Exception { var future = new CompletableFuture<T>(); server.execute(() -> { try { future.complete(action.call()); } catch (Throwable failure) { future.completeExceptionally(failure); } }); return future.get(10, TimeUnit.SECONDS); }
    private void check(boolean condition, String name) { if (!condition) throw new IllegalStateException(name); passed.add(name); }
    private void await(Callable<Boolean> condition, String label) throws Exception { long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); while (!condition.call() && System.nanoTime() < end) Thread.sleep(10); check(condition.call(), label); }
    private void expect(Callable<?> action, String code) throws Exception {
        try { Object value = action.call(); if (value instanceof AgentTool.Result reply && !reply.success() && reply.errorCode().equals(code)) { passed.add("rejected:" + code); return; } }
        catch (Exception failure) { Throwable cause = failure; while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause(); if (code.equals(cause.getMessage())) { passed.add("rejected:" + code); return; } throw failure; }
        throw new IllegalStateException("EXPECTED_REJECTION:" + code);
    }
    public JsonObject tick() { inventory.tick(); fluids.tick(); service.tick(); dispatcher.tick(); return result.isDone() ? result.join() : null; }
    @Override public void close() throws Exception {
        active = null; mode = Mode.NORMAL; service.close(); service.finalizePendingClose(); dispatcher.close(); dispatcher.awaitEvidenceStopped(10000);
        nativeTeleports.getClass().getField("warpRequirements").set(nativeTeleports, originalRequirements);
        nativeTeleports.getClass().getField("enableCosts").setBoolean(nativeTeleports, originalCosts);
        nativeTeleports.getClass().getField("enableCooldowns").setBoolean(nativeTeleports, originalCooldowns);
        for (var session : sessions) {
            for (var chunk : session.fixtureTickets) (chunk.equals(new ChunkPos(session.destination)) ? session.destinationLevel : server.overworld()).getChunkSource().removeRegionTicket(TicketType.FORCED, chunk, 0, chunk);
            server.getPlayerList().remove(session.player);
        }
        mixed.close(); fluids.close(); inventory.close(); io.shutdown(); io.awaitTermination(10, TimeUnit.SECONDS); channels.forEach(EmbeddedChannel::finishAndReleaseAll); workspaces.close();
    }
    private static Class<?> api() throws ClassNotFoundException { return type("net.blay09.mods.waystones.api.WaystonesAPI"); }
    private static Class<?> waystone() throws ClassNotFoundException { return type("net.blay09.mods.waystones.api.Waystone"); }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name); }
}
