package firefly520.fireflymc.client.relay;

import firefly520.fireflymc.client.ClientState;
import firefly520.fireflymc.client.relay.p2p.P2PConnectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

/**
 * 单人世界公开大厅发布与中继桥接管理。
 *
 * <h3>HostingState 保证范围 (路线 A)</h3>
 * <p>{@code HostingState} 是联机操作意图与 UI 展示的唯一权威状态源，用于防止公开入口重复调用、
 * 保证客户端主线程上的启停顺序，并为界面提供四态信息。</p>
 * <p><b>它不代表底层 WebSocket executor、P2P 回调和 HostBridge 异步资源已经完全收敛，
 * 也不解决现有 relay 核心的跨线程迟到回调竞态。</b></p>
 * <p>因此：</p>
 * <ul>
 *   <li>{@code HOSTING} 表示主线程已执行完启动同步流程并发布托管意图；</li>
 *   <li>{@code STOPPED} 表示 Manager 当前可见清理流程已执行完成，并允许新的用户操作；</li>
 *   <li><b>不构成</b>底层所有异步回调已终止的证明（executor 队列、WebSocket 回调触发的 P2P probe、
 *       HostBridge stream 可能在停止后迟到执行）。</li>
 * </ul>
 * <p>完整治理列入未来专项（路线 B）。</p>
 */
public final class SingleplayerRelayManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SingleplayerRelayManager.class);
    private static final SingleplayerRelayManager INSTANCE = new SingleplayerRelayManager();
    public enum HostingState { STOPPED, STARTING, HOSTING, STOPPING }

    private final java.util.concurrent.atomic.AtomicReference<HostingState> hostingState =
            new java.util.concurrent.atomic.AtomicReference<>(HostingState.STOPPED);
    private volatile String currentRoomId;
    private RelayHostBridge hostBridge;

    private SingleplayerRelayManager() {
    }

    public static SingleplayerRelayManager getInstance() {
        return INSTANCE;
    }

    /** @return 当前联机托管状态（权威状态源）。 */
    public HostingState getHostingState() { return hostingState.get(); }

    /** @return 当前房间 ID，停止后为 {@code null}。由渲染线程读取、主线程写入。 */
    public String getCurrentRoomId() { return currentRoomId; }

    /**
     * 开始将当前单人世界发布到 FireflyMC 联机大厅。
     * 非主线程调用会被调度到客户端主线程执行。
     *
     * <p><b>主线程不变量：</b>本次新增的所有 {@code hostingState} 写操作（含 {@code STARTING → HOSTING}）
     * 必须发生在客户端主线程。当前 {@code startHostingOnClientThread} 的所有 CAS 均在主线程同步执行，
     * 满足该不变量。若未来将启动完成点移至异步回调（如 WebSocket 回调线程），
     * 执行 {@code STARTING → HOSTING} 前必须通过 {@code Minecraft.execute(...)} 回到客户端主线程；
     * 后台回调不得直接写 {@code hostingState} 或 {@code ClientState.isSingleplayerRelayHosting}。</p>
     */
    public void startHosting() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            mc.execute(this::startHosting);
            return;
        }
        startHostingOnClientThread();
    }

    private void startHostingOnClientThread() {
        Minecraft mc = Minecraft.getInstance();
        if (!RelayConfig.RELAY.SINGLEPLAYER_RELAY_ENABLED.get()) {
            LOGGER.info("[FireflyMC] 单人世界联机功能未启用");
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            LOGGER.warn("[FireflyMC] 当前不在单人世界，无法开启联机");
            return;
        }
        if (!hostingState.compareAndSet(HostingState.STOPPED, HostingState.STARTING)) {
            LOGGER.debug("[FireflyMC] startHosting 重入拒绝,当前状态={}", hostingState.get());
            return;
        }
        int port;
        try {
            if (!server.isPublished()) {
                boolean allowCommands = RelayConfig.RELAY.SINGLEPLAYER_RELAY_ALLOW_COMMANDS.get();
                int requestedPort = findAvailablePort();
                boolean published = server.publishServer(GameType.SURVIVAL, allowCommands, requestedPort);
                server.setUsesAuthentication(false);
                server.setPreventProxyConnections(false);
                LOGGER.info("[FireflyMC] 单人世界开放 LAN 结果: {}, requestedPort={}, allowCommands={}",
                        published, requestedPort, allowCommands);
            } else {
                server.setUsesAuthentication(false);
                server.setPreventProxyConnections(false);
            }
            port = server.getPort();
            ClientState.singleplayerRelayLanPort = port;
            if (port <= 0) {
                LOGGER.warn("[FireflyMC] 单人世界已开放 LAN，但暂未能读取监听端口");
                hostingState.compareAndSet(HostingState.STARTING, HostingState.STOPPED);
                return;
            }
            LOGGER.info("[FireflyMC] 单人世界 LAN 端口已准备: {}", port);
            publishLobbyRoom(mc, server, port);
        } catch (Exception e) {
            ClientState.singleplayerRelayLanPort = -1;
            LOGGER.error("[FireflyMC] 开启单人世界联机准备失败", e);
            hostingState.compareAndSet(HostingState.STARTING, HostingState.STOPPED);
            return;
        }
        if (!hostingState.compareAndSet(HostingState.STARTING, HostingState.HOSTING)) {
            LOGGER.debug("Ignoring hosting completion because state is {}", hostingState.get());
            return;
        }
        ClientState.isSingleplayerRelayHosting = true;
    }

    /**
     * 停止单人世界公开联机。非主线程调用会被调度到客户端主线程执行。
     *
     * <p><b>主线程不变量：</b>所有 {@code hostingState} 写操作必须发生在客户端主线程。
     * 若未来将停止完成点移至异步回调，必须先通过 {@code Minecraft.execute(...)}
     * 回到客户端主线程再写入 {@code hostingState}。</p>
     */
    public void stopHosting() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            mc.execute(this::stopHosting);
            return;
        }
        stopHostingOnClientThread();
    }

    private void stopHostingOnClientThread() {
        while (true) {
            HostingState current = hostingState.get();
            if (current == HostingState.STOPPED || current == HostingState.STOPPING) return;
            if (hostingState.compareAndSet(current, HostingState.STOPPING)) break;
        }
        if (ClientState.isSingleplayerRelayHosting || ClientState.singleplayerRelayLanPort > 0) {
            LOGGER.info("[FireflyMC] 停止单人世界公开联机状态");
        }
        try {
            RelayLobbyWebSocketClient.getInstance().closeRoom();
            P2PConnectionManager.getInstance().stopHost();
            if (hostBridge != null) {
                hostBridge.stop();
                hostBridge = null;
            }
            RelayLobbyWebSocketClient.getInstance().setHostBridge(null);
        } finally {
            currentRoomId = null;
            ClientState.isSingleplayerRelayHosting = false;
            ClientState.singleplayerRelayLanPort = -1;
            hostingState.set(HostingState.STOPPED);
        }
    }

    private void publishLobbyRoom(Minecraft mc, IntegratedServer server, int port) {
        if (currentRoomId == null) {
            currentRoomId = UUID.randomUUID().toString();
        }

        String worldName = resolveWorldName(server);
        String playerName = mc.getUser().getName();
        String playerUuid = mc.getUser().getProfileId().toString();
        int maxPlayers = RelayConfig.RELAY.SINGLEPLAYER_RELAY_MAX_PLAYERS.get();

        RelayLobbyMessage message = RelayConfig.RELAY.SINGLEPLAYER_RELAY_P2P_ENABLED.get()
            ? RelayLobbyMessage.hostOpenP2P(
                currentRoomId,
                worldName,
                playerName,
                playerUuid,
                port,
                maxPlayers,
                currentRoomId
            )
            : RelayLobbyMessage.hostOpen(
                currentRoomId,
                worldName,
                playerName,
                playerUuid,
                port,
                maxPlayers
            );
            if (hostBridge != null) {
                hostBridge.stop();
            }
            hostBridge = new RelayHostBridge(port);
            RelayLobbyWebSocketClient.getInstance().setHostBridge(hostBridge);
            P2PConnectionManager.getInstance().prepareHost(currentRoomId, port);
        RelayLobbyWebSocketClient.getInstance().publishRoom(message, currentRoomId);
        LOGGER.info("[FireflyMC] 已准备发布公开房间: roomId={}, worldName={}, maxPlayers={}",
                currentRoomId, worldName, maxPlayers);
    }

    private String resolveWorldName(IntegratedServer server) {
        try {
            String name = server.getWorldData().getLevelName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (Exception ignored) {
            // 使用降级名称
        }
        return "我的单人世界";
    }

    private int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
