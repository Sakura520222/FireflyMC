package firefly520.fireflymc.ai.function;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.BuildAnchorManager;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.TreeMap;

/**
 * 查看玩家周围区域方块的函数工具（只读）。
 * <p>
 * 返回每个位置的方块及其状态（含朝向），用于建造时检查结果、发现朝向错误，
 * 形成「建造 → 查看 → 修正」的闭环。与 {@link PlaceBlockFunctionTool} 配合实现精细修补。
 */
public class GetBlocksFunctionTool implements AIFunctionTool {

    private static final int MIN_OFFSET = -32;
    private static final int MAX_OFFSET = 32;
    /** 单次查看体积上限，避免返回信息过大 */
    private static final int MAX_VOLUME = 100;

    private static final String TARGET_PARAM = "target_player";

    @Override
    public String getName() {
        return "get_blocks";
    }

    @Override
    public String getDescription() {
        return "查看玩家周围一定区域的方块及其状态（含朝向），用于建造时检查结果、发现错误以便修正。建议建造后查看验证。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject targetParam = new JsonObject();
        targetParam.addProperty("type", "string");
        targetParam.addProperty("description", "坐标参考玩家，默认执行者（控制台必填）");
        properties.add(TARGET_PARAM, targetParam);

        properties.add("x1", offsetParam("X 起点"));
        properties.add("y1", offsetParam("Y 起点"));
        properties.add("z1", offsetParam("Z 起点"));
        properties.add("x2", offsetParam("X 终点"));
        properties.add("y2", offsetParam("Y 终点"));
        properties.add("z2", offsetParam("Z 终点"));

        schema.add("properties", properties);
        return schema;
    }

    private JsonObject offsetParam(String desc) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "integer");
        p.addProperty("description", desc + "（相对玩家脚下，0=玩家位置）");
        p.addProperty("default", 0);
        p.addProperty("minimum", MIN_OFFSET);
        p.addProperty("maximum", MAX_OFFSET);
        return p;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public FunctionCallResult execute(ToolContext ctx, JsonObject arguments) {
        var targetResult = FunctionToolHelper.getOptionalTargetPlayer(ctx, arguments, TARGET_PARAM);
        if (targetResult.hasError()) {
            return targetResult.error();
        }
        ServerPlayer target = targetResult.player();

        int x1 = clamp(FunctionToolHelper.getOptionalInt(arguments, "x1", 0));
        int y1 = clamp(FunctionToolHelper.getOptionalInt(arguments, "y1", 0));
        int z1 = clamp(FunctionToolHelper.getOptionalInt(arguments, "z1", 0));
        int x2 = clamp(FunctionToolHelper.getOptionalInt(arguments, "x2", 0));
        int y2 = clamp(FunctionToolHelper.getOptionalInt(arguments, "y2", 0));
        int z2 = clamp(FunctionToolHelper.getOptionalInt(arguments, "z2", 0));

        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        int volume = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_VOLUME) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "区域过大: " + volume + " 个方块（上限 " + MAX_VOLUME + "），请缩小范围");
        }

        // 建造以玩家持久建造锚点为基准（玩家后续移动/追加需求不影响相对坐标对齐）
        var anchor = BuildAnchorManager.getOrCreate(target);
        var anchorLevel = anchor.level(ctx.server());
        if (anchorLevel.isEmpty()) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "建造锚点所在维度不存在: " + anchor.dimension().location());
        }
        BlockPos base = anchor.pos();
        var level = anchorLevel.get();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s 周围 (%d,%d,%d)-(%d,%d,%d) 方块：",
                target.getGameProfile().getName(), minX, minY, minZ, maxX, maxY, maxZ));

        Map<String, Integer> counts = new TreeMap<>();
        for (int dx = minX; dx <= maxX; dx++) {
            for (int dy = minY; dy <= maxY; dy++) {
                for (int dz = minZ; dz <= maxZ; dz++) {
                    BlockState state = level.getBlockState(base.offset(dx, dy, dz));
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    counts.merge(id.toString(), 1, Integer::sum);
                    sb.append(String.format("\n(%d,%d,%d) ", dx, dy, dz))
                            .append(PlaceBlockFunctionTool.describeBlock(state));
                }
            }
        }

        sb.append("\n统计: ");
        boolean first = true;
        for (var entry : counts.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getKey()).append(" x").append(entry.getValue());
            first = false;
        }

        return FunctionCallResult.success(sb.toString());
    }

    private static int clamp(int value) {
        return Math.max(MIN_OFFSET, Math.min(MAX_OFFSET, value));
    }
}
