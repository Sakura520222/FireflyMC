package firefly520.fireflymc.ai.blueprint;

import com.google.gson.*;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Server-authoritative blueprint content and bounded world observations; no world mutation is performed here. */
public final class BlueprintService {
    private record Work<T>(TaskControl control, Callable<T> action, CompletableFuture<T> future) {}
    private final MinecraftServer server;
    private final Predicate<ServerPlayer> authenticated;
    private final InstalledPermissionAdapters permissions;
    private final ArrayBlockingQueue<Work<?>> queue = new ArrayBlockingQueue<>(128);
    private volatile boolean closed;
    private BuildRegionService regionProposals;
    public BlueprintService(MinecraftServer server, Predicate<ServerPlayer> authenticated, InstalledPermissionAdapters permissions) {
        if (!server.isDedicatedServer()) throw new IllegalArgumentException("DEDICATED_SERVER_REQUIRED");
        this.server = server; this.authenticated = authenticated; this.permissions = permissions;
    }
    public void bindRegionProposals(BuildRegionService service) {
        if (regionProposals != null) throw new IllegalStateException("BUILD_PROPOSAL_SERVICE_ALREADY_BOUND");
        regionProposals = java.util.Objects.requireNonNull(service);
    }
    public void register(ToolRegistry registry) {
        JsonObject schema = AiJson.object("""
                {"type":"object","properties":{"name":{"type":"string","maxLength":128},
                "origin":{"type":"array","items":{"type":"integer"},"minItems":3,"maxItems":3},
                "boxes":{"type":"array","minItems":1,"maxItems":64,"items":{"type":"object","properties":{
                "from":{"type":"array","items":{"type":"integer"},"minItems":3,"maxItems":3},
                "to":{"type":"array","items":{"type":"integer"},"minItems":3,"maxItems":3},
                "blockId":{"type":"string"},"properties":{"type":"object","additionalProperties":{"type":"string"}}},
                "required":["from","to","blockId"],"additionalProperties":false}}},"required":["name","boxes"],"additionalProperties":false}
                """, 8192, 16);
        registry.register(new DefinedTool(new AgentTool.Descriptor("blueprint_generate", "生成私人建筑蓝图：boxes 为相对原点的实心长方体，可用多块墙/地板/屋顶和显式空气表达结构；后定义覆盖前定义。默认省略 origin，服务端按本人朝向建议位置并调查现场，无需玩家手填坐标或选两角点。手动模式生成待确认候选；YOLO 模式自动确定位置，之后直接生成并执行施工计划。此工具本身不扣料或放置方块。",
                schema, AgentTool.Risk.READ_ONLY, "blueprint.own", 0, AgentTool.ExecutionThread.IO, 120000, true, ""), (ctx, args) -> {
                    JsonObject generated = args.deepCopy();
                    if (!generated.has("origin")) { var zero = new JsonArray(); zero.add(0); zero.add(0); zero.add(0); generated.add("origin", zero); }
                    BlueprintDocument document = generate(generated, ctx.control());
                    if (!args.has("origin")) {
                        var shape = document;
                        var origin = inspect(ctx.control(), ctx.actor(), () -> {
                            var p = player(ctx.actor()); var pos = p.blockPosition();
                            return BuildRegionService.suggestedOrigin(shape, new BlueprintDocument.Position(pos.getX(), pos.getY(), pos.getZ()),
                                    p.getDirection().getStepX(), p.getDirection().getStepZ());
                        });
                        document = new BlueprintDocument(1, document.name(), document.source(), document.sourceVersion(), document.sourceDigest(), origin, document.blocks());
                    }
                    var saved = save(ctx.workspace(), ctx.actor(), ctx.control(), UUID.randomUUID(), null, 0, "", ctx.taskId(), document);
                    var data = manifest(saved.entry());
                    if (regionProposals != null) data.add("regionProposal", regionProposals.propose(ctx.workspace(), ctx.actor(), ctx.control(),
                            saved.entry().id(), saved.entry().revision(), saved.entry().hash(), false));
                    boolean automatic = data.has("regionProposal") && data.getAsJsonObject("regionProposal").has("regionConfirmed")
                            && data.getAsJsonObject("regionProposal").get("regionConfirmed").getAsBoolean();
                    return new AgentTool.Result(true, "", automatic
                            ? "YOLO：蓝图和位置已确定，可直接生成并执行施工计划，无需玩家确认。"
                            : "蓝图与位置候选已保存；在世界预览确认位置后继续施工计划。", data, "server-blueprint", "", false);
                }));
        JsonObject query = AiJson.object("""
                {"type":"object","properties":{"id":{"type":"string"},"revision":{"type":"integer","minimum":1},"hash":{"type":"string"},"offset":{"type":"integer","minimum":0}},
                "required":["id","revision","hash"],"additionalProperties":false}
                """, 8192, 16);
        registry.register(new DefinedTool(new AgentTool.Descriptor("blueprint_inspect", "读取本人确定版本蓝图及当前世界差异：区分已有满足、新增、拆除、替换、数据方块和保护冲突；分页不加载远处区块，差异不代表施工授权。",
                query, AgentTool.Risk.READ_ONLY, "blueprint.own", 0, AgentTool.ExecutionThread.IO, 30000, true, ""), (ctx, args) -> {
                    var data = page(ctx.workspace(), ctx.actor(), ctx.control(), args, true);
                    return new AgentTool.Result(true, "", "服务端蓝图和当前差异，尚未执行施工。", data, "server-blueprint", "", false);
                }));
    }
    public BlueprintRepository.Revision save(Workspace workspace, Actor actor, TaskControl control, UUID request,
                                             UUID id, long revision, String hash, UUID task, BlueprintDocument document) throws Exception {
        return save(workspace, actor, control, request, id, revision, hash, task, document, workspace.conversation().privacyEpoch());
    }
    public BlueprintRepository.Revision save(Workspace workspace, Actor actor, TaskControl control, UUID request,
                                             UUID id, long revision, String hash, UUID task, BlueprintDocument document, long expectedPrivacyEpoch) throws Exception {
        if (workspace.conversation().privacyEpoch() != expectedPrivacyEpoch) throw new IllegalStateException("BLUEPRINT_PRIVACY_CHANGED");
        var repository = new BlueprintRepository(workspace);
        record Context(String dimension, int minimum, int maximum, Object connection) {}
        Context context = onMain(control, () -> { var p = player(actor); return new Context(p.serverLevel().dimension().location().toString(),
                p.serverLevel().getMinBuildHeight(), p.serverLevel().getMaxBuildHeight(), p.connection); });
        var states = document.blocks().stream().map(BlueprintDocument.Block::state).distinct().toList();
        if (states.size() > 4096) throw new IllegalArgumentException("BLUEPRINT_PALETTE_LIMIT");
        var normalized = new HashMap<BlueprintDocument.State, BlueprintDocument.State>();
        for (int first = 0; first < states.size(); first += 16) {
            int begin = first, end = Math.min(states.size(), first + 16);
            normalized.putAll(onMain(control, () -> {
                var player = player(actor);
                if (player.connection != context.connection() || !context.dimension().equals(player.serverLevel().dimension().location().toString()))
                    throw new IllegalStateException("BLUEPRINT_CONTEXT_CHANGED");
                var result = new HashMap<BlueprintDocument.State, BlueprintDocument.State>();
                for (var state : states.subList(begin, end)) result.put(state, BlueprintBlockStateCodec.describe(BlueprintBlockStateCodec.resolve(state)));
                return result;
            }));
        }
        var blocks = new ArrayList<BlueprintDocument.Block>();
        for (var block : document.blocks()) {
            control.check();
            var pos = document.origin().add(block.position());
            if (pos.y() < context.minimum() || pos.y() >= context.maximum()) throw new IllegalArgumentException("BLUEPRINT_HEIGHT_LIMIT");
            blocks.add(new BlueprintDocument.Block(block.position(), normalized.get(block.state())));
        }
        var accepted = new BlueprintDocument(1, document.name(), document.source(), document.sourceVersion(), document.sourceDigest(), document.origin(), blocks);
        synchronized (workspace.conversation()) {
            control.check();
            if (workspace.conversation().privacyEpoch() != expectedPrivacyEpoch) throw new IllegalStateException("BLUEPRINT_PRIVACY_CHANGED");
            if (task != null && (workspace.conversation().task(task) == null || workspace.conversation().task(task).state().terminal()))
                throw new TaskControl.Stopped("TASK_ENDED");
            return repository.save(actor, request, id, revision, hash, task, context.dimension(), accepted);
        }
    }
    public JsonObject page(Workspace workspace, Actor actor, TaskControl control, JsonObject args, boolean inspect) throws Exception {
        var repository = new BlueprintRepository(workspace);
        var saved = repository.read(actor, UUID.fromString(args.get("id").getAsString()), args.get("revision").getAsLong(), args.get("hash").getAsString());
        int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
        if (offset < 0 || offset > saved.document().blocks().size()) throw new IllegalArgumentException("BLUEPRINT_OFFSET");
        JsonObject data = manifest(saved.entry()); data.add("origin", AiJson.GSON.toJsonTree(saved.document().origin()));
        data.addProperty("source", saved.document().source()); data.addProperty("sourceVersion", saved.document().sourceVersion());
        data.addProperty("sourceDigest", saved.document().sourceDigest());
        data.addProperty("current", repository.current(actor, saved.entry()));
        JsonArray cells = new JsonArray(); int end = offset;
        while (end < saved.document().blocks().size() && end < offset + (inspect ? 16 : 64)) {
            var block = saved.document().blocks().get(end);
            JsonObject cell = AiJson.GSON.toJsonTree(block).getAsJsonObject();
            if (data.toString().length() + cells.toString().length() + cell.toString().length() > 16000) break;
            cells.add(cell); end++;
        }
        if (inspect) {
            final int start = offset, last = end;
            var desired = new HashMap<BlueprintDocument.Position, BlueprintDocument.Block>();
            saved.document().blocks().forEach(block -> desired.put(block.position(), block));
            var observations = onMain(control, () -> {
                var player = player(actor); var level = player.serverLevel();
                var rows = new ArrayList<JsonObject>();
                for (var block : saved.document().blocks().subList(start, last)) {
                    var absolute = saved.document().origin().add(block.position());
                    BlockPos pos = new BlockPos(absolute.x(), absolute.y(), absolute.z());
                    var row = new JsonObject();
                    String status;
                    if (!level.dimension().location().toString().equals(saved.entry().dimension())) status = "OTHER_DIMENSION";
                    else if (pos.distToCenterSqr(player.position()) > 128 * 128 || !level.hasChunkAt(pos)) status = "NOT_OBSERVED";
                    else {
                        var before = level.getBlockState(pos); var target = BlueprintBlockStateCodec.resolve(block.state());
                        row.add("actual", AiJson.GSON.toJsonTree(BlueprintBlockStateCodec.describe(before)));
                        row.addProperty("blockEntityPresent", level.getBlockEntity(pos) != null);
                        if (before.equals(target)) status = "SATISFIED";
                        else if (level.getBlockEntity(pos) != null) status = "DATA_BLOCK_CONFLICT";
                        else if (!level.getWorldBorder().isWithinBounds(pos) || !level.mayInteract(player, pos)
                                || !permissions.checkPlace(player, level, pos).allowed()
                                || !before.isAir() && !permissions.checkBreak(player, level, pos, net.minecraft.core.Direction.UP).allowed()) status = "PROTECTED";
                        else if (target.isAir()) status = "DEMOLITION";
                        else if (before.isAir()) status = "ADD";
                        else status = "REPLACE";
                        if (!before.equals(target) && !target.isAir()) {
                            try {
                                var group = PlacementMaterialResolver.resolve(block, desired);
                                if (group.isPresent()) {
                                    var observed = new HashMap<BlueprintDocument.Position, net.minecraft.world.level.block.state.BlockState>();
                                    for (var member : group.get().targets()) {
                                        var p = saved.document().origin().add(member.position()); var memberPos = new BlockPos(p.x(), p.y(), p.z());
                                        if (!level.hasChunkAt(memberPos)) throw new IllegalStateException("PLACEMENT_WORLD_NOT_OBSERVED");
                                        observed.put(member.position(), level.getBlockState(memberPos));
                                    }
                                    int count = PlacementMaterialResolver.requiredItems(group.get(), observed);
                                    var quote = new JsonObject(); quote.addProperty("itemId", group.get().itemId()); quote.addProperty("count", count);
                                    quote.addProperty("kind", group.get().kind().name()); quote.addProperty("waterloggedTarget", group.get().waterlogged());
                                    row.add("placement", quote);
                                } else row.addProperty("pairedPlacement", true);
                            } catch (IllegalArgumentException | IllegalStateException unsupported) {
                                row.addProperty("placementIssue", unsupported.getMessage() != null && unsupported.getMessage().matches("[A-Z_]{1,80}")
                                        ? unsupported.getMessage() : "PLACEMENT_ADAPTER_REQUIRED");
                            }
                        }
                    }
                    row.addProperty("status", status); rows.add(row);
                }
                return rows;
            });
            for (int i = 0; i < observations.size(); i++) cells.get(i).getAsJsonObject().add("observation", observations.get(i));
        }
        data.add("blocks", cells); data.addProperty("offset", offset); data.addProperty("nextOffset", end);
        data.addProperty("inspection", inspect);
        data.addProperty("total", saved.document().blocks().size()); data.addProperty("constructionApproved", false);
        while (data.toString().length() > 23000 && cells.size() > 1) { cells.remove(cells.size() - 1); data.addProperty("nextOffset", --end); }
        if (data.toString().length() > 23000) throw new IllegalStateException("BLUEPRINT_CELL_DATA_LIMIT");
        return data;
    }
    public BlueprintRepository.Revision transform(Workspace workspace, Actor actor, TaskControl control, UUID request, JsonObject args) throws Exception {
        long expectedPrivacyEpoch = workspace.conversation().privacyEpoch();
        var repository = new BlueprintRepository(workspace);
        UUID id = UUID.fromString(args.get("id").getAsString()); long revision = args.get("revision").getAsLong(); String hash = args.get("hash").getAsString();
        var current = repository.read(actor, id, revision, hash);
        repository.invalidateReview(actor, id, hash);
        Rotation rotation = Rotation.valueOf(args.has("rotation") ? args.get("rotation").getAsString() : "NONE");
        Mirror mirror = Mirror.valueOf(args.has("mirror") ? args.get("mirror").getAsString() : "NONE");
        var origin = args.has("origin") ? position(args.getAsJsonArray("origin")) : current.document().origin();
        var states = current.document().blocks().stream().map(BlueprintDocument.Block::state).distinct().toList();
        var transformed = new HashMap<BlueprintDocument.State, BlueprintDocument.State>();
        for (int first = 0; first < states.size(); first += 16) {
            int begin = first, end = Math.min(states.size(), first + 16);
            transformed.putAll(onMain(control, () -> {
                var player = player(actor);
                if (!player.serverLevel().dimension().location().toString().equals(current.entry().dimension())) throw new IllegalStateException("BLUEPRINT_DIMENSION_CHANGED");
                var values = new HashMap<BlueprintDocument.State, BlueprintDocument.State>();
                for (var state : states.subList(begin, end)) values.put(state, BlueprintBlockStateCodec.describe(BlueprintBlockStateCodec.resolve(state).mirror(mirror).rotate(rotation)));
                return values;
            }));
        }
        var blocks = current.document().blocks().stream().map(block -> {
            var p = block.position(); long x = mirror == Mirror.FRONT_BACK ? -(long) p.x() : p.x();
            long z = mirror == Mirror.LEFT_RIGHT ? -(long) p.z() : p.z();
            long rx = switch (rotation) { case NONE -> x; case CLOCKWISE_90 -> -z; case CLOCKWISE_180 -> -x; case COUNTERCLOCKWISE_90 -> z; };
            long rz = switch (rotation) { case NONE -> z; case CLOCKWISE_90 -> x; case CLOCKWISE_180 -> -z; case COUNTERCLOCKWISE_90 -> -x; };
            return new BlueprintDocument.Block(new BlueprintDocument.Position(Math.toIntExact(rx), p.y(), Math.toIntExact(rz)), transformed.get(block.state()));
        }).toList();
        var document = new BlueprintDocument(1, current.document().name(), "fireflymc-transform", "1", current.entry().documentHash(), origin, blocks);
        return save(workspace, actor, control, request, id, revision, hash, null, document, expectedPrivacyEpoch);
    }
    public static JsonObject manifest(BlueprintRepository.Entry entry) {
        JsonObject data = new JsonObject(); data.addProperty("id", entry.id().toString()); data.addProperty("revision", entry.revision());
        data.addProperty("hash", entry.hash()); data.addProperty("name", entry.name()); data.addProperty("dimension", entry.dimension());
        data.addProperty("blocks", entry.blocks()); data.addProperty("documentHash", entry.documentHash()); return data;
    }
    private static BlueprintDocument generate(JsonObject args, TaskControl control) {
        var origin = position(args.getAsJsonArray("origin")); var cells = new LinkedHashMap<BlueprintDocument.Position, BlueprintDocument.Block>();
        var boxes = args.getAsJsonArray("boxes");
        if (boxes.size() < 1 || boxes.size() > 64) throw new IllegalArgumentException("BLUEPRINT_BOX_LIMIT");
        long visited = 0;
        for (var value : boxes) {
            var box = value.getAsJsonObject(); var min = position(box.getAsJsonArray("from")); var max = position(box.getAsJsonArray("to"));
            if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) throw new IllegalArgumentException("BLUEPRINT_BOX_INVALID");
            long volume = Math.multiplyExact(Math.multiplyExact((long) max.x() - min.x() + 1, (long) max.y() - min.y() + 1), (long) max.z() - min.z() + 1);
            visited = Math.addExact(visited, volume);
            if (visited > BlueprintDocument.MAX_BLOCKS) throw new IllegalArgumentException("BLUEPRINT_CELL_LIMIT");
            var props = new TreeMap<String, String>();
            if (box.has("properties")) box.getAsJsonObject("properties").entrySet().forEach(e -> props.put(e.getKey(), e.getValue().getAsString()));
            var state = new BlueprintDocument.State(box.get("blockId").getAsString(), props);
            for (long y = min.y(); y <= max.y(); y++) for (long z = min.z(); z <= max.z(); z++) for (long x = min.x(); x <= max.x(); x++) {
                control.check(); var pos = new BlueprintDocument.Position((int) x, (int) y, (int) z);
                cells.put(pos, new BlueprintDocument.Block(pos, state));
            }
        }
        return new BlueprintDocument(1, args.get("name").getAsString(), "fireflymc-generated", "1", "", origin, List.copyOf(cells.values()));
    }
    private static BlueprintDocument.Position position(JsonArray array) {
        if (array == null || array.size() != 3) throw new IllegalArgumentException("BLUEPRINT_POSITION_INVALID");
        var pos = new BlueprintDocument.Position(array.get(0).getAsBigDecimal().intValueExact(), array.get(1).getAsBigDecimal().intValueExact(), array.get(2).getAsBigDecimal().intValueExact());
        BlueprintDocument.checkWorld(pos); return pos;
    }
    private ServerPlayer player(Actor actor) {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        var player = server.getPlayerList().getPlayer(UUID.fromString(actor.owner()));
        if (player == null || !authenticated.test(player)) throw new SecurityException("PLAYER_NOT_AUTHENTICATED");
        actor.requireAccess(player.getUUID().toString()); return player;
    }
    private <T> T onMain(TaskControl control, Callable<T> action) throws Exception {
        if (closed) throw new IllegalStateException("AI_STOPPING");
        var future = new CompletableFuture<T>();
        if (!queue.offer(new Work<>(control, action, future))) throw new IllegalStateException("BLUEPRINT_QUEUE_FULL");
        return control.await(future, 10000);
    }
    /** Bounded authenticated native inspection reused by blueprint planning services. */
    public <T> T inspect(TaskControl control, Actor actor, Callable<T> action) throws Exception {
        return onMain(control, () -> { player(actor); return action.call(); });
    }
    public void tick() {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        long end = System.nanoTime() + 1_500_000;
        for (int i = 0; i < 2 && System.nanoTime() < end; i++) { var work = queue.poll(); if (work == null) break; run(work); }
    }
    private static <T> void run(Work<T> work) {
        if (work.future().isCancelled()) return;
        try { work.control().check(); work.future().complete(work.action().call()); }
        catch (Exception failure) { work.future().completeExceptionally(failure); }
    }
    public void close() {
        closed = true; Work<?> work;
        while ((work = queue.poll()) != null) work.future().completeExceptionally(new IllegalStateException("AI_STOPPING"));
    }
}
