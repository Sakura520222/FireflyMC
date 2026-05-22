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
    private static final String RELAY_HOST_FALLBACK = resolveRelayHostFallback();

    public boolean isUsable() {
        return roomId != null && !roomId.isBlank()
                && guestSessionId != null && !guestSessionId.isBlank()
                && p2pSessionId != null && !p2pSessionId.isBlank()
                && p2pToken != null && !p2pToken.isBlank()
                && udpHost != null && !udpHost.isBlank()
                && udpPort > 0 && udpPort <= 65535;
    }

    public String effectiveUdpHost() {
        if (udpHost == null || udpHost.isBlank() || "0.0.0.0".equals(udpHost) || "127.0.0.1".equals(udpHost) || "localhost".equalsIgnoreCase(udpHost)) {
            return RELAY_HOST_FALLBACK;
        }
        return udpHost;
    }

    private static String resolveRelayHostFallback() {
        try {
            String relayUrl = firefly520.fireflymc.client.relay.RelayConfig.RELAY.SINGLEPLAYER_RELAY_URL.get();
            return java.net.URI.create(relayUrl).getHost();
        } catch (Exception ignored) {
            return "";
        }
    }
}
