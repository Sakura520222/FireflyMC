package firefly520.fireflymc.ai.function;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.ToolContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * AI 函数工具辅助类。
 * <p>
 * 提供公共的参数解析、目标玩家解析、维度解析等工具方法，减少工具实现的重复代码。
 * 权限校验与服务器就绪检查由调度层统一完成，本类不再提供此类前置检查。
 */
public class FunctionToolHelper {

    private FunctionToolHelper() {
    }

    // ===== 目标玩家解析（基于 ToolContext；参数名建议统一为 target_player）=====

    /**
     * 获取可选的目标玩家：未指定时默认为执行者；控制台触发且未指定时返回错误。
     */
    public static PlayerResult getOptionalTargetPlayer(ToolContext ctx, JsonObject arguments, String paramName) {
        String targetName = getOptionalString(arguments, paramName, null);
        if (targetName == null || targetName.isBlank()) {
            ServerPlayer self = ctx.player();
            if (self == null) {
                return new PlayerResult(null, FunctionCallResult.failure(
                        FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                        "控制台调用必须显式指定 " + paramName));
            }
            return new PlayerResult(self, null);
        }
        ServerPlayer target = ctx.server().getPlayerList().getPlayerByName(targetName);
        if (target == null) {
            return new PlayerResult(null, FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "玩家 " + targetName + " 不在线"));
        }
        return new PlayerResult(target, null);
    }

    /**
     * 获取必需的目标玩家（无论玩家/控制台触发，都必须显式指定）。
     */
    public static PlayerResult getRequiredTargetPlayer(ToolContext ctx, JsonObject arguments, String paramName) {
        ParameterResult<String> nameResult = getRequiredString(arguments, paramName);
        if (nameResult.hasError()) {
            return new PlayerResult(null, nameResult.error());
        }
        String targetName = nameResult.value();
        ServerPlayer target = ctx.server().getPlayerList().getPlayerByName(targetName);
        if (target == null) {
            return new PlayerResult(null, FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "玩家 " + targetName + " 不在线"));
        }
        return new PlayerResult(target, null);
    }

    /**
     * 玩家结果包装类。
     */
    public record PlayerResult(ServerPlayer player, FunctionCallResult error) {
        public boolean hasError() {
            return error != null;
        }
    }

    // ===== 参数类型校验 =====

    /**
     * 验证字符串参数类型。
     *
     * @return 验证失败返回错误结果，通过返回 null
     */
    public static FunctionCallResult validateStringType(JsonElement element, String paramName) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    paramName + " 参数必须是字符串");
        }
        return null;
    }

    /**
     * 验证数字参数类型。
     */
    public static FunctionCallResult validateNumberType(JsonElement element, String paramName) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    paramName + " 参数必须是数字");
        }
        return null;
    }

    // ===== 参数获取 =====

    /**
     * 获取必需的字符串参数。
     */
    public static ParameterResult<String> getRequiredString(JsonObject arguments, String paramName) {
        if (!arguments.has(paramName)) {
            return new ParameterResult<>(null, FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "缺少必需参数: " + paramName));
        }
        JsonElement element = arguments.get(paramName);
        if (element.isJsonNull()) {
            return new ParameterResult<>(null, FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    paramName + " 参数不能为空"));
        }
        FunctionCallResult validationResult = validateStringType(element, paramName);
        if (validationResult != null) {
            return new ParameterResult<>(null, validationResult);
        }
        return new ParameterResult<>(element.getAsString(), null);
    }

    /**
     * 获取可选的字符串参数。
     */
    public static String getOptionalString(JsonObject arguments, String paramName, String defaultValue) {
        if (!arguments.has(paramName) || arguments.get(paramName).isJsonNull()) {
            return defaultValue;
        }
        JsonElement element = arguments.get(paramName);
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        return defaultValue;
    }

    /**
     * 获取可选的整数参数。
     */
    public static int getOptionalInt(JsonObject arguments, String paramName, int defaultValue) {
        if (!arguments.has(paramName) || arguments.get(paramName).isJsonNull()) {
            return defaultValue;
        }
        JsonElement element = arguments.get(paramName);
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            try {
                return element.getAsInt();
            } catch (NumberFormatException | IllegalStateException ignored) {
                // 返回默认值
            }
        }
        return defaultValue;
    }

    /**
     * 获取必需的 double 参数。
     */
    public static ParameterResult<Double> getRequiredDouble(JsonObject arguments, String paramName) {
        if (!arguments.has(paramName)) {
            return new ParameterResult<>(null, FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "缺少必需参数: " + paramName));
        }
        JsonElement element = arguments.get(paramName);
        if (element.isJsonNull()) {
            return new ParameterResult<>(null, FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    paramName + " 参数不能为空"));
        }
        FunctionCallResult validationResult = validateNumberType(element, paramName);
        if (validationResult != null) {
            return new ParameterResult<>(null, validationResult);
        }
        return new ParameterResult<>(element.getAsDouble(), null);
    }

    /**
     * 获取可选的布尔参数。
     */
    public static boolean getOptionalBoolean(JsonObject arguments, String paramName, boolean defaultValue) {
        if (!arguments.has(paramName) || arguments.get(paramName).isJsonNull()) {
            return defaultValue;
        }
        JsonElement element = arguments.get(paramName);
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
            return element.getAsBoolean();
        }
        return defaultValue;
    }

    /**
     * 参数结果包装类。
     */
    public record ParameterResult<T>(T value, FunctionCallResult error) {
        public boolean hasError() {
            return error != null;
        }
    }

    // ===== 维度解析 =====

    /**
     * 解析维度（ServerLevel）。
     */
    public static LevelResult resolveDimension(MinecraftServer server, String dimensionStr) {
        ResourceLocation dimensionId = ResourceLocation.tryParse(dimensionStr);
        if (dimensionId == null) {
            return new LevelResult(null, FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "无效的维度ID: " + dimensionStr));
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().equals(dimensionId)) {
                return new LevelResult(level, null);
            }
        }
        return new LevelResult(null, FunctionCallResult.failure(
                FunctionCallResult.ErrorType.EXECUTION_FAILED,
                "维度不存在: " + dimensionStr));
    }

    /**
     * 解析维度（ToolContext 版）。
     */
    public static LevelResult resolveDimension(ToolContext ctx, String dimensionStr) {
        return resolveDimension(ctx.server(), dimensionStr);
    }

    /**
     * 维度结果包装类。
     */
    public record LevelResult(ServerLevel level, FunctionCallResult error) {
        public boolean hasError() {
            return error != null;
        }
    }
}
