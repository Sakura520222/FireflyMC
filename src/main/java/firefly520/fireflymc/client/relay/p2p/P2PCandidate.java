package firefly520.fireflymc.client.relay.p2p;

import java.net.InetSocketAddress;

public record P2PCandidate(String address, int port) {
    public boolean isValid() {
        return address != null && !address.isBlank() && port > 0 && port <= 65535;
    }

    public InetSocketAddress toSocketAddress() {
        return new InetSocketAddress(address, port);
    }
}
