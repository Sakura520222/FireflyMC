package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 传送到坐标的函数工具
 */
public class TeleportPositionFunctionTool implements AIFunctionTool {

    @Override
    public String getName() {
        return "teleport_position";
    }

    @Override
    public String getDescription() {
        return "将玩家传送到指定坐标。支持跨维度传送。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        // x 参数
        JsonObject xParam = new JsonObject();
        xParam.addProperty("type", "number");
        xParam.addProperty("description", "X坐标");
        properties.add("x", xParam);

        // y 参数
        JsonObject yParam = new JsonObject();
        yParam.addProperty("type", "number");
        yParam.addProperty("description", "Y坐标");
        properties.add("y", yParam);

        // z 参数
        JsonObject zParam = new JsonObject();
        zParam.addProperty("type", "number");
        zParam.addProperty("description", "Z坐标");
        properties.add("z", zParam);

        // dimension 参数
        JsonObject dimensionParam = new JsonObject();
        dimensionParam.addProperty("type", "string");
        dimensionParam.addProperty("description", "维度ID，如 'minecraft:overworld'、'minecraft:the_nether'、'minecraft:the_end'");
        JsonArray enumValues = new JsonArray();
        enumValues.add("minecraft:overworld");
        enumValues.add("minecraft:the_nether");
        enumValues.add("minecraft:the_end");
        dimensionParam.add("enum", enumValues);
        properties.add("dimension", dimensionParam);

        // targetPlayer 参数
        JsonObject targetPlayerParam = new JsonObject();
        targetPlayerParam.addProperty("type", "string");
        targetPlayerParam.addProperty("description", "被传送的玩家名称，默认为执行者");
        properties.add("targetPlayer", targetPlayerParam);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("x");
        required.add("y");
        required.add("z");
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
        if (!arguments.has("x") || !arguments.has("y") || !arguments.has("z")) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "缺少必需参数: x, y, z"
            );
        }

        // 验证坐标参数类型
        var xElement = arguments.get("x");
        var yElement = arguments.get("y");
        var zElement = arguments.get("z");

        if (!xElement.isJsonPrimitive() || !xElement.getAsJsonPrimitive().isNumber()) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "x 参数必须是数字"
            );
        }
        if (!yElement.isJsonPrimitive() || !yElement.getAsJsonPrimitive().isNumber()) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "y 参数必须是数字"
            );
        }
        if (!zElement.isJsonPrimitive() || !zElement.getAsJsonPrimitive().isNumber()) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "z 参数必须是数字"
            );
        }

        double x = xElement.getAsDouble();
        double y = yElement.getAsDouble();
        double z = zElement.getAsDouble();

        // 确定目标玩家
        ServerPlayer targetPlayer = player;
        if (arguments.has("targetPlayer") && !arguments.get("targetPlayer").isJsonNull()) {
            var targetPlayerElement = arguments.get("targetPlayer");
            if (!targetPlayerElement.isJsonPrimitive() || !targetPlayerElement.getAsJsonPrimitive().isString()) {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                        "targetPlayer 参数必须是字符串"
                );
            }
            String targetName = targetPlayerElement.getAsString();
            targetPlayer = server.getPlayerList().getPlayerByName(targetName);
            if (targetPlayer == null) {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.EXECUTION_FAILED,
                        "玩家 " + targetName + " 不在线"
                );
            }
        }

        // 确定目标维度
        ServerLevel targetLevel = targetPlayer.serverLevel();
        if (arguments.has("dimension") && !arguments.get("dimension").isJsonNull()) {
            var dimensionElement = arguments.get("dimension");
            if (!dimensionElement.isJsonPrimitive() || !dimensionElement.getAsJsonPrimitive().isString()) {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                        "dimension 参数必须是字符串"
                );
            }
            String dimensionStr = dimensionElement.getAsString();
            ResourceLocation dimensionId = ResourceLocation.tryParse(dimensionStr);
            if (dimensionId == null) {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                        "无效的维度ID: " + dimensionStr
                );
            }

            // 查找维度
            ServerLevel foundLevel = null;
            for (ServerLevel level : server.getAllLevels()) {
                if (level.dimension().location().equals(dimensionId)) {
                    foundLevel = level;
                    break;
                }
            }

            if (foundLevel == null) {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.EXECUTION_FAILED,
                        "维度不存在: " + dimensionStr
                );
            }
            targetLevel = foundLevel;
        }

        // 执行传送
        String playerName = targetPlayer.getGameProfile().getName();
        float yaw = targetPlayer.getYRot();
        float pitch = targetPlayer.getXRot();

        targetPlayer.teleportTo(targetLevel, x, y, z, yaw, pitch);

        String dimensionName = targetLevel.dimension().location().toString();
        return FunctionCallResult.success(
                String.format("已将 %s 传送到 (%.1f, %.1f, %.1f) %s",
                        playerName, x, y, z, dimensionName)
        );
    }
}
