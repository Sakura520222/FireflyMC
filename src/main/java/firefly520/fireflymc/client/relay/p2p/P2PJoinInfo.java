package firefly520.fireflymc.client.relay.p2p;

public record P2PJoinInfo(
        String roomId,
        String guestSessionId,
        String p2pSessionId,
        String p2pToken,
        String udpHost,
        int udpPort,
        int timeoutSeconds
) {
    public boolean isUsable() {
        return roomId != null && !roomId.isBlank()
                && guestSessionId != null && !guestSessionId.isBlank()
                && p2pSessionId != null && !p2pSessionId.isBlank()
                && p2pToken != null && !p2pToken.isBlank()
                && udpHost != null && !udpHost.isBlank()
                && udpPort > 0 && udpPort <= 65535;
    }
}
