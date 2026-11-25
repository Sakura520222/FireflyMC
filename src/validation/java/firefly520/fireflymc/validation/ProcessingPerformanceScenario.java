package firefly520.fireflymc.validation;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.AiServerService;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage.ToolCall;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Actual dedicated components and native saves, with ten authenticated mock connections; no LLM latency claim. */
public final class ProcessingPerformanceScenario implements AutoCloseable {
    private static final int PLAYERS = 10, ROUNDS = 8, WARMUP_TICKS = 100, INPUT_STACKS = 32;
    private final boolean forceFixtureSaves = Boolean.getBoolean("fireflymc.validation.forceFixtureSaves");
    private final MinecraftServer server;
    private final WorkspaceManager workspaces;
    private final MaterialLedger ledger;
    private final MaterialInventoryService inventory;
    private final VirtualProcessingService processing;
    private final ToolRegistry tools = new ToolRegistry();
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64),
            Thread.ofPlatform().daemon(true).name("ai4-load-tools-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private final GameToolBridge bridge;
    private final CompletableFuture<Void> warm = new CompletableFuture<>();
    private final CompletableFuture<JsonObject> result;
    private final List<Double> rollingMspt = new ArrayList<>();
    private final List<Double> planLatency = new ArrayList<>(), initialLatency = new ArrayList<>(), completionLatency = new ArrayList<>();
    private final List<Integer> waitingIoWorkers = new ArrayList<>();
    private final List<UUID> operations = new ArrayList<>();
    private final AtomicInteger deferredCount = new AtomicInteger();
    private final AtomicInteger completedOperations = new AtomicInteger();
    private final Map<String, Integer> deferredPhases = new LinkedHashMap<>();
    private int fixtureSaveRequests;
    private int ticks, maximumActiveIo, dispatcherOver2Millis, inventoryOver2Millis;
    private long peakHeap;
    private double maximumInventoryTickMillis, maximumDispatcherTickMillis;
    private volatile String phase = "WARMUP";
    private record Session(ServerPlayer player, Actor actor, WorkspaceManager.Workspace workspace) {}
    private record Invocation(Session session, AgentTool.Context context, ToolCall call, JsonObject arguments,
                              long started, AtomicLong finished, CompletableFuture<AgentTool.Result> future) {}

    public ProcessingPerformanceScenario(MinecraftServer server) throws Exception {
        if (!server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_PERFORMANCE_REQUIRED");
        this.server = server;
        Path root = Files.createTempDirectory(server.getWorldPath(LevelResource.ROOT).toAbsolutePath(), "ai4-processing-load-");
        workspaces = new WorkspaceManager(root); ledger = new MaterialLedger(root);
        inventory = new MaterialInventoryService(server, AiServerService::authenticated, new InstalledPermissionAdapters(server));
        processing = new VirtualProcessingService(server, inventory, ledger, () -> 1); processing.register(tools);
        for (int i = 0; i < PLAYERS; i++) {
            var player = createPlayer(); var actor = Actor.player(player.getUUID(), true, true, 0);
            var workspace = workspaces.getWorkspace(actor);
            inventory.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis));
            inventory.bindPreferences(actor.owner(), new MaterialPreferences(workspace));
            sessions.put(actor.owner(), new Session(player, actor, workspace));
        }
        bridge = new GameToolBridge(server, identity -> {
            var session = sessions.get(identity);
            if (session == null) return new Actor(identity, false, false, 0);
            return session.actor(); // Native inventory access rechecks the actual authenticated player on main.
        }, name -> tools.get(name) != null, () -> false, io);
        result = CompletableFuture.supplyAsync(this::measure, action -> Thread.ofPlatform().daemon(true).name("ai4-processing-load-driver").start(action));
    }
    private ServerPlayer createPlayer() {
        UUID id = UUID.randomUUID();
        var cookie = new CommonListenerCookie(new GameProfile(id, "load_" + id.toString().substring(0, 8)), 0,
                ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        var player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND); channels.add(new EmbeddedChannel(connection));
        NetworkRegistry.configureMockConnection(connection); server.getPlayerList().placeNewPlayer(connection, player, cookie);
        ValidationHandshake.reply(player); ValidationHandshake.authenticateFixture(player);
        player.setGameMode(GameType.SURVIVAL); player.setNoGravity(true); player.getInventory().clearContent();
        for (int slot = 0; slot < INPUT_STACKS; slot++) player.getInventory().setItem(slot, new ItemStack(Items.OAK_PLANKS, 64));
        return player;
    }
    private JsonObject measure() {
        var report = new JsonObject(); long started = System.nanoTime();
        try {
            warm.get(15, TimeUnit.SECONDS); started = System.nanoTime();
            for (int round = 0; round < ROUNDS; round++) runRound(round);
            phase = "VERIFYING";
            for (var session : sessions.values()) {
                boolean conserved = main(() -> count(session.player(), Items.OAK_PLANKS) == INPUT_STACKS * 64 - ROUNDS * 4
                        && count(session.player(), Items.CRAFTING_TABLE) == ROUNDS && AiServerService.authenticated(session.player()));
                if (!conserved || ledger.list(session.actor()).size() != ROUNDS || !ledger.settled(session.actor().owner()))
                    throw new IllegalStateException("TEN_PLAYER_NATIVE_CONSERVATION_OR_SAVE_FAILED");
                if (ledger.list(session.actor()).stream().anyMatch(op -> !op.owner().equals(session.actor().owner())))
                    throw new IllegalStateException("LOAD_PLAYER_ISOLATION_FAILED");
            }
            if (deferredCount.get() != PLAYERS * ROUNDS) throw new IllegalStateException("LOAD_MUST_OBSERVE_EVERY_REAL_INPUT_SAVE_WAIT");
            report.addProperty("status", "PASSED");
        } catch (Throwable failure) {
            failure.printStackTrace(); report.addProperty("status", "FAILED"); report.addProperty("error", failure.toString());
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        report.addProperty("phase", phase); report.addProperty("durationMillis", millis);
        report.addProperty("authenticatedMockPlayers", PLAYERS); report.addProperty("rounds", ROUNDS);
        report.addProperty("plannedOperations", operations.size()); report.addProperty("completedNativeOperations", completedOperations.get());
        report.addProperty("observedInputSaveWaits", deferredCount.get());
        report.add("deferredPhases", AiJson.GSON.toJsonTree(deferredPhases));
        report.addProperty("forceFixtureSaves", forceFixtureSaves); report.addProperty("fixtureSaveRequests", fixtureSaveRequests);
        report.addProperty("usesProductionNativeSavePump", true);
        report.add("nativeSaveDispatch", AiJson.GSON.toJsonTree(inventory.nativeSaves().dispatchMetrics()));
        report.addProperty("bridgeIoWorkers", 4); report.add("ioActiveWhileAllCallsDeferred", AiJson.GSON.toJsonTree(waitingIoWorkers));
        report.add("planningLatencyMillis", stats(planLatency)); report.add("initialDispatchLatencyMillis", stats(initialLatency));
        report.add("operationLatencyWithCoordinatedNativeSavesMillis", stats(completionLatency));
        report.addProperty("throughputOperationsPerSecondWithCoordinatedNativeSaves", millis == 0 ? 0 : completedOperations.get() * 1000.0 / millis);
        report.addProperty("performanceAcceptance", "MEASURED_NOT_CLAIMED_AS_LONG_DURATION_TPS_OR_HARD_TICK_BUDGET_PASS");
        report.addProperty("maximumHeapBytes", Runtime.getRuntime().maxMemory()); report.addProperty("os", System.getProperty("os.name"));
        report.add("loadedMods", AiJson.GSON.toJsonTree(ModList.get().getMods().stream().map(mod -> Map.of("id", mod.getModId(), "version", mod.getVersion().toString())).toList()));
        report.addProperty("scope", "Actual production inventory scan, recipe planner, GameToolBridge, MATERIALS_ONLY crafting callbacks, ledgers and player/stat/advancement saves with ten authenticated mock players. Each owner has one in-flight operation. "
                + (forceFixtureSaves ? "Explicit fixture saves are enabled; their latency is not production save-pump evidence. "
                        : "Canonical saves are requested only by the real production native-save pump; the fixture does not force saves. ")
                + "These are not ordinary autosave, AgentRuntime/LLM, real-client, full-pack, FPS or long-duration TPS acceptance results.");
        return report;
    }
    private void runRound(int round) throws Exception {
        phase = "PLANNING_" + round;
        var plans = new ArrayList<Invocation>();
        for (var session : sessions.values()) {
            var task = AgentTask.create(session.actor().owner(), UUID.randomUUID(), "Concurrent native crafting load round " + round);
            session.workspace().conversation().append(List.of(Draft.checkpoint(task)));
            var ctx = new AgentTool.Context(session.workspace(), session.actor(), new TaskControl(120000), task.id());
            var args = new JsonObject(); args.addProperty("recipeId", "minecraft:crafting_table");
            plans.add(invoke(session, ctx, "processing_plan", args));
        }
        var executions = new ArrayList<Invocation>();
        for (var planned : plans) {
            var value = planned.future().get(60, TimeUnit.SECONDS); planLatency.add((planned.finished().get() - planned.started()) / 1_000_000.0);
            if (!value.success()) throw new IllegalStateException("LOAD_PLAN_FAILED:" + value.errorCode());
            var args = new JsonObject(); args.addProperty("planId", value.observations().get("planId").getAsString());
            args.addProperty("planHash", value.observations().get("planHash").getAsString());
            operations.add(UUID.fromString(args.get("planId").getAsString()));
            executions.add(invoke(planned.session(), planned.context(), "processing_execute", args));
        }
        phase = "INPUT_SAVE_WAIT_" + round;
        var waits = new LinkedHashMap<Invocation, AgentTool.Deferred>();
        for (var invoked : executions) {
            try { invoked.future().get(60, TimeUnit.SECONDS); throw new IllegalStateException("LOAD_INPUT_SAVE_WAIT_NOT_OBSERVED"); }
            catch (ExecutionException failure) {
                AgentTool.Deferred wait = deferred(failure);
                if (wait == null) throw failure;
                if (!"WAITING_INPUT_SAVE".equals(wait.reason())) throw new IllegalStateException("LOAD_INITIAL_INPUT_SAVE_PHASE_MISSING:" + wait.reason());
                waits.put(invoked, wait); deferredCount.incrementAndGet(); deferredPhases.merge(wait.reason(), 1, Integer::sum);
                initialLatency.add((invoked.finished().get() - invoked.started()) / 1_000_000.0);
            }
            var held = ledger.get(invoked.session().actor(), UUID.fromString(invoked.arguments().get("planId").getAsString()));
            // The production pump may finish the input proof while the driver collects other players'
            // waits. No continuation has run yet, so custody must still be intact and no output exist.
            if (held.state() != MaterialLedger.State.ESCROWED || !held.products().isEmpty())
                throw new IllegalStateException("LOAD_OUTPUT_VISIBLE_BEFORE_NATIVE_INPUT_SAVE");
        }
        for (int continuation = 0; !waits.isEmpty(); continuation++) {
            if (continuation >= 8) throw new IllegalStateException("LOAD_DEFERRED_PHASE_BUDGET");
            waitingIoWorkers.add(io.getActiveCount()); saveNative(); phase = "CONTINUING_" + round + "_" + continuation;
            var completions = new LinkedHashMap<Invocation, CompletableFuture<AgentTool.Result>>();
            for (var entry : waits.entrySet()) {
                var invoked = entry.getKey(); var wait = entry.getValue(); var ctx = invoked.context();
                ctx.control().check(); wait.readiness().toCompletableFuture().get(30, TimeUnit.SECONDS); ctx.control().check();
                AgentTool resumed = new AgentTool() {
                    public Descriptor descriptor() { return tools.get("processing_execute").descriptor(); }
                    public Result execute(Context fresh, JsonObject args) throws Exception {
                        if (fresh.control() != invoked.context().control() || !fresh.taskId().equals(invoked.context().taskId()))
                            throw new IllegalStateException("LOAD_ACCEPTED_CONTROL_CHANGED");
                        return wait.continuation().resume(fresh);
                    }
                };
                completions.put(invoked, bridge.execute(resumed, ctx, invoked.arguments(),
                        ToolPolicyEngine.digest(ctx.actor().owner(), invoked.call()), invoked.call()));
            }
            waits = new LinkedHashMap<>();
            for (var entry : completions.entrySet()) {
                var invoked = entry.getKey(); invoked.context().control().check();
                try {
                    var value = entry.getValue().get(60, TimeUnit.SECONDS); invoked.context().control().check();
                    if (!value.success()) throw new IllegalStateException("LOAD_CRAFTING_FAILED:" + value.errorCode());
                } catch (ExecutionException failure) {
                    AgentTool.Deferred wait = deferred(failure);
                    if (wait == null) throw failure;
                    waits.put(invoked, wait); deferredPhases.merge(wait.reason(), 1, Integer::sum);
                }
            }
        }
        saveNative();
        for (var invoked : executions) {
            invoked.context().control().check();
            UUID id = UUID.fromString(invoked.arguments().get("planId").getAsString());
            inventory.nativeSaves().awaitJournalled(id).toCompletableFuture().get(30, TimeUnit.SECONDS);
            if (!ledger.get(invoked.session().actor(), id).saved()) throw new IllegalStateException("LOAD_OUTPUT_SAVE_PROOF_MISSING");
            completedOperations.incrementAndGet();
            completionLatency.add(elapsed(invoked.started()));
        }
        System.out.println("AI4_PERFORMANCE completedRound=" + (round + 1) + " players=" + PLAYERS);
    }
    private static AgentTool.Deferred deferred(Throwable failure) {
        for (Throwable current = failure; current != null && current.getCause() != current; current = current.getCause())
            if (current instanceof AgentTool.Deferred wait) return wait;
        return null;
    }
    private Invocation invoke(Session session, AgentTool.Context context, String tool, JsonObject args) {
        var call = new ToolCall(UUID.randomUUID().toString(), tool, args.toString()); long started = System.nanoTime();
        var finished = new AtomicLong();
        var future = bridge.execute(tools.get(tool), context, args, ToolPolicyEngine.digest(session.actor().owner(), call), call)
                .whenComplete((value, failure) -> finished.set(System.nanoTime()));
        return new Invocation(session, context, call, args, started, finished, future);
    }
    private void saveNative() throws Exception {
        if (!forceFixtureSaves) return;
        fixtureSaveRequests++;
        main(() -> {
            server.getPlayerList().saveAll();
            for (var session : sessions.values()) { session.player().getStats().save(); session.player().getAdvancements().save(); }
            for (var level : server.getAllLevels()) level.save(null, true, false);
            return true;
        });
        IOUtilities.waitUntilIOWorkerComplete();
    }
    private <T> T main(Callable<T> action) throws Exception {
        var future = new CompletableFuture<T>(); server.execute(() -> { try { future.complete(action.call()); } catch (Throwable failure) { future.completeExceptionally(failure); } });
        return future.get(30, TimeUnit.SECONDS);
    }
    private static int count(ServerPlayer player, Item item) {
        int count = 0; for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
            if (player.getInventory().getItem(slot).is(item)) count += player.getInventory().getItem(slot).getCount();
        return count;
    }
    private static double elapsed(long started) { return (System.nanoTime() - started) / 1_000_000.0; }
    private static JsonObject stats(List<Double> samples) {
        var sorted = samples.stream().sorted().toList(); var result = new JsonObject(); result.addProperty("samples", sorted.size());
        result.addProperty("mean", samples.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        for (int percentile : new int[]{50, 95, 99, 100}) result.addProperty("p" + percentile,
                sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * percentile / 100.0) - 1)));
        return result;
    }
    public JsonObject tick() {
        ticks++; long started = System.nanoTime(); inventory.tick(); double inventoryMillis = elapsed(started);
        maximumInventoryTickMillis = Math.max(maximumInventoryTickMillis, inventoryMillis); if (inventoryMillis > 2) inventoryOver2Millis++;
        started = System.nanoTime(); processing.tick(); double dispatchMillis = elapsed(started);
        maximumDispatcherTickMillis = Math.max(maximumDispatcherTickMillis, dispatchMillis); if (dispatchMillis > 2) dispatcherOver2Millis++;
        maximumActiveIo = Math.max(maximumActiveIo, io.getActiveCount());
        peakHeap = Math.max(peakHeap, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        if (ticks == WARMUP_TICKS) warm.complete(null);
        if (ticks >= WARMUP_TICKS && ticks % 20 == 0) rollingMspt.add(server.getAverageTickTimeNanos() / 1_000_000.0);
        if (!result.isDone()) return null;
        var report = result.join(); report.addProperty("warmupTicks", WARMUP_TICKS); report.addProperty("workloadTicks", ticks - WARMUP_TICKS);
        report.add("rollingMsptSamples", AiJson.GSON.toJsonTree(rollingMspt)); report.add("rollingMspt", stats(rollingMspt));
        report.addProperty("maximumInventoryTickMillis", maximumInventoryTickMillis); report.addProperty("maximumDispatcherTickMillis", maximumDispatcherTickMillis);
        report.addProperty("inventoryTicksOver2Millis", inventoryOver2Millis); report.addProperty("dispatcherTicksOver2Millis", dispatcherOver2Millis);
        report.addProperty("observedPeakUsedHeapBytes", peakHeap); report.addProperty("observedPeakActiveBridgeIo", maximumActiveIo); return report;
    }
    @Override public void close() throws Exception {
        processing.close(); if (!processing.awaitEvidenceStopped(10000)) throw new IllegalStateException("LOAD_NATIVE_WRITER_SHUTDOWN_TIMEOUT");
        inventory.close(); io.shutdown(); if (!io.awaitTermination(10000, TimeUnit.MILLISECONDS)) throw new IllegalStateException("LOAD_TOOL_IO_SHUTDOWN_TIMEOUT");
        sessions.values().forEach(session -> server.getPlayerList().remove(session.player())); channels.forEach(EmbeddedChannel::finishAndReleaseAll); workspaces.close();
    }
}
