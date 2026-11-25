package firefly520.fireflymc.ai.blueprint;

import com.google.gson.*;
import firefly520.fireflymc.ai.blueprint.BlueprintDocument.Position;
import firefly520.fireflymc.ai.integration.InstalledPermissionAdapters;
import firefly520.fireflymc.ai.security.Actor;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.task.TaskControl;
import firefly520.fireflymc.ai.tools.*;
import firefly520.fireflymc.ai.workspace.WorkspaceManager.Workspace;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Fixed region proposals are confirmed in the workbench or automatically in the owner's YOLO mode. */
public final class BuildRegionService {
    private final MinecraftServer server;
    private final BlueprintService blueprints;
    private final InstalledPermissionAdapters permissions;
    public BuildRegionService(MinecraftServer server, BlueprintService blueprints, InstalledPermissionAdapters permissions) {
        if (!server.isDedicatedServer()) throw new IllegalStateException("DEDICATED_SERVER_REQUIRED");
        this.server = server; this.blueprints = blueprints; this.permissions = permissions;
    }
    public void register(ToolRegistry tools) {
        var proposalSchema = AiJson.object("""
                {"type":"object","properties":{"blueprintId":{"type":"string"},"revision":{"type":"integer","minimum":1},
                "blueprintHash":{"type":"string"},"nearPlayer":{"type":"boolean"}},
                "required":["blueprintId","revision","blueprintHash"],"additionalProperties":false}
                """, 2048, 8);
        tools.register(new DefinedTool(new AgentTool.Descriptor("build_region_propose",
                "为本人固定蓝图提出施工位置和区域并调查真实现场；默认按本人朝向放在前方，不要求手填坐标。手动模式保存待确认候选；YOLO 模式自动采用已调查的固定版本，可直接继续生成并执行施工计划。",
                proposalSchema, AgentTool.Risk.READ_ONLY, "construction.own", 0, AgentTool.ExecutionThread.IO, 120000, true, ""),
                (ctx, args) -> new AgentTool.Result(true, "", "已生成固定蓝图及现场调查；regionConfirmed=true 时可直接继续施工计划，false 时由工作台确认位置。",
                        propose(ctx.workspace(), ctx.actor(), ctx.control(), UUID.fromString(args.get("blueprintId").getAsString()),
                                args.get("revision").getAsBigDecimal().longValueExact(), args.get("blueprintHash").getAsString(),
                                !args.has("nearPlayer") || args.get("nearPlayer").getAsBoolean()), "server-blueprint-region-proposal", "", false)));
        var schema = new JsonObject(); schema.addProperty("type", "object"); schema.add("properties", new JsonObject()); schema.addProperty("additionalProperties", false);
        for (String name : List.of("build_region_view", "build_region_survey")) tools.register(new DefinedTool(new AgentTool.Descriptor(name,
                name.endsWith("view") ? "读取本人在工作台选定的施工区域、版本和确认状态；区域确认不是扣料或施工批准。"
                        : "分 Tick 调查本人选区的实际占用、流体、带数据方块和保护结果；只访问已加载区块，不读取容器内容，不生成新区块。",
                schema, AgentTool.Risk.READ_ONLY, "construction.own", 0, AgentTool.ExecutionThread.IO, 60000, true, ""),
                (ctx, args) -> {
                    var store = new BuildRegionStore(ctx.workspace()); var selection = store.current(ctx.actor());
                    var data = view(ctx.workspace(), selection);
                    if (name.endsWith("survey")) data.add("survey", survey(ctx.actor(), ctx.control(), selection.orElseThrow(() -> new IllegalStateException("BUILD_REGION_EMPTY"))));
                    return new AgentTool.Result(true, "", "本人选区及服务端证据；未执行施工。", data, "owned-region", "", false);
                }));
    }
    public JsonObject handle(Workspace workspace, Actor actor, UUID request, JsonObject args, TaskControl control) throws Exception {
        if (!Set.of("action", "expectedRevision", "privacyEpoch", "hash", "corner", "first", "second", "blueprintId", "blueprintHash",
                "blueprintRevision", "proposalId", "proposalHash", "documentHash", "nearPlayer").containsAll(args.keySet()))
            throw new IllegalArgumentException("BUILD_REGION_FIELDS");
        var store = new BuildRegionStore(workspace); var current = store.current(actor);
        String action = args.has("action") ? args.get("action").getAsString() : "view";
        if (action.equals("view")) return view(workspace, current);
        if (action.equals("propose")) {
            propose(workspace, actor, control, UUID.fromString(args.get("blueprintId").getAsString()),
                    args.get("blueprintRevision").getAsBigDecimal().longValueExact(), args.get("blueprintHash").getAsString(),
                    args.has("nearPlayer") && args.get("nearPlayer").getAsBoolean());
            return view(workspace, current);
        }
        if (action.equals("proposal_confirm")) return confirmProposal(workspace, actor, request, args, control);
        if (action.equals("survey")) {
            var result = view(workspace, current); result.add("survey", survey(actor, control, current.orElseThrow(() -> new IllegalStateException("BUILD_REGION_EMPTY")))); return result;
        }
        long expected = args.get("expectedRevision").getAsBigDecimal().longValueExact(), epoch = args.get("privacyEpoch").getAsBigDecimal().longValueExact();
        if (current.map(BuildRegionStore.Selection::revision).orElse(0L) != expected) throw new IllegalStateException("BUILD_REGION_REVISION_CHANGED");
        String dimension;
        Position first = current.map(BuildRegionStore.Selection::first).orElse(null), second = current.map(BuildRegionStore.Selection::second).orElse(null);
        JsonObject surveyed = null; boolean confirmed = false;
        switch (action) {
            case "clear" -> { dimension = ""; first = null; second = null; }
            case "corner" -> {
                int corner = args.get("corner").getAsBigDecimal().intValueExact();
                if (corner != 1 && corner != 2) throw new IllegalArgumentException("BUILD_REGION_CORNER");
                record Look(String dimension, Position point) { }
                var look = blueprints.inspect(control, actor, () -> {
                    var player = player(actor); var hit = player.pick(player.blockInteractionRange(), 1, false);
                    if (!(hit instanceof BlockHitResult block) || block.getType() != HitResult.Type.BLOCK) throw new IllegalStateException("LOOK_AT_REGION_CORNER");
                    requirePosition(player, block.getBlockPos()); return new Look(player.serverLevel().dimension().location().toString(), position(block.getBlockPos()));
                });
                dimension = look.dimension();
                if (current.isPresent() && !dimension.equals(current.get().dimension())) { first = null; second = null; }
                if (corner == 1) first = look.point(); else second = look.point();
            }
            case "set" -> {
                first = position(args.getAsJsonObject("first")); second = position(args.getAsJsonObject("second"));
                BuildRegionStore.bounds(first, second); Position a = first, b = second;
                dimension = blueprints.inspect(control, actor, () -> { var player = player(actor);
                    requirePosition(player, nativePos(a)); requirePosition(player, nativePos(b)); return player.serverLevel().dimension().location().toString(); });
            }
            case "confirm" -> {
                var selected = current.orElseThrow(() -> new IllegalStateException("BUILD_REGION_EMPTY"));
                if (!selected.hash().equals(args.get("hash").getAsString())) throw new IllegalStateException("BUILD_REGION_CHANGED");
                surveyed = survey(actor, control, selected);
                if (surveyed.get("protected").getAsInt() > 0 || surveyed.get("unloaded").getAsInt() > 0)
                    throw new IllegalStateException("BUILD_REGION_NOT_AVAILABLE");
                dimension = selected.dimension(); confirmed = true;
            }
            default -> throw new IllegalArgumentException("BUILD_REGION_ACTION");
        }
        control.check();
        var accepted = store.save(actor, request, expected, epoch, dimension, first, second, confirmed);
        var result = view(workspace, Optional.of(accepted)); if (surveyed != null) result.add("survey", surveyed); return result;
    }
    private JsonObject survey(Actor actor, TaskControl control, BuildRegionStore.Selection selected) throws Exception {
        var bounds = selected.bounds(); var digest = MessageDigest.getInstance("SHA-256");
        int occupied = 0, fluids = 0, dataBlocks = 0, denied = 0, unloaded = 0;
        var points = new ArrayList<BlockPos>((int) bounds.volume());
        for (int y = bounds.minimum().y(); y <= bounds.maximum().y(); y++)
            for (int z = bounds.minimum().z(); z <= bounds.maximum().z(); z++)
                for (int x = bounds.minimum().x(); x <= bounds.maximum().x(); x++) points.add(new BlockPos(x, y, z));
        record Slice(int occupied, int fluids, int dataBlocks, int denied, int unloaded, String digest) { }
        for (int start = 0; start < points.size(); start += 32) {
            int from = start, to = Math.min(points.size(), start + 32);
            Slice slice = blueprints.inspect(control, actor, () -> {
                ServerPlayer player = player(actor); var level = player.serverLevel();
                if (!level.dimension().location().toString().equals(selected.dimension())) throw new IllegalStateException("BUILD_REGION_DIMENSION_CHANGED");
                int solid = 0, fluid = 0, be = 0, blocked = 0, missing = 0; var facts = new StringBuilder();
                for (var pos : points.subList(from, to)) {
                    if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) { blocked++; continue; }
                    if (!level.hasChunkAt(pos)) { missing++; facts.append(pos.asLong()).append(":unloaded;"); continue; }
                    var state = level.getBlockState(pos); if (!state.isAir()) solid++; if (!state.getFluidState().isEmpty()) fluid++; if (state.hasBlockEntity()) be++;
                    if (!permissions.checkPlace(player, level, pos).allowed()
                            || !state.isAir() && !permissions.checkBreak(player, level, pos, net.minecraft.core.Direction.UP).allowed()) blocked++;
                    facts.append(pos.asLong()).append(':').append(state).append(';');
                }
                return new Slice(solid, fluid, be, blocked, missing, facts.toString());
            });
            occupied += slice.occupied(); fluids += slice.fluids(); dataBlocks += slice.dataBlocks(); denied += slice.denied(); unloaded += slice.unloaded();
            digest.update(slice.digest().getBytes(StandardCharsets.UTF_8));
        }
        var result = new JsonObject(); result.addProperty("cells", points.size()); result.addProperty("occupied", occupied); result.addProperty("fluids", fluids);
        result.addProperty("dataBlocks", dataBlocks); result.addProperty("protected", denied); result.addProperty("unloaded", unloaded);
        result.addProperty("worldDigest", HexFormat.of().formatHex(digest.digest())); result.addProperty("regionHash", selected.hash());
        result.addProperty("note", "分批观察，施工前仍重新核验；占用/流体/带数据方块不会因选区确认而获得拆除许可。"); return result;
    }
    public static JsonObject view(Workspace workspace, Optional<BuildRegionStore.Selection> selected) {
        synchronized (workspace.conversation()) {
        var data = selected.map(value -> AiJson.GSON.toJsonTree(value).getAsJsonObject()).orElseGet(JsonObject::new);
        data.remove("owner"); data.remove("requestId"); data.addProperty("revision", selected.map(BuildRegionStore.Selection::revision).orElse(0L));
        data.addProperty("privacyEpoch", workspace.conversation().privacyEpoch()); data.addProperty("empty", selected.isEmpty() || !selected.get().active());
        if (selected.isPresent() && selected.get().complete()) data.add("bounds", AiJson.GSON.toJsonTree(selected.get().bounds()));
        data.addProperty("maximumVolume", BuildRegionStore.MAX_VOLUME); data.addProperty("maximumAxis", BuildRegionStore.MAX_AXIS);
        data.addProperty("constructionApproved", false);
        data.add("proposal", new BuildRegionProposalStore(workspace).view());
        data.addProperty("projectionRevision", workspace.conversation().getSnapshot(Long.MAX_VALUE).revision());
        return data;
        }
    }

    public JsonObject propose(Workspace workspace, Actor actor, TaskControl control, UUID id, long revision, String hash, boolean nearPlayer) throws Exception {
        actor.requireAccess(workspace.owner());
        var repository = new BlueprintRepository(workspace);
        var saved = repository.read(actor, id, revision, hash);
        if (!repository.current(actor, saved.entry())) throw new IllegalStateException("BLUEPRINT_VERSION_NOT_CURRENT");
        if (nearPlayer) {
            var document = saved.document();
            String savedDimension = saved.entry().dimension();
            var origin = blueprints.inspect(control, actor, () -> {
                var player = player(actor);
                if (!player.serverLevel().dimension().location().toString().equals(savedDimension))
                    throw new IllegalStateException("BUILD_REGION_DIMENSION_CHANGED");
                return suggestedOrigin(document, position(player.blockPosition()), player.getDirection().getStepX(), player.getDirection().getStepZ());
            });
            if (!origin.equals(saved.document().origin())) {
                var transform = new JsonObject(); transform.addProperty("id", id.toString()); transform.addProperty("revision", revision);
                transform.addProperty("hash", hash); var point = new JsonArray(); point.add(origin.x()); point.add(origin.y()); point.add(origin.z()); transform.add("origin", point);
                saved = blueprints.transform(workspace, actor, control, UUID.randomUUID(), transform);
            }
        }
        var bounds = blueprintBounds(saved.document());
        var region = new BuildRegionStore.Selection(1, workspace.metadata().serverInstanceId(), workspace.primaryConversationId(), workspace.owner(),
                workspace.conversation().privacyEpoch(), 1, UUID.randomUUID(), saved.entry().dimension(), bounds.minimum(), bounds.maximum(), false, "proposal-only");
        JsonObject surveyed = survey(actor, control, region); surveyed.remove("regionHash");
        control.check();
        var proposal = new BuildRegionProposalStore(workspace).save(actor, saved, bounds, surveyed);
        JsonObject result = new BuildRegionProposalStore(workspace).view();
        result.add("blueprint", BlueprintService.manifest(saved.entry()));
        result.addProperty("regionConfirmed", false); result.addProperty("proposalHash", proposal.hash());
        if (firefly520.fireflymc.ai.workspace.WorkspacePreferences.load(workspace).yoloMode()) {
            var args = new JsonObject();
            args.addProperty("proposalId", proposal.id().toString()); args.addProperty("proposalHash", proposal.hash());
            args.addProperty("documentHash", proposal.documentHash()); args.addProperty("privacyEpoch", proposal.privacyEpoch());
            args.addProperty("expectedRevision", new BuildRegionStore(workspace).current(actor).map(BuildRegionStore.Selection::revision).orElse(0L));
            result = confirmProposal(workspace, actor, UUID.randomUUID(), args, control, true);
            result.addProperty("regionConfirmed", true); result.addProperty("executionMode", "YOLO");
        }
        return result;
    }

    private JsonObject confirmProposal(Workspace workspace, Actor actor, UUID request, JsonObject args, TaskControl control) throws Exception {
        return confirmProposal(workspace, actor, request, args, control, false);
    }
    private JsonObject confirmProposal(Workspace workspace, Actor actor, UUID request, JsonObject args, TaskControl control, boolean automatic) throws Exception {
        var proposals = new BuildRegionProposalStore(workspace);
        var proposal = proposals.require(actor, UUID.fromString(args.get("proposalId").getAsString()), args.get("proposalHash").getAsString());
        if (args.get("privacyEpoch").getAsBigDecimal().longValueExact() != proposal.privacyEpoch()
                || !proposal.documentHash().equals(args.get("documentHash").getAsString())) throw new IllegalStateException("BUILD_PROPOSAL_PREVIEW_CHANGED");
        var repository = new BlueprintRepository(workspace);
        var blueprint = repository.read(actor, proposal.blueprintId(), proposal.blueprintRevision(), proposal.blueprintHash());
        if (!repository.current(actor, blueprint.entry()) || !blueprintBounds(blueprint.document()).equals(BuildRegionStore.bounds(proposal.first(), proposal.second())))
            throw new IllegalStateException("BUILD_PROPOSAL_CHANGED");
        var store = new BuildRegionStore(workspace); var current = store.current(actor);
        long expected = args.get("expectedRevision").getAsBigDecimal().longValueExact();
        if (current.map(BuildRegionStore.Selection::revision).orElse(0L) != expected) throw new IllegalStateException("BUILD_REGION_REVISION_CHANGED");
        var selected = new BuildRegionStore.Selection(1, workspace.metadata().serverInstanceId(), workspace.primaryConversationId(), workspace.owner(),
                proposal.privacyEpoch(), 1, request, proposal.dimension(), proposal.first(), proposal.second(), false, "proposal-only");
        var surveyed = survey(actor, control, selected);
        if (!proposal.worldDigest().equals(surveyed.get("worldDigest").getAsString())) throw new IllegalStateException("BUILD_PROPOSAL_WORLD_CHANGED_REPREVIEW");
        if (surveyed.get("protected").getAsInt() > 0 || surveyed.get("unloaded").getAsInt() > 0) throw new IllegalStateException("BUILD_REGION_NOT_AVAILABLE");
        synchronized (workspace.conversation()) {
            control.check(); proposals.require(actor, proposal.id(), proposal.hash());
            if (automatic && !firefly520.fireflymc.ai.workspace.WorkspacePreferences.load(workspace).yoloMode())
                throw new IllegalStateException("YOLO_DISABLED");
            if (!repository.current(actor, blueprint.entry())) throw new IllegalStateException("BUILD_PROPOSAL_CHANGED");
            var accepted = store.save(actor, request, expected, proposal.privacyEpoch(), proposal.dimension(), proposal.first(), proposal.second(), true);
            proposals.confirmed(actor, proposal, accepted);
            var result = view(workspace, Optional.of(accepted)); result.add("survey", surveyed);
            result.addProperty("proposalConfirmed", true); result.add("blueprint", BlueprintService.manifest(blueprint.entry()));
            return result;
        }
    }

    public static BuildRegionStore.Bounds blueprintBounds(BlueprintDocument document) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (var block : document.blocks()) {
            var p = document.origin().add(block.position());
            minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y()); minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x()); maxY = Math.max(maxY, p.y()); maxZ = Math.max(maxZ, p.z());
        }
        return BuildRegionStore.bounds(new Position(minX, minY, minZ), new Position(maxX, maxY, maxZ));
    }
    public static Position suggestedOrigin(BlueprintDocument document, Position player, int directionX, int directionZ) {
        if (Math.abs(directionX) + Math.abs(directionZ) != 1) throw new IllegalArgumentException("BUILD_PROPOSAL_DIRECTION");
        var bounds = blueprintBounds(document); var relativeMin = bounds.minimum().subtract(document.origin());
        int width = bounds.maximum().x() - bounds.minimum().x() + 1, depth = bounds.maximum().z() - bounds.minimum().z() + 1;
        int x = Math.addExact(player.x(), directionX > 0 ? 4 : directionX < 0 ? -3 - width : -width / 2);
        int z = Math.addExact(player.z(), directionZ > 0 ? 4 : directionZ < 0 ? -3 - depth : -depth / 2);
        return new Position(x, Math.subtractExact(player.y(), 1), z).subtract(relativeMin);
    }
    private ServerPlayer player(Actor actor) {
        if (!server.isSameThread()) throw new IllegalStateException("SERVER_THREAD_REQUIRED");
        var player = server.getPlayerList().getPlayer(UUID.fromString(actor.owner())); if (player == null) throw new IllegalStateException("PLAYER_OFFLINE");
        actor.requireAccess(player.getUUID().toString()); return player;
    }
    private static void requirePosition(ServerPlayer player, BlockPos pos) {
        if (player.serverLevel().isOutsideBuildHeight(pos) || !player.serverLevel().getWorldBorder().isWithinBounds(pos)) throw new IllegalArgumentException("BUILD_REGION_WORLD_BOUNDS");
        if (!player.serverLevel().hasChunkAt(pos)) throw new IllegalStateException("BUILD_REGION_UNLOADED");
    }
    private static Position position(BlockPos pos) { return new Position(pos.getX(), pos.getY(), pos.getZ()); }
    private static BlockPos nativePos(Position pos) { return new BlockPos(pos.x(), pos.y(), pos.z()); }
    private static Position position(JsonObject point) {
        if (point == null || !point.keySet().equals(Set.of("x", "y", "z"))) throw new IllegalArgumentException("BUILD_REGION_POINT");
        return new Position(point.get("x").getAsBigDecimal().intValueExact(), point.get("y").getAsBigDecimal().intValueExact(), point.get("z").getAsBigDecimal().intValueExact());
    }
}
