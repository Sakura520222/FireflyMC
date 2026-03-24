package firefly520.fireflymc.ai;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;

/**
 * AI函数工具接口
 * <p>
 * 用于定义可被AI助手调用的函数工具，遵循OpenAI Function Calling规范
 */
public interface AIFunctionTool {
    /**
     * 获取函数名称
     * <p>
     * 用于在API调用中标识该函数
     *
     * @return 函数名称（如 "spawn_entities"）
     */
    String getName();

    /**
     * 获取函数描述
     * <p>
     * AI会根据此描述理解函数的用途，并决定何时调用
     *
     * @return 函数描述
     */
    String getDescription();

    /**
     * 获取参数JSON Schema
     * <p>
     * 遵循JSON Schema规范，定义函数接受的参数结构
     *
     * @return 参数Schema的JsonObject
     */
    JsonObject getParametersSchema();

    /**
     * 获取所需权限等级（0-4）
     * <p>
     * 0 = 无权限要求
     * 4 = OP权限等级4（最高）
     *
     * @return 所需权限等级
     */
    int getRequiredPermissionLevel();

    /**
     * 执行函数
     * <p>
     * 当AI决定调用此函数时，此方法将被调用
     *
     * @param player    触发此函数调用的玩家（用于权限验证）
     * @param arguments AI传递的函数参数
     * @return 执行结果
     */
    FunctionCallResult execute(ServerPlayer player, JsonObject arguments);
}
