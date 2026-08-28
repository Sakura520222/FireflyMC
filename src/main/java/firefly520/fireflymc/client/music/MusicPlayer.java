package firefly520.fireflymc.client.music;

import firefly520.fireflymc.music.MusicApiClient;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 播放线程：缓存/HTTP 流 → JLayer 解码 → PCM →（音量乘法）→ SourceDataLine。
 * 边下边播（Tee 双写缓存）；中途加入 discard 快进；无输出设备静音降级。
 * 网络断流（停滞/RST/字节级截断）经恢复循环重取 CDN 直链 + 快进到权威位置续播，
 * 恢复耗尽静音到曲终且不投全局失败票（Issue #64）。
 * 两个时钟语义严格分离：权威单调时钟（authPositionMs）只管"房间播到哪"（恢复点）；
 * 文件完整性只看 HTTP 字节（实收 vs Content-Length）——停滞期间权威时钟持续推进，
 * 拿它验证数据完整性会随卡顿时长失真；估算时长（240s fallback）同样不可靠。
 * 本线程绝不接触 Minecraft 对象（音量经 AtomicReference 传入）。
 */
public class MusicPlayer implements Runnable {

    /** 断流恢复尝试上限（含首次开播）：网络型失败不投全局失败票，静音到曲终 */
    private static final int STREAM_RECOVERY_ATTEMPTS = 3;
    /** 恢复 attempt 间退避基数（随 attempt 递增：0.5s/1s） */
    private static final long RETRY_BACKOFF_MS = 500L;

    /** 本地播放失败码（供 manager 上报；NETWORK/STREAM 属网络型，PERMANENT/DECODE 属音源型） */
    public enum LocalFailure { NONE, NETWORK_FAILED, STREAM_INTERRUPTED, HTTP_PERMANENT_FAILED, MP3_DECODE_FAILED }

    /** 单次流会话结果；RETRY 为网络型可重试（恢复点取权威时钟），其余为终态 */
    private enum Attempt { COMPLETED, CANCELLED, RETRY, OPEN_PERMANENT, DECODE_FAILED }

    public interface Callbacks {
        /** 解码循环自然结束（在播放线程调用，manager 自行切回主线程） */
        void onFinished(long playbackId, boolean success);

        /** 下载/解码失败（上报信号；quorum 由服务端决定） */
        void onLocalFailure(long playbackId, LocalFailure code);

        /** 断流恢复开始（播放线程直接调用）：客户端本地"正在重连"提示用，不上报服务端 */
        void onStreamRecovering(long playbackId, long positionMs);
    }

    private final long playbackId;
    private final String songId;
    private final long basePositionMs;
    private final long durationMs;
    private final AtomicReference<Float> volumeRef;
    private final MusicCache cache;
    private final Callbacks callbacks;
    private final MusicPlaybackState.ClockRef clockRef;

    private volatile boolean cancelled = false;
    private volatile InputStream httpStream;   // stop 序列 close 用
    private volatile Path partFile;
    private volatile OutputStream teeBranch;
    private volatile TeeInputStream tee;       // 落盘前检查缓存分支健康（磁盘满不 finalize 残缺 .part）

    // ---- 权威单调时钟（仅播放线程读写）：断流恢复的同步真值 ----
    private long authBaseMs;
    private long authStartNano;
    /** 最近一次成功打开的流协议（https/http；断流发生在 https 流上时下次恢复强制原始地址） */
    private String lastStreamScheme;
    /** https 流发生过断流：后续恢复 attempt 走 ORIGINAL 策略（原始 Location，多为 http） */
    private boolean httpsStalled;

    public MusicPlayer(long playbackId, String songId, long basePositionMs, long durationMs,
                       AtomicReference<Float> volumeRef,
                       MusicCache cache, Callbacks callbacks,
                       MusicPlaybackState.ClockRef clockRef) {
        this.playbackId = playbackId;
        this.songId = songId;
        this.basePositionMs = basePositionMs;
        this.durationMs = durationMs;
        this.volumeRef = volumeRef;
        this.cache = cache;
        this.callbacks = callbacks;
        this.clockRef = clockRef;
    }

    /** 旧 worker 完成信号：runPlayback 返回（line 已释放 + 资源清理）后 countDown（切歌竞争修复） */
    private final CountDownLatch exited = new CountDownLatch(1);

    @Override
    public void run() {
        try {
            runPlayback();
        } finally {
            exited.countDown(); // 所有退出路径（return/异常）都经此处发出完成信号
        }
    }

    /** 权威时钟锚定：以 base 为当前位置重新起算（起播/恢复起播瞬间调用） */
    private void resetAuthClock(long baseMs) {
        authBaseMs = baseMs;
        authStartNano = System.nanoTime();
    }

    /** 权威位置：房间同步意义的当前进度（停滞期间持续推进，与 line 时钟无关） */
    private long authPositionMs() {
        return authBaseMs + (System.nanoTime() - authStartNano) / 1_000_000L;
    }

    /** 播放主体。所有 return 路径都经 run() 的 finally 发出完成信号。 */
    private void runPlayback() {
        // 一次性 stale .part 清理由首个 worker 承担（主线程类初始化不做任何 I/O）
        cache.ensureInitialized();
        // 权威时钟从 payload 位置起算：打开流/快进的耗时也随真实时间推进，
        // 后续快进目标（liveTarget）自动追平这部分延迟
        resetAuthClock(basePositionMs);
        long positionMs = basePositionMs;

        // 缓存命中：本地文件优先；损坏（不可读/解码失败）→ 失效缓存并落入网络恢复流程，
        // 绝不能静默吞掉（否则下次仍命中同一坏文件，永久无声）
        Path localFile = cache.getCachedFile(songId).orElse(null);
        if (localFile != null) {
            Attempt result;
            try (InputStream in = new BufferedInputStream(Files.newInputStream(localFile), 65536)) {
                result = streamAttempt(in, positionMs, MusicApiClient.HttpSchemePolicy.PREFER_HTTPS);
            } catch (IOException e) {
                result = Attempt.RETRY; // 文件不可读视为缓存损坏
            }
            if (result == Attempt.COMPLETED || result == Attempt.CANCELLED) {
                reportFinal(result, positionMs);
                return;
            }
            cache.invalidate(songId);
            positionMs = clampResume(authPositionMs());
            // HUD 从权威位置续走（line 已关，JavaSound 时钟冻结）
            clockRef.set(new PlaybackClock.Silent(positionMs));
            callbacks.onStreamRecovering(playbackId, positionMs);
            firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                    "[Music] 本地缓存损坏已失效 songId={} positionMs={} → 重新下载", songId, positionMs);
        }

        // 网络模式：断流恢复循环（Issue #64）。每次 attempt 重新走 outer/url 拿 CDN 直链，
        // 从文件头重新下载 + discard 快进到权威位置恢复，最终 .part 仍能形成完整 MP3。
        for (int attempt = 1; attempt <= STREAM_RECOVERY_ATTEMPTS && !cancelled; attempt++) {
            // HTTPS 策略：https 流发生过断流 → 后续恢复强制走网易原始 Location（多为 http）。
            // 否则"HTTPS 每次都能建连但播放中途稳定卡死"时 3 次恢复会全走同一条坏链路
            MusicApiClient.HttpSchemePolicy policy = (attempt > 1 && httpsStalled)
                    ? MusicApiClient.HttpSchemePolicy.ORIGINAL
                    : MusicApiClient.HttpSchemePolicy.PREFER_HTTPS;
            Attempt result = streamAttempt(null, positionMs, policy);
            if (result == Attempt.RETRY) {
                // 恢复点 = 权威时钟当前位置（停滞期间持续推进，与房间同步）
                long resume = clampResume(authPositionMs());
                if (resume != positionMs) {
                    // HUD 时钟切回 Silent（JavaSound 时钟随 line 关闭冻结），
                    // 恢复下载/快进期间 HUD/歌词按真实时间无缝续走
                    clockRef.set(new PlaybackClock.Silent(resume));
                    callbacks.onStreamRecovering(playbackId, resume);
                    positionMs = resume;
                }
                if (attempt < STREAM_RECOVERY_ATTEMPTS) {
                    sleepQuietly(RETRY_BACKOFF_MS * attempt); // 0.5s/1s 退避
                }
                continue;
            }
            reportFinal(result, positionMs);
            return;
        }
        if (cancelled) {
            return; // 取消/切歌：无回调（耗尽日志对取消场景无意义）
        }
        // 恢复耗尽（网络型）：不再向服务端投失败票，HUD 已在 Silent 时钟续走，静音到曲终
        firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                "[Music] 断流恢复 {} 次仍失败 songId={} positionMs={} → 静音到曲终",
                STREAM_RECOVERY_ATTEMPTS, songId, positionMs);
        if (waitSilent(positionMs)) {
            callbacks.onFinished(playbackId, true);
        }
    }

    /** 终态回调：COMPLETED 正常收尾；PERMANENT/DECODE 为音源型失败（投 quorum 票）；
     *  网络型失败经恢复循环消化，不产生全局失败票 */
    private void reportFinal(Attempt result, long positionMs) {
        switch (result) {
            case COMPLETED -> callbacks.onFinished(playbackId, true);
            case OPEN_PERMANENT -> callbacks.onLocalFailure(playbackId, LocalFailure.HTTP_PERMANENT_FAILED);
            case DECODE_FAILED -> callbacks.onLocalFailure(playbackId, LocalFailure.MP3_DECODE_FAILED);
            case CANCELLED, RETRY -> {
            } // 取消无回调；RETRY 由恢复循环消化
        }
    }

    /**
     * 单次"打开流（网络模式）→ 解码 → 播放"会话。
     *
     * @param presetSource 非空 = 本地缓存文件流（无打开/无 tee/无 .part）；null = 网络模式
     * @param policy       CDN 直链协议策略（仅网络模式生效）
     * @return RETRY 表示网络型可重试（恢复点=权威时钟），其余为终态
     */
    private Attempt streamAttempt(InputStream presetSource, long positionMs,
                                  MusicApiClient.HttpSchemePolicy policy) {
        boolean localMode = presetSource != null;
        StallGuardInputStream guard = null;   // 网络模式的流看护（含字节计数）
        long expectedBytes = -1L;             // Content-Length；-1 = 无法证明完整性
        InputStream dataSource;
        if (localMode) {
            dataSource = presetSource;
        } else {
            // 打开流（单次；重试与退避由外层恢复循环统一承担）
            HttpResponse<InputStream> response;
            try {
                response = MusicApiClient.openAudioStream(songId, null, policy);
            } catch (Exception e) {
                firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                        "[Music] 打开音频流失败 songId={}: {}", songId, String.valueOf(e));
                return Attempt.RETRY;
            }
            int code = response.statusCode();
            if (code != 200) {
                firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                        "[Music] 音频请求失败 songId={} HTTP {}", songId, code);
                closeQuietly(response.body());
                if (code == 404 || code == 410) {
                    return Attempt.OPEN_PERMANENT; // 确定性不可播：投音源失败票
                }
                return Attempt.RETRY; // 403/408/429/5xx 等瞬态
            }
            lastStreamScheme = response.uri().getScheme();
            // 字节级完整性判断的基准：Content-Length 缺失（chunked 响应）时无法证明完整，
            // 本次可正常播放但数据绝不落盘
            expectedBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            // 闲置看护：头后停滞由 watchdog 关闭流走恢复链路（cancel 也经此关闭）
            guard = new StallGuardInputStream(response.body());
            httpStream = guard;

            // Tee 双写缓存（失败 attempt 的 .part 已删除，createFile 不会冲突）
            InputStream raw = httpStream;
            tee = null;
            partFile = cache.beginPartFile(songId, playbackId);
            if (partFile != null) {
                try {
                    teeBranch = Files.newOutputStream(partFile);
                    tee = new TeeInputStream(httpStream, teeBranch);
                    raw = tee;
                } catch (IOException e) {
                    cache.deletePartFile(partFile); // 磁盘异常：降级不缓存，播放照常
                    partFile = null;
                }
            }
            dataSource = new BufferedInputStream(raw, 65536);
        }

        Bitstream bitstream = new Bitstream(dataSource);
        JavaSoundOutput output = null;
        boolean completed = false;      // 走到 EOF（自然播完）
        boolean truncated = false;      // 字节级证明截断：实收 < Content-Length（判定必须在 finally 落盘之前）
        // 静音降级不消费流到 EOF：.part 只有第一帧预读字节，绝不能落盘为有效缓存
        // （否则下次播放命中损坏缓存导致腰斩/解码失败）
        boolean silentDegraded = false;
        try {
            Decoder decoder = new Decoder();
            Header header = bitstream.readFrame();
            if (header == null) {
                firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                        "[Music] 解码失败（非 MP3 数据，可能为付费歌曲的 404 页）songId={}", songId);
                return Attempt.DECODE_FAILED;
            }
            int sampleRate = header.frequency();
            int channels = (header.mode() == Header.SINGLE_CHANNEL) ? 1 : 2;

            output = JavaSoundOutput.tryOpen(sampleRate, channels);
            if (output == null) {
                // 静音降级：无输出设备。不得跑高速解码循环（无 write 背压），
                // 由 Silent 时钟维持 HUD 进度，线程按权威时长等待
                silentDegraded = true;
                return waitSilent(positionMs) ? Attempt.COMPLETED : Attempt.CANCELLED;
            }

            // discard 快进（中途加入/断流恢复）：解码并丢弃目标位置之前的 PCM。
            // 目标取权威时钟实时值（liveTarget）：打开流/快进自身的耗时也被追平，
            // 恢复后音频从"房间当前进度"起播，而非落后于服务端
            boolean seeking = Math.max(positionMs, authPositionMs()) > 0;
            long discardedSamples = 0L;

            while (!cancelled) {
                SampleBuffer pcm = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                short[] samples = pcm.getBuffer();
                int len = pcm.getBufferLength();

                if (seeking) {
                    long elapsedMs = discardedSamples * 1000L / ((long) sampleRate * channels);
                    long frameMs = len * 1000L / ((long) sampleRate * channels);
                    if (elapsedMs + frameMs <= Math.max(positionMs, authPositionMs())) {
                        discardedSamples += len;
                        bitstream.closeFrame();
                        header = bitstream.readFrame();
                        if (header == null) {
                            completed = true; // 快进直达 EOF：流被完整消费（截断与否交给字节完整性判定）
                            break;
                        }
                        continue;
                    }
                    // 起播瞬间：权威时钟重锚定到实际起播位置；HUD 换精确 JavaSound 时钟
                    seeking = false;
                    resetAuthClock(elapsedMs);
                    clockRef.set(output.clock(elapsedMs));
                }

                applyVolume(samples, len);
                output.writePcm(samples, 0, len);
                bitstream.closeFrame();
                header = bitstream.readFrame();
                if (header == null) {
                    completed = true; // 流结束（自然播完）
                    break;
                }
            }
            // 截断判定必须在 finally（缓存落盘）之前完成，且只看字节不看时长：
            // 实收 < Content-Length = CDN 平静截断。authPositionMs 在停滞期间持续推进、
            // 240s fallback 的估算时长都会失真，均不可作为完整性依据
            if (completed && !localMode && expectedBytes > 0 && guard.bytesRead() < expectedBytes) {
                truncated = true;
                // 平静截断同样说明该 https 链路不可靠：下次恢复强制原始地址
                markHttpsStalled();
            }
        } catch (Exception e) {
            if (cancelled) {
                return Attempt.CANCELLED;
            }
            // stall（看护关流）= 明确的网络型断流。JLayer 把底层 IO 错误包装为
            // BitstreamException（不抛裸 IOException），但同一异常也可能是
            // "字节已完整、MP3 数据本身损坏"——HTTP 完整性与解码有效性是两个维度，
            // 用字节信息区分：字节不完整/未知 → 网络原因仍有可能，重试；
            // 字节已完整仍解码失败 → 音源损坏，投 quorum 票（全服跳过坏音源）。
            // 本地坏缓存（localMode，expectedBytes=-1）同样归 RETRY → invalidate 重下
            boolean stalled = guard != null && guard.isTripped();
            boolean byteIncomplete = expectedBytes > 0 && guard != null && guard.bytesRead() < expectedBytes;
            firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                    "[Music] 流中断 songId={} stalled={} byteIncomplete={} received={}/{} err={}",
                    songId, stalled, byteIncomplete,
                    guard == null ? -1L : guard.bytesRead(), expectedBytes, String.valueOf(e));
            if (stalled) {
                // 恢复点 = 权威时钟当前位置：停滞 30s 期间它持续推进，恢复后与房间同步；
                // line 时钟早已冻结，不能作为恢复点（会落后服务端数十秒）
                markHttpsStalled();
                return Attempt.RETRY;
            }
            if (e instanceof javazoom.jl.decoder.BitstreamException) {
                if (expectedBytes <= 0 || byteIncomplete) {
                    markHttpsStalled();
                    return Attempt.RETRY; // 字节不完整/未知：网络原因仍有可能
                }
                return Attempt.DECODE_FAILED; // 字节完整仍解码失败：音源本身损坏
            }
            return Attempt.DECODE_FAILED;
        } finally {
            if (output != null) {
                // completed 即自然播完：drain 排空 line 内缓冲，尾音不截断；取消/失败 flush 丢弃
                output.stopAndClose(completed);
            }
            try {
                bitstream.close();
            } catch (Exception ignored) {
            }
            closeQuietly(dataSource);
            closeQuietly(teeBranch);
            // 缓存只在"字节级证明完整"时落盘（实收 ≥ Content-Length）；无 Content-Length
            // 的响应可正常播放但无法证明完整，同样不落盘。失败 attempt 的 .part 一律删除，
            // 保证下一 attempt 的 beginPartFile（Files.createFile 语义）不冲突
            boolean byteVerified = expectedBytes > 0 && guard != null && guard.bytesRead() >= expectedBytes;
            boolean validComplete = completed && !truncated && byteVerified && !silentDegraded;
            if (!localMode && validComplete && partFile != null
                    && (tee == null || !tee.isBranchBroken())) {
                cache.finalizePartFile(partFile, songId);
            } else {
                cache.deletePartFile(partFile);
            }
            // 字段复位：流已关，cancel() 与下一 attempt 不再重复处理
            teeBranch = null;
            tee = null;
            partFile = null;
            closeQuietly(httpStream);
            httpStream = null;
        }
        if (truncated) {
            firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                    "[Music] 流被截断 songId={} received={}/{} bytes → 恢复",
                    songId, guard.bytesRead(), expectedBytes);
            return Attempt.RETRY;
        }
        return completed ? Attempt.COMPLETED : Attempt.CANCELLED;
    }

    /** 当前流为 https 时标记链路不可靠：后续恢复 attempt 强制原始 Location（多为 http） */
    private void markHttpsStalled() {
        if ("https".equals(lastStreamScheme)) {
            httpsStalled = true;
        }
    }

    /** 恢复位置 clamp：[0, durationMs] */
    private long clampResume(long positionMs) {
        return Math.max(0L, Math.min(durationMs, positionMs));
    }

    /** 可中断退避：打断即置取消（退避期间被切歌 → 下一 attempt 检测 cancelled 直接退出） */
    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelled = true;
        }
    }

    /** 静音降级等待：从 positionMs 按权威时长睡到曲终（或被 cancel 唤醒）。不下载不解码 */
    private boolean waitSilent(long positionMs) {
        long pos = Math.max(0L, positionMs);
        try {
            while (!cancelled && pos < durationMs) {
                Thread.sleep(200L);
                pos += 200L;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !cancelled;
    }

    /** 音量乘法：读 AtomicReference 纯数值，对 PCM 样本乘系数（MASTER×MUSIC 由 tick 线程算好） */
    private void applyVolume(short[] samples, int length) {
        float volume = volumeRef.get();
        if (volume >= 0.999f) {
            return;
        }
        if (volume <= 0.001f) {
            Arrays.fill(samples, 0, length, (short) 0);
            return;
        }
        for (int i = 0; i < length; i++) {
            int v = Math.round(samples[i] * volume);
            samples[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
        }
    }

    /**
     * stop 序列（MC 主线程调用，必须 O(1)）：置取消 + 后台虚拟线程关网络流
     * （解除阻塞在 CDN read 上的 worker）。主线程不做任何同步 I/O——close 网络/文件句柄
     * 在 Windows 上（Defender 扫描、句柄清理）可停顿数 ms~数十 ms（实测切歌卡顿源）。
     * teeBranch/.part 由 worker 的 finally 必然关闭/删除（cancelled → completed=false
     * → delete 分支）；此处与 finally 可能并发 close 同一流，底层实现幂等，竞态无害。
     */
    public void cancel() {
        cancelled = true;
        InputStream stream = httpStream;
        if (stream != null) {
            Thread.ofVirtual().name("fireflymc-music-stream-close").start(() -> closeQuietly(stream));
        }
    }

    /** 包装线程放弃尚未启动的 worker（连切 stale 检查路径）：置取消并立即放行完成信号，
     *  避免 next worker 对着一个永不 countDown 的 latch 白等 2s */
    void abandon() {
        cancelled = true;
        exited.countDown();
    }

    /** 完成信号等待：runPlayback 返回（line 已释放）后返回 true；超时/打断返回 false */
    boolean awaitExit(long timeoutMs) throws InterruptedException {
        return exited.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
