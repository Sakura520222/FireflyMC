package firefly520.fireflymc.music;

import java.util.UUID;

/**
 * 队列项：搜索结果 + 元数据（时长由虚拟线程探测后传入）
 */
public record QueuedSong(
        String songId,
        String title,
        String author,
        String lrc,
        String requesterName,
        UUID requesterId,
        long durationMs
) {}
