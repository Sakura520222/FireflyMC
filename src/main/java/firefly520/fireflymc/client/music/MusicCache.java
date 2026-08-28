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
    /** 缓存目录格式标记文件名 */
    private static final String FORMAT_MARKER = ".format-version";
    /**
     * v2：正式缓存转正需要字节级完整性证明。3.0.1 的"EOF 无异常即落盘"可能已把提前
     * 截断的半首歌存成 {songId}.mp3，且无法事后区分好坏——检测到旧格式（标记缺失或
     * < 2）时全量作废正式缓存。缓存是可重建数据，首次启动重新下载远小于 #64 升级后继续复现。
     */
    private static final int FORMAT_VERSION = 2;

    private final Path cacheDir;
    /** stale .part 清理只执行一次（首个播放 worker 的 ensureInitialized 触发） */
    private boolean initialized;

    /**
     * 构造不做任何 I/O：实例化发生在 MC 主线程（MusicPlaybackManager 静态字段初始化），
     * Files.list 扫描+删除在 Windows 上可产生可观停顿（实测首次点歌卡顿源）。
     * 残留清理延后到 {@link #ensureInitialized()}，由首个播放 worker 线程承担。
     */
    public MusicCache(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /**
     * 清理崩溃/强杀残留的 .part（幂等，一次性）。必须在播放线程、本 worker 的
     * beginPartFile 之前调用：播放线程串行推进（新 worker 先等旧 worker 退出），
     * 绝不会误删自己即将创建/正在写的新 .part。
     * 残留文件无人认领，不清理会随反复崩溃无限累积。
     */
    public synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        // 一次性格式迁移（升级 3.0.1 → 3.0.2）：旧标记缺失/过旧 → 正式缓存全量作废。
        // 必须在首个 worker、beginPartFile 之前执行（播放线程串行，无误删风险）。
        // fail closed：删除未全部成功（Windows 句柄/杀软占用）时绝不写标记，且
        // initialized 保持 false——同进程后续播放仍会重试迁移，坏缓存不会因"盖章成功"永久留存
        if (readFormatVersion() < FORMAT_VERSION) {
            long wiped = wipeLegacyCache();
            if (hasLegacyCacheLeft()) {
                firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                        "[Music] 缓存格式升级 v{} 未完成：已删 {} 个但仍有旧缓存文件删除失败（句柄/杀软占用），下次播放重试迁移",
                        FORMAT_VERSION, wiped);
                return; // 本曲按无缓存处理走网络下载；不写标记、不置 initialized
            }
            firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                    "[Music] 缓存格式升级 v{}：作废 {} 个旧缓存文件（3.0.1 可能存有截断的半截 MP3）",
                    FORMAT_VERSION, wiped);
            writeFormatMarker();
        }
        cleanStaleParts();
        initialized = true;
    }

    /** 读取目录格式版本；标记缺失/损坏/目录不存在 → 0（视为最旧格式） */
    private int readFormatVersion() {
        try {
            Path marker = safeResolve(FORMAT_MARKER);
            if (marker == null || !Files.isRegularFile(marker)) {
                return 0;
            }
            return Integer.parseInt(Files.readString(marker).strip());
        } catch (Exception e) {
            return 0;
        }
    }

    private void cleanStaleParts() {
        try (Stream<Path> files = Files.list(cacheDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".mp3.part"))
                    .forEach(this::deleteQuietly);
        } catch (IOException ignored) {
            // 目录不存在：无需清理
        }
    }

    private void writeFormatMarker() {
        try {
            Files.createDirectories(cacheDir);
            Files.writeString(cacheDir.resolve(FORMAT_MARKER), String.valueOf(FORMAT_VERSION));
        } catch (IOException ignored) {
        }
    }

    /** 旧格式缓存文件（正式 .mp3 与残留 .part） */
    private boolean isLegacyCache(Path p) {
        String name = p.getFileName().toString();
        return name.endsWith(".mp3") || name.endsWith(".mp3.part");
    }

    /** 全量作废旧格式缓存；返回成功删除数（删除失败由调用方二次扫描兜底） */
    private long wipeLegacyCache() {
        long[] wiped = {0L};
        try (Stream<Path> files = Files.list(cacheDir)) {
            files.filter(this::isLegacyCache)
                    .forEach(p -> {
                        deleteQuietly(p);
                        wiped[0]++;
                    });
        } catch (IOException ignored) {
        }
        return wiped[0];
    }

    /** 删除后二次扫描：仍有旧格式文件残留 = 迁移未完成（fail closed，不写版本标记） */
    private boolean hasLegacyCacheLeft() {
        try (Stream<Path> files = Files.list(cacheDir)) {
            return files.anyMatch(this::isLegacyCache);
        } catch (IOException e) {
            return true; // 无法确认干净 = 视为未完成
        }
    }

    /** 纵深防护：songId 是服务端不可信输入，解析+归一后必须仍在缓存目录内（防目录逃逸） */
    private Path safeResolve(String fileName) {
        Path target = cacheDir.resolve(fileName).normalize();
        if (!target.toAbsolutePath().startsWith(cacheDir.toAbsolutePath().normalize())) {
            return null;
        }
        return target;
    }

    /** 实际使用的目录（懒创建）：运行目录/music-cache */
    public static MusicCache createDefault() {
        return new MusicCache(Path.of("music-cache"));
    }

    /** 查询已完成的缓存文件；命中时 touch lastModified（近似 LRU 的关键） */
    public Optional<Path> getCachedFile(String songId) {
        try {
            Path file = safeResolve(songId + ".mp3");
            if (file == null) {
                return Optional.empty();
            }
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
            Path part = safeResolve(songId + "." + playbackId + ".mp3.part");
            if (part == null) {
                return null; // 路径逃逸：拒绝缓存（降级），播放照常
            }
            return Files.createFile(part);
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
            Path target = safeResolve(songId + ".mp3");
            if (target == null) {
                deleteQuietly(partFile); // 路径逃逸：拒绝转正，.part 一并删除
                return;
            }
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

    /** 缓存损坏时删除正式缓存文件（下次播放重新下载）；不触碰任何 .part */
    public void invalidate(String songId) {
        Path file = safeResolve(songId + ".mp3");
        if (file != null) {
            deleteQuietly(file);
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
