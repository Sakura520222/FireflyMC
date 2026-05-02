package firefly520.fireflymc.client.relay;

import firefly520.fireflymc.Config;
import firefly520.fireflymc.client.ClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

/**
 * 阶段一：单人世界联机的 LAN 暴露准备。
 *
 * 后续阶段会在获取到 LAN 端口后接入 WebSocket 中继和公开大厅。
 */
public final class SingleplayerRelayManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(SingleplayerRelayManager.class);
    private static final SingleplayerRelayManager INSTANCE = new SingleplayerRelayManager();
    private String currentRoomId;

    private SingleplayerRelayManager() {
    }

    public static SingleplayerRelayManager getInstance() {
        return INSTANCE;
    }

    /**
     * 开始为当前单人世界做 LAN 暴露准备。
     * 必须在客户端主线程调用。
     */
    public void startHosting() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) {
            mc.execute(this::startHosting);
            return;
        }

        if (!Config.CLIENT.SINGLEPLAYER_RELAY_ENABLED.get()) {
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
                boolean allowCommands = Config.CLIENT.SINGLEPLAYER_RELAY_ALLOW_COMMANDS.get();
                int requestedPort = findAvailablePort();
                boolean published = server.publishServer(GameType.SURVIVAL, allowCommands, requestedPort);
                LOGGER.info("[FireflyMC] 单人世界开放 LAN 结果: {}, requestedPort={}, allowCommands={}",
                        published, requestedPort, allowCommands);
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

    /**
     * 阶段一仅清理本模组状态。原版 LAN 监听会随 IntegratedServer 退出自动关闭。
     */
    public void stopHosting() {
        if (ClientState.isSingleplayerRelayHosting || ClientState.singleplayerRelayLanPort > 0) {
            LOGGER.info("[FireflyMC] 停止单人世界联机准备状态");
        }
        RelayLobbyWebSocketClient.getInstance().closeRoom();
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
        int maxPlayers = Config.CLIENT.SINGLEPLAYER_RELAY_MAX_PLAYERS.get();

        RelayLobbyMessage message = RelayLobbyMessage.hostOpen(
                currentRoomId,
                worldName,
                playerName,
                playerUuid,
                port,
                maxPlayers
        );
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
