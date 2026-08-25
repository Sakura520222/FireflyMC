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
