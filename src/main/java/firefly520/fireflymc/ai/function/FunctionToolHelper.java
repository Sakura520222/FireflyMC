package firefly520.fireflymc.ai.function;

import com.google.gson.JsonElement;
import firefly520.fireflymc.ai.AIFunctionTool;
import firefly520.fireflymc.ai.FunctionCallResult;
import net.minecraft.server.level.ServerPlayer;

/**
 * AI函数工具辅助类
 * <p>
 * 提供公共的验证和辅助方法，减少代码重复
 */
public class FunctionToolHelper {

    /**
     * 检查通用前置条件（权限和服务器就绪状态）
     *
     * @param player    执行工具的玩家
     * @param toolName  工具名称
     * @param requiredPermission 所需权限等级
     * @return 如果检查失败返回错误结果，如果检查通过返回null
     */
    public static FunctionCallResult checkPreconditions(ServerPlayer player, String toolName, int requiredPermission) {
        // 权限验证
        if (!player.createCommandSourceStack().hasPermission(requiredPermission)) {
            return FunctionCallResult.failure(
                    FunctionCallResult.ErrorType.PERMISSION_DENIED,
                    "权限不足：需要" + requiredPermission + "级OP权限"
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
     * 检查通用前置条件（使用工具的权限等级）
     *
     * @param player   执行工具的玩家
     * @param tool     工具实例
     * @return 如果检查失败返回错误结果，如果检查通过返回null
     */
    public static FunctionCallResult checkPreconditions(ServerPlayer player, AIFunctionTool tool) {
        return checkPreconditions(player, tool.getName(), tool.getRequiredPermissionLevel());
    }

    /**
     * 验证字符串参数类型
     *
     * @param arguments 参数对象
     * @param paramName 参数名称
     * @return 如果验证失败返回错误结果，如果验证通过返回null
     */
    public static FunctionCallResult validateStringType(JsonElement arguments, String paramName) {
        if (!arguments.isJsonPrimitive() || !arguments.getAsJsonPrimitive().isString()) {
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
     * 安全获取字符串参数
     *
     * @param arguments 参数对象
     * @param paramName 参数名称
     * @return 字符串值，如果类型不匹配返回null
     */
    public static String getStringSafely(JsonElement arguments, String paramName) {
        if (arguments.isJsonPrimitive() && arguments.getAsJsonPrimitive().isString()) {
            return arguments.getAsString();
        }
        return null;
    }

    /**
     * 安全获取整数参数
     *
     * @param element Json元素
     * @param paramName 参数名称
     * @param defaultValue 默认值
     * @return 整数值，如果类型不匹配返回默认值
     */
    public static int getIntSafely(JsonElement element, String paramName, int defaultValue) {
        try {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
                return element.getAsInt();
            }
        } catch (NumberFormatException | IllegalStateException ignored) {
            // 返回默认值
        }
        return defaultValue;
    }
}
