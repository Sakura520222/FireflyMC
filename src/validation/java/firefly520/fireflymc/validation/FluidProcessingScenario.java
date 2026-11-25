package firefly520.fireflymc.validation;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.AiServerService;
import firefly520.fireflymc.ai.agent.AgentRuntime;
import firefly520.fireflymc.ai.context.ContextCompactor;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.processing.*;
import firefly520.fireflymc.ai.processing.special.IronCauldronAdapter;
import firefly520.fireflymc.ai.recovery.RecoveryTaskEvidence;
import firefly520.fireflymc.ai.recovery.RecoveryWorkbenchService;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.security.ToolPolicyEngine;
import firefly520.fireflymc.ai.security.SecretFilter;
import firefly520.fireflymc.ai.model.ModelGateway;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.model.ModelMessage.ToolCall;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.tools.GameToolBridge;
import firefly520.fireflymc.ai.tools.ToolRegistry;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import firefly520.fireflymc.ai.workspace.WorkspacePrivacyService;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Real dedicated service chain and native backpack tanks; no model, client rendering or production instance. */
public final class FluidProcessingScenario implements AutoCloseable {
    private final MinecraftServer server;
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<String> passed = new ArrayList<>();
    private final WorkspaceManager manager;
    private final Session owner, other;
    private final MaterialLedger items;
    private final FluidLedger fluids;
    private final MaterialInventoryService inventory;
    private final FluidInventoryService tanks;
    private final VirtualProcessingService dispatcher;
    private final FluidProcessingService processing;
    private final AtomicLong generation = new AtomicLong(1);
    private final CompletableFuture<JsonObject> result;
    private volatile Runnable beforeDispatch;
    private volatile TaskControl cancelDuringNative;
    private volatile boolean nativeCancellationObserved;
    private volatile Throwable hookFailure;
    private double maximumDispatcherSliceMillis;
    private boolean ironRecipeServiceRan;
    private int inputSaveWaits;
    private final Set<String> inputSaveWaitMechanisms = new HashSet<>();
    private boolean workbenchRecoveryRan;
    private final List<String> controllerResumeCases = new ArrayList<>();

    public FluidProcessingScenario(MinecraftServer server) throws Exception {
        this.server = server;
        check(server.isDedicatedServer(), "DEDICATED_FLUID_PROCESSING_REQUIRED");
        check(ModList.get().isLoaded("sophisticatedbackpacks"), "ACTUAL_BACKPACK_MOD_REQUIRED");
        Path root = Files.createTempDirectory(server.getWorldPath(LevelResource.ROOT).toAbsolutePath(), "ai4-fluid-processing-");
        manager = new WorkspaceManager(root); items = new MaterialLedger(root); fluids = new FluidLedger(root);
        var permissions = new InstalledPermissionAdapters(server);
        inventory = new MaterialInventoryService(server, AiServerService::authenticated, permissions);
        tanks = new FluidInventoryService(server, inventory, permissions);
        dispatcher = new VirtualProcessingService(server, inventory, items, generation::get);
        processing = new FluidProcessingService(server, inventory, tanks, items, fluids, dispatcher, generation::get);
        owner = new Session("fluid_agent_one"); other = new Session("fluid_agent_two");
        tanks.recoveryGuard(this::nativeTransferGuard, processing::recoveryBlocksPlayer);
        inventory.recoveryGuard((identity, backend) -> processing.recoveryBlocksPlayer(identity) || processing.recoveryBlocksBackend(backend));
        result = CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime(); var report = new JsonObject();
            try { run(); report.addProperty("status", "PASSED"); }
            catch (Throwable failure) {
                failure.printStackTrace(); report.addProperty("status", "FAILED"); report.addProperty("error", failure.toString());
                report.add("failureFrames", AiJson.GSON.toJsonTree(Arrays.stream(failure.getStackTrace()).limit(8).map(Object::toString).toList()));
            }
            report.add("passedChecks", AiJson.GSON.toJsonTree(passed));
            report.addProperty("ironRecipeServiceRan", ironRecipeServiceRan);
            report.addProperty("inputSaveWaits", inputSaveWaits);
            report.addProperty("workbenchRecoveryRan", workbenchRecoveryRan);
            report.add("controllerResumeCases", AiJson.GSON.toJsonTree(controllerResumeCases));
            report.addProperty("controllerReopenOnly", true);
            report.addProperty("durationMillis", (System.nanoTime() - started) / 1_000_000);
            report.addProperty("scope", "Real FluidProcessingService and private RecoveryWorkbenchService on installed Sophisticated tanks and authenticated dedicated fixture players; real save receipts, saved-custody controller reopen/resume and shared verifier through AgentRuntime acknowledgement; no JVM restart, external provider, graphical client, two-client network, power-loss or full-modpack acceptance claim.");
            return report;
        }, work -> Thread.ofPlatform().daemon(true).name("fluid-processing-validation").start(work));
    }

    private void run() throws Exception {
        main(() -> { owner.fill(4000); other.fill(1200); owner.player.getInventory().setItem(1, new ItemStack(Items.BUCKET, 2)); return true; });
        approvalPolicy();
        var filling = context(owner, "Fill one real water bucket"); var fill = plan(filling, true, null);
        check(main(owner::amount) == 4000 && main(() -> count(owner.player, Items.BUCKET)) == 2, "planning-does-not-mutate-native-inputs");
        var executed = executeService(filling, fill);
        check(!executed.errorCode().equals("RECOVERY_REQUIRED"), "fill-service-produced-known-result:" + executed.errorCode());
        check(main(owner::amount) == 3000 && main(() -> count(owner.player, Items.WATER_BUCKET)) == 1
                && main(() -> count(owner.player, Items.BUCKET)) == 1, "native-fill-exact-item-and-1000mb-conservation");
        check(!owner.journal.settled(owner.actor, fill.id()), "native-save-is-required-before-settlement");
        save(); await(() -> owner.journal.settled(owner.actor, fill.id()), "filled-bucket-durable-save");
        var duplicate = processing.execute(filling, fill);
        check(!duplicate.success() && duplicate.errorCode().equals("FLUID_ALREADY_RECORDED")
                && main(owner::amount) == 3000 && main(() -> count(owner.player, Items.WATER_BUCKET)) == 1, "duplicate-execute-does-not-replay-native-effects");
        expectFailure(() -> processing.execute(context(other, "Cannot use another player's approved operation"), fill), "WORKSPACE_ACCESS_DENIED");
        expectFailure(() -> owner.journal.get(other.actor, fill.id()), "WORKSPACE_ACCESS_DENIED");
        check(main(other::amount) == 1200 && main(() -> count(other.player, Items.WATER_BUCKET)) == 0, "other-player-native-state-remains-isolated");

        var emptying = context(owner, "Empty actual water bucket into the authorized tank"); var empty = plan(emptying, false, owner.grant.id());
        executeService(emptying, empty);
        check(main(owner::amount) == 4000 && main(() -> count(owner.player, Items.WATER_BUCKET)) == 0
                && main(() -> count(owner.player, Items.BUCKET)) == 2, "native-empty-exact-fluid-and-container-conservation");
        save(); await(() -> owner.journal.settled(owner.actor, empty.id()), "emptied-bucket-durable-save");

        revokeOutputAndReturn();
        cancelAfterDrainAndReturn();
        invalidGenerationAndTimeout();
        authenticationAndOutputPreflight();
        check(processing.resourcesSettled(owner.actor.owner()) && items.settled(owner.actor.owner()), "all-actual-custody-settled-before-privacy-change");
        check(!processing.exportJournal(owner.actor).contains(other.actor.owner()), "owner-export-excludes-other-player");
        if (ModList.get().isLoaded("irons_spellbooks")) ironRecipes();
        check(inputSaveWaitMechanisms.containsAll(List.of(FluidProcessingAdapters.BUCKET_FILL, FluidProcessingAdapters.BUCKET_EMPTY)),
                "real-native-input-save-waits-observed-in-both-bucket-directions");
        privacy();
        workbenchRecovery();
        controllerPartialResume(false);
        controllerPartialResume(true);
        controllerResume(false, false);
        if (ModList.get().isLoaded("irons_spellbooks")) {
            controllerResume(true, false);
            controllerResume(true, true);
        }
    }

    private record IronFixture(String recipeId, MaterialStack input, FluidAmount requiredFluid,
                               List<MaterialStack> itemOutputs, List<FluidAmount> fluidOutputs) {}
    private void ironRecipes() throws Exception {
        // These are installed native RecipeManager holders, never synthetic replacement recipes.
        var session = main(() -> new Session("fluid_iron_native"));
        try {
            var soak = main(() -> ironFixture("minecraft:alchemist_cauldron/soak_crying_obsidian", false));
            check(!soak.requiredFluid().empty() && soak.fluidOutputs().isEmpty() && !soak.itemOutputs().isEmpty(),
                    "installed-iron-soak-is-a-real-raw-fluid-to-item-recipe");
            executeIronRecipe(session, soak, "raw-fluid-soak");
            var fill = main(() -> ironFixture("", true));
            check(fill.requiredFluid().empty() && !fill.fluidOutputs().isEmpty(), "installed-iron-fill-produces-real-raw-fluid");
            executeIronRecipe(session, fill, "native-fill");
            check(processing.resourcesSettled(session.actor.owner()) && items.settled(session.actor.owner()),
                    "installed-iron-service-custody-and-native-saves-fully-settled");
            ironRecipeServiceRan = true;
        } finally { main(() -> { server.getPlayerList().remove(session.player); return true; }); }
    }
    private IronFixture ironFixture(String exactId, boolean fill) {
        var holder = fill ? server.getRecipeManager().getRecipes().stream()
                .filter(recipe -> IronCauldronAdapter.kind(recipe.value()) == IronCauldronAdapter.Kind.FILL)
                .sorted(Comparator.comparing(recipe -> recipe.id().toString())).findFirst()
                .orElseThrow(() -> new IllegalStateException("INSTALLED_IRON_FILL_RECIPE_MISSING"))
                : server.getRecipeManager().byKey(ResourceLocation.parse(exactId)).orElseThrow();
        ItemStack[] variants = IronCauldronAdapter.itemIngredient(holder, server.overworld()).getItems();
        if (variants.length == 0) throw new IllegalStateException("INSTALLED_IRON_INGREDIENT_HAS_NO_REAL_ITEM");
        ItemStack input = variants[0].copyWithCount(1);
        FluidStack required = IronCauldronAdapter.fluidIngredient(holder, server.overworld())
                .map(fluid -> fluid.restore(server.registryAccess())).orElse(FluidStack.EMPTY);
        var expected = IronCauldronAdapter.verify(holder, input, required, server.overworld());
        return new IronFixture(holder.id().toString(), ItemSnapshotCodec.capture(input, server.registryAccess()),
                FluidSnapshotCodec.capture(required, server.registryAccess()), expected.producedItems(),
                expected.producedFluids().stream().map(fluid -> FluidSnapshotCodec.capture(fluid.restore(server.registryAccess()), server.registryAccess())).toList());
    }
    private void executeIronRecipe(Session session, IronFixture fixture, String kind) throws Exception {
        main(() -> {
            if (FluidTankSnapshot.capture(session.handler(), session.player.registryAccess()).tanks().stream().anyMatch(tank -> !tank.fluid().empty()))
                throw new IllegalStateException("IRON_RECIPE_FIXTURE_REQUIRES_EMPTY_REAL_TANK");
            session.player.getInventory().clearContent(); session.player.getInventory().setItem(0, session.backpack);
            session.player.getInventory().setItem(1, ItemSnapshotCodec.restore(fixture.input(), session.player.registryAccess()));
            if (!fixture.requiredFluid().empty()) {
                FluidStack fluid = FluidSnapshotCodec.restore(fixture.requiredFluid(), session.player.registryAccess()); int remaining = fluid.getAmount();
                for (int i = 0; remaining > 0 && i < 128; i++) {
                    int accepted = session.handler().fill(fluid.copyWithAmount(remaining), IFluidHandler.FluidAction.EXECUTE);
                    if (accepted <= 0 || accepted > remaining) throw new IllegalStateException("IRON_NATIVE_FIXTURE_FILL_REJECTED"); remaining -= accepted;
                }
                if (remaining != 0) throw new IllegalStateException("IRON_NATIVE_FIXTURE_FILL_LIMIT");
            }
            return true;
        });
        var ctx = context(session, "Execute installed Iron recipe " + fixture.recipeId());
        var plan = processing.plan(ctx, new FluidProcessingPlanner.Request(fixture.recipeId(), "", "", "", 1,
                fixture.fluidOutputs().isEmpty() ? null : session.grant.id()));
        check(plan.mechanismId().isEmpty() && plan.recipeId().equals(fixture.recipeId())
                && plan.itemInputs().equals(List.of(fixture.input())) && plan.itemOutputs().equals(fixture.itemOutputs())
                && plan.fluidOutputs().equals(fixture.fluidOutputs()), "installed-iron-" + kind + "-plan-keeps-native-recipe-contract");
        check(plan.fluidClaims().stream().mapToInt(claim -> claim.amount().millibuckets()).sum() == fixture.requiredFluid().millibuckets(),
                "installed-iron-" + kind + "-plan-reserves-exact-real-fluid");
        var response = executeService(ctx, plan);
        check(!response.errorCode().equals("RECOVERY_REQUIRED"), "installed-iron-" + kind + "-service-produces-known-result:" + response.errorCode());
        var nativeItems = main(() -> {
            var stacks = new ArrayList<MaterialStack>();
            for (int slot = 1; slot < session.player.getInventory().getContainerSize(); slot++) {
                var stack = session.player.getInventory().getItem(slot);
                if (!stack.isEmpty()) stacks.add(ItemSnapshotCodec.capture(stack, session.player.registryAccess()));
            }
            return itemTotals(stacks);
        });
        check(nativeItems.equals(itemTotals(fixture.itemOutputs())), "installed-iron-" + kind + "-native-item-output-conservation");
        var nativeFluids = main(() -> fluidTotals(FluidTankSnapshot.capture(session.handler(), session.player.registryAccess()).tanks()
                .stream().map(FluidTankSnapshot.Tank::fluid).filter(fluid -> !fluid.empty()).toList()));
        check(nativeFluids.equals(fluidTotals(fixture.fluidOutputs())), "installed-iron-" + kind + "-native-tank-output-conservation");
        check(plan.fluidClaims().stream().allMatch(claim -> fluids.get(session.actor, claim.operationId()).consumedMb() == claim.amount().millibuckets()),
                "installed-iron-" + kind + "-actual-fluid-consumption-is-journalled");
        main(() -> {
            var field = net.minecraft.server.players.PlayerList.class.getDeclaredField("playerIo"); field.setAccessible(true);
            ((net.minecraft.world.level.storage.PlayerDataStorage) field.get(server.getPlayerList())).save(session.player);
            server.overworld().getDataStorage().save(); IOUtilities.waitUntilIOWorkerComplete(); return true;
        });
        await(() -> session.journal.settled(session.actor, plan.id()), "installed-iron-" + kind + "-native-save-confirmed");
    }
    private static Map<String, Long> itemTotals(List<MaterialStack> stacks) {
        var result = new TreeMap<String, Long>(); stacks.forEach(stack -> result.merge(stack.itemJson(), (long) stack.count(), Long::sum)); return result;
    }
    private static Map<String, Long> fluidTotals(List<FluidAmount> amounts) {
        var result = new TreeMap<String, Long>(); amounts.forEach(fluid -> result.merge(fluid.unitJson(), (long) fluid.millibuckets(), Long::sum)); return result;
    }

    private void approvalPolicy() {
        var registry = new ToolRegistry(); processing.register(registry); var policy = new ToolPolicyEngine();
        var op = new Actor(owner.actor.owner(), true, true, 4);
        var unauthenticated = new Actor(owner.actor.owner(), false, true, 4);
        for (String name : List.of("fluid_processing_execute", "fluid_processing_return", "fluid_processing_reconcile")) {
            var tool = registry.get(name); check(tool != null && tool.descriptor().risk() == AgentTool.Risk.PLAYER_STATE
                    && tool.descriptor().requiredCapability().equals("materials.own"), "real-registered-mutation-descriptor:" + name);
            var arguments = new JsonObject();
            if (name.equals("fluid_processing_execute")) {
                arguments.addProperty("planId", UUID.randomUUID().toString()); arguments.addProperty("planHash", AiJson.digest("policy-fixture"));
            } else if (name.equals("fluid_processing_return")) {
                arguments.addProperty("reviewId", UUID.randomUUID().toString()); arguments.addProperty("reviewHash", AiJson.digest("policy-fixture"));
            } else {
                arguments.addProperty("operationId", UUID.randomUUID().toString()); arguments.addProperty("revision", 1);
                arguments.addProperty("reviewHash", AiJson.digest("policy-fixture"));
            }
            var call = new ToolCall(UUID.randomUUID().toString(), name, arguments.toString());
            ToolPolicyEngine.arguments(call, tool.descriptor().inputSchema());
            check(policy.check(owner.actor, owner.actor.owner(), tool.descriptor(), true, call, "").decision() == ToolPolicyEngine.Decision.APPROVAL,
                    "unapproved-fluid-tool-requires-workbench-approval:" + name);
            check(policy.check(op, owner.actor.owner(), tool.descriptor(), true, call, "").decision() == ToolPolicyEngine.Decision.APPROVAL,
                    "op-level-four-cannot-bypass-fluid-approval:" + name);
            String digest = ToolPolicyEngine.digest(owner.actor.owner(), call);
            check(policy.check(owner.actor, owner.actor.owner(), tool.descriptor(), true, call, digest).decision() == ToolPolicyEngine.Decision.ALLOW,
                    "exact-owner-call-digest-allows-approved-tool:" + name);
            check(policy.check(unauthenticated, owner.actor.owner(), tool.descriptor(), true, call, digest).decision() == ToolPolicyEngine.Decision.DENY,
                    "unverified-actor-is-denied-even-with-approval:" + name);
            arguments.addProperty(name.equals("fluid_processing_execute") ? "planHash" : "reviewHash", AiJson.digest("changed-policy-fixture"));
            var changed = new ToolCall(call.id(), call.name(), arguments.toString());
            check(policy.check(owner.actor, owner.actor.owner(), tool.descriptor(), true, changed, digest).decision() == ToolPolicyEngine.Decision.APPROVAL,
                    "changed-call-cannot-reuse-fluid-approval:" + name);
        }
        for (String name : List.of("fluid_processing_mechanisms", "fluid_processing_plan", "fluid_processing_status",
                "fluid_processing_return_preview", "fluid_processing_reconcile_preview")) {
            var tool = registry.get(name);
            check(tool != null && tool.descriptor().risk() == AgentTool.Risk.READ_ONLY, "read-only-fluid-tool-descriptor:" + name);
        }
    }

    private void authenticationAndOutputPreflight() throws Exception {
        int amount = main(owner::amount), buckets = main(() -> count(owner.player, Items.BUCKET));
        var auth = context(owner, "Server authentication is rechecked for real native execution"); var authPlan = plan(auth, true, null);
        main(() -> { firefly520.fireflymc.network.ModPayloadHandler.VERIFIED_PLAYERS.remove(owner.player.getUUID()); return true; });
        try { expectFailure(() -> processing.execute(auth, authPlan), "AUTH_REQUIRED"); }
        finally { main(() -> {
            // Only the version gate was revoked. Its real reply reuses this session's
            // already verified password and rules, just as a client reply would.
            ValidationHandshake.reply(owner.player);
            if (!AiServerService.authenticated(owner.player)) throw new IllegalStateException("TEST_REAL_AUTH_RESTORE_FAILED");
            return true;
        }); }
        check(owner.journal.find(owner.actor, authPlan.id()).isEmpty() && main(owner::amount) == amount
                && main(() -> count(owner.player, Items.BUCKET)) == buckets, "unverified-real-player-never-extracts-native-inputs");
        main(() -> {
            int slot = owner.player.getInventory().getFreeSlot();
            if (slot < 0) throw new IllegalStateException("FIXTURE_HAS_NO_FREE_INPUT_SLOT");
            owner.player.getInventory().setItem(slot, new ItemStack(Items.WATER_BUCKET)); return true;
        });
        var revoked = context(owner, "Revoke output grant before any extraction"); var revokedPlan = plan(revoked, false, owner.grant.id());
        int waterBuckets = main(() -> count(owner.player, Items.WATER_BUCKET));
        main(() -> { owner.grants.revoke(owner.actor, owner.grant.id()); return true; });
        expectFailure(() -> processing.execute(revoked, revokedPlan), "SOURCE_AUTHORIZATION_CHANGED");
        check(owner.journal.find(owner.actor, revokedPlan.id()).isEmpty() && items.find(owner.actor, revokedPlan.id()).isEmpty()
                && main(owner::amount) == amount && main(() -> count(owner.player, Items.WATER_BUCKET)) == waterBuckets,
                "revoked-output-preflight-rejects-before-first-item-extraction");
        main(() -> { owner.grant = owner.authorize(); return true; });
    }

    private void privacy() throws Exception {
        var ctx = context(owner, "Old privacy epoch plan must not execute"); var oldPlan = plan(ctx, true, null);
        expectFailure(() -> owner.workspace.conversation().erase(owner.workspace.conversation().revision()), "WORKSPACE_NOT_SAFE_TO_ERASE");
        var privacy = new WorkspacePrivacyService(System::currentTimeMillis,
                workspace -> items.settled(workspace.owner()) && processing.resourcesSettled(workspace.owner()));
        expectFailure(() -> privacy.preview(owner.actor, owner.workspace), "ACTIVE_TASK_OR_ESCROW_REQUIRES_SETTLEMENT");
        var completedTasks = owner.journal.list(owner.actor).stream().filter(operation -> owner.journal.settled(owner.actor, operation.definition().id()))
                .map(operation -> operation.definition().taskId()).collect(java.util.stream.Collectors.toSet());
        var checkpoints = owner.workspace.conversation().tasks().stream().filter(task -> !task.state().terminal())
                .map(task -> Draft.checkpoint(task.update(completedTasks.contains(task.id()) ? AgentTask.State.COMPLETED : AgentTask.State.CANCELLED,
                        "ISOLATED_VALIDATION_FINISHED_REAL_CUSTODY_CHECKED"))).toList();
        owner.workspace.conversation().append(checkpoints);
        long epoch = owner.workspace.conversation().privacyEpoch(), revision = owner.workspace.conversation().revision();
        long otherRevision = other.workspace.conversation().revision();
        UUID primary = owner.workspace.primaryConversationId(); int amount = main(owner::amount);
        var preview = privacy.preview(owner.actor, owner.workspace);
        expectFailure(() -> privacy.confirm(other.actor, owner.workspace, preview.token()), "WORKSPACE_ACCESS_DENIED");
        long erasedRevision = privacy.confirm(owner.actor, owner.workspace, preview.token());
        main(() -> { items.forgetSettled(owner.actor); processing.forgetSettled(owner.actor);
            inventory.clearPrivateViews(owner.actor.owner()); tanks.clearPrivateViews(owner.actor.owner()); return true; });
        check(erasedRevision == revision + 1 && owner.workspace.conversation().privacyEpoch() == erasedRevision
                && erasedRevision > epoch && primary.equals(owner.workspace.primaryConversationId()),
                "real-private-erasure-preserves-one-primary-conversation-and-advances-epoch");
        check(!Files.exists(owner.workspace.artifact(oldPlan.id())) && !Files.exists(owner.workspace.directory().resolve("mixed-materials/journal.jsonl"))
                && !Files.exists(owner.workspace.directory().resolve("fluids/journal.jsonl")), "real-private-erasure-removes-owned-fluid-and-mixed-journals");
        check(other.workspace.conversation().revision() == otherRevision && Files.exists(other.workspace.directory().resolve("fluids/grants.json"))
                && main(other::amount) == 1200 && main(owner::amount) == amount, "privacy-erasure-preserves-native-world-and-other-player");
        expectFailure(() -> processing.load(owner.workspace, owner.actor, oldPlan.id(), oldPlan.hash()), null);
        check(!owner.journal.journalAvailable(), "old-journal-fenced-by-privacy-epoch");
    }

    private void revokeOutputAndReturn() throws Exception {
        main(() -> { owner.player.getInventory().setItem(2, new ItemStack(Items.WATER_BUCKET)); return true; });
        var ctx = context(owner, "Output authorization is withdrawn after processing"); var plan = plan(ctx, false, owner.grant.id());
        UUID oldGrant = owner.grant.id();
        beforeDispatch = () -> {
            var op = owner.journal.find(owner.actor, plan.id()).orElse(null);
            if (op != null && op.state() == MixedResourceJournal.State.COMMITTED) {
                beforeDispatch = null;
                try { owner.grants.revoke(owner.actor, oldGrant); } catch (Exception failure) { throw new RuntimeException(failure); }
            }
        };
        var stopped = executeService(ctx, plan); checkHook();
        check(!stopped.success() && beforeDispatch == null, "withdrawn-output-grant-stops-real-service-delivery");
        check(main(owner::amount) == 4000 && main(() -> count(owner.player, Items.WATER_BUCKET)) == 0,
                "withdrawn-output-keeps-real-1000mb-in-joint-custody");
        check(owner.journal.heldFluidOutputs(owner.actor, plan.id()).getFirst().millibuckets() == 1000, "undelivered-fluid-product-is-audited");
        save(); await(() -> items.get(owner.actor, plan.id()).saved(), "revoked-output-item-save");
        main(() -> { owner.grant = owner.authorize(); return true; });
        var returning = context(owner, "Return the existing fluid to freshly authorized tank");
        var preview = processing.returnPreview(returning, plan.id(), owner.grant.id());
        processing.returnMaterials(returning, preview);
        check(main(owner::amount) == 5000 && owner.journal.heldFluidOutputs(owner.actor, plan.id()).getFirst().empty(),
                "new-authorized-return-delivers-only-existing-fluid-product");
        save(); await(() -> items.get(owner.actor, plan.id()).saved()
                && owner.journal.get(owner.actor, plan.id()).fluidDeliveriesSaved(), "returned-output-native-save");
        var finishing = context(owner, "Confirm saved output return is fully settled");
        processing.returnMaterials(finishing, processing.returnPreview(finishing, plan.id(), owner.grant.id()));
        check(owner.journal.settled(owner.actor, plan.id()), "returned-output-terminal-state-is-durable");
        expectFailure(() -> processing.returnMaterials(returning, preview), "FLUID_RETURN_PREVIEW_CHANGED");
        check(main(owner::amount) == 5000, "reused-return-preview-does-not-duplicate-fluid");
    }

    private void cancelAfterDrainAndReturn() throws Exception {
        var ctx = context(owner, "Cancel accepted native drain before recipe commit"); var plan = plan(ctx, true, null);
        int buckets = main(() -> count(owner.player, Items.BUCKET));
        nativeCancellationObserved = false; cancelDuringNative = ctx.control();
        expectFailure(() -> processing.execute(ctx, plan), "CANCELLED"); checkHook();
        check(nativeCancellationObserved && cancelDuringNative == null && main(owner::amount) == 4000, "cancelled-accepted-call-still-records-actual-native-drain");
        var op = owner.journal.get(owner.actor, plan.id());
        check(op.returnMode() == MixedResourceJournal.ReturnMode.INPUTS && op.state() == MixedResourceJournal.State.RETURNING,
                "cancelled-service-retains-real-inputs-without-producing-output");
        check(plan.fluidClaims().stream().allMatch(claim -> fluids.get(owner.actor, claim.operationId()).consumedMb() == 0), "cancelled-fluid-was-never-recipe-consumed");
        save(); await(() -> items.get(owner.actor, plan.id()).saved()
                && plan.fluidClaims().stream().allMatch(claim -> fluids.get(owner.actor, claim.operationId()).saved()), "cancelled-input-native-save");
        var returning = context(owner, "Return only physically extracted inputs");
        processing.returnMaterials(returning, processing.returnPreview(returning, plan.id(), owner.grant.id()));
        check(main(owner::amount) == 5000 && main(() -> count(owner.player, Items.BUCKET)) == buckets,
                "cancelled-service-returns-exact-real-item-and-fluid-inputs");
        save(); await(() -> items.get(owner.actor, plan.id()).saved()
                && plan.fluidClaims().stream().allMatch(claim -> fluids.get(owner.actor, claim.operationId()).saved()), "returned-input-native-save");
        var finishing = context(owner, "Confirm saved input return is fully settled");
        processing.returnMaterials(finishing, processing.returnPreview(finishing, plan.id(), owner.grant.id()));
        check(owner.journal.settled(owner.actor, plan.id()), "cancelled-input-return-reaches-saved-terminal-state");
    }

    private void workbenchRecovery() throws Exception {
        var session = main(() -> new Session("fluid_recovery_native"));
        var toolIo = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16),
                Thread.ofPlatform().daemon(true).name("fluid-recovery-tools-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
        try {
            main(() -> { session.fill(4000); session.player.getInventory().setItem(1, new ItemStack(Items.BUCKET)); return true; });
            var registry = new ToolRegistry(); dispatcher.register(registry); processing.register(registry);
            java.util.function.Function<String, Actor> currentActor = identity -> {
                try { return main(() -> {
                    var player = server.getPlayerList().getPlayer(UUID.fromString(identity));
                    return player == null ? new Actor(identity, false, false, 0)
                            : Actor.player(player.getUUID(), AiServerService.authenticated(player), true, 0);
                }); } catch (Exception failure) { throw new CompletionException(failure); }
            };
            var bridge = new GameToolBridge(server, currentActor, name -> registry.get(name) != null, () -> false, toolIo);
            var scheduler = new TaskScheduler(1, 8, 16);
            var unexpectedModelCalls = new java.util.concurrent.atomic.AtomicInteger();
            var unexpectedRuntimeDispatches = new java.util.concurrent.atomic.AtomicInteger();
            var acknowledged = new java.util.concurrent.atomic.AtomicInteger();
            var callbackFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            ModelGateway gateway = (request, control) -> {
                unexpectedModelCalls.incrementAndGet(); throw new IllegalStateException("RECOVERY_MUST_NOT_REQUEST_MODEL");
            };
            var environment = new AgentRuntime.Environment() {
                public Actor actor(String identity) { return currentActor.apply(identity); }
                public boolean enabled(String name) { return registry.get(name) != null; }
                public boolean recoverySettled(WorkspaceManager.Workspace workspace, UUID task) {
                    // This pure verifier runs under the runtime's conversation lock.
                    // The bridge and settlement callback separately capture current online authority.
                    return RecoveryTaskEvidence.allSettled(workspace, session.actor, task, items, processing);
                }
                public CompletableFuture<AgentTool.Result> execute(AgentTool tool, AgentTool.Context ctx, JsonObject args,
                                                                  String approval, ToolCall call) {
                    unexpectedRuntimeDispatches.incrementAndGet();
                    return CompletableFuture.failedFuture(new IllegalStateException("RECOVERY_MUST_NOT_REPLAY_RUNTIME_TOOL"));
                }
                public void changed(WorkspaceManager.Workspace workspace, AgentTask task, String notice) {}
            };
            try (var runtime = new AgentRuntime(manager, scheduler, gateway, registry,
                    new ContextCompactor(new ContextCompactor.Settings(32000, 512, 1024, 512, .75, 2), "native-recovery-validation"),
                    new AgentRuntime.Limits(8, 8, 200000, 120000, 512, true, false), environment, new SecretFilter(List.of()))) {
                var recovery = new RecoveryWorkbenchService(items, processing, dispatcher, registry, bridge, inventory.nativeSaves(),
                        currentActor, name -> registry.get(name) != null, (workspace, task) -> {
                    if (!RecoveryTaskEvidence.allSettled(workspace, currentActor.apply(workspace.owner()), task, items, processing)) return;
                    try { runtime.acknowledgeRecovery(currentActor.apply(workspace.owner()), task); acknowledged.incrementAndGet(); }
                    catch (Exception failure) { callbackFailure.compareAndSet(null, failure); }
                });
                try {
                    var ctx = context(session, "Recover only actual native inputs from a cancelled accepted drain");
                    var plan = plan(ctx, true, null); UUID conversation = session.workspace.primaryConversationId();
                    var args = new JsonObject(); args.addProperty("planId", plan.id().toString()); args.addProperty("planHash", plan.hash());
                    var call = new ToolCall(UUID.randomUUID().toString(), "fluid_processing_execute", args.toString());
                    var executeTool = registry.get(call.name()); ToolPolicyEngine.arguments(call, executeTool.descriptor().inputSchema());
                    var task = session.workspace.conversation().task(ctx.taskId()).progress(1, 0, 0, 0, List.of(call), 0,
                            ToolPolicyEngine.digest(session.actor.owner(), call), true, true).update(AgentTask.State.EXECUTING, "NATIVE_VALIDATION_DISPATCH");
                    var start = new JsonObject(); start.addProperty("name", call.name()); start.addProperty("risk", executeTool.descriptor().risk().name());
                    start.addProperty("inputDigest", ToolPolicyEngine.digest(session.actor.owner(), call));
                    session.workspace.conversation().append(List.of(Draft.message(task.id(), new ModelMessage("assistant", "", List.of(call), null, null), null),
                            new Draft(task.id(), "tool_start", null, start, call.id()), Draft.checkpoint(task)));
                    nativeCancellationObserved = false; cancelDuringNative = ctx.control();
                    var executing = bridge.execute(executeTool, ctx, args, task.approvalDigest(), call);
                    expectFailure(() -> executing.get(10, TimeUnit.SECONDS), "CANCELLED"); checkHook();
                    check(nativeCancellationObserved && main(session::amount) == 3000
                            && main(() -> count(session.player, Items.BUCKET)) == 0 && main(() -> count(session.player, Items.WATER_BUCKET)) == 0,
                            "workbench-fixture-retains-actual-cancelled-native-input-custody");
                    var stopped = session.journal.get(session.actor, plan.id());
                    check(stopped.returnMode() == MixedResourceJournal.ReturnMode.INPUTS && !session.journal.settled(session.actor, plan.id()),
                            "workbench-recovery-is-derived-from-real-stopped-unsettled-operation");
                    var checkpoint = new JsonObject(); checkpoint.addProperty("operationId", plan.id().toString());
                    checkpoint.addProperty("stoppedReason", ctx.control().stopReason());
                    checkpoint.add("materialEvidence", AiJson.GSON.toJsonTree(items.get(session.actor, plan.id())));
                    checkpoint.add("mixedMaterialEvidence", AiJson.GSON.toJsonTree(stopped));
                    task = task.update(AgentTask.State.RECOVERY_REQUIRED, "CANCELLED_NATIVE_CUSTODY_REQUIRES_VERIFICATION");
                    session.workspace.conversation().append(List.of(new Draft(task.id(), "tool_checkpoint", null, checkpoint, call.id()), Draft.checkpoint(task)));
                    expectFailure(() -> runtime.acknowledgeRecovery(session.actor, ctx.taskId()), "RECOVERY_EVIDENCE_INCOMPLETE");
                    expectFailure(() -> runtime.control(session.actor, ctx.taskId(), "resume"), "RECOVERY_REQUIRES_WORLD_VERIFICATION");
                    save(session.player);
                    await(() -> items.get(session.actor, plan.id()).inputsSaved()
                            && plan.fluidClaims().stream().allMatch(claim -> fluids.get(session.actor, claim.operationId()).inputsSaved()),
                            "workbench-cancelled-inputs-have-real-positive-native-save-proof");
                    var view = recovery.view(session.workspace, session.actor, ctx.taskId(), 0);
                    check(view.get("total").getAsInt() == 1 && view.getAsJsonArray("operations").get(0).getAsJsonObject()
                            .get("operationId").getAsString().equals(plan.id().toString())
                            && !view.toString().contains(other.actor.owner()), "workbench-view-is-bound-to-real-owner-task-and-operation");
                    expectFailure(() -> recovery.view(session.workspace, other.actor, ctx.taskId(), 0), "WORKSPACE_ACCESS_DENIED");
                    UUID connection = UUID.randomUUID();
                    if (session.journal.get(session.actor, plan.id()).state() == MixedResourceJournal.State.RECOVERY_REQUIRED) {
                        var review = recovery.preview(session.workspace, session.actor, connection, ctx.taskId(),
                                RecoveryWorkbenchService.Action.MIXED_RECONCILE, plan.id(), null);
                        var queued = recovery.confirm(session.workspace, session.actor, connection,
                                UUID.fromString(review.get("token").getAsString()), review.get("digest").getAsString());
                        UUID action = UUID.fromString(queued.get("actionId").getAsString());
                        await(() -> recoveryEvent(session, "workbench_recovery_result", action).isPresent(), "workbench-real-reconcile-result-recorded");
                        check(recoveryEvent(session, "workbench_recovery_result", action).orElseThrow().get("status").getAsString().equals("RECONCILED_CUSTODY_ONLY")
                                && main(session::amount) == 3000 && main(() -> count(session.player, Items.BUCKET)) == 0,
                                "workbench-reconcile-does-not-repeat-native-drain-or-invent-return");
                    }
                    var preview = recovery.preview(session.workspace, session.actor, connection, ctx.taskId(),
                            RecoveryWorkbenchService.Action.MIXED_RETURN, plan.id(), session.grant.id());
                    UUID token = UUID.fromString(preview.get("token").getAsString()); String digest = preview.get("digest").getAsString();
                    expectFailure(() -> recovery.confirm(session.workspace, other.actor, connection, token, digest), "WORKSPACE_ACCESS_DENIED");
                    expectFailure(() -> recovery.confirm(session.workspace, session.actor, UUID.randomUUID(), token, digest), "RECOVERY_PREVIEW_STALE");
                    expectFailure(() -> recovery.confirm(session.workspace, session.actor, connection, UUID.randomUUID(), digest), "RECOVERY_PREVIEW_STALE");
                    expectFailure(() -> recovery.confirm(session.workspace, session.actor, connection, token, AiJson.digest("changed-recovery-confirm")), "RECOVERY_PREVIEW_STALE");
                    expectFailure(() -> runtime.acknowledgeRecovery(session.actor, ctx.taskId()), "RECOVERY_EVIDENCE_INCOMPLETE");
                    var queued = recovery.confirm(session.workspace, session.actor, connection, token, digest);
                    UUID action = UUID.fromString(queued.get("actionId").getAsString());
                    check(queued.get("queued").getAsBoolean() && queued.get("taskId").getAsString().equals(ctx.taskId().toString())
                            && queued.get("operationId").getAsString().equals(plan.id().toString()), "workbench-confirm-only-queues-original-owned-recovery-action");
                    await(() -> recoveryEvent(session, "workbench_recovery_wait", action).filter(event ->
                            event.get("status").getAsString().equals("NATIVE_SAVE_PENDING")).isPresent(), "workbench-native-return-awaits-real-output-save");
                    check(main(session::amount) == 4000 && main(() -> count(session.player, Items.BUCKET)) == 1
                            && main(() -> count(session.player, Items.WATER_BUCKET)) == 0, "workbench-return-restores-only-extracted-real-item-and-fluid");
                    check(recoveryEvent(session, "workbench_recovery_result", action).isEmpty()
                            && session.workspace.conversation().task(ctx.taskId()).state() == AgentTask.State.RECOVERY_REQUIRED,
                            "queued-or-unsaved-return-cannot-unblock-original-recovery-task");
                    expectFailure(() -> runtime.acknowledgeRecovery(session.actor, ctx.taskId()), "RECOVERY_EVIDENCE_INCOMPLETE");
                    save(session.player);
                    await(() -> recoveryEvent(session, "workbench_recovery_result", action).isPresent(), "workbench-real-saved-return-result-recorded");
                    await(() -> callbackFailure.get() != null || acknowledged.get() == 1
                            && session.workspace.conversation().task(ctx.taskId()).state() == AgentTask.State.FAILED,
                            "workbench-positive-proof-reaches-real-runtime-ack");
                    var receipt = recoveryEvent(session, "workbench_recovery_result", action).orElseThrow();
                    var done = session.workspace.conversation().task(ctx.taskId());
                    check(receipt.get("private").getAsBoolean() && receipt.get("status").getAsString().equals("RETURNED_AND_SAVED")
                            && receipt.has("materialEvidence") && receipt.has("mixedMaterialEvidence") && session.journal.settled(session.actor, plan.id()),
                            "workbench-private-result-carries-real-saved-terminal-ledger-evidence");
                    check(done.reason().equals("RECOVERY_SETTLED_REPLAN_REQUIRED") && done.nextCall() == done.pending().size()
                            && conversation.equals(session.workspace.primaryConversationId()) && callbackFailure.get() == null && acknowledged.get() == 1,
                            "actual-runtime-ack-closes-original-call-on-positive-shared-verifier-proof");
                    expectFailure(() -> recovery.confirm(session.workspace, session.actor, connection, token, digest), "RECOVERY_PREVIEW_STALE");
                    check(main(session::amount) == 4000 && main(() -> count(session.player, Items.BUCKET)) == 1
                            && unexpectedModelCalls.get() == 0 && unexpectedRuntimeDispatches.get() == 0,
                            "workbench-recovery-and-runtime-ack-never-replay-drain-or-request-model");
                    workbenchRecoveryRan = true;
                } finally {
                    recovery.finalizePendingClose();
                    if (!recovery.awaitStopped(10000)) throw new IllegalStateException("VALIDATION_RECOVERY_SHUTDOWN_TIMEOUT");
                    runtime.close();
                    if (!runtime.awaitStopped(10000)) throw new IllegalStateException("VALIDATION_RUNTIME_SHUTDOWN_TIMEOUT");
                    runtime.finalizePendingClose();
                }
            }
        } finally {
            cancelDuringNative = null; toolIo.shutdown();
            if (!toolIo.awaitTermination(10000, TimeUnit.MILLISECONDS)) throw new IllegalStateException("VALIDATION_RECOVERY_TOOLS_SHUTDOWN_TIMEOUT");
            main(() -> { server.getPlayerList().remove(session.player); return true; });
        }
    }
    private Optional<JsonObject> recoveryEvent(Session session, String kind, UUID action) {
        return session.workspace.conversation().getSnapshot(0).events().stream().filter(event -> event.kind().equals(kind)
                && event.data() != null && event.data().has("actionId") && event.data().get("actionId").getAsString().equals(action.toString()))
                .map(event -> event.data()).reduce((previous, current) -> current);
    }

    private record PortableInput(ItemStack host, IItemHandlerModifiable contents, MaterialSourceGrants.Grant grant) {}
    private PortableInput portableInput(Session session, MaterialStack input) throws Exception {
        var host = new ItemStack(nativeItem("diamond_backpack"));
        Class<?> type = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
        Object wrapper = type.getMethod("fromStack", ItemStack.class).invoke(null, host);
        type.getMethod("setContentsUuid", UUID.class).invoke(wrapper, UUID.randomUUID());
        var contents = (IItemHandlerModifiable) type.getMethod("getInventoryHandler").invoke(wrapper);
        contents.setStackInSlot(0, ItemSnapshotCodec.restore(input, session.player.registryAccess()));
        session.player.getInventory().setItem(1, host);
        var selection = inventory.selectBackpack(session.actor, "inventory/1"); var grants = inventory.grants(session.actor);
        var preview = grants.preview(session.actor, selection.candidate());
        return new PortableInput(host, contents, grants.confirm(session.actor, preview.id(), preview.digest(), inventory.confirmSelection(session.actor, selection)));
    }
    private ToolCall pendingNativeCall(Session session, AgentTool.Context ctx, FluidProcessingPlan plan, ToolRegistry registry) throws Exception {
        var args = new JsonObject(); args.addProperty("planId", plan.id().toString()); args.addProperty("planHash", plan.hash());
        var call = new ToolCall(UUID.randomUUID().toString(), "fluid_processing_execute", args.toString());
        var tool = registry.get(call.name()); ToolPolicyEngine.arguments(call, tool.descriptor().inputSchema());
        var task = session.workspace.conversation().task(ctx.taskId()).progress(1, 0, 0, 0, List.of(call), 0,
                ToolPolicyEngine.digest(session.actor.owner(), call), true, true).update(AgentTask.State.EXECUTING, "NATIVE_VALIDATION_DISPATCH");
        var start = new JsonObject(); start.addProperty("name", call.name()); start.addProperty("risk", tool.descriptor().risk().name());
        start.addProperty("inputDigest", task.approvalDigest());
        session.workspace.conversation().append(List.of(Draft.message(task.id(), new ModelMessage("assistant", "", List.of(call), null, null), null),
                new Draft(task.id(), "tool_start", null, start, call.id()), Draft.checkpoint(task)));
        return call;
    }
    private void controllerPartialResume(boolean reloadWhileWaiting) throws Exception {
        var session = main(() -> new Session("partial_resume_native")); var localGeneration = new AtomicLong(1);
        main(() -> { session.fill(4000); session.player.getInventory().setItem(1, new ItemStack(Items.BUCKET, 2)); return true; });
        FluidProcessingPlan plan;
        try {
            var initial = main(() -> new FluidProcessingService(server, inventory, tanks, items, fluids, dispatcher, localGeneration::get));
            try (var old = new ResumeWorkbench(session, initial)) {
                var ctx = context(session, "Resume exact remaining claims after native partial extraction and controller reopen");
                plan = initial.plan(ctx, new FluidProcessingPlanner.Request("", FluidProcessingAdapters.BUCKET_FILL, "minecraft:water", "minecraft:bucket", 2, null));
                var call = pendingNativeCall(session, ctx, plan, old.registry);
                var journal = initial.prepare(session.workspace);
                journal.create(session.actor, new MixedResourceJournal.Definition(plan.id(), plan.taskId(), plan.hash(), plan.itemInputs(),
                        plan.fluidClaims().stream().map(claim -> new MixedResourceJournal.FluidInput(claim.operationId(), claim.amount())).toList(),
                        plan.itemOutputs(), plan.fluidOutputs(), plan.evidence()));
                items.reserve(session.actor, plan.taskId(), plan.id(), plan.hash(), plan.sources(), plan.needs());
                for (var claim : plan.fluidClaims()) fluids.reserve(session.actor, plan.taskId(), claim.operationId(), plan.hash(), claim.source(), claim.amount());
                var stock = plan.sources().stream().filter(source -> source.key().equals(plan.needs().getFirst().stockKey())).findFirst().orElseThrow();
                var itemInput = dispatcher.inspectOwned(ctx, () -> inventory.prepareExtraction(session.actor, stock, 1));
                items.beginExtraction(session.actor, plan.id(), 0, itemInput.before(), itemInput.poolUnits(), itemInput.witness(), 1);
                dispatcher.commitOwned(ctx, () -> inventory.extract(itemInput, 1), actual -> {
                    var observed = items.extractionEvidence(session.actor, plan.id(), itemInput.witness(), actual.actual(), actual.poolUnitsAfter());
                    inventory.nativeSaves().record(items, session.actor, plan.id(), "extract", itemInput.witness(), actual.save()); return observed;
                });
                var claim = plan.fluidClaims().getFirst();
                var fluidInput = dispatcher.inspectOwned(ctx, () -> tanks.prepare(session.actor, claim.source(), claim.amount().withAmount(500), false));
                String witness = UUID.randomUUID().toString(); fluids.begin(session.actor, claim.operationId(), witness, fluidInput.source(), fluidInput.amount(), false);
                dispatcher.commitOwned(ctx, () -> tanks.perform(fluidInput), actual -> {
                    fluids.observed(session.actor, claim.operationId(), witness, actual);
                    tanks.nativeSaves().record(claim.operationId(), actual.nativeSave(), proof -> fluids.nativeSaved(session.actor, claim.operationId(), witness, proof));
                    return items.get(session.actor, plan.id());
                });
                check(items.get(session.actor, plan.id()).claims().getFirst().remaining() == 1
                        && fluids.get(session.actor, claim.operationId()).escrowMb() == 500
                        && main(session::amount) == 3500 && main(() -> count(session.player, Items.BUCKET)) == 1,
                        "partial-resume-real-native-prefix-is-one-bucket-and-500-mB");
                save(session.player);
                inventory.nativeSaves().awaitJournalled(plan.id()).toCompletableFuture().get(10, TimeUnit.SECONDS);
                tanks.nativeSaves().awaitJournalled(claim.operationId()).toCompletableFuture().get(10, TimeUnit.SECONDS);
                var task = session.workspace.conversation().task(ctx.taskId()).update(AgentTask.State.RECOVERY_REQUIRED, "SAVED_PARTIAL_NATIVE_INPUTS");
                var checkpoint = new JsonObject(); checkpoint.addProperty("reason", "PARTIAL_NATIVE_FIXTURE_CONTROLLER_REOPEN");
                checkpoint.addProperty("scope", "Real production transfer primitives deliberately stop after a known prefix; not a simulated full execute result");
                session.workspace.conversation().append(List.of(new Draft(ctx.taskId(), "tool_wait_external", null, checkpoint, call.id()), Draft.checkpoint(task)));
            }
            localGeneration.incrementAndGet();
            var reopened = main(() -> new FluidProcessingService(server, inventory, tanks, items, fluids, dispatcher, localGeneration::get));
            try (var current = new ResumeWorkbench(session, reopened)) {
                var journal = reopened.prepare(session.workspace);
                check(journal.get(session.actor, plan.id()).state() == MixedResourceJournal.State.RECOVERY_REQUIRED,
                        "partial-resume-controller-reopen-quarantines-prefix-before-new-approval");
                var reconciled = current.queue(plan.taskId(), RecoveryWorkbenchService.Action.MIXED_RECONCILE, plan.id(), null);
                UUID reconcileId = UUID.fromString(reconciled.get("actionId").getAsString());
                await(() -> recoveryEvent(session, "workbench_recovery_result", reconcileId).isPresent(), "partial-resume-positive-prefix-reconciled");
                var fresh = new AgentTool.Context(session.workspace, session.actor, new TaskControl(120000), plan.taskId());
                var review = reopened.resumePreview(fresh, plan.id(), null);
                check(review.requiresExtraction() && review.itemCustody().claims().getFirst().remaining() == 1
                        && review.fluidCustody().stream().mapToInt(fluid -> fluid.requested().millibuckets() - fluid.extracted()).sum() == 1500
                        && items.get(session.actor, plan.id()).products().isEmpty() && main(session::amount) == 3500,
                        "partial-resume-new-preview-separates-saved-prefix-from-exact-missing-claims");
                var preview = current.preview(plan.taskId(), RecoveryWorkbenchService.Action.MIXED_RESUME, plan.id(), null);
                var queued = current.confirm(preview); UUID initialAction = UUID.fromString(queued.get("actionId").getAsString());
                expectFailure(() -> current.confirm(preview), "RECOVERY_PREVIEW_STALE");
                await(() -> items.get(session.actor, plan.id()).state() == MaterialLedger.State.ESCROWED
                        && plan.fluidClaims().stream().allMatch(input -> fluids.get(session.actor, input.operationId()).extracted() == input.amount().millibuckets()),
                        "partial-resume-new-approval-extracts-only-one-bucket-and-1500-mB");
                check(main(session::amount) == 2000 && main(() -> count(session.player, Items.BUCKET)) == 0
                        && main(() -> count(session.player, Items.WATER_BUCKET)) == 0 && items.get(session.actor, plan.id()).products().isEmpty()
                        && plan.fluidClaims().stream().allMatch(input -> fluids.get(session.actor, input.operationId()).consumedMb() == 0),
                        "partial-resume-newly-extracted-remainder-also-waits-for-real-save-before-output");
                await(() -> recoveryEvent(session, "workbench_recovery_wait", initialAction)
                        .filter(event -> event.get("status").getAsString().equals("WAITING_INPUT_SAVE")).isPresent(),
                        "partial-resume-new-approval-releases-worker-while-missing-input-save-is-pending");
                if (reloadWhileWaiting) localGeneration.incrementAndGet();
                save(session.player);
                UUID finalAction;
                if (reloadWhileWaiting) {
                    await(() -> recoveryEvent(session, "workbench_recovery_result", initialAction).isPresent(),
                            "partial-resume-reload-during-save-wait-records-actual-refusal");
                    var failed = recoveryEvent(session, "workbench_recovery_result", initialAction).orElseThrow();
                    check(!failed.getAsJsonObject("result").get("success").getAsBoolean()
                            && failed.getAsJsonObject("result").get("errorCode").getAsString().equals("FLUID_RECIPES_RELOADED")
                            && items.get(session.actor, plan.id()).products().isEmpty()
                            && main(session::amount) == 2000 && main(() -> count(session.player, Items.WATER_BUCKET)) == 0
                            && journal.get(session.actor, plan.id()).returnMode() == MixedResourceJournal.ReturnMode.NONE,
                            "partial-resume-reload-cannot-use-old-approval-or-refund-already-saved-inputs");
                    var rebound = reopened.resumePreview(fresh, plan.id(), null);
                    check(!rebound.requiresExtraction(), "partial-resume-after-reload-new-preview-keeps-full-saved-custody");
                    var retry = current.queue(plan.taskId(), RecoveryWorkbenchService.Action.MIXED_RESUME, plan.id(), null);
                    finalAction = UUID.fromString(retry.get("actionId").getAsString());
                } else finalAction = initialAction;
                UUID action = finalAction;
                await(() -> recoveryEvent(session, "workbench_recovery_wait", action).filter(event -> event.get("status").getAsString().equals("NATIVE_SAVE_PENDING")).isPresent(),
                        "partial-resume-continuation-crosses-real-bridge-and-awaits-output-save");
                check(main(() -> count(session.player, Items.WATER_BUCKET)) == 2 && main(session::amount) == 2000
                        && items.get(session.actor, plan.id()).claims().stream().mapToInt(MaterialLedger.Claim::extracted).sum() == 2
                        && plan.fluidClaims().stream().mapToInt(input -> fluids.get(session.actor, input.operationId()).consumedMb()).sum() == 2000,
                        "partial-resume-original-operation-consumes-total-exact-inputs-once");
                save(session.player);
                await(() -> recoveryEvent(session, "workbench_recovery_result", action).isPresent(), "partial-resume-real-saved-terminal-result-recorded");
                await(() -> current.acknowledged.get() == 1 || current.callbackFailure.get() != null, "partial-resume-original-task-reaches-shared-verifier");
                check(recoveryEvent(session, "workbench_recovery_result", action).orElseThrow().get("status").getAsString().equals("RESUMED_AND_SAVED")
                        && reopened.operationSettled(session.workspace, session.actor, plan.id()) && current.callbackFailure.get() == null
                        && current.unexpectedCalls.get() == 0 && items.list(session.actor).size() == 1,
                        "partial-resume-completes-through-workbench-without-model-or-old-tool-replay");
                controllerResumeCases.add(reloadWhileWaiting ? "partial-bucket-fill-reload-during-save-wait" : "partial-bucket-fill-native-prefix");
            }
        } finally { main(() -> { server.getPlayerList().remove(session.player); return true; }); }
    }

    private void controllerResume(boolean iron, boolean removeRecipe) throws Exception {
        String label = iron ? removeRecipe ? "iron-removed-recipe" : "iron-soak" : "bucket-empty";
        var session = main(() -> new Session("fluid_resume_native")); var localGeneration = new AtomicLong(1);
        var fixture = iron ? main(() -> ironFixture("minecraft:alchemist_cauldron/soak_crying_obsidian", false))
                : main(() -> new IronFixture("", ItemSnapshotCodec.capture(new ItemStack(Items.WATER_BUCKET), session.player.registryAccess()),
                        FluidAmount.EMPTY, List.of(ItemSnapshotCodec.capture(new ItemStack(Items.BUCKET), session.player.registryAccess())),
                        List.of(FluidSnapshotCodec.capture(new FluidStack(Fluids.WATER, 1000), session.player.registryAccess()))));
        var origin = main(() -> {
            var selected = portableInput(session, fixture.input());
            if (!fixture.requiredFluid().empty()) {
                var fluid = FluidSnapshotCodec.restore(fixture.requiredFluid(), session.player.registryAccess()); int left = fluid.getAmount();
                for (int attempt = 0; left > 0 && attempt < 128; attempt++) {
                    int inserted = session.handler().fill(fluid.copyWithAmount(left), IFluidHandler.FluidAction.EXECUTE);
                    if (inserted <= 0 || inserted > left) throw new IllegalStateException("RESUME_NATIVE_FIXTURE_FILL_REJECTED"); left -= inserted;
                }
                if (left != 0) throw new IllegalStateException("RESUME_NATIVE_FIXTURE_FILL_LIMIT");
            }
            return selected;
        });
        FluidProcessingPlan plan;
        try {
            var initial = main(() -> new FluidProcessingService(server, inventory, tanks, items, fluids, dispatcher, localGeneration::get));
            try (var old = new ResumeWorkbench(session, initial)) {
                var ctx = context(session, "Continue only complete saved custody after controller reopen: " + label);
                plan = initial.plan(ctx, new FluidProcessingPlanner.Request(fixture.recipeId(), iron ? "" : FluidProcessingAdapters.BUCKET_EMPTY,
                        "", iron ? "" : "minecraft:water_bucket", 1, iron ? null : session.grant.id()));
                check(plan.sources().stream().allMatch(source -> source.sourceId().equals(origin.grant().id().toString())),
                        "controller-resume-" + label + "-input-is-from-real-independent-authorized-backpack");
                var call = pendingNativeCall(session, ctx, plan, old.registry); AgentTool.Deferred wait;
                try {
                    old.bridge.execute(old.registry.get(call.name()), ctx, AiJson.object(call.arguments(), 65536, 24),
                            ToolPolicyEngine.digest(session.actor.owner(), call), call).get(10, TimeUnit.SECONDS);
                    throw new IllegalStateException("RESUME_FIXTURE_REQUIRES_REAL_INPUT_SAVE_WAIT");
                } catch (ExecutionException failure) {
                    Throwable actual = failure; while (actual.getCause() != null && actual.getCause() != actual) actual = actual.getCause();
                    if (!(actual instanceof AgentTool.Deferred deferred)) throw failure; wait = deferred;
                }
                var pending = initial.prepare(session.workspace).get(session.actor, plan.id());
                check(wait.reason().equals("WAITING_INPUT_SAVE") && pending.state() == MixedResourceJournal.State.INPUTS_PENDING
                        && pending.returnMode() == MixedResourceJournal.ReturnMode.NONE && items.get(session.actor, plan.id()).products().isEmpty()
                        && main(() -> origin.contents().getStackInSlot(0).isEmpty()) && main(session::amount) == 0,
                        "controller-resume-" + label + "-old-wait-has-real-full-inputs-and-no-output-or-return-fence");
                ctx.control().stop("SHUTDOWN");
                var checkpoint = new JsonObject(); checkpoint.addProperty("reason", wait.reason()); checkpoint.add("checkpoint", wait.checkpoint());
                checkpoint.addProperty("continuationDispatch", "INHIBITED_CONTROLLER_REOPEN");
                var task = session.workspace.conversation().task(ctx.taskId()).update(AgentTask.State.RECOVERY_REQUIRED, "CONTROLLER_REOPEN_REQUIRES_NATIVE_VERIFICATION");
                session.workspace.conversation().append(List.of(new Draft(ctx.taskId(), "tool_wait_external", null, checkpoint, call.id()), Draft.checkpoint(task)));
                save(session.player); wait.readiness().toCompletableFuture().get(10, TimeUnit.SECONDS);
                check(items.get(session.actor, plan.id()).inputsSaved()
                        && plan.fluidClaims().stream().allMatch(claim -> fluids.get(session.actor, claim.operationId()).inputsSaved()),
                        "controller-resume-" + label + "-old-inputs-have-positive-native-file-receipts");
            }
            var savedItem = items.get(session.actor, plan.id());
            var savedDrains = new LinkedHashMap<UUID, List<FluidLedger.Effect>>();
            for (var claim : plan.fluidClaims()) savedDrains.put(claim.operationId(), fluids.get(session.actor, claim.operationId()).effects());
            inventory.grants(session.actor).revoke(session.actor, origin.grant().id());
            session.grants.revoke(session.actor, session.grant.id());
            main(() -> {
                session.player.getInventory().setItem(1, ItemStack.EMPTY);
                if (iron) session.player.getInventory().setItem(0, ItemStack.EMPTY);
                else session.grant = session.authorize();
                return true;
            });
            localGeneration.incrementAndGet();
            var reopened = main(() -> new FluidProcessingService(server, inventory, tanks, items, fluids, dispatcher, localGeneration::get));
            try (var current = new ResumeWorkbench(session, reopened)) {
                var journal = reopened.prepare(session.workspace);
                check(journal.get(session.actor, plan.id()).state() == MixedResourceJournal.State.RECOVERY_REQUIRED,
                        "controller-resume-" + label + "-reopen-requires-real-journal-reconciliation");
                var reconciled = current.queue(plan.taskId(), RecoveryWorkbenchService.Action.MIXED_RECONCILE, plan.id(), null);
                UUID reconcileId = UUID.fromString(reconciled.get("actionId").getAsString());
                await(() -> recoveryEvent(session, "workbench_recovery_result", reconcileId).isPresent(), "controller-resume-" + label + "-reconcile-result-recorded");
                check(recoveryEvent(session, "workbench_recovery_result", reconcileId).orElseThrow().get("status").getAsString().equals("RECONCILED_CUSTODY_ONLY")
                        && journal.get(session.actor, plan.id()).state() == MixedResourceJournal.State.INPUTS_PENDING
                        && journal.get(session.actor, plan.id()).returnMode() == MixedResourceJournal.ReturnMode.NONE
                        && items.get(session.actor, plan.id()).products().isEmpty() && main(session::amount) == 0,
                        "controller-resume-" + label + "-reconcile-restores-inputs-without-committing-or-producing");
                var fresh = new AgentTool.Context(session.workspace, session.actor, new TaskControl(120000), plan.taskId());
                UUID target = iron ? null : session.grant.id();
                var review = reopened.resumePreview(fresh, plan.id(), target);
                check(!review.executionEpoch().equals(plan.executionEpoch()) && review.recipeGeneration() == localGeneration.get()
                        && review.originalPlan().equals(plan) && review.evaluations().equals(plan.evaluations()),
                        "controller-resume-" + label + "-new-epoch-and-generation-keep-original-fixed-native-contract");
                var changedGeneration = current.preview(plan.taskId(), RecoveryWorkbenchService.Action.MIXED_RESUME, plan.id(), target);
                localGeneration.incrementAndGet();
                expectFailure(() -> current.confirm(changedGeneration), "FLUID_RECIPES_RELOADED");
                var changedPreference = current.preview(plan.taskId(), RecoveryWorkbenchService.Action.MIXED_RESUME, plan.id(), target);
                var preferences = inventory.preferences(session.actor); var oldPreferences = preferences.view(session.actor);
                String protectedItem = AiJson.object(fixture.input().itemJson(), 1048576, 32).get("id").getAsString();
                preferences.save(session.actor, oldPreferences.revision(), Set.of(protectedItem));
                try { expectFailure(() -> current.confirm(changedPreference), "MATERIAL_PROTECTED_BY_OWNER"); }
                finally { preferences.save(session.actor, preferences.view(session.actor).revision(), oldPreferences.protectedItems()); }
                check(items.get(session.actor, plan.id()).products().isEmpty() && main(session::amount) == 0,
                        "controller-resume-" + label + "-changed-generation-or-protection-rejects-before-output");
                expectFailure(() -> current.runtime.acknowledgeRecovery(session.actor, plan.taskId()), "RECOVERY_EVIDENCE_INCOMPLETE");
                JsonObject queued; String expectedStatus;
                if (removeRecipe) {
                    var preview = current.preview(plan.taskId(), RecoveryWorkbenchService.Action.MIXED_RESUME, plan.id(), null);
                    var recipes = main(() -> List.copyOf(server.getRecipeManager().getRecipes()));
                    try {
                        main(() -> { server.getRecipeManager().replaceRecipes(recipes.stream().filter(holder -> !holder.id().toString().equals(plan.recipeId())).toList()); return true; });
                        queued = current.confirm(preview); UUID action = UUID.fromString(queued.get("actionId").getAsString());
                        await(() -> recoveryEvent(session, "workbench_recovery_result", action).isPresent(), "controller-resume-removed-native-recipe-rejection-recorded");
                        var failed = recoveryEvent(session, "workbench_recovery_result", action).orElseThrow();
                        check(failed.has("result") && !failed.getAsJsonObject("result").get("success").getAsBoolean()
                                && failed.getAsJsonObject("result").get("errorCode").getAsString().equals("FLUID_RECIPE_NOT_FOUND")
                                && items.get(session.actor, plan.id()).products().isEmpty()
                                && plan.fluidClaims().stream().allMatch(claim -> fluids.get(session.actor, claim.operationId()).consumedMb() == 0),
                                "controller-resume-removed-native-holder-cannot-commit-approved-old-preview");
                    } finally { main(() -> { server.getRecipeManager().replaceRecipes(recipes); return true; }); }
                    var returnJournal = reopened.prepare(session.workspace);
                    check(returnJournal.get(session.actor, plan.id()).returnMode() == MixedResourceJournal.ReturnMode.NONE,
                            "removed-recipe-failure-preserves-inputs-without-pretending-the-owner-cancelled");
                    main(() -> { session.player.getInventory().setItem(0, session.backpack); session.grant = session.authorize(); return true; });
                    queued = current.queue(plan.taskId(), RecoveryWorkbenchService.Action.MIXED_RETURN, plan.id(), session.grant.id());
                    await(() -> returnJournal.get(session.actor, plan.id()).returnMode() != MixedResourceJournal.ReturnMode.NONE,
                            "approved-return-establishes-the-real-cancellation-fence");
                    expectFailure(() -> reopened.resumePreview(fresh, plan.id(), null), "FLUID_RESUME_CANCELLED_COMPLETE_APPROVED_RETURN");
                    expectedStatus = "RETURNED_AND_SAVED";
                } else {
                    var preview = current.preview(plan.taskId(), RecoveryWorkbenchService.Action.MIXED_RESUME, plan.id(), target);
                    UUID token = UUID.fromString(preview.get("token").getAsString()); String digest = preview.get("digest").getAsString();
                    expectFailure(() -> current.recovery.confirm(session.workspace, session.actor, UUID.randomUUID(), token, digest), "RECOVERY_PREVIEW_STALE");
                    queued = current.confirm(preview); expectedStatus = "RESUMED_AND_SAVED";
                    expectFailure(() -> current.confirm(preview), "RECOVERY_PREVIEW_STALE");
                }
                UUID action = UUID.fromString(queued.get("actionId").getAsString());
                check(queued.get("queued").getAsBoolean() && queued.get("operationId").getAsString().equals(plan.id().toString()),
                        "controller-resume-" + label + "-fresh-approval-only-queues-original-operation");
                await(() -> recoveryEvent(session, "workbench_recovery_wait", action).filter(event -> event.get("status").getAsString().equals("NATIVE_SAVE_PENDING")).isPresent(),
                        "controller-resume-" + label + "-actual-output-awaits-native-save");
                if (!removeRecipe) {
                    var nativeItems = main(() -> {
                        var stacks = new ArrayList<MaterialStack>();
                        for (int slot = iron ? 0 : 1; slot < session.player.getInventory().getContainerSize(); slot++) {
                            var stack = session.player.getInventory().getItem(slot); if (!stack.isEmpty())
                                stacks.add(ItemSnapshotCodec.capture(stack, session.player.registryAccess()));
                        }
                        return itemTotals(stacks);
                    });
                    var nativeFluidOutputs = main(() -> fluidTotals(FluidTankSnapshot.capture(session.handler(), session.player.registryAccess()).tanks()
                            .stream().map(FluidTankSnapshot.Tank::fluid).filter(fluid -> !fluid.empty()).toList()));
                    check(nativeItems.equals(itemTotals(fixture.itemOutputs())) && nativeFluidOutputs.equals(fluidTotals(fixture.fluidOutputs())),
                            "controller-resume-" + label + "-native-output-conserved-with-original-sources-revoked-and-removed");
                } else {
                    var returned = main(() -> {
                        var stacks = new ArrayList<MaterialStack>();
                        for (int slot = 1; slot < session.player.getInventory().getContainerSize(); slot++) {
                            var stack = session.player.getInventory().getItem(slot);
                            if (!stack.isEmpty()) stacks.add(ItemSnapshotCodec.capture(stack, session.player.registryAccess()));
                        }
                        return itemTotals(stacks);
                    });
                    var returnedFluids = main(() -> fluidTotals(FluidTankSnapshot.capture(session.handler(), session.player.registryAccess()).tanks()
                            .stream().map(FluidTankSnapshot.Tank::fluid).filter(fluid -> !fluid.empty()).toList()));
                    check(returned.equals(itemTotals(List.of(fixture.input()))) && returnedFluids.equals(fluidTotals(List.of(fixture.requiredFluid()))),
                            "controller-resume-removed-recipe-returns-only-original-real-item-and-raw-fluid");
                }
                check(recoveryEvent(session, "workbench_recovery_result", action).isEmpty()
                        && session.workspace.conversation().task(plan.taskId()).state() == AgentTask.State.RECOVERY_REQUIRED,
                        "controller-resume-" + label + "-unsaved-output-cannot-ack-original-task");
                save(session.player);
                await(() -> recoveryEvent(session, "workbench_recovery_result", action).isPresent(), "controller-resume-" + label + "-saved-terminal-result-recorded");
                await(() -> current.callbackFailure.get() != null || current.acknowledged.get() == 1
                        && session.workspace.conversation().task(plan.taskId()).state() == AgentTask.State.FAILED,
                        "controller-resume-" + label + "-shared-positive-verifier-reaches-real-runtime-ack");
                check(recoveryEvent(session, "workbench_recovery_result", action).orElseThrow().get("status").getAsString().equals(expectedStatus)
                        && reopened.operationSettled(session.workspace, session.actor, plan.id()) && current.callbackFailure.get() == null,
                        "controller-resume-" + label + "-only-real-saved-terminal-evidence-completes-action");
                var completedItem = items.get(session.actor, plan.id());
                check(completedItem.claims().equals(savedItem.claims())
                        && completedItem.nativeSaves().stream().filter(effect -> effect.kind().equals("extract")).toList().equals(savedItem.nativeSaves())
                        && savedDrains.entrySet().stream().allMatch(entry -> fluids.get(session.actor, entry.getKey()).effects().stream().filter(effect -> !effect.filling()).toList().equals(entry.getValue()))
                        && items.list(session.actor).size() == 1 && current.unexpectedCalls.get() == 0,
                        "controller-resume-" + label + "-never-reserves-extracts-drains-or-model-replays-old-inputs");
                controllerResumeCases.add(label);
            }
        } finally { main(() -> { server.getPlayerList().remove(session.player); return true; }); }
    }
    /** A controller reopen shares real saved native state and journals; it does not claim a JVM/power-loss test. */
    private final class ResumeWorkbench implements AutoCloseable {
        final Session session; final FluidProcessingService controller; final ToolRegistry registry = new ToolRegistry();
        final ThreadPoolExecutor io; final GameToolBridge bridge; final AgentRuntime runtime; final RecoveryWorkbenchService recovery;
        final UUID connection = UUID.randomUUID();
        final java.util.concurrent.atomic.AtomicInteger acknowledged = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger unexpectedCalls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicReference<Throwable> callbackFailure = new java.util.concurrent.atomic.AtomicReference<>();
        ResumeWorkbench(Session session, FluidProcessingService controller) {
            this.session = session; this.controller = controller; dispatcher.register(registry); controller.register(registry);
            io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16),
                    Thread.ofPlatform().daemon(true).name("fluid-resume-tools-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
            java.util.function.Function<String, Actor> resolver = identity -> {
                try { return main(() -> {
                    var player = server.getPlayerList().getPlayer(UUID.fromString(identity));
                    return player == null ? new Actor(identity, false, false, 0) : Actor.player(player.getUUID(), AiServerService.authenticated(player), true, 0);
                }); } catch (Exception failure) { throw new CompletionException(failure); }
            };
            bridge = new GameToolBridge(server, resolver, name -> registry.get(name) != null, () -> false, io);
            ModelGateway gateway = (request, control) -> { unexpectedCalls.incrementAndGet(); throw new IllegalStateException("RESUME_MUST_NOT_REQUEST_MODEL"); };
            var environment = new AgentRuntime.Environment() {
                public Actor actor(String identity) { return resolver.apply(identity); }
                public boolean enabled(String name) { return registry.get(name) != null; }
                public boolean recoverySettled(WorkspaceManager.Workspace workspace, UUID task) {
                    return RecoveryTaskEvidence.allSettled(workspace, session.actor, task, items, controller);
                }
                public CompletableFuture<AgentTool.Result> execute(AgentTool tool, AgentTool.Context context, JsonObject arguments, String approval, ToolCall call) {
                    unexpectedCalls.incrementAndGet(); return CompletableFuture.failedFuture(new IllegalStateException("RESUME_MUST_NOT_REPLAY_RUNTIME_TOOL"));
                }
                public void changed(WorkspaceManager.Workspace workspace, AgentTask task, String notice) {}
            };
            runtime = new AgentRuntime(manager, new TaskScheduler(1, 8, 16), gateway, registry,
                    new ContextCompactor(new ContextCompactor.Settings(32000, 512, 1024, 512, .75, 2), "native-controller-resume-validation"),
                    new AgentRuntime.Limits(8, 8, 200000, 120000, 512, true, false), environment, new SecretFilter(List.of()));
            recovery = new RecoveryWorkbenchService(items, controller, dispatcher, registry, bridge, inventory.nativeSaves(), resolver,
                    name -> registry.get(name) != null, (workspace, task) -> {
                if (!RecoveryTaskEvidence.allSettled(workspace, session.actor, task, items, controller)) return;
                try { runtime.acknowledgeRecovery(resolver.apply(workspace.owner()), task); acknowledged.incrementAndGet(); }
                catch (Exception failure) { callbackFailure.compareAndSet(null, failure); }
            });
        }
        JsonObject preview(UUID task, RecoveryWorkbenchService.Action action, UUID operation, UUID target) throws Exception {
            return recovery.preview(session.workspace, session.actor, connection, task, action, operation, target);
        }
        JsonObject confirm(JsonObject preview) throws Exception {
            return recovery.confirm(session.workspace, session.actor, connection, UUID.fromString(preview.get("token").getAsString()), preview.get("digest").getAsString());
        }
        JsonObject queue(UUID task, RecoveryWorkbenchService.Action action, UUID operation, UUID target) throws Exception {
            return confirm(preview(task, action, operation, target));
        }
        public void close() throws Exception {
            recovery.finalizePendingClose(); if (!recovery.awaitStopped(10000)) throw new IllegalStateException("RESUME_RECOVERY_SHUTDOWN_TIMEOUT");
            runtime.close(); if (!runtime.awaitStopped(10000)) throw new IllegalStateException("RESUME_RUNTIME_SHUTDOWN_TIMEOUT"); runtime.finalizePendingClose();
            io.shutdown(); if (!io.awaitTermination(10000, TimeUnit.MILLISECONDS)) throw new IllegalStateException("RESUME_IO_SHUTDOWN_TIMEOUT");
            main(() -> { controller.close(); return true; });
        }
    }

    private void invalidGenerationAndTimeout() throws Exception {
        int buckets = main(() -> count(owner.player, Items.BUCKET)); int amount = main(owner::amount);
        var staleCtx = context(owner, "Generation changes invalidate processing before extraction"); var stale = plan(staleCtx, true, null);
        generation.incrementAndGet();
        expectFailure(() -> processing.execute(staleCtx, stale), "FLUID_RECIPES_RELOADED");
        check(owner.journal.find(owner.actor, stale.id()).isEmpty() && main(owner::amount) == amount
                && main(() -> count(owner.player, Items.BUCKET)) == buckets, "generation-change-never-creates-custody-or-consumes-inputs");
        var timeoutCtx = context(owner, "Expired execution must not enter native code"); var timed = plan(timeoutCtx, true, null);
        var timedContext = new AgentTool.Context(owner.workspace, owner.actor, new TaskControl(0), timeoutCtx.taskId());
        expectFailure(() -> processing.execute(timedContext, timed), "TIMEOUT");
        check(owner.journal.find(owner.actor, timed.id()).isEmpty() && main(owner::amount) == amount
                && main(() -> count(owner.player, Items.BUCKET)) == buckets, "timeout-before-dispatch-keeps-native-state-unchanged");
    }

    private FluidProcessingPlan plan(AgentTool.Context ctx, boolean fill, UUID target) throws Exception {
        return processing.plan(ctx, new FluidProcessingPlanner.Request("", fill ? FluidProcessingAdapters.BUCKET_FILL : FluidProcessingAdapters.BUCKET_EMPTY,
                fill ? "minecraft:water" : "", fill ? "minecraft:bucket" : "minecraft:water_bucket", 1, target));
    }
    private AgentTool.Context context(Session session, String prompt) throws Exception {
        var task = AgentTask.create(session.actor.owner(), UUID.randomUUID(), prompt);
        session.workspace.conversation().append(List.of(Draft.checkpoint(task)));
        return new AgentTool.Context(session.workspace, session.actor, new TaskControl(120000), task.id());
    }
    private record NativeMaterials(Map<String, Long> items, Map<String, Long> fluids) {}
    private NativeMaterials nativeMaterials(ServerPlayer player) {
        var stacks = new ArrayList<MaterialStack>();
        for (int slot = 1; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) stacks.add(ItemSnapshotCodec.capture(stack, player.registryAccess()));
        }
        var handler = Objects.requireNonNull(player.getInventory().getItem(0).getCapability(Capabilities.FluidHandler.ITEM));
        return new NativeMaterials(itemTotals(stacks), fluidTotals(FluidTankSnapshot.capture(handler, player.registryAccess())
                .tanks().stream().map(FluidTankSnapshot.Tank::fluid).filter(fluid -> !fluid.empty()).toList()));
    }
    private static Map<String, Long> subtract(Map<String, Long> before, Map<String, Long> consumed) {
        var remaining = new TreeMap<>(before);
        consumed.forEach((kind, amount) -> {
            long left = remaining.getOrDefault(kind, 0L) - amount;
            if (left < 0) throw new IllegalStateException("NATIVE_FIXTURE_INPUTS_DO_NOT_MATCH_PLAN");
            if (left == 0) remaining.remove(kind); else remaining.put(kind, left);
        });
        return remaining;
    }
    private AgentTool.Result executeService(AgentTool.Context ctx, FluidProcessingPlan plan) throws Exception {
        var player = main(() -> inventory.requirePlayer(ctx.actor()));
        var before = main(() -> nativeMaterials(player));
        try { return processing.execute(ctx, plan); }
        catch (AgentTool.Deferred deferred) {
            inputSaveWaits++;
            inputSaveWaitMechanisms.add(plan.mechanismId());
            var checkpoint = deferred.checkpoint();
            check(deferred.reason().equals("WAITING_INPUT_SAVE")
                    && checkpoint.get("operationId").getAsString().equals(plan.id().toString())
                    && checkpoint.get("planHash").getAsString().equals(plan.hash()), "native-input-save-wait-keeps-exact-operation-and-plan");
            var waiting = new NativeMaterials(subtract(before.items(), itemTotals(plan.itemInputs())),
                    subtract(before.fluids(), fluidTotals(plan.fluidInputs())));
            check(main(() -> nativeMaterials(player)).equals(waiting), "native-inputs-extracted-without-exposing-item-or-fluid-outputs");
            var journal = processing.prepare(ctx.workspace());
            check(journal.get(ctx.actor(), plan.id()).state() == MixedResourceJournal.State.INPUTS_PENDING
                    && items.get(ctx.actor(), plan.id()).products().isEmpty()
                    && plan.fluidClaims().stream().allMatch(claim -> fluids.get(ctx.actor(), claim.operationId()).consumedMb() == 0),
                    "save-wait-retains-input-custody-without-recipe-output-or-fluid-debit");
            expectFailure(() -> journal.heldFluidOutputs(ctx.actor(), plan.id()), "MIXED_COMMIT_NOT_CONFIRMED");
            // Let normal server ticks pass without calling any fixture save method.
            // The external signal must remain pending, and no native product may appear.
            Thread.sleep(100);
            check(!deferred.readiness().toCompletableFuture().isDone()
                    && main(() -> nativeMaterials(player)).equals(waiting), "normal-save-delay-does-not-produce-or-expose-outputs");
            save(player);
            deferred.readiness().toCompletableFuture().get(10, TimeUnit.SECONDS);
            check(items.get(ctx.actor(), plan.id()).inputsSaved()
                    && plan.fluidClaims().stream().allMatch(claim -> fluids.get(ctx.actor(), claim.operationId()).inputsSaved()),
                    "trusted-continuation-waits-for-real-player-and-saved-data-receipts");
            var fresh = new AgentTool.Context(ctx.workspace(), ctx.actor(), new TaskControl(120000), ctx.taskId(), ctx.modelResultBytes());
            var registry = new ToolRegistry(); processing.register(registry);
            var original = registry.get("fluid_processing_execute");
            var resume = new AgentTool() {
                public Descriptor descriptor() { return original.descriptor(); }
                public Result execute(Context current, JsonObject arguments) throws Exception {
                    if (current.control() == ctx.control() || !Objects.equals(current.taskId(), ctx.taskId()))
                        throw new IllegalStateException("NATIVE_CONTINUATION_REQUIRES_FRESH_CONTROL_AND_ORIGINAL_TASK");
                    return deferred.continuation().resume(current);
                }
            };
            var arguments = new JsonObject(); arguments.addProperty("planId", plan.id().toString()); arguments.addProperty("planHash", plan.hash());
            var call = new ToolCall(UUID.randomUUID().toString(), original.descriptor().name(), arguments.toString());
            ToolPolicyEngine.arguments(call, original.descriptor().inputSchema());
            var bridge = new GameToolBridge(server, identity -> {
                try { return main(() -> {
                    if (!identity.equals(player.getUUID().toString())) throw new SecurityException("WORKSPACE_ACCESS_DENIED");
                    return Actor.player(player.getUUID(), AiServerService.authenticated(player),
                            server.getPlayerList().getPlayer(player.getUUID()) == player, 0);
                }); } catch (Exception failure) { throw new CompletionException(failure); }
            }, name -> name.equals(original.descriptor().name()), () -> false);
            var result = fresh.control().await(bridge.execute(resume, fresh, arguments,
                    ToolPolicyEngine.digest(ctx.actor().owner(), call), call), 10000);
            check(!result.errorCode().equals("APPROVAL_REQUIRED") && !result.errorCode().equals("AUTH_REQUIRED"),
                    "saved-input-continuation-reenters-real-bridge-with-bound-approval");
            return result;
        }
    }
    private void save() throws Exception {
        save(owner.player, other.player);
    }
    private void save(ServerPlayer... players) throws Exception {
        main(() -> {
            var field = net.minecraft.server.players.PlayerList.class.getDeclaredField("playerIo"); field.setAccessible(true);
            var store = (net.minecraft.world.level.storage.PlayerDataStorage) field.get(server.getPlayerList());
            for (var player : players) store.save(player);
            server.overworld().getDataStorage().save();
            IOUtilities.waitUntilIOWorkerComplete(); return true;
        });
    }
    private void await(BooleanSupplier condition, String message) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= end) throw new IllegalStateException("SAVE_RECEIPT_TIMEOUT:" + message);
            Thread.sleep(10);
        }
        passed.add(message);
    }
    private <T> T main(Callable<T> work) throws Exception {
        var future = new CompletableFuture<T>();
        server.execute(() -> { try { future.complete(work.call()); } catch (Throwable failure) { future.completeExceptionally(failure); } });
        return future.get(10, TimeUnit.SECONDS);
    }
    private void expectFailure(Callable<?> action, String code) throws Exception {
        try {
            Object value = action.call();
            if (value instanceof AgentTool.Result response && !response.success()
                    && (code == null || code.equals(response.errorCode()))) { passed.add("expected-rejection:" + code); return; }
        } catch (Exception failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
            if (code == null || code.equals(cause.getMessage())) { passed.add("expected-rejection:" + code); return; }
            throw failure;
        }
        throw new IllegalStateException("EXPECTED_REJECTION:" + code);
    }
    private void check(boolean condition, String name) { if (!condition) throw new IllegalStateException(name); passed.add(name); }
    private void checkHook() { if (hookFailure != null) throw new IllegalStateException("NATIVE_VALIDATION_HOOK_FAILED", hookFailure); }
    private boolean nativeTransferGuard(String backend) {
        // A fixture-only hook in the real service's guard distinguishes perform from planning.
        // It stops the waiter after CommittedCall claims dispatch, but permits the native call
        // and its real recorder to finish. No amount, result or journal evidence is synthesized.
        TaskControl control = cancelDuringNative;
        if (control != null && StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                frame.getClassName().equals(FluidInventoryService.class.getName()) && frame.getMethodName().equals("perform")))) {
            cancelDuringNative = null; nativeCancellationObserved = true; control.stop("CANCELLED");
        }
        return processing.recoveryBlocksBackend(backend);
    }
    private static int count(ServerPlayer player, Item item) {
        int result = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) if (player.getInventory().getItem(i).is(item)) result += player.getInventory().getItem(i).getCount();
        return result;
    }
    public JsonObject tick() {
        inventory.tick(); tanks.tick();
        Runnable hook = beforeDispatch;
        if (hook != null) try { hook.run(); } catch (Throwable failure) { hookFailure = failure; beforeDispatch = null; }
        long start = System.nanoTime(); dispatcher.tick();
        maximumDispatcherSliceMillis = Math.max(maximumDispatcherSliceMillis, (System.nanoTime() - start) / 1_000_000.0);
        if (!result.isDone()) return null;
        var report = result.join(); report.addProperty("maximumDispatcherSliceMillis", maximumDispatcherSliceMillis);
        report.addProperty("maximumFluidScanSliceMillis", tanks.maximumSliceNanos() / 1_000_000.0); return report;
    }

    private final class Session {
        final ServerPlayer player;
        final Actor actor;
        final WorkspaceManager.Workspace workspace;
        final MaterialSourceGrants grants;
        final MixedResourceJournal journal;
        final ItemStack backpack;
        MaterialSourceGrants.Grant grant;
        Session(String name) throws Exception {
            UUID identity = UUID.randomUUID();
            // The real auth fixture stores passwords by player name across isolated server runs.
            // Each fixture gets a fresh bounded name; previous server configuration stays intact.
            var cookie = new CommonListenerCookie(new GameProfile(identity, "fproc_" + identity.toString().substring(0, 8)), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
            player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); channels.add(new EmbeddedChannel(connection));
            NetworkRegistry.configureMockConnection(connection); server.getPlayerList().placeNewPlayer(connection, player, cookie);
            ValidationHandshake.reply(player); ValidationHandshake.authenticateFixture(player);
            player.setGameMode(GameType.SURVIVAL); player.setNoGravity(true); player.getInventory().clearContent();
            actor = Actor.player(player.getUUID(), true, true, 0); workspace = manager.getWorkspace(actor);
            inventory.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis));
            inventory.bindPreferences(actor.owner(), new MaterialPreferences(workspace));
            grants = new MaterialSourceGrants(workspace, System::currentTimeMillis, MaterialSourceGrants.Domain.FLUIDS); tanks.bind(actor.owner(), grants);
            backpack = new ItemStack(nativeItem("diamond_backpack")); player.getInventory().setItem(0, backpack);
            Class<?> type = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
            Object wrapper = type.getMethod("fromStack", ItemStack.class).invoke(null, backpack);
            type.getMethod("setContentsUuid", UUID.class).invoke(wrapper, UUID.randomUUID()); type.getMethod("getInventoryHandler").invoke(wrapper);
            ((IItemHandlerModifiable) type.getMethod("getUpgradeHandler").invoke(wrapper)).setStackInSlot(0, new ItemStack(nativeItem("tank_upgrade")));
            grant = authorize(); journal = processing.prepare(workspace);
        }
        MaterialSourceGrants.Grant authorize() throws Exception {
            var selection = tanks.selectBackpack(actor, "inventory/0"); var preview = grants.preview(actor, selection.candidate());
            return grants.confirm(actor, preview.id(), preview.digest(), tanks.confirmSelection(actor, selection));
        }
        IFluidHandler handler() { return Objects.requireNonNull(backpack.getCapability(Capabilities.FluidHandler.ITEM)); }
        int amount() {
            int amount = 0; IFluidHandler handler = handler();
            for (int i = 0; i < handler.getTanks(); i++) { var fluid = handler.getFluidInTank(i); if (fluid.getFluid() == Fluids.WATER) amount += fluid.getAmount(); }
            return amount;
        }
        void fill(int amount) {
            int remaining = amount;
            for (int i = 0; remaining > 0 && i < 128; i++) {
                int accepted = handler().fill(new FluidStack(Fluids.WATER, remaining), IFluidHandler.FluidAction.EXECUTE);
                if (accepted <= 0 || accepted > remaining) throw new IllegalStateException("NATIVE_FIXTURE_FILL_REJECTED"); remaining -= accepted;
            }
            if (remaining != 0) throw new IllegalStateException("NATIVE_FIXTURE_FILL_LIMIT");
        }
    }
    private static Item nativeItem(String id) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse("sophisticatedbackpacks:" + id));
        if (item == Items.AIR) throw new IllegalStateException("NATIVE_FIXTURE_ITEM_MISSING:" + id); return item;
    }
    @Override public void close() throws Exception {
        beforeDispatch = null; cancelDuringNative = null; processing.close(); tanks.close(); inventory.close(); dispatcher.close(); dispatcher.awaitEvidenceStopped(10000);
        for (var session : List.of(owner, other)) server.getPlayerList().remove(session.player);
        server.overworld().getDataStorage().save(); IOUtilities.waitUntilIOWorkerComplete();
        channels.forEach(EmbeddedChannel::finishAndReleaseAll); manager.close();
    }
}
