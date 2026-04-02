package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import net.minecraft.server.MinecraftServer;
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
        FunctionCallResult checkResult = FunctionToolHelper.checkPreconditions(player, this);
        if (checkResult != null) return checkResult;

        var server = player.getServer();

        // 解析坐标
        var xResult = FunctionToolHelper.getRequiredDouble(arguments, "x");
        if (xResult.hasError()) return xResult.error();
        var yResult = FunctionToolHelper.getRequiredDouble(arguments, "y");
        if (yResult.hasError()) return yResult.error();
        var zResult = FunctionToolHelper.getRequiredDouble(arguments, "z");
        if (zResult.hasError()) return zResult.error();

        // 确定目标玩家
        var targetResult = FunctionToolHelper.getOptionalTargetPlayer(server, arguments, "targetPlayer", player);
        if (targetResult.hasError()) return targetResult.error();
        ServerPlayer targetPlayer = targetResult.player();

        // 确定目标维度
        var levelResult = resolveTargetLevel(server, targetPlayer, arguments);
        if (levelResult.hasError()) return levelResult.error();

        return teleportPlayer(targetPlayer, levelResult.level(), xResult.value(), yResult.value(), zResult.value());
    }

    @Override
    public FunctionCallResult execute(MinecraftServer server, JsonObject arguments) {
        if (!arguments.has("targetPlayer")) {
            return FunctionCallResult.failure(FunctionCallResult.ErrorType.INVALID_ARGUMENT, "从控制台执行必须指定 targetPlayer");
        }

        var xResult = FunctionToolHelper.getRequiredDouble(arguments, "x");
        if (xResult.hasError()) return xResult.error();
        var yResult = FunctionToolHelper.getRequiredDouble(arguments, "y");
        if (yResult.hasError()) return yResult.error();
        var zResult = FunctionToolHelper.getRequiredDouble(arguments, "z");
        if (zResult.hasError()) return zResult.error();

        var targetResult = FunctionToolHelper.getRequiredTargetPlayer(server, arguments, "targetPlayer");
        if (targetResult.hasError()) return targetResult.error();

        var levelResult = resolveTargetLevel(server, targetResult.player(), arguments);
        if (levelResult.hasError()) return levelResult.error();

        return teleportPlayer(targetResult.player(), levelResult.level(), xResult.value(), yResult.value(), zResult.value());
    }

    private FunctionToolHelper.LevelResult resolveTargetLevel(MinecraftServer server, ServerPlayer targetPlayer, JsonObject arguments) {
        String dimensionStr = FunctionToolHelper.getOptionalString(arguments, "dimension", null);
        if (dimensionStr == null || dimensionStr.isBlank()) {
            return new FunctionToolHelper.LevelResult(targetPlayer.serverLevel(), null);
        }
        return FunctionToolHelper.resolveDimension(server, dimensionStr);
    }

    private FunctionCallResult teleportPlayer(ServerPlayer targetPlayer, ServerLevel targetLevel, double x, double y, double z) {
        String playerName = targetPlayer.getGameProfile().getName();
        float yaw = targetPlayer.getYRot();
        float pitch = targetPlayer.getXRot();
        targetPlayer.teleportTo(targetLevel, x, y, z, yaw, pitch);

        String dimensionName = targetLevel.dimension().location().toString();
        return FunctionCallResult.success(
                String.format("已将 %s 传送到 (%.1f, %.1f, %.1f) %s", playerName, x, y, z, dimensionName));
    }
}
