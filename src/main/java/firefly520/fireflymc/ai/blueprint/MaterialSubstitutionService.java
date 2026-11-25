package firefly520.fireflymc.ai.blueprint;

import com.google.gson.*;
import firefly520.fireflymc.ai.conversation.ConversationStore.Draft;
import firefly520.fireflymc.ai.material.*;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.QuestionStore;
import firefly520.fireflymc.ai.task.QuestionCoordinator;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Native compatibility, real inventory evidence, a blocking player choice, then an immutable new blueprint revision. */
public final class MaterialSubstitutionService {
    public static final String ACCEPT = "采用此替代方案", KEEP = "保留原材料";
    public record StateChange(BlueprintDocument.State before, BlueprintDocument.State after) {}
    public record Facts(UUID id, UUID server, UUID conversation, String owner, UUID task, UUID executionEpoch,
                        long privacyEpoch, long generation, UUID blueprintId, long blueprintRevision, String blueprintHash,
                        String dimension, String fromBlock, String toBlock, long availableUnits, int changedCells,
                        int estimatedUnits, int appearanceDistance, List<StateChange> states, List<String> evidence) {
        public Facts { states = List.copyOf(states); evidence = List.copyOf(evidence); }
    }
    public record Proposal(Facts facts, UUID questionId, String questionDigest) {}
    private record Evaluation(List<StateChange> states, int cells, int units, int appearance, List<String> evidence) {}
    private final BlueprintService blueprints;
    private final MaterialInventoryService inventory;
    private final MaterialLedger ledger;
    private final LongSupplier generation;
    private final UUID epoch = UUID.randomUUID();
    public MaterialSubstitutionService(BlueprintService blueprints, MaterialInventoryService inventory, MaterialLedger ledger, LongSupplier generation) {
        this.blueprints = blueprints; this.inventory = inventory; this.ledger = ledger; this.generation = generation;
    }
    public void register(ToolRegistry registry) {
        String reference = "\"blueprintId\":{\"type\":\"string\"},\"revision\":{\"type\":\"integer\",\"minimum\":1},\"blueprintHash\":{\"type\":\"string\"},\"fromBlock\":{\"type\":\"string\"}";
        tool(registry, "blueprint_material_options", "按本人真实授权库存查找蓝图材质替代。功能块保持精确；先验证碰撞、状态、光照、流体、可燃性与强度，再按可用量和色差排序。结果只作规划，不能直接改世界。",
                "{" + reference + ",\"offset\":{\"type\":\"integer\",\"minimum\":0}}", List.of("blueprintId", "revision", "blueprintHash", "fromBlock"), this::options);
        tool(registry, "blueprint_material_propose", "提出具体材质替代并在工作台发出阻塞问题；提供真实库存依据和影响数量，不扣料。玩家接受后用 blueprint_material_apply 创建新版本，施工仍需新的材料预算与批准。",
                "{" + reference + ",\"toBlock\":{\"type\":\"string\"}}", List.of("blueprintId", "revision", "blueprintHash", "fromBlock", "toBlock"), this::propose);
        tool(registry, "blueprint_material_apply", "仅当本人已回答固定替代问题并选择采用时创建新蓝图版本；拒绝/未答/旧版本/其他任务均不生效。不会授予施工权限。",
                "{\"proposalId\":{\"type\":\"string\"},\"proposalHash\":{\"type\":\"string\"}}", List.of("proposalId", "proposalHash"), this::apply);
    }
    private BlueprintRepository.Revision source(AgentTool.Context ctx, JsonObject args) throws Exception {
        var repository = new BlueprintRepository(ctx.workspace());
        var value = repository.read(ctx.actor(), UUID.fromString(args.get("blueprintId").getAsString()), args.get("revision").getAsLong(), args.get("blueprintHash").getAsString());
        if (!repository.current(ctx.actor(), value.entry())) throw new IllegalStateException("BLUEPRINT_REVISION_CHANGED");
        return value;
    }
    private Map<String, Long> available(AgentTool.Context ctx) throws Exception {
        var snapshot = ctx.control().await(inventory.scan(ctx.actor()), 30000); var amount = ledger.available(snapshot.rows());
        var result = new TreeMap<String, Long>();
        for (var row : snapshot.rows()) {
            ctx.control().check(); var item = AiJson.object(row.stack().itemJson(), 1_048_576, 32);
            if (item.has("components") && !Collections.disjoint(item.getAsJsonObject("components").keySet(),
                    Set.of("minecraft:custom_name", "minecraft:block_entity_data", "minecraft:container", "minecraft:enchantments"))) continue;
            long count = amount.getOrDefault(row.key(), 0L);
            if (count > 0) result.merge(item.get("id").getAsString(), count, Math::addExact);
        }
        return result;
    }
    private AgentTool.Result options(AgentTool.Context ctx, JsonObject args) throws Exception {
        var saved = source(ctx, args); String from = args.get("fromBlock").getAsString(); var stock = available(ctx);
        int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
        var rows = List.copyOf(stock.entrySet()); if (offset < 0 || offset > rows.size()) throw new IllegalArgumentException("SUBSTITUTION_OFFSET");
        var candidates = new ArrayList<JsonObject>(); int end = Math.min(rows.size(), offset + 48);
        for (var row : rows.subList(offset, end)) {
            String block = blueprints.inspect(ctx.control(), ctx.actor(), () -> {
                var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(row.getKey()));
                return item instanceof BlockItem placeable ? BuiltInRegistries.BLOCK.getKey(placeable.getBlock()).toString() : "";
            });
            if (block.isEmpty() || block.equals(from)) continue;
            try {
                var assessment = evaluate(ctx, saved, from, block); var item = new JsonObject();
                item.addProperty("toBlock", block); item.addProperty("availableUnits", row.getValue()); item.addProperty("changedCells", assessment.cells());
                item.addProperty("estimatedUnitsOnEmptyGround", assessment.units()); item.addProperty("appearanceDistance", assessment.appearance());
                item.add("compatibilityEvidence", AiJson.GSON.toJsonTree(assessment.evidence())); candidates.add(item);
            } catch (IllegalArgumentException | IllegalStateException unsupported) {
                if (unsupported instanceof firefly520.fireflymc.ai.task.TaskControl.Stopped) throw unsupported;
                if (unsupported.getMessage() == null || !unsupported.getMessage().startsWith("SUBSTITUTION_")) throw unsupported;
            }
        }
        candidates.sort(Comparator.<JsonObject>comparingLong(row -> -row.get("availableUnits").getAsLong()).thenComparingInt(row -> row.get("appearanceDistance").getAsInt()));
        var data = new JsonObject(); data.add("candidates", AiJson.GSON.toJsonTree(candidates)); data.addProperty("nextOffset", end); data.addProperty("totalInventoryKinds", rows.size());
        data.addProperty("note", "只列当前真实库存；排序不能覆盖硬性兼容要求。采用替代须本人回答，实际施工重新核验用料与世界。");
        return result("已核验的材质替代候选。", data);
    }
    private AgentTool.Result propose(AgentTool.Context ctx, JsonObject args) throws Exception {
        var saved = source(ctx, args); String from = args.get("fromBlock").getAsString(), to = args.get("toBlock").getAsString();
        if (from.equals(to)) throw new IllegalArgumentException("SUBSTITUTION_NO_CHANGE");
        long atGeneration = generation.getAsLong(); var evaluation = evaluate(ctx, saved, from, to); var stock = available(ctx);
        String itemId = blueprints.inspect(ctx.control(), ctx.actor(), () -> BuiltInRegistries.ITEM.getKey(BuiltInRegistries.BLOCK.get(ResourceLocation.parse(to)).asItem()).toString());
        long count = stock.getOrDefault(itemId, 0L); if (count == 0) throw new IllegalStateException("SUBSTITUTION_MATERIAL_UNAVAILABLE");
        var facts = new Facts(UUID.randomUUID(), ctx.workspace().metadata().serverInstanceId(), ctx.workspace().primaryConversationId(), ctx.actor().owner(), ctx.taskId(), epoch,
                ctx.workspace().conversation().privacyEpoch(), atGeneration, saved.entry().id(), saved.entry().revision(), saved.entry().hash(), saved.entry().dimension(), from, to,
                count, evaluation.cells(), evaluation.units(), evaluation.appearance(), evaluation.states(), evaluation.evidence());
        synchronized (ctx.workspace().conversation()) {
            requireActive(ctx, facts); new BlueprintRepository(ctx.workspace()).reviewStamp(ctx.actor(), facts.blueprintId(), facts.blueprintHash());
            String prompt = "是否采用此蓝图材质替代？\n" + from + " → " + to + "\n影响 " + evaluation.cells() + " 格；按空地放置估计 " + evaluation.units()
                    + " 件，当前可用 " + count + " 件。\n碰撞、状态、透光、流体、可燃性及强度已核验；外观会改变。接受后创建新版本，施工仍需重新预算和审批。\n方案摘要：" + AiJson.digest(AiJson.canonical(facts));
            String stepId = QuestionCoordinator.toolStep("blueprint_material_apply", facts.id());
            var question = new QuestionStore(ctx.workspace()).createForSteps(ctx.actor(), ctx.taskId(), prompt, List.of(ACCEPT, KEEP),
                    stepId, List.of(stepId), AiJson.digest(AiJson.canonical(facts)));
            var proposal = new Proposal(facts, question.id(), question.digest()); String hash = AiJson.digest(AiJson.canonical(proposal));
            AiJson.write(ctx.workspace().artifact(facts.id()), proposal);
            var index = new JsonObject(); index.addProperty("id", facts.id().toString()); index.addProperty("hash", hash);
            ctx.workspace().conversation().append(List.of(new Draft(ctx.taskId(), "material_substitution", null, index, null)));
            var data = index.deepCopy(); data.addProperty("proposalId", facts.id().toString()); data.addProperty("proposalHash", hash);
            data.add("question", AiJson.GSON.toJsonTree(question)); data.add("proposal", AiJson.GSON.toJsonTree(facts));
            return result("材质方案已提交本人工作台，等待阻塞问题回答；蓝图和世界尚未改变。", data);
        }
    }
    private AgentTool.Result apply(AgentTool.Context ctx, JsonObject args) throws Exception {
        UUID id = UUID.fromString(args.get("proposalId").getAsString()); var proposal = load(ctx.workspace(), ctx.actor(), id, args.get("proposalHash").getAsString()); var facts = proposal.facts();
        requireActive(ctx, facts);
        var question = new QuestionStore(ctx.workspace()).list(ctx.actor(), facts.task()).stream().filter(value -> value.id().equals(proposal.questionId())).findFirst().orElseThrow();
        if (!question.digest().equals(proposal.questionDigest()) || !question.answered()) throw new IllegalStateException("SUBSTITUTION_ANSWER_REQUIRED");
        if (!ACCEPT.equals(question.answer().strip())) throw new IllegalStateException("SUBSTITUTION_NOT_ACCEPTED");
        new QuestionStore(ctx.workspace()).requireBeforeStep(ctx.actor(), facts.task(),
                QuestionCoordinator.toolStep("blueprint_material_apply", facts.id()), AiJson.digest(AiJson.canonical(facts)));
        var repository = new BlueprintRepository(ctx.workspace());
        var existing = repository.history(ctx.actor()).stream().filter(entry -> id.equals(entry.requestId())).findFirst();
        if (existing.isPresent()) return result("该替代已创建固定版本，没有重复修改。", BlueprintService.manifest(existing.get()));
        var saved = repository.read(ctx.actor(), facts.blueprintId(), facts.blueprintRevision(), facts.blueprintHash());
        if (!repository.current(ctx.actor(), saved.entry())) throw new IllegalStateException("BLUEPRINT_REVISION_CHANGED");
        var checked = evaluate(ctx, saved, facts.fromBlock(), facts.toBlock());
        if (!checked.states().equals(facts.states())) throw new IllegalStateException("SUBSTITUTION_SEMANTICS_CHANGED");
        requireActive(ctx, facts);
        var replacements = new HashMap<BlueprintDocument.State, BlueprintDocument.State>(); facts.states().forEach(change -> replacements.put(change.before(), change.after()));
        var blocks = new ArrayList<BlueprintDocument.Block>();
        for (var block : saved.document().blocks()) { ctx.control().check(); blocks.add(new BlueprintDocument.Block(block.position(), replacements.getOrDefault(block.state(), block.state()))); }
        var document = new BlueprintDocument(1, saved.document().name(), "fireflymc-material-substitution", "1", saved.entry().documentHash(), saved.document().origin(), blocks);
        var next = blueprints.save(ctx.workspace(), ctx.actor(), ctx.control(), id, facts.blueprintId(), facts.blueprintRevision(), facts.blueprintHash(), ctx.taskId(), document, facts.privacyEpoch());
        return result("材质替代已形成新蓝图版本；旧批准失效，施工须对新版本重新预算和审批。", BlueprintService.manifest(next.entry()));
    }
    public static Proposal load(Workspace workspace, Actor actor, UUID id, String hash) throws Exception {
        actor.requireAccess(workspace.owner());
        boolean indexed = workspace.conversation().getSnapshot(workspace.conversation().privacyEpoch()).events().stream().anyMatch(event -> event.kind().equals("material_substitution")
                && id.toString().equals(event.data().get("id").getAsString()) && hash.equals(event.data().get("hash").getAsString()));
        Path file = workspace.artifact(id);
        if (!indexed || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1_048_576) throw new IllegalArgumentException("SUBSTITUTION_NOT_FOUND");
        var proposal = AiJson.GSON.fromJson(Files.readString(file), Proposal.class); var facts = proposal.facts();
        if (!hash.equals(AiJson.digest(AiJson.canonical(proposal))) || !facts.id().equals(id) || !facts.owner().equals(actor.owner())
                || !facts.server().equals(workspace.metadata().serverInstanceId()) || !facts.conversation().equals(workspace.primaryConversationId())) throw new SecurityException("SUBSTITUTION_CHANGED");
        return proposal;
    }
    private void requireActive(AgentTool.Context ctx, Facts facts) {
        ctx.actor().requireAccess(facts.owner()); ctx.control().check(); var task = ctx.workspace().conversation().task(ctx.taskId());
        if (task == null || task.state().terminal() || !Objects.equals(ctx.taskId(), facts.task())) throw new IllegalStateException("SUBSTITUTION_TASK_CHANGED");
        if (!epoch.equals(facts.executionEpoch()) || facts.generation() != generation.getAsLong() || facts.privacyEpoch() != ctx.workspace().conversation().privacyEpoch())
            throw new IllegalStateException("SUBSTITUTION_REVALIDATION_REQUIRED");
    }
    private Evaluation evaluate(AgentTool.Context ctx, BlueprintRepository.Revision saved, String from, String to) throws Exception {
        var selected = saved.document().blocks().stream().filter(block -> block.state().blockId().equals(from)).toList();
        if (selected.isEmpty()) throw new IllegalArgumentException("SUBSTITUTION_SOURCE_NOT_IN_BLUEPRINT");
        var counts = new LinkedHashMap<BlueprintDocument.State, Integer>(); var positions = new HashMap<BlueprintDocument.State, BlueprintDocument.Position>();
        for (var block : selected) { counts.merge(block.state(), 1, Math::addExact); positions.putIfAbsent(block.state(), saved.document().origin().add(block.position())); }
        if (counts.size() > 64) throw new IllegalArgumentException("SUBSTITUTION_STATE_LIMIT");
        var changes = new ArrayList<StateChange>(); var evidence = new LinkedHashSet<String>(); int appearance = 0, units = 0;
        for (var entry : counts.entrySet()) {
            var assessment = blueprints.inspect(ctx.control(), ctx.actor(), () -> {
                var player = inventory.requirePlayer(ctx.actor()); var level = player.serverLevel();
                if (!level.dimension().location().toString().equals(saved.entry().dimension())) throw new IllegalStateException("BLUEPRINT_DIMENSION_CHANGED");
                var pos = positions.get(entry.getKey()); var at = new BlockPos(pos.x(), pos.y(), pos.z());
                if (!level.hasChunkAt(at) || !level.getWorldBorder().isWithinBounds(at) || level.isOutsideBuildHeight(at)) throw new IllegalStateException("BLUEPRINT_REGION_UNAVAILABLE");
                var target = ResourceLocation.parse(to); if (!BuiltInRegistries.BLOCK.containsKey(target)) throw new IllegalArgumentException("SUBSTITUTION_UNKNOWN_BLOCK");
                return MaterialSubstitutionRules.assess(BlueprintBlockStateCodec.resolve(entry.getKey()), BuiltInRegistries.BLOCK.get(target), level, at);
            });
            if (!assessment.allowed()) throw new IllegalArgumentException("SUBSTITUTION_" + assessment.reason());
            changes.add(new StateChange(entry.getKey(), assessment.replacement())); evidence.addAll(assessment.evidence()); appearance = Math.max(appearance, assessment.appearanceDistance());
            int each = "double".equals(assessment.replacement().properties().get("type")) ? 2 : 1;
            units = Math.addExact(units, Math.multiplyExact(entry.getValue(), each));
        }
        return new Evaluation(changes, selected.size(), units, appearance, List.copyOf(evidence));
    }
    private static AgentTool.Result result(String summary, JsonObject data) { return new AgentTool.Result(true, "", summary, data, "server-material-substitution", "", false); }
    private static void tool(ToolRegistry registry, String name, String description, String properties, List<String> required, DefinedTool.Action action) {
        var schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", JsonParser.parseString(properties)); schema.add("required", AiJson.GSON.toJsonTree(required)); schema.addProperty("additionalProperties", false);
        registry.register(new DefinedTool(new AgentTool.Descriptor(name, description, schema, AgentTool.Risk.READ_ONLY, "blueprint.own", 0,
                AgentTool.ExecutionThread.IO, 120000, true, ""), action));
    }
}
