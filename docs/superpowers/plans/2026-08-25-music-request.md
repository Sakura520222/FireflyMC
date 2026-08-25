# 点歌系统实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **本项目禁止自主 git 提交**（CLAUDE.md）：所有任务的"提交点"仅指"代码已完成、可提交"，执行者**必须等待用户明确指令**才能执行 `git commit/push`。计划中不包含任何自主提交步骤。

**Goal:** 实现 `/点歌 <歌名>` 功能——网易云（netease via txqq）搜索、服务端权威队列、全服同步播放（JLayer 流式解码 + JavaSound）、HUD 状态卡片（曲名/进度条/歌词/排队）。

**Architecture:** 三场景（单人/LAN/专服）统一为逻辑服务端权威：`MusicQueueManager` 单线程状态机管理队列/一首锁/epoch/权威计时，广播 `MusicStartPayload` 等自定义包；客户端 `MusicPlaybackManager` 边下边播（outer url 延迟解析 → JLayer → PCM → SourceDataLine），音量经 Atomic 桥接 MASTER×MUSIC，完全绕开 MC OpenAL SoundEngine。

**Tech Stack:** NeoForge 21.1.241 / MC 1.21.1 / Java 21 / JLayer 1.0.1 (jarJar) / JUnit 5.10.2

**设计文档:** `docs/superpowers/specs/2026-08-25-music-request-design.md`（实现前先通读）

**验证命令：** `.\gradlew.bat test`（单测）／`.\gradlew.bat compileJava`（编译）／`.\gradlew.bat runClient`（手动验证）。Windows PowerShell 环境。

---

## 文件结构总览

```
新建：
src/main/java/firefly520/fireflymc/music/
├── MusicCommandHandler.java        # 命令树（Task 10）
├── MusicQueueManager.java          # 队列状态机（Task 7）
├── MusicApiClient.java             # txqq 搜索 + 时长探测（Task 8）
├── Mp3DurationProbe.java           # MP3 头解析（Task 4）
├── QueuedSong.java                 # 队列项（Task 7）
src/main/java/firefly520/fireflymc/network/
├── MusicStartPayload.java          # （Task 6）
├── MusicQueueSyncPayload.java      # （Task 6）
├── MusicStopPayload.java           # （Task 6）
├── MusicPlaybackFailedPayload.java # （Task 6）
src/main/java/firefly520/fireflymc/client/music/
├── MusicPlaybackManager.java       # （Task 11）
├── MusicPlayer.java                # （Task 11）
├── JavaSoundOutput.java            # （Task 11）
├── PlaybackClock.java              # （Task 3）
├── MusicCache.java                 # （Task 5）
├── LrcParser.java                  # （Task 2）
├── MusicPlaybackState.java         # （Task 11）
├── MusicHudRenderer.java           # （Task 13）
src/main/java/firefly520/fireflymc/client/HudRenderUtil.java  # （Task 13）
src/test/java/firefly520/fireflymc/...
修改：
build.gradle                        # JLayer（Task 1）
ModNetwork.java                     # 注册 + 版本 1.1.0（Task 9）
ModPayloadHandler.java              # 握手 capability（Task 9）
ClientHandler.java                  # 纵向 stack 布局（Task 13）
HUDRenderer.java                    # renderAt 重构（Task 13）
FireflyMCMod.java                   # 客户端事件挂钩（Task 12）
ClientPayloadHandler.java           # 音乐包分发（Task 12）
zh_cn.json / en_us.json             # lang key（Task 14）
```

依赖顺序：Task 1（构建）→ 2/3/4/5（纯逻辑，可任意序）→ 6（协议）→ 7（状态机）→ 8（API）→ 9（网络注册）→ 10（命令）→ 11（播放器）→ 12（分发+挂钩）→ 13（HUD）→ 14（lang+全量构建+手动矩阵）。

---

### Task 1: JLayer 依赖配置

**Files:**
- Modify: `build.gradle:123-152`（dependencies 块）

- [x] **Step 1: 添加 jarJar 依赖**

在 `build.gradle` 的 `dependencies {` 块内（`testImplementation` 行之前）加入：

```gradle
    // 点歌系统：JLayer MP3 解码器（LGPL，以嵌套 jar 分发，不 shade 不 relocate）
    jarJar(implementation('javazoom:jlayer:1.0.1')) {
        version {
            strictly '[1.0.1]'
            prefer '1.0.1'
        }
    }
    // ModDevGradle 开发环境需要额外将 jarJar 库放进运行时 classpath，否则 runClient 时 ClassNotFoundException
    additionalRuntimeClasspath 'javazoom:jlayer:1.0.1'
```

- [x] **Step 2: 编译验证**

Run: `.\gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL（无音乐代码引用 JLayer，仅验证依赖解析成功）

- [x] **Step 3: 验证 jarJar 打包**

Run: `.\gradlew.bat build`
Expected: BUILD SUCCESSFUL。检查 `build\libs\` 下主 jar 内含 `META-INF\jarjar\` 且内嵌 `jlayer-1.0.1.jar`（可用解压工具抽查；若不放心可跳过，Task 11 引用后 runClient 验证）

- [x] **Step 4: 创建 NOTICE（LGPL 分发义务）**

新建项目根目录 `NOTICE.md`：

```markdown
# FireflyMC 第三方组件声明 / Third-party Notices

## JLayer 1.0.1 (javazoom:jlayer)

- 用途：点歌系统 MP3 音频解码（以 jarJar 嵌套 jar 原样分发，未修改、未 shade、未 relocate）
- 许可证：GNU Lesser General Public License (LGPL)
- 来源：https://repo1.maven.org/maven2/javazoom/jlayer/1.0.1/
- 版权：Copyright (C) 1999-2008 JavaZOOM

完整 LGPL 许可证文本发布时随发布物提供（或指向
https://www.gnu.org/licenses/lgpl-3.0.html 及仓库内随附副本）。

> 发布核对清单：发布前确认发布物包含 JLayer 嵌套 jar 原件、本声明、LGPL 文本。
```

---

### Task 2: LrcParser（LRC 歌词解析）

**Files:**
- Create: `src/main/java/firefly520/fireflymc/client/music/LrcParser.java`
- Test: `src/test/java/firefly520/fireflymc/client/music/LrcParserTest.java`

- [x] **Step 1: 写失败测试**

```java
package firefly520.fireflymc.client.music;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.*;

class LrcParserTest {

    @Test
    void standardTimestamp() {
        TreeMap<Long, String> map = LrcParser.parse("[00:12.50]吹着前奏望着天空\n[01:30.00]但偏偏雨渐渐大到我看你不见");
        assertEquals(2, map.size());
        assertEquals("吹着前奏望着天空", map.get(12500L));
        assertEquals("但偏偏雨渐渐大到我看你不见", map.get(90000L));
    }

    @Test
    void multipleTimestampsOnOneLine() {
        TreeMap<Long, String> map = LrcParser.parse("[00:10.00][00:20.00]重复歌词");
        assertEquals(2, map.size());
        assertEquals("重复歌词", map.get(10000L));
        assertEquals("重复歌词", map.get(20000L));
    }

    @Test
    void invalidLinesSkipped() {
        TreeMap<Long, String> map = LrcParser.parse("[ti:晴天]\n[ar:周杰伦]\n不是时间标签的行\n[99:99.99]分钟越界\n[00:05.00]有效行");
        assertEquals(1, map.size());
        assertEquals("有效行", map.firstEntry().getValue());
    }

    @Test
    void emptyLyrics() {
        assertTrue(LrcParser.parse("").isEmpty());
        assertTrue(LrcParser.parse(null).isEmpty());
        assertTrue(LrcParser.parse("[00:00.00]").isEmpty()); // 只有标签没有文本
    }

    @Test
    void currentLineLookup() {
        TreeMap<Long, String> map = LrcParser.parse("[00:10.00]第一句\n[00:20.00]第二句");
        assertEquals(Optional.empty(), LrcParser.currentLine(map, 5000L));
        assertEquals(Optional.of("第一句"), LrcParser.currentLine(map, 10000L));
        assertEquals(Optional.of("第一句"), LrcParser.currentLine(map, 19999L));
        assertEquals(Optional.of("第二句"), LrcParser.currentLine(map, 25000L));
    }
}
```

- [x] **Step 2: 运行测试验证失败**

Run: `.\gradlew.bat test --tests "firefly520.fireflymc.client.music.LrcParserTest"`
Expected: 编译失败（`LrcParser` 不存在）

- [x] **Step 3: 实现 LrcParser**

```java
package firefly520.fireflymc.client.music;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC 歌词解析器（纯客户端组件）
 * 支持 [mm:ss.xx] 时间标签，同一行多个时间戳；无效行跳过
 */
public final class LrcParser {

    /** 匹配行首一个或多个连续时间标签 */
    private static final Pattern TIME_TAGS = Pattern.compile("(\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?])+");

    private LrcParser() {}

    /**
     * 解析 LRC 文本为 时间毫秒 -> 歌词行 的有序映射
     */
    public static TreeMap<Long, String> parse(String lrc) {
        TreeMap<Long, String> result = new TreeMap<>();
        if (lrc == null || lrc.isBlank()) {
            return result;
        }
        for (String line : lrc.split("\\r?\\n")) {
            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = TIME_TAGS.matcher(line);
            if (!m.find() || m.start() != 0) {
                continue; // 行首不是时间标签则跳过（[ti:] 等元数据、纯文本）
            }
            String tags = m.group();
            String text = line.substring(tags.length()).strip();
            if (text.isEmpty()) {
                continue;
            }
            Matcher tagMatcher = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]").matcher(tags);
            while (tagMatcher.find()) {
                long minutes = Long.parseLong(tagMatcher.group(1));
                long seconds = Long.parseLong(tagMatcher.group(2));
                String fracStr = tagMatcher.group(3);
                long millis = 0;
                if (fracStr != null) {
                    // [.:ff] 按百分秒（2位）解释；3位按毫秒
                    millis = switch (fracStr.length()) {
                        case 1 -> Long.parseLong(fracStr) * 100L;
                        case 2 -> Long.parseLong(fracStr) * 10L;
                        default -> Long.parseLong(fracStr.length() > 3 ? fracStr.substring(0, 3) : fracStr);
                    };
                }
                if (minutes > 99 || seconds > 59) {
                    continue; // 分钟/秒越界视为无效标签
                }
                long timeMs = (minutes * 60 + seconds) * 1000L + millis;
                result.put(timeMs, text);
            }
        }
        return result;
    }

    /**
     * 查询 positionMs 时刻的当前歌词行（floorEntry）
     */
    public static Optional<String> currentLine(TreeMap<Long, String> map, long positionMs) {
        Map.Entry<Long, String> entry = map.floorEntry(positionMs);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.getValue());
    }
}
```

- [x] **Step 4: 运行测试验证通过**

Run: `.\gradlew.bat test --tests "firefly520.fireflymc.client.music.LrcParserTest"`
Expected: 5 个测试全部 PASS

---

### Task 3: PlaybackClock（播放时钟，含 basePositionMs 偏移）

**Files:**
- Create: `src/main/java/firefly520/fireflymc/client/music/PlaybackClock.java`
- Test: `src/test/java/firefly520/fireflymc/client/music/PlaybackClockTest.java`

- [x] **Step 1: 写失败测试**

```java
package firefly520.fireflymc.client.music;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackClockTest {

    @Test
    void offsetAppliesToPosition() {
        // 中途加入：base=151000ms，line 已播 5000 帧 @ 44100Hz ≈ 113.4ms
        long pos = PlaybackClock.positionWithOffset(151000L, 5000L, 44100);
        assertEquals(151000L + 113L, pos); // 5000*1000/44100 = 113.37 → 113
    }

    @Test
    void zeroOffsetIsNormalStart() {
        assertEquals(0L, PlaybackClock.positionWithOffset(0L, 0L, 44100));
    }

    @Test
    void silentClockMonotonic() throws InterruptedException {
        PlaybackClock.Silent clock = new PlaybackClock.Silent(30000L);
        long a = clock.positionMs();
        Thread.sleep(50);
        long b = clock.positionMs();
        assertTrue(b > a, "静音时钟必须单调推进");
        assertTrue(a >= 30000L, "静音时钟也必须带 base 偏移");
    }
}
```

- [x] **Step 2: 运行测试验证失败**

Run: `.\gradlew.bat test --tests "firefly520.fireflymc.client.music.PlaybackClockTest"`
Expected: 编译失败（`PlaybackClock` 不存在）

- [x] **Step 3: 实现 PlaybackClock**

```java
package firefly520.fireflymc.client.music;

import javax.sound.sampled.SourceDataLine;

/**
 * 播放进度时钟。HUD position truth = PlaybackClock（含 base 偏移）；
 * decodedFrames / writtenFrames 仅作诊断，不作真相源。
 */
public interface PlaybackClock {

    /** 当前播放位置（毫秒） */
    long positionMs();

    /** JavaSound 实现：base + line 已播放帧数换算 */
    record JavaSound(SourceDataLine line, int sampleRate, long basePositionMs) implements PlaybackClock {
        @Override
        public long positionMs() {
            return positionWithOffset(basePositionMs, line.getLongFramePosition(), sampleRate);
        }
    }

    /** 静音降级实现：无 Mixer 时用单调时钟维持进度（不得高速跑完解码循环） */
    class Silent implements PlaybackClock {
        private final long basePositionMs;
        private final long startNano = System.nanoTime();

        public Silent(long basePositionMs) {
            this.basePositionMs = basePositionMs;
        }

        @Override
        public long positionMs() {
            return basePositionMs + (System.nanoTime() - startNano) / 1_000_000L;
        }
    }

    /** 统一的偏移换算：base + frames * 1000 / sampleRate */
    static long positionWithOffset(long basePositionMs, long playedFrames, int sampleRate) {
        if (sampleRate <= 0) {
            return basePositionMs;
        }
        return basePositionMs + playedFrames * 1000L / sampleRate;
    }
}
```

- [x] **Step 4: 运行测试验证通过**

Run: `.\gradlew.bat test --tests "firefly520.fireflymc.client.music.PlaybackClockTest"`
Expected: 3 个测试 PASS

---

### Task 4: Mp3DurationProbe（MP3 头时长解析）

**Files:**
- Create: `src/main/java/firefly520/fireflymc/music/Mp3DurationProbe.java`
- Test: `src/test/java/firefly520/fireflymc/music/Mp3DurationProbeTest.java`

- [x] **Step 1: 写失败测试（手工构造 MP3 头字节）**

```java
package firefly520.fireflymc.music;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class Mp3DurationProbeTest {

    /** MPEG1 Layer3 128kbps 44100Hz stereo 帧头 */
    private static final byte[] FRAME_HEADER = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00};

    /** 构造：帧头 + N 字节填充 + 附加数据 */
    private byte[] mp3Head(int sideInfoAndPadding, byte[] extra) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(FRAME_HEADER);
        out.writeBytes(new byte[sideInfoAndPadding]);
        if (extra != null) out.writeBytes(extra);
        return out.toByteArray();
    }

    @Test
    void xingHeader() {
        // stereo: sideInfo 32 字节；Xing + flags(0x00000001=FRAME) + frameCount=2820
        byte[] xing = new ByteArrayOutputStream() {{
            writeBytes("Xing".getBytes(StandardCharsets.US_ASCII));
            write(new byte[]{0, 0, 0, 1});                       // flags: frames
            write(new byte[]{0, 0, (byte) 0x0B, 0x04});          // 2820 帧
        }}.toByteArray();
        byte[] head = mp3Head(32, xing);
        // duration = 2820 * 1152 / 44100 = 73.63s ≈ 73698ms（误差 ±5ms 内）
        long duration = Mp3DurationProbe.probeDurationMs(head, 4_738_291L);
        assertEquals(73698L, duration, 5L);
    }

    @Test
    void cbrEstimate() {
        // 无 Xing/VBRI → CBR：totalBytes*8/bitrate = 4738291*8/128000 = 296.14s
        byte[] head = mp3Head(64, null);
        long duration = Mp3DurationProbe.probeDurationMs(head, 4_738_291L);
        assertEquals(296143L, duration, 5L);
    }

    @Test
    void garbageReturnsFallback() {
        // 无有效帧头 → fallback
        assertEquals(Mp3DurationProbe.FALLBACK_DURATION_MS,
                Mp3DurationProbe.probeDurationMs("not an mp3".getBytes(), 1000L));
    }

    @Test
    void emptyHeadReturnsFallback() {
        assertEquals(Mp3DurationProbe.FALLBACK_DURATION_MS,
                Mp3DurationProbe.probeDurationMs(new byte[0], 1000L));
    }
}
```

- [x] **Step 2: 运行测试验证失败**

Run: `.\gradlew.bat test --tests "firefly520.fireflymc.music.Mp3DurationProbeTest"`
Expected: 编译失败

- [x] **Step 3: 实现 Mp3DurationProbe**

```java
package firefly520.fireflymc.music;

import java.nio.charset.StandardCharsets;

/**
 * MP3 头部时长解析（服务端时长探测 + 客户端自检共用）
 * 优先级：Xing/Info → VBRI → CBR 估算（首帧 bitrate + 文件总大小）→ fallback
 */
public final class Mp3DurationProbe {

    /** 解析失败时的保守默认时长 */
    public static final long FALLBACK_DURATION_MS = 240_000L;

    /** MPEG1 Layer3 每帧采样数 */
    private static final int SAMPLES_PER_FRAME_MPEG1_L3 = 1152;

    /** MPEG1 Layer3 各 bitrate index 的 kbps（index 1-14） */
    private static final int[] BITRATES_KBPS = {
            0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0
    };

    /** 采样率 index 0-2 对应 MPEG1 的 Hz */
    private static final int[] SAMPLE_RATES = {44100, 48000, 32000};

    private Mp3DurationProbe() {}

    /**
     * 从音频头部字节解析时长（毫秒）
     *
     * @param head          头部字节（至少含第一帧头 + 可能的 Xing/VBRI，建议 64KB）
     * @param totalFileBytes 文件总大小（来自 Content-Range total 或 Content-Length，由调用方保证语义正确）
     */
    public static long probeDurationMs(byte[] head, long totalFileBytes) {
        if (head == null || head.length < 64) {
            return FALLBACK_DURATION_MS;
        }
        // 定位第一帧帧头（在开头少量字节内扫，容忍 ID3v2 之外的杂散字节）
        int frameStart = findFrameHeader(head);
        if (frameStart < 0) {
            return FALLBACK_DURATION_MS;
        }
        int header = ((head[frameStart] & 0xFF) << 24) | ((head[frameStart + 1] & 0xFF) << 16)
                | ((head[frameStart + 2] & 0xFF) << 8) | (head[frameStart + 3] & 0xFF);

        int versionBits = (header >> 19) & 0x3;   // 3=MPEG1
        int layerBits = (header >> 17) & 0x3;     // 1=Layer3
        if (versionBits != 3 || layerBits != 1) {
            return FALLBACK_DURATION_MS; // 仅支持 MPEG1 Layer3（netease 源实测即此格式）
        }
        int bitrateIndex = (header >> 12) & 0xF;
        int sampleRateIndex = (header >> 10) & 0x3;
        int channelMode = (header >> 6) & 0x3;    // 3=mono
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) {
            return FALLBACK_DURATION_MS;
        }
        int kbps = BITRATES_KBPS[bitrateIndex];
        int sampleRate = SAMPLE_RATES[sampleRateIndex];
        int sideInfoSize = (channelMode == 3) ? 17 : 32;

        // 1. Xing / Info 头（位于帧头 + sideInfo 之后）
        int xingOffset = frameStart + 4 + sideInfoSize;
        if (xingOffset + 12 <= head.length) {
            String tag = new String(head, xingOffset, 4, StandardCharsets.US_ASCII);
            if (tag.equals("Xing") || tag.equals("Info")) {
                int flags = readIntBE(head, xingOffset + 4);
                if ((flags & 0x1) != 0) { // FRAMES flag
                    long frames = readIntBE(head, xingOffset + 8) & 0xFFFFFFFFL;
                    if (frames > 0) {
                        return frames * SAMPLES_PER_FRAME_MPEG1_L3 * 1000L / sampleRate;
                    }
                }
            }
        }

        // 2. VBRI 头（固定位于帧头 + 36）
        int vbriOffset = frameStart + 36;
        if (vbriOffset + 14 <= head.length
                && new String(head, vbriOffset, 4, StandardCharsets.US_ASCII).equals("VBRI")) {
            long frames = readIntBE(head, vbriOffset + 14) & 0xFFFFFFFFL;
            if (frames > 0) {
                return frames * SAMPLES_PER_FRAME_MPEG1_L3 * 1000L / sampleRate;
            }
        }

        // 3. CBR 估算：总大小 * 8 / bitrate
        if (totalFileBytes > 0 && kbps > 0) {
            return totalFileBytes * 8L / kbps;
        }
        return FALLBACK_DURATION_MS;
    }

    /** 在前 8KB 内寻找 MPEG 帧同步字 0xFFE0 */
    private static int findFrameHeader(byte[] data) {
        int limit = Math.min(data.length - 4, 8192);
        for (int i = 0; i < limit; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xE0) == 0xE0) {
                return i;
            }
        }
        return -1;
    }

    private static int readIntBE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }
}
```

- [x] **Step 4: 运行测试验证通过**

Run: `.\gradlew.bat test --tests "firefly520.fireflymc.music.Mp3DurationProbeTest"`
Expected: 4 个测试 PASS

---

### Task 5: MusicCache（缓存：.part / atomic rename / 近似 LRU）

**Files:**
- Create: `src/main/java/firefly520/fireflymc/client/music/MusicCache.java`
- Test: `src/test/java/firefly520/fireflymc/client/music/MusicCacheTest.java`

- [x] **Step 1: 写失败测试**

```java
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
    void partNameContainsPlaybackId() {
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
    void lruEvictionOverLimit() throws IOException {
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
```

- [x] **Step 2: 运行测试验证失败**

Run: `.\gradlew.bat test --tests "firefly520.fireflymc.client.music.MusicCacheTest"`
Expected: 编译失败

- [x] **Step 3: 实现 MusicCache**

```java
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
            List<Path> mp3s = files.filter(p -> p.getFileName().toString().endsWith(".mp3")).toList();
            long total = mp3s.stream().mapToLong(this::sizeOf).sum();
            if (total <= maxCacheBytes()) {
                return;
            }
            mp3s.stream()
                    .sorted(Comparator.comparingLong(this::lastModifiedOf)) // 最旧在前
                    .takeWhile(p -> (total -= sizeOf(p)) > maxCacheBytes())
                    .forEach(this::deleteQuietly);
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
```

- [x] **Step 4: 运行测试验证通过**

Run: `.\gradlew.bat test --tests "firefly520.fireflymc.client.music.MusicCacheTest"`
Expected: 5 个测试 PASS

---

### Task 6: 四个网络 Payload + 编解码测试

**Files:**
- Create: `src/main/java/firefly520/fireflymc/network/MusicStartPayload.java`
- Create: `src/main/java/firefly520/fireflymc/network/MusicQueueSyncPayload.java`
- Create: `src/main/java/firefly520/fireflymc/network/MusicStopPayload.java`
- Create: `src/main/java/firefly520/fireflymc/network/MusicPlaybackFailedPayload.java`
- Test: `src/test/java/firefly520/fireflymc/network/MusicPayloadCodecTest.java`

- [x] **Step 1: 写失败测试（codec round-trip）**

```java
package firefly520.fireflymc.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MusicPayloadCodecTest {

    private <T> T roundTrip(net.minecraft.network.codec.StreamCodec<ByteBuf, T> codec, T value) {
        ByteBuf buf = Unpooled.buffer();
        codec.encode(buf, value);
        T decoded = codec.decode(buf);
        buf.release();
        return decoded;
    }

    @Test
    void startPayloadRoundTrip() {
        MusicStartPayload p = new MusicStartPayload(7L, "1330348068", "起风了",
                "买辣椒也用券", "[00:01.00]test", "Firefly", 243000L, 151000L);
        assertEquals(p, roundTrip(MusicStartPayload.STREAM_CODEC, p));
    }

    @Test
    void queueSyncRoundTrip() {
        MusicQueueSyncPayload p = new MusicQueueSyncPayload(
                new MusicQueueSyncPayload.SongSummary("起风了", "买辣椒也用券", "Firefly"),
                List.of(new MusicQueueSyncPayload.SongSummary("稻香", "周杰伦", "Alice")));
        assertEquals(p, roundTrip(MusicQueueSyncPayload.STREAM_CODEC, p));
    }

    @Test
    void queueSyncEmptyQueueAllowed() {
        MusicQueueSyncPayload p = new MusicQueueSyncPayload(null, List.of());
        assertEquals(p, roundTrip(MusicQueueSyncPayload.STREAM_CODEC, p));
    }

    @Test
    void stopPayloadRoundTrip() {
        MusicStopPayload p = new MusicStopPayload(0L, MusicStopPayload.Reason.QUEUE_CLEARED);
        assertEquals(p, roundTrip(MusicStopPayload.STREAM_CODEC, p));
    }

    @Test
    void failedPayloadRoundTrip() {
        MusicPlaybackFailedPayload p = new MusicPlaybackFailedPayload(7L,
                MusicPlaybackFailedPayload.FailureCode.MP3_DECODE_FAILED);
        assertEquals(p, roundTrip(MusicPlaybackFailedPayload.STREAM_CODEC, p));
    }
}
```

- [x] **Step 2: 运行测试验证失败**

Run: `.\gradlew.bat test --tests "firefly520.fireflymc.network.MusicPayloadCodecTest"`
Expected: 编译失败

- [x] **Step 3: 实现 MusicStartPayload**

```java
package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 开始播放：服务端广播；玩家登录时对当前曲单独发送（中途加入，positionMs 为已播毫秒）
 */
public record MusicStartPayload(
        long playbackId,
        String songId,
        String title,
        String author,
        String lrc,
        String requesterName,
        long durationMs,
        long positionMs
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MusicStartPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "music_start"));

    public static final StreamCodec<ByteBuf, MusicStartPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, MusicStartPayload::playbackId,
                    ByteBufCodecs.STRING_UTF8, MusicStartPayload::songId,
                    ByteBufCodecs.STRING_UTF8, MusicStartPayload::title,
                    ByteBufCodecs.STRING_UTF8, MusicStartPayload::author,
                    ByteBufCodecs.STRING_UTF8, MusicStartPayload::lrc,
                    ByteBufCodecs.STRING_UTF8, MusicStartPayload::requesterName,
                    ByteBufCodecs.VAR_LONG, MusicStartPayload::durationMs,
                    ByteBufCodecs.VAR_LONG, MusicStartPayload::positionMs,
                    MusicStartPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
```

- [x] **Step 4: 实现 MusicQueueSyncPayload**

```java
package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 队列状态同步：当前曲概要 + 排队列表（不含 url/lrc）
 */
public record MusicQueueSyncPayload(SongSummary current, List<SongSummary> queue) implements CustomPacketPayload {

    public record SongSummary(String title, String author, String requesterName) {
        public static final StreamCodec<ByteBuf, SongSummary> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SongSummary::title,
                ByteBufCodecs.STRING_UTF8, SongSummary::author,
                ByteBufCodecs.STRING_UTF8, SongSummary::requesterName,
                SongSummary::new
        );
    }

    public static final CustomPacketPayload.Type<MusicQueueSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "music_queue_sync"));

    public static final StreamCodec<ByteBuf, MusicQueueSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(SongSummary.STREAM_CODEC), p -> java.util.Optional.ofNullable(p.current()),
            ByteBufCodecs.list(SongSummary.STREAM_CODEC), MusicQueueSyncPayload::queue,
            (currentOpt, queue) -> new MusicQueueSyncPayload(currentOpt.orElse(null), queue)
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
```

- [x] **Step 5: 实现 MusicStopPayload**

```java
package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 停止播放。playbackId = 0 表示"无活动播放实例"（如 /stop 时无 currentSong）
 */
public record MusicStopPayload(long playbackId, Reason reason) implements CustomPacketPayload {

    public enum Reason {
        FINISHED, SKIPPED, FAILED, QUEUE_CLEARED
    }

    public static final CustomPacketPayload.Type<MusicStopPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "music_stop"));

    public static final StreamCodec<ByteBuf, MusicStopPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, MusicStopPayload::playbackId,
            ByteBufCodecs.idMapper(Reason::ordinal, Reason::values), MusicStopPayload::reason,
            MusicStopPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
```

- [x] **Step 6: 实现 MusicPlaybackFailedPayload**

```java
package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端播放失败上报（信号而非权威，服务端按 quorum 聚合处理）
 */
public record MusicPlaybackFailedPayload(long playbackId, FailureCode failureCode) implements CustomPacketPayload {

    /** 受限枚举：不接受客户端任意字符串。本地降级（无设备/缓存失败）不在此列，不上报 */
    public enum FailureCode {
        HTTP_FAILED, SOURCE_UNAVAILABLE, STREAM_INTERRUPTED, MP3_DECODE_FAILED
    }

    public static final CustomPacketPayload.Type<MusicPlaybackFailedPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "music_playback_failed"));

    public static final StreamCodec<ByteBuf, MusicPlaybackFailedPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, MusicPlaybackFailedPayload::playbackId,
            ByteBufCodecs.idMapper(FailureCode::ordinal, FailureCode::values), MusicPlaybackFailedPayload::failureCode,
            MusicPlaybackFailedPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
```

- [x] **Step 7: 运行测试验证通过**

Run: `.\gradlew.bat test --tests "firefly520.fireflymc.network.MusicPayloadCodecTest"`
Expected: 5 个测试 PASS

---

### Task 7: QueuedSong + MusicQueueManager（服务端状态机）

**Files:**
- Create: `src/main/java/firefly520/fireflymc/music/QueuedSong.java`
- Create: `src/main/java/firefly520/fireflymc/music/MusicQueueManager.java`
- Test: `src/test/java/firefly520/fireflymc/music/MusicQueueManagerTest.java`

设计要点（全部来自设计文档 §3）：所有方法**只允许在逻辑服务端线程调用**（纯状态机，无锁）；广播与时钟经构造注入（测试可控）；`playbackId` 恒 > 0（0 保留给协议"无实例"）。

- [x] **Step 1: 实现 QueuedSong（数据类）**

```java
package firefly520.fireflymc.music;

/**
 * 队列项：搜索结果 + 元数据（时长由虚拟线程探测后传入）
 */
public record QueuedSong(
        String songId,
        String title,
        String author,
        String lrc,
        String requesterName,
        java.util.UUID requesterId,
        long durationMs
) {}
```

- [x] **Step 2: 写失败测试**

```java
package firefly520.fireflymc.music;

import firefly520.fireflymc.network.MusicPlaybackFailedPayload;
import firefly520.fireflymc.network.MusicQueueSyncPayload;
import firefly520.fireflymc.network.MusicStartPayload;
import firefly520.fireflymc.network.MusicStopPayload;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MusicQueueManagerTest {

    /** 可控时钟 */
    private static class FakeClock {
        long nano = 0;
        java.util.function.LongSupplier supplier = () -> nano;
        void advanceMs(long ms) { nano += ms * 1_000_000L; }
    }

    /** 收集广播的假集成层 */
    private static class Recorder {
        final List<MusicStartPayload> starts = new ArrayList<>();
        final List<MusicStopPayload> stops = new ArrayList<>();
        final List<MusicQueueSyncPayload> syncs = new ArrayList<>();

        MusicQueueManager.StartBroadcaster startB() { return starts::add; }
        MusicQueueManager.StopBroadcaster stopB() { return stops::add; }
        MusicQueueManager.QueueBroadcaster queueB() { return syncs::add; }
        MusicQueueManager.CapabilityLookup caps = uuid -> true; // 默认全员可音乐
    }

    private static MusicQueueManager newManager(FakeClock clock, Recorder rec) {
        return new MusicQueueManager(clock.supplier, rec.startB(), rec.stopB(), rec.queueB(), rec.caps);
    }

    private static QueuedSong song(String title, UUID requester, long durationMs) {
        return new QueuedSong("1000" + Math.abs(title.hashCode()) % 100000, title, "歌手",
                "", "P" + requester.toString().charAt(0), requester, durationMs);
    }

    @Test
    void oneSongLockLifecycle() {
        FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        MusicQueueManager m = newManager(clock, rec);
        UUID a = UUID.randomUUID();

        MusicQueueManager.BeginResult begin = m.tryBeginRequest(a, false);
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, begin);
        m.completeRequest(a, song("晴天", a, 60_000L)); // 立即开始播放
        assertEquals(1, rec.starts.size());
        assertTrue(rec.starts.get(0).playbackId() > 0);

        // 已锁：再点被拒
        assertEquals(MusicQueueManager.BeginResult.LOCKED, m.tryBeginRequest(a, false));
        // 播完（60s + 2s 容差）
        clock.advanceMs(61_999L);
        m.tick();
        assertTrue(rec.stops.isEmpty()); // 未到容差
        clock.advanceMs(1L);
        m.tick();
        assertEquals(1, rec.stops.size());
        assertEquals(MusicStopPayload.Reason.FINISHED, rec.stops.get(0).reason());
        // 解锁：可再点
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
    }

    @Test
    void pendingBlocksDoubleRequest() {
        FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        MusicQueueManager m = newManager(clock, rec);
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
        FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        MusicQueueManager m = newManager(clock, rec);
        UUID op = UUID.randomUUID();
        // 特权者连点 50+1 首：第 51 首被 QUEUE_FULL 拒
        for (int i = 0; i < MusicQueueManager.MAX_QUEUE_SIZE; i++) {
            assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(op, true));
            m.completeRequest(op, song("歌" + i, op, 60_000L));
        }
        assertEquals(MusicQueueManager.BeginResult.QUEUE_FULL, m.tryBeginRequest(op, true));
    }

    @Test
    void concurrentPrivilegedRequestsRecheckLimit() {
        FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        MusicQueueManager m = newManager(clock, rec);
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
        FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        MusicQueueManager m = newManager(clock, rec);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        m.tryBeginRequest(a, false);
        m.completeRequest(a, song("A的歌", a, 60_000L));
        m.tryBeginRequest(b, false);
        m.completeRequest(b, song("B的歌", b, 60_000L));
        // 当前是 A 的歌
        m.skip();
        // A 的歌被跳 → A 解锁；B 仍锁
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        assertEquals(MusicQueueManager.BeginResult.LOCKED, m.tryBeginRequest(b, false));
        // skip 后 B 的歌开始
        assertEquals(2, rec.starts.size());
        assertEquals("B的歌", rec.starts.get(1).title());
    }

    @Test
    void stopAllCancelsEverythingWithEpoch() {
        FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        MusicQueueManager m = newManager(clock, rec);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        m.tryBeginRequest(a, false);
        m.completeRequest(a, song("A1", a, 60_000L));
        m.tryBeginRequest(b, false);
        m.completeRequest(b, song("B1", b, 60_000L));
        // a 又发起一次搜索（pending 中）
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        MusicQueueManager.SearchSession session = m.latestSession();

        m.stopAll();
        assertEquals(1, rec.stops.size());
        assertEquals(MusicStopPayload.Reason.QUEUE_CLEARED, rec.stops.get(0).reason());
        assertEquals(0L, rec.stops.get(0).playbackId() != 0 ? 1 : 0); // stop 后 playbackId 可为 0 或当前值，此处当前歌存在则非 0
        // 全员解锁
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(a, false));
        assertEquals(MusicQueueManager.BeginResult.ACCEPTED, m.tryBeginRequest(b, false));
        // stop 前发起的旧搜索结果返回 → epoch 不符 → 丢弃
        assertFalse(m.completeRequest(a, song("旧结果", a, 60_000L)), "epoch 不符的结果必须丢弃");
        assertTrue(rec.starts.stream().noneMatch(p -> "旧结果".equals(p.title())));
    }

    @Test
    void logoutDoesNotUnlock() {
        FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        MusicQueueManager m = newManager(clock, rec);
        UUID a = UUID.randomUUID();
        m.tryBeginRequest(a, false);
        m.completeRequest(a, song("A", a, 60_000L));
        m.onPlayerLogout(a);
        assertEquals(MusicQueueManager.BeginResult.LOCKED, m.tryBeginRequest(a, false), "掉线不得解锁");
    }

    @Test
    void sameSongIdDifferentPlaybackId() {
        FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        MusicQueueManager m = newManager(clock, rec);
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
        FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        // capability：只有 c1/c2/c3 可音乐
        Recorder recWithCaps = rec;
        MusicQueueManager m = new MusicQueueManager(clock.supplier, rec.startB(), rec.stopB(), rec.queueB(),
                uuid -> !uuid.toString().startsWith("00000000") ? true : false);
        UUID c1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID c2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID c3 = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID plain = UUID.fromString("00000000-0000-0000-0000-000000000000"); // 原版玩家，不入分母
        assertEquals(true, m.isMusicCapable(c1));

        UUID requester = UUID.fromString("44444444-4444-4444-4444-444444444444");
        m.tryBeginRequest(requester, true);
        m.completeRequest(requester, song("Q", requester, 60_000L));
        long playbackId = rec.starts.get(0).playbackId();

        // 单客户端失败（1/3 < 50%）→ 不切歌
        m.onClientFailure(c1, playbackId, MusicPlaybackFailedPayload.FailureCode.HTTP_FAILED);
        clock.advanceMs(60_000L + 2_000L);
        m.tick();
        assertEquals(MusicStopPayload.Reason.FINISHED, rec.stops.get(0).reason(), "quorum 未达按权威计时 FINISHED");

        // 重新来一轮：2/3 ≥ 50% → 提前 FAILED
        m.tryBeginRequest(requester, true);
        m.completeRequest(requester, song("Q2", requester, 60_000L));
        long id2 = rec.starts.get(1).playbackId();
        m.onClientFailure(c1, id2, MusicPlaybackFailedPayload.FailureCode.HTTP_FAILED);
        m.onClientFailure(c1, id2, MusicPlaybackFailedPayload.FailureCode.HTTP_FAILED); // 同玩家重复上报去重
        m.onClientFailure(c2, id2, MusicPlaybackFailedPayload.FailureCode.MP3_DECODE_FAILED);
        assertEquals(MusicStopPayload.Reason.FAILED, rec.stops.get(1).reason(), "2/3 达 quorum 提前 FAILED");
        // 旧 playbackId 迟到上报不误伤
        m.onClientFailure(c3, playbackId, MusicPlaybackFailedPayload.FailureCode.HTTP_FAILED);
    }

    @Test
    void loginSyncReturnsCurrentPayload() {
        FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        MusicQueueManager m = newManager(clock, rec);
        UUID a = UUID.randomUUID();
        assertNullSafe(m.currentStartPayload());
        m.tryBeginRequest(a, false);
        m.completeRequest(a, song("当前曲", a, 60_000L));
        clock.advanceMs(10_000L);
        MusicStartPayload p = m.currentStartPayload();
        assertNotNull(p);
        assertEquals("当前曲", p.title());
        assertEquals(10_000L, p.positionMs(), "登录同步必须带已播进度");
    }

    private static void assertNullSafe(Object o) { assertNull(o); }
    private static void assertNotNull(Object o) { assert o != null; }
    private static void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
    private static void assertTrue(boolean c) { org.junit.jupiter.api.Assertions.assertTrue(c); }
    private static void assertFalse(boolean c) { org.junit.jupiter.api.Assertions.assertFalse(c); }
    private static void assertNull(Object o) { org.junit.jupiter.api.Assertions.assertNull(o); }
    private static void assertNotEquals(Object a, Object b) { org.junit.jupiter.api.Assertions.assertNotEquals(a, b); }
}
```

**注意**：上面测试文件里的静态断言包装方法是为了让代码块自包含；实现时**直接用 JUnit 的静态导入**替代这些包装（`import static org.junit.jupiter.api.Assertions.*;`），删掉文件末尾的私有包装方法。写文件时以静态导入版本为准。

- [x] **Step 3: 运行测试验证失败**

Run: `.\gradlew.bat test --tests "firefly520.fireflymc.music.MusicQueueManagerTest"`
Expected: 编译失败（`MusicQueueManager` 不存在）

- [x] **Step 4: 实现 MusicQueueManager**

```java
package firefly520.fireflymc.music;

import firefly520.fireflymc.network.MusicPlaybackFailedPayload;
import firefly520.fireflymc.network.MusicQueueSyncPayload;
import firefly520.fireflymc.network.MusicStartPayload;
import firefly520.fireflymc.network.MusicStopPayload;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 点歌队列状态机（服务端权威）。
 * 所有方法只允许在逻辑服务端线程调用（单线程状态所有权，无锁）；
 * 广播/时钟/能力查询经构造注入，核心逻辑可在 JUnit 中脱离 MC 测试。
 */
public class MusicQueueManager {

    public static final int MAX_QUEUE_SIZE = 50;
    /** 权威切歌容差 */
    private static final long END_TOLERANCE_MS = 2_000L;
    /** FAILED quorum：失败客户端 ≥ 分母的此比例且 ≥ 2 个 */
    private static final double FAILED_QUORUM_RATIO = 0.5;

    public enum BeginResult { ACCEPTED, LOCKED, PENDING, QUEUE_FULL }

    /** 异步搜索会话（捕获发起时的 epoch） */
    public record SearchSession(long epoch) {}

    public interface StartBroadcaster extends Consumer<MusicStartPayload> {}
    public interface StopBroadcaster extends Consumer<MusicStopPayload> {}
    public interface QueueBroadcaster extends Consumer<MusicQueueSyncPayload> {}
    /** 查询某玩家是否为已确认的音乐能力客户端（musicCapablePlayers ∩ 在线） */
    public interface CapabilityLookup { boolean isCapable(UUID player); }

    private final LongSupplier clock; // System.nanoTime 语义
    private final StartBroadcaster startBroadcaster;
    private final StopBroadcaster stopBroadcaster;
    private final QueueBroadcaster queueBroadcaster;
    private final CapabilityLookup capabilityLookup;

    private final ArrayDeque<QueuedSong> queue = new ArrayDeque<>();
    private final Set<UUID> lockedPlayers = new HashSet<>();
    private final Set<UUID> pendingPlayers = new HashSet<>();
    /** playbackId -> (客户端UUID集合)：失败上报去重 */
    private final Map<Long, Set<UUID>> failedClients = new HashMap<>();
    /** 最近一次 tryBeginRequest 的会话（集成层拿去异步搜索） */
    private SearchSession latestSession;

    private long queueEpoch = 0L;
    private long nextPlaybackId = 1L; // 恒 > 0；0 保留给协议"无实例"
    private QueuedSong currentSong;
    private long currentPlaybackId;
    private long currentStartNano;
    /** pending 请求的发起者（completeRequest 时校验/移除） */
    private final Map<UUID, SearchSession> pendingSessions = new HashMap<>();

    public MusicQueueManager(LongSupplier clock,
                             StartBroadcaster startBroadcaster,
                             StopBroadcaster stopBroadcaster,
                             QueueBroadcaster queueBroadcaster,
                             CapabilityLookup capabilityLookup) {
        this.clock = clock;
        this.startBroadcaster = startBroadcaster;
        this.stopBroadcaster = stopBroadcaster;
        this.queueBroadcaster = queueBroadcaster;
        this.capabilityLookup = capabilityLookup;
    }

    // ---------- 点歌流程 ----------

    /** 命令线程（服务端线程）调用：权限/锁/pending/队列上限检查 */
    public BeginResult tryBeginRequest(UUID player, boolean privileged) {
        if (!privileged) {
            if (lockedPlayers.contains(player)) {
                return BeginResult.LOCKED;
            }
            if (pendingPlayers.contains(player)) {
                return BeginResult.PENDING;
            }
        }
        if (queue.size() >= MAX_QUEUE_SIZE) {
            return BeginResult.QUEUE_FULL;
        }
        pendingPlayers.add(player);
        latestSession = new SearchSession(queueEpoch);
        pendingSessions.put(player, latestSession);
        return BeginResult.ACCEPTED;
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
        if (queue.size() >= MAX_QUEUE_SIZE) {
            return false; // 回检：多个特权请求并发搜索时可能同时通过 begin 检查
        }
        lockedPlayers.add(song.requesterId());
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
            lockedPlayers.remove(requester);
            stopBroadcaster.accept(new MusicStopPayload(id, MusicStopPayload.Reason.QUEUE_CLEARED));
        } else {
            stopBroadcaster.accept(new MusicStopPayload(0L, MusicStopPayload.Reason.QUEUE_CLEARED));
        }
        while (!queue.isEmpty()) {
            lockedPlayers.remove(queue.poll().requesterId());
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
        if (failed.size() < 2) {
            return false;
        }
        // 分母 = 在线 ∩ 可音乐客户端，由集成层提供
        int capableOnline = countCapableOnline();
        if (capableOnline <= 1) {
            return true; // 单人世界：唯一客户端失败立即 FAILED
        }
        return failed.size() * 2 >= capableOnline; // >= 50%
    }

    /** 集成层注入在线玩家集合后使用；测试中由 capabilityLookup 直接判定 */
    private int countCapableOnline() {
        // 集成层在构造 capabilityLookup 时已并入在线判断，这里统计通过者
        // 简化实现：集成层会传一个"可数"的 lookup；测试注入恒真值时退化为 failed >= 2 即触发
        return capabilityCount != null ? capabilityCount.getAsInt() : (failedTriggerAll() ? 1 : 2);
    }

    private boolean failedTriggerAll() { return false; }

    /** 可选：注入"在线可音乐客户端数"（集成层每 tick 更新） */
    private IntSupplierHolder capabilityCount;

    public void setCapableOnlineCount(java.util.function.IntSupplier count) {
        this.capabilityCount = new IntSupplierHolder(count);
    }

    private record IntSupplierHolder(java.util.function.IntSupplier supplier) {
        int getAsInt() { return supplier.getAsInt(); }
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

    /** 玩家登出：仅清理 capability；locked 保留（掉线不解锁） */
    public void onPlayerLogout(UUID player) {
        // capability 集合由集成层维护；此处无状态可清（locked 故意保留）
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
        currentSong = null;
        lockedPlayers.remove(requester);
        failedClients.remove(id);
        if (!queue.isEmpty()) {
            startNext(); // 下一首立即开始（Start 即隐式 stop 旧曲）
        } else {
            stopBroadcaster.accept(new MusicStopPayload(id, reason));
        }
        broadcastQueueSync();
    }

    private MusicStartPayload toStartPayload(QueuedSong s, long positionMs) {
        return new MusicStartPayload(
                s == currentSong ? currentPlaybackId : nextPlaybackId, // startNext 前构造时用 next 值
                s.songId(), s.title(), s.author(), s.lrc(), s.requesterName(),
                s.durationMs(), positionMs);
    }

    private void broadcastQueueSync() {
        var currentSummary = currentSong == null ? null
                : new MusicQueueSyncPayload.SongSummary(currentSong.title(), currentSong.author(), currentSong.requesterName());
        var queueSummaries = queue.stream()
                .map(q -> new MusicQueueSyncPayload.SongSummary(q.title(), q.author(), q.requesterName()))
                .toList();
        queueBroadcaster.accept(new MusicQueueSyncPayload(currentSummary, queueSummaries));
    }
}
```

**实现者注意**：上面 `countCapableOnline` / `IntSupplierHolder` 是初稿的权宜结构，**实现时简化**——把 `CapabilityLookup` 接口改为同时提供判定与计数：

```java
public interface CapabilityLookup {
    boolean isCapable(UUID player);
    default int capableOnlineCount() { return 2; } // 集成层覆写
}
```

`shouldFailEarly` 直接用 `capabilityLookup.capableOnlineCount()`；删除 `IntSupplierHolder`、`failedTriggerAll`、`setCapableOnlineCount` 与 `countCapableOnline`。测试里 `caps` 改为匿名类覆写 `capableOnlineCount()` 返回 3。这是最终结构，上面大段代码以此为准修正后再落盘。

- [x] **Step 5: 运行测试验证通过（含失败计数简化修正）**

Run: `.\gradlew.bat test --tests "firefly520.fireflymc.music.MusicQueueManagerTest"`
Expected: 10 个测试 PASS。若 `failedQuorumTriggersEarlyFinish` 的 capability 桩与接口不符，按"实现者注意"修正接口后重跑

---

### Task 8: MusicApiClient（txqq 搜索 + 时长探测）

**Files:**
- Create: `src/main/java/firefly520/fireflymc/music/MusicApiClient.java`

本任务无单测（真实第三方 HTTP，属手动验证范围）；纯逻辑（songId 校验/字段截断）为简单单行，随类自测。

- [x] **Step 1: 实现 MusicApiClient**

```java
package firefly520.fireflymc.music;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import firefly520.fireflymc.FireflyMCMod;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * txqq 聚合接口客户端（netease 平台）。
 * 全部异步（调用方跑在虚拟线程）；超时与有界读取见各方法。
 * 队列与 payload 中只存 songId，播放时由客户端访问 outer url 延迟解析。
 */
public final class MusicApiClient {

    private static final String SEARCH_URL = "https://music.txqq.pro/";
    private static final String OUTER_URL_TEMPLATE = "https://music.163.com/song/media/outer/url?id=%s.mp3";
    private static final Gson GSON = new Gson();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(10);
    /** 搜索响应体上限 2 MiB */
    private static final int MAX_SEARCH_BYTES = 2 * 1024 * 1024;
    /** 时长探测最多读 64 KiB */
    private static final int PROBE_HEAD_BYTES = 64 * 1024;

    /** 字段上限 */
    private static final int MAX_TITLE = 128, MAX_AUTHOR = 128, MAX_REQUESTER = 64, MAX_LRC = 256 * 1024;

    public static final String OUTBOUND_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0";

    /** 搜索结果（第一首） */
    public record SongInfo(String songId, String title, String author, String lrc) {}

    /** 构造客户端用的 outer url（延迟解析入口，只依赖 songId） */
    public static String outerUrl(String songId) {
        return String.format(OUTER_URL_TEMPLATE, songId);
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL) // outer url 302 自动跟随
            .build();

    private MusicApiClient() {}

    /** 搜索歌曲，取第一首。songId 必须纯数字（SSRF 防护）。 */
    public static CompletableFuture<SongInfo> search(String keyword) {
        return CompletableFuture.supplyAsync(() -> {
            String form = "input=" + java.net.URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                    + "&filter=name&type=netease&page=1";
            HttpRequest request = HttpRequest.newBuilder(URI.create(SEARCH_URL))
                    .timeout(SEARCH_TIMEOUT)
                    .header("User-Agent", OUTBOUND_UA)
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Origin", SEARCH_URL)
                    .header("Referer", SEARCH_URL)
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    throw new IOException("搜索接口 HTTP " + response.statusCode());
                }
                // 有界读取：最多 2 MiB，超出视为异常响应
                byte[] body = readBounded(response.body(), MAX_SEARCH_BYTES);
                JsonObject json = GSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
                if (json == null || !json.has("data") || !json.get("data").isJsonArray()) {
                    throw new IOException("搜索接口返回结构异常");
                }
                JsonArray data = json.getAsJsonArray("data");
                if (data.isEmpty()) {
                    return null; // 无结果（含付费歌被过滤）
                }
                JsonObject first = data.get(0).getAsJsonObject();
                String songId = truncate(first.has("songid") ? first.get("songid").getAsString() : "", 32);
                if (!songId.matches("\\d{4,20}")) {
                    throw new IOException("songId 非纯数字: " + songId);
                }
                return new SongInfo(
                        songId,
                        truncate(first.has("title") ? first.get("title").getAsString() : "未知", MAX_TITLE),
                        truncate(first.has("author") ? first.get("author").getAsString() : "未知", MAX_AUTHOR),
                        truncate(first.has("lrc") ? first.get("lrc").getAsString() : "", MAX_LRC));
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("搜索失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 时长探测：Range 请求头部 64 KiB。
     * 206 → 总大小取 Content-Range 的 total；200 → Content-Length。
     * 必须流式有界读取（ofInputStream + 最多 64KiB 即 close），严禁 ofByteArray。
     */
    public static CompletableFuture<Long> probeDurationMs(String songId) {
        return CompletableFuture.supplyAsync(() -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(outerUrl(songId)))
                    .timeout(PROBE_TIMEOUT)
                    .header("User-Agent", OUTBOUND_UA)
                    .header("Range", "bytes=0-" + (PROBE_HEAD_BYTES - 1))
                    .header("Accept-Encoding", "identity") // 字节长度语义不被内容编码干扰
                    .GET()
                    .build();
            try {
                HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    long totalBytes = resolveTotalBytes(response);
                    byte[] head = readBounded(body, PROBE_HEAD_BYTES);
                    return Mp3DurationProbe.probeDurationMs(head, totalBytes);
                }
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                FireflyMCMod.LOGGER.warn("[Music] 时长探测失败 songId={}: {}", songId, e.getMessage());
                return Mp3DurationProbe.FALLBACK_DURATION_MS; // 探测失败不阻塞入队
            }
        });
    }

    /** 206 → Content-Range total；200 → Content-Length；异常 → -1（触发 fallback） */
    private static long resolveTotalBytes(HttpResponse<?> response) {
        String contentRange = response.headers().firstValue("Content-Range").orElse(null);
        // 格式：bytes 0-65535/4738291
        if (contentRange != null && response.statusCode() == 206) {
            int slash = contentRange.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < contentRange.length()) {
                try {
                    return Long.parseLong(contentRange.substring(slash + 1).strip());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return response.headers().firstValueAsLong("Content-Length").orElse(-1L);
    }

    /** 有界读取：最多 maxBytes，超出抛异常（防无界下载进内存） */
    private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > maxBytes) {
                throw new IOException("响应超过上限 " + maxBytes + " 字节");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
```

**注意**：`FireflyMCMod.LOGGER` 若项目主类没有该字段（当前主类用 `System.out.println`），在 `FireflyMCMod` 中补：

```java
public static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
```

（`LogUtils` 是 MC 自带 slf4j 工厂，无需新依赖。）

- [x] **Step 2: 编译验证**

Run: `.\gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL

- [x] **Step 3: 手动验证搜索与探测（临时 main 方法或 JShell）**

写临时类 `src/test/java/firefly520/fireflymc/music/ManualApiCheck.java`（无 @Test 注解，不进 CI）：

```java
package firefly520.fireflymc.music;

public class ManualApiCheck {
    public static void main(String[] args) {
        MusicApiClient.search("起风了").thenAccept(song -> {
            System.out.println("搜索结果: " + song);
            if (song != null) {
                MusicApiClient.probeDurationMs(song.songId()).thenAccept(d ->
                        System.out.println("探测时长: " + d + "ms（约 " + d / 1000 + "s）"));
            }
        }).join();
        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
    }
}
```

Run: `.\gradlew.bat compileTestJava` 后用 IDE 运行 main（或临时加 application 插件运行）。
Expected: 控制台输出 `搜索结果: SongInfo[songId=1330348068, title=起风了, ...]` 与 `探测时长: ≈243000ms`。若本机 IPv6 环境导致挂起，参考设计文档 §2.5——不在代码层强制 IPv4，换网络验证。

---

### Task 9: 网络注册 + 握手 capability

**Files:**
- Modify: `src/main/java/firefly520/fireflymc/network/ModNetwork.java:15`（版本号 + 新注册块）
- Modify: `src/main/java/firefly520/fireflymc/network/ModPayloadHandler.java:32-55`（握手 capability）
- Create: `src/main/java/firefly520/fireflymc/music/MusicServerBridge.java`（集成层：把 payload 广播到 PacketDistributor）

- [x] **Step 1: ModPayloadHandler 增加音乐能力记录**

在 `ModPayloadHandler` 类中新增字段：

```java
    // 音乐能力客户端（已握手，无论 dedicated / LAN / 单人；离线时由集成层移除）
    public static final Map<UUID, Boolean> MUSIC_CAPABLE_PLAYERS = new ConcurrentHashMap<>();
```

修改 `handleHandshakeReply`（第 32-55 行），在 `if (serverPlayer.server.isSingleplayer()) { return; }` **之前**插入 capability 记录（版本匹配才记录）：

```java
    public static void handleHandshakeReply(ModHandshakeReplyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                // 音乐能力记录：所有服务器类型（含单人/LAN）都在 isSingleplayer 早退前完成
                if (payload.modVersion().equals(FireflyMCMod.VERSION)) {
                    MUSIC_CAPABLE_PLAYERS.put(serverPlayer.getUUID(), true);
                }
                if (serverPlayer.server.isSingleplayer()) {
                    return; // 原有逻辑：单人/LAN 不做版本验证踢人
                }
                // ...原有 VERIFIED_PLAYERS 逻辑保持不变...
```

- [x] **Step 2: MusicServerBridge 集成层**

```java
package firefly520.fireflymc.music;

import firefly520.fireflymc.network.ModPayloadHandler;
import firefly520.fireflymc.network.MusicPlaybackFailedPayload;
import firefly520.fireflymc.network.MusicQueueSyncPayload;
import firefly520.fireflymc.network.MusicStartPayload;
import firefly520.fireflymc.network.MusicStopPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 音乐模块与 MC 服务端的集成层：
 * - 持有每个 server 实例的 MusicQueueManager（广播经 PacketDistributor 发包）
 * - 维护 musicCapablePlayers ∩ 在线 的计数与判定
 */
public final class MusicServerBridge {

    private static final AtomicReference<MusicQueueManager> INSTANCE = new AtomicReference<>();
    private static MinecraftServer server;

    public static void onServerStarted(MinecraftServer mcServer) {
        server = mcServer;
        MusicQueueManager manager = new MusicQueueManager(
                System::nanoTime,
                start -> PacketDistributor.sendToAllPlayers(start),
                stop -> PacketDistributor.sendToAllPlayers(stop),
                sync -> PacketDistributor.sendToAllPlayers(sync),
                new MusicQueueManager.CapabilityLookup() {
                    @Override
                    public boolean isCapable(UUID player) {
                        return server.getPlayerList().getPlayer(player) != null
                                && ModPayloadHandler.MUSIC_CAPABLE_PLAYERS.containsKey(player);
                    }

                    @Override
                    public int capableOnlineCount() {
                        int n = 0;
                        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                            if (ModPayloadHandler.MUSIC_CAPABLE_PLAYERS.containsKey(p.getUUID())) {
                                n++;
                            }
                        }
                        return n;
                    }
                });
        INSTANCE.set(manager);
    }

    public static void onServerStopping() {
        INSTANCE.set(null);
        server = null;
    }

    public static MusicQueueManager manager() {
        return INSTANCE.get();
    }

    /** 玩家登录：定向发送当前曲（中途加入）+ 队列状态 */
    public static void onPlayerLoggedIn(ServerPlayer player) {
        MusicQueueManager m = manager();
        if (m == null) {
            return;
        }
        MusicStartPayload current = m.currentStartPayload();
        if (current != null) {
            PacketDistributor.sendToPlayer(player, current);
        }
    }

    /** 玩家登出：capability 移除（locked 保留，由状态机负责） */
    public static void onPlayerLoggedOut(ServerPlayer player) {
        ModPayloadHandler.MUSIC_CAPABLE_PLAYERS.remove(player.getUUID());
        MusicQueueManager m = manager();
        if (m != null) {
            m.onPlayerLogout(player.getUUID());
        }
    }

    /** 服务端每 tick（由 ServerTickEvent 驱动，见 Task 12 挂钩） */
    public static void tick() {
        MusicQueueManager m = manager();
        if (m != null) {
            m.tick();
        }
    }

    /** 客户端失败上报入口（ModPayloadHandler 收包后调用） */
    public static void onClientFailure(ServerPlayer reporter, MusicPlaybackFailedPayload payload) {
        MusicQueueManager m = manager();
        if (m != null) {
            m.onClientFailure(reporter.getUUID(), payload.playbackId(), payload.failureCode());
        }
    }
}
```

- [x] **Step 3: ModNetwork 注册四个包 + 版本号**

`ModNetwork.java` 第 15 行改为：

```java
    public static final String NETWORK_VERSION = "1.1.0";
```

在 `registerPayloads` 方法内（`CrossChatRelayPayload` 注册之后）追加：

```java
        // ===== 点歌系统 =====
        // S→C 开始播放（含登录同步）
        registrar.playToClient(
                MusicStartPayload.TYPE,
                MusicStartPayload.STREAM_CODEC,
                (payload, context) -> handleMusicStartOnClient(payload, context)
        );
        // S→C 队列同步
        registrar.playToClient(
                MusicQueueSyncPayload.TYPE,
                MusicQueueSyncPayload.STREAM_CODEC,
                (payload, context) -> handleMusicQueueSyncOnClient(payload, context)
        );
        // S→C 停止
        registrar.playToClient(
                MusicStopPayload.TYPE,
                MusicStopPayload.STREAM_CODEC,
                (payload, context) -> handleMusicStopOnClient(payload, context)
        );
        // C→S 播放失败上报
        registrar.playToServer(
                MusicPlaybackFailedPayload.TYPE,
                MusicPlaybackFailedPayload.STREAM_CODEC,
                ModPayloadHandler::handleMusicPlaybackFailed
        );
```

类末尾追加三个反射转发方法（模式与现有 `handleCrossChatRelayOnClient` 完全一致）：

```java
    private static void handleMusicStartOnClient(MusicStartPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            try {
                Class<?> handlerClass = Class.forName("firefly520.fireflymc.client.ClientPayloadHandler");
                java.lang.reflect.Method method = handlerClass.getDeclaredMethod(
                    "handleMusicStart", MusicStartPayload.class, IPayloadContext.class);
                method.invoke(null, payload, context);
            } catch (Exception e) {
                // 忽略
            }
        }
    }

    private static void handleMusicQueueSyncOnClient(MusicQueueSyncPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            try {
                Class<?> handlerClass = Class.forName("firefly520.fireflymc.client.ClientPayloadHandler");
                java.lang.reflect.Method method = handlerClass.getDeclaredMethod(
                    "handleMusicQueueSync", MusicQueueSyncPayload.class, IPayloadContext.class);
                method.invoke(null, payload, context);
            } catch (Exception e) {
                // 忽略
            }
        }
    }

    private static void handleMusicStopOnClient(MusicStopPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            try {
                Class<?> handlerClass = Class.forName("firefly520.fireflymc.client.ClientPayloadHandler");
                java.lang.reflect.Method method = handlerClass.getDeclaredMethod(
                    "handleMusicStop", MusicStopPayload.class, IPayloadContext.class);
                method.invoke(null, payload, context);
            } catch (Exception e) {
                // 忽略
            }
        }
    }
```

在 `ModPayloadHandler` 中新增失败上报处理：

```java
    /**
     * 服务端处理客户端播放失败上报（回到主线程后交给 MusicServerBridge 聚合）
     */
    public static void handleMusicPlaybackFailed(MusicPlaybackFailedPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer serverPlayer) {
                firefly520.fireflymc.music.MusicServerBridge.onClientFailure(serverPlayer, payload);
            }
        });
    }
```

- [x] **Step 4: 编译验证**

Run: `.\gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL（`MusicServerBridge.onPlayerLoggedIn/Out` 引用尚无调用方没关系，编译通过即可；ClientPayloadHandler 的三个 handleMusicXxx 将在 Task 12 实现——**注意：此时反射调用目标还不存在，但反射在运行时才解析，编译不受影响**）

- [x] **Step 5: FireflyMCMod 挂服务端生命周期（本任务先挂 server tick/登录登出）**

`FireflyMCMod.java` 构造器中，在 `NeoForge.EVENT_BUS.addListener(ModEventHandler::onPlayerLoggedOut);` 之后追加：

```java
    // 点歌系统：服务端生命周期与 tick
    NeoForge.EVENT_BUS.addListener(MusicServerEvents::onServerStarted);
    NeoForge.EVENT_BUS.addListener(MusicServerEvents::onServerStopping);
    NeoForge.EVENT_BUS.addListener(MusicServerEvents::onPlayerLoggedIn);
    NeoForge.EVENT_BUS.addListener(MusicServerEvents::onPlayerLoggedOut);
    NeoForge.EVENT_BUS.addListener(MusicServerEvents::onServerTick);
```

新建 `src/main/java/firefly520/fireflymc/music/MusicServerEvents.java`：

```java
package firefly520.fireflymc.music;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.ServerStartedEvent;
import net.neoforged.neoforge.event.ServerStoppingEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 音乐模块服务端事件挂钩（登录同步/登出清理/权威计时 tick） */
public class MusicServerEvents {
    public static void onServerStarted(ServerStartedEvent event) {
        MusicServerBridge.onServerStarted(event.getServer());
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        MusicServerBridge.onServerStopping();
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            MusicServerBridge.onPlayerLoggedIn(sp);
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            MusicServerBridge.onPlayerLoggedOut(sp);
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MusicServerBridge.tick();
    }
}
```

（`ServerTickEvent.Post` 为 NeoForge 21.1 的服务端 tick 事件；若类名/包名不符，以本地 `.sakura/docs/` 文档或 NeoForge 源码为准修正。）

- [x] **Step 6: 编译验证**

Run: `.\gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL

---

### Task 10: MusicCommandHandler（命令树）

**Files:**
- Create: `src/main/java/firefly520/fireflymc/music/MusicCommandHandler.java`
- Modify: 现有命令注册事件（找到项目现有 `RegisterCommandsEvent` 订阅类，把音乐命令挂进同一订阅）

- [x] **Step 1: 实现 MusicCommandHandler**

**注意**：不加 `Dist.DEDICATED_SERVER` 限定（音乐系统必须在单人/LAN/专服全部工作——这是设计文档 §7 明确的现有代码坑规避点）。

```java
package firefly520.fireflymc.music;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import firefly520.fireflymc.network.MusicQueueSyncPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * 点歌命令。注册到 NeoForge GAME 总线（不限定 Dist：单人/LAN/专服都要工作）。
 *
 * /点歌 <歌名>
 * /fireflymc music request <歌名> | queue | skip | stop
 */
@EventBusSubscriber
public class MusicCommandHandler {

    private static final int MAX_KEYWORD_CHARS = 256;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // 中文顶层别名
        dispatcher.register(Commands.literal("点歌")
                .then(Commands.argument("keyword", StringArgumentType.greedyString())
                        .executes(ctx -> requestSong(ctx.getSource(), StringArgumentType.getString(ctx, "keyword")))));

        // 英文路径（沿用 /fireflymc 子命令树风格）
        dispatcher.register(Commands.literal("fireflymc")
                .then(Commands.literal("music")
                        .then(Commands.literal("request")
                                .then(Commands.argument("keyword", StringArgumentType.greedyString())
                                        .executes(ctx -> requestSong(ctx.getSource(), StringArgumentType.getString(ctx, "keyword")))))
                        .then(Commands.literal("queue").executes(ctx -> showQueue(ctx.getSource())))
                        .then(Commands.literal("skip").executes(ctx -> privilegedAction(ctx.getSource(), true)))
                        .then(Commands.literal("stop").executes(ctx -> privilegedAction(ctx.getSource(), false)))));
    }

    /** 点歌：权限/锁检查（服务端线程）→ 虚拟线程搜索 → server.execute 回状态机 */
    private static int requestSong(CommandSourceStack source, String keyword) {
        if (keyword.length() > MAX_KEYWORD_CHARS) {
            source.sendFailure(Component.translatable("fireflymc.music.error.keyword_too_long"));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("fireflymc.music.error.player_only"));
            return 0;
        }
        MusicQueueManager manager = MusicServerBridge.manager();
        if (manager == null) {
            return 0;
        }
        MinecraftServer server = player.server;
        boolean privileged = server.isSingleplayerOwner(player.getGameProfile())
                || source.hasPermission(2);

        MusicQueueManager.BeginResult begin = manager.tryBeginRequest(player.getUUID(), privileged);
        switch (begin) {
            case LOCKED -> {
                source.sendFailure(Component.translatable("fireflymc.music.error.locked"));
                return 0;
            }
            case PENDING -> {
                source.sendFailure(Component.translatable("fireflymc.music.error.pending"));
                return 0;
            }
            case QUEUE_FULL -> {
                source.sendFailure(Component.translatable("fireflymc.music.error.queue_full",
                        MusicQueueManager.MAX_QUEUE_SIZE));
                return 0;
            }
        }
        MusicQueueManager.SearchSession session = manager.latestSession();
        source.sendSuccess(() -> Component.translatable("fireflymc.music.searching", keyword), false);

        // 虚拟线程：搜索 + 时长探测（两个 IO 串行）
        Thread.ofVirtual().name("fireflymc-music-search").start(() -> {
            MusicApiClient.SongInfo info;
            try {
                info = MusicApiClient.search(keyword).join();
            } catch (Exception e) {
                info = null;
            }
            if (info == null) {
                server.execute(() -> {
                    manager.failRequest(player.getUUID());
                    if (player.hasDisconnected()) {
                        return;
                    }
                    player.sendSystemMessage(Component.translatable("fireflymc.music.error.not_found", keyword));
                });
                return;
            }
            long durationMs = MusicApiClient.probeDurationMs(info.songId()).join();
            QueuedSong song = new QueuedSong(info.songId(), info.title(), info.author(),
                    info.lrc(), player.getGameProfile().getName(), player.getUUID(), durationMs);
            server.execute(() -> {
                boolean accepted = manager.completeRequest(player.getUUID(), song);
                if (!player.hasDisconnected()) {
                    if (accepted) {
                        player.sendSystemMessage(Component.translatable(
                                "fireflymc.music.queued", info.title(), info.author()));
                        // 全服播报
                        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                            if (p != player) {
                                p.sendSystemMessage(Component.translatable(
                                        "fireflymc.music.announce", player.getGameProfile().getName(),
                                        info.title(), info.author()));
                            }
                        }
                    } else {
                        player.sendSystemMessage(Component.translatable("fireflymc.music.error.queue_full",
                                MusicQueueManager.MAX_QUEUE_SIZE));
                    }
                }
            });
        });
        return 1;
    }

    /** /fireflymc music queue：聊天栏输出当前曲+完整队列 */
    private static int showQueue(CommandSourceStack source) {
        MusicQueueManager manager = MusicServerBridge.manager();
        if (manager == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("fireflymc.music.queue.header"), false);
        List<MusicQueueSyncPayload.SongSummary> queue = manager.queueSummaries();
        if (queue.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("fireflymc.music.queue.empty"), false);
        } else {
            for (int i = 0; i < queue.size(); i++) {
                MusicQueueSyncPayload.SongSummary s = queue.get(i);
                int idx = i + 1;
                source.sendSuccess(() -> Component.translatable(
                        "fireflymc.music.queue.entry", idx, s.title(), s.author(), s.requesterName()), false);
            }
        }
        return 1;
    }

    /** skip（true）/ stop（false）：仅特权 */
    private static int privilegedAction(CommandSourceStack source, boolean skip) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return 0;
        }
        MinecraftServer server = player.server;
        boolean privileged = server.isSingleplayerOwner(player.getGameProfile())
                || source.hasPermission(2);
        if (!privileged) {
            source.sendFailure(Component.translatable("fireflymc.music.error.no_permission"));
            return 0;
        }
        MusicQueueManager manager = MusicServerBridge.manager();
        if (manager == null) {
            return 0;
        }
        if (skip) {
            manager.skip();
        } else {
            manager.stopAll();
        }
        source.sendSuccess(() -> Component.translatable(
                skip ? "fireflymc.music.skipped" : "fireflymc.music.stopped"), false);
        return 1;
    }
}
```

- [x] **Step 2: MusicQueueManager 补充 queueSummaries()**

在 `MusicQueueManager` 中加公开方法（broadcastQueueSync 已有同样映射逻辑，抽公共）：

```java
    /** 供 /fireflymc music queue 命令读取完整队列 */
    public java.util.List<MusicQueueSyncPayload.SongSummary> queueSummaries() {
        return queue.stream()
                .map(q -> new MusicQueueSyncPayload.SongSummary(q.title(), q.author(), q.requesterName()))
                .toList();
    }
```

- [x] **Step 3: 编译验证**

Run: `.\gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL（lang key 尚未定义，translatable 在运行时才解析，编译通过）

---

### Task 11: 客户端播放器（状态 + 输出 + 解码线程 + 管理器）

**Files:**
- Create: `src/main/java/firefly520/fireflymc/client/music/MusicPlaybackState.java`
- Create: `src/main/java/firefly520/fireflymc/client/music/JavaSoundOutput.java`
- Create: `src/main/java/firefly520/fireflymc/client/music/MusicPlayer.java`
- Create: `src/main/java/firefly520/fireflymc/client/music/MusicPlaybackManager.java`

本任务为手动测试组件（JavaSound/线程），无单测；进度逻辑已由 Task 3 的 PlaybackClock 覆盖。

- [x] **Step 1: MusicPlaybackState（HUD 读取的状态单例）**

```java
package firefly520.fireflymc.client.music;

import firefly520.fireflymc.network.MusicQueueSyncPayload;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端播放状态（HUD 每 tick 读取；播放线程/主线程写）。
 * 全部字段均为 volatile/不可变快照，避免 HUD 读到半更新状态。
 */
public final class MusicPlaybackState {

    public record PlayingInfo(
            long playbackId,
            String songId,
            String title,
            String author,
            String requesterName,
            long durationMs,          // 服务端权威时长（HUD 总时长以此为准）
            TreeMap<Long, String> lrc, // 解析后的歌词；空 map 表示无歌词
            PlaybackClock clock       // 当前时钟（JavaSound / Silent）
    ) {}

    private static final AtomicReference<PlayingInfo> CURRENT = new AtomicReference<>();
    private static final AtomicReference<List<MusicQueueSyncPayload.SongSummary>> QUEUE =
            new AtomicReference<>(List.of());

    private MusicPlaybackState() {}

    public static void setPlaying(PlayingInfo info) { CURRENT.set(info); }
    public static void clearPlaying() { CURRENT.set(null); }
    public static PlayingInfo current() { return CURRENT.get(); }

    public static void setQueue(List<MusicQueueSyncPayload.SongSummary> queue) {
        QUEUE.set(List.copyOf(queue));
    }
    public static List<MusicQueueSyncPayload.SongSummary> queue() { return QUEUE.get(); }

    /** HUD 用：当前歌词行 */
    public static Optional<String> currentLyricLine() {
        PlayingInfo info = current();
        if (info == null || info.lrc().isEmpty()) {
            return Optional.empty();
        }
        return LrcParser.currentLine(info.lrc(), info.clock().positionMs());
    }
}
```

- [x] **Step 2: JavaSoundOutput（SourceDataLine 封装，打不开则返回 null 触发静音降级）**

```java
package firefly520.fireflymc.client.music;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * JavaSound 输出封装。打开失败返回 null（调用方走 SilentPlaybackClock 静音降级，
 * HUD 照常显示，不误报 FAILED——无输出设备是本地降级不是全局播放失败）。
 */
public final class JavaSoundOutput {

    private final SourceDataLine line;
    private final int sampleRate;

    private JavaSoundOutput(SourceDataLine line, int sampleRate) {
        this.line = line;
        this.sampleRate = sampleRate;
    }

    /** 尝试以 MP3 解码参数打开线路；失败返回 null */
    public static JavaSoundOutput tryOpen(int sampleRate, int channels) {
        try {
            AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(
                    new javax.sound.sampled.DataLine.Info(SourceDataLine.class, format));
            line.open(format, 4096 * 8); // 缓冲约 0.7s @44.1k stereo
            line.start();
            return new JavaSoundOutput(line, sampleRate);
        } catch (LineUnavailableException | IllegalArgumentException e) {
            return null;
        }
    }

    /** 阻塞式写入（write 本身即背压） */
    public void writePcm(short[] samples, int offset, int length) {
        byte[] bytes = new byte[length * 2];
        for (int i = 0; i < length; i++) {
            int v = samples[offset + i];
            bytes[i * 2] = (byte) v;
            bytes[i * 2 + 1] = (byte) (v >> 8);
        }
        line.write(bytes, 0, bytes.length);
    }

    public PlaybackClock clock(long basePositionMs) {
        return new PlaybackClock.JavaSound(line, sampleRate, basePositionMs);
    }

    public void stopAndClose() {
        try {
            line.flush();
            line.stop();
            line.close();
        } catch (Exception ignored) {
        }
    }
}
```

- [x] **Step 3: MusicPlayer（解码线程：边下边播 + Tee 缓存 + 音量乘法 + discard 快进）**

```java
package firefly520.fireflymc.client.music;

import firefly520.fireflymc.music.MusicApiClient;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 播放线程：HTTP 流 → JLayer 解码 → PCM →（音量乘法）→ SourceDataLine。
 * 边下边播；Tee 双写缓存；中途加入 discard 快进。
 * 本线程绝不接触 Minecraft 对象（音量经 AtomicReference 传入）。
 */
public class MusicPlayer implements Runnable {

    /** 本地播放失败码（供 manager 上报） */
    public enum LocalFailure { NONE, HTTP_FAILED, MP3_DECODE_FAILED }

    public interface Callbacks {
        void onFinished(long playbackId, boolean success);          // 解码循环自然结束
        void onLocalFailure(long playbackId, LocalFailure code);    // 下载/解码失败（上报信号）
    }

    private final long playbackId;
    private final String songId;
    private final long basePositionMs;
    private final TreeMap<Long, String> lrc;
    private final AtomicReference<Float> volumeRef;
    private final MusicCache cache;
    private final Callbacks callbacks;

    private volatile boolean cancelled = false;
    private volatile InputStream httpStream;   // stop 序列 close 用
    private volatile Path partFile;
    private volatile boolean httpMode = false;

    public MusicPlayer(long playbackId, String songId, long basePositionMs,
                       TreeMap<Long, String> lrc,
                       AtomicReference<Float> volumeRef,
                       MusicCache cache, Callbacks callbacks) {
        this.playbackId = playbackId;
        this.songId = songId;
        this.basePositionMs = basePositionMs;
        this.lrc = lrc;
        this.volumeRef = volumeRef;
        this.cache = cache;
        this.callbacks = callbacks;
    }

    @Override
    public void run() {
        // 1. 数据源：缓存命中 → 本地；否则 outer url 流式下载（Tee 写 .part）
        java.io.InputStream dataSource;
        OutputStream tee = null;
        Path localFile = cache.getCachedFile(songId).orElse(null);
        if (localFile != null) {
            try {
                dataSource = new BufferedInputStream(Files.newInputStream(localFile), 65536);
            } catch (IOException e) {
                callbacks.onLocalFailure(playbackId, LocalFailure.HTTP_FAILED);
                return;
            }
        } else {
            try {
                HttpClient http = HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
                HttpRequest request = HttpRequest.newBuilder(java.net.URI.create(MusicApiClient.outerUrl(songId)))
                        .timeout(java.time.Duration.ofSeconds(20))
                        .header("User-Agent", MusicApiClient.OUTBOUND_UA)
                        .GET()
                        .build();
                HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    response.body().close();
                    callbacks.onLocalFailure(playbackId, LocalFailure.HTTP_FAILED);
                    return;
                }
                httpStream = response.body();
                httpMode = true;
                dataSource = new BufferedInputStream(httpStream, 65536);
                partFile = cache.beginPartFile(songId, playbackId);
                if (partFile != null) {
                    tee = Files.newOutputStream(partFile);
                }
            } catch (Exception e) {
                closeQuietly(httpStream);
                cache.deletePartFile(partFile);
                callbacks.onLocalFailure(playbackId, LocalFailure.HTTP_FAILED);
                return;
            }
        }

        // 2. 解码循环
        boolean success = false;
        JavaSoundOutput output = null;
        Bitstream bitstream = new Bitstream(dataSource);
        try {
            Decoder decoder = new Decoder();
            Header firstHeader = bitstream.readFrame();
            if (firstHeader == null) {
                callbacks.onLocalFailure(playbackId, LocalFailure.MP3_DECODE_FAILED);
                return;
            }
            int sampleRate = firstHeader.frequency();
            int channels = firstHeader.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
            output = JavaSoundOutput.tryOpen(sampleRate, channels);
            // output == null → 静音降级：不跑高速解码循环，由 Silent 时钟维持 HUD
            PlaybackClock clock = output != null
                    ? output.clock(basePositionMs)
                    : new PlaybackClock.Silent(basePositionMs);

            // discard 快进（中途加入）：解码并丢弃 positionMs 之前的 PCM
            boolean seeking = basePositionMs > 0 && output != null;
            long totalFrames = 0L;

            Header header = firstHeader;
            while (!cancelled) {
                if (tee != null && httpMode) {
                    // 帧数据已在 readFrame 内部流过，Tee 需要独立拷贝——见下方实现注意
                }
                SampleBuffer pcm = decoder.decodeFrame(header, bitstream);
                short[] samples = pcm.getBuffer();
                int len = pcm.getBufferLength();

                if (seeking) {
                    long frameMs = len * 1000L / sampleRate;
                    if (totalFrames * 1000L / sampleRate + frameMs <= basePositionMs) {
                        totalFrames += len;
                        bitstream.closeFrame();
                        header = bitstream.readFrame();
                        if (header == null) {
                            break;
                        }
                        continue;
                    }
                    seeking = false; // 越过 base 位置，开始正常播放
                }

                if (output != null) {
                    applyVolume(samples, len);
                    output.writePcm(samples, 0, len);
                }
                bitstream.closeFrame();
                header = bitstream.readFrame();
                if (header == null) {
                    break; // 流结束（自然播完）
                }
            }
            success = !cancelled;
        } catch (Exception e) {
            success = false;
            if (!cancelled) {
                callbacks.onLocalFailure(playbackId, LocalFailure.MP3_DECODE_FAILED);
            }
        } finally {
            if (output != null) {
                output.stopAndClose();
            }
            try {
                bitstream.close();
            } catch (Exception ignored) {
            }
            closeQuietly(dataSource);
            if (tee != null) {
                closeQuietly(tee);
            }
            if (success && partFile != null) {
                cache.finalizePartFile(partFile, songId); // 成功才 rename 为 .mp3
            } else {
                cache.deletePartFile(partFile);
            }
        }
        if (!cancelled) {
            callbacks.onFinished(playbackId, success);
        }
    }

    /** 音量乘法：读 AtomicReference 纯数值，对 PCM 样本乘系数（MASTER×MUSIC 由 tick 线程算好） */
    private void applyVolume(short[] samples, int length) {
        float volume = volumeRef.get();
        if (volume >= 0.999f) {
            return;
        }
        if (volume <= 0.001f) {
            java.util.Arrays.fill(samples, 0, length, (short) 0);
            return;
        }
        for (int i = 0; i < length; i++) {
            int v = Math.round(samples[i] * volume);
            samples[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
        }
    }

    /** stop 序列：置取消 + 关闭网络流（解除 read 阻塞）+ 删 .part */
    public void cancel() {
        cancelled = true;
        closeQuietly(httpStream);
        cache.deletePartFile(partFile);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
```

**实现注意（Tee 的正确实现）**：JLayer 的 `Bitstream` 直接消费 `InputStream`，帧原始字节不会暴露给调用方，因此"边解码边写缓存"**不能**通过包装 Bitstream 的输入流简单实现——正确做法是用 `TeeInputStream` 包装原始流：

```java
/** 在 src/main/java/firefly520/fireflymc/client/music/TeeInputStream.java 新建 */
package firefly520.fireflymc.client.music;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 输入流 Tee：读取数据同时写入缓存分支（缓存写失败仅降级停止拷贝，不影响播放） */
public class TeeInputStream extends FilterInputStream {
    private final OutputStream branch;
    private volatile boolean branchBroken = false;

    public TeeInputStream(InputStream in, OutputStream branch) {
        super(in);
        this.branch = branch;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0 && !branchBroken) {
            try {
                branch.write(b, off, n);
            } catch (IOException e) {
                branchBroken = true; // 磁盘满等：静默降级，仅不缓存
            }
        }
        return n;
    }

    public void closeBranch() throws IOException {
        if (!branchBroken) {
            branch.flush();
            branch.close();
        }
    }
}
```

`MusicPlayer` 中把 `dataSource = new BufferedInputStream(httpStream, 65536);` 改为：

```java
                InputStream raw = httpStream;
                if (partFile != null) {
                    tee = Files.newOutputStream(partFile);
                    raw = new TeeInputStream(httpStream, tee);
                }
                dataSource = new BufferedInputStream(raw, 65536);
```

并删除解码循环内那个空的 `if (tee != null && httpMode)` 占位块。`finally` 里 tee 的关闭改调 `((TeeInputStream) ...).closeBranch()` 或统一 `closeQuietly(tee)`（OutputStream close 即 flush）。缓存成功的判定改为"HTTP 数据源完整读完（header==null 正常 break）"——即现有 `success` 逻辑。

- [x] **Step 4: MusicPlaybackManager（生命周期权威：stop 完整序列 + playbackId 校验）**

```java
package firefly520.fireflymc.client.music;

import firefly520.fireflymc.network.MusicStartPayload;
import firefly520.fireflymc.network.MusicQueueSyncPayload;
import firefly520.fireflymc.network.MusicStopPayload;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端播放生命周期权威（全部方法只在客户端主线程调用——payload handler 已 enqueueWork）。
 * stop 完整序列：失效 playbackId → close 网络流 → close Bitstream/line → 删 .part → interrupt。
 * 旧 worker 的任何写回先验证仍是当前 playbackId。
 */
public final class MusicPlaybackManager {

    /** MASTER×MUSIC 音量（tick 线程写，播放线程读） */
    private static final AtomicReference<Float> VOLUME = new AtomicReference<>(1.0f);

    private static final MusicCache CACHE = MusicCache.createDefault();
    private static volatile Thread workerThread;
    private static volatile long currentPlaybackId = 0L; // 0=无实例

    private MusicPlaybackManager() {}

    /** 音量桥写入（ClientTick 每 tick 调用，不碰播放线程） */
    public static void setEffectiveVolume(float volume) {
        VOLUME.set(volume);
    }

    /** 收到 MusicStartPayload：无条件停旧曲再起新曲（服务端权威） */
    public static void start(MusicStartPayload payload) {
        stopInternal(false);
        currentPlaybackId = payload.playbackId();
        TreeMap<Long, String> lrc = LrcParser.parse(payload.lrc());

        // 静音降级探测：JavaSoundOutput.tryOpen 在 MusicPlayer 内做，
        // 这里先以占位 Silent 时钟建立状态，解码线程启动后替换为真实时钟
        PlaybackClock initialClock = new PlaybackClock.Silent(payload.positionMs());
        MusicPlaybackState.setPlaying(new MusicPlaybackState.PlayingInfo(
                payload.playbackId(), payload.songId(), payload.title(), payload.author(),
                payload.requesterName(), payload.durationMs(), lrc, initialClock));

        MusicPlayer player = new MusicPlayer(
                payload.playbackId(), payload.songId(), payload.positionMs(),
                lrc, VOLUME, CACHE, new MusicPlayer.Callbacks() {
                    @Override
                    public void onFinished(long playbackId, boolean success) {
                        if (playbackId != currentPlaybackId) {
                            return; // 旧 worker 写回，忽略
                        }
                        // 自然结束：保持 HUD（等下一个 Start/Stop）；时钟已停走由 Silent 兜底
                        MusicPlaybackState.PlayingInfo info = MusicPlaybackState.current();
                        if (info != null) {
                            MusicPlaybackState.setPlaying(new MusicPlaybackState.PlayingInfo(
                                    info.playbackId(), info.songId(), info.title(), info.author(),
                                    info.requesterName(), info.durationMs(), info.lrc(),
                                    new PlaybackClock.Silent(info.durationMs()))); // 钉在终点
                        }
                    }

                    @Override
                    public void onLocalFailure(long playbackId, MusicPlayer.LocalFailure code) {
                        if (playbackId != currentPlaybackId) {
                            return;
                        }
                        // 上报信号（服务端 quorum 决定是否全服切歌）+ 本地切 Silent 模式
                        firefly520.fireflymc.client.ClientMusicFailReporter.report(playbackId, code);
                        MusicPlaybackState.PlayingInfo info = MusicPlaybackState.current();
                        if (info != null) {
                            MusicPlaybackState.setPlaying(new MusicPlaybackState.PlayingInfo(
                                    info.playbackId(), info.songId(), info.title(), info.author(),
                                    info.requesterName(), info.durationMs(), info.lrc(),
                                    info.clock())); // 保留当前时钟（Silent），HUD 继续按权威时长走
                        }
                    }
                });
        Thread t = new Thread(player, "fireflymc-music-playback");
        t.setDaemon(true);
        workerThread = t;
        t.start();
    }

    /** 收到 MusicStopPayload */
    public static void stop(MusicStopPayload payload) {
        stopInternal(true);
    }

    /** 客户端断开连接/退出世界：停止一切本地状态 */
    public static void shutdown() {
        stopInternal(true);
        MusicPlaybackState.clearPlaying();
        MusicPlaybackState.setQueue(java.util.List.of());
    }

    /** 队列同步（HUD 排队列表） */
    public static void onQueueSync(MusicQueueSyncPayload payload) {
        MusicPlaybackState.setQueue(payload.queue());
    }

    private static void stopInternal(boolean clearState) {
        currentPlaybackId = 0L; // 失效 playbackId：旧 worker 写回全部失效
        Thread t = workerThread;
        workerThread = null;
        if (t != null) {
            // cancel() 内部完成：close 网络流（解除 read 阻塞）→ 删 .part；line 由线程 finally 关闭
            t.interrupt();
        }
        if (clearState) {
            MusicPlaybackState.clearPlaying();
        }
    }
}
```

**问题：`MusicPlayer.cancel()` 未被调用。** 修正 `stopInternal`：保存当前 player 引用（`private static volatile MusicPlayer currentWorker;`），`stopInternal` 中先 `currentWorker.cancel()`（close 流 + 删 .part + 置 cancelled）再 `t.interrupt()`。落盘时按此修正。

另需新建 `ClientMusicFailReporter`（客户端专用，避免 manager 引用网络类产生循环）：

```java
package firefly520.fireflymc.client;

import firefly520.fireflymc.client.music.MusicPlayer;
import firefly520.fireflymc.network.MusicPlaybackFailedPayload;
import net.neoforged.neoforge.network.PacketDistributor;

/** 客户端→服务端播放失败上报（只在主线程调用） */
public final class ClientMusicFailReporter {
    private ClientMusicFailReporter() {}

    public static void report(long playbackId, MusicPlayer.LocalFailure code) {
        MusicPlaybackFailedPayload.FailureCode failureCode = switch (code) {
            case HTTP_FAILED -> MusicPlaybackFailedPayload.FailureCode.HTTP_FAILED;
            case MP3_DECODE_FAILED -> MusicPlaybackFailedPayload.FailureCode.MP3_DECODE_FAILED;
            case NONE -> null;
        };
        if (failureCode != null) {
            PacketDistributor.sendToServer(new MusicPlaybackFailedPayload(playbackId, failureCode));
        }
    }
}
```

（文件路径 `src/main/java/firefly520/fireflymc/client/ClientMusicFailReporter.java`）

- [x] **Step 5: 编译验证**

Run: `.\gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL

---

### Task 12: 客户端 payload 分发 + 主类挂钩（音量桥/断开清理）

**Files:**
- Modify: `src/main/java/firefly520/fireflymc/client/ClientPayloadHandler.java`
- Modify: `src/main/java/firefly520/fireflymc/FireflyMCMod.java`（客户端事件块）

- [x] **Step 1: ClientPayloadHandler 追加三个分发方法**

在 `ClientPayloadHandler` 类内追加（签名必须与 `ModNetwork` 反射查找的**完全一致**）：

```java
    /** 点歌：开始播放（含登录同步）。已在 Netty 线程，enqueueWork 回主线程 */
    public static void handleMusicStart(firefly520.fireflymc.network.MusicStartPayload payload,
                                        net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() ->
                firefly520.fireflymc.client.music.MusicPlaybackManager.start(payload));
    }

    /** 点歌：队列同步 */
    public static void handleMusicQueueSync(firefly520.fireflymc.network.MusicQueueSyncPayload payload,
                                            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() ->
                firefly520.fireflymc.client.music.MusicPlaybackManager.onQueueSync(payload));
    }

    /** 点歌：停止 */
    public static void handleMusicStop(firefly520.fireflymc.network.MusicStopPayload payload,
                                        net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() ->
                firefly520.fireflymc.client.music.MusicPlaybackManager.stop(payload));
    }
```

（import 方式跟随该文件现有风格——现有方法用的是顶部 import，落盘时统一改为顶部 import。）

- [x] **Step 2: FireflyMCMod 客户端块追加音量桥与断开清理**

`FireflyMCMod.java` 的 `if (FMLEnvironment.dist == Dist.CLIENT) {` 块内追加：

```java
      // 点歌系统：音量桥（每 tick 读 MASTER×MUSIC 写入 AtomicReference）+ 断开清理
      NeoForge.EVENT_BUS.addListener(firefly520.fireflymc.client.music.MusicClientEvents::onClientTick);
      NeoForge.EVENT_BUS.addListener(firefly520.fireflymc.client.music.MusicClientEvents::onLoggingOut);
```

新建 `src/main/java/firefly520/fireflymc/client/music/MusicClientEvents.java`：

```java
package firefly520.fireflymc.client.music;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** 客户端音乐事件：音量桥 + 断开连接清理 */
public class MusicClientEvents {

    /** 每 tick：MASTER × MUSIC → AtomicReference（播放线程只读纯数值，不碰 Minecraft 对象） */
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) {
            return;
        }
        double master = mc.options.getSoundSourceVolume(SoundSource.MASTER);
        double music = mc.options.getSoundSourceVolume(SoundSource.MUSIC);
        MusicPlaybackManager.setEffectiveVolume((float) (master * music));
    }

    /** 断开连接/退出世界：停止本地播放并清空 HUD 状态 */
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        MusicPlaybackManager.shutdown();
    }
}
```

- [x] **Step 3: 编译验证**

Run: `.\gradlew.bat compileJava`
Expected: BUILD SUCCESSFUL

---

### Task 13: HUD（HudRenderUtil 抽取 + 纵向 stack + MusicHudRenderer）

**Files:**
- Create: `src/main/java/firefly520/fireflymc/client/HudRenderUtil.java`
- Modify: `src/main/java/firefly520/fireflymc/client/ClientHandler.java`
- Modify: `src/main/java/firefly520/fireflymc/client/HUDRenderer.java:38-110`（render 拆为 renderAt）
- Create: `src/main/java/firefly520/fireflymc/client/music/MusicHudRenderer.java`

- [x] **Step 1: HudRenderUtil（从 HUDRenderer 抽出圆角绘制）**

```java
package firefly520.fireflymc.client;

import net.minecraft.client.gui.GuiGraphics;

/** HUD 共享绘制工具（音乐卡片与服务器信息卡片共用） */
public final class HudRenderUtil {

    public static final int BORDER_COLOR = 0x40FFFFFF;
    public static final int BORDER_RADIUS = 4;
    public static final int BORDER_THICKNESS = 1;

    private HudRenderUtil() {}

    /** 圆角边框（自 HUDRenderer 原样抽出，逻辑不变） */
    public static void drawRoundedBorder(GuiGraphics guiGraphics, int x, int y, int width, int height) {
        int r = Math.min(BORDER_RADIUS, Math.min(width / 2, height / 2));
        int t = BORDER_THICKNESS;

        guiGraphics.fill(x + r, y, x + width - r, y + t, BORDER_COLOR);
        guiGraphics.fill(x + r, y + height - t, x + width - r, y + height, BORDER_COLOR);
        guiGraphics.fill(x, y + r, x + t, y + height - r, BORDER_COLOR);
        guiGraphics.fill(x + width - t, y + r, x + width, y + height - r, BORDER_COLOR);

        for (int angle = 0; angle < 90; angle += 2) {
            double rad = Math.toRadians(angle);
            int dx = (int) (r * Math.cos(rad));
            int dy = (int) (r * Math.sin(rad));

            guiGraphics.fill(x + r - dx, y + r - dy, x + r - dx + t, y + r - dy + t, BORDER_COLOR);
            guiGraphics.fill(x + width - r + dx - t, y + r - dy, x + width - r + dx, y + r - dy + t, BORDER_COLOR);
            guiGraphics.fill(x + r - dx, y + height - r + dy - t, x + r - dx + t, y + height - r + dy, BORDER_COLOR);
            guiGraphics.fill(x + width - r + dx - t, y + height - r + dy - t, x + width - r + dx, y + height - r + dy, BORDER_COLOR);
        }
    }
}
```

- [x] **Step 2: HUDRenderer 重构（render 拆 renderAt）**

修改 `HUDRenderer.java`：

1. `private static void drawRoundedBorder(...)` 方法**删除**，其唯一调用点改为 `HudRenderUtil.drawRoundedBorder(guiGraphics, x, y, baseWidth + 16, totalHeight);`；`BORDER_COLOR/BORDER_RADIUS/BORDER_THICKNESS` 常量删除（改用 HudRenderUtil 的）。
2. `public static void render(GuiGraphics guiGraphics)` 改为瘦入口 + 可复用渲染：

```java
  public static void render(GuiGraphics guiGraphics) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.options.hideGui || mc.screen != null) {
      return;
    }
    if (mc.getSingleplayerServer() != null && !mc.getSingleplayerServer().isPublished()) {
      return; // 未发布的单人世界不显示服务器信息卡（原有行为保留）
    }
    LocalPlayer player = mc.player;
    if (player == null) {
      return;
    }
    int[] totalHeightHolder = new int[1];
    float scale = measureScale();
    int contentHeight = measureHeight(mc, player, totalHeightHolder);
    // 垂直居中位置由 ClientHandler 的 stack 布局传入；单独调用时自行居中
    int scaledHeight = (int)(mc.getWindow().getGuiScaledHeight() / scale);
    int y = (scaledHeight - contentHeight) / 2;
    renderAt(guiGraphics, y, () -> {});
  }

  /** ClientHandler 纵向 stack 调用：在指定 y 渲染本卡片 */
  public static void renderAt(GuiGraphics guiGraphics, int y, Runnable noOp) {
    // 原 render(...) 主体：跳过可见性检查与居中计算，直接从参数 y 开始绘制
    // （把原方法体内 y 计算四行删除，其余绘制逻辑原样保留；可见性检查留在 render() 入口）
  }
```

**落盘说明（给实现者的精确指引）**：把原 `render` 方法体从"从配置读取缩放值"（约 line 97）到末尾整体移入新方法 `renderAt(GuiGraphics guiGraphics, int startY)`；`renderAt` 内删除 `int y = (scaledHeight - totalHeight) / 2;` 一行，改用参数 `startY`；`render` 保留全部前置可见性检查后计算 `startY` 调 `renderAt`。`measureHeight` 如抽取困难可放弃抽取——ClientHandler 直接调用 `HUDRenderer.measureTotalHeight()` 获取高度（新增一个只测量不绘制的静态方法，复制高度计算三行：`lineHeight * (2 + urlLines) + playerListHeight + 6`）。`Runnable noOp` 参数不需要，签名定为 `renderAt(GuiGraphics guiGraphics, int startY)`。

- [x] **Step 3: ClientHandler 纵向 stack 布局**

替换 `ClientHandler.java` 全文：

```java
package firefly520.fireflymc.client;

import firefly520.fireflymc.Config;
import firefly520.fireflymc.client.music.MusicHudRenderer;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

public class ClientHandler {

  /** 左侧纵向 stack：音乐卡片在上（4px 间隔）、服务器信息卡在下，整体垂直居中 */
  public static void onRenderGui(RenderGuiEvent.Post event) {
    Minecraft mc = Minecraft.getInstance();
    if (mc.options.hideGui || mc.screen != null || mc.player == null) {
      return;
    }
    float scale = Config.CLIENT.HUD_SCALE.get().floatValue();
    int scaledHeight = (int)(mc.getWindow().getGuiScaledHeight() / scale);

    int musicHeight = MusicHudRenderer.isVisible()
            ? MusicHudRenderer.measureHeight(mc)
            : 0;
    int serverHeight = HUDRenderer.isServerHudVisible(mc)
            ? HUDRenderer.measureTotalHeight(mc)
            : 0;
    int gap = (musicHeight > 0 && serverHeight > 0) ? 4 : 0;
    int total = musicHeight + gap + serverHeight;

    int topY = (scaledHeight - total) / 2;
    int musicY = topY;
    int serverY = topY + musicHeight + gap;

    if (musicHeight > 0) {
      MusicHudRenderer.renderAt(mc, event.getGuiGraphics(), musicY, scale);
    }
    if (serverHeight > 0) {
      HUDRenderer.renderAt(event.getGuiGraphics(), serverY, scale);
    }
  }
}
```

**落盘说明**：`renderAt` 需要 scale 参数（卡片内部不再各自 pushPose/scale，由 ClientHandler 统一 push 一次 scale，两卡片在缩放后坐标系内绘制；或保持两卡片各自 scale——为最小改动，采用**各卡片内部保持原 scale 逻辑**，`renderAt(gui, y)` 内自行 pushPose，ClientHandler 传的 y 已是"缩放后坐标系"的值——因为两个卡片 HUD_SCALE 相同，坐标系统一）。实现时：`HUDRenderer.renderAt(GuiGraphics, int startY)` 内部第一行 `pushPose + scale`（原有逻辑），`MusicHudRenderer.renderAt` 同样；`measureTotalHeight`/`measureHeight` 返回缩放后坐标系高度。`isServerHudVisible(Minecraft)` 是把 render() 前置检查抽出（F1/screen/player/单人未发布）。

- [x] **Step 4: MusicHudRenderer（音乐卡片）**

```java
package firefly520.fireflymc.client.music;

import firefly520.fireflymc.FireflyMCMod;
import firefly520.fireflymc.network.MusicQueueSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Optional;

/**
 * 音乐 HUD 卡片（左侧纵向 stack 的上卡片）。
 * 无歌时不渲染（isVisible=false）。单人世界同样显示（与服务器信息卡的隐藏规则不同）。
 */
public final class MusicHudRenderer {

    private static final int X = 5;
    private static final int LINE_HEIGHT = 11;
    private static final int PADDING = 8;
    private static final int PROGRESS_HEIGHT = 3;
    private static final int MAX_QUEUE_ITEMS = 3;
    private static final int MAX_WIDTH = 180;
    private static final int TEXT_COLOR = 0xFFFFFF;
    private static final int LYRIC_COLOR = 0xFFB8B8B8;
    private static final int REQUESTER_COLOR = 0xFF8A8A8A;
    private static final int PROGRESS_BG = 0x66333333;
    private static final int PROGRESS_FG = 0xFF6EC9FF;

    private MusicHudRenderer() {}

    public static boolean isVisible() {
        return MusicPlaybackState.current() != null;
    }

    /** 缩放后坐标系高度 */
    public static int measureHeight(Minecraft mc) {
        Font font = mc.font;
        int lines = 1 /*曲名*/ + 2 /*进度行+间距*/ + 1 /*歌词*/;
        List<MusicQueueSyncPayload.SongSummary> queue = MusicPlaybackState.queue();
        if (!queue.isEmpty()) {
            lines += 1 /*分隔行*/ + Math.min(queue.size(), MAX_QUEUE_ITEMS)
                    + (queue.size() > MAX_QUEUE_ITEMS ? 1 : 0);
        }
        return lines * LINE_HEIGHT + PADDING * 2;
    }

    /** 在缩放后坐标系 (X, startY) 渲染 */
    public static void renderAt(Minecraft mc, GuiGraphics guiGraphics, int startY, float scale) {
        MusicPlaybackState.PlayingInfo info = MusicPlaybackState.current();
        if (info == null) {
            return;
        }
        Font font = mc.font;
        List<MusicQueueSyncPayload.SongSummary> queue = MusicPlaybackState.queue();
        int width = measureWidth(font, info, queue);
        int height = measureHeight(mc);

        guiGraphics.pose().pushPose();
        guiGraphics.pose().scale(scale, scale, 1.0F);

        firefly520.fireflymc.client.HudRenderUtil.drawRoundedBorder(guiGraphics, X, startY, width, height);

        int y = startY + PADDING;
        // 1. 曲名 - 歌手（超宽滚动跑马灯）
        String title = info.title() + " - " + info.author();
        drawMarquee(guiGraphics, font, title, X + PADDING, y, width - PADDING * 2, TEXT_COLOR);
        y += LINE_HEIGHT + 2;

        // 2. 进度条 + 时间
        long pos = info.clock().positionMs();
        long total = Math.max(info.durationMs(), 1);
        float ratio = Math.min(1.0f, pos / (float) total);
        int barWidth = width - PADDING * 2;
        guiGraphics.fill(X + PADDING, y, X + PADDING + barWidth, y + PROGRESS_HEIGHT, PROGRESS_BG);
        guiGraphics.fill(X + PADDING, y, X + PADDING + (int)(barWidth * ratio), y + PROGRESS_HEIGHT, PROGRESS_FG);
        y += PROGRESS_HEIGHT + 2;
        String time = formatTime(pos) + " / " + formatTime(total);
        guiGraphics.drawString(font, time, X + PADDING, y, REQUESTER_COLOR);
        y += LINE_HEIGHT;

        // 3. 当前歌词行（无歌词整行不渲染）
        Optional<String> lyric = MusicPlaybackState.currentLyricLine();
        if (lyric.isPresent()) {
            drawClipped(guiGraphics, font, lyric.get(), X + PADDING, y, width - PADDING * 2, LYRIC_COLOR);
            y += LINE_HEIGHT;
        }

        // 4. 排队列表（最多 3 项 + "还有 N 首"）
        if (!queue.isEmpty()) {
            String header = "── 排队 (" + queue.size() + ") ──";
            guiGraphics.drawString(font, header, X + PADDING, y, REQUESTER_COLOR);
            y += LINE_HEIGHT;
            int shown = Math.min(queue.size(), MAX_QUEUE_ITEMS);
            for (int i = 0; i < shown; i++) {
                MusicQueueSyncPayload.SongSummary s = queue.get(i);
                // 截断优先级：歌名 > 歌手 > 点歌者（requester 最先被截断）
                String line = truncateWithPriority(font, (i + 1) + ". " + s.title(), s.author(),
                        " · " + s.requesterName(), width - PADDING * 2);
                guiGraphics.drawString(font, line, X + PADDING, y, TEXT_COLOR);
                y += LINE_HEIGHT;
            }
            if (queue.size() > MAX_QUEUE_ITEMS) {
                guiGraphics.drawString(font, "       还有 " + (queue.size() - MAX_QUEUE_ITEMS) + " 首",
                        X + PADDING, y, REQUESTER_COLOR);
            }
        }

        guiGraphics.pose().popPose();
    }

    private static int measureWidth(Font font, MusicPlaybackState.PlayingInfo info,
                                    List<MusicQueueSyncPayload.SongSummary> queue) {
        int w = font.width(info.title() + " - " + info.author());
        for (int i = 0; i < Math.min(queue.size(), MAX_QUEUE_ITEMS); i++) {
            MusicQueueSyncPayload.SongSummary s = queue.get(i);
            w = Math.max(w, font.width((i + 1) + ". " + s.title() + " - " + s.author() + " · " + s.requesterName()));
        }
        return Math.min(MAX_WIDTH, Math.max(w + PADDING * 2, 100));
    }

    /** 跑马灯：超宽时按时间循环滚动；不超宽直接绘制 */
    private static void drawMarquee(GuiGraphics g, Font font, String text, int x, int y, int maxWidth, int color) {
        int textWidth = font.width(text);
        if (textWidth <= maxWidth) {
            g.drawString(font, text, x, y, color);
            return;
        }
        long t = System.currentTimeMillis() / 30; // 滚动速度
        int scroll = (int)(t % (textWidth + 40));
        g.enableScissor(x, y, x + maxWidth, y + LINE_HEIGHT);
        int drawX = x - Math.max(0, scroll - 20);
        g.drawString(font, text, drawX, y, color);
        // 循环首尾相接
        if (scroll > 20) {
            g.drawString(font, text, drawX + textWidth + 40, y, color);
        }
        g.disableScissor();
    }

    private static void drawClipped(GuiGraphics g, Font font, String text, int x, int y, int maxWidth, int color) {
        String clipped = font.plainSubstrByWidth(text, maxWidth);
        g.drawString(font, clipped, x, y, color);
    }

    /** 截断优先级：歌名 > 歌手 > requester 最先被截断 */
    private static String truncateWithPriority(Font font, String prefixTitle, String author,
                                               String requesterSuffix, int maxWidth) {
        int w = font.width(prefixTitle + " - " + author + requesterSuffix);
        if (w <= maxWidth) {
            return prefixTitle + " - " + author + requesterSuffix;
        }
        // 先砍 requester
        String s = prefixTitle + " - " + author;
        if (font.width(s) <= maxWidth) {
            return s;
        }
        // 再砍 author（保留歌名）
        return font.plainSubstrByWidth(s, maxWidth);
    }

    private static String formatTime(long ms) {
        long totalSec = Math.max(0, ms / 1000);
        return String.format("%d:%02d", totalSec / 60, totalSec % 60);
    }
}
```

- [x] **Step 5: 编译 + 全量单测**

Run: `.\gradlew.bat compileJava test`
Expected: BUILD SUCCESSFUL，既有测试（LrcParser/PlaybackClock/Mp3DurationProbe/MusicCache/MusicPayloadCodec/MusicQueueManager/Ipv6ConnectivityChecker 等）全部 PASS

---

### Task 14: lang 文件 + 全量构建 + 手动集成测试矩阵

**Files:**
- Modify: `src/main/resources/assets/fireflymc/lang/zh_cn.json`
- Modify: `src/main/resources/assets/fireflymc/lang/en_us.json`

- [ ] **Step 1: zh_cn.json 追加 key**

```json
  "fireflymc.music.searching": "♪ 正在搜索: %s ...",
  "fireflymc.music.queued": "§a♪ 已点播: %s - %s",
  "fireflymc.music.announce": "§b♪ %s 点播了《%s》- %s",
  "fireflymc.music.queue.header": "§6===== 点歌队列 =====",
  "fireflymc.music.queue.empty": "队列为空",
  "fireflymc.music.queue.entry": "%d. %s - %s（点歌者: %s）",
  "fireflymc.music.skipped": "§e已跳过当前歌曲",
  "fireflymc.music.stopped": "§e已停止播放并清空队列",
  "fireflymc.music.error.locked": "§c你已点过一首歌，听完当前这首才能再点哦～",
  "fireflymc.music.error.pending": "§c正在处理你的上一个点歌请求，请稍等",
  "fireflymc.music.error.queue_full": "§c点歌队列已满（%d），请稍后再试",
  "fireflymc.music.error.not_found": "§c没有找到《%s》相关的可播放歌曲（付费歌曲无法点播）",
  "fireflymc.music.error.no_permission": "§c你没有权限执行此操作",
  "fireflymc.music.error.keyword_too_long": "§c歌名过长（上限 256 字符）",
  "fireflymc.music.error.player_only": "§c只有玩家可以点歌",
```

- [ ] **Step 2: en_us.json 追加对应英文**

```json
  "fireflymc.music.searching": "♪ Searching: %s ...",
  "fireflymc.music.queued": "§a♪ Requested: %s - %s",
  "fireflymc.music.announce": "§b♪ %s requested \"%s\" - %s",
  "fireflymc.music.queue.header": "§6===== Music Queue =====",
  "fireflymc.music.queue.empty": "Queue is empty",
  "fireflymc.music.queue.entry": "%d. %s - %s (by %s)",
  "fireflymc.music.skipped": "§eSkipped current song",
  "fireflymc.music.stopped": "§eStopped and cleared the queue",
  "fireflymc.music.error.locked": "§cYou already requested a song. Listen to it fully before requesting again.",
  "fireflymc.music.error.pending": "§cYour previous request is still being processed, please wait",
  "fireflymc.music.error.queue_full": "§cMusic queue is full (%d), try again later",
  "fireflymc.music.error.not_found": "§cNo playable result for \"%s\" (paid songs unavailable)",
  "fireflymc.music.error.no_permission": "§cYou don't have permission for this action",
  "fireflymc.music.error.keyword_too_long": "§cKeyword too long (max 256 chars)",
  "fireflymc.music.error.player_only": "§cOnly players can request songs",
```

- [ ] **Step 3: 全量构建**

Run: `.\gradlew.bat build`
Expected: BUILD SUCCESSFUL；jar 内含 jarJar 的 `jlayer-1.0.1.jar`

- [ ] **Step 4: 手动集成测试矩阵（runClient / runServer，逐项勾选）**

**单人（runClient）：**
- [ ] `/点歌 起风了` → 几秒内开始播放；聊天栏出现搜索与点播消息
- [ ] HUD 左侧卡片出现：曲名-歌手、进度条走动、时间 `m:ss / m:ss`、歌词行滚动
- [ ] 连续 `/点歌 稻香`（无需等待，单人无限）→ 第一首未完时第二首排队；`/fireflymc music queue` 显示队列；HUD 显示排队 1 项
- [ ] 第一首自然结束后 2s 内自动切第二首
- [ ] 同一首歌第二次点 → 从缓存秒播（查 `run/music-cache/` 目录）
- [ ] F1 隐藏 HUD；音乐音量滑条调 0 → 点歌静音、调 1 → 正常（MASTER 与 MUSIC 两个滑条都试）
- [ ] `/fireflymc music skip`、`/fireflymc music stop` 正常
- [ ] 点付费歌曲（如 `/点歌 晴天 周杰伦`）→ 提示未找到（预期行为）

**LAN（两个客户端：一个开 LAN，一个直连）：**
- [ ] 房主无限点歌；访客点一首成功后再点 → 提示 locked
- [ ] 访客的歌播完 → 访客解锁可再点
- [ ] 访客中途加入（退出重进）→ 自动收到当前曲并从头附近进度继续播
- [ ] 访客无权限执行 skip/stop → 提示无权限；房主可以

**服务器（runServer + 1 客户端）：**
- [ ] OP（`/op 自己`）无限点歌
- [ ] 非 OP 点一首 → 锁定；skip 由 OP 执行后该玩家解锁
- [ ] `/fireflymc music stop` 清空队列（含 pending：点歌后 15s 内立刻 stop，旧结果不复活）

**异常路径：**
- [ ] 断网状态点歌 → 15s 超时后收到"未找到"提示，玩家未被锁（可再点）
- [ ] `music-cache/` 手动放入半截 `.part` → 重启后不命中缓存
- [ ] 静音降级（可临时改 `JavaSoundOutput.tryOpen` 首行 `return null;` 验证）：不出声、HUD 照常走完整时长、结束后正常切歌

- [ ] **Step 5: 提交点（等待用户指令）**

全部矩阵通过后**通知用户**，由用户决定提交。**执行者不得自主 `git commit`。**

---

## Self-Review 记录

- **Spec 覆盖**：§2 音源（Task 8）、§3 服务端（Task 7/9/10）、§4 协议（Task 6/9）、§5 客户端（Task 3/5/11/12）、§6 HUD（Task 13）、§7 命令（Task 10）、§8 构建（Task 1）、§10 测试（各 Task + Task 14 矩阵）——全覆盖
- **用户批准时的两条注意事项已落实**：epoch 校验 + 队列上限回检（Task 7 `completeRequest`）；`nextPlaybackId` 从 1 起恒 >0（Task 7 字段注释）
- **类型一致性**：`MusicQueueManager.CapabilityLookup`（isCapable + capableOnlineCount）在 Task 7 定义、Task 9 MusicServerBridge 匿名实现——一致；`renderAt(GuiGraphics, int)` 签名在 Task 13 ClientHandler/HUDRenderer/MusicHudRenderer 间一致（MusicHudRenderer 因需 scale 额外传参，已在落盘说明标注统一坐标系处理）
- **已知实现自由度**：Task 7 Step 4 的初稿权宜结构（IntSupplierHolder）已标注最终修正版；Task 11 的 `cancel()` 未接线问题已标注修正方案；Task 13 HUDRenderer 重构给了精确行号指引而非全文（避免复制 200+ 行原代码）
- **有意简化**：设计 §5.6"客户端本地探测时长作 fallback"在计划中不实现 HUD 路径——`MusicStartPayload.durationMs` 恒有值（探测失败时为 240s fallback），HUD 始终以服务端值为准（与服务端切歌时钟一致是首要目标）；客户端自检/调试场景留待日后需要时再加，符合 YAGNI
