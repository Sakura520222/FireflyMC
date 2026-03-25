package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.FunctionToolRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * 给予药水效果的函数工具
 */
public class GiveEffectFunctionTool implements AIFunctionTool {

    private static final int MIN_DURATION = 1;
    private static final int MAX_DURATION = 3600;
    private static final int DEFAULT_DURATION = 60;
    private static final int DEFAULT_AMPLIFIER = 0;
    private static final int MAX_AMPLIFIER = 255;

    @Override
    public String getName() {
        return "give_effect";
    }

    @Override
    public String getDescription() {
        return "给予玩家药水效果。支持常见效果如speed、strength、regeneration等。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        // effect 参数
        JsonObject effectParam = new JsonObject();
        effectParam.addProperty("type", "string");
        effectParam.addProperty("description", "效果ID，如 'speed'、'strength'、'regeneration'、'jump_boost'、'night_vision' 等");
        properties.add("effect", effectParam);

        // duration 参数
        JsonObject durationParam = new JsonObject();
        durationParam.addProperty("type", "integer");
        durationParam.addProperty("description", "持续时间(秒)");
        durationParam.addProperty("default", DEFAULT_DURATION);
        durationParam.addProperty("minimum", MIN_DURATION);
        durationParam.addProperty("maximum", MAX_DURATION);
        properties.add("duration", durationParam);

        // amplifier 参数
        JsonObject amplifierParam = new JsonObject();
        amplifierParam.addProperty("type", "integer");
        amplifierParam.addProperty("description", "效果强度(0为等级I，1为等级II，依此类推)");
        amplifierParam.addProperty("default", DEFAULT_AMPLIFIER);
        amplifierParam.addProperty("minimum", 0);
        amplifierParam.addProperty("maximum", MAX_AMPLIFIER);
        properties.add("amplifier", amplifierParam);

        // targetPlayer 参数
        JsonObject targetPlayerParam = new JsonObject();
        targetPlayerParam.addProperty("type", "string");
        targetPlayerParam.addProperty("description", "目标玩家名称，默认为执行者");
        properties.add("targetPlayer", targetPlayerParam);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("effect");
        schema.add("required", required);

        return schema;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;  // 4级OP权限
    }

    @Override
    public FunctionCallResult execute(ServerPlayer player, JsonObject arguments) {
        // 权限验证
        if (!FunctionToolRegistry.hasPermissionForTool(player, getName())) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.PERMISSION_DENIED,
                    "权限不足：需要4级OP权限"
            );
        }

        var server = player.getServer();
        if (server == null) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "服务器未就绪"
            );
        }

        // 解析必需参数
        if (!arguments.has("effect")) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "缺少必需参数: effect"
            );
        }

        String effectStr = arguments.get("effect").getAsString().toLowerCase();
        int duration = DEFAULT_DURATION;
        int amplifier = DEFAULT_AMPLIFIER;

        // 解析可选参数并添加类型验证
        try {
            if (arguments.has("duration")) {
                duration = arguments.get("duration").getAsInt();
            }
            if (arguments.has("amplifier")) {
                amplifier = arguments.get("amplifier").getAsInt();
            }
        } catch (IllegalStateException e) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "duration和amplifier参数必须是整数"
            );
        }

        // 验证范围
        duration = Math.max(MIN_DURATION, Math.min(MAX_DURATION, duration));
        amplifier = Math.max(0, Math.min(MAX_AMPLIFIER, amplifier));

        // 确定目标玩家
        ServerPlayer targetPlayer = player;
        String targetName = player.getGameProfile().getName();

        if (arguments.has("targetPlayer") && !arguments.get("targetPlayer").isJsonNull()) {
            targetName = arguments.get("targetPlayer").getAsString();
            targetPlayer = server.getPlayerList().getPlayerByName(targetName);
            if (targetPlayer == null) {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.EXECUTION_FAILED,
                        "玩家 " + targetName + " 不在线"
                );
            }
        }

        // 解析效果ID（尝试添加minecraft:前缀）
        ResourceLocation effectId = ResourceLocation.tryParse(effectStr);
        if (effectId == null) {
            effectId = ResourceLocation.tryParse("minecraft:" + effectStr);
            if (effectId == null) {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                        "无效的效果ID: " + effectStr
                );
            }
        }

        var effectHolder = BuiltInRegistries.MOB_EFFECT.getHolder(effectId);
        if (effectHolder.isEmpty()) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "未知的效果: " + effectStr
            );
        }

        // 给予效果
        int durationTicks = duration * 20;
        targetPlayer.addEffect(new MobEffectInstance(effectHolder.get(), durationTicks, amplifier, false, true));

        // 获取效果显示名称
        String effectName = effectId.getPath().replace("_", " ");

        return FunctionCallResult.success(
                String.format("已给予 %s %d级%s效果，持续%d秒",
                        targetName, amplifier + 1, effectName, duration)
        );
    }
}
