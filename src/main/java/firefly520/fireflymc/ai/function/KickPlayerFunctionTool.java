package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.FunctionToolRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 踢出玩家的函数工具
 */
public class KickPlayerFunctionTool implements AIFunctionTool {

    private static final String DEFAULT_REASON = "被管理员踢出";

    @Override
    public String getName() {
        return "kick_player";
    }

    @Override
    public String getDescription() {
        return "踢出指定玩家。需要4级OP权限。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        // playerName 参数
        JsonObject playerNameParam = new JsonObject();
        playerNameParam.addProperty("type", "string");
        playerNameParam.addProperty("description", "要踢出的玩家名称");
        properties.add("playerName", playerNameParam);

        // reason 参数
        JsonObject reasonParam = new JsonObject();
        reasonParam.addProperty("type", "string");
        reasonParam.addProperty("description", "踢出原因");
        reasonParam.addProperty("default", DEFAULT_REASON);
        properties.add("reason", reasonParam);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("playerName");
        schema.add("required", required);

        return schema;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;  // 4级OP权限
    }

    @Override
    public FunctionCallResult execute(ServerPlayer player, JsonObject arguments) {
        // 权限验证
        if (!FunctionToolRegistry.hasPermissionForTool(player, getName())) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.PERMISSION_DENIED,
                    "权限不足：需要4级OP权限"
            );
        }

        var server = player.getServer();
        if (server == null) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "服务器未就绪"
            );
        }

        // 解析参数
        if (!arguments.has("playerName")) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "缺少必需参数: playerName"
            );
        }

        String targetName = arguments.get("playerName").getAsString();

        // 检查是否尝试踢出自己
        if (targetName.equals(player.getGameProfile().getName())) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "不能踢出自己"
            );
        }

        String reason = arguments.has("reason") && !arguments.get("reason").isJsonNull()
                ? arguments.get("reason").getAsString()
                : DEFAULT_REASON;

        // 查找目标玩家
        ServerPlayer targetPlayer = server.getPlayerList().getPlayerByName(targetName);
        if (targetPlayer == null) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "玩家 " + targetName + " 不在线"
            );
        }

        // 踢出玩家
        targetPlayer.connection.disconnect(Component.literal(reason));

        return FunctionCallResult.success("已踢出玩家 " + targetName + "，原因: " + reason);
    }
}
