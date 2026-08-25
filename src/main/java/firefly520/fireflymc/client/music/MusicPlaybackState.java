package firefly520.fireflymc.client.music;

import firefly520.fireflymc.network.MusicQueueSyncPayload;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端播放状态（HUD 每 tick 读取；主线程/播放线程写）。
 * 全部字段均为 volatile/不可变快照，避免 HUD 读到半更新状态。
 */
public final class MusicPlaybackState {

    /** 可替换时钟引用：player 线程成功打开 SourceDataLine 后把 Silent 换成精确的 JavaSound 时钟 */
    public static final class ClockRef {
        private volatile PlaybackClock clock;

        public ClockRef(PlaybackClock initial) {
            this.clock = initial;
        }

        public long positionMs() {
            return clock.positionMs();
        }

        void set(PlaybackClock replacement) {
            clock = replacement;
        }
    }

    public record PlayingInfo(
            long playbackId,
            String songId,
            String title,
            String author,
            String requesterName,
            long durationMs,          // 服务端权威时长（HUD 总时长以此为准）
            TreeMap<Long, String> lrc, // 解析后的歌词；空 map 表示无歌词
            ClockRef clock
    ) {}

    private static final AtomicReference<PlayingInfo> CURRENT = new AtomicReference<>();
    private static final AtomicReference<List<MusicQueueSyncPayload.SongSummary>> QUEUE =
            new AtomicReference<>(List.of());

    private MusicPlaybackState() {}

    public static void setPlaying(PlayingInfo info) {
        CURRENT.set(info);
    }

    public static void clearPlaying() {
        CURRENT.set(null);
    }

    public static PlayingInfo current() {
        return CURRENT.get();
    }

    public static void setQueue(List<MusicQueueSyncPayload.SongSummary> queue) {
        QUEUE.set(List.copyOf(queue));
    }

    public static List<MusicQueueSyncPayload.SongSummary> queue() {
        return QUEUE.get();
    }

    /** HUD 用：当前歌词行 */
    public static Optional<String> currentLyricLine() {
        PlayingInfo info = current();
        if (info == null || info.lrc().isEmpty()) {
            return Optional.empty();
        }
        return LrcParser.currentLine(info.lrc(), info.clock().positionMs());
    }
}
