package firefly520.fireflymc.event.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 玩家事件WebSocket客户端
 *
 * 使用Java标准库的WebSocket API（Java 11+）
 */
public class PlayerEventWebSocketClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerEventWebSocketClient.class);
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "FireflyMC-WebSocket-Client");
        thread.setDaemon(true);
        return thread;
    });

    private static java.net.http.WebSocket wsClient;
    private static final AtomicBoolean isConnected = new AtomicBoolean(false);

    /**
     * 初始化WebSocket连接
     */
    public static void init() {
        if (!WebSocketConfig.ENABLED) {
            LOGGER.info("[FireflyMC] WebSocket事件广播未启用");
            return;
        }

        connect();
    }

    /**
     * 连接到WebSocket服务端
     */
    private static void connect() {
        try {
            URI serverUri = URI.create(WebSocketConfig.SERVER_URL);
            LOGGER.info("[FireflyMC] 正在连接到 WebSocket: {}", WebSocketConfig.SERVER_URL);

            var client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();
            var builder = client.newWebSocketBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10));

            // 创建监听器处理连接生命周期
            wsClient = builder.buildAsync(serverUri, new java.net.http.WebSocket.Listener() {
                @Override
                public void onOpen(java.net.http.WebSocket webSocket) {
                    LOGGER.info("[FireflyMC] WebSocket连接成功: {}", WebSocketConfig.SERVER_URL);
                    isConnected.set(true);
                    // 请求接收更多数据
                    webSocket.request(1);
                }

                @Override
                public CompletionStage<?> onText(java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
                    LOGGER.debug("[FireflyMC] 收到服务端消息: {}", data);
                    webSocket.request(1);
                    return java.net.http.WebSocket.Listener.super.onText(webSocket, data, last);
                }

                @Override
                public CompletionStage<?> onClose(java.net.http.WebSocket webSocket, int statusCode, String reason) {
                    LOGGER.warn("[FireflyMC] WebSocket连接关闭: {} - {}", statusCode, reason);
                    isConnected.set(false);
                    scheduleReconnect();
                    return java.net.http.WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
                }

                @Override
                public void onError(java.net.http.WebSocket webSocket, Throwable error) {
                    LOGGER.error("[FireflyMC] WebSocket错误: {}", error.getMessage());
                    isConnected.set(false);
                }
            }).join();

        } catch (java.util.concurrent.CompletionException e) {
            isConnected.set(false);
            Throwable cause = e.getCause();
            LOGGER.error("[FireflyMC] WebSocket连接失败: {} - {}", cause.getClass().getSimpleName(), cause.getMessage());
            scheduleReconnect();
        } catch (Exception e) {
            isConnected.set(false);
            LOGGER.error("[FireflyMC] WebSocket连接失败: {} - {}", e.getClass().getSimpleName(), e.getMessage());
            scheduleReconnect();
        }
    }

    /**
     * 发送玩家事件消息
     */
    public static void sendEvent(PlayerEventMessage message) {
        if (!WebSocketConfig.ENABLED) {
            return;
        }

        EXECUTOR.submit(() -> {
            if (wsClient != null && isConnected.get()) {
                try {
                    wsClient.sendText(message.toJson(), true).join();
                    LOGGER.debug("[FireflyMC] 发送玩家事件: {}", message.toJson());
                } catch (Exception e) {
                    LOGGER.error("[FireflyMC] 发送事件失败: {}", e.getMessage());
                    isConnected.set(false);
                    scheduleReconnect();
                }
            } else {
                LOGGER.warn("[FireflyMC] WebSocket未连接，跳过事件发送");
            }
        });
    }

    /**
     * 安排重连（无限重连，间隔5秒）
     */
    private static void scheduleReconnect() {
        if (!WebSocketConfig.AUTO_RECONNECT) {
            return;
        }

        LOGGER.info("[FireflyMC] 5秒后尝试重连...");
        EXECUTOR.schedule(() -> {
            connect();
        }, 5000, TimeUnit.MILLISECONDS);
    }

    /**
     * 关闭连接
     */
    public static void shutdown() {
        isConnected.set(false);
        if (wsClient != null) {
            try {
                wsClient.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "Server shutting down").join();
            } catch (Exception e) {
                LOGGER.error("[FireflyMC] 关闭WebSocket连接失败: {}", e.getMessage());
            }
        }
        EXECUTOR.shutdown();
    }
}
