package firefly520.fireflymc.client.relay.p2p;

public record P2PResult(boolean success, int localPort, String reason) {
    public static P2PResult success(int localPort) {
        return new P2PResult(true, localPort, "ok");
    }

    public static P2PResult failed(String reason) {
        return new P2PResult(false, -1, reason == null ? "failed" : reason);
    }
}
