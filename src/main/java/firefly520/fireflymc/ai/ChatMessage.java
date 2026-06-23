package firefly520.fireflymc.ai;

import com.google.gson.JsonArray;

/**
 * 聊天历史中的一条消息。
 * <p>
 * 除传统的 PLAYER/SYSTEM/ASSISTANT 文本消息外，新增两类用于标准 tool-calling：
 * <ul>
 *   <li>{@link MessageType#TOOL_CALL}：AI 发起的工具调用，{@link #toolCalls()} 携带 OpenAI 规范的 tool_calls 数组</li>
 *   <li>{@link MessageType#TOOL_RESULT}：工具执行结果，{@link #toolCallId()} 关联对应的 tool_call_id</li>
 * </ul>
 * 这两类消息通过 {@link #toApiMessage()} 还原为 OpenAI 标准消息结构，使多轮 Agentic 循环成为可能。
 */
public record ChatMessage(String sender, String content, MessageType type,
                          JsonArray toolCalls, String toolCallId) {

    public ChatMessage {
        if (sender == null || sender.isBlank()) {
            sender = "System";
        }
        if (content == null) {
            content = "";
        }
    }

    /** 普通文本消息（PLAYER/SYSTEM/ASSISTANT）。 */
    public static ChatMessage of(String sender, String content, MessageType type) {
        return new ChatMessage(sender, content, type, null, null);
    }

    /** AI 发起的工具调用消息（assistant 携带 tool_calls，content 可为空）。 */
    public static ChatMessage toolCall(JsonArray toolCalls, String content) {
        return new ChatMessage("Assistant", content == null ? "" : content,
                MessageType.TOOL_CALL, toolCalls, null);
    }

    /** 工具执行结果消息（role:tool，携带 tool_call_id）。 */
    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage("System", content == null ? "" : content,
                MessageType.TOOL_RESULT, null, toolCallId);
    }

    /**
     * 转换为 API 消息格式。
     * <p>
     * 角色映射：
     * <ul>
     *   <li>PLAYER → user（带玩家名）</li>
     *   <li>SYSTEM → user + name=Server（游戏事件，非指令）</li>
     *   <li>ASSISTANT → assistant 文本</li>
     *   <li>TOOL_CALL → assistant 携带 tool_calls</li>
     *   <li>TOOL_RESULT → tool 携带 tool_call_id</li>
     * </ul>
     * 真正的 system 指令（AI 人设）由 {@code AIApiClient} 单独注入。
     */
    public ApiMessage toApiMessage() {
        return switch (type) {
            case PLAYER -> new ApiMessage("user", sender, content, null, null);
            case SYSTEM -> new ApiMessage("user", "Server", "[系统消息] " + content, null, null);
            case ASSISTANT -> new ApiMessage("assistant", null, content, null, null);
            case TOOL_CALL -> new ApiMessage("assistant", null, content, toolCalls, null);
            case TOOL_RESULT -> new ApiMessage("tool", null, content, null, toolCallId);
        };
    }
}
