package firefly520.fireflymc.ai.function;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * 设置游戏时间的函数工具
 */
public class SetTimeFunctionTool implements AIFunctionTool {

    private static final Map<String, Long> TIME_KEYWORDS = new HashMap<>();

    static {
        TIME_KEYWORDS.put("day", 1000L);           // 白天
        TIME_KEYWORDS.put("noon", 6000L);          // 正午
        TIME_KEYWORDS.put("afternoon", 8000L);     // 下午
        TIME_KEYWORDS.put("sunset", 12000L);       // 日落
        TIME_KEYWORDS.put("night", 13000L);        // 晚上
        TIME_KEYWORDS.put("midnight", 18000L);     // 午夜
        TIME_KEYWORDS.put("sunrise", 23000L);      // 日出
    }

    @Override
    public String getName() {
        return "set_time";
    }

    @Override
    public String getDescription() {
        return "设置游戏时间。支持关键字(day/night/noon/midnight/sunrise/sunset)或具体数值(0-24000)。";
    }

    @Override
    public JsonObject getParametersSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        // time 参数
        JsonObject timeParam = new JsonObject();
        timeParam.addProperty("type", "string");
        timeParam.addProperty("description", "时间设置：支持关键字(day/night/noon/midnight/sunrise/sunset/afternoon)或数字(0-24000)");
        JsonArray enumValues = new JsonArray();
        enumValues.add("day");
        enumValues.add("night");
        enumValues.add("noon");
        enumValues.add("midnight");
        enumValues.add("sunrise");
        enumValues.add("sunset");
        enumValues.add("afternoon");
        timeParam.add("enum", enumValues);
        properties.add("time", timeParam);

        // timeValue 参数（备用数字）
        JsonObject timeValueParam = new JsonObject();
        timeValueParam.addProperty("type", "integer");
        timeValueParam.addProperty("description", "具体时间值(0-24000)，当time参数为数字时使用");
        timeValueParam.addProperty("minimum", 0);
        timeValueParam.addProperty("maximum", 24000);
        properties.add("timeValue", timeValueParam);

        schema.add("properties", properties);

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

        long timeValue;

        // 解析时间参数
        if (arguments.has("time") && !arguments.get("time").isJsonNull()) {
            // 验证time参数类型
            FunctionCallResult validationResult = FunctionToolHelper.validateStringType(
                    arguments.get("time"), "time"
            );
            if (validationResult != null) {
                return validationResult;
            }

            String timeStr = arguments.get("time").getAsString().toLowerCase().trim();

            // 尝试解析关键字
            if (TIME_KEYWORDS.containsKey(timeStr)) {
                timeValue = TIME_KEYWORDS.get(timeStr);
            } else {
                // 尝试解析为数字
                try {
                    timeValue = Long.parseLong(timeStr);
                    if (timeValue < 0 || timeValue > 24000) {
                        return FunctionCallResult.failure(
                                FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                                "时间值必须在0-24000之间"
                        );
                    }
                } catch (NumberFormatException e) {
                    return FunctionCallResult.failure(
                            FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                            "无效的时间参数: " + timeStr + "。支持: day, night, noon, midnight, sunrise, sunset 或 0-24000的数字"
                    );
                }
            }
        } else if (arguments.has("timeValue")) {
            // 验证timeValue参数类型
            var timeValueElement = arguments.get("timeValue");
            if (!timeValueElement.isJsonPrimitive() || !timeValueElement.getAsJsonPrimitive().isNumber()) {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                        "timeValue 参数必须是数字"
                );
            }
            timeValue = timeValueElement.getAsLong();
            if (timeValue < 0 || timeValue > 24000) {
                return FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                        "时间值必须在0-24000之间"
                );
            }
        } else {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "缺少时间参数"
            );
        }

        // 设置所有维度的时间
        for (ServerLevel level : server.getAllLevels()) {
            level.setDayTime(timeValue);
        }

        // 返回友好的时间描述
        String timeDesc = getTimeDescription(timeValue);
        return FunctionCallResult.success("已将时间设置为 " + timeDesc);
    }

    @Override
    public FunctionCallResult execute(MinecraftServer server, JsonObject arguments) {
        long timeValue;

        if (arguments.has("time") && !arguments.get("time").isJsonNull()) {
            FunctionCallResult validationResult = FunctionToolHelper.validateStringType(arguments.get("time"), "time");
            if (validationResult != null) return validationResult;

            String timeStr = arguments.get("time").getAsString().toLowerCase().trim();
            if (TIME_KEYWORDS.containsKey(timeStr)) {
                timeValue = TIME_KEYWORDS.get(timeStr);
            } else {
                try {
                    timeValue = Long.parseLong(timeStr);
                    if (timeValue < 0 || timeValue > 24000) {
                        return FunctionCallResult.failure(FunctionCallResult.ErrorType.INVALID_ARGUMENT, "时间值必须在0-24000之间");
                    }
                } catch (NumberFormatException e) {
                    return FunctionCallResult.failure(FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                            "无效的时间参数: " + timeStr + "。支持: day, night, noon, midnight, sunrise, sunset 或 0-24000的数字");
                }
            }
        } else if (arguments.has("timeValue")) {
            var timeValueElement = arguments.get("timeValue");
            if (!timeValueElement.isJsonPrimitive() || !timeValueElement.getAsJsonPrimitive().isNumber()) {
                return FunctionCallResult.failure(FunctionCallResult.ErrorType.INVALID_ARGUMENT, "timeValue 参数必须是数字");
            }
            timeValue = timeValueElement.getAsLong();
            if (timeValue < 0 || timeValue > 24000) {
                return FunctionCallResult.failure(FunctionCallResult.ErrorType.INVALID_ARGUMENT, "时间值必须在0-24000之间");
            }
        } else {
            return FunctionCallResult.failure(FunctionCallResult.ErrorType.INVALID_ARGUMENT, "缺少时间参数");
        }

        for (ServerLevel level : server.getAllLevels()) {
            level.setDayTime(timeValue);
        }

        String timeDesc = getTimeDescription(timeValue);
        return FunctionCallResult.success("已将时间设置为 " + timeDesc);
    }

    private String getTimeDescription(long time) {
        if (time >= 0 && time < 2000) return "黎明 (" + time + ")";
        if (time >= 2000 && time < 9000) return "白天 (" + time + ")";
        if (time >= 9000 && time < 12000) return "下午 (" + time + ")";
        if (time >= 12000 && time < 14000) return "黄昏 (" + time + ")";
        if (time >= 14000 && time < 22000) return "夜晚 (" + time + ")";
        return "深夜 (" + time + ")";
    }
}
