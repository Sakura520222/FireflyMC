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
    /** 权威切歌容差 */
    private static final long END_TOLERANCE_MS = 2_000L;
    /** FAILED quorum：失败客户端 ≥ 分母的此比例且 ≥ 2 个（分母 ≤ 1 时单失败即触发） */
    private static final double FAILED_QUORUM_RATIO = 0.5;

    public enum BeginResult { ACCEPTED, LOCKED, PENDING, QUEUE_FULL }

    /** 异步搜索会话（捕获发起时的 epoch） */
    public record SearchSession(long epoch) {}

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

    /** FAILED 终态通知（集成层给点歌者发提示消息） */
    public interface FailureNotifier extends Consumer<QueuedSong> {}

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
    /** playbackId -> 上报失败的客户端集合（去重） */
    private final Map<Long, Set<UUID>> failedClients = new HashMap<>();
    /** pending 请求的会话（completeRequest 时校验/移除） */
    private final Map<UUID, SearchSession> pendingSessions = new HashMap<>();

    private long queueEpoch = 0L;
    private long nextPlaybackId = 1L; // 恒 > 0；0 保留给协议"无实例"
    private QueuedSong currentSong;
    private long currentPlaybackId;
    private long currentStartNano;
    private SearchSession latestSession;

    public MusicQueueManager(LongSupplier clock,
                             StartBroadcaster startBroadcaster,
                             StopBroadcaster stopBroadcaster,
                             QueueBroadcaster queueBroadcaster,
                             CapabilityLookup capabilityLookup) {
        this(clock, startBroadcaster, stopBroadcaster, queueBroadcaster, capabilityLookup, song -> {});
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
        latestSession = new SearchSession(queueEpoch);
        pendingSessions.put(player, latestSession);
        return BeginResult.ACCEPTED;
    }

    /** 系统内总曲目 = 当前播放 + 排队中（上限防的是 QueueSync 广播的完整列表膨胀） */
    private int totalSongsInSystem() {
        return queue.size() + (currentSong != null ? 1 : 0);
    }

    public SearchSession latestSession() {
        return latestSession;
    }

    /** 虚拟线程搜索成功后，经 server.execute 回到服务端线程调用。
     *  @return true=入队成功；false=丢弃（epoch 失效/队列已满） */
    public boolean completeRequest(UUID player, QueuedSong song) {
        SearchSession session = pendingSessions.remove(player);
        pendingPlayers.remove(player);
        if (session == null || session.epoch() != queueEpoch) {
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

    /** 虚拟线程搜索失败后调用：移除 pending，不锁定 */
    public void failRequest(UUID player) {
        pendingSessions.remove(player);
        pendingPlayers.remove(player);
    }

    // ---------- 播放推进 ----------

    /** 服务端每 tick 调用：权威计时切歌 */
    public void tick() {
        if (currentSong == null) {
            return;
        }
        long elapsedMs = (clock.getAsLong() - currentStartNano) / 1_000_000L;
        if (elapsedMs >= currentSong.durationMs() + END_TOLERANCE_MS) {
            finishCurrent(MusicStopPayload.Reason.FINISHED);
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
        Set<UUID> failed = failedClients.computeIfAbsent(playbackId, k -> new HashSet<>());
        if (!failed.add(client)) {
            return; // 同玩家同实例去重
        }
        if (shouldFailEarly(failed)) {
            finishCurrent(MusicStopPayload.Reason.FAILED);
        }
    }

    private boolean shouldFailEarly(Set<UUID> failed) {
        int capableOnline = capabilityLookup.capableOnlineCount();
        if (capableOnline <= 1) {
            return true; // 单人世界：唯一客户端失败立即 FAILED
        }
        return failed.size() >= 2 && failed.size() * 2 >= (int) Math.ceil(capableOnline * FAILED_QUORUM_RATIO * 2);
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
        failedClients.remove(id);
        if (reason == MusicStopPayload.Reason.FAILED) {
            failureNotifier.accept(finished); // 播放失败：通知点歌者（可能为付费歌曲等）
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
        var currentSummary = currentSong == null ? null
                : new MusicQueueSyncPayload.SongSummary(currentSong.title(), currentSong.author(), currentSong.requesterName());
        var queueSummaries = queueSummaries();
        queueBroadcaster.accept(new MusicQueueSyncPayload(currentSummary, queueSummaries));
    }
}
