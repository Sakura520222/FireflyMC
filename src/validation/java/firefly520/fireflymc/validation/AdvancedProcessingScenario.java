package firefly520.fireflymc.validation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.processing.advanced.*;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameType;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Isolated real DedicatedServer, two mock network players and production advanced service/ledger. No model/network provider calls. */
public final class AdvancedProcessingScenario implements AutoCloseable {
    private final MinecraftServer server;
    private final ServerPlayer owner, other;
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final Actor actor;
    private final WorkspaceManager manager;
    private final WorkspaceManager.Workspace workspace;
    private final MaterialLedger ledger;
    private final MaterialInventoryService inventory;
    private final VirtualProcessingService processing;
    private final AdvancedProcessingService advanced;
    private final AtomicLong generation = new AtomicLong(1);
    private final AtomicReference<TaskControl> stopAfterEnchantment = new AtomicReference<>();
    private final AtomicBoolean failAfterEnchantment = new AtomicBoolean();
    private final List<String> passed = new ArrayList<>();
    private final List<Double> mspt = new ArrayList<>();
    private final CompletableFuture<JsonObject> result;
    private final List<RecipeHolder<?>> originalRecipes;
    private RecipeHolder<?> fixture;
    private boolean infusion;

    public AdvancedProcessingScenario(MinecraftServer server) throws Exception {
        if (!server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_TEST_REQUIRED");
        this.server = server; owner = player("advanced_owner", true); other = player("advanced_other", false);
        actor = Actor.player(owner.getUUID(), true, true, 0);
        Path root = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "advanced-processing-case-");
        manager = new WorkspaceManager(root); workspace = manager.getWorkspace(actor); ledger = new MaterialLedger(root);
        inventory = new MaterialInventoryService(server, firefly520.fireflymc.ai.AiServerService::authenticated, new InstalledPermissionAdapters(server));
        inventory.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis));
        inventory.bindPreferences(actor.owner(), new MaterialPreferences(workspace));
        processing = new VirtualProcessingService(server, inventory, ledger, generation::get);
        advanced = new AdvancedProcessingService(server, inventory, ledger, processing, generation::get);
        originalRecipes = List.copyOf(server.getRecipeManager().getRecipes());
        result = CompletableFuture.supplyAsync(() -> {
            long started = System.nanoTime(); var report = new JsonObject();
            try { run(); report.addProperty("status", "PASSED"); }
            catch (Throwable failure) { failure.printStackTrace(); report.addProperty("status", "FAILED"); report.addProperty("error", failure.toString()); }
            report.addProperty("durationMillis", (System.nanoTime() - started) / 1_000_000);
            report.add("passedChecks", AiJson.GSON.toJsonTree(passed));
            report.addProperty("scope", "Actual installed advanced adapter, dedicated-server production inventory/ledger/tool service, two mock network players; synthetic native recipe fixtures and controlled callback interruption; no graphical client or paid model call.");
            return report;
        }, action -> Thread.ofPlatform().daemon(true).name("advanced-processing-validation").start(action));
    }
    private void run() throws Exception {
        main(() -> { installFixture(); refill(); return true; });
        var first = context("one native advanced processing operation"); var plan = planned(first);
        var otherActor = Actor.player(other.getUUID(), true, true, 0); var otherWorkspace = manager.getWorkspace(otherActor);
        try { advanced.load(otherWorkspace, otherActor, plan.id(), plan.hash()); throw new IllegalStateException("OTHER_OWNER_PLAN_VISIBLE"); }
        catch (IllegalArgumentException expected) { passed.add("two-owner-artifact-isolation"); }
        var forged = execution(plan); forged.addProperty("planHash", "0".repeat(64));
        require(!call("advanced_processing_execute", first, forged).success() && ledger.find(actor, plan.id()).isEmpty(), "changed-approval-hash-cannot-execute", null);
        var foreignTask = context("unrelated task");
        var wrongTask = call("advanced_processing_execute", foreignTask, execution(plan));
        require(!wrongTask.success() && wrongTask.errorCode().equals("ADVANCED_TASK_MISMATCH"), "task-bound-plan", wrongTask);
        var policy = new ToolPolicyEngine(); var modelCall = new ModelMessage.ToolCall("advanced-approved", "advanced_processing_execute", execution(plan).toString());
        var descriptor = registry().get("advanced_processing_execute").descriptor();
        require(policy.check(actor, actor.owner(), descriptor, true, modelCall, "").decision() == ToolPolicyEngine.Decision.APPROVAL,
                "player-state-write-requires-workbench-approval", null);
        require(policy.check(actor, actor.owner(), descriptor, true, modelCall, ToolPolicyEngine.digest(actor.owner(), modelCall)).decision() == ToolPolicyEngine.Decision.ALLOW,
                "approval-binds-exact-tool-body", null);
        String preview = advanced.review(workspace, actor, plan);
        require(preview.contains("真实材料") && main(() -> owner.getInventory().getItem(0).getCount()) == 1, "review-shows-real-materials-without-consuming", null);
        var executed = call("advanced_processing_execute", first, execution(plan));
        require(executed.success(), "actual-native-transformation-delivered", executed);
        var operation = ledger.get(actor, plan.id());
        require(operation.state() == MaterialLedger.State.COMPLETED && operation.samplingEvidence().contains("fixed-native-outcome"), "outcome-and-consumption-committed-together", operation.state());
        require(main(() -> owner.getInventory().countItem(Items.DIAMOND)) == (infusion ? 1 : 2)
                        && main(() -> owner.getInventory().countItem(infusion ? Items.COAL : Items.GOLD_INGOT)) == 0
                        && main(() -> owner.totalExperience) == 0, "material-conservation-and-zero-experience-cost", null);
        var duplicate = call("advanced_processing_execute", first, execution(plan));
        require(!duplicate.success() && duplicate.errorCode().equals("ADVANCED_ALREADY_RECORDED"), "duplicate-operation-does-not-replay", duplicate);

        main(() -> { refill(); return true; });
        var changedContext = context("source changed after preview"); var changed = planned(changedContext);
        main(() -> { owner.getInventory().setItem(0, new ItemStack(Items.STONE)); return true; });
        var refused = call("advanced_processing_execute", changedContext, execution(changed));
        require(!refused.success() && ledger.find(actor, changed.id()).isEmpty(), "source-change-rejected-before-reservation", refused);
        main(() -> { refill(); return true; });
        var reloadContext = context("recipe reload after preview"); var reload = planned(reloadContext); generation.incrementAndGet();
        var stale = call("advanced_processing_execute", reloadContext, execution(reload));
        require(!stale.success() && stale.errorCode().equals("ADVANCED_RECIPE_RELOADED") && ledger.find(actor, reload.id()).isEmpty(), "reload-invalidates-approval-before-extraction", stale);
        var stoppedContext = context("cancel before dispatch"); var stopped = planned(stoppedContext); stoppedContext.control().stop("CANCELLED");
        var stoppedReceipt = call("advanced_processing_execute", stoppedContext, execution(stopped));
        require(!stoppedReceipt.success() && stoppedReceipt.errorCode().equals("CANCELLED") && ledger.find(actor, stopped.id()).isEmpty()
                && stoppedReceipt.observations().get("dispatch").getAsString().equals("REJECTED_BEFORE_EXECUTION"), "cancel-before-dispatch-does-not-consume", stoppedReceipt);

        if (infusion) {
            main(() -> { refill(); return true; });
            var interrupted = context("cancel during accepted native callback"); var interruptedPlan = planned(interrupted);
            stopAfterEnchantment.set(interrupted.control());
            try { var pauseReceipt = call("advanced_processing_execute", interrupted, execution(interruptedPlan));
                require(!pauseReceipt.success() && pauseReceipt.errorCode().equals("PROCESSING_PAUSED"), "native-callback-cancellation-retains-durable-receipt", pauseReceipt); }
            finally { stopAfterEnchantment.set(null); }
            require(ledger.get(actor, interruptedPlan.id()).state() == MaterialLedger.State.CALLBACK_DONE,
                    "accepted-callback-evidence-survives-cancellation", ledger.get(actor, interruptedPlan.id()).state());
            int seed = main(owner::getEnchantmentSeed);
            var resumed = call("advanced_processing_execute", new AgentTool.Context(workspace, actor, new TaskControl(120000), interrupted.taskId()), execution(interruptedPlan));
            require(resumed.success() && main(owner::getEnchantmentSeed) == seed, "resume-delivers-without-replaying-seed-or-callback", resumed);
        }
        if (ModList.get().isLoaded("apotheosis")) { stableReforgingPreview(); salvageSampling(); }
        if (infusion) {
            main(() -> { refill(); return true; });
            var unknown = context("unknown native callback outcome"); var unknownPlan = planned(unknown); failAfterEnchantment.set(true);
            var uncertain = call("advanced_processing_execute", unknown, execution(unknownPlan)); failAfterEnchantment.set(false);
            require(!uncertain.success() && uncertain.errorCode().equals("RECOVERY_REQUIRED") && ledger.get(actor, unknownPlan.id()).uncertain(), "uncertain-callback-quarantines-owned-output", uncertain);
            int seed = main(owner::getEnchantmentSeed);
            var repeated = call("advanced_processing_execute", unknown, execution(unknownPlan));
            require(!repeated.success() && repeated.errorCode().equals("RECOVERY_REQUIRED") && main(owner::getEnchantmentSeed) == seed, "uncertain-callback-cannot-be-replayed", repeated);
        }
    }
    private void installFixture() throws Exception {
        Object recipe;
        infusion = ModList.get().isLoaded("apothic_enchanting");
        if (infusion) {
            Class<?> stats = type("dev.shadowsoffire.apothic_enchanting.table.EnchantingStatRegistry$Stats");
            Object minimum = stats.getConstructor(float.class, float.class, float.class, float.class, int.class).newInstance(100f, 1f, 0f, 0f, 0);
            Object maximum = stats.getConstructor(float.class, float.class, float.class, float.class, int.class).newInstance(-1f, -1f, -1f, -1f, -1);
            recipe = type("dev.shadowsoffire.apothic_enchanting.table.infusion.InfusionRecipe")
                    .getConstructor(ItemStack.class, Ingredient.class, stats, stats).newInstance(new ItemStack(Items.DIAMOND), Ingredient.of(Items.COAL), minimum, maximum);
        } else if (ModList.get().isLoaded("psi")) {
            recipe = type("vazkii.psi.common.crafting.recipe.TrickRecipe").getConstructor(type("vazkii.psi.api.spell.piece.PieceCraftingTrick"), Ingredient.class, ItemStack.class, ItemStack.class)
                    .newInstance(null, Ingredient.of(Items.GOLD_INGOT), new ItemStack(Items.DIAMOND, 2), ItemStack.EMPTY);
        } else throw new IllegalStateException("ACTUAL_ADVANCED_MOD_PROFILE_REQUIRED");
        fixture = new RecipeHolder<>(ResourceLocation.parse("fireflymc:validation_advanced_materials"), (Recipe<?>) recipe);
        var recipes = new ArrayList<RecipeHolder<?>>(originalRecipes); recipes.add(fixture); server.getRecipeManager().replaceRecipes(recipes);
    }
    private void refill() {
        owner.getInventory().clearContent(); owner.getInventory().setItem(0, new ItemStack(infusion ? Items.COAL : Items.GOLD_INGOT));
        if (infusion) owner.getInventory().setItem(1, new ItemStack(Items.LAPIS_LAZULI, 3));
    }
    private void stableReforgingPreview() throws Exception {
        var context = context("stable native reforge previews");
        RecipeHolder<?> recipe = main(() -> server.getRecipeManager().byKey(ResourceLocation.parse("apotheosis:reforging/common")).orElseThrow());
        main(() -> {
            owner.getPersistentData().putInt(ApotheosisReforgingAdapter.SEED_KEY, 0); owner.getInventory().clearContent();
            owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
            var requirement = ApotheosisReforgingAdapter.inputRequirements(recipe, owner, 0).get(1).alternatives().getFirst();
            var material = net.minecraft.core.registries.BuiltInRegistries.ITEM.stream().map(ItemStack::new).filter(requirement.matches()).findFirst().orElseThrow();
            material.setCount(requirement.count()); owner.getInventory().setItem(1, material); return true;
        });
        var args = boundArguments(recipe.id().toString(), Map.of(0, 1, 1, 1));
        var first = plan(context, args); var second = plan(context, args);
        require(first.initializationSeed() == second.initializationSeed() && first.products().equals(second.products())
                && main(() -> owner.getPersistentData().getInt(ApotheosisReforgingAdapter.SEED_KEY)) == 0,
                "new-preview-id-cannot-reroll-uninitialized-reforge-seed", null);
        var executed = call("advanced_processing_execute", context, execution(first));
        require(executed.success(), "native-reforge-executes-through-material-service", executed);
        main(() -> { server.getPlayerList().saveAll(); return true; });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ledger.get(actor, first.id()).saved() && System.nanoTime() < deadline) Thread.sleep(10);
        require(ledger.get(actor, first.id()).saved(), "native-player-save-proves-reforge-input-output-and-seed-callback", ledger.get(actor, first.id()).nativeSaves());
        var oldPreview = call("advanced_processing_execute", context, execution(second));
        require(!oldPreview.success() && oldPreview.errorCode().equals("ADVANCED_NATIVE_RESULT_OR_CONTEXT_CHANGED"), "seed-advance-invalidates-other-preview", oldPreview);
    }
    private void salvageSampling() throws Exception {
        RecipeHolder<?> recipe = main(() -> {
            Object output = type("dev.shadowsoffire.apotheosis.affix.salvaging.SalvagingRecipe$OutputData")
                    .getConstructor(ItemStack.class, int.class, int.class).newInstance(new ItemStack(Items.DIAMOND), 0, 1);
            Object nativeRecipe = type("dev.shadowsoffire.apotheosis.affix.salvaging.SalvagingRecipe")
                    .getConstructor(Ingredient.class, List.class).newInstance(Ingredient.of(Items.PAPER), List.of(output));
            var added = new RecipeHolder<>(ResourceLocation.parse("fireflymc:validation_uniform_salvage"), (Recipe<?>) nativeRecipe);
            var recipes = new ArrayList<RecipeHolder<?>>(server.getRecipeManager().getRecipes()); recipes.add(added); server.getRecipeManager().replaceRecipes(recipes);
            owner.getInventory().clearContent(); owner.getInventory().setItem(0, new ItemStack(Items.PAPER)); return added;
        });
        var ctx = context("persist native uniform outcome once"); var plan = plan(ctx, boundArguments(recipe.id().toString(), Map.of(0, 1)));
        var executed = call("advanced_processing_execute", ctx, execution(plan)); require(executed.success(), "salvage-native-random-result-completes", executed);
        var operation = ledger.get(actor, plan.id());
        require(operation.samplingEvidence().startsWith("native-salvaging:seed=") && operation.sampledOutputs().equals(operation.products()), "salvage-sampled-evidence-is-the-produced-outcome", null);
        var events = com.google.gson.JsonParser.parseString(ledger.exportJournal(actor)).getAsJsonArray();
        JsonObject[] first = new JsonObject[1];
        MaterialJournalCodec.replay(events, data -> {
            if (first[0] == null && data.get("operationId").getAsString().equals(plan.id().toString())
                    && !data.get("samplingEvidence").getAsString().isEmpty()) first[0] = data;
        });
        var firstSample = Objects.requireNonNull(first[0]);
        require(firstSample.get("state").getAsString().equals("PRODUCED") && firstSample.getAsJsonArray("escrow").isEmpty(),
                "export-never-reveals-salvage-roll-while-inputs-remain-refundable", null);
        var before = operation.sampledOutputs(); call("advanced_processing_execute", ctx, execution(plan));
        require(ledger.get(actor, plan.id()).sampledOutputs().equals(before), "repeated-salvage-request-cannot-resample", null);
    }
    private AgentTool.Context context(String prompt) throws Exception {
        var task = AgentTask.create(actor.owner(), UUID.randomUUID(), prompt); workspace.conversation().append(List.of(Draft.checkpoint(task)));
        return new AgentTool.Context(workspace, actor, new TaskControl(120000), task.id());
    }
    private AdvancedProcessingPlan planned(AgentTool.Context ctx) throws Exception {
        return plan(ctx, boundArguments(fixture.id().toString(), infusion ? Map.of(0, 1, 1, 3) : Map.of(0, 1)));
    }
    private JsonObject boundArguments(String recipeId, Map<Integer, Integer> slots) throws Exception {
        var snapshot = new TaskControl(30000).await(inventory.scan(actor), 30000);
        var args = new JsonObject(); args.addProperty("recipeId", recipeId); var values = new JsonArray();
        for (var requested : new TreeMap<>(slots).entrySet()) {
            MaterialStack expected = main(() -> ItemSnapshotCodec.capture(owner.getInventory().getItem(requested.getKey()), owner.registryAccess()));
            var stock = snapshot.rows().stream().filter(row -> row.stack().itemJson().equals(expected.itemJson())).findFirst().orElseThrow();
            var bind = new JsonObject(); bind.addProperty("slot", requested.getKey()); bind.addProperty("sourceKey", stock.key()); bind.addProperty("count", requested.getValue()); values.add(bind);
        }
        args.add("inputs", values); return args;
    }
    private AdvancedProcessingPlan plan(AgentTool.Context ctx, JsonObject args) throws Exception {
        var result = call("advanced_processing_plan", ctx, args);
        if (!result.success()) throw new IllegalStateException("PLAN_FAILED:" + result.errorCode() + ":" + result.observations());
        return advanced.load(workspace, actor, UUID.fromString(result.observations().get("planId").getAsString()), result.observations().get("planHash").getAsString());
    }
    private static JsonObject execution(AdvancedProcessingPlan plan) { var args = new JsonObject(); args.addProperty("planId", plan.id().toString()); args.addProperty("planHash", plan.hash()); return args; }
    private ToolRegistry registry() { var tools = new ToolRegistry(); advanced.register(tools); return tools; }
    private AgentTool.Result call(String name, AgentTool.Context ctx, JsonObject args) throws Exception {
        return ValidationDeferredCalls.execute(server, ledger, inventory, registry().get(name), ctx, args, fresh -> {
            stopAfterEnchantment.compareAndSet(ctx.control(), fresh.control());
        }, passed::add);
    }
    private void require(boolean condition, String name, Object detail) { if (!condition) throw new IllegalStateException(name + ":" + detail); passed.add(name); }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name); }
    private <T> T main(Callable<T> work) throws Exception {
        var future = new CompletableFuture<T>(); server.execute(() -> { try { future.complete(work.call()); } catch (Exception failure) { future.completeExceptionally(failure); } });
        return future.get(10, TimeUnit.SECONDS);
    }
    public JsonObject tick() {
        inventory.tick(); processing.tick(); advanced.tick(); mspt.add(server.getAverageTickTimeNanos() / 1_000_000.0);
        if (!result.isDone()) return null;
        var report = result.join(); report.addProperty("msptMean", mspt.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        report.addProperty("msptMaxRollingSample", mspt.stream().mapToDouble(Double::doubleValue).max().orElse(0)); report.add("msptSamples", AiJson.GSON.toJsonTree(mspt)); return report;
    }
    private ServerPlayer player(String name, boolean callbackControls) {
        var cookie = new CommonListenerCookie(new GameProfile(UUID.randomUUID(), "adapt-" + UUID.randomUUID().toString().substring(0, 8)), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        var player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation()) {
            @Override public void onEnchantmentPerformed(ItemStack input, int cost) {
                super.onEnchantmentPerformed(input, cost);
                if (callbackControls) {
                    var control = stopAfterEnchantment.get(); if (control != null) control.stop("USER_PAUSED");
                    if (failAfterEnchantment.get()) throw new IllegalStateException("TEST_CALLBACK_UNCERTAIN");
                }
            }
        };
        var connection = new Connection(PacketFlow.SERVERBOUND); channels.add(new EmbeddedChannel(connection));
        NetworkRegistry.configureMockConnection(connection); server.getPlayerList().placeNewPlayer(connection, player, cookie);
        ValidationHandshake.reply(player); ValidationHandshake.authenticateFixture(player);
        player.setGameMode(GameType.SURVIVAL); player.setNoGravity(true); return player;
    }
    @Override public void close() throws Exception {
        advanced.close(); inventory.close(); processing.close(); processing.awaitEvidenceStopped(10000);
        server.getRecipeManager().replaceRecipes(originalRecipes);
        for (var player : List.of(owner, other)) server.getPlayerList().remove(player);
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete(); channels.forEach(EmbeddedChannel::finishAndReleaseAll); manager.close();
    }
}
