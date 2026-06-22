package firefly520.fireflymc.ai.function;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;

/**
 * 获取服务器运行时间的函数工具。
 */
public class GetServerUptimeFunctionTool implements AIFunctionTool {

    @Override
    public String getName() {
        return "get_server_uptime";
    }

    @Override
    public String getDescription() {
        return "获取服务器运行时间。";
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
        // 基于 tick 计数计算运行时间（每秒 20 tick）
        long tickCount = ctx.server().getTickCount();
        long totalSeconds = tickCount / 20;

        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder result = new StringBuilder();
        result.append("服务器已运行 ");
        if (days > 0) {
            result.append(days).append("天 ");
        }
        if (hours > 0 || days > 0) {
            result.append(hours).append("小时 ");
        }
        result.append(minutes).append("分钟 ");
        result.append(seconds).append("秒");

        return FunctionCallResult.success(result.toString());
    }
}
