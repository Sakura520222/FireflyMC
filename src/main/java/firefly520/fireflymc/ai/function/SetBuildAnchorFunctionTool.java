package firefly520.fireflymc.ai.function;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.BuildAnchorManager;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * 设置 AI 自动建造锚点的函数工具。
 * <p>
 * 当玩家移动到新位置并明确要求“从这里重新建/把锚点改到这里”时，AI 应调用本工具。
 * 后续 fill_blocks / place_block / get_blocks 都以该锚点为相对坐标原点。
 */
public class SetBuildAnchorFunctionTool implements AIFunctionTool {

    private static final int MIN_OFFSET = -32;
    private static final int MAX_OFFSET = 32;
    private static final String TARGET_PARAM = "target_player";

    @Override
    public String getName() {
        return "set_build_anchor";
    }

    @Override
    public String getDescription() {
        return "设置或重置 AI 自动建造锚点。后续 fill_blocks/place_block/get_blocks 的相对坐标都以此锚点为原点。玩家移动后继续修补旧建筑时不要调用；玩家明确要求换位置/从这里重新建时调用。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject targetParam = new JsonObject();
        targetParam.addProperty("type", "string");
        targetParam.addProperty("description", "目标玩家，默认执行者（控制台必填）。锚点会设置在该玩家当前位置加偏移处");
        properties.add(TARGET_PARAM, targetParam);

        properties.add("x", offsetParam("X 偏移"));
        properties.add("y", offsetParam("Y 偏移"));
        properties.add("z", offsetParam("Z 偏移"));

        schema.add("properties", properties);
        return schema;
    }

    private JsonObject offsetParam(String desc) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "integer");
        p.addProperty("description", desc + "（相对目标玩家当前位置，默认 0）");
        p.addProperty("default", 0);
        p.addProperty("minimum", MIN_OFFSET);
        p.addProperty("maximum", MAX_OFFSET);
        return p;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public FunctionCallResult execute(ToolContext ctx, JsonObject arguments) {
        var targetResult = FunctionToolHelper.getOptionalTargetPlayer(ctx, arguments, TARGET_PARAM);
        if (targetResult.hasError()) {
            return targetResult.error();
        }
        ServerPlayer target = targetResult.player();

        int x = clamp(FunctionToolHelper.getOptionalInt(arguments, "x", 0));
        int y = clamp(FunctionToolHelper.getOptionalInt(arguments, "y", 0));
        int z = clamp(FunctionToolHelper.getOptionalInt(arguments, "z", 0));

        BlockPos pos = target.blockPosition().offset(x, y, z);
        var anchor = BuildAnchorManager.setTo(target, pos);
        return FunctionCallResult.success(String.format(
                "已将 %s 的建造锚点设置为 %s",
                target.getGameProfile().getName(), anchor.describe()));
    }

    private static int clamp(int value) {
        return Math.max(MIN_OFFSET, Math.min(MAX_OFFSET, value));
    }
}
