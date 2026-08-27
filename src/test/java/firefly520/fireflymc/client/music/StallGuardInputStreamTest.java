package firefly520.fireflymc.client.music;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * StallGuardInputStream 行为覆盖（短超时 + 真实调度器，睡眠等待确定性足够）：
 * 读后重排定时器（持续读取不断触发）、EOF/close 取消、到期关闭底层流并置位 tripped。
 */
class StallGuardInputStreamTest {

    /** 记录底层流是否被看护关闭 */
    private static final class TrackingStream extends ByteArrayInputStream {
        volatile boolean closed = false;

        TrackingStream(byte[] buf) {
            super(buf);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor();

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
    }

    @Test
    void dataFlowsThrough() throws IOException {
        TrackingStream inner = new TrackingStream(new byte[]{1, 2, 3, 4, 5});
        StallGuardInputStream guard = new StallGuardInputStream(inner, SCHEDULER, 100);
        assertEquals(5, guard.read(new byte[8]), "包装不得改变读取语义");
        guard.close();
    }

    @Test
    void firesWithoutReads() throws IOException, InterruptedException {
        TrackingStream inner = new TrackingStream(new byte[10]);
        StallGuardInputStream guard = new StallGuardInputStream(inner, SCHEDULER, 50);
        sleep(150);
        assertTrue(guard.isTripped(), "超时必须触发 tripped");
        assertTrue(inner.closed, "超时必须关闭底层流");
        guard.close();
    }

    @Test
    void successfulReadsKeepItAlive() throws IOException, InterruptedException {
        TrackingStream inner = new TrackingStream(new byte[64]);
        // 单次超时 100ms：每 40ms 读一次、连续 5 次（200ms > 100ms）
        // 若 read 不重排定时器，中途必然已经 trip
        StallGuardInputStream guard = new StallGuardInputStream(inner, SCHEDULER, 100);
        for (int i = 0; i < 5; i++) {
            sleep(40);
            int n = guard.read(new byte[4]);
            assertTrue(n > 0, "读取期间数据可用 n=" + n);
            assertFalse(guard.isTripped(), "持续读取必须保持存活（read 重排定时器）");
        }
        guard.close();
    }

    @Test
    void eofCancelsTimer() throws IOException, InterruptedException {
        TrackingStream inner = new TrackingStream(new byte[2]);
        StallGuardInputStream guard = new StallGuardInputStream(inner, SCHEDULER, 60);
        int n;
        do {
            n = guard.read(new byte[16]);
        } while (n >= 0);
        sleep(120); // 超过原定时时长：若 EOF 未取消定时器此刻应已 trip
        assertFalse(guard.isTripped(), "EOF 必须取消定时器");
        guard.close();
    }

    @Test
    void closeCancelsTimer() throws IOException, InterruptedException {
        TrackingStream inner = new TrackingStream(new byte[10]);
        StallGuardInputStream guard = new StallGuardInputStream(inner, SCHEDULER, 60);
        guard.close();
        sleep(120);
        assertFalse(guard.isTripped(), "close 必须取消定时器");
        assertTrue(inner.closed, "close 必须关闭底层流");
    }
}
