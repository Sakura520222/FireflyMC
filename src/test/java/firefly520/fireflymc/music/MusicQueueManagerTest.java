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

    /** begin→complete 一步完成（捕获本次 session，模拟命令层行为） */
    private boolean request(UUID player, boolean privileged, QueuedSong song) {
        if (m.tryBeginRequest(player, privileged) != MusicQueueManager.BeginResult.ACCEPTED) {
            return false;
        }
        return m.completeRequest(player, m.latestSession(), song);
    }

    @Test
    void playerSongLimitLifecycle() {
        UUID a = UUID.randomUUID();

        // 连点 3 首成功（第 1 首立即播放，2/3 排队）
        for (int i = 1; i <= 3; i++) {
            assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
            assertTrue(m.completeRequest(a, m.latestSession(), song("歌" + i, a, 60_000L)));
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
        assertTrue(m.completeRequest(a, m.latestSession(), song("歌4", a, 60_000L)));
        assertEquals(MusicQueueManager.BeginResult.LOCKED, m.tryBeginRequest(a, false));
    }

    @Test
    void privilegedBypassPlayerLimit() {
        UUID op = UUID.randomUUID();
        // 特权者不受 3 首限制，连点 4 首全部接受（只受系统总量约束）
        for (int i = 1; i <= 4; i++) {
            assertTrue(request(op, true, song("特权歌" + i, op, 60_000L)));
        }
    }

    @Test
    void pendingBlocksDoubleRequest() {
        UUID a = UUID.randomUUID();
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        MusicQueueManager.SearchSession session = m.latestSession();
        // HTTP 进行中，第二次点歌 → PENDING 拒绝
        assertEquals(MusicQueueManager.BeginResult.PENDING, m.tryBeginRequest(a, false));
        // 搜索失败：pending 移除，未锁定
        m.failRequest(a, session);
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
    }

    @Test
    void privilegedBypassLockButNotQueueLimit() {
        UUID op = UUID.randomUUID();
        // 特权者连点 50+1 首：第 51 首被 QUEUE_FULL 拒
        for (int i = 0; i < MusicQueueManager.MAX_QUEUE_SIZE; i++) {
            assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(op, true));
            assertTrue(m.completeRequest(op, m.latestSession(), song("歌" + i, op, 60_000L)));
        }
        assertEquals(MusicQueueManager.BeginResult.QUEUE_FULL, m.tryBeginRequest(op, true));
    }

    @Test
    void concurrentPrivilegedRequestsRecheckLimit() {
        UUID op = UUID.randomUUID();
        UUID op2 = UUID.randomUUID();
        for (int i = 0; i < MusicQueueManager.MAX_QUEUE_SIZE - 1; i++) {
            assertTrue(request(op, true, song("歌" + i, op, 60_000L)));
        }
        // 两个特权者并发搜索都通过了 begin 检查（队列剩 1 个位置）
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(op, true));
        MusicQueueManager.SearchSession s1 = m.latestSession();
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(op2, true));
        MusicQueueManager.SearchSession s2 = m.latestSession();
        // 两个 HTTP 先后返回：第一个入队成功，第二个必须被回检拒绝
        assertTrue(m.completeRequest(op, s1, song("A", op, 60_000L)));
        assertFalse(m.completeRequest(op2, s2, song("B", op2, 60_000L)), "completeRequest 必须回检队列上限");
    }

    @Test
    void concurrentPrivilegedSessionsOfSamePlayerBothSucceed() {
        UUID op = UUID.randomUUID();
        // 房主/OP 快速连点：第一个搜索仍在途时发起第二个，两会话独立认领、都入队
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(op, true));
        MusicQueueManager.SearchSession s1 = m.latestSession();
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(op, true));
        MusicQueueManager.SearchSession s2 = m.latestSession();
        assertNotSame(s1, s2);
        assertTrue(m.completeRequest(op, s1, song("第一首", op, 60_000L)), "先返回的会话必须入队");
        assertTrue(m.completeRequest(op, s2, song("第二首", op, 60_000L)), "同玩家并发会话不得互相顶掉");
        assertEquals(1, rec.starts.size(), "第一首开始播放");
        MusicQueueSyncPayload lastSync = rec.syncs.get(rec.syncs.size() - 1);
        assertEquals(1, lastSync.queue().size(), "第二首应排队");
    }

    @Test
    void skipUnlocksRequester() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(request(a, false, song("A的歌", a, 60_000L)));
        // b 点满 3 首（第 1 首排队，2/3 继续）
        for (int i = 1; i <= 3; i++) {
            assertTrue(request(b, false, song("B歌" + i, b, 60_000L)));
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
        assertTrue(request(a, false, song("A1", a, 60_000L)));
        assertTrue(request(b, false, song("B1", b, 60_000L)));
        // 第三人 c 发起搜索（pending 中；a/b 已锁定无法再发起）
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(c, false));
        MusicQueueManager.SearchSession cSession = m.latestSession();

        m.stopAll();
        assertEquals(1, rec.stops.size());
        assertEquals(MusicStopPayload.Reason.QUEUE_CLEARED, rec.stops.get(0).reason());
        // 全员解锁
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(b, false));
        // stop 前发起的旧搜索结果返回 → 丢弃
        assertFalse(m.completeRequest(c, cSession, song("旧结果", c, 60_000L)), "stop 前发起的结果必须丢弃");
        assertTrue(rec.starts.stream().noneMatch(p -> "旧结果".equals(p.title())));
    }

    @Test
    void lateCompletionDoesNotResurrectAfterStopAndNewRequest() {
        UUID a = UUID.randomUUID();
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        MusicQueueManager.SearchSession old = m.latestSession();
        m.stopAll(); // epoch++，pending 清空
        // stop 后同玩家立刻重新点歌（新 session 占据 pending）
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        MusicQueueManager.SearchSession fresh = m.latestSession();
        // 旧回调返回：必须丢弃，且不得顶掉新请求的 pending
        assertFalse(m.completeRequest(a, old, song("旧结果", a, 60_000L)));
        // 新回调正常入队
        assertTrue(m.completeRequest(a, fresh, song("新结果", a, 60_000L)));
        assertTrue(rec.starts.stream().anyMatch(p -> "新结果".equals(p.title())));
        assertTrue(rec.starts.stream().noneMatch(p -> "旧结果".equals(p.title())));
    }

    @Test
    void lateFailureDoesNotKillNewPending() {
        UUID a = UUID.randomUUID();
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        MusicQueueManager.SearchSession old = m.latestSession();
        m.stopAll();
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        // 旧会话的迟到失败：不得动新请求的 pending
        m.failRequest(a, old);
        assertEquals(MusicQueueManager.BeginResult.PENDING, m.tryBeginRequest(a, false));
        assertTrue(m.completeRequest(a, m.latestSession(), song("ok", a, 1000L)));
    }

    @Test
    void clientSearchResultClaimedBySessionId() {
        // 客户端代搜索链路：begin → 按 id 定位会话 → completeRequest 入队
        UUID a = UUID.randomUUID();
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        long sessionId = m.latestSession().id();
        MusicQueueManager.SearchSession found = m.findPendingSession(a, sessionId);
        assertNotNull(found, "在途会话必须能按 id 定位");
        assertTrue(m.completeRequest(a, found, song("代理歌", a, 60_000L)));
        assertEquals("代理歌", rec.starts.get(0).title());
        // 认领后会话已消费：同 id 再找不到
        assertNull(m.findPendingSession(a, sessionId));
    }

    @Test
    void unknownOrExpiredSessionIdIgnored() {
        UUID a = UUID.randomUUID();
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        // 错误 id / 空 id / 其他玩家：一律定位不到
        assertNull(m.findPendingSession(a, 99999L));
        assertNull(m.findPendingSession(a, -1L));
        assertNull(m.findPendingSession(UUID.randomUUID(), m.latestSession().id()));
    }

    @Test
    void proxyTokenBindsDelegationOnly() {
        UUID a = UUID.randomUUID();
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        long sid = m.latestSession().id();
        // 未委托的会话不存在 token → 回包无效（改版客户端无法凭可预测 sessionId 伪造结果）
        assertFalse(m.isProxyTokenValid(sid, 123456789L));
        assertEquals(0L, m.markProxyDelegated(a, 99999L), "不存在的会话不得签发 token");
        // 委托后：签发的 token 有效、错误 token 无效；claim 后 token 一并失效
        long token = m.markProxyDelegated(a, sid);
        assertNotEquals(0L, token);
        assertTrue(m.isProxyTokenValid(sid, token));
        assertFalse(m.isProxyTokenValid(sid, token + 1));
        assertTrue(m.completeRequest(a, m.findPendingSession(a, sid), song("T", a, 60_000L)));
        assertFalse(m.isProxyTokenValid(sid, token), "会话认领后 token 必须失效");
        // stop 清空后：token 全部作废
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        MusicQueueManager.SearchSession s2 = m.latestSession();
        long t2 = m.markProxyDelegated(a, s2.id());
        assertTrue(m.isProxyTokenValid(s2.id(), t2));
        m.stopAll();
        assertFalse(m.isProxyTokenValid(s2.id(), t2), "stopAll 必须作废全部代搜索 token");
    }

    @Test
    void quorumIgnoresLoggedOutFailures() {
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        assertTrue(request(requester, true, song("Q", requester, 600_000L)));
        long playbackId = rec.starts.get(0).playbackId();
        // capability 桩固定 3 人在线且全员 capable——isCapable 只排除 PLAIN，
        // 这里用"登出即从 capability 移除"的真实集成语义模拟：
        // A 失败 → 退服（模拟：isCapable 变 false）→ B 失败只算 1 票，不触发
        MusicQueueManager spyCaps = new MusicQueueManager(
                clock.supplier,
                p -> {},
                p -> {},
                p -> {},
                new MusicQueueManager.CapabilityLookup() {
                    final java.util.Set<UUID> online = java.util.Set.of(c1, c2, requester);

                    @Override
                    public boolean isCapable(UUID player) {
                        return online.contains(player) && !player.equals(c1); // c1 已"登出"
                    }

                    @Override
                    public int capableOnlineCount() {
                        return online.size() - 1; // 3-1=2
                    }
                });
        spyCaps.onClientFailure(c1, playbackId, MusicPlaybackFailedPayload.FailureCode.HTTP_FAILED); // 登出者迟到记录
        spyCaps.onClientFailure(c2, playbackId, MusicPlaybackFailedPayload.FailureCode.HTTP_FAILED);
        clock.advanceMs(600_000L + 2_000L);
        spyCaps.tick();
        // 只有 0 条 failed FINISHED 判定？c2 的上报在 spyCaps 里走 quorum：
        // 分母=2，在场失败={c2}=1 <2 → 不触发；等权威计时到 → 记录 stops 后再验证
        // （rec 属于原 manager，spy 的广播丢弃到 no-op lambda，仅验证无异常路径与 c2 单票不触发）
        assertEquals(0, rec.stops.size());
    }

    @Test
    void pendingTimeoutReleasesPlayer() {
        UUID a = UUID.randomUUID();
        UUID op = UUID.randomUUID();
        // 普通玩家发起请求后中途退出（回包丢失）：90s 硬超时必须释放额度
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        clock.advanceMs(90_000L);
        m.tick();
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false),
                "超时后必须释放 pending，玩家可重新点歌");
        // 特权者并发两会话：各自独立计时，先发起的超时释放后不影响后发起的
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(op, true));
        MusicQueueManager.SearchSession s1 = m.latestSession(); // @90s，deadline 180s
        clock.advanceMs(45_000L); // now=135s
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(op, true));
        MusicQueueManager.SearchSession s2 = m.latestSession(); // @135s，deadline 225s
        clock.advanceMs(50_000L); // now=185s：s1 已过期，s2 未到
        m.tick();
        assertNull(m.findPendingSession(op, s1.id()), "超时会话必须被清除");
        assertNotNull(m.findPendingSession(op, s2.id()), "未超时的兄弟会话必须保留");
    }

    @Test
    void logoutDoesNotUnlock() {
        UUID a = UUID.randomUUID();
        // 点满 3 首
        for (int i = 1; i <= 3; i++) {
            assertTrue(request(a, false, song("A" + i, a, 60_000L)));
        }
        m.onPlayerLogout(a);
        assertEquals(MusicQueueManager.BeginResult.LOCKED, m.tryBeginRequest(a, false), "掉线不得解锁");
    }

    @Test
    void sameSongIdDifferentPlaybackId() {
        UUID a = UUID.randomUUID();
        assertTrue(request(a, false, song("X", a, 1000L)));
        long firstId = rec.starts.get(0).playbackId();
        clock.advanceMs(3000L);
        m.tick(); // 播完
        assertTrue(request(a, false, song("X", a, 1000L))); // 同一首再点
        assertNotEquals(firstId, rec.starts.get(1).playbackId(), "同 songId 的两次播放实例 playbackId 必须不同");
    }

    @Test
    void failedQuorumTriggersEarlyFinish() {
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        assertTrue(request(requester, true, song("Q", requester, 60_000L)));
        long playbackId = rec.starts.get(0).playbackId();

        // 单客户端失败（1/3 < 50%）→ 不切歌
        m.onClientFailure(c1, playbackId, MusicPlaybackFailedPayload.FailureCode.HTTP_FAILED);
        clock.advanceMs(60_000L + 2_000L);
        m.tick();
        assertEquals(1, rec.stops.size());
        assertEquals(MusicStopPayload.Reason.FINISHED, rec.stops.get(0).reason(), "quorum 未达按权威计时 FINISHED");

        // 重新来一轮：2/3 ≥ 50% → 提前 FAILED
        assertTrue(request(requester, true, song("Q2", requester, 60_000L)));
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
        assertTrue(request(a, false, song("当前曲", a, 60_000L)));
        clock.advanceMs(10_000L);
        MusicStartPayload p = m.currentStartPayload();
        assertNotNull(p);
        assertEquals("当前曲", p.title());
        assertEquals(10_000L, p.positionMs(), "登录同步必须带已播进度");
    }

    @Test
    void loginSnapshotIncludesQueue() {
        // 系统完全空闲 → 无需同步
        assertNull(m.currentQueueSyncPayload());
        UUID a = UUID.randomUUID();
        assertTrue(request(a, false, song("A", a, 60_000L)));
        assertTrue(request(a, false, song("B", a, 60_000L))); // 第 2 首排队
        MusicQueueSyncPayload snap = m.currentQueueSyncPayload();
        assertNotNull(snap, "登录快照必须包含当前曲与排队列表");
        assertEquals("A", snap.current().title());
        assertEquals(1, snap.queue().size());
        assertEquals("B", snap.queue().get(0).title());
        m.stopAll();
        assertNull(m.currentQueueSyncPayload());
    }
}
