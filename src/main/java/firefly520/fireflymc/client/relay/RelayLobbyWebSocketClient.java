package firefly520.fireflymc.client.relay;

import firefly520.fireflymc.client.relay.RelayConfig;
import firefly520.fireflymc.client.relay.p2p.P2PConnectionManager;
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
import java.util.regex.Pattern;

/**
 * 单人世界公开大厅 WebSocket 客户端。
 */
public final class RelayLobbyWebSocketClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(RelayLobbyWebSocketClient.class);
    private static final RelayLobbyWebSocketClient INSTANCE = new RelayLobbyWebSocketClient();
    private static final Pattern P2P_UDP_HOST_PATTERN = Pattern.compile("(\\\"p2pUdpHost\\\"\\s*:\\s*\\\")([^\\\"]*)(\\\")");
    private static final Pattern P2P_CANDIDATE_ADDRESS_PATTERN = Pattern.compile("(\\\"address\\\"\\s*:\\s*\\\")([^\\\"]*)(\\\")");

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "FireflyMC-Relay-Lobby");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private WebSocket webSocket;
    private ScheduledFuture<?> heartbeatTask;
    private String currentRoomId;
    private RelayGuestProxy guestProxy;
    private RelayHostBridge hostBridge;
    private CompletableFuture<RelayControlMessage> pendingJoin;
    private final StringBuilder textAccumulator = new StringBuilder();
    private ByteBuffer binaryAccumulator = null;
    private long lastLobbyListRequestAt = 0L;

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

    public void setHostBridge(RelayHostBridge hostBridge) {
        this.hostBridge = hostBridge;
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
        long now = System.currentTimeMillis();
        if (RelayLobbyState.isRefreshing() || now - lastLobbyListRequestAt < 1000) {
            LOGGER.debug("[FireflyMC] 跳过重复公开大厅刷新请求");
            return;
        }
        lastLobbyListRequestAt = now;
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

    public CompletableFuture<RelayControlMessage> joinRoom(RelayLobbyRoom room, String guestPlayerName, String guestUuid) {
        CompletableFuture<RelayControlMessage> future = new CompletableFuture<>();
        pendingJoin = future;
        executor.execute(() -> {
            try {
                ensureConnected();
                send(RelayLobbyMessage.guestJoin(room.roomId(), guestPlayerName, guestUuid));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    public void setGuestProxy(RelayGuestProxy proxy) {
        this.guestProxy = proxy;
    }

    public void clearGuestProxy(RelayGuestProxy proxy) {
        if (this.guestProxy == proxy) {
            this.guestProxy = null;
        }
    }

    public void sendControl(RelayLobbyMessage message) {
        executor.execute(() -> {
            try {
                ensureConnected();
                send(message);
            } catch (Exception e) {
                LOGGER.debug("[FireflyMC] 发送 relay 控制消息失败: {}", e.getMessage());
            }
        });
    }

    /**
     * 异步发送二进制帧。不使用 executor 以避免阻塞单线程调度器。
     * 二进制数据量大且频繁，不能排队到单线程 executor 中。
     */
    public void sendBinary(ByteBuffer buffer) {
        try {
            if (webSocket != null && connected.get()) {
                // 异步发送，不阻塞，不使用 executor
                webSocket.sendBinary(buffer, true);
            }
        } catch (Exception e) {
            LOGGER.debug("[FireflyMC] 发送 relay 二进制数据失败: {}", e.getMessage());
        }
    }

    private void ensureConnected() {
        if (webSocket != null && connected.get()) {
            return;
        }

        URI uri = URI.create(RelayConfig.RELAY.SINGLEPLAYER_RELAY_URL.get());
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
                            LOGGER.debug("[FireflyMC] 收到公开大厅文本消息: {}", sanitizeRelayJsonForLog(json));
                            handleTextMessage(json);
                        }
                        webSocket.request(1);
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                        ByteBuffer slice = data.slice();
                        if (!last) {
                            // 累积分片
                            if (binaryAccumulator == null) {
                                binaryAccumulator = ByteBuffer.allocate(slice.remaining() * 4);
                            }
                            // 如果空间不够，扩容
                            if (binaryAccumulator.remaining() < slice.remaining()) {
                                int newCapacity = (binaryAccumulator.position() + slice.remaining()) * 2;
                                ByteBuffer newBuf = ByteBuffer.allocate(newCapacity);
                                binaryAccumulator.flip();
                                newBuf.put(binaryAccumulator);
                                binaryAccumulator = newBuf;
                            }
                            binaryAccumulator.put(slice);
                            webSocket.request(1);
                            return CompletableFuture.completedFuture(null);
                        }
                        // last=true: 合并累积数据（如果有）
                        ByteBuffer fullData;
                        if (binaryAccumulator != null && binaryAccumulator.position() > 0) {
                            binaryAccumulator.put(slice);
                            binaryAccumulator.flip();
                            fullData = binaryAccumulator;
                            binaryAccumulator = null;
                        } else {
                            fullData = slice;
                            binaryAccumulator = null;
                        }
                        byte[] bytes = new byte[fullData.remaining()];
                        fullData.get(bytes);
                        String message = new String(bytes, StandardCharsets.UTF_8);
                        if (message.startsWith("{")) {
                            LOGGER.debug("[FireflyMC] 收到公开大厅二进制JSON消息: {}", sanitizeRelayJsonForLog(message));
                            handleTextMessage(message);
                        } else {
                            handleBinaryFrame(bytes);
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
            LOGGER.warn("[FireflyMC] 公开大厅消息 JSON 解析失败: {}", sanitizeRelayJsonForLog(json));
        } else {
            RelayControlMessage message = RelayControlMessage.fromJson(json);
            handleControlMessage(message, json);
        }
    }

    private void handleControlMessage(RelayControlMessage message, String rawJson) {
        if (message == null || message.type() == null) {
            LOGGER.debug("[FireflyMC] 收到未处理的公开大厅消息: {}", sanitizeRelayJsonForLog(rawJson));
            return;
        }

        switch (message.type()) {
            case "join_accepted" -> {
                if (pendingJoin != null) {
                    pendingJoin.complete(message);
                    pendingJoin = null;
                }
            }
            case "stream_open" -> {
                if (hostBridge != null && message.streamId() != null) {
                    hostBridge.openStream(message.streamId());
                }
            }
            case "stream_close" -> {
                if (hostBridge != null && message.streamId() != null) {
                    hostBridge.closeStream(message.streamId(), "remote_closed");
                }
            }
            case "error" -> {
                if (pendingJoin != null) {
                    pendingJoin.completeExceptionally(new IllegalStateException(message.code() + ": " + message.message()));
                    pendingJoin = null;
                }
                RelayLobbyState.setStatusMessage("Relay 错误: " + message.message());
            }
                case "host_open_ack", "guest_joined", "guest_leave", "p2p_offer", "p2p_answer", "p2p_candidate", "p2p_udp_observed", "p2p_ready", "p2p_failed", "relay_fallback" ->
                    P2PConnectionManager.getInstance().handleControlMessage(message);
            default -> LOGGER.debug("[FireflyMC] 收到未处理的公开大厅消息: {}", sanitizeRelayJsonForLog(rawJson));
        }
    }

    private void handleBinaryFrame(byte[] bytes) {
        boolean handled = false;
        if (guestProxy != null) {
            handled = guestProxy.handleBinary(bytes);
        }
        if (!handled && hostBridge != null) {
            handled = hostBridge.handleBinary(bytes);
        }
        if (!handled) {
            LOGGER.debug("[FireflyMC] 收到未路由的 relay 二进制消息: {} bytes", bytes.length);
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
            LOGGER.debug("[FireflyMC] 已发送公开大厅心跳: {}", sanitizeRelayJsonForLog(json));
        } else if ("stream_open".equals(message.type()) || "stream_close".equals(message.type()) || "guest_leave".equals(message.type())) {
            LOGGER.debug("[FireflyMC] 已发送 relay 流控制消息: {}", sanitizeRelayJsonForLog(json));
        } else {
            LOGGER.info("[FireflyMC] 已发送公开大厅消息: {}", sanitizeRelayJsonForLog(json));
        }
    }

    private static String sanitizeRelayJsonForLog(String json) {
        if (json == null) {
            return null;
        }
        String sanitized = P2P_UDP_HOST_PATTERN.matcher(json).replaceAll("$1<hidden>$3");
        return P2P_CANDIDATE_ADDRESS_PATTERN.matcher(sanitized).replaceAll("$1<hidden>$3");
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
