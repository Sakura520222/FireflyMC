package firefly520.fireflymc.ai;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 聊天历史管理器 - 线程安全
 */
public class ChatHistoryManager {
    private static final int DEFAULT_MAX_SIZE = 30;
    private final int maxSize;
    private final Queue<ChatMessage> history;

    public ChatHistoryManager() {
        this(DEFAULT_MAX_SIZE);
    }

    public ChatHistoryManager(int maxSize) {
        this.maxSize = maxSize;
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
        // 超过限制时移除最旧的
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
