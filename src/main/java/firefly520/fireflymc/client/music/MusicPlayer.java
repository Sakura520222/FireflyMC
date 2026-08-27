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
 * 网络断流（停滞/RST/提前 EOF）经恢复循环重取 CDN 直链 + 快进到快照位置续播，
 * 恢复耗尽静音到曲终且不投全局失败票（Issue #64）。
 * 本线程绝不接触 Minecraft 对象（音量经 AtomicReference 传入）。
 */
public class MusicPlayer implements Runnable {

    /** 断流恢复尝试上限（含首次开播）：网络型失败不投全局失败票，静音到曲终 */
    private static final int STREAM_RECOVERY_ATTEMPTS = 3;
    /** 恢复 attempt 间退避基数（随 attempt 递增：0.5s/1s） */
    private static final long RETRY_BACKOFF_MS = 500L;
    /** 提前 EOF 截断容差：距权威时长结尾不足此值视为正常播完（时长探测误差保护） */
    private static final long EARLY_EOF_TOLERANCE_MS = 3_000L;

    /** 本地播放失败码（供 manager 上报；NETWORK/STREAM 属网络型，PERMANENT/DECODE 属音源型） */
    public enum LocalFailure { NONE, NETWORK_FAILED, STREAM_INTERRUPTED, HTTP_PERMANENT_FAILED, MP3_DECODE_FAILED }

    /** 单次流会话结果；RETRY 为网络型可重试（resumeAtMs 携带恢复位置），其余为终态 */
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
    /** 当前 attempt 的恢复位置快照（RETRY 时有效；仅播放线程读写，无需并发保护） */
    private long resumeAtMs = -1L;

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

    /** 播放主体。所有 return 路径都经 run() 的 finally 发出完成信号。 */
    private void runPlayback() {
        // 缓存命中：本地文件无断流概念，一次播放到底（不做提前 EOF 截断检测）
        Path localFile = cache.getCachedFile(songId).orElse(null);
        if (localFile != null) {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(localFile), 65536)) {
                reportFinal(streamAttempt(in, basePositionMs), basePositionMs);
            } catch (IOException e) {
                // 本地缓存读失败可删缓存重下，非音源本身问题 → 网络型
                callbacks.onLocalFailure(playbackId, LocalFailure.NETWORK_FAILED);
            }
            return;
        }

        // 网络模式：断流恢复循环（Issue #64）。每次 attempt 重新走 outer/url 拿 CDN 直链
        //（HTTPS/HTTP 重新决策 → 中途卡死场景自然回退原始 http），从文件头重新下载 +
        // discard 快进到快照位置恢复，最终 .part 仍能形成完整 MP3。
        long positionMs = basePositionMs;
        for (int attempt = 1; attempt <= STREAM_RECOVERY_ATTEMPTS && !cancelled; attempt++) {
            Attempt result = streamAttempt(null, positionMs);
            long resume = resumeAtMs;
            if (result == Attempt.RETRY) {
                if (resume != positionMs) {
                    // 真断流（有已播放位置）：HUD 时钟切回 Silent（JavaSound 时钟随 line
                    // 关闭冻结），恢复下载/快进期间 HUD/歌词按真实时间无缝续走
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
            case CANCELLED -> {
            } // 取消：无回调
            case RETRY -> {
            } // 不应到达（恢复循环内消化）
        }
    }

    /**
     * 单次"打开流（网络模式）→ 解码 → 播放"会话。
     *
     * @param presetSource 非空 = 本地缓存文件流（无打开/无 tee/无 .part）；null = 网络模式
     * @return RETRY 表示网络型可重试（resumeAtMs 携带恢复位置），其余为终态
     */
    private Attempt streamAttempt(InputStream presetSource, long positionMs) {
        boolean localMode = presetSource != null;
        InputStream dataSource;
        if (localMode) {
            dataSource = presetSource;
        } else {
            // 打开流（单次；重试与退避由外层恢复循环统一承担）。每次重新走 outer/url
            // 拿 CDN 直链，HTTPS 升级失败/中途卡死自然回退原始 http（Issue #64 #5）
            HttpResponse<InputStream> response;
            try {
                response = MusicApiClient.openAudioStream(songId, null);
            } catch (Exception e) {
                firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                        "[Music] 打开音频流失败 songId={}: {}", songId, String.valueOf(e));
                resumeAtMs = positionMs;
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
                resumeAtMs = positionMs;
                return Attempt.RETRY; // 403/408/429/5xx 等瞬态
            }
            // 闲置看护：头后停滞由 watchdog 关闭流走恢复链路（cancel 也经此关闭）
            httpStream = new StallGuardInputStream(response.body());

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
        // 静音降级不消费流到 EOF：.part 只有第一帧预读字节，绝不能落盘为有效缓存
        // （否则下次播放命中损坏缓存导致腰斩/解码失败）
        boolean silentDegraded = false;
        long finalPos = positionMs;
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
            clockRef.set(output.clock(positionMs)); // 换精确 JavaSound 时钟（恢复场景 base=快照位置）

            // discard 快进（中途加入/断流恢复）：解码并丢弃 positionMs 之前的 PCM
            boolean seeking = positionMs > 0;
            long discardedSamples = 0L;

            while (!cancelled) {
                SampleBuffer pcm = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                short[] samples = pcm.getBuffer();
                int len = pcm.getBufferLength();

                if (seeking) {
                    long elapsedMs = discardedSamples * 1000L / ((long) sampleRate * channels);
                    long frameMs = len * 1000L / ((long) sampleRate * channels);
                    if (elapsedMs + frameMs <= positionMs) {
                        discardedSamples += len;
                        bitstream.closeFrame();
                        header = bitstream.readFrame();
                        if (header == null) {
                            break;
                        }
                        continue;
                    }
                    seeking = false; // 越过 base 位置，开始正常播放
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
            if (!cancelled && output != null) {
                finalPos = clockRef.positionMs(); // line 关闭前快照（关闭后 frame 计数不可靠）
            }
        } catch (Exception e) {
            if (cancelled) {
                return Attempt.CANCELLED;
            }
            // 看护触发的停滞中断，与 JLayer 包装的流读取错误（BitstreamException——底层是
            // CDN RST/中途 EOF 等网络断流，JLayer 不抛裸 IOException）都属网络型可恢复断流；
            // 其余解码异常 = 数据损坏（音源型）。
            // 注意 guard 在字段 httpStream 里——dataSource 已被 BufferedInputStream 包一层，
            // instanceof dataSource 恒为 false
            boolean stalled = httpStream instanceof StallGuardInputStream guard && guard.isTripped();
            boolean streamBroken = stalled || e instanceof javazoom.jl.decoder.BitstreamException;
            firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                    "[Music] 流中断 songId={} streamBroken={} err={}", songId, streamBroken, String.valueOf(e));
            if (streamBroken) {
                // 异常发生时 line 尚在，时钟位置即已播放到的准确位置（clamp 到 [0, durationMs]）
                resumeAtMs = clampResume(clockRef.positionMs());
                return Attempt.RETRY;
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
            // 缓存只在完整播完（EOF）且缓存分支健康时落盘；失败 attempt 的 .part 一律删除，
            // 保证下一 attempt 的 beginPartFile（Files.createFile 语义）不冲突
            if (!localMode && completed && !silentDegraded && partFile != null
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
        // 提前 EOF 截断检测：距权威时长结尾还远就"自然结束"，多为 CDN 提前断流（无异常的截断）——
        // 按可恢复断流处理，避免半截音频被缓存落盘、被当成正常播完
        if (completed && !localMode && finalPos < durationMs - EARLY_EOF_TOLERANCE_MS) {
            firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                    "[Music] 流提前结束 songId={} positionMs={}/{}ms → 恢复", songId, finalPos, durationMs);
            resumeAtMs = clampResume(finalPos);
            return Attempt.RETRY;
        }
        return completed ? Attempt.COMPLETED : Attempt.CANCELLED;
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

    /** stop 序列：置取消 → close 网络流（解除 read 阻塞）→ 删 .part（interrupt 由 manager 调） */
    public void cancel() {
        cancelled = true;
        closeQuietly(httpStream);
        closeQuietly(teeBranch);
        cache.deletePartFile(partFile);
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
