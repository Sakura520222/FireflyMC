package firefly520.fireflymc.client.relay;

import firefly520.fireflymc.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单人世界公开大厅 WebSocket 客户端。
 *
 * 当前阶段只负责 host_open / host_close / heartbeat 控制消息；
 * 真实 Minecraft 字节流转发将在后续阶段接入。
 */
public final class RelayLobbyWebSocketClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(RelayLobbyWebSocketClient.class);
    private static final RelayLobbyWebSocketClient INSTANCE = new RelayLobbyWebSocketClient();

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "FireflyMC-Relay-Lobby");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private WebSocket webSocket;
    private ScheduledFuture<?> heartbeatTask;
    private String currentRoomId;
    private final StringBuilder textAccumulator = new StringBuilder();

    private RelayLobbyWebSocketClient() {
    }

    public static RelayLobbyWebSocketClient getInstance() {
        return INSTANCE;
    }

    public void publishRoom(RelayLobbyMessage message, String roomId) {
        this.currentRoomId = roomId;
        executor.execute(() -> {
            try {
                ensureConnected();
                send(message);
                startHeartbeat();
            } catch (Exception e) {
                LOGGER.warn("[FireflyMC] 发布单人世界公开房间失败: {}", e.getMessage());
            }
        });
    }

    public void closeRoom() {
        String roomId = currentRoomId;
        executor.execute(() -> {
            stopHeartbeat();
            if (roomId != null && webSocket != null && connected.get()) {
                try {
                    send(RelayLobbyMessage.hostClose(roomId));
                } catch (Exception e) {
                    LOGGER.debug("[FireflyMC] 发送关闭公开房间消息失败: {}", e.getMessage());
                }
            }
            currentRoomId = null;
        });
    }

    public void requestLobbyList() {
        RelayLobbyState.setRefreshing(true);
        executor.execute(() -> {
            try {
                ensureConnected();
                LOGGER.info("[FireflyMC] 正在请求公开大厅房间列表");
                send(RelayLobbyMessage.lobbyList());
                scheduleLobbyListTimeout();
            } catch (Exception e) {
                RelayLobbyState.setStatusMessage("刷新失败: " + e.getMessage());
                LOGGER.warn("[FireflyMC] 请求公开大厅列表失败: {}", e.getMessage());
            }
        });
    }

    private void ensureConnected() {
        if (webSocket != null && connected.get()) {
            return;
        }

        URI uri = URI.create(Config.CLIENT.SINGLEPLAYER_RELAY_URL.get());
        LOGGER.info("[FireflyMC] 正在连接单人世界公开大厅: {}", uri);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        CompletableFuture<WebSocket> connectFuture = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        connected.set(true);
                        LOGGER.info("[FireflyMC] 单人世界公开大厅连接成功");
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        textAccumulator.append(data);
                        if (last) {
                            String json = textAccumulator.toString();
                            textAccumulator.setLength(0);
                            LOGGER.info("[FireflyMC] 收到公开大厅文本消息: {}", json);
                            handleTextMessage(json);
                        }
                        webSocket.request(1);
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                        if (last) {
                            ByteBuffer copy = data.slice();
                            byte[] bytes = new byte[copy.remaining()];
                            copy.get(bytes);
                            String message = new String(bytes, StandardCharsets.UTF_8);
                            if (message.startsWith("{")) {
                                LOGGER.info("[FireflyMC] 收到公开大厅二进制JSON消息: {}", message);
                                handleTextMessage(message);
                            } else {
                                LOGGER.debug("[FireflyMC] 收到公开大厅二进制消息: {} bytes", bytes.length);
                            }
                        } else {
                            LOGGER.debug("[FireflyMC] 收到公开大厅二进制分片，等待后续阶段处理");
                        }
                        webSocket.request(1);
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        connected.set(false);
                        LOGGER.info("[FireflyMC] 单人世界公开大厅连接关闭: {} - {}", statusCode, reason);
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        connected.set(false);
                        LOGGER.warn("[FireflyMC] 单人世界公开大厅连接错误: {}", error.getMessage());
                    }
                });

        webSocket = connectFuture.join();
        waitUntilOpen();
    }

    private void waitUntilOpen() {
        long deadline = System.currentTimeMillis() + 5000;
        while (!connected.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!connected.get()) {
            throw new IllegalStateException("Relay lobby WebSocket open timeout");
        }
    }

    private void handleTextMessage(String json) {
        RelayLobbyListResult result = RelayLobbyListResult.fromJson(json);
        if (result != null && result.isLobbyListResult()) {
            LOGGER.info("[FireflyMC] 已收到公开大厅房间列表: {} 个房间", result.rooms().size());
            RelayLobbyState.updateRooms(result.rooms());
        } else if (result == null) {
            LOGGER.warn("[FireflyMC] 公开大厅消息 JSON 解析失败: {}", json);
        } else {
            LOGGER.info("[FireflyMC] 收到未处理的公开大厅消息: {}", json);
        }
    }

    private void scheduleLobbyListTimeout() {
        executor.schedule(() -> {
            if (RelayLobbyState.isRefreshing()) {
                RelayLobbyState.setStatusMessage("公开大厅暂无响应，请稍后重试");
                LOGGER.warn("[FireflyMC] 公开大厅列表请求超时");
            }
        }, 8, TimeUnit.SECONDS);
    }

    private void send(RelayLobbyMessage message) {
        if (webSocket == null || !connected.get()) {
            throw new IllegalStateException("Relay lobby WebSocket is not connected");
        }
        String json = message.toJson();
        webSocket.sendText(json, true).join();
        if ("heartbeat".equals(message.type())) {
            LOGGER.debug("[FireflyMC] 已发送公开大厅心跳: {}", json);
        } else {
            LOGGER.info("[FireflyMC] 已发送公开大厅消息: {}", json);
        }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTask = executor.scheduleAtFixedRate(() -> {
            if (currentRoomId == null || webSocket == null || !connected.get()) {
                return;
            }
            try {
                send(RelayLobbyMessage.heartbeat(currentRoomId));
            } catch (Exception e) {
                LOGGER.debug("[FireflyMC] 发送公开大厅心跳失败: {}", e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null && !heartbeatTask.isDone()) {
            heartbeatTask.cancel(false);
        }
        heartbeatTask = null;
    }
}
