package firefly520.fireflymc.client.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Host 侧 LAN TCP 桥接。
 */
public class RelayHostBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(RelayHostBridge.class);
    private static final int STREAM_ID_LENGTH = 36;
    private static final int RELAY_BUFFER_SIZE = 64 * 1024;
    private static final int SOCKET_BUFFER_SIZE = 256 * 1024;

    private final int lanPort;
    private final AtomicLong hostToRelayBytes = new AtomicLong(0);
    private final AtomicLong relayToHostBytes = new AtomicLong(0);
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "FireflyMC-Host-Bridge");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Socket> streamSockets = new ConcurrentHashMap<>();

    public RelayHostBridge(int lanPort) {
        this.lanPort = lanPort;
    }

    public void openStream(String streamId) {
        if (streamSockets.containsKey(streamId)) {
            return;
        }
        executor.execute(() -> {
            try {
                Socket socket = new Socket("127.0.0.1", lanPort);
                socket.setTcpNoDelay(true);
                socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
                socket.setSendBufferSize(SOCKET_BUFFER_SIZE);
                streamSockets.put(streamId, socket);
                LOGGER.info("[FireflyMC] Host 桥接已连接本地 LAN: streamId={}, port={}", streamId, lanPort);
                pipeLanToRelay(streamId, socket);
            } catch (IOException e) {
                LOGGER.warn("[FireflyMC] Host 桥接连接本地 LAN 失败: {}", e.getMessage());
                RelayLobbyWebSocketClient.getInstance().sendControl(RelayLobbyMessage.streamClose(null, streamId, "host_connect_failed"));
            }
        });
    }

    public boolean handleBinary(byte[] bytes) {
        if (bytes.length <= STREAM_ID_LENGTH) {
            return false;
        }
        String streamId = new String(bytes, 0, STREAM_ID_LENGTH, StandardCharsets.UTF_8);
        Socket socket = streamSockets.get(streamId);
        if (socket == null || socket.isClosed()) {
            LOGGER.debug("[FireflyMC] Host 收到二进制但无对应流: streamId={}, bytes={}", streamId, bytes.length);
            return false;
        }
        try {
            int payloadLen = bytes.length - STREAM_ID_LENGTH;
            OutputStream output = socket.getOutputStream();
            output.write(bytes, STREAM_ID_LENGTH, payloadLen);
            output.flush();
            relayToHostBytes.addAndGet(payloadLen);
            if (payloadLen > 1000) {
                LOGGER.debug("[FireflyMC] Host relay→LAN: {} bytes, total relay→host: {} KB", payloadLen, relayToHostBytes.get() / 1024);
            }
            return true;
        } catch (IOException e) {
            closeStream(streamId, "host_write_failed");
            return false;
        }
    }

    public void closeStream(String streamId, String reason) {
        Socket socket = streamSockets.remove(streamId);
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            RelayLobbyWebSocketClient.getInstance().sendControl(RelayLobbyMessage.streamClose(null, streamId, reason));
        }
    }

    public void stop() {
        streamSockets.keySet().forEach(streamId -> closeStream(streamId, "host_stopped"));
        executor.shutdownNow();
    }

    private void pipeLanToRelay(String streamId, Socket socket) {
        byte[] buffer = new byte[RELAY_BUFFER_SIZE];
        try (InputStream input = socket.getInputStream()) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                byte[] frame = new byte[STREAM_ID_LENGTH + read];
                byte[] streamBytes = streamId.getBytes(StandardCharsets.UTF_8);
                System.arraycopy(streamBytes, 0, frame, 0, STREAM_ID_LENGTH);
                System.arraycopy(buffer, 0, frame, STREAM_ID_LENGTH, read);
                RelayLobbyWebSocketClient.getInstance().sendBinary(ByteBuffer.wrap(frame));
                hostToRelayBytes.addAndGet(read);
                if (read > 1000) {
                    LOGGER.debug("[FireflyMC] Host LAN→relay: {} bytes, total host→relay: {} KB", read, hostToRelayBytes.get() / 1024);
                }
            }
        } catch (IOException e) {
            LOGGER.debug("[FireflyMC] Host LAN 流关闭: {}", e.getMessage());
        } finally {
            LOGGER.info("[FireflyMC] Host 流结束: streamId={}, sent {} KB, recv {} KB", streamId, hostToRelayBytes.get() / 1024, relayToHostBytes.get() / 1024);
            closeStream(streamId, "host_local_closed");
        }
    }
}
