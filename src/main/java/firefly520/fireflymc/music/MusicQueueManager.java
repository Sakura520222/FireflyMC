package firefly520.fireflymc.music;

import firefly520.fireflymc.network.MusicPlaybackFailedPayload;
import firefly520.fireflymc.network.MusicQueueSyncPayload;
import firefly520.fireflymc.network.MusicStartPayload;
import firefly520.fireflymc.network.MusicStopPayload;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 点歌队列状态机（服务端权威）。
 * 所有方法只允许在逻辑服务端线程调用（单线程状态所有权，无锁）；
 * 广播/时钟/能力查询经构造注入，核心逻辑可在 JUnit 中脱离 MC 测试。
 */
public class MusicQueueManager {

    public static final int MAX_QUEUE_SIZE = 50;
    /** 普通玩家同时在系统内（播放中 + 排队中）的自点歌曲上限 */
    public static final int PLAYER_SONG_LIMIT = 3;
    /** pending 请求硬超时：服务端搜索有 watchdog 兜底（≈28s 上界），客户端代搜索路径
     *  的回包在玩家中途退出时会丢失——到期强制释放，防永久 pending 占额 */
    private static final long PENDING_TIMEOUT_NS = 90_000L * 1_000_000L;
    /** 权威切歌容差 */
    private static final long END_TOLERANCE_MS = 2_000L;
    /** FAILED quorum：失败客户端 ≥ 分母的此比例且 ≥ 2 个（分母 ≤ 1 时单失败即触发） */
    private static final double FAILED_QUORUM_RATIO = 0.5;

    public enum BeginResult { ACCEPTED, LOCKED, PENDING, QUEUE_FULL }

        /**
     * 异步搜索会话（捕获发起时的 epoch 与硬超时时刻）。
     * id 为单实例内唯一序号：特权者可在同一代内并发多个在途会话，须按唯一身份认领
     * （仅凭 epoch 相等会在队列中误删兄弟会话）；deadline 用于玩家中途退出等
     * 回包丢失场景的兜底释放。
     */
    public record SearchSession(long id, long epoch, long deadlineNs) {}

    public interface StartBroadcaster extends Consumer<MusicStartPayload> {}
    public interface StopBroadcaster extends Consumer<MusicStopPayload> {}
    public interface QueueBroadcaster extends Consumer<MusicQueueSyncPayload> {}

    /** 查询音乐能力客户端（musicCapablePlayers ∩ 在线，由集成层提供判定与计数） */
    public interface CapabilityLookup {
        boolean isCapable(UUID player);

        default int capableOnlineCount() {
            return 2;
        }
    }

    /** FAILED 终态通知（集成层按失败性质给点歌者发不同提示） */
    public interface FailureNotifier extends BiConsumer<QueuedSong, MusicPlaybackFailedPayload.FailureCode> {}

    private final LongSupplier clock; // System.nanoTime 语义
    private final StartBroadcaster startBroadcaster;
    private final StopBroadcaster stopBroadcaster;
    private final QueueBroadcaster queueBroadcaster;
    private final CapabilityLookup capabilityLookup;
    private final FailureNotifier failureNotifier;

    private final ArrayDeque<QueuedSong> queue = new ArrayDeque<>();
    /** 玩家在系统内（播放中+排队）的自点歌曲数；0 = 可点 */
    private final Map<UUID, Integer> activeSongs = new HashMap<>();
    private final Set<UUID> pendingPlayers = new HashSet<>();
    /** playbackId -> 上报失败的客户端 → 首个失败码（去重；仅确定性失败入表） */
    private final Map<Long, Map<UUID, MusicPlaybackFailedPayload.FailureCode>> failedClients = new HashMap<>();
    /** 在途搜索会话（特权者可并发多个，按 session 身份认领移除；各自带硬超时） */
    private final Map<UUID, ArrayDeque<SearchSession>> pendingSessions = new HashMap<>();
    /** 已进入客户端代搜索的会话签发的防伪造 token（sessionId → token） */
    private final Map<Long, Long> proxyTokens = new HashMap<>();

    private long queueEpoch = 0L;
    private long nextPlaybackId = 1L; // 恒 > 0；0 保留给协议"无实例"
    private long nextSessionId = 1L;  // 会话唯一序号（防同代会话值相等误删）
    private QueuedSong currentSong;
    private long currentPlaybackId;
    private long currentStartNano;
    private SearchSession latestSession;

    public MusicQueueManager(LongSupplier clock,
                             StartBroadcaster startBroadcaster,
                             StopBroadcaster stopBroadcaster,
                             QueueBroadcaster queueBroadcaster,
                             CapabilityLookup capabilityLookup) {
        this(clock, startBroadcaster, stopBroadcaster, queueBroadcaster, capabilityLookup, (song, code) -> {});
    }

    public MusicQueueManager(LongSupplier clock,
                             StartBroadcaster startBroadcaster,
                             StopBroadcaster stopBroadcaster,
                             QueueBroadcaster queueBroadcaster,
                             CapabilityLookup capabilityLookup,
                             FailureNotifier failureNotifier) {
        this.clock = clock;
        this.startBroadcaster = startBroadcaster;
        this.stopBroadcaster = stopBroadcaster;
        this.queueBroadcaster = queueBroadcaster;
        this.capabilityLookup = capabilityLookup;
        this.failureNotifier = failureNotifier;
    }

    // ---------- 点歌流程 ----------

    /** 命令线程（服务端线程）调用：权限/自点上限/pending/队列上限检查 */
    public BeginResult tryBeginRequest(UUID player, boolean privileged) {
        if (!privileged) {
            if (activeSongs.getOrDefault(player, 0) >= PLAYER_SONG_LIMIT) {
                return BeginResult.LOCKED;
            }
            if (pendingPlayers.contains(player)) {
                return BeginResult.PENDING;
            }
        }
        if (totalSongsInSystem() >= MAX_QUEUE_SIZE) {
            return BeginResult.QUEUE_FULL;
        }
        pendingPlayers.add(player);
        latestSession = new SearchSession(nextSessionId++, queueEpoch, clock.getAsLong() + PENDING_TIMEOUT_NS);
        pendingSessions.computeIfAbsent(player, k -> new ArrayDeque<>()).add(latestSession);
        return BeginResult.ACCEPTED;
    }

    /** 系统内总曲目 = 当前播放 + 排队中（上限防的是 QueueSync 广播的完整列表膨胀） */
    private int totalSongsInSystem() {
        return queue.size() + (currentSong != null ? 1 : 0);
    }

    public SearchSession latestSession() {
        return latestSession;
    }

    /**
     * 按 id 定位该玩家的在途会话（客户端代搜索回包认领用，不消费）。
     * @return 匹配的会话；无在途会话或 id 不存在返回 null
     */
    public SearchSession findPendingSession(UUID player, long sessionId) {
        ArrayDeque<SearchSession> sessions = pendingSessions.get(player);
        if (sessions == null || sessionId <= 0) {
            return null;
        }
        for (SearchSession s : sessions) {
            if (s.id() == sessionId) {
                return s;
            }
        }
        return null;
    }

    /**
     * 标记会话进入客户端代搜索并签发不可猜测 token（服务端线程）。
     * 回包必须原样携带此 token 才被受理——sessionId 单调可预测，
     * 无 token 绑定时改版客户端可伪造回包绕过服务端搜索与可播性预检。
     *
     * @return token；会话不存在/已被消费返回 0
     */
    public long markProxyDelegated(UUID player, long sessionId) {
        SearchSession session = findPendingSession(player, sessionId);
        if (session == null) {
            return 0L;
        }
        long token = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        proxyTokens.put(sessionId, token);
        return token;
    }

    /** 校验回包 token（不消费；会话认领时随 completeRequest/failRequest 一并失效） */
    public boolean isProxyTokenValid(long sessionId, long token) {
        Long expected = proxyTokens.get(sessionId);
        return expected != null && token != 0 && expected == token;
    }

    /** 虚拟线程搜索成功后，经 server.execute 回到服务端线程调用。
     *  必须携带发起时捕获的 session（按唯一身份认领）：/stop 清掉在途请求后同玩家重发
     *  新请求时，旧回调不得消费新会话——否则被取消的歌死灰复燃、新请求被误删。
     *  特权者可同代并发多个在途会话，各自独立认领。
     *  @return true=入队成功；false=丢弃（session 失效/epoch 失效/队列已满） */
    public boolean completeRequest(UUID player, SearchSession session, QueuedSong song) {
        ArrayDeque<SearchSession> sessions = pendingSessions.get(player);
        if (session == null || sessions == null || !sessions.remove(session)) {
            return false; // 旧会话的迟到回调：丢弃且不动在途会话
        }
        proxyTokens.remove(session.id()); // 会话已消费：其代搜索 token 一并失效
        if (sessions.isEmpty()) {
            pendingSessions.remove(player);
        }
        // 普通玩家单会话此刻已无在途；特权者本就不受 pending 检查，移除无害
        pendingPlayers.remove(player);
        if (session.epoch() != queueEpoch) {
            return false; // stop 期间发起的旧请求，丢弃
        }
        if (totalSongsInSystem() >= MAX_QUEUE_SIZE) {
            return false; // 回检：多个特权请求并发搜索时可能同时通过 begin 检查
        }
        activeSongs.merge(song.requesterId(), 1, Integer::sum);
        queue.add(song);
        broadcastQueueSync();
        if (currentSong == null) {
            startNext();
        }
        return true;
    }

    /** 虚拟线程搜索失败后调用：移除 pending，不锁定。同样按 session 身份认领 */
    public void failRequest(UUID player, SearchSession session) {
        ArrayDeque<SearchSession> sessions = pendingSessions.get(player);
        if (session == null || sessions == null || !sessions.remove(session)) {
            return; // 旧会话的迟到失败：不动在途会话
        }
        proxyTokens.remove(session.id());
        if (sessions.isEmpty()) {
            pendingSessions.remove(player);
            pendingPlayers.remove(player);
        }
    }

    // ---------- 播放推进 ----------

    /** 服务端每 tick 调用：权威计时切歌 + 在途搜索会话硬超时兜底 */
    public void tick() {
        expireStalePendings();
        if (currentSong == null) {
            return;
        }
        long elapsedMs = (clock.getAsLong() - currentStartNano) / 1_000_000L;
        if (elapsedMs >= currentSong.durationMs() + END_TOLERANCE_MS) {
            finishCurrent(MusicStopPayload.Reason.FINISHED);
        }
    }

    /** 强制释放超时未回包的 pending（玩家中途退出等场景防永久占额） */
    private void expireStalePendings() {
        long now = clock.getAsLong();
        var it = pendingSessions.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            e.getValue().removeIf(s -> {
                if (now >= s.deadlineNs()) {
                    proxyTokens.remove(s.id()); // 过期会话的代搜索 token 一并失效
                    return true;
                }
                return false;
            });
            if (e.getValue().isEmpty()) {
                pendingPlayers.remove(e.getKey());
                it.remove();
            }
        }
    }

    /** 特权者跳过当前曲（不影响队列与进行中的搜索） */
    public void skip() {
        if (currentSong != null) {
            finishCurrent(MusicStopPayload.Reason.SKIPPED);
        }
    }

    /** 特权者全清：当前曲+队列+pending 全部 CANCELLED，epoch++ 作废旧异步结果 */
    public void stopAll() {
        queueEpoch++;
        if (currentSong != null) {
            UUID requester = currentSong.requesterId();
            long id = currentPlaybackId;
            currentSong = null;
            decrementActive(requester);
            stopBroadcaster.accept(new MusicStopPayload(id, MusicStopPayload.Reason.QUEUE_CLEARED));
        } else {
            stopBroadcaster.accept(new MusicStopPayload(0L, MusicStopPayload.Reason.QUEUE_CLEARED));
        }
        while (!queue.isEmpty()) {
            decrementActive(queue.poll().requesterId());
        }
        pendingPlayers.clear();
        pendingSessions.clear();
        proxyTokens.clear();
        failedClients.clear();
        broadcastQueueSync();
    }

    // ---------- 失败聚合 ----------

    /** 客户端失败上报：去重 + quorum。仅受理当前 playbackId */
    public void onClientFailure(UUID client, long playbackId,
                                MusicPlaybackFailedPayload.FailureCode code) {
        if (currentSong == null || playbackId != currentPlaybackId) {
            return; // 旧实例迟到上报，不误伤
        }
        // 网络型失败（播放中瞬断/瞬态连接失败）是客户端局部网络问题，不代表音源不可播，
        // 不得投全局跳歌票（Issue #64：单人一次瞬断即跳歌、双人同抖即误杀）——
        // 客户端自行重开流恢复，恢复失败静音到曲终，由权威计时自然切歌
        if (code == MusicPlaybackFailedPayload.FailureCode.STREAM_INTERRUPTED
                || code == MusicPlaybackFailedPayload.FailureCode.NETWORK_FAILED) {
            return;
        }
        Map<UUID, MusicPlaybackFailedPayload.FailureCode> failed =
                failedClients.computeIfAbsent(playbackId, k -> new HashMap<>());
        if (failed.putIfAbsent(client, code) != null) {
            return; // 同玩家同实例去重
        }
        if (shouldFailEarly(failed.keySet())) {
            finishCurrent(MusicStopPayload.Reason.FAILED);
        }
    }

    private boolean shouldFailEarly(Set<UUID> failed) {
        int capableOnline = capabilityLookup.capableOnlineCount();
        // 分子只计仍在场的失败者：A 失败后退服时其记录仍留在 failedClients，
        // 不过滤会让后来 B 的一次真实失败被累计成两票、凭空满足 quorum
        int activeFailed = (int) failed.stream().filter(capabilityLookup::isCapable).count();
        if (capableOnline <= 1) {
            return activeFailed >= 1; // 单人世界：唯一客户端失败立即 FAILED
        }
        return activeFailed >= 2 && activeFailed * 2 >= (int) Math.ceil(capableOnline * FAILED_QUORUM_RATIO * 2);
    }

    public boolean isMusicCapable(UUID player) {
        return capabilityLookup.isCapable(player);
    }

    // ---------- 登录/登出 ----------

    /** 玩家登录：返回当前曲的同步 payload（含已播进度），无播放返回 null */
    public MusicStartPayload currentStartPayload() {
        if (currentSong == null) {
            return null;
        }
        long elapsedMs = (clock.getAsLong() - currentStartNano) / 1_000_000L;
        return toStartPayload(currentSong, elapsedMs);
    }

    /** 玩家登出：locked 故意保留（掉线不解锁）；capability 集合由集成层维护 */
    public void onPlayerLogout(UUID player) {
        // 无状态可清
    }

    /** 当前曲概要（/queue 命令输出与登录定向同步用），无播放返回 null */
    public MusicQueueSyncPayload.SongSummary currentSummary() {
        return currentSong == null ? null
                : new MusicQueueSyncPayload.SongSummary(currentSong.title(), currentSong.author(), currentSong.requesterName());
    }

    /** 登录定向同步的队列快照；系统完全空闲（无当前曲且队列空）返回 null */
    public MusicQueueSyncPayload currentQueueSyncPayload() {
        if (currentSong == null && queue.isEmpty()) {
            return null;
        }
        return new MusicQueueSyncPayload(currentSummary(), queueSummaries());
    }

    /** 供 /fireflymc music queue 命令读取完整队列 */
    public List<MusicQueueSyncPayload.SongSummary> queueSummaries() {
        return queue.stream()
                .map(q -> new MusicQueueSyncPayload.SongSummary(q.title(), q.author(), q.requesterName()))
                .toList();
    }

    // ---------- 内部 ----------

    private void startNext() {
        QueuedSong next = queue.poll();
        if (next == null) {
            return;
        }
        currentSong = next;
        currentPlaybackId = nextPlaybackId++;
        currentStartNano = clock.getAsLong();
        failedClients.clear();
        startBroadcaster.accept(toStartPayload(next, 0L));
        broadcastQueueSync();
    }

    private void finishCurrent(MusicStopPayload.Reason reason) {
        UUID requester = currentSong.requesterId();
        long id = currentPlaybackId;
        QueuedSong finished = currentSong;
        currentSong = null;
        decrementActive(requester);
        // FAILED 通知需主导失败码（先取后清；quorum 只收音源型码，此处取首个上报者）
        Map<UUID, MusicPlaybackFailedPayload.FailureCode> failed = failedClients.remove(id);
        if (reason == MusicStopPayload.Reason.FAILED) {
            MusicPlaybackFailedPayload.FailureCode code = failed == null || failed.isEmpty()
                    ? MusicPlaybackFailedPayload.FailureCode.MP3_DECODE_FAILED
                    : failed.values().iterator().next();
            failureNotifier.accept(finished, code);
        }
        if (!queue.isEmpty()) {
            startNext(); // 下一首立即开始（Start 即隐式 stop 旧曲）
        } else {
            stopBroadcaster.accept(new MusicStopPayload(id, reason));
        }
        broadcastQueueSync();
    }

    /** 自点歌曲数 -1，减到 0 移除记录 */
    private void decrementActive(UUID requester) {
        activeSongs.computeIfPresent(requester, (k, v) -> v <= 1 ? null : v - 1);
    }

    private MusicStartPayload toStartPayload(QueuedSong s, long positionMs) {
        return new MusicStartPayload(
                currentPlaybackId,
                s.songId(), s.title(), s.author(), s.lrc(), s.requesterName(),
                s.durationMs(), positionMs);
    }

    private void broadcastQueueSync() {
        queueBroadcaster.accept(new MusicQueueSyncPayload(currentSummary(), queueSummaries()));
    }
}
