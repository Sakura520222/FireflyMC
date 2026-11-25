package firefly520.fireflymc.ai.processing.advanced;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.processing.ProcessingBatchProducts;
import firefly520.fireflymc.ai.processing.VirtualProcessingService;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.CommittedCall;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.common.CommonHooks;

/** Approved one-operation advanced material transactions. No model-controlled seeds, quantities or native results. */
public final class AdvancedProcessingService implements AutoCloseable {
    private record Work<T>(TaskControl control, Callable<T> action, CompletableFuture<T> future) {}
    private record Binding(int slot, String sourceKey, int count) {}
    private record NativeSnapshot(AdvancedTransformation transformation, List<MaterialStack> products,
                                  Map<ResourceLocation, Recipe<?>> recipes, String dimension, long generation) {}
    private final MinecraftServer server;
    private final MaterialInventoryService inventory;
    private final MaterialLedger ledger;
    private final VirtualProcessingService processing;
    private final LongSupplier generation;
    private final UUID epoch = UUID.randomUUID();
    private final ArrayBlockingQueue<Work<?>> queue = new ArrayBlockingQueue<>(128);
    private final Map<UUID, Map<ResourceLocation, Recipe<?>>> bindings = new ConcurrentHashMap<>();
    private final Map<UUID, Long> created = new ConcurrentHashMap<>();
    private final Set<UUID> executing = ConcurrentHashMap.newKeySet();
    private final SecureRandom random = new SecureRandom();
    private volatile boolean closed;

    public AdvancedProcessingService(MinecraftServer server, MaterialInventoryService inventory, MaterialLedger ledger,
                                     VirtualProcessingService processing, LongSupplier generation) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_SERVER_REQUIRED");
        this.server = server; this.inventory = inventory; this.ledger = ledger; this.processing = processing; this.generation = generation;
    }
    public void register(ToolRegistry tools) {
        tool(tools, "advanced_processing_plan", "神化/Psi、精妙系列/抽屉、Quark/锦致装饰/地图册的已适配专属及动态加工。每次一个操作，先用 inventory_list 获取本人 sourceKey，再用 inputs 指定真实角色槽与数量；不凭显示配方造输入。recipeId/mechanismId 二选一；choice 仅重铸/附魔的原生0..2档。动态合成用原3×3槽0..8；空槽可按受审配方提供免费用虚拟辅助工具，不能凭空返还工具。锻造模板0/装备1/追加2；宝石base0/top1/left2/right3；灌注目标0/催化剂1；重铸装备0/材料1/印记2；刷怪笼目标0/main1/off2；Psi trick目标0。成功后 advanced_processing_execute 请求工作台审批。",
                "{\"recipeId\":{\"type\":\"string\",\"maxLength\":256},\"mechanismId\":{\"type\":\"string\",\"maxLength\":256},\"choice\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2},\"inputs\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":81,\"items\":{\"type\":\"object\",\"properties\":{\"slot\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":8},\"sourceKey\":{\"type\":\"string\",\"maxLength\":4096},\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":64}},\"required\":[\"slot\",\"sourceKey\",\"count\"],\"additionalProperties\":false}}}",
                List.of("inputs"), AgentTool.Risk.READ_ONLY, this::plan);
        tool(tools, "advanced_processing_execute", "执行工作台批准的固定专属加工计划 planId/planHash。固定 MATERIALS_ONLY，实际扣取本人授权材料，持久采样/回调/交付；不重新抽样，不用控制台或管理指令。每次一个原生操作。",
                "{\"planId\":{\"type\":\"string\"},\"planHash\":{\"type\":\"string\"}}", List.of("planId", "planHash"), AgentTool.Risk.PLAYER_STATE,
                (ctx, args) -> execute(ctx, load(ctx.workspace(), ctx.actor(), UUID.fromString(args.get("planId").getAsString()), args.get("planHash").getAsString())));
    }

    private AgentTool.Result plan(AgentTool.Context ctx, JsonObject args) throws Exception {
        if (ctx.taskId() == null || args.has("recipeId") == args.has("mechanismId")) throw new IllegalArgumentException("ONE_ADVANCED_REFERENCE_REQUIRED");
        long privacy = ctx.workspace().conversation().privacyEpoch();
        String recipeId = args.has("recipeId") ? args.get("recipeId").getAsString() : "";
        String mechanism = args.has("mechanismId") ? args.get("mechanismId").getAsString() : "";
        if (recipeId.length() > 256 || mechanism.length() > 256 || recipeId.isBlank() == mechanism.isBlank()) throw new IllegalArgumentException("ONE_ADVANCED_REFERENCE_REQUIRED");
        int choice = args.has("choice") ? args.get("choice").getAsBigDecimal().intValueExact() : 0;
        if (choice < 0 || choice > 2) throw new IllegalArgumentException("ADVANCED_CHOICE_INVALID");
        List<Binding> requested = parseBindings(args.getAsJsonArray("inputs"));
        var snapshot = ctx.control().await(inventory.scan(ctx.actor()), 30000);
        var unique = new LinkedHashMap<String, MaterialLedger.Stock>();
        for (var stock : snapshot.rows()) unique.merge(stock.key(), stock, (a, b) -> a.extractableCount() >= b.extractableCount() ? a : b);
        var selected = new LinkedHashMap<String, MaterialLedger.Stock>();
        var required = new LinkedHashMap<String, Integer>();
        int size = onMain(ctx.control(), () -> inputSize(recipeId, mechanism, ctx.actor()));
        var inputs = new ArrayList<>(Collections.nCopies(size, MaterialStack.EMPTY));
        for (Binding bind : requested) {
            if (bind.slot() >= size) throw new IllegalArgumentException("ADVANCED_ROLE_SLOT_INVALID");
            var source = unique.get(bind.sourceKey());
            if (source == null) throw new IllegalArgumentException("ADVANCED_SOURCE_NOT_IN_CURRENT_INVENTORY");
            MaterialStack current = inputs.get(bind.slot());
            if (!current.empty() && !current.itemJson().equals(source.stack().itemJson())) throw new IllegalArgumentException("ADVANCED_ROLE_COMPONENT_MIXING");
            int count = Math.addExact(current.count(), bind.count());
            if (count > 64) throw new IllegalArgumentException("ADVANCED_ROLE_QUANTITY_LIMIT");
            inputs.set(bind.slot(), source.stack().withCount(count)); selected.putIfAbsent(source.key(), source);
            required.merge(source.key(), bind.count(), Math::addExact);
        }
        var stocks = List.copyOf(selected.values()); var available = ledger.available(stocks);
        var needs = required.entrySet().stream().map(entry -> new MaterialLedger.Need(entry.getKey(), entry.getValue())).toList();
        if (needs.stream().anyMatch(need -> need.count() > available.getOrDefault(need.stockKey(), 0L))) throw new IllegalStateException("MATERIAL_RESERVED_OR_MISSING");
        int initialSeed = initialSeed(ctx.workspace());
        UUID operationId = UUID.randomUUID();
        NativeSnapshot calculated = onMain(ctx.control(), () -> calculate(ctx.actor(), recipeId, mechanism, inputs, choice, initialSeed, operationId));
        var transformation = calculated.transformation();
        if (!transformation.consumedBySlot().equals(inputs.stream().map(MaterialStack::count).toList())) {
            var data = new JsonObject(); data.add("requiredRoleCounts", AiJson.GSON.toJsonTree(transformation.consumedBySlot()));
            return result(false, "ADVANCED_EXACT_ROLE_QUANTITY_REQUIRED", "原生核验得到精确角色数量，请按 requiredRoleCounts 重新绑定；未扣料。", data);
        }
        onMain(ctx.control(), () -> {
            inventory.requirePlayer(ctx.actor());
            if (!inventory.outputFits(ctx.actor(), calculated.products(), stocks, needs)) throw new IllegalStateException("OUTPUT_FULL");
            return true;
        });
        var unsigned = new AdvancedProcessingPlan(1, operationId, ctx.workspace().metadata().serverInstanceId(), ctx.workspace().primaryConversationId(),
                ctx.actor().owner(), ctx.taskId(), epoch, privacy, calculated.dimension(), calculated.generation(), recipeId, mechanism, choice, initialSeed,
                transformation.mechanism(), inputs, transformation.consumedBySlot(), calculated.products(), transformation.contextRequirements(), transformation.backingRecipes(),
                transformation.callbackPlan().requiredStages(), !recipeId.isBlank() && AdvancedProcessingAdapters.requiresFreshSampling(calculated.recipes().get(ResourceLocation.parse(recipeId))), stocks, needs, "");
        var plan = unsigned.signed();
        if (AiJson.GSON.toJson(plan).getBytes(StandardCharsets.UTF_8).length > 8 * 1024 * 1024) throw new IllegalArgumentException("ADVANCED_PLAN_SIZE_LIMIT");
        synchronized (ctx.workspace().conversation()) {
            ctx.control().check(); requireTask(ctx, privacy);
            if (bindings.size() >= 2048) throw new IllegalStateException("ADVANCED_PENDING_PLAN_LIMIT");
            if (Files.exists(ctx.workspace().artifact(plan.id()))) throw new IllegalStateException("ADVANCED_PLAN_ALREADY_EXISTS");
            AiJson.write(ctx.workspace().artifact(plan.id()), plan);
            var manifest = new JsonObject(); manifest.addProperty("planId", plan.id().toString()); manifest.addProperty("hash", plan.hash());
            manifest.addProperty("adapter", plan.adapter()); manifest.addProperty("recipeId", plan.recipeId()); manifest.addProperty("mechanismId", plan.mechanismId());
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "advanced_processing_plan", null, manifest, null)));
            bindings.put(plan.id(), calculated.recipes());
            created.put(plan.id(), System.nanoTime());
        }
        return result(true, "", "已由服务端核验一个专属加工操作；实际扣料需工作台批准。", view(plan));
    }

    private AgentTool.Result execute(AgentTool.Context ctx, AdvancedProcessingPlan plan) throws Exception {
        ctx.actor().requireAccess(plan.owner());
        if (!plan.taskId().equals(ctx.taskId())) throw new SecurityException("ADVANCED_TASK_MISMATCH");
        if (!executing.add(plan.id())) throw new IllegalStateException("ADVANCED_OPERATION_BUSY");
        try {
            var existing = ledger.find(ctx.actor(), plan.id());
            if (existing.isPresent() && (existing.get().settled() || existing.get().uncertain()))
                return result(false, existing.get().uncertain() ? "RECOVERY_REQUIRED" : "ADVANCED_ALREADY_RECORDED", "操作已有终态记录，未重放。", status(existing.get()));
            requireTask(ctx, plan.privacyEpoch());
            if (existing.isPresent() && existing.get().state() == MaterialLedger.State.CALLBACK_DONE) {
                if (!existing.get().planHash().equals(plan.hash()) || !existing.get().taskId().equals(plan.taskId())) throw new SecurityException("ADVANCED_OPERATION_ID_REUSED");
                return finishCommittedProducts(ctx, plan);
            }
            onMain(ctx.control(), () -> validate(ctx.actor(), plan));
            MaterialLedger.Operation op;
            if (existing.isEmpty()) {
                onMain(ctx.control(), () -> {
                    check(ctx.actor(), plan);
                    if (!inventory.outputFits(ctx.actor(), plan.products(), plan.sources(), plan.needs())) throw new IllegalStateException("OUTPUT_FULL");
                    return true;
                });
                for (var need : plan.needs()) onMain(ctx.control(), () -> {
                    check(ctx.actor(), plan); var stock = source(plan, need.stockKey());
                    return inventory.prepareExtraction(ctx.actor(), stock, Math.min(need.count(), stock.maxTransferCount()));
                });
                synchronized (ctx.workspace().conversation()) {
                    ctx.control().check(); requireTask(ctx, plan.privacyEpoch());
                    op = ledger.reserve(ctx.actor(), plan.taskId(), plan.id(), plan.hash(), plan.sources(), plan.needs());
                }
            } else {
                op = existing.get();
                if (!op.planHash().equals(plan.hash()) || !op.taskId().equals(plan.taskId())) throw new SecurityException("ADVANCED_OPERATION_ID_REUSED");
                if (op.intent() != null) return result(false, "RECOVERY_REQUIRED", "已有未决原生调用，必须先对账。",
                        status(ledger.markUncertain(ctx.actor(), plan.id(), "UNRESOLVED_ADVANCED_INTENT")));
            }
            try {
                if (op.state() == MaterialLedger.State.RESERVED) op = processing.extractReserved(ctx, op, () -> check(ctx.actor(), plan));
                if (op.uncertain()) return result(false, "RECOVERY_REQUIRED", "实际材料变化不确定，保留证据后停止。", status(op));
                return processSavedEscrow(ctx, plan);
            } catch (AgentTool.Deferred deferred) { throw deferred; }
            catch (Exception failure) { return advancedFailure(ctx, plan, failure); }
        } finally { executing.remove(plan.id()); }
    }
    private AgentTool.Result finishSavedEscrow(AgentTool.Context ctx, AdvancedProcessingPlan plan) throws Exception {
        ctx.actor().requireAccess(plan.owner());
        if (!plan.taskId().equals(ctx.taskId())) throw new SecurityException("ADVANCED_TASK_MISMATCH");
        if (!executing.add(plan.id())) throw new IllegalStateException("ADVANCED_OPERATION_BUSY");
        try { return processSavedEscrow(ctx, plan); }
        catch (AgentTool.Deferred deferred) { throw deferred; }
        catch (Exception failure) { return advancedFailure(ctx, plan, failure); }
        finally { executing.remove(plan.id()); }
    }
    private AgentTool.Result advancedFailure(AgentTool.Context ctx, AdvancedProcessingPlan plan, Exception failure) throws Exception {
        var op = ledger.get(ctx.actor(), plan.id());
        if (op.intent() != null && !op.uncertain()) op = ledger.markUncertain(ctx.actor(), plan.id(), "ADVANCED_INTERRUPTED_DURING_INTENT");
        if (failure instanceof TaskControl.Stopped stopped) throw stopped;
        return result(false, op.uncertain() ? "RECOVERY_REQUIRED" : "PROCESSING_PAUSED", "专属加工未全部完成：" + safe(failure) + "；保留真实托管物料与产物。", status(op));
    }
    private AgentTool.Result processSavedEscrow(AgentTool.Context ctx, AdvancedProcessingPlan plan) throws Exception {
        requireTask(ctx, plan.privacyEpoch()); ctx.actor().requireAccess(plan.owner());
        if (!plan.taskId().equals(ctx.taskId())) throw new SecurityException("ADVANCED_TASK_MISMATCH");
        var op = ledger.get(ctx.actor(), plan.id());
        if (!op.planHash().equals(plan.hash()) || !op.taskId().equals(plan.taskId())) throw new SecurityException("ADVANCED_OPERATION_ID_REUSED");
        if (op.uncertain() || op.intent() != null) return result(false, "RECOVERY_REQUIRED", "原生阶段未决，不能继续加工。", status(op));
        if (op.state() == MaterialLedger.State.ESCROWED) processing.awaitInputSaves(ctx, plan.id(), fresh -> finishSavedEscrow(fresh, plan));
        if (!op.inputsSaved()) return result(false, "RECOVERY_REQUIRED", "输入保存凭据不完整，未继续回调或交付。",
                status(ledger.markUncertain(ctx.actor(), plan.id(), "INPUT_NATIVE_SAVE_PROOF_UNAVAILABLE")));
        for (var input : op.escrow()) if (inventory.protectedByOwner(ctx.actor(), input)) throw new SecurityException("MATERIAL_PROTECTED_BY_OWNER");
        NativeSnapshot verified = onMain(ctx.control(), () -> {
            var current = validate(ctx.actor(), plan);
            if (!inventory.outputFits(ctx.actor(), plan.products(), List.of(), List.of())) throw new IllegalStateException("OUTPUT_FULL");
            return current;
        });
        if ("true".equals(plan.context().get("requiresNativeAssemblyCommit"))) {
            if (op.state() != MaterialLedger.State.ESCROWED || plan.callbacks().isEmpty() || plan.freshSampling())
                throw new IllegalStateException("ADVANCED_NATIVE_PRODUCTION_STATE_INVALID");
            String witness = "advanced-native-production:" + plan.hash();
            ctx.control().check(); ledger.beginNativeProduction(ctx.actor(), plan.id(), witness);
            firefly520.fireflymc.ai.processing.NativeProcessingCallback.Outcome outcome;
            try { outcome = processing.commitOwned(ctx, () -> {
                var current = validate(ctx.actor(), plan); ServerPlayer player = check(ctx.actor(), plan);
                return firefly520.fireflymc.ai.processing.NativeProcessingCallback.run(player,
                        () -> { try (var operation = DynamicRecipeOperationScope.open(plan.id())) {
                            commitWithoutAdditionalCosts(current.transformation(), player);
                        } }, 0);
            }, effect -> processing.recordNativeProduction(ctx, plan.id(), witness, verified.products(), effect)); }
            catch (firefly520.fireflymc.ai.task.CommittedCall.Rejected rejected) {
                ledger.callbackRejectedBeforeDispatch(ctx.actor(), plan.id(), witness, 0, "REJECTED_BEFORE_NATIVE_INVOCATION");
                throw rejected;
            }
            op = ledger.get(ctx.actor(), plan.id());
            if (!outcome.completed() || op.uncertain()) return result(false, op.uncertain() ? "RECOVERY_REQUIRED" : "PROCESSING_PAUSED",
                    "已保留真实输入和原生提交记录；未确认的共享存储产物没有交付，未重放装配。", status(op));
            return finishCommittedProducts(ctx, plan);
        }
        if (op.state() == MaterialLedger.State.ESCROWED) {
            List<MaterialStack> products = verified.products(); String evidence = "fixed-native-outcome:" + plan.hash();
            if (plan.freshSampling()) {
                long seed = random.nextLong();
                products = onMain(ctx.control(), () -> {
                    ServerPlayer player = check(ctx.actor(), plan);
                    var sampled = AdvancedProcessingAdapters.sample(current(plan.recipeId()), restore(plan.inputs(), player), player, seed);
                    if (!sampled.contextRequirements().equals(plan.context()) || !sampled.backingRecipes().equals(plan.backingRecipes())
                            || !sampled.consumedBySlot().equals(plan.consumed())) throw new IllegalStateException("ADVANCED_DISTRIBUTION_CHANGED");
                    return products(sampled, player);
                });
                evidence = "native-salvaging:seed=" + seed + ";plan=" + plan.hash();
            }
            ctx.control().check(); op = ledger.produceSampled(ctx.actor(), plan.id(), products, evidence + ";advanced=" + plan.adapter()
                    + ";generation=" + plan.recipeGeneration() + ";policy=MATERIALS_ONLY;paidCosts=0");
        }
        if (op.state() == MaterialLedger.State.PRODUCED) {
            if (plan.callbacks().isEmpty()) op = ledger.noCallbacksRequired(ctx.actor(), plan.id(), "native-no-success-callback:" + plan.adapter());
            else {
                String witness = "advanced:" + plan.hash(); ledger.beginCallback(ctx.actor(), plan.id(), witness, 1);
                firefly520.fireflymc.ai.processing.NativeProcessingCallback.Outcome outcome;
                try { outcome = processing.commitOwned(ctx, () -> {
                    var current = validate(ctx.actor(), plan); ServerPlayer player = check(ctx.actor(), plan);
                    return firefly520.fireflymc.ai.processing.NativeProcessingCallback.run(player, () -> {
                        var previous = CommonHooks.getCraftingPlayer(); int level = player.experienceLevel, total = player.totalExperience;
                        float progress = player.experienceProgress; CommonHooks.setCraftingPlayer(player);
                        try { current.transformation().commitCallbacks(player); }
                        finally {
                            if (player.experienceLevel < level || player.totalExperience < total || player.experienceLevel == level && player.experienceProgress < progress) {
                                player.experienceLevel = level; player.totalExperience = total; player.experienceProgress = progress;
                            }
                            CommonHooks.setCraftingPlayer(previous);
                        }
                    }, 0);
                }, effect -> processing.recordNativeCallbackPart(ctx, plan.id(), witness, 0, effect)); }
                catch (firefly520.fireflymc.ai.task.CommittedCall.Rejected rejected) {
                    ledger.callbackRejectedBeforeDispatch(ctx.actor(), plan.id(), witness, 0, "REJECTED_BEFORE_NATIVE_INVOCATION");
                    throw rejected;
                }
                op = ledger.get(ctx.actor(), plan.id());
                if (!outcome.completed() || op.uncertain()) return result(false, "RECOVERY_REQUIRED", "原生回调结果与保存义务已记录，未重复触发。", status(op));
            }
        }
        if (op.uncertain()) return result(false, "RECOVERY_REQUIRED", "回调未能确定完成；不会重新触发或重新抽样。", status(op));
        if (op.state() == MaterialLedger.State.CALLBACK_DONE) op = processing.deliver(ctx, op);
        if (op.settled()) { bindings.remove(plan.id()); created.remove(plan.id()); }
        return processing.awaitFinalResult(ctx, plan.id(), current -> result(current.state() == MaterialLedger.State.COMPLETED,
                current.state() == MaterialLedger.State.COMPLETED ? "" : "PROCESSING_PAUSED",
                current.state() == MaterialLedger.State.COMPLETED ? current.products().isEmpty() ? "已实际消耗原料完成加工并确认原生保存；本次概率结果没有产物。"
                        : "专属加工已实际扣料、核验、交付并确认原生保存；附加费用为零。" : "产物保留在本人托管，尚未全部交付。", status(current)));
    }
    private static void commitWithoutAdditionalCosts(AdvancedTransformation transformation, ServerPlayer player) {
        var previous = CommonHooks.getCraftingPlayer(); int level = player.experienceLevel, total = player.totalExperience;
        float progress = player.experienceProgress; CommonHooks.setCraftingPlayer(player);
        try { transformation.commitCallbacks(player); }
        finally {
            if (player.experienceLevel < level || player.totalExperience < total || player.experienceLevel == level && player.experienceProgress < progress) {
                player.experienceLevel = level; player.totalExperience = total; player.experienceProgress = progress;
            }
            CommonHooks.setCraftingPlayer(previous);
        }
    }
    /** Already-created shared output: never re-run its preview or native assembler after the data store changed. */
    private AgentTool.Result finishCommittedProducts(AgentTool.Context ctx, AdvancedProcessingPlan plan) throws Exception {
        requireTask(ctx, plan.privacyEpoch()); ctx.actor().requireAccess(plan.owner());
        if (!plan.taskId().equals(ctx.taskId())) throw new SecurityException("ADVANCED_TASK_MISMATCH");
        var op = ledger.get(ctx.actor(), plan.id());
        if (!op.planHash().equals(plan.hash()) || !op.taskId().equals(plan.taskId())) throw new SecurityException("ADVANCED_OPERATION_ID_REUSED");
        if (op.uncertain() || op.intent() != null) return result(false, "RECOVERY_REQUIRED", "原生提交尚未确认，产物继续隔离。", status(op));
        boolean inputsReturned = op.reason().equals("RETURNING_INPUTS");
        if (op.state() == MaterialLedger.State.CALLBACK_DONE) {
            processing.awaitBeforeDeliverySaves(ctx, plan.id(), fresh -> finishCommittedProducts(fresh, plan));
            op = processing.deliver(ctx, ledger.get(ctx.actor(), plan.id()));
        }
        if (op.settled()) { bindings.remove(plan.id()); created.remove(plan.id()); }
        return processing.awaitFinalResult(ctx, plan.id(), current -> result(!inputsReturned && current.state() == MaterialLedger.State.COMPLETED,
                inputsReturned ? "ADVANCED_INPUTS_RETURNED" : current.state() == MaterialLedger.State.COMPLETED ? "" : "PROCESSING_PAUSED",
                inputsReturned ? "未加工原料已归还；没有继续原配方。" : "只交付已有真实产物并确认保存；没有重算、重建存储、迁移或触发回调。", status(current)));
    }

    public AdvancedProcessingPlan load(Workspace workspace, Actor actor, UUID id, String hash) throws Exception {
        synchronized (workspace.conversation()) {
            actor.requireAccess(workspace.owner());
            boolean indexed = workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events().stream()
                    .anyMatch(event -> event.kind().equals("advanced_processing_plan") && id.toString().equals(event.data().get("planId").getAsString())
                            && hash.equals(event.data().get("hash").getAsString()));
            var path = workspace.artifact(id);
            if (!indexed || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 8 * 1024 * 1024) throw new IllegalArgumentException("ADVANCED_PLAN_NOT_FOUND");
            var plan = AiJson.GSON.fromJson(Files.readString(path), AdvancedProcessingPlan.class);
            if (!plan.id().equals(id) || !plan.owner().equals(actor.owner()) || !plan.serverInstanceId().equals(workspace.metadata().serverInstanceId())
                    || !plan.conversationId().equals(workspace.primaryConversationId()) || !plan.hash().equals(hash) || !plan.intact()
                    || plan.privacyEpoch() != workspace.conversation().privacyEpoch()) throw new SecurityException("ADVANCED_PLAN_IDENTITY_OR_HASH_CHANGED");
            return plan;
        }
    }
    public String review(Workspace workspace, Actor actor, AdvancedProcessingPlan plan) throws Exception {
        load(workspace, actor, plan.id(), plan.hash());
        onMain(new TaskControl(30000), () -> validate(actor, plan));
        var text = new StringBuilder("专属加工审批（一个操作）\n").append(plan.adapter()).append('\n')
                .append(plan.recipeId().isBlank() ? "机制：" + plan.mechanismId() : "配方：" + plan.recipeId()).append("\n真实材料：\n");
        for (var need : plan.needs()) text.append("• ").append(itemId(source(plan, need.stockKey()).stack())).append(" × ").append(need.count()).append('\n');
        text.append(plan.freshSampling() ? "产物上限（概率并非保底）：\n" : "核验产物与返还物：\n");
        for (var product : plan.products()) text.append("• ").append(itemId(product)).append(" × ").append(product.count()).append(" [").append(product.fingerprint(), 0, 12).append("]\n");
        for (var entry : plan.context().entrySet()) if (entry.getKey().startsWith("uniform.") || Set.of("virtualStatsPolicy", "eterna", "quanta", "arcana", "choice", "piece", "tier").contains(entry.getKey()))
            text.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        return text.append("免设备、燃料、能耗、经验、工具损耗与加工等待；不免真实材料。\n不确定回调必须先对账，不能通用重放。\n计划摘要：").append(plan.hash()).toString();
    }
    public static JsonObject view(AdvancedProcessingPlan plan) {
        var data = new JsonObject(); data.addProperty("planId", plan.id().toString()); data.addProperty("planHash", plan.hash());
        data.addProperty("recipeId", plan.recipeId()); data.addProperty("mechanismId", plan.mechanismId()); data.addProperty("adapter", plan.adapter());
        data.addProperty("recipeGeneration", plan.recipeGeneration()); data.addProperty("policy", "MATERIALS_ONLY"); data.addProperty("operations", 1);
        data.addProperty("requiresSampling", plan.freshSampling()); data.add("inputs", projection(plan.inputs())); data.add("products", projection(plan.products()));
        data.add("context", AiJson.GSON.toJsonTree(publicContext(plan.context()))); return data;
    }
    private static Map<String, String> publicContext(Map<String, String> context) {
        var safe = new TreeMap<String, String>();
        context.forEach((key, value) -> { if (!key.toLowerCase(Locale.ROOT).contains("seed") && !key.equals("owner") && !key.equals("stages")) safe.put(key, value); }); return safe;
    }
    private static com.google.gson.JsonElement projection(List<MaterialStack> stacks) {
        return AiJson.GSON.toJsonTree(stacks.stream().map(stack -> stack.empty() ? Map.of("empty", true)
                : Map.of("item", itemId(stack), "count", stack.count(), "componentDigest", stack.fingerprint())).toList());
    }
    private static JsonObject status(MaterialLedger.Operation operation) {
        var data = new JsonObject(); data.addProperty("operationId", operation.operationId().toString()); data.addProperty("state", operation.state().name());
        data.addProperty("uncertain", operation.uncertain()); data.add("held", projection(operation.escrow())); data.add("outputs", projection(operation.products()));
        data.add("delivered", AiJson.GSON.toJsonTree(operation.delivered())); return data;
    }
    private NativeSnapshot validate(Actor actor, AdvancedProcessingPlan plan) {
        check(actor, plan);
        NativeSnapshot actual = calculate(actor, plan.recipeId(), plan.mechanismId(), plan.inputs(), plan.choice(), plan.initializationSeed(), plan.id());
        var value = actual.transformation();
        if (!actual.products().equals(plan.products()) || !value.contextRequirements().equals(plan.context()) || !value.consumedBySlot().equals(plan.consumed())
                || !value.backingRecipes().equals(plan.backingRecipes()) || !value.callbackPlan().requiredStages().equals(plan.callbacks()) || !value.mechanism().equals(plan.adapter()))
            throw new IllegalStateException("ADVANCED_NATIVE_RESULT_OR_CONTEXT_CHANGED");
        return actual;
    }
    private ServerPlayer check(Actor actor, AdvancedProcessingPlan plan) {
        if (!epoch.equals(plan.executionEpoch())) throw new IllegalStateException("ADVANCED_RESTART_REVIEW_REQUIRED");
        if (generation.getAsLong() != plan.recipeGeneration()) throw new IllegalStateException("ADVANCED_RECIPE_RELOADED");
        ServerPlayer player = inventory.requirePlayer(actor);
        if (!player.serverLevel().dimension().location().toString().equals(plan.dimension())) throw new IllegalStateException("ADVANCED_DIMENSION_CHANGED");
        var retained = bindings.get(plan.id());
        if (retained == null) throw new IllegalStateException("ADVANCED_PREVIEW_EXPIRED");
        for (var entry : retained.entrySet()) if (server.getRecipeManager().byKey(entry.getKey()).map(RecipeHolder::value).orElse(null) != entry.getValue())
            throw new IllegalStateException("ADVANCED_NATIVE_RECIPE_REPLACED");
        return player;
    }
    private NativeSnapshot calculate(Actor actor, String recipeId, String mechanism, List<MaterialStack> inputs, int choice, int initialSeed, UUID operationId) {
        ServerPlayer player = inventory.requirePlayer(actor); var nativeInputs = restore(inputs, player);
        var previous = CommonHooks.getCraftingPlayer(); CommonHooks.setCraftingPlayer(player);
        try (var operation = DynamicRecipeOperationScope.open(operationId)) {
            AdvancedTransformation verified;
            if (!mechanism.isBlank()) {
                if (!mechanism.equals(ApothicEnchantingMechanism.ID)) throw new IllegalArgumentException("UNSUPPORTED_PROCESSING_MECHANISM");
                verified = ApothicEnchantingMechanism.verify(nativeInputs, player, choice);
            } else {
                var holder = current(recipeId);
                if (choice != 0 && !ApotheosisReforgingAdapter.supports(holder.value())) throw new IllegalArgumentException("ADVANCED_CHOICE_NOT_APPLICABLE");
                if (holder.value().getType() == RecipeType.CRAFTING && !holder.value().isSpecial()
                        && player.serverLevel().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING) && !player.getRecipeBook().contains(holder))
                    throw new IllegalStateException("RECIPE_NOT_UNLOCKED");
                verified = AdvancedProcessingAdapters.verify(holder, nativeInputs, player, choice, initialSeed);
            }
            var retained = new LinkedHashMap<ResourceLocation, Recipe<?>>();
            for (var ref : verified.backingRecipes()) retained.put(ref.id(), current(ref.id().toString()).value());
            return new NativeSnapshot(verified, products(verified, player), Map.copyOf(retained), player.serverLevel().dimension().location().toString(), generation.getAsLong());
        } finally { CommonHooks.setCraftingPlayer(previous); }
    }
    private int inputSize(String recipeId, String mechanism, Actor actor) {
        inventory.requirePlayer(actor);
        if (!mechanism.isBlank()) {
            if (!mechanism.equals(ApothicEnchantingMechanism.ID) || !ApothicEnchantingMechanism.available()) throw new IllegalArgumentException("UNSUPPORTED_PROCESSING_MECHANISM");
            return 2;
        }
        Recipe<?> value = current(recipeId).value();
        if (AdvancedCraftingAdapter.supports(value)) return 9;
        if (StorageDynamicRecipes.supports(value)) return StorageDynamicRecipes.inputSize(value);
        if (BuildingDynamicRecipes.supports(value)) return BuildingDynamicRecipes.inputSize(value);
        if (ApotheosisGemCuttingAdapter.supports(value)) return 4;
        if (ApotheosisSmithingAdapter.supports(value) || ApotheosisReforgingAdapter.supports(value) || ApothicSpawnerAdapter.supports(value)) return 3;
        if (ApothicInfusionAdapter.supports(value)) return 2;
        if (ApotheosisSalvageAdapter.supports(value) || PsiTrickAdapter.supports(value)) return 1;
        throw new IllegalArgumentException("UNSUPPORTED_PROCESSING_ADAPTER");
    }
    private RecipeHolder<?> current(String id) { return server.getRecipeManager().byKey(ResourceLocation.parse(id)).orElseThrow(() -> new IllegalArgumentException("RECIPE_NOT_FOUND")); }
    private static List<ItemStack> restore(List<MaterialStack> inputs, ServerPlayer player) { return inputs.stream().map(input -> ItemSnapshotCodec.restore(input, player.registryAccess())).toList(); }
    private static List<MaterialStack> products(AdvancedTransformation result, ServerPlayer player) { return ProcessingBatchProducts.aggregate(result, 1, player.registryAccess()); }
    private static int initialSeed(Workspace workspace) {
        String hash = AiJson.digest("native-initial-reforge-v1:" + workspace.metadata().serverInstanceId() + ":" + workspace.owner() + ":" + workspace.primaryConversationId());
        int seed = (int) Long.parseLong(hash.substring(0, 8), 16); return seed == 0 ? 1 : seed;
    }
    private static MaterialLedger.Stock source(AdvancedProcessingPlan plan, String key) { return plan.sources().stream().filter(source -> source.key().equals(key)).findFirst().orElseThrow(); }
    private static String itemId(MaterialStack stack) { return AiJson.object(stack.itemJson(), 1_048_576, 32).get("id").getAsString(); }
    private static List<Binding> parseBindings(JsonArray json) {
        if (json == null || json.isEmpty() || json.size() > 81) throw new IllegalArgumentException("ADVANCED_INPUT_BINDINGS_REQUIRED");
        List<Binding> result = new ArrayList<>(); int total = 0;
        for (var element : json) {
            var value = element.getAsJsonObject(); int slot = value.get("slot").getAsBigDecimal().intValueExact(), count = value.get("count").getAsBigDecimal().intValueExact(); String key = value.get("sourceKey").getAsString();
            if (slot < 0 || slot > 8 || count < 1 || count > 64 || key.length() > 4096 || (total += count) > 512) throw new IllegalArgumentException("ADVANCED_INPUT_BINDINGS_INVALID");
            result.add(new Binding(slot, key, count));
        }
        return List.copyOf(result);
    }
    private static void requireTask(AgentTool.Context ctx, long privacy) {
        if (ctx.workspace().conversation().privacyEpoch() != privacy) throw new IllegalStateException("ADVANCED_PRIVACY_EPOCH_CHANGED");
        var task = ctx.workspace().conversation().task(ctx.taskId());
        if (task == null || task.state().terminal()) throw new IllegalStateException("ADVANCED_TASK_ENDED");
    }
    private <T> T onMain(TaskControl control, Callable<T> action) throws Exception {
        if (closed) throw new IllegalStateException("AI_STOPPING"); var future = new CompletableFuture<T>();
        if (!queue.offer(new Work<>(control, action, future))) throw new IllegalStateException("ADVANCED_QUEUE_FULL");
        return control.await(future, 10000);
    }
    public void tick() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        long now = System.nanoTime();
        for (var entry : created.entrySet()) if (!executing.contains(entry.getKey()) && now - entry.getValue() > TimeUnit.MINUTES.toNanos(30)) {
            created.remove(entry.getKey()); bindings.remove(entry.getKey());
        }
        long end = System.nanoTime() + (server.getAverageTickTimeNanos() > 45_000_000 ? 500_000 : 1_500_000);
        for (int i = 0; i < 2 && System.nanoTime() < end; i++) {
            Work<?> work = queue.poll(); if (work == null) break; run(work);
        }
    }
    private static <T> void run(Work<T> work) {
        if (work.future().isCancelled()) return;
        try { work.control().check(); work.future().complete(work.action().call()); }
        catch (Exception failure) { work.future().completeExceptionally(failure); }
    }
    @Override public void close() {
        closed = true; Work<?> work;
        while ((work = queue.poll()) != null) work.future().completeExceptionally(new IllegalStateException("AI_STOPPING"));
        bindings.clear(); created.clear();
    }
    private static String safe(Throwable failure) { String message = failure.getMessage(); return message != null && message.matches("[A-Z_]{1,120}") ? message : "ADVANCED_PROCESSING_FAILED"; }
    private static AgentTool.Result result(boolean success, String code, String summary, JsonObject data) { return new AgentTool.Result(success, code, summary, data, "server-advanced-material-ledger", "", false); }
    private void tool(ToolRegistry tools, String name, String description, String properties, List<String> required, AgentTool.Risk risk, DefinedTool.Action action) {
        var schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", JsonParser.parseString(properties));
        schema.add("required", AiJson.GSON.toJsonTree(required)); schema.addProperty("additionalProperties", false);
        tools.register(new DefinedTool(new AgentTool.Descriptor(name, description, schema, risk, "materials.own", 0, AgentTool.ExecutionThread.IO, 120000, true, ""),
                (ctx, args) -> {
                    ctx.actor().requireAccess(ctx.workspace().owner()); ctx.control().check();
                    try { return action.run(ctx, args); }
                    catch (AgentTool.Deferred deferred) { throw deferred; }
                    catch (TaskControl.Stopped stopped) {
                        if (!risk.requiresApproval()) throw stopped;
                        return MaterialStopReceipt.capture(ledger, ctx, args, stopped);
                    }
                    catch (Exception failure) { return result(false, safe(failure), "专属加工未完成：" + safe(failure), new JsonObject()); }
                }));
    }
}
