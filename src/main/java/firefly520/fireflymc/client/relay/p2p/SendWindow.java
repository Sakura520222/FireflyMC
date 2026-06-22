package firefly520.fireflymc.client.relay.p2p;

import firefly520.fireflymc.client.relay.RelayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sliding window sender with ACK-based retransmission.
 * Tracks sent packets and retransmits those not acknowledged within a timeout.
 */
final class SendWindow {
    private static final Logger LOGGER = LoggerFactory.getLogger(SendWindow.class);
    private static final int MAX_WINDOW = 128;
    /** 单包最大重传次数，超过则判定对端不可达，触发通道关闭以停止持续发包。 */
    private static final int MAX_RETRIES = 20;

    private final Map<Integer, SentPacket> unacked = new ConcurrentHashMap<>();
    private final AtomicInteger lastAckedSeq = new AtomicInteger(0);
    private final ScheduledExecutorService executor;
    private final UdpSender sender;
    private final Runnable onPeerUnreachable;
    private ScheduledFuture<?> retransmitTask;
    private volatile boolean closed;

    interface UdpSender {
        void send(InetSocketAddress target, byte[] packet);
    }

    SendWindow(ScheduledExecutorService executor, UdpSender sender, Runnable onPeerUnreachable) {
        this.executor = executor;
        this.sender = sender;
        this.onPeerUnreachable = onPeerUnreachable;
    }

    void start() {
        int retransmitMs = RelayConfig.RELAY.SINGLEPLAYER_RELAY_P2P_RETRANSMIT_MILLIS.get();
        retransmitTask = executor.scheduleAtFixedRate(this::retransmit, retransmitMs, retransmitMs, TimeUnit.MILLISECONDS);
    }

    void record(int seq, byte[] packet, InetSocketAddress target) {
        if (closed) return;
        unacked.put(seq, new SentPacket(seq, packet, target, System.currentTimeMillis()));
        // Evict very old packets to prevent memory leak
        if (unacked.size() > MAX_WINDOW * 4) {
            int cutoff = lastAckedSeq.get() - MAX_WINDOW;
            unacked.entrySet().removeIf(e -> e.getKey() < cutoff);
        }
    }

    void acknowledge(int ackSeq) {
        if (ackSeq <= 0) return;
        lastAckedSeq.set(Math.max(lastAckedSeq.get(), ackSeq));
        // Remove all packets with seq <= ackSeq
        unacked.entrySet().removeIf(e -> e.getKey() <= ackSeq);
    }

    int pendingCount() {
        return unacked.size();
    }

    boolean isFull() {
        return unacked.size() >= MAX_WINDOW;
    }

    void close() {
        closed = true;
        if (retransmitTask != null) {
            retransmitTask.cancel(false);
        }
        unacked.clear();
    }

    private void retransmit() {
        if (closed || unacked.isEmpty()) return;
        long now = System.currentTimeMillis();
        int retransmitMs = RelayConfig.RELAY.SINGLEPLAYER_RELAY_P2P_RETRANSMIT_MILLIS.get();
        int retransmitted = 0;
        for (SentPacket sp : unacked.values()) {
            if (now - sp.sentAt >= retransmitMs) {
                sp.retryCount++;
                if (sp.retryCount > MAX_RETRIES) {
                    LOGGER.warn("[FireflyMC] P2P 对端不可达: seq={} 已重传 {} 次未确认，触发关闭", sp.seq, sp.retryCount);
                    if (onPeerUnreachable != null) {
                        onPeerUnreachable.run();
                    }
                    return;
                }
                sender.send(sp.target, sp.packet);
                sp.sentAt = now;
                retransmitted++;
            }
        }
        if (retransmitted > 0) {
            LOGGER.debug("[FireflyMC] P2P 重传: {} packets, pending={}", retransmitted, unacked.size());
        }
    }

    private static class SentPacket {
        final int seq;
        final byte[] packet;
        final InetSocketAddress target;
        volatile long sentAt;
        volatile int retryCount;

        SentPacket(int seq, byte[] packet, InetSocketAddress target, long sentAt) {
            this.seq = seq;
            this.packet = packet;
            this.target = target;
            this.sentAt = sentAt;
        }
    }
}
