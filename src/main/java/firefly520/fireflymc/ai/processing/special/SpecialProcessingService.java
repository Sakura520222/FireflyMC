package firefly520.fireflymc.ai.processing.special;

import com.google.gson.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.processing.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.task.CommittedCall;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.EventHooks;

/** Approved, bounded service for native special recipes. Exact source assignments remain server-authoritative. */
public final class SpecialProcessingService {
    public static final String UNCRAFT = "twilightforest:uncrafting";
    public static final String RECRAFT = "twilightforest:equipment_recrafting";
    public record Assignment(String sourceKey, int roleSlot, int count) {
        public Assignment {
            if (sourceKey == null || sourceKey.length() > 2048 || roleSlot < 0 || roleSlot > 9 || count < 1 || count > 64)
                throw new IllegalArgumentException("SPECIAL_ASSIGNMENT_INVALID");
        }
    }
    public record Outcome(String label, double probability, List<MaterialStack> products) {
        public Outcome {
            products = List.copyOf(products);
            if (!Double.isFinite(probability) || probability < 0 || probability > 1 || products.size() > 128)
                throw new IllegalArgumentException("SPECIAL_OUTCOME_LIMIT");
        }
    }
    public record Plan(int version, UUID id, UUID executionEpoch, UUID serverInstanceId, UUID conversationId,
            String owner, UUID taskId, long privacyEpoch, long recipeGeneration, String dimension,
            String recipeId, String mechanismId, String backingRecipeId, String targetRecipeId, int variantCycle,
            List<VerifiedTransformation.RecipeReference> recipes, List<MaterialStack> inputs,
            List<Assignment> assignments, List<MaterialLedger.Stock> sources, List<MaterialLedger.Need> needs,
            List<Outcome> outcomes, boolean sampled, float experience, String callbackCategory,
            Map<String, String> nativeContext, String policy, String hash) {
        public Plan {
            recipes = List.copyOf(recipes); inputs = List.copyOf(inputs); assignments = List.copyOf(assignments);
            sources = List.copyOf(sources); needs = List.copyOf(needs); outcomes = List.copyOf(outcomes); nativeContext = Map.copyOf(nativeContext);
            if (version != 1 || inputs.isEmpty() || inputs.size() > 10 || assignments.isEmpty() || assignments.size() > 64
                    || needs.size() > 64 || outcomes.isEmpty() || outcomes.size() > 64 || !Float.isFinite(experience) || experience < 0)
                throw new IllegalArgumentException("SPECIAL_PLAN_LIMIT");
        }
        public Plan withHash(String digest) {
            return new Plan(version, id, executionEpoch, serverInstanceId, conversationId, owner, taskId, privacyEpoch,
                    recipeGeneration, dimension, recipeId, mechanismId, backingRecipeId, targetRecipeId, variantCycle,
                    recipes, inputs, assignments, sources, needs, outcomes, sampled, experience, callbackCategory, nativeContext, policy, digest);
        }
        public boolean intact() { return AiJson.digest(AiJson.canonical(withHash(""))).equals(hash); }
    }
    private record Evaluation(List<VerifiedTransformation.RecipeReference> refs, List<MaterialStack> inputs,
            List<Integer> consumed, List<Outcome> outcomes, boolean sampled, float experience,
            String category, Map<String, String> context) {}
    private record Work<T>(TaskControl control, Callable<T> action, CompletableFuture<T> future) {}
    private record CallbackEvidence(boolean success, boolean entered, String error, String outputDigest, int experience,
                                    List<MaterialStack> nativeOutputs) {
        CallbackEvidence { nativeOutputs = List.copyOf(nativeOutputs); }
    }
    private record CapturedCallback(CallbackEvidence evidence, NativeProcessingCallback.Outcome stores) {}
    private final MinecraftServer server;
    private final MaterialInventoryService inventory;
    private final MaterialLedger ledger;
    private final VirtualProcessingService processing;
    private final LongSupplier generation;
    private final SpecialEntityService entities;
    private final UUID epoch = UUID.randomUUID();
    private final ArrayBlockingQueue<Work<?>> main = new ArrayBlockingQueue<>(64);
    private final Map<UUID, Map<ResourceLocation, Recipe<?>>> liveRecipes = new LinkedHashMap<>();
    private final Set<UUID> executing = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public SpecialProcessingService(MinecraftServer server, MaterialInventoryService inventory, MaterialLedger ledger,
            VirtualProcessingService processing, LongSupplier generation) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        this.server = server; this.inventory = inventory; this.ledger = ledger; this.processing = processing; this.generation = generation;
        entities = new SpecialEntityService(this, server, inventory, ledger, processing, generation, epoch);
    }

    public void register(ToolRegistry tools) {
        entities.register(tools);
        tool(tools, "special_processing_sources", "获取专属加工需要的真实库存 sourceKey。只读取本人及工作台授权来源，返回快照和可用数量，不暴露完整私有组件。",
                "{\"snapshotId\":{\"type\":\"string\"},\"offset\":{\"type\":\"integer\",\"minimum\":0}}", List.of(), AgentTool.Risk.READ_ONLY, this::sources);
        tool(tools, "special_processing_inputs", "查看服务端专属配方的真实输入角色和已支持机制。GRID使用3×3 roleSlot 0–8，FUSION为基底0/附加1，SMITHING为模板0/基底1/附加2；缺省空槽不能填虚构材料。",
                "{\"recipeId\":{\"type\":\"string\"},\"offset\":{\"type\":\"integer\",\"minimum\":0}}", List.of(), AgentTool.Risk.READ_ONLY, this::inputs);
        tool(tools, "special_processing_plan", "规划天境/深入天境、暮色、灾变、女仆物品祭坛、法术书和Bumble专属加工。真实sourceKey分配到角色槽，固定配方/代次/组件；仅规划，执行须工作台批准。recipeId与mechanismId二选一；暮色拆/重做及irons_spellbooks:contained_cauldron需backingRecipeId，重做还需targetRecipeId。contained_cauldron角色0为真实目标原料，1–4为真实含液容器，5–9为输出空容器；所有mB必须封装回真实容器，不接受凭空流体或客户端产物。",
                "{\"snapshotId\":{\"type\":\"string\"},\"recipeId\":{\"type\":\"string\"},\"mechanismId\":{\"type\":\"string\"},\"backingRecipeId\":{\"type\":\"string\"},\"targetRecipeId\":{\"type\":\"string\"},\"variantCycle\":{\"type\":\"integer\",\"minimum\":-4096,\"maximum\":4096},\"assignments\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":64,\"items\":{\"type\":\"object\",\"properties\":{\"sourceKey\":{\"type\":\"string\",\"maxLength\":2048},\"roleSlot\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":9},\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":64}},\"required\":[\"sourceKey\",\"roleSlot\",\"count\"],\"additionalProperties\":false}}}",
                List.of("assignments"), AgentTool.Risk.READ_ONLY, (ctx, args) -> success("真实材料与专属语义已核验，尚未扣料；执行需工作台批准。", view(plan(ctx, args))));
        tool(tools, "special_processing_execute", "执行本人已经在工作台批准的固定专属加工计划。真实扣料、产物/组件与概率核验、完整回调证据和托管交付；重复operation不再执行。",
                "{\"planId\":{\"type\":\"string\"},\"planHash\":{\"type\":\"string\"}}", List.of("planId", "planHash"), AgentTool.Risk.PLAYER_STATE,
                (ctx, args) -> execute(ctx, load(ctx.workspace(), ctx.actor(), UUID.fromString(args.get("planId").getAsString()), args.get("planHash").getAsString())));
    }

    private AgentTool.Result sources(AgentTool.Context ctx, JsonObject args) throws Exception {
        var snapshot = args.has("snapshotId") ? onMain(ctx.control(), () -> inventory.snapshot(ctx.actor(), UUID.fromString(args.get("snapshotId").getAsString())))
                : ctx.control().await(inventory.scan(ctx.actor()), 30000);
        int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
        if (offset < 0 || offset > snapshot.rows().size()) throw new IllegalArgumentException("SPECIAL_SOURCE_OFFSET");
        var available = ledger.available(snapshot.rows()); var rows = new JsonArray();
        for (var row : snapshot.rows().subList(offset, Math.min(snapshot.rows().size(), offset + 24))) {
            JsonObject data = new JsonObject(); data.addProperty("sourceKey", row.key()); data.addProperty("sourceId", row.sourceId());
            data.addProperty("slot", row.slot()); data.addProperty("available", available.getOrDefault(row.key(), 0L)); data.add("item", itemView(row.stack())); rows.add(data);
        }
        JsonObject data = new JsonObject(); data.addProperty("snapshotId", snapshot.id().toString()); data.add("sources", rows);
        data.addProperty("nextOffset", Math.min(snapshot.rows().size(), offset + 24)); data.addProperty("total", snapshot.rows().size());
        return success("真实可用来源。", data);
    }

    private AgentTool.Result inputs(AgentTool.Context ctx, JsonObject args) throws Exception {
        if (!args.has("recipeId")) {
            int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
            if (offset < 0 || offset > 20000) throw new IllegalArgumentException("SPECIAL_RECIPE_OFFSET");
            var cursor = onMain(ctx.control(), () -> {
                inventory.requirePlayer(ctx.actor());
                return new AbstractMap.SimpleImmutableEntry<>(generation.getAsLong(), server.getRecipeManager().getRecipes().iterator());
            });
            var found = new ArrayList<String>(); int matched = 0; boolean more = true;
            while (more && found.size() < 25) {
                var batch = onMain(ctx.control(), () -> {
                    inventory.requirePlayer(ctx.actor());
                    if (generation.getAsLong() != cursor.getKey()) throw new IllegalStateException("SPECIAL_RECIPES_RELOADED");
                    var ids = new ArrayList<String>();
                    for (int i = 0; i < 32 && cursor.getValue().hasNext(); i++) {
                        var holder = cursor.getValue().next();
                        if (SpecialItemProcessingAdapter.supports(holder.value()) || BumbleCandleAdapter.supports(holder.value())) ids.add(holder.id().toString());
                    }
                    return new AbstractMap.SimpleImmutableEntry<>(cursor.getValue().hasNext(), ids);
                });
                more = batch.getKey();
                for (String id : batch.getValue()) if (matched++ >= offset && found.size() < 25) found.add(id);
            }
            boolean hasMore = found.size() > 24 || more;
            var data = new JsonObject(); data.addProperty("recipeGeneration", cursor.getKey());
            data.add("recipes", AiJson.GSON.toJsonTree(found.stream().limit(24).toList()));
            data.addProperty("hasMore", hasMore); data.addProperty("nextOffset", offset + Math.min(found.size(), 24));
            var mechanisms = onMain(ctx.control(), () -> {
                var names = new ArrayList<String>();
                if (TwilightUncraftingAdapter.available()) names.addAll(List.of(UNCRAFT, RECRAFT));
                if (SpecialNative.version("irons_spellbooks", "1.21.1-3.16.2")) names.add(ContainedFluidProcessingAdapter.ID);
                return names;
            });
            data.add("mechanisms", AiJson.GSON.toJsonTree(mechanisms));
            return success("专属物品加工接口。", data);
        }
        return onMain(ctx.control(), () -> {
            ServerPlayer player = inventory.requirePlayer(ctx.actor()); JsonObject data = new JsonObject();
            data.addProperty("recipeGeneration", generation.getAsLong());
            if (args.has("recipeId")) {
                var holder = current(args.get("recipeId").getAsString()); var profile = SpecialItemProcessingAdapter.profile(holder.value());
                if (profile == null && !BumbleCandleAdapter.supports(holder.value())) throw new IllegalArgumentException("SPECIAL_ITEM_ADAPTER_NOT_AVAILABLE");
                data.addProperty("recipeId", holder.id().toString()); data.addProperty("roles", profile == null ? "GRID" : profile.input().name());
                var list = profile == null ? holder.value().getIngredients() : SpecialItemProcessingAdapter.ingredients(holder, player.serverLevel());
                var ingredients = new JsonArray();
                for (int slot = 0; slot < Math.min(list.size(), 9); slot++) {
                    var row = new JsonObject(); row.addProperty("ingredientIndex", slot);
                    row.add("candidateItems", AiJson.GSON.toJsonTree(Arrays.stream(list.get(slot).getItems()).limit(16)
                            .map(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).toString()).toList())); ingredients.add(row);
                }
                data.add("ingredients", ingredients); data.addProperty("warning", "动态配方/有序形状仍由完整输入的native matches核验；索引不是自由拼料授权。");
            }
            return success("专属物品加工接口。", data);
        });
    }

    public Plan plan(AgentTool.Context ctx, JsonObject args) throws Exception {
        if (ctx.taskId() == null || args.has("recipeId") == args.has("mechanismId")) throw new IllegalArgumentException("SPECIAL_REFERENCE_OR_TASK_REQUIRED");
        String recipe = text(args, "recipeId"), mechanism = text(args, "mechanismId");
        String backing = text(args, "backingRecipeId"), target = text(args, "targetRecipeId");
        int cycle = args.has("variantCycle") ? args.get("variantCycle").getAsInt() : 0;
        if (cycle < -4096 || cycle > 4096) throw new IllegalArgumentException("SPECIAL_VARIANT_LIMIT");
        List<Assignment> assignments = Arrays.asList(AiJson.GSON.fromJson(args.getAsJsonArray("assignments"), Assignment[].class));
        if (assignments.isEmpty() || assignments.size() > 64) throw new IllegalArgumentException("SPECIAL_ASSIGNMENT_LIMIT");
        long privacy = ctx.workspace().conversation().privacyEpoch();
        var snapshot = args.has("snapshotId") ? onMain(ctx.control(), () -> inventory.snapshot(ctx.actor(), UUID.fromString(args.get("snapshotId").getAsString())))
                : ctx.control().await(inventory.scan(ctx.actor()), 30000);
        var rows = new LinkedHashMap<String, MaterialLedger.Stock>();
        for (var row : snapshot.rows()) rows.merge(row.key(), row, (a, b) -> a.extractableCount() >= b.extractableCount() ? a : b);
        var needsMap = new LinkedHashMap<String, Integer>(); var chosen = new LinkedHashMap<String, MaterialLedger.Stock>();
        for (var assignment : assignments) {
            var source = rows.get(assignment.sourceKey());
            if (source == null) throw new IllegalArgumentException("SPECIAL_SOURCE_NOT_IN_SNAPSHOT");
            needsMap.merge(source.key(), assignment.count(), Math::addExact); chosen.putIfAbsent(source.key(), source);
        }
        var stocks = List.copyOf(chosen.values());
        var needs = needsMap.entrySet().stream().map(e -> new MaterialLedger.Need(e.getKey(), e.getValue())).toList();
        var available = ledger.available(stocks); var pools = ledger.availablePools(stocks); var usedPools = new HashMap<String, Long>();
        for (var need : needs) {
            var stock = chosen.get(need.stockKey());
            if (need.count() > available.getOrDefault(need.stockKey(), 0L)) throw new IllegalStateException("SPECIAL_MATERIAL_MISSING");
            usedPools.merge(stock.poolId(), Math.multiplyExact((long) need.count(), stock.unitsPerItem()), Math::addExact);
        }
        for (var usage : usedPools.entrySet()) if (usage.getValue() > pools.getOrDefault(usage.getKey(), 0L)) throw new IllegalStateException("SPECIAL_SHARED_POOL_OVERALLOCATED");
        Plan saved = onMain(ctx.control(), () -> {
            ServerPlayer player = inventory.requirePlayer(ctx.actor());
            int size = inputSize(recipe, mechanism);
            var materialInputs = new ArrayList<>(Collections.nCopies(size, MaterialStack.EMPTY));
            for (var assignment : assignments) {
                if (assignment.roleSlot() >= size) throw new IllegalArgumentException("SPECIAL_ROLE_SLOT_INVALID");
                var unit = chosen.get(assignment.sourceKey()).stack().withCount(assignment.count());
                var previous = materialInputs.get(assignment.roleSlot());
                if (!previous.empty() && !previous.itemJson().equals(unit.itemJson())) throw new IllegalArgumentException("SPECIAL_ROLE_COMPONENT_MISMATCH");
                materialInputs.set(assignment.roleSlot(), unit.withCount(unit.count() + previous.count()));
            }
            Evaluation evaluation = evaluate(recipe, mechanism, backing, target, cycle, materialInputs, player);
            for (int i = 0; i < materialInputs.size(); i++) if (materialInputs.get(i).count() != evaluation.consumed().get(i))
                throw new IllegalArgumentException("SPECIAL_ROLE_COUNT_MUST_EQUAL_NATIVE_CONSUMPTION");
            UUID id = UUID.randomUUID();
            Plan value = new Plan(1, id, epoch, ctx.workspace().metadata().serverInstanceId(), ctx.workspace().primaryConversationId(), ctx.actor().owner(),
                    ctx.taskId(), privacy, generation.getAsLong(), player.serverLevel().dimension().location().toString(), recipe, mechanism, backing, target, cycle,
                    evaluation.refs(), List.copyOf(materialInputs), assignments, stocks, needs, evaluation.outcomes(), evaluation.sampled(), evaluation.experience(),
                    evaluation.category(), evaluation.context(), "MATERIALS_ONLY@1", "");
            var runtime = new LinkedHashMap<ResourceLocation, Recipe<?>>();
            for (var ref : value.recipes()) runtime.put(ref.id(), current(ref.id().toString()).value());
            liveRecipes.put(id, Map.copyOf(runtime));
            while (liveRecipes.size() > 256) liveRecipes.remove(liveRecipes.keySet().iterator().next());
            return value.withHash(AiJson.digest(AiJson.canonical(value)));
        });
        synchronized (ctx.workspace().conversation()) {
            ctx.control().check();
            if (privacy != ctx.workspace().conversation().privacyEpoch()) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
            var task = ctx.workspace().conversation().task(ctx.taskId());
            if (task == null || task.state().terminal()) throw new IllegalStateException("SPECIAL_TASK_ENDED");
            String serialized = AiJson.GSON.toJson(saved);
            if (serialized.getBytes(StandardCharsets.UTF_8).length > 8 * 1024 * 1024) throw new IllegalArgumentException("SPECIAL_PLAN_SIZE_LIMIT");
            if (Files.exists(ctx.workspace().artifact(saved.id()))) throw new IllegalStateException("SPECIAL_PLAN_ALREADY_EXISTS");
            AiJson.write(ctx.workspace().artifact(saved.id()), saved);
            var manifest = new JsonObject(); manifest.addProperty("planId", saved.id().toString()); manifest.addProperty("hash", saved.hash());
            manifest.addProperty("recipeId", saved.recipeId()); manifest.addProperty("mechanismId", saved.mechanismId());
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "special_processing_plan", null, manifest, null)));
        }
        return saved;
    }

    public Plan load(Workspace workspace, Actor actor, UUID id, String hash) throws Exception {
        synchronized (workspace.conversation()) {
            actor.requireAccess(workspace.owner());
            boolean indexed = workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events().stream()
                    .anyMatch(e -> e.kind().equals("special_processing_plan") && id.toString().equals(e.data().get("planId").getAsString())
                            && hash.equals(e.data().get("hash").getAsString()));
            Path file = workspace.artifact(id);
            if (!indexed || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 8 * 1024 * 1024)
                throw new IllegalArgumentException("SPECIAL_PLAN_NOT_FOUND");
            Plan plan = AiJson.GSON.fromJson(Files.readString(file), Plan.class);
            if (!plan.id().equals(id) || !plan.hash().equals(hash) || !plan.intact() || !plan.owner().equals(actor.owner())
                    || !plan.serverInstanceId().equals(workspace.metadata().serverInstanceId()) || !plan.conversationId().equals(workspace.primaryConversationId())
                    || plan.privacyEpoch() != workspace.conversation().privacyEpoch() || !plan.policy().equals("MATERIALS_ONLY@1"))
                throw new SecurityException("SPECIAL_PLAN_IDENTITY_OR_HASH_CHANGED");
            return plan;
        }
    }

    public AgentTool.Result execute(AgentTool.Context ctx, Plan plan) throws Exception {
        ctx.actor().requireAccess(plan.owner());
        if (!Objects.equals(ctx.taskId(), plan.taskId())) throw new SecurityException("SPECIAL_TASK_MISMATCH");
        if (!executing.add(plan.id())) return failure("SPECIAL_ALREADY_EXECUTING", "此固定计划正在执行，未重复派发。", new JsonObject());
        try {
            var prior = ledger.find(ctx.actor(), plan.id());
            if (prior.isPresent()) return failure(prior.get().uncertain() ? "RECOVERY_REQUIRED" : "SPECIAL_ALREADY_RECORDED", "该操作已有记录，未重放。", operation(prior.get()));
            onMain(ctx.control(), () -> { validate(ctx, plan); return true; });
            for (Outcome outcome : plan.outcomes()) onMain(ctx.control(), () -> {
                check(ctx, plan);
                if (!inventory.outputFits(ctx.actor(), outcome.products(), plan.sources(), plan.needs())) throw new IllegalStateException("OUTPUT_FULL");
                return true;
            });
            for (var need : plan.needs()) onMain(ctx.control(), () -> {
                check(ctx, plan); var source = stock(plan, need.stockKey());
                return inventory.prepareExtraction(ctx.actor(), source, Math.min(need.count(), source.maxTransferCount()));
            });
            MaterialLedger.Operation op = ledger.reserve(ctx.actor(), ctx.taskId(), plan.id(), plan.hash(), plan.sources(), plan.needs());
            try {
                op = processing.extractReserved(ctx, op, () -> check(ctx, plan));
                if (op.uncertain()) return failure("RECOVERY_REQUIRED", "原生取料证据不确定，已停止。", operation(op));
                processing.awaitInputSaves(ctx, plan.id(), fresh -> finishSavedEscrow(fresh, plan));
                return processSavedEscrow(ctx, plan);
            } catch (AgentTool.Deferred deferred) { throw deferred; }
            catch (Exception failure) {
                var saved = ledger.get(ctx.actor(), plan.id());
                if (saved.intent() != null) saved = ledger.markUncertain(ctx.actor(), plan.id(), "SPECIAL_INTERRUPTED_DURING_INTENT");
                if (failure instanceof TaskControl.Stopped stopped) throw stopped;
                return failure(saved.uncertain() ? "RECOVERY_REQUIRED" : "PROCESSING_PAUSED", "专属加工未全部完成：" + safe(failure) + "；真实物料与证据已保留。", operation(saved));
            }
        } catch (AgentTool.Deferred deferred) { throw deferred; }
        catch (TaskControl.Stopped stopped) { throw stopped; }
        catch (Exception rejected) {
            if (ledger.find(ctx.actor(), plan.id()).isPresent()) throw rejected;
            var data = new JsonObject(); data.addProperty("dispatch", "REJECTED_BEFORE_EXECUTION");
            return failure(safe(rejected), "执行前拒绝，未扣料或加工：" + safe(rejected), data);
        } finally { executing.remove(plan.id()); }
    }

    private AgentTool.Result finishSavedEscrow(AgentTool.Context ctx, Plan plan) throws Exception {
        ctx.actor().requireAccess(plan.owner());
        if (!Objects.equals(ctx.taskId(), plan.taskId())) throw new SecurityException("SPECIAL_TASK_MISMATCH");
        if (!executing.add(plan.id())) throw new IllegalStateException("SPECIAL_ALREADY_EXECUTING");
        try { return processSavedEscrow(ctx, plan); }
        catch (AgentTool.Deferred deferred) { throw deferred; }
        catch (Exception failure) {
            var op = ledger.get(ctx.actor(), plan.id());
            if (op.intent() != null && !op.uncertain()) op = ledger.markUncertain(ctx.actor(), plan.id(), "SPECIAL_INTERRUPTED_DURING_INTENT");
            if (failure instanceof TaskControl.Stopped stopped) throw stopped;
            return failure(op.uncertain() ? "RECOVERY_REQUIRED" : "PROCESSING_PAUSED", "专属加工续接未完成；真实物料与证据保持托管。", operation(op));
        } finally { executing.remove(plan.id()); }
    }
    private AgentTool.Result processSavedEscrow(AgentTool.Context ctx, Plan plan) throws Exception {
        ctx.actor().requireAccess(plan.owner());
        if (!Objects.equals(ctx.taskId(), plan.taskId())) throw new SecurityException("SPECIAL_TASK_MISMATCH");
        var op = ledger.get(ctx.actor(), plan.id());
        if (!op.planHash().equals(plan.hash())) throw new SecurityException("SPECIAL_PLAN_CHANGED");
        processing.awaitInputSaves(ctx, plan.id(), fresh -> finishSavedEscrow(fresh, plan));
        for (var input : op.escrow()) if (inventory.protectedByOwner(ctx.actor(), input)) throw new SecurityException("MATERIAL_PROTECTED_BY_OWNER");
        onMain(ctx.control(), () -> {
            check(ctx, plan);
            for (var outcome : plan.outcomes()) if (!inventory.outputFits(ctx.actor(), outcome.products(), List.of(), List.of())) throw new IllegalStateException("OUTPUT_FULL");
            return true;
        });
        onMain(ctx.control(), () -> { validate(ctx, plan); return true; });
        List<MaterialStack> products;
        if (plan.sampled()) {
            var selection = onMain(ctx.control(), () -> {
                ServerPlayer player = check(ctx, plan); validate(ctx, plan);
                long seed = player.getRandom().nextLong();
                var sampled = BumbleCandleAdapter.sample(current(plan.recipeId()), restore(plan.inputs(), player), seed, player.serverLevel());
                List<MaterialStack> sampledProducts = products(sampled, player);
                if (plan.outcomes().stream().noneMatch(o -> o.products().equals(sampledProducts))) throw new IllegalStateException("SPECIAL_SAMPLE_OUTSIDE_APPROVAL");
                return new AbstractMap.SimpleImmutableEntry<>(seed, sampledProducts);
            });
            products = selection.getValue(); ctx.control().check();
            op = ledger.produceSampled(ctx.actor(), plan.id(), products, "bumble-candle:whole-batch;seed=" + selection.getKey() + ";plan=" + plan.hash());
        } else {
            products = plan.outcomes().getFirst().products();
            op = ledger.produce(ctx.actor(), plan.id(), products, "special=" + plan.hash() + ";generation=" + plan.recipeGeneration() + ";policy=MATERIALS_ONLY;paidCosts=0");
        }
        if (plan.callbackCategory().equals("NO_CALLBACK")) op = ledger.noCallbacksRequired(ctx.actor(), plan.id(), "Native special path has no callback");
        else {
            final int xp = onMain(ctx.control(), () -> {
                ServerPlayer player = check(ctx, plan); int integer = (int) Math.floor(plan.experience());
                return integer + (player.getRandom().nextFloat() < plan.experience() - integer ? 1 : 0);
            });
            String callbackWitness = UUID.randomUUID().toString();
            String callbackIntent = plan.callbackCategory() + ";witness=" + callbackWitness + ";xp=" + xp;
            ledger.beginCallback(ctx.actor(), plan.id(), callbackIntent, 1);
            List<MaterialStack> observedProducts = products;
            CapturedCallback evidence;
            try {
            evidence = processing.commitOwned(ctx, () -> {
                ServerPlayer player = check(ctx, plan);
                var observed = new java.util.concurrent.atomic.AtomicReference<CallbackEvidence>();
                var stores = NativeProcessingCallback.run(player, () -> {
                    var value = callback(ctx, plan, observedProducts, xp); observed.set(value);
                    if (!value.success()) throw new IllegalStateException(value.error());
                }, xp);
                var domain = observed.get();
                if (domain == null) domain = new CallbackEvidence(false, stores.entered(), stores.code(), "", 0, List.of());
                return new CapturedCallback(domain, stores);
            }, observed -> {
                JsonObject data = AiJson.GSON.toJsonTree(observed.evidence()).getAsJsonObject(); data.addProperty("operationId", plan.id().toString());
                data.addProperty("witness", callbackWitness); data.addProperty("planHash", plan.hash());
                data.addProperty("nativeStoresCompleted", observed.stores().completed()); data.addProperty("nativeStoreError", observed.stores().code());
                data.addProperty("nativeObservations", observed.stores().observations());
                ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "special_callback_evidence", null, data, callbackWitness)));
                return processing.recordNativeCallbackPart(ctx, plan.id(), callbackIntent, 0, observed.stores());
            });
            } catch (CommittedCall.Rejected rejected) {
                ledger.rejectionEvidence(ctx.actor(), plan.id(), callbackIntent, "callback", "CANCELLED_BEFORE_NATIVE_CALLBACK");
                if (rejected.getCause() instanceof TaskControl.Stopped stopped) throw stopped;
                throw rejected;
            }
            op = ledger.get(ctx.actor(), plan.id());
            if (!evidence.evidence().success() || !evidence.stores().completed() || op.uncertain())
                return failure("RECOVERY_REQUIRED", "原生回调结果与保存义务已记录，需先核对；未重放或交付不确定产物。", operation(op));
        }
        op = processing.deliver(ctx, op);
        return processing.awaitFinalResult(ctx, plan.id(), current -> current.state() == MaterialLedger.State.COMPLETED
                ? success("专属加工已真实扣料、核验、交付并确认原生保存；附加费用为零。", operation(current))
                : failure("PROCESSING_PAUSED", "已核验产物仍在本人托管，等待可用输出位置。", operation(current)));
    }

    private Evaluation evaluate(String recipeId, String mechanism, String backing, String target, int cycle, List<MaterialStack> materials, ServerPlayer player) {
        var input = restore(materials, player); VerifiedTransformation verified;
        if (mechanism.isEmpty()) {
            RecipeHolder<?> holder = current(recipeId); unlocked(player, holder);
            if (BumbleCandleAdapter.supports(holder.value())) {
                var preview = BumbleCandleAdapter.preview(holder, input, player.serverLevel());
                var outcomes = preview.outcomes().stream().map(o -> {
                    var values = new ArrayList<MaterialStack>(); values.add(o.stack()); values.addAll(preview.remainders().stream().filter(s -> !s.empty()).toList());
                    return new Outcome(o.effectId(), o.probability(), totals(values));
                }).toList();
                return new Evaluation(List.of(new VerifiedTransformation.RecipeReference(holder.id(), ResourceLocation.withDefaultNamespace("crafting"))),
                        materials, materials.stream().map(s -> s.empty() ? 0 : 1).toList(), outcomes, true, 0, "BUMBLE_CANDLE",
                        Map.of("distribution", "UNIFORM_CATEGORICAL_WHOLE_BATCH", "version", "7.15.2+1.21.1-neoforge"));
            }
            verified = SpecialItemProcessingAdapter.verify(holder, input, player.serverLevel());
        } else if (mechanism.equals(UNCRAFT)) verified = TwilightUncraftingAdapter.verify(current(backing), input.getFirst(), cycle, player.serverLevel());
        else if (mechanism.equals(RECRAFT)) verified = TwilightRecraftingAdapter.verify(current(backing), input.getFirst(), cycle, current(target), input.subList(1, 10), player);
        else if (mechanism.equals(ContainedFluidProcessingAdapter.ID)) verified = ContainedFluidProcessingAdapter.verify(current(backing), input, player.serverLevel());
        else throw new IllegalArgumentException("SPECIAL_MECHANISM_NOT_AVAILABLE");
        return new Evaluation(verified.backingRecipes(), materials, verified.consumedBySlot(), List.of(new Outcome("deterministic", 1, products(verified, player))),
                false, verified.experience().perOperation(), verified.callbackPlan().category(), verified.contextRequirements());
    }

    private ServerPlayer check(AgentTool.Context ctx, Plan plan) {
        if (closed || !epoch.equals(plan.executionEpoch())) throw new IllegalStateException("SPECIAL_EXECUTION_EPOCH_CHANGED");
        if (generation.getAsLong() != plan.recipeGeneration()) throw new IllegalStateException("SPECIAL_RECIPES_RELOADED");
        if (!plan.owner().equals(ctx.actor().owner()) || !plan.serverInstanceId().equals(ctx.workspace().metadata().serverInstanceId())
                || !plan.conversationId().equals(ctx.workspace().primaryConversationId()) || plan.privacyEpoch() != ctx.workspace().conversation().privacyEpoch())
            throw new SecurityException("SPECIAL_WORKSPACE_CHANGED");
        ServerPlayer player = inventory.requirePlayer(ctx.actor());
        if (!plan.dimension().equals(player.serverLevel().dimension().location().toString())) throw new IllegalStateException("SPECIAL_DIMENSION_CHANGED");
        Map<ResourceLocation, Recipe<?>> runtime = liveRecipes.get(plan.id());
        if (runtime == null) throw new IllegalStateException("SPECIAL_PLAN_RUNTIME_EXPIRED");
        for (var ref : plan.recipes()) {
            var holder = current(ref.id().toString());
            if (holder.value() != runtime.get(ref.id()) || !BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()).equals(ref.type()))
                throw new IllegalStateException("SPECIAL_RECIPE_INSTANCE_CHANGED");
        }
        return player;
    }

    private void validate(AgentTool.Context ctx, Plan plan) {
        if (!plan.intact()) throw new SecurityException("SPECIAL_PLAN_CHANGED");
        ServerPlayer player = check(ctx, plan);
        Evaluation current = evaluate(plan.recipeId(), plan.mechanismId(), plan.backingRecipeId(), plan.targetRecipeId(), plan.variantCycle(), plan.inputs(), player);
        if (!current.outcomes().equals(plan.outcomes()) || !current.refs().equals(plan.recipes()) || current.sampled() != plan.sampled()
                || current.experience() != plan.experience() || !current.category().equals(plan.callbackCategory()) || !current.context().equals(plan.nativeContext()))
            throw new IllegalStateException("SPECIAL_NATIVE_RESULT_CHANGED");
    }

    private CallbackEvidence callback(AgentTool.Context ctx, Plan plan, List<MaterialStack> products, int experience) {
        boolean entered = false;
        var nativeOutputs = new ArrayList<MaterialStack>();
        ServerPlayer player;
        try { player = check(ctx, plan); } catch (Exception rejected) { return new CallbackEvidence(false, false, safe(rejected), "", 0, List.of()); }
        var previous = CommonHooks.getCraftingPlayer(); int oldLevel = player.experienceLevel, oldTotal = player.totalExperience; float oldProgress = player.experienceProgress;
        try {
            CommonHooks.setCraftingPlayer(player);
            var inputs = restore(plan.inputs(), player);
            entered = true;
            if (plan.mechanismId().equals(UNCRAFT)) TwilightUncraftingAdapter.callback(inputs.getFirst(), player);
            else if (plan.callbackCategory().equals("MAID_ALTAR_ITEM")) SpecialItemProcessingAdapter.altarCallback(current(plan.recipeId()), player);
            else {
                boolean crafting = plan.callbackCategory().equals("BUMBLE_CANDLE") || plan.callbackCategory().equals("CRAFTING_RESULT_SLOT");
                boolean smelted = Set.of("AETHER_FURNACE", "FURNACE_RESULT_SLOT", "DEEP_AETHER_COMBINER").contains(plan.callbackCategory());
                boolean award = !plan.callbackCategory().equals("DEEP_AETHER_COMBINER");
                RecipeHolder<?> holder = current(plan.mechanismId().equals(RECRAFT) ? plan.targetRecipeId() : plan.recipeId());
                List<ItemStack> crafted;
                if (plan.sampled()) {
                    var candidate = BumbleCandleAdapter.preview(holder, inputs, player.serverLevel()).outcomes().stream()
                            .filter(o -> products.stream().anyMatch(s -> s.itemJson().equals(o.stack().itemJson()))).findFirst().orElseThrow();
                    crafted = List.of(ItemSnapshotCodec.restore(candidate.stack(), player.registryAccess()));
                } else {
                    VerifiedTransformation verified = plan.mechanismId().equals(RECRAFT)
                            ? TwilightRecraftingAdapter.verify(current(plan.backingRecipeId()), inputs.getFirst(), plan.variantCycle(), holder, inputs.subList(1, 10), player)
                            : SpecialItemProcessingAdapter.verify(holder, inputs, player.serverLevel());
                    crafted = verified.outputs();
                }
                for (var output : crafted) {
                    var before = ItemSnapshotCodec.capture(output, player.registryAccess());
                    try {
                        output.onCraftedBy(player.serverLevel(), player, output.getCount());
                        if (crafting) EventHooks.firePlayerCraftingEvent(player, output, new SimpleContainer(inputs.toArray(ItemStack[]::new)));
                        if (smelted) EventHooks.firePlayerSmeltedEvent(player, output);
                    } finally { nativeOutputs.add(ItemSnapshotCodec.capture(output, player.registryAccess())); }
                    if (!before.equals(ItemSnapshotCodec.capture(output, player.registryAccess()))) throw new IllegalStateException("SPECIAL_CALLBACK_OUTPUT_CHANGED");
                }
                if (award && !holder.value().isSpecial()) { player.awardRecipes(List.of(holder)); player.triggerRecipeCrafted(holder, inputs); }
                if (experience > 0) ExperienceOrb.award(player.serverLevel(), player.position(), experience);
            }
            return new CallbackEvidence(true, entered, "", AiJson.digest(AiJson.canonical(products)), experience, nativeOutputs);
        } catch (Exception | LinkageError uncertain) {
            return new CallbackEvidence(false, entered, "SPECIAL_CALLBACK_UNCERTAIN", AiJson.digest(AiJson.canonical(products)), experience, nativeOutputs);
        } finally {
            if (player.totalExperience < oldTotal || player.experienceLevel < oldLevel || player.experienceLevel == oldLevel && player.experienceProgress < oldProgress) {
                player.experienceLevel = oldLevel; player.totalExperience = oldTotal; player.experienceProgress = oldProgress;
            }
            CommonHooks.setCraftingPlayer(previous);
        }
    }

    public String review(Plan plan) {
        StringBuilder text = new StringBuilder("确定的专属加工计划\n\n").append(plan.recipeId().isEmpty() ? "机制：" + plan.mechanismId() : "配方：" + plan.recipeId())
                .append("\n一次操作，配方代次 ").append(plan.recipeGeneration()).append("\n\n真实材料：\n");
        for (var need : plan.needs()) text.append("• ").append(itemId(stock(plan, need.stockKey()).stack())).append(" × ").append(need.count())
                .append("（").append(stock(plan, need.stockKey()).sourceId()).append("，槽位 ").append(stock(plan, need.stockKey()).slot()).append("）\n");
        text.append(plan.sampled() ? "\n概率结果（一次只抽其中一整批，恢复不重抽）：\n" : "\n核验产物与返还：\n");
        for (var outcome : plan.outcomes()) {
            text.append(outcome.label()).append(" · ").append(String.format(Locale.ROOT, "%.2f%%", outcome.probability() * 100)).append('\n');
            for (var product : outcome.products()) text.append("• ").append(itemId(product)).append(" × ").append(product.count()).append("，组件摘要 ").append(product.fingerprint(), 0, 12).append('\n');
        }
        return text.append("\n免设备、燃料、能耗、经验费用、等待和辅助工具耐久；真实材料照常消耗。\n计划摘要：").append(plan.hash()).toString();
    }
    public String review(Workspace workspace, Actor actor, Plan plan) { actor.requireAccess(workspace.owner()); if (!plan.owner().equals(actor.owner())) throw new SecurityException("SPECIAL_OWNER_MISMATCH"); return review(plan); }
    private static JsonObject view(Plan plan) {
        var data = new JsonObject(); data.addProperty("planId", plan.id().toString()); data.addProperty("planHash", plan.hash());
        data.addProperty("recipeId", plan.recipeId()); data.addProperty("mechanismId", plan.mechanismId()); data.addProperty("costPolicy", plan.policy());
        data.addProperty("sampled", plan.sampled()); data.addProperty("possibleOutcomes", plan.outcomes().size());
        data.add("outputs", AiJson.GSON.toJsonTree(plan.outcomes().stream().limit(8).map(o -> Map.of("probability", o.probability(), "items", o.products().stream().map(SpecialProcessingService::itemView).toList())).toList()));
        return data;
    }
    private static JsonObject operation(MaterialLedger.Operation op) {
        var data = new JsonObject(); data.addProperty("operationId", op.operationId().toString()); data.addProperty("state", op.state().name()); data.addProperty("reason", op.reason());
        data.add("heldInputs", AiJson.GSON.toJsonTree(op.escrow().stream().map(SpecialProcessingService::itemView).toList()));
        data.add("undelivered", AiJson.GSON.toJsonTree(op.remainingProducts().stream().map(SpecialProcessingService::itemView).toList())); return data;
    }
    private static JsonObject itemView(MaterialStack item) { var data = new JsonObject(); data.addProperty("itemId", itemId(item)); data.addProperty("count", item.count()); if (!item.empty()) data.addProperty("componentsDigest", item.fingerprint()); return data; }
    private static String itemId(MaterialStack item) { return item.empty() ? "minecraft:air" : AiJson.object(item.itemJson(), 1_048_576, 32).get("id").getAsString(); }
    private static String text(JsonObject args, String name) { return args.has(name) ? args.get(name).getAsString() : ""; }
    private static List<ItemStack> restore(List<MaterialStack> values, ServerPlayer player) { return values.stream().map(s -> ItemSnapshotCodec.restore(s, player.registryAccess())).toList(); }
    private static List<MaterialStack> products(VerifiedTransformation verified, ServerPlayer player) {
        var values = new ArrayList<MaterialStack>();
        for (var output : verified.outputs()) if (!output.isEmpty()) values.add(ItemSnapshotCodec.capture(output, player.registryAccess()));
        for (var remainder : verified.remaindersBySlot()) if (!remainder.isEmpty()) values.add(ItemSnapshotCodec.capture(remainder, player.registryAccess()));
        return totals(values);
    }
    private static List<MaterialStack> totals(List<MaterialStack> values) {
        var totals = new TreeMap<String, MaterialStack>();
        for (var value : values) if (!value.empty()) totals.merge(value.itemJson(), value, (a, b) -> a.withCount(Math.addExact(a.count(), b.count())));
        if (totals.size() > 128) throw new IllegalArgumentException("SPECIAL_OUTPUT_LIMIT");
        return List.copyOf(totals.values());
    }
    private static MaterialLedger.Stock stock(Plan plan, String key) { return plan.sources().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow(); }
    private RecipeHolder<?> current(String id) { return server.getRecipeManager().byKey(ResourceLocation.parse(id)).orElseThrow(() -> new IllegalArgumentException("SPECIAL_RECIPE_NOT_FOUND")); }
    private int inputSize(String recipeId, String mechanismId) {
        if (mechanismId.equals(UNCRAFT)) return 1;
        if (mechanismId.equals(RECRAFT)) return 10;
        if (mechanismId.equals(ContainedFluidProcessingAdapter.ID)) return 10;
        if (!mechanismId.isEmpty()) throw new IllegalArgumentException("SPECIAL_MECHANISM_NOT_AVAILABLE");
        Recipe<?> recipe = current(recipeId).value();
        if (BumbleCandleAdapter.supports(recipe)) return 9;
        var profile = SpecialItemProcessingAdapter.profile(recipe);
        if (profile == null) throw new IllegalArgumentException("SPECIAL_ITEM_ADAPTER_NOT_AVAILABLE");
        return switch (profile.input()) { case SINGLE -> 1; case FUSION -> 2; case COMBINER, SMITHING -> 3; case GRID -> 9; };
    }
    private static void unlocked(ServerPlayer player, RecipeHolder<?> holder) {
        if (holder.value().getType() == RecipeType.CRAFTING && !holder.value().isSpecial()
                && player.serverLevel().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING) && !player.getRecipeBook().contains(holder.id()))
            throw new IllegalStateException("SPECIAL_RECIPE_NOT_UNLOCKED");
    }
    <T> T onMain(TaskControl control, Callable<T> action) throws Exception {
        if (closed) throw new IllegalStateException("AI_STOPPING");
        var future = new CompletableFuture<T>();
        if (!main.offer(new Work<>(control, action, future))) throw new IllegalStateException("SPECIAL_QUEUE_FULL");
        return control.await(future, 10000);
    }
    public void tick() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        long end = System.nanoTime() + 1_500_000;
        for (int i = 0; i < 2 && System.nanoTime() < end; i++) { var work = main.poll(); if (work == null) return; run(work); }
    }
    private static <T> void run(Work<T> work) {
        if (work.future().isCancelled()) return;
        try { work.control().check(); work.future().complete(work.action().call()); }
        catch (Exception | LinkageError failure) { work.future().completeExceptionally(failure); }
    }
    public void close() { closed = true; Work<?> work; while ((work = main.poll()) != null) work.future().completeExceptionally(new IllegalStateException("AI_STOPPING")); }
    void tool(ToolRegistry tools, String name, String description, String properties, List<String> required, AgentTool.Risk risk, DefinedTool.Action action) {
        JsonObject schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", JsonParser.parseString(properties));
        schema.add("required", AiJson.GSON.toJsonTree(required)); schema.addProperty("additionalProperties", false);
        tools.register(new DefinedTool(new AgentTool.Descriptor(name, description, schema, risk, "materials.own", 0,
                AgentTool.ExecutionThread.IO, 120000, true, ""), (ctx, args) -> {
            try { return action.run(ctx, args); }
            catch (AgentTool.Deferred deferred) { throw deferred; }
            catch (TaskControl.Stopped stopped) {
                if (!risk.requiresApproval()) throw stopped;
                return MaterialStopReceipt.capture(ledger, ctx, args, stopped);
            }
            catch (Exception error) { if (risk.requiresApproval()) throw error; return failure(safe(error), "专属加工查询或规划失败：" + safe(error) + "；没有扣料。", new JsonObject()); }
        }));
    }
    private static AgentTool.Result success(String summary, JsonObject data) { return new AgentTool.Result(true, "", summary, data, "trusted-special-adapter-and-material-ledger", "", false); }
    private static AgentTool.Result failure(String code, String summary, JsonObject data) { return new AgentTool.Result(false, code, summary, data, "trusted-special-adapter-and-material-ledger", "", false); }
    private static String safe(Throwable failure) { String reason = failure.getMessage(); return reason != null && reason.matches("[A-Z0-9_]{1,100}") ? reason : "SPECIAL_PROCESSING_FAILED"; }
    public String reviewEntity(Workspace workspace, Actor actor, UUID id, String hash) throws Exception { return entities.review(workspace, actor, id, hash); }
}
