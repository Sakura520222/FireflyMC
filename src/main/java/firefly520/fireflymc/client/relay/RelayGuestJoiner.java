package firefly520.fireflymc.client.relay;

import firefly520.fireflymc.Config;
import firefly520.fireflymc.client.relay.p2p.P2PConnectionManager;
import firefly520.fireflymc.client.relay.p2p.P2PGuestProxy;
import firefly520.fireflymc.client.relay.p2p.P2PJoinInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Guest 加入公开房间流程。
 */
public final class RelayGuestJoiner {
    private static final Logger LOGGER = LoggerFactory.getLogger(RelayGuestJoiner.class);
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "FireflyMC-Guest-Joiner");
        thread.setDaemon(true);
        return thread;
    });
    private static final long CONNECT_TIMEOUT_SECONDS = 20;

    private static RelayGuestProxy activeProxy;
    private static String pendingRoomId;
    private static String pendingGuestSessionId;
    private static final AtomicBoolean connectingToRelayRoom = new AtomicBoolean(false);
    private static ScheduledFuture<?> connectTimeoutTask;

    private RelayGuestJoiner() {
    }

    public static void join(Screen parent, RelayLobbyRoom room) {
        Minecraft mc = Minecraft.getInstance();
        RelayLobbyState.setStatusMessage("正在加入房间: " + room.worldName());

        String playerName = mc.getUser().getName();
        String playerUuid = mc.getUser().getProfileId().toString();
        RelayLobbyWebSocketClient.getInstance()
                .joinRoom(room, playerName, playerUuid)
                .whenComplete((joinAccepted, error) -> mc.execute(() -> {
                    if (error != null) {
                        RelayLobbyState.setStatusMessage("加入失败: " + error.getMessage());
                        LOGGER.warn("[FireflyMC] 加入公开房间失败: {}", error.getMessage());
                        return;
                    }
                    String guestSessionId = joinAccepted.guestSessionId();
                    try {
                        if (Config.CLIENT.SINGLEPLAYER_RELAY_P2P_ENABLED.get()
                                && joinAccepted.p2pSupported()
                            && "udp_reliable_v1".equals(joinAccepted.p2pTransport())) {
                            RelayLobbyState.setStatusMessage("正在尝试 P2P 连接...");
                            P2PJoinInfo info = new P2PJoinInfo(
                                    room.roomId(),
                                    guestSessionId,
                                    joinAccepted.p2pSessionId(),
                                    joinAccepted.p2pToken(),
                                    joinAccepted.p2pUdpHost(),
                                    joinAccepted.p2pUdpPort(),
                                    joinAccepted.p2pConnectTimeoutSeconds() > 0
                                            ? joinAccepted.p2pConnectTimeoutSeconds()
                                            : Config.CLIENT.SINGLEPLAYER_RELAY_P2P_CONNECT_TIMEOUT_SECONDS.get()
                            );
                            P2PConnectionManager.getInstance().tryGuestConnect(info)
                                    .whenComplete((result, p2pError) -> mc.execute(() -> {
                                        try {
                                            if (p2pError == null && result != null && result.success()) {
                                                RelayLobbyState.setStatusMessage("P2P 连接成功");
                                                P2PGuestProxy proxy = new P2PGuestProxy(result.channel());
                                                proxy.start();
                                                proxy.connectMinecraft(parent, room.worldName());
                                                return;
                                            }
                                            RelayLobbyState.setStatusMessage("P2P 不可用，正在切换中继...");
                                            startProxyAndConnect(parent, room, guestSessionId);
                                        } catch (Exception fallbackError) {
                                            RelayLobbyWebSocketClient.getInstance().sendControl(
                                                    RelayLobbyMessage.guestLeave(room.roomId(), guestSessionId, "proxy_start_failed")
                                            );
                                            RelayLobbyState.setStatusMessage("连接失败: " + fallbackError.getMessage());
                                            LOGGER.warn("[FireflyMC] 回退中继失败: {}", fallbackError.getMessage());
                                        }
                                    }));
                            return;
                        }
                        startProxyAndConnect(parent, room, guestSessionId);
                    } catch (Exception e) {
                        RelayLobbyWebSocketClient.getInstance().sendControl(
                                RelayLobbyMessage.guestLeave(room.roomId(), guestSessionId, "proxy_start_failed")
                        );
                        RelayLobbyState.setStatusMessage("连接失败: " + e.getMessage());
                        LOGGER.warn("[FireflyMC] 启动本地代理失败: {}", e.getMessage());
                    }
                }));
    }

    public static void stopActiveRelay(String reason) {
        if (connectingToRelayRoom.get() && activeProxy != null && !activeProxy.hasAcceptedClientConnection()) {
            LOGGER.debug("[FireflyMC] 忽略连接阶段的断开事件，等待本地代理连接或超时: {}", reason);
            return;
        }
        if (activeProxy != null) {
            activeProxy.stop(reason);
            activeProxy = null;
            pendingRoomId = null;
            pendingGuestSessionId = null;
            connectingToRelayRoom.set(false);
            cancelConnectTimeout();
            return;
        }
        if (pendingRoomId != null && pendingGuestSessionId != null) {
            RelayLobbyWebSocketClient.getInstance().sendControl(
                    RelayLobbyMessage.guestLeave(pendingRoomId, pendingGuestSessionId, reason)
            );
            pendingRoomId = null;
            pendingGuestSessionId = null;
            connectingToRelayRoom.set(false);
            cancelConnectTimeout();
        }
    }

    public static void markProxyAcceptedConnection(RelayGuestProxy proxy) {
        if (activeProxy == proxy) {
            connectingToRelayRoom.set(false);
            cancelConnectTimeout();
        }
    }

    private static void startProxyAndConnect(Screen parent, RelayLobbyRoom room, String guestSessionId) throws Exception {
        if (activeProxy != null) {
            activeProxy.stop();
        }

        activeProxy = new RelayGuestProxy(room.roomId(), guestSessionId);
        int port = activeProxy.start();
        RelayLobbyWebSocketClient.getInstance().setGuestProxy(activeProxy);
        pendingRoomId = room.roomId();
        pendingGuestSessionId = guestSessionId;
        connectingToRelayRoom.set(true);
        scheduleConnectTimeout(activeProxy);

        String addressText = "127.0.0.1:" + port;
        ServerAddress address = ServerAddress.parseString(addressText);
        ServerData serverData = new ServerData("FireflyMC - " + room.worldName(), addressText, ServerData.Type.OTHER);
        RelayLobbyState.setStatusMessage("正在连接本地代理: " + addressText);
        LOGGER.info("[FireflyMC] 正在通过本地代理加入房间: roomId={}, address={}", room.roomId(), addressText);

        ConnectScreen.startConnecting(
                parent,
                Minecraft.getInstance(),
                address,
                serverData,
                false,
                null
        );
    }

    private static void scheduleConnectTimeout(RelayGuestProxy proxy) {
        cancelConnectTimeout();
        connectTimeoutTask = EXECUTOR.schedule(() -> Minecraft.getInstance().execute(() -> {
            if (activeProxy == proxy && connectingToRelayRoom.get() && !proxy.hasAcceptedClientConnection()) {
                LOGGER.warn("[FireflyMC] 加入公开房间超时，本地客户端未连接代理，释放房间名额");
                forceStopActiveRelay("connect_timeout");
                RelayLobbyState.setStatusMessage("连接超时，已释放房间名额");
            }
        }), CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static void forceStopActiveRelay(String reason) {
        if (activeProxy != null) {
            activeProxy.stop(reason);
            activeProxy = null;
        } else if (pendingRoomId != null && pendingGuestSessionId != null) {
            RelayLobbyWebSocketClient.getInstance().sendControl(
                    RelayLobbyMessage.guestLeave(pendingRoomId, pendingGuestSessionId, reason)
            );
        }
        pendingRoomId = null;
        pendingGuestSessionId = null;
        connectingToRelayRoom.set(false);
        cancelConnectTimeout();
    }

    private static void cancelConnectTimeout() {
        if (connectTimeoutTask != null) {
            connectTimeoutTask.cancel(false);
            connectTimeoutTask = null;
        }
    }
}
