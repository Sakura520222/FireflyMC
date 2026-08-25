package firefly520.fireflymc.client.music;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackClockTest {

    @Test
    void offsetAppliesToPosition() {
        // 中途加入：base=151000ms，line 已播 5000 帧 @ 44100Hz ≈ 113.4ms
        long pos = PlaybackClock.positionWithOffset(151000L, 5000L, 44100);
        assertEquals(151000L + 113L, pos); // 5000*1000/44100 = 113.37 → 113
    }

    @Test
    void zeroOffsetIsNormalStart() {
        assertEquals(0L, PlaybackClock.positionWithOffset(0L, 0L, 44100));
    }

    @Test
    void silentClockMonotonic() throws InterruptedException {
        PlaybackClock.Silent clock = new PlaybackClock.Silent(30000L);
        long a = clock.positionMs();
        Thread.sleep(50);
        long b = clock.positionMs();
        assertTrue(b > a, "静音时钟必须单调推进");
        assertTrue(a >= 30000L, "静音时钟也必须带 base 偏移");
    }
}
