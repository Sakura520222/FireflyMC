package firefly520.fireflymc.validation;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.blueprint.*;
import firefly520.fireflymc.ai.blueprint.ownership.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.model.ModelGateway;
import firefly520.fireflymc.ai.agent.AgentRuntime;
import firefly520.fireflymc.ai.context.ContextCompactor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.*;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Development-only real DedicatedServer scenario; no model calls and no graphical clients. */
public final class ConstructionScenario implements AutoCloseable {
    private final MinecraftServer server;
    private final ServerPlayer owner, other;
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final Actor actor;
    private final WorkspaceManager manager;
    private final WorkspaceManager.Workspace workspace;
    private final MaterialLedger ledger;
    private final MaterialInventoryService inventory;
    private final VirtualProcessingService processing;
    private final BlueprintService blueprints;
    private final MaterialSubstitutionService substitution;
    private final AtomicLong generation = new AtomicLong(1);
    private volatile ConstructionService construction;
    private final CompletableFuture<JsonObject> result;
    private final List<Double> samples = new ArrayList<>();
    private final List<String> passed = new ArrayList<>();
    private final BlockPos origin;
    private final Set<net.minecraft.world.level.ChunkPos> fixtureChunks = new HashSet<>();
    private volatile AgentTool.Context currentSlice;
    public ConstructionScenario(MinecraftServer server) throws Exception {
        this.server = server;
        if (!server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_TEST_REQUIRED");
        Path counter = Path.of(".").toAbsolutePath().normalize().resolve("construction-next-region.json");
        if (Files.isSymbolicLink(counter)) throw new IllegalStateException("UNSAFE_VALIDATION_COUNTER");
        int region = Files.exists(counter) ? JsonParser.parseString(Files.readString(counter)).getAsJsonObject().get("nextRegion").getAsInt() : 0;
        if (region < 0 || region > 30000) throw new IllegalStateException("VALIDATION_REGION_LIMIT");
        var next = new JsonObject(); next.addProperty("nextRegion", region + 1); AiJson.write(counter, next);
        // Keep previous failed worlds/receipts intact; each invocation uses a fresh bounded fixture area.
        origin = new BlockPos(8 + (region + 1) * 96, 72, 8);
        owner = player("builder"); other = player("observer"); actor = Actor.player(owner.getUUID(), true, true, 0);
        Path root = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "construction-case-");
        manager = new WorkspaceManager(root); workspace = manager.getWorkspace(actor); ledger = new MaterialLedger(root);
        inventory = new MaterialInventoryService(server, p -> (p == owner || p == other) && firefly520.fireflymc.ai.AiServerService.authenticated(p), new InstalledPermissionAdapters(server));
        inventory.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis));
        inventory.bindPreferences(actor.owner(), new MaterialPreferences(workspace));
        processing = new VirtualProcessingService(server, inventory, ledger, generation::get);
        blueprints = new BlueprintService(server, p -> (p == owner || p == other) && firefly520.fireflymc.ai.AiServerService.authenticated(p), new InstalledPermissionAdapters(server));
        substitution = new MaterialSubstitutionService(blueprints, inventory, ledger, generation::get);
        owner.moveTo(origin.getX() + 64.5, origin.getY(), origin.getZ() + .5, 30, 0);
        other.moveTo(origin.getX() + 40.5, origin.getY(), origin.getZ() + .5, 0, 0);
        owner.setNoGravity(true); other.setNoGravity(true);
        for (int x = (origin.getX() - 2) >> 4; x <= (origin.getX() + 32) >> 4; x++)
            for (int z = (origin.getZ() - 2) >> 4; z <= (origin.getZ() + 4) >> 4; z++) {
                var chunk = new net.minecraft.world.level.ChunkPos(x, z);
                fixtureChunks.add(chunk); owner.serverLevel().setChunkForced(x, z, true); owner.serverLevel().getChunk(x, z);
            }
        for (int x = -2; x < 32; x++) for (int z = -2; z < 4; z++) for (int y = -1; y < 4; y++)
            owner.serverLevel().setBlock(origin.offset(x, y, z), y == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
        construction = create(false);
        result = CompletableFuture.supplyAsync(() -> {
            JsonObject report = new JsonObject(); long started = System.nanoTime();
            try { run(); report.addProperty("status", "PASSED"); }
            catch (Throwable failure) { failure.printStackTrace(); report.addProperty("status", "FAILED"); report.addProperty("error", failure.toString()); }
            report.addProperty("durationMillis", (System.nanoTime() - started) / 1_000_000);
            report.add("passedChecks", AiJson.GSON.toJsonTree(passed));
            report.addProperty("forceFixtureSaves", Boolean.getBoolean("fireflymc.validation.forceFixtureSaves"));
            report.addProperty("nativeSaveMode", Boolean.getBoolean("fireflymc.validation.forceFixtureSaves")
                    ? "EXPLICIT_FIXTURE_SAVE_OVERRIDE" : "PRODUCTION_NATIVE_SAVE_PUMP");
            report.addProperty("scope", "Real DedicatedServer, two mock network players, production inventory/construction services and native events; no graphical UI, real credentials, or model request.");
            return report;
        }, runnable -> Thread.ofPlatform().daemon(true).name("construction-validation").start(runnable));
    }
    private ConstructionService create(boolean allowed) {
        return new ConstructionService(server, inventory, ledger, processing, new InstalledPermissionAdapters(server), generation::get, allowed);
    }
    private void run() throws Exception {
        if (Boolean.getBoolean("fireflymc.validation.constructionOwnershipOnly")) { ownershipOnly(); return; }
        if (Boolean.getBoolean("fireflymc.validation.constructionClosureOnly")) { waterPlacement(); functionalReadiness(); return; }
        var first = context("remote two-block construction"); var blueprint = blueprint(first, 0, 2);
        var denied = call("build_plan", first, blueprint);
        require(!denied.success() && denied.errorCode().equals("VIRTUAL_CONSTRUCTION_PERMISSION_REQUIRED"), "dedicated-proxy-permission-required", denied);
        main(() -> { owner.moveTo(origin.getX() + .5, origin.getY(), origin.getZ() + 3.5, 0, 0); owner.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2)); return true; });
        var nearby = planned(first, blueprint);
        require(nearby.placements().size() == 2, "ordinary-reach-planning-does-not-require-remote-privilege", null);
        main(() -> { owner.moveTo(origin.getX() + 64.5, origin.getY(), origin.getZ() + .5, 30, 0); return true; });
        var moved = call("build_execute", first, args(nearby));
        require(!moved.success() && moved.errorCode().equals("VIRTUAL_CONSTRUCTION_PERMISSION_REQUIRED") && ledger.find(actor, nearby.id()).isEmpty(),
                "moving-out-of-native-reach-rechecks-remote-permission-before-deduction", moved);
        main(() -> { construction.close(); construction = create(true); owner.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2)); return true; });
        var plan = planned(first, blueprint); var execute = args(plan);
        var otherActor = Actor.player(other.getUUID(), true, true, 0); var otherWorkspace = manager.getWorkspace(otherActor);
        try { construction.load(otherWorkspace, otherActor, plan.id(), plan.hash()); throw new IllegalStateException("OTHER_OWNER_PLAN_VISIBLE"); }
        catch (IllegalArgumentException expected) { passed.add("two-owner-private-plan-isolation"); }
        ToolRegistry registry = registry(); var call = new ModelMessage.ToolCall("approved-build", "build_execute", execute.toString());
        var policy = new ToolPolicyEngine();
        require(policy.check(actor, actor.owner(), registry.get("build_execute").descriptor(), true, call, "").decision() == ToolPolicyEngine.Decision.APPROVAL,
                "world-change-needs-exact-approval", null);
        require(policy.check(actor, actor.owner(), registry.get("build_execute").descriptor(), true, call, ToolPolicyEngine.digest(actor.owner(), call)).decision() == ToolPolicyEngine.Decision.ALLOW,
                "approval-binds-tool-call", null);
        var beforePosition = main(owner::position); var beforeAdvancement = main(owner::getAdvancements);
        String review = construction.review(workspace, actor, plan);
        require(review.contains("原生放置次数：2") && main(() -> owner.getInventory().getItem(0).getCount()) == 2, "review-has-real-material-and-no-side-effect", null);
        var placed = call("build_execute", first, execute);
        require(placed.success(), "two-native-placements-and-ledger-completed", placed);
        require(main(() -> owner.position().equals(beforePosition) && owner.getAdvancements() == beforeAdvancement
                        && owner.getInventory().getItem(0).isEmpty() && owner.serverLevel().getBlockState(origin).is(Blocks.OAK_PLANKS)
                        && owner.serverLevel().getBlockState(origin.east()).is(Blocks.OAK_PLANKS)), "remote-construction-does-not-move-owner", placed);
        require(ledger.get(actor, plan.id()).worldDebits().stream().mapToInt(MaterialLedger.WorldDebit::count).sum() == 2, "actual-world-material-conservation", null);
        require(ledger.get(actor, plan.id()).saved(), "successful-build-return-requires-native-input-world-and-callback-save", null);
        optionalFixtureSave();
        long saveDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!ledger.get(actor, plan.id()).saved() && System.nanoTime() < saveDeadline) Thread.sleep(20);
        require(ledger.get(actor, plan.id()).saved(), "native-world-player-stat-and-advancement-writes-confirm-construction", ledger.get(actor, plan.id()));
        var repeated = call("build_execute", first, execute);
        require(!repeated.success() && repeated.errorCode().equals("BUILD_ALREADY_RECORDED"), "duplicate-operation-does-not-replay", repeated);
        poiConstruction();

        main(() -> { owner.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3)); return true; });
        var changedContext = context("detect external edit and restoration"); var changed = planned(changedContext, blueprint(changedContext, 3, 1));
        main(() -> { owner.serverLevel().setBlock(origin.offset(3, 0, 0), Blocks.STONE.defaultBlockState(), 3); owner.serverLevel().setBlock(origin.offset(3, 0, 0), Blocks.AIR.defaultBlockState(), 3); return true; });
        var refused = call("build_execute", changedContext, args(changed));
        require(!refused.success() && refused.errorCode().equals("BUILD_WORLD_CHANGED_REVIEW_REQUIRED") && ledger.find(actor, changed.id()).isEmpty(), "edit-then-restore-invalidates-approval-before-deduction", refused);

        var reloadContext = context("reload invalidation"); var reload = planned(reloadContext, blueprint(reloadContext, 4, 1)); generation.incrementAndGet();
        var reloadResult = call("build_execute", reloadContext, args(reload));
        require(!reloadResult.success() && reloadResult.errorCode().equals("BUILD_REGISTRY_RELOADED") && ledger.find(actor, reload.id()).isEmpty(), "reload-requires-new-plan", reloadResult);

        // Invalid previews are released by explicit blueprint edits or expiration; use a new service epoch for remaining independent cases.
        main(() -> { construction.close(); construction = create(true); return true; });
        var cancelledContext = context("pause after one accepted native placement"); var cancelled = planned(cancelledContext, blueprint(cancelledContext, 5, 3));
        Consumer<BlockEvent.EntityPlaceEvent> stop = event -> { if (event.getEntity() != null && event.getEntity().getUUID().equals(owner.getUUID())) activeControl(cancelledContext).stop("USER_PAUSED"); };
        main(() -> { NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, stop); return true; });
        try { var stopped = call("build_execute", cancelledContext, args(cancelled));
            require(!stopped.success() && stopped.errorCode().equals("PROCESSING_PAUSED"), "cooperative-pause-returns-durable-native-receipt", stopped); }
        finally { main(() -> { NeoForge.EVENT_BUS.unregister(stop); return true; }); }
        var partial = ledger.get(actor, cancelled.id());
        require(!partial.uncertain() && partial.worldDebits().stream().mapToInt(MaterialLedger.WorldDebit::count).sum() == 1
                        && partial.escrow().stream().mapToInt(MaterialStack::count).sum() == 2, "accepted-placement-recorded-after-pause-and-only-unused-material-held", null);
        var remaining = ledger.cancel(actor, cancelled.id());
        remaining = processing.deliver(new AgentTool.Context(workspace, actor, new TaskControl(60000), cancelledContext.taskId()), remaining);
        require(remaining.settled() && main(() -> owner.getInventory().countItem(Items.OAK_PLANKS)) == 2, "pause-return-does-not-recreate-consumed-block", null);
        main(() -> { owner.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3)); return true; });
        var resumeContext = context("resume only the verified remaining prefix"); var resumed = planned(resumeContext, blueprint(resumeContext, 8, 3));
        Consumer<BlockEvent.EntityPlaceEvent> pauseOnce = event -> { if (event.getEntity() != null && event.getEntity().getUUID().equals(owner.getUUID())) activeControl(resumeContext).stop("USER_PAUSED"); };
        main(() -> { NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, pauseOnce); return true; });
        try { var stopped = call("build_execute", resumeContext, args(resumed));
            require(!stopped.success() && stopped.errorCode().equals("PROCESSING_PAUSED"), "resume-fixture-paused-after-native-result", stopped); }
        finally { main(() -> { NeoForge.EVENT_BUS.unregister(pauseOnce); return true; }); }
        var continued = call("build_execute", new AgentTool.Context(workspace, actor, new TaskControl(60000), resumeContext.taskId()), args(resumed));
        require(continued.success() && ledger.get(actor, resumed.id()).worldDebits().size() == 3,
                "resume-skips-verified-prefix-and-finishes-only-unplaced-steps", continued);
        require(main(() -> owner.getInventory().countItem(Items.OAK_PLANKS)) == 0, "resume-neither-extracts-again-nor-duplicates-output", null);

        main(() -> { owner.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS)); return true; });
        var protectedContext = context("native protection denial"); var protectedPlan = planned(protectedContext, blueprint(protectedContext, 11, 1));
        Consumer<BlockEvent.EntityPlaceEvent> protection = event -> { if (event.getEntity() != null && event.getEntity().getUUID().equals(owner.getUUID())) event.setCanceled(true); };
        main(() -> { NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, false, protection); return true; });
        AgentTool.Result protectedResult;
        try { protectedResult = call("build_execute", protectedContext, args(protectedPlan)); }
        finally { main(() -> { NeoForge.EVENT_BUS.unregister(protection); return true; }); }
        var blocked = ledger.get(actor, protectedPlan.id());
        require(!protectedResult.success() && !blocked.uncertain() && blocked.worldDebits().stream().mapToInt(MaterialLedger.WorldDebit::count).sum() == 0
                        && main(() -> owner.serverLevel().getBlockState(origin.offset(11, 0, 0)).isAir()), "native-protection-denial-keeps-world-and-material", protectedResult);
        substitution();
        runtimePauseResume();
        regionSelection();
        demolition();
        safeUndo();
        swallowedOwnerCallbackViolation();
    }
    /** Small native hook acceptance path; the full construction matrix remains independently runnable. */
    private void waterPlacement() throws Exception {
        main(() -> { construction.close(); construction = create(true); owner.getInventory().clearContent(); return true; });
        var source = origin.offset(18, 0, 0);
        main(() -> { waterWell(source, true); owner.getInventory().setItem(0, new ItemStack(Items.OAK_SLAB)); return true; });
        var ctx = context("preserve a real water source through paid native slab placement");
        var plan = planned(ctx, wetBlueprint(ctx, source));
        require(plan.needs().stream().mapToInt(MaterialLedger.Need::count).sum() == 1
                        && construction.review(workspace, actor, plan).contains("原位源水保留：1"),
                "waterlogged-plan-charges-one-slab-and-binds-existing-source", null);
        var result = call("build_execute", ctx, args(plan));
        require(result.success() && main(() -> owner.getInventory().getItem(0).isEmpty()
                        && owner.serverLevel().getBlockState(source).is(Blocks.OAK_SLAB)
                        && owner.serverLevel().getBlockState(source).getValue(net.minecraft.world.level.block.SlabBlock.WATERLOGGED)
                        && owner.serverLevel().getFluidState(source).isSource()),
                "waterlogged-native-placement-consumes-real-slab-preserves-real-source", result);
        require(ledger.get(actor, plan.id()).worldDebits().stream().mapToInt(MaterialLedger.WorldDebit::count).sum() == 1,
                "waterlogged-material-ledger-never-invents-a-water-bucket", null);
        optionalFixtureSave();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!ledger.get(actor, plan.id()).saved() && System.nanoTime() < deadline) Thread.sleep(20);
        require(ledger.get(actor, plan.id()).saved(), "waterlogged-state-and-native-fluid-ticks-have-real-canonical-save", null);

        var dry = origin.offset(22, 0, 0);
        main(() -> { waterWell(dry, false); owner.getInventory().setItem(0, new ItemStack(Items.OAK_SLAB)); return true; });
        var dryCtx = context("missing source water cannot be synthesized from a slab");
        var missing = call("build_plan", dryCtx, wetBlueprint(dryCtx, dry));
        require(!missing.success() && "BUILD_EXISTING_SOURCE_WATER_REQUIRED".equals(missing.errorCode())
                        && main(() -> owner.getInventory().getItem(0).getCount() == 1 && owner.serverLevel().getBlockState(dry).isAir()),
                "waterlogged-target-without-real-source-refuses-before-taking-material", missing);

        var cancelled = origin.offset(26, 0, 0);
        main(() -> { waterWell(cancelled, true); owner.getInventory().setItem(0, new ItemStack(Items.OAK_SLAB)); return true; });
        var deniedCtx = context("native protection cancellation preserves both water and real slab custody");
        var deniedPlan = planned(deniedCtx, wetBlueprint(deniedCtx, cancelled));
        java.util.function.Consumer<net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent> deny = event -> {
            if (event.getEntity() != null && event.getEntity().getUUID().equals(owner.getUUID())
                    && event.getPos().equals(cancelled)) event.setCanceled(true);
        };
        main(() -> { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGH, false, deny); return true; });
        AgentTool.Result rejected;
        try { rejected = call("build_execute", deniedCtx, args(deniedPlan)); }
        finally { main(() -> { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(deny); return true; }); }
        var custody = ledger.get(actor, deniedPlan.id());
        var rejectedStep = new BuildJournal(workspace, deniedPlan.executionEpoch()).list(actor).stream()
                .filter(step -> step.planId().equals(deniedPlan.id())).findFirst().orElseThrow();
        require(!rejected.success() && !custody.uncertain() && custody.worldDebits().size() == 1
                        && custody.worldDebits().getFirst().count() == 0
                        && custody.escrow().stream().mapToInt(MaterialStack::count).sum() == 1
                        && rejectedStep.state() == BuildJournal.State.REJECTED && rejectedStep.observation() != null
                        && rejectedStep.observation().mutationEntered() && !rejectedStep.observation().uncertain()
                        && rejectedStep.observation().remaining().count() == 1
                        && main(() -> owner.serverLevel().getBlockState(cancelled).is(Blocks.WATER)
                        && owner.serverLevel().getFluidState(cancelled).isSource()),
                "waterlogged-native-event-cancellation-retains-source-and-real-escrow", rejected);
        // Cancellation still invoked native callbacks. Its zero-consumption receipt must remain
        // recorded and be persisted by the production pump, rather than being mistaken for no call.
        inventory.nativeSaves().awaitJournalled(deniedPlan.id()).toCompletableFuture().get(8, TimeUnit.SECONDS);
        require(ledger.get(actor, deniedPlan.id()).saved(), "cancelled-water-placement-keeps-real-zero-debit-and-canonical-save-proof", null);
    }
    private void waterWell(BlockPos position, boolean water) {
        for (var direction : Direction.values()) owner.serverLevel().setBlockAndUpdate(position.relative(direction), Blocks.STONE.defaultBlockState());
        owner.serverLevel().setBlockAndUpdate(position, (water ? Blocks.WATER : Blocks.AIR).defaultBlockState());
    }
    private JsonObject wetBlueprint(AgentTool.Context ctx, BlockPos position) throws Exception {
        var document = new BlueprintDocument(1, "real source water placement", "test-fixture", "1", "", position(position),
                List.of(new BlueprintDocument.Block(new BlueprintDocument.Position(0, 0, 0),
                        new BlueprintDocument.State("minecraft:oak_slab", Map.of("type", "bottom", "waterlogged", "true")))));
        var entry = new BlueprintRepository(workspace).save(actor, UUID.randomUUID(), null, 0, "", ctx.taskId(), "minecraft:overworld", document).entry();
        var args = new JsonObject(); args.addProperty("blueprintId", entry.id().toString()); args.addProperty("revision", entry.revision());
        args.addProperty("blueprintHash", entry.hash()); return args;
    }
    private void functionalReadiness() throws Exception {
        var p = origin.offset(30, 0, 0);
        main(() -> { owner.getInventory().clearContent(); owner.getInventory().setItem(0, new ItemStack(Items.FURNACE)); return true; });
        require(main(() -> {
            var empty = new ItemStack(Items.FURNACE);
            if (!net.minecraft.world.item.component.ItemContainerContents.EMPTY.equals(empty.get(net.minecraft.core.component.DataComponents.CONTAINER))) return false;
            NativePlacementComponents.require(empty);
            var contents = empty.copy(); contents.set(net.minecraft.core.component.DataComponents.CONTAINER,
                    net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 3))));
            var arbitrary = empty.copy(); var nbt = new net.minecraft.nbt.CompoundTag(); nbt.putString("id", "minecraft:furnace"); nbt.putInt("BurnTime", 999);
            arbitrary.set(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA, net.minecraft.world.item.component.CustomData.of(nbt));
            for (var unsafe : List.of(contents, arbitrary)) {
                try { NativePlacementComponents.require(unsafe); return false; }
                catch (IllegalArgumentException expected) { if (!expected.getMessage().equals("PLACEMENT_COMPONENT_ADAPTER_REQUIRED")) throw expected; }
            }
            return owner.serverLevel().getBlockState(p).isAir() && owner.getInventory().getItem(0).getCount() == 1;
        }), "native-empty-furnace-default-container-is-allowed-but-nbt-and-stored-items-are-not", null);
        var ctx = context("build a real furnace without claiming native fuel is waived");
        var plan = planned(ctx, blueprint(ctx, 30, 1, "minecraft:furnace"));
        var furnaceTarget = plan.initialWorld().stream().filter(cell -> cell.position().equals(position(p))).findFirst().orElseThrow().target();
        require(furnaceTarget.properties().equals(Map.of("facing", "north", "lit", "false"))
                        && plan.placements().getFirst().footprint().stream().anyMatch(cell -> furnaceTarget.equals(cell.target())),
                "furnace-omitted-blueprint-properties-expand-to-one-exact-native-plan-state", furnaceTarget);
        var built = call("build_execute", ctx, args(plan));
        require(!built.success() && "STRUCTURE_COMPLETE_FUNCTION_PENDING".equals(built.errorCode())
                        && built.observations().get("structureState").getAsString().equals("STRUCTURE_COMPLETE")
                        && ledger.get(actor, plan.id()).state() == MaterialLedger.State.COMPLETED
                        && main(() -> owner.getInventory().getItem(0).isEmpty() && owner.serverLevel().getBlockState(p).is(Blocks.FURNACE)),
                "real-furnace-structure-completes-but-function-stays-pending-without-input-and-fuel", built);
        var first = built.observations().getAsJsonObject("verification").getAsJsonArray("functions").get(0).getAsJsonObject();
        var reasons = first.getAsJsonArray("reasons").asList().stream().map(JsonElement::getAsString).toList();
        require(reasons.contains("NATIVE_INPUT_REQUIRED") && reasons.contains("NATIVE_FUEL_REQUIRED")
                        && first.getAsJsonObject("observed").get("nativeFuelBurnTicks").getAsInt() == 0,
                "furnace-functional-check-uses-real-native-canBurn-and-fuel-contract", first);
        require(workspace.conversation().getSnapshot(0).events().stream().anyMatch(event -> event.kind().equals("build_verification")
                        && plan.id().toString().equals(event.data().get("planId").getAsString())),
                "functional-verification-is-a-private-permanent-versioned-fact", null);

        var nativeObject = main(() -> (net.minecraft.world.level.block.entity.FurnaceBlockEntity) owner.serverLevel().getBlockEntity(p));
        main(() -> { nativeObject.setItem(0, new ItemStack(Items.IRON_ORE, 2)); nativeObject.setItem(1, new ItemStack(Items.COAL, 2)); return true; });
        long runningDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!main(() -> owner.serverLevel().getBlockState(p).getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT))
                && System.nanoTime() < runningDeadline) Thread.sleep(20);
        var before = main(() -> List.of(ItemSnapshotCodec.capture(nativeObject.getItem(0), owner.registryAccess()),
                ItemSnapshotCodec.capture(nativeObject.getItem(1), owner.registryAccess()), ItemSnapshotCodec.capture(nativeObject.getItem(2), owner.registryAccess())));
        var checked = call("build_verify", ctx, args(plan));
        var after = main(() -> List.of(ItemSnapshotCodec.capture(nativeObject.getItem(0), owner.registryAccess()),
                ItemSnapshotCodec.capture(nativeObject.getItem(1), owner.registryAccess()), ItemSnapshotCodec.capture(nativeObject.getItem(2), owner.registryAccess())));
        require(checked.success() && checked.observations().get("structureState").getAsString().equals("STRUCTURE_COMPLETE")
                        && checked.observations().get("functionality").getAsString().equals("READY") && before.equals(after),
                "real-furnace-can-run-after-native-costs-are-met-query-does-not-process-or-spend", checked);
        require(checked.observations().getAsJsonObject("verification").getAsJsonArray("functions").get(0).getAsJsonObject()
                        .getAsJsonObject("observed").get("costPolicy").getAsString().equals("NATIVE_WORLD_MACHINE_COSTS"),
                "world-machine-costs-remain-separate-from-virtual-materials-only-processing", checked);
        main(() -> {
            owner.serverLevel().removeBlockEntity(p);
            var replacement = new net.minecraft.world.level.block.entity.FurnaceBlockEntity(p, owner.serverLevel().getBlockState(p));
            owner.serverLevel().setBlockEntity(replacement);
            replacement.setItem(0, new ItemStack(Items.DIAMOND, 3)); return true;
        });
        var replaced = call("build_verify", ctx, args(plan));
        var finding = replaced.observations().getAsJsonObject("verification").getAsJsonArray("functions").get(0).getAsJsonObject();
        require(!replaced.success() && finding.get("state").getAsString().equals("UNVERIFIED")
                        && !finding.getAsJsonObject("observed").has("hasInput") && !finding.toString().contains("diamond"),
                "same-blockstate-replacement-native-object-never-inherits-private-functional-read-authority", replaced);
    }
    private void ownershipOnly() throws Exception {
        main(() -> {
            construction.close(); construction = create(true); owner.getInventory().clearContent();
            owner.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3)); return true;
        });
        var ctx = context("native ownership metadata and palette observation");
        var plan = planned(ctx, blueprint(ctx, 0, 2));
        var built = call("build_execute", ctx, args(plan));
        require(built.success(), "ownership-native-two-placements-spend-real-materials", built);
        var metadata = workspace.conversation().getSnapshot(0).events().stream().filter(e -> e.kind().equals("build_ownership"))
                .map(e -> AiJson.GSON.fromJson(e.data(), BuildOwnershipEvent.class)).filter(e -> e.sourcePlanId().equals(plan.id())).toList();
        var tokens = metadata.stream().flatMap(e -> e.tokens().stream()).toList();
        require(metadata.size() == 2 && metadata.stream().allMatch(e -> e.reason().isEmpty()) && tokens.size() == 2,
                "ownership-real-native-transitions-produce-two-private-causal-receipts", metadata);
        require(main(() -> { for (var token : tokens) NativeOwnership.require(owner.serverLevel(), token); return true; }),
                "ownership-current-native-object-accepts-exact-tokens", null);
        // Read the actual canonical payload only after production's final-save gate. An explicit
        // fixture override is available for separate save tests, but ordinary runs do not force it.
        optionalFixtureSave();
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
        var chunkPos = new net.minecraft.world.level.ChunkPos(origin);
        var payload = main(() -> owner.serverLevel().getChunkSource().chunkMap.read(chunkPos)).get(10, TimeUnit.SECONDS).orElseThrow();
        var canonical = OwnershipNbt.readCanonical(payload, chunkPos.x, chunkPos.z, plan.dimension());
        require(canonical.claims().containsAll(tokens), "ownership-marker-is-in-actual-native-chunk-disk-payload", canonical);
        require(main(() -> {
            var restored = net.minecraft.world.level.chunk.storage.ChunkSerializer.read(owner.serverLevel(), owner.serverLevel().getPoiManager(),
                    new net.minecraft.world.level.chunk.storage.RegionStorageInfo("ownership-fixture", owner.serverLevel().dimension(), "chunk"), chunkPos, payload.copy());
            var chunk = ((net.minecraft.world.level.chunk.ImposterProtoChunk) restored).getWrapped();
            var state = ((OwnershipAccess.Chunk) chunk).fireflymc$ownership();
            for (var token : tokens) state.require(token, token.runtimeSession(), NativeOwnership.describe(chunk.getBlockState(BlockPos.of(token.position()))));
            return true;
        }), "ownership-native-canonical-deserialization-retains-live-runtime-unrevoked-claims", null);

        // An independently paid native build must still be reversibly mined through the production service.
        var inverseCtx = context("native ownership protected inverse"); var inverse = planned(inverseCtx, blueprint(inverseCtx, 3, 1));
        require(call("build_execute", inverseCtx, args(inverse)).success(), "ownership-inverse-fixture-has-real-paid-placement", null);
        main(() -> { owner.getInventory().setItem(0, new ItemStack(Items.IRON_AXE)); return true; });
        var undoCtx = context("undo the independently paid native placement"); var preview = call("build_undo_plan", undoCtx, args(inverse));
        require(preview.success(), "ownership-production-undo-preview-requires-live-causal-right", preview);
        var undo = new JsonObject(); undo.add("planId", preview.observations().get("planId")); undo.add("planHash", preview.observations().get("planHash"));
        var undone = call("build_undo_execute", undoCtx, undo);
        require(undone.success() && main(() -> owner.serverLevel().getBlockState(origin.offset(3, 0, 0)).isAir()
                && owner.getInventory().countItem(Items.OAK_PLANKS) == 1 && owner.getMainHandItem().getDamageValue() == 1),
                "ownership-approved-inverse-uses-native-mining-real-wear-and-real-drop", undone);

        var first = tokens.stream().filter(t -> t.position() == origin.asLong()).findFirst().orElseThrow();
        var second = tokens.stream().filter(t -> t.position() == origin.east().asLong()).findFirst().orElseThrow();
        require(main(() -> {
            var chunk = owner.serverLevel().getChunkAt(origin); var states = chunk.getSection(owner.serverLevel().getSectionIndex(origin.getY())).getStates();
            states.set(origin.getX() & 15, origin.getY() & 15, origin.getZ() & 15, Blocks.STONE.defaultBlockState());
            states.set(origin.getX() & 15, origin.getY() & 15, origin.getZ() & 15, Blocks.OAK_PLANKS.defaultBlockState());
            try { NativeOwnership.require(owner.serverLevel(), first); return false; }
            catch (IllegalStateException rejected) {
                NativeOwnership.require(owner.serverLevel(), second);
                return rejected.getMessage().equals("OWNERSHIP_NONCE_OR_GENERATION_CHANGED");
            }
        }), "ownership-direct-native-palette-aba-revokes-only-edited-cell", null);
        var escape = main(() -> {
            var chunk = owner.serverLevel().getChunkAt(origin); var states = chunk.getSection(owner.serverLevel().getSectionIndex(origin.getY())).getStates();
            var field = net.minecraft.world.level.chunk.PalettedContainer.class.getDeclaredField("data"); field.setAccessible(true);
            var storage = ((OwnershipAccess.PaletteData) field.get(states)).fireflymc$ownershipBits();
            var detail = new JsonObject(); detail.addProperty("storageClass", storage.getClass().getName());
            detail.addProperty("storageBound", storage instanceof OwnershipAccess.Storage bound && bound.fireflymc$ownershipStorage() != null);
            detail.addProperty("arrayLength", storage.getRaw().length); // Unknown mutable-reference escape must revoke authority.
            var snapshot = ((OwnershipAccess.Chunk) chunk).fireflymc$ownership().snapshot();
            detail.addProperty("unsafe", snapshot.unsafe()); detail.addProperty("reason", snapshot.reason());
            try { NativeOwnership.require(owner.serverLevel(), second); detail.addProperty("rejected", false); }
            catch (IllegalStateException rejected) { detail.addProperty("rejected", true); detail.addProperty("rejectionCode", rejected.getMessage()); }
            return detail;
        });
        // The negative nonce index is intentionally checked before the current object's reason.
        require(escape.get("storageBound").getAsBoolean() && escape.get("arrayLength").getAsInt() > 0
                        && escape.get("unsafe").getAsBoolean() && escape.get("reason").getAsString().equals("OWNERSHIP_MUTABLE_REFERENCE_ESCAPED")
                        && escape.get("rejected").getAsBoolean()
                        && Set.of("OWNERSHIP_NONCE_OR_GENERATION_CHANGED", "OWNERSHIP_MUTABLE_REFERENCE_ESCAPED").contains(escape.get("rejectionCode").getAsString()),
                "ownership-native-raw-bit-array-escape-revokes-chunk-without-changing-gameplay", escape);
        require(main(() -> {
            construction.close(); construction = create(true);
            try { NativeOwnership.require(owner.serverLevel(), second); return false; }
            catch (IllegalStateException rejected) { return rejected.getMessage().equals("OWNERSHIP_RESTART_CONTINUITY_UNPROVEN"); }
        }), "ownership-new-service-epoch-cannot-reuse-old-token", null);
    }
    private void safeUndo() throws Exception {
        main(() -> { owner.getInventory().clearContent(); owner.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2)); return true; });
        var buildCtx = context("build a structure with a live provenance witness for undo"); var built = planned(buildCtx, blueprint(buildCtx, 19, 2));
        require(call("build_execute", buildCtx, args(built)).success(), "undo-fixture-has-real-native-placement-and-material-debits", null);
        main(() -> { owner.getInventory().setItem(0, new ItemStack(Items.IRON_AXE)); return true; });
        var undoCtx = context("safely undo only this original construction"); var preview = call("build_undo_plan", undoCtx, args(built));
        require(preview.success(), "undo-plan-binds-original-verified-live-construction", preview);
        var args = new JsonObject(); args.add("planId", preview.observations().get("planId")); args.add("planHash", preview.observations().get("planHash"));
        var removal = construction.removal().load(workspace, actor, args);
        require(removal.sourcePlanId().equals(built.id()) && removal.steps().stream().mapToInt(s -> s.sourceSteps().size()).sum() == 2,
                "undo-plan-signs-every-source-operation", removal);
        var result = call("build_undo_execute", undoCtx, args);
        require(result.success() && main(() -> owner.serverLevel().getBlockState(origin.offset(19, 0, 0)).isAir()
                && owner.serverLevel().getBlockState(origin.offset(20, 0, 0)).isAir() && owner.getInventory().countItem(Items.OAK_PLANKS) == 2
                && owner.getMainHandItem().getDamageValue() == 2), "safe-undo-uses-native-mining-and-actual-drops", result);
        var records = workspace.conversation().getSnapshot(0).events().stream().filter(e -> e.kind().equals("build_step"))
                .map(e -> AiJson.GSON.fromJson(e.data(), BuildJournal.Step.class)).filter(s -> s.planId().equals(built.id()) && s.state() == BuildJournal.State.UNDONE).toList();
        require(records.size() == 2 && ledger.get(actor, built.id()).worldDebits().stream().mapToInt(MaterialLedger.WorldDebit::count).sum() == 2,
                "undo-links-original-journal-without-erasing-or-refunding-material-debits", records);
        require(!call("build_undo_execute", undoCtx, args).success() && main(() -> owner.getInventory().countItem(Items.OAK_PLANKS)) == 2,
                "duplicate-undo-never-refunds-original-material-again", null);
        main(() -> { owner.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS)); return true; });
        var changedCtx = context("reject undo after another edit even when the block was restored"); var changed = planned(changedCtx, blueprint(changedCtx, 23, 1));
        require(call("build_execute", changedCtx, args(changed)).success(), "undo-edited-source-fixture-built", null);
        main(() -> { var position = origin.offset(23, 0, 0); owner.serverLevel().setBlockAndUpdate(position, Blocks.STONE.defaultBlockState());
            owner.serverLevel().setBlockAndUpdate(position, Blocks.OAK_PLANKS.defaultBlockState()); owner.getInventory().setItem(0, new ItemStack(Items.IRON_AXE)); return true; });
        var refused = call("build_undo_plan", context("undo modified source"), args(changed));
        require(!refused.success() && main(() -> owner.serverLevel().getBlockState(origin.offset(23, 0, 0)).is(Blocks.OAK_PLANKS)),
                "edit-then-restore-is-not-proof-of-undo-ownership", refused);
        undoMultiBlock(false);
        undoMultiBlock(true);
        if (net.neoforged.fml.ModList.get().isLoaded("kleeslabs")) {
            kleeCancelledHalf();
            kleeRestoreExistingHalf(false);
            kleeRestoreExistingHalf(true);
        }
    }
    private void kleeRestoreExistingHalf(boolean top) throws Exception {
        var p = origin.offset(26, 0, top ? 3 : 2); var type = top ? net.minecraft.world.level.block.state.properties.SlabType.TOP : net.minecraft.world.level.block.state.properties.SlabType.BOTTOM;
        var original = Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE, type);
        main(() -> { owner.getInventory().clearContent(); owner.getInventory().setItem(0, new ItemStack(Items.OAK_SLAB)); owner.serverLevel().setBlockAndUpdate(p, original); return true; });
        var ctx = context("merge one real slab into an existing half without owning that original half");
        var doc = new BlueprintDocument(1, "preserve pre-existing slab", "test-fixture", "1", "", position(p), List.of(new BlueprintDocument.Block(new BlueprintDocument.Position(0, 0, 0),
                new BlueprintDocument.State("minecraft:oak_slab", Map.of("type", "double", "waterlogged", "false")))));
        var saved = new BlueprintRepository(workspace).save(actor, UUID.randomUUID(), null, 0, "", ctx.taskId(), "minecraft:overworld", doc).entry();
        var ref = new JsonObject(); ref.addProperty("blueprintId", saved.id().toString()); ref.addProperty("revision", saved.revision()); ref.addProperty("blueprintHash", saved.hash());
        var source = planned(ctx, ref); var placed = call("build_execute", ctx, args(source));
        require(placed.success() && source.placements().size() == 1 && ledger.get(actor, source.id()).worldDebits().size() == 1,
                "existing-slab-merge-pays-one-item-" + top, placed);
        main(() -> { owner.getInventory().setItem(0, new ItemStack(Items.IRON_AXE)); return true; });
        var undoCtx = context("undo only the paid extra half"); var preview = call("build_undo_plan", undoCtx, args(source));
        require(preview.success(), "undo-of-extra-half-keeps-original-slab-in-fixed-plan-" + top, preview);
        var args = new JsonObject(); args.add("planId", preview.observations().get("planId")); args.add("planHash", preview.observations().get("planHash"));
        var plan = construction.removal().load(workspace, actor, args); var result = call("build_undo_execute", undoCtx, args);
        require(result.success() && plan.steps().size() == 1 && main(() -> owner.serverLevel().getBlockState(p).equals(original)
                        && owner.getInventory().countItem(Items.OAK_SLAB) == 1 && owner.getMainHandItem().getDamageValue() == 0),
                "undo-removes-only-owned-half-and-preserves-native-klee-costs-" + top, result);
        var sourceStep = new BuildJournal(workspace, source.executionEpoch()).get(actor, source.placements().getFirst().operationId());
        require(sourceStep.state() == BuildJournal.State.UNDONE, "source-half-is-undone-only-after-its-exact-before-state-is-restored-" + top, sourceStep);
        // Standalone selective demolition uses the same native half selection, with a new review.
        main(() -> { owner.serverLevel().setBlockAndUpdate(p, original.setValue(net.minecraft.world.level.block.SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.DOUBLE)); return true; });
        var regions = new BuildRegionStore(workspace); long revision = regions.current(actor).map(BuildRegionStore.Selection::revision).orElse(0L);
        var region = regions.save(actor, UUID.randomUUID(), revision, workspace.conversation().privacyEpoch(), "minecraft:overworld", position(p), position(p), true);
        var selectiveCtx = context("select only one half in a demolition plan"); var request = new JsonObject(); request.add("positions", AiJson.GSON.toJsonTree(List.of(List.of(p.getX(), p.getY(), p.getZ()))));
        request.addProperty("slabPart", top ? "lower" : "upper"); var selected = call("build_demolition_plan", selectiveCtx, request);
        require(selected.success(), "explicit-upper-or-lower-demolition-is-supported-" + top, selected);
        var selectedArgs = new JsonObject(); selectedArgs.add("planId", selected.observations().get("planId")); selectedArgs.add("planHash", selected.observations().get("planHash"));
        var removed = call("build_demolition_execute", selectiveCtx, selectedArgs);
        require(removed.success() && main(() -> owner.serverLevel().getBlockState(p).equals(original) && owner.getInventory().countItem(Items.OAK_SLAB) == 2),
                "selective-demolition-preserves-the-unapproved-half-" + top, removed);
        regions.save(actor, UUID.randomUUID(), region.revision(), workspace.conversation().privacyEpoch(), "", null, null, false);
    }
    private void kleeCancelledHalf() throws Exception {
        var p = origin.offset(24, 0, 2);
        main(() -> { owner.getInventory().clearContent(); owner.getInventory().setItem(0, new ItemStack(Items.IRON_AXE));
            owner.serverLevel().setBlockAndUpdate(p, Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE,
                    net.minecraft.world.level.block.state.properties.SlabType.DOUBLE)); return true; });
        var regions = new BuildRegionStore(workspace); long revision = regions.current(actor).map(BuildRegionStore.Selection::revision).orElse(0L);
        var region = regions.save(actor, UUID.randomUUID(), revision, workspace.conversation().privacyEpoch(), "minecraft:overworld", position(p), position(p), true);
        var ctx = context("cancel after an accepted native first slab phase"); var request = new JsonObject();
        request.add("positions", AiJson.GSON.toJsonTree(List.of(List.of(p.getX(), p.getY(), p.getZ()))));
        var planned = call("build_demolition_plan", ctx, request); require(planned.success(), "klee-cancel-fixture-has-two-fixed-native-phases", planned);
        var args = new JsonObject(); args.add("planId", planned.observations().get("planId")); args.add("planHash", planned.observations().get("planHash"));
        var plan = construction.removal().load(workspace, actor, args);
        Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> cancel = event -> { if (NativeKleeSlabScope.isExpectedCandidate(event.getEntity())) ctx.control().stop("CANCELLED"); };
        main(() -> { NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, cancel); return true; });
        try {
            var result = call("build_demolition_execute", ctx, args);
            require(!result.success() && result.errorCode().equals("CANCELLED") && result.observations().get("completedOperations").getAsInt() == 1
                            && result.observations().get("removedBlocks").getAsInt() == 0,
                    "klee-cancel-reports-one-real-phase-without-claiming-whole-block-removal", result);
        } finally { main(() -> { NeoForge.EVENT_BUS.unregister(cancel); return true; }); }
        require(main(() -> owner.serverLevel().getBlockState(p).getValue(net.minecraft.world.level.block.SlabBlock.TYPE) == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM)
                        && ledger.get(actor, plan.steps().getFirst().id()).remainingProducts().stream().mapToInt(MaterialStack::count).sum() == 1
                        && ledger.find(actor, plan.steps().getLast().id()).isEmpty(), "klee-cancel-retains-one-half-and-exactly-one-owned-custody-item", null);
        require(!call("build_demolition_execute", new AgentTool.Context(workspace, actor, new TaskControl(60000), ctx.taskId()), args).success(),
                "cancelled-half-plan-cannot-be-replayed-with-a-fresh-waiter", null);
        var evidence = main(() -> NativeRemovalProfiles.evidence(server));
        require(evidence.get("state").getAsString().equals("RUNTIME_READY") && !evidence.get("behaviorVerifiedHere").getAsBoolean(),
                "klee-runtime-evidence-does-not-claim-full-current-server-acceptance", evidence);
        regions.save(actor, UUID.randomUUID(), region.revision(), workspace.conversation().privacyEpoch(), "", null, null, false);
    }
    private void undoMultiBlock(boolean slab) throws Exception {
        var p = origin.offset(slab ? 30 : 28, 0, 2); var item = slab ? Items.OAK_SLAB : Items.OAK_DOOR;
        main(() -> { owner.getInventory().clearContent(); owner.getInventory().setItem(0, new ItemStack(item, slab ? 2 : 1)); return true; });
        var ctx = context(slab ? "undo two paid slab placements as one native double-slab break" : "undo one paid door placement as a two-cell native break");
        var blocks = new ArrayList<BlueprintDocument.Block>();
        blocks.add(new BlueprintDocument.Block(new BlueprintDocument.Position(0, 0, 0), new BlueprintDocument.State(slab ? "minecraft:oak_slab" : "minecraft:oak_door",
                slab ? Map.of("type", "double", "waterlogged", "false") : Map.of("half", "lower", "facing", "north", "hinge", "left", "open", "false", "powered", "false"))));
        if (!slab) blocks.add(new BlueprintDocument.Block(new BlueprintDocument.Position(0, 1, 0),
                new BlueprintDocument.State("minecraft:oak_door", Map.of("half", "upper", "facing", "north", "hinge", "left", "open", "false", "powered", "false"))));
        var doc = new BlueprintDocument(1, "native undo material fixture", "test-fixture", "1", "", position(p), blocks);
        var saved = new BlueprintRepository(workspace).save(actor, UUID.randomUUID(), null, 0, "", ctx.taskId(), "minecraft:overworld", doc).entry();
        var ref = new JsonObject(); ref.addProperty("blueprintId", saved.id().toString()); ref.addProperty("revision", saved.revision()); ref.addProperty("blueprintHash", saved.hash());
        var source = planned(ctx, ref); require(call("build_execute", ctx, args(source)).success(), "native-undo-source-built-" + (slab ? "double-slab" : "door"), null);
        main(() -> { owner.getInventory().setItem(0, new ItemStack(Items.IRON_AXE)); return true; });
        var undoCtx = context("native multiblock undo"); var planned = call("build_undo_plan", undoCtx, args(source));
        require(planned.success(), "native-multiblock-undo-planned-" + slab, planned);
        var args = new JsonObject(); args.add("planId", planned.observations().get("planId")); args.add("planHash", planned.observations().get("planHash"));
        var plan = construction.removal().load(workspace, actor, args);
        boolean klee = slab && net.neoforged.fml.ModList.get().isLoaded("kleeslabs");
        require(plan.steps().size() == (klee ? 2 : 1) && plan.steps().getLast().sourceSteps().size() == (slab ? 2 : 1)
                && (!klee || plan.steps().getFirst().sourceSteps().isEmpty()), "native-undo-groups-physical-operation-and-source-debits-" + slab, plan);
        if (klee) {
            var pausedControl = undoCtx.control();
            Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> pause = event -> {
                if (NativeKleeSlabScope.isExpectedCandidate(event.getEntity())) pausedControl.stop("USER_PAUSED");
            };
            main(() -> { NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, pause); return true; });
            try {
                var paused = call("build_undo_execute", undoCtx, args);
                require(!paused.success() && paused.errorCode().equals("PROCESSING_PAUSED") && main(() -> owner.serverLevel().getBlockState(p).getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
                        == net.minecraft.world.level.block.state.properties.SlabType.BOTTOM), "klee-paused-between-phases-retains-actual-lower-half", paused);
            } finally { main(() -> { NeoForge.EVENT_BUS.unregister(pause); return true; }); }
            var first = ledger.get(actor, plan.steps().getFirst().id());
            require(first.remainingProducts().stream().mapToInt(MaterialStack::count).sum() == 1 && first.delivered().stream().mapToInt(Integer::intValue).sum() == 0,
                    "klee-first-half-loot-remains-private-custody-after-pause", first);
            require(new BuildJournal(workspace, source.executionEpoch()).list(actor).stream().filter(s -> s.planId().equals(source.id())).allMatch(s -> s.state() == BuildJournal.State.VERIFIED),
                    "klee-half-completion-does-not-mark-the-whole-source-undone", null);
            require(construction.removal().review(workspace, actor, plan).contains("两阶段"), "klee-resume-review-revalidates-the-intermediate-world", null);
            undoCtx = new AgentTool.Context(workspace, actor, new TaskControl(60000), undoCtx.taskId());
        }
        var result = call("build_undo_execute", undoCtx, args);
        require(result.success() && main(() -> owner.serverLevel().getBlockState(p).isAir() && (slab || owner.serverLevel().getBlockState(p.above()).isAir())
                && owner.getInventory().countItem(item) == (slab ? 2 : 1) && owner.getMainHandItem().getDamageValue() == 1), "native-multiblock-undo-pays-once-and-keeps-actual-drop-count-" + slab, result);
        if (klee) {
            optionalFixtureSave();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (plan.steps().stream().anyMatch(s -> !ledger.get(actor, s.id()).saved()) && System.nanoTime() < deadline) Thread.sleep(20);
            require(plan.steps().stream().allMatch(s -> ledger.get(actor, s.id()).saved()), "klee-both-phases-and-deliveries-have-independent-native-save-proofs", null);
        }
    }
    private void demolition() throws Exception {
        var start = origin.offset(14, 0, 2); var second = start.east();
        main(() -> { owner.getInventory().clearContent(); owner.getInventory().selected = 0; owner.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
            owner.serverLevel().setBlockAndUpdate(start, Blocks.STONE.defaultBlockState()); owner.serverLevel().setBlockAndUpdate(second, Blocks.STONE.defaultBlockState()); return true; });
        var regions = new BuildRegionStore(workspace); long revision = regions.current(actor).map(BuildRegionStore.Selection::revision).orElse(0L);
        var selected = regions.save(actor, UUID.randomUUID(), revision, workspace.conversation().privacyEpoch(), "minecraft:overworld", position(start), position(second), true);
        var ctx = context("native survival demolition with private custody"); var request = new JsonObject();
        request.add("positions", AiJson.GSON.toJsonTree(List.of(List.of(start.getX(), start.getY(), start.getZ()), List.of(second.getX(), second.getY(), second.getZ()))));
        var planned = call("build_demolition_plan", ctx, request); require(planned.success(), "demolition-fixed-plan-from-confirmed-region", planned);
        var args = new JsonObject(); args.add("planId", planned.observations().get("planId")); args.add("planHash", planned.observations().get("planHash"));
        var plan = construction.removal().load(workspace, actor, args);
        var descriptor = registry().get("build_demolition_execute").descriptor();
        var toolCall = new ModelMessage.ToolCall("demolition", "build_demolition_execute", args.toString());
        require(new ToolPolicyEngine().check(actor, actor.owner(), descriptor, true, toolCall, "").decision() == ToolPolicyEngine.Decision.APPROVAL,
                "demolition-always-needs-bound-world-change-approval", null);
        var otherActor = Actor.player(other.getUUID(), true, true, 0);
        try { construction.removal().load(manager.getWorkspace(otherActor), otherActor, args); throw new IllegalStateException("FOREIGN_REMOVAL_PLAN_READ"); }
        catch (IllegalArgumentException expected) { passed.add("demolition-plan-private-to-owner"); }
        require(construction.removal().review(workspace, actor, plan).contains("minecraft:stone"), "demolition-review-contains-real-blocks-and-cost-rule", null);
        var before = main(owner::position); int tick = main(server::getTickCount);
        var result = call("build_demolition_execute", ctx, args);
        require(result.success(), "demolition-two-real-native-stages-deliver-harvest", result);
        require(main(() -> owner.position().equals(before) && owner.getMainHandItem().getDamageValue() == 2 && owner.getInventory().countItem(Items.COBBLESTONE) == 2
                && owner.serverLevel().getBlockState(start).isAir() && owner.serverLevel().getBlockState(second).isAir()) && main(server::getTickCount) > tick + 2,
                "demolition-pays-native-time-tool-and-delivers-only-real-loot-without-moving-owner", result);
        require(plan.steps().stream().allMatch(s -> ledger.get(actor, s.id()).state() == MaterialLedger.State.COMPLETED), "harvest-ledger-completes-only-after-delivery", null);
        optionalFixtureSave();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (plan.steps().stream().anyMatch(s -> !ledger.get(actor, s.id()).saved()) && System.nanoTime() < end) Thread.sleep(20);
        require(plan.steps().stream().allMatch(s -> ledger.get(actor, s.id()).saved()), "demolition-world-player-stats-and-delivery-native-saves-confirmed", null);
        var repeat = call("build_demolition_execute", ctx, args);
        require(!repeat.success() && main(() -> owner.getInventory().countItem(Items.COBBLESTONE)) == 2, "repeated-demolition-never-destroys-or-delivers-twice", repeat);

        // A full inventory pauses BEFORE the second target; the first actual loot remains private custody.
        main(() -> { for (int i = 1; i < 36; i++) owner.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            owner.serverLevel().setBlockAndUpdate(start, Blocks.STONE.defaultBlockState()); owner.serverLevel().setBlockAndUpdate(second, Blocks.STONE.defaultBlockState()); return true; });
        var fullCtx = context("full-inventory native demolition pause and resume"); var fullPlan = call("build_demolition_plan", fullCtx, request);
        require(fullPlan.success(), "full-inventory-does-not-fake-demolition-success", fullPlan);
        var fullArgs = new JsonObject(); fullArgs.add("planId", fullPlan.observations().get("planId")); fullArgs.add("planHash", fullPlan.observations().get("planHash"));
        var full = call("build_demolition_execute", fullCtx, fullArgs);
        require(!full.success() && full.errorCode().equals("WAITING_RESOURCES") && main(() -> owner.serverLevel().getBlockState(start).isAir()
                && owner.serverLevel().getBlockState(second).is(Blocks.STONE) && owner.getMainHandItem().getDamageValue() == 3), "full-inventory-holds-first-loot-and-stops-next-break", full);
        main(() -> { owner.getInventory().setItem(1, ItemStack.EMPTY); return true; });
        var resumed = call("build_demolition_execute", new AgentTool.Context(workspace, actor, new TaskControl(60000), fullCtx.taskId()), fullArgs);
        require(resumed.success() && main(() -> owner.getInventory().getItem(1).getCount() == 2 && owner.getMainHandItem().getDamageValue() == 4),
                "demolition-resume-delivers-custody-without-replaying-break-or-replacing-hand", resumed);

        main(() -> { owner.serverLevel().setBlockAndUpdate(start, Blocks.OBSIDIAN.defaultBlockState()); return true; });
        var cancelCtx = context("cancel native mining before removal"); var one = new JsonObject(); one.add("positions", AiJson.GSON.toJsonTree(List.of(List.of(start.getX(), start.getY(), start.getZ()))));
        var cancelPlan = call("build_demolition_plan", cancelCtx, one); require(cancelPlan.success(), "cancel-fixture-fixed-demolition-plan", cancelPlan);
        var cancelArgs = new JsonObject(); cancelArgs.add("planId", cancelPlan.observations().get("planId")); cancelArgs.add("planHash", cancelPlan.observations().get("planHash"));
        Consumer<net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock> cancel = event -> {
            if (event.getEntity() == owner && event.getAction() == net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action.START) cancelCtx.control().stop("CANCELLED");
        };
        main(() -> { NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, cancel); return true; });
        try {
            var cancelled = call("build_demolition_execute", cancelCtx, cancelArgs);
            require(!cancelled.success() && cancelled.errorCode().equals("CANCELLED") && main(() -> owner.serverLevel().getBlockState(start).is(Blocks.OBSIDIAN)
                    && owner.getMainHandItem().getDamageValue() == 4), "cancelled-native-mining-aborts-without-world-write-or-fictitious-refund", cancelled);
        } finally { main(() -> { NeoForge.EVENT_BUS.unregister(cancel); return true; }); }
        var pauseCtx = context("cancel a previously paused native mining session"); var pausePlan = call("build_demolition_plan", pauseCtx, one);
        require(pausePlan.success(), "paused-session-cancellation-fixture-planned", pausePlan);
        var pauseArgs = new JsonObject(); pauseArgs.add("planId", pausePlan.observations().get("planId")); pauseArgs.add("planHash", pausePlan.observations().get("planHash"));
        Consumer<net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock> pause = event -> {
            if (event.getEntity() == owner && event.getAction() == net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action.START) pauseCtx.control().stop("USER_PAUSED");
        };
        main(() -> { NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, pause); return true; });
        try { require(call("build_demolition_execute", pauseCtx, pauseArgs).errorCode().equals("PROCESSING_PAUSED"), "native-mining-can-pause-after-start-without-replaying-it", null); }
        finally { main(() -> { NeoForge.EVENT_BUS.unregister(pause); return true; }); }
        construction.removal().cancelTask(workspace, actor, pauseCtx.taskId());
        var pausedPlan = construction.removal().load(workspace, actor, pauseArgs);
        var stopped = new RemovalJournal(workspace, pausedPlan.executionEpoch()).get(actor, pausedPlan.steps().getFirst().id());
        require(stopped.state() == RemovalJournal.State.CANCELLED && main(() -> owner.serverLevel().getBlockState(start).is(Blocks.OBSIDIAN)),
                "later-workbench-cancel-closes-paused-native-session", stopped);
        regions.save(actor, UUID.randomUUID(), selected.revision(), workspace.conversation().privacyEpoch(), "", null, null, false);
    }
    private static BlueprintDocument.Position position(BlockPos p) { return new BlueprintDocument.Position(p.getX(), p.getY(), p.getZ()); }
    private void swallowedOwnerCallbackViolation() throws Exception {
        var player = (CallbackPlayer) owner; var attempted = new java.util.concurrent.atomic.AtomicBoolean();
        main(() -> { owner.getInventory().clearContent(); owner.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS)); return true; });
        var ctx = context("owner callback catches an unapproved neighbor write"); var plan = planned(ctx, blueprint(ctx, 29, 1));
        var outside = origin.offset(30, 0, 0);
        main(() -> {
            player.itemUsedHook = () -> {
                attempted.set(true);
                try { owner.serverLevel().setBlockAndUpdate(outside, Blocks.DIAMOND_BLOCK.defaultBlockState()); }
                catch (SecurityException intentionallyCaughtByListener) { }
            };
            return true;
        });
        AgentTool.Result result;
        try { result = call("build_execute", ctx, args(plan)); }
        finally { main(() -> { player.itemUsedHook = null; return true; }); }
        require(attempted.get() && main(() -> owner.serverLevel().getBlockState(outside).isAir()),
                "real-owner-callback-cannot-write-outside-approved-footprint", null);
        require(!result.success() && result.errorCode().equals("RECOVERY_REQUIRED") && ledger.get(actor, plan.id()).uncertain(),
                "caught-owner-callback-violation-still-retains-uncertain-receipt", result);
        var repeated = call("build_execute", ctx, args(plan));
        require(!repeated.success() && main(() -> owner.getInventory().countItem(Items.OAK_PLANKS)) == 0
                        && main(() -> owner.serverLevel().getBlockState(origin.offset(29, 0, 0)).is(Blocks.OAK_PLANKS)),
                "uncertain-owner-callback-does-not-refund-or-replay-placement", repeated);
    }
    private void regionSelection() throws Exception {
        var region = new BuildRegionService(server, blueprints, new InstalledPermissionAdapters(server));
        var ctx = context("select a manual working region without granting construction");
        var reference = blueprint(ctx, 25, 2); var a = origin.offset(25, 0, 0); var b = origin.offset(27, 0, 0);
        main(() -> { owner.getInventory().clearContent(); owner.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
            owner.serverLevel().getChunkAt(a); owner.serverLevel().getChunkAt(b); owner.serverLevel().setBlockAndUpdate(b, Blocks.BARREL.defaultBlockState());
            ((net.minecraft.world.level.block.entity.BarrelBlockEntity) owner.serverLevel().getBlockEntity(b)).setItem(0, new ItemStack(Items.DIAMOND, 5)); return true; });
        try {
            var args = new JsonObject(); args.addProperty("action", "set"); args.addProperty("expectedRevision", 0); args.addProperty("privacyEpoch", workspace.conversation().privacyEpoch());
            args.add("first", AiJson.GSON.toJsonTree(new BlueprintDocument.Position(a.getX(), a.getY(), a.getZ())));
            args.add("second", AiJson.GSON.toJsonTree(new BlueprintDocument.Position(b.getX(), b.getY(), b.getZ())));
            var chosen = region.handle(workspace, actor, UUID.randomUUID(), args, new TaskControl(60000));
            require(chosen.getAsJsonObject("bounds").get("volume").getAsInt() == 3 && !chosen.get("confirmed").getAsBoolean(), "manual-region-is-versioned-and-unconfirmed", chosen);
            var rejected = call("build_plan", ctx, reference);
            require(!rejected.success() && rejected.errorCode().equals("BUILD_REGION_NOT_CONFIRMED"), "unconfirmed-region-cannot-create-build-plan", rejected);
            var surveyArgs = new JsonObject(); surveyArgs.addProperty("action", "survey");
            var survey = region.handle(workspace, actor, UUID.randomUUID(), surveyArgs, new TaskControl(60000)).getAsJsonObject("survey");
            require(survey.get("occupied").getAsInt() == 1 && survey.get("dataBlocks").getAsInt() == 1 && !survey.toString().contains("diamond"),
                    "region-survey-reports-existing-container-without-private-contents", survey);
            var confirm = new JsonObject(); confirm.addProperty("action", "confirm"); confirm.add("expectedRevision", chosen.get("revision"));
            confirm.add("privacyEpoch", chosen.get("privacyEpoch")); confirm.add("hash", chosen.get("hash"));
            if (net.neoforged.fml.ModList.get().isLoaded("openpartiesandclaims")) {
                Class<?> api = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
                Class<?> contract = Class.forName("xaero.pac.common.server.claims.api.IServerClaimsManagerAPI");
                Object claims = main(() -> api.getMethod("getServerClaimsManager").invoke(api.getMethod("get", MinecraftServer.class).invoke(null, server)));
                var dimension = net.minecraft.resources.ResourceLocation.parse("minecraft:overworld");
                main(() -> { contract.getMethod("claim", net.minecraft.resources.ResourceLocation.class, UUID.class, int.class, int.class, int.class, boolean.class)
                        .invoke(claims, dimension, other.getUUID(), 0, a.getX() >> 4, a.getZ() >> 4, false); return true; });
                try {
                    try { region.handle(workspace, actor, UUID.randomUUID(), confirm, new TaskControl(60000)); throw new IllegalStateException("FOREIGN_REGION_CONFIRMED"); }
                    catch (IllegalStateException refused) { require(refused.getMessage().equals("BUILD_REGION_NOT_AVAILABLE"), "native-protected-region-cannot-be-confirmed", refused); }
                } finally { main(() -> { contract.getMethod("unclaim", net.minecraft.resources.ResourceLocation.class, int.class, int.class)
                        .invoke(claims, dimension, a.getX() >> 4, a.getZ() >> 4); return true; }); }
            }
            var confirmed = region.handle(workspace, actor, UUID.randomUUID(), confirm, new TaskControl(60000));
            require(confirmed.get("confirmed").getAsBoolean() && !confirmed.get("constructionApproved").getAsBoolean()
                            && main(() -> owner.getInventory().countItem(Items.OAK_PLANKS)) == 2,
                    "confirmed-region-does-not-spend-or-authorize-construction", confirmed);
            var plan = planned(ctx, reference);
            args.add("expectedRevision", confirmed.get("revision"));
            var edited = region.handle(workspace, actor, UUID.randomUUID(), args, new TaskControl(60000));
            var oldPlan = call("build_execute", ctx, args(plan));
            require(!oldPlan.success() && oldPlan.errorCode().equals("BLUEPRINT_REVIEW_CHANGED") && ledger.find(actor, plan.id()).isEmpty(),
                    "region-edit-invalidates-existing-approval-before-extraction", oldPlan);
            var clear = new JsonObject(); clear.addProperty("action", "clear"); clear.add("expectedRevision", edited.get("revision")); clear.add("privacyEpoch", edited.get("privacyEpoch"));
            require(region.handle(workspace, actor, UUID.randomUUID(), clear, new TaskControl(60000)).get("empty").getAsBoolean(), "owner-can-explicitly-clear-selected-region", null);
            require(main(() -> ((net.minecraft.world.level.block.entity.BarrelBlockEntity) owner.serverLevel().getBlockEntity(b)).getItem(0).getCount()) == 5,
                    "region-selection-never-mutates-existing-container", null);
        } finally { main(() -> { owner.serverLevel().setBlockAndUpdate(b, Blocks.AIR.defaultBlockState()); return true; }); }
    }
    private void runtimePauseResume() throws Exception {
        main(() -> { construction.close(); construction = create(true); owner.getInventory().clearContent();
            owner.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3)); return true; });
        try (var runtimeManager = new WorkspaceManager(Files.createTempDirectory(Path.of(".").toAbsolutePath(), "construction-runtime-"));
             var toolsIo = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var owned = runtimeManager.getWorkspace(actor);
            JsonObject input = blueprint(new AgentTool.Context(owned, actor, new TaskControl(60000)), 16, 3);
            var tools = registry(); var scheduler = new TaskScheduler(1, 8, 16);
            var activeControl = new java.util.concurrent.atomic.AtomicReference<TaskControl>();
            var round = new java.util.concurrent.atomic.AtomicInteger(); var pausedOnce = new java.util.concurrent.atomic.AtomicBoolean();
            var executeArgs = new java.util.concurrent.atomic.AtomicReference<JsonObject>();
            var bridge = new GameToolBridge(server, ignored -> actor, ignored -> true, () -> false, toolsIo);
            ModelGateway gateway = (request, control) -> {
                int turn = round.getAndIncrement();
                if (turn == 0) return modelCall("plan", "build_plan", input);
                if (turn == 1) {
                    String last = request.messages().stream().filter(message -> message.role().equals("tool")).reduce((a, b) -> b).orElseThrow().text();
                    var planResult = JsonParser.parseString(last).getAsJsonObject();
                    if (!planResult.get("success").getAsBoolean()) throw new IllegalStateException("RUNTIME_PLAN_FAILED:" + planResult.get("errorCode"));
                    JsonObject observation = planResult.getAsJsonObject("observations");
                    JsonObject args = new JsonObject(); args.add("planId", observation.get("planId")); args.add("planHash", observation.get("planHash")); executeArgs.set(args);
                    return modelCall("first-build", "build_execute", args);
                }
                if (turn == 2) return modelCall("remaining-build", "build_execute", executeArgs.get());
                return new ModelGateway.Reply(ModelMessage.text("assistant", "Verified remaining construction completed."), 10, 10);
            };
            AgentRuntime.Environment environment = new AgentRuntime.Environment() {
                @Override public Actor actor(String ignored) { return actor; }
                @Override public boolean enabled(String ignored) { return true; }
                @Override public void changed(WorkspaceManager.Workspace ws, AgentTask task, String notice) { }
                @Override public boolean requiresRecovery(WorkspaceManager.Workspace ws, UUID task) {
                    return ledger.list(actor).stream().anyMatch(op -> task.equals(op.taskId()) && (op.intent() != null || op.uncertain()));
                }
                @Override public CompletableFuture<AgentTool.Result> execute(AgentTool tool, AgentTool.Context ctx, JsonObject args, String approval, ModelMessage.ToolCall call) {
                    activeControl.set(ctx.control()); return bridge.execute(tool, ctx, args, approval, call);
                }
            };
            try (var runtime = new AgentRuntime(runtimeManager, scheduler, gateway, tools,
                    new ContextCompactor(new ContextCompactor.Settings(32000, 512, 1024, 512, .75, 2), "native-validation"),
                    new AgentRuntime.Limits(8, 8, 200000, 60000, 512, true, false), environment, new SecretFilter(List.of()))) {
                Consumer<BlockEvent.EntityPlaceEvent> pause = event -> {
                    if (event.getEntity() != null && event.getEntity().getUUID().equals(owner.getUUID()) && pausedOnce.compareAndSet(false, true))
                        activeControl.get().stop("USER_PAUSED");
                };
                main(() -> { NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, pause); return true; });
                try {
                    var task = runtime.submit(actor, UUID.randomUUID(), "Build the fixed three-block native validation blueprint");
                    awaitState(owned, task.id(), AgentTask.State.WAITING_APPROVAL); runtime.control(actor, task.id(), "approve");
                    awaitState(owned, task.id(), AgentTask.State.WAITING_EXTERNAL);
                    require(ledger.list(actor).stream().filter(operation -> task.id().equals(operation.taskId())).allMatch(operation -> operation.worldDebits().isEmpty()),
                            "agent-input-save-wait-does-not-place-a-block", null);
                    ValidationDeferredCalls.save(server);
                    awaitState(owned, task.id(), AgentTask.State.PAUSED);
                    while (scheduler.isBusy(actor.owner())) Thread.sleep(10);
                    UUID plan = UUID.fromString(executeArgs.get().get("planId").getAsString());
                    var operation = ledger.get(actor, plan);
                    require(!operation.uncertain() && operation.worldDebits().size() == 1
                            && operation.escrow().stream().mapToInt(MaterialStack::count).sum() == 2,
                            "agent-bridge-pause-retains-one-real-placement-and-two-held-materials", operation.state());
                    require(owned.conversation().getSnapshot(0).events().stream().anyMatch(e -> e.kind().equals("tool_result")
                            && e.data().get("errorCode").getAsString().equals("PROCESSING_PAUSED")), "agent-records-full-known-pause-receipt", null);
                    runtime.control(actor, task.id(), "resume"); awaitState(owned, task.id(), AgentTask.State.WAITING_APPROVAL);
                    require(ledger.get(actor, plan).worldDebits().size() == 1, "resumed-construction-needs-new-explicit-approval", null);
                    runtime.control(actor, task.id(), "approve"); awaitState(owned, task.id(), AgentTask.State.COMPLETED);
                    operation = ledger.get(actor, plan);
                    require(operation.state() == MaterialLedger.State.COMPLETED && operation.worldDebits().size() == 3
                            && main(() -> owner.getInventory().countItem(Items.OAK_PLANKS)) == 0,
                            "agent-resume-completes-only-two-remaining-placements-without-reextraction", operation.state());
                    long starts = owned.conversation().getSnapshot(0).events().stream().filter(e -> e.kind().equals("tool_start")).count();
                    long results = owned.conversation().getSnapshot(0).events().stream().filter(e -> e.kind().equals("tool_result")).count();
                    require(starts == 3 && results == 3, "agent-native-tool-transactions-all-have-durable-results", null);
                } finally { main(() -> { NeoForge.EVENT_BUS.unregister(pause); return true; }); runtime.close(); runtime.awaitStopped(10000); runtime.finalizePendingClose(); }
            }
        }
    }
    private static ModelGateway.Reply modelCall(String id, String name, JsonObject args) {
        return new ModelGateway.Reply(new ModelMessage("assistant", "", List.of(new ModelMessage.ToolCall(id, name, args.toString())), null, null), 10, 10);
    }
    private static void awaitState(WorkspaceManager.Workspace ws, UUID id, AgentTask.State state) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (ws.conversation().task(id).state() != state) {
            var task = ws.conversation().task(id);
            if (task.state() == AgentTask.State.RECOVERY_REQUIRED || task.state().terminal() || System.nanoTime() >= end)
                throw new IllegalStateException("RUNTIME_EXPECTED_" + state + "_ACTUAL_" + task.state() + ":" + task.reason());
            Thread.sleep(10);
        }
    }
    private void substitution() throws Exception {
        main(() -> { owner.getInventory().setItem(0, new ItemStack(Items.BIRCH_PLANKS, 8)); return true; });
        var ctx = context("substitute the planned wood without granting world writes"); var reference = blueprint(ctx, 12, 2);
        var repository = new BlueprintRepository(workspace);
        UUID id = UUID.fromString(reference.get("blueprintId").getAsString()); String hash = reference.get("blueprintHash").getAsString();
        var lease = repository.reviewLease(actor, id, hash, repository.reviewStamp(actor, id, hash));
        reference.addProperty("fromBlock", "minecraft:oak_planks"); reference.addProperty("toBlock", "minecraft:birch_planks");
        var offered = call("blueprint_material_propose", ctx, reference); var data = offered.observations();
        var questions = new QuestionStore(workspace);
        String dependency = QuestionCoordinator.toolStep("blueprint_material_apply", UUID.fromString(data.get("proposalId").getAsString()));
        var scopedQuestion = data.getAsJsonObject("question");
        require(offered.success() && questions.hasUnansweredSteps(ctx.taskId()) && !questions.blocking(ctx.taskId())
                        && scopedQuestion.get("blockingScope").getAsString().equals("STEPS")
                        && scopedQuestion.getAsJsonArray("requiredBeforeStepIds").asList().stream().map(JsonElement::getAsString).toList().contains(dependency),
                "material-substitution-blocks-only-its-trusted-apply-step", offered);
        require(main(() -> owner.getInventory().countItem(Items.BIRCH_PLANKS)) == 8 && repository.current(actor, repository.read(actor, id, 1, hash).entry()),
                "material-proposal-does-not-spend-or-change-blueprint", null);
        JsonObject apply = new JsonObject(); apply.add("proposalId", data.get("proposalId")); apply.add("proposalHash", data.get("proposalHash"));
        try { call("blueprint_material_apply", ctx, apply); throw new IllegalStateException("SUBSTITUTION_APPLIED_WITHOUT_ANSWER"); }
        catch (IllegalStateException expected) { require("SUBSTITUTION_ANSWER_REQUIRED".equals(expected.getMessage()), "unanswered-substitution-cannot-apply", expected); }
        var question = data.getAsJsonObject("question");
        new QuestionStore(workspace).answer(actor, UUID.fromString(question.get("id").getAsString()), question.get("digest").getAsString(), MaterialSubstitutionService.ACCEPT, UUID.randomUUID());
        var applied = call("blueprint_material_apply", ctx, apply); var manifest = applied.observations();
        require(applied.success() && manifest.get("revision").getAsLong() == 2, "accepted-substitution-creates-new-blueprint-revision", applied);
        var updated = repository.read(actor, id, 2, manifest.get("hash").getAsString());
        require(updated.document().blocks().stream().allMatch(block -> block.state().blockId().equals("minecraft:birch_planks")), "new-version-contains-the-approved-material", null);
        try { lease.check(); throw new IllegalStateException("OLD_BLUEPRINT_APPROVAL_SURVIVED_SUBSTITUTION"); }
        catch (IllegalStateException expected) { require("BLUEPRINT_REVIEW_CHANGED".equals(expected.getMessage()), "substitution-invalidates-old-world-approval", expected); }
        var repeated = call("blueprint_material_apply", ctx, apply);
        require(repeated.observations().get("revision").getAsLong() == 2 && repository.history(actor).stream().filter(entry -> entry.id().equals(id)).count() == 2,
                "repeated-apply-does-not-create-another-version", repeated);
        require(main(() -> owner.getInventory().countItem(Items.BIRCH_PLANKS)) == 8, "material-choice-never-grants-or-consumes-construction-inputs", null);
    }
    private AgentTool.Context context(String prompt) throws Exception {
        var task = AgentTask.create(actor.owner(), UUID.randomUUID(), prompt); workspace.conversation().append(List.of(Draft.checkpoint(task)));
        return new AgentTool.Context(workspace, actor, new TaskControl(120000), task.id());
    }
    private void poiConstruction() throws Exception {
        main(() -> { owner.getInventory().setItem(0, new ItemStack(Items.SMITHING_TABLE)); return true; });
        var ctx = context("place a native workstation and verify its independent POI store");
        var plan = planned(ctx, blueprint(ctx, 21, 1, "minecraft:smithing_table"));
        var placed = call("build_execute", ctx, args(plan));
        require(placed.success() && main(() -> owner.serverLevel().getPoiManager().existsAtPosition(
                net.minecraft.world.entity.ai.village.poi.PoiTypes.TOOLSMITH, origin.offset(21, 0, 0))),
                "approved-workstation-native-placement-includes-confirmed-poi-update", placed);
        optionalFixtureSave();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!ledger.get(actor, plan.id()).saved() && System.nanoTime() < deadline) Thread.sleep(20);
        require(ledger.get(actor, plan.id()).saved(), "workstation-construction-requires-chunk-and-independent-poi-save", ledger.get(actor, plan.id()));
    }
    private JsonObject blueprint(AgentTool.Context ctx, int x, int count) throws Exception {
        return blueprint(ctx, x, count, "minecraft:oak_planks");
    }
    private JsonObject blueprint(AgentTool.Context ctx, int x, int count, String block) throws Exception {
        var blocks = new ArrayList<BlueprintDocument.Block>();
        for (int i = 0; i < count; i++) blocks.add(new BlueprintDocument.Block(new BlueprintDocument.Position(i, 0, 0), new BlueprintDocument.State(block, Map.of())));
        var doc = new BlueprintDocument(1, "construction validation", "test-fixture", "1", "", new BlueprintDocument.Position(origin.getX() + x, origin.getY(), origin.getZ()), blocks);
        var revision = new BlueprintRepository(ctx.workspace()).save(actor, UUID.randomUUID(), null, 0, "", ctx.taskId(), "minecraft:overworld", doc).entry();
        var data = new JsonObject(); data.addProperty("blueprintId", revision.id().toString()); data.addProperty("revision", revision.revision()); data.addProperty("blueprintHash", revision.hash()); return data;
    }
    private ConstructionPlan planned(AgentTool.Context ctx, JsonObject blueprint) throws Exception {
        var value = call("build_plan", ctx, blueprint);
        if (!value.success() || !value.observations().has("planId")) throw new IllegalStateException("PLAN_FAILED:" + value.errorCode() + ":" + value.summary());
        return construction.load(workspace, actor, UUID.fromString(value.observations().get("planId").getAsString()), value.observations().get("planHash").getAsString());
    }
    private static JsonObject args(ConstructionPlan plan) { var args = new JsonObject(); args.addProperty("planId", plan.id().toString()); args.addProperty("planHash", plan.hash()); return args; }
    private ToolRegistry registry() { var tools = new ToolRegistry(); construction.register(tools); substitution.register(tools); return tools; }
    private AgentTool.Result call(String tool, AgentTool.Context ctx, JsonObject args) throws Exception {
        if (!tool.equals("build_execute")) return registry().get(tool).execute(ctx, args);
        currentSlice = ctx;
        return ValidationDeferredCalls.execute(server, ledger, inventory, registry().get(tool), ctx, args, fresh -> currentSlice = fresh, passed::add, false);
    }
    private TaskControl activeControl(AgentTool.Context original) {
        var slice = currentSlice;
        return slice != null && Objects.equals(slice.taskId(), original.taskId()) ? slice.control() : original.control();
    }
    private void require(boolean condition, String label, Object detail) { if (!condition) throw new IllegalStateException(label + ":" + detail); passed.add(label); }
    private <T> T main(Callable<T> work) throws Exception {
        var future = new CompletableFuture<T>(); server.execute(() -> { try { future.complete(work.call()); } catch (Exception failure) { future.completeExceptionally(failure); } });
        return future.get(10, TimeUnit.SECONDS);
    }
    /** Diagnostic opt-in only. Successful ordinary runs must be persisted by the production save pump. */
    private void optionalFixtureSave() throws Exception {
        if (!Boolean.getBoolean("fireflymc.validation.forceFixtureSaves")) return;
        main(() -> { server.getPlayerList().saveAll(); owner.serverLevel().save(null, true, false); return true; });
    }
    public JsonObject tick() {
        inventory.tick(); processing.tick(); construction.tick(); blueprints.tick(); samples.add(server.getAverageTickTimeNanos() / 1_000_000.0);
        if (!result.isDone()) return null;
        var report = result.join(); report.addProperty("msptMean", samples.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        report.addProperty("msptMaxRollingSample", samples.stream().mapToDouble(Double::doubleValue).max().orElse(0)); report.add("msptSamples", AiJson.GSON.toJsonTree(samples)); return report;
    }
    private ServerPlayer player(String name) {
        UUID id = UUID.randomUUID(); String freshName = name.substring(0, Math.min(7, name.length())) + "_" + id.toString().substring(0, 8);
        if (firefly520.fireflymc.auth.PlayerPasswordManager.getInstance().hasPasswordByName(freshName)) throw new IllegalStateException("TEST_FIXTURE_NAME_COLLISION");
        var cookie = new CommonListenerCookie(new GameProfile(id, freshName), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        var player = new CallbackPlayer(server, cookie);
        var connection = new Connection(PacketFlow.SERVERBOUND); channels.add(new EmbeddedChannel(connection));
        NetworkRegistry.configureMockConnection(connection); server.getPlayerList().placeNewPlayer(connection, player, cookie);
        ValidationHandshake.reply(player); ValidationHandshake.authenticateFixture(player);
        player.setGameMode(GameType.SURVIVAL); player.getInventory().clearContent(); return player;
    }
    /** Controlled native owner callback, after the placement kernel has returned; production code has no hook. */
    private static final class CallbackPlayer extends ServerPlayer {
        private Runnable itemUsedHook;
        CallbackPlayer(MinecraftServer server, CommonListenerCookie cookie) {
            super(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
        }
        @Override public void awardStat(net.minecraft.stats.Stat<?> stat, int amount) {
            super.awardStat(stat, amount);
            if (itemUsedHook != null && stat.getType() == net.minecraft.stats.Stats.ITEM_USED) itemUsedHook.run();
        }
    }
    @Override public void close() throws Exception {
        construction.close(); blueprints.close(); inventory.close(); processing.close(); processing.awaitEvidenceStopped(10000);
        for (var chunk : fixtureChunks) server.overworld().setChunkForced(chunk.x, chunk.z, false);
        for (var player : List.of(owner, other)) server.getPlayerList().remove(player);
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete(); channels.forEach(EmbeddedChannel::finishAndReleaseAll); manager.close();
    }
}
