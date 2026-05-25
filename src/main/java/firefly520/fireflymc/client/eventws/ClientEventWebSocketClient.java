package firefly520.fireflymc.client.eventws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端事件通知 WebSocket 连接。
 */
public final class ClientEventWebSocketClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientEventWebSocketClient.class);
    private static final ClientEventWebSocketClient INSTANCE = new ClientEventWebSocketClient();

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "FireflyMC-Event-Notification");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    private final AtomicBoolean sessionActive = new AtomicBoolean(false);
    private final Queue<ClientEventNotificationMessage> pendingMessages = new ArrayDeque<>();

    private HttpClient httpClient;
    private int httpClientTimeoutMillis;
    private WebSocket webSocket;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> reconnectTask;
    private String activeUrl;
    private int tickCounter;
    private long connectionGeneration;

    private ClientEventWebSocketClient() {
    }

    public static ClientEventWebSocketClient getInstance() {
        return INSTANCE;
    }

    public void onClientTick() {
        if (++tickCounter < 20) {
            return;
        }
        tickCounter = 0;
        executor.execute(() -> {
            if (sessionActive.get()) {
                refreshConnectionState();
            }
        });
    }

    public void send(ClientEventNotificationMessage message) {
        if (message == null) {
            return;
        }
        executor.execute(() -> {
            if (!ClientEventNotificationConfig.enabled()) {
                return;
            }
            sessionActive.set(true);
            if (!refreshConnectionState()) {
                return;
            }
            enqueue(message);
            flushPendingMessages();
        });
    }

    public void close() {
        reset("client_logged_out");
    }

    public void reset() {
        reset("reset");
    }

    private void reset(String reason) {
        executor.execute(() -> {
            sessionActive.set(false);
            tickCounter = 0;
            closeConnection(false, reason);
            reconnectScheduled.set(false);
            connected.set(false);
            connecting.set(false);
            activeUrl = null;
        });
    }

    private boolean refreshConnectionState() {
        if (!ClientEventNotificationConfig.enabled()) {
            closeConnection(false, "disabled");
            return false;
        }

        String configuredUrl = ClientEventNotificationConfig.webSocketUrl();
        if (configuredUrl == null || configuredUrl.isBlank()) {
            closeConnection(false, "empty_url");
            return false;
        }

        if (activeUrl != null && !activeUrl.equals(configuredUrl)) {
            closeConnection(false, "url_changed");
        }

        if (!connected.get() && !connecting.get() && !reconnectScheduled.get()) {
            connect(configuredUrl);
        }
        return true;
    }

    private void connect(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[FireflyMC] 事件通知 WebSocket URL 无效: {}", e.getMessage());
            return;
        }

        connecting.set(true);
        activeUrl = url;
        long generation = ++connectionGeneration;
        LOGGER.info("[FireflyMC] 正在连接事件通知 WebSocket: {}", uri);

        getHttpClient().newWebSocketBuilder()
            .connectTimeout(Duration.ofMillis(ClientEventNotificationConfig.sendTimeoutMillis()))
            .buildAsync(uri, new Listener(generation))
            .whenCompleteAsync((socket, error) -> {
                if (generation != connectionGeneration) {
                    closeStaleSocket(socket);
                    return;
                }
                connecting.set(false);
                if (!sessionActive.get() || !url.equals(activeUrl)) {
                    closeStaleSocket(socket);
                    return;
                }
                if (error != null) {
                    connected.set(false);
                    webSocket = null;
                    LOGGER.warn("[FireflyMC] 事件通知 WebSocket 连接失败: {}", error.getMessage());
                    scheduleReconnect();
                    return;
                }
                webSocket = socket;
            }, executor);
    }

    private HttpClient getHttpClient() {
        int timeoutMillis = ClientEventNotificationConfig.sendTimeoutMillis();
        if (httpClient == null || httpClientTimeoutMillis != timeoutMillis) {
            httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .build();
            httpClientTimeoutMillis = timeoutMillis;
        }
        return httpClient;
    }

    private void enqueue(ClientEventNotificationMessage message) {
        int capacity = ClientEventNotificationConfig.queueCapacity();
        while (pendingMessages.size() >= capacity && !pendingMessages.isEmpty()) {
            pendingMessages.poll();
        }
        pendingMessages.offer(message);
    }

    private void flushPendingMessages() {
        if (webSocket == null || !connected.get()) {
            return;
        }

        while (!pendingMessages.isEmpty() && webSocket != null && connected.get()) {
            ClientEventNotificationMessage message = pendingMessages.poll();
            sendNow(message);
        }
    }

    private void sendNow(ClientEventNotificationMessage message) {
        WebSocket socket = webSocket;
        long generation = connectionGeneration;
        if (socket == null || !connected.get()) {
            return;
        }

        String json = message.toJson();
        try {
            socket.sendText(json, true)
                .orTimeout(ClientEventNotificationConfig.sendTimeoutMillis(), TimeUnit.MILLISECONDS)
                .whenCompleteAsync((sentSocket, error) -> {
                    if (!isCurrentConnection(generation, socket)) {
                        return;
                    }
                    if (error != null) {
                        LOGGER.debug("[FireflyMC] 事件通知发送失败: {}", error.getMessage());
                        markDisconnected();
                        enqueue(message);
                        scheduleReconnect();
                    } else if ("heartbeat".equals(message.type())) {
                        LOGGER.debug("[FireflyMC] 已发送事件通知心跳");
                    } else {
                        LOGGER.info("[FireflyMC] 已发送事件通知: {}", message.type());
                    }
                }, executor);
        } catch (Exception e) {
            if (!isCurrentConnection(generation, socket)) {
                return;
            }
            LOGGER.debug("[FireflyMC] 事件通知发送异常: {}", e.getMessage());
            markDisconnected();
            enqueue(message);
            scheduleReconnect();
        }
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTask = executor.scheduleAtFixedRate(() -> {
            if (!ClientEventNotificationConfig.enabled() || webSocket == null || !connected.get()) {
                return;
            }
            sendNow(ClientEventNotificationMessage.heartbeat());
        }, ClientEventNotificationConfig.heartbeatIntervalMillis(), ClientEventNotificationConfig.heartbeatIntervalMillis(), TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null && !heartbeatTask.isDone()) {
            heartbeatTask.cancel(false);
        }
        heartbeatTask = null;
    }

    private void scheduleReconnect() {
        if (!sessionActive.get() || !ClientEventNotificationConfig.enabled() || !ClientEventNotificationConfig.autoReconnect()) {
            return;
        }
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        reconnectTask = executor.schedule(() -> {
            reconnectScheduled.set(false);
            refreshConnectionState();
        }, ClientEventNotificationConfig.reconnectIntervalMillis(), TimeUnit.MILLISECONDS);
    }

    private void closeConnection(boolean reconnect, String reason) {
        stopHeartbeat();
        stopReconnect();
        pendingMessages.clear();
        connectionGeneration++;
        WebSocket socket = webSocket;
        webSocket = null;
        connected.set(false);
        connecting.set(false);
        activeUrl = null;
        if (socket != null) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, reason);
            } catch (Exception e) {
                LOGGER.debug("[FireflyMC] 关闭事件通知 WebSocket 失败: {}", e.getMessage());
            }
        }
        if (reconnect) {
            scheduleReconnect();
        }
    }

    private void stopReconnect() {
        if (reconnectTask != null && !reconnectTask.isDone()) {
            reconnectTask.cancel(false);
        }
        reconnectTask = null;
        reconnectScheduled.set(false);
    }

    private void markDisconnected() {
        stopHeartbeat();
        connected.set(false);
        webSocket = null;
    }

    private boolean isCurrentConnection(long generation, WebSocket socket) {
        return sessionActive.get() && generation == connectionGeneration && socket != null && socket == webSocket;
    }

    private void closeStaleSocket(WebSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "stale_connection");
        } catch (Exception e) {
            LOGGER.debug("[FireflyMC] 关闭过期事件通知 WebSocket 失败: {}", e.getMessage());
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final long generation;

        private Listener(long generation) {
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            executor.execute(() -> {
                if (generation != connectionGeneration || !sessionActive.get()) {
                    closeStaleSocket(webSocket);
                    return;
                }
                ClientEventWebSocketClient.this.webSocket = webSocket;
                connected.set(true);
                reconnectScheduled.set(false);
                LOGGER.info("[FireflyMC] 事件通知 WebSocket 连接成功");
                startHeartbeat();
                flushPendingMessages();
            });
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            executor.execute(() -> {
                if (generation != connectionGeneration) {
                    return;
                }
                LOGGER.info("[FireflyMC] 事件通知 WebSocket 连接关闭: {} - {}", statusCode, reason);
                markDisconnected();
                scheduleReconnect();
            });
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            executor.execute(() -> {
                if (generation != connectionGeneration) {
                    return;
                }
                LOGGER.warn("[FireflyMC] 事件通知 WebSocket 连接错误: {}", error.getMessage());
                markDisconnected();
                scheduleReconnect();
            });
        }
    }
}
