package firefly520.fireflymc.ai.processing;

import com.google.gson.*;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.task.CommittedCall;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
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
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Fixed-recipe, fixed-material plans followed by approved survival processing, without machines or fees. */
public final class VirtualProcessingService {
    public record Chance(MaterialStack template, float chance) {
        public Chance { if (template == null || template.empty() || !Float.isFinite(chance)) throw new IllegalArgumentException("PROCESSING_DISTRIBUTION_INVALID"); }
    }
    public record Plan(UUID id, UUID executionEpoch, String owner, UUID taskId, String recipeId, String mechanismId, String recipeType, long recipeGeneration,
                       String dimension, int craftCount, String targetItem, List<MaterialStack> inputs, List<MaterialStack> products,
                       List<Chance> chances, Map<String, String> virtualParameters,
                       List<MaterialLedger.Stock> sources, List<MaterialLedger.Need> needs,
                       float experiencePerCraft, String callbackCategory, String policy, List<Plan> steps, String hash) {
        public Plan {
            targetItem = targetItem == null ? "" : targetItem; inputs = List.copyOf(inputs); products = List.copyOf(products);
            mechanismId = mechanismId == null ? "" : mechanismId;
            chances = chances == null ? List.of() : List.copyOf(chances);
            virtualParameters = virtualParameters == null ? Map.of() : Map.copyOf(virtualParameters);
            sources = List.copyOf(sources); needs = List.copyOf(needs);
            steps = steps == null ? List.of() : List.copyOf(steps);
            if (steps.size() > 64 || steps.stream().anyMatch(p -> !p.steps().isEmpty())) throw new IllegalArgumentException("PROCESSING_CHAIN_LIMIT");
        }
    }
    private record RecipeFacts(RecipeHolder<?> holder, String mechanismId, String dimension, long generation, int inputSize,
                               List<Integer> positions, List<Integer> amounts, Set<Integer> reusablePositions,
                               boolean aggregateInputs, List<Predicate<ItemStack>> matchers) {}
    private record Work<T>(TaskControl control, Callable<T> action, CompletableFuture<T> future, CommittedCall<T> committed) {}
    private record Applied<T>(T effect, MaterialLedger.Operation operation) {}
    @FunctionalInterface public interface EvidenceRecorder<T> { MaterialLedger.Operation record(T effect) throws Exception; }
    @FunctionalInterface public interface ObservationRecorder<T> { void record(T effect) throws Exception; }
    private final MinecraftServer server;
    private final MaterialInventoryService inventory;
    private final MaterialLedger ledger;
    private final LongSupplier generation;
    private final UUID executionEpoch = UUID.randomUUID();
    private final LinkedHashMap<UUID, Recipe<?>> runtimeRecipes = new LinkedHashMap<>();
    private final ArrayBlockingQueue<Work<?>> main = new ArrayBlockingQueue<>(128);
    private final java.util.concurrent.atomic.AtomicInteger nativePending = new java.util.concurrent.atomic.AtomicInteger();
    private final ThreadPoolExecutor evidenceIo = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64),
            Thread.ofPlatform().daemon(true).name("fireflymc-material-evidence").factory(), new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;

    public VirtualProcessingService(MinecraftServer server, MaterialInventoryService inventory, MaterialLedger ledger,
                                    LongSupplier generation) {
        this.server = server; this.inventory = inventory; this.ledger = ledger; this.generation = generation;
    }
    public String support(RecipeHolder<?> recipe) {
        if (VanillaProcessingKernel.supports(recipe.value())) return "VANILLA_MATERIALS_ONLY_V1";
        if (ChippedProcessingAdapter.supports(recipe.value())) return "CHIPPED_4_0_2_MATERIALS_ONLY_V1";
        if (FdCookingProcessingAdapter.supports(recipe.value())) return "FD_1_3_2_COOKING_MATERIALS_ONLY_V1";
        if (FdCuttingProcessingAdapter.supports(recipe.value())) return "FD_1_3_2_CUTTING_MATERIALS_ONLY_V1";
        if (FdDoughProcessingAdapter.supports(recipe.value())) return "FD_1_3_2_DOUGH_MATERIALS_ONLY_V1";
        if (FdServingProcessingAdapter.supports(recipe.value())) return "FD_1_3_2_SERVING_MATERIALS_ONLY_V1";
        if (CfbRecipeProcessingAdapter.supports(recipe.value())) return recipe.value().getResultItem(server.registryAccess()).isEmpty()
                ? "UNAVAILABLE_EMPTY_OUTPUT" : "CFB_21_1_24_MATERIALS_ONLY_V1";
        return "NO_EXECUTION_ADAPTER";
    }
    private static boolean executable(String support) { return support.endsWith("MATERIALS_ONLY_V1"); }
    public void register(ToolRegistry tools) {
        var discovery = new CraftableDiscoveryService(server, inventory, ledger, this, generation);
        tool(tools, "crafting_discover", "直接获取当前材料可制作的物品清单。复用原版配方书可合成判定，一次调用完成库存和配方查询，完整清单自动显示到聊天；无需装载工具、翻页、传快照或向玩家确认。只需可选的物品筛选词 query。",
                "{\"query\":{\"type\":\"string\",\"maxLength\":120}}",
                List.of(), AgentTool.Risk.READ_ONLY, discovery::discover);
        tool(tools, "material_preferences", "读取本人保留材料规则。受保护物品不参与后续取料；模型不能修改规则。如任务依赖保留材料，应在工作台提出阻塞问题，由玩家自行决定是否调整。",
                "{}", List.of(), AgentTool.Risk.READ_ONLY, (ctx, args) -> {
                    var data = AiJson.GSON.toJsonTree(inventory.preferences(ctx.actor()).view(ctx.actor())).getAsJsonObject(); data.remove("owner");
                    return result(true, "", "本人明确保留的材料。", data);
                });
        tool(tools, "inventory_list", "盘点本人真实背包、副手及工作台已授权来源，扣除共享预约；不扫描未授权容器。",
                "{\"snapshotId\":{\"type\":\"string\"},\"offset\":{\"type\":\"integer\",\"minimum\":0}}", List.of(), AgentTool.Risk.READ_ONLY, (ctx, args) -> {
                    var snapshot = args.has("snapshotId") ? onMain(ctx.control(), () -> inventory.snapshot(ctx.actor(),
                            UUID.fromString(args.get("snapshotId").getAsString()))) : ctx.control().await(inventory.scan(ctx.actor()), 30000);
                    int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
                    if (offset < 0 || offset > snapshot.rows().size()) throw new IllegalArgumentException("INVENTORY_OFFSET");
                    int end = Math.min(snapshot.rows().size(), offset + 24);
                    var available = ledger.available(snapshot.rows());
                    JsonObject data = new JsonObject(); data.addProperty("snapshotId", snapshot.id().toString());
                    data.addProperty("capturedAt", snapshot.createdAt()); data.addProperty("nextOffset", end);
                    data.addProperty("total", snapshot.rows().size());
                    data.add("items", AiJson.GSON.toJsonTree(snapshot.rows().subList(offset, end).stream().map(row -> Map.of(
                            "sourceKey", row.key(), "sourceId", row.sourceId(), "slot", row.slot(), "item", publicMaterial(row.stack()),
                            "physical", row.stack().count(), "available", available.getOrDefault(row.key(), 0L),
                            "pool", row.poolId(), "protectedByOwner", inventory.protectedByOwner(ctx.actor(), row.stack()))).toList()));
                    data.add("refusals", AiJson.GSON.toJsonTree(snapshot.refusals()));
                    return result(true, "", "真实库存盘点完成。", data);
                });
        tool(tools, "crafting_plan", "按目标物品和数量求解多步合成依赖（crafting / 合成链）。先用已有成品，真实组件配料与共享库存，有界循环搜索，保留余料；只规划受信确定性配方。返回整链 planId/planHash 后调用 processing_execute；YOLO 直接执行，手动模式再审批。概率加工及动态目标需先用 processing_plan 单独核验。",
                "{\"targetItem\":{\"type\":\"string\"},\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":64}}",
                List.of("targetItem", "count"), AgentTool.Risk.READ_ONLY, (ctx, args) -> chainPlan(ctx, args.get("targetItem").getAsString(), args.get("count").getAsInt()));
        tool(tools, "processing_plan", "合成、制作、加工计划（crafting / processing）：recipeId 与 mechanismId 二选一，真实材料、免设备与附加费用。支持原版合成/切石/锻造/热加工、Chipped、FD/MND烹饪/砧板/面团/盛餐、CFB配方及烤面包机制。Chipped 需 targetItem。返回 planId/planHash 后调用 processing_execute；YOLO 无需玩家确认。craftCount 为执行次数，概率产出不保底。",
                "{\"recipeId\":{\"type\":\"string\"},\"mechanismId\":{\"type\":\"string\"},\"targetItem\":{\"type\":\"string\"},\"craftCount\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":16}}",
                List.of(), AgentTool.Risk.READ_ONLY, (ctx, args) -> {
                    if (args.has("recipeId") == args.has("mechanismId")) throw new IllegalArgumentException("ONE_PROCESSING_REFERENCE_REQUIRED");
                    Plan plan = plan(ctx, args.has("recipeId") ? args.get("recipeId").getAsString() : "",
                            args.has("mechanismId") ? args.get("mechanismId").getAsString() : "",
                            args.has("craftCount") ? args.get("craftCount").getAsInt() : 1,
                            args.has("targetItem") ? args.get("targetItem").getAsString() : "");
                    return result(true, "", "已核验配方与材料，尚未扣料；继续调用 processing_execute，是否人工审批由当前执行模式决定。", view(plan));
                });
        tool(tools, "processing_mechanisms", "发现不属于 RecipeManager 的受信加工机制，例如懒人厨房面包烘烤。mechanismId 不能冒充 recipeId。",
                "{}", List.of(), AgentTool.Risk.READ_ONLY, (ctx, args) -> onMain(ctx.control(), () -> {
                    inventory.requirePlayer(ctx.actor());
                    JsonObject data = new JsonObject();
                    data.add("mechanisms", AiJson.GSON.toJsonTree(CfbToastMechanism.available()
                            ? List.of(Map.of("mechanismId", CfbToastMechanism.ID, "version", "21.1.24", "input", "minecraft:bread",
                            "description", "保留原生烤面包组件与配置分支；免设备，消耗真实面包；原生 toaster 配方存在时须按 recipeId 选择")) : List.of()));
                    return result(true, "", "服务端加工机制目录。", data);
                }));
        tool(tools, "processing_execute", "执行合成、制作与加工（crafting / processing）：执行已生成的固定 planId/planHash，按真实库存扣料并核验产物，MATERIALS_ONLY。YOLO 直接执行，无需玩家授权；仅手动模式需要工作台审批。",
                "{\"planId\":{\"type\":\"string\"},\"planHash\":{\"type\":\"string\"}}", List.of("planId", "planHash"),
                AgentTool.Risk.PLAYER_STATE, (ctx, args) -> {
                    UUID id = UUID.fromString(args.get("planId").getAsString());
                    try { return execute(ctx, load(ctx.workspace(), ctx.actor(), id, args.get("planHash").getAsString())); }
                    catch (AgentTool.Deferred deferred) { throw deferred; }
                    catch (TaskControl.Stopped stopped) { throw stopped; }
                    catch (Exception preflightFailure) {
                        if (ledger.find(ctx.actor(), id).isPresent()) throw preflightFailure;
                        JsonObject data = new JsonObject(); data.addProperty("dispatch", "REJECTED_BEFORE_EXECUTION");
                        return result(false, safe(preflightFailure), "执行前校验失败，未扣料或加工：" + safe(preflightFailure), data);
                    }
                });
        tool(tools, "processing_status", "查询本人加工操作、真实托管和未交付产物；不确定操作不能普通继续。",
                "{\"offset\":{\"type\":\"integer\",\"minimum\":0},\"operationId\":{\"type\":\"string\"}}", List.of(), AgentTool.Risk.READ_ONLY, (ctx, args) -> {
                    JsonObject data = new JsonObject();
                    if (args.has("operationId")) data.add("operation", operationView(ledger.get(ctx.actor(), UUID.fromString(args.get("operationId").getAsString()))));
                    else {
                        var operations = new ArrayList<>(ledger.list(ctx.actor())); Collections.reverse(operations);
                        operations.sort(Comparator.comparing(MaterialLedger.Operation::settled));
                        int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
                        if (offset < 0 || offset > operations.size()) throw new IllegalArgumentException("PROCESSING_OFFSET");
                        int end = Math.min(operations.size(), offset + 8);
                        data.add("operations", AiJson.GSON.toJsonTree(operations.subList(offset, end).stream().map(this::operationView).toList()));
                        data.addProperty("nextOffset", end); data.addProperty("total", operations.size());
                    }
                    return result(true, "", "本人加工账本。", data);
                });
        tool(tools, "processing_reconcile_preview", "检查本人操作的重启对账证据。仅在原生保存凭证齐全且与最后确定账本一致时生成固定复核；物品数量相等、未知回调或普通继续均不能替代证据。",
                "{\"operationId\":{\"type\":\"string\"}}", List.of("operationId"), AgentTool.Risk.READ_ONLY, (ctx, args) -> {
                    onMain(ctx.control(), () -> inventory.requirePlayer(ctx.actor()));
                    requireStandaloneRecovery(ctx, UUID.fromString(args.get("operationId").getAsString()));
                    var review = ledger.recoveryReview(ctx.actor(), UUID.fromString(args.get("operationId").getAsString()));
                    return result(true, "", "保存凭证与最后确定账本一致；工作台批准后只恢复账本阶段，不执行取料、回调或交付。", AiJson.GSON.toJsonTree(review).getAsJsonObject());
                });
        tool(tools, "processing_reconcile", "按固定复核恢复已由原生落盘凭证确认的本人账本阶段。必须工作台批准；不重放任何副作用，继续施工/加工/返还还需对应的新审批与现场校验。",
                "{\"operationId\":{\"type\":\"string\"},\"revision\":{\"type\":\"integer\",\"minimum\":1},\"reviewHash\":{\"type\":\"string\"}}",
                List.of("operationId", "revision", "reviewHash"), AgentTool.Risk.PLAYER_STATE, (ctx, args) -> {
                    onMain(ctx.control(), () -> inventory.requirePlayer(ctx.actor()));
                    requireStandaloneRecovery(ctx, UUID.fromString(args.get("operationId").getAsString()));
                    MaterialLedger.Operation op;
                    try { op = ledger.reconcile(ctx.actor(), UUID.fromString(args.get("operationId").getAsString()),
                            args.get("revision").getAsLong(), args.get("reviewHash").getAsString()); }
                    catch (IllegalStateException | SecurityException rejected) {
                        JsonObject data = new JsonObject(); data.addProperty("dispatch", "REJECTED_BEFORE_EXECUTION");
                        return result(false, safe(rejected), "对账凭证或复核版本未通过，未恢复账本或执行原生操作。", data);
                    }
                    return result(true, "", "已根据原生保存证据恢复账本阶段；未扣料、重放回调或交付物品。后续动作仍须单独批准。", operationView(op));
                });
        tool(tools, "processing_return", "取消未完成取料并将真实托管原料或已加工产物归还本人背包；满仓时继续保留，不重造已消耗原料，不处理不确定操作。",
                "{\"operationId\":{\"type\":\"string\"}}", List.of("operationId"), AgentTool.Risk.PLAYER_STATE, (ctx, args) -> {
                    return returnMaterials(ctx, UUID.fromString(args.get("operationId").getAsString()));
                });
    }
    private AgentTool.Result returnMaterials(AgentTool.Context ctx, UUID id) throws Exception {
        requireStandaloneRecovery(ctx, id); var op = ledger.cancel(ctx.actor(), id);
        if (op.uncertain()) return result(false, "RECOVERY_REQUIRED", "需要先核对实际物料，未重放。", operationView(op));
        if (op.state() == MaterialLedger.State.CALLBACK_DONE) {
            awaitBeforeDeliverySaves(ctx, id, fresh -> returnMaterials(fresh, id));
            op = deliver(ctx, ledger.get(ctx.actor(), id));
        }
        return awaitFinalResult(ctx, id, current -> result(current.settled(), current.settled() ? "" : "PROCESSING_PAUSED",
                "已归还可确认物料；没有重算原料、重抽结果或执行未开始的成功回调。未归还部分仍在本人托管。", operationView(current)));
    }

    /** A generic resource tool cannot split a world transaction from its owning verifier. */
    private void requireStandaloneRecovery(AgentTool.Context ctx, UUID id) {
        var op = ledger.get(ctx.actor(), id);
        boolean owned = !op.worldDebits().isEmpty() || op.externalDebits().stream().anyMatch(debit -> !debit.known() || debit.kind().equals("native_block_harvest"))
                || op.samplingEvidence().startsWith("native_block_harvest:") || op.samplingEvidence().startsWith("uncertain_native_removal:")
                || new firefly520.fireflymc.ai.blueprint.BuildJournal(ctx.workspace(), UUID.randomUUID()).list(ctx.actor()).stream()
                        .anyMatch(step -> step.materialOperationId().equals(id))
                || new firefly520.fireflymc.ai.blueprint.RemovalJournal(ctx.workspace(), UUID.randomUUID()).list(ctx.actor()).stream()
                        .anyMatch(step -> step.input().operationId().equals(id) || step.input().planId().equals(id))
                || ctx.workspace().conversation().getSnapshot(ctx.workspace().conversation().privacyEpoch()).events().stream()
                        .anyMatch(event -> event.kind().equals("construction_plan") && event.data().has("planId") && id.toString().equals(event.data().get("planId").getAsString()));
        if (owned) throw new IllegalStateException("RECOVERY_REQUIRES_OWNING_WORLD_JOURNAL");
    }

    private Plan plan(AgentTool.Context ctx, String recipeId, String mechanismId, int crafts, String targetItem) throws Exception {
        if (ctx.taskId() == null || crafts < 1 || crafts > 16) throw new IllegalArgumentException("PROCESSING_PLAN_LIMIT");
        var snapshot = ctx.control().await(inventory.scan(ctx.actor()), 30000);
        var unique = new LinkedHashMap<String, MaterialLedger.Stock>();
        for (var row : snapshot.rows()) unique.merge(row.key(), row, (a, b) -> a.extractableCount() >= b.extractableCount() ? a : b);
        var stocks = List.copyOf(unique.values());
        Plan plan = preview(ctx, recipeId, mechanismId, crafts, targetItem, stocks, false);
        Path path = path(ctx.workspace(), plan.id());
        boundedPlan(plan);
        synchronized (ctx.workspace().conversation()) {
            ctx.control().check();
            var task = ctx.workspace().conversation().task(ctx.taskId());
            if (task == null || task.state().terminal()) throw new TaskControl.Stopped("TASK_ENDED");
            if (!Files.exists(path)) AiJson.write(path, plan);
        }
        return plan;
    }

    /** Shared allocator and native adapter preflight. Discovery retains no executable plan or recipe reference. */
    private Plan preview(AgentTool.Context ctx, String recipeId, String mechanismId, int crafts, String targetItem,
                         List<MaterialLedger.Stock> stocks, boolean discovery) throws Exception {
        var initialAvailability = ledger.available(stocks);
        RecipeFacts facts = onMain(ctx.control(), () -> facts(ctx.actor(), recipeId, mechanismId, targetItem, crafts, stocks, initialAvailability));
        var domains = new ArrayList<List<Integer>>();
        for (int i = 0; i < facts.positions().size(); i++) domains.add(new ArrayList<>());
        // Component decoding and optional Ingredient predicates execute only in bounded main-thread slices.
        for (int offset = 0; offset < stocks.size(); offset += 16) {
            int first = offset, last = Math.min(offset + 16, stocks.size());
            var matches = onMain(ctx.control(), () -> {
                ServerPlayer player = check(ctx.actor(), facts);
                var found = new ArrayList<List<Integer>>();
                for (int i = 0; i < facts.positions().size(); i++) found.add(new ArrayList<>());
                for (int j = first; j < last; j++) {
                    ItemStack stack = ItemSnapshotCodec.restore(stocks.get(j).stack().withCount(1), player.registryAccess());
                    for (int i = 0; i < facts.matchers().size(); i++) if (facts.matchers().get(i).test(stack)) found.get(i).add(j);
                }
                return found;
            });
            for (int i = 0; i < domains.size(); i++) domains.get(i).addAll(matches.get(i));
        }
        var available = ledger.available(stocks);
        int[][] assignments = IngredientAllocator.allocateBatchCounts(domains.stream().map(d -> d.stream().mapToInt(Integer::intValue).toArray()).toList(),
                stocks.stream().map(s -> new IngredientAllocator.Stock((int) Math.min(s.extractableCount(), available.getOrDefault(s.key(), 0L)),
                        s.poolId(), s.unitsPerItem())).toList(), stocks.stream().map(s -> s.stack().fingerprint()).toList(),
                ledger.availablePools(stocks), java.util.stream.IntStream.range(0, facts.amounts().size())
                        .map(i -> Math.multiplyExact(facts.amounts().get(i), facts.reusablePositions().contains(facts.positions().get(i)) ? 1 : crafts)).toArray(), 50000);
        var inputs = new ArrayList<>(Collections.nCopies(facts.inputSize(), MaterialStack.EMPTY));
        var needed = new LinkedHashMap<String, Integer>();
        var selected = new LinkedHashMap<String, MaterialLedger.Stock>();
        for (int i = 0; i < assignments.length; i++) {
            for (int row = 0; row < stocks.size(); row++) if (assignments[i][row] > 0) {
                var stock = stocks.get(row);
                int inputCount = facts.amounts().get(i) * (facts.aggregateInputs() && !facts.reusablePositions().contains(facts.positions().get(i)) ? crafts : 1);
                inputs.set(facts.positions().get(i), stock.stack().withCount(inputCount));
                selected.put(stock.key(), stock); needed.merge(stock.key(), assignments[i][row], Math::addExact);
            }
        }
        List<MaterialLedger.Need> needs = needed.entrySet().stream().map(e -> new MaterialLedger.Need(e.getKey(), e.getValue())).toList();
        Plan plan = onMain(ctx.control(), () -> {
            ServerPlayer player = check(ctx.actor(), facts);
            var verified = facts.holder() == null ? CfbToastMechanism.verify(ItemSnapshotCodec.restore(inputs.getFirst(), player.registryAccess()), player.serverLevel())
                    : verify(facts.holder(), inputs, targetItem, crafts, player);
            var products = products(verified, crafts, player);
            if (!inventory.outputFits(ctx.actor(), products, List.copyOf(selected.values()), needs)) throw new IllegalStateException("OUTPUT_FULL");
            Plan unsigned = new Plan(UUID.randomUUID(), executionEpoch, ctx.actor().owner(), ctx.taskId(), recipeId, mechanismId,
                    facts.holder() == null ? "" : BuiltInRegistries.RECIPE_TYPE.getKey(facts.holder().value().getType()).toString(), facts.generation(),
                    facts.dimension(), crafts, targetItem, inputs, products, chances(verified, player), verified.contextRequirements(), List.copyOf(selected.values()), needs,
                    verified.experience().perOperation(), verified.callbackPlan().category(), "MATERIALS_ONLY@1", List.of(), "");
            if (!discovery) {
                if (facts.holder() != null) runtimeRecipes.put(unsigned.id(), facts.holder().value());
                while (runtimeRecipes.size() > 2048) runtimeRecipes.remove(runtimeRecipes.keySet().iterator().next());
            }
            return withHash(unsigned, AiJson.digest(AiJson.canonical(unsigned)));
        });
        return plan;
    }

    JsonObject discoveryPreview(AgentTool.Context ctx, String recipeId, List<MaterialLedger.Stock> stocks) throws Exception {
        Plan plan = preview(ctx, recipeId, "", 1, "", stocks, true);
        // Recheck the selected physical inputs, current grant, owner preferences and recovery fence.
        for (var need : plan.needs()) onMain(ctx.control(), () -> {
            var stock = source(plan, need.stockKey());
            if (inventory.protectedByOwner(ctx.actor(), stock.stack())) throw new SecurityException("MATERIAL_PROTECTED_BY_OWNER");
            return inventory.prepareExtraction(ctx.actor(), stock, Math.min(need.count(), stock.maxTransferCount()));
        });
        JsonObject data = new JsonObject();
        data.addProperty("recipeId", plan.recipeId()); data.addProperty("recipeType", plan.recipeType());
        data.addProperty("craftCount", 1); data.addProperty("policy", plan.policy());
        data.addProperty("outputFits", true); data.addProperty("status", "VERIFIED_SINGLE_OPERATION");
        data.addProperty("productSemantics", plan.chances().isEmpty() ? "DETERMINISTIC_PRODUCTS_AND_REMAINDERS"
                : "DISTRIBUTION_MAXIMUM_AND_REMAINDERS_NOT_GUARANTEED");
        data.add("productsIncludingRemainders", AiJson.GSON.toJsonTree(plan.products().stream().map(VirtualProcessingService::publicMaterial).toList()));
        data.add("probabilityOutputs", AiJson.GSON.toJsonTree(plan.chances().stream().map(chance ->
                Map.of("item", publicMaterial(chance.template()), "chance", chance.chance())).toList()));
        data.add("contextRequirements", AiJson.GSON.toJsonTree(plan.virtualParameters()));
        data.add("inputs", AiJson.GSON.toJsonTree(plan.needs().stream().map(need -> {
            var stock = source(plan, need.stockKey());
            return Map.of("sourceId", stock.sourceId(), "sourceKey", stock.key(), "item", publicMaterial(stock.stack().withCount(need.count())));
        }).toList()));
        return data;
    }

    private AgentTool.Result chainPlan(AgentTool.Context ctx, String targetId, int amount) throws Exception {
        if (ctx.taskId() == null || amount < 1 || amount > 64) throw new IllegalArgumentException("PROCESSING_PLAN_LIMIT");
        var snapshot = ctx.control().await(inventory.scan(ctx.actor()), 30000);
        var unique = new LinkedHashMap<String, MaterialLedger.Stock>();
        for (var row : snapshot.rows()) unique.merge(row.key(), row, (a, b) -> a.extractableCount() >= b.extractableCount() ? a : b);
        var stocks = List.copyOf(unique.values());
        var available = ledger.available(stocks);
        ChainCatalog catalog = new ChainCatalog(ctx, stocks, targetId);
        catalog.index();
        var solved = CraftingPlanner.solve(stocks.stream().map(s -> new CraftingPlanner.Stock(s.key(), s.stack(),
                        (int) Math.min(s.extractableCount(), available.getOrDefault(s.key(), 0L)), s.poolId(), s.unitsPerItem())).toList(),
                ledger.availablePools(stocks), catalog.targets, amount, catalog, CraftingPlanner.Limits.standard());
        if (solved.steps().isEmpty()) {
            JsonObject data = new JsonObject(); data.addProperty("alreadyAvailable", true); data.addProperty("targetItem", targetId);
            data.addProperty("count", amount); data.addProperty("visitedNodes", solved.visitedNodes());
            return result(true, "", "现有可用成品已满足目标，无需加工或扣料。", data);
        }
        var steps = new ArrayList<Plan>();
        for (var step : solved.steps()) steps.add(onMain(ctx.control(), () -> {
            ServerPlayer player = catalog.player();
            var holder = catalog.holders.get(step.recipeId());
            var verified = verify(holder, step.inputs(), "", 1, player);
            if (!verified.samplingEvidence().distribution().isEmpty() || !products(verified, 1, player).equals(step.products()))
                throw new IllegalStateException("PROCESSING_RESULT_CHANGED");
            Plan value = new Plan(UUID.randomUUID(), executionEpoch, ctx.actor().owner(), ctx.taskId(), step.recipeId(), "",
                    BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()).toString(), catalog.recipeGeneration, catalog.dimension,
                    1, "", step.inputs(), step.products(), List.of(), verified.contextRequirements(), List.of(), List.of(),
                    verified.experience().perOperation(), verified.callbackPlan().category(), "MATERIALS_ONLY@1", List.of(), "");
            runtimeRecipes.put(value.id(), holder.value());
            return withHash(value, AiJson.digest(AiJson.canonical(value)));
        }));
        var selected = stocks.stream().filter(s -> solved.physicalInputs().containsKey(s.key())).toList();
        var needs = selected.stream().map(s -> new MaterialLedger.Need(s.key(), solved.physicalInputs().get(s.key()))).toList();
        if (needs.size() > 81) throw new IllegalStateException("PLANNING_SOURCE_LIMIT");
        var replayed = ProcessingChainMaterials.replay(selected.stream().map(s -> s.stack().withCount(solved.physicalInputs().get(s.key()))).toList(), solved.steps());
        if (!ProcessingChainMaterials.totals(replayed).equals(ProcessingChainMaterials.totals(solved.products())))
            throw new IllegalStateException("CHAIN_MATERIAL_MISMATCH");
        onMain(ctx.control(), () -> {
            catalog.player();
            if (!inventory.outputFits(ctx.actor(), replayed, selected, needs)) throw new IllegalStateException("OUTPUT_FULL");
            while (runtimeRecipes.size() > 2048) runtimeRecipes.remove(runtimeRecipes.keySet().iterator().next());
            return true;
        });
        Plan unsigned = new Plan(UUID.randomUUID(), executionEpoch, ctx.actor().owner(), ctx.taskId(), "", "fireflymc:processing_chain", "",
                catalog.recipeGeneration, catalog.dimension, 1, targetId, List.of(), replayed, List.of(),
                Map.of("targetCount", Integer.toString(amount), "visitedNodes", Integer.toString(solved.visitedNodes()), "adapter", "deterministic-chain-v1"),
                selected, needs, 0, "PROCESSING_CHAIN", "MATERIALS_ONLY@1", steps, "");
        Plan plan = withHash(unsigned, AiJson.digest(AiJson.canonical(unsigned)));
        boundedPlan(plan);
        synchronized (ctx.workspace().conversation()) {
            ctx.control().check();
            var task = ctx.workspace().conversation().task(ctx.taskId());
            if (task == null || task.state().terminal()) throw new TaskControl.Stopped("TASK_ENDED");
            AiJson.write(path(ctx.workspace(), plan.id()), plan);
        }
        return result(true, "", "已求解并核验确定的加工链；整链需一次工作台审批，尚未扣料。", view(plan));
    }

    private final class ChainCatalog implements CraftingPlanner.Catalog {
        private final AgentTool.Context ctx;
        private final List<MaterialLedger.Stock> stocks;
        private final String targetId;
        private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        private final Map<String, RecipeHolder<?>> holders = new LinkedHashMap<>();
        private final Map<String, List<String>> outputs = new HashMap<>();
        private final Map<String, List<CraftingPlanner.Route>> captured = new HashMap<>();
        private List<MaterialStack> targets;
        private long recipeGeneration;
        private String dimension;
        ChainCatalog(AgentTool.Context ctx, List<MaterialLedger.Stock> stocks, String targetId) {
            this.ctx = ctx; this.stocks = stocks; this.targetId = targetId;
        }
        void index() throws Exception {
            var iterator = onMain(ctx.control(), () -> {
                ServerPlayer player = inventory.requirePlayer(ctx.actor());
                recipeGeneration = generation.getAsLong(); dimension = player.serverLevel().dimension().location().toString();
                var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(targetId)).orElseThrow(() -> new IllegalArgumentException("TARGET_ITEM_UNKNOWN"));
                var target = ItemSnapshotCodec.capture(item.getDefaultInstance(), player.registryAccess());
                if (target.empty()) throw new IllegalArgumentException("TARGET_ITEM_UNKNOWN");
                var choices = new LinkedHashMap<String, MaterialStack>();
                for (var stock : stocks) if (itemId(stock.stack()).equals(targetId)) choices.put(stock.stack().itemJson(), stock.stack().withCount(1));
                choices.put(target.itemJson(), target.withCount(1)); targets = List.copyOf(choices.values());
                return server.getRecipeManager().getRecipes().iterator();
            });
            boolean more;
            do {
                check();
                more = onMain(ctx.control(), () -> {
                    ServerPlayer player = player();
                    for (int i = 0; i < 24 && iterator.hasNext(); i++) {
                        var holder = iterator.next(); Recipe<?> recipe = holder.value();
                        if (!chainEligible(recipe)) continue;
                        var output = recipe.getResultItem(player.registryAccess());
                        if (output.isEmpty()) continue;
                        holders.put(holder.id().toString(), holder);
                        outputs.computeIfAbsent(BuiltInRegistries.ITEM.getKey(output.getItem()).toString(), ignored -> new ArrayList<>()).add(holder.id().toString());
                    }
                    return iterator.hasNext();
                });
            } while (more);
        }
        @Override public List<CraftingPlanner.Route> producers(MaterialStack wanted) throws Exception {
            String itemId = itemId(wanted);
            if (captured.containsKey(itemId)) return captured.get(itemId);
            var routes = new ArrayList<CraftingPlanner.Route>();
            for (String id : outputs.getOrDefault(itemId, List.of()).stream().sorted().toList()) {
                check();
                var holder = holders.get(id);
                RecipeFacts facts = onMain(ctx.control(), () -> {
                    player();
                    try { return facts(ctx.actor(), id, "", "", 1, stocks, Map.of()); }
                    catch (SecurityException unavailable) { return null; }
                });
                if (facts == null) continue;
                var candidates = onMain(ctx.control(), () -> {
                    ServerPlayer player = player();
                    var values = new LinkedHashMap<String, MaterialStack>();
                    for (Ingredient ingredient : holder.value().getIngredients()) for (ItemStack item : ingredient.getItems()) {
                        if (values.size() >= 2048) throw new IllegalStateException("PLANNING_CANDIDATE_LIMIT");
                        if (!item.isEmpty()) { var snapshot = ItemSnapshotCodec.capture(item.copyWithCount(1), player.registryAccess()); values.put(snapshot.itemJson(), snapshot); }
                    }
                    if (FdCookingProcessingAdapter.supports(holder.value())) {
                        ItemStack container = FdCookingProcessingAdapter.container(holder, player.serverLevel());
                        if (!container.isEmpty()) { var snapshot = ItemSnapshotCodec.capture(container.copyWithCount(1), player.registryAccess()); values.put(snapshot.itemJson(), snapshot); }
                    }
                    return values;
                });
                // Include real component variants without exposing private codecs to the model.
                for (var stock : stocks) candidates.putIfAbsent(stock.stack().itemJson(), stock.stack().withCount(1));
                var matches = new ArrayList<LinkedHashMap<String, MaterialStack>>();
                for (int i = 0; i < facts.positions().size(); i++) matches.add(new LinkedHashMap<>());
                var values = new ArrayList<>(candidates.values());
                for (int first = 0; first < values.size(); first += 32) {
                    int begin = first, end = Math.min(values.size(), first + 32);
                    onMain(ctx.control(), () -> {
                        ServerPlayer player = player();
                        for (int i = begin; i < end; i++) {
                            var snapshot = values.get(i); ItemStack stack = ItemSnapshotCodec.restore(snapshot, player.registryAccess());
                            for (int p = 0; p < facts.matchers().size(); p++) if (facts.matchers().get(p).test(stack)) {
                                if (matches.get(p).size() >= 256) throw new IllegalStateException("PLANNING_CANDIDATE_LIMIT");
                                matches.get(p).put(snapshot.itemJson(), snapshot);
                            }
                        }
                        return true;
                    });
                }
                if (matches.stream().anyMatch(Map::isEmpty)) continue;
                var ingredients = new ArrayList<CraftingPlanner.Ingredient>();
                for (int i = 0; i < matches.size(); i++) ingredients.add(new CraftingPlanner.Ingredient(facts.positions().get(i), facts.amounts().get(i), List.copyOf(matches.get(i).values())));
                routes.add(new CraftingPlanner.Route(id, facts.inputSize(), ingredients));
            }
            captured.put(itemId, List.copyOf(routes)); return List.copyOf(routes);
        }
        @Override public List<MaterialStack> verify(CraftingPlanner.Route route, List<MaterialStack> inputs) throws Exception {
            return onMain(ctx.control(), () -> {
                ServerPlayer player = player(); var holder = holders.get(route.recipeId());
                if (server.getRecipeManager().byKey(holder.id()).map(RecipeHolder::value).orElse(null) != holder.value()) throw new IllegalStateException("PROCESSING_RECIPE_STALE");
                unlock(player, holder);
                var verified = VirtualProcessingService.verify(holder, inputs, "", 1, player);
                if (!verified.samplingEvidence().distribution().isEmpty()) throw new IllegalStateException("CHAIN_PROBABILITY_NOT_GUARANTEED");
                return products(verified, 1, player);
            });
        }
        ServerPlayer player() {
            ServerPlayer player = inventory.requirePlayer(ctx.actor());
            if (recipeGeneration != generation.getAsLong() || !dimension.equals(player.serverLevel().dimension().location().toString()))
                throw new IllegalStateException("PROCESSING_RECIPE_STALE");
            return player;
        }
        @Override public void check() {
            ctx.control().check();
            if (System.nanoTime() > deadline) throw new IllegalStateException("PLANNING_TIME_LIMIT");
        }
    }
    private static boolean chainEligible(Recipe<?> recipe) {
        return VanillaProcessingKernel.supports(recipe) && !(recipe instanceof SmithingRecipe)
                || FdCookingProcessingAdapter.supports(recipe);
    }
    private static String itemId(MaterialStack stack) { return AiJson.object(stack.itemJson(), 1_048_576, 32).get("id").getAsString(); }

    private AgentTool.Result execute(AgentTool.Context ctx, Plan plan) throws Exception {
        if (!Objects.equals(plan.taskId(), ctx.taskId())) throw new SecurityException("PROCESSING_TASK_MISMATCH");
        var prior = ledger.find(ctx.actor(), plan.id());
        if (prior.isPresent()) {
            var op = prior.get();
            return awaitFinalResult(ctx, op.operationId(), current -> result(current.state() == MaterialLedger.State.COMPLETED,
                    current.uncertain() ? "RECOVERY_REQUIRED" : "PROCESSING_ALREADY_RECORDED",
                    "此操作已有记录，未重复扣料、合成或触发回调。", operationView(current)));
        }
        validatePlan(ctx, plan);
        onMain(ctx.control(), () -> {
            check(ctx.actor(), plan);
            if (!inventory.outputFits(ctx.actor(), plan.products(), plan.sources(), plan.needs())) throw new IllegalStateException("OUTPUT_FULL");
            return true;
        });
        for (var need : plan.needs()) onMain(ctx.control(), () -> {
            check(ctx.actor(), plan); var source = source(plan, need.stockKey());
            return inventory.prepareExtraction(ctx.actor(), source, Math.min(need.count(), source.maxTransferCount()));
        });
        MaterialLedger.Operation op = ledger.reserve(ctx.actor(), plan.taskId(), plan.id(), plan.hash(), plan.sources(), plan.needs());
        try {
            op = extractReserved(ctx, op, () -> check(ctx.actor(), plan));
            if (op.uncertain()) return result(false, "RECOVERY_REQUIRED", "原生库存返回与变化不一致，已保留证据并停止。", operationView(op));
            return finishFromEscrow(ctx, plan);
        } catch (AgentTool.Deferred deferred) {
            throw deferred;
        } catch (Exception failure) {
            return processingFailure(ctx, plan, failure);
        }
    }

    private AgentTool.Result finishFromEscrow(AgentTool.Context ctx, Plan plan) throws Exception {
        try {
            ctx.control().check();
            ctx.actor().requireAccess(plan.owner());
            if (!Objects.equals(plan.taskId(), ctx.taskId()) || !load(ctx.workspace(), ctx.actor(), plan.id(), plan.hash()).equals(plan))
                throw new SecurityException("PROCESSING_PLAN_CHANGED");
            var op = ledger.get(ctx.actor(), plan.id());
            if (op.state() != MaterialLedger.State.ESCROWED || !op.planHash().equals(plan.hash()))
                return result(false, op.uncertain() ? "RECOVERY_REQUIRED" : "PROCESSING_ALREADY_RECORDED",
                        "此操作阶段已改变，未重复加工或触发回调。", operationView(op));
            awaitInputSaves(ctx, plan.id(), fresh -> finishFromEscrow(fresh, plan));
            validatePlan(ctx, plan);
            validateEscrowContext(ctx, plan);
            if (!plan.chances().isEmpty()) {
                long seed = onMain(ctx.control(), () -> check(ctx.actor(), plan).getRandom().nextLong());
                var observed = new LinkedHashMap<String, MaterialStack>();
                for (int attempt = 0; attempt < plan.craftCount(); attempt++) {
                    final long attemptSeed = seed + 0x9e3779b97f4a7c15L * attempt;
                    var output = onMain(ctx.control(), () -> {
                        ServerPlayer player = check(ctx.actor(), plan);
                        var sampled = FdCuttingProcessingAdapter.sample(current(plan), ItemSnapshotCodec.restore(plan.inputs().getFirst(), player.registryAccess()),
                                player.serverLevel(), attemptSeed);
                        if (!chances(sampled, player).equals(plan.chances()) || !sampled.contextRequirements().equals(plan.virtualParameters()))
                            throw new IllegalStateException("PROCESSING_DISTRIBUTION_CHANGED");
                        return products(sampled, 1, player);
                    });
                    for (var item : output) observed.merge(item.itemJson(), item, (a, b) -> a.withCount(Math.addExact(a.count(), b.count())));
                }
                ctx.control().check();
                op = ledger.produceSampled(ctx.actor(), plan.id(), List.copyOf(observed.values()), "fd-native:seed=" + seed
                        + ";attempts=" + plan.craftCount() + ";distribution=" + AiJson.digest(AiJson.canonical(plan.chances())));
            } else op = ledger.produce(ctx.actor(), plan.id(), plan.products(), (plan.mechanismId().isEmpty() ? "recipe=" + plan.recipeId() : "mechanism=" + plan.mechanismId())
                    + ";generation=" + plan.recipeGeneration()
                    + ";policy=MATERIALS_ONLY;paidCosts=0;plan=" + plan.hash());
            if (Set.of("CHIPPED_DECORATIVE", "CFB_TOASTER").contains(plan.callbackCategory())) {
                op = ledger.noCallbacksRequired(ctx.actor(), plan.id(), "Native conversion has no item-crafting or XP callback: " + plan.callbackCategory());
            } else {
            var callbacks = plan.steps().isEmpty() ? List.of(plan) : plan.steps();
            var rewards = new ArrayList<Integer>();
            for (Plan step : callbacks) rewards.add(step.experiencePerCraft() <= 0 ? 0 : onMain(ctx.control(), () -> {
                ServerPlayer player = check(ctx.actor(), step);
                float exact = step.experiencePerCraft() * step.craftCount(); int amount = (int) Math.floor(exact);
                return amount + (player.getRandom().nextFloat() < exact - amount ? 1 : 0);
            }));
            int expectedParts = 0;
            for (int stepIndex = 0; stepIndex < callbacks.size(); stepIndex++) {
                Plan step = callbacks.get(stepIndex);
                if (!Set.of("CHIPPED_DECORATIVE", "CFB_TOASTER").contains(step.callbackCategory()))
                    expectedParts += step.callbackCategory().equals("FD_COOKING_RESULT") ? 1 : step.craftCount();
                if (rewards.get(stepIndex) > 0) expectedParts++;
            }
            String witness = plan.callbackCategory() + ":sampledExperience=" + rewards;
            if (expectedParts == 0) {
                op = ledger.noCallbacksRequired(ctx.actor(), plan.id(), "All chain stages declare no native success callback: "
                        + callbacks.stream().map(Plan::callbackCategory).toList());
            } else {
            ledger.beginCallback(ctx.actor(), plan.id(), witness, expectedParts);
            // Each accepted bounded part records its outcome even after its requesting task stops waiting.
            int partIndex = 0;
            for (int stepIndex = 0; stepIndex < callbacks.size(); stepIndex++) {
                Plan step = callbacks.get(stepIndex);
                if (Set.of("CHIPPED_DECORATIVE", "CFB_TOASTER").contains(step.callbackCategory())) continue;
                int callbackCount = step.callbackCategory().equals("FD_COOKING_RESULT") ? 1 : step.craftCount();
                for (int count = 0; count < callbackCount; count++) {
                    commitCallbackPart(ctx, plan, witness, partIndex++, step, count, 0);
                }
                int experience = rewards.get(stepIndex);
                if (experience > 0) commitCallbackPart(ctx, plan, witness, partIndex++, step, 0, experience);
            }
            op = ledger.get(ctx.actor(), plan.id());
            if (op.intent() != null) throw new IllegalStateException("MATERIAL_CALLBACK_BATCH_INCOMPLETE");
            }
            }
            op = deliver(ctx, op);
            return awaitFinalResult(ctx, plan.id(), current -> result(current.state() == MaterialLedger.State.COMPLETED,
                    current.state() == MaterialLedger.State.COMPLETED ? "" : "PROCESSING_PAUSED",
                    current.state() == MaterialLedger.State.COMPLETED
                            ? current.products().isEmpty() ? "已实际消耗原料完成加工并确认原生保存；本次概率结果没有产物。" : "配方已实际加工、交付并确认原生保存，真实原料已扣除；附加费用为零。"
                            : "产物仍在本人托管，等待可用输出位置。", operationView(current)));
        } catch (AgentTool.Deferred deferred) {
            throw deferred;
        } catch (Exception failure) {
            return processingFailure(ctx, plan, failure);
        }
    }

    private AgentTool.Result processingFailure(AgentTool.Context ctx, Plan plan, Exception failure) throws Exception {
        var saved = ledger.get(ctx.actor(), plan.id());
        if (saved.intent() != null && !saved.uncertain()) saved = ledger.markUncertain(ctx.actor(), plan.id(),
                (saved.reason().isEmpty() ? "" : saved.reason() + "\n") + "PROCESSING_INTERRUPTED_DURING_INTENT");
        if (failure instanceof TaskControl.Stopped) throw failure;
        return result(false, saved.uncertain() ? "RECOVERY_REQUIRED" : "PROCESSING_PAUSED",
                "加工未全部完成：" + safe(failure) + "。已取物料或产物仍受托管，不能当作已交付。", operationView(saved));
    }

    private void commitCallbackPart(AgentTool.Context ctx, Plan plan, String witness, int partIndex,
                                    Plan step, int callbackIndex, int experience) throws Exception {
        NativeProcessingCallback.Outcome outcome;
        try { outcome = commitOwned(ctx, () -> {
            ServerPlayer player = check(ctx.actor(), step);
            return NativeProcessingCallback.run(player, () -> {
                if (experience > 0) ExperienceOrb.award(player.serverLevel(), player.position(), experience);
                else callbacks(ctx.actor(), step, callbackIndex);
            }, experience);
        }, effect -> {
            return recordNativeCallbackPart(ctx, plan.id(), witness, partIndex, effect);
        }); } catch (CommittedCall.Rejected rejected) {
            ledger.callbackRejectedBeforeDispatch(ctx.actor(), plan.id(), witness, partIndex, "REJECTED_BEFORE_NATIVE_INVOCATION");
            throw rejected;
        }
        if (!outcome.completed()) throw new IllegalStateException(outcome.code());
        if (ledger.get(ctx.actor(), plan.id()).uncertain()) throw new IllegalStateException("RECOVERY_REQUIRED");
    }

    /** IO-only evidence closure shared by trusted processing adapters; it outlives a stopped waiter. */
    public MaterialLedger.Operation recordNativeCallbackPart(AgentTool.Context ctx, UUID operationId, String witness,
                                                               int partIndex, NativeProcessingCallback.Outcome effect) throws Exception {
        ctx.actor().requireAccess(ctx.workspace().owner());
        if (!effect.entered()) return ledger.callbackRejectedBeforeDispatch(ctx.actor(), operationId, witness, partIndex, effect.code());
        boolean covered = effect.completed() && effect.entered() && effect.save() != null;
        String code = effect.completed() && !covered ? "NATIVE_CALLBACK_SAVE_UNCOVERED" : effect.code();
        var recorded = ledger.callbackPartEvidence(ctx.actor(), operationId, witness, partIndex,
                covered, effect.entered(), code, Set.of("player:" + ctx.actor().owner()), effect.observations());
        if (effect.entered() && effect.save() != null) inventory.nativeSaves().record(ledger, ctx.actor(), operationId,
                covered ? "callback-part" : "callback-part-unknown",
                MaterialLedger.callbackPartWitness(witness, partIndex), effect.save());
        // Final execution evidence belongs to the accepted writer, which can outlive this tool's waiter.
        if (covered && recorded.intent() != null && recorded.intent().count() == partIndex + 1)
            recorded = ledger.callbackEvidence(ctx.actor(), operationId, witness);
        return recorded;
    }
    public MaterialLedger.Operation recordNativeProduction(AgentTool.Context ctx, UUID operationId, String witness,
                                                            List<MaterialStack> products, NativeProcessingCallback.Outcome effect) throws Exception {
        ctx.actor().requireAccess(ctx.workspace().owner());
        if (!effect.entered()) return ledger.callbackRejectedBeforeDispatch(ctx.actor(), operationId, witness, 0, effect.code());
        boolean covered = effect.completed() && effect.save() != null;
        var recorded = ledger.callbackPartEvidence(ctx.actor(), operationId, witness, 0, covered, true,
                effect.completed() && !covered ? "NATIVE_PRODUCTION_SAVE_UNCOVERED" : effect.code(),
                Set.of("player:" + ctx.actor().owner()), effect.observations());
        if (effect.save() != null) inventory.nativeSaves().record(ledger, ctx.actor(), operationId,
                covered ? "callback-part" : "callback-part-unknown", MaterialLedger.callbackPartWitness(witness, 0), effect.save());
        return covered ? ledger.nativeProductionEvidence(ctx.actor(), operationId, witness, products) : recorded;
    }
    public void awaitBeforeDeliverySaves(AgentTool.Context ctx, UUID operationId, AgentTool.Continuation continuation) throws Exception {
        new ProcessingBeforeDeliverySaves(ledger, id -> inventory.nativeSaves().awaitJournalled(id)).await(ctx, operationId, continuation);
    }

    /** Call only after extraction; the continuation must retain immutable plans or identifiers, never native objects. */
    public void awaitInputSaves(AgentTool.Context ctx, UUID operationId, AgentTool.Continuation continuation) throws Exception {
        if (closed) throw new IllegalStateException("AI_STOPPING");
        new ProcessingInputSaves(ledger, id -> inventory.nativeSaves().awaitJournalled(id)).await(ctx, operationId, continuation);
    }

    /** Final result only: its deferred continuation must never perform extraction, callbacks or delivery again. */
    public AgentTool.Result awaitFinalResult(AgentTool.Context ctx, UUID operationId,
                                             java.util.function.Function<MaterialLedger.Operation, AgentTool.Result> finalResult) throws Exception {
        if (closed) throw new IllegalStateException("AI_STOPPING");
        return new ProcessingFinalSaves(ledger, id -> inventory.nativeSaves().awaitJournalled(id)).complete(ctx, operationId, finalResult);
    }

    private void validateEscrowContext(AgentTool.Context ctx, Plan plan) throws Exception {
        for (var need : plan.needs()) onMain(ctx.control(), () -> {
            check(ctx.actor(), plan);
            var stock = source(plan, need.stockKey());
            if (!stock.sourceId().equals("player"))
                inventory.grants(ctx.actor()).require(ctx.actor(), UUID.fromString(stock.sourceId()), stock.sourceRevision(), true);
            if (inventory.protectedByOwner(ctx.actor(), stock.stack())) throw new SecurityException("MATERIAL_PROTECTED_BY_OWNER");
            return true;
        });
        onMain(ctx.control(), () -> {
            check(ctx.actor(), plan);
            // Inputs are already held in escrow: do not subtract them from the current inventory a second time.
            if (!inventory.outputFits(ctx.actor(), plan.products(), List.of(), List.of())) throw new IllegalStateException("OUTPUT_FULL");
            return true;
        });
        ctx.control().check();
    }

    private void validatePlan(AgentTool.Context ctx, Plan plan) throws Exception {
        if (!plan.steps().isEmpty()) {
            if (!plan.mechanismId().equals("fireflymc:processing_chain") || !plan.chances().isEmpty()) throw new IllegalStateException("CHAIN_PLAN_INVALID");
            for (var step : plan.steps()) {
                if (!step.owner().equals(plan.owner()) || !Objects.equals(step.taskId(), plan.taskId()) || step.craftCount() != 1
                        || step.recipeGeneration() != plan.recipeGeneration() || !step.executionEpoch().equals(plan.executionEpoch())
                        || !step.dimension().equals(plan.dimension()) || !step.chances().isEmpty() || !step.mechanismId().isEmpty())
                    throw new IllegalStateException("CHAIN_PLAN_INVALID");
                validatePlan(ctx, step);
            }
            var physical = plan.needs().stream().map(n -> source(plan, n.stockKey()).stack().withCount(n.count())).toList();
            var steps = plan.steps().stream().map(p -> new CraftingPlanner.Step(p.recipeId(), p.inputs(), p.products())).toList();
            if (!ProcessingChainMaterials.totals(ProcessingChainMaterials.replay(physical, steps)).equals(ProcessingChainMaterials.totals(plan.products())))
                throw new IllegalStateException("CHAIN_MATERIAL_MISMATCH");
        } else onMain(ctx.control(), () -> {
            ServerPlayer player = check(ctx.actor(), plan);
            var verified = verifyPlan(plan, player);
            if (!products(verified, plan.craftCount(), player).equals(plan.products()) || !chances(verified, player).equals(plan.chances())
                    || !verified.contextRequirements().equals(plan.virtualParameters())) throw new IllegalStateException("PROCESSING_RESULT_CHANGED");
            return true;
        });
    }

    /** Read-only preflight of only the original claims that have not yet been extracted. */
    public void validateReservedInputs(AgentTool.Context ctx, MaterialLedger.Operation initial, Runnable mainGuard) throws Exception {
        ctx.actor().requireAccess(ctx.workspace().owner());
        var op = ledger.get(ctx.actor(), initial.operationId());
        if (!Objects.equals(op.taskId(), ctx.taskId()) || op.intent() != null || op.uncertain())
            throw new IllegalStateException("MATERIAL_TASK_OR_CUSTODY_CHANGED");
        for (var claim : op.claims()) if (claim.remaining() > 0) {
            var expected = afterOwnExtractions(op, claim.stock());
            onMain(ctx.control(), () -> {
                mainGuard.run();
                return inventory.prepareExtraction(ctx.actor(), expected, Math.min(claim.remaining(), expected.maxTransferCount()));
            });
        }
    }

    /** Reused by approved construction without manufacturing an intermediate inventory or a recipe. */
    public MaterialLedger.Operation extractReserved(AgentTool.Context ctx, MaterialLedger.Operation initial, Runnable mainGuard) throws Exception {
        ctx.actor().requireAccess(ctx.workspace().owner()); UUID id = initial.operationId();
        MaterialLedger.Operation op = ledger.get(ctx.actor(), id);
        if (!Objects.equals(op.taskId(), ctx.taskId())) throw new SecurityException("MATERIAL_TASK_MISMATCH");
        for (int i = 0; i < op.claims().size(); i++) {
            while (op.claims().get(i).remaining() > 0) {
                final int index = i; var claim = op.claims().get(i);
                final int transferCount = Math.min(claim.remaining(), claim.stock().maxTransferCount());
                MaterialLedger.Stock expected = afterOwnExtractions(op, claim.stock());
                var prepared = onMain(ctx.control(), () -> { mainGuard.run(); return inventory.prepareExtraction(ctx.actor(), expected, transferCount); });
                ledger.beginExtraction(ctx.actor(), id, index, prepared.before(), prepared.poolUnits(), prepared.witness(), transferCount);
                Applied<MaterialInventoryService.Extracted> applied;
                try { applied = commitOnMain(ctx.control(), () -> inventory.extract(prepared, transferCount), effect -> {
                    var recorded = effect.mutationEntered()
                            ? ledger.extractionEvidence(ctx.actor(), id, prepared.witness(), effect.actual(), effect.poolUnitsAfter())
                            : ledger.rejectionEvidence(ctx.actor(), id, prepared.witness(), "extract", effect.error());
                    inventory.nativeSaves().record(ledger, ctx.actor(), id, "extract", prepared.witness(), effect.save());
                    return recorded;
                }); }
                catch (CommittedCall.Rejected rejected) {
                    ledger.rejectionEvidence(ctx.actor(), id, prepared.witness(), "extract", "CANCELLED_BEFORE_NATIVE_EXTRACTION");
                    if (rejected.getCause() instanceof TaskControl.Stopped stopped) throw stopped;
                    throw rejected;
                }
                var extracted = applied.effect(); op = applied.operation();
                if (!extracted.mutationEntered()) throw new IllegalStateException(extracted.error());
                if (op.uncertain()) return op;
                if (extracted.actual().empty()) throw new IllegalStateException("NO_EXTRACTION_PROGRESS");
            }
        }
        return op;
    }
    /** Runs owned inspection/calculation through the existing bounded main-thread queue. */
    public <T> T inspectOwned(AgentTool.Context ctx, Callable<T> action) throws Exception {
        ctx.actor().requireAccess(ctx.workspace().owner());
        return onMain(ctx.control(), action);
    }

    /** Collects and records an accepted native effect even after the requesting task stops waiting. */
    public <T> T commitOwned(AgentTool.Context ctx, Callable<T> nativeAction, EvidenceRecorder<T> recorder) throws Exception {
        ctx.actor().requireAccess(ctx.workspace().owner());
        return commitOnMain(ctx.control(), nativeAction, recorder).effect();
    }
    /** Accepted evidence for native domains with their own journal, without inventing a material operation. */
    public <T> T commitObserved(AgentTool.Context ctx, Callable<T> nativeAction, ObservationRecorder<T> recorder) throws Exception {
        ctx.actor().requireAccess(ctx.workspace().owner());
        return commitOnMain(ctx.control(), nativeAction, effect -> { recorder.record(effect); return null; }).effect();
    }
    public MaterialLedger.Operation deliver(AgentTool.Context ctx, MaterialLedger.Operation op) throws Exception {
        return deliver(ctx, op, -1);
    }
    public MaterialLedger.Operation deliver(AgentTool.Context ctx, MaterialLedger.Operation op, int protectedPlayerSlot) throws Exception {
        ctx.actor().requireAccess(ctx.workspace().owner());
        op = ledger.get(ctx.actor(), op.operationId());
        UUID id = op.operationId();
        for (int index = 0; index < op.products().size(); index++) {
            while (op.delivered().get(index) < op.products().get(index).count()) {
                MaterialStack left = op.products().get(index).withCount(op.products().get(index).count() - op.delivered().get(index));
                MaterialInventoryService.PreparedDelivery prepared;
                try { prepared = onMain(ctx.control(), () -> inventory.prepareDelivery(ctx.actor(), left, protectedPlayerSlot)); }
                catch (IllegalStateException full) { if ("OUTPUT_FULL".equals(full.getMessage())) return op; throw full; }
                ledger.beginDelivery(ctx.actor(), id, index, prepared.product().count(), prepared.witness(), prepared.before(), inventory.deliveryPools(prepared));
                Applied<MaterialInventoryService.Delivered> applied;
                try { applied = commitOnMain(ctx.control(), () -> inventory.deliver(prepared), effect -> {
                    var recorded = effect.mutationEntered()
                            ? ledger.deliveryEvidence(ctx.actor(), id, prepared.witness(), effect.accepted(), effect.verified())
                            : ledger.rejectionEvidence(ctx.actor(), id, prepared.witness(), "deliver", effect.error());
                    inventory.nativeSaves().record(ledger, ctx.actor(), id, "deliver", prepared.witness(), effect.save());
                    return recorded;
                }); }
                catch (CommittedCall.Rejected rejected) {
                    ledger.rejectionEvidence(ctx.actor(), id, prepared.witness(), "deliver", "CANCELLED_BEFORE_NATIVE_DELIVERY");
                    if (rejected.getCause() instanceof TaskControl.Stopped stopped) throw stopped;
                    throw rejected;
                }
                var delivered = applied.effect(); op = applied.operation();
                if (!delivered.mutationEntered()) return op;
                if (op.uncertain() || delivered.accepted() == 0) return op;
            }
        }
        return op;
    }

    private RecipeFacts facts(Actor actor, String id, String mechanismId, String targetItem, int crafts,
                              List<MaterialLedger.Stock> stocks, Map<String, Long> available) {
        ServerPlayer player = inventory.requirePlayer(actor);
        if (!mechanismId.isEmpty()) {
            if (!mechanismId.equals(CfbToastMechanism.ID) || !CfbToastMechanism.available()) throw new IllegalArgumentException("UNSUPPORTED_PROCESSING_MECHANISM");
            return new RecipeFacts(null, mechanismId, player.serverLevel().dimension().location().toString(), generation.getAsLong(), 1,
                    List.of(0), List.of(1), Set.of(), false, List.of(stack -> CfbToastMechanism.candidate(stack, player.serverLevel())));
        }
        var holder = server.getRecipeManager().byKey(ResourceLocation.parse(id)).orElseThrow(() -> new IllegalArgumentException("RECIPE_NOT_FOUND"));
        if (!executable(support(holder))) throw new IllegalArgumentException("UNSUPPORTED_PROCESSING_ADAPTER");
        var positions = new ArrayList<Integer>();
        var amounts = new ArrayList<Integer>();
        Set<Integer> reusablePositions = new HashSet<>();
        boolean aggregateInputs = false;
        var matchers = new ArrayList<Predicate<ItemStack>>();
        Recipe<?> recipe = holder.value();
        int size;
        if (CfbRecipeProcessingAdapter.supports(recipe)) {
            size = 1; positions.add(0); matchers.add(stack -> CfbRecipeProcessingAdapter.matches(holder, stack, player.serverLevel()));
        } else if (FdServingProcessingAdapter.supports(recipe)) {
            size = 9; aggregateInputs = true; positions.addAll(List.of(0, 1)); reusablePositions.add(0);
            ItemStack selectedPot = null, selectedContainer = null;
            for (var stock : stocks) {
                if (available.getOrDefault(stock.key(), 0L) < 1) continue;
                if (!stock.stack().itemJson().contains("farmersdelight:cooking_pot")) continue;
                ItemStack pot = ItemSnapshotCodec.restore(stock.stack().withCount(1), player.registryAccess());
                var contents = FdServingProcessingAdapter.contents(pot, player.serverLevel());
                if (contents.meal().count() < crafts || contents.container().empty()) continue;
                if (!targetItem.isBlank() && !JsonParser.parseString(contents.meal().itemJson()).getAsJsonObject().get("id").getAsString().equals(targetItem)) continue;
                ItemStack container = ItemSnapshotCodec.restore(contents.container().withCount(1), player.registryAccess());
                var quantities = new HashMap<String, Long>();
                for (var candidate : stocks) {
                    ItemStack value = ItemSnapshotCodec.restore(candidate.stack().withCount(1), player.registryAccess());
                    if (ItemStack.isSameItem(value, container)) quantities.merge(candidate.stack().fingerprint(), available.getOrDefault(candidate.key(), 0L), Long::sum);
                }
                if (quantities.values().stream().noneMatch(amount -> amount >= crafts)) continue;
                selectedPot = pot; selectedContainer = container; break;
            }
            if (selectedPot == null) throw new IllegalStateException("SERVING_SOURCE_OR_CONTAINERS_MISSING");
            final ItemStack pot = selectedPot, container = selectedContainer;
            matchers.add(stack -> ItemStack.isSameItemSameComponents(stack, pot));
            matchers.add(stack -> ItemStack.isSameItem(stack, container));
        } else if (FdDoughProcessingAdapter.supports(recipe)) {
            size = 9; positions.addAll(List.of(0, 1)); reusablePositions.add(1);
            matchers.add(stack -> stack.is(net.minecraft.world.item.Items.WHEAT));
            matchers.add(stack -> stack.is(net.neoforged.neoforge.common.Tags.Items.BUCKETS_WATER));
        } else if (FdCuttingProcessingAdapter.supports(recipe)) {
            size = 1; positions.add(0); matchers.add(stack -> FdCuttingProcessingAdapter.matches(holder, stack, player.serverLevel()));
        } else if (FdCookingProcessingAdapter.supports(recipe)) {
            size = 7;
            if (recipe.getIngredients().size() > 6) throw new IllegalArgumentException("FD_COOKING_INPUT_SIZE");
            for (Ingredient ingredient : recipe.getIngredients()) {
                positions.add(positions.size()); matchers.add(ingredient::test); amounts.add(1);
            }
            ItemStack container = FdCookingProcessingAdapter.container(holder, player.serverLevel());
            if (!container.isEmpty()) {
                int needed = recipe.getResultItem(player.registryAccess()).getCount();
                if (needed < 1 || needed > 64) throw new IllegalArgumentException("FD_COOKING_OUTPUT_INVALID");
                positions.add(6); amounts.add(needed); matchers.add(stack -> ItemStack.isSameItem(stack, container));
            }
        } else if (ChippedProcessingAdapter.supports(recipe)) {
            if (targetItem.isBlank()) throw new IllegalArgumentException("CHIPPED_TARGET_REQUIRED");
            size = 1; positions.add(0);
            matchers.add(stack -> ChippedProcessingAdapter.matches(holder, stack, targetItem, player.serverLevel()));
        } else if (recipe instanceof ShapedRecipe shaped) {
            size = 9;
            for (int y = 0; y < shaped.getHeight(); y++) for (int x = 0; x < shaped.getWidth(); x++) {
                Ingredient ingredient = shaped.getIngredients().get(x + y * shaped.getWidth());
                if (!ingredient.isEmpty()) { positions.add(x + y * 3); matchers.add(ingredient::test); }
            }
        } else if (recipe instanceof ShapelessRecipe) {
            size = 9;
            for (Ingredient ingredient : recipe.getIngredients()) { positions.add(positions.size()); matchers.add(ingredient::test); }
        } else if (recipe instanceof SmithingRecipe smithing) {
            size = 3; positions.addAll(List.of(0, 1, 2));
            matchers.add(smithing::isTemplateIngredient); matchers.add(smithing::isBaseIngredient); matchers.add(smithing::isAdditionIngredient);
        } else {
            size = 1; positions.add(0); matchers.add(recipe.getIngredients().getFirst()::test);
        }
        if (positions.isEmpty() || positions.size() > size) throw new IllegalArgumentException("PROCESSING_INPUT_INVALID");
        if (amounts.isEmpty()) for (int i = 0; i < positions.size(); i++) amounts.add(1);
        RecipeFacts facts = new RecipeFacts(holder, "", player.serverLevel().dimension().location().toString(), generation.getAsLong(), size, positions, amounts,
                Set.copyOf(reusablePositions), aggregateInputs, matchers);
        check(actor, facts); return facts;
    }
    private ServerPlayer check(Actor actor, RecipeFacts facts) {
        ServerPlayer player = inventory.requirePlayer(actor);
        if (generation.getAsLong() != facts.generation() || !player.serverLevel().dimension().location().toString().equals(facts.dimension())
                || facts.holder() != null && server.getRecipeManager().byKey(facts.holder().id()).map(RecipeHolder::value).orElse(null) != facts.holder().value())
            throw new IllegalStateException("PROCESSING_RECIPE_STALE");
        if (facts.holder() != null) unlock(player, facts.holder()); return player;
    }
    private ServerPlayer check(Actor actor, Plan plan) {
        ServerPlayer player = inventory.requirePlayer(actor);
        if (!executionEpoch.equals(plan.executionEpoch()) || generation.getAsLong() != plan.recipeGeneration()
                || !player.serverLevel().dimension().location().toString().equals(plan.dimension()))
            throw new IllegalStateException("PROCESSING_RECIPE_STALE");
        if (!plan.steps().isEmpty() && plan.mechanismId().equals("fireflymc:processing_chain")) return player;
        if (plan.mechanismId().isEmpty()) unlock(player, current(plan));
        else if (!plan.mechanismId().equals(CfbToastMechanism.ID) || !CfbToastMechanism.available()) throw new IllegalStateException("PROCESSING_MECHANISM_CHANGED");
        return player;
    }
    private static void unlock(ServerPlayer player, RecipeHolder<?> holder) {
        if (holder.value().getType() == RecipeType.CRAFTING && player.serverLevel().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING)
                && !holder.value().isSpecial() && !player.getRecipeBook().contains(holder))
            throw new SecurityException("RECIPE_NOT_UNLOCKED");
    }
    private RecipeHolder<?> current(Plan plan) {
        var holder = server.getRecipeManager().byKey(ResourceLocation.parse(plan.recipeId()))
                .orElseThrow(() -> new IllegalStateException("PROCESSING_RECIPE_STALE"));
        if (runtimeRecipes.get(plan.id()) != holder.value()) throw new IllegalStateException("PROCESSING_RECIPE_STALE");
        if (!executable(support(holder))
                || !BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType()).toString().equals(plan.recipeType()))
            throw new IllegalStateException("PROCESSING_RECIPE_STALE");
        return holder;
    }
    private static VerifiedTransformation verify(RecipeHolder<?> holder, List<MaterialStack> snapshots, String targetItem, int crafts, ServerPlayer player) {
        var inputs = snapshots.stream().map(s -> ItemSnapshotCodec.restore(s, player.registryAccess())).toList();
        var previous = CommonHooks.getCraftingPlayer();
        CommonHooks.setCraftingPlayer(player);
        try {
            if (ChippedProcessingAdapter.supports(holder.value())) return ChippedProcessingAdapter.verify(holder, inputs.getFirst(), targetItem, player.serverLevel());
            if (FdCookingProcessingAdapter.supports(holder.value())) return FdCookingProcessingAdapter.verify(holder, inputs, player.serverLevel());
            if (FdCuttingProcessingAdapter.supports(holder.value())) return FdCuttingProcessingAdapter.preview(holder, inputs.getFirst(), player.serverLevel());
            if (FdDoughProcessingAdapter.supports(holder.value())) return FdDoughProcessingAdapter.verify(holder, inputs, player.serverLevel());
            if (FdServingProcessingAdapter.supports(holder.value())) return FdServingProcessingAdapter.verify(holder, inputs, crafts, player.serverLevel());
            if (CfbRecipeProcessingAdapter.supports(holder.value())) return CfbRecipeProcessingAdapter.verify(holder, inputs.getFirst(), player.serverLevel());
            if (holder.value().getType() == RecipeType.CRAFTING) return VanillaProcessingKernel.verifyCrafting(holder, inputs, player.serverLevel());
            if (holder.value().getType() == RecipeType.SMITHING) return VanillaProcessingKernel.verifySmithing(holder,
                    new SmithingRecipeInput(inputs.get(0), inputs.get(1), inputs.get(2)), player.serverLevel());
            return VanillaProcessingKernel.verifySingle(holder, new SingleRecipeInput(inputs.getFirst()), player.serverLevel());
        } finally { CommonHooks.setCraftingPlayer(previous); }
    }
    private VerifiedTransformation verifyPlan(Plan plan, ServerPlayer player) {
        return plan.mechanismId().isEmpty() ? verify(current(plan), plan.inputs(), plan.targetItem(), plan.craftCount(), player)
                : CfbToastMechanism.verify(ItemSnapshotCodec.restore(plan.inputs().getFirst(), player.registryAccess()), player.serverLevel());
    }
    private static List<MaterialStack> products(VerifiedTransformation result, int crafts, ServerPlayer player) {
        return ProcessingBatchProducts.aggregate(result, crafts, player.registryAccess());
    }
    private static List<Chance> chances(VerifiedTransformation transformation, ServerPlayer player) {
        return transformation.samplingEvidence().distribution().stream().map(entry ->
                new Chance(ItemSnapshotCodec.capture(entry.template(), player.registryAccess()), entry.chance())).toList();
    }
    private void callbacks(Actor actor, Plan plan, int stepIndex) {
        ServerPlayer player = check(actor, plan);
        var holder = current(plan);
        if (plan.callbackCategory().equals("FD_CUTTING_USE")) { FdCuttingProcessingAdapter.callbacks(holder, player); return; }
        var verified = verify(holder, plan.inputs(), plan.targetItem(), plan.craftCount(), player);
        if (verified.callbackPlan().category().equals("CHIPPED_DECORATIVE")) return;
        var inputs = verified.inputSnapshots();
        var stepOutputs = verified.outputs();
        if (verified instanceof FdServingProcessingAdapter.Batch batch) {
            var step = batch.steps().get(stepIndex);
            inputs = step.inputs().stream().map(s -> ItemSnapshotCodec.restore(s, player.registryAccess())).toList();
            stepOutputs = step.outputs().stream().map(s -> ItemSnapshotCodec.restore(s, player.registryAccess())).toList();
        }
        var previous = CommonHooks.getCraftingPlayer();
        int oldLevel = player.experienceLevel, oldTotal = player.totalExperience;
        float oldProgress = player.experienceProgress;
        CommonHooks.setCraftingPlayer(player);
        try {
            var callbackOutputs = new ArrayList<ItemStack>();
            for (ItemStack output : stepOutputs) callbackOutputs.addAll(plan.callbackCategory().equals("FD_COOKING_RESULT")
                    ? CommonModMaterialRules.splitOutput(output, output.getCount() * plan.craftCount(), player.registryAccess()) : List.of(output));
            for (ItemStack output : callbackOutputs) {
                MaterialStack before = ItemSnapshotCodec.capture(output, player.registryAccess());
                output.onCraftedBy(player.serverLevel(), player, output.getCount());
                if (holder.value().getType() == RecipeType.CRAFTING)
                    EventHooks.firePlayerCraftingEvent(player, output, new SimpleContainer(inputs.toArray(ItemStack[]::new)));
                if (holder.value() instanceof AbstractCookingRecipe || plan.callbackCategory().equals("CFB_VIRTUAL_OVEN"))
                    EventHooks.firePlayerSmeltedEvent(player, output);
                if (!before.equals(ItemSnapshotCodec.capture(output, player.registryAccess())))
                    throw new IllegalStateException("PROCESSING_CALLBACK_OUTPUT_UNCERTAIN");
            }
            if (!holder.value().isSpecial() && !plan.callbackCategory().equals("CFB_VIRTUAL_OVEN")) {
                player.awardRecipes(List.of(holder));
                player.triggerRecipeCrafted(holder, inputs);
            }
        } finally {
            // Standard callbacks may grant rewards; purely charged crafting XP is waived locally.
            if (player.totalExperience < oldTotal || player.experienceLevel < oldLevel
                    || player.experienceLevel == oldLevel && player.experienceProgress < oldProgress) {
                player.experienceLevel = oldLevel; player.totalExperience = oldTotal; player.experienceProgress = oldProgress;
            }
            CommonHooks.setCraftingPlayer(previous);
        }
    }
    private static MaterialLedger.Stock source(Plan plan, String key) {
        return plan.sources().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }
    private static MaterialLedger.Stock afterOwnExtractions(MaterialLedger.Operation op, MaterialLedger.Stock stock) {
        long removed = op.claims().stream().filter(c -> c.stock().poolId().equals(stock.poolId()))
                .mapToLong(c -> Math.multiplyExact((long) c.extracted(), c.stock().unitsPerItem())).sum();
        long units = stock.poolUnits() - removed;
        int count = (int) Math.min(stock.stack().count(), units / stock.unitsPerItem());
        return new MaterialLedger.Stock(stock.sourceId(), stock.sourceRevision(), stock.backendId(), stock.poolId(), stock.slot(),
                stock.stack().withCount(count), stock.unitsPerItem(), units, Math.min(stock.extractableCount(), count), Math.min(stock.maxTransferCount(), count));
    }
    public JsonObject view(Plan plan) {
        JsonObject data = new JsonObject();
        data.addProperty("planId", plan.id().toString()); data.addProperty("planHash", plan.hash());
        data.addProperty("recipeId", plan.recipeId()); data.addProperty("recipeGeneration", plan.recipeGeneration());
        data.addProperty("mechanismId", plan.mechanismId()); data.addProperty("sourceKind", plan.mechanismId().isEmpty() ? "RECIPE" : "MECHANISM");
        if (!plan.steps().isEmpty()) {
            data.addProperty("sourceKind", "PROCESSING_CHAIN");
            data.add("steps", AiJson.GSON.toJsonTree(plan.steps().stream().map(p -> Map.of("recipeId", p.recipeId(), "adapter", supportId(p),
                    "inputs", p.inputs().stream().filter(s -> !s.empty()).map(VirtualProcessingService::publicMaterial).toList(),
                    "outputsAndRemainders", p.products().stream().map(VirtualProcessingService::publicMaterial).toList())).toList()));
        }
        data.addProperty("craftCount", plan.craftCount()); data.addProperty("policy", plan.policy());
        data.add("virtualParameters", AiJson.GSON.toJsonTree(plan.virtualParameters()));
        data.addProperty("outputsAreMaximum", !plan.chances().isEmpty());
        data.add("probabilities", AiJson.GSON.toJsonTree(plan.chances().stream().map(c -> Map.of(
                "output", publicMaterial(c.template()), "chancePerUnit", c.chance())).toList()));
        data.add("consumedInputs", AiJson.GSON.toJsonTree(plan.needs().stream().map(n -> Map.of(
                "source", source(plan, n.stockKey()).sourceId(), "slot", source(plan, n.stockKey()).slot(),
                "item", publicMaterial(source(plan, n.stockKey()).stack().withCount(n.count())), "count", n.count())).toList()));
        data.add("outputsAndRemainders", AiJson.GSON.toJsonTree(plan.products().stream().map(VirtualProcessingService::publicMaterial).toList()));
        data.addProperty("costs", "免设备、免燃料、免能耗、免经验消耗、免辅助工具损耗、无加工等待；真实原料照常消耗。");
        return data;
    }
    public String review(Plan plan) {
        StringBuilder text = new StringBuilder("确定的加工计划\n\n").append(plan.mechanismId().isEmpty() ? "配方：" + plan.recipeId() : "独立机制：" + plan.mechanismId())
                .append("\n执行次数：").append(plan.craftCount());
        if (!plan.steps().isEmpty()) {
            text.append("\n\n整条加工链（一次批准全部确定步骤）：\n");
            for (int index = 0; index < plan.steps().size(); index++) {
                var step = plan.steps().get(index);
                text.append(index + 1).append(". ").append(step.recipeId()).append(" → ");
                for (var output : step.products()) text.append(materialLabel(output)).append(" × ").append(output.count()).append("；");
                text.append('\n');
            }
        }
        text.append("\n\n真实物料（含已有目标物品；中间产物不重复扣除）：\n");
        for (var need : plan.needs()) {
            var stock = source(plan, need.stockKey());
            text.append("• ").append(materialLabel(stock.stack())).append(" × ").append(need.count())
                    .append(stock.sourceId().equals("player") ? "（本人背包，槽位 " : "（授权来源，槽位 ")
                    .append(stock.slot()).append("）\n");
        }
        text.append(plan.chances().isEmpty() ? "\n产物及返还：\n" : "\n产物上限（概率结果不保证达到此数量）：\n");
        for (var product : plan.products()) text.append("• ").append(materialLabel(product)).append(" × ").append(product.count()).append('\n');
        for (var chance : plan.chances()) text.append("每次每单位：").append(materialLabel(chance.template())).append(" · ")
                .append(String.format(Locale.ROOT, "%.1f%%", Math.clamp(chance.chance(), 0, 1) * 100)).append('\n');
        if (plan.virtualParameters().containsKey("virtualTool")) text.append("\n虚拟辅助工具：").append(plan.virtualParameters().get("virtualTool"))
                .append("，时运 0；无需持有，不扣耐久。\n");
        return text.append("\n免设备、免燃料、免能耗、免经验消耗、免辅助工具损耗、无加工等待。\n")
                .append("满仓时产物保留在本人托管中；出现不确定变化将停止并等待对账。\n\n")
                .append("配方代次：").append(plan.recipeGeneration()).append("\n计划摘要：").append(plan.hash()).toString();
    }
    private static String materialLabel(MaterialStack stack) {
        JsonObject item = AiJson.object(stack.itemJson(), 1_048_576, 32);
        String id = item.get("id").getAsString();
        String[] parts = id.split(":", 2);
        String name = id;
        if (parts.length == 2) for (String prefix : List.of("block.", "item.")) {
            String key = prefix + parts[0] + "." + parts[1];
            String translated = firefly520.fireflymc.util.ServerLanguageLoader.getTranslation(key);
            if (!translated.equals(key)) { name = translated + " [" + id + "]"; break; }
        }
        if (item.has("components") && !item.getAsJsonObject("components").isEmpty())
            name += "（带组件，摘要 " + stack.fingerprint().substring(0, 12) + "）";
        return name;
    }
    public String recoveryReviewText(Actor actor, UUID id, long revision, String hash) {
        var review = ledger.recoveryReview(actor, id);
        if (review.revision() != revision || !review.reviewHash().equals(hash)) throw new IllegalStateException("RECOVERY_REVIEW_CHANGED");
        return "恢复已保存的本人材料账本\n\n原生保存凭证与最后确定账本一致。\n操作：" + id
                + "\n复核版本：" + revision + "\n恢复阶段：" + review.restorePhase()
                + "\n托管原料：" + review.escrowUnits() + " 个\n未交付产物：" + review.undeliveredUnits()
                + " 个\n\n本次只恢复账本阶段，不执行取料、配方回调、放置或发放。后续执行和返还仍须单独批准与现场核验。";
    }
    private JsonObject operationView(MaterialLedger.Operation op) {
        JsonObject result = new JsonObject(); result.addProperty("operationId", op.operationId().toString());
        result.addProperty("state", op.state().name()); result.addProperty("reason", op.reason());
        result.addProperty("planHash", op.planHash()); result.addProperty("policy", op.policy());
        result.addProperty("nativeSaveStatus", op.saved() ? op.nativeSaves().isEmpty() ? "NOT_REQUIRED" : "CONFIRMED" : "PENDING_NATIVE_SAVE");
        result.addProperty("pendingNativeSaves", op.nativeSaves().stream().filter(save -> save.receipt().isEmpty()).count());
        result.add("heldInputs", AiJson.GSON.toJsonTree(op.escrow().stream().map(VirtualProcessingService::publicMaterial).toList()));
        result.add("undelivered", AiJson.GSON.toJsonTree(op.remainingProducts().stream().map(VirtualProcessingService::publicMaterial).toList()));
        result.add("deliveredCounts", AiJson.GSON.toJsonTree(op.delivered()));
        return result;
    }
    /** Full component codecs stay in the private ledger, not in automatic model inventory scans. */
    public static JsonObject publicMaterial(MaterialStack stack) {
        JsonObject data = new JsonObject();
        data.addProperty("count", stack.count());
        if (stack.empty()) { data.addProperty("empty", true); return data; }
        JsonObject encoded = AiJson.object(stack.itemJson(), 1_048_576, 32);
        data.add("id", encoded.get("id")); data.addProperty("componentsDigest", stack.fingerprint());
        if (encoded.has("components")) {
            var components = encoded.getAsJsonObject("components");
            data.add("componentTypes", AiJson.GSON.toJsonTree(components.keySet()));
            data.addProperty("customNamePresent", components.has("minecraft:custom_name"));
            data.addProperty("enchanted", components.has("minecraft:enchantments") || components.has("minecraft:stored_enchantments"));
            if (components.has("minecraft:damage") && components.get("minecraft:damage").isJsonPrimitive()
                    && components.get("minecraft:damage").getAsJsonPrimitive().isNumber()) data.add("damage", components.get("minecraft:damage"));
        }
        return data;
    }
    public Plan load(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        actor.requireAccess(workspace.owner());
        Path file = path(workspace, id);
        if (!Files.exists(file) || Files.size(file) > 8 * 1024 * 1024) throw new IllegalArgumentException("PROCESSING_PLAN_NOT_FOUND");
        Plan plan = AiJson.GSON.fromJson(Files.readString(file), Plan.class);
        if (!plan.id().equals(id) || !plan.owner().equals(actor.owner()) || !plan.policy().equals("MATERIALS_ONLY@1")
                || plan.recipeId().isBlank() == plan.mechanismId().isBlank()
                || plan.craftCount() < 1 || plan.craftCount() > 16 || plan.needs().size() > 81
                || !plan.hash().equals(hash) || !AiJson.digest(AiJson.canonical(withHash(plan, ""))).equals(hash))
            throw new SecurityException("PROCESSING_PLAN_CHANGED");
        return plan;
    }
    private static Path path(Workspace workspace, UUID id) {
        Path dir = workspace.directory().resolve("processing"), file = dir.resolve(id + ".json");
        if (Files.isSymbolicLink(dir) || Files.isSymbolicLink(file)) throw new SecurityException("UNSAFE_PROCESSING_PATH");
        return file;
    }
    private static Plan withHash(Plan p, String hash) {
        return new Plan(p.id(), p.executionEpoch(), p.owner(), p.taskId(), p.recipeId(), p.mechanismId(), p.recipeType(), p.recipeGeneration(), p.dimension(),
                p.craftCount(), p.targetItem(), p.inputs(), p.products(), p.chances(), p.virtualParameters(), p.sources(), p.needs(), p.experiencePerCraft(), p.callbackCategory(), p.policy(), p.steps(), hash);
    }
    private static String supportId(Plan plan) { return plan.callbackCategory() + ":MATERIALS_ONLY@1"; }
    private static void boundedPlan(Plan plan) {
        if (plan.products().size() > 128 || plan.needs().size() > 81
                || AiJson.GSON.toJson(plan).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8 * 1024 * 1024)
            throw new IllegalStateException("PROCESSING_PLAN_LIMIT");
    }
    private <T> T onMain(TaskControl control, Callable<T> action) throws Exception {
        if (closed) throw new IllegalStateException("AI_STOPPING");
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!main.offer(new Work<>(control, action, future, null))) throw new IllegalStateException("PROCESSING_QUEUE_FULL");
        return control.await(future, 10000);
    }
    private <T> Applied<T> commitOnMain(TaskControl control, Callable<T> action, EvidenceRecorder<T> recorder) throws Exception {
        if (closed) throw new CommittedCall.Rejected(new IllegalStateException("AI_STOPPING"));
        var committed = new CommittedCall<T>();
        var recorded = new CompletableFuture<Applied<T>>();
        nativePending.incrementAndGet();
        committed.whenSettled((effect, failure) -> {
            try {
                if (failure != null) recorded.completeExceptionally(failure);
                else evidenceIo.execute(() -> {
                    try { recorded.complete(new Applied<>(effect, recorder.record(effect))); }
                    catch (Exception recordingFailure) { recorded.completeExceptionally(recordingFailure); }
                });
            } catch (RejectedExecutionException unavailable) { recorded.completeExceptionally(unavailable); }
            finally { if (nativePending.decrementAndGet() == 0 && closed) evidenceIo.shutdown(); }
        });
        if (closed || !main.offer(new Work<>(control, action, new CompletableFuture<>(), committed)))
            committed.rejectBeforeClaim(new IllegalStateException("PROCESSING_QUEUE_UNAVAILABLE"));
        committed.awaitEvidence(10000);
        // Recording outlives a cancelled/timed-out waiter. It never performs disk IO on the server thread.
        try { return recorded.get(10000, TimeUnit.MILLISECONDS); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception error) throw error;
            throw failure;
        }
    }
    public void tick() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        long end = System.nanoTime() + 2_000_000;
        for (int count = 0; count < 2 && System.nanoTime() < end; count++) {
            Work<?> work = main.poll(); if (work == null) break; run(work);
        }
    }
    private static <T> void run(Work<T> work) {
        if (work.committed() != null) { work.committed().run(work.control(), work.action()); return; }
        if (work.future().isCancelled()) return;
        try { work.control().check(); work.future().complete(work.action().call()); }
        catch (Exception failure) { work.future().completeExceptionally(failure); }
    }
    public void close() {
        closed = true; Work<?> work;
        runtimeRecipes.clear();
        while ((work = main.poll()) != null) {
            if (work.committed() != null) work.committed().rejectBeforeClaim(new IllegalStateException("AI_STOPPING"));
            else work.future().completeExceptionally(new IllegalStateException("AI_STOPPING"));
        }
        if (nativePending.get() == 0) evidenceIo.shutdown();
    }
    public boolean awaitEvidenceStopped(long milliseconds) throws InterruptedException { return evidenceIo.awaitTermination(milliseconds, TimeUnit.MILLISECONDS); }
    @FunctionalInterface private interface Action { AgentTool.Result run(AgentTool.Context context, JsonObject arguments) throws Exception; }
    private void tool(ToolRegistry registry, String name, String description, String properties, List<String> required,
                      AgentTool.Risk risk, Action action) {
        JsonObject schema = new JsonObject(); schema.addProperty("type", "object");
        schema.add("properties", JsonParser.parseString(properties)); schema.add("required", AiJson.GSON.toJsonTree(required));
        schema.addProperty("additionalProperties", false);
        registry.register(new DefinedTool(new AgentTool.Descriptor(name, description, schema, risk, "materials.own", 0,
                AgentTool.ExecutionThread.IO, 120000, true, ""), (ctx, args) -> {
                    ctx.actor().requireAccess(ctx.workspace().owner()); ctx.control().check();
                    try { return action.run(ctx, args); }
                    catch (AgentTool.Deferred deferred) { throw deferred; }
                    catch (TaskControl.Stopped stopped) {
                        if (!risk.requiresApproval()) throw stopped;
                        return MaterialStopReceipt.capture(ledger, ctx, args, stopped);
                    }
                    catch (Exception failure) {
                        if (risk != AgentTool.Risk.READ_ONLY) throw failure;
                        return result(false, safe(failure), "查询或规划未完成：" + safe(failure) + "。没有扣料或加工。", new JsonObject());
                    }
                }));
    }
    private static AgentTool.Result result(boolean success, String code, String summary, JsonObject data) {
        return new AgentTool.Result(success, code, summary, data, "server-material-ledger", "", false);
    }
    private static String safe(Throwable error) {
        String text = error.getMessage(); return text != null && text.matches("[A-Z_]{1,100}") ? text : "PROCESSING_FAILED";
    }
}
