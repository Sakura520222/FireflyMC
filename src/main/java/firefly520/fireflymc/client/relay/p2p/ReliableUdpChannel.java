package firefly520.fireflymc.client.relay.p2p;

import firefly520.fireflymc.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MVP reliable UDP channel shell.
 *
 * <p>The first implementation uses this class for NAT probe/punch orchestration and
 * reserves the data codec for the reliable tunnel. If the channel cannot establish
 * quickly, callers fall back to the existing WebSocket relay.</p>
 */
public class ReliableUdpChannel {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReliableUdpChannel.class);

    private final DatagramSocket socket;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "FireflyMC-P2P-UDP");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile InetSocketAddress peerAddress;
    private volatile boolean observedByServer;
    private volatile boolean punchedPeer;

    public ReliableUdpChannel() throws SocketException {
        this.socket = createSocket();
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    public CompletableFuture<Boolean> probeAndPunch(P2PJoinInfo info, P2PCandidate peerCandidate, String role) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!info.isUsable()) {
            result.complete(false);
            return result;
        }
        running.set(true);
        if (peerCandidate != null && peerCandidate.isValid()) {
            peerAddress = peerCandidate.toSocketAddress();
        }
        executor.execute(this::receiveLoop);
        InetSocketAddress serverAddress = new InetSocketAddress(info.udpHost(), info.udpPort());
        int timeout = info.timeoutSeconds() > 0
                ? info.timeoutSeconds()
                : Config.CLIENT.SINGLEPLAYER_RELAY_P2P_CONNECT_TIMEOUT_SECONDS.get();
        byte[] probe = UdpPacketCodec.probe(info.roomId(), info.guestSessionId(), role, info.p2pToken());
        byte[] punch = UdpPacketCodec.punch(info.roomId(), info.guestSessionId(), role, info.p2pToken());
        ScheduledFuture<?> probeTask = executor.scheduleAtFixedRate(() -> {
            send(serverAddress, probe);
            InetSocketAddress peer = peerAddress;
            if (peer != null) {
                send(peer, punch);
            }
            if (observedByServer && punchedPeer && !result.isDone()) {
                result.complete(true);
            }
        }, 0, 300, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> timeoutTask = executor.schedule(() -> {
            if (!result.isDone()) {
                result.complete(false);
            }
        }, timeout, TimeUnit.SECONDS);
        result.whenComplete((ignored, error) -> {
            probeTask.cancel(false);
            timeoutTask.cancel(false);
            if (error != null || !Boolean.TRUE.equals(ignored)) {
                close();
            }
        });
        return result;
    }

    public void setPeerCandidate(P2PCandidate candidate) {
        if (candidate != null && candidate.isValid()) {
            this.peerAddress = candidate.toSocketAddress();
        }
    }

    public void close() {
        running.set(false);
        socket.close();
        executor.shutdownNow();
    }

    private void receiveLoop() {
        byte[] buffer = new byte[1500];
        while (running.get() && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String text = new String(packet.getData(), packet.getOffset(), packet.getLength());
                if (text.contains("probe_ack")) {
                    observedByServer = true;
                } else if (text.contains("punch")) {
                    punchedPeer = true;
                    peerAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());
                }
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.debug("[FireflyMC] P2P UDP receive failed: {}", e.getMessage());
                }
            }
        }
    }

    private void send(InetSocketAddress target, byte[] bytes) {
        try {
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, target);
            socket.send(packet);
        } catch (IOException e) {
            LOGGER.debug("[FireflyMC] P2P UDP send failed: {}", e.getMessage());
        }
    }

    private static DatagramSocket createSocket() throws SocketException {
        int min = Config.CLIENT.SINGLEPLAYER_RELAY_P2P_UDP_PORT_MIN.get();
        int max = Config.CLIENT.SINGLEPLAYER_RELAY_P2P_UDP_PORT_MAX.get();
        if (min > 0 && max >= min) {
            for (int port = min; port <= max; port++) {
                try {
                    return new DatagramSocket(port);
                } catch (SocketException ignored) {
                    // try next port
                }
            }
        }
        return new DatagramSocket(0);
    }
}
