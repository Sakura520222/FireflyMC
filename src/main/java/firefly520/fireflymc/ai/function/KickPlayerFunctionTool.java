package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 踢出玩家的函数工具。
 */
public class KickPlayerFunctionTool implements AIFunctionTool {

    private static final String TARGET_PARAM = "target_player";
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

        JsonObject targetParam = new JsonObject();
        targetParam.addProperty("type", "string");
        targetParam.addProperty("description", "要踢出的玩家名称");
        properties.add(TARGET_PARAM, targetParam);

        JsonObject reasonParam = new JsonObject();
        reasonParam.addProperty("type", "string");
        reasonParam.addProperty("description", "踢出原因");
        reasonParam.addProperty("default", DEFAULT_REASON);
        properties.add("reason", reasonParam);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add(TARGET_PARAM);
        schema.add("required", required);

        return schema;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public FunctionCallResult execute(ToolContext ctx, JsonObject arguments) {
        var targetResult = FunctionToolHelper.getRequiredTargetPlayer(ctx, arguments, TARGET_PARAM);
        if (targetResult.hasError()) {
            return targetResult.error();
        }
        ServerPlayer target = targetResult.player();

        // 玩家触发时不允许踢出自己
        if (!ctx.isConsole() && target.getUUID().equals(ctx.player().getUUID())) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "不能踢出自己");
        }

        String reason = FunctionToolHelper.getOptionalString(arguments, "reason", DEFAULT_REASON);
        String targetName = target.getGameProfile().getName();
        target.connection.disconnect(Component.literal(reason));

        return FunctionCallResult.success("已踢出玩家 " + targetName + "，原因: " + reason);
    }
}
