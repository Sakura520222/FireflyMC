package firefly520.fireflymc.ai;

import com.google.gson.JsonObject;

/**
 * AI 函数工具接口。
 * <p>
 * 遵循 OpenAI Function Calling 规范，定义可被 AI 助手调用的工具。
 * 工具实现只需提供唯一的 {@link #execute(ToolContext, JsonObject)}，
 * 通过 {@link ToolContext} 同时覆盖「玩家触发」与「控制台触发」两条路径。
 * <p>
 * 权限校验与启用开关由调度层（{@code AgenticToolLoop}）统一完成，
 * 工具实现聚焦业务逻辑，不再各自做前置检查。
 */
public interface AIFunctionTool {

    /**
     * 获取函数名称（如 "spawn_entities"），用于在 API 调用中标识该函数。
     */
    String getName();

    /**
     * 获取函数描述，AI 据此判断何时调用该函数。
     */
    String getDescription();

    /**
     * 获取参数 JSON Schema（遵循 JSON Schema 规范）。
     */
    JsonObject getParametersSchema();

    /**
     * 获取所需权限等级（0-4）。0 = 无要求，4 = 最高 OP。
     * 控制台触发时视为 4 级，始终满足。
     */
    int getRequiredPermissionLevel();

    /**
     * 执行函数。
     *
     * @param ctx       执行上下文（含 server 与可选 player；控制台触发时 player 为 null）
     * @param arguments AI 传递的函数参数
     * @return 执行结果（成功/失败 + 消息）
     */
    FunctionCallResult execute(ToolContext ctx, JsonObject arguments);
}
