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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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
    private volatile boolean loggedObserved;
    private volatile boolean loggedPunch;
    private final Map<Integer, OutputStream> outputs = new ConcurrentHashMap<>();
    private final Map<Integer, ReorderBuffer> reorderBuffers = new ConcurrentHashMap<>();
    private final Map<Integer, List<UdpPacketCodec.DecodedData>> pendingBeforeRegister = new ConcurrentHashMap<>();
    private final AtomicInteger nextSeq = new AtomicInteger(1);
    private final AtomicInteger lastAck = new AtomicInteger(0);
    private final AtomicLong sentBytes = new AtomicLong(0);
    private final AtomicLong receivedBytes = new AtomicLong(0);
    private final AtomicLong nextSentLogAt = new AtomicLong(64 * 1024);
    private final AtomicLong nextReceivedLogAt = new AtomicLong(64 * 1024);
    private final SendWindow sendWindow;

    public ReliableUdpChannel() throws SocketException {
        this.socket = createSocket();
        this.receiverThread = new Thread(this::receiveLoop, "FireflyMC-P2P-UDP-Receiver");
        this.receiverThread.setDaemon(true);
        this.sendWindow = new SendWindow(executor, this::send);
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
        InetSocketAddress serverAddress = new InetSocketAddress(info.effectiveUdpHost(), info.udpPort());
        int timeout = info.timeoutSeconds() > 0
                ? info.timeoutSeconds()
                : Config.CLIENT.SINGLEPLAYER_RELAY_P2P_CONNECT_TIMEOUT_SECONDS.get();
        byte[] probe = UdpPacketCodec.probe(info.roomId(), info.guestSessionId(), role, info.p2pToken());
        byte[] punch = UdpPacketCodec.punch(info.roomId(), info.guestSessionId(), role, info.p2pToken());
        LOGGER.info("[FireflyMC] P2P {} 开始探测: server={}, localUdp={}, timeout={}s, rawUdpHost={}",
            role, serverAddress, localPort(), timeout, info.udpHost());
        ScheduledFuture<?> probeTask = executor.scheduleAtFixedRate(() -> {
            send(serverAddress, probe);
            InetSocketAddress peer = peerAddress;
            if (peer != null) {
                send(peer, punch);
            }
            if (observedByServer && punchedPeer && !result.isDone()) {
                LOGGER.info("[FireflyMC] P2P {} 打洞完成: peer={}", role, peerAddress);
                sendWindow.start();
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
        List<UdpPacketCodec.DecodedData> pending = pendingBeforeRegister.remove(streamId);
        if (pending != null && !pending.isEmpty()) {
            LOGGER.info("[FireflyMC] P2P 回放早到数据: stream={}, packets={}", streamId, pending.size());
            pending.stream()
                    .sorted(java.util.Comparator.comparingInt(UdpPacketCodec.DecodedData::seq))
                    .forEach(decoded -> writeDecodedPayload(decoded, output));
        }
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
            byte[] packet = UdpPacketCodec.data(streamId, seq, lastAck.get(), (byte) 0, payload, payload.length);
            send(peer, packet);
            sendWindow.record(seq, packet, peer);
            long totalSent = sentBytes.addAndGet(payload.length);
            long threshold = nextSentLogAt.get();
            if (totalSent <= 8192 || totalSent >= threshold) {
                nextSentLogAt.compareAndSet(threshold, threshold + 64 * 1024);
                LOGGER.info("[FireflyMC] P2P UDP 发送进度: stream={}, seq={}, chunk={} bytes, total={} KB, peer={}",
                        streamId, seq, payload.length, totalSent / 1024, peer);
            }
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
        sendWindow.close();
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
                    if (!loggedObserved) {
                        loggedObserved = true;
                        LOGGER.info("[FireflyMC] P2P 已被服务端观测到: {}", text);
                    }
                } else if (text.contains("punch")) {
                    punchedPeer = true;
                    peerAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());
                    if (!loggedPunch) {
                        loggedPunch = true;
                        LOGGER.info("[FireflyMC] P2P 收到对端 punch: {}", peerAddress);
                    }
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
            sendWindow.acknowledge(decoded.ack());
            return;
        }
        lastAck.set(Math.max(lastAck.get(), decoded.seq()));
        // Send ACK back to peer
        InetSocketAddress peer = peerAddress;
        if (peer != null) {
            ReorderBuffer reorder = reorderBuffers.computeIfAbsent(decoded.streamId(), ignored -> new ReorderBuffer());
            send(peer, UdpPacketCodec.ack(decoded.streamId(), reorder.ackSeq()));
        }
        OutputStream output = outputs.get(decoded.streamId());
        if (output != null && decoded.payload().length > 0) {
            writeDecodedPayload(decoded, output);
        } else if (decoded.payload().length > 0) {
            List<UdpPacketCodec.DecodedData> pending = pendingBeforeRegister.computeIfAbsent(
                    decoded.streamId(), ignored -> Collections.synchronizedList(new ArrayList<>())
            );
            if (pending.size() < 256) {
                pending.add(decoded);
                LOGGER.info("[FireflyMC] P2P 缓存早到数据: stream={}, seq={}, bytes={}, pending={}",
                        decoded.streamId(), decoded.seq(), decoded.payload().length, pending.size());
            }
        }
    }

    private void writeDecodedPayload(UdpPacketCodec.DecodedData decoded, OutputStream output) {
        try {
            ReorderBuffer reorder = reorderBuffers.computeIfAbsent(decoded.streamId(), ignored -> new ReorderBuffer());
            reorder.accept(decoded.seq(), decoded.payload(), output);
            receivedBytes.addAndGet(decoded.payload().length);
            if (receivedBytes.get() <= 8192 || decoded.payload().length > 1000) {
                long totalReceived = receivedBytes.get();
                long threshold = nextReceivedLogAt.get();
                if (totalReceived <= 8192 || totalReceived >= threshold) {
                    nextReceivedLogAt.compareAndSet(threshold, threshold + 64 * 1024);
                    LOGGER.info("[FireflyMC] P2P UDP 接收进度: stream={}, seq={}, chunk={} bytes, total={} KB",
                            decoded.streamId(), decoded.seq(), decoded.payload().length, totalReceived / 1024);
                }
            }
        } catch (IOException e) {
            LOGGER.debug("[FireflyMC] P2P stream write failed: {}", e.getMessage());
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
