package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 传送到玩家的函数工具
 */
public class TeleportPlayerFunctionTool implements AIFunctionTool {

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

        // targetPlayer 参数（被传送的玩家）
        JsonObject targetPlayerParam = new JsonObject();
        targetPlayerParam.addProperty("type", "string");
        targetPlayerParam.addProperty("description", "被传送的玩家名称，默认为执行者");
        properties.add("targetPlayer", targetPlayerParam);

        // destinationPlayer 参数（目标玩家）
        JsonObject destinationPlayerParam = new JsonObject();
        destinationPlayerParam.addProperty("type", "string");
        destinationPlayerParam.addProperty("description", "目标玩家名称（传送到该玩家位置）");
        properties.add("destinationPlayer", destinationPlayerParam);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("destinationPlayer");
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

        // 解析被传送的玩家（默认为执行者）
        var targetResult = FunctionToolHelper.getOptionalTargetPlayer(server, arguments, "targetPlayer", player);
        if (targetResult.hasError()) return targetResult.error();

        // 解析目的地玩家
        var destResult = FunctionToolHelper.getRequiredTargetPlayer(server, arguments, "destinationPlayer");
        if (destResult.hasError()) return destResult.error();

        return teleportToPlayer(targetResult.player(), destResult.player());
    }

    @Override
    public FunctionCallResult execute(MinecraftServer server, JsonObject arguments) {
        if (!arguments.has("targetPlayer")) {
            return FunctionCallResult.failure(FunctionCallResult.ErrorType.INVALID_ARGUMENT, "从控制台执行必须指定 targetPlayer");
        }

        var targetResult = FunctionToolHelper.getRequiredTargetPlayer(server, arguments, "targetPlayer");
        if (targetResult.hasError()) return targetResult.error();

        var destResult = FunctionToolHelper.getRequiredTargetPlayer(server, arguments, "destinationPlayer");
        if (destResult.hasError()) return destResult.error();

        return teleportToPlayer(targetResult.player(), destResult.player());
    }

    /**
     * 共享的传送逻辑
     */
    private FunctionCallResult teleportToPlayer(ServerPlayer targetPlayer, ServerPlayer destPlayer) {
        ServerLevel destLevel = destPlayer.serverLevel();
        double x = destPlayer.getX();
        double y = destPlayer.getY();
        double z = destPlayer.getZ();
        float yaw = destPlayer.getYRot();
        float pitch = destPlayer.getXRot();

        targetPlayer.teleportTo(destLevel, x, y, z, yaw, pitch);

        String targetName = targetPlayer.getGameProfile().getName();
        String destName = destPlayer.getGameProfile().getName();
        String dimensionName = destLevel.dimension().location().toString();
        return FunctionCallResult.success(
                String.format("已将 %s 传送到 %s 的位置 %s", targetName, destName, dimensionName));
    }
}
