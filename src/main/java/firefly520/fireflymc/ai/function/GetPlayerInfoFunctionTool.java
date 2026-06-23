package firefly520.fireflymc.ai.function;

import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/**
 * 获取玩家详细信息的函数工具。
 */
public class GetPlayerInfoFunctionTool implements AIFunctionTool {

    private static final String TARGET_PARAM = "target_player";

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

        JsonObject targetParam = new JsonObject();
        targetParam.addProperty("type", "string");
        targetParam.addProperty("description", "目标玩家名称，不填则查询执行者（控制台调用时必填）");
        properties.add(TARGET_PARAM, targetParam);

        schema.add("properties", properties);

        return schema;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public FunctionCallResult execute(ToolContext ctx, JsonObject arguments) {
        var targetResult = FunctionToolHelper.getOptionalTargetPlayer(ctx, arguments, TARGET_PARAM);
        if (targetResult.hasError()) {
            return targetResult.error();
        }
        return buildPlayerInfo(targetResult.player());
    }

    private FunctionCallResult buildPlayerInfo(ServerPlayer target) {
        String name = target.getGameProfile().getName();
        BlockPos pos = target.blockPosition();
        String dimension = target.serverLevel().dimension().location().toString();
        float health = target.getHealth();
        float maxHealth = target.getMaxHealth();
        int foodLevel = target.getFoodData().getFoodLevel();
        int xpLevel = target.experienceLevel;
        float xpProgress = target.experienceProgress;
        String gameMode = target.gameMode.getGameModeForPlayer().getName();
        boolean isFlying = target.getAbilities().flying;
        int ping = target.connection.latency();

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
