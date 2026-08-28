package firefly520.fireflymc.client.music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class MusicCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void partFileIsNotHit() throws IOException {
        MusicCache cache = new MusicCache(tempDir);
        Files.write(tempDir.resolve("123456.1.mp3.part"), new byte[10]);
        assertTrue(cache.getCachedFile("123456").isEmpty(), ".part 不得算命中");
    }

    @Test
    void constructorDoesNoIoAndEnsureInitializedCleans() throws IOException {
        // 崩溃/强杀残留的 .part 无人认领：构造不做任何 I/O（主线程类初始化卡顿修复），
        // 清理由 ensureInitialized（首个播放 worker 调用）承担；完成的 .mp3 保留
        Files.write(tempDir.resolve("123456.1.mp3.part"), new byte[10]);
        Files.write(tempDir.resolve("654321.mp3"), new byte[10]);
        MusicCache cache = new MusicCache(tempDir);
        assertTrue(Files.exists(tempDir.resolve("123456.1.mp3.part")), "构造函数不得做 I/O（.part 应仍在）");
        cache.ensureInitialized();
        assertFalse(Files.exists(tempDir.resolve("123456.1.mp3.part")), "残留 .part 必须被清理");
        assertTrue(Files.exists(tempDir.resolve("654321.mp3")), "已完成缓存不得误删");
    }

    @Test
    void ensureInitializedIsIdempotentAndNeverDeletesClaimedParts() throws IOException {
        // 幂等标志是正确性约束：重复清理若不挡住，会误删 worker 已 begin、正在写的新 .part
        MusicCache cache = new MusicCache(tempDir);
        cache.ensureInitialized();
        Path fresh = cache.beginPartFile("999", 1L);
        Files.write(fresh, new byte[10]);
        cache.ensureInitialized();
        assertTrue(Files.exists(fresh), "重复初始化不得误删已认领的 .part");
    }

    @Test
    void finalizeAtomicRename() throws IOException {
        MusicCache cache = new MusicCache(tempDir);
        Path part = cache.beginPartFile("123456", 42L);
        Files.write(part, new byte[100]);
        cache.finalizePartFile(part, "123456");
        assertTrue(Files.exists(tempDir.resolve("123456.mp3")));
        assertFalse(Files.exists(part));
    }

    @Test
    void partNameContainsPlaybackId() throws IOException {
        MusicCache cache = new MusicCache(tempDir);
        Path partA = cache.beginPartFile("111", 1L);
        Path partB = cache.beginPartFile("111", 2L);
        assertNotEquals(partA, partB, "同一 songId 不同 playbackId 的 .part 必须隔离");
        assertTrue(partA.getFileName().toString().contains(".1."));
    }

    @Test
    void invalidateDeletesOnlyCompletedCache() throws IOException {
        // 坏缓存修复（审查 #5）：invalidate 只删正式缓存，下次播放重新下载；
        // 不存在的 songId 静默无异常
        MusicCache cache = new MusicCache(tempDir);
        Path part = cache.beginPartFile("333", 1L);
        Files.write(part, new byte[10]);
        cache.finalizePartFile(part, "333");
        assertTrue(cache.getCachedFile("333").isPresent());
        cache.invalidate("333");
        assertTrue(cache.getCachedFile("333").isEmpty(), "invalidate 必须删除正式缓存");
        assertDoesNotThrow(() -> cache.invalidate("不存在"));
    }

    @Test
    void cacheHitTouchesLastModified() throws IOException, InterruptedException {
        MusicCache cache = new MusicCache(tempDir);
        Path part = cache.beginPartFile("222", 1L);
        Files.write(part, new byte[10]);
        cache.finalizePartFile(part, "222");
        Path mp3 = tempDir.resolve("222.mp3");
        Files.setLastModifiedTime(mp3,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 86_400_000L)); // 昨日
        long before = Files.getLastModifiedTime(mp3).toMillis();
        Thread.sleep(20);
        Optional<Path> hit = cache.getCachedFile("222");
        assertTrue(hit.isPresent());
        assertTrue(Files.getLastModifiedTime(mp3).toMillis() > before, "命中后必须 touch");
    }

    @Test
    void lruEvictionOverLimit() throws IOException, InterruptedException {
        MusicCache cache = new MusicCache(tempDir) {
            @Override // 测试用小上限
            protected long maxCacheBytes() { return 300L; }
        };
        for (int i = 0; i < 5; i++) {
            Path part = cache.beginPartFile("song" + i, (long) i);
            Files.write(part, new byte[100]);
            cache.finalizePartFile(part, "song" + i);
            Thread.sleep(15); // 保证 lastModified 有区分度
        }
        // 300 上限 → 保留最新 3 个（song2/3/4），最旧的 song0/song1 被删
        assertFalse(Files.exists(tempDir.resolve("song0.mp3")));
        assertFalse(Files.exists(tempDir.resolve("song1.mp3")));
        assertTrue(Files.exists(tempDir.resolve("song4.mp3")));
    }
}
