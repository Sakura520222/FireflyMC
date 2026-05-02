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

/**
 * Guest 侧本地 TCP 代理。
 */
public class RelayGuestProxy {
    private static final Logger LOGGER = LoggerFactory.getLogger(RelayGuestProxy.class);
    private static final int STREAM_ID_LENGTH = 36;

    private final String roomId;
    private final String guestSessionId;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "FireflyMC-Guest-Proxy");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Socket> streamSockets = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

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
        executor.shutdownNow();
    }

    public boolean handleBinary(byte[] bytes) {
        if (bytes.length <= STREAM_ID_LENGTH) {
            return false;
        }
        String streamId = new String(bytes, 0, STREAM_ID_LENGTH, StandardCharsets.UTF_8);
        Socket socket = streamSockets.get(streamId);
        if (socket == null || socket.isClosed()) {
            return false;
        }
        try {
            OutputStream output = socket.getOutputStream();
            output.write(bytes, STREAM_ID_LENGTH, bytes.length - STREAM_ID_LENGTH);
            output.flush();
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
        byte[] buffer = new byte[8192];
        try (InputStream input = socket.getInputStream()) {
            int read;
            while (running.get() && (read = input.read(buffer)) != -1) {
                byte[] frame = new byte[STREAM_ID_LENGTH + read];
                byte[] streamBytes = streamId.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(streamBytes, 0, frame, 0, STREAM_ID_LENGTH);
                System.arraycopy(buffer, 0, frame, STREAM_ID_LENGTH, read);
                RelayLobbyWebSocketClient.getInstance().sendBinary(ByteBuffer.wrap(frame));
            }
        } catch (IOException e) {
            LOGGER.debug("[FireflyMC] Guest 本地代理流关闭: {}", e.getMessage());
        } finally {
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
    }
}
