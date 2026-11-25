package firefly520.fireflymc.validation;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.AiServerService;
import firefly520.fireflymc.ai.blueprint.ConstructionWorldEvents;
import firefly520.fireflymc.ai.blueprint.WorldMutationTracker;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.integration.maid.MaidCombatPlan;
import firefly520.fireflymc.ai.integration.maid.MaidCombatService;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage.ToolCall;
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
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Isolated real native melee; fixture setup is never a production tool or an AI cost waiver. */
public final class MaidCombatScenario implements AutoCloseable {
    private final MinecraftServer server;
    private final WorkspaceManager manager;
    private final WorkspaceManager.Workspace workspace;
    private final MaterialInventoryService inventory;
    private final MaterialLedger ledger;
    private final VirtualProcessingService dispatcher;
    private final MaidCombatService combat;
    private final ToolRegistry tools = new ToolRegistry();
    private final GameToolBridge bridge;
    private final ServerPlayer owner, other;
    private final Actor actor, foreign;
    private final TamableAnimal maid;
    private final Husk target;
    private final WorldMutationTracker tracker;
    private final boolean ownsTracker;
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<String> passed = new ArrayList<>();
    private final List<Long> actualHitTicks = new CopyOnWriteArrayList<>();
    private final CompletableFuture<JsonObject> result;
    private float previousHealth;
    private long ticks;
    private final boolean forceFixtureSaves = Boolean.getBoolean("fireflymc.validation.forceFixtureSaves");
    private int fixtureSaveRequests;

    public MaidCombatScenario(MinecraftServer server) throws Exception {
        if (!server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_MAID_COMBAT_SCENARIO_REQUIRED");
        this.server = server;
        WorldMutationTracker current; boolean owned;
        try { current = ConstructionWorldEvents.tracker(server); owned = false; }
        catch (IllegalStateException absent) {
            if (!"WORLD_MUTATION_TRACKER_UNAVAILABLE".equals(absent.getMessage())) throw absent;
            current = new WorldMutationTracker(); ConstructionWorldEvents.install(server, current); owned = true;
        }
        tracker = current; ownsTracker = owned;
        owner = player(); other = player();
        actor = Actor.player(owner.getUUID(), true, true, 0); foreign = Actor.player(other.getUUID(), true, true, 0);
        Path root = Files.createTempDirectory(server.getWorldPath(LevelResource.ROOT).toAbsolutePath(), "ai4-maid-combat-");
        manager = new WorkspaceManager(root); workspace = manager.getWorkspace(actor); ledger = new MaterialLedger(root);
        var permissions = new InstalledPermissionAdapters(server);
        inventory = new MaterialInventoryService(server, AiServerService::authenticated, permissions);
        inventory.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis));
        inventory.bindPreferences(actor.owner(), new MaterialPreferences(workspace));
        dispatcher = new VirtualProcessingService(server, inventory, ledger, () -> 1);
        combat = new MaidCombatService(server, inventory, permissions, dispatcher, () -> 1, ledger); combat.register(tools);

        int y = server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
        // The isolated validation world's ground only. Production always uses native approved placement.
        for (int x = -2; x <= 12; x++) for (int z = -3; z <= 5; z++) {
            server.overworld().setBlockAndUpdate(new BlockPos(x, y - 1, z), Blocks.STONE.defaultBlockState());
            for (int dy = 0; dy < 4; dy++) server.overworld().setBlockAndUpdate(new BlockPos(x, y + dy, z), Blocks.AIR.defaultBlockState());
        }
        owner.moveTo(.5, y, .5); other.moveTo(.5, y, 3.5);
        Entity entity = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("touhou_little_maid:maid")).create(server.overworld());
        if (!(entity instanceof TamableAnimal pet)) throw new IllegalStateException("ACTUAL_MAID_ENTITY_REQUIRED");
        maid = pet; maid.tame(owner); maid.moveTo(5.5, y, .5);
        maid.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SWORD));
        maid.getClass().getMethod("setPickup", boolean.class).invoke(maid, false);
        if (!server.overworld().addFreshEntity(maid)) throw new IllegalStateException("ACTUAL_MAID_FIXTURE_JOIN_DENIED");
        Object schedule = maid.getClass().getMethod("getSchedulePos").invoke(maid);
        schedule.getClass().getMethod("setHomeModeEnable", maid.getClass(), BlockPos.class).invoke(schedule, maid, maid.blockPosition());
        maid.getClass().getMethod("setHomeModeEnable", boolean.class).invoke(maid, true);
        // SchedulePos.setHomeModeEnable calls restrictTo, but that method is a no-op until
        // the separate native home flag is true. The ordinary 40-tick refresh has not run yet.
        schedule.getClass().getMethod("restrictTo", maid.getClass()).invoke(schedule, maid);
        if (!(boolean) maid.getClass().getMethod("isHomeModeEnable").invoke(maid)
                || !maid.blockPosition().equals(maid.getClass().getMethod("getRestrictCenter").invoke(maid)))
            throw new IllegalStateException("NATIVE_COMBAT_HOME_FIXTURE_NOT_INITIALIZED");
        if (inventory.maids().bindOwned(owner, owner.getUUID(), maid.getUUID()).binding() == null) throw new IllegalStateException("ACTUAL_MAID_BINDING_REQUIRED");
        target = new Husk(EntityType.HUSK, server.overworld());
        target.setNoAi(true); target.setPersistenceRequired(); target.moveTo(6.6, y, .5);
        // Guaranteed native equipment drop, instead of asserting a particular randomized loot roll.
        target.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS)); target.setDropChance(EquipmentSlot.FEET, 1);
        if (!server.overworld().addFreshEntity(target)) throw new IllegalStateException("ACTUAL_HOSTILE_FIXTURE_JOIN_DENIED");
        previousHealth = target.getHealth();
        bridge = new GameToolBridge(server, identity -> {
            try { return main(() -> {
                ServerPlayer connected = server.getPlayerList().getPlayer(UUID.fromString(identity));
                return connected == null ? new Actor(identity, false, false, 0) : Actor.player(connected.getUUID(), AiServerService.authenticated(connected), true, 0);
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
            report.addProperty("forceFixtureSaves", forceFixtureSaves);
            report.addProperty("fixtureSaveRequests", fixtureSaveRequests);
            report.add("nativeSaveDispatch", AiJson.GSON.toJsonTree(inventory.nativeSaves().dispatchMetrics()));
            report.addProperty("scope", "Actual TLM 1.5.3 native single-target melee, real Husk/sword/cooldown/ground drops and canonical saves in an isolated dedicated world with authenticated mock network players. No spell/AoE/addon/full-modpack or graphical-client claim.");
            return report;
        }, action -> Thread.ofPlatform().daemon(true).name("maid-native-combat-validation").start(action));
    }

    private void run() throws Exception {
        combat.prepare(workspace);
        check(main(() -> AiServerService.authenticated(owner) && AiServerService.authenticated(other)), "two-players-use-real-firefly-auth");
        var originalPosition = main(owner::position);
        var originalInventory = main(() -> owner.getInventory().items.stream().map(stack -> ItemSnapshotCodec.capture(stack, owner.registryAccess())).toList());
        float healthBeforeCancellation = main(target::getHealth);
        var cancelContext = context("Cancel approved native combat before its first strike");
        var cancelledPlan = combat.plan(cancelContext, target.getUUID(), 6, 400);
        var cancelled = start(cancelContext, cancelledPlan);
        check(cancelled.reason().equals("WAITING_NATIVE_MAID_COMBAT"), "native-combat-initially-yields-for-real-ai-readiness");
        cancelContext.control().stop("CANCELLED");
        await(() -> settled(cancelledPlan.id()), "cancelled-navigation-closes-no-hit-journal-without-replay");
        check(main(() -> target.getHealth() == healthBeforeCancellation && maid.getMainHandItem().getDamageValue() == 0), "cancel-before-first-hit-preserves-real-target-and-weapon");

        var ctx = context("Native finite melee against one real loaded hostile");
        var plan = combat.plan(ctx, target.getUUID(), 8, 600);
        expectFailure(() -> combat.load(workspace, foreign, plan.id(), plan.hash()), "foreign-player-cannot-load-private-combat-plan");
        var tool = tools.get("maid_combat_execute"); var args = arguments(plan); var call = new ToolCall("native-combat-" + plan.id(), tool.descriptor().name(), args.toString());
        check(new ToolPolicyEngine().check(actor, actor.owner(), tool.descriptor(), true, call, "").decision() == ToolPolicyEngine.Decision.APPROVAL,
                "combat-requires-own-fixed-plan-approval");
        double speed = main(() -> maid.getAttributeValue(Attributes.ATTACK_SPEED));
        var completed = execute(ctx, plan);
        check(completed.success() && completed.observations().get("settled").getAsBoolean() && completed.observations().get("targetDefeatedAndSaved").getAsBoolean(),
                "real-native-target-death-has-positive-canonical-save");
        check(main(() -> !target.isAlive() && maid.getMainHandItem().is(Items.WOODEN_SWORD) && maid.getMainHandItem().getDamageValue() > 0), "native-strikes-charge-real-sword-durability");
        check(actualHitTicks.size() >= 2, "real-hostile-needs-multiple-observed-native-strikes");
        long minimum = speed > 0 ? (long) (20 / speed) : 20;
        for (int i = 1; i < actualHitTicks.size(); i++) check(actualHitTicks.get(i) - actualHitTicks.get(i - 1) >= minimum, "native-attack-speed-cooldown-kept-between-hit-" + i);
        var hits = completed.observations().getAsJsonArray("hits"); int realDrops = 0;
        for (var value : hits) {
            var hit = value.getAsJsonObject();
            check(hit.get("verified").getAsBoolean() && hit.get("nativeProof").getAsString().matches("[0-9a-f]{64}"), "every-real-strike-has-own-observation-and-save");
            realDrops += hit.getAsJsonArray("groundDrops").size();
        }
        check(realDrops > 0 && completed.observations().get("groundDropsAreNotDeliveryReceipts").getAsBoolean(), "real-ground-loot-keeps-native-uuid-without-invented-delivery");
        check(main(() -> owner.position().equals(originalPosition) && owner.getInventory().items.stream().map(stack -> ItemSnapshotCodec.capture(stack, owner.registryAccess())).toList().equals(originalInventory)),
                "combat-does-not-teleport-owner-or-mint-rewards-into-inventory");
        check(!completed.observations().get("processingCostWaiversApply").getAsBoolean() && ledger.find(actor, plan.id()).isEmpty(), "native-combat-is-not-virtual-materials-only-processing");
        var duplicate = combat.execute(new AgentTool.Context(workspace, actor, new TaskControl(120000), ctx.taskId()), plan);
        check(!duplicate.success() && duplicate.errorCode().equals("MAID_COMBAT_ALREADY_RECORDED"), "duplicate-operation-does-not-replay-hit-or-reward");
        check(combat.resourcesSettled(workspace, actor) && workspace.primaryConversationId().equals(plan.conversationId()), "all-native-combat-obligations-settle-in-permanent-main-conversation");
    }

    private AgentTool.Deferred start(AgentTool.Context ctx, MaidCombatPlan plan) throws Exception {
        var tool = tools.get("maid_combat_execute"); var args = arguments(plan); var call = new ToolCall("native-combat-" + plan.id(), tool.descriptor().name(), args.toString());
        try { var actual = bridge.execute(tool, ctx, args, ToolPolicyEngine.digest(actor.owner(), call), call).get(10, TimeUnit.SECONDS); throw new IllegalStateException("NATIVE_COMBAT_WAIT_EXPECTED:" + actual.errorCode()); }
        catch (ExecutionException failed) { if (failed.getCause() instanceof AgentTool.Deferred deferred) return deferred; throw failed; }
    }
    private AgentTool.Result execute(AgentTool.Context ctx, MaidCombatPlan plan) throws Exception {
        AgentTool current = tools.get("maid_combat_execute"); var args = arguments(plan);
        var call = new ToolCall("native-combat-" + plan.id(), current.descriptor().name(), args.toString());
        String approval = ToolPolicyEngine.digest(actor.owner(), call);
        for (int phase = 0; phase < 24; phase++) {
            try { return bridge.execute(current, ctx, args, approval, call).get(10, TimeUnit.SECONDS); }
            catch (ExecutionException failed) {
                if (!(failed.getCause() instanceof AgentTool.Deferred waiting)) throw failed;
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20), nextSave = 0;
                while (!waiting.readiness().toCompletableFuture().isDone()) {
                    if (System.nanoTime() >= end) throw new IllegalStateException("NATIVE_COMBAT_WAIT_TIMEOUT:" + waiting.reason());
                    if (forceFixtureSaves && waiting.reason().contains("SAVE") && System.nanoTime() >= nextSave) { saveNative(); nextSave = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250); }
                    Thread.sleep(20);
                }
                waiting.readiness().toCompletableFuture().get(10, TimeUnit.SECONDS);
                current = new DefinedTool(current.descriptor(), (fresh, ignored) -> waiting.continuation().resume(fresh));
            }
        }
        throw new IllegalStateException("NATIVE_COMBAT_PHASE_BUDGET_EXHAUSTED");
    }
    private void saveNative() throws Exception {
        if (!forceFixtureSaves) return;
        fixtureSaveRequests++;
        main(() -> {
            for (var level : server.getAllLevels()) { level.save(null, true, false); level.getDataStorage().save(); }
            server.getPlayerList().saveAll();
            for (var player : List.of(owner, other)) { player.getStats().save(); player.getAdvancements().save(); }
            return true;
        });
        IOUtilities.waitUntilIOWorkerComplete();
    }
    private AgentTool.Context context(String prompt) throws Exception {
        var task = AgentTask.create(actor.owner(), UUID.randomUUID(), prompt); workspace.conversation().append(List.of(Draft.checkpoint(task)));
        return new AgentTool.Context(workspace, actor, new TaskControl(120000), task.id());
    }
    private boolean settled(UUID id) { try { return combat.operationSettled(workspace, actor, id); } catch (Exception failed) { throw new CompletionException(failed); } }
    private static JsonObject arguments(MaidCombatPlan plan) { var args = new JsonObject(); args.addProperty("planId", plan.id().toString()); args.addProperty("planHash", plan.hash()); return args; }
    private ServerPlayer player() {
        UUID id = UUID.randomUUID(); var cookie = new CommonListenerCookie(new GameProfile(id, "combat_" + id.toString().substring(0, 8)), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        var player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND); channels.add(new EmbeddedChannel(connection)); NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie); ValidationHandshake.reply(player); ValidationHandshake.authenticateFixture(player);
        player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent(); player.setNoGravity(true); return player;
    }
    private JsonObject diagnostics() throws Exception {
        var data = new JsonObject(); data.addProperty("maidPosition", maid.position().toString()); data.addProperty("targetPosition", target.position().toString());
        data.addProperty("maidHealth", maid.getHealth()); data.addProperty("targetHealth", target.getHealth()); data.addProperty("targetRemoved", target.isRemoved());
        data.addProperty("weaponDamage", maid.getMainHandItem().getDamageValue()); data.addProperty("brainMoving", (boolean) maid.getClass().getMethod("canBrainMoving").invoke(maid));
        data.addProperty("home", (boolean) maid.getClass().getMethod("isHomeModeEnable").invoke(maid));
        data.addProperty("restrictCenter", maid.getClass().getMethod("getRestrictCenter").invoke(maid).toString());
        data.addProperty("restrictRadius", (float) maid.getClass().getMethod("getRestrictRadius").invoke(maid));
        data.add("hitTicks", AiJson.GSON.toJsonTree(actualHitTicks)); return data;
    }
    private <T> T main(Callable<T> action) throws Exception {
        if (server.isSameThread()) return action.call(); var future = new CompletableFuture<T>();
        server.execute(() -> { try { future.complete(action.call()); } catch (Throwable failed) { future.completeExceptionally(failed); } });
        return future.get(10, TimeUnit.SECONDS);
    }
    private void await(BooleanSupplier condition, String name) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) { if (System.nanoTime() >= end) throw new IllegalStateException("NATIVE_COMBAT_WAIT_TIMEOUT:" + name); Thread.sleep(20); }
        passed.add(name);
    }
    private void expectFailure(Callable<?> action, String name) throws Exception {
        try { action.call(); } catch (Exception expected) { passed.add(name); return; } throw new IllegalStateException("EXPECTED_REFUSAL:" + name);
    }
    private void check(boolean actual, String name) { if (!actual) throw new IllegalStateException(name); passed.add(name); }
    public JsonObject tick() {
        inventory.tick(); dispatcher.tick(); combat.tick(); ticks++;
        float current = target.getHealth(); if (current < previousHealth) actualHitTicks.add(server.overworld().getGameTime()); previousHealth = current;
        if (!result.isDone()) return null; JsonObject report = result.join(); report.addProperty("ticks", ticks); return report;
    }
    @Override public void close() throws Exception {
        combat.close(); if (!combat.awaitStopped(10000)) throw new IllegalStateException("MAID_COMBAT_EVIDENCE_SHUTDOWN_TIMEOUT");
        inventory.close(); dispatcher.close(); if (!dispatcher.awaitEvidenceStopped(10000)) throw new IllegalStateException("MAID_COMBAT_DISPATCH_SHUTDOWN_TIMEOUT");
        maid.discard(); target.discard(); server.getPlayerList().remove(owner); server.getPlayerList().remove(other);
        channels.forEach(EmbeddedChannel::finishAndReleaseAll); manager.close();
        if (ownsTracker) { tracker.close(); ConstructionWorldEvents.remove(server, tracker); }
    }
}
