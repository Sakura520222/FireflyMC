package firefly520.fireflymc.validation;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.AiServerService;
import firefly520.fireflymc.ai.blueprint.ConstructionWorldEvents;
import firefly520.fireflymc.ai.blueprint.WorldMutationTracker;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.integration.trees.*;
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
import net.minecraft.core.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** A real small oak network and native deferred/falling pipeline in an isolated dedicated world. */
public final class TreeHarvestScenario implements AutoCloseable {
    private final MinecraftServer server;
    private final WorkspaceManager manager;
    private final WorkspaceManager.Workspace workspace;
    private final MaterialInventoryService inventory;
    private final MaterialLedger ledger;
    private final VirtualProcessingService dispatcher;
    private final TreeHarvestService trees;
    private final ToolRegistry tools = new ToolRegistry();
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<String> passed = new ArrayList<>();
    private final ServerPlayer owner, other;
    private final Actor actor, foreign;
    private final WorldMutationTracker tracker;
    private final boolean ownsTracker;
    private final BlockPos anchor;
    private final CompletableFuture<JsonObject> result;
    private final boolean forceFixtureSaves = Boolean.getBoolean("fireflymc.validation.forceFixtureSaves");
    private int fixtureSaveRequests;
    private int ticks;

    public TreeHarvestScenario(MinecraftServer server) throws Exception {
        if (!server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_TREE_SCENARIO_REQUIRED");
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
        Path root = Files.createTempDirectory(server.getWorldPath(LevelResource.ROOT).toAbsolutePath(), "ai4-tree-harvest-");
        manager = new WorkspaceManager(root); workspace = manager.getWorkspace(actor); ledger = new MaterialLedger(root);
        var permissions = new InstalledPermissionAdapters(server);
        inventory = new MaterialInventoryService(server, AiServerService::authenticated, permissions);
        inventory.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis));
        inventory.bindPreferences(actor.owner(), new MaterialPreferences(workspace));
        dispatcher = new VirtualProcessingService(server, inventory, ledger, () -> 1);
        trees = new TreeHarvestService(server, inventory, ledger, permissions, dispatcher, () -> 1, () -> true);
        trees.register(tools);
        var level = server.overworld();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            level.getChunk(x, z);
            level.getChunkSource().addRegionTicket(TicketType.FORCED, new ChunkPos(x, z), 2, new ChunkPos(x, z));
        }
        // The validation superflat surface is at y=-60. Native leaf/neighbor survey bounds
        // extend below the root; keep this fixture's entire impact inside the world's real
        // build limits instead of weakening the production outside-world refusal.
        int ground = Math.max(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 8, 8), level.getMinBuildHeight() + 16);
        anchor = new BlockPos(8, ground, 8);
        // Fixture-only setup. Production felling uses actual survival mining and DT's own callbacks.
        for (BlockPos pos : BlockPos.betweenClosed(anchor.offset(-5, 0, -5), anchor.offset(5, 10, 5))) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        for (BlockPos pos : BlockPos.betweenClosed(anchor.offset(-5, -1, -5), anchor.offset(5, -1, 5))) level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 3);
        Object registry = Class.forName("com.dtteam.dynamictrees.tree.species.Species").getField("REGISTRY").get(null);
        Object species = registry.getClass().getMethod("get", String.class).invoke(registry, "dynamictrees:oak");
        boolean rootPlaced = (boolean) species.getClass().getMethod("placeRootyDirtBlock", LevelAccessor.class, BlockPos.class, int.class)
                .invoke(species, level, anchor.below(), 15);
        if (!rootPlaced) throw new IllegalStateException("NATIVE_OAK_ROOT_FIXTURE_REQUIRED");
        Object family = species.getClass().getMethod("getFamily").invoke(species);
        Object branch = ((Optional<?>) family.getClass().getMethod("getBranch").invoke(family)).orElseThrow();
        for (int height = 0; height < 3; height++) branch.getClass().getMethod("setRadius", LevelAccessor.class, BlockPos.class, int.class, Direction.class, int.class)
                .invoke(branch, level, anchor.above(height), 8, Direction.DOWN, 3);
        owner.moveTo(anchor.getX() - 1.5, ground, anchor.getZ() + .5); other.moveTo(32.5, ground, 32.5);
        owner.setNoGravity(false);
        // Authenticated mock connections do not send client movement packets. Resolve a real
        // small downward collision against the fixture dirt instead of forcing onGround or
        // bypassing the genuine airborne survival mining penalty in production.
        owner.move(net.minecraft.world.entity.MoverType.SELF, new net.minecraft.world.phys.Vec3(0, -.05, 0));
        owner.getInventory().setItem(0, new ItemStack(Items.IRON_AXE)); owner.getInventory().selected = 0;
        result = CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime(); var report = new JsonObject();
            try { run(); report.addProperty("status", "PASSED"); }
            catch (Throwable failure) {
                failure.printStackTrace(); report.addProperty("status", "FAILED"); report.addProperty("error", failure.toString());
                try { report.addProperty("treeJournal", trees.exportJournal(workspace, actor)); } catch (Exception ignored) { }
                try { report.add("nativeDiagnostics", diagnostics()); }
                catch (Exception unavailable) { report.addProperty("diagnosticsFailure", unavailable.getClass().getSimpleName()); }
            }
            report.add("passedChecks", AiJson.GSON.toJsonTree(passed));
            report.addProperty("durationMillis", (System.nanoTime() - started) / 1_000_000);
            report.addProperty("forceFixtureSaves", forceFixtureSaves); report.addProperty("fixtureSaveRequests", fixtureSaveRequests);
            report.addProperty("scope", "Actual Dynamic Trees 1.7.2 small oak network, authenticated mock network players, survival deferred break/falling/output and canonical saves. No graphical client, model, Plus mushroom/cactus, all species, claims combination or crash acceptance claim.");
            return report;
        }, action -> Thread.ofPlatform().daemon(true).name("tree-native-validation").start(action));
    }

    private void run() throws Exception {
        trees.prepare(workspace);
        check(main(() -> AiServerService.authenticated(owner) && AiServerService.authenticated(other)), "both-players-use-existing-firefly-auth");
        check(main(() -> owner.onGround() && !server.overworld().getBlockState(owner.blockPosition().below()).isAir()),
                "native-floor-collision-keeps-real-survival-mining-grounded");
        var task = AgentTask.create(actor.owner(), UUID.randomUUID(), "Harvest this real nearby dynamic oak without manual region selection");
        workspace.conversation().append(List.of(Draft.checkpoint(task)));
        var context = new AgentTool.Context(workspace, actor, new TaskControl(120000), task.id());
        var beforePosition = main(owner::position);
        var beforeTool = main(() -> ItemSnapshotCodec.capture(owner.getMainHandItem(), owner.registryAccess()));
        var plan = trees.plan(context, anchor, Direction.WEST);
        check(plan.nativeNodes() >= 3 && plan.species().equals("dynamictrees:oak"), "native-species-node-network-resolves-real-oak");
        check(plan.footprint().size() > plan.nativeNodes() && main(() -> !server.overworld().getBlockState(anchor).isAir()), "preview-binds-full-impact-and-does-not-cut");
        check(main(() -> owner.canInteractWithBlock(anchor, 0)), "nearby-survival-owner-does-not-need-remote-proxy-or-manual-selection");
        expectFailure(() -> trees.load(workspace, foreign, plan.id(), plan.hash()));
        var args = new JsonObject(); args.addProperty("planId", plan.id().toString()); args.addProperty("planHash", plan.hash());
        AgentTool original = tools.get("tree_harvest_execute"), stage = original;
        var call = new ToolCall("native-tree-" + plan.id(), original.descriptor().name(), args.toString());
        var bridge = new GameToolBridge(server, identity -> identity.equals(actor.owner()) ? actor : foreign, name -> tools.get(name) != null, () -> false);
        AgentTool.Result completed = null;
        for (int phase = 0; phase < 4; phase++) {
            AgentTool.Deferred waiting;
            try { completed = bridge.execute(stage, context, args, ToolPolicyEngine.digest(actor.owner(), call), call).get(30, TimeUnit.SECONDS); break; }
            catch (ExecutionException failure) {
                Throwable cause = failure; while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                if (!(cause instanceof AgentTool.Deferred deferred)) throw failure; waiting = deferred;
            }
            check(waiting.reason().equals("NATIVE_TREE_HARVEST"), "native-operation-yields-instead-of-blocking-main-thread");
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!waiting.readiness().toCompletableFuture().isDone()) {
                context.control().check();
                if (System.nanoTime() >= end) throw new IllegalStateException("TREE_NATIVE_OR_SAVE_TIMEOUT");
                saveNative(); Thread.sleep(100);
            }
            waiting.readiness().toCompletableFuture().get(10, TimeUnit.SECONDS);
            stage = new DefinedTool(original.descriptor(), (fresh, ignored) -> waiting.continuation().resume(fresh));
        }
        check(completed != null, "native-tool-final-result-returned");
        check(!"RECOVERY_REQUIRED".equals(completed.errorCode()), "native-deferred-falling-and-save-finished-with-known-outcome:" + completed.errorCode());
        check(completed.success() || "TREE_REAL_PICKUP_REQUIRED".equals(completed.errorCode()), "world-remainder-is-explicitly-not-complete-inventory-delivery");
        check(main(() -> server.overworld().getBlockState(anchor).isAir()), "real-native-branch-is-gone-after-once-only-future-break");
        check(main(() -> owner.getMainHandItem().getDamageValue() > 0), "native-felling-consumes-real-axe-durability");
        check(main(() -> owner.position().equals(beforePosition)), "harvest-does-not-teleport-owner-for-reach-or-pickup");
        check(trees.operationSettled(workspace, actor, plan.id()), "all-accepted-stages-have-canonical-native-save-proofs");
        check(trees.resourcesSettled(workspace, actor), "saved-terminal-releases-real-backend-and-world-fences");
        String journal = trees.exportJournal(workspace, actor);
        check(journal.contains("QUEUED") && journal.contains("FALLING"), "journal-retains-native-deferred-and-falling-transaction-history");
        check(!beforeTool.empty() && journal.contains("fallingEntities") && journal.contains("drops"), "actual-uuid-output-and-real-tool-history-retained");
        var duplicate = trees.execute(context, plan);
        check(duplicate.errorCode().equals(completed.errorCode()), "duplicate-operation-does-not-repeat-native-felling");
    }

    private ServerPlayer player() {
        UUID id = UUID.randomUUID();
        var cookie = new CommonListenerCookie(new GameProfile(id, "tree_" + id.toString().substring(0, 8)), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        var player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND); channels.add(new EmbeddedChannel(connection));
        NetworkRegistry.configureMockConnection(connection); server.getPlayerList().placeNewPlayer(connection, player, cookie);
        ValidationHandshake.reply(player); ValidationHandshake.authenticateFixture(player); player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent(); player.setNoGravity(true); return player;
    }
    private void saveNative() throws Exception {
        if (!forceFixtureSaves) return; // The default scenario must exercise the production canonical-save dispatcher.
        fixtureSaveRequests++;
        IOUtilities.waitUntilIOWorkerComplete();
        main(() -> {
            // ServerLevel.save already schedules this level's SavedData. A second explicit
            // dataStorage.save races NeoForge's asynchronous atomic writes on Windows.
            for (var level : server.getAllLevels()) level.save(null, true, false);
            server.getPlayerList().saveAll();
            for (var player : List.of(owner, other)) { player.getStats().save(); player.getAdvancements().save(); }
            return true;
        });
        IOUtilities.waitUntilIOWorkerComplete();
    }
    /** Private fixture report only: fixed fields, bounded identities, no whole-world/entity NBT scan. */
    private JsonObject diagnostics() throws Exception {
        var operations = trees.prepare(workspace).snapshot(actor);
        var latest = operations.isEmpty() ? null : operations.getLast().observation();
        List<UUID> nativeIds = latest == null ? List.of() : latest.fallingEntities().stream().limit(8).toList();
        List<TreeHarvestNative.RootTickRecord> roots = latest == null ? List.of() : latest.scheduledRootTicks().stream().limit(8).toList();
        List<UUID> completed = latest == null ? List.of() : latest.completedRootTicks();
        return main(() -> {
            var value = new JsonObject(); var level = owner.serverLevel();
            value.addProperty("gameTime", level.getGameTime()); value.addProperty("serverTick", server.getTickCount());
            value.addProperty("ownerOnGround", owner.onGround()); value.addProperty("ownerPosition", owner.position().toString());
            var rootRows = new com.google.gson.JsonArray();
            for (var root : roots) {
                var pos = new BlockPos(root.position().x(), root.position().y(), root.position().z());
                long chunkKey = new ChunkPos(pos).toLong(); var row = new JsonObject();
                row.addProperty("id", root.id().toString()); row.addProperty("completed", completed.contains(root.id()));
                row.addProperty("dueGameTick", root.dueGameTick()); row.addProperty("loaded", level.hasChunkAt(pos));
                row.addProperty("nativeBlockTickChunkKey", chunkKey); row.addProperty("nativeBlockTickRange", level.shouldTickBlocksAt(chunkKey));
                row.addProperty("currentBlock", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
                rootRows.add(row);
            }
            value.add("rootCallbacks", rootRows); var entities = new com.google.gson.JsonArray();
            for (UUID id : nativeIds) {
                var row = new JsonObject(); row.addProperty("id", id.toString()); var entity = level.getEntity(id);
                if (entity == null) { row.addProperty("present", false); entities.add(row); continue; }
                row.addProperty("present", true); row.addProperty("class", entity.getClass().getName()); row.addProperty("tickCount", entity.tickCount);
                row.addProperty("removed", entity.isRemoved()); row.addProperty("onGround", entity.onGround());
                row.addProperty("position", entity.position().toString()); row.addProperty("velocity", entity.getDeltaMovement().toString());
                row.addProperty("boundingBox", entity.getBoundingBox().toString()); row.addProperty("pendingOwnedOutput", DynamicTreeNativeScope.pending(entity));
                Class<?> contract = Class.forName(DynamicTreeAccess.FALLING_TREE);
                for (String fieldName : List.of("landed", "firstUpdate")) {
                    var field = contract.getDeclaredField(fieldName);
                    if (field.trySetAccessible()) row.addProperty(fieldName, field.getBoolean(entity));
                }
                Object animation = contract.getField("currentAnimationHandler").get(entity);
                row.addProperty("animationClass", animation == null ? "null" : animation.getClass().getName());
                Object data = contract.getField("dataAnimationHandler").get(entity);
                row.addProperty("animationDataClass", data == null ? "null" : data.getClass().getName());
                if (data != null) for (String fieldName : List.of("fallSpeed", "bounces")) {
                    try {
                        var field = data.getClass().getDeclaredField(fieldName);
                        if (field.trySetAccessible() && field.get(data) instanceof Number number) row.addProperty(fieldName, number);
                    } catch (NoSuchFieldException absent) { /* Other genuine native animation data does not have this field. */ }
                }
                entities.add(row);
            }
            value.add("fallingEntities", entities); return value;
        });
    }
    private <T> T main(Callable<T> action) throws Exception {
        if (server.isSameThread()) return action.call();
        var future = new CompletableFuture<T>(); server.execute(() -> {
            try { future.complete(action.call()); } catch (Exception failure) { future.completeExceptionally(failure); }
        }); return future.get(10, TimeUnit.SECONDS);
    }
    private void expectFailure(Callable<?> action) throws Exception {
        try { action.call(); } catch (SecurityException expected) { passed.add("other-player-cannot-use-private-plan"); return; }
        throw new IllegalStateException("TREE_FOREIGN_PLAN_SHOULD_BE_DENIED");
    }
    private void check(boolean condition, String name) { if (!condition) throw new IllegalStateException(name); passed.add(name); }
    public JsonObject tick() {
        inventory.tick(); dispatcher.tick(); trees.tick(); ticks++;
        if (!result.isDone()) return null;
        var report = result.join(); report.addProperty("ticks", ticks); return report;
    }
    @Override public void close() throws Exception {
        trees.close(); if (!trees.awaitStopped(10000)) throw new IllegalStateException("TREE_WORKER_SHUTDOWN_TIMEOUT");
        inventory.close(); dispatcher.close(); dispatcher.awaitEvidenceStopped(10000);
        server.getPlayerList().remove(owner); server.getPlayerList().remove(other);
        channels.forEach(EmbeddedChannel::finishAndReleaseAll); manager.close();
        if (ownsTracker) { tracker.close(); ConstructionWorldEvents.remove(server, tracker); }
    }
}
