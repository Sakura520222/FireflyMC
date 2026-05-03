package firefly520.fireflymc.client.relay.p2p;

import firefly520.fireflymc.client.relay.RelayControlMessage;
import firefly520.fireflymc.client.relay.RelayLobbyMessage;
import firefly520.fireflymc.client.relay.RelayLobbyWebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Coordinates P2P attempts and keeps relay fallback decisions centralized. */
public final class P2PConnectionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(P2PConnectionManager.class);
    private static final P2PConnectionManager INSTANCE = new P2PConnectionManager();

    private final Map<String, ReliableUdpChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, P2PCandidate> candidates = new ConcurrentHashMap<>();
    private final Map<String, P2PJoinInfo> joinInfos = new ConcurrentHashMap<>();
    private final Map<String, String> loggedCandidates = new ConcurrentHashMap<>();
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
            LOGGER.warn("[FireflyMC] P2P join info incomplete, fallback to relay: room={}, session={}, udp={}:{}",
                    info.roomId(), info.guestSessionId(), info.udpHost(), info.udpPort());
            result.complete(P2PResult.failed("p2p_join_info_incomplete"));
            return result;
        }
        try {
            ReliableUdpChannel channel = new ReliableUdpChannel();
            channels.put(info.guestSessionId(), channel);
            joinInfos.put(info.guestSessionId(), info);
                LOGGER.info("[FireflyMC] P2P Guest 开始连接: room={}, session={}, udp={}:{} (raw={})",
                    info.roomId(), info.guestSessionId(), info.effectiveUdpHost(), info.udpPort(), info.udpHost());
            RelayLobbyWebSocketClient.getInstance().sendControl(
                    RelayLobbyMessage.p2pOffer(info.roomId(), info.guestSessionId(), info.p2pSessionId(), info.p2pToken())
            );
            P2PCandidate candidate = candidates.get(info.guestSessionId());
            channel.probeAndPunch(info, candidate, "guest").whenComplete((success, error) -> {
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
            LOGGER.info("[FireflyMC] P2P 服务端观测本端 UDP: role={}, candidate={}:{}",
                    message.role(), message.candidate().address(), message.candidate().port());
        } else if ("p2p_candidate".equals(message.type()) && message.candidate() != null) {
            if (message.guestSessionId() == null) {
                return;
            }
            RelayControlMessage.P2PCandidate raw = message.candidate();
            P2PCandidate candidate = new P2PCandidate(raw.address(), raw.port());
            candidates.put(message.guestSessionId(), candidate);
            ReliableUdpChannel channel = channels.get(message.guestSessionId());
            if (channel != null) {
                channel.setPeerCandidate(candidate);
            }
            if (message.guestSessionId().equals(hostSessionId)) {
                startHostProbeIfReady();
            }
            String candidateKey = raw.address() + ":" + raw.port();
            if (!candidateKey.equals(loggedCandidates.put(message.guestSessionId(), candidateKey))) {
                LOGGER.info("[FireflyMC] P2P 收到对端候选: session={}, candidate={}:{}",
                        message.guestSessionId(), raw.address(), raw.port());
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
            startHostBridge();
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
                ReliableUdpChannel channel = channels.get(hostSessionId);
                P2PCandidate candidate = candidates.get(hostSessionId);
                if (channel != null && candidate != null) {
                    channel.setPeerCandidate(candidate);
                }
                return;
            }
            ReliableUdpChannel channel = new ReliableUdpChannel();
            channels.put(hostSessionId, channel);
            P2PJoinInfo info = new P2PJoinInfo(hostRoomId, hostSessionId, hostRoomId, hostToken, hostUdpHost, hostUdpPort, 10);
            joinInfos.put(hostSessionId, info);
                LOGGER.info("[FireflyMC] P2P Host 开始探测: room={}, session={}, udp={}:{}",
                    hostRoomId, hostSessionId, hostUdpHost, hostUdpPort);
            channel.probeAndPunch(info, candidates.get(hostSessionId), "host");
        } catch (SocketException e) {
            LOGGER.warn("[FireflyMC] P2P Host UDP channel create failed: {}", e.getMessage());
        }
    }

    private void startHostBridge() {
        ReliableUdpChannel channel = channels.get(hostSessionId);
        if (channel != null && hostLanPort > 0 && !hostBridges.containsKey(hostSessionId)) {
            P2PHostBridge hostBridge = new P2PHostBridge(hostLanPort, channel);
            hostBridges.put(hostSessionId, hostBridge);
            hostBridge.startDefaultStream();
            LOGGER.info("[FireflyMC] P2P Host bridge started for LAN port {}", hostLanPort);
        }
    }

    public void stop(String guestSessionId) {
        ReliableUdpChannel channel = channels.remove(guestSessionId);
        if (channel != null) {
            channel.close();
        }
        candidates.remove(guestSessionId);
        joinInfos.remove(guestSessionId);
        P2PHostBridge bridge = hostBridges.remove(guestSessionId);
        if (bridge != null) {
            bridge.stop();
        }
    }
}
