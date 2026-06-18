package firefly520.fireflymc.client.relay.p2p;

import firefly520.fireflymc.client.ClientState;
import firefly520.fireflymc.client.relay.RelayLobbyMessage;
import firefly520.fireflymc.client.relay.RelayLobbyWebSocketClient;
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
import java.util.concurrent.atomic.AtomicLong;

/** Guest-side local TCP proxy backed by a P2P UDP channel. */
public class P2PGuestProxy {
    private static final Logger LOGGER = LoggerFactory.getLogger(P2PGuestProxy.class);
    private static final int BUFFER_SIZE = 512 * 1024;
    private static final int SOCKET_BUFFER_SIZE = 2 * 1024 * 1024;
    private static final int STREAM_ID = 1;

    private final ReliableUdpChannel channel;
    private final String roomId;
    private final String guestSessionId;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "FireflyMC-P2P-Guest-Proxy");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean acceptedClientConnection = new AtomicBoolean(false);
    private final AtomicBoolean leaveSent = new AtomicBoolean(false);
    private final AtomicLong localToP2pBytes = new AtomicLong(0);
    private Runnable onClientAccepted;
    private ServerSocket serverSocket;
    private int localPort = -1;
    private volatile Socket clientSocket;

    public P2PGuestProxy(ReliableUdpChannel channel, String roomId, String guestSessionId) {
        this.channel = channel;
        this.roomId = roomId;
        this.guestSessionId = guestSessionId;
        // P2P 通道断开（如房主退出导致对端不可达）时，立即关闭本地 Minecraft 连接，
        // 否则 MC 的 TCP 连接到本地代理保持，玩家会滞留在无服务器响应的"幽灵世界"。
        channel.addCloseHandler(this::onChannelClosed);
    }

    /** P2P 通道断开时关闭本地 Minecraft 连接，使 MC 检测到断开并返回菜单。 */
    private void onChannelClosed() {
        Socket socket = clientSocket;
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        LOGGER.info("[FireflyMC] P2P 通道断开，已断开本地 Minecraft 连接");
    }

    public String roomId() {
        return roomId;
    }

    public String guestSessionId() {
        return guestSessionId;
    }

    public boolean hasAcceptedClientConnection() {
        return acceptedClientConnection.get();
    }

    public void setOnClientAccepted(Runnable onClientAccepted) {
        this.onClientAccepted = onClientAccepted;
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
        // 标记为联机大厅发起，让 ConnectScreenMixin 放行（关机时仅拦截原版多人菜单连接）
        ClientState.isLobbyInitiatedConnection = true;
        try {
            ConnectScreen.startConnecting(parent, Minecraft.getInstance(), address, serverData, false, null);
        } finally {
            ClientState.isLobbyInitiatedConnection = false;
        }
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
        channel.unregisterStream(STREAM_ID);
        channel.close();
        sendGuestLeave(reason);
        executor.shutdownNow();
    }

    private void sendGuestLeave(String reason) {
        if (roomId == null || guestSessionId == null) {
            return;
        }
        if (leaveSent.compareAndSet(false, true)) {
            RelayLobbyWebSocketClient.getInstance().sendControl(
                    RelayLobbyMessage.guestLeave(roomId, guestSessionId, reason)
            );
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                clientSocket = socket;
                socket.setTcpNoDelay(true);
                socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
                socket.setSendBufferSize(SOCKET_BUFFER_SIZE);
                acceptedClientConnection.set(true);
                if (onClientAccepted != null) {
                    onClientAccepted.run();
                }
                channel.registerStream(STREAM_ID, socket.getOutputStream());
                LOGGER.info("[FireflyMC] P2P Guest 本地 Minecraft 已连接代理: streamId={}", STREAM_ID);
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
                localToP2pBytes.addAndGet(read);
                if (localToP2pBytes.get() <= 8192 || read > 1000) {
                    LOGGER.info("[FireflyMC] P2P Guest 本地→UDP: {} bytes, total={} KB", read, localToP2pBytes.get() / 1024);
                }
            }
        } catch (IOException e) {
            LOGGER.debug("[FireflyMC] P2P Guest 本地流关闭: {}", e.getMessage());
        } finally {
            if (channel.isRunning()) {
                channel.sendFin(STREAM_ID);
            }
            channel.unregisterStream(STREAM_ID);
        }
    }
}
