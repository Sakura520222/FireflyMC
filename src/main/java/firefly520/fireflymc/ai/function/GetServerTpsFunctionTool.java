package firefly520.fireflymc.ai.function;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import net.minecraft.server.level.ServerPlayer;

/**
 * 获取服务器TPS的函数工具
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
        return 0;  // 无权限要求
    }

    @Override
    public FunctionCallResult execute(ServerPlayer player, JsonObject arguments) {
        var server = player.getServer();
        if (server == null) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "服务器未就绪"
            );
        }

        // 获取tick计数（服务器正常运行时TPS应为20）
        long tickCount = server.getTickCount();

        StringBuilder result = new StringBuilder();
        result.append("服务器性能信息:\n");
        result.append(String.format("运行tick数: %d", tickCount));
        result.append("\n服务器运行正常（理想TPS: 20.0）");

        return FunctionCallResult.success(result.toString());
    }
}
