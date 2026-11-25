package firefly520.fireflymc.ai.material;

import com.google.gson.*;
import firefly520.fireflymc.ai.storage.AiJson;
import firefly520.fireflymc.ai.tools.*;
import java.util.*;

/** Only public IDs, exact quantities and component digests leave the server's private fluid codec. */
public final class FluidInventoryTools {
    private FluidInventoryTools() {}
    public static void register(ToolRegistry tools, FluidInventoryService service) {
        var schema = AiJson.object("{\"type\":\"object\",\"properties\":{\"snapshotId\":{\"type\":\"string\"},\"offset\":{\"type\":\"integer\",\"minimum\":0}},\"additionalProperties\":false}", 1024, 8);
        tools.register(new DefinedTool(new AgentTool.Descriptor("fluid_inventory",
                "盘点本人在工作台单独授权的原料流体仓库。数量为 mB，按后端去重并区分数据组件；实际存量不代表已预留或对应加工适配器已就绪。物品来源授权不会允许取液。",
                schema, AgentTool.Risk.READ_ONLY, "materials.own", 0, AgentTool.ExecutionThread.IO, 30000, true, ""), (context, args) -> {
            context.actor().requireAccess(context.workspace().owner());
            var snapshot = context.control().await(args.has("snapshotId")
                    ? service.snapshotAsync(context.actor(), UUID.fromString(args.get("snapshotId").getAsString()))
                    : service.scan(context.actor()), 30000);
            return new AgentTool.Result(true, "", "已盘点明确授权的原料流体。", view(snapshot, args.has("offset") ? args.get("offset").getAsInt() : 0),
                    "server-fluid-capability", "", false);
        }));
    }
    public static JsonObject view(FluidInventoryService.Snapshot snapshot, int offset) {
        var rows = new ArrayList<JsonObject>();
        for (var source : snapshot.sources()) for (var kind : source.snapshot().kinds().values()) {
            var row = new JsonObject();
            row.addProperty("id", AiJson.object(kind.unitJson(), 262144, 32).get("id").getAsString());
            row.addProperty("physicalMb", source.snapshot().amount(kind.fingerprint()));
            row.addProperty("componentsDigest", kind.fingerprint());
            row.addProperty("sourceId", source.grantId().toString()); row.addProperty("sourceRevision", source.revision());
            row.addProperty("backend", source.backend()); rows.add(row);
        }
        if (offset < 0 || offset > rows.size()) throw new IllegalArgumentException("FLUID_OFFSET_INVALID");
        int end = Math.min(offset + 24, rows.size()); var data = new JsonObject();
        data.addProperty("snapshotId", snapshot.id().toString()); data.addProperty("capturedAt", snapshot.capturedAt());
        data.addProperty("total", rows.size()); data.addProperty("nextOffset", end);
        data.add("fluids", AiJson.GSON.toJsonTree(rows.subList(offset, end)));
        data.add("refusals", AiJson.GSON.toJsonTree(snapshot.refusals().stream().map(row -> Map.of("sourceId", row.sourceId(), "reason", row.code())).toList()));
        data.addProperty("text", "只显示单独授权的真实流体存量（mB）。未扣液、预留或加工；空罐仍可在来源列表中管理。");
        return data;
    }
}
