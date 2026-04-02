package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
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
        // 检查前置条件
        FunctionCallResult checkResult = FunctionToolHelper.checkPreconditions(player, this);
        if (checkResult != null) {
            return checkResult;
        }

        var server = player.getServer();

        // 解析必需参数
        var playerNameResult = FunctionToolHelper.getRequiredString(arguments, "playerName");
        if (playerNameResult.hasError()) {
            return playerNameResult.error();
        }
        String targetName = playerNameResult.value();

        // 检查是否尝试踢出自己
        if (targetName.equals(player.getGameProfile().getName())) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "不能踢出自己"
            );
        }

        String reason = FunctionToolHelper.getOptionalString(arguments, "reason", DEFAULT_REASON);

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

    @Override
    public FunctionCallResult execute(MinecraftServer server, JsonObject arguments) {
        var targetResult = FunctionToolHelper.getRequiredTargetPlayer(server, arguments, "playerName");
        if (targetResult.hasError()) return targetResult.error();

        String targetName = targetResult.player().getGameProfile().getName();
        String reason = FunctionToolHelper.getOptionalString(arguments, "reason", DEFAULT_REASON);

        targetResult.player().connection.disconnect(Component.literal(reason));
        return FunctionCallResult.success("已踢出玩家 " + targetName + "，原因: " + reason);
    }
}
