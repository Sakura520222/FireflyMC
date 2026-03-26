package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import net.minecraft.server.MinecraftServer;
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
        // 检查前置条件
        FunctionCallResult checkResult = FunctionToolHelper.checkPreconditions(player, this);
        if (checkResult != null) {
            return checkResult;
        }

        var server = player.getServer();

        // 解析天气类型
        if (!arguments.has("weather")) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "缺少必需参数: weather"
            );
        }

        // 验证weather参数类型
        var weatherElement = arguments.get("weather");
        if (!weatherElement.isJsonPrimitive() || !weatherElement.getAsJsonPrimitive().isString()) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "weather 参数必须是字符串"
            );
        }

        String weather = weatherElement.getAsString().toLowerCase();

        // 解析duration参数
        int duration = DEFAULT_DURATION;
        if (arguments.has("duration")) {
            var durationElement = arguments.get("duration");
            if (!durationElement.isJsonPrimitive() || !durationElement.getAsJsonPrimitive().isNumber()) {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                        "duration 参数必须是数字"
                );
            }
            duration = durationElement.getAsInt();
        }

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

        // weather 已在前面验证过，default分支不会执行（防御性编程）
        String weatherDesc = switch (weather) {
            case "clear" -> "晴天";
            case "rain" -> "雨天";
            case "thunder" -> "雷暴";
            default -> throw new IllegalStateException("无效的天气类型: " + weather);
        };

        String durationDesc = duration == 0 ? "永久" : duration + "秒";
        return FunctionCallResult.success("已将天气设置为 " + weatherDesc + "，持续 " + durationDesc);
    }

    @Override
    public FunctionCallResult execute(MinecraftServer server, JsonObject arguments) {
        if (!arguments.has("weather")) {
            return FunctionCallResult.failure(FunctionCallResult.ErrorType.INVALID_ARGUMENT, "缺少必需参数: weather");
        }
        var weatherElement = arguments.get("weather");
        if (!weatherElement.isJsonPrimitive() || !weatherElement.getAsJsonPrimitive().isString()) {
            return FunctionCallResult.failure(FunctionCallResult.ErrorType.INVALID_ARGUMENT, "weather 参数必须是字符串");
        }

        String weather = weatherElement.getAsString().toLowerCase();

        int duration = DEFAULT_DURATION;
        if (arguments.has("duration")) {
            var durationElement = arguments.get("duration");
            if (!durationElement.isJsonPrimitive() || !durationElement.getAsJsonPrimitive().isNumber()) {
                return FunctionCallResult.failure(FunctionCallResult.ErrorType.INVALID_ARGUMENT, "duration 参数必须是数字");
            }
            duration = durationElement.getAsInt();
        }

        duration = Math.max(MIN_DURATION, Math.min(MAX_DURATION, duration));
        int durationTicks = duration * 20;

        for (ServerLevel level : server.getAllLevels()) {
            switch (weather) {
                case "clear" -> level.setWeatherParameters(durationTicks, 0, false, false);
                case "rain" -> level.setWeatherParameters(0, durationTicks, true, false);
                case "thunder" -> level.setWeatherParameters(0, durationTicks, true, true);
                default -> {
                    return FunctionCallResult.failure(FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                            "无效的天气类型: " + weather + "。支持: clear, rain, thunder");
                }
            }
        }

        String weatherDesc = switch (weather) {
            case "clear" -> "晴天";
            case "rain" -> "雨天";
            case "thunder" -> "雷暴";
            default -> throw new IllegalStateException("无效的天气类型: " + weather);
        };
        String durationDesc = duration == 0 ? "永久" : duration + "秒";
        return FunctionCallResult.success("已将天气设置为 " + weatherDesc + "，持续 " + durationDesc);
    }
}
