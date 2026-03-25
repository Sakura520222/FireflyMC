package firefly520.fireflymc.ai.function;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.FunctionToolRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * 获取玩家详细信息的函数工具
 */
public class GetPlayerInfoFunctionTool implements AIFunctionTool {

    @Override
    public String getName() {
        return "get_player_info";
    }

    @Override
    public String getDescription() {
        return "获取玩家的详细信息，包括位置、血量、饥饿值、经验等级、游戏模式等。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        // playerName 参数
        JsonObject playerName = new JsonObject();
        playerName.addProperty("type", "string");
        playerName.addProperty("description", "玩家名称，不填则查询执行者");
        properties.add("playerName", playerName);

        schema.add("properties", properties);

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

        // 确定目标玩家
        ServerPlayer targetPlayer = player;
        if (arguments.has("playerName") && !arguments.get("playerName").getAsString().isBlank()) {
            String targetName = arguments.get("playerName").getAsString();
            targetPlayer = server.getPlayerList().getPlayerByName(targetName);
            if (targetPlayer == null) {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.EXECUTION_FAILED,
                        "玩家 " + targetName + " 不在线"
                );
            }
        }

        // 获取玩家信息
        String name = targetPlayer.getGameProfile().getName();
        BlockPos pos = targetPlayer.blockPosition();
        String dimension = targetPlayer.serverLevel().dimension().location().toString();
        float health = targetPlayer.getHealth();
        float maxHealth = targetPlayer.getMaxHealth();
        int foodLevel = targetPlayer.getFoodData().getFoodLevel();
        int xpLevel = targetPlayer.experienceLevel;
        float xpProgress = targetPlayer.experienceProgress;
        String gameMode = targetPlayer.gameMode.getGameModeForPlayer().getName();
        boolean isFlying = targetPlayer.getAbilities().flying;
        int ping = targetPlayer.connection.latency();

        StringBuilder result = new StringBuilder();
        result.append(String.format("玩家 %s 的信息:\n", name));
        result.append(String.format("位置: (%d, %d, %d) %s\n", pos.getX(), pos.getY(), pos.getZ(), dimension));
        result.append(String.format("血量: %.1f/%.1f\n", health, maxHealth));
        result.append(String.format("饥饿值: %d/20\n", foodLevel));
        result.append(String.format("经验: 等级%d (%.1f%%)\n", xpLevel, xpProgress * 100));
        result.append(String.format("游戏模式: %s\n", gameMode));
        result.append(String.format("状态: %s\n", isFlying ? "飞行中" : "行走"));
        result.append(String.format("延迟: %dms", ping));

        return FunctionCallResult.success(result.toString());
    }
}
