package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 召唤玩家的函数工具
 */
public class SummonPlayerFunctionTool implements AIFunctionTool {

    @Override
    public String getName() {
        return "summon_player";
    }

    @Override
    public String getDescription() {
        return "将其他玩家召唤到执行者的位置。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        // playerName 参数
        JsonObject playerNameParam = new JsonObject();
        playerNameParam.addProperty("type", "string");
        playerNameParam.addProperty("description", "被召唤的玩家名称");
        properties.add("playerName", playerNameParam);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("playerName");
        schema.add("required", required);

        return schema;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 3;  // 3级OP权限
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
        if (!arguments.has("playerName")) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "缺少必需参数: playerName"
            );
        }

        // 验证playerName参数类型
        FunctionCallResult validationResult = FunctionToolHelper.validateStringType(
                arguments.get("playerName"), "playerName"
        );
        if (validationResult != null) {
            return validationResult;
        }

        String targetName = arguments.get("playerName").getAsString();

        // 查找目标玩家
        ServerPlayer targetPlayer = server.getPlayerList().getPlayerByName(targetName);
        if (targetPlayer == null) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "玩家 " + targetName + " 不在线"
            );
        }

        // 执行召唤
        ServerLevel playerLevel = player.serverLevel();
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYRot();
        float pitch = player.getXRot();

        targetPlayer.teleportTo(playerLevel, x, y, z, yaw, pitch);

        String dimensionName = playerLevel.dimension().location().toString();
        return FunctionCallResult.success(
                String.format("已将 %s 召唤到你的位置 %s (%.1f, %.1f, %.1f)",
                        targetName, dimensionName, x, y, z)
        );
    }
}
