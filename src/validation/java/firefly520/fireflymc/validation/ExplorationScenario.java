package firefly520.fireflymc.validation;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.AiServerService;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.integration.exploration.*;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.*;
import firefly520.fireflymc.mixin.CompassStructureStateAccess;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.*;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Real installed compass workers, native nonzero fees/damage and save receipts in an isolated dedicated server. */
public final class ExplorationScenario implements AutoCloseable {
    private final MinecraftServer server;
    private final WorkspaceManager manager;
    private final MaterialLedger materials;
    private final MaterialInventoryService inventory;
    private final VirtualProcessingService dispatcher;
    private final CompassSearchService service;
    private final ToolRegistry registry = new ToolRegistry();
    private final AtomicLong generation = new AtomicLong(1);
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<String> passed = new CopyOnWriteArrayList<>();
    private final Map<Object, Object> originalConfig = new LinkedHashMap<>();
    private final Session owner, other;
    private final GameToolBridge bridge;
    private final ExecutorService io = Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon(true).name("exploration-fixture-tools-", 0).factory());
    private final CompletableFuture<JsonObject> result;
    private final boolean preparedOnly = Boolean.getBoolean("fireflymc.validation.explorationPreparedOnly");
    private final Set<net.minecraft.world.level.ChunkPos> preparationFixtureTickets = new HashSet<>();
    private volatile TaskControl cancelOnWork;
    private volatile boolean changeConfigOnWork;
    private volatile Runnable planAdmissionHook;
    private volatile boolean moveHandOnWork;
    private double maximumDispatchSliceMillis;
    private int inputWaits, nativeSaveWaits;
    private final boolean forceFixtureSaves = Boolean.getBoolean("fireflymc.validation.forceFixtureSaves");
    private int fixtureSaveRequests;
    private JsonObject preparedDiagnostic = new JsonObject();
    public ExplorationScenario(MinecraftServer server) throws Exception {
        this.server = server;
        if (!server.isDedicatedServer() || !ModList.get().isLoaded("naturescompass") || !ModList.get().isLoaded("explorerscompass"))
            throw new IllegalStateException("ACTUAL_DEDICATED_COMPASS_MODS_REQUIRED");
        Path root = Files.createTempDirectory(server.getWorldPath(LevelResource.ROOT).toAbsolutePath(), "ai4-exploration-");
        manager = new WorkspaceManager(root); materials = new MaterialLedger(root);
        var permissions = new InstalledPermissionAdapters(server);
        inventory = new MaterialInventoryService(server, AiServerService::authenticated, permissions);
        dispatcher = new VirtualProcessingService(server, inventory, materials, generation::get);
        service = new CompassSearchService(server, this::authenticate, permissions, dispatcher, generation::get, () -> true);
        service.register(registry); owner = new Session(); other = new Session();
        bridge = new GameToolBridge(server, identity -> {
            try { return main(() -> {
                var player = server.getPlayerList().getPlayer(UUID.fromString(identity));
                return player == null ? new Actor(identity, false, false, 0) : Actor.player(player.getUUID(), AiServerService.authenticated(player), true, 0);
            }); } catch (Exception failure) { throw new CompletionException(failure); }
        }, name -> registry.get(name) != null, () -> false, io);
        configure(CompassSearchPlan.Kind.BIOME, "defaultXpLevels", 2);
        configure(CompassSearchPlan.Kind.BIOME, "perBiomeXpLevels", List.of());
        configure(CompassSearchPlan.Kind.BIOME, "compassDurability", 3);
        configure(CompassSearchPlan.Kind.BIOME, "displayCoordinates", true);
        configure(CompassSearchPlan.Kind.STRUCTURE, "defaultXpLevels", 2);
        configure(CompassSearchPlan.Kind.STRUCTURE, "xpLevelOverrides", List.of());
        configure(CompassSearchPlan.Kind.STRUCTURE, "compassDurability", 3);
        result = CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime(); var report = new JsonObject();
            try { if (preparedOnly) runPreparedOnly(); else run(); report.addProperty("status", "PASSED"); }
            catch (Throwable failure) { failure.printStackTrace(); report.addProperty("status", "FAILED"); report.addProperty("error", failure.toString()); }
            report.add("passedChecks", AiJson.GSON.toJsonTree(passed)); report.addProperty("durationMillis", (System.nanoTime() - start) / 1_000_000);
            report.addProperty("nativeRunWaits", inputWaits); report.addProperty("nativeSaveWaits", nativeSaveWaits);
            report.addProperty("forceFixtureSaves", forceFixtureSaves); report.addProperty("fixtureSaveRequests", fixtureSaveRequests);
            if (preparedOnly) report.add("preparedDiagnostic", preparedDiagnostic.deepCopy());
            report.addProperty("scope", preparedOnly ? "Actual installed Explorer worker, finite STRUCTURE_STARTS preparation and canonical native writes with authenticated mock dedicated players. No graphical client/model/FULL terrain generation/full-range absence claim. Only fixture setup positions players; production never teleports them."
                    : "Actual installed compass classes/workers and authenticated mock dedicated players; controlled nonzero native XP/damage config restored after fixture. No graphical clients, model, teleport, command or full-modpack claim. This branch only exercises loaded-candidate Explorer searches.");
            return report;
        }, runnable -> Thread.ofPlatform().daemon(true).name("exploration-validation").start(runnable));
    }
    private boolean authenticate(ServerPlayer player) {
        var planningHook = planAdmissionHook;
        if (planningHook != null && StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> frame.getClassName().equals(CompassSearchService.class.getName())
                && frame.getMethodName().startsWith("lambda$plan$")))) { planAdmissionHook = null; planningHook.run(); }
        boolean work = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> frame.getClassName().equals(CompassSearchService.class.getName() + "$RuntimeJob") && frame.getMethodName().equals("work")));
        if (work) {
            var cancel = cancelOnWork; if (cancel != null) { cancelOnWork = null; cancel.stop("CANCELLED"); }
            if (changeConfigOnWork) {
                changeConfigOnWork = false;
                try { configure(CompassSearchPlan.Kind.BIOME, "defaultXpLevels", 3); } catch (Exception failure) { throw new IllegalStateException(failure); }
            }
            if (moveHandOnWork) {
                moveHandOnWork = false;
                var stack = player.getMainHandItem(); player.setItemInHand(InteractionHand.OFF_HAND, stack); player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            }
        }
        return AiServerService.authenticated(player);
    }
    private void run() throws Exception {
        main(() -> {
            var state = server.overworld().getChunkSource().getGeneratorState();
            if (!(state instanceof CompassStructureStateAccess accessor)) throw new IllegalStateException("ACTUAL_STRUCTURE_STATE_ACCESSOR_MISSING");
            var field = net.minecraft.world.level.chunk.ChunkGeneratorStructureState.class.getDeclaredField("ringPositions"); field.setAccessible(true);
            check(field.get(state) == accessor.fireflymc$ringPositions(), "actual-locked-minecraft-ring-accessor-reads-the-native-mojmap-field");
            return true;
        });
        main(() -> { reset(owner, CompassSearchPlan.Kind.BIOME); reset(other, CompassSearchPlan.Kind.BIOME); return true; });
        // Deterministically start the other player's actual ordinary native worker during the AI
        // request's main-thread admission, before busy arbitration. No native worker object is faked.
        planAdmissionHook = () -> {
            try {
                var stack = other.player.getMainHandItem();
                stack.getItem().getClass().getMethod("searchForBiome", ServerLevel.class, net.minecraft.world.entity.player.Player.class,
                        net.minecraft.resources.ResourceLocation.class, net.minecraft.core.BlockPos.class, ItemStack.class)
                        .invoke(stack.getItem(), other.player.serverLevel(), other.player, net.minecraft.resources.ResourceLocation.parse("minecraft:desert"), other.player.blockPosition(), stack);
            } catch (Exception failure) { throw new IllegalStateException("ORDINARY_NATIVE_WORKER_SETUP_FAILED", failure); }
        };
        expect(() -> plan(context(owner, "Do not stop another player's existing native compass worker"), CompassSearchPlan.Kind.BIOME, "minecraft:plains", 512), "COMPASS_NATIVE_SEARCH_BUSY");
        check(planAdmissionHook == null && main(() -> owner.player.experienceLevel) == 6 && main(() -> other.player.experienceLevel) == 4,
                "busy-native-worker-is-not-overwritten-and-only-its-own-ordinary-start-fee-was-charged");
        main(() -> {
            var stack = other.player.getMainHandItem(); var field = stack.getItem().getClass().getDeclaredField("worker"); field.setAccessible(true); var nativeWorker = field.get(stack.getItem());
            if (nativeWorker != null) nativeWorker.getClass().getMethod("stop").invoke(nativeWorker);
            stack.getItem().getClass().getMethod("setInactive", ItemStack.class, net.minecraft.world.entity.player.Player.class).invoke(stack.getItem(), stack, other.player);
            reset(other, CompassSearchPlan.Kind.BIOME); return true;
        });
        var first = context(owner, "Find plains with real native compass fees and damage"); var plan = plan(first, CompassSearchPlan.Kind.BIOME, "minecraft:plains", 512);
        var args = arguments(plan); var call = new ModelMessage.ToolCall(UUID.randomUUID().toString(), "compass_search_execute", args.toString());
        var refused = bridge.execute(registry.get(call.name()), first, args, "", call).get(10, TimeUnit.SECONDS);
        check(!refused.success() && refused.errorCode().equals("EXPLICIT_APPROVAL_REQUIRED")
                && refused.observations().get("dispatch").getAsString().equals("REJECTED_BEFORE_EXECUTION"), "native-compass-search-requires-fixed-approval");
        check(service.prepare(owner.workspace).find(owner.actor, plan.id()).isEmpty() && main(() -> owner.player.experienceLevel) == 6,
                "unapproved-search-neither-starts-worker-nor-charges-xp");
        var found = execute(first, plan);
        check(found.success() && found.observations().get("nativeSaveConfirmed").getAsBoolean(), "actual-native-biome-search-verifies-result-and-native-save");
        check(main(() -> owner.player.experienceLevel) == 4 && damage(owner, CompassSearchPlan.Kind.BIOME) == 1,
                "native-xp-two-levels-and-success-durability-are-not-materials-only-exempt");
        awaitPublishedSaved(plan.id(), first.control());
        var originalCompass = main(() -> ItemSnapshotCodec.capture(owner.player.getMainHandItem(), owner.player.registryAccess()));
        service.prepare(owner.workspace).requireRecovery(owner.actor, plan.id(), "CONTROLLED_REVIEW_OF_REAL_SAVED_NATIVE_OUTCOME");
        var module = new CompassRecoveryModule(service);
        var recoveryContext = new AgentTool.Context(owner.workspace, owner.actor, first.control(), first.taskId());
        var preview = module.preview(recoveryContext, plan.id());
        module.validatePreview(owner.workspace, owner.actor, first.taskId(), plan.id(), preview.arguments());
        check(!module.settled(owner.workspace, owner.actor, plan.id()), "saved-native-recovery-fence-needs-new-explicit-review");
        var recoveryCall = new ModelMessage.ToolCall(UUID.randomUUID().toString(), preview.toolName(), preview.arguments().toString());
        var recovered = bridge.execute(registry.get(preview.toolName()), recoveryContext, preview.arguments(), ToolPolicyEngine.digest(owner.actor.owner(), recoveryCall), recoveryCall).get(10, TimeUnit.SECONDS);
        check(recovered.success() && module.settled(owner.workspace, owner.actor, plan.id()) && module.settledCall(owner.workspace, owner.actor, first.taskId(), recoveryCall).orElse(false)
                && main(() -> owner.player.experienceLevel) == 4 && originalCompass.equals(main(() -> ItemSnapshotCodec.capture(owner.player.getMainHandItem(), owner.player.registryAccess()))),
                "actual-saved-compass-recovery-module-only-restores-known-result-without-search-refund-or-item-change");
        expect(() -> { module.validatePreview(owner.workspace, owner.actor, first.taskId(), plan.id(), preview.arguments()); return null; }, "COMPASS_RECONCILIATION_VERSION_CHANGED");
        var nativeBefore = main(() -> ItemSnapshotCodec.capture(owner.player.getMainHandItem(), owner.player.registryAccess()));
        var duplicate = registry.get("compass_search_execute").execute(first, args);
        check(!duplicate.success() && main(() -> owner.player.experienceLevel) == 4 && nativeBefore.equals(main(() -> ItemSnapshotCodec.capture(owner.player.getMainHandItem(), owner.player.registryAccess()))),
                "duplicate-operation-never-restarts-charges-or-damages-native-compass");
        var otherContext = context(other, "Cannot inspect another player's compass operation");
        var statusArgs = new JsonObject(); statusArgs.addProperty("operationId", plan.id().toString());
        check(!registry.get("compass_search_status").execute(otherContext, statusArgs).success() && main(() -> other.player.experienceLevel) == 6,
                "second-real-player-cannot-read-or-consume-first-private-search");
        check(!owner.workspace.primaryConversationId().equals(other.workspace.primaryConversationId()), "two-owner-permanent-primary-conversations-remain-distinct");
        main(() -> { reset(owner, CompassSearchPlan.Kind.BIOME); owner.player.giveExperienceLevels(-6); return true; });
        expect(() -> plan(context(owner, "Insufficient native XP"), CompassSearchPlan.Kind.BIOME, "minecraft:plains", 512), "COMPASS_XP_LEVELS_REQUIRED");
        main(() -> { reset(owner, CompassSearchPlan.Kind.BIOME); owner.player.getCooldowns().addCooldown(owner.player.getMainHandItem().getItem(), 200); return true; });
        expect(() -> plan(context(owner, "Native cooldown remains enforced"), CompassSearchPlan.Kind.BIOME, "minecraft:plains", 512), "COMPASS_COOLDOWN");
        main(() -> { owner.player.getCooldowns().removeCooldown(owner.player.getMainHandItem().getItem()); return true; });
        var changed = context(owner, "Native component changes revoke preview"); var changedPlan = plan(changed, CompassSearchPlan.Kind.BIOME, "minecraft:plains", 512);
        main(() -> { owner.player.getMainHandItem().set(DataComponents.CUSTOM_NAME, Component.literal("changed after preview")); return true; });
        var changedResult = execute(changed, changedPlan);
        check(!changedResult.success() && changedResult.errorCode().equals("COMPASS_COMPONENTS_CHANGED") && main(() -> owner.player.experienceLevel) == 6,
                "component-version-change-before-start-is-free-and-does-not-start-worker");
        main(() -> { reset(owner, CompassSearchPlan.Kind.BIOME); return true; });
        var swapped = context(owner, "An identical copied stack is still a different native binding"); var swappedPlan = plan(swapped, CompassSearchPlan.Kind.BIOME, "minecraft:plains", 512);
        main(() -> { owner.player.setItemInHand(InteractionHand.MAIN_HAND, owner.player.getMainHandItem().copy()); return true; });
        var swappedResult = execute(swapped, swappedPlan);
        check(!swappedResult.success() && swappedResult.errorCode().equals("COMPASS_HAND_BINDING_CHANGED") && main(() -> owner.player.experienceLevel) == 6,
                "identical-replacement-stack-does-not-borrow-an-old-preview-binding");
        var returnLevel = main(owner.player::serverLevel); var returnPosition = main(owner.player::position);
        try {
            String absentAtOrigin = main(() -> {
                var nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
                if (nether == null) throw new IllegalStateException("REAL_MULTIBIOME_NETHER_FIXTURE_REQUIRED");
                // Isolated fixture setup only, never a production AI travel path. A flat plains
                // generator legitimately matches at the first sample even with radius one.
                owner.player.teleportTo(nether, 0.5, 64, 0.5, 0, 0); reset(owner, CompassSearchPlan.Kind.BIOME);
                var source = nether.getChunkSource().getGenerator().getBiomeSource(); var sampler = nether.getChunkSource().randomState().sampler();
                var registry = owner.player.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
                var choices = new TreeSet<String>(); source.possibleBiomes().forEach(holder -> choices.add(Objects.requireNonNull(registry.getKey(holder.value())).toString()));
                int[] heights = net.minecraft.util.Mth.outFromOrigin(owner.player.getBlockY(), nether.getMinBuildHeight() + 1, nether.getMaxBuildHeight(), 64).toArray();
                for (int height : heights) {
                    var actual = source.getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(owner.player.getBlockX()), net.minecraft.core.QuartPos.fromBlock(height),
                            net.minecraft.core.QuartPos.fromBlock(owner.player.getBlockZ()), sampler);
                    choices.remove(Objects.requireNonNull(registry.getKey(actual.value())).toString());
                }
                var utility = Class.forName(CompassSearchPlan.Kind.BIOME.prefix() + ".util.BiomeUtils");
                for (String choice : choices) {
                    var key = net.minecraft.resources.ResourceLocation.parse(choice);
                    boolean banned = (boolean) utility.getMethod("biomeKeyIsBlacklisted", net.minecraft.world.level.Level.class, net.minecraft.resources.ResourceLocation.class).invoke(null, nether, key);
                    boolean hidden = (boolean) utility.getMethod("biomeKeyIsHidden", net.minecraft.world.level.Level.class, net.minecraft.resources.ResourceLocation.class).invoke(null, nether, key);
                    if (!banned && !hidden) return choice;
                }
                throw new IllegalStateException("REAL_NATIVE_EXTENT_FIXTURE_HAS_NO_UNMATCHED_ALLOWED_BIOME");
            });
            var bounded = context(owner, "Finite extent cannot become a full-world biome scan"); var boundedPlan = plan(bounded, CompassSearchPlan.Kind.BIOME, absentAtOrigin, 1);
            var stopped = execute(bounded, boundedPlan);
            check(!stopped.success() && Set.of("COMPASS_RADIUS_LIMIT", "COMPASS_SLICE_LIMIT").contains(stopped.errorCode())
                    && main(() -> owner.player.experienceLevel) == 4 && damage(owner, CompassSearchPlan.Kind.BIOME) == 0,
                    "extent-stop-is-incomplete-and-keeps-real-start-xp-without-fake-success-damage-or-refund");
        } finally { main(() -> { owner.player.teleportTo(returnLevel, returnPosition.x, returnPosition.y, returnPosition.z, 0, 0); return true; }); }
        main(() -> { reset(owner, CompassSearchPlan.Kind.BIOME); return true; });
        var cancelled = context(owner, "Cancel at real worker admission after native fee charge"); var cancelledPlan = plan(cancelled, CompassSearchPlan.Kind.BIOME, "minecraft:plains", 512);
        cancelOnWork = cancelled.control(); var cancellation = execute(cancelled, cancelledPlan);
        check(!cancellation.success() && cancellation.errorCode().equals("CANCELLED") && cancelOnWork == null
                && main(() -> owner.player.experienceLevel) == 4 && damage(owner, CompassSearchPlan.Kind.BIOME) == 0,
                "accepted-native-search-cancellation-stops-before-noise-and-does-not-refund-or-replay");
        main(() -> { reset(owner, CompassSearchPlan.Kind.BIOME); return true; });
        var config = context(owner, "Config changes cannot continue an old paid search approval"); var configPlan = plan(config, CompassSearchPlan.Kind.BIOME, "minecraft:plains", 512);
        changeConfigOnWork = true; var configResult = execute(config, configPlan);
        check(!configResult.success() && configResult.errorCode().equals("COMPASS_CONFIG_CHANGED") && !changeConfigOnWork
                && main(() -> owner.player.experienceLevel) == 4, "native-config-change-during-search-stops-without-second-charge");
        main(() -> { configure(CompassSearchPlan.Kind.BIOME, "defaultXpLevels", 2); configure(CompassSearchPlan.Kind.BIOME, "displayCoordinates", false); reset(owner, CompassSearchPlan.Kind.BIOME); return true; });
        var hidden = context(owner, "Native hidden-coordinate settings are not overridden by AI"); var hiddenPlan = plan(hidden, CompassSearchPlan.Kind.BIOME, "minecraft:plains", 512);
        var hiddenResult = execute(hidden, hiddenPlan);
        check(hiddenResult.success() && !hiddenResult.observations().getAsJsonObject("result").get("coordinatesVisible").getAsBoolean()
                && !hiddenResult.observations().getAsJsonObject("result").has("x") && !hiddenResult.observations().getAsJsonObject("result").has("z"),
                "actual-found-native-compass-keeps-hidden-coordinates-out-of-model-result");
        main(() -> { reset(owner, CompassSearchPlan.Kind.STRUCTURE); return true; });
        var explorer = context(owner, "Loaded-only native structure search cannot force new chunks");
        var explorerPlan = plan(explorer, CompassSearchPlan.Kind.STRUCTURE, "minecraft:village_plains", 512);
        var explored = execute(explorer, explorerPlan);
        check(explored.success() && explored.observations().getAsJsonObject("result").get("verified").getAsBoolean()
                || !explored.success() && Set.of("COMPASS_UNLOADED_CHUNK_REQUIRED", "COMPASS_RADIUS_LIMIT", "COMPASS_CHECK_LIMIT", "COMPASS_SLICE_LIMIT", "NATIVE_COMPASS_NOT_FOUND").contains(explored.errorCode()),
                "real-explorer-worker-is-verified-loaded-result-or-explicit-bounded-incomplete-not-invented-coordinates");
        check(!explored.observations().get("newChunksAllowed").getAsBoolean() && main(() -> owner.player.experienceLevel) == 4,
                "explorer-uses-real-native-start-charge-and-no-new-chunk-policy");
        check(service.resourcesSettled(owner.workspace, owner.actor), "all-known-native-search-results-have-positive-actual-file-receipts");
        main(() -> { reset(owner, CompassSearchPlan.Kind.BIOME); return true; });
        var lost = context(owner, "A hand transfer after the native charge cannot clear persistence uncertainty"); var lostPlan = plan(lost, CompassSearchPlan.Kind.BIOME, "minecraft:plains", 512);
        moveHandOnWork = true; var moved = execute(lost, lostPlan);
        check(!moved.success() && moved.errorCode().equals("RECOVERY_REQUIRED") && !moveHandOnWork
                && main(() -> owner.player.experienceLevel) == 4 && main(() -> !owner.player.getOffhandItem().isEmpty()),
                "real-hand-transfer-after-fee-stops-owned-worker-and-retains-uncertain-save-obligation");
        var uncertainRows = module.rows(owner.workspace, owner.actor, lost.taskId());
        check(uncertainRows.size() == 1 && uncertainRows.getFirst().getAsJsonArray("actions").isEmpty() && !service.resourcesSettled(owner.workspace, owner.actor),
                "unknown-compass-effect-has-no-generic-continue-reconcile-or-privacy-clear");
        expect(() -> module.preview(new AgentTool.Context(owner.workspace, owner.actor, new TaskControl(120000), lost.taskId()), lostPlan.id()), "COMPASS_RECONCILIATION_INVALID");
    }
    private record PreparationFixture(net.minecraft.world.level.ChunkPos candidate, net.minecraft.world.level.ChunkPos origin) { }
    private void runPreparedOnly() throws Exception {
        var fixture = main(() -> {
            reset(owner, CompassSearchPlan.Kind.STRUCTURE); reset(other, CompassSearchPlan.Kind.STRUCTURE);
            var level = server.overworld(); var registry = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
            var structure = Objects.requireNonNull(registry.get(net.minecraft.resources.ResourceLocation.parse("minecraft:village_plains")));
            var placements = level.getChunkSource().getGeneratorState().getPlacementsForStructure(registry.wrapAsHolder(structure));
            var placement = placements.stream().filter(value -> value instanceof net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)
                    .map(value -> (net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement) value).findFirst().orElseThrow();
            check(placement.spacing() >= 28, "actual-village-placement-has-room-for-finite-isolated-candidate-fixture");
            int base = 100_000 + (int) Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100_000L);
            var candidate = placement.getPotentialStructureChunk(level.getSeed(), base, base);
            int relative = Math.floorMod(candidate.x, placement.spacing());
            var origin = new net.minecraft.world.level.ChunkPos(candidate.x + (relative >= 14 ? -14 : 14), candidate.z);
            check(placement.getPotentialStructureChunk(level.getSeed(), origin.x, origin.z).equals(candidate), "fixture-origin-keeps-the-actual-native-first-placement-candidate");
            return new PreparationFixture(candidate, origin);
        });
        var beforeDisk = main(() -> server.overworld().getChunkSource().chunkMap.read(fixture.candidate())).get(10, TimeUnit.SECONDS);
        check(beforeDisk.isEmpty(), "candidate-is-not-an-existing-persisted-chunk-before-approved-preparation");
        main(() -> {
            var level = server.overworld(); var origin = fixture.origin();
            level.getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.FORCED, origin, 0, origin); preparationFixtureTickets.add(origin);
            var block = new net.minecraft.core.BlockPos(origin.getMiddleBlockX(), -59, origin.getMiddleBlockZ()); level.getChunkAt(block);
            owner.player.moveTo(block.getX() + .5, block.getY(), block.getZ() + .5);
            check(level.getChunkSource().getChunkNow(fixture.candidate().x, fixture.candidate().z) == null, "actual-native-candidate-is-unloaded-at-fixed-search-preview");
            return true;
        });
        var originalPosition = main(owner.player::position);
        var context = context(owner, "Find structure with explicitly approved tiny native preparation, never FULL terrain generation");
        var plan = service.planPrepared(context, "minecraft:village_plains", "MAIN_HAND", new CompassSearchBudget(512, 64, 15000), new CompassPreparationBudget(2, 15000));
        var args = new JsonObject(); args.addProperty("preparationId", plan.id().toString()); args.addProperty("preparationHash", plan.hash());
        var call = new ModelMessage.ToolCall(UUID.randomUUID().toString(), "compass_prepared_search_execute", args.toString());
        var denied = bridge.execute(registry.get(call.name()), context, args, "", call).get(10, TimeUnit.SECONDS);
        check(!denied.success() && denied.errorCode().equals("EXPLICIT_APPROVAL_REQUIRED") && main(() -> owner.player.experienceLevel) == 6
                && service.prepare(owner.workspace).find(owner.actor, plan.search().id()).isEmpty(), "tiny-new-chunk-policy-needs-its-own-exact-workbench-approval-before-native-charge-or-generation");
        expect(() -> service.loadPrepared(other.workspace, other.actor, plan.id(), plan.hash()), "COMPASS_PREPARATION_PLAN_NOT_FOUND");
        var outcome = executeTool(context, "compass_prepared_search_execute", args);
        diagnosePrepared(plan.search().id(), outcome);
        awaitPublishedSaved(plan.search().id(), context.control());
        var preparation = latestPreparation(plan.search().id());
        diagnosePrepared(plan.search().id(), outcome);
        check(!preparation.candidates().isEmpty() && preparation.candidates().size() <= 2, "real-native-search-used-at-least-one-and-at-most-two-approved-candidate-preparations");
        check(preparation.candidates().getFirst().x() == fixture.candidate().x && preparation.candidates().getFirst().z() == fixture.candidate().z,
                "paused-native-worker-resumed-the-original-candidate-without-skipping-it");
        long wait = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < wait && preparation.candidates().stream().anyMatch(candidate -> !candidate.nativeReady() || candidate.saveProof().isEmpty())) {
            context.control().check();
            save(owner.player, other.player); Thread.sleep(50); preparation = latestPreparation(plan.search().id());
        }
        check(preparation.candidates().stream().allMatch(candidate -> candidate.nativeReady() && !candidate.saveProof().isEmpty()),
                "each-accepted-native-STRUCTURE_STARTS-has-its-own-real-canonical-save-receipt");
        check(main(() -> owner.player.experienceLevel) == 4 && main(owner.player::position).equals(originalPosition), "native-search-start-was-charged-once-and-production-did-not-teleport-the-player");
        var disk = main(() -> server.overworld().getChunkSource().chunkMap.read(fixture.candidate())).get(10, TimeUnit.SECONDS).orElseThrow();
        check("minecraft:structure_starts".equals(disk.getString("Status")), "fresh-candidate-was-canonically-saved-at-STRUCTURE_STARTS-without-FULL-terrain");
        check(main(() -> server.overworld().getChunkSource().getChunkNow(fixture.candidate().x, fixture.candidate().z)) == null,
                "preparation-did-not-load-the-candidate-as-a-FULL-level-chunk");
        if (!service.operationSettled(owner.workspace, owner.actor, plan.search().id())) {
            check(outcome.errorCode().equals("RECOVERY_REQUIRED"), "incomplete-or-cancelled-preparation-keeps-a-visible-recovery-fence");
            var module = new CompassRecoveryModule(service); var fresh = new AgentTool.Context(owner.workspace, owner.actor, context.control(), context.taskId());
            var review = module.preview(fresh, plan.search().id()); module.validatePreview(owner.workspace, owner.actor, context.taskId(), plan.search().id(), review.arguments());
            var reconciled = executeTool(fresh, review.toolName(), review.arguments());
            check(reconciled.success() && module.settled(owner.workspace, owner.actor, plan.search().id()), "positive-revision-bound-preparation-recovery-only-closes-saved-history-without-a-new-search");
        }
        var unchanged = main(() -> ItemSnapshotCodec.capture(owner.player.getMainHandItem(), owner.player.registryAccess()));
        int xpBeforeDuplicate = main(() -> owner.player.experienceLevel);
        var searchBeforeDuplicate = service.operations(owner.workspace, owner.actor).stream().filter(operation -> operation.id().equals(plan.search().id())).findFirst().orElseThrow();
        var preparationBeforeDuplicate = latestPreparation(plan.search().id());
        var duplicate = registry.get(call.name()).execute(context, args);
        var searchAfterDuplicate = service.operations(owner.workspace, owner.actor).stream().filter(operation -> operation.id().equals(plan.search().id())).findFirst().orElseThrow();
        var preparationAfterDuplicate = latestPreparation(plan.search().id());
        int xpAfterDuplicate = main(() -> owner.player.experienceLevel);
        boolean compassUnchanged = unchanged.equals(main(() -> ItemSnapshotCodec.capture(owner.player.getMainHandItem(), owner.player.registryAccess())));
        preparedDiagnostic.addProperty("duplicateSuccess", duplicate.success()); preparedDiagnostic.addProperty("duplicateErrorCode", duplicate.errorCode());
        preparedDiagnostic.addProperty("duplicateSearchRevisionBefore", searchBeforeDuplicate.revision()); preparedDiagnostic.addProperty("duplicateSearchRevisionAfter", searchAfterDuplicate.revision());
        preparedDiagnostic.addProperty("duplicatePreparationRevisionBefore", preparationBeforeDuplicate.revision()); preparedDiagnostic.addProperty("duplicatePreparationRevisionAfter", preparationAfterDuplicate.revision());
        preparedDiagnostic.addProperty("duplicateCandidateCountBefore", preparationBeforeDuplicate.candidates().size()); preparedDiagnostic.addProperty("duplicateCandidateCountAfter", preparationAfterDuplicate.candidates().size());
        preparedDiagnostic.addProperty("duplicateXpBefore", xpBeforeDuplicate); preparedDiagnostic.addProperty("duplicateXpAfter", xpAfterDuplicate);
        preparedDiagnostic.addProperty("duplicateCompassUnchanged", compassUnchanged);
        boolean historicalOrRejected = duplicate.success()
                ? duplicate.observations().has("operationId") && plan.search().id().toString().equals(duplicate.observations().get("operationId").getAsString())
                    && duplicate.observations().has("nativeSaveConfirmed") && duplicate.observations().get("nativeSaveConfirmed").getAsBoolean()
                : Set.of("COMPASS_OPERATION_ALREADY_RECORDED", "COMPASS_PREVIEW_RUNTIME_EXPIRED", "COMPASS_PREPARATION_PREVIEW_EXPIRED").contains(duplicate.errorCode());
        check(historicalOrRejected && searchBeforeDuplicate.equals(searchAfterDuplicate) && preparationBeforeDuplicate.equals(preparationAfterDuplicate)
                && preparationBeforeDuplicate.candidates().equals(preparationAfterDuplicate.candidates())
                && xpBeforeDuplicate == 4 && xpAfterDuplicate == xpBeforeDuplicate && compassUnchanged,
                "duplicate-prepared-operation-never-recharges-or-dispatches-more-candidates");
        check(service.resourcesSettled(owner.workspace, owner.actor), "tiny-native-preparation-and-original-search-obligations-are-both-closed");
        runExistingFullPreparation(fixture);
    }
    private void runExistingFullPreparation(PreparationFixture fixture) throws Exception {
        main(() -> {
            var level = server.overworld(); var candidate = fixture.candidate();
            // Isolated fixture setup only: reuse the same already-approved candidate, materialise it
            // once as a genuine FULL chunk, flush it and release the fixture ticket before production.
            level.getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.FORCED, candidate, 0, candidate); preparationFixtureTickets.add(candidate);
            level.getChunkAt(new net.minecraft.core.BlockPos(candidate.getMiddleBlockX(), -59, candidate.getMiddleBlockZ()));
            level.getChunkSource().save(true);
            level.getChunkSource().removeRegionTicket(net.minecraft.server.level.TicketType.FORCED, candidate, 0, candidate); preparationFixtureTickets.remove(candidate);
            return true;
        });
        var before = main(() -> server.overworld().getChunkSource().chunkMap.read(fixture.candidate())).get(10, TimeUnit.SECONDS).orElseThrow();
        check("minecraft:full".equals(before.getString("Status")), "existing-FULL-preparation-fixture-is-an-actually-saved-native-full-chunk");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && main(() -> server.overworld().getChunkSource().getChunkNow(fixture.candidate().x, fixture.candidate().z)) != null) Thread.sleep(50);
        check(main(() -> server.overworld().getChunkSource().getChunkNow(fixture.candidate().x, fixture.candidate().z)) == null,
                "existing-FULL-candidate-is-unloaded-before-the-new-approved-native-search");
        main(() -> { reset(owner, CompassSearchPlan.Kind.STRUCTURE); return true; });
        var originalPosition = main(owner.player::position);
        var context = context(owner, "Load one existing FULL candidate only to STRUCTURE_STARTS and save its actual wrapped native identity");
        var plan = service.planPrepared(context, "minecraft:village_plains", "MAIN_HAND", new CompassSearchBudget(512, 64, 15000), new CompassPreparationBudget(1, 15000));
        var args = new JsonObject(); args.addProperty("preparationId", plan.id().toString()); args.addProperty("preparationHash", plan.hash());
        var outcome = executeTool(context, "compass_prepared_search_execute", args); diagnosePrepared(plan.search().id(), outcome);
        awaitPublishedSaved(plan.search().id(), context.control());
        var preparation = latestPreparation(plan.search().id());
        check(preparation.candidates().size() == 1 && preparation.candidates().getFirst().nativeReady() && !preparation.candidates().getFirst().saveProof().isEmpty(),
                "existing-FULL-Imposter-has-one-real-wrapped-canonical-save-receipt");
        boolean submitted = service.preparationHistory(owner.workspace, owner.actor).stream().map(event -> event.data())
                .filter(data -> data != null && plan.search().id().toString().equals(data.get("id").getAsString()))
                .map(data -> AiJson.GSON.fromJson(data, CompassPreparationJournal.Operation.class))
                .flatMap(operation -> operation.candidates().stream()).anyMatch(candidate -> candidate.reason().equals("NATIVE_WRAPPED_FULL_CANONICAL_WRITE_SUBMITTED"));
        check(submitted, "actual-Imposter-path-used-native-ChunkMap-save-on-its-wrapped-object");
        if (!service.operationSettled(owner.workspace, owner.actor, plan.search().id())) {
            var module = new CompassRecoveryModule(service); var fresh = new AgentTool.Context(owner.workspace, owner.actor, context.control(), context.taskId());
            var review = module.preview(fresh, plan.search().id());
            check(executeTool(fresh, review.toolName(), review.arguments()).success(), "existing-FULL-saved-history-reconciliation-does-not-regenerate-the-candidate");
        }
        var after = main(() -> server.overworld().getChunkSource().chunkMap.read(fixture.candidate())).get(10, TimeUnit.SECONDS).orElseThrow();
        check("minecraft:full".equals(after.getString("Status")) && before.getList("sections", net.minecraft.nbt.Tag.TAG_COMPOUND).equals(after.getList("sections", net.minecraft.nbt.Tag.TAG_COMPOUND)),
                "existing-FULL-preparation-does-not-change-terrain-or-create-a-lower-status-replacement");
        check(main(() -> owner.player.experienceLevel) == 4 && main(owner.player::position).equals(originalPosition)
                && service.resourcesSettled(owner.workspace, owner.actor), "existing-FULL-preparation-closes-real-obligations-with-one-search-charge-and-no-player-movement");
    }
    private CompassPreparationJournal.Operation latestPreparation(UUID id) throws Exception {
        return service.preparationOperations(owner.workspace, owner.actor).stream().filter(operation -> operation.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("ACTUAL_PREPARATION_JOURNAL_NOT_FOUND"));
    }
    /** Bounded failure evidence without native coordinates, nonces, fingerprints or complete provider/tool payloads. */
    private void diagnosePrepared(UUID id, AgentTool.Result result) {
        var diagnostic = new JsonObject(); diagnostic.addProperty("success", result.success()); diagnostic.addProperty("errorCode", result.errorCode());
        try {
            var search = service.operations(owner.workspace, owner.actor).stream().filter(operation -> operation.id().equals(id)).findFirst().orElseThrow();
            diagnostic.addProperty("searchState", search.state().name()); diagnostic.addProperty("searchReason", search.reason());
            diagnostic.addProperty("nativeEntered", search.entered()); diagnostic.addProperty("terminalKnown", search.terminalKnown()); diagnostic.addProperty("saved", search.saved());
            if (search.result() != null) {
                diagnostic.addProperty("checks", search.result().checks()); diagnostic.addProperty("frames", search.result().frames());
                diagnostic.addProperty("maximumSliceNanos", search.result().maximumSliceNanos()); diagnostic.addProperty("sliceOverruns", search.result().sliceOverruns());
            }
            var preparation = latestPreparation(id); diagnostic.addProperty("preparationCount", preparation.candidates().size());
            diagnostic.addProperty("maximumChunks", preparation.budget().maximumChunks()); diagnostic.addProperty("admissionClosed", preparation.admissionClosed());
            var candidates = new JsonArray();
            for (var candidate : preparation.candidates().stream().limit(8).toList()) {
                var row = new JsonObject(); row.addProperty("state", candidate.state().name()); row.addProperty("reason", candidate.reason());
                row.addProperty("nativeReady", candidate.nativeReady()); row.addProperty("saveConfirmed", !candidate.saveProof().isEmpty()); candidates.add(row);
            }
            diagnostic.add("preparations", candidates);
        } catch (Exception unknown) { diagnostic.addProperty("diagnosticError", unknown.getClass().getSimpleName()); }
        preparedDiagnostic = diagnostic;
    }
    private void awaitPublishedSaved(UUID id, TaskControl control) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            control.check();
            if (service.operations(owner.workspace, owner.actor).stream().anyMatch(op -> op.id().equals(id) && op.saved())) return;
            Thread.sleep(10);
        }
        throw new IllegalStateException("EXPLORATION_FINAL_SAVE_NOT_PUBLISHED");
    }
    private CompassSearchPlan plan(AgentTool.Context context, CompassSearchPlan.Kind kind, String target, int radius) throws Exception {
        return service.plan(context, kind, target, "MAIN_HAND", new CompassSearchBudget(radius, 256, 15000));
    }
    private AgentTool.Context context(Session session, String prompt) throws Exception {
        var task = AgentTask.create(session.actor.owner(), UUID.randomUUID(), prompt); session.workspace.conversation().append(List.of(Draft.checkpoint(task)));
        return new AgentTool.Context(session.workspace, session.actor, new TaskControl(120000), task.id());
    }
    private AgentTool.Result execute(AgentTool.Context context, CompassSearchPlan plan) throws Exception {
        return executeTool(context, "compass_search_execute", arguments(plan));
    }
    private AgentTool.Result executeTool(AgentTool.Context context, String name, JsonObject args) throws Exception {
        AgentTool tool = registry.get(name);
        var call = new ModelMessage.ToolCall(UUID.randomUUID().toString(), tool.descriptor().name(), args.toString());
        var task = context.workspace().conversation().task(context.taskId()).progress(1, 0, 0, 0, List.of(call), 0, ToolPolicyEngine.digest(context.actor().owner(), call), true, true)
                .update(AgentTask.State.EXECUTING, "NATIVE_EXPLORATION_FIXTURE");
        var start = new JsonObject(); start.addProperty("name", call.name()); start.addProperty("risk", tool.descriptor().risk().name()); start.addProperty("inputDigest", task.approvalDigest());
        context.workspace().conversation().append(List.of(Draft.message(task.id(), new ModelMessage("assistant", "", List.of(call), null, null), null),
                new Draft(task.id(), "tool_start", null, start, call.id()), Draft.checkpoint(task)));
        AgentTool.Context current = context;
        for (int phase = 0; phase < 8; phase++) {
            try {
                var result = bridge.execute(tool, current, args, ToolPolicyEngine.digest(context.actor().owner(), call), call).get(20, TimeUnit.SECONDS);
                var evidence = new JsonObject(); evidence.addProperty("name", call.name()); evidence.addProperty("success", result.success());
                evidence.addProperty("errorCode", result.errorCode()); evidence.addProperty("fullResult", AiJson.GSON.toJson(result));
                context.workspace().conversation().append(List.of(new Draft(context.taskId(), "tool_result", ModelMessage.result(call.id(), AiJson.GSON.toJson(result)), evidence, call.id()))); return result;
            } catch (ExecutionException failure) {
                Throwable actual = failure; while (actual.getCause() != null && actual.getCause() != actual) actual = actual.getCause();
                if (!(actual instanceof AgentTool.Deferred deferred)) throw failure;
                if (deferred.reason().equals("NATIVE_COMPASS_SEARCH")) inputWaits++;
                else if (Set.of("WAITING_COMPASS_NATIVE_SAVE", "WAITING_COMPASS_PREPARATION_SAVE").contains(deferred.reason())) {
                    nativeSaveWaits++; save(owner.player, other.player);
                } else throw new IllegalStateException("UNEXPECTED_EXPLORATION_WAIT:" + deferred.reason());
                deferred.readiness().toCompletableFuture().get(20, TimeUnit.SECONDS);
                var original = registry.get(name);
                tool = new AgentTool() {
                    public Descriptor descriptor() { return original.descriptor(); }
                    public Result execute(Context fresh, JsonObject arguments) throws Exception { return deferred.continuation().resume(fresh); }
                };
                current = new AgentTool.Context(context.workspace(), context.actor(), context.control(), context.taskId());
            }
        }
        throw new IllegalStateException("EXPLORATION_DEFERRED_PHASE_LIMIT");
    }
    private JsonObject arguments(CompassSearchPlan plan) { var args = new JsonObject(); args.addProperty("planId", plan.id().toString()); args.addProperty("planHash", plan.hash()); return args; }
    private int damage(Session session, CompassSearchPlan.Kind kind) throws Exception {
        return main(() -> {
            Object component = Class.forName(kind.modClass()).getField(kind == CompassSearchPlan.Kind.BIOME
                    ? "DAMAGE" : "DAMAGE_COMPONENT").get(null);
            @SuppressWarnings("unchecked") var value = (net.minecraft.core.component.DataComponentType<Integer>) component;
            return session.player.getMainHandItem().getOrDefault(value, 0);
        });
    }
    private void reset(Session session, CompassSearchPlan.Kind kind) throws Exception {
        session.player.getInventory().clearContent(); session.player.giveExperienceLevels(6 - session.player.experienceLevel);
        String field = kind == CompassSearchPlan.Kind.BIOME ? "naturesCompass" : "explorersCompass";
        Item item = (Item) Class.forName(kind.modClass()).getField(field).get(null);
        session.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
        session.player.getCooldowns().removeCooldown(item);
    }
    private void configure(CompassSearchPlan.Kind kind, String name, Object value) throws Exception {
        Object config = Class.forName(kind.prefix() + ".config.ConfigHandler").getField("GENERAL").get(null);
        Object property = config.getClass().getField(name).get(config);
        if (!originalConfig.containsKey(property)) originalConfig.put(property, property.getClass().getMethod("get").invoke(property));
        property.getClass().getMethod("set", Object.class).invoke(property, value);
    }
    private void save(ServerPlayer... players) throws Exception {
        if (!forceFixtureSaves) return; // Default waits rely on the production dispatcher, never fixture-accelerated saves.
        fixtureSaveRequests++;
        main(() -> {
            var field = net.minecraft.server.players.PlayerList.class.getDeclaredField("playerIo"); field.setAccessible(true);
            var storage = (PlayerDataStorage) field.get(server.getPlayerList());
            for (var player : players) { storage.save(player); player.getStats().save(); player.getAdvancements().save(); }
            return true;
        });
    }
    private <T> T main(Callable<T> action) throws Exception {
        if (server.isSameThread()) return action.call(); var future = new CompletableFuture<T>();
        server.execute(() -> { try { future.complete(action.call()); } catch (Throwable failure) { future.completeExceptionally(failure); } });
        return future.get(10, TimeUnit.SECONDS);
    }
    private void expect(Callable<?> operation, String code) throws Exception {
        try { operation.call(); } catch (Exception failure) {
            Throwable actual = failure; while (actual.getCause() != null && actual.getCause() != actual) actual = actual.getCause();
            if (code.equals(actual.getMessage())) { passed.add("expected-rejection:" + code); return; } throw failure;
        }
        throw new IllegalStateException("EXPECTED_EXPLORATION_REJECTION:" + code);
    }
    private void check(boolean condition, String name) { if (!condition) throw new IllegalStateException(name); passed.add(name); }
    public JsonObject tick() {
        inventory.tick(); service.tick(); long start = System.nanoTime(); dispatcher.tick();
        maximumDispatchSliceMillis = Math.max(maximumDispatchSliceMillis, (System.nanoTime() - start) / 1_000_000.0);
        if (!result.isDone()) return null; var report = result.join(); report.addProperty("maximumDispatcherSliceMillis", maximumDispatchSliceMillis); return report;
    }
    private final class Session {
        final ServerPlayer player;
        final Actor actor;
        final WorkspaceManager.Workspace workspace;
        Session() throws Exception {
            UUID id = UUID.randomUUID(); var cookie = new CommonListenerCookie(new GameProfile(id, "expl_" + id.toString().substring(0, 8)), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); channels.add(new EmbeddedChannel(connection)); NetworkRegistry.configureMockConnection(connection);
            server.getPlayerList().placeNewPlayer(connection, player, cookie); ValidationHandshake.reply(player); ValidationHandshake.authenticateFixture(player);
            player.setGameMode(GameType.SURVIVAL); player.setNoGravity(true); player.getInventory().clearContent();
            actor = Actor.player(id, true, true, 0); workspace = manager.getWorkspace(actor); service.prepare(workspace);
        }
    }
    @Override public void close() throws Exception {
        cancelOnWork = null; changeConfigOnWork = false; planAdmissionHook = null; moveHandOnWork = false; service.close(); dispatcher.close();
        if (!service.awaitStopped(10000)) throw new IllegalStateException("EXPLORATION_EVIDENCE_SHUTDOWN_TIMEOUT");
        if (!dispatcher.awaitEvidenceStopped(10000)) throw new IllegalStateException("EXPLORATION_SHARED_EVIDENCE_SHUTDOWN_TIMEOUT");
        for (var entry : originalConfig.entrySet()) entry.getKey().getClass().getMethod("set", Object.class).invoke(entry.getKey(), entry.getValue());
        for (var chunk : preparationFixtureTickets) server.overworld().getChunkSource().removeRegionTicket(net.minecraft.server.level.TicketType.FORCED, chunk, 0, chunk);
        io.shutdown(); if (!io.awaitTermination(10000, TimeUnit.MILLISECONDS)) throw new IllegalStateException("EXPLORATION_FIXTURE_IO_SHUTDOWN_TIMEOUT");
        server.getPlayerList().remove(owner.player); server.getPlayerList().remove(other.player); inventory.close();
        channels.forEach(EmbeddedChannel::finishAndReleaseAll); manager.close();
    }
}
