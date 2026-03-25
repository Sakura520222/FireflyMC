package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.FunctionToolRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 设置天气的函数工具
 */
public class SetWeatherFunctionTool implements AIFunctionTool {

    private static final int MIN_DURATION = 0;
    private static final int MAX_DURATION = 3600;
    private static final int DEFAULT_DURATION = 600;

    @Override
    public String getName() {
        return "set_weather";
    }

    @Override
    public String getDescription() {
        return "设置游戏天气。支持: clear(晴天), rain(雨天), thunder(雷暴)。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        // weather 参数
        JsonObject weatherParam = new JsonObject();
        weatherParam.addProperty("type", "string");
        weatherParam.addProperty("description", "天气类型");
        JsonArray enumValues = new JsonArray();
        enumValues.add("clear");
        enumValues.add("rain");
        enumValues.add("thunder");
        weatherParam.add("enum", enumValues);
        properties.add("weather", weatherParam);

        // duration 参数
        JsonObject durationParam = new JsonObject();
        durationParam.addProperty("type", "integer");
        durationParam.addProperty("description", "持续时间(秒)，0表示一直持续");
        durationParam.addProperty("default", DEFAULT_DURATION);
        durationParam.addProperty("minimum", MIN_DURATION);
        durationParam.addProperty("maximum", MAX_DURATION);
        properties.add("duration", durationParam);

        schema.add("properties", properties);

        JsonArray required = new JsonArray();
        required.add("weather");
        schema.add("required", required);

        return schema;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 3;  // 3级OP权限
    }

    @Override
    public FunctionCallResult execute(ServerPlayer player, JsonObject arguments) {
        // 权限验证
        if (!FunctionToolRegistry.hasPermissionForTool(player, getName())) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.PERMISSION_DENIED,
                    "权限不足：需要3级OP权限"
            );
        }

        var server = player.getServer();
        if (server == null) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "服务器未就绪"
            );
        }

        // 解析天气类型
        if (!arguments.has("weather")) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "缺少必需参数: weather"
            );
        }

        String weather = arguments.get("weather").getAsString().toLowerCase();
        int duration = arguments.has("duration")
                ? arguments.get("duration").getAsInt()
                : DEFAULT_DURATION;

        // 验证范围
        duration = Math.max(MIN_DURATION, Math.min(MAX_DURATION, duration));
        int durationTicks = duration * 20;  // 转换为tick

        // 设置所有维度的天气
        for (ServerLevel level : server.getAllLevels()) {
            switch (weather) {
                case "clear" -> level.setWeatherParameters(durationTicks, 0, false, false);
                case "rain" -> level.setWeatherParameters(0, durationTicks, true, false);
                case "thunder" -> level.setWeatherParameters(0, durationTicks, true, true);
                default -> {
                    return FunctionCallResult.failure(
                            FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                            "无效的天气类型: " + weather + "。支持: clear, rain, thunder"
                    );
                }
            }
        }

        String weatherDesc = switch (weather) {
            case "clear" -> "晴天";
            case "rain" -> "雨天";
            case "thunder" -> "雷暴";
            default -> weather;
        };

        String durationDesc = duration == 0 ? "永久" : duration + "秒";
        return FunctionCallResult.success("已将天气设置为 " + weatherDesc + "，持续 " + durationDesc);
    }
}
