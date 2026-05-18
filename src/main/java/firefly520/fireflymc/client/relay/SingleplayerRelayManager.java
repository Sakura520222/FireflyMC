package firefly520.fireflymc.client.relay;

import firefly520.fireflymc.client.relay.RelayConfig;
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
 */
public final class SingleplayerRelayManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SingleplayerRelayManager.class);
    private static final SingleplayerRelayManager INSTANCE = new SingleplayerRelayManager();
    private String currentRoomId;
    private RelayHostBridge hostBridge;

    private SingleplayerRelayManager() {
    }

    public static SingleplayerRelayManager getInstance() {
        return INSTANCE;
    }

    /**
        * 开始将当前单人世界发布到 FireflyMC 联机大厅。
     * 必须在客户端主线程调用。
     */
    public void startHosting() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            mc.execute(this::startHosting);
            return;
        }

        if (!RelayConfig.RELAY.SINGLEPLAYER_RELAY_ENABLED.get()) {
            LOGGER.info("[FireflyMC] 单人世界联机功能未启用");
            return;
        }

        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            LOGGER.warn("[FireflyMC] 当前不在单人世界，无法开启联机");
            return;
        }

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

            int port = server.getPort();
            ClientState.isSingleplayerRelayHosting = true;
            ClientState.singleplayerRelayLanPort = port;

            if (port > 0) {
                LOGGER.info("[FireflyMC] 单人世界 LAN 端口已准备: {}", port);
                publishLobbyRoom(mc, server, port);
            } else {
                LOGGER.warn("[FireflyMC] 单人世界已开放 LAN，但暂未能读取监听端口");
            }
        } catch (Exception e) {
            ClientState.isSingleplayerRelayHosting = false;
            ClientState.singleplayerRelayLanPort = -1;
            LOGGER.error("[FireflyMC] 开启单人世界联机准备失败", e);
        }
    }

    public void stopHosting() {
        if (ClientState.isSingleplayerRelayHosting || ClientState.singleplayerRelayLanPort > 0) {
            LOGGER.info("[FireflyMC] 停止单人世界公开联机状态");
        }
        RelayLobbyWebSocketClient.getInstance().closeRoom();
        P2PConnectionManager.getInstance().stopHost();
        if (hostBridge != null) {
            hostBridge.stop();
            hostBridge = null;
        }
        RelayLobbyWebSocketClient.getInstance().setHostBridge(null);
        currentRoomId = null;
        ClientState.isSingleplayerRelayHosting = false;
        ClientState.singleplayerRelayLanPort = -1;
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
