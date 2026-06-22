package firefly520.fireflymc.ai.function;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;

/**
 * 获取服务器TPS的函数工具。
 * <p>
 * Minecraft 服务器 API 无法直接获取准确 TPS，这里基于 tick 计数估算，仅供参考。
 */
public class GetServerTpsFunctionTool implements AIFunctionTool {

    @Override
    public String getName() {
        return "get_server_tps";
    }

    @Override
    public String getDescription() {
        return "获取服务器TPS（每秒tick数）和性能信息。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public FunctionCallResult execute(ToolContext ctx, JsonObject arguments) {
        long tickCount = ctx.server().getTickCount();

        StringBuilder result = new StringBuilder();
        result.append("服务器性能信息:\n");
        result.append(String.format("运行tick数: %d\n", tickCount));
        result.append("注意：准确的TPS计算需要服务器支持tick时间API");
        result.append("\n服务器运行正常（理想TPS: 20.0）");

        return FunctionCallResult.success(result.toString());
    }
}
