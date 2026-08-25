package firefly520.fireflymc.music;

import firefly520.fireflymc.network.MusicPlaybackFailedPayload;
import firefly520.fireflymc.network.MusicQueueSyncPayload;
import firefly520.fireflymc.network.MusicStartPayload;
import firefly520.fireflymc.network.MusicStopPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MusicQueueManagerTest {

    /** 可控时钟 */
    static class FakeClock {
        long nano = 0;
        final java.util.function.LongSupplier supplier = () -> nano;

        void advanceMs(long ms) {
            nano += ms * 1_000_000L;
        }
    }

    /** 收集广播的假集成层 */
    static class Recorder {
        final List<MusicStartPayload> starts = new ArrayList<>();
        final List<MusicStopPayload> stops = new ArrayList<>();
        final List<MusicQueueSyncPayload> syncs = new ArrayList<>();
    }

    FakeClock clock;
    Recorder rec;
    MusicQueueManager m;

    @BeforeEach
    void setup() {
        clock = new FakeClock();
        rec = new Recorder();
        // capability：普通玩家全员可音乐（3 人在线），plain 不可
        m = new MusicQueueManager(
                clock.supplier,
                rec.starts::add,
                rec.stops::add,
                rec.syncs::add,
                new MusicQueueManager.CapabilityLookup() {
                    @Override
                    public boolean isCapable(UUID player) {
                        return !player.equals(PLAIN);
                    }

                    @Override
                    public int capableOnlineCount() {
                        return 3;
                    }
                });
    }

    /** 不具备音乐能力的原版玩家（不入 quorum 分母） */
    static final UUID PLAIN = UUID.fromString("00000000-0000-0000-0000-000000000000");

    static QueuedSong song(String title, UUID requester, long durationMs) {
        return new QueuedSong("1000" + Math.abs(title.hashCode()) % 100000, title, "歌手",
                "", "Req" + requester.toString().charAt(0), requester, durationMs);
    }

    @Test
    void playerSongLimitLifecycle() {
        UUID a = UUID.randomUUID();

        // 连点 3 首成功（第 1 首立即播放，2/3 排队）
        for (int i = 1; i <= 3; i++) {
            assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
            assertTrue(m.completeRequest(a, song("歌" + i, a, 60_000L)));
        }
        // 第 4 首被拒
        assertEquals(MusicQueueManager.BeginResult.LOCKED, m.tryBeginRequest(a, false));

        // 第 1 首播完（60s + 2s 容差）→ 切第 2 首（队列非空不发 Stop）→ 系统内剩 2 首 → 可再点第 4 首
        clock.advanceMs(60_000L + 2_000L);
        m.tick();
        assertEquals(2, rec.starts.size(), "第 1 首播完应直接切第 2 首");
        assertTrue(rec.stops.isEmpty(), "队列非空时不发 Stop");
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        // 第 4 首入队后又是 3 首 → 第 5 首拒
        assertTrue(m.completeRequest(a, song("歌4", a, 60_000L)));
        assertEquals(MusicQueueManager.BeginResult.LOCKED, m.tryBeginRequest(a, false));
    }

    @Test
    void privilegedBypassPlayerLimit() {
        UUID op = UUID.randomUUID();
        // 特权者不受 3 首限制，连点 4 首全部接受（只受系统总量约束）
        for (int i = 1; i <= 4; i++) {
            assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(op, true));
            assertTrue(m.completeRequest(op, song("特权歌" + i, op, 60_000L)));
        }
    }

    @Test
    void pendingBlocksDoubleRequest() {
        UUID a = UUID.randomUUID();
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        // HTTP 进行中，第二次点歌 → PENDING 拒绝
        assertEquals(MusicQueueManager.BeginResult.PENDING, m.tryBeginRequest(a, false));
        // 搜索失败：pending 移除，未锁定
        m.failRequest(a);
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
    }

    @Test
    void privilegedBypassLockButNotQueueLimit() {
        UUID op = UUID.randomUUID();
        // 特权者连点 50+1 首：第 51 首被 QUEUE_FULL 拒
        for (int i = 0; i < MusicQueueManager.MAX_QUEUE_SIZE; i++) {
            assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(op, true));
            assertTrue(m.completeRequest(op, song("歌" + i, op, 60_000L)));
        }
        assertEquals(MusicQueueManager.BeginResult.QUEUE_FULL, m.tryBeginRequest(op, true));
    }

    @Test
    void concurrentPrivilegedRequestsRecheckLimit() {
        UUID op = UUID.randomUUID();
        // 两个并发搜索都通过了 begin 检查（队列剩 1 个位置）
        for (int i = 0; i < MusicQueueManager.MAX_QUEUE_SIZE - 1; i++) {
            m.tryBeginRequest(op, true);
            m.completeRequest(op, song("歌" + i, op, 60_000L));
        }
        m.tryBeginRequest(op, true);
        m.tryBeginRequest(op, true);
        // 两个 HTTP 先后返回：第一个入队成功，第二个必须被回检拒绝
        assertTrue(m.completeRequest(op, song("A", op, 60_000L)));
        assertFalse(m.completeRequest(op, song("B", op, 60_000L)), "completeRequest 必须回检队列上限");
    }

    @Test
    void skipUnlocksRequester() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        m.tryBeginRequest(a, false);
        m.completeRequest(a, song("A的歌", a, 60_000L));
        // b 点满 3 首（第 1 首排队，2/3 继续）
        for (int i = 1; i <= 3; i++) {
            m.tryBeginRequest(b, false);
            m.completeRequest(b, song("B歌" + i, b, 60_000L));
        }
        // 当前是 A 的歌
        m.skip();
        // A 的歌被跳（A 系统内 0 首）→ A 可点；B 仍有 3 首 → 拒
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        assertEquals(MusicQueueManager.BeginResult.LOCKED, m.tryBeginRequest(b, false));
        // skip 后 B 的歌 1 开始（Start #2）
        assertEquals(2, rec.starts.size());
        assertEquals("B歌1", rec.starts.get(1).title());
    }

    @Test
    void stopAllCancelsEverythingWithEpoch() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        m.tryBeginRequest(a, false);
        m.completeRequest(a, song("A1", a, 60_000L));
        m.tryBeginRequest(b, false);
        m.completeRequest(b, song("B1", b, 60_000L));
        // 第三人 c 发起搜索（pending 中；a/b 已锁定无法再发起）
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(c, false));

        m.stopAll();
        assertEquals(1, rec.stops.size());
        assertEquals(MusicStopPayload.Reason.QUEUE_CLEARED, rec.stops.get(0).reason());
        // 全员解锁
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(b, false));
        // stop 前发起的旧搜索结果返回 → epoch 不符 → 丢弃
        assertFalse(m.completeRequest(c, song("旧结果", c, 60_000L)), "epoch 不符的结果必须丢弃");
        assertTrue(rec.starts.stream().noneMatch(p -> "旧结果".equals(p.title())));
    }

    @Test
    void logoutDoesNotUnlock() {
        UUID a = UUID.randomUUID();
        // 点满 3 首
        for (int i = 1; i <= 3; i++) {
            m.tryBeginRequest(a, false);
            m.completeRequest(a, song("A" + i, a, 60_000L));
        }
        m.onPlayerLogout(a);
        assertEquals(MusicQueueManager.BeginResult.LOCKED, m.tryBeginRequest(a, false), "掉线不得解锁");
    }

    @Test
    void sameSongIdDifferentPlaybackId() {
        UUID a = UUID.randomUUID();
        m.tryBeginRequest(a, false);
        m.completeRequest(a, song("X", a, 1000L));
        long firstId = rec.starts.get(0).playbackId();
        clock.advanceMs(3000L);
        m.tick(); // 播完
        m.tryBeginRequest(a, false);
        m.completeRequest(a, song("X", a, 1000L)); // 同一首再点
        assertNotEquals(firstId, rec.starts.get(1).playbackId(), "同 songId 的两次播放实例 playbackId 必须不同");
    }

    @Test
    void failedQuorumTriggersEarlyFinish() {
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        m.tryBeginRequest(requester, true);
        m.completeRequest(requester, song("Q", requester, 60_000L));
        long playbackId = rec.starts.get(0).playbackId();

        // 单客户端失败（1/3 < 50%）→ 不切歌
        m.onClientFailure(c1, playbackId, MusicPlaybackFailedPayload.FailureCode.HTTP_FAILED);
        clock.advanceMs(60_000L + 2_000L);
        m.tick();
        assertEquals(1, rec.stops.size());
        assertEquals(MusicStopPayload.Reason.FINISHED, rec.stops.get(0).reason(), "quorum 未达按权威计时 FINISHED");

        // 重新来一轮：2/3 ≥ 50% → 提前 FAILED
        m.tryBeginRequest(requester, true);
        m.completeRequest(requester, song("Q2", requester, 60_000L));
        long id2 = rec.starts.get(1).playbackId();
        m.onClientFailure(c1, id2, MusicPlaybackFailedPayload.FailureCode.HTTP_FAILED);
        m.onClientFailure(c1, id2, MusicPlaybackFailedPayload.FailureCode.HTTP_FAILED); // 同玩家重复上报去重
        m.onClientFailure(c2, id2, MusicPlaybackFailedPayload.FailureCode.MP3_DECODE_FAILED);
        assertEquals(2, rec.stops.size());
        assertEquals(MusicStopPayload.Reason.FAILED, rec.stops.get(1).reason(), "2/3 达 quorum 提前 FAILED");
        // 旧 playbackId 迟到上报不误伤（不应有第三条 stop）
        m.onClientFailure(c1, playbackId, MusicPlaybackFailedPayload.FailureCode.HTTP_FAILED);
        assertEquals(2, rec.stops.size());
    }

    @Test
    void loginSyncReturnsCurrentPayload() {
        assertNull(m.currentStartPayload());
        UUID a = UUID.randomUUID();
        m.tryBeginRequest(a, false);
        m.completeRequest(a, song("当前曲", a, 60_000L));
        clock.advanceMs(10_000L);
        MusicStartPayload p = m.currentStartPayload();
        assertNotNull(p);
        assertEquals("当前曲", p.title());
        assertEquals(10_000L, p.positionMs(), "登录同步必须带已播进度");
    }
}
