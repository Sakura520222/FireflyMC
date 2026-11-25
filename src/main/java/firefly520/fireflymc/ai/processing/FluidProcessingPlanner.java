package firefly520.fireflymc.ai.processing;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.tools.AgentTool;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.*;

/** Bounded native inspection followed by pure physical-pool and whole-backend planning. */
public final class FluidProcessingPlanner implements AutoCloseable {
    public record Request(String recipeId, String mechanismId, String fluidId, String inputItemId,
                          int count, UUID outputSourceId) {
        public Request {
            recipeId = Objects.requireNonNullElse(recipeId, ""); mechanismId = Objects.requireNonNullElse(mechanismId, "");
            fluidId = Objects.requireNonNullElse(fluidId, ""); inputItemId = Objects.requireNonNullElse(inputItemId, "");
            if (recipeId.isEmpty() == mechanismId.isEmpty() || count < 1 || count > 16
                    || !recipeId.isEmpty() && !FluidProcessingPlan.resource(recipeId)
                    || !inputItemId.isEmpty() && !FluidProcessingPlan.resource(inputItemId))
                throw new IllegalArgumentException("FLUID_PROCESSING_REQUEST_INVALID");
            if (!mechanismId.isEmpty() && !Set.of(FluidProcessingAdapters.BUCKET_FILL, FluidProcessingAdapters.BUCKET_EMPTY).contains(mechanismId))
                throw new IllegalArgumentException("FLUID_MECHANISM_UNSUPPORTED");
            if (mechanismId.equals(FluidProcessingAdapters.BUCKET_FILL)) {
                if (!Set.of("minecraft:water", "minecraft:lava").contains(fluidId)) throw new IllegalArgumentException("VANILLA_BUCKET_FLUID_REQUIRED");
                if (!inputItemId.isEmpty() && !inputItemId.equals("minecraft:bucket")) throw new IllegalArgumentException("FLUID_ITEM_MISMATCH");
            } else if (!fluidId.isEmpty()) throw new IllegalArgumentException("FLUID_OVERRIDE_NOT_APPLICABLE");
            if (mechanismId.equals(FluidProcessingAdapters.BUCKET_EMPTY)
                    && !Set.of("minecraft:water_bucket", "minecraft:lava_bucket").contains(inputItemId))
                throw new IllegalArgumentException("EXPLICIT_FILLED_BUCKET_REQUIRED");
        }
    }
    record ItemSelection(List<MaterialStack> inputs, List<MaterialLedger.Stock> sources, List<MaterialLedger.Need> needs) {
        ItemSelection { inputs = List.copyOf(inputs); sources = List.copyOf(sources); needs = List.copyOf(needs); }
    }
    private record Facts(RecipeHolder<?> recipe, Ingredient ingredient, FluidAmount required,
                         long generation, String dimension, String type, String serializer) {}
    record Binding(FluidProcessingPlan plan, Recipe<?> recipe) {
        Binding { Objects.requireNonNull(plan); }
        /** Artifact equality is checked on IO once; native slices only accept this immutable object. */
        FluidProcessingPlan canonical(FluidProcessingPlan loaded) {
            if (!plan.equals(loaded)) throw new SecurityException("FLUID_PLAN_CHANGED");
            return plan;
        }
        void requireOriginal(FluidProcessingPlan candidate) {
            if (plan != candidate) throw new SecurityException("FLUID_PLAN_CHANGED");
        }
    }
    record ResumeFacts(RecipeHolder<?> recipe, long generation, String dimension,
                       List<FluidProcessingAdapters.Evaluation> evaluations) {
        ResumeFacts { evaluations = List.copyOf(evaluations); }
    }
    record ResumeBinding(FluidProcessingResume review, Recipe<?> recipe) {
        FluidProcessingResume canonical(FluidProcessingResume loaded) {
            if (!review.equals(loaded)) throw new SecurityException("FLUID_RESUME_CHANGED");
            return review;
        }
        void requireOriginal(FluidProcessingResume candidate) {
            if (review != candidate) throw new SecurityException("FLUID_RESUME_CHANGED");
        }
    }
    private record NativeContext(ServerPlayer player, RecipeHolder<?> recipe) {}
    private final MinecraftServer server;
    private final MaterialInventoryService items;
    private final FluidInventoryService fluids;
    private final MaterialLedger itemLedger;
    private final FluidLedger fluidLedger;
    private final VirtualProcessingService dispatcher;
    private final LongSupplier generation;
    private final UUID epoch = UUID.randomUUID();
    private final Map<UUID, Binding> bindings = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<UUID, ResumeBinding> resumeBindings = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile boolean closed;

    public FluidProcessingPlanner(MinecraftServer server, MaterialInventoryService items, FluidInventoryService fluids,
            MaterialLedger itemLedger, FluidLedger fluidLedger, VirtualProcessingService dispatcher, LongSupplier generation) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        this.server = server; this.items = items; this.fluids = fluids; this.itemLedger = itemLedger;
        this.fluidLedger = fluidLedger; this.dispatcher = dispatcher; this.generation = generation;
    }
    public UUID executionEpoch() { return epoch; }
    public long recipeGeneration() { return generation.getAsLong(); }

    /** A restarted process may review old custody, but cannot reuse the old execution binding. */
    ResumeFacts resumeFacts(AgentTool.Context ctx, FluidProcessingPlan original) throws Exception {
        validateResumePermissions(ctx, original);
        var facts = dispatcher.inspectOwned(ctx, () -> {
            thread(); var player = items.requirePlayer(ctx.actor()); var recipe = current(original.recipeId());
            requireResumeRecipe(original, recipe);
            return new ResumeFacts(recipe, generation.getAsLong(), player.serverLevel().dimension().location().toString(), List.of());
        });
        var evaluated = new ArrayList<FluidProcessingAdapters.Evaluation>();
        for (var input : original.inputs()) evaluated.add(dispatcher.inspectOwned(ctx, () -> {
            var live = currentResume(ctx.actor(), original, facts.recipe() == null ? null : facts.recipe().value(), facts.generation(), facts.dimension());
            var required = FluidProcessingAdapters.requiredFluid(live.recipe(), original.mechanismId(), original.fluidId(), live.player().serverLevel())
                    .orElse(FluidAmount.EMPTY);
            return FluidProcessingAdapters.evaluate(live.recipe(), original.mechanismId(), original.fluidId(),
                    ItemSnapshotCodec.restore(input, live.player().registryAccess()), required, live.player().serverLevel());
        }));
        FluidProcessingResume.requireEvaluations(original, evaluated);
        return new ResumeFacts(facts.recipe(), facts.generation(), facts.dimension(), evaluated);
    }
    void bindResume(AgentTool.Context ctx, FluidProcessingResume review, ResumeFacts facts) throws Exception {
        if (!epoch.equals(review.executionEpoch()) || review.recipeGeneration() != facts.generation()
                || !review.dimension().equals(facts.dimension()) || !review.evaluations().equals(facts.evaluations()))
            throw new SecurityException("FLUID_RESUME_CHANGED");
        dispatcher.inspectOwned(ctx, () -> {
            currentResume(ctx.actor(), review.originalPlan(), facts.recipe() == null ? null : facts.recipe().value(), facts.generation(), facts.dimension());
            synchronized (resumeBindings) {
                resumeBindings.put(review.id(), new ResumeBinding(review, facts.recipe() == null ? null : facts.recipe().value()));
                while (resumeBindings.size() > 256) resumeBindings.remove(resumeBindings.keySet().iterator().next());
            }
            return true;
        });
    }
    FluidProcessingResume canonicalResume(FluidProcessingResume loaded) {
        FluidProcessingResume.requireEpoch(loaded.executionEpoch(), epoch);
        var binding = resumeBindings.get(loaded.id());
        if (binding == null) throw new IllegalStateException("FLUID_RESUME_RUNTIME_EXPIRED_NEW_PREVIEW_REQUIRED");
        return binding.canonical(loaded);
    }
    /** Pure guard repeated immediately before the durable commit; canonical/hash work stays on IO. */
    void validateResumeRuntime(FluidProcessingResume review) {
        if (closed) throw new IllegalStateException("SERVER_STOPPING");
        FluidProcessingResume.requireEpoch(review.executionEpoch(), epoch);
        if (generation.getAsLong() != review.recipeGeneration()) throw new IllegalStateException("FLUID_RECIPES_RELOADED");
        var binding = resumeBindings.get(review.id());
        if (binding == null) throw new IllegalStateException("FLUID_RESUME_RUNTIME_EXPIRED_NEW_PREVIEW_REQUIRED");
        binding.requireOriginal(review);
    }
    void validateResumeOnMain(Actor actor, FluidProcessingResume review) {
        thread(); validateResumeRuntime(review); actor.requireAccess(review.owner());
        var binding = resumeBindings.get(review.id());
        currentResume(actor, review.originalPlan(), binding.recipe(), review.recipeGeneration(), review.dimension());
    }
    void validateResumeNative(AgentTool.Context ctx, FluidProcessingResume review) throws Exception {
        validateResumePermissions(ctx, review.originalPlan());
        for (int index = 0; index < review.originalPlan().count(); index++) {
            int craft = index;
            dispatcher.inspectOwned(ctx, () -> {
                validateResumeOnMain(ctx.actor(), review);
                var player = items.requirePlayer(ctx.actor()); var original = review.originalPlan();
                var recipe = current(original.recipeId());
                var required = FluidProcessingAdapters.requiredFluid(recipe, original.mechanismId(), original.fluidId(), player.serverLevel())
                        .orElse(FluidAmount.EMPTY);
                var evaluated = FluidProcessingAdapters.evaluate(recipe, original.mechanismId(), original.fluidId(),
                        ItemSnapshotCodec.restore(original.inputs().get(craft), player.registryAccess()), required, player.serverLevel());
                if (!review.evaluations().get(craft).equals(evaluated))
                    throw new IllegalStateException("FLUID_RESUME_RECIPE_CHANGED_RETURN_AND_REPLAN");
                return true;
            });
        }
    }
    private NativeContext currentResume(Actor actor, FluidProcessingPlan original, Recipe<?> expected, long expectedGeneration, String dimension) {
        thread(); actor.requireAccess(original.owner()); var player = items.requirePlayer(actor);
        if (generation.getAsLong() != expectedGeneration) throw new IllegalStateException("FLUID_RECIPES_RELOADED");
        if (!player.serverLevel().dimension().location().toString().equals(dimension))
            throw new IllegalStateException("FLUID_DIMENSION_CHANGED");
        var recipe = current(original.recipeId()); requireResumeRecipe(original, recipe);
        if (recipe != null && recipe.value() != expected) throw new IllegalStateException("FLUID_RECIPE_INSTANCE_CHANGED");
        return new NativeContext(player, recipe);
    }
    private static void requireResumeRecipe(FluidProcessingPlan original, RecipeHolder<?> recipe) {
        if (recipe != null && (!BuiltInRegistries.RECIPE_TYPE.getKey(recipe.value().getType()).toString().equals(original.recipeType())
                || !BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.value().getSerializer()).toString().equals(original.serializerId())))
            throw new IllegalStateException("FLUID_RESUME_RECIPE_CHANGED_RETURN_AND_REPLAN");
    }
    private void validateResumePermissions(AgentTool.Context ctx, FluidProcessingPlan original) throws Exception {
        ctx.actor().requireAccess(original.owner());
        for (var input : original.itemInputs()) if (items.protectedByOwner(ctx.actor(), input))
            throw new IllegalStateException("MATERIAL_PROTECTED_BY_OWNER");
        // Saved custody retains its historical extraction authority without requiring the old host.
        // A partial resume separately revalidates current origin access for each missing claim;
        // new native output calls always authorize their current targets.
        dispatcher.inspectOwned(ctx, () -> items.requirePlayer(ctx.actor()));
    }

    public FluidProcessingPlan plan(AgentTool.Context ctx, Request request) throws Exception {
        ctx.actor().requireAccess(ctx.workspace().owner());
        if (ctx.taskId() == null) throw new IllegalArgumentException("FLUID_TASK_REQUIRED");
        long privacy = ctx.workspace().conversation().privacyEpoch();
        var itemSnapshot = ctx.control().await(items.scan(ctx.actor()), 30000);
        var fluidSnapshot = ctx.control().await(fluids.scan(ctx.actor()), 30000);
        Facts facts = dispatcher.inspectOwned(ctx, () -> {
            thread(); ServerPlayer player = items.requirePlayer(ctx.actor());
            var recipe = current(request.recipeId());
            var ingredient = FluidProcessingAdapters.itemIngredient(recipe, request.mechanismId(), player.serverLevel());
            var required = FluidProcessingAdapters.requiredFluid(recipe, request.mechanismId(), request.fluidId(), player.serverLevel()).orElse(FluidAmount.EMPTY);
            return new Facts(recipe, ingredient, required, generation.getAsLong(), player.serverLevel().dimension().location().toString(),
                    recipe == null ? "" : BuiltInRegistries.RECIPE_TYPE.getKey(recipe.value().getType()).toString(),
                    recipe == null ? "" : BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.value().getSerializer()).toString());
        });
        List<MaterialLedger.Stock> stocks = deduplicateItems(itemSnapshot.rows());
        var available = itemLedger.available(stocks); var matched = new ArrayList<MaterialLedger.Stock>();
        for (int offset = 0; offset < stocks.size(); offset += 16) {
            int first = offset, end = Math.min(offset + 16, stocks.size());
            matched.addAll(dispatcher.inspectOwned(ctx, () -> {
                ServerPlayer player = checkFacts(ctx.actor(), facts);
                var result = new ArrayList<MaterialLedger.Stock>();
                for (var stock : stocks.subList(first, end)) {
                    if (available.getOrDefault(stock.key(), 0L) <= 0 || items.protectedByOwner(ctx.actor(), stock.stack())) continue;
                    var stack = ItemSnapshotCodec.restore(stock.stack().withCount(1), player.registryAccess());
                    if ((request.inputItemId().isEmpty() || BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(request.inputItemId()))
                            && facts.ingredient().test(stack)) result.add(stock);
                }
                return result;
            }));
        }
        ItemSelection selection = selectItems(matched, itemLedger.available(matched), itemLedger.availablePools(matched), request.count());
        var evaluations = new ArrayList<FluidProcessingAdapters.Evaluation>();
        for (var input : selection.inputs()) evaluations.add(dispatcher.inspectOwned(ctx, () -> {
            ServerPlayer player = checkFacts(ctx.actor(), facts);
            return FluidProcessingAdapters.evaluate(facts.recipe(), request.mechanismId(), request.fluidId(),
                    ItemSnapshotCodec.restore(input, player.registryAccess()), facts.required(), player.serverLevel());
        }));
        var rawInputs = evaluations.stream().flatMap(value -> value.consumedFluids().stream()).toList();
        var rawOutputs = evaluations.stream().flatMap(value -> value.producedFluids().stream()).toList();
        if (rawOutputs.size() > 64 || rawInputs.stream().mapToLong(FluidAmount::millibuckets).sum() > 1_000_000
                || rawOutputs.stream().mapToLong(FluidAmount::millibuckets).sum() > 1_000_000)
            throw new IllegalArgumentException("FLUID_PLAN_MATERIAL_LIMIT");
        var inputSources = new ArrayList<FluidInventoryService.Source>();
        var outputSources = new ArrayList<FluidInventoryService.Source>();
        for (var source : deduplicateFluids(fluidSnapshot.sources())) {
            var permissions = dispatcher.inspectOwned(ctx, () -> {
                checkFacts(ctx.actor(), facts);
                var grant = fluids.grants(ctx.actor()).list(ctx.actor()).stream().filter(g -> g.id().equals(source.grantId())
                        && g.revision() == source.revision() && !g.revoked()).findFirst().orElseThrow(() -> new IllegalStateException("FLUID_SOURCE_CHANGED"));
                var current = fluids.current(ctx.actor(), source.grantId(), source.revision(), grant.source().extract());
                if (!current.equals(source)) throw new IllegalStateException("FLUID_SOURCE_CHANGED");
                return new boolean[] {grant.source().extract(), grant.source().insert()};
            });
            if (permissions[0]) inputSources.add(source);
            if (permissions[1] && (request.outputSourceId() == null || request.outputSourceId().equals(source.grantId()))) outputSources.add(source);
        }
        if (request.outputSourceId() != null && outputSources.isEmpty()) throw new IllegalArgumentException("FLUID_OUTPUT_SOURCE_UNAVAILABLE");
        var claims = allocateClaims(rawInputs, inputSources);
        var targets = allocateOutputs(rawOutputs, outputSources, claims);
        for (var need : selection.needs()) {
            dispatcher.inspectOwned(ctx, () -> {
                checkFacts(ctx.actor(), facts);
                var source = selection.sources().stream().filter(value -> value.key().equals(need.stockKey())).findFirst().orElseThrow();
                items.prepareExtraction(ctx.actor(), source, Math.min(need.count(), source.maxTransferCount()));
                return true;
            });
        }
        var products = evaluations.stream().flatMap(value -> value.producedItems().stream()).toList();
        dispatcher.inspectOwned(ctx, () -> {
            checkFacts(ctx.actor(), facts);
            if (!items.outputFits(ctx.actor(), products, selection.sources(), selection.needs())) throw new IllegalStateException("OUTPUT_FULL");
            return true;
        });
        // Constructor consistency checks and canonical JSON/hash work use captured immutable data,
        // so none of this whole-plan work belongs in a main-thread dispatcher slice.
        FluidProcessingPlan plan = new FluidProcessingPlan(1, UUID.randomUUID(), ctx.workspace().metadata().serverInstanceId(), ctx.workspace().primaryConversationId(),
                ctx.actor().owner(), ctx.taskId(), epoch, privacy, facts.dimension(), facts.generation(), request.recipeId(), request.mechanismId(),
                request.fluidId(), request.inputItemId(), request.count(), facts.type(), facts.serializer(), selection.inputs(), evaluations,
                selection.sources(), selection.needs(), claims, targets, "").signed();
        dispatcher.inspectOwned(ctx, () -> {
            checkFacts(ctx.actor(), facts);
            synchronized (bindings) {
                bindings.put(plan.id(), new Binding(plan, facts.recipe() == null ? null : facts.recipe().value()));
                while (bindings.size() > 256) bindings.remove(bindings.keySet().iterator().next());
            }
            return true;
        });
        synchronized (ctx.workspace().conversation()) {
            ctx.control().check();
            if (privacy != ctx.workspace().conversation().privacyEpoch()) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
            var task = ctx.workspace().conversation().task(ctx.taskId());
            if (task == null || task.state().terminal()) throw new IllegalStateException("FLUID_TASK_ENDED");
            if (AiJson.GSON.toJson(plan).getBytes(StandardCharsets.UTF_8).length > 8 * 1024 * 1024) throw new IllegalArgumentException("FLUID_PLAN_SIZE_LIMIT");
            if (Files.exists(ctx.workspace().artifact(plan.id()), LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("FLUID_PLAN_ALREADY_EXISTS");
            AiJson.write(ctx.workspace().artifact(plan.id()), plan);
            var manifest = new JsonObject(); manifest.addProperty("planId", plan.id().toString()); manifest.addProperty("hash", plan.hash());
            manifest.addProperty("recipeId", plan.recipeId()); manifest.addProperty("mechanismId", plan.mechanismId());
            ctx.workspace().conversation().append(List.of(new Draft(plan.taskId(), "fluid_processing_plan", null, manifest, null)));
        }
        return plan;
    }

    public FluidProcessingPlan load(Workspace workspace, Actor actor, UUID id, String hash) throws Exception {
        var loaded = readOwned(workspace, actor, id, hash);
        var binding = bindings.get(id);
        // Plans from an earlier process remain readable for custody/return evidence. Execution
        // still requires an active binding and this process's execution epoch on the main thread.
        return binding == null ? loaded : binding.canonical(loaded);
    }
    static FluidProcessingPlan readOwned(Workspace workspace, Actor actor, UUID id, String hash) throws Exception {
        synchronized (workspace.conversation()) {
            actor.requireAccess(workspace.owner());
            if (id == null || hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("FLUID_PLAN_NOT_FOUND");
            boolean indexed = workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events().stream()
                    .anyMatch(event -> event.kind().equals("fluid_processing_plan") && event.data() != null
                            && event.data().has("planId") && event.data().has("hash")
                            && id.toString().equals(event.data().get("planId").getAsString()) && hash.equals(event.data().get("hash").getAsString()));
            Path path = workspace.artifact(id);
            if (!indexed || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 8 * 1024 * 1024)
                throw new IllegalArgumentException("FLUID_PLAN_NOT_FOUND");
            var plan = AiJson.GSON.fromJson(Files.readString(path), FluidProcessingPlan.class);
            if (plan == null || !plan.id().equals(id) || !plan.hash().equals(hash) || !plan.intact() || !plan.owner().equals(actor.owner())
                    || !plan.serverInstanceId().equals(workspace.metadata().serverInstanceId()) || !plan.conversationId().equals(workspace.primaryConversationId())
                    || plan.privacyEpoch() != workspace.conversation().privacyEpoch()) throw new SecurityException("FLUID_PLAN_IDENTITY_OR_HASH_CHANGED");
            return plan;
        }
    }
    /** Constant-size runtime guard for every native transfer, including a partly extracted batch. */
    public void validateOnMain(Actor actor, FluidProcessingPlan plan) {
        currentPlan(actor, plan);
    }
    /** Re-evaluate each native craft in its own queued slice before extraction and before commit. */
    public void validateNative(AgentTool.Context ctx, FluidProcessingPlan plan) throws Exception {
        for (int i = 0; i < plan.count(); i++) {
            int index = i;
            dispatcher.inspectOwned(ctx, () -> {
                var live = currentPlan(ctx.actor(), plan);
                var required = FluidProcessingAdapters.requiredFluid(live.recipe(), plan.mechanismId(), plan.fluidId(), live.player().serverLevel())
                        .orElse(FluidAmount.EMPTY);
                var evaluated = FluidProcessingAdapters.evaluate(live.recipe(), plan.mechanismId(), plan.fluidId(),
                        ItemSnapshotCodec.restore(plan.inputs().get(index), live.player().registryAccess()), required, live.player().serverLevel());
                if (!evaluated.equals(plan.evaluations().get(index))) throw new IllegalStateException("FLUID_NATIVE_RESULT_CHANGED");
                return true;
            });
        }
    }
    private NativeContext currentPlan(Actor actor, FluidProcessingPlan plan) {
        thread(); actor.requireAccess(plan.owner());
        if (!epoch.equals(plan.executionEpoch())) throw new IllegalStateException("FLUID_EXECUTION_EPOCH_CHANGED");
        if (generation.getAsLong() != plan.recipeGeneration()) throw new IllegalStateException("FLUID_RECIPES_RELOADED");
        var player = items.requirePlayer(actor);
        if (!player.serverLevel().dimension().location().toString().equals(plan.dimension())) throw new IllegalStateException("FLUID_DIMENSION_CHANGED");
        Binding binding = bindings.get(plan.id());
        if (binding == null || !binding.plan().owner().equals(actor.owner()))
            throw new IllegalStateException("FLUID_PLAN_RUNTIME_EXPIRED");
        binding.requireOriginal(plan);
        var recipe = current(plan.recipeId());
        if (recipe != null && (recipe.value() != binding.recipe()
                || !BuiltInRegistries.RECIPE_TYPE.getKey(recipe.value().getType()).toString().equals(plan.recipeType())
                || !BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.value().getSerializer()).toString().equals(plan.serializerId())))
            throw new IllegalStateException("FLUID_RECIPE_INSTANCE_CHANGED");
        return new NativeContext(player, recipe);
    }
    private ServerPlayer checkFacts(Actor actor, Facts facts) {
        thread(); var player = items.requirePlayer(actor);
        if (generation.getAsLong() != facts.generation()) throw new IllegalStateException("FLUID_RECIPES_RELOADED");
        if (!player.serverLevel().dimension().location().toString().equals(facts.dimension())) throw new IllegalStateException("FLUID_DIMENSION_CHANGED");
        if (facts.recipe() != null && current(facts.recipe().id().toString()).value() != facts.recipe().value())
            throw new IllegalStateException("FLUID_RECIPE_INSTANCE_CHANGED");
        return player;
    }
    private RecipeHolder<?> current(String id) {
        return id.isEmpty() ? null : server.getRecipeManager().byKey(ResourceLocation.parse(id))
                .orElseThrow(() -> new IllegalArgumentException("FLUID_RECIPE_NOT_FOUND"));
    }
    private List<FluidProcessingPlan.Claim> allocateClaims(List<FluidAmount> inputs, List<FluidInventoryService.Source> sources) {
        var claims = new ArrayList<FluidProcessingPlan.Claim>();
        for (var entry : FluidProcessingPlan.fluidTotals(inputs).entrySet()) {
            int left = Math.toIntExact(entry.getValue()); var fluid = new FluidAmount(entry.getKey(), left);
            for (var source : sources) {
                int amount = (int) Math.min(left, fluidLedger.available(source, fluid));
                if (amount > 0) { claims.add(new FluidProcessingPlan.Claim(UUID.randomUUID(), source, fluid.withAmount(amount))); left -= amount; }
                if (left == 0) break;
            }
            if (left != 0) throw new IllegalStateException("FLUID_MATERIAL_MISSING");
        }
        if (claims.size() > 32) throw new IllegalArgumentException("FLUID_PLAN_CLAIM_LIMIT");
        return List.copyOf(claims);
    }
    static List<MaterialLedger.Stock> deduplicateItems(List<MaterialLedger.Stock> values) {
        if (values.size() > 16384) throw new IllegalArgumentException("MATERIAL_SCAN_LIMIT");
        var unique = new LinkedHashMap<String, MaterialLedger.Stock>(); var pools = new HashMap<String, Long>();
        for (var stock : values) {
            Long pool = pools.putIfAbsent(stock.poolId(), stock.poolUnits());
            if (pool != null && pool != stock.poolUnits()) throw new IllegalStateException("MATERIAL_POOL_CHANGED");
            var previous = unique.putIfAbsent(stock.key(), stock);
            if (previous != null && (previous.unitsPerItem() != stock.unitsPerItem() || !previous.stack().equals(stock.stack())))
                throw new IllegalStateException("MATERIAL_ALIAS_CONFLICT");
            if (previous != null && stock.extractableCount() > previous.extractableCount()) unique.put(stock.key(), stock);
        }
        return List.copyOf(unique.values());
    }
    static ItemSelection selectItems(List<MaterialLedger.Stock> values, Map<String, Long> available, Map<String, Long> pools, int count) {
        if (count < 1 || count > 16) throw new IllegalArgumentException("FLUID_PROCESSING_REQUEST_INVALID");
        var inputs = new ArrayList<MaterialStack>(); var sources = new ArrayList<MaterialLedger.Stock>();
        var needs = new ArrayList<MaterialLedger.Need>(); var used = new HashMap<String, Long>();
        // One ingredient role only. Cheapest equivalent pool units first avoids consuming nine
        // shared units through one compacted item when several smaller accepted items are needed.
        for (var stock : deduplicateItems(values).stream().sorted(Comparator.comparingLong(MaterialLedger.Stock::unitsPerItem)).toList()) {
            long room = Math.max(0, pools.getOrDefault(stock.poolId(), 0L) - used.getOrDefault(stock.poolId(), 0L));
            int amount = (int) Math.min(count - inputs.size(), Math.min(stock.extractableCount(),
                    Math.min(available.getOrDefault(stock.key(), 0L), room / stock.unitsPerItem())));
            if (amount <= 0) continue;
            sources.add(stock); needs.add(new MaterialLedger.Need(stock.key(), amount));
            for (int i = 0; i < amount; i++) inputs.add(stock.stack().withCount(1));
            used.merge(stock.poolId(), Math.multiplyExact((long) amount, stock.unitsPerItem()), Math::addExact);
            if (inputs.size() == count) return new ItemSelection(inputs, sources, needs);
        }
        throw new IllegalStateException("FLUID_ITEM_MATERIAL_MISSING");
    }
    static List<FluidInventoryService.Source> deduplicateFluids(List<FluidInventoryService.Source> values) {
        if (values.size() > 128) throw new IllegalArgumentException("FLUID_PLAN_SOURCE_LIMIT");
        var unique = new LinkedHashMap<String, FluidInventoryService.Source>();
        for (var value : values) {
            var previous = unique.putIfAbsent(value.backend(), value);
            if (previous != null && (!previous.snapshot().equals(value.snapshot())
                    || !previous.authorizationFingerprint().equals(value.authorizationFingerprint())))
                throw new IllegalStateException("FLUID_BACKEND_ALIAS_CONFLICT");
        }
        return List.copyOf(unique.values());
    }
    /** No live handler is mutated: drain and fill share one shadow per complete routing backend. */
    static Map<String, List<FluidTankSnapshot.Tank>> shadows(List<FluidInventoryService.Source> sources, List<FluidProcessingPlan.Claim> claims) {
        var result = new LinkedHashMap<String, List<FluidTankSnapshot.Tank>>();
        for (var source : deduplicateFluids(sources)) result.put(source.backend(), new ArrayList<>(source.snapshot().tanks()));
        for (var claim : claims) {
            var tanks = result.get(claim.source().backend());
            if (tanks == null) continue; // A consumed source need not be an authorized output destination.
            int left = claim.amount().millibuckets();
            var matching = tanks.stream().filter(tank -> tank.fluid().unitJson().equals(claim.amount().unitJson())).toList();
            long held = matching.stream().mapToLong(tank -> tank.fluid().millibuckets()).sum();
            if (held < left) throw new IllegalArgumentException("FLUID_PLAN_DRAIN_OVERALLOCATED");
            // A whole-backend drain may route through several tanks. Without a native routing
            // contract a partial drain cannot promise which tank becomes empty. Conservatively
            // require existing capacity; a full-kind drain or a single matching tank is exact.
            if (matching.size() > 1 && left < held) continue;
            for (int i = 0; i < tanks.size() && left > 0; i++) {
                var tank = tanks.get(i);
                if (!tank.fluid().unitJson().equals(claim.amount().unitJson())) continue;
                int amount = Math.min(left, tank.fluid().millibuckets());
                tanks.set(i, new FluidTankSnapshot.Tank(tank.capacity(), tank.fluid().withAmount(tank.fluid().millibuckets() - amount))); left -= amount;
            }
            if (left != 0) throw new IllegalArgumentException("FLUID_PLAN_DRAIN_OVERALLOCATED");
        }
        return result;
    }
    static int fill(List<FluidTankSnapshot.Tank> tanks, FluidAmount fluid) {
        int left = fluid.millibuckets();
        // Prefer already matching tanks; an empty tank stays available for a different later output.
        for (int pass = 0; pass < 2; pass++) for (int i = 0; i < tanks.size() && left > 0; i++) {
            var tank = tanks.get(i);
            if (pass == 0 ? tank.fluid().empty() || !tank.fluid().unitJson().equals(fluid.unitJson()) : !tank.fluid().empty()) continue;
            int accepted = Math.min(left, tank.capacity() - tank.fluid().millibuckets());
            if (accepted > 0) tanks.set(i, new FluidTankSnapshot.Tank(tank.capacity(), fluid.withAmount(tank.fluid().millibuckets() + accepted)));
            left -= accepted;
        }
        return fluid.millibuckets() - left;
    }
    static List<FluidProcessingPlan.OutputTarget> allocateOutputs(List<FluidAmount> outputs,
            List<FluidInventoryService.Source> sources, List<FluidProcessingPlan.Claim> claims) {
        if (outputs.size() > 64 || outputs.stream().anyMatch(FluidAmount::empty)
                || outputs.stream().mapToLong(FluidAmount::millibuckets).sum() > 1_000_000)
            throw new IllegalArgumentException("FLUID_PLAN_MATERIAL_LIMIT");
        var unique = deduplicateFluids(sources); var shadows = shadows(unique, claims);
        var targets = new ArrayList<FluidProcessingPlan.OutputTarget>();
        for (int i = 0; i < outputs.size(); i++) {
            var fluid = outputs.get(i); int left = fluid.millibuckets();
            for (var source : unique) {
                int accepted = fill(shadows.get(source.backend()), fluid.withAmount(left));
                if (accepted > 0) { targets.add(new FluidProcessingPlan.OutputTarget(i, source, fluid.withAmount(accepted))); left -= accepted; }
                if (left == 0) break;
            }
            if (left > 0) throw new IllegalStateException("FLUID_OUTPUT_FULL");
        }
        if (targets.size() > 256) throw new IllegalArgumentException("FLUID_PLAN_TARGET_LIMIT");
        return List.copyOf(targets);
    }
    /** Removing references performs no native reads and is safe from the privacy IO transaction. */
    public void clearPrivateViews(String owner) {
        synchronized (bindings) { bindings.values().removeIf(binding -> binding.plan().owner().equals(owner)); }
        synchronized (resumeBindings) { resumeBindings.values().removeIf(binding -> binding.review().owner().equals(owner)); }
    }
    @Override public void close() { if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED"); closed = true; bindings.clear(); resumeBindings.clear(); }
    private void thread() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        if (closed) throw new IllegalStateException("SERVER_STOPPING");
    }
}
