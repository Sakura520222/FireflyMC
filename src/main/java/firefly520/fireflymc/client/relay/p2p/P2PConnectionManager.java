package firefly520.fireflymc.client.relay.p2p;

import firefly520.fireflymc.client.relay.RelayConfig;
import firefly520.fireflymc.client.relay.RelayControlMessage;
import firefly520.fireflymc.client.relay.RelayLobbyMessage;
import firefly520.fireflymc.client.relay.RelayLobbyWebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Coordinates P2P attempts and keeps relay fallback decisions centralized. */
public final class P2PConnectionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(P2PConnectionManager.class);
    private static final P2PConnectionManager INSTANCE = new P2PConnectionManager();

    private final Map<String, ReliableUdpChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, List<P2PCandidate>> candidates = new ConcurrentHashMap<>();
    private final Map<String, P2PJoinInfo> joinInfos = new ConcurrentHashMap<>();
    private final Map<String, P2PHostBridge> hostBridges = new ConcurrentHashMap<>();
    private volatile int hostLanPort = -1;
    private volatile String hostRoomId;
    private volatile String hostSessionId;
    private volatile String hostToken;
    private volatile String hostUdpHost;
    private volatile int hostUdpPort = -1;

    private P2PConnectionManager() {
    }

    public static P2PConnectionManager getInstance() {
        return INSTANCE;
    }

    public CompletableFuture<P2PResult> tryGuestConnect(P2PJoinInfo info) {
        CompletableFuture<P2PResult> result = new CompletableFuture<>();
        if (!info.isUsable()) {
            LOGGER.warn("[FireflyMC] P2P join info incomplete, fallback to relay: room={}, session={}, udpPort={}",
                    info.roomId(), info.guestSessionId(), info.udpPort());
            result.complete(P2PResult.failed("p2p_join_info_incomplete"));
            return result;
        }
        try {
            ReliableUdpChannel channel = new ReliableUdpChannel();
            channels.put(info.guestSessionId(), channel);
            joinInfos.put(info.guestSessionId(), info);
            channel.setOnClose(() -> onChannelClosed(info.guestSessionId()));
                LOGGER.info("[FireflyMC] P2P Guest 开始连接: room={}, session={}, udpPort={}",
                    info.roomId(), info.guestSessionId(), info.udpPort());
            RelayLobbyWebSocketClient.getInstance().sendControl(
                    RelayLobbyMessage.p2pOffer(info.roomId(), info.guestSessionId(), info.p2pSessionId(), info.p2pToken())
            );
            sendLocalIpv6Candidates(info.roomId(), info.guestSessionId(), info.p2pSessionId(), info.p2pToken(), channel.localPort());
            List<P2PCandidate> candidateList = candidates.getOrDefault(info.guestSessionId(), List.of());
            channel.probeAndPunch(info, candidateList, "guest").whenComplete((success, error) -> {
                if (error != null || !Boolean.TRUE.equals(success)) {
                    RelayLobbyWebSocketClient.getInstance().sendControl(
                            RelayLobbyMessage.relayFallback(info.roomId(), info.guestSessionId(), info.p2pSessionId(), info.p2pToken(), "p2p_timeout")
                    );
                    result.complete(P2PResult.failed("p2p_timeout"));
                    LOGGER.warn("[FireflyMC] P2P Guest 连接失败，回退中继: room={}, session={}", info.roomId(), info.guestSessionId());
                    return;
                }
                RelayLobbyWebSocketClient.getInstance().sendControl(
                        RelayLobbyMessage.p2pReady(info.roomId(), info.guestSessionId(), info.p2pSessionId(), info.p2pToken())
                );
                result.complete(P2PResult.success(-1, channel));
                LOGGER.info("[FireflyMC] P2P Guest 连接成功: room={}, session={}", info.roomId(), info.guestSessionId());
            });
        } catch (SocketException e) {
            LOGGER.warn("[FireflyMC] P2P UDP channel create failed: {}", e.getMessage());
            result.complete(P2PResult.failed("udp_socket_failed"));
        }
        return result;
    }

    public void handleControlMessage(RelayControlMessage message) {
        if (message == null || message.type() == null) {
            return;
        }
        if ("p2p_udp_observed".equals(message.type()) && message.candidate() != null) {
            LOGGER.info("[FireflyMC] P2P 服务端观测本端 UDP: role={}, udpPort={}",
                    message.role(), message.candidate().port());
        } else if ("p2p_candidate".equals(message.type()) && message.candidate() != null) {
            if (message.guestSessionId() == null) {
                return;
            }
            RelayControlMessage.P2PCandidate raw = message.candidate();
            addCandidate(message.guestSessionId(), new P2PCandidate(raw.address(), raw.port()));
            if (message.guestSessionId().equals(hostSessionId)) {
                startHostProbeIfReady();
            }
        } else if ("guest_joined".equals(message.type()) && message.p2pSupported()) {
            if (message.guestSessionId() == null) {
                return;
            }
            hostSessionId = message.guestSessionId();
            hostRoomId = message.roomId();
            hostToken = message.p2pToken();
            startHostProbeIfReady();
        } else if ("host_open_ack".equals(message.type()) && message.p2pSupported()) {
            hostRoomId = message.roomId();
            hostToken = message.p2pToken();
            hostUdpHost = message.p2pUdpHost();
            hostUdpPort = message.p2pUdpPort();
            startHostProbeIfReady();
        } else if ("p2p_ready".equals(message.type())) {
            startHostBridge(message.guestSessionId());
        } else if ("guest_leave".equals(message.type())) {
            handleGuestLeave(message.guestSessionId());
        }
    }

    public void prepareHost(String roomId, int lanPort) {
        this.hostRoomId = roomId;
        this.hostLanPort = lanPort;
    }

    public void stopHost() {
        hostBridges.values().forEach(P2PHostBridge::stop);
        hostBridges.clear();
        if (hostSessionId != null) {
            stop(hostSessionId);
        }
        hostLanPort = -1;
        hostRoomId = null;
        hostSessionId = null;
        hostToken = null;
        hostUdpHost = null;
        hostUdpPort = -1;
    }

    private void startHostProbeIfReady() {
        if (hostRoomId == null || hostSessionId == null || hostToken == null || hostLanPort <= 0
                || hostUdpHost == null || hostUdpHost.isBlank() || hostUdpPort <= 0) {
            return;
        }
        try {
            if (channels.containsKey(hostSessionId)) {
                // 通道已建立，对端候选已通过 addCandidate 动态注入通道，无需重复探测
                return;
            }
            ReliableUdpChannel channel = new ReliableUdpChannel();
            channels.put(hostSessionId, channel);
            P2PJoinInfo info = new P2PJoinInfo(hostRoomId, hostSessionId, hostRoomId, hostToken, hostUdpHost, hostUdpPort, 10);
            joinInfos.put(hostSessionId, info);
            channel.setOnClose(() -> onChannelClosed(info.guestSessionId()));
                LOGGER.info("[FireflyMC] P2P Host 开始探测: room={}, session={}, udpPort={}",
                    hostRoomId, hostSessionId, hostUdpPort);
            sendLocalIpv6Candidates(hostRoomId, hostSessionId, hostRoomId, hostToken, channel.localPort());
            channel.probeAndPunch(info, candidates.getOrDefault(hostSessionId, List.of()), "host").whenComplete((success, error) -> {
                if (error != null || !Boolean.TRUE.equals(success)) {
                    LOGGER.warn("[FireflyMC] P2P Host 探测失败，清理 session={}", info.guestSessionId());
                    stop(info.guestSessionId());
                }
            });
        } catch (SocketException e) {
            LOGGER.warn("[FireflyMC] P2P Host UDP channel create failed: {}", e.getMessage());
        }
    }

    private void startHostBridge(String sessionId) {
        if (sessionId == null) {
            sessionId = hostSessionId;
        }
        ReliableUdpChannel channel = channels.get(sessionId);
        if (channel != null && hostLanPort > 0 && !hostBridges.containsKey(sessionId)) {
            P2PHostBridge hostBridge = new P2PHostBridge(hostLanPort, channel);
            hostBridges.put(sessionId, hostBridge);
            hostBridge.startDefaultStream();
            LOGGER.info("[FireflyMC] P2P Host bridge started for LAN port {}, session={}", hostLanPort, sessionId);
        }
    }

    public void stop(String guestSessionId) {
        ReliableUdpChannel channel = channels.remove(guestSessionId);
        if (channel != null) {
            channel.setOnClose(null);
            channel.close();
        }
        onChannelClosed(guestSessionId);
    }

    /**
     * channel 自身因 idle 超时、收到对端 FIN 或重传上限触发 {@link ReliableUdpChannel#close()} 时回调，
     * 负责清理管理器侧的映射并停止对应的 Host bridge，避免房主端在加入方断开后持续发包。
     */
    private void onChannelClosed(String guestSessionId) {
        channels.remove(guestSessionId);
        candidates.remove(guestSessionId);
        joinInfos.remove(guestSessionId);
        P2PHostBridge bridge = hostBridges.remove(guestSessionId);
        if (bridge != null) {
            bridge.stop();
        }
        if (guestSessionId != null && guestSessionId.equals(hostSessionId)) {
            hostSessionId = null;
        }
    }

    /**
     * 房主收到加入方的 guest_leave 控制消息时调用，立即停止对应 session 的 P2P 通道与 bridge。
     * 走 WebSocket 中继传输，不依赖 P2P UDP，是最可靠的房主端清理信号。
     */
    public void handleGuestLeave(String guestSessionId) {
        if (guestSessionId == null) {
            return;
        }
        LOGGER.info("[FireflyMC] P2P 收到 guest_leave，清理 session={}", guestSessionId);
        stop(guestSessionId);
    }

    /** 加入对端候选（IPv6/IPv4 均可），并同步到已建立的通道；同一地址去重。 */
    private void addCandidate(String sessionId, P2PCandidate candidate) {
        if (candidate == null || !candidate.isValid()) {
            return;
        }
        List<P2PCandidate> list = candidates.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>());
        boolean exists = list.stream().anyMatch(c -> c.port() == candidate.port() && candidate.address().equals(c.address()));
        if (!exists) {
            list.add(candidate);
            LOGGER.info("[FireflyMC] P2P 收到候选: session={}, candidate={}:{}, 候选数={}",
                    sessionId, candidate.address(), candidate.port(), list.size());
        }
        ReliableUdpChannel channel = channels.get(sessionId);
        if (channel != null) {
            channel.addCandidate(candidate);
        }
    }

    /**
     * 向对端上报本机公网 IPv6 candidate，通过 p2p_candidate 信令经服务器转发给对端。
     * IPv6 直连用——双方都有公网 IPv6 时可直接端到端连接，无需 NAT 打洞。
     */
    private void sendLocalIpv6Candidates(String roomId, String sessionId, String p2pSessionId,
                                         String token, int localPort) {
        if (!RelayConfig.RELAY.SINGLEPLAYER_RELAY_P2P_IPV6_ENABLED.get()) {
            return;
        }
        if (localPort <= 0) {
            return;
        }
        List<String> ipv6Addresses = Ipv6AddressCollector.collectGlobalIpv6();
        if (ipv6Addresses.isEmpty()) {
            LOGGER.debug("[FireflyMC] P2P 本机无公网 IPv6，跳过 IPv6 candidate 上报");
            return;
        }
        for (String addr : ipv6Addresses) {
            RelayLobbyWebSocketClient.getInstance().sendControl(
                    RelayLobbyMessage.p2pCandidate(roomId, sessionId, p2pSessionId, token, addr, localPort)
            );
        }
        LOGGER.info("[FireflyMC] P2P 已上报本机 IPv6 candidate: session={}, count={}, localPort={}",
                sessionId, ipv6Addresses.size(), localPort);
    }
}
