package firefly520.fireflymc.client.relay.p2p;

import firefly520.fireflymc.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final Thread receiverThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile InetSocketAddress peerAddress;
    private volatile boolean observedByServer;
    private volatile boolean punchedPeer;
    private final Map<Integer, OutputStream> outputs = new ConcurrentHashMap<>();
    private final Map<Integer, ReorderBuffer> reorderBuffers = new ConcurrentHashMap<>();
    private final AtomicInteger nextSeq = new AtomicInteger(1);
    private final AtomicInteger lastAck = new AtomicInteger(0);

    public ReliableUdpChannel() throws SocketException {
        this.socket = createSocket();
        this.receiverThread = new Thread(this::receiveLoop, "FireflyMC-P2P-UDP-Receiver");
        this.receiverThread.setDaemon(true);
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
            LOGGER.info("[FireflyMC] P2P 初始 peer candidate: {}", peerAddress);
        }
        if (!receiverThread.isAlive()) {
            receiverThread.start();
        }
        InetSocketAddress serverAddress = new InetSocketAddress(info.udpHost(), info.udpPort());
        int timeout = info.timeoutSeconds() > 0
                ? info.timeoutSeconds()
                : Config.CLIENT.SINGLEPLAYER_RELAY_P2P_CONNECT_TIMEOUT_SECONDS.get();
        byte[] probe = UdpPacketCodec.probe(info.roomId(), info.guestSessionId(), role, info.p2pToken());
        byte[] punch = UdpPacketCodec.punch(info.roomId(), info.guestSessionId(), role, info.p2pToken());
        LOGGER.info("[FireflyMC] P2P {} 开始探测: server={}, localUdp={}, timeout={}s",
            role, serverAddress, localPort(), timeout);
        ScheduledFuture<?> probeTask = executor.scheduleAtFixedRate(() -> {
            send(serverAddress, probe);
            InetSocketAddress peer = peerAddress;
            if (peer != null) {
                send(peer, punch);
            }
            if (observedByServer && punchedPeer && !result.isDone()) {
                LOGGER.info("[FireflyMC] P2P {} 打洞完成: peer={}", role, peerAddress);
                result.complete(true);
            }
        }, 0, 300, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> timeoutTask = executor.schedule(() -> {
            if (!result.isDone()) {
                LOGGER.warn("[FireflyMC] P2P {} 探测超时: observedByServer={}, punchedPeer={}, peer={}",
                        role, observedByServer, punchedPeer, peerAddress);
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
            LOGGER.info("[FireflyMC] P2P 更新 peer candidate: {}", this.peerAddress);
        }
    }

    public void registerStream(int streamId, OutputStream output) {
        outputs.put(streamId, output);
        reorderBuffers.computeIfAbsent(streamId, ignored -> new ReorderBuffer());
    }

    public void unregisterStream(int streamId) {
        outputs.remove(streamId);
        reorderBuffers.remove(streamId);
    }

    public void sendData(int streamId, byte[] bytes, int length) {
        InetSocketAddress peer = peerAddress;
        if (peer == null || length <= 0) {
            return;
        }
        int offset = 0;
        while (offset < length) {
            int chunk = Math.min(UdpPacketCodec.MAX_PAYLOAD_SIZE, length - offset);
            byte[] payload = Arrays.copyOfRange(bytes, offset, offset + chunk);
            int seq = nextSeq.getAndIncrement();
            send(peer, UdpPacketCodec.data(streamId, seq, lastAck.get(), (byte) 0, payload, payload.length));
            offset += chunk;
        }
    }

    public void sendFin(int streamId) {
        InetSocketAddress peer = peerAddress;
        if (peer != null) {
            send(peer, UdpPacketCodec.fin(streamId, nextSeq.getAndIncrement(), lastAck.get()));
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
                byte[] packetBytes = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
                if (UdpPacketCodec.isBinaryData(packetBytes)) {
                    handleDataPacket(packetBytes, packet);
                    continue;
                }
                String text = new String(packet.getData(), packet.getOffset(), packet.getLength());
                if (text.contains("probe_ack")) {
                    observedByServer = true;
                    LOGGER.info("[FireflyMC] P2P 已被服务端观测到: {}", text);
                } else if (text.contains("punch")) {
                    punchedPeer = true;
                    peerAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());
                    LOGGER.info("[FireflyMC] P2P 收到对端 punch: {}", peerAddress);
                }
            } catch (IOException e) {
                if (running.get()) {
                    LOGGER.debug("[FireflyMC] P2P UDP receive failed: {}", e.getMessage());
                }
            }
        }
    }

    private void handleDataPacket(byte[] bytes, DatagramPacket source) {
        UdpPacketCodec.DecodedData decoded = UdpPacketCodec.decodeData(bytes);
        if (decoded == null) {
            return;
        }
        peerAddress = new InetSocketAddress(source.getAddress(), source.getPort());
        if ((decoded.flags() & UdpPacketCodec.FLAG_ACK) != 0) {
            return;
        }
        lastAck.set(Math.max(lastAck.get(), decoded.seq()));
        OutputStream output = outputs.get(decoded.streamId());
        if (output != null && decoded.payload().length > 0) {
            try {
                ReorderBuffer reorder = reorderBuffers.computeIfAbsent(decoded.streamId(), ignored -> new ReorderBuffer());
                reorder.accept(decoded.seq(), decoded.payload(), output);
                InetSocketAddress peer = peerAddress;
                if (peer != null) {
                    send(peer, UdpPacketCodec.ack(decoded.streamId(), reorder.ackSeq()));
                }
            } catch (IOException e) {
                LOGGER.debug("[FireflyMC] P2P stream write failed: {}", e.getMessage());
            }
        }
    }

    private void send(InetSocketAddress target, byte[] bytes) {
        try {
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, target);
            socket.send(packet);
        } catch (IOException e) {
            LOGGER.warn("[FireflyMC] P2P UDP send failed: target={}, error={}", target, e.getMessage());
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
