package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 将一个玩家传送到另一个玩家位置的函数工具。
 */
public class TeleportPlayerFunctionTool implements AIFunctionTool {

    private static final String TARGET_PARAM = "target_player";
    private static final String DEST_PARAM = "destination_player";

    @Override
    public String getName() {
        return "teleport_player";
    }

    @Override
    public String getDescription() {
        return "将玩家传送到另一个玩家的位置。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject targetParam = new JsonObject();
        targetParam.addProperty("type", "string");
        targetParam.addProperty("description", "被传送的玩家名称，默认为执行者（控制台调用时必填）");
        properties.add(TARGET_PARAM, targetParam);

        JsonObject destParam = new JsonObject();
        destParam.addProperty("type", "string");
        destParam.addProperty("description", "目标玩家名称（被传送者将到达该玩家位置）");
        properties.add(DEST_PARAM, destParam);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add(DEST_PARAM);
        schema.add("required", required);

        return schema;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 3;
    }

    @Override
    public FunctionCallResult execute(ToolContext ctx, JsonObject arguments) {
        var targetResult = FunctionToolHelper.getOptionalTargetPlayer(ctx, arguments, TARGET_PARAM);
        if (targetResult.hasError()) return targetResult.error();

        var destResult = FunctionToolHelper.getRequiredTargetPlayer(ctx, arguments, DEST_PARAM);
        if (destResult.hasError()) return destResult.error();

        return teleportToPlayer(targetResult.player(), destResult.player());
    }

    private FunctionCallResult teleportToPlayer(ServerPlayer target, ServerPlayer dest) {
        ServerLevel destLevel = dest.serverLevel();
        double x = dest.getX();
        double y = dest.getY();
        double z = dest.getZ();
        float yaw = dest.getYRot();
        float pitch = dest.getXRot();

        target.teleportTo(destLevel, x, y, z, yaw, pitch);

        String targetName = target.getGameProfile().getName();
        String destName = dest.getGameProfile().getName();
        String dimensionName = destLevel.dimension().location().toString();
        return FunctionCallResult.success(
                String.format("已将 %s 传送到 %s 的位置 %s", targetName, destName, dimensionName));
    }
}
