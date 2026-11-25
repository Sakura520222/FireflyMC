package firefly520.fireflymc.validation;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.model.ModelMessage;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.processing.special.*;
import firefly520.fireflymc.ai.security.*;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.*;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.GameType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Isolated real-service scenario. Actual target JARs are required; there is no mock processing backend. */
public final class SpecialProcessingScenario implements AutoCloseable {
    private final MinecraftServer server;
    private final ServerPlayer owner, other;
    private final Actor actor;
    private final WorkspaceManager manager;
    private final WorkspaceManager.Workspace workspace;
    private final MaterialLedger ledger;
    private final MaterialInventoryService inventory;
    private final VirtualProcessingService processing;
    private final SpecialProcessingService special;
    private final AtomicLong generation = new AtomicLong(1);
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private final List<String> passed = new ArrayList<>();
    private final List<Double> samples = new ArrayList<>();
    private final JsonObject nativeEvidence = new JsonObject();
    private final CompletableFuture<JsonObject> result;
    private List<RecipeHolder<?>> originalRecipes;
    private record Fixture(RecipeHolder<?> holder, ItemStack input) {}

    public SpecialProcessingScenario(MinecraftServer server) throws Exception {
        this.server = server;
        if (!server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_TEST_REQUIRED");
        owner = player("special_owner"); other = player("special_other");
        owner.setNoGravity(true); other.setNoGravity(true);
        actor = Actor.player(owner.getUUID(), true, true, 0);
        Path root = Files.createTempDirectory(Path.of(".").toAbsolutePath(), "special-processing-case-");
        manager = new WorkspaceManager(root); workspace = manager.getWorkspace(actor); ledger = new MaterialLedger(root);
        inventory = new MaterialInventoryService(server, firefly520.fireflymc.ai.AiServerService::authenticated, new InstalledPermissionAdapters(server));
        inventory.bind(actor.owner(), new MaterialSourceGrants(workspace, System::currentTimeMillis));
        inventory.bindPreferences(actor.owner(), new MaterialPreferences(workspace));
        processing = new VirtualProcessingService(server, inventory, ledger, generation::get);
        special = new SpecialProcessingService(server, inventory, ledger, processing, generation::get);
        result = CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime(); JsonObject report = new JsonObject();
            try {
                if (System.getProperty("fireflymc.validation.scenario", "").equals("dedicated-entities")) runEntities();
                else if (System.getProperty("fireflymc.validation.scenario", "").equals("dedicated-fluids")) runFluids();
                else if (System.getProperty("fireflymc.validation.profile", "").equals("bumble")) runCandle();
                else run();
                report.addProperty("status", "PASSED");
            }
            catch (Throwable failure) { failure.printStackTrace(); report.addProperty("status", "FAILED"); report.addProperty("error", failure.toString()); }
            report.addProperty("durationMillis", (System.nanoTime() - start) / 1_000_000);
            report.add("passedChecks", AiJson.GSON.toJsonTree(passed));
            report.add("nativeEvidence", nativeEvidence.deepCopy());
            report.addProperty("scope", "Actual installed special recipe and dedicated-server inventory/ledger/service; two mock network players; no graphical clients or model calls.");
            return report;
        }, runnable -> Thread.ofPlatform().daemon(true).name("special-processing-validation").start(runnable));
    }

    private void run() throws Exception {
        Fixture fixture = main(() -> {
            owner.getInventory().clearContent(); other.getInventory().clearContent();
            for (var holder : server.getRecipeManager().getRecipes()) {
                var profile = SpecialItemProcessingAdapter.profile(holder.value());
                if (profile == null || profile.input() != SpecialItemProcessingAdapter.InputKind.SINGLE) continue;
                if (holder.value().getIngredients().isEmpty() || holder.value().getIngredients().getFirst().getItems().length == 0) continue;
                ItemStack input = holder.value().getIngredients().getFirst().getItems()[0].copyWithCount(1);
                if (profile.className().endsWith("AltarRepairRecipe") && input.isDamageableItem()) input.setDamageValue(Math.min(8, input.getMaxDamage() - 1));
                if (SpecialItemProcessingAdapter.matches(holder, List.of(input), owner.serverLevel())) return new Fixture(holder, input);
            }
            throw new IllegalStateException("REAL_SPECIAL_SINGLE_RECIPE_REQUIRED");
        });
        var ctx = context("complete one native special processing operation");
        var plan = plan(ctx, fixture);
        require(main(() -> owner.getInventory().getItem(0).getCount()) == 1, "plan-does-not-consume-real-input", null);
        require(special.review(plan).contains(plan.hash()) && special.review(plan).contains("真实材料"), "review-binds-real-materials-and-hash", null);
        var otherActor = Actor.player(other.getUUID(), true, true, 0);
        try { special.load(manager.getWorkspace(otherActor), otherActor, plan.id(), plan.hash()); throw new IllegalStateException("CROSS_PLAYER_PLAN_LEAK"); }
        catch (IllegalArgumentException expected) { passed.add("two-owner-private-artifact-isolation"); }
        var args = execution(plan); var call = new ModelMessage.ToolCall("special-approved", "special_processing_execute", args.toString());
        var policy = new ToolPolicyEngine(); var tools = registry();
        require(policy.check(actor, actor.owner(), tools.get(call.name()).descriptor(), true, call, "").decision() == ToolPolicyEngine.Decision.APPROVAL,
                "player-state-tool-requires-workbench-approval", null);
        require(policy.check(actor, actor.owner(), tools.get(call.name()).descriptor(), true, call, ToolPolicyEngine.digest(actor.owner(), call)).decision() == ToolPolicyEngine.Decision.ALLOW,
                "approval-binds-owner-call-arguments", null);
        var executed = executeSaved(tools.get(call.name()), ctx, args);
        require(executed.success(), "real-special-plan-extract-native-callback-deliver", executed);
        var operation = ledger.get(actor, plan.id());
        require(operation.state() == MaterialLedger.State.COMPLETED && operation.claims().stream().allMatch(c -> c.extracted() == c.requested()), "actual-input-deduction-and-ledger-complete", operation.state());
        require(main(() -> inventoryContents(owner)).equals(totals(plan.outcomes().getFirst().products())), "inventory-equals-verified-output-and-returns", null);
        var repeat = tools.get(call.name()).execute(ctx, args);
        require(!repeat.success() && repeat.errorCode().equals("SPECIAL_ALREADY_RECORDED"), "duplicate-operation-cannot-repeat-conversion", repeat);

        var changedCtx = context("source changed before acceptance"); var changed = plan(changedCtx, fixture);
        main(() -> { owner.getInventory().setItem(0, ItemStack.EMPTY); return true; });
        var sourceChanged = special.execute(changedCtx, changed);
        require(!sourceChanged.success() && ledger.find(actor, changed.id()).isEmpty(), "moved-input-rejected-before-reservation", sourceChanged);

        var reloadCtx = context("recipe generation changes"); var reloaded = plan(reloadCtx, fixture); generation.incrementAndGet();
        var reload = special.execute(reloadCtx, reloaded);
        require(!reload.success() && reload.errorCode().equals("SPECIAL_RECIPES_RELOADED") && ledger.find(actor, reloaded.id()).isEmpty(), "reload-refuses-old-approved-plan", reload);

        var cancelledCtx = context("cancel before native side effects"); var cancelled = plan(cancelledCtx, fixture); cancelledCtx.control().stop("CANCELLED");
        try { special.execute(cancelledCtx, cancelled); throw new IllegalStateException("CANCEL_IGNORED"); }
        catch (TaskControl.Stopped expected) { require(ledger.find(actor, cancelled.id()).isEmpty(), "cancel-does-not-create-native-operation", null); }

        var wrongTask = context("different task cannot take old plan");
        try { special.execute(wrongTask, cancelled); throw new IllegalStateException("TASK_BINDING_MISSING"); }
        catch (SecurityException expected) { passed.add("permanent-conversation-task-binding"); }
        var manifest = workspace.conversation().getSnapshot(0).events().stream().filter(e -> e.kind().equals("special_processing_plan")).count();
        require(manifest == 4, "all-private-plans-indexed-in-main-conversation", manifest);
    }
    private record EntityFixture(RecipeHolder<?> holder, List<ItemStack> inputs, BlockPos target) {}
    private void runCandle() throws Exception {
        var holder = main(() -> {
            var type = Class.forName(BumbleCandleAdapter.CLASS);
            var required = net.minecraft.core.NonNullList.<Ingredient>create(); required.add(Ingredient.of(Items.HONEYCOMB));
            Recipe<?> recipe = (Recipe<?>) type.getConstructor(CraftingBookCategory.class, String.class, int.class, int.class,
                    net.minecraft.core.NonNullList.class, net.minecraft.core.NonNullList.class, int.class, int.class,
                    boolean.class, boolean.class, boolean.class, int.class).newInstance(CraftingBookCategory.MISC, "", 2, 8,
                    required, net.minecraft.core.NonNullList.<Ingredient>create(), 1, 1, true, true, true, 10);
            var added = new RecipeHolder<>(ResourceLocation.parse("fireflymc_validation:sampled_candle"), recipe);
            originalRecipes = List.copyOf(server.getRecipeManager().getRecipes()); var all = new ArrayList<RecipeHolder<?>>(originalRecipes); all.add(added);
            server.getRecipeManager().replaceRecipes(all); owner.getInventory().clearContent();
            owner.getInventory().setItem(0, new ItemStack(Items.HONEYCOMB));
            owner.getInventory().setItem(1, net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION, net.minecraft.world.item.alchemy.Potions.HEALING));
            owner.getInventory().setItem(2, net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION, net.minecraft.world.item.alchemy.Potions.SWIFTNESS));
            owner.getInventory().setItem(3, net.minecraft.world.item.alchemy.PotionContents.createItemStack(Items.POTION, net.minecraft.world.item.alchemy.Potions.SWIFTNESS));
            return added;
        });
        var ctx = context("commit sampled native potion candle together with four real inputs");
        var snapshot = ctx.control().await(inventory.scan(actor), 30000); var args = new JsonObject();
        args.addProperty("recipeId", holder.id().toString()); args.addProperty("snapshotId", snapshot.id().toString()); var assignments = new JsonArray();
        for (int slot = 0; slot < 4; slot++) {
            final int index = slot; var source = snapshot.rows().stream().filter(row -> row.sourceId().equals("player") && row.slot() == index).findFirst().orElseThrow();
            var assignment = new JsonObject(); assignment.addProperty("roleSlot", slot); assignment.addProperty("sourceKey", source.key()); assignment.addProperty("count", 1); assignments.add(assignment);
        }
        args.add("assignments", assignments); var tools = registry(); var proposed = tools.get("special_processing_plan").execute(ctx, args);
        require(proposed.success(), "native-candle-all-possible-outcomes-preview", proposed);
        var plan = special.load(workspace, actor, UUID.fromString(proposed.observations().get("planId").getAsString()), proposed.observations().get("planHash").getAsString());
        require(plan.sampled() && plan.outcomes().size() == 2 && ledger.find(actor, plan.id()).isEmpty(), "candle-preview-does-not-publish-selected-random-result", plan.outcomes());
        int expectedBottles = main(() -> {
            var grid = new ArrayList<ItemStack>(Collections.nCopies(9, ItemStack.EMPTY));
            for (int i = 0; i < 4; i++) grid.set(i, owner.getInventory().getItem(i).copy());
            var input = CraftingInput.ofPositioned(3, 3, grid).input();
            return ((CraftingRecipe) holder.value()).getRemainingItems(input).stream().filter(stack -> stack.is(Items.GLASS_BOTTLE)).mapToInt(ItemStack::getCount).sum();
        });
        nativeEvidence.addProperty("nativeReturnedGlassBottles", expectedBottles);
        var executed = executeSaved(tools.get("special_processing_execute"), ctx, execution(plan));
        require(executed.success(), "native-candle-service-consumes-inputs-callbacks-and-delivers", executed);
        var op = ledger.get(actor, plan.id());
        require(op.claims().stream().mapToInt(MaterialLedger.Claim::extracted).sum() == 4 && op.sampledOutputs().equals(op.products()),
                "candle-four-real-inputs-accounted-and-random-outcome-fixed", op);
        require(main(() -> inventoryContents(owner)).equals(totals(op.products())) && main(() -> owner.getInventory().countItem(Items.GLASS_BOTTLE)) == expectedBottles,
                "candle-real-components-and-native-container-semantics-conserved", null);
        var events = JsonParser.parseString(ledger.exportJournal(actor)).getAsJsonArray();
        JsonObject[] first = new JsonObject[1];
        MaterialJournalCodec.replay(events, data -> {
            if (first[0] == null && data.get("operationId").getAsString().equals(plan.id().toString())
                    && !data.get("samplingEvidence").getAsString().isEmpty()) first[0] = data;
        });
        var firstSample = Objects.requireNonNull(first[0]);
        require(firstSample.get("state").getAsString().equals("PRODUCED") && firstSample.getAsJsonArray("escrow").isEmpty(),
                "candle-exported-roll-never-has-refundable-inputs", null);
        var repeated = tools.get("special_processing_execute").execute(ctx, execution(plan));
        require(!repeated.success() && ledger.get(actor, plan.id()).sampledOutputs().equals(op.sampledOutputs())
                && main(() -> inventoryContents(owner)).equals(totals(op.products())), "duplicate-candle-execution-cannot-reroll-or-duplicate-output", repeated);
        nativeEvidence.addProperty("fixture", "Real Bumblezone PotionCandleRecipe with two equally likely native effects, registered only in this isolated test; original recipe registry restored on close.");
    }
    private void runFluids() throws Exception {
        var recipe = main(() -> {
            var type = Class.forName("io.redspace.ironsspellbooks.recipe_types.alchemist_cauldron.BrewAlchemistCauldronRecipe");
            Recipe<?> nativeRecipe = (Recipe<?>) type.getConstructor(net.neoforged.neoforge.fluids.FluidStack.class, Ingredient.class, List.class, Optional.class)
                    .newInstance(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1000), Ingredient.of(Items.REDSTONE),
                            List.of(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1000)), Optional.of(new ItemStack(Items.GOLD_NUGGET)));
            var holder = new RecipeHolder<>(ResourceLocation.parse("fireflymc_validation:contained_fluid_conversion"), nativeRecipe);
            originalRecipes = List.copyOf(server.getRecipeManager().getRecipes()); var all = new ArrayList<RecipeHolder<?>>(originalRecipes); all.add(holder);
            server.getRecipeManager().replaceRecipes(all);
            owner.getInventory().clearContent(); owner.getInventory().setItem(0, new ItemStack(Items.REDSTONE)); owner.getInventory().setItem(1, new ItemStack(Items.WATER_BUCKET));
            return holder;
        });
        var ctx = context("real raw fluid and returned container accounting"); var snapshot = ctx.control().await(inventory.scan(actor), 30000);
        var args = new JsonObject(); args.addProperty("mechanismId", ContainedFluidProcessingAdapter.ID); args.addProperty("backingRecipeId", recipe.id().toString());
        args.addProperty("snapshotId", snapshot.id().toString()); var assignments = new JsonArray();
        for (int slot = 0; slot < 2; slot++) {
            final int index = slot; var source = snapshot.rows().stream().filter(row -> row.sourceId().equals("player") && row.slot() == index).findFirst().orElseThrow();
            var assignment = new JsonObject(); assignment.addProperty("roleSlot", slot); assignment.addProperty("sourceKey", source.key()); assignment.addProperty("count", 1); assignments.add(assignment);
        }
        args.add("assignments", assignments);
        var proposed = registry().get("special_processing_plan").execute(ctx, args);
        require(proposed.success(), "closed-fluid-plan-verifies-real-container-chain", proposed);
        var plan = special.load(workspace, actor, UUID.fromString(proposed.observations().get("planId").getAsString()), proposed.observations().get("planHash").getAsString());
        require(main(() -> owner.getInventory().countItem(Items.REDSTONE) == 1 && owner.getInventory().countItem(Items.WATER_BUCKET) == 1),
                "fluid-preview-does-not-decant-real-inventory", null);
        var request = execution(plan); var call = new ModelMessage.ToolCall("closed-fluid-approved", "special_processing_execute", request.toString());
        require(new ToolPolicyEngine().check(actor, actor.owner(), registry().get(call.name()).descriptor(), true, call, "").decision() == ToolPolicyEngine.Decision.APPROVAL,
                "fluid-conversion-needs-fixed-material-approval", null);
        var converted = executeSaved(registry().get(call.name()), ctx, request); require(converted.success(), "closed-fluid-native-service-conversion-completes", converted);
        require(main(() -> owner.getInventory().countItem(Items.REDSTONE) == 0 && owner.getInventory().countItem(Items.WATER_BUCKET) == 1
                && owner.getInventory().countItem(Items.LAVA_BUCKET) == 0 && owner.getInventory().countItem(Items.GOLD_NUGGET) == 1
                && owner.getInventory().countItem(Items.BUCKET) == 0), "one-real-bucket-is-reused-no-phantom-fuel-container-or-leftover-fluid", null);
        var op = ledger.get(actor, plan.id()); require(op.state() == MaterialLedger.State.COMPLETED && op.claims().stream().mapToInt(MaterialLedger.Claim::extracted).sum() == 2,
                "fluid-material-ledger-accounts-for-both-real-inputs", null);
        var inventoryBeforeDuplicate = main(() -> inventoryContents(owner));
        var duplicate = registry().get(call.name()).execute(ctx, request);
        require(!duplicate.success() && duplicate.errorCode().equals("SPECIAL_ALREADY_RECORDED")
                && inventoryBeforeDuplicate.equals(main(() -> inventoryContents(owner)))
                && ledger.get(actor, plan.id()).revision() == op.revision(), "duplicate-fluid-request-does-not-repeat-conversion", duplicate);
        nativeEvidence.addProperty("inputFluidMb", 1000); nativeEvidence.addProperty("outputFluidMb", 1000); nativeEvidence.addProperty("actualContainerCount", 1);
        nativeEvidence.addProperty("fixture", "Actual Iron native recipe class with a controlled recipe registered only in this isolated test; original registry restored on close.");
    }
    private void runEntities() throws Exception {
        // Failed runs retain their world and entity evidence. Allocate a fresh isolated test region instead of deleting it.
        Path regionFile = Path.of("entity-validation-next-region.txt").toAbsolutePath();
        if (Files.isSymbolicLink(regionFile) || Files.exists(regionFile) && Files.size(regionFile) > 32) throw new IllegalStateException("ENTITY_TEST_REGION_FILE_INVALID");
        int region = Files.exists(regionFile) ? Integer.parseInt(Files.readString(regionFile).strip()) : 0;
        if (region < 0 || region > 100000) throw new IllegalStateException("ENTITY_TEST_REGION_LIMIT");
        Files.writeString(regionFile, Integer.toString(region + 1));
        var fixture = main(() -> {
            RecipeHolder<?> holder = null;
            for (var candidate : server.getRecipeManager().getRecipes()) {
                var kind = EntityProcessingAdapter.kind(candidate.value());
                if (kind == EntityProcessingAdapter.Kind.AETHER_INCUBATION) { holder = candidate; break; }
                if (kind == EntityProcessingAdapter.Kind.MAID_ALTAR && candidate.value().getClass().getMethod("getEntityType").invoke(candidate.value())
                        .equals(ResourceLocation.parse("touhou_little_maid:box"))) holder = candidate;
            }
            if (holder == null) throw new IllegalStateException("REAL_ENTITY_RECIPE_REQUIRED");
            var inputs = new ArrayList<ItemStack>();
            for (Ingredient ingredient : holder.value().getIngredients()) {
                if (ingredient.isEmpty() || ingredient.getItems().length == 0) throw new IllegalStateException("ENTITY_FIXTURE_INGREDIENT_UNAVAILABLE");
                inputs.add(ingredient.getItems()[0].copyWithCount(1));
            }
            if (inputs.isEmpty() || inputs.size() > 6) throw new IllegalStateException("ENTITY_FIXTURE_INPUT_LIMIT");
            BlockPos target = new BlockPos(32 + region * 16, 80, 8);
            for (BlockPos pos : BlockPos.betweenClosed(target.offset(-2, -1, -2), target.offset(2, 4, 2)))
                owner.serverLevel().setBlock(pos, pos.getY() == target.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            owner.moveTo(target.getX() + .5, 80, 12.5, 180, 0); other.moveTo(target.getX() + .5, 80, 15.5, 180, 0);
            owner.getInventory().clearContent(); other.getInventory().clearContent();
            for (int i = 0; i < inputs.size(); i++) owner.getInventory().setItem(i, inputs.get(i).copy());
            return new EntityFixture(holder, inputs, target);
        });
        var ctx = context("native entity recipe with exact escrow and world receipts");
        var input = entityArguments(ctx, fixture, fixture.target());
        var planned = registry().get("special_entity_plan").execute(ctx, input);
        require(planned.success(), "entity-plan-matches-real-loaded-recipe", planned);
        UUID id = UUID.fromString(planned.observations().get("planId").getAsString()); String hash = planned.observations().get("planHash").getAsString();
        var execute = new JsonObject(); execute.addProperty("planId", id.toString()); execute.addProperty("planHash", hash);
        require(main(() -> owner.getInventory().getItem(0).getCount()) == 1 && ledger.find(actor, id).isEmpty(), "entity-preview-does-not-spawn-or-deduct", null);
        var otherActor = Actor.player(other.getUUID(), true, true, 0);
        var otherContext = new AgentTool.Context(manager.getWorkspace(otherActor), otherActor, new TaskControl(30000), ctx.taskId());
        try {
            var foreign = registry().get("special_entity_execute").execute(otherContext, execute);
            require(!foreign.success(), "another-owner-cannot-execute-entity-plan", foreign);
        } catch (IllegalArgumentException | SecurityException expected) { passed.add("another-owner-cannot-execute-entity-plan"); }
        var call = new ModelMessage.ToolCall("entity-approval", "special_entity_execute", execute.toString());
        require(new ToolPolicyEngine().check(actor, actor.owner(), registry().get(call.name()).descriptor(), true, call, "").decision() == ToolPolicyEngine.Decision.APPROVAL,
                "entity-conversion-requires-world-change-approval", null);
        require(special.reviewEntity(workspace, actor, id, hash).contains(hash), "entity-review-binds-actual-plan-and-region", null);
        var completed = executeSaved(registry().get("special_entity_execute"), ctx, execute);
        require(completed.success() || completed.errorCode().equals("CUSTODY_OR_SAVE_PENDING") && ledger.get(actor, id).saved(), "native-entity-creation-callback-and-escrow-complete", completed);
        var operation = ledger.get(actor, id);
        require(operation.state() == MaterialLedger.State.COMPLETED && operation.externalDebits().size() == 1
                        && operation.externalDebits().getFirst().known() && operation.externalDebits().getFirst().accepted(), "entity-ledger-requires-positive-native-receipt", null);
        var preparedEvent = workspace.conversation().getSnapshot(0).events().stream().filter(event -> event.kind().equals("special_entity_prepared")
                && id.toString().equals(event.data().get("operationId").getAsString())).findFirst().orElseThrow();
        var prepared = AiJson.GSON.fromJson(preparedEvent.data().get("prepared"), EntityProcessingAdapter.Prepared.class);
        require(main(() -> EntityProcessingAdapter.verifyPresent(prepared, owner.serverLevel())), "all-entity-uuids-owner-markers-and-passengers-match", null);
        var remaining = main(() -> owner.getInventory().items.stream().filter(stack -> !stack.isEmpty()).map(stack -> Map.of(
                "item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), "count", stack.getCount())).toList());
        nativeEvidence.add("itemsAfterEntityCallback", AiJson.GSON.toJsonTree(remaining));
        require(operation.externalDebits().getFirst().requestedInputs().stream().mapToInt(MaterialStack::count).sum() == fixture.inputs().size()
                        && operation.escrow().isEmpty() && operation.products().isEmpty(), "entity-inputs-are-committed-without-invented-ledger-refunds", null);
        require(main(() -> fixture.inputs().stream().noneMatch(inputStack -> owner.getInventory().contains(inputStack))),
                "original-recipe-inputs-are-not-returned-after-success", remaining);
        int count = prepared.members().size();
        require(count == planned.observations().get("nativeEntityCount").getAsInt(), "entity-count-including-passengers-matches-preview", null);
        var repeated = registry().get("special_entity_execute").execute(ctx, execute);
        require(!repeated.success() && ledger.get(actor, id).externalDebits().size() == 1, "duplicate-entity-operation-cannot-create-another-output", repeated);
        main(() -> { for (var member : prepared.members()) { Entity entity = owner.serverLevel().getEntity(member.uuid()); if (entity != null) entity.discard(); } return true; });

        var stoppedContext = context("cancel native entity before dispatch");
        main(() -> { for (int i = 0; i < fixture.inputs().size(); i++) owner.getInventory().setItem(i, fixture.inputs().get(i).copy()); return true; });
        var next = registry().get("special_entity_plan").execute(stoppedContext, entityArguments(stoppedContext, fixture, fixture.target()));
        require(next.success(), "second-entity-plan-is-independent", next);
        UUID nextId = UUID.fromString(next.observations().get("planId").getAsString()); var nextArgs = new JsonObject();
        nextArgs.add("planId", next.observations().get("planId")); nextArgs.add("planHash", next.observations().get("planHash"));
        stoppedContext.control().stop("CANCELLED");
        try { registry().get("special_entity_execute").execute(stoppedContext, nextArgs); throw new IllegalStateException("ENTITY_CANCEL_IGNORED"); }
        catch (TaskControl.Stopped expected) { require(ledger.find(actor, nextId).isEmpty(), "cancel-before-native-dispatch-does-not-consume-or-create", null); }
        var invalid = entityArguments(context("invalid entity target"), fixture, new BlockPos(fixture.target().getX(), server.overworld().getMaxBuildHeight() + 10, 8));
        var refused = registry().get("special_entity_plan").execute(ctx, invalid);
        require(!refused.success(), "out-of-region-or-world-entity-target-refused", refused);
    }
    private JsonObject entityArguments(AgentTool.Context ctx, EntityFixture fixture, BlockPos target) throws Exception {
        var snapshot = ctx.control().await(inventory.scan(actor), 30000); var assignments = new JsonArray();
        for (int slot = 0; slot < fixture.inputs().size(); slot++) {
            final int index = slot; var stock = snapshot.rows().stream().filter(row -> row.sourceId().equals("player") && row.slot() == index).findFirst().orElseThrow();
            var assignment = new JsonObject(); assignment.addProperty("roleSlot", slot); assignment.addProperty("sourceKey", stock.key()); assignment.addProperty("count", 1); assignments.add(assignment);
        }
        var data = new JsonObject(); data.addProperty("recipeId", fixture.holder().id().toString()); data.addProperty("snapshotId", snapshot.id().toString());
        data.addProperty("x", target.getX()); data.addProperty("y", target.getY()); data.addProperty("z", target.getZ()); data.add("assignments", assignments); return data;
    }

    private SpecialProcessingService.Plan plan(AgentTool.Context ctx, Fixture fixture) throws Exception {
        main(() -> { owner.getInventory().clearContent(); owner.getInventory().setItem(0, fixture.input().copy()); return true; });
        var snapshot = ctx.control().await(inventory.scan(actor), 30000);
        var source = snapshot.rows().stream().filter(s -> s.sourceId().equals("player") && s.slot() == 0).findFirst().orElseThrow();
        JsonObject args = new JsonObject(); args.addProperty("snapshotId", snapshot.id().toString()); args.addProperty("recipeId", fixture.holder().id().toString());
        JsonArray assignments = new JsonArray(); JsonObject assignment = new JsonObject(); assignment.addProperty("sourceKey", source.key());
        assignment.addProperty("roleSlot", 0); assignment.addProperty("count", 1); assignments.add(assignment); args.add("assignments", assignments);
        var result = registry().get("special_processing_plan").execute(ctx, args);
        if (!result.success()) throw new IllegalStateException("SPECIAL_PLAN_FAILED:" + result.errorCode());
        return special.load(workspace, actor, UUID.fromString(result.observations().get("planId").getAsString()), result.observations().get("planHash").getAsString());
    }
    private AgentTool.Result executeSaved(AgentTool tool, AgentTool.Context ctx, JsonObject args) throws Exception {
        return ValidationDeferredCalls.execute(server, ledger, inventory, tool, ctx, args, ignored -> {}, passed::add);
    }
    private AgentTool.Context context(String prompt) throws Exception {
        var task = AgentTask.create(actor.owner(), UUID.randomUUID(), prompt); workspace.conversation().append(List.of(Draft.checkpoint(task)));
        return new AgentTool.Context(workspace, actor, new TaskControl(120000), task.id());
    }
    private static JsonObject execution(SpecialProcessingService.Plan plan) { JsonObject args = new JsonObject(); args.addProperty("planId", plan.id().toString()); args.addProperty("planHash", plan.hash()); return args; }
    private ToolRegistry registry() { var tools = new ToolRegistry(); special.register(tools); return tools; }
    private static Map<String, Integer> totals(List<MaterialStack> items) { var result = new TreeMap<String, Integer>(); items.forEach(s -> { if (!s.empty()) result.merge(s.itemJson(), s.count(), Math::addExact); }); return result; }
    private static Map<String, Integer> inventoryContents(ServerPlayer player) {
        var items = new ArrayList<MaterialStack>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) items.add(ItemSnapshotCodec.capture(player.getInventory().getItem(i), player.registryAccess()));
        return totals(items);
    }
    private void require(boolean success, String label, Object details) { if (!success) throw new IllegalStateException(label + ":" + details); passed.add(label); }
    private <T> T main(Callable<T> work) throws Exception {
        var future = new CompletableFuture<T>(); server.execute(() -> { try { future.complete(work.call()); } catch (Exception failure) { future.completeExceptionally(failure); } });
        return future.get(10, TimeUnit.SECONDS);
    }
    public JsonObject tick() {
        inventory.tick(); processing.tick(); special.tick(); samples.add(server.getAverageTickTimeNanos() / 1_000_000.0);
        if (!result.isDone()) return null;
        var report = result.join(); report.addProperty("msptMean", samples.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        report.addProperty("msptMaxRollingSample", samples.stream().mapToDouble(Double::doubleValue).max().orElse(0)); report.add("msptSamples", AiJson.GSON.toJsonTree(samples)); return report;
    }
    private ServerPlayer player(String name) {
        var cookie = new CommonListenerCookie(new GameProfile(UUID.randomUUID(), "adapt-" + UUID.randomUUID().toString().substring(0, 8)), 0, ClientInformation.createDefault(), false, ConnectionType.NEOFORGE);
        var player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND); channels.add(new EmbeddedChannel(connection));
        NetworkRegistry.configureMockConnection(connection); server.getPlayerList().placeNewPlayer(connection, player, cookie);
        ValidationHandshake.reply(player); ValidationHandshake.authenticateFixture(player); player.setGameMode(GameType.SURVIVAL); return player;
    }
    @Override public void close() throws Exception {
        if (originalRecipes != null) server.getRecipeManager().replaceRecipes(originalRecipes);
        special.close(); inventory.close(); processing.close(); processing.awaitEvidenceStopped(10000);
        for (var player : List.of(owner, other)) server.getPlayerList().remove(player);
        net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete(); channels.forEach(EmbeddedChannel::finishAndReleaseAll); manager.close();
    }
}
