package firefly520.fireflymc.event.websocket;

/**
 * WebSocket事件广播配置
 */
public class WebSocketConfig {
    // WebSocket服务端URL (使用wss://协议连接反向代理)
    public static final String SERVER_URL = "wss://fk.firefly520.top/";

    // 是否启用事件广播
    public static final boolean ENABLED = true;

    // 自动重连配置
    public static final boolean AUTO_RECONNECT = true;

    private WebSocketConfig() {}
}
