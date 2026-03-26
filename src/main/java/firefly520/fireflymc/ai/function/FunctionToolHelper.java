package firefly520.fireflymc.ai.function;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import firefly520.fireflymc.ai.FunctionToolRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;

/**
 * AI函数工具辅助类
 * <p>
 * 提供公共的验证和辅助方法，减少代码重复
 */
public class FunctionToolHelper {

    /**
     * 检查通用前置条件（权限和服务器就绪状态）
     * 使用FunctionToolRegistry进行权限验证，保持与项目其他部分一致
     *
     * @param player   执行工具的玩家
     * @param tool     工具实例
     * @return 如果检查失败返回错误结果，如果检查通过返回null
     */
    public static FunctionCallResult checkPreconditions(ServerPlayer player, AIFunctionTool tool) {
        // 使用FunctionToolRegistry进行权限验证（与项目其他部分保持一致）
        if (!FunctionToolRegistry.hasPermissionForTool(player, tool.getName())) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.PERMISSION_DENIED,
                    "权限不足：需要" + tool.getRequiredPermissionLevel() + "级OP权限"
            );
        }

        // 服务器就绪检查
        if (player.getServer() == null) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "服务器未就绪"
            );
        }

        return null; // 检查通过
    }

    /**
     * 仅检查服务器就绪状态（用于无权限要求的工具）
     *
     * @param player 执行工具的玩家
     * @return 如果检查失败返回错误结果，如果检查通过返回null
     */
    public static FunctionCallResult checkServerReady(ServerPlayer player) {
        if (player.getServer() == null) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "服务器未就绪"
            );
        }
        return null;
    }

    /**
     * 获取服务器实例，如果未就绪则返回错误结果
     *
     * @param player 执行工具的玩家
     * @return 包含服务器的结果，如果服务器未就绪则返回错误
     */
    public static ServerResult getServer(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return new ServerResult(null, FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "服务器未就绪"
            ));
        }
        return new ServerResult(server, null);
    }

    /**
     * 服务器结果包装类
     */
    public record ServerResult(MinecraftServer server, FunctionCallResult error) {
        public boolean hasError() {
            return error != null;
        }
    }

    /**
     * 从服务器获取必需的目标玩家（控制台版本，无默认玩家）
     *
     * @param server    Minecraft服务器实例
     * @param arguments 参数对象
     * @param paramName 目标玩家参数名（如 "targetPlayer"）
     * @return 包含目标玩家的结果
     */
    public static PlayerResult getRequiredTargetPlayer(MinecraftServer server, JsonObject arguments, String paramName) {
        var nameResult = getRequiredString(arguments, paramName);
        if (nameResult.hasError()) {
            return new PlayerResult(null, nameResult.error());
        }
        String targetName = nameResult.value();
        ServerPlayer targetPlayer = server.getPlayerList().getPlayerByName(targetName);
        if (targetPlayer == null) {
            return new PlayerResult(null, FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.EXECUTION_FAILED,
                    "玩家 " + targetName + " 不在线"
            ));
        }
        return new PlayerResult(targetPlayer, null);
    }

    /**
     * 玩家结果包装类
     */
    public record PlayerResult(ServerPlayer player, FunctionCallResult error) {
        public boolean hasError() {
            return error != null;
        }
    }

    /**
     * 验证字符串参数类型
     *
     * @param element   Json元素
     * @param paramName 参数名称
     * @return 如果验证失败返回错误结果，如果验证通过返回null
     */
    public static FunctionCallResult validateStringType(JsonElement element, String paramName) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    paramName + " 参数必须是字符串"
            );
        }
        return null;
    }

    /**
     * 验证数字参数类型
     *
     * @param element   Json元素
     * @param paramName 参数名称
     * @return 如果验证失败返回错误结果，如果验证通过返回null
     */
    public static FunctionCallResult validateNumberType(JsonElement element, String paramName) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    paramName + " 参数必须是数字"
            );
        }
        return null;
    }

    /**
     * 获取必需的字符串参数
     *
     * @param arguments 参数对象
     * @param paramName 参数名称
     * @return 包含字符串值的ParameterResult，如果出错则包含错误
     */
    public static ParameterResult<String> getRequiredString(JsonObject arguments, String paramName) {
        if (!arguments.has(paramName)) {
            return new ParameterResult<>(null, FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    "缺少必需参数: " + paramName
            ));
        }

        JsonElement element = arguments.get(paramName);
        if (element.isJsonNull()) {
            return new ParameterResult<>(null, FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.INVALID_ARGUMENT,
                    paramName + " 参数不能为空"
            ));
        }

        FunctionCallResult validationResult = validateStringType(element, paramName);
        if (validationResult != null) {
            return new ParameterResult<>(null, validationResult);
        }

        return new ParameterResult<>(element.getAsString(), null);
    }

    /**
     * 获取可选的字符串参数
     *
     * @param arguments    参数对象
     * @param paramName    参数名称
     * @param defaultValue 默认值
     * @return 字符串值，如果参数不存在或类型错误则返回默认值
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
     * 获取可选的整数参数
     *
     * @param arguments    参数对象
     * @param paramName    参数名称
     * @param defaultValue 默认值
     * @return 整数值，如果参数不存在或类型错误则返回默认值
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
     * 参数结果包装类
     */
    public record ParameterResult<T>(T value, FunctionCallResult error) {
        public boolean hasError() {
            return error != null;
        }
    }
}
