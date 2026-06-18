package firefly520.fireflymc.client.eventws;

import firefly520.fireflymc.Config;

/**
 * 客户端事件通知配置访问入口。
 */
public final class ClientEventNotificationConfig {
    public static final Config.ClientConfig CONFIG = Config.CLIENT;

    private ClientEventNotificationConfig() {
    }

    public static boolean enabled() {
        return CONFIG.EVENT_NOTIFICATION_ENABLED.get();
    }

    public static boolean crossChatEnabled() {
        return CONFIG.CROSS_CHAT_ENABLED.get();
    }

    /**
     * 事件通知 WebSocket 通道是否激活：事件通知或跨级聊天任一启用即需建立连接。
     */
    public static boolean channelActive() {
        return enabled() || crossChatEnabled();
    }

    public static String webSocketUrl() {
        return CONFIG.EVENT_NOTIFICATION_URL.get();
    }

    public static boolean autoReconnect() {
        return CONFIG.EVENT_NOTIFICATION_AUTO_RECONNECT.get();
    }

    public static int reconnectIntervalMillis() {
        return positive(CONFIG.EVENT_NOTIFICATION_RECONNECT_INTERVAL_MILLIS.get(), 5000);
    }

    public static int heartbeatIntervalMillis() {
        return positive(CONFIG.EVENT_NOTIFICATION_HEARTBEAT_INTERVAL_MILLIS.get(), 30000);
    }

    public static int sendTimeoutMillis() {
        return positive(CONFIG.EVENT_NOTIFICATION_SEND_TIMEOUT_MILLIS.get(), 5000);
    }

    public static int queueCapacity() {
        return positive(CONFIG.EVENT_NOTIFICATION_QUEUE_CAPACITY.get(), 128);
    }

    private static int positive(Integer value, int fallback) {
        if (value == null || value <= 0) {
            return fallback;
        }
        return value;
    }
}
