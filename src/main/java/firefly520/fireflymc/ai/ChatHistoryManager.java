package firefly520.fireflymc.ai;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 聊天历史管理器 - 线程安全。
 * <p>
 * 容量上限每次 {@link #addMessage} 时从 {@link AIConfig} 实时读取，
 * 使 {@code maxHistorySize} 配置支持热重载（改 toml 后立即生效，无需重启服务器）。
 */
public class ChatHistoryManager {
    private final Queue<ChatMessage> history;

    public ChatHistoryManager() {
        this.history = new ConcurrentLinkedQueue<>();
    }

    /**
     * 添加消息到历史
     */
    public void addMessage(ChatMessage message) {
        if (message == null) {
            return;
        }
        history.offer(message);
        // 实时读取配置，支持热重载
        int maxSize = AIConfig.getMaxHistorySize();
        while (history.size() > maxSize) {
            history.poll();
        }
        // 裁剪后若开头是 tool 结果，说明它对应的 assistant tool_calls 已被裁掉（孤立），
        // 必须一并删除——否则发送时 messages 会以 tool 结果开头，违反 OpenAI 规范：
        // 每条 role:tool 必须紧跟携带对应 tool_call_id 的 assistant tool_calls。
        // 严格 provider（GLM/月之暗面等）会因此报「messages 参数非法」。
        while (!history.isEmpty() && history.peek().type() == MessageType.TOOL_RESULT) {
            history.poll();
        }
    }

    /**
     * 获取所有历史消息
     */
    public Queue<ChatMessage> getHistory() {
        return new ConcurrentLinkedQueue<>(history);
    }

    /**
     * 清空历史
     */
    public void clear() {
        history.clear();
    }

    /**
     * 获取当前历史大小
     */
    public int size() {
        return history.size();
    }
}
