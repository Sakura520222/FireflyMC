package firefly520.fireflymc.validation;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.network.*;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Two real dedicated processes. A real extraction's IO registration intentionally arrives after native save. */
public final class NativeShutdownScenario {
    private final MinecraftServer server;
    private final String phase = System.getProperty("fireflymc.validation.restartPhase");
    private final UUID run = UUID.fromString(System.getProperty("fireflymc.validation.restartRun"));
    private final Path root, manifestPath;
    private final CountDownLatch releaseEvidence = new CountDownLatch(1);
    private final AtomicBoolean nativeDone = new AtomicBoolean();
    private final JsonObject report = new JsonObject();
    private final List<String> passed = new ArrayList<>();
    private AiServerService service;
    private WorkspaceManager manager;
    private WorkspaceManager.Workspace workspace;
    private MaterialLedger ledger;
    private Actor actor;
    private UUID operation;
    private JsonObject manifest;
    private volatile CompletableFuture<AgentTool.Result> tool;
    private CompletableFuture<Void> preparation;
    private ServerPlayer player;
    private EmbeddedChannel channel;
    private boolean returned;

    public NativeShutdownScenario(MinecraftServer server) {
        if (!server.isDedicatedServer() || !Set.of("prepare", "verify").contains(phase)) throw new IllegalStateException("EXPLICIT_RESTART_SCENARIO_REQUIRED");
        this.server = server; root = server.getWorldPath(LevelResource.ROOT).resolve("data/fireflymc/ai").toAbsolutePath().normalize();
        manifestPath = Path.of("shutdown-fixtures").toAbsolutePath().resolve(run + ".json");
        report.addProperty("phase", phase); report.addProperty("restartRun", run.toString());
        report.addProperty("processId", ProcessHandle.current().pid());
        report.addProperty("vmStartedAt", java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime());
        report.addProperty("scope", "Production AI service lifecycle, real native player save and material extraction; two isolated dedicated processes, mock network client, no model requests or power-loss claim.");
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::releaseAfterNativeSaves);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::verifyStopped);
    }
    public JsonObject tick() throws Exception {
        if (returned) return null;
        if (service == null) {
            service = (AiServerService) field(AiServerEvents.class, null, "SERVICES", Map.class).get(server);
            if (service == null || !service.available()) { service = null; return null; }
            manager = field(AiServerService.class, service, "workspaces", WorkspaceManager.class);
            ledger = field(AiServerService.class, service, "materialLedger", MaterialLedger.class);
            if (phase.equals("verify")) { returned = true; verifyRestart(); report.add("passedChecks", AiJson.GSON.toJsonTree(passed)); return report; }
            if (Files.exists(manifestPath)) throw new IllegalStateException("RESTART_FIXTURE_ALREADY_EXISTS");
            player = player(); actor = Actor.player(player.getUUID(), true, false, 0);
            player.getInventory().clearContent(); player.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
            service.request(player, new AiRequestPayload(AiProtocol.VERSION, UUID.randomUUID(), AiProtocol.NO_SESSION, AiProtocol.Action.HELLO, "{}"));
            preparation = CompletableFuture.runAsync(() -> {
                try { prepare(); } catch (Exception failure) { throw new CompletionException(failure); }
            }, runnable -> Thread.ofPlatform().daemon(true).name("shutdown-validation-prepare").start(runnable));
        }
        if (preparation != null && preparation.isCompletedExceptionally()) preparation.join();
        if (tool != null && tool.isCompletedExceptionally()) tool.join();
        if (tool != null && tool.isDone() && !tool.join().success()) throw new IllegalStateException("TEST_TOOL_REJECTED:" + tool.join().errorCode());
        if (!nativeDone.get()) return null;
        require(player.getInventory().countItem(Items.OAK_PLANKS) == 1, "actual-native-extraction-happened-before-shutdown");
        require(!ledger.get(actor, operation).saved(), "memory-result-is-not-a-native-player-save");
        report.addProperty("status", "PENDING_SHUTDOWN"); report.add("passedChecks", AiJson.GSON.toJsonTree(passed));
        returned = true; return report;
    }
    private void prepare() throws Exception {
        workspace = manager.getWorkspace(actor);
        var inventory = field(AiServerService.class, service, "inventory", MaterialInventoryService.class);
        var processing = field(AiServerService.class, service, "processing", VirtualProcessingService.class);
        inventory.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis));
        inventory.bindPreferences(actor.owner(), new MaterialPreferences(workspace));
        var scan = main(() -> inventory.scan(actor)).get(10, TimeUnit.SECONDS);
        var stock = scan.rows().stream().filter(s -> s.stack().itemJson().contains("minecraft:oak_planks")).findFirst().orElseThrow();
        operation = UUID.randomUUID(); String planHash = AiJson.digest("shutdown-real-extraction:" + run);
        var call = new ModelMessage.ToolCall("shutdown-" + run, "validation_native_extract", "{}");
        var task = AgentTask.create(actor.owner(), UUID.randomUUID(), "Retain real native extraction and permanent conversation across a normal server stop")
                .progress(1, 1, 0, 0, List.of(call), 0, "", true, true).update(AgentTask.State.PAUSED, "USER_PAUSED");
        workspace.conversation().append(List.of(Draft.checkpoint(task), Draft.message(task.id(), new ModelMessage("assistant", "", List.of(call), null, null), call.id())));
        ledger.reserve(actor, task.id(), operation, planHash, List.of(stock), List.of(new MaterialLedger.Need(stock.key(), 1)));
        var prepared = main(() -> inventory.prepareExtraction(actor, stock, 1));
        ledger.beginExtraction(actor, operation, 0, prepared.before(), prepared.poolUnits(), prepared.witness());
        manifest = new JsonObject(); manifest.addProperty("run", run.toString()); manifest.addProperty("owner", actor.owner());
        manifest.addProperty("processId", ProcessHandle.current().pid());
        manifest.addProperty("vmStartedAt", java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime());
        manifest.addProperty("name", player.getGameProfile().getName()); manifest.addProperty("operation", operation.toString()); manifest.addProperty("task", task.id().toString());
        manifest.addProperty("conversation", workspace.primaryConversationId().toString()); manifest.addProperty("server", manager.serverInstanceId().toString()); manifest.addProperty("call", call.id());
        AiJson.write(manifestPath, manifest);
        var evidence = field(VirtualProcessingService.class, processing, "evidenceIo", ExecutorService.class);
        var holding = new CountDownLatch(1);
        evidence.execute(() -> { holding.countDown(); try { if (!releaseEvidence.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_RELEASE_TIMEOUT"); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); } });
        if (!holding.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_EVIDENCE_HOLD_NOT_STARTED");
        var descriptor = new AgentTool.Descriptor(call.name(), "Isolated real extraction fixture", AiJson.object("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}", 1024, 8),
                AgentTool.Risk.WORLD_CHANGE, "materials.own", 0, AgentTool.ExecutionThread.IO, 30000, true, "");
        var nativeTool = new DefinedTool(descriptor, (ctx, args) -> {
            var actual = processing.commitOwned(ctx, () -> {
                var value = inventory.extract(prepared, 1);
                if (!value.mutationEntered() || !value.error().isEmpty() || value.actual().count() != 1) throw new IllegalStateException("TEST_NATIVE_EXTRACTION_FAILED");
                nativeDone.set(true); return value;
            }, value -> {
                var recorded = ledger.extracted(actor, operation, value.actual(), value.poolUnitsAfter());
                inventory.nativeSaves().record(ledger, actor, operation, "extract", prepared.witness(), value.save()); return recorded;
            });
            var data = new JsonObject(); data.addProperty("actuallyExtracted", actual.actual().count());
            return new AgentTool.Result(true, "", "Actual native extraction recorded", data, "native-fixture", "", false);
        });
        tool = service.execute(nativeTool, new AgentTool.Context(workspace, actor, new TaskControl(30000), task.id()), new JsonObject(), ToolPolicyEngine.digest(actor.owner(), call), call)
                .thenApply(result -> {
                    try {
                        workspace.conversation().append(List.of(Draft.message(task.id(), ModelMessage.result(call.id(), AiJson.GSON.toJson(result)), call.id()),
                                Draft.checkpoint(task.progress(1, 1, 0, 0, List.of(call), 1, "", true, true))));
                        return result;
                    } catch (Exception failure) { throw new CompletionException(failure); }
                });
    }
    private void releaseAfterNativeSaves(ServerStoppedEvent event) {
        if (event.getServer() != server || !phase.equals("prepare")) return;
        Thread.ofPlatform().daemon(true).name("shutdown-validation-release").start(() -> {
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            releaseEvidence.countDown();
        });
    }
    private void verifyStopped(ServerStoppedEvent event) {
        if (event.getServer() != server || !phase.equals("prepare") || service == null || !nativeDone.get()) return;
        try {
            var method = AiServerService.class.getMethod("shutdownCompletion");
            ((CompletionStage<?>) method.invoke(service)).whenComplete((ignored, failure) -> finishStopped(failure));
        } catch (NoSuchMethodException oldLifecycle) { finishStopped(null); }
        catch (Exception failure) { finishStopped(failure); }
    }
    private void finishStopped(Throwable error) {
        try {
            if (error != null) throw new IllegalStateException("PRODUCTION_SHUTDOWN_FAILED", error);
            require(tool != null && tool.get(10, TimeUnit.SECONDS).success(), "accepted-native-tool-result-drained-before-process-exit");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!ledger.get(actor, operation).saved() && System.nanoTime() < deadline) Thread.sleep(10);
            require(ledger.get(actor, operation).saved(), "late-evidence-registration-keeps-native-save-proof");
            require(ledger.get(actor, operation).escrow().stream().mapToInt(MaterialStack::count).sum() == 1, "exactly-one-real-item-owned-in-durable-escrow");
            try (var reopened = new WorkspaceManager(root)) {
                require(reopened.getWorkspace(actor).primaryConversationId().equals(workspace.primaryConversationId()), "shutdown-completion-releases-workspace-lock-after-all-history-writes");
            }
            manifest.addProperty("preparedSuccessfully", true); AiJson.write(manifestPath, manifest);
            report.addProperty("status", "PASSED");
        } catch (Throwable failure) { report.addProperty("status", "FAILED"); report.addProperty("error", failure.toString()); }
        report.add("passedChecks", AiJson.GSON.toJsonTree(passed));
        try {
            Path reports = Path.of(System.getProperty("fireflymc.validation.reports"));
            AiJson.write(reports.resolve(report.get("archive").getAsString()), report);
            String profile = report.has("profile") ? report.get("profile").getAsString() : "";
            AiJson.write(reports.resolve("dedicated-shutdown" + (profile.isEmpty() ? "" : "-" + profile) + ".json"), report);
            System.out.println("AI4_SHUTDOWN_VALIDATION " + report.get("status").getAsString());
        } catch (Exception failed) { System.err.println("AI4_SHUTDOWN_REPORT_FAILED " + failed.getClass().getSimpleName()); }
    }
    private void verifyRestart() throws Exception {
        manifest = AiJson.object(Files.readString(manifestPath), 8192, 16);
        require(run.toString().equals(manifest.get("run").getAsString()), "restart-fixture-is-bound-to-this-validation-run");
        require(manifest.get("processId").getAsLong() != ProcessHandle.current().pid()
                || manifest.get("vmStartedAt").getAsLong() != java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime(), "verification-runs-in-a-different-jvm-process");
        require(manifest.has("preparedSuccessfully") && manifest.get("preparedSuccessfully").getAsBoolean(), "previous-process-completed-native-save-and-evidence-drain");
        actor = Actor.player(UUID.fromString(manifest.get("owner").getAsString()), true, false, 0); workspace = manager.getWorkspace(actor);
        operation = UUID.fromString(manifest.get("operation").getAsString());
        require(manager.serverInstanceId().toString().equals(manifest.get("server").getAsString()) && workspace.primaryConversationId().toString().equals(manifest.get("conversation").getAsString()),
                "new-process-keeps-the-same-server-workspace-and-permanent-conversation");
        var nativePlayer = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.fromString(actor.owner()), manifest.get("name").getAsString()), ClientInformation.createDefault());
        require(server.getPlayerList().load(nativePlayer).isPresent() && nativePlayer.getInventory().countItem(Items.OAK_PLANKS) == 1, "real-minecraft-player-data-reloaded-the-post-extraction-inventory");
        var op = ledger.get(actor, operation);
        require(op.saved() && op.escrow().stream().mapToInt(MaterialStack::count).sum() == 1 && op.state() == MaterialLedger.State.RECOVERY_REQUIRED,
                "restart-retains-escrow-and-requires-explicit-positive-reconciliation");
        var review = ledger.recoveryReview(actor, operation); ledger.reconcile(actor, operation, review.revision(), review.reviewHash());
        require(ledger.get(actor, operation).state() == MaterialLedger.State.ESCROWED && nativePlayer.getInventory().countItem(Items.OAK_PLANKS) == 1,
                "proven-reconciliation-does-not-repeat-native-extraction");
        String call = manifest.get("call").getAsString();
        require(workspace.conversation().getSnapshot(0).events().stream().filter(e -> e.message() != null && "tool".equals(e.message().role()) && call.equals(e.message().toolCallId())).count() == 1,
                "complete-accepted-tool-result-survives-once-in-the-primary-history");
        report.addProperty("status", "PASSED");
    }
    private ServerPlayer player() {
        UUID id = UUID.randomUUID(); String name = "stop_" + id.toString().substring(0, 8);
        var cookie = new CommonListenerCookie(new GameProfile(id, name), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        var value = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND); channel = new EmbeddedChannel(connection); NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection, value, cookie); ValidationHandshake.reply(value); ValidationHandshake.authenticateFixture(value);
        value.setGameMode(GameType.SURVIVAL); return value;
    }
    private <T> T main(Callable<T> action) throws Exception {
        var result = new CompletableFuture<T>(); server.execute(() -> { try { result.complete(action.call()); } catch (Exception failed) { result.completeExceptionally(failed); } });
        return result.get(10, TimeUnit.SECONDS);
    }
    private void require(boolean condition, String label) { if (!condition) throw new IllegalStateException(label); passed.add(label); }
    private static <T> T field(Class<?> owner, Object object, String name, Class<T> type) throws Exception {
        var field = owner.getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(object));
    }
    /** The real server stop owns native saves and service shutdown; do not close the fixture early. */
    public void close() {}
}
