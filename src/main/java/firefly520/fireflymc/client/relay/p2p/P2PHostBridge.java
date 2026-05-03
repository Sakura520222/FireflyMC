package firefly520.fireflymc.client.relay.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Host-side bridge from P2P UDP streams to local integrated-server LAN TCP. */
public class P2PHostBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(P2PHostBridge.class);
    private static final int BUFFER_SIZE = 512 * 1024;
    private static final int SOCKET_BUFFER_SIZE = 2 * 1024 * 1024;
    private static final int STREAM_ID = 1;

    private final int lanPort;
    private final ReliableUdpChannel channel;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "FireflyMC-P2P-Host-Bridge");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Integer, Socket> sockets = new ConcurrentHashMap<>();
    private final AtomicLong lanToP2pBytes = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(true);

    public P2PHostBridge(int lanPort, ReliableUdpChannel channel) {
        this.lanPort = lanPort;
        this.channel = channel;
    }

    public void startDefaultStream() {
        LOGGER.info("[FireflyMC] P2P Host 正在打开默认 stream 到本地 LAN: port={}", lanPort);
        openStream(STREAM_ID);
    }

    public void stop() {
        running.set(false);
        sockets.values().forEach(socket -> {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        });
        sockets.clear();
        channel.unregisterStream(STREAM_ID);
        executor.shutdownNow();
    }

    private void openStream(int streamId) {
        if (sockets.containsKey(streamId)) {
            return;
        }
        executor.execute(() -> {
            try {
                Socket socket = new Socket("127.0.0.1", lanPort);
                socket.setTcpNoDelay(true);
                socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
                socket.setSendBufferSize(SOCKET_BUFFER_SIZE);
                sockets.put(streamId, socket);
                channel.registerStream(streamId, socket.getOutputStream());
                LOGGER.info("[FireflyMC] P2P Host 已连接本地 LAN: streamId={}, port={}", streamId, lanPort);
                pipeLanToP2P(streamId, socket);
            } catch (IOException e) {
                LOGGER.warn("[FireflyMC] P2P Host 连接本地 LAN 失败: {}", e.getMessage());
            }
        });
    }

    private void pipeLanToP2P(int streamId, Socket socket) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try (Socket ignored = socket; InputStream input = socket.getInputStream()) {
            int read;
            while (running.get() && (read = input.read(buffer)) != -1) {
                if (!running.get()) break;
                channel.sendData(streamId, buffer, read);
                lanToP2pBytes.addAndGet(read);
                if (lanToP2pBytes.get() <= 8192 || read > 1000) {
                    LOGGER.info("[FireflyMC] P2P Host LAN→UDP: {} bytes, total={} KB", read, lanToP2pBytes.get() / 1024);
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                LOGGER.debug("[FireflyMC] P2P Host LAN 流关闭: {}", e.getMessage());
            }
        } finally {
            sockets.remove(streamId);
            if (channel.isRunning()) {
                channel.sendFin(streamId);
            }
            channel.unregisterStream(streamId);
        }
    }
}
