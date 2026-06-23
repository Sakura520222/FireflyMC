package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * 给予药水效果的函数工具。
 */
public class GiveEffectFunctionTool implements AIFunctionTool {

    private static final int MIN_DURATION = 1;
    private static final int MAX_DURATION = 3600;
    private static final int DEFAULT_DURATION = 60;
    private static final int DEFAULT_AMPLIFIER = 0;
    private static final int MAX_AMPLIFIER = 255;

    private static final String TARGET_PARAM = "target_player";

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

        JsonObject effectParam = new JsonObject();
        effectParam.addProperty("type", "string");
        effectParam.addProperty("description", "效果ID，如 'speed'、'strength'、'regeneration'、'jump_boost'、'night_vision' 等");
        properties.add("effect", effectParam);

        JsonObject durationParam = new JsonObject();
        durationParam.addProperty("type", "integer");
        durationParam.addProperty("description", "持续时间(秒)");
        durationParam.addProperty("default", DEFAULT_DURATION);
        durationParam.addProperty("minimum", MIN_DURATION);
        durationParam.addProperty("maximum", MAX_DURATION);
        properties.add("duration", durationParam);

        JsonObject amplifierParam = new JsonObject();
        amplifierParam.addProperty("type", "integer");
        amplifierParam.addProperty("description", "效果强度(0为等级I，1为等级II，依此类推)");
        amplifierParam.addProperty("default", DEFAULT_AMPLIFIER);
        amplifierParam.addProperty("minimum", 0);
        amplifierParam.addProperty("maximum", MAX_AMPLIFIER);
        properties.add("amplifier", amplifierParam);

        JsonObject targetParam = new JsonObject();
        targetParam.addProperty("type", "string");
        targetParam.addProperty("description", "目标玩家名称，默认为执行者（控制台调用时必填）");
        properties.add(TARGET_PARAM, targetParam);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("effect");
        schema.add("required", required);

        return schema;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public FunctionCallResult execute(ToolContext ctx, JsonObject arguments) {
        var effectResult = FunctionToolHelper.getRequiredString(arguments, "effect");
        if (effectResult.hasError()) {
            return effectResult.error();
        }
        String effectStr = effectResult.value().toLowerCase();

        int duration = FunctionToolHelper.getOptionalInt(arguments, "duration", DEFAULT_DURATION);
        int amplifier = FunctionToolHelper.getOptionalInt(arguments, "amplifier", DEFAULT_AMPLIFIER);
        duration = Math.max(MIN_DURATION, Math.min(MAX_DURATION, duration));
        amplifier = Math.max(0, Math.min(MAX_AMPLIFIER, amplifier));

        var targetResult = FunctionToolHelper.getOptionalTargetPlayer(ctx, arguments, TARGET_PARAM);
        if (targetResult.hasError()) {
            return targetResult.error();
        }
        ServerPlayer target = targetResult.player();
        String targetName = target.getGameProfile().getName();

        // 解析效果ID（尝试补 minecraft: 前缀）
        ResourceLocation effectId = ResourceLocation.tryParse(effectStr);
        if (effectId == null) {
            effectId = ResourceLocation.tryParse("minecraft:" + effectStr);
            if (effectId == null) {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                        "无效的效果ID: " + effectStr);
            }
        }
        var effectHolder = BuiltInRegistries.MOB_EFFECT.getHolder(effectId);
        if (effectHolder.isEmpty()) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "未知的效果: " + effectStr);
        }

        int durationTicks = duration * 20;
        target.addEffect(new MobEffectInstance(effectHolder.get(), durationTicks, amplifier, false, true));

        String effectName = effectId.getPath().replace("_", " ");
        return FunctionCallResult.success(
                String.format("已给予 %s %d级%s效果，持续%d秒", targetName, amplifier + 1, effectName, duration));
    }
}
