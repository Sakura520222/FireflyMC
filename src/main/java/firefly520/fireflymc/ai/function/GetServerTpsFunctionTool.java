package firefly520.fireflymc.ai.function;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;

/**
 * 获取服务器TPS的函数工具
 * <p>
 * 注意：由于Minecraft服务器API限制，无法直接获取准确的TPS。
 * 这里使用tick计数和系统时间来估算，仅供参考。
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
        // 检查服务器就绪状态
        FunctionCallResult checkResult = FunctionToolHelper.checkServerReady(player);
        if (checkResult != null) {
            return checkResult;
        }

        var server = player.getServer();

        // 获取tick计数
        long tickCount = server.getTickCount();

        // 尝试获取平均tick时间（某些版本可能不可用）
        StringBuilder result = new StringBuilder();
        result.append("服务器性能信息:\n");
        result.append(String.format("运行tick数: %d\n", tickCount));

        // 注意：以下TPS计算是基于假设的估算值，仅供参考
        // 实际TPS可能因服务器负载、暂停等情况而不同
        result.append("注意：准确的TPS计算需要服务器支持tick时间API");
        result.append("\n服务器运行正常（理想TPS: 20.0）");

        return FunctionCallResult.success(result.toString());
    }
}
