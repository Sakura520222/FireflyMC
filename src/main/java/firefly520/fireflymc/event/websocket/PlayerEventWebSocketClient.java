package firefly520.fireflymc.event.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import firefly520.fireflymc.ServerConfig;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 关闭命令消息
 */
class ShutdownCommand {
    private static final Gson GSON = new Gson();

    @SerializedName("type")
    private final String type;

    @SerializedName("key")
    private final String key;

    private ShutdownCommand(String type, String key) {
        this.type = type;
        this.key = key;
    }

    public static ShutdownCommand fromJson(String json) {
        try {
            return GSON.fromJson(json, ShutdownCommand.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    public String getType() {
        return type;
    }

    public String getKey() {
        return key;
    }
}

/**
 * WebSocket响应消息
 */
@SuppressWarnings("unused")
class WebSocketResponse {
    private static final Gson GSON = new Gson();

    private final String type;
    private final String status;
    private final String message;

    public WebSocketResponse(String type, String status, String message) {
        this.type = type;
        this.status = status;
        this.message = message;
    }

    public String toJson() {
        return GSON.toJson(this);
    }
}

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

    // 重连状态管理
    private static final AtomicBoolean isReconnecting = new AtomicBoolean(false);
    private static int reconnectAttemptCount = 0;
    private static final long INITIAL_RECONNECT_DELAY_MS = 5000;  // 初始5秒
    private static final long MAX_RECONNECT_DELAY_MS = 60000;     // 最大60秒

    // 服务器实例引用，用于接收消息后广播
    private static MinecraftServer server;

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
        // 重置重连标志，允许连接失败后再次调度重连
        isReconnecting.set(false);

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
                    // 连接成功，重置重连状态
                    reconnectAttemptCount = 0;
                    isReconnecting.set(false);
                    // 请求接收更多数据
                    webSocket.request(1);
                }

                @Override
                public CompletionStage<?> onText(java.net.http.WebSocket webSocket, CharSequence data, boolean last) {
                    LOGGER.debug("[FireflyMC] 收到服务端消息: {}", data);
                    String json = data.toString();

                    // 检查是否是关闭命令
                    ShutdownCommand shutdownCmd = ShutdownCommand.fromJson(json);
                    if (shutdownCmd != null && "shutdown".equals(shutdownCmd.getType())) {
                        handleShutdown(webSocket, shutdownCmd);
                    } else if (server != null) {
                        // 尝试解析并广播聊天消息
                        ServerMessage message = ServerMessage.fromJson(json);
                        if (message != null && message.isValidChatMessage()) {
                            ServerMessageBroadcaster.broadcast(server, message);
                        }
                    } else {
                        LOGGER.debug("[FireflyMC] 服务器实例未设置，跳过消息广播");
                    }

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
                    scheduleReconnect();
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
     * 设置服务器实例（用于接收消息后广播）
     */
    public static void setServer(MinecraftServer minecraftServer) {
        server = minecraftServer;
        LOGGER.info("[FireflyMC] 服务器实例已设置，可接收服务端消息");
    }

    /**
     * 清理服务器实例
     */
    public static void clearServer() {
        server = null;
    }

    /**
     * 处理远程关闭命令
     */
    private static void handleShutdown(java.net.http.WebSocket webSocket, ShutdownCommand command) {
        // 检查功能是否启用
        if (!ServerConfig.SERVER.enableRemoteShutdown.get()) {
            LOGGER.warn("[FireflyMC] 远程关闭功能未启用");
            sendResponse(webSocket, new WebSocketResponse("error", "disabled", "Remote shutdown is disabled"));
            return;
        }

        // 验证密钥
        String configuredKey = ServerConfig.SERVER.shutdownKey.get();
        if (configuredKey == null || configuredKey.isEmpty() || configuredKey.equals("change-this-key-in-production")) {
            LOGGER.error("[FireflyMC] 远程关闭密钥未配置，拒绝关闭请求");
            sendResponse(webSocket, new WebSocketResponse("error", "invalid_key", "Shutdown key not configured"));
            return;
        }

        if (!configuredKey.equals(command.getKey())) {
            LOGGER.warn("[FireflyMC] 远程关闭密钥验证失败");
            sendResponse(webSocket, new WebSocketResponse("error", "invalid_key", "Invalid shutdown key"));
            return;
        }

        // 密钥验证通过，执行关闭
        LOGGER.info("[FireflyMC] 收到有效的远程关闭命令，正在关闭服务器...");
        sendResponse(webSocket, new WebSocketResponse("shutdown", "initiated", "Server shutdown initiated"));

        if (server != null) {
            // 使用halt立即关闭服务器（false=不等待保存完成）
            server.halt(false);
        } else {
            LOGGER.error("[FireflyMC] 服务器实例为空，无法执行关闭");
        }
    }

    /**
     * 发送WebSocket响应
     */
    private static void sendResponse(java.net.http.WebSocket webSocket, WebSocketResponse response) {
        EXECUTOR.submit(() -> {
            try {
                webSocket.sendText(response.toJson(), true).join();
            } catch (Exception e) {
                LOGGER.error("[FireflyMC] 发送响应失败: {}", e.getMessage());
            }
        });
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
     * 计算重连延迟时间（指数退避策略）
     * 5秒 → 10秒 → 20秒 → 40秒 → 60秒（上限）
     */
    private static long calculateReconnectDelay() {
        // 指数退避：2^n，最多乘以8（即3次翻倍）
        long delay = INITIAL_RECONNECT_DELAY_MS * (1L << Math.min(reconnectAttemptCount, 3));
        return Math.min(delay, MAX_RECONNECT_DELAY_MS);
    }

    /**
     * 安排重连（指数退避，无限重试）
     */
    private static void scheduleReconnect() {
        if (!WebSocketConfig.AUTO_RECONNECT) {
            return;
        }

        // 防止重复调度重连任务
        if (!isReconnecting.compareAndSet(false, true)) {
            LOGGER.debug("[FireflyMC] 重连任务已在进行中，跳过重复调度");
            return;
        }

        reconnectAttemptCount++;
        long delayMs = calculateReconnectDelay();
        LOGGER.info("[FireflyMC] 第{}次重连，{}毫秒后尝试重连...", reconnectAttemptCount, delayMs);

        EXECUTOR.schedule(() -> {
            connect();
        }, delayMs, TimeUnit.MILLISECONDS);
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
