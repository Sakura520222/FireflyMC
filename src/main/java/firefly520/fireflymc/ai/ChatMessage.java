package firefly520.fireflymc.ai;

public record ChatMessage(String sender, String content, MessageType type) {
    public ChatMessage {
        if (sender == null || sender.isBlank()) {
            sender = "System";
        }
        if (content == null || content.isBlank()) {
            content = "";
        }
    }

    /**
     * 转换为API消息格式
     *
     * 角色映射说明：
     * - PLAYER: user角色，带玩家名
     * - SYSTEM: user角色，name=Server，表示这是游戏事件（不是指令）
     * - ASSISTANT: assistant角色，AI回复
     *
     * 注意：真正的system指令（AI人设）在AIApiClient中单独添加
     */
    public ApiMessage toApiMessage() {
        return switch (type) {
            case PLAYER -> new ApiMessage("user", sender, content);
            case SYSTEM -> new ApiMessage("user", "Server", "[系统消息] " + content);
            case ASSISTANT -> new ApiMessage("assistant", null, content);
        };
    }
}
