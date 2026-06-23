package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 传送到指定坐标的函数工具。
 */
public class TeleportPositionFunctionTool implements AIFunctionTool {

    private static final String TARGET_PARAM = "target_player";

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

        JsonObject xParam = new JsonObject();
        xParam.addProperty("type", "number");
        xParam.addProperty("description", "X坐标");
        properties.add("x", xParam);

        JsonObject yParam = new JsonObject();
        yParam.addProperty("type", "number");
        yParam.addProperty("description", "Y坐标");
        properties.add("y", yParam);

        JsonObject zParam = new JsonObject();
        zParam.addProperty("type", "number");
        zParam.addProperty("description", "Z坐标");
        properties.add("z", zParam);

        JsonObject dimensionParam = new JsonObject();
        dimensionParam.addProperty("type", "string");
        dimensionParam.addProperty("description", "维度ID，如 'minecraft:overworld'、'minecraft:the_nether'、'minecraft:the_end'");
        JsonArray enumValues = new JsonArray();
        enumValues.add("minecraft:overworld");
        enumValues.add("minecraft:the_nether");
        enumValues.add("minecraft:the_end");
        dimensionParam.add("enum", enumValues);
        properties.add("dimension", dimensionParam);

        JsonObject targetParam = new JsonObject();
        targetParam.addProperty("type", "string");
        targetParam.addProperty("description", "被传送的玩家名称，默认为执行者（控制台调用时必填）");
        properties.add(TARGET_PARAM, targetParam);

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
        return 3;
    }

    @Override
    public FunctionCallResult execute(ToolContext ctx, JsonObject arguments) {
        var xResult = FunctionToolHelper.getRequiredDouble(arguments, "x");
        if (xResult.hasError()) return xResult.error();
        var yResult = FunctionToolHelper.getRequiredDouble(arguments, "y");
        if (yResult.hasError()) return yResult.error();
        var zResult = FunctionToolHelper.getRequiredDouble(arguments, "z");
        if (zResult.hasError()) return zResult.error();

        var targetResult = FunctionToolHelper.getOptionalTargetPlayer(ctx, arguments, TARGET_PARAM);
        if (targetResult.hasError()) return targetResult.error();
        ServerPlayer target = targetResult.player();

        var levelResult = resolveTargetLevel(ctx.server(), target, arguments);
        if (levelResult.hasError()) return levelResult.error();

        return teleportPlayer(target, levelResult.level(), xResult.value(), yResult.value(), zResult.value());
    }

    private FunctionToolHelper.LevelResult resolveTargetLevel(MinecraftServer server, ServerPlayer target, JsonObject arguments) {
        String dimensionStr = FunctionToolHelper.getOptionalString(arguments, "dimension", null);
        if (dimensionStr == null || dimensionStr.isBlank()) {
            return new FunctionToolHelper.LevelResult(target.serverLevel(), null);
        }
        return FunctionToolHelper.resolveDimension(server, dimensionStr);
    }

    private FunctionCallResult teleportPlayer(ServerPlayer target, ServerLevel targetLevel, double x, double y, double z) {
        String playerName = target.getGameProfile().getName();
        float yaw = target.getYRot();
        float pitch = target.getXRot();
        target.teleportTo(targetLevel, x, y, z, yaw, pitch);

        String dimensionName = targetLevel.dimension().location().toString();
        return FunctionCallResult.success(
                String.format("已将 %s 传送到 (%.1f, %.1f, %.1f) %s", playerName, x, y, z, dimensionName));
    }
}
