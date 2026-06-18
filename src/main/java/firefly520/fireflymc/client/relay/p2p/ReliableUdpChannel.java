package firefly520.fireflymc.client.relay.p2p;

import firefly520.fireflymc.client.relay.RelayConfig;
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
    /** 主动打洞/直连的候选地址列表（IPv6 优先），运行期可通过 addCandidate 追加。 */
    private final List<InetSocketAddress> peerCandidatesAddresses = new java.util.concurrent.CopyOnWriteArrayList<>();
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
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> closeHandlers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile long lastReceivedAt = System.currentTimeMillis();
    private static final long PEER_IDLE_TIMEOUT_MS = 30_000;
    /** 发送窗口背压最长等待时间，超过则丢弃当前数据，避免窗口长期不释放时无限堆积。 */
    private static final long WINDOW_BACKPRESSURE_TIMEOUT_MS = 5_000;

    public ReliableUdpChannel() throws SocketException {
        this.socket = createSocket();
        this.receiverThread = new Thread(this::receiveLoop, "FireflyMC-P2P-UDP-Receiver");
        this.receiverThread.setDaemon(true);
        this.sendWindow = new SendWindow(executor, this::send, this::handlePeerUnreachable);
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    public CompletableFuture<Boolean> probeAndPunch(P2PJoinInfo info, P2PCandidate peerCandidate, String role) {
        return probeAndPunch(info,
                peerCandidate != null && peerCandidate.isValid() ? List.of(peerCandidate) : List.of(),
                role);
    }

    /**
     * 发起探测与打洞，支持多个候选地址（IPv6 优先）。
     *
     * <p>持续向服务器探测地址发 probe（维持 IPv4 NAT 映射观测），同时向所有候选地址
     * 发 punch。任一候选双向打通即视为成功（punchedPeer）。IPv6 候选因通常无 NAT，
     * 无需服务器观测即可直连成功；故成功条件不再强制 observedByServer。
     */
    public CompletableFuture<Boolean> probeAndPunch(P2PJoinInfo info, List<P2PCandidate> peerCandidates, String role) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!info.isUsable()) {
            result.complete(false);
            return result;
        }
        running.set(true);
        for (P2PCandidate candidate : peerCandidates) {
            addCandidate(candidate);
        }
        if (!receiverThread.isAlive()) {
            receiverThread.start();
        }
        InetSocketAddress serverAddress = new InetSocketAddress(info.effectiveUdpHost(), info.udpPort());
        int timeout = info.timeoutSeconds() > 0
                ? info.timeoutSeconds()
                : RelayConfig.RELAY.SINGLEPLAYER_RELAY_P2P_CONNECT_TIMEOUT_SECONDS.get();
        byte[] probe = UdpPacketCodec.probe(info.roomId(), info.guestSessionId(), role, info.p2pToken());
        byte[] punch = UdpPacketCodec.punch(info.roomId(), info.guestSessionId(), role, info.p2pToken());
        LOGGER.info("[FireflyMC] P2P {} 开始探测: serverUdpPort={}, localUdp={}, candidates={}, timeout={}s",
            role, serverAddress.getPort(), localPort(), peerCandidatesAddresses.size(), timeout);
        ScheduledFuture<?> probeTask = executor.scheduleAtFixedRate(() -> {
            send(serverAddress, probe);
            for (InetSocketAddress peer : peerCandidatesAddresses) {
                send(peer, punch);
            }
            if (punchedPeer && !result.isDone()) {
                LOGGER.info("[FireflyMC] P2P {} 直连/打洞完成: peerUdpPort={}, observedByServer={}",
                        role, peerAddress != null ? peerAddress.getPort() : -1, observedByServer);
                sendWindow.start();
                startIdleCheck();
                result.complete(true);
            }
        }, 0, 300, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> timeoutTask = executor.schedule(() -> {
            if (!result.isDone()) {
                LOGGER.warn("[FireflyMC] P2P {} 探测超时: observedByServer={}, punchedPeer={}, peerUdpPort={}",
                        role, observedByServer, punchedPeer, peerAddress != null ? peerAddress.getPort() : -1);
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

    /** 加入一个候选地址（IPv6 优先），运行期可多次调用追加新候选（如收到 p2p_candidate）。 */
    public void addCandidate(P2PCandidate candidate) {
        if (candidate == null || !candidate.isValid()) {
            return;
        }
        InetSocketAddress addr = candidate.toSocketAddress();
        if (!peerCandidatesAddresses.contains(addr)) {
            peerCandidatesAddresses.add(addr);
            LOGGER.info("[FireflyMC] P2P 加入候选: {}, 当前候选数={}", addr, peerCandidatesAddresses.size());
        }
    }

    public void setPeerCandidate(P2PCandidate candidate) {
        addCandidate(candidate);
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
        if (peer == null || length <= 0 || !running.get()) {
            return;
        }
        int offset = 0;
        while (offset < length) {
            int chunk = Math.min(UdpPacketCodec.MAX_PAYLOAD_SIZE, length - offset);
            byte[] payload = Arrays.copyOfRange(bytes, offset, offset + chunk);
            if (!awaitWindowSpace()) {
                LOGGER.warn("[FireflyMC] P2P 发送窗口长时间未释放，丢弃剩余数据: stream={}, remaining={} bytes", streamId, length - offset);
                return;
            }
            int seq = nextSeq.getAndIncrement();
            byte[] packet = UdpPacketCodec.data(streamId, seq, lastAck.get(), (byte) 0, payload, payload.length);
            send(peer, packet);
            sendWindow.record(seq, packet, peer);
            long totalSent = sentBytes.addAndGet(payload.length);
            long threshold = nextSentLogAt.get();
            if (totalSent <= 8192 || totalSent >= threshold) {
                nextSentLogAt.compareAndSet(threshold, threshold + 64 * 1024);
                LOGGER.info("[FireflyMC] P2P UDP 发送进度: stream={}, seq={}, chunk={} bytes, total={} KB, peerUdpPort={}",
                        streamId, seq, payload.length, totalSent / 1024, peer.getPort());
            }
            offset += chunk;
        }
    }

    /**
     * 发送窗口背压：窗口满时等待对端 ACK 释放空间，将发送速率与对端处理能力对齐，
     * 避免在加入方未就绪或网络拥塞时持续灌入并放大流量。
     */
    private boolean awaitWindowSpace() {
        long deadline = System.currentTimeMillis() + WINDOW_BACKPRESSURE_TIMEOUT_MS;
        while (running.get() && sendWindow.isFull()) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return running.get();
    }

    public void sendFin(int streamId) {
        InetSocketAddress peer = peerAddress;
        if (peer != null && running.get()) {
            send(peer, UdpPacketCodec.fin(streamId, nextSeq.getAndIncrement(), lastAck.get()));
        }
    }

    public boolean isRunning() {
        return running.get() && !socket.isClosed();
    }

    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        LOGGER.info("[FireflyMC] P2P UDP channel closing: localPort={}", socket.getLocalPort());
        sendWindow.close();
        socket.close();
        executor.shutdownNow();
        for (Runnable handler : closeHandlers) {
            try {
                handler.run();
            } catch (Exception e) {
                LOGGER.warn("[FireflyMC] P2P close handler 异常: {}", e.getMessage());
            }
        }
        closeHandlers.clear();
    }

    public void setOnClose(Runnable onClose) {
        closeHandlers.clear();
        if (onClose != null) {
            closeHandlers.add(onClose);
        }
    }

    /** 追加 close 回调；通道关闭时全部触发，可用于级联断开本地连接（如房主退出后断开 MC）。 */
    public void addCloseHandler(Runnable handler) {
        if (handler != null) {
            closeHandlers.add(handler);
        }
    }

    private void handlePeerUnreachable() {
        if (!running.get()) {
            return;
        }
        LOGGER.warn("[FireflyMC] P2P 对端不可达，关闭通道: localPort={}", socket.getLocalPort());
        close();
    }

    private void startIdleCheck() {
        executor.scheduleAtFixedRate(() -> {
            if (!running.get()) return;
            long idle = System.currentTimeMillis() - lastReceivedAt;
            if (idle > PEER_IDLE_TIMEOUT_MS) {
                LOGGER.warn("[FireflyMC] P2P peer idle timeout ({}ms), closing channel", idle);
                close();
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void receiveLoop() {
        byte[] buffer = new byte[1500];
        while (running.get() && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                byte[] packetBytes = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
                if (UdpPacketCodec.isBinaryData(packetBytes)) {
                    lastReceivedAt = System.currentTimeMillis();
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
                    lastReceivedAt = System.currentTimeMillis();
                    peerAddress = new InetSocketAddress(packet.getAddress(), packet.getPort());
                    if (!loggedPunch) {
                        loggedPunch = true;
                        LOGGER.info("[FireflyMC] P2P 收到对端 punch: udpPort={}", peerAddress.getPort());
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
        if ((decoded.flags() & UdpPacketCodec.FLAG_FIN) != 0) {
            LOGGER.info("[FireflyMC] P2P 收到对端 FIN，关闭通道: stream={}", decoded.streamId());
            InetSocketAddress peer = peerAddress;
            if (peer != null) {
                send(peer, UdpPacketCodec.ack(decoded.streamId(), decoded.seq()));
            }
            close();
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
            if (pending.size() < 1024) {
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
            LOGGER.warn("[FireflyMC] P2P UDP send failed: targetPort={}, error={}", target.getPort(), e.getMessage());
        }
    }

    private static DatagramSocket createSocket() throws SocketException {
        int min = RelayConfig.RELAY.SINGLEPLAYER_RELAY_P2P_UDP_PORT_MIN.get();
        int max = RelayConfig.RELAY.SINGLEPLAYER_RELAY_P2P_UDP_PORT_MAX.get();
        if (min > 0 && max >= min) {
            for (int port = min; port <= max; port++) {
                try {
                    return bindDualStackSocket(port);
                } catch (SocketException ignored) {
                    // try next port
                }
            }
        }
        return bindDualStackSocket(0);
    }

    /**
     * 创建绑定 IPv6 通配符（::）的 UDP socket。
     *
     * <p>双栈系统上同一 socket 可同时收发 IPv6 与 IPv4-mapped 流量，使 IPv6 直连与
     * IPv4 NAT 打洞共用同一通道。若系统不支持 IPv6（纯 IPv4 环境），回退到 IPv4 socket
     * 以保持兼容。
     */
    private static DatagramSocket bindDualStackSocket(int port) throws SocketException {
        try {
            java.nio.channels.DatagramChannel channel =
                    java.nio.channels.DatagramChannel.open(java.net.StandardProtocolFamily.INET6);
            channel.bind(new InetSocketAddress(port));
            return channel.socket();
        } catch (Exception ipv6Failed) {
            LOGGER.debug("[FireflyMC] P2P IPv6 socket 绑定失败，回退 IPv4: {}", ipv6Failed.getMessage());
            return new DatagramSocket(port);
        }
    }
}
