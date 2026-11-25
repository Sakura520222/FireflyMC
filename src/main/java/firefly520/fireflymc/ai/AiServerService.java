package firefly520.fireflymc.ai;

import com.google.gson.*;
import firefly520.fireflymc.FireflyMCMod;
import firefly520.fireflymc.ServerConfig;
import firefly520.fireflymc.ai.agent.AgentRuntime;
import firefly520.fireflymc.ai.config.AiConfiguration;
import firefly520.fireflymc.ai.context.ContextCompactor;
import firefly520.fireflymc.ai.context.ManualContextRequests;
import firefly520.fireflymc.ai.conversation.ConversationStore;
import firefly520.fireflymc.ai.memory.MemoryStore;
import firefly520.fireflymc.ai.events.PersonalEventStore;
import firefly520.fireflymc.ai.sharing.ShareService;
import firefly520.fireflymc.ai.knowledge.ServerKnowledgeService;
import firefly520.fireflymc.ai.knowledge.PublicConfigService;
import firefly520.fireflymc.ai.web.*;
import firefly520.fireflymc.ai.model.*;
import firefly520.fireflymc.ai.network.*;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.blueprint.*;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import firefly520.fireflymc.network.ModPayloadHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Dedicated-server player workspace service. UI packets are the only human interaction path. */
public final class AiServerService implements AgentRuntime.Environment {
    private static final class Session {
        final UUID token = UUID.randomUUID();
        final Object connection;
        final AiRequestGate gate = new AiRequestGate(System::currentTimeMillis);
        final Map<UUID, AiResponsePayload> replies = Collections.synchronizedMap(new LinkedHashMap<>());
        volatile boolean closed;
        volatile boolean crossChatReady;
        volatile String playerName;
        Session(Object connection) { this.connection = connection; }
    }
    private final MinecraftServer server;
    private final AiConfiguration configuration;
    private final ThreadPoolExecutor io;
    private final ThreadPoolExecutor toolIo;
    private final ThreadPoolExecutor exportIo;
    private final Set<String> exporting = ConcurrentHashMap.newKeySet();
    private final Map<String, Actor> actors = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, WorkspacePreferences> preferences = new ConcurrentHashMap<>();
    private final Set<String> initializedOwners = ConcurrentHashMap.newKeySet();
    private record Delivery(String owner, UUID token, long expiresAt) {}
    private final Map<UUID, Delivery> deliveries = new ConcurrentHashMap<>();
    private static final class LiveText {
        final String owner;
        final UUID task, stream;
        volatile String text = "";
        volatile long sequence;
        long sent;
        LiveText(String owner, UUID task, UUID stream) { this.owner = owner; this.task = task; this.stream = stream; }
        synchronized void update(String next) {
            next = next.substring(0, Math.min(6000, next.length()));
            if (!next.equals(text)) { text = next; sequence++; }
        }
        synchronized JsonObject view() {
            JsonObject data = json("taskId", task.toString());
            data.addProperty("streamId", stream.toString()); data.addProperty("sequence", sequence);
            data.addProperty("text", text); data.addProperty("cumulative", true);
            while (data.toString().length() > 20000) {
                data.addProperty("text", data.get("text").getAsString().substring(0, data.get("text").getAsString().length() / 2));
                data.addProperty("truncated", true);
            }
            return data;
        }
    }
    private final Map<UUID, LiveText> liveText = new ConcurrentHashMap<>();
    private int streamTicks;
    private final ToolRegistry tools = new ToolRegistry();
    private final SecretFilter secrets;
    private final WorkspacePrivacyService privacy = new WorkspacePrivacyService(System::currentTimeMillis,
            workspace -> materialsSettled(workspace.owner())
                    && companionResourcesSettled(workspace)
                    && !Files.exists(workspace.directory().resolve("build")) && !exporting.contains(workspace.owner()));
    private final GameToolBridge gameBridge;
    private final ContextCompactor compactor;
    private record ManualContextRequest(Workspace workspace, ManualContextRequests.Request reference) {
        UUID requestId() { return reference.requestId(); }
        long privacyEpoch() { return reference.privacyEpoch(); }
    }
    private final Map<String, ManualContextRequest> pendingContext = new ConcurrentHashMap<>();
    /** Accessed only by the single store executor, never by the Minecraft thread. */
    private final ArrayDeque<String> contextOwners = new ArrayDeque<>();
    private final Map<String, Long> lastContextRequest = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean contextSweepQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private final ServerKnowledgeService knowledge;
    private final InstalledPermissionAdapters permissions;
    private final firefly520.fireflymc.ai.integration.environment.EnvironmentFactsService environmentFacts;
    private final MaterialInventoryService inventory;
    private volatile MaterialLedger materialLedger;
    private volatile VirtualProcessingService processing;
    private volatile firefly520.fireflymc.ai.processing.FluidProcessingService fluidProcessing;
    private volatile firefly520.fireflymc.ai.recovery.RecoveryWorkbenchService recovery;
    private volatile firefly520.fireflymc.ai.blueprint.ConstructionService construction;
    private volatile firefly520.fireflymc.ai.blueprint.recovery.WorldRecoveryService worldRecovery;
    private volatile firefly520.fireflymc.ai.processing.advanced.AdvancedProcessingService advancedProcessing;
    private volatile firefly520.fireflymc.ai.processing.special.SpecialProcessingService specialProcessing;
    private volatile firefly520.fireflymc.ai.integration.maid.MaidControlService maidControl;
    private volatile firefly520.fireflymc.ai.integration.maid.MaidFarmService maidFarm;
    private volatile firefly520.fireflymc.ai.integration.maid.MaidCombatService maidCombat;
    private volatile firefly520.fireflymc.ai.integration.trees.TreeHarvestService treeHarvest;
    private volatile firefly520.fireflymc.ai.integration.maid.transport.MaidTransportService maidTransport;
    private volatile firefly520.fireflymc.ai.integration.travel.WaystoneTravelService waystoneTravel;
    private volatile firefly520.fireflymc.ai.integration.exploration.CompassSearchService exploration;
    private final BlueprintService blueprints;
    private final firefly520.fireflymc.ai.blueprint.BuildRegionService buildRegions;
    private final Map<String, firefly520.fireflymc.ai.blueprint.BuildProgressProjection> buildProgress = new ConcurrentHashMap<>();
    private final firefly520.fireflymc.ai.integration.equipment.EquipmentTools equipment;
    private final BlueprintUpload blueprintUploads = new BlueprintUpload(System::currentTimeMillis);
    private record SelectedSource(String owner, long expiresAt, MaterialInventoryService.SourceSelection selection) {}
    private final Map<UUID, SelectedSource> selectedSources = new ConcurrentHashMap<>();
    private final FluidInventoryService fluids;
    private record SelectedFluidSource(String owner, long expiresAt, FluidInventoryService.Selection selection) {}
    private final Map<UUID, SelectedFluidSource> selectedFluidSources = new ConcurrentHashMap<>();
    private final WebResearchService research;
    private volatile WorkspaceManager workspaces;
    private volatile AgentRuntime runtime;
    private volatile boolean ready;
    private volatile boolean stopping;
    private volatile String failure = "STARTING";
    private final CompletableFuture<Void> shutdownComplete = new CompletableFuture<>();

    public AiServerService(MinecraftServer server, AiConfiguration configuration) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        if (!configuration.enabled()) throw new IllegalStateException("AI_DISABLED");
        this.server = server;
        this.configuration = configuration;
        compactor = new ContextCompactor(configuration.context(), configuration.model());
        io = pool("fireflymc-ai-store", 1, 128);
        toolIo = pool("fireflymc-ai-tools", 2, 32);
        exportIo = pool("fireflymc-ai-export", 1, 4);
        JsonObject web = configuration.web();
        secrets = new SecretFilter(List.of(configuration.apiKey(), web.has("apiKey") ? web.get("apiKey").getAsString() : ""));
        gameBridge = new GameToolBridge(server, owner -> {
            Actor current = actor(owner);
            if (server.isSameThread()) {
                ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(owner));
                return player == null ? new Actor(owner, current.authenticated(), false, 0) : capture(player);
            }
            return current;
        }, this::enabled, () -> stopping, toolIo, this::yoloMode);
        WorkspaceTools.register(tools);
        QuestionStore.register(tools);
        knowledge = new ServerKnowledgeService(server);
        knowledge.register(tools);
        environmentFacts = new firefly520.fireflymc.ai.integration.environment.EnvironmentFactsService(server, AiServerService::authenticated);
        environmentFacts.register(tools);
        permissions = new InstalledPermissionAdapters(server);
        knowledge.integrationEvidence(this::integrationEvidence);
        inventory = new MaterialInventoryService(server, AiServerService::authenticated, permissions);
        fluids = new FluidInventoryService(server, inventory, permissions);
        FluidInventoryTools.register(tools, fluids);
        equipment = new firefly520.fireflymc.ai.integration.equipment.EquipmentTools(server, inventory);
        equipment.register(tools);
        blueprints = new BlueprintService(server, AiServerService::authenticated, permissions);
        blueprints.register(tools);
        buildRegions = new firefly520.fireflymc.ai.blueprint.BuildRegionService(server, blueprints, permissions);
        blueprints.bindRegionProposals(buildRegions);
        buildRegions.register(tools);
        new PublicConfigService(configuration.publicConfigFields(), net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get(),
                server.getWorldPath(LevelResource.ROOT).resolve("serverconfig"), secrets).register(tools);
        research = new WebResearchService(new SafeWebClient(), secrets, web, knowledge::fingerprint, knowledge::modVersion);
        research.register(tools);
        tools.registerDiscovery(this::enabled);
        Path root = server.getWorldPath(LevelResource.ROOT).resolve("data/fireflymc/ai");
        io.execute(() -> {
            try {
                workspaces = new WorkspaceManager(root);
                try {
                    materialLedger = new MaterialLedger(root);
                    var fluidLedger = new FluidLedger(root);
                    processing = new VirtualProcessingService(server, inventory, materialLedger, knowledge::generation);
                    fluidProcessing = new firefly520.fireflymc.ai.processing.FluidProcessingService(server, inventory, fluids,
                            materialLedger, fluidLedger, processing, knowledge::generation);
                    // Rebuild joint ownership before exposing standalone material return/recovery to any player.
                    for (var workspace : workspaces.all()) fluidProcessing.prepare(workspace);
                    server.execute(() -> {
                        fluids.recoveryGuard(fluidProcessing::recoveryBlocksBackend, fluidProcessing::recoveryBlocksPlayer);
                        inventory.recoveryGuard((owner, backend) -> fluidProcessing.recoveryBlocksPlayer(owner)
                                || fluidProcessing.recoveryBlocksBackend(backend));
                    });
                    fluidProcessing.register(tools);
                    processing.register(tools);
                    construction = new firefly520.fireflymc.ai.blueprint.ConstructionService(server, inventory, materialLedger, processing,
                            permissions, knowledge::generation, configuration.virtualConstruction());
                    construction.register(tools);
                    new firefly520.fireflymc.ai.blueprint.MaterialSubstitutionService(blueprints, inventory, materialLedger, knowledge::generation).register(tools);
                    advancedProcessing = new firefly520.fireflymc.ai.processing.advanced.AdvancedProcessingService(server, inventory, materialLedger, processing, knowledge::generation);
                    specialProcessing = new firefly520.fireflymc.ai.processing.special.SpecialProcessingService(server, inventory, materialLedger, processing, knowledge::generation);
                    advancedProcessing.register(tools); specialProcessing.register(tools);
                    recovery = new firefly520.fireflymc.ai.recovery.RecoveryWorkbenchService(materialLedger, fluidProcessing,
                            processing, tools, gameBridge, inventory.nativeSaves(), this::actor, this::enabled, (workspace, task) -> {
                        if (runtime != null) try { runtime.acknowledgeRecovery(new Actor(workspace.owner(), true, false, 0), task); }
                        catch (IllegalStateException stillHeld) { /* Partial or unsupported evidence keeps the task fenced. */ }
                        catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
                        var current = workspace.conversation().task(task);
                        if (current != null && current.state() == AgentTask.State.RECOVERY_REQUIRED)
                            changed(workspace, current, "物料核对记录已更新，请在工作台查看实际状态。");
                    });
                    recovery.worldOperations(this::worldMaterialOperation);
                    recovery.additionalRows(this::worldRecoveryRows);
                    worldRecovery = new firefly520.fireflymc.ai.blueprint.recovery.WorldRecoveryService(materialLedger, inventory, processing, construction::idleForRecovery, construction::retireForRecovery);
                    for (var workspace : workspaces.all()) worldRecovery.prepare(workspace);
                    worldRecovery.register(tools);
                    recovery.registerModule(firefly520.fireflymc.ai.recovery.RecoveryWorkbenchService.Action.BUILD_RECONCILE, worldRecovery);
                    initializeNativeCompanions();
                    server.execute(() -> knowledge.processingSupport(holder -> {
                        if (!configuration.disabledTools().contains("fluid_processing_execute")
                                && firefly520.fireflymc.ai.processing.special.IronCauldronAdapter.kind(holder.value()) != null) return "FLUID_MATERIALS_ONLY_V1";
                        if (!configuration.disabledTools().contains("advanced_processing_execute")
                                && firefly520.fireflymc.ai.processing.advanced.AdvancedProcessingAdapters.supports(holder.value())) return "ADVANCED_MATERIALS_ONLY_V1";
                        if (!configuration.disabledTools().contains("special_processing_execute")
                                && (firefly520.fireflymc.ai.processing.special.SpecialItemProcessingAdapter.supports(holder.value())
                                || firefly520.fireflymc.ai.processing.special.BumbleCandleAdapter.supports(holder.value()))) return "SPECIAL_MATERIALS_ONLY_V1";
                        return configuration.disabledTools().contains("processing_execute") ? "PROCESSING_DISABLED" : processing.support(holder);
                    }));
                } catch (Exception materialFailure) {
                    FireflyMCMod.LOGGER.error("[AI4] Material journal requires review; private conversation remains available ({})",
                            materialFailure.getClass().getSimpleName());
                }
                runtime = new AgentRuntime(workspaces,
                        new TaskScheduler(configuration.concurrency(), 8, 128),
                        new HttpModelGateway(configuration.endpoint(), configuration.apiKey(), configuration.model(),
                                configuration.adapter(), configuration.timeoutSeconds() * 1000L, 2),
                        tools, compactor,
                        configuration.limits(), this, secrets);
                failure = "";
                ready = true;
            } catch (Exception e) {
                failure = "AI_STORAGE_ERROR";
                FireflyMCMod.LOGGER.error("[AI4] Workspace unavailable ({})", e.getClass().getSimpleName());
            }
        });
    }

    private static ThreadPoolExecutor pool(String name, int count, int queue) {
        return new ThreadPoolExecutor(count, count, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue),
                Thread.ofPlatform().daemon(true).name(name + "-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
    /** Optional native modules retain their own journals even when the corresponding mod is absent. */
    private void initializeNativeCompanions() {
        try {
            maidControl = new firefly520.fireflymc.ai.integration.maid.MaidControlService(server, inventory, permissions, processing);
            maidControl.capabilityPolicy(this::enabled);
            for (var workspace : workspaces.all()) maidControl.prepare(workspace);
            maidControl.register(tools);
            try {
                maidFarm = new firefly520.fireflymc.ai.integration.maid.MaidFarmService(server, inventory, permissions, processing, knowledge::generation, materialLedger);
                for (var workspace : workspaces.all()) maidFarm.prepare(workspace);
                maidFarm.register(tools);
            } catch (Exception unavailable) {
                FireflyMCMod.LOGGER.warn("[AI4] Maid farming journal or adapter unavailable ({})", unavailable.getClass().getSimpleName());
            }
            try {
                maidCombat = new firefly520.fireflymc.ai.integration.maid.MaidCombatService(server, inventory, permissions, processing, knowledge::generation, materialLedger);
                maidCombat.capabilityPolicy(this::enabled);
                for (var workspace : workspaces.all()) maidCombat.prepare(workspace);
                maidCombat.register(tools);
            } catch (Exception unavailable) {
                FireflyMCMod.LOGGER.warn("[AI4] Maid combat journal or adapter unavailable ({})", unavailable.getClass().getSimpleName());
            }
            firefly520.fireflymc.ai.recovery.RecoveryModule maidRecovery = new firefly520.fireflymc.ai.integration.maid.MaidRecoveryModule(maidControl, maidFarm);
            if (maidCombat != null) maidRecovery = new firefly520.fireflymc.ai.integration.maid.MaidCombatRecoveryModule(maidCombat, maidRecovery);
            recovery.registerModule(firefly520.fireflymc.ai.recovery.RecoveryWorkbenchService.Action.MAID_RECONCILE, maidRecovery);
        } catch (Exception unavailable) {
            FireflyMCMod.LOGGER.warn("[AI4] Maid journal or control unavailable ({})", unavailable.getClass().getSimpleName());
        }
        try {
            maidTransport = new firefly520.fireflymc.ai.integration.maid.transport.MaidTransportService(server, inventory, permissions,
                    processing, knowledge::generation, () -> available() && enabled("maid_transport_execute"), materialLedger);
            for (var workspace : workspaces.all()) maidTransport.prepare(workspace);
            maidTransport.register(tools);
            recovery.registerModule(firefly520.fireflymc.ai.recovery.RecoveryWorkbenchService.Action.MAID_TRANSPORT_RECONCILE,
                    new firefly520.fireflymc.ai.integration.maid.transport.MaidTransportRecoveryModule(maidTransport));
        } catch (Exception unavailable) {
            FireflyMCMod.LOGGER.warn("[AI4] Maid transport journal or adapter unavailable ({})", unavailable.getClass().getSimpleName());
        }
        try {
            treeHarvest = new firefly520.fireflymc.ai.integration.trees.TreeHarvestService(server, inventory, materialLedger, permissions,
                    processing, knowledge::generation, () -> available() && enabled("tree_harvest_execute"), configuration.virtualConstruction());
            for (var workspace : workspaces.all()) treeHarvest.prepare(workspace);
            treeHarvest.register(tools);
            recovery.registerModule(firefly520.fireflymc.ai.recovery.RecoveryWorkbenchService.Action.TREE_RECONCILE,
                    new firefly520.fireflymc.ai.integration.trees.TreeRecoveryModule(treeHarvest));
        } catch (Exception unavailable) {
            FireflyMCMod.LOGGER.warn("[AI4] Tree harvest journal or adapter unavailable ({})", unavailable.getClass().getSimpleName());
        }
        try {
            waystoneTravel = new firefly520.fireflymc.ai.integration.travel.WaystoneTravelService(server, AiServerService::authenticated,
                    permissions, processing, inventory.nativeSaves());
            for (var workspace : workspaces.all()) waystoneTravel.prepare(workspace);
            waystoneTravel.register(tools);
            recovery.registerModule(firefly520.fireflymc.ai.recovery.RecoveryWorkbenchService.Action.TRAVEL_RECONCILE,
                    new firefly520.fireflymc.ai.integration.travel.WaystoneRecoveryModule(waystoneTravel));
        } catch (Exception unavailable) {
            FireflyMCMod.LOGGER.warn("[AI4] Native travel journal or adapter unavailable ({})", unavailable.getClass().getSimpleName());
        }
        try {
            exploration = new firefly520.fireflymc.ai.integration.exploration.CompassSearchService(server, AiServerService::authenticated,
                    permissions, processing, knowledge::generation, () -> available() && !configuration.disabledTools().contains("compass_search_execute"));
            exploration.capabilityPolicy(this::enabled);
            for (var workspace : workspaces.all()) exploration.prepare(workspace);
            exploration.register(tools);
            recovery.registerModule(firefly520.fireflymc.ai.recovery.RecoveryWorkbenchService.Action.COMPASS_RECONCILE,
                    new firefly520.fireflymc.ai.integration.exploration.CompassRecoveryModule(exploration));
        } catch (Exception unavailable) {
            FireflyMCMod.LOGGER.warn("[AI4] Native compass journal or adapter unavailable ({})", unavailable.getClass().getSimpleName());
        }
    }
    public static boolean authenticated(ServerPlayer player) {
        return AiEnvironmentGate.evaluate(player.server.isDedicatedServer(), true, true, true,
                player.server.usesAuthentication(), ModPayloadHandler.VERIFIED_PLAYERS.getOrDefault(player.getUUID(), false),
                ModPayloadHandler.CONFIRMED_PLAYERS.getOrDefault(player.getUUID(), false),
                ServerConfig.SERVER.playerAuthEnabled.get(),
                ModPayloadHandler.PASSWORD_VERIFIED_PLAYERS.getOrDefault(player.getUUID(), false))
                == AiEnvironmentGate.Availability.READY;
    }
    private Actor capture(ServerPlayer player) {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        // OP status does not expand the companion tool set or data scope.
        return Actor.player(player.getUUID(), authenticated(player), true, 0);
    }
    public void disconnected(UUID player) {
        if (maidControl != null) maidControl.disconnected(player);
        if (maidFarm != null) maidFarm.disconnected(player);
        if (maidCombat != null) maidCombat.disconnected(player);
        if (treeHarvest != null) treeHarvest.disconnected(player);
        if (maidTransport != null) maidTransport.disconnected(player);
        equipment.disconnected(player);
        Session session = sessions.remove(player);
        if (session != null) session.closed = true;
        if (session != null && recovery != null) recovery.disconnect(player.toString(), session.token);
        Actor before = actors.get(player.toString());
        if (before != null) actors.put(before.owner(), new Actor(before.owner(), before.authenticated(), false, 0));
        if (runtime != null) runtime.disconnected(player.toString());
        selectedSources.values().removeIf(selection -> selection.owner().equals(player.toString()));
        selectedFluidSources.values().removeIf(selection -> selection.owner().equals(player.toString()));
        if (!stopping) fluids.clearPrivateViews(player.toString());
        blueprintUploads.disconnect(player.toString());
    }
    public void tick() {
        if (stopping) return;
        knowledge.tick();
        inventory.tick();
        fluids.tick();
        blueprints.tick();
        if (construction != null) construction.tick();
        if (advancedProcessing != null) advancedProcessing.tick();
        if (specialProcessing != null) specialProcessing.tick();
        if (maidControl != null) maidControl.tick();
        if (maidFarm != null) maidFarm.tick();
        if (maidCombat != null) maidCombat.tick();
        if (treeHarvest != null) treeHarvest.tick();
        if (maidTransport != null) maidTransport.tick();
        if (waystoneTravel != null) waystoneTravel.tick();
        if (exploration != null) exploration.tick();
        if (processing != null) processing.tick();
        if (ready && server.getTickCount() % 20 == 0 && !pendingContext.isEmpty() && contextSweepQueued.compareAndSet(false, true)) {
            try { io.execute(() -> {
                try {
                    int count = Math.min(2, contextOwners.size());
                    for (int i = 0; i < count; i++) {
                        String owner = contextOwners.removeFirst(); var queued = pendingContext.get(owner);
                        if (queued == null) continue;
                        processManualContext(queued);
                        if (pendingContext.containsKey(owner)) contextOwners.addLast(owner);
                    }
                }
                finally { contextSweepQueued.set(false); }
            }); }
            catch (RejectedExecutionException closed) { contextSweepQueued.set(false); }
        }
        if (++streamTicks % 2 == 0) for (LiveText stream : liveText.values()) {
            if (stream.sequence == stream.sent || liveText.get(stream.task) != stream) continue;
            Session session = sessions.get(UUID.fromString(stream.owner));
            if (session != null && !session.closed) {
                JsonObject data = stream.view();
                stream.sent = data.get("sequence").getAsLong();
                send(UUID.fromString(stream.owner), session, new AiResponsePayload(AiProtocol.VERSION, UUID.randomUUID(),
                        session.token, "text_delta", data.toString()), true);
            }
        }
        for (Actor before : actors.values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(before.owner()));
            Actor after = player == null ? new Actor(before.owner(), before.authenticated(), false, 0) : capture(player);
            actors.put(after.owner(), after);
            if (ready && after.authenticated() && after.online() && (!before.online() || !before.authenticated())) {
                try { io.execute(() -> {
                    try { prepareWorkspace(after); runtime.resumeTasks(after); }
                    catch (Exception e) { FireflyMCMod.LOGGER.warn("[AI4] Automatic resume requires workspace review"); }
                }); } catch (RejectedExecutionException ignored) { /* retry is available through the workbench */ }
            }
        }
        long now = System.currentTimeMillis();
        selectedSources.values().removeIf(selection -> selection.expiresAt() < now);
        selectedFluidSources.values().removeIf(selection -> selection.expiresAt() < now);
        for (var entry : deliveries.entrySet()) {
            if (now <= entry.getValue().expiresAt() || !deliveries.remove(entry.getKey(), entry.getValue())) continue;
            try { io.execute(() -> {
                try {
                    Workspace workspace = workspaces.load(entry.getValue().owner());
                    var done = shares(workspace).finish(
                            new Actor(workspace.owner(), true, false, 0), entry.getKey(), ShareService.State.DELIVERY_UNCERTAIN);
                    Session current = sessions.get(UUID.fromString(workspace.owner()));
                    if (current != null) {
                        JsonObject data = new JsonObject(); data.add("preview", AiJson.GSON.toJsonTree(done));
                        send(UUID.fromString(workspace.owner()), current, new AiResponsePayload(AiProtocol.VERSION,
                                UUID.randomUUID(), current.token, "share_result", data.toString()), true);
                    }
                } catch (Exception e) { FireflyMCMod.LOGGER.warn("[AI4] Share delivery requires review"); }
            }); } catch (RejectedExecutionException ignored) {}
        }
    }
    public void datapacksReloaded() { knowledge.invalidate(); }
    public void observe(ServerPlayer player, PersonalEventStore.Type type, String sourceId, JsonObject data) {
        if (!available() || !authenticated(player)) return;
        var preference = preferences.get(player.getUUID().toString());
        if (preference != null && !(type == PersonalEventStore.Type.DEATH ? preference.recordDeaths() : preference.recordAdvancements())) return;
        Actor actor = capture(player);
        JsonObject safe = JsonParser.parseString(secrets.redact(data.toString())).getAsJsonObject();
        try { io.execute(() -> {
            try { new PersonalEventStore(prepareWorkspace(actor)).record(actor, type, sourceId, safe); }
            catch (Exception e) { FireflyMCMod.LOGGER.warn("[AI4] Personal observation could not be saved"); }
        }); } catch (RejectedExecutionException ignored) {}
    }

    public void request(ServerPlayer player, AiRequestPayload request) {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (!server.isDedicatedServer()) return;
        UUID owner = player.getUUID();
        Session session = sessions.compute(owner, (id, old) -> {
            if (old != null && old.connection == player.connection && !old.closed) return old;
            if (old != null) old.closed = true;
            return new Session(player.connection);
        });
        Actor actor = capture(player);
        session.playerName = player.getGameProfile().getName();
        if (request.action() == AiProtocol.Action.HELLO || request.action() == AiProtocol.Action.OPEN) {
            JsonObject hello = AiProtocol.object(request.body(), AiProtocol.REQUEST_CHARS);
            session.crossChatReady = configuration.crossChatSharing() && hello.has("crossChatReady")
                    && hello.get("crossChatReady").getAsBoolean() && hello.has("crossChatEndpointHash")
                    && configuration.crossChatEndpointHash().equals(hello.get("crossChatEndpointHash").getAsString());
        }
        actors.put(actor.owner(), actor);
        AiEnvironmentGate.Availability status = stopping ? AiEnvironmentGate.Availability.DISABLED
                : !ready ? AiEnvironmentGate.Availability.NOT_READY
                : !actor.authenticated() ? AiEnvironmentGate.Availability.AUTH_REQUIRED : AiEnvironmentGate.Availability.READY;
        if (request.action() != AiProtocol.Action.HELLO && !request.sessionId().equals(session.token)) {
            respond(owner, session, request.requestId(), "error", json("code", "SESSION_CHANGED"), false);
            return;
        }
        try {
            boolean fresh = session.gate.accept(request.requestId(), AiJson.digest(request.action() + request.body()));
            if (!fresh) {
                AiResponsePayload previous = session.replies.get(request.requestId());
                if (previous != null) send(owner, session, previous, status == AiEnvironmentGate.Availability.READY);
                return;
            }
        } catch (RuntimeException rejected) {
            respond(owner, session, request.requestId(), "error", json("code", "REQUEST_REJECTED"), false);
            return;
        }
        if (request.action() == AiProtocol.Action.HELLO || status != AiEnvironmentGate.Availability.READY) {
            JsonObject availability = json("status", status.name());
            availability.add("actions", AiJson.GSON.toJsonTree(AiProtocol.implementedActions()));
            availability.addProperty("reason", failure);
            if (ready && actor.authenticated()) availability.addProperty("serverInstanceId", workspaces.serverInstanceId().toString());
            respond(owner, session, request.requestId(), "availability", availability, false);
            return;
        }
        try {
            io.execute(() -> {
                try {
                    Actor current = actor(owner.toString());
                    if (session.closed || !current.online() || !current.authenticated() || !available()) return;
                    Workspace workspace = prepareWorkspace(current);
                    JsonObject args = AiProtocol.object(request.body(), AiProtocol.REQUEST_CHARS);
                    if (Set.of(AiProtocol.Action.MATERIALS_SCAN, AiProtocol.Action.SOURCE_PREVIEW,
                            AiProtocol.Action.FLUIDS_SCAN, AiProtocol.Action.FLUID_SOURCE_PREVIEW, AiProtocol.Action.FLUID_SOURCE_CONFIRM,
                            AiProtocol.Action.SOURCE_CONFIRM, AiProtocol.Action.RECIPE_SHOW, AiProtocol.Action.INTEGRATIONS, AiProtocol.Action.BLUEPRINT_VIEW,
                            AiProtocol.Action.BLUEPRINT_IMPORT, AiProtocol.Action.BLUEPRINT_TRANSFORM, AiProtocol.Action.BUILD_SELECT,
                            AiProtocol.Action.EQUIPMENT_VIEW, AiProtocol.Action.CARRIER_SELECT, AiProtocol.Action.APPROVAL_PREVIEW,
                            AiProtocol.Action.RECOVERY_PREVIEW).contains(request.action())
                            && !(request.action() == AiProtocol.Action.BLUEPRINT_TRANSFORM && args.has("invalidateOnly") && args.get("invalidateOnly").getAsBoolean())) {
                        toolIo.execute(() -> {
                            try {
                                var result = dispatch(workspace, current, session, request, args);
                                respond(owner, session, request.requestId(), request.action().name().toLowerCase(Locale.ROOT), result, true);
                            } catch (Exception e) { respond(owner, session, request.requestId(), "error", json("code", safeError(e)), true); }
                        });
                        return;
                    }
                    if (request.action() == AiProtocol.Action.EXPORT && !args.has("id")) {
                        if (!exporting.add(workspace.owner())) throw new IllegalStateException("EXPORT_ALREADY_RUNNING");
                        respond(owner, session, request.requestId(), "export_pending", json("exportId", request.requestId().toString()), true);
                        try { exportIo.execute(() -> {
                            try {
                                actor(workspace.owner()).requireAccess(workspace.owner());
                                var result = dispatch(workspace, current, session, request, args);
                                respond(owner, session, request.requestId(), "export", result, true);
                            } catch (Exception e) {
                                respond(owner, session, request.requestId(), "error", json("code", safeError(e)), true);
                            } finally { exporting.remove(workspace.owner()); }
                        }); } catch (RejectedExecutionException e) { exporting.remove(workspace.owner()); throw e; }
                        return;
                    }
                    JsonObject result = dispatch(workspace, current, session, request, args);
                    respond(owner, session, request.requestId(), request.action().name().toLowerCase(Locale.ROOT), result, true);
                } catch (Exception e) {
                    respond(owner, session, request.requestId(), "error", json("code", safeError(e)), true);
                }
            });
        } catch (RejectedExecutionException e) {
            respond(owner, session, request.requestId(), "error", json("code", "QUEUE_FULL"), true);
        }
    }
    private Workspace prepareWorkspace(Actor actor) throws java.io.IOException {
        Workspace workspace = runtime.prepareWorkspace(actor);
        if (!initializedOwners.contains(actor.owner())) {
            if (fluidProcessing != null) fluidProcessing.prepare(workspace);
            if (maidControl != null) maidControl.prepare(workspace);
            if (maidFarm != null) maidFarm.prepare(workspace);
            if (maidCombat != null) maidCombat.prepare(workspace);
            if (treeHarvest != null) treeHarvest.prepare(workspace);
            if (maidTransport != null) maidTransport.prepare(workspace);
            if (worldRecovery != null) worldRecovery.prepare(workspace);
            if (waystoneTravel != null) waystoneTravel.prepare(workspace);
            if (exploration != null) exploration.prepare(workspace);
            inventory.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis));
            fluids.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis, MaterialSourceGrants.Domain.FLUIDS));
            inventory.bindPreferences(actor.owner(), new MaterialPreferences(workspace));
            equipment.prepare(workspace);
            var profile = WorkspacePreferences.load(workspace);
            shares(workspace).recoverPending();
            preferences.put(actor.owner(), profile);
            initializedOwners.add(actor.owner());
            restoreManualContext(workspace);
        }
        return workspace;
    }
    /** IO-only user request. It never creates a task, calls a model or mutates the complete timeline's messages. */
    private void queueManualContext(Workspace workspace, Actor actor, UUID requestId) throws java.io.IOException {
        actor.requireAccess(workspace.owner());
        var previous = pendingContext.get(workspace.owner());
        if (previous != null && previous.privacyEpoch() == workspace.conversation().privacyEpoch()) return;
        long now = System.currentTimeMillis();
        if (now - lastContextRequest.getOrDefault(workspace.owner(), 0L) < 5000)
            throw new IllegalStateException("CONTEXT_COMPACTION_COOLDOWN");
        if (pendingContext.size() >= 128) throw new IllegalStateException("CONTEXT_COMPACTION_QUEUE_FULL");
        var reference = ManualContextRequests.enqueue(workspace, actor, requestId);
        if (ManualContextRequests.pending(workspace, actor).isEmpty()) return;
        var request = new ManualContextRequest(workspace, reference);
        if (pendingContext.put(workspace.owner(), request) == null) contextOwners.addLast(workspace.owner());
        lastContextRequest.put(workspace.owner(), now);
        processManualContext(request);
    }
    /** Restored only after this player has authenticated and prepared the same permanent workspace. */
    private void restoreManualContext(Workspace workspace) {
        var restored = ManualContextRequests.pending(workspace, actor(workspace.owner()));
        if (restored.isPresent() && pendingContext.size() < 128
                && pendingContext.putIfAbsent(workspace.owner(), new ManualContextRequest(workspace, restored.get())) == null)
            contextOwners.addLast(workspace.owner());
    }
    private void processManualContext(ManualContextRequest request) {
        Workspace workspace = request.workspace();
        if (!available() || pendingContext.get(workspace.owner()) != request) return;
        try {
            actor(workspace.owner()).requireAccess(workspace.owner());
            if (workspace.conversation().privacyEpoch() != request.privacyEpoch()) { pendingContext.remove(workspace.owner(), request); return; }
            var outcome = compactor.compactManually(workspace);
            if (Set.of("WAITING_TOOL_TRANSACTION", "CONTEXT_CHANGED").contains(outcome.status())) return;
            if (!Set.of("COMPACTED", "NO_CLOSED_HISTORY").contains(outcome.status())) throw new IllegalStateException("CONTEXT_COMPACTION_RESULT_INVALID");
            if (workspace.conversation().privacyEpoch() != request.privacyEpoch()) { pendingContext.remove(workspace.owner(), request); return; }
            ManualContextRequests.complete(workspace, actor(workspace.owner()), request.reference(), outcome.status(),
                    outcome.view().summary() == null ? 0 : outcome.view().summary().summaryRevision());
            pendingContext.remove(workspace.owner(), request);
            contextOwners.remove(workspace.owner());
            Session session = sessions.get(UUID.fromString(workspace.owner()));
            if (session != null && !session.closed) {
                var notice = json("notice", outcome.status().equals("COMPACTED") ? "上下文已压缩；完整历史保留。" : "没有可压缩的旧完整轮次；完整历史保持。" );
                notice.addProperty("revision", workspace.conversation().revision());
                send(UUID.fromString(workspace.owner()), session, new AiResponsePayload(AiProtocol.VERSION, UUID.randomUUID(), session.token,
                        "changed", notice.toString()), true);
            }
        } catch (SecurityException awaitingAuthentication) {
            // An accepted private request may survive reconnect, but never bypasses current authentication.
        } catch (Exception unavailable) {
            FireflyMCMod.LOGGER.warn("[AI4] Manual context compaction remains queued ({})", unavailable.getClass().getSimpleName());
        }
    }
    private JsonObject contextProjection(Workspace workspace, int offset, String expectedDigest) throws java.io.IOException {
        var view = compactor.inspect(workspace); var result = new JsonObject();
        result.addProperty("viewVersion", 1);
        var metadata = AiJson.GSON.toJsonTree(view).getAsJsonObject();
        var summary = AiJson.GSON.toJsonTree(view.summary());
        if (summary.isJsonObject()) summary.getAsJsonObject().remove("content");
        metadata.add("summary", summary); result.add("context", metadata); result.add("summary", summary);
        boolean pending = pendingContext.containsKey(workspace.owner()); result.addProperty("manualPending", pending);
        String status = pending ? "WAITING_TOOL_TRANSACTION" : "IDLE";
        if (!pending) {
            var latest = workspace.conversation().getSnapshot(0).events().stream().filter(event -> event.kind().equals("context_compaction_result")
                    && event.data().get("privacyEpoch").getAsLong() == workspace.conversation().privacyEpoch()).reduce((first, second) -> second);
            if (latest.isPresent()) status = latest.get().data().get("status").getAsString();
        }
        result.addProperty("manualStatus", status);
        String text = firefly520.fireflymc.ai.context.ContextViewText.describe(view, status); String digest = AiJson.digest(text);
        if (offset < 0 || offset > text.length()) throw new IllegalArgumentException("CONTEXT_OFFSET");
        if (offset > 0 && !digest.equals(expectedDigest)) throw new IllegalStateException("CONTEXT_VIEW_CHANGED");
        int end = Math.min(text.length(), offset + 6000);
        result.addProperty("text", text.substring(offset, end)); result.addProperty("offset", offset);
        result.addProperty("nextOffset", end); result.addProperty("length", text.length()); result.addProperty("viewDigest", digest);
        return result;
    }
    private static String safeError(Exception exception) {
        if ((exception instanceof SecurityException || exception instanceof IllegalArgumentException
                || exception instanceof IllegalStateException) && exception.getMessage() != null
                && exception.getMessage().matches("[A-Z][A-Z0-9_]{2,79}")) return exception.getMessage();
        return exception instanceof SecurityException ? "ACCESS_DENIED" : exception instanceof IllegalArgumentException
                ? "INVALID_ARGUMENT" : exception instanceof RejectedExecutionException ? "QUEUE_FULL" : "OPERATION_UNAVAILABLE";
    }

    private JsonObject dispatch(Workspace workspace, Actor actor, Session session, AiRequestPayload request, JsonObject args) throws Exception {
        if (session.closed || sessions.get(UUID.fromString(actor.owner())) != session) throw new IllegalStateException("SESSION_CLOSED");
        Actor current = actor(actor.owner()); current.requireAccess(workspace.owner());
        if (!current.online()) throw new IllegalStateException("PLAYER_OFFLINE");
        JsonObject result = new JsonObject();
        switch (request.action()) {
            case CLIENT_POINT -> result = new firefly520.fireflymc.ai.workspace.CoordinateHintStore(workspace, secrets::redact).accept(current, args);
            case MAP_ROUTE -> {
                if (!args.keySet().equals(Set.of("taskId"))) throw new IllegalArgumentException("MAP_ROUTE_FIELDS");
                result = firefly520.fireflymc.ai.workspace.TaskCoordinateRoutes.view(workspace, current, UUID.fromString(args.get("taskId").getAsString()));
            }
            case OPEN, SYNC, HISTORY, TASK_MESSAGES -> {
                long after = args.has("afterSeq") ? args.get("afterSeq").getAsLong() : 0;
                long before = args.has("beforeSeq") ? args.get("beforeSeq").getAsLong() : Long.MAX_VALUE;
                if (request.action() == AiProtocol.Action.TASK_MESSAGES) {
                    recoveryFields(args, Set.of("taskId", "clientViewEpoch"));
                    UUID focusTask = UUID.fromString(args.get("taskId").getAsString());
                    long anchor = workspace.conversation().latestTaskMessage(focusTask)
                            .orElseThrow(() -> new IllegalStateException("TASK_MESSAGE_NOT_AVAILABLE"));
                    after = 0; before = Math.addExact(anchor, 1);
                    result.addProperty("focusTaskId", focusTask.toString()); result.addProperty("focusSeq", anchor);
                }
                if (after < 0 || before < 0) throw new IllegalArgumentException("SEQUENCE");
                var snapshot = request.action() == AiProtocol.Action.SYNC
                        ? workspace.conversation().eventPage(after, before, 24, false)
                        : workspace.conversation().chatPage(after, before, 24);
                List<firefly520.fireflymc.ai.conversation.ConversationStore.Event> candidates = snapshot.events();
                if (request.action() != AiProtocol.Action.SYNC) {
                    candidates = new ArrayList<>(snapshot.events());
                    Collections.reverse(candidates);
                }
                JsonArray events = new JsonArray();
                long last = after;
                for (var event : candidates) {
                    JsonObject view = new JsonObject();
                    view.addProperty("seq", event.seq());
                    view.addProperty("messageId", event.messageId().toString());
                    if (event.taskId() != null) view.addProperty("taskId", event.taskId().toString());
                    view.addProperty("timestamp", event.timestamp());
                    view.addProperty("kind", event.kind());
                    if (event.message() != null && !event.kind().equals("question_answer_input") && !event.message().text().isBlank()) {
                        view.addProperty("role", event.message().role());
                        view.addProperty("intermediate", "assistant".equals(event.message().role()) && !event.message().calls().isEmpty());
                        view.addProperty("text", bounded(event.message().text(), 600));
                        view.addProperty("truncated", event.message().text().length() > 600);
                        var preview = firefly520.fireflymc.ai.presentation.ResponseBlocks.preview(event.message().text(), 1800);
                        if (!preview.isEmpty()) {
                            view.add("blocks", preview);
                            view.addProperty("truncated", !preview.equals(AiJson.GSON.toJsonTree(
                                    firefly520.fireflymc.ai.presentation.ResponseBlocks.parse(event.message().text()))));
                        } else if (event.message().text().length() <= 600) view.add("blocks", AiJson.GSON.toJsonTree(
                                    firefly520.fireflymc.ai.presentation.ResponseBlocks.parse(event.message().text())));
                    } else if ("share".equals(event.kind())) {
                        view.addProperty("role", "notice");
                        view.addProperty("text", "分享 " + event.data().get("target").getAsString()
                                + "：" + event.data().get("result").getAsString());
                    } else if ("client_coordinate_hint".equals(event.kind())) {
                        view.addProperty("role", "notice");
                        view.addProperty("text", bounded(event.data().get("text").getAsString(), 600));
                    } else if ("task_notice".equals(event.kind())) {
                        view.addProperty("role", "notice");
                        view.addProperty("text", bounded(event.data().get("text").getAsString(), 600));
                        view.addProperty("essential", event.data().has("essential") && event.data().get("essential").getAsBoolean());
                    } else if (event.kind().startsWith("workbench_recovery_") && event.data().has("text")) {
                        view.addProperty("role", "notice");
                        view.addProperty("text", bounded(secrets.redact(event.data().get("text").getAsString()), 600));
                        view.addProperty("essential", !event.kind().equals("workbench_recovery_start"));
                        for (String field : List.of("actionId", "action", "status")) if (event.data().has(field)) view.add(field, event.data().get(field));
                    } else if ("question".equals(event.kind())) {
                        view.addProperty("role", "question");
                        view.addProperty("text", bounded(event.data().get("prompt").getAsString(), 600));
                        view.add("questionId", event.data().get("id"));
                    } else if ("question_answer".equals(event.kind())) {
                        view.addProperty("role", "user");
                        view.addProperty("text", "回答任务问题：" + bounded(event.data().get("answer").getAsString(), 600));
                    }
                    if (view.has("text") && !view.has("truncated")) {
                        String text = event.message() != null ? event.message().text() : switch (event.kind()) {
                            case "question" -> event.data().get("prompt").getAsString();
                            case "question_answer" -> event.data().get("answer").getAsString();
                            default -> event.data() != null && event.data().has("text") ? event.data().get("text").getAsString() : "";
                        };
                        view.addProperty("truncated", text.length() > 600);
                    }
                    if (events.size() >= 24 || events.toString().length() + view.toString().length() > 10000) break;
                    events.add(view);
                    last = event.seq();
                }
                if (request.action() != AiProtocol.Action.SYNC) {
                    JsonArray chronological = new JsonArray();
                    for (int i = events.size() - 1; i >= 0; i--) chronological.add(events.get(i));
                    events = chronological;
                }
                result.addProperty("serverInstanceId", workspace.metadata().serverInstanceId().toString());
                result.addProperty("conversationId", workspace.primaryConversationId().toString());
                result.addProperty("revision", snapshot.revision());
                result.addProperty("lastSeq", last);
                result.add("events", events);
                result.add("tasks", taskViews(workspace));
                JsonArray questions = new JsonArray();
                for (var question : new QuestionStore(workspace).pending(actor, 8)) {
                    JsonObject view = json("id", question.id().toString());
                    view.addProperty("taskId", question.taskId().toString());
                    view.addProperty("digest", question.digest());
                    view.addProperty("prompt", bounded(question.prompt(), 160));
                    view.addProperty("blocking", question.blocking());
                    view.addProperty("blockingScope", question.blockingScope().name());
                    view.addProperty("stepId", question.stepId());
                    view.addProperty("contextDigest", question.contextDigest());
                    view.add("requiredBeforeStepIds", AiJson.GSON.toJsonTree(question.requiredBeforeStepIds().stream().limit(4).toList()));
                    view.addProperty("dependencyCount", question.requiredBeforeStepIds().size());
                    questions.add(view);
                    if (questions.size() == 8) break;
                }
                result.add("questions", questions);
                var profile = WorkspacePreferences.load(workspace);
                JsonObject preferenceView = AiJson.GSON.toJsonTree(profile).getAsJsonObject();
                preferenceView.remove("personality");
                result.add("preferences", preferenceView);
                result.add("buildRegion", firefly520.fireflymc.ai.blueprint.BuildRegionService.view(workspace,
                        new firefly520.fireflymc.ai.blueprint.BuildRegionStore(workspace).current(actor)));
                preferences.put(workspace.owner(), profile);
                if (profile.yoloMode()) runtime.resumeAutomaticApprovals(actor);
                JsonArray targets = new JsonArray();
                targets.add(ShareService.Target.SERVER_CHAT.name());
                if (session.crossChatReady) targets.add(ShareService.Target.CROSS_CHAT.name());
                result.add("shareTargets", targets);
                JsonArray streams = new JsonArray();
                liveText.values().stream().filter(s -> s.owner.equals(actor.owner())).forEach(s -> {
                    JsonObject view = s.view();
                    view.addProperty("text", "");
                    view.addProperty("snapshotRequired", true);
                    streams.add(view);
                });
                result.add("streams", streams);
                while (result.toString().length() > 22000 && !events.isEmpty()) {
                    events.remove(request.action() == AiProtocol.Action.SYNC ? events.size() - 1 : 0);
                }
                result.addProperty("lastSeq", events.isEmpty() ? after : events.get(events.size() - 1).getAsJsonObject().get("seq").getAsLong());
                if (request.action() != AiProtocol.Action.SYNC) {
                    long oldest = events.isEmpty() ? 0 : events.get(0).getAsJsonObject().get("seq").getAsLong();
                    result.addProperty("nextBeforeSeq", oldest);
                    result.addProperty("hasEarlierHistory", oldest > 0 && !workspace.conversation().chatPage(0, oldest, 1).events().isEmpty());
                    if (request.action() == AiProtocol.Action.OPEN) result.addProperty("lastSeq", snapshot.revision());
                }
                result.addProperty("recipeGeneration", knowledge.generation());
                result.add("actions", AiJson.GSON.toJsonTree(AiProtocol.implementedActions()));
            }
            case SUBMIT -> {
                var task = runtime.submit(actor, request.requestId(), secrets.redact(args.get("text").getAsString()));
                result.addProperty("taskId", task.id().toString());
                result.addProperty("state", task.state().name());
            }
            case RECOVERY_VIEW -> {
                recoveryFields(args, Set.of("taskId", "offset"));
                if (recovery == null) throw new IllegalStateException("MATERIAL_JOURNAL_REQUIRES_REVIEW");
                UUID task = UUID.fromString(args.get("taskId").getAsString());
                // A crash may occur after every resource was saved but before the derived task
                // checkpoint. Reopening the ledger may close that stale barrier only on proof.
                if (workspace.conversation().task(task) != null && workspace.conversation().task(task).state() == AgentTask.State.RECOVERY_REQUIRED)
                    try { runtime.acknowledgeRecovery(actor, task); } catch (IllegalStateException insufficient) { /* Keep the existing fence. */ }
                result = recovery.view(workspace, actor, task, args.has("offset") ? args.get("offset").getAsInt() : 0);
            }
            case RECOVERY_PREVIEW -> {
                recoveryFields(args, Set.of("taskId", "operationId", "action", "targetSourceId"));
                if (recovery == null) throw new IllegalStateException("MATERIAL_JOURNAL_REQUIRES_REVIEW");
                result = recovery.preview(workspace, actor, session.token, UUID.fromString(args.get("taskId").getAsString()),
                        firefly520.fireflymc.ai.recovery.RecoveryWorkbenchService.Action.valueOf(args.get("action").getAsString()),
                        UUID.fromString(args.get("operationId").getAsString()), args.has("targetSourceId") ? UUID.fromString(args.get("targetSourceId").getAsString()) : null);
            }
            case RECOVERY_CONFIRM -> {
                recoveryFields(args, Set.of("token", "digest"));
                if (recovery == null) throw new IllegalStateException("MATERIAL_JOURNAL_REQUIRES_REVIEW");
                result = recovery.confirm(workspace, actor, session.token, UUID.fromString(args.get("token").getAsString()), args.get("digest").getAsString());
            }
            case CONTROL -> {
                UUID id = UUID.fromString(args.get("taskId").getAsString());
                String action = args.get("action").getAsString();
                synchronized (workspace.conversation()) {
                    if ("approve".equals(action)) {
                        var pending = workspace.conversation().task(id);
                        if (pending == null || pending.nextCall() >= pending.pending().size()
                                || !args.has("callDigest") || !ToolPolicyEngine.digest(workspace.owner(),
                                pending.pending().get(pending.nextCall())).equals(args.get("callDigest").getAsString()))
                            throw new SecurityException("APPROVAL_PREVIEW_CHANGED");
                        if (!new ApprovalStore(workspace, System::currentTimeMillis).consume(actor,
                                UUID.fromString(args.get("approvalToken").getAsString()), request.requestId(), pending)) {
                            result.addProperty("state", pending.state().name());
                            return result;
                        }
                    }
                    var task = runtime.control(actor, id, action);
                    result.addProperty("taskId", task.id().toString());
                    result.addProperty("state", task.state().name());
                }
                if ("cancel".equals(action) && construction != null) construction.removal().cancelTask(workspace, actor, id);
                if (recovery != null && Set.of("cancel", "deny", "pause").contains(action)) recovery.cancelTask(actor.owner(), id,
                        "pause".equals(action) ? "USER_PAUSED" : "CANCELLED");
            }
            case QUESTION_VIEW -> result.add("question", AiJson.GSON.toJsonTree(new QuestionStore(workspace)
                    .list(actor, null).stream().filter(q -> q.id().toString().equals(args.get("id").getAsString()))
                    .findFirst().orElseThrow()));
            case QUESTION_ANSWER -> result.add("question", AiJson.GSON.toJsonTree(runtime.answer(actor,
                    UUID.fromString(args.get("id").getAsString()), args.get("digest").getAsString(),
                    args.get("answer").getAsString(), request.requestId())));
            case APPROVAL_PREVIEW -> {
                var task = workspace.conversation().task(UUID.fromString(args.get("taskId").getAsString()));
                var preview = new ApprovalStore(workspace, System::currentTimeMillis).preview(actor, task);
                var call = task.pending().get(task.nextCall());
                if (call.arguments().length() > 16000) throw new IllegalArgumentException("APPROVAL_DETAIL_TOO_LARGE");
                result.add("preview", AiJson.GSON.toJsonTree(preview));
                result.addProperty("tool", call.name());
                result.addProperty("arguments", secrets.redact(call.arguments()));
                if ("processing_execute".equals(call.name())) {
                    var arguments = AiJson.object(call.arguments(), 65536, 24);
                    var plan = processing.load(workspace, actor, UUID.fromString(arguments.get("planId").getAsString()),
                            arguments.get("planHash").getAsString());
                    result.addProperty("reviewText", processing.review(plan));
                }
                if ("processing_reconcile".equals(call.name())) {
                    var arguments = AiJson.object(call.arguments(), 65536, 24);
                    result.addProperty("reviewText", processing.recoveryReviewText(actor, UUID.fromString(arguments.get("operationId").getAsString()),
                            arguments.get("revision").getAsLong(), arguments.get("reviewHash").getAsString()));
                }
                if ("fluid_processing_execute".equals(call.name())) {
                    var arguments = AiJson.object(call.arguments(), 65536, 24);
                    result.addProperty("reviewText", fluidProcessing.review(fluidProcessing.load(workspace, actor,
                            UUID.fromString(arguments.get("planId").getAsString()), arguments.get("planHash").getAsString())));
                }
                if ("fluid_processing_return".equals(call.name())) {
                    var arguments = AiJson.object(call.arguments(), 65536, 24);
                    result.addProperty("reviewText", fluidProcessing.returnReview(fluidProcessing.loadReturn(workspace, actor,
                            UUID.fromString(arguments.get("reviewId").getAsString()), arguments.get("reviewHash").getAsString())));
                }
                if ("fluid_processing_resume".equals(call.name())) {
                    var arguments = AiJson.object(call.arguments(), 65536, 24);
                    var review = fluidProcessing.loadResume(workspace, actor, UUID.fromString(arguments.get("reviewId").getAsString()), arguments.get("reviewHash").getAsString());
                    fluidProcessing.validateResumeVersion(workspace, actor, review);
                    result.addProperty("reviewText", fluidProcessing.resumeReview(review));
                }
                if ("fluid_processing_reconcile".equals(call.name())) {
                    var arguments = AiJson.object(call.arguments(), 65536, 24);
                    result.addProperty("reviewText", fluidProcessing.recoveryReview(workspace, actor,
                            UUID.fromString(arguments.get("operationId").getAsString()), arguments.get("revision").getAsLong(), arguments.get("reviewHash").getAsString()));
                }
                if ("build_execute".equals(call.name())) {
                    var arguments = AiJson.object(call.arguments(), 65536, 24);
                    var plan = construction.load(workspace, actor, UUID.fromString(arguments.get("planId").getAsString()), arguments.get("planHash").getAsString());
                    result.addProperty("reviewText", construction.review(workspace, actor, plan));
                }
                if (Set.of("build_demolition_execute", "build_undo_execute").contains(call.name())) {
                    var arguments = AiJson.object(call.arguments(), 65536, 24);
                    var plan = construction.removal().load(workspace, actor, arguments);
                    result.addProperty("reviewText", construction.removal().review(workspace, actor, plan));
                }
                if ("advanced_processing_execute".equals(call.name())) {
                    var arguments = AiJson.object(call.arguments(), 65536, 24);
                    var plan = advancedProcessing.load(workspace, actor, UUID.fromString(arguments.get("planId").getAsString()), arguments.get("planHash").getAsString());
                    result.addProperty("reviewText", advancedProcessing.review(workspace, actor, plan));
                }
                if ("special_processing_execute".equals(call.name())) {
                    var arguments = AiJson.object(call.arguments(), 65536, 24);
                    var plan = specialProcessing.load(workspace, actor, UUID.fromString(arguments.get("planId").getAsString()), arguments.get("planHash").getAsString());
                    result.addProperty("reviewText", specialProcessing.review(workspace, actor, plan));
                }
                if ("special_entity_execute".equals(call.name())) {
                    var arguments = AiJson.object(call.arguments(), 65536, 24);
                    result.addProperty("reviewText", specialProcessing.reviewEntity(workspace, actor, UUID.fromString(arguments.get("planId").getAsString()), arguments.get("planHash").getAsString()));
                }
                var nativeReview = nativeApprovalReview(workspace, actor, call);
                if (nativeReview != null) result.addProperty("reviewText", nativeReview);
            }
            case SETTINGS -> {
                var settings = WorkspacePreferences.load(workspace).update(args);
                new WorkspacePreferences(1, secrets.redact(settings.name()), secrets.redact(settings.personality()),
                        settings.recordDeaths(), settings.recordAdvancements(), settings.yoloMode()).save(workspace, actor);
                result.add("preferences", AiJson.GSON.toJsonTree(WorkspacePreferences.load(workspace)));
                preferences.put(workspace.owner(), WorkspacePreferences.load(workspace));
                if (settings.yoloMode()) runtime.resumeAutomaticApprovals(actor);
            }
            case SETTINGS_VIEW -> result.add("preferences", AiJson.GSON.toJsonTree(WorkspacePreferences.load(workspace)));
            case STREAM -> {
                var live = liveText.get(UUID.fromString(args.get("taskId").getAsString()));
                if (live == null || !live.owner.equals(actor.owner())) throw new IllegalArgumentException("STREAM_NOT_ACTIVE");
                result = live.view();
            }
            case MEMORY_VIEW -> {
                var notes = new MemoryStore(workspace).view(actor);
                int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
                if (offset < 0 || offset > notes.size()) throw new IllegalArgumentException("MEMORY_OFFSET");
                int end = Math.min(notes.size(), offset + 10);
                result.add("notes", AiJson.GSON.toJsonTree(notes.subList(offset, end)));
                result.addProperty("nextOffset", end); result.addProperty("total", notes.size());
            }
            case MEMORY_REMEMBER -> result.add("note", AiJson.GSON.toJsonTree(new MemoryStore(workspace)
                    .remember(actor, secrets.redact(args.get("text").getAsString()), "workbench")));
            case MEMORY_UPDATE -> result.add("note", AiJson.GSON.toJsonTree(new MemoryStore(workspace).edit(actor,
                    UUID.fromString(args.get("id").getAsString()), secrets.redact(args.get("text").getAsString()),
                    args.get("expectedUpdatedAt").getAsLong())));
            case MEMORY_FORGET -> new MemoryStore(workspace).forget(actor, UUID.fromString(args.get("id").getAsString()));
            case MEMORY_CLEAR -> new MemoryStore(workspace).clear(actor);
            case RECIPE_SHOW -> result = server.submit(() -> knowledge.recipeView(args)).get(10, TimeUnit.SECONDS);
            case INTEGRATIONS -> result = server.submit(() -> knowledge.integrationView(args)).get(10, TimeUnit.SECONDS);
            case BUILD_SELECT -> result = buildRegions.handle(workspace, actor, request.requestId(), args, new TaskControl(60000));
            case BLUEPRINT_VIEW -> {
                if (args.has("id")) result = blueprints.page(workspace, actor, new TaskControl(30000), args, args.has("inspect") && args.get("inspect").getAsBoolean());
                else {
                    var repository = new BlueprintRepository(workspace);
                    var rows = args.has("historyId") ? repository.history(actor).stream()
                            .filter(entry -> entry.id().toString().equals(args.get("historyId").getAsString()))
                            .sorted(Comparator.comparingLong(BlueprintRepository.Entry::revision).reversed()).toList() : repository.latest(actor);
                    int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
                    if (offset < 0 || offset > rows.size()) throw new IllegalArgumentException("BLUEPRINT_OFFSET");
                    int end = Math.min(rows.size(), offset + 16);
                    result.add("entries", AiJson.GSON.toJsonTree(rows.subList(offset, end).stream().map(BlueprintService::manifest).toList()));
                    result.addProperty("nextOffset", end); result.addProperty("total", rows.size());
                }
            }
            case BLUEPRINT_TRANSFORM -> {
                if (args.has("invalidateOnly") && args.get("invalidateOnly").getAsBoolean()) {
                    String stamp = new BlueprintRepository(workspace).invalidateReview(actor, UUID.fromString(args.get("id").getAsString()), args.get("hash").getAsString());
                    result.addProperty("reviewInvalidated", true); result.addProperty("reviewStamp", stamp);
                } else result = BlueprintService.manifest(blueprints.transform(workspace, actor, new TaskControl(120000), request.requestId(), args).entry());
            }
            case BLUEPRINT_IMPORT -> {
                String phase = args.get("phase").getAsString(); long epoch = workspace.conversation().privacyEpoch();
                switch (phase) {
                    case "begin" -> result = AiJson.GSON.toJsonTree(blueprintUploads.begin(actor.owner(), session.token, epoch,
                            args.get("totalChars").getAsInt(), args.get("digest").getAsString())).getAsJsonObject();
                    case "chunk" -> result = AiJson.GSON.toJsonTree(blueprintUploads.append(actor.owner(), session.token, epoch,
                            UUID.fromString(args.get("uploadId").getAsString()), args.get("offset").getAsInt(), args.get("text").getAsString())).getAsJsonObject();
                    case "commit" -> {
                        UUID id = UUID.fromString(args.get("uploadId").getAsString());
                        var document = blueprintUploads.finish(actor.owner(), session.token, epoch, id);
                        if (session.closed) throw new IllegalStateException("SESSION_CLOSED");
                        UUID previous = args.has("id") ? UUID.fromString(args.get("id").getAsString()) : null;
                        long previousRevision = previous == null ? 0 : args.get("revision").getAsLong();
                        String previousHash = previous == null ? "" : args.get("hash").getAsString();
                        var saved = blueprints.save(workspace, actor, new TaskControl(120000), id, previous, previousRevision, previousHash, null, document, epoch);
                        blueprintUploads.completed(actor.owner(), session.token, epoch, id);
                        result = BlueprintService.manifest(saved.entry()); result.addProperty("complete", true);
                    }
                    case "cancel" -> { blueprintUploads.disconnect(actor.owner()); result.addProperty("cancelled", true); }
                    default -> throw new IllegalArgumentException("BLUEPRINT_UPLOAD_PHASE");
                }
            }
            case MATERIALS_VIEW -> {
                if (materialLedger == null) throw new IllegalStateException("MATERIAL_JOURNAL_REQUIRES_REVIEW");
                int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
                var grants = inventory.grants(actor).list(actor).stream().filter(g -> !g.revoked()).toList();
                if (offset < 0 || offset > grants.size()) throw new IllegalArgumentException("SOURCE_OFFSET");
                int end = Math.min(grants.size(), offset + 8);
                result.add("sources", AiJson.GSON.toJsonTree(grants.subList(offset, end).stream().map(g -> Map.of(
                        "id", g.id(), "revision", g.revision(), "description", g.source().description(),
                        "extract", g.source().extract(), "insert", g.source().insert())).toList()));
                result.addProperty("nextOffset", end); result.addProperty("total", grants.size());
                result.addProperty("text", "本人主背包和副手在批准的材料计划范围内可用。装备与额外仓库须单独授权。授权所指容器允许读写全部列明成员；撤销立即阻止后续取料。");
            }
            case MATERIALS_SCAN -> {
                if (materialLedger == null) throw new IllegalStateException("MATERIAL_JOURNAL_REQUIRES_REVIEW");
                var snapshot = args.has("snapshotId") ? server.submit(() -> inventory.snapshot(actor,
                        UUID.fromString(args.get("snapshotId").getAsString()))).get(10, TimeUnit.SECONDS)
                        : inventory.scan(actor).get(30, TimeUnit.SECONDS);
                int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
                if (offset < 0 || offset > snapshot.rows().size()) throw new IllegalArgumentException("INVENTORY_OFFSET");
                int end = Math.min(snapshot.rows().size(), offset + 12);
                var available = materialLedger.available(snapshot.rows());
                result.addProperty("snapshotId", snapshot.id().toString());
                result.addProperty("nextOffset", end); result.addProperty("total", snapshot.rows().size());
                result.add("items", AiJson.GSON.toJsonTree(snapshot.rows().subList(offset, end).stream().map(row -> Map.of(
                        "id", JsonParser.parseString(row.stack().itemJson()).getAsJsonObject().get("id").getAsString(),
                        "slot", row.slot(), "physical", row.stack().count(), "available", available.get(row.key()),
                        "source", row.sourceId(), "componentsDigest", row.stack().fingerprint(), "protectedByOwner", inventory.protectedByOwner(actor, row.stack()))).toList()));
                result.add("refusals", AiJson.GSON.toJsonTree(snapshot.refusals()));
            }
            case MATERIAL_PREFERENCES -> {
                var policy = inventory.preferences(actor);
                if (args.has("protectedItems")) {
                    var values = new TreeSet<String>(); var supplied = args.getAsJsonArray("protectedItems");
                    if (supplied.size() > 128) throw new IllegalArgumentException("MATERIAL_PREFERENCES_INVALID");
                    for (var value : supplied) values.add(value.getAsString());
                    result = AiJson.GSON.toJsonTree(policy.save(actor, args.get("revision").getAsLong(), values)).getAsJsonObject();
                    server.execute(() -> inventory.clearPrivateViews(actor.owner()));
                } else result = AiJson.GSON.toJsonTree(policy.view(actor)).getAsJsonObject();
                result.remove("owner");
                result.addProperty("note", "这些物品不能被 AI 后续取料；已托管的获批任务请先暂停或取消。更改只影响本人的 AI，不改变其他玩家或 Mod 玩法。");
            }
            case SOURCE_PREVIEW -> {
                if (materialLedger == null) throw new IllegalStateException("MATERIAL_JOURNAL_REQUIRES_REVIEW");
                var selection = server.submit(() -> args.has("backpack") ? inventory.selectBackpack(actor, args.get("backpack").getAsString())
                        : args.has("maid") ? inventory.selectMaidBackpack(actor, UUID.fromString(args.get("maid").getAsString()))
                        : inventory.selectLookedAt(actor)).get(10, TimeUnit.SECONDS);
                var preview = inventory.grants(actor).preview(actor, selection.candidate());
                selectedSources.put(preview.id(), new SelectedSource(actor.owner(), preview.expiresAt(), selection));
                result.addProperty("previewId", preview.id().toString()); result.addProperty("digest", preview.digest());
                StringBuilder text = new StringBuilder("授权 AI 使用此容器来源\n\n").append(selection.candidate().description())
                        .append("\n权限：盘点物品、按获批计划取物和归还物品。原料流体须在“原料流体”页单独授权。\n物理成员：\n");
                for (var member : selection.candidate().members()) text.append(member.dimension()).append(" ")
                        .append(member.x()).append(", ").append(member.y()).append(", ").append(member.z()).append(" · ")
                        .append(member.blockEntityType()).append('\n');
                result.addProperty("text", text.append("\n授权绑定容器身份；容器替换、领地权限改变后须重新核验。可随时在物料页撤销。").toString());
            }
            case EQUIPMENT_VIEW -> {
                result = server.submit(() -> {
                    var data = equipment.inspectEquipment(actor, args.has("offset") ? args.get("offset").getAsInt() : 0);
                    data.add("backpacks", AiJson.GSON.toJsonTree(inventory.backpackChoices(actor)));
                    data.add("carrier", equipment.inspectCarrier(actor, 0)); return data;
                }).get(10, TimeUnit.SECONDS);
            }
            case CARRIER_SELECT -> {
                String operation = args.get("operation").getAsString();
                if (operation.equals("preview")) {
                    var preview = equipment.previewCarrier(actor, args.has("maidId") ? UUID.fromString(args.get("maidId").getAsString()) : null);
                    result.add("preview", AiJson.GSON.toJsonTree(preview));
                    result.addProperty("text", "选择本人女仆作为伙伴载体\n实体：" + preview.choice().maidId()
                            + "\n维度：" + preview.choice().dimension() + "\n原生任务：" + preview.choice().nativeTaskId()
                            + "\n此选择不授权消耗背包，不接管战斗任务。原生任务或所有权改变时重新核验。");
                } else if (operation.equals("confirm")) result.add("selection", AiJson.GSON.toJsonTree(equipment.confirmCarrier(actor,
                        UUID.fromString(args.get("id").getAsString()), args.get("digest").getAsString())));
                else if (operation.equals("clear")) result.add("selection", AiJson.GSON.toJsonTree(equipment.clearCarrier(actor, args.get("revision").getAsLong())));
                else throw new IllegalArgumentException("CARRIER_ACTION_INVALID");
            }
            case SOURCE_CONFIRM -> {
                UUID id = UUID.fromString(args.get("previewId").getAsString());
                String digest = args.get("digest").getAsString();
                var grants = inventory.grants(actor);
                grants.pending(actor, id, digest);
                var selection = selectedSources.get(id);
                if (selection == null || !selection.owner().equals(actor.owner()) || selection.expiresAt() < System.currentTimeMillis())
                    throw new IllegalStateException("SOURCE_PREVIEW_STALE");
                var confirmed = server.submit(() -> {
                    grants.pending(actor, id, digest);
                    return inventory.confirmSelection(actor, selection.selection());
                }).get(10, TimeUnit.SECONDS);
                var grant = grants.confirm(actor, id, digest, confirmed);
                selectedSources.remove(id); result.addProperty("id", grant.id().toString());
            }
            case SOURCE_REVOKE -> inventory.grants(actor).revoke(actor, UUID.fromString(args.get("id").getAsString()));
            case FLUIDS_VIEW -> {
                var grants = fluids.grants(actor).list(actor).stream().filter(grant -> !grant.revoked()).toList();
                int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
                if (offset < 0 || offset > grants.size()) throw new IllegalArgumentException("FLUID_OFFSET_INVALID");
                int end = Math.min(offset + 8, grants.size());
                result.add("sources", AiJson.GSON.toJsonTree(grants.subList(offset, end).stream().map(grant -> Map.of(
                        "id", grant.id(), "revision", grant.revision(), "description", grant.source().description(),
                        "extract", grant.source().extract(), "insert", grant.source().insert())).toList()));
                result.addProperty("total", grants.size()); result.addProperty("nextOffset", end);
                result.addProperty("text", "原料流体来源单独授权。现有物品授权不允许取液；撤销会阻止后续操作。当前支持本服匹配版本的精妙背包储罐。");
            }
            case FLUIDS_SCAN -> {
                var snapshot = args.has("snapshotId") ? server.submit(() -> fluids.snapshot(actor,
                        UUID.fromString(args.get("snapshotId").getAsString()))).get(10, TimeUnit.SECONDS)
                        : fluids.scan(actor).get(30, TimeUnit.SECONDS);
                result = FluidInventoryTools.view(snapshot, args.has("offset") ? args.get("offset").getAsInt() : 0);
            }
            case FLUID_SOURCE_PREVIEW -> {
                var selection = server.submit(() -> args.has("backpack") ? fluids.selectBackpack(actor, args.get("backpack").getAsString())
                        : fluids.selectLookedAt(actor)).get(10, TimeUnit.SECONDS);
                var preview = fluids.grants(actor).preview(actor, selection.candidate());
                selectedFluidSources.put(preview.id(), new SelectedFluidSource(actor.owner(), preview.expiresAt(), selection));
                result.addProperty("previewId", preview.id().toString()); result.addProperty("digest", preview.digest());
                result.addProperty("text", "授权原料流体来源\n\n" + selection.candidate().description()
                        + "\n\n允许盘点、按获批计划取液及回填。每次仍核验身份、宿主、升级与领地权限。\n此授权不允许消耗背包物品、装备或罐内的容器；不自动提供未就绪的加工能力。");
            }
            case FLUID_SOURCE_CONFIRM -> {
                UUID id = UUID.fromString(args.get("previewId").getAsString()); String digest = args.get("digest").getAsString();
                var selected = selectedFluidSources.get(id); var grants = fluids.grants(actor);
                if (selected == null || !selected.owner().equals(actor.owner()) || selected.expiresAt() < System.currentTimeMillis())
                    throw new IllegalStateException("SOURCE_PREVIEW_STALE");
                var confirmed = server.submit(() -> {
                    grants.pending(actor, id, digest); return fluids.confirmSelection(actor, selected.selection());
                }).get(10, TimeUnit.SECONDS);
                var grant = grants.confirm(actor, id, digest, confirmed); selectedFluidSources.remove(id);
                result.addProperty("id", grant.id().toString());
            }
            case FLUID_SOURCE_REVOKE -> fluids.grants(actor).revoke(actor, UUID.fromString(args.get("id").getAsString()));
            case CONTEXT -> {
                if (!Set.of("offset", "viewDigest").containsAll(args.keySet())) throw new IllegalArgumentException("CONTEXT_REQUEST_FIELDS");
                int offset = args.has("offset") ? args.get("offset").getAsBigDecimal().intValueExact() : 0;
                result = contextProjection(workspace, offset, args.has("viewDigest") ? args.get("viewDigest").getAsString() : "");
            }
            case CONTEXT_COMPACT -> {
                if (!args.keySet().equals(Set.of("viewVersion")) || !args.get("viewVersion").isJsonPrimitive()
                        || !args.getAsJsonPrimitive("viewVersion").isNumber()
                        || args.get("viewVersion").getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) != 0)
                    throw new IllegalArgumentException("CONTEXT_VIEW_VERSION_UNSUPPORTED");
                queueManualContext(workspace, actor, request.requestId());
                result = contextProjection(workspace, 0, "");
            }
            case SEARCH -> {
                String query = args.get("query").getAsString();
                var hits = workspace.conversation().searchComplete(query,
                        args.has("beforeSeq") ? args.get("beforeSeq").getAsLong() : Long.MAX_VALUE, 10);
                JsonArray matches = new JsonArray();
                for (var hit : hits) {
                    JsonObject view = json("messageId", hit.messageId().toString());
                    view.addProperty("seq", hit.seq()); view.addProperty("kind", hit.kind());
                    if (hit.taskId() != null) view.addProperty("taskId", hit.taskId().toString());
                    int found = hit.text().toLowerCase(Locale.ROOT).indexOf(query.toLowerCase(Locale.ROOT));
                    int start = Math.min(hit.text().length(), Math.max(0, found - 60)), end = Math.min(hit.text().length(), start + 300);
                    view.addProperty("excerpt", hit.text().substring(start, end));
                    matches.add(view);
                }
                result.add("matches", matches); result.addProperty("query", query);
                result.addProperty("nextBeforeSeq", hits.isEmpty() ? 0 : hits.getLast().seq());
            }
            case MESSAGE -> {
                long seq = args.get("seq").getAsLong();
                var event = workspace.conversation().eventPage(seq - 1, Math.addExact(seq, 1), 1, false).events().stream()
                        .filter(e -> e.seq() == seq).findFirst().orElseThrow();
                String full = secrets.redact(event.message() != null ? event.message().text() : switch (event.kind()) {
                    case "task_notice", "client_coordinate_hint", "workbench_recovery_start", "workbench_recovery_wait", "workbench_recovery_result", "workbench_recovery_evidence" -> event.data().get("text").getAsString();
                    case "question_answer" -> event.data().get("answer").getAsString();
                    case "question" -> event.data().get("prompt").getAsString();
                    case "tool_result" -> event.data().get("fullResult").getAsString();
                    default -> throw new IllegalArgumentException("MESSAGE_HAS_NO_TEXT");
                });
                int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
                if (offset < 0 || offset > full.length()) throw new IllegalArgumentException("OFFSET");
                int end = firefly520.fireflymc.ai.presentation.MessageTextChunks.end(full, offset, 6000);
                result.addProperty("text", full.substring(offset, end));
                result.addProperty("offset", offset);
                result.addProperty("nextOffset", end);
                result.addProperty("length", full.length());
                result.addProperty("seq", seq);
                result.addProperty("messageId", event.messageId().toString());
                result.addProperty("role", event.message() == null ? "notice" : event.message().role());
                while (result.toString().length() > 18000) {
                    end = firefly520.fireflymc.ai.presentation.MessageTextChunks.end(full, offset, Math.max(1, (end - offset) / 2));
                    result.addProperty("text", full.substring(offset, end));
                    result.addProperty("nextOffset", end);
                }
                if (offset == 0 && end == full.length() && full.length() < 6000)
                    result.add("blocks", AiJson.GSON.toJsonTree(firefly520.fireflymc.ai.presentation.ResponseBlocks.parse(full)));
                if (result.toString().length() > 22000) result.remove("blocks");
            }
            case EVENTS -> {
                JsonArray records = new JsonArray();
                var available = new PersonalEventStore(workspace)
                        .page(actor, args.has("beforeSeq") ? args.get("beforeSeq").getAsLong() : Long.MAX_VALUE, 20);
                for (var event : available.reversed()) {
                    var item = AiJson.GSON.toJsonTree(event);
                    if (records.toString().length() + item.toString().length() > 18000) break;
                    records.add(item);
                }
                JsonArray chronological = new JsonArray();
                for (int i = records.size() - 1; i >= 0; i--) chronological.add(records.get(i));
                result.add("events", chronological);
                result.addProperty("nextBeforeSeq", chronological.isEmpty() ? 0 : chronological.get(0).getAsJsonObject().get("seq").getAsLong());
            }
            case EVENTS_CLEAR -> new PersonalEventStore(workspace).clear(actor);
            case SHARE_PREVIEW -> {
                var target = ShareService.Target.valueOf(args.get("target").getAsString());
                var preview = shares(workspace).preview(actor,
                        UUID.fromString(args.get("messageId").getAsString()), target, session.playerName,
                        target == ShareService.Target.SERVER_CHAT || session.crossChatReady);
                result.add("preview", AiJson.GSON.toJsonTree(preview));
            }
            case SHARE_CONFIRM -> {
                var shares = shares(workspace);
                var target = ShareService.Target.valueOf(args.get("target").getAsString());
                var confirmation = shares.confirm(actor, UUID.fromString(args.get("previewId").getAsString()),
                        request.requestId(), args.get("contentDigest").getAsString(), target,
                        target == ShareService.Target.SERVER_CHAT || session.crossChatReady);
                var preview = confirmation.preview();
                if (confirmation.dispatch()) publish(workspace, actor, session, preview);
                result.add("preview", AiJson.GSON.toJsonTree(preview));
            }
            case SHARE_DELIVERY_RESULT -> {
                UUID id = UUID.fromString(args.get("previewId").getAsString());
                Delivery delivery = deliveries.get(id);
                if (delivery == null || !delivery.owner().equals(actor.owner())
                        || !delivery.token().toString().equals(args.get("deliveryToken").getAsString()))
                    throw new SecurityException("UNKNOWN_DELIVERY");
                if (deliveries.remove(id, delivery)) {
                    ShareService.State state = args.get("accepted").getAsBoolean()
                            ? ShareService.State.RELAY_ACCEPTED : ShareService.State.DELIVERY_UNCERTAIN;
                    result.add("preview", AiJson.GSON.toJsonTree(shares(workspace)
                            .finish(actor, id, state)));
                }
            }
            case EXPORT -> {
                var exports = new WorkspaceExportService(workspace, () -> materialLedger.exportJournal(actor),
                        fluidProcessing == null ? null : () -> fluidProcessing.exportJournal(actor), companionExportSnapshots(workspace, actor),
                        firefly520.fireflymc.ai.integration.exploration.CompassPrivacyProjection::currentStructureCoordinatesVisible);
                result.add("manifest", AiJson.GSON.toJsonTree(args.has("id")
                        ? exports.manifest(actor, UUID.fromString(args.get("id").getAsString()))
                        : exports.prepare(actor, request.requestId())));
            }
            case EXPORT_CHUNK -> result.add("chunk", AiJson.GSON.toJsonTree(new WorkspaceExportService(workspace).chunk(actor,
                    UUID.fromString(args.get("id").getAsString()), args.get("offset").getAsLong())));
            case EXPORT_LIST -> result.add("exports", AiJson.GSON.toJsonTree(new WorkspaceExportService(workspace).list(actor)));
            case PRIVACY_PREVIEW -> result.add("preview", AiJson.GSON.toJsonTree(privacy.preview(actor, workspace)));
            case PRIVACY_CONFIRM -> {
                long revision = privacy.confirm(actor, workspace, UUID.fromString(args.get("token").getAsString()));
                pendingContext.remove(actor.owner()); contextOwners.remove(actor.owner()); lastContextRequest.remove(actor.owner()); compactor.forget(workspace);
                if (construction != null) construction.forgetFunctionalBindings(workspace, actor);
                if (maidControl != null) { maidControl.forgetErased(workspace, actor); maidControl.prepare(workspace); }
                if (maidFarm != null) { maidFarm.forgetErased(workspace, actor); maidFarm.prepare(workspace); }
                if (maidCombat != null) { maidCombat.forgetErased(workspace, actor); maidCombat.prepare(workspace); }
                if (treeHarvest != null) { treeHarvest.forgetErased(workspace, actor); treeHarvest.prepare(workspace); }
                if (maidTransport != null) { maidTransport.forgetErased(workspace, actor); maidTransport.prepare(workspace); }
                if (worldRecovery != null) worldRecovery.prepare(workspace);
                if (waystoneTravel != null) { waystoneTravel.forgetErased(workspace, actor); waystoneTravel.prepare(workspace); }
                if (exploration != null) { exploration.forgetErased(workspace, actor); exploration.prepare(workspace); }
                if (materialLedger != null) materialLedger.forgetSettled(actor);
                if (fluidProcessing != null) fluidProcessing.forgetSettled(actor);
                inventory.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis));
                fluids.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis, MaterialSourceGrants.Domain.FLUIDS));
                inventory.bindPreferences(actor.owner(), new MaterialPreferences(workspace));
                selectedSources.values().removeIf(selection -> selection.owner().equals(actor.owner()));
                selectedFluidSources.values().removeIf(selection -> selection.owner().equals(actor.owner()));
                server.execute(() -> inventory.clearPrivateViews(actor.owner()));
                server.execute(() -> fluids.clearPrivateViews(actor.owner()));
                equipment.clearPrivateViews(actor.owner()); equipment.prepare(workspace);
                preferences.put(actor.owner(), WorkspacePreferences.defaults());
                session.replies.clear();
                result.addProperty("deleted", true);
                result.addProperty("revision", revision);
                result.addProperty("conversationId", workspace.primaryConversationId().toString());
            }
            case ARTIFACT -> {
                String text = workspace.conversation().readableArtifact(UUID.fromString(args.get("id").getAsString()))
                        .orElseThrow(() -> new IllegalArgumentException("ARTIFACT_NOT_READABLE"));
                int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
                if (offset < 0 || offset > text.length()) throw new IllegalArgumentException("OFFSET");
                text = secrets.redact(text);
                if (offset > text.length()) throw new IllegalArgumentException("OFFSET");
                int end = Math.min(text.length(), offset + 6000);
                result.addProperty("text", text.substring(offset, end));
                result.addProperty("id", args.get("id").getAsString());
                result.addProperty("offset", offset);
                result.addProperty("nextOffset", end);
                result.addProperty("length", text.length());
                while (result.toString().length() > 18000) {
                    end = offset + (end - offset) / 2;
                    result.addProperty("text", text.substring(offset, end));
                    result.addProperty("nextOffset", end);
                }
            }
            default -> throw new IllegalStateException("UI_ACTION_NOT_IMPLEMENTED");
        }
        if (request.action().name().startsWith("BLUEPRINT_") && args.has("clientEpoch")) result.add("clientEpoch", args.get("clientEpoch"));
        return result;
    }

    private JsonArray taskViews(Workspace workspace) {
        var tasks = workspace.conversation().tasks();
        var progress = buildProgress.computeIfAbsent(workspace.owner(), firefly520.fireflymc.ai.blueprint.BuildProgressProjection::new).view(workspace.conversation());
        var materialOperations = materialLedger == null ? List.<MaterialLedger.Operation>of()
                : materialLedger.list(Actor.player(UUID.fromString(workspace.owner()), true, true, 0));
        var selected = new ArrayList<>(tasks.stream().filter(t -> !t.state().terminal()).toList());
        for (int i = tasks.size() - 1; i >= 0 && selected.size() < 8; i--)
            if (tasks.get(i).state().terminal()) selected.add(tasks.get(i));
        JsonArray result = new JsonArray();
        for (var task : selected) {
            JsonObject view = json("taskId", task.id().toString());
            view.addProperty("prompt", bounded(task.prompt(), 120));
            view.addProperty("state", task.state().name());
            view.addProperty("reason", task.reason());
            view.addProperty("steps", task.steps());
            view.addProperty("toolCalls", task.toolCalls());
            view.addProperty("tokens", task.tokens());
            var build = progress.get(task.id());
            if (build != null) view.add("construction", AiJson.GSON.toJsonTree(build));
            var ownedMaterials = materialOperations.stream().filter(operation -> task.id().equals(operation.taskId())).toList();
            if (!ownedMaterials.isEmpty()) {
                JsonObject materials = new JsonObject();
                materials.addProperty("heldInputUnits", ownedMaterials.stream().flatMap(operation -> operation.escrow().stream()).mapToLong(MaterialStack::count).sum());
                materials.addProperty("undeliveredUnits", ownedMaterials.stream().flatMap(operation -> operation.remainingProducts().stream()).mapToLong(MaterialStack::count).sum());
                materials.addProperty("recoveryRequired", ownedMaterials.stream().anyMatch(MaterialLedger.Operation::uncertain));
                view.add("materials", materials);
            }
            if (task.nextCall() < task.pending().size()) {
                var call = task.pending().get(task.nextCall());
                view.addProperty("tool", call.name());
                view.addProperty("callDigest", ToolPolicyEngine.digest(workspace.owner(), call));
            }
            view.addProperty("requestId", task.requestId().toString());
            result.add(view);
        }
        return result;
    }
    private void publish(Workspace workspace, Actor actor, Session session, ShareService.Preview preview) {
        UUID owner = UUID.fromString(actor.owner());
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            boolean allowed = player != null && authenticated(player) && !session.closed
                    && player.connection == session.connection && available();
            if (allowed && preview.target() == ShareService.Target.CROSS_CHAT) {
                UUID token = UUID.randomUUID();
                deliveries.put(preview.id(), new Delivery(actor.owner(), token, System.currentTimeMillis() + 15000));
                JsonObject data = new JsonObject();
                data.add("preview", AiJson.GSON.toJsonTree(preview));
                data.addProperty("deliveryToken", token.toString());
                send(owner, session, new AiResponsePayload(AiProtocol.VERSION, UUID.randomUUID(), session.token,
                        "share_delivery", data.toString()), true);
                return;
            }
            if (allowed) server.getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal(preview.publicText()), false);
            ShareService.State state = allowed ? ShareService.State.PUBLISHED : ShareService.State.FAILED;
            try { io.execute(() -> {
                try {
                    var done = shares(workspace).finish(actor, preview.id(), state);
                    JsonObject data = new JsonObject();
                    data.add("preview", AiJson.GSON.toJsonTree(done));
                    send(owner, session, new AiResponsePayload(AiProtocol.VERSION, UUID.randomUUID(), session.token,
                            "share_result", data.toString()), true);
                } catch (Exception e) { FireflyMCMod.LOGGER.warn("[AI4] Share result requires reconciliation"); }
            }); } catch (RejectedExecutionException ignored) {}
        });
    }
    private void respond(UUID owner, Session session, UUID request, String kind, JsonObject data, boolean privateData) {
        String body = secrets.redact(data.toString());
        if (body.length() > AiProtocol.RESPONSE_CHARS) { kind = "error"; body = "{\"code\":\"RESPONSE_PAGE_TOO_LARGE\"}"; }
        var response = new AiResponsePayload(AiProtocol.VERSION, request, session.token, kind, body);
        synchronized (session.replies) {
            session.replies.put(request, response);
            while (session.replies.size() > 256) session.replies.remove(session.replies.keySet().iterator().next());
        }
        send(owner, session, response, privateData);
    }
    private void send(UUID owner, Session session, AiResponsePayload response, boolean privateData) {
        if (stopping) return;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(owner);
            if (player == null || session.closed || sessions.get(owner) != session || player.connection != session.connection) return;
            if (privateData && (!authenticated(player) || !available())) return;
            PacketDistributor.sendToPlayer(player, response);
        });
    }
    private static JsonObject json(String key, String value) { JsonObject result = new JsonObject(); result.addProperty(key, value); return result; }
    private String nativeApprovalReview(Workspace workspace, Actor actor, ModelMessage.ToolCall call) throws Exception {
        if (!Set.of("maid_control_execute", "maid_control_reconcile", "maid_farm_execute", "maid_farm_reconcile",
                "maid_transport_execute", "maid_transport_return", "maid_transport_reconcile", "maid_combat_execute", "maid_combat_reconcile",
                "tree_harvest_execute", "tree_harvest_reconcile", "waystone_target_prepare_execute",
                "waystone_travel_execute", "waystone_travel_reconcile", "compass_search_execute", "compass_prepared_search_execute", "compass_search_reconcile", "build_recovery_execute").contains(call.name())) return null;
        var args = AiJson.object(call.arguments(), 65536, 24);
        return switch (call.name()) {
            case "maid_control_execute" -> maidControl.review(workspace, actor, UUID.fromString(args.get("planId").getAsString()), args.get("planHash").getAsString());
            case "maid_control_reconcile" -> maidControl.reconciliationReview(maidControl.loadReconciliation(workspace, actor,
                    UUID.fromString(args.get("reviewId").getAsString()), args.get("reviewHash").getAsString()));
            case "maid_farm_execute" -> maidFarm.review(workspace, actor, UUID.fromString(args.get("planId").getAsString()), args.get("planHash").getAsString());
            case "maid_farm_reconcile" -> maidFarm.reconciliationReview(maidFarm.loadReconciliation(workspace, actor,
                    UUID.fromString(args.get("reviewId").getAsString()), args.get("reviewHash").getAsString()));
            case "maid_combat_execute" -> maidCombat.review(workspace, actor, UUID.fromString(args.get("planId").getAsString()), args.get("planHash").getAsString());
            case "maid_combat_reconcile" -> maidCombat.reconciliationReview(maidCombat.loadReconciliation(workspace, actor,
                    UUID.fromString(args.get("reviewId").getAsString()), args.get("reviewHash").getAsString()));
            case "tree_harvest_execute" -> treeHarvest.review(workspace, actor, UUID.fromString(args.get("planId").getAsString()), args.get("planHash").getAsString());
            case "tree_harvest_reconcile" -> treeHarvest.reconciliationReview(treeHarvest.loadReconciliation(workspace, actor,
                    UUID.fromString(args.get("reviewId").getAsString()), args.get("reviewHash").getAsString()));
            case "maid_transport_execute" -> maidTransport.review(workspace, actor, UUID.fromString(args.get("planId").getAsString()), args.get("planHash").getAsString());
            case "maid_transport_return" -> maidTransport.returnReview(workspace, actor, maidTransport.loadReturn(workspace, actor,
                    UUID.fromString(args.get("returnId").getAsString()), args.get("returnHash").getAsString()));
            case "maid_transport_reconcile" -> maidTransport.reconciliationReview(workspace, actor, maidTransport.loadReconciliation(workspace, actor,
                    UUID.fromString(args.get("reviewId").getAsString()), args.get("reviewHash").getAsString()));
            case "waystone_target_prepare_execute" -> waystoneTravel.reviewPreparation(workspace, actor,
                    UUID.fromString(args.get("preparationId").getAsString()), args.get("preparationHash").getAsString());
            case "waystone_travel_execute" -> waystoneTravel.review(workspace, actor, UUID.fromString(args.get("planId").getAsString()), args.get("planHash").getAsString());
            case "waystone_travel_reconcile" -> waystoneTravel.reviewRecovery(workspace, actor, UUID.fromString(args.get("operationId").getAsString()),
                    args.get("revision").getAsLong(), args.get("reviewHash").getAsString());
            case "compass_search_execute" -> exploration.review(workspace, actor, UUID.fromString(args.get("planId").getAsString()), args.get("planHash").getAsString());
            case "compass_prepared_search_execute" -> exploration.preparedReview(workspace, actor,
                    UUID.fromString(args.get("preparationId").getAsString()), args.get("preparationHash").getAsString());
            case "compass_search_reconcile" -> exploration.reconciliationReview(exploration.loadReconciliation(workspace, actor,
                    UUID.fromString(args.get("reviewId").getAsString()), args.get("reviewHash").getAsString()));
            case "build_recovery_execute" -> worldRecovery.reviewText(workspace, actor, worldRecovery.load(workspace, actor,
                    UUID.fromString(args.get("reviewId").getAsString()), args.get("reviewHash").getAsString()));
            default -> throw new IllegalArgumentException("APPROVAL_NATIVE_TOOL_UNKNOWN");
        };
    }
    private static void recoveryFields(JsonObject arguments, Set<String> allowed) {
        if (!allowed.containsAll(arguments.keySet())) throw new IllegalArgumentException("RECOVERY_ARGUMENT_NOT_ALLOWED");
    }
    private Set<UUID> worldMaterialOperations(Workspace workspace) {
        Actor owner = new Actor(workspace.owner(), true, false, 0); var ids = new HashSet<UUID>();
        for (var event : workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events())
            if (event.kind().equals("construction_plan") && event.data().has("planId")) ids.add(UUID.fromString(event.data().get("planId").getAsString()));
        for (var step : new firefly520.fireflymc.ai.blueprint.BuildJournal(workspace, UUID.randomUUID()).list(owner)) ids.add(step.materialOperationId());
        for (var step : new firefly520.fireflymc.ai.blueprint.RemovalJournal(workspace, UUID.randomUUID()).list(owner)) {
            ids.add(step.input().operationId()); ids.add(step.input().planId());
        }
        return Set.copyOf(ids);
    }
    private boolean worldMaterialOperation(Workspace workspace, UUID operation) { return worldMaterialOperations(workspace).contains(operation); }
    private List<JsonObject> worldRecoveryRows(Workspace workspace, UUID task) {
        Actor owner = new Actor(workspace.owner(), true, false, 0); var rows = new ArrayList<JsonObject>();
        var world = worldMaterialOperations(workspace);
        for (var op : materialLedger.list(owner)) if (op.taskId().equals(task) && world.contains(op.operationId()) && (!op.settled() || !op.saved()))
            rows.add(worldRecoveryRow(op.operationId(), task, "BUILD", op.state().name(),
                    "施工材料仍与世界步骤绑定，需要所属施工记录的专用复核；不能通过普通物料返还拆开账本。"));
        for (var step : new firefly520.fireflymc.ai.blueprint.BuildJournal(workspace, UUID.randomUUID()).list(owner))
            if (step.taskId().equals(task) && !step.settled()) rows.add(worldRecoveryRow(step.operationId(), task, "BUILD", step.state().name(),
                    "施工步骤的原生结果尚待专用复核；当前不能通过通用继续重放世界修改。"));
        for (var step : new firefly520.fireflymc.ai.blueprint.RemovalJournal(workspace, UUID.randomUUID()).list(owner))
            if (step.input().taskId().equals(task) && !step.settled()) rows.add(worldRecoveryRow(step.input().operationId(), task, "REMOVAL", step.state().name(),
                    "拆除或撤销的采集、掉落与保存证据尚待专用复核，不能按方块或物品数量猜测完成。"));
        return List.copyOf(rows);
    }
    private static JsonObject worldRecoveryRow(UUID id, UUID task, String kind, String state, String reason) {
        var row = new JsonObject(); row.addProperty("operationId", id.toString()); row.addProperty("taskId", task.toString());
        row.addProperty("kind", kind); row.addProperty("state", state); row.addProperty("reason", "WORLD_VERIFIER_REQUIRED");
        row.addProperty("reasonText", reason); row.add("actions", new JsonArray()); return row;
    }
    private boolean materialsSettled(String owner) { return materialLedger != null && materialLedger.settled(owner)
            && fluidProcessing != null && fluidProcessing.resourcesSettled(owner); }
    private boolean companionResourcesSettled(Workspace workspace) {
        var actor = new Actor(workspace.owner(), true, false, 0);
        try {
            if (maidControl == null ? Files.exists(workspace.directory().resolve("maid-control")) : !maidControl.resourcesSettled(workspace, actor)) return false;
            if (maidFarm == null ? Files.exists(workspace.directory().resolve("maid-farm")) : !maidFarm.resourcesSettled(workspace, actor)) return false;
            if (maidCombat == null ? Files.exists(workspace.directory().resolve("maid-combat")) : !maidCombat.resourcesSettled(workspace, actor)) return false;
            if (treeHarvest == null ? Files.exists(workspace.directory().resolve("tree-harvest")) : !treeHarvest.resourcesSettled(workspace, actor)) return false;
            if (maidTransport == null ? Files.exists(workspace.directory().resolve("maid-transport")) : !maidTransport.resourcesSettled(workspace, actor)) return false;
            if (exploration == null ? Files.exists(workspace.directory().resolve("exploration")) : !exploration.resourcesSettled(workspace, actor)) return false;
            return !(waystoneTravel == null ? Files.exists(workspace.directory().resolve("travel")) : !waystoneTravel.resourcesSettled(workspace, actor));
        } catch (Exception unavailable) { return false; }
    }
    private Map<String, java.util.function.Supplier<String>> companionExportSnapshots(Workspace workspace, Actor actor) throws java.io.IOException {
        var snapshots = new LinkedHashMap<String, java.util.function.Supplier<String>>();
        if (maidControl != null) snapshots.put("maid-control-journal.json", () -> {
            try { return maidControl.exportJournal(workspace, actor); } catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
        });
        else if (Files.exists(workspace.directory().resolve("maid-control"))) throw new java.io.IOException("MAID_JOURNAL_UNAVAILABLE");
        if (maidFarm != null) snapshots.put("maid-farm-journal.json", () -> {
            try { return maidFarm.exportJournal(workspace, actor); } catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
        });
        else if (Files.exists(workspace.directory().resolve("maid-farm"))) throw new java.io.IOException("MAID_FARM_JOURNAL_UNAVAILABLE");
        if (maidCombat != null) snapshots.put("maid-combat-journal.json", () -> {
            try { return maidCombat.exportJournal(workspace, actor); } catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
        });
        else if (Files.exists(workspace.directory().resolve("maid-combat"))) throw new java.io.IOException("MAID_COMBAT_JOURNAL_UNAVAILABLE");
        if (treeHarvest != null) snapshots.put("tree-harvest-journal.json", () -> {
            try { return treeHarvest.exportJournal(workspace, actor); } catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
        });
        else if (Files.exists(workspace.directory().resolve("tree-harvest"))) throw new java.io.IOException("TREE_HARVEST_JOURNAL_UNAVAILABLE");
        if (maidTransport != null) snapshots.put("maid-transport-journal.json", () -> {
            try { return maidTransport.exportJournal(workspace, actor); } catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
        });
        else if (Files.exists(workspace.directory().resolve("maid-transport"))) throw new java.io.IOException("MAID_TRANSPORT_JOURNAL_UNAVAILABLE");
        if (waystoneTravel != null) snapshots.put("travel-journal.json", () -> {
            try { return waystoneTravel.exportJournal(workspace, actor); } catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
        });
        else if (Files.exists(workspace.directory().resolve("travel"))) throw new java.io.IOException("TRAVEL_JOURNAL_UNAVAILABLE");
        if (exploration != null) snapshots.put("exploration-journal.json", () -> {
            try { return exploration.exportJournal(workspace, actor); } catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
        });
        else if (Files.exists(workspace.directory().resolve("exploration"))) throw new java.io.IOException("EXPLORATION_JOURNAL_UNAVAILABLE");
        if (exploration != null) snapshots.put("compass-preparation-journal.json", () -> {
            try { return exploration.exportPreparationJournal(workspace, actor); }
            catch (java.io.IOException failed) { throw new java.io.UncheckedIOException(failed); }
        });
        return Map.copyOf(snapshots);
    }
    private ShareService shares(Workspace workspace) {
        return new ShareService(workspace, secrets, System::currentTimeMillis, configuration.crossChatSharing()
                ? Map.of(ShareService.Target.CROSS_CHAT, new ShareService.Channel("cross-chat", configuration.crossChatAudience(),
                configuration.crossChatEndpointHash())) : Map.of());
    }
    private static String bounded(String text, int limit) { return text.substring(0, Math.min(limit, text.length())); }
    @Override public Actor actor(String owner) { return actors.getOrDefault(owner, new Actor(owner, false, false, 0)); }
    @Override public boolean available() { return ready && !stopping && server.isDedicatedServer(); }
    @Override public boolean yoloMode(String owner) {
        var profile = preferences.get(owner);
        return profile != null && profile.yoloMode();
    }
    @Override public String companionName(Workspace workspace) throws java.io.IOException { return WorkspacePreferences.load(workspace).name(); }
    @Override public String personalContext(Workspace workspace) throws java.io.IOException {
        JsonObject result = new JsonObject();
        result.addProperty("style", WorkspacePreferences.load(workspace).personality());
        var currentTools = tools.all().stream().map(tool -> tool.descriptor().name()).filter(this::enabled).toList();
        result.addProperty("capabilityRevision", AiJson.digest(AiJson.GSON.toJson(currentTools)));
        result.addProperty("processingAvailable", processing != null && enabled("processing_execute"));
        result.addProperty("processingPolicy", "MATERIALS_ONLY");
        result.addProperty("rawFluidProcessingAvailable", fluidProcessing != null && enabled("fluid_processing_execute"));
        result.addProperty("clientCoordinateHint", firefly520.fireflymc.ai.workspace.CoordinateHintStore.context(workspace));
        if (fluidProcessing != null) result.add("pendingMixedMaterials", fluidProcessing.personalContext(workspace));
        result.addProperty("rawFluidProcessingNote", "原料流体与物品授权独立；新加工用fluid_processing_plan/execute，必须有真实原料。联合托管用fluid_processing_status；有保存凭据的完整或部分输入可用fluid_processing_resume_preview/resume重新审批继续，部分缺项仍须原授权来源可用，复核本身不取料；已取消的操作只能获批退还。退还先fluid_processing_return_preview；未知结果先在工作台复核，不能调用独立processing_return。");
        result.addProperty("capabilityNote", "当前工具目录优先于历史不可用结论；目标物品可用 crafting_plan 求解依赖，指定配方用 processing_plan，再由 processing_execute 请求工作台审批；具体配方仍按服务端适配器核验。");
        var notes = new MemoryStore(workspace).view(actor(workspace.owner()));
        result.add("recentMemory", AiJson.GSON.toJsonTree(notes.subList(Math.max(0, notes.size() - 6), notes.size())
                .stream().map(n -> Map.of("id", n.id(), "text", bounded(n.text(), 256), "source", n.source(),
                        "confidence", n.confidence(), "updatedAt", n.updatedAt())).toList()));
        result.addProperty("memoryCount", notes.size());
        var region = new firefly520.fireflymc.ai.blueprint.BuildRegionStore(workspace).current(actor(workspace.owner()));
        if (region.isPresent() && region.get().active()) result.add("selectedBuildRegion",
                firefly520.fireflymc.ai.blueprint.BuildRegionService.view(workspace, region));
        if (materialLedger != null) {
            try {
                var unsettled = materialLedger.list(Actor.player(UUID.fromString(workspace.owner()), true, false, 0)).stream().filter(operation -> !operation.settled()).toList();
                result.addProperty("unsettledMaterialOperationCount", unsettled.size());
                result.add("unsettledMaterialOperations", AiJson.GSON.toJsonTree(unsettled.stream().limit(8).map(operation -> Map.of(
                        "operationId", operation.operationId(), "taskId", operation.taskId(), "state", operation.state(),
                        "recordedEscrowUnits", operation.escrow().stream().mapToLong(MaterialStack::count).sum(),
                        "recordedUndeliveredUnits", operation.remainingProducts().stream().mapToLong(MaterialStack::count).sum())).toList()));
                if (!unsettled.isEmpty()) result.addProperty("materialRecoveryNote", "这些本人账本记录不能因上下文压缩而忽略；用 processing_status 分页读取完整证据。不确定操作须对账，不得重新取料、抽样或调用副作用来猜结果。");
            } catch (RuntimeException journalUnavailable) { result.addProperty("materialRecoveryNote", "材料账本不可读，不能推断已有操作完成或重放；只读会话仍可继续。"); }
        }
        result.addProperty("more", "完整记忆通过 memory_lookup 检索；不把用户偏好当作已验证的世界状态。");
        return result.toString();
    }
    @Override public void modelStarted(Workspace workspace, UUID taskId, UUID streamId) {
        liveText.put(taskId, new LiveText(workspace.owner(), taskId, streamId));
    }
    @Override public void textDelta(Workspace workspace, UUID taskId, UUID streamId, String text) {
        var live = liveText.get(taskId);
        if (live != null && live.owner.equals(workspace.owner()) && live.stream.equals(streamId)) live.update(text);
    }
    @Override public boolean enabled(String tool) { return available() && !configuration.disabledTools().contains(tool); }
    private List<JsonObject> integrationEvidence() {
        var rows = new ArrayList<>(environmentFacts.adapterEvidence());
        rows.add(firefly520.fireflymc.ai.blueprint.NativeRemovalProfiles.evidence(server));
        if (treeHarvest != null) rows.add(treeHarvest.adapterEvidence());
        var snapshot = permissions.probe();
        for (var status : List.of(snapshot.protection(), snapshot.ranks())) {
            var row = new JsonObject(); row.addProperty("modId", status.modId());
            row.addProperty("loadedVersion", status.installedVersion()); row.addProperty("inspectedVersion", status.inspectedVersion());
            row.addProperty("state", switch (status.state()) {
                case STATIC_API_MATCH -> "INTERFACE_MATCHED"; case RUNTIME_CALLABLE -> "RUNTIME_READY";
                case UNSUPPORTED_VERSION -> "VERSION_UNSUPPORTED"; default -> status.state().name();
            });
            row.addProperty("capability", status.modId().equals(InstalledPermissionAdapters.OPAC_ID) ? "逐操作领地保护校验" : "AI 权限节点与配额校验");
            row.addProperty("reason", status.reason()); row.addProperty("evidence", status.evidenceId());
            row.addProperty("behaviorVerifiedHere", false); row.addProperty("scope", "接口状态不授予操作权限；每次操作重新检查。"); rows.add(row);
        }
        return List.copyOf(rows);
    }
    @Override public AgentTool.Result auditToolResult(Workspace workspace, AgentTask task, ModelMessage.ToolCall call, AgentTool.Result result) {
        if (fluidProcessing != null && call.name().startsWith("fluid_processing_"))
            result = fluidProcessing.audit(workspace, AiJson.object(call.arguments(), 65536, 24), result);
        var tool = tools.get(call.name());
        if (materialLedger == null || tool == null || !tool.descriptor().risk().requiresApproval()) return result;
        UUID id;
        try {
            var args = AiJson.object(call.arguments(), 65536, 24);
            String key = args.has("planId") ? "planId" : args.has("operationId") ? "operationId" : "";
            if (key.isEmpty()) return result; id = UUID.fromString(args.get(key).getAsString());
        } catch (RuntimeException invalidArguments) { return result; }
        try {
            var operation = materialLedger.find(Actor.player(UUID.fromString(workspace.owner()), true, false, 0), id);
            if (operation.isEmpty()) return result;
            var op = operation.get(); var data = result.observations().deepCopy(); var observed = new JsonObject();
            observed.addProperty("operationId", id.toString()); observed.addProperty("state", op.state().name());
            observed.addProperty("escrowRecordedUnits", op.escrow().stream().mapToLong(MaterialStack::count).sum());
            observed.addProperty("undeliveredRecordedUnits", op.remainingProducts().stream().mapToLong(MaterialStack::count).sum());
            observed.addProperty("worldPlacementsAcknowledged", op.worldDebits().stream().filter(debit -> debit.count() > 0).count());
            observed.addProperty("externalEffectsAcknowledged", op.externalDebits().stream().filter(effect -> effect.known() && effect.accepted()).count());
            boolean uncertain = op.uncertain() || op.intent() != null;
            boolean saved = op.saved();
            observed.addProperty("recoveryRequired", uncertain);
            observed.addProperty("nativeSaveConfirmed", saved);
            observed.addProperty("note", "服务端账本在回填时的实际记录；存在未决意图时数量仍需对账，不能重放或推断全部完成。");
            data.add("materialEvidence", observed);
            boolean missingFinalSave = result.success() && op.state() == MaterialLedger.State.COMPLETED && !saved;
            return new AgentTool.Result(result.success() && !uncertain && !missingFinalSave,
                    uncertain ? "RECOVERY_REQUIRED" : missingFinalSave ? "PENDING_NATIVE_SAVE" : result.errorCode(),
                    result.summary() + (uncertain ? " 部分游戏操作尚需对账，已停止后续执行。"
                            : missingFinalSave ? " 实际效果已记录，但原生保存尚未确认，不能声称完成。" : " 已附实际材料/世界操作记录。"),
                    data, result.evidence() + " material-ledger:" + id, result.worldChanges(), result.retriable() && !uncertain && !missingFinalSave);
        } catch (RuntimeException missingEvidence) {
            return new AgentTool.Result(false, "EVIDENCE_REQUIRES_REVIEW", "无法确认材料/世界副作用证据，不能声称完成。",
                    result.observations(), result.evidence(), result.worldChanges(), false);
        }
    }
    @Override public boolean requiresRecovery(Workspace workspace, UUID taskId) {
        boolean wrote = workspace.conversation().getSnapshot(0).events().stream().anyMatch(event -> taskId.equals(event.taskId())
                && event.kind().equals("tool_start") && (!event.data().has("risk")
                || !Set.of("READ_ONLY", "MEMORY_WRITE").contains(event.data().get("risk").getAsString())));
        if (!wrote) return false;
        try {
            var proofActor = new Actor(workspace.owner(), true, false, 0);
            if (maidControl != null && maidControl.requiresRecovery(workspace, proofActor, taskId)) return true;
            if (maidFarm != null && !maidFarm.taskSettled(workspace, proofActor, taskId)) return true;
            if (maidCombat != null && !maidCombat.taskSettled(workspace, proofActor, taskId)) return true;
            if (treeHarvest != null && !treeHarvest.taskSettled(workspace, proofActor, taskId)) return true;
            if (maidTransport != null && maidTransport.requiresRecovery(workspace, proofActor, taskId)) return true;
            if (waystoneTravel != null && waystoneTravel.requiresRecovery(workspace, proofActor, taskId)) return true;
            if (exploration != null && exploration.requiresRecovery(workspace, taskId)) return true;
            if (firefly520.fireflymc.ai.blueprint.RemovalJournal.requiresRecovery(workspace, taskId)) return true;
            if (fluidProcessing != null && fluidProcessing.requiresRecovery(workspace, taskId)) return true;
            if (materialLedger != null && materialLedger.list(Actor.player(UUID.fromString(workspace.owner()), true, false, 0)).stream()
                    .anyMatch(operation -> taskId.equals(operation.taskId()) && (operation.uncertain() || operation.intent() != null
                            || operation.state() == MaterialLedger.State.COMPLETED && !operation.saved()))) return true;
            return new firefly520.fireflymc.ai.blueprint.BuildJournal(workspace, UUID.randomUUID())
                    .list(Actor.player(UUID.fromString(workspace.owner()), true, false, 0)).stream()
                    .anyMatch(step -> taskId.equals(step.taskId()) && Set.of(firefly520.fireflymc.ai.blueprint.BuildJournal.State.DISPATCHING,
                            firefly520.fireflymc.ai.blueprint.BuildJournal.State.OBSERVED, firefly520.fireflymc.ai.blueprint.BuildJournal.State.RECOVERY_REQUIRED).contains(step.state()));
        } catch (Exception invalidJournal) { return true; }
    }
    @Override public boolean recoverySettled(Workspace workspace, UUID taskId) {
        var actor = new Actor(workspace.owner(), true, false, 0);
        try {
            if (maidFarm != null && !maidFarm.taskSettled(workspace, actor, taskId)
                    || maidTransport != null && maidTransport.requiresRecovery(workspace, actor, taskId)) return false;
            if (maidCombat != null && !maidCombat.taskSettled(workspace, actor, taskId)
                    || treeHarvest != null && !treeHarvest.taskSettled(workspace, actor, taskId)) return false;
            if (maidControl != null && !maidControl.taskSettled(workspace, actor, taskId)
                    || waystoneTravel != null && !waystoneTravel.settledForTask(workspace, actor, taskId)) return false;
            if (exploration != null && exploration.requiresRecovery(workspace, taskId)) return false;
            return firefly520.fireflymc.ai.recovery.RecoveryTaskEvidence.allSettled(workspace, actor,
                    taskId, materialLedger, fluidProcessing, call -> recovery == null ? Optional.empty() : recovery.settledModuleCall(workspace, actor, taskId, call));
        } catch (Exception unconfirmed) { return false; }
    }
    @Override public CompletableFuture<AgentTool.Result> execute(AgentTool tool, AgentTool.Context context,
            JsonObject arguments, String approval, ModelMessage.ToolCall call) {
        return gameBridge.execute(tool, context, arguments, approval, call);
    }
    @Override public void changed(Workspace workspace, AgentTask task, String notice) {
        if (maidCombat != null) maidCombat.taskChanged(workspace, task);
        liveText.remove(task.id());
        UUID owner = UUID.fromString(workspace.owner());
        Session session = sessions.get(owner);
        if (session == null || session.closed) return;
        JsonObject data = json("taskId", task.id().toString());
        data.addProperty("state", task.state().name());
        data.addProperty("revision", workspace.conversation().revision());
        data.addProperty("notice", bounded(notice, 600));
        send(owner, session, new AiResponsePayload(AiProtocol.VERSION, UUID.randomUUID(), session.token,
                "changed", secrets.redact(data.toString())), true);
    }
    public CompletionStage<Void> shutdownCompletion() { return shutdownComplete.minimalCompletionStage(); }
    public void stop() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (stopping) return;
        stopping = true;
        ready = false;
        closeNativeQueues();
        sessions.values().forEach(s -> s.closed = true);
        sessions.clear();
        liveText.clear();
        selectedSources.clear();
        selectedFluidSources.clear();
        io.shutdown();
        // Accepted work retains its IO owner. Native saves occur next in Minecraft's stop sequence.
        toolIo.shutdown();
        exportIo.shutdownNow();
        research.close();
    }
    private void closeNativeQueues() {
        if (recovery != null) recovery.close();
        if (maidControl != null) maidControl.close();
        if (maidFarm != null) maidFarm.close();
        if (maidCombat != null) maidCombat.close();
        if (treeHarvest != null) treeHarvest.close();
        if (maidTransport != null) maidTransport.close();
        if (waystoneTravel != null) waystoneTravel.close();
        if (exploration != null) exploration.close();
        blueprints.close();
        equipment.close();
        buildProgress.clear();
        if (construction != null) construction.close();
        if (advancedProcessing != null) advancedProcessing.close();
        if (specialProcessing != null) specialProcessing.close();
        if (fluidProcessing != null) fluidProcessing.close();
        inventory.close();
        fluids.close();
        if (processing != null) processing.close();
        if (runtime != null) runtime.close();
    }
    /** The native server is no longer ticking here; only bounded immutable journal IO remains. */
    public void finishStopped() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (shutdownComplete.isDone()) return;
        stop(); Exception failure = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try {
            if (!io.awaitTermination(remaining(deadline), TimeUnit.MILLISECONDS)) throw new TimeoutException("AI_REQUEST_IO_NOT_DRAINED");
            // Initialization itself runs on io and may have created these services after stop().
            closeNativeQueues();
            if (!toolIo.awaitTermination(remaining(deadline), TimeUnit.MILLISECONDS)) throw new TimeoutException("AI_TOOL_IO_NOT_DRAINED");
            if (processing != null && !processing.awaitEvidenceStopped(remaining(deadline))) throw new TimeoutException("AI_NATIVE_EVIDENCE_NOT_DRAINED");
            if (maidControl != null && !maidControl.awaitStopped(remaining(deadline))) throw new TimeoutException("AI_MAID_EVIDENCE_NOT_DRAINED");
            if (maidCombat != null && !maidCombat.awaitStopped(remaining(deadline))) throw new TimeoutException("AI_MAID_COMBAT_EVIDENCE_NOT_DRAINED");
            if (treeHarvest != null && !treeHarvest.awaitStopped(remaining(deadline))) throw new TimeoutException("AI_TREE_EVIDENCE_NOT_DRAINED");
            if (maidTransport != null && !maidTransport.awaitStopped(remaining(deadline))) throw new TimeoutException("AI_MAID_TRANSPORT_EVIDENCE_NOT_DRAINED");
            if (waystoneTravel != null && !waystoneTravel.awaitStopped(remaining(deadline))) throw new TimeoutException("AI_TRAVEL_EVIDENCE_NOT_DRAINED");
            if (exploration != null && !exploration.awaitStopped(remaining(deadline))) throw new TimeoutException("AI_EXPLORATION_EVIDENCE_NOT_DRAINED");
            if (!exportIo.awaitTermination(remaining(deadline), TimeUnit.MILLISECONDS)) throw new TimeoutException("AI_EXPORT_IO_NOT_DRAINED");
            if (runtime != null && !runtime.awaitStopped(remaining(deadline))) throw new TimeoutException("AI_RUNTIME_NOT_DRAINED");
        } catch (Exception failed) { failure = failed; }
        // Receipts may be registered by the accepted IO work above after native files have already saved.
        firefly520.fireflymc.ai.material.NativeSaveObserver.serverStopped(server);
        try {
            if (!inventory.nativeSaves().awaitStopped(remaining(deadline))) throw new IllegalStateException("NATIVE_SAVE_JOURNAL_NOT_DRAINED");
            if (failure == null && workspaces != null) {
                var finalized = new CompletableFuture<Void>();
                Thread.ofPlatform().daemon(false).name("fireflymc-ai-store-close").start(() -> {
                    try {
                        if (runtime != null) runtime.finalizePendingClose();
                        if (recovery != null) {
                            recovery.finalizePendingClose();
                            if (!recovery.awaitStopped(remaining(deadline))) throw new TimeoutException("AI_RECOVERY_EVIDENCE_NOT_DRAINED");
                        }
                        if (waystoneTravel != null) waystoneTravel.finalizePendingClose();
                        workspaces.close(); finalized.complete(null);
                    } catch (Exception error) { finalized.completeExceptionally(error); }
                });
                finalized.get(remaining(deadline), TimeUnit.MILLISECONDS);
            }
        } catch (Exception failed) { if (failure == null) failure = failed; else failure.addSuppressed(failed); }
        if (failure == null) shutdownComplete.complete(null);
        else {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            FireflyMCMod.LOGGER.error("[AI4] Shutdown did not drain all durable evidence ({})", failure.getClass().getSimpleName());
            shutdownComplete.completeExceptionally(failure);
        }
    }
    private static long remaining(long deadline) { return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())); }
}
