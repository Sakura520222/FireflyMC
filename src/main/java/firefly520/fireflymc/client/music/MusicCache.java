package firefly520.fireflymc.client.music;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 点歌音频缓存。核心不变量：永远不把未完成文件命名成 {songId}.mp3。
 * 失败一律静默降级，绝不影响播放主链路。
 */
public class MusicCache {

    private static final long MAX_CACHE_BYTES = 256L * 1024 * 1024;

    private final Path cacheDir;

    public MusicCache(Path cacheDir) {
        this.cacheDir = cacheDir;
        cleanStaleParts();
    }

    /**
     * 清理崩溃/强杀残留的 .part：本实例此刻不可能有播放线程在写
     * （类初始化链路在首个播放开始前），残留文件无人认领且不计入清理范围，
     * 反复崩溃会无限累积。
     */
    private void cleanStaleParts() {
        try (Stream<Path> files = Files.list(cacheDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".mp3.part"))
                    .forEach(this::deleteQuietly);
        } catch (IOException ignored) {
            // 目录不存在：无需清理
        }
    }

    /** 实际使用的目录（懒创建）：运行目录/music-cache */
    public static MusicCache createDefault() {
        return new MusicCache(Path.of("music-cache"));
    }

    /** 查询已完成的缓存文件；命中时 touch lastModified（近似 LRU 的关键） */
    public Optional<Path> getCachedFile(String songId) {
        try {
            Path file = cacheDir.resolve(songId + ".mp3");
            if (Files.isRegularFile(file) && Files.size(file) > 0) {
                Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
                return Optional.of(file);
            }
        } catch (IOException ignored) {
        }
        return Optional.empty();
    }

    /** 创建临时文件句柄：{songId}.{playbackId}.mp3.part（新旧播放实例不争抢） */
    public Path beginPartFile(String songId, long playbackId) {
        try {
            Files.createDirectories(cacheDir);
            return Files.createFile(cacheDir.resolve(songId + "." + playbackId + ".mp3.part"));
        } catch (IOException e) {
            return null; // null = 本曲不缓存（降级），播放照常
        }
    }

    /** 播放成功后：.part → {songId}.mp3（原子，失败回退普通 move），随后惰性清理超限 */
    public void finalizePartFile(Path partFile, String songId) {
        if (partFile == null) {
            return;
        }
        try {
            Path target = cacheDir.resolve(songId + ".mp3");
            try {
                Files.move(partFile, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(partFile, target, StandardCopyOption.REPLACE_EXISTING);
            }
            evictIfNeeded();
        } catch (IOException ignored) {
            deleteQuietly(partFile);
        }
    }

    /** 删除指定 .part（stop 序列 / 播放失败时调用） */
    public void deletePartFile(Path partFile) {
        if (partFile != null) {
            deleteQuietly(partFile);
        }
    }

    /** 按 lastModified 删最旧，直到低于上限 */
    private void evictIfNeeded() {
        try (Stream<Path> files = Files.list(cacheDir)) {
            List<Path> mp3s = files.filter(p -> p.getFileName().toString().endsWith(".mp3"))
                    .sorted(Comparator.comparingLong(this::lastModifiedOf)) // 最旧在前
                    .toList();
            long total = mp3s.stream().mapToLong(this::sizeOf).sum();
            for (Path p : mp3s) {
                if (total <= maxCacheBytes()) {
                    break;
                }
                total -= sizeOf(p);
                deleteQuietly(p);
            }
        } catch (IOException ignored) {
        }
    }

    protected long maxCacheBytes() {
        return MAX_CACHE_BYTES;
    }

    private long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0L;
        }
    }

    private long lastModifiedOf(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }
}
