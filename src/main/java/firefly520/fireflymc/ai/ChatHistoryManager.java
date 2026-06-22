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
