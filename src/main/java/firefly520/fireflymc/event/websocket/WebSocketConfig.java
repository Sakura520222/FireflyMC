package firefly520.fireflymc.event.websocket;

/**
 * WebSocket事件广播配置
 */
public class WebSocketConfig {
    // WebSocket服务端URL (使用127.0.0.1而不是localhost，避免IPv6解析问题)
    public static final String SERVER_URL = "ws://127.0.0.1:8765";

    // 是否启用事件广播
    public static final boolean ENABLED = true;

    // 自动重连配置
    public static final boolean AUTO_RECONNECT = true;

    private WebSocketConfig() {}
}
