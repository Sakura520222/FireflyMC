package firefly520.fireflymc.ai;

public enum MessageType {
    PLAYER,       // 玩家消息
    SYSTEM,       // 系统消息（游戏事件：加入/离开/死亡/成就等）
    ASSISTANT,    // AI 文本回复
    TOOL_CALL,    // AI 发起的工具调用（assistant 消息携带 tool_calls）
    TOOL_RESULT   // 工具执行结果（role:tool，携带 tool_call_id）
}
