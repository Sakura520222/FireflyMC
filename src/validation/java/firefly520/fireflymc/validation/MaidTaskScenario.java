package firefly520.fireflymc.validation;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.AiServerService;
import firefly520.fireflymc.ai.agent.AgentRuntime;
import firefly520.fireflymc.ai.blueprint.ConstructionWorldEvents;
import firefly520.fireflymc.ai.blueprint.WorldMutationTracker;
import firefly520.fireflymc.ai.context.ContextCompactor;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.integration.maid.*;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage.ToolCall;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.model.ModelGateway;
import firefly520.fireflymc.ai.recovery.*;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Real mode control and native follow acceptance; no claim of harvest/combat/transport execution. */
public final class MaidTaskScenario implements AutoCloseable {
    private final MinecraftServer server;
    private final WorkspaceManager manager;
    private final MaterialInventoryService inventory;
    private final MaterialLedger ledger;
    private final VirtualProcessingService dispatcher;
    private final MaidControlService control;
    private final MaidFarmService farm;
    private final java.util.concurrent.atomic.AtomicLong farmGeneration = new java.util.concurrent.atomic.AtomicLong(1);
    private final ServerPlayer owner, other;
    private final TamableAnimal maid;
    private final Actor actor, foreign;
    private final WorkspaceManager.Workspace workspace;
    private final ToolRegistry tools = new ToolRegistry();
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<String> passed = new ArrayList<>();
    private final CompletableFuture<JsonObject> result;
    private final GameToolBridge bridge;
    private final WorldMutationTracker worldTracker;
    private final boolean ownsWorldTracker;
    private final java.util.concurrent.atomic.AtomicInteger ticks = new java.util.concurrent.atomic.AtomicInteger();

    public MaidTaskScenario(MinecraftServer server) throws Exception {
        if (!server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_MAID_SCENARIO_REQUIRED");
        this.server = server;
        WorldMutationTracker tracker; boolean owned;
        try { tracker = ConstructionWorldEvents.tracker(server); owned = false; }
        catch (IllegalStateException absent) {
            if (!"WORLD_MUTATION_TRACKER_UNAVAILABLE".equals(absent.getMessage())) throw absent;
            tracker = new WorldMutationTracker(); ConstructionWorldEvents.install(server, tracker); owned = true;
        }
        worldTracker = tracker; ownsWorldTracker = owned;
        owner = player(); other = player(); actor = Actor.player(owner.getUUID(), true, true, 0); foreign = Actor.player(other.getUUID(), true, true, 0);
        Path root = Files.createTempDirectory(server.getWorldPath(LevelResource.ROOT).toAbsolutePath(), "ai4-maid-task-");
        manager = new WorkspaceManager(root); workspace = manager.getWorkspace(actor); ledger = new MaterialLedger(root);
        var permissions = new InstalledPermissionAdapters(server);
        inventory = new MaterialInventoryService(server, AiServerService::authenticated, permissions);
        inventory.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis));
        inventory.bindPreferences(actor.owner(), new MaterialPreferences(workspace));
        dispatcher = new VirtualProcessingService(server, inventory, ledger, () -> 1);
        control = new MaidControlService(server, inventory, permissions, dispatcher); control.register(tools);
        farm = new MaidFarmService(server, inventory, permissions, dispatcher, farmGeneration::get, ledger); farm.register(tools);
        var entity = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("touhou_little_maid:maid")).create(server.overworld());
        if (!(entity instanceof TamableAnimal pet)) throw new IllegalStateException("ACTUAL_MAID_ENTITY_REQUIRED");
        maid = pet; maid.tame(owner);
        int ground = server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
        // Keep the native brain inside the player's actively ticking chunk, while still outside
        // the real non-home follow threshold (8 + 2). A merely loaded distant chunk is insufficient.
        owner.moveTo(0.5, ground, 0.5); other.moveTo(0.5, ground, 3.5); maid.moveTo(12.5, ground, 0.5);
        if (!server.overworld().addFreshEntity(maid)) throw new IllegalStateException("ACTUAL_MAID_FIXTURE_JOIN_DENIED");
        Object schedule = maid.getClass().getMethod("getSchedulePos").invoke(maid);
        schedule.getClass().getMethod("setHomeModeEnable", maid.getClass(), BlockPos.class).invoke(schedule, maid, maid.blockPosition());
        // SchedulePos configures anchors/restriction; the native packet sets the mode flag separately.
        maid.getClass().getMethod("setHomeModeEnable", boolean.class).invoke(maid, true);
        if (!bool("isHomeModeEnable")) throw new IllegalStateException("NATIVE_HOME_FIXTURE_NOT_INITIALIZED");
        if (inventory.maids().bindOwned(owner, owner.getUUID(), maid.getUUID()).binding() == null) throw new IllegalStateException("ACTUAL_MAID_BINDING_REQUIRED");
        bridge = new GameToolBridge(server, identity -> {
            try { return main(() -> {
                var player = server.getPlayerList().getPlayer(UUID.fromString(identity));
                return player == null ? new Actor(identity, false, false, 0) : Actor.player(player.getUUID(), AiServerService.authenticated(player), true, 0);
            }); } catch (Exception failure) { throw new CompletionException(failure); }
        }, name -> tools.get(name) != null, () -> false);
        result = CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime(); var report = new JsonObject();
            try { run(); report.addProperty("status", "PASSED"); }
            catch (Throwable failure) {
                failure.printStackTrace(); report.addProperty("status", "FAILED"); report.addProperty("error", failure.toString());
                try { report.add("nativeDiagnostics", main(this::diagnostics)); } catch (Exception unavailable) { report.addProperty("diagnosticFailure", unavailable.getClass().getSimpleName()); }
            }
            report.add("passedChecks", AiJson.GSON.toJsonTree(passed)); report.addProperty("durationMillis", (System.nanoTime() - started) / 1_000_000);
            report.addProperty("scope", "Actual TLM native entity/brain and dedicated authenticated mock network players, production control/approval/save journal; FOLLOW/STAY only. No graphical client, model request, harvest, combat or transport acceptance claim.");
            return report;
        }, action -> Thread.ofPlatform().daemon(true).name("maid-native-task-validation").start(action));
    }
    private void run() throws Exception {
        control.prepare(workspace);
        check(main(() -> AiServerService.authenticated(owner) && AiServerService.authenticated(other)), "two-native-players-use-existing-firefly-auth");
        var followContext = context("Enable native maid follow with independently approved exact mode");
        var follow = control.plan(followContext, MaidControlPlan.Mode.FOLLOW);
        check(follow.before().home() && main(() -> bool("isHomeModeEnable")), "follow-preview-does-not-change-native-home-mode");
        expectFailure(() -> control.load(workspace, foreign, follow.id(), follow.hash()));
        check(!tools.get("maid_control_execute").descriptor().requiredCapability().contains("admin"), "op-does-not-turn-maid-control-into-administration");
        var args = arguments(follow); var tool = tools.get("maid_control_execute");
        var call = new ToolCall("actual-maid-follow", tool.descriptor().name(), args.toString());
        check(new ToolPolicyEngine().check(actor, actor.owner(), tool.descriptor(), true, call, "").decision() == ToolPolicyEngine.Decision.APPROVAL,
                "native-maid-mode-control-needs-its-own-workbench-approval");
        var playerPosition = main(owner::position);
        var followed = executeSaved(followContext, follow, false);
        check(followed.success() && followed.observations().get("settledAndSaved").getAsBoolean()
                && !followed.observations().get("arrivalOrWorldTaskCompleted").getAsBoolean(), "follow-result-proves-saved-mode-without-claiming-arrival");
        await(() -> {
            try { return main(() -> maid.distanceToSqr(owner) <= 8 * 8); } catch (Exception failure) { throw new CompletionException(failure); }
        }, "actual-native-core-follow-brings-owned-maid-within-native-range");
        check(main(() -> maid.tickCount > 0 && !maid.isNoAi()), "actual-native-follow-is-ticked-with-native-ai-enabled");
        check(main(() -> owner.position().equals(playerPosition)), "native-follow-never-teleports-the-player");
        var duplicate = control.execute(contextFor(followContext.taskId()), follow);
        check(!duplicate.success() && duplicate.errorCode().equals("MAID_CONTROL_ALREADY_RECORDED"), "duplicate-native-mode-request-does-not-repeat-callback");

        var stayContext = context("Stay through a native mode callback and retain late save after cancellation");
        var stay = control.plan(stayContext, MaidControlPlan.Mode.STAY);
        var stayed = executeSaved(stayContext, stay, true);
        check(stayed.observations().get("settledAndSaved").getAsBoolean() && main(() -> bool("isMaidInSittingPose")),
                "cancelled-waiter-retains-accepted-native-sitting-and-real-entity-save");
        check(main(() -> maid.getNavigation().isDone() && maid.getTarget() == null), "native-stay-clears-navigation-and-attack-target");
        reconcileChangedMode();

        var abaContext = context("A native manual control reversal must invalidate an old exact mode approval");
        var aba = control.plan(abaContext, MaidControlPlan.Mode.FOLLOW);
        main(() -> {
            maid.getClass().getMethod("setInSittingPose", boolean.class).invoke(maid, false);
            maid.getClass().getMethod("setInSittingPose", boolean.class).invoke(maid, true); return true;
        });
        expectFailure(() -> control.execute(abaContext, aba));
        check(main(() -> bool("isMaidInSittingPose")), "native-control-aba-cannot-reuse-an-old-approval-even-when-visible-mode-matches");
        nativeFarming();

        var staleContext = context("Manual native task changes invalidate a pending follow approval");
        var stale = control.plan(staleContext, MaidControlPlan.Mode.FOLLOW);
        main(() -> {
            Class<?> tasks = Class.forName("com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager");
            Object attack = ((Optional<?>) tasks.getMethod("findTask", ResourceLocation.class).invoke(null, ResourceLocation.parse("touhou_little_maid:attack"))).orElseThrow();
            maid.getClass().getMethod("setTask", Class.forName("com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask")).invoke(maid, attack); return true;
        });
        expectFailure(() -> control.execute(staleContext, stale));
        check(main(() -> bool("isMaidInSittingPose")), "manual-native-task-takeover-is-not-overwritten-by-old-follow-approval");
        main(() -> {
            Class<?> tasks = Class.forName("com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager");
            Object idle = tasks.getMethod("getIdleTask").invoke(null);
            maid.getClass().getMethod("setTask", Class.forName("com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask")).invoke(maid, idle);
            inventory.maids().bindOwned(owner, owner.getUUID(), maid.getUUID()); return true;
        });
        var ownershipContext = context("Ownership changes reject the old approved maid mode without observing foreign inventory");
        var owned = control.plan(ownershipContext, MaidControlPlan.Mode.FOLLOW);
        main(() -> { maid.setOwnerUUID(other.getUUID()); return true; });
        expectFailure(() -> control.execute(ownershipContext, owned));
        check(main(() -> bool("isMaidInSittingPose")), "owner-change-prevents-native-follow-mutation");
        check(workspace.primaryConversationId().equals(follow.conversationId()) && workspace.primaryConversationId().equals(stay.conversationId()),
                "native-maid-controls-stay-in-one-permanent-primary-conversation");
        check(control.resourcesSettled(workspace, actor), "all-accepted-native-mode-effects-have-positive-save-or-no-dispatch-evidence");
        closeBeforeNativeSave();
    }
    private void nativeFarming() throws Exception {
        farm.prepare(workspace);
        var standingContext = context("Stand through real native follow mode before finite native farming");
        executeSaved(standingContext, control.plan(standingContext, MaidControlPlan.Mode.FOLLOW), false);
        var positions = main(() -> {
            var level = server.overworld(); int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 4, 0);
            owner.moveTo(.5, ground, .5); maid.moveTo(4.5, ground, .5); level.setDayTime(6000);
            var first = new BlockPos(4, ground, 0); var second = new BlockPos(4, ground, 1);
            for (var pos : List.of(first, second)) {
                level.setBlockAndUpdate(pos.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
                level.setBlockAndUpdate(pos.below().east(), Blocks.WATER.defaultBlockState());
            }
            level.setBlockAndUpdate(first, ((CropBlock) Blocks.WHEAT).getStateForAge(((CropBlock) Blocks.WHEAT).getMaxAge()));
            level.setBlockAndUpdate(second, Blocks.AIR.defaultBlockState()); return List.of(first, second);
        });
        await(() -> {
            try { return main(() -> maid.getBrain().isActive(Activity.WORK)); } catch (Exception error) { throw new CompletionException(error); }
        }, "real-native-work-schedule-is-active-before-farming");
        var selection = main(() -> inventory.selectMaidBackpack(actor, maid.getUUID()));
        var grants = inventory.grants(actor); var proposed = grants.preview(actor, selection.candidate());
        var grant = grants.confirm(actor, proposed.id(), proposed.digest(), main(() -> inventory.confirmSelection(actor, selection)));
        var harvestContext = context("Harvest one approved mature wheat cell with the actual MaidFarmPlantTask");
        var harvest = farm.plan(harvestContext, MaidFarmPlan.Kind.HARVEST, positions.getFirst(), grant.id(), -1);
        check(main(() -> ((CropBlock) Blocks.WHEAT).isMaxAge(server.overworld().getBlockState(positions.getFirst()))),
                "native-farm-preview-does-not-harvest-or-credit-loot");
        var harvested = executeFarmSaved(harvestContext, harvest);
        check(harvested.success() && main(() -> ((CropBlock) Blocks.WHEAT).getAge(server.overworld().getBlockState(positions.getFirst())) == 0),
                "actual-native-maid-harvest-regrows-the-approved-crop-with-real-backpack-loot");
        check(main(() -> inventory.maids().backpackSource(owner, owner.getUUID(), maid.getUUID()).inventory().getStackInSlot(0).getCount() > 0),
                "native-harvest-outputs-are-actual-maid-inventory-items");
        int seedSlot = main(() -> {
            var source = inventory.maids().backpackSource(owner, owner.getUUID(), maid.getUUID()).inventory();
            for (int slot = 0; slot < source.getSlots(); slot++) if (source.getStackInSlot(slot).is(Items.WHEAT_SEEDS)) return slot;
            for (int slot = 0; slot < source.getSlots(); slot++) if (source.getStackInSlot(slot).isEmpty()) {
                // Real fixture input, not an AI output: retained and charged by the production native action.
                if (!source.insertItem(slot, new ItemStack(Items.WHEAT_SEEDS, 2), false).isEmpty()) throw new IllegalStateException("NATIVE_SEED_FIXTURE_REJECTED");
                return slot;
            }
            throw new IllegalStateException("NO_NATIVE_SEED_FIXTURE_SLOT");
        });
        int beforeSeeds = main(() -> maidSeeds()); int beforeTicks = ticks.get();
        var plantContext = context("Plant one real wheat seed through the actual native maid behavior");
        var plant = farm.plan(plantContext, MaidFarmPlan.Kind.PLANT, positions.get(1), grant.id(), seedSlot);
        var planted = executeFarmSaved(plantContext, plant);
        check(planted.success() && main(() -> server.overworld().getBlockState(positions.get(1)).is(Blocks.WHEAT)) && main(this::maidSeeds) == beforeSeeds - 1,
                "actual-native-maid-plant-consumes-one-existing-seed-and-places-the-real-crop");
        check(ticks.get() - beforeTicks >= 120, "native-maid-check-rate-was-not-waived-or-decremented-twice-per-tick");
        var staleContext = context("Resource reload refuses a pending fixed native farming plan");
        main(() -> { server.overworld().setBlockAndUpdate(positions.get(1), ((CropBlock) Blocks.WHEAT).getStateForAge(((CropBlock) Blocks.WHEAT).getMaxAge())); return true; });
        var stale = farm.plan(staleContext, MaidFarmPlan.Kind.HARVEST, positions.get(1), grant.id(), -1);
        farmGeneration.incrementAndGet(); expectFailure(() -> farm.execute(staleContext, stale));
        check(main(() -> ((CropBlock) Blocks.WHEAT).isMaxAge(server.overworld().getBlockState(positions.get(1)))),
                "resource-reload-does-not-repeat-a-native-world-or-inventory-effect");
        check(farm.resourcesSettled(workspace, actor), "all-real-native-farm-callbacks-have-canonical-save-receipts");
        var staying = context("Return to an explicitly approved native stay mode for the following takeover fixture");
        executeSaved(staying, control.plan(staying, MaidControlPlan.Mode.STAY), false);
    }
    private int maidSeeds() {
        int total = 0; var source = inventory.maids().backpackSource(owner, owner.getUUID(), maid.getUUID()).inventory();
        for (int slot = 0; slot < source.getSlots(); slot++) if (source.getStackInSlot(slot).is(Items.WHEAT_SEEDS)) total += source.getStackInSlot(slot).getCount();
        return total;
    }
    private AgentTool.Result executeFarmSaved(AgentTool.Context initial, MaidFarmPlan plan) throws Exception {
        var args = new JsonObject(); args.addProperty("planId", plan.id().toString()); args.addProperty("planHash", plan.hash());
        var original = tools.get("maid_farm_execute"); AgentTool stage = original; var context = initial;
        var call = new ToolCall("native-farm-" + plan.id(), original.descriptor().name(), args.toString());
        for (int phase = 0; phase < 4; phase++) {
            AgentTool.Deferred waiting;
            try { return context.control().await(bridge.execute(stage, context, args, ToolPolicyEngine.digest(actor.owner(), call), call), 10000); }
            catch (Exception failure) {
                Throwable cause = failure; while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                if (!(cause instanceof AgentTool.Deferred deferred)) throw failure; waiting = deferred;
            }
            if (waiting.reason().equals("WAITING_NATIVE_MAID_FARM_SAVE")) saveNative();
            else check(waiting.reason().equals("WAITING_NATIVE_MAID_FARM"), "native-farm-only-waits-for-real-navigation-rate-or-save");
            waiting.readiness().toCompletableFuture().get(20, TimeUnit.SECONDS);
            context = contextFor(initial.taskId());
            stage = new AgentTool() {
                public Descriptor descriptor() { return original.descriptor(); }
                public Result execute(Context current, JsonObject arguments) throws Exception { return waiting.continuation().resume(current); }
            };
        }
        throw new IllegalStateException("NATIVE_FARM_CONTINUATION_LIMIT");
    }
    private void closeBeforeNativeSave() throws Exception {
        main(() -> {
            maid.setOwnerUUID(owner.getUUID());
            Class<?> tasks = Class.forName("com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager");
            maid.getClass().getMethod("setTask", Class.forName("com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask"))
                    .invoke(maid, tasks.getMethod("getIdleTask").invoke(null));
            maid.getClass().getMethod("setInSittingPose", boolean.class).invoke(maid, false);
            maid.getClass().getMethod("setHomeModeEnable", boolean.class).invoke(maid, true);
            inventory.maids().bindOwned(owner, owner.getUUID(), maid.getUUID()); return true;
        });
        var context = context("Accepted native mode survives controller shutdown until ordinary canonical entity save");
        var plan = control.plan(context, MaidControlPlan.Mode.FOLLOW); var args = arguments(plan);
        var tool = tools.get("maid_control_execute"); var call = new ToolCall("close-before-save-" + plan.id(), tool.descriptor().name(), args.toString());
        var waiting = deferred(bridge.execute(tool, context, args, ToolPolicyEngine.digest(actor.owner(), call), call));
        check(main(() -> !bool("isHomeModeEnable")) && !waiting.readiness().toCompletableFuture().isDone(),
                "closing-fixture-has-a-real-accepted-mode-and-unwritten-specific-witness");
        control.close();
        check(control.awaitStopped(10000) && !waiting.readiness().toCompletableFuture().isDone(),
                "controller-close-drains-evidence-without-blocking-or-erasing-pending-native-save-witness");
        saveNative(); waiting.readiness().toCompletableFuture().get(10, TimeUnit.SECONDS);
        check(control.operationSettled(workspace, actor, plan.id()) && control.taskSettled(workspace, actor, plan.taskId()),
                "canonical-entity-save-after-controller-close-still-writes-the-real-positive-mode-proof");
    }
    private void reconcileChangedMode() throws Exception {
        try (var workbench = new MaidWorkbench()) {
        var ctx = context("Reconcile current native mode after an accepted follow was manually replaced before saving");
        var follow = control.plan(ctx, MaidControlPlan.Mode.FOLLOW); var originalArgs = arguments(follow);
        var originalTool = tools.get("maid_control_execute"); var originalCall = new ToolCall("unconfirmed-mode-" + follow.id(), originalTool.descriptor().name(), originalArgs.toString());
        var started = workspace.conversation().task(ctx.taskId()).progress(1, 0, 0, 0, List.of(originalCall), 0,
                ToolPolicyEngine.digest(actor.owner(), originalCall), true, true).update(AgentTask.State.EXECUTING, "REAL_MAID_NATIVE_DISPATCH");
        var start = new JsonObject(); start.addProperty("name", originalCall.name()); start.addProperty("risk", originalTool.descriptor().risk().name());
        start.addProperty("inputDigest", started.approvalDigest());
        workspace.conversation().append(List.of(Draft.message(ctx.taskId(), new ModelMessage("assistant", "", List.of(originalCall), null, null), null),
                new Draft(ctx.taskId(), "tool_start", null, start, originalCall.id()), Draft.checkpoint(started)));
        AgentTool.Deferred original = deferred(workbench.bridge.execute(originalTool, ctx, originalArgs, ToolPolicyEngine.digest(actor.owner(), originalCall), originalCall));
        ctx.control().stop("CANCELLED");
        var interrupted = workspace.conversation().task(ctx.taskId()).update(AgentTask.State.RECOVERY_REQUIRED, "REAL_MODE_SAVE_WAIT_INTERRUPTED");
        var wait = new JsonObject(); wait.addProperty("reason", original.reason()); wait.add("checkpoint", original.checkpoint());
        workspace.conversation().append(List.of(new Draft(ctx.taskId(), "tool_wait_external", null, wait, originalCall.id()), Draft.checkpoint(interrupted)));
        main(() -> { maid.getClass().getMethod("setInSittingPose", boolean.class).invoke(maid, true); return true; });
        saveNative();
        check(!original.readiness().toCompletableFuture().isDone(), "a-different-saved-native-mode-cannot-satisfy-the-old-mode-witness");
        var fresh = contextFor(ctx.taskId()); var review = control.recoveryPreview(fresh, follow.id());
        check(review.observed().seated() && main(() -> bool("isMaidInSittingPose")), "current-state-reconcile-preview-is-read-only-and-keeps-manual-sitting");
        control.validateReconciliationVersion(workspace, actor, review);
        var view = workbench.recovery.view(workspace, actor, ctx.taskId(), 0);
        check(view.getAsJsonArray("operations").asList().stream().anyMatch(element -> element.getAsJsonObject().get("operationId").getAsString().equals(follow.id().toString())
                && element.getAsJsonObject().getAsJsonArray("actions").asList().stream().anyMatch(action -> action.getAsString().equals("MAID_RECONCILE"))),
                "direct-own-workbench-view-exposes-current-state-recovery-while-model-task-is-fenced");
        var preview = workbench.recovery.preview(workspace, actor, workbench.connection, ctx.taskId(), RecoveryWorkbenchService.Action.MAID_RECONCILE, follow.id(), null);
        UUID token = UUID.fromString(preview.get("token").getAsString()); String digest = preview.get("digest").getAsString();
        expectFailure(() -> workbench.recovery.confirm(workspace, actor, UUID.randomUUID(), token, digest));
        var queued = workbench.recovery.confirm(workspace, actor, workbench.connection, token, digest);
        UUID actionId = UUID.fromString(queued.get("actionId").getAsString());
        expectFailure(() -> workbench.recovery.confirm(workspace, actor, workbench.connection, token, digest));
        await(() -> recoveryEvent("workbench_recovery_wait", actionId).filter(event -> event.get("status").getAsString().equals("WAITING_MAID_RECONCILE_SAVE")).isPresent(),
                "direct-workbench-approved-current-state-recovery-releases-its-worker-for-native-save");
        check(workbench.io.submit(() -> true).get(1, TimeUnit.SECONDS) && main(() -> bool("isMaidInSittingPose")),
                "approved-current-state-reconcile-registers-a-new-save-without-replaying-follow");
        expectFailure(() -> workbench.runtime.acknowledgeRecovery(actor, ctx.taskId()));
        check(!original.readiness().toCompletableFuture().isDone() && workbench.acknowledged.get() == 0,
                "queued-or-unsaved-current-state-recovery-cannot-ack-the-original-mode-task");
        saveNative();
        await(() -> recoveryEvent("workbench_recovery_result", actionId).isPresent(), "direct-workbench-current-state-result-is-durable");
        var recoveryResultEvent = recoveryEvent("workbench_recovery_result", actionId).orElseThrow();
        check(recoveryResultEvent.get("status").getAsString().equals("RECONCILED_CURRENT_STATE"), "direct-workbench-status-does-not-claim-original-mode-success");
        var result = AiJson.GSON.fromJson(recoveryResultEvent.get("result"), AgentTool.Result.class);
        check(result.success() && result.errorCode().equals("RECONCILED_CURRENT_STATE")
                && result.observations().get("state").getAsString().equals("RECONCILED_CURRENT_STATE")
                && !result.observations().get("verified").getAsBoolean() && main(() -> bool("isMaidInSittingPose")),
                "current-mode-is-saved-and-reconciled-without-claiming-original-follow-success");
        original.readiness().toCompletableFuture().get(10, TimeUnit.SECONDS);
        check(control.taskSettled(workspace, actor, ctx.taskId()) && workspace.conversation().getSnapshot(0).events().stream()
                .anyMatch(event -> event.kind().equals("maid_control_late_native_proof") && event.taskId().equals(ctx.taskId())),
                "only-the-durable-current-state-resolution-retires-the-old-mode-save-duty");
        expectFailure(() -> original.continuation().resume(ctx));
        await(() -> workbench.acknowledged.get() == 1 || workbench.callbackFailure.get() != null,
                "shared-native-call-verifier-reaches-real-agent-runtime-acknowledgement");
        check(workbench.callbackFailure.get() == null && workbench.unexpected.get() == 0
                && workspace.conversation().task(ctx.taskId()).state() == AgentTask.State.FAILED
                && workspace.conversation().getSnapshot(0).events().stream().filter(event0 -> event0.kind().equals("maid_control_evidence")
                    && ctx.taskId().equals(event0.taskId()) && event0.data().get("state").getAsString().equals("INTENT")).count() == 1,
                "original-task-replans-through-real-runtime-with-zero-model-requests-and-no-mode-callback-replay");
        }
    }
    private Optional<JsonObject> recoveryEvent(String kind, UUID action) {
        return workspace.conversation().getSnapshot(0).events().stream().filter(event -> event.kind().equals(kind) && event.data() != null
                && event.data().has("actionId") && action.toString().equals(event.data().get("actionId").getAsString())).map(event -> event.data()).reduce((a, b) -> b);
    }
    private final class MaidWorkbench implements AutoCloseable {
        final UUID connection = UUID.randomUUID();
        final java.util.concurrent.atomic.AtomicInteger unexpected = new java.util.concurrent.atomic.AtomicInteger(), acknowledged = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<Throwable> callbackFailure = new java.util.concurrent.atomic.AtomicReference<>();
        final ThreadPoolExecutor io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16),
                Thread.ofPlatform().daemon(true).name("maid-recovery-test-io").factory(), new ThreadPoolExecutor.AbortPolicy());
        final GameToolBridge bridge;
        final AgentRuntime runtime;
        final RecoveryWorkbenchService recovery;
        final MaidRecoveryModule module = new MaidRecoveryModule(control);
        MaidWorkbench() {
            java.util.function.Function<String, Actor> resolver = identity -> {
                try { return main(() -> {
                    var player = server.getPlayerList().getPlayer(UUID.fromString(identity));
                    return player == null ? new Actor(identity, false, false, 0) : Actor.player(player.getUUID(), AiServerService.authenticated(player), true, 0);
                }); } catch (Exception failure) { throw new CompletionException(failure); }
            };
            bridge = new GameToolBridge(server, resolver, name -> tools.get(name) != null, () -> false, io);
            var environment = new AgentRuntime.Environment() {
                public Actor actor(String identity) { return resolver.apply(identity); }
                public boolean enabled(String name) { return tools.get(name) != null; }
                public boolean requiresRecovery(WorkspaceManager.Workspace current, UUID task) {
                    try { return control.requiresRecovery(current, actor, task); } catch (Exception invalid) { return true; }
                }
                public boolean recoverySettled(WorkspaceManager.Workspace current, UUID task) {
                    try {
                        return control.taskSettled(current, actor, task) && ledger.settledForTask(actor, task)
                                && !firefly520.fireflymc.ai.blueprint.BuildJournal.hasUnresolvedWorldEffects(current)
                                && RecoveryTaskEvidence.supportedEffectsSettled(current, task, id -> false, args -> null,
                                    call -> module.settledCall(current, actor, task, call));
                    } catch (Exception invalid) { return false; }
                }
                public CompletableFuture<AgentTool.Result> execute(AgentTool tool, AgentTool.Context context, JsonObject arguments, String approval, ToolCall call) {
                    unexpected.incrementAndGet(); return CompletableFuture.failedFuture(new IllegalStateException("RECOVERY_MUST_NOT_REPLAY_ORIGINAL_MODE"));
                }
                public void changed(WorkspaceManager.Workspace current, AgentTask task, String notice) {}
            };
            ModelGateway gateway = (request, task) -> { unexpected.incrementAndGet(); throw new IllegalStateException("RECOVERY_MUST_NOT_REQUEST_MODEL"); };
            runtime = new AgentRuntime(manager, new TaskScheduler(1, 8, 16), gateway, tools,
                    new ContextCompactor(new ContextCompactor.Settings(32000, 512, 1024, 512, .75, 2), "native-maid-recovery-validation"),
                    new AgentRuntime.Limits(8, 8, 200000, 120000, 512, true, false), environment, new SecretFilter(List.of()));
            recovery = new RecoveryWorkbenchService(ledger, null, dispatcher, tools, bridge, inventory.nativeSaves(), resolver,
                    name -> tools.get(name) != null, (current, task) -> {
                        if (!environment.recoverySettled(current, task)) return;
                        try { runtime.acknowledgeRecovery(resolver.apply(current.owner()), task); acknowledged.incrementAndGet(); }
                        catch (Exception failure) { callbackFailure.compareAndSet(null, failure); }
                    });
            recovery.registerModule(RecoveryWorkbenchService.Action.MAID_RECONCILE, module);
        }
        public void close() throws Exception {
            recovery.finalizePendingClose(); if (!recovery.awaitStopped(10000)) throw new IllegalStateException("MAID_RECOVERY_SHUTDOWN_TIMEOUT");
            runtime.close(); if (!runtime.awaitStopped(10000)) throw new IllegalStateException("MAID_RUNTIME_SHUTDOWN_TIMEOUT"); runtime.finalizePendingClose();
            io.shutdown(); if (!io.awaitTermination(10000, TimeUnit.MILLISECONDS)) throw new IllegalStateException("MAID_RECOVERY_IO_SHUTDOWN_TIMEOUT");
        }
    }
    private AgentTool.Deferred deferred(CompletableFuture<AgentTool.Result> result) throws Exception {
        try { result.get(10, TimeUnit.SECONDS); }
        catch (ExecutionException failure) {
            Throwable cause = failure; while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            if (cause instanceof AgentTool.Deferred waiting) return waiting;
            throw failure;
        }
        throw new IllegalStateException("NATIVE_SAVE_DEFERRED_EXPECTED");
    }
    private void saveNative() throws Exception {
        main(() -> {
            for (var level : server.getAllLevels()) { level.save(null, true, false); level.getDataStorage().save(); }
            server.getPlayerList().saveAll();
            for (var player : List.of(owner, other)) { player.getStats().save(); player.getAdvancements().save(); }
            return true;
        });
        IOUtilities.waitUntilIOWorkerComplete();
    }
    private AgentTool.Result executeSaved(AgentTool.Context ctx, MaidControlPlan plan, boolean cancelWhileWaiting) throws Exception {
        var tool = tools.get("maid_control_execute"); var args = arguments(plan);
        var call = new ToolCall("mode-" + plan.id(), tool.descriptor().name(), args.toString());
        AgentTool.Deferred deferred;
        try {
            var result = bridge.execute(tool, ctx, args, ToolPolicyEngine.digest(actor.owner(), call), call).get(10, TimeUnit.SECONDS);
            throw new IllegalStateException("NATIVE_MODE_SAVE_WAIT_EXPECTED:" + result.errorCode());
        } catch (ExecutionException failure) {
            Throwable cause = failure; while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            if (!(cause instanceof AgentTool.Deferred waiting)) throw failure; deferred = waiting;
        }
        check(deferred.reason().equals("WAITING_MAID_NATIVE_SAVE") && !deferred.readiness().toCompletableFuture().isDone(), "native-mode-acceptance-waits-for-real-entity-region-save");
        if (cancelWhileWaiting) ctx.control().stop("CANCELLED");
        saveNative(); deferred.readiness().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (cancelWhileWaiting) {
            expectFailure(() -> deferred.continuation().resume(ctx));
            var status = tools.get("maid_control_status").execute(contextFor(ctx.taskId()), statusArguments(plan.id()));
            return status;
        }
        var fresh = contextFor(ctx.taskId());
        AgentTool resumed = new AgentTool() {
            public Descriptor descriptor() { return tool.descriptor(); }
            public Result execute(Context current, JsonObject arguments) throws Exception {
                if (current.control() == ctx.control()) throw new IllegalStateException("FRESH_CONTROL_REQUIRED");
                return deferred.continuation().resume(current);
            }
        };
        return fresh.control().await(bridge.execute(resumed, fresh, args, ToolPolicyEngine.digest(actor.owner(), call), call), 10000);
    }
    private AgentTool.Context context(String prompt) throws Exception {
        var task = AgentTask.create(actor.owner(), UUID.randomUUID(), prompt); workspace.conversation().append(List.of(Draft.checkpoint(task)));
        return contextFor(task.id());
    }
    private AgentTool.Context contextFor(UUID task) { return new AgentTool.Context(workspace, actor, new TaskControl(120000), task); }
    private static JsonObject arguments(MaidControlPlan plan) { var args = new JsonObject(); args.addProperty("planId", plan.id().toString()); args.addProperty("planHash", plan.hash()); return args; }
    private static JsonObject statusArguments(UUID id) { var args = new JsonObject(); args.addProperty("operationId", id.toString()); return args; }
    private ServerPlayer player() {
        UUID id = UUID.randomUUID(); var cookie = new CommonListenerCookie(new GameProfile(id, "maid_" + id.toString().substring(0, 8)), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        var player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND); channels.add(new EmbeddedChannel(connection));
        NetworkRegistry.configureMockConnection(connection); server.getPlayerList().placeNewPlayer(connection, player, cookie);
        ValidationHandshake.reply(player); ValidationHandshake.authenticateFixture(player); player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent(); player.setNoGravity(true); return player;
    }
    private boolean bool(String method) throws Exception { return (boolean) maid.getClass().getMethod(method).invoke(maid); }
    private JsonObject diagnostics() throws Exception {
        var data = new JsonObject(); data.addProperty("maidTicks", maid.tickCount); data.addProperty("noAi", maid.isNoAi());
        data.addProperty("distanceSquared", maid.distanceToSqr(owner)); data.addProperty("maidPosition", maid.position().toString());
        data.addProperty("ownerPosition", owner.position().toString()); data.addProperty("ownerResolved", maid.getOwner() == owner);
        data.addProperty("home", bool("isHomeModeEnable")); data.addProperty("seated", bool("isMaidInSittingPose"));
        data.addProperty("brainMoving", bool("canBrainMoving")); data.addProperty("navigationDone", maid.getNavigation().isDone());
        data.addProperty("schedule", maid.getClass().getMethod("getSchedule").invoke(maid).toString());
        var chunk = server.overworld().getChunkSource().getChunkNow(maid.chunkPosition().x, maid.chunkPosition().z);
        data.addProperty("chunkLoaded", chunk != null);
        if (chunk != null) data.addProperty("chunkStatus", chunk.getFullStatus().name());
        return data;
    }
    private <T> T main(Callable<T> action) throws Exception {
        if (server.isSameThread()) return action.call(); var result = new CompletableFuture<T>();
        server.execute(() -> { try { result.complete(action.call()); } catch (Exception failure) { result.completeExceptionally(failure); } });
        return result.get(10, TimeUnit.SECONDS);
    }
    private void await(BooleanSupplier predicate, String check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!predicate.getAsBoolean()) { if (System.nanoTime() >= end) throw new IllegalStateException("MAID_WAIT_TIMEOUT:" + check); Thread.sleep(20); }
        passed.add(check);
    }
    private void expectFailure(Callable<?> action) throws Exception {
        try {
            var value = action.call(); if (value instanceof AgentTool.Result response && !response.success()) { passed.add("native-control-explicit-refusal"); return; }
        } catch (Exception expected) { passed.add("native-control-explicit-refusal"); return; }
        throw new IllegalStateException("EXPECTED_NATIVE_MAID_REFUSAL");
    }
    private void check(boolean condition, String check) { if (!condition) throw new IllegalStateException(check); passed.add(check); }
    public JsonObject tick() {
        inventory.tick(); dispatcher.tick(); control.tick(); farm.tick(); ticks.incrementAndGet();
        if (!result.isDone()) return null;
        var report = result.join(); report.addProperty("ticks", ticks.get()); return report;
    }
    @Override public void close() throws Exception {
        farm.close(); control.close(); if (!control.awaitStopped(10000)) throw new IllegalStateException("MAID_CONTROL_EVIDENCE_SHUTDOWN_TIMEOUT");
        inventory.close(); dispatcher.close(); dispatcher.awaitEvidenceStopped(10000);
        maid.discard(); server.getPlayerList().remove(owner); server.getPlayerList().remove(other);
        channels.forEach(EmbeddedChannel::finishAndReleaseAll); manager.close();
        if (ownsWorldTracker) { worldTracker.close(); ConstructionWorldEvents.remove(server, worldTracker); }
    }
}
