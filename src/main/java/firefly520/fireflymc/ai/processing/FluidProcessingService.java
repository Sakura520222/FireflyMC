package firefly520.fireflymc.ai.processing;

import com.google.gson.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.CommittedCall;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import net.minecraft.server.MinecraftServer;

/** Fixed item + raw-fluid execution. Only trusted native transfers can enter or leave custody. */
public final class FluidProcessingService implements AutoCloseable {
    public record ReturnTarget(UUID inputOperation, int outputIndex, FluidInventoryService.Source source, FluidAmount amount) {}
    public record ReturnPlan(int version, UUID id, UUID operationId, String owner, UUID serverInstanceId,
                             UUID conversationId, long privacyEpoch, long operationRevision, long itemRevision,
                             Map<UUID, Long> fluidRevisions, List<ReturnTarget> targets, List<MaterialStack> itemCustody,
                             String direction, String hash) {
        public ReturnPlan { fluidRevisions = Map.copyOf(fluidRevisions); targets = List.copyOf(targets); itemCustody = List.copyOf(itemCustody); }
        ReturnPlan withHash(String value) { return new ReturnPlan(version, id, operationId, owner, serverInstanceId,
                conversationId, privacyEpoch, operationRevision, itemRevision, fluidRevisions, targets, itemCustody, direction, value); }
        boolean intact() { return version == 1 && targets.size() <= 256 && itemCustody.size() <= 128
                && Set.of("INPUTS", "OUTPUTS").contains(direction) && AiJson.digest(AiJson.canonical(withHash(""))).equals(hash); }
    }
    private final MaterialInventoryService inventory;
    private final FluidInventoryService fluids;
    private final MaterialLedger items;
    private final FluidLedger ledger;
    private final VirtualProcessingService dispatcher;
    private final FluidProcessingPlanner planner;
    private final Map<String, MixedResourceJournal> journals = new ConcurrentHashMap<>();
    private final Set<UUID> executing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, UUID> activeResumes = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile boolean broken;

    public FluidProcessingService(MinecraftServer server, MaterialInventoryService inventory, FluidInventoryService fluids,
                                  MaterialLedger items, FluidLedger ledger, VirtualProcessingService dispatcher,
                                  LongSupplier generation) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("AI_DEDICATED_SERVER_REQUIRED");
        this.inventory = inventory; this.fluids = fluids; this.items = items; this.ledger = ledger; this.dispatcher = dispatcher;
        planner = new FluidProcessingPlanner(server, inventory, fluids, items, ledger, dispatcher, generation);
    }
    /** Called before the runtime accepts tools, for every existing workspace, including offline owners. */
    public synchronized MixedResourceJournal prepare(Workspace workspace) throws IOException {
        if (closed) throw new IllegalStateException("AI_STOPPING");
        var existing = journals.get(workspace.owner());
        if (existing != null) return existing;
        try {
            var created = new MixedResourceJournal(workspace, items, ledger);
            journals.put(workspace.owner(), created); return created;
        } catch (IOException | RuntimeException failure) { broken = true; throw failure; }
    }
    private MixedResourceJournal journal(AgentTool.Context ctx) throws IOException {
        ctx.actor().requireAccess(ctx.workspace().owner()); return prepare(ctx.workspace());
    }
    public boolean recoveryBlocksBackend(String backend) {
        return broken || !ledger.journalAvailable() || items.recoveryBlocksBackend(backend) || ledger.recoveryBlocksBackend(backend)
                || journals.values().stream().anyMatch(journal -> journal.recoveryBlocksBackend(backend));
    }
    public boolean recoveryBlocksPlayer(String owner) {
        return broken || items.recoveryBlocksPlayer(owner) || ledger.recoveryBlocksPlayer(owner)
                || journals.values().stream().anyMatch(journal -> journal.recoveryBlocksPlayer(owner));
    }
    public boolean resourcesSettled(String owner) {
        Actor actor = new Actor(owner, true, false, 0); var journal = journals.get(owner);
        return !broken && ledger.settled(actor) && (journal == null || journal.resourcesSettled(actor));
    }
    public void forgetSettled(Actor actor) {
        // WorkspacePrivacyService already changed the privacy epoch after checking settlement.
        ledger.forgetSettled(actor); journals.remove(actor.owner()); planner.clearPrivateViews(actor.owner());
    }
    public String exportJournal(Actor actor) {
        var data = new JsonObject();
        try { data.add("fluids", JsonParser.parseString(ledger.exportJournal(actor))); }
        catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
        var journal = journals.get(actor.owner());
        data.add("mixed", journal == null ? new JsonArray() : JsonParser.parseString(journal.exportJournal(actor)));
        return data.toString();
    }
    public boolean requiresRecovery(Workspace workspace, UUID task) {
        var journal = journals.get(workspace.owner()); if (journal == null) return false;
        Actor actor = new Actor(workspace.owner(), true, false, 0);
        return journal.snapshot(actor).stream().anyMatch(op -> op.definition().taskId().equals(task) && uncertain(actor, journal, op));
    }
    public JsonObject personalContext(Workspace workspace) {
        var data = new JsonObject(); var journal = journals.get(workspace.owner()); if (journal == null) return data;
        var actor = new Actor(workspace.owner(), true, false, 0);
        var pending = journal.snapshot(actor).stream().filter(op -> op.state() != MixedResourceJournal.State.RETURNED
                && (op.state() != MixedResourceJournal.State.COMMITTED || !op.fluidDeliveriesSaved()
                || java.util.stream.IntStream.range(0, op.definition().fluidOutputs().size()).anyMatch(index -> op.deliveredMb(index) < op.definition().fluidOutputs().get(index).millibuckets())
                || op.definition().fluidInputs().stream().anyMatch(input -> ledger.find(actor, input.operationId()).map(fluid -> !fluid.settled() || !fluid.saved()).orElse(true))
                || items.find(actor, op.definition().id()).map(item -> !item.settled() || !item.saved()).orElse(true))).toList();
        data.addProperty("count", pending.size()); data.add("operations", AiJson.GSON.toJsonTree(pending.stream().limit(8).map(op -> Map.of(
                "operationId", op.definition().id(), "taskId", op.definition().taskId(), "state", op.state(), "returnMode", op.returnMode())).toList()));
        data.addProperty("note", "联合物品/流体义务不因对话压缩消失；完整证据使用fluid_processing_status，未决副作用先复核，不能重做原配方或单独返还输入。"); return data;
    }
    public void register(ToolRegistry tools) {
        tool(tools, "fluid_processing_mechanisms", "发现真实原料流体装桶/倒桶机制；法术书固定配方仍通过recipeId查询和核验。",
                "{}", List.of(), AgentTool.Risk.READ_ONLY, (ctx, args) -> {
                    dispatcher.inspectOwned(ctx, () -> inventory.requirePlayer(ctx.actor())); var data = new JsonObject();
                    data.add("mechanisms", AiJson.GSON.toJsonTree(List.of(
                            Map.of("mechanismId", FluidProcessingAdapters.BUCKET_FILL, "input", "minecraft:bucket", "fluidId", "minecraft:water or minecraft:lava", "millibucketsPerCraft", 1000),
                            Map.of("mechanismId", FluidProcessingAdapters.BUCKET_EMPTY, "inputItemId", "minecraft:water_bucket or minecraft:lava_bucket", "millibucketsPerCraft", 1000))));
                    data.addProperty("planningTool", "fluid_processing_plan"); data.addProperty("executionTool", "fluid_processing_execute");
                    return result(true, "", "服务端原生容器机制；需要真实物品及独立授权的有限流体来源/目标。", data);
                });
        tool(tools, "fluid_processing_plan", "规划真实物品与原料流体加工：原版水/岩浆装桶或倒桶，以及已匹配的法术书炼药锅配方。必须先在工作台授权流体来源；recipeId/mechanismId二选一，装桶指定fluidId，倒桶指定inputItemId。固定真实原料、mB、组件、配方代次与产物储罐，仅规划不扣料。",
                "{\"recipeId\":{\"type\":\"string\"},\"mechanismId\":{\"type\":\"string\"},\"fluidId\":{\"type\":\"string\"},\"inputItemId\":{\"type\":\"string\"},\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":16},\"outputSourceId\":{\"type\":\"string\"}}", List.of(), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> result(true, "", "已固定物品、流体和产物去向；尚未扣料，执行需工作台批准。", view(planner.plan(ctx,
                        new FluidProcessingPlanner.Request(text(args, "recipeId"), text(args, "mechanismId"), text(args, "fluidId"), text(args, "inputItemId"),
                                args.has("count") ? args.get("count").getAsInt() : 1, args.has("outputSourceId") ? UUID.fromString(text(args, "outputSourceId")) : null)))));
        tool(tools, "fluid_processing_execute", "执行工作台已批准的固定物品/流体计划；真实取料、联合消耗与产物托管，MATERIALS_ONLY。重复计划不重放，未知原生结果先对账。",
                "{\"planId\":{\"type\":\"string\"},\"planHash\":{\"type\":\"string\"}}", List.of("planId", "planHash"), AgentTool.Risk.PLAYER_STATE,
                (ctx, args) -> execute(ctx, planner.load(ctx.workspace(), ctx.actor(), UUID.fromString(text(args, "planId")), text(args, "planHash"))));
        tool(tools, "fluid_processing_status", "读取本人联合物料账本，包含未交付mB、保存状态和待对账阶段；不会重新执行。",
                "{\"operationId\":{\"type\":\"string\"},\"offset\":{\"type\":\"integer\",\"minimum\":0}}", List.of(), AgentTool.Risk.READ_ONLY, (ctx, args) -> {
                    var journal = journal(ctx); var data = new JsonObject();
                    if (args.has("operationId")) data = status(ctx.actor(), journal, UUID.fromString(text(args, "operationId")));
                    else {
                        var all = journal.list(ctx.actor()); int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
                        if (offset < 0 || offset > all.size()) throw new IllegalArgumentException("FLUID_OPERATION_OFFSET");
                        var rows = new JsonArray(); int end = Math.min(offset + 8, all.size());
                        for (var op : all.subList(offset, end)) rows.add(status(ctx.actor(), journal, op.definition().id()));
                        data.add("operations", rows); data.addProperty("nextOffset", end); data.addProperty("total", all.size());
                    }
                    return result(true, "", "本人实际联合账本；未确认保存不代表已安全落盘。", data);
                });
        tool(tools, "fluid_processing_return_preview", "预览取消剩余取料并归还真实托管：加工前返原料，加工后仅交付产物。可指定已授权targetSourceId重新选择流体去向；生成固定退还版本，批准前不改变操作。",
                "{\"operationId\":{\"type\":\"string\"},\"targetSourceId\":{\"type\":\"string\"}}", List.of("operationId"), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> result(true, "", "退还版本已固定；每次实际回填仍核验授权和容量，未交付部分保留托管。", returnView(returnPreview(ctx,
                        UUID.fromString(text(args, "operationId")), args.has("targetSourceId") ? UUID.fromString(text(args, "targetSourceId")) : null))));
        tool(tools, "fluid_processing_return", "按工作台批准的固定退还预览归还已有物料。不会凭空返还已消耗原料；满仓/撤权保持托管，未知结果禁止普通继续。",
                "{\"reviewId\":{\"type\":\"string\"},\"reviewHash\":{\"type\":\"string\"}}", List.of("reviewId", "reviewHash"), AgentTool.Risk.PLAYER_STATE,
                (ctx, args) -> returnMaterials(ctx, loadReturn(ctx.workspace(), ctx.actor(), UUID.fromString(text(args, "reviewId")), text(args, "reviewHash"))));
        tool(tools, "fluid_processing_resume_preview", "对已复核且有真实保存凭据的输入托管生成新的继续加工审批。逐次用当前原生配方核验旧输入和固定产物，区分已托管与仍需从原授权来源提取的精确数量；已取部分不重取。可指定新的已授权流体目标targetSourceId。取消或未知结果不能续作，先复核或批准退还。",
                "{\"operationId\":{\"type\":\"string\"},\"targetSourceId\":{\"type\":\"string\"}}", List.of("operationId"), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> result(true, "", "已固定当前配方核验、原操作和实际托管；继续加工需要新的工作台批准。", resumeView(resumePreview(ctx,
                        UUID.fromString(text(args, "operationId")), args.has("targetSourceId") ? UUID.fromString(text(args, "targetSourceId")) : null))));
        tool(tools, "fluid_processing_resume", "按新批准的继续版本保留原operationId、taskId和固定产物，已保存托管不重复提取；有缺项时仅从仍有效的原授权来源取出预览固定的剩余数量，再等待真实保存后加工。当前配方、授权、维度、保存证据或输出去向变化时暂停。",
                "{\"reviewId\":{\"type\":\"string\"},\"reviewHash\":{\"type\":\"string\"}}", List.of("reviewId", "reviewHash"), AgentTool.Risk.PLAYER_STATE,
                (ctx, args) -> resume(ctx, loadResume(ctx.workspace(), ctx.actor(), UUID.fromString(text(args, "reviewId")), text(args, "reviewHash"))));
        tool(tools, "fluid_processing_reconcile_preview", "检查联合物品/流体的实际原生保存凭证，生成固定复核；数量相同不算证据，不派发原生操作。",
                "{\"operationId\":{\"type\":\"string\"}}", List.of("operationId"), AgentTool.Risk.READ_ONLY,
                (ctx, args) -> {
                    var journal = journal(ctx); UUID id = UUID.fromString(text(args, "operationId"));
                    promoteUncertainty(ctx.actor(), journal, id);
                    return result(true, "", "仅凭正向保存证据生成复核，批准后不重放原生取料、回填或交付。", AiJson.GSON.toJsonTree(journal.recoveryReview(ctx.actor(), id, items, ledger)).getAsJsonObject());
                });
        tool(tools, "fluid_processing_reconcile", "工作台批准后恢复联合账本的已知阶段；补齐纯账本写入，不重放任何原生副作用。后续退还需另行批准。",
                "{\"operationId\":{\"type\":\"string\"},\"revision\":{\"type\":\"integer\",\"minimum\":1},\"reviewHash\":{\"type\":\"string\"}}",
                List.of("operationId", "revision", "reviewHash"), AgentTool.Risk.PLAYER_STATE, (ctx, args) -> {
                    UUID id = UUID.fromString(text(args, "operationId")); var journal = journal(ctx);
                    dispatcher.inspectOwned(ctx, () -> inventory.requirePlayer(ctx.actor()));
                    journal.reconcile(ctx.actor(), id, args.get("revision").getAsLong(), text(args, "reviewHash"), items, ledger);
                    return result(true, "", "联合账本已按保存证据恢复；没有重放原生操作。", status(ctx.actor(), journal, id));
                });
    }
    public FluidProcessingPlan load(Workspace workspace, Actor actor, UUID id, String hash) throws Exception { return planner.load(workspace, actor, id, hash); }
    public FluidProcessingPlan plan(AgentTool.Context ctx, FluidProcessingPlanner.Request request) throws Exception { return planner.plan(ctx, request); }
    public List<MixedResourceJournal.Operation> recoveryOperations(Workspace workspace, Actor actor) throws IOException {
        actor.requireAccess(workspace.owner()); return prepare(workspace).snapshot(actor);
    }
    public MixedResourceJournal.RecoveryReview recoveryReview(Workspace workspace, Actor actor, UUID id) throws IOException {
        var journal = prepare(workspace); promoteUncertainty(actor, journal, id);
        return journal.recoveryReview(actor, id, items, ledger);
    }
    public java.util.concurrent.CompletionStage<Void> awaitKnown(Workspace workspace, Actor actor, UUID id) throws IOException {
        actor.requireAccess(workspace.owner()); var op = prepare(workspace).get(actor, id);
        var stages = new ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        stages.add(inventory.nativeSaves().awaitJournalled(id).toCompletableFuture());
        for (var input : op.definition().fluidInputs()) stages.add(fluids.nativeSaves().awaitJournalled(input.operationId()).toCompletableFuture());
        return java.util.concurrent.CompletableFuture.allOf(stages.toArray(java.util.concurrent.CompletableFuture[]::new)).minimalCompletionStage();
    }
    public MixedResourceJournal.ReturnView continueReturn(Workspace workspace, Actor actor, UUID id) throws IOException {
        actor.requireAccess(workspace.owner()); return prepare(workspace).continueReturn(actor, id);
    }
    public boolean settledForTask(Workspace workspace, Actor actor, UUID task) {
        actor.requireAccess(workspace.owner()); var journal = journals.get(actor.owner());
        return !broken && ledger.list(actor).stream().filter(op -> op.task().equals(task)).allMatch(op -> op.settled() && op.saved())
                && (journal == null || journal.resourcesSettledForTask(actor, task));
    }
    public boolean operationSettled(Workspace workspace, Actor actor, UUID operation) {
        actor.requireAccess(workspace.owner()); var journal = journals.get(actor.owner());
        return journal != null && journal.resourceOperationSettled(actor, operation);
    }
    public boolean ownsOperation(Workspace workspace, Actor actor, UUID operation) {
        actor.requireAccess(workspace.owner()); var journal = journals.get(actor.owner());
        return journal != null && journal.snapshot(actor).stream().anyMatch(op -> op.definition().id().equals(operation));
    }
    public List<MaterialSourceGrants.Grant> sources(Workspace workspace, Actor actor) {
        actor.requireAccess(workspace.owner()); return fluids.grants(actor).list(actor);
    }

    /** Read-only preview: a new process binds the current recipe, while the original definition stays fixed. */
    public boolean resumeAvailable(Workspace workspace, Actor actor, UUID id) {
        try {
            actor.requireAccess(workspace.owner()); var operation = prepare(workspace).get(actor, id);
            var original = planner.load(workspace, actor, id, operation.definition().planHash());
            FluidProcessingResume.requirePending(original, operation);
            var item = items.get(actor, id); FluidProcessingResume.requireSavedInputs(original, item, resumeFluids(actor, original));
            return item.escrow().stream().noneMatch(input -> inventory.protectedByOwner(actor, input));
        } catch (Exception notReady) { return false; }
    }
    public FluidProcessingResume resumePreview(AgentTool.Context ctx, UUID id, UUID targetSourceId) throws Exception {
        var journal = journal(ctx); var operation = journal.get(ctx.actor(), id);
        var original = planner.load(ctx.workspace(), ctx.actor(), id, operation.definition().planHash());
        if (!Objects.equals(ctx.taskId(), original.taskId())) throw new SecurityException("FLUID_TASK_MISMATCH");
        FluidProcessingResume.requirePending(original, operation);
        var item = items.get(ctx.actor(), id); var custody = resumeFluids(ctx.actor(), original);
        FluidProcessingResume.requireSavedInputs(original, item, custody);
        var facts = planner.resumeFacts(ctx, original);
        var targets = new ArrayList<FluidProcessingPlan.OutputTarget>();
        if (targetSourceId != null) {
            if (original.fluidOutputs().isEmpty()) throw new IllegalArgumentException("FLUID_RESUME_NO_FLUID_OUTPUT");
            var selected = ctx.control().await(fluids.scan(ctx.actor()), 30000).sources().stream()
                    .filter(source -> source.grantId().equals(targetSourceId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("FLUID_OUTPUT_SOURCE_UNAVAILABLE"));
            var fresh = dispatcher.inspectOwned(ctx, () -> current(ctx.actor(), selected, false));
            targets.addAll(FluidProcessingPlanner.allocateOutputs(original.fluidOutputs(), List.of(fresh), List.of()));
        } else {
            var refreshed = new LinkedHashMap<String, FluidInventoryService.Source>();
            for (var target : original.outputTargets()) {
                var fresh = refreshed.get(target.source().backend());
                if (fresh == null) {
                    fresh = dispatcher.inspectOwned(ctx, () -> current(ctx.actor(), target.source(), false));
                    refreshed.put(target.source().backend(), fresh);
                }
                targets.add(new FluidProcessingPlan.OutputTarget(target.outputIndex(), fresh, target.amount()));
            }
        }
        var review = new FluidProcessingResume(1, UUID.randomUUID(), original, planner.executionEpoch(), facts.generation(), facts.dimension(),
                operation.revision(), item, custody, facts.evaluations(), targets, "").signed();
        validateResumeDestinations(ctx, review);
        validateResumeVersion(ctx.workspace(), ctx.actor(), review);
        planner.bindResume(ctx, review, facts);
        validateResumeRemainingInputs(ctx, review);
        synchronized (ctx.workspace().conversation()) {
            ctx.control().check(); review.requireIdentity(ctx.workspace(), ctx.actor(), planner.executionEpoch());
            if (Files.exists(ctx.workspace().artifact(review.id()), LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("FLUID_RESUME_ALREADY_EXISTS");
            if (AiJson.GSON.toJson(review).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8 * 1024 * 1024)
                throw new IllegalArgumentException("FLUID_RESUME_SIZE_LIMIT");
            AiJson.write(ctx.workspace().artifact(review.id()), review);
            var manifest = new JsonObject(); manifest.addProperty("reviewId", review.id().toString()); manifest.addProperty("hash", review.hash());
            manifest.addProperty("operationId", id.toString());
            ctx.workspace().conversation().append(List.of(new Draft(original.taskId(), "fluid_resume_preview", null, manifest, null)));
        }
        return review;
    }
    public FluidProcessingResume loadResume(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        return planner.canonicalResume(readResume(workspace, actor, id, hash, planner.executionEpoch()));
    }
    static FluidProcessingResume readResume(Workspace workspace, Actor actor, UUID id, String hash, UUID executionEpoch) throws IOException {
        var review = readStoredResume(workspace, actor, id, hash);
        review.requireIdentity(workspace, actor, executionEpoch); return review;
    }
    public UUID resumeOperationReference(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        return readStoredResume(workspace, actor, id, hash).operationId();
    }
    static FluidProcessingResume readStoredResume(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        actor.requireAccess(workspace.owner());
        if (id == null || hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("FLUID_RESUME_NOT_FOUND");
        synchronized (workspace.conversation()) {
            boolean indexed = workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events().stream().anyMatch(event ->
                    event.kind().equals("fluid_resume_preview") && event.data() != null && event.data().has("reviewId") && event.data().has("hash")
                    && id.toString().equals(event.data().get("reviewId").getAsString()) && hash.equals(event.data().get("hash").getAsString()));
            Path file = workspace.artifact(id);
            if (!indexed || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 8 * 1024 * 1024)
                throw new IllegalArgumentException("FLUID_RESUME_NOT_FOUND");
            var review = AiJson.GSON.fromJson(Files.readString(file), FluidProcessingResume.class);
            if (review == null || !id.equals(review.id()) || !hash.equals(review.hash()))
                throw new SecurityException("FLUID_RESUME_IDENTITY_OR_HASH_CHANGED");
            review.requireStorageIdentity(workspace, actor); return review;
        }
    }
    /** Version/custody validation is pure IO; approval review must also load the indexed artifact. */
    public void validateResumeVersion(Workspace workspace, Actor actor, FluidProcessingResume review) throws IOException {
        review.requireIdentity(workspace, actor, planner.executionEpoch());
        if (closed || broken || !items.journalAvailable() || !ledger.journalAvailable())
            throw new IllegalStateException("MATERIAL_JOURNAL_REQUIRES_REVIEW");
        if (review.recipeGeneration() != planner.recipeGeneration()) throw new IllegalStateException("FLUID_RECIPES_RELOADED");
        var journal = prepare(workspace);
        review.requireCurrent(journal.get(actor, review.operationId()), items.get(actor, review.operationId()), resumeFluids(actor, review.originalPlan()));
        for (var input : review.itemCustody().escrow()) if (inventory.protectedByOwner(actor, input))
            throw new IllegalStateException("MATERIAL_PROTECTED_BY_OWNER");
        for (var target : review.outputTargets()) fluids.grants(actor).require(actor, target.source().grantId(), target.source().revision(), false);
    }
    private List<FluidLedger.Operation> resumeFluids(Actor actor, FluidProcessingPlan original) {
        return original.fluidClaims().stream().map(claim -> ledger.get(actor, claim.operationId())).toList();
    }
    private void validateResumeDestinations(AgentTool.Context ctx, FluidProcessingResume review) throws Exception {
        var refreshed = new LinkedHashMap<String, FluidInventoryService.Source>();
        for (var target : review.outputTargets()) if (!refreshed.containsKey(target.source().backend()))
            refreshed.put(target.source().backend(), dispatcher.inspectOwned(ctx, () -> current(ctx.actor(), target.source(), false)));
        var shadows = FluidProcessingPlanner.shadows(List.copyOf(refreshed.values()), List.of());
        for (var target : review.outputTargets()) {
            if (FluidProcessingPlanner.fill(shadows.get(target.source().backend()), target.amount()) != target.amount().millibuckets())
                throw new IllegalStateException("FLUID_OUTPUT_FULL");
            dispatcher.inspectOwned(ctx, () -> fluids.prepare(ctx.actor(), current(ctx.actor(), target.source(), false), target.amount(), true));
        }
        dispatcher.inspectOwned(ctx, () -> {
            if (!inventory.outputFits(ctx.actor(), review.originalPlan().itemOutputs(), List.of(), List.of())) throw new IllegalStateException("OUTPUT_FULL");
            return true;
        });
    }
    public AgentTool.Result resume(AgentTool.Context ctx, FluidProcessingResume review) throws Exception {
        ctx.actor().requireAccess(review.owner());
        if (!Objects.equals(ctx.taskId(), review.taskId())) throw new SecurityException("FLUID_TASK_MISMATCH");
        var journal = journal(ctx); UUID id = review.operationId();
        if (!executing.add(id)) throw new IllegalStateException("FLUID_ALREADY_EXECUTING");
        boolean deferred = false;
        try {
            // The immutable approved object must be the one bound by this process's new preview.
            planner.validateResumeRuntime(review);
            validateResumeVersion(ctx.workspace(), ctx.actor(), review);
            planner.validateResumeNative(ctx, review);
            validateResumeDestinations(ctx, review);
            validateResumeRemainingInputs(ctx, review);
            dispatcher.inspectOwned(ctx, () -> { planner.validateResumeOnMain(ctx.actor(), review); return true; });
            ctx.control().check();
            validateResumeVersion(ctx.workspace(), ctx.actor(), review); planner.validateResumeRuntime(review);
            // A newer confirmed review supersedes a waiting continuation, including its destinations.
            activeResumes.put(id, review.id());
            var accepted = new JsonObject(); accepted.addProperty("reviewId", review.id().toString());
            accepted.addProperty("hash", review.hash()); accepted.addProperty("operationId", id.toString());
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "fluid_resume_started", null, accepted, null)));
            journal.approveRemainingInputs(ctx.actor(), id, review.operationRevision(), review.hash());
            var item = dispatcher.extractReserved(ctx, items.get(ctx.actor(), id), () -> planner.validateResumeOnMain(ctx.actor(), review));
            if (item.uncertain()) throw new IllegalStateException("RECOVERY_REQUIRED");
            for (var claim : review.originalPlan().fluidClaims()) drainResume(ctx, journal, review, claim);
            deferUntilInputsSaved(ctx, journal, id, fresh -> finishResumeFromEscrow(fresh, review));
            return commitResumeAndDeliver(ctx, journal, review);
        } catch (AgentTool.Deferred wait) {
            deferred = true; throw wait;
        } catch (Exception failure) {
            handleExecutionFailure(ctx, journal, id, failure);
            if (failure instanceof TaskControl.Stopped stopped) throw stopped;
            return result(false, uncertain(ctx.actor(), journal, journal.get(ctx.actor(), id)) ? "RECOVERY_REQUIRED" : safe(failure),
                    "继续加工已暂停；实际输入或产物保持原操作托管。配方或来源不兼容时请批准退还后重新规划。", status(ctx.actor(), journal, id));
        } finally { executing.remove(id); if (!deferred) activeResumes.remove(id, review.id()); }
    }

    private void validateResumeRemainingInputs(AgentTool.Context ctx, FluidProcessingResume review) throws Exception {
        dispatcher.validateReservedInputs(ctx, items.get(ctx.actor(), review.operationId()), () -> planner.validateResumeOnMain(ctx.actor(), review));
        for (var claim : review.originalPlan().fluidClaims()) {
            var held = ledger.get(ctx.actor(), claim.operationId()); int left = held.requested().millibuckets() - held.extracted();
            if (left > 0) dispatcher.inspectOwned(ctx, () -> {
                planner.validateResumeOnMain(ctx.actor(), review);
                return fluids.prepare(ctx.actor(), current(ctx.actor(), claim.source(), true), claim.amount().withAmount(left), false);
            });
        }
    }
    private void drainResume(AgentTool.Context ctx, MixedResourceJournal journal, FluidProcessingResume review,
                             FluidProcessingPlan.Claim claim) throws Exception {
        while (true) {
            var held = ledger.get(ctx.actor(), claim.operationId()); int left = held.requested().millibuckets() - held.extracted();
            if (left == 0) return;
            if (held.state() != FluidLedger.State.RESERVED) throw new IllegalStateException("FLUID_PHASE_CHANGED");
            var prepared = dispatcher.inspectOwned(ctx, () -> {
                planner.validateResumeOnMain(ctx.actor(), review);
                return fluids.prepare(ctx.actor(), current(ctx.actor(), claim.source(), true), claim.amount().withAmount(left), false);
            });
            String witness = UUID.randomUUID().toString();
            ledger.begin(ctx.actor(), held.id(), witness, prepared.source(), prepared.amount(), false);
            transfer(ctx, journal, review.operationId(), held.id(), -1, witness, prepared);
        }
    }
    private AgentTool.Result finishResumeFromEscrow(AgentTool.Context ctx, FluidProcessingResume review) throws Exception {
        review.requireIdentity(ctx.workspace(), ctx.actor(), planner.executionEpoch());
        if (!Objects.equals(ctx.taskId(), review.taskId())) throw new SecurityException("FLUID_TASK_MISMATCH");
        var journal = journal(ctx); UUID id = review.operationId();
        if (!executing.add(id)) throw new IllegalStateException("FLUID_ALREADY_EXECUTING");
        try {
            if (!review.id().equals(activeResumes.get(id))) throw new IllegalStateException("FLUID_RESUME_SUPERSEDED");
            return commitResumeAndDeliver(ctx, journal, review);
        } catch (Exception failure) {
            handleExecutionFailure(ctx, journal, id, failure);
            if (failure instanceof TaskControl.Stopped stopped) throw stopped;
            return result(false, uncertain(ctx.actor(), journal, journal.get(ctx.actor(), id)) ? "RECOVERY_REQUIRED" : safe(failure),
                    "续作保存等待后的核验未完成；真实物料仍按原操作托管。", status(ctx.actor(), journal, id));
        } finally { executing.remove(id); activeResumes.remove(id, review.id()); }
    }
    private AgentTool.Result commitResumeAndDeliver(AgentTool.Context ctx, MixedResourceJournal journal, FluidProcessingResume review) throws Exception {
        UUID id = review.operationId();
        FluidProcessingResume.requirePending(review.originalPlan(), journal.get(ctx.actor(), id));
        FluidProcessingResume.requireExactInputs(review.originalPlan(), items.get(ctx.actor(), id), resumeFluids(ctx.actor(), review.originalPlan()));
        planner.validateResumeRuntime(review); planner.validateResumeNative(ctx, review); validateResumeDestinations(ctx, review);
        dispatcher.inspectOwned(ctx, () -> { planner.validateResumeOnMain(ctx.actor(), review); return true; });
        ctx.control().check();
        if (!review.id().equals(activeResumes.get(id))) throw new IllegalStateException("FLUID_RESUME_SUPERSEDED");
        journal.prepareCommit(ctx.actor(), id, items, ledger); journal.commit(ctx.actor(), id, items, ledger);
        items.noCallbacksRequired(ctx.actor(), id, "native-fluid-bucket-wrapper-21.1.264;iron-cauldron-1.21.1-3.16.2:no-player-callback");
        dispatcher.deliver(ctx, items.get(ctx.actor(), id));
        for (var target : review.outputTargets()) fillOutput(ctx, journal, id, target.outputIndex(), target.source(), target.amount());
        return completed(ctx.actor(), journal, id);
    }

    public AgentTool.Result execute(AgentTool.Context ctx, FluidProcessingPlan plan) throws Exception {
        ctx.actor().requireAccess(plan.owner());
        if (!Objects.equals(ctx.taskId(), plan.taskId())) throw new SecurityException("FLUID_TASK_MISMATCH");
        if (!ctx.workspace().metadata().serverInstanceId().equals(plan.serverInstanceId())
                || !ctx.workspace().primaryConversationId().equals(plan.conversationId())
                || ctx.workspace().conversation().privacyEpoch() != plan.privacyEpoch()) throw new SecurityException("FLUID_PLAN_IDENTITY_OR_HASH_CHANGED");
        var journal = journal(ctx); UUID id = plan.id();
        if (!executing.add(id)) return result(false, "FLUID_ALREADY_EXECUTING", "此操作正在执行，未重复派发。", new JsonObject());
        try {
            if (journal.find(ctx.actor(), id).isPresent() || items.find(ctx.actor(), id).isPresent())
                return result(false, "FLUID_ALREADY_RECORDED", "已有操作记录，未重放；请查询账本或生成退还预览。", status(ctx.actor(), journal, id));
            planner.validateNative(ctx, plan);
            dispatcher.inspectOwned(ctx, () -> { planner.validateOnMain(ctx.actor(), plan);
                if (!inventory.outputFits(ctx.actor(), plan.itemOutputs(), plan.sources(), plan.needs())) throw new IllegalStateException("OUTPUT_FULL");
                return true; });
            for (var need : plan.needs()) dispatcher.inspectOwned(ctx, () -> {
                var source = plan.sources().stream().filter(stock -> stock.key().equals(need.stockKey())).findFirst().orElseThrow();
                return inventory.prepareExtraction(ctx.actor(), source, Math.min(need.count(), source.maxTransferCount()));
            });
            for (var claim : plan.fluidClaims()) dispatcher.inspectOwned(ctx, () -> fluids.prepare(ctx.actor(), claim.source(), claim.amount(), false));
            validateDestinations(ctx, plan, true);
            ctx.control().check();
            journal.create(ctx.actor(), new MixedResourceJournal.Definition(id, ctx.taskId(), plan.hash(), plan.itemInputs(),
                    plan.fluidClaims().stream().map(claim -> new MixedResourceJournal.FluidInput(claim.operationId(), claim.amount())).toList(),
                    plan.itemOutputs(), plan.fluidOutputs(), plan.evidence()));
            try {
                var item = items.reserve(ctx.actor(), ctx.taskId(), id, plan.hash(), plan.sources(), plan.needs());
                for (var claim : plan.fluidClaims()) ledger.reserve(ctx.actor(), ctx.taskId(), claim.operationId(), plan.hash(), claim.source(), claim.amount());
                item = dispatcher.extractReserved(ctx, item, () -> planner.validateOnMain(ctx.actor(), plan));
                if (item.uncertain()) throw new IllegalStateException("RECOVERY_REQUIRED");
                for (var claim : plan.fluidClaims()) drain(ctx, plan, journal, claim);
                deferUntilInputsSaved(ctx, journal, id, fresh -> finishFromEscrow(fresh, plan));
                return commitAndDeliver(ctx, journal, plan);
            } catch (Exception failure) {
                if (failure instanceof AgentTool.Deferred deferred) throw deferred;
                handleExecutionFailure(ctx, journal, id, failure);
                if (failure instanceof TaskControl.Stopped stopped) throw stopped;
                return result(false, uncertain(ctx.actor(), journal, journal.get(ctx.actor(), id)) ? "RECOVERY_REQUIRED" : safe(failure),
                        "执行已停止；实际物料仍按联合账本托管。请检查状态后复核或预览退还。", status(ctx.actor(), journal, id));
            }
        } finally { executing.remove(id); }
    }
    private boolean inputsSaved(Actor actor, MixedResourceJournal journal, UUID id) {
        var op = journal.get(actor, id);
        return items.find(actor, id).map(MaterialLedger.Operation::inputsSaved).orElse(true)
                && op.definition().fluidInputs().stream().allMatch(input -> ledger.find(actor, input.operationId())
                .map(FluidLedger.Operation::inputsSaved).orElse(true));
    }
    private void deferUntilInputsSaved(AgentTool.Context ctx, MixedResourceJournal journal, UUID id,
                                       AgentTool.Continuation continuation) throws Exception {
        ctx.control().check();
        if (inputsSaved(ctx.actor(), journal, id)) return;
        var op = journal.get(ctx.actor(), id);
        if (op.state() == MixedResourceJournal.State.INPUTS_PENDING) journal.waitForInputSave(ctx.actor(), id);
        var stages = new ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        stages.add(inventory.nativeSaves().awaitJournalled(id).toCompletableFuture());
        for (var input : op.definition().fluidInputs()) stages.add(fluids.nativeSaves().awaitJournalled(input.operationId()).toCompletableFuture());
        var ready = java.util.concurrent.CompletableFuture.allOf(stages.toArray(java.util.concurrent.CompletableFuture[]::new));
        if (ready.isDone()) {
            if (inputsSaved(ctx.actor(), journal, id)) return;
            journal.requireRecovery(ctx.actor(), id, "INPUT_NATIVE_SAVE_PROOF_UNAVAILABLE");
            throw new IllegalStateException("RECOVERY_REQUIRED");
        }
        var checkpoint = new JsonObject(); checkpoint.addProperty("operationId", id.toString());
        checkpoint.addProperty("planHash", op.definition().planHash()); checkpoint.addProperty("phase", "WAITING_INPUT_SAVE");
        // Readiness callbacks do no filesystem or Minecraft work. Fresh-context continuation checks proofs again.
        throw new AgentTool.Deferred("WAITING_INPUT_SAVE", checkpoint, ready.handle((ignored, failure) -> null), fresh -> {
            if (!inputsSaved(fresh.actor(), journal, id)) {
                journal.requireRecovery(fresh.actor(), id, "INPUT_NATIVE_SAVE_PROOF_UNAVAILABLE");
                throw new IllegalStateException("RECOVERY_REQUIRED");
            }
            return continuation.resume(fresh);
        });
    }
    private AgentTool.Result finishFromEscrow(AgentTool.Context ctx, FluidProcessingPlan plan) throws Exception {
        ctx.actor().requireAccess(plan.owner());
        if (!Objects.equals(ctx.taskId(), plan.taskId()) || ctx.workspace().conversation().privacyEpoch() != plan.privacyEpoch())
            throw new SecurityException("FLUID_PLAN_IDENTITY_OR_HASH_CHANGED");
        var journal = journal(ctx); UUID id = plan.id();
        if (!executing.add(id)) throw new IllegalStateException("FLUID_ALREADY_EXECUTING");
        try {
            if (journal.get(ctx.actor(), id).state() != MixedResourceJournal.State.INPUTS_PENDING)
                throw new IllegalStateException("MIXED_PHASE_CHANGED");
            return commitAndDeliver(ctx, journal, plan);
        } catch (Exception failure) {
            handleExecutionFailure(ctx, journal, id, failure);
            if (failure instanceof TaskControl.Stopped stopped) throw stopped;
            return result(false, uncertain(ctx.actor(), journal, journal.get(ctx.actor(), id)) ? "RECOVERY_REQUIRED" : safe(failure),
                    "等待结束后的现场核验或交付未完成；实际物料仍按联合账本托管。", status(ctx.actor(), journal, id));
        } finally { executing.remove(id); }
    }
    private AgentTool.Result commitAndDeliver(AgentTool.Context ctx, MixedResourceJournal journal, FluidProcessingPlan plan) throws Exception {
        UUID id = plan.id();
        planner.validateNative(ctx, plan);
        for (var source : plan.sources()) if (!source.sourceId().equals("player"))
            inventory.grants(ctx.actor()).require(ctx.actor(), UUID.fromString(source.sourceId()), source.sourceRevision(), true);
        for (var claim : plan.fluidClaims()) fluids.grants(ctx.actor()).require(ctx.actor(), claim.source().grantId(), claim.source().revision(), true);
        validateDestinations(ctx, plan, false);
        dispatcher.inspectOwned(ctx, () -> {
            planner.validateOnMain(ctx.actor(), plan);
            if (!inventory.outputFits(ctx.actor(), plan.itemOutputs(), List.of(), List.of())) throw new IllegalStateException("OUTPUT_FULL");
            return true;
        });
        ctx.control().check(); journal.prepareCommit(ctx.actor(), id, items, ledger); journal.commit(ctx.actor(), id, items, ledger);
        items.noCallbacksRequired(ctx.actor(), id, "native-fluid-bucket-wrapper-21.1.264;iron-cauldron-1.21.1-3.16.2:no-player-callback");
        dispatcher.deliver(ctx, items.get(ctx.actor(), id));
        for (var target : plan.outputTargets()) fillOutput(ctx, journal, id, target.outputIndex(), target.source(), target.amount());
        return completed(ctx.actor(), journal, id);
    }
    private void handleExecutionFailure(AgentTool.Context ctx, MixedResourceJournal journal, UUID id, Exception failure) {
        try {
            // Output pressure, reload, disconnect, timeout and pause retain the saved inputs.
            // Only an explicit cancellation establishes the irreversible return fence.
            if (failure instanceof TaskControl.Stopped && Set.of("CANCELLED", "DENIED").contains(failure.getMessage()))
                journal.beginReturn(ctx.actor(), id);
            else promoteUncertainty(ctx.actor(), journal, id);
        } catch (Exception recording) { failure.addSuppressed(recording); }
    }
    private void drain(AgentTool.Context ctx, FluidProcessingPlan plan, MixedResourceJournal journal, FluidProcessingPlan.Claim claim) throws Exception {
        while (true) {
            var op = ledger.get(ctx.actor(), claim.operationId()); int left = op.requested().millibuckets() - op.extracted();
            if (left == 0) return;
            if (op.state() == FluidLedger.State.RECOVERY_REQUIRED) throw new IllegalStateException("RECOVERY_REQUIRED");
            var prepared = dispatcher.inspectOwned(ctx, () -> { planner.validateOnMain(ctx.actor(), plan);
                return fluids.prepare(ctx.actor(), current(ctx.actor(), claim.source(), true), claim.amount().withAmount(left), false); });
            String witness = UUID.randomUUID().toString(); ledger.begin(ctx.actor(), op.id(), witness, prepared.source(), prepared.amount(), false);
            transfer(ctx, journal, plan.id(), op.id(), -1, witness, prepared);
        }
    }
    private FluidInventoryService.Source current(Actor actor, FluidInventoryService.Source expected, boolean extraction) {
        var value = fluids.current(actor, expected.grantId(), expected.revision(), extraction);
        if (!value.backend().equals(expected.backend()) || !value.authorizationFingerprint().equals(expected.authorizationFingerprint()))
            throw new IllegalStateException("FLUID_TARGET_CHANGED");
        return value;
    }
    /** Native reads are sliced; capacity math is pure and never chooses an unapproved destination. */
    private void validateDestinations(AgentTool.Context ctx, FluidProcessingPlan plan, boolean beforeDrains) throws Exception {
        var sources = new LinkedHashMap<String, FluidInventoryService.Source>();
        for (var target : plan.outputTargets()) if (!sources.containsKey(target.source().backend()))
            sources.put(target.source().backend(), dispatcher.inspectOwned(ctx, () -> current(ctx.actor(), target.source(), false)));
        if (sources.isEmpty()) return;
        var claims = beforeDrains ? plan.fluidClaims().stream().filter(claim -> sources.containsKey(claim.source().backend())).toList() : List.<FluidProcessingPlan.Claim>of();
        var shadows = FluidProcessingPlanner.shadows(List.copyOf(sources.values()), claims);
        for (var target : plan.outputTargets()) if (FluidProcessingPlanner.fill(shadows.get(target.source().backend()), target.amount()) != target.amount().millibuckets())
            throw new IllegalStateException("FLUID_OUTPUT_FULL");
    }
    private void fillOutput(AgentTool.Context ctx, MixedResourceJournal journal, UUID id, int index,
                            FluidInventoryService.Source target, FluidAmount allocation) throws Exception {
        int remaining = allocation.millibuckets();
        while (remaining > 0) {
            FluidAmount held = journal.heldFluidOutputs(ctx.actor(), id).get(index);
            if (held.empty()) return;
            var intended = held.withAmount(Math.min(remaining, held.millibuckets()));
            var prepared = dispatcher.inspectOwned(ctx, () -> fluids.prepare(ctx.actor(), current(ctx.actor(), target, false), intended, true));
            String witness = UUID.randomUUID().toString(); journal.beginFluidDelivery(ctx.actor(), id, index, witness, prepared.source(), prepared.amount());
            remaining -= transfer(ctx, journal, id, null, index, witness, prepared).millibuckets();
        }
    }
    private FluidAmount transfer(AgentTool.Context ctx, MixedResourceJournal journal, UUID mixedId, UUID inputId,
                                 int outputIndex, String witness, FluidInventoryService.Prepared prepared) throws Exception {
        FluidInventoryService.Outcome outcome;
        try {
            outcome = dispatcher.commitOwned(ctx, () -> fluids.perform(prepared), actual -> {
                if (outputIndex >= 0) {
                    journal.fluidDeliveryObserved(ctx.actor(), mixedId, witness, actual);
                    fluids.nativeSaves().record(mixedId, actual.nativeSave(), proof -> journal.fluidDeliverySaved(ctx.actor(), mixedId, witness, proof));
                } else {
                    ledger.observed(ctx.actor(), inputId, witness, actual);
                    fluids.nativeSaves().record(inputId, actual.nativeSave(), proof -> ledger.nativeSaved(ctx.actor(), inputId, witness, proof));
                    if (!actual.verified()) journal.requireRecovery(ctx.actor(), mixedId, "FLUID_NATIVE_RESULT_UNCERTAIN");
                }
                return items.get(ctx.actor(), mixedId);
            });
        } catch (CommittedCall.Rejected rejected) {
            if (outputIndex >= 0) journal.fluidDeliveryRejected(ctx.actor(), mixedId, witness, "NOT_DISPATCHED");
            else ledger.rejected(ctx.actor(), inputId, witness, "NOT_DISPATCHED");
            if (rejected.getCause() instanceof TaskControl.Stopped stopped) throw stopped;
            throw rejected;
        }
        if (!outcome.verified()) throw new IllegalStateException("RECOVERY_REQUIRED");
        if (!outcome.entered() || outcome.actual().empty()) throw new IllegalStateException(outcome.reason().isBlank() ? "FLUID_NO_PROGRESS" : outcome.reason());
        return outcome.actual();
    }

    public ReturnPlan returnPreview(AgentTool.Context ctx, UUID id, UUID targetId) throws Exception {
        var journal = journal(ctx); var op = journal.get(ctx.actor(), id);
        if (uncertain(ctx.actor(), journal, op)) throw new IllegalStateException("RECOVERY_REQUIRED");
        var original = planner.load(ctx.workspace(), ctx.actor(), id, op.definition().planHash());
        boolean inputReturn = op.state() == MixedResourceJournal.State.INPUTS_PENDING || op.returnMode() == MixedResourceJournal.ReturnMode.INPUTS;
        var targets = new ArrayList<ReturnTarget>(); var revisions = new LinkedHashMap<UUID, Long>();
        FluidInventoryService.Source override = targetId == null ? null : ctx.control().await(fluids.scan(ctx.actor()), 30000).sources().stream()
                .filter(source -> source.grantId().equals(targetId)).findFirst().orElseThrow(() -> new IllegalArgumentException("FLUID_SOURCE_NOT_FOUND"));
        for (var claim : original.fluidClaims()) {
            var fluid = ledger.find(ctx.actor(), claim.operationId()).orElse(null);
            revisions.put(claim.operationId(), fluid == null ? 0L : fluid.revision());
            if (inputReturn && fluid != null && fluid.escrowMb() > 0) {
                var target = override == null ? claim.source() : override;
                var fresh = dispatcher.inspectOwned(ctx, () -> current(ctx.actor(), target, false));
                targets.add(new ReturnTarget(fluid.id(), -1, fresh, fluid.requested().withAmount(fluid.escrowMb())));
            }
        }
        if (!inputReturn) for (int index = 0; index < op.definition().fluidOutputs().size(); index++) {
            var output = op.definition().fluidOutputs().get(index); int left = output.millibuckets() - op.deliveredMb(index);
            if (left == 0) continue;
            if (override != null) targets.add(new ReturnTarget(null, index, override, output.withAmount(left)));
            else {
                int skipped = op.deliveredMb(index);
                for (var target : original.outputTargets()) if (target.outputIndex() == index) {
                    int skip = Math.min(skipped, target.amount().millibuckets()); skipped -= skip;
                    int count = target.amount().millibuckets() - skip; if (count == 0) continue;
                    var fresh = dispatcher.inspectOwned(ctx, () -> current(ctx.actor(), target.source(), false));
                    targets.add(new ReturnTarget(null, index, fresh, output.withAmount(count)));
                }
            }
        }
        var item = items.find(ctx.actor(), id).orElse(null);
        var plan = new ReturnPlan(1, UUID.randomUUID(), id, ctx.actor().owner(), ctx.workspace().metadata().serverInstanceId(),
                ctx.workspace().primaryConversationId(), ctx.workspace().conversation().privacyEpoch(), op.revision(),
                item == null ? 0 : item.revision(), revisions, targets,
                item == null ? List.of() : inputReturn && item.state() != MaterialLedger.State.CALLBACK_DONE && !item.settled()
                        ? item.escrow() : item.remainingProducts(), inputReturn ? "INPUTS" : "OUTPUTS", "");
        plan = plan.withHash(AiJson.digest(AiJson.canonical(plan)));
        validateReturnVersion(ctx, journal, plan);
        synchronized (ctx.workspace().conversation()) {
            ctx.control().check();
            if (plan.privacyEpoch() != ctx.workspace().conversation().privacyEpoch()) throw new IllegalStateException("WORKSPACE_PRIVACY_EPOCH_CHANGED");
            AiJson.write(ctx.workspace().artifact(plan.id()), plan);
            var data = new JsonObject(); data.addProperty("reviewId", plan.id().toString()); data.addProperty("hash", plan.hash());
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "fluid_return_preview", null, data, null)));
        }
        return plan;
    }
    public ReturnPlan loadReturn(Workspace workspace, Actor actor, UUID id, String hash) throws IOException {
        actor.requireAccess(workspace.owner());
        synchronized (workspace.conversation()) {
            boolean indexed = workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events().stream().anyMatch(event ->
                    event.kind().equals("fluid_return_preview") && id.toString().equals(event.data().get("reviewId").getAsString()) && hash.equals(event.data().get("hash").getAsString()));
            Path file = workspace.artifact(id);
            if (!indexed || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 8 * 1024 * 1024) throw new IllegalArgumentException("FLUID_RETURN_NOT_FOUND");
            var plan = AiJson.GSON.fromJson(Files.readString(file), ReturnPlan.class);
            if (!plan.intact() || !id.equals(plan.id()) || !hash.equals(plan.hash()) || !actor.owner().equals(plan.owner())
                    || !workspace.metadata().serverInstanceId().equals(plan.serverInstanceId()) || !workspace.primaryConversationId().equals(plan.conversationId())
                    || workspace.conversation().privacyEpoch() != plan.privacyEpoch()) throw new SecurityException("FLUID_RETURN_IDENTITY_CHANGED");
            return plan;
        }
    }
    public void validateReturn(Workspace workspace, Actor actor, ReturnPlan plan) throws IOException {
        actor.requireAccess(workspace.owner());
        validateReturnVersion(new AgentTool.Context(workspace, actor, new TaskControl(120000)), prepare(workspace), plan);
    }
    private void validateReturnVersion(AgentTool.Context ctx, MixedResourceJournal journal, ReturnPlan plan) {
        if (!plan.intact() || !ctx.workspace().owner().equals(plan.owner())
                || !ctx.workspace().metadata().serverInstanceId().equals(plan.serverInstanceId())
                || !ctx.workspace().primaryConversationId().equals(plan.conversationId())
                || ctx.workspace().conversation().privacyEpoch() != plan.privacyEpoch()
                || journal.get(ctx.actor(), plan.operationId()).revision() != plan.operationRevision()
                || items.find(ctx.actor(), plan.operationId()).map(MaterialLedger.Operation::revision).orElse(0L) != plan.itemRevision())
            throw new IllegalStateException("FLUID_RETURN_PREVIEW_CHANGED");
        for (var entry : plan.fluidRevisions().entrySet()) if (ledger.find(ctx.actor(), entry.getKey()).map(FluidLedger.Operation::revision).orElse(0L).longValue() != entry.getValue())
            throw new IllegalStateException("FLUID_RETURN_PREVIEW_CHANGED");
    }
    public AgentTool.Result returnMaterials(AgentTool.Context ctx, ReturnPlan plan) throws Exception {
        var journal = journal(ctx); UUID id = plan.operationId(); ctx.actor().requireAccess(plan.owner());
        if (!executing.add(id)) throw new IllegalStateException("FLUID_ALREADY_EXECUTING");
        try {
            validateReturnVersion(ctx, journal, plan);
            dispatcher.inspectOwned(ctx, () -> inventory.requirePlayer(ctx.actor()));
            var returns = journal.beginReturn(ctx.actor(), id);
            if (returns.phase() == MixedResourceJournal.State.RECOVERY_REQUIRED) throw new IllegalStateException("RECOVERY_REQUIRED");
            deferUntilInputsSaved(ctx, journal, id, fresh -> finishReturn(fresh, plan));
            return deliverReturn(ctx, journal, plan);
        } catch (Exception failure) {
            if (failure instanceof AgentTool.Deferred deferred) throw deferred;
            try { journal.continueReturn(ctx.actor(), id); } catch (Exception recording) { failure.addSuppressed(recording); }
            if (uncertain(ctx.actor(), journal, journal.get(ctx.actor(), id)))
                journal.requireRecovery(ctx.actor(), id, "RETURN_NATIVE_EFFECT_REQUIRES_REVIEW");
            throw failure;
        } finally { executing.remove(id); }
    }
    private AgentTool.Result finishReturn(AgentTool.Context ctx, ReturnPlan plan) throws Exception {
        var journal = journal(ctx); UUID id = plan.operationId(); ctx.actor().requireAccess(plan.owner());
        if (!ctx.workspace().metadata().serverInstanceId().equals(plan.serverInstanceId())
                || !ctx.workspace().primaryConversationId().equals(plan.conversationId())
                || ctx.workspace().conversation().privacyEpoch() != plan.privacyEpoch()) throw new SecurityException("FLUID_RETURN_IDENTITY_CHANGED");
        if (!executing.add(id)) throw new IllegalStateException("FLUID_ALREADY_EXECUTING");
        try {
            var op = journal.get(ctx.actor(), id);
            if (!Set.of(MixedResourceJournal.State.RETURNING, MixedResourceJournal.State.RETURNED).contains(op.state()))
                throw new IllegalStateException("MIXED_PHASE_CHANGED");
            dispatcher.inspectOwned(ctx, () -> inventory.requirePlayer(ctx.actor()));
            return deliverReturn(ctx, journal, plan);
        } catch (Exception failure) {
            try { journal.continueReturn(ctx.actor(), id); } catch (Exception recording) { failure.addSuppressed(recording); }
            if (uncertain(ctx.actor(), journal, journal.get(ctx.actor(), id))) journal.requireRecovery(ctx.actor(), id, "RETURN_NATIVE_EFFECT_REQUIRES_REVIEW");
            throw failure;
        } finally { executing.remove(id); }
    }
    private AgentTool.Result deliverReturn(AgentTool.Context ctx, MixedResourceJournal journal, ReturnPlan plan) throws Exception {
            UUID id = plan.operationId(); var item = items.find(ctx.actor(), id).orElse(null);
            if (item != null && item.state() == MaterialLedger.State.PRODUCED)
                item = items.noCallbacksRequired(ctx.actor(), id, "native-fluid-adapter-v1:no-player-callback");
            if (item != null && item.state() == MaterialLedger.State.CALLBACK_DONE) dispatcher.deliver(ctx, item);
            for (var target : plan.targets()) {
                if (target.inputOperation() == null) fillOutput(ctx, journal, id, target.outputIndex(), target.source(), target.amount());
                else while (ledger.get(ctx.actor(), target.inputOperation()).escrowMb() > 0) {
                    int left = ledger.get(ctx.actor(), target.inputOperation()).escrowMb();
                    var prepared = dispatcher.inspectOwned(ctx, () -> fluids.prepare(ctx.actor(), current(ctx.actor(), target.source(), false), target.amount().withAmount(left), true));
                    String witness = UUID.randomUUID().toString();
                    journal.beginFluidReturn(ctx.actor(), id, target.inputOperation(), witness, prepared.source(), prepared.amount());
                    transfer(ctx, journal, id, target.inputOperation(), -1, witness, prepared);
                }
            }
            journal.continueReturn(ctx.actor(), id); return completed(ctx.actor(), journal, id);
    }
    private boolean uncertain(Actor actor, MixedResourceJournal journal, MixedResourceJournal.Operation op) {
        return op.state() == MixedResourceJournal.State.RECOVERY_REQUIRED || op.state() == MixedResourceJournal.State.COMMIT_INTENT
                || op.state() == MixedResourceJournal.State.FLUID_DELIVERY_INTENT
                || items.find(actor, op.definition().id()).map(item -> item.uncertain() || item.intent() != null).orElse(false)
                || op.definition().fluidInputs().stream().anyMatch(input -> ledger.find(actor, input.operationId())
                        .map(fluid -> Set.of(FluidLedger.State.RECOVERY_REQUIRED, FluidLedger.State.DRAIN_INTENT, FluidLedger.State.FILL_INTENT).contains(fluid.state())).orElse(false));
    }
    public JsonObject status(Actor actor, MixedResourceJournal journal, UUID id) {
        var op = journal.get(actor, id); var data = new JsonObject();
        data.addProperty("operationId", id.toString()); data.addProperty("taskId", op.definition().taskId().toString());
        data.addProperty("state", op.state().name()); data.addProperty("returnMode", op.returnMode().name()); data.addProperty("revision", op.revision());
        data.addProperty("reason", op.reason()); data.addProperty("inputsSaved", inputsSaved(actor, journal, id));
        data.addProperty("recoveryRequired", uncertain(actor, journal, op)); data.addProperty("settledAndSaved", journal.settled(actor, id));
        data.addProperty("heldInputMb", op.definition().fluidInputs().stream().map(input -> ledger.find(actor, input.operationId()).orElse(null))
                .filter(Objects::nonNull).mapToInt(FluidLedger.Operation::escrowMb).sum());
        boolean produced = op.returnMode() != MixedResourceJournal.ReturnMode.INPUTS
                && Set.of(MixedResourceJournal.State.COMMITTED, MixedResourceJournal.State.RETURNING,
                MixedResourceJournal.State.RETURNED, MixedResourceJournal.State.FLUID_DELIVERY_INTENT).contains(op.state());
        data.addProperty("outputCustodyConfirmed", produced);
        if (produced) data.addProperty("undeliveredOutputMb", java.util.stream.IntStream.range(0, op.definition().fluidOutputs().size())
                .map(index -> op.definition().fluidOutputs().get(index).millibuckets() - op.deliveredMb(index)).sum());
        items.find(actor, id).ifPresent(item -> { data.addProperty("itemState", item.state().name());
            data.addProperty("undeliveredItems", item.remainingProducts().stream().mapToLong(MaterialStack::count).sum()); });
        return data;
    }
    private void promoteUncertainty(Actor actor, MixedResourceJournal journal, UUID id) throws IOException {
        var op = journal.get(actor, id);
        if (op.state() != MixedResourceJournal.State.RECOVERY_REQUIRED && uncertain(actor, journal, op))
            journal.requireRecovery(actor, id, "LINKED_NATIVE_EFFECT_REQUIRES_REVIEW");
    }
    private AgentTool.Result completed(Actor actor, MixedResourceJournal journal, UUID id) throws IOException {
        promoteUncertainty(actor, journal, id);
        var data = status(actor, journal, id); boolean saved = data.get("settledAndSaved").getAsBoolean();
        return result(saved, saved ? "" : "CUSTODY_OR_SAVE_PENDING", saved ? "真实物料已交付并确认原生保存。" : "已记录实际交付；未交付物料或原生保存仍待完成，具体状态见账本。", data);
    }
    public JsonObject view(FluidProcessingPlan plan) {
        var data = new JsonObject(); data.addProperty("planId", plan.id().toString()); data.addProperty("planHash", plan.hash());
        data.addProperty("recipeId", plan.recipeId()); data.addProperty("mechanismId", plan.mechanismId()); data.addProperty("recipeGeneration", plan.recipeGeneration());
        data.addProperty("policy", "MATERIALS_ONLY"); data.add("itemsConsumed", AiJson.GSON.toJsonTree(plan.itemInputs().stream().map(VirtualProcessingService::publicMaterial).toList()));
        data.add("itemsProduced", AiJson.GSON.toJsonTree(plan.itemOutputs().stream().map(VirtualProcessingService::publicMaterial).toList()));
        data.add("fluidsConsumed", AiJson.GSON.toJsonTree(plan.fluidClaims().stream().map(claim -> fluidView(claim.source(), claim.amount())).toList()));
        data.add("fluidDestinations", AiJson.GSON.toJsonTree(plan.outputTargets().stream().map(target -> fluidView(target.source(), target.amount())).toList())); return data;
    }
    public String review(FluidProcessingPlan plan) { return "固定物品/流体加工；MATERIALS_ONLY，免设备和附加费用。\n" + AiJson.GSON.toJson(view(plan)); }
    public JsonObject resumeView(FluidProcessingResume review) {
        var data = view(review.originalPlan());
        data.addProperty("reviewId", review.id().toString()); data.addProperty("reviewHash", review.hash());
        data.addProperty("operationId", review.operationId().toString()); data.addProperty("taskId", review.taskId().toString());
        data.addProperty("operationRevision", review.operationRevision()); data.addProperty("itemRevision", review.itemRevision());
        data.addProperty("executionEpoch", review.executionEpoch().toString()); data.addProperty("recipeGeneration", review.recipeGeneration());
        data.addProperty("dimension", review.dimension());
        data.addProperty("inputExtractionAllowed", review.requiresExtraction()); data.addProperty("positiveInputSaveConfirmed", true);
        data.add("remainingItemClaims", AiJson.GSON.toJsonTree(review.itemCustody().claims().stream().filter(claim -> claim.remaining() > 0)
                .map(claim -> Map.of("sourceId", claim.stock().sourceId(), "sourceRevision", claim.stock().sourceRevision(),
                    "slot", claim.stock().slot(), "material", VirtualProcessingService.publicMaterial(claim.stock().stack().withCount(claim.remaining())))).toList()));
        data.add("remainingFluidClaims", AiJson.GSON.toJsonTree(review.fluidCustody().stream()
                .filter(fluid -> fluid.extracted() < fluid.requested().millibuckets())
                .map(fluid -> fluidView(fluid.origin(), fluid.requested().withAmount(fluid.requested().millibuckets() - fluid.extracted()))).toList()));
        data.add("itemCustody", AiJson.GSON.toJsonTree(review.itemCustody().escrow().stream().map(VirtualProcessingService::publicMaterial).toList()));
        data.add("fluidCustody", AiJson.GSON.toJsonTree(review.fluidCustody().stream().map(fluid -> Map.of(
                "operationId", fluid.id(), "revision", fluid.revision(), "millibuckets", fluid.escrowMb(),
                "componentDigest", fluid.requested().fingerprint())).toList()));
        data.add("fluidDestinations", AiJson.GSON.toJsonTree(review.outputTargets().stream().map(target -> Map.of(
                "outputIndex", target.outputIndex(), "target", fluidView(target.source(), target.amount()))).toList()));
        return data;
    }
    public String resumeReview(FluidProcessingResume review) {
        return (review.requiresExtraction()
                ? "继续原操作：已托管部分有真实保存凭据；批准后仅从仍有效的原授权来源提取下列缺少数量，保存后再加工。\n"
                : "继续原操作已经保存的完整输入托管；当前原生配方已逐次核验，保留原操作和固定产物，无需再次取料。\n")
                + AiJson.GSON.toJson(resumeView(review));
    }
    public String returnReview(ReturnPlan plan) { return "取消剩余取料并交付已有托管；不返还已消耗原料。去向改变需新预览；容量不足的部分继续托管。\n" + AiJson.GSON.toJson(returnView(plan)); }
    public String recoveryReview(Workspace workspace, Actor actor, UUID id, long revision, String hash) throws IOException {
        var journal = prepare(workspace); promoteUncertainty(actor, journal, id);
        var review = journal.recoveryReview(actor, id, items, ledger);
        if (review.revision() != revision || !review.reviewHash().equals(hash)) throw new IllegalStateException("MIXED_REVIEW_CHANGED");
        return "联合物料复核：只补齐由原生保存凭证证明的账本阶段，不重放游戏操作。\n" + AiJson.GSON.toJson(review);
    }
    public AgentTool.Result audit(Workspace workspace, JsonObject args, AgentTool.Result result) {
        var journal = journals.get(workspace.owner()); if (journal == null) return result;
        try {
            Actor actor = new Actor(workspace.owner(), true, false, 0); UUID id;
            if (args.has("reviewId")) {
                UUID reviewId = UUID.fromString(text(args, "reviewId")); String hash = text(args, "reviewHash");
                boolean resume = workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events().stream().anyMatch(event ->
                        event.kind().equals("fluid_resume_preview") && event.data() != null && event.data().has("reviewId") && event.data().has("hash")
                        && reviewId.toString().equals(event.data().get("reviewId").getAsString()) && hash.equals(event.data().get("hash").getAsString()));
                id = resume ? loadResume(workspace, actor, reviewId, hash).operationId() : loadReturn(workspace, actor, reviewId, hash).operationId();
            }
            else if (args.has("operationId") || args.has("planId")) id = UUID.fromString(text(args, args.has("operationId") ? "operationId" : "planId"));
            else return result;
            if (journal.find(actor, id).isEmpty()) return result;
            var data = result.observations().deepCopy(); var actual = status(actor, journal, id); data.add("mixedMaterialEvidence", actual);
            boolean unknown = actual.get("recoveryRequired").getAsBoolean();
            return new AgentTool.Result(result.success() && !unknown, unknown ? "RECOVERY_REQUIRED" : result.errorCode(),
                    result.summary() + " 已附实际物品和流体托管记录。", data, result.evidence(), result.worldChanges(), false);
        } catch (Exception unreadable) { return result(false, "EVIDENCE_REQUIRES_REVIEW", "联合物料证据不可读，不能声称完成。", result.observations()); }
    }
    private JsonObject returnView(ReturnPlan plan) {
        var data = new JsonObject(); data.addProperty("reviewId", plan.id().toString()); data.addProperty("reviewHash", plan.hash());
        data.addProperty("operationId", plan.operationId().toString()); data.addProperty("operationRevision", plan.operationRevision());
        data.addProperty("direction", plan.direction()); data.add("itemCustody", AiJson.GSON.toJsonTree(plan.itemCustody().stream().map(VirtualProcessingService::publicMaterial).toList()));
        data.add("destinations", AiJson.GSON.toJsonTree(plan.targets().stream().map(target -> fluidView(target.source(), target.amount())).toList())); return data;
    }
    private static Map<String, Object> fluidView(FluidInventoryService.Source source, FluidAmount fluid) {
        return Map.of("sourceId", source.grantId(), "revision", source.revision(), "fluid", AiJson.object(fluid.unitJson(), 262144, 32).get("id").getAsString(), "millibuckets", fluid.millibuckets(), "componentDigest", fluid.fingerprint());
    }
    @Override public void close() { closed = true; activeResumes.clear(); planner.close(); }
    @FunctionalInterface private interface Action { AgentTool.Result run(AgentTool.Context ctx, JsonObject args) throws Exception; }
    private void tool(ToolRegistry tools, String name, String description, String properties, List<String> required, AgentTool.Risk risk, Action action) {
        var schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", JsonParser.parseString(properties));
        schema.add("required", AiJson.GSON.toJsonTree(required)); schema.addProperty("additionalProperties", false);
        tools.register(new DefinedTool(new AgentTool.Descriptor(name, description, schema, risk, "materials.own", 0,
                AgentTool.ExecutionThread.IO, 120000, true, ""), (ctx, args) -> {
            ctx.actor().requireAccess(ctx.workspace().owner()); ctx.control().check();
            if (closed || broken || !items.journalAvailable() || !ledger.journalAvailable()) throw new IllegalStateException("MATERIAL_JOURNAL_REQUIRES_REVIEW");
            try { return action.run(ctx, args); }
            catch (AgentTool.Deferred deferred) { throw deferred; }
            catch (TaskControl.Stopped stopped) { throw stopped; }
            catch (Exception failure) { return result(false, safe(failure), "操作未完成：" + safe(failure) + "；请查看实际账本，未确认的效果不能重放。", new JsonObject()); }
        }));
    }
    private static String text(JsonObject args, String key) { return args.has(key) ? args.get(key).getAsString() : ""; }
    private static String safe(Exception failure) { String code = failure.getMessage(); return code != null && code.matches("[A-Z_]{1,100}") ? code : "FLUID_PROCESSING_FAILED"; }
    private static AgentTool.Result result(boolean success, String code, String summary, JsonObject data) {
        return new AgentTool.Result(success, code, summary, data, "server-mixed-material-ledger", "", false);
    }
}
