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
                    return;
                }
                RelayLobbyWebSocketClient.getInstance().sendControl(
                        RelayLobbyMessage.p2pReady(info.roomId(), info.guestSessionId(), info.p2pSessionId(), info.p2pToken())
                );
                // The reliable stream bridge is reserved for the next increment; fallback remains safe.
                result.complete(P2PResult.failed("p2p_stream_bridge_not_ready"));
            });
        } catch (SocketException e) {
            LOGGER.warn("[FireflyMC] P2P UDP channel create failed: {}", e.getMessage());
            result.complete(P2PResult.failed("udp_socket_failed"));
        }
        return result;
    }

    public void handleControlMessage(RelayControlMessage message) {
        if (message == null || message.guestSessionId() == null) {
            return;
        }
        if ("p2p_udp_observed".equals(message.type()) && message.candidate() != null) {
            RelayControlMessage.P2PCandidate raw = message.candidate();
            P2PCandidate candidate = new P2PCandidate(raw.address(), raw.port());
            candidates.put(message.guestSessionId(), candidate);
            ReliableUdpChannel channel = channels.get(message.guestSessionId());
            if (channel != null) {
                channel.setPeerCandidate(candidate);
            }
        }
    }

    public void stop(String guestSessionId) {
        ReliableUdpChannel channel = channels.remove(guestSessionId);
        if (channel != null) {
            channel.close();
        }
        candidates.remove(guestSessionId);
    }
}
