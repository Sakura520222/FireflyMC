package firefly520.fireflymc.client.relay.p2p;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Guest-side local TCP proxy backed by a P2P UDP channel. */
public class P2PGuestProxy {
    private static final Logger LOGGER = LoggerFactory.getLogger(P2PGuestProxy.class);
    private static final int BUFFER_SIZE = 512 * 1024;
    private static final int SOCKET_BUFFER_SIZE = 2 * 1024 * 1024;
    private static final int STREAM_ID = 1;

    private final ReliableUdpChannel channel;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "FireflyMC-P2P-Guest-Proxy");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private int localPort = -1;

    public P2PGuestProxy(ReliableUdpChannel channel) {
        this.channel = channel;
    }

    public int start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            return localPort;
        }
        serverSocket = new ServerSocket(0);
        serverSocket.setReuseAddress(true);
        localPort = serverSocket.getLocalPort();
        executor.execute(this::acceptLoop);
        LOGGER.info("[FireflyMC] P2P Guest 本地代理已启动: 127.0.0.1:{}", localPort);
        return localPort;
    }

    public void connectMinecraft(Screen parent, String worldName) {
        String addressText = "127.0.0.1:" + localPort;
        ServerAddress address = ServerAddress.parseString(addressText);
        ServerData serverData = new ServerData("FireflyMC P2P - " + worldName, addressText, ServerData.Type.OTHER);
        ConnectScreen.startConnecting(parent, Minecraft.getInstance(), address, serverData, false, null);
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        channel.unregisterStream(STREAM_ID);
        executor.shutdownNow();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
                socket.setSendBufferSize(SOCKET_BUFFER_SIZE);
                channel.registerStream(STREAM_ID, socket.getOutputStream());
                executor.execute(() -> pipeLocalToP2P(socket));
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.warn("[FireflyMC] P2P Guest 接受本地连接失败: {}", e.getMessage());
                }
            }
        }
    }

    private void pipeLocalToP2P(Socket socket) {
        byte[] buffer = new byte[BUFFER_SIZE];
        try (Socket ignored = socket; InputStream input = socket.getInputStream()) {
            int read;
            while (running.get() && (read = input.read(buffer)) != -1) {
                channel.sendData(STREAM_ID, buffer, read);
            }
        } catch (IOException e) {
            LOGGER.debug("[FireflyMC] P2P Guest 本地流关闭: {}", e.getMessage());
        } finally {
            channel.sendFin(STREAM_ID);
            channel.unregisterStream(STREAM_ID);
        }
    }
}
