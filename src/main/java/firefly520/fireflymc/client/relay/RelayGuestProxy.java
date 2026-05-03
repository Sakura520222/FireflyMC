package firefly520.fireflymc.client.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Guest 侧本地 TCP 代理。
 */
public class RelayGuestProxy {
    private static final Logger LOGGER = LoggerFactory.getLogger(RelayGuestProxy.class);
    private static final int STREAM_ID_LENGTH = 36;
    private static final int RELAY_BUFFER_SIZE = 64 * 1024;
    private static final int SOCKET_BUFFER_SIZE = 256 * 1024;

    private final String roomId;
    private final String guestSessionId;
    private final AtomicBoolean leaveSent = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "FireflyMC-Guest-Proxy");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Socket> streamSockets = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean acceptedClientConnection = new AtomicBoolean(false);
    private final AtomicLong guestToRelayBytes = new AtomicLong(0);
    private final AtomicLong relayToGuestBytes = new AtomicLong(0);

    private ServerSocket serverSocket;
    private int localPort = -1;

    public RelayGuestProxy(String roomId, String guestSessionId) {
        this.roomId = roomId;
        this.guestSessionId = guestSessionId;
    }

    public int start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return localPort;
        }

        serverSocket = new ServerSocket(0);
        serverSocket.setReuseAddress(true);
        localPort = serverSocket.getLocalPort();
        executor.execute(this::acceptLoop);
        LOGGER.info("[FireflyMC] Guest 本地代理已启动: 127.0.0.1:{}", localPort);
        return localPort;
    }

    public void stop() {
        stop("guest_stopped");
    }

    public void stop(String reason) {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        streamSockets.values().forEach(socket -> {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        });
        streamSockets.clear();
        sendGuestLeave(reason);
        RelayLobbyWebSocketClient.getInstance().clearGuestProxy(this);
        executor.shutdownNow();
    }

    public String guestSessionId() {
        return guestSessionId;
    }

    public String roomId() {
        return roomId;
    }

    public boolean hasAcceptedClientConnection() {
        return acceptedClientConnection.get();
    }

    public boolean handleBinary(byte[] bytes) {
        if (bytes.length <= STREAM_ID_LENGTH) {
            return false;
        }
        String streamId = new String(bytes, 0, STREAM_ID_LENGTH, StandardCharsets.UTF_8);
        Socket socket = streamSockets.get(streamId);
        if (socket == null || socket.isClosed()) {
            LOGGER.debug("[FireflyMC] Guest 收到二进制但无对应流: streamId={}, bytes={}", streamId, bytes.length);
            return false;
        }
        try {
            int payloadLen = bytes.length - STREAM_ID_LENGTH;
            OutputStream output = socket.getOutputStream();
            output.write(bytes, STREAM_ID_LENGTH, payloadLen);
            output.flush();
            relayToGuestBytes.addAndGet(payloadLen);
            if (payloadLen > 1000) {
                LOGGER.debug("[FireflyMC] Guest relay→本地: {} bytes, total relay→guest: {} KB", payloadLen, relayToGuestBytes.get() / 1024);
            }
            return true;
        } catch (IOException e) {
            closeStream(streamId, "write_failed");
            return false;
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
                socket.setSendBufferSize(SOCKET_BUFFER_SIZE);
                acceptedClientConnection.set(true);
                RelayGuestJoiner.markProxyAcceptedConnection(this);
                String streamId = UUID.randomUUID().toString();
                streamSockets.put(streamId, socket);
                RelayLobbyWebSocketClient.getInstance().sendControl(RelayLobbyMessage.streamOpen(roomId, guestSessionId, streamId));
                executor.execute(() -> pipeLocalToRelay(streamId, socket));
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.warn("[FireflyMC] Guest 本地代理接受连接失败: {}", e.getMessage());
                }
            }
        }
    }

    private void pipeLocalToRelay(String streamId, Socket socket) {
        byte[] buffer = new byte[RELAY_BUFFER_SIZE];
        try (InputStream input = socket.getInputStream()) {
            int read;
            while (running.get() && (read = input.read(buffer)) != -1) {
                byte[] frame = new byte[STREAM_ID_LENGTH + read];
                byte[] streamBytes = streamId.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(streamBytes, 0, frame, 0, STREAM_ID_LENGTH);
                System.arraycopy(buffer, 0, frame, STREAM_ID_LENGTH, read);
                RelayLobbyWebSocketClient.getInstance().sendBinary(ByteBuffer.wrap(frame));
                guestToRelayBytes.addAndGet(read);
                if (read > 1000) {
                    LOGGER.debug("[FireflyMC] Guest 本地→relay: {} bytes, total guest→relay: {} KB", read, guestToRelayBytes.get() / 1024);
                }
            }
        } catch (IOException e) {
            LOGGER.debug("[FireflyMC] Guest 本地代理流关闭: {}", e.getMessage());
        } finally {
            LOGGER.info("[FireflyMC] Guest 流结束: streamId={}, sent {} KB, recv {} KB", streamId, guestToRelayBytes.get() / 1024, relayToGuestBytes.get() / 1024);
            closeStream(streamId, "local_closed");
        }
    }

    private void closeStream(String streamId, String reason) {
        Socket socket = streamSockets.remove(streamId);
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            RelayLobbyWebSocketClient.getInstance().sendControl(RelayLobbyMessage.streamClose(roomId, streamId, reason));
        }
        if (streamSockets.isEmpty()) {
            sendGuestLeave(reason);
        }
    }

    private void sendGuestLeave(String reason) {
        if (leaveSent.compareAndSet(false, true)) {
            RelayLobbyWebSocketClient.getInstance().sendControl(RelayLobbyMessage.guestLeave(roomId, guestSessionId, reason));
        }
    }
}
