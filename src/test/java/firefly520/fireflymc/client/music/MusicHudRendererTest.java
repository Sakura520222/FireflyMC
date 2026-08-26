package firefly520.fireflymc.client.music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 歌词横向跑马灯时间轴插值（marqueeOffset）验收场景。
 * 纯函数、不依赖 Minecraft 类，直接以 (positionMs, 句窗口, maxOffset) 驱动。
 */
class MusicHudRendererTest {

    // 常规句 10s~16s：available = 6000-300 = 5700 ≥ 500+300+300 → startHold=500, endHold=300
    // 滚动窗口 hStart=10800 → hEnd=15700（4900ms）
    private static final long LONG_START = 10_000L;
    private static final long LONG_END = 16_000L;
    private static final int MAX = 200;

    @Test
    void shortLyricDoesNotScroll() {
        assertEquals(0, MusicHudRenderer.marqueeOffset(12_000L, LONG_START, LONG_END, 0));
    }

    @Test
    void startHoldKeepsBeginningVisible() {
        // 纵向动画（300ms）+ 起点停留（500ms）内 offset 恒 0
        assertEquals(0, MusicHudRenderer.marqueeOffset(10_100L, LONG_START, LONG_END, MAX));
        assertEquals(0, MusicHudRenderer.marqueeOffset(10_799L, LONG_START, LONG_END, MAX));
    }

    @Test
    void scrollsMonotonicallyWithinWindow() {
        int last = 0;
        for (long pos = LONG_START; pos <= LONG_END; pos += 100) {
            int offset = MusicHudRenderer.marqueeOffset(pos, LONG_START, LONG_END, MAX);
            assertTrue(offset >= last, "offset 必须随播放时间非递减 pos=" + pos);
            assertTrue(offset <= MAX);
            last = offset;
        }
        assertTrue(last == MAX, "句末必须滚到末尾");
    }

    @Test
    void holdsAtEndWithoutLooping() {
        assertEquals(MAX, MusicHudRenderer.marqueeOffset(15_700L, LONG_START, LONG_END, MAX));
        assertEquals(MAX, MusicHudRenderer.marqueeOffset(16_000L, LONG_START, LONG_END, MAX));
        // 越过窗口末端（下一句前的间隙）仍保持末尾，不回卷
        assertEquals(MAX, MusicHudRenderer.marqueeOffset(15_999L, LONG_START, LONG_END, MAX));
    }

    @Test
    void shorterSentenceScrollsFaster() {
        // 同 maxOffset、同样从各自滚动起点经过 500ms：
        // 常规句滚动窗 4900ms vs 短句（2s，available=1700 ≥ 1100 → 窗口 900ms）
        int longOff = MusicHudRenderer.marqueeOffset(10_800L + 500, LONG_START, LONG_END, MAX);
        int shortOff = MusicHudRenderer.marqueeOffset(20_500L + 500, 20_000L, 22_000L, MAX);
        assertTrue(longOff < shortOff, "短句必须自动加速（" + longOff + " 应 < " + shortOff + "）");
        assertTrue(longOff > 0 && shortOff < MAX);
    }

    @Test
    void veryShortSentenceStillReachesEnd() {
        // 极短句 0.8s：available=500 < 1100 → 压缩停留（holdBudget=200 → 125/75）
        // 滚动窗 = 500-200 = 300ms，句末（+800ms > hEnd=start+725）必然到达末尾
        assertEquals(MAX, MusicHudRenderer.marqueeOffset(800L, 0L, 800L, MAX));
        assertEquals(MAX, MusicHudRenderer.marqueeOffset(726L, 0L, 800L, MAX));
    }

    @Test
    void midJoinLandsAtTimelinePosition() {
        // 中途加入：直接调用即得到与播放进度匹配的位置（首帧正确，无需从头滚）
        // position=13250 恰为滚动窗口中点 → smoothstep(0.5)=0.5 → MAX/2
        int offset = MusicHudRenderer.marqueeOffset(13_250L, LONG_START, LONG_END, MAX);
        assertEquals(MAX / 2, offset, 1);
        // 且早于它的时刻 offset 更小——同一时间轴
        assertTrue(MusicHudRenderer.marqueeOffset(12_250L, LONG_START, LONG_END, MAX) < offset);
    }

    @Test
    void newSentenceRestartsOwnTimeline() {
        // 新一句（16s~22s）在自己窗口起点处 offset=0，不受上一句影响
        assertEquals(0, MusicHudRenderer.marqueeOffset(16_100L, 16_000L, 22_000L, MAX));
    }
}
