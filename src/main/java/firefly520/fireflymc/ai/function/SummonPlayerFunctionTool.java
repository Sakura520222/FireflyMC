package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 召唤玩家的函数工具。
 * <p>
 * 将指定玩家召唤到执行者位置；控制台触发时召唤到主世界出生点。
 */
public class SummonPlayerFunctionTool implements AIFunctionTool {

    private static final String TARGET_PARAM = "target_player";

    @Override
    public String getName() {
        return "summon_player";
    }

    @Override
    public String getDescription() {
        return "将指定玩家召唤到执行者位置（控制台触发时召唤到主世界出生点）。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject targetParam = new JsonObject();
        targetParam.addProperty("type", "string");
        targetParam.addProperty("description", "被召唤的玩家名称");
        properties.add(TARGET_PARAM, targetParam);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add(TARGET_PARAM);
        schema.add("required", required);

        return schema;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 3;
    }

    @Override
    public FunctionCallResult execute(ToolContext ctx, JsonObject arguments) {
        var targetResult = FunctionToolHelper.getRequiredTargetPlayer(ctx, arguments, TARGET_PARAM);
        if (targetResult.hasError()) {
            return targetResult.error();
        }
        ServerPlayer target = targetResult.player();
        String targetName = target.getGameProfile().getName();

        ServerLevel destLevel;
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        String locationDesc;

        if (ctx.isConsole()) {
            // 控制台无"执行者位置"，使用主世界出生点
            destLevel = ctx.server().overworld();
            var spawnPos = destLevel.getSharedSpawnPos();
            x = spawnPos.getX() + 0.5;
            y = destLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawnPos.getX(), spawnPos.getZ());
            z = spawnPos.getZ() + 0.5;
            yaw = 0;
            pitch = 0;
            locationDesc = "主世界出生点";
        } else {
            ServerPlayer executor = ctx.player();
            destLevel = executor.serverLevel();
            x = executor.getX();
            y = executor.getY();
            z = executor.getZ();
            yaw = executor.getYRot();
            pitch = executor.getXRot();
            locationDesc = "执行者位置 " + destLevel.dimension().location();
        }

        target.teleportTo(destLevel, x, y, z, yaw, pitch);

        return FunctionCallResult.success(
                String.format("已将 %s 召唤到%s (%.1f, %.1f, %.1f)", targetName, locationDesc, x, y, z));
    }
}
