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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 播放线程：缓存/HTTP 流 → JLayer 解码 → PCM →（音量乘法）→ SourceDataLine。
 * 边下边播（Tee 双写缓存）；中途加入 discard 快进；无输出设备静音降级。
 * 本线程绝不接触 Minecraft 对象（音量经 AtomicReference 传入）。
 */
public class MusicPlayer implements Runnable {

    /** 本地播放失败码（供 manager 上报） */
    public enum LocalFailure { NONE, HTTP_FAILED, MP3_DECODE_FAILED }

    public interface Callbacks {
        /** 解码循环自然结束（在播放线程调用，manager 自行切回主线程） */
        void onFinished(long playbackId, boolean success);

        /** 下载/解码失败（上报信号；quorum 由服务端决定） */
        void onLocalFailure(long playbackId, LocalFailure code);
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

    @Override
    public void run() {
        // 1. 数据源：缓存命中 → 本地；否则 outer url 流式下载（Tee 写 .part）
        Path localFile = cache.getCachedFile(songId).orElse(null);
        InputStream dataSource;
        if (localFile != null) {
            try {
                dataSource = new BufferedInputStream(Files.newInputStream(localFile), 65536);
            } catch (IOException e) {
                callbacks.onLocalFailure(playbackId, LocalFailure.HTTP_FAILED);
                return;
            }
        } else {
            // 网络环境对网易 CDN 存在间歇性连接超时（TUN/IPv6 抖动），重试最多 3 次
            boolean opened = false;
            for (int attempt = 1; attempt <= 3 && !cancelled; attempt++) {
                try {
                    HttpClient http = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(10))
                            // ALWAYS：outer url 302 到 http://m*.music.126.net（https→http 降级）
                            .followRedirects(HttpClient.Redirect.ALWAYS)
                            .build();
                    HttpRequest request = HttpRequest.newBuilder(URI.create(MusicApiClient.outerUrl(songId)))
                            .timeout(Duration.ofSeconds(20))
                            .header("User-Agent", MusicApiClient.OUTBOUND_UA)
                            .GET()
                            .build();
                    HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() == 200) {
                        httpStream = response.body();
                        opened = true;
                        break;
                    }
                    // 服务端明确拒绝（如 404 页）不重试，直接失败
                    firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                            "[Music] 音频请求失败 songId={} HTTP {}", songId, response.statusCode());
                    response.body().close();
                    break;
                } catch (Exception e) {
                    firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                            "[Music] 下载尝试 {}/3 失败 songId={}: {}", attempt, songId, String.valueOf(e));
                    closeQuietly(httpStream);
                    httpStream = null;
                    try {
                        Thread.sleep(500L * attempt); // 退避 0.5s/1s
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        cancelled = true;
                    }
                }
            }
            if (!opened) {
                if (!cancelled) {
                    callbacks.onLocalFailure(playbackId, LocalFailure.HTTP_FAILED);
                }
                return;
            }
            InputStream raw = httpStream;
            partFile = cache.beginPartFile(songId, playbackId);
            if (partFile != null) {
                try {
                    teeBranch = Files.newOutputStream(partFile);
                    raw = new TeeInputStream(httpStream, teeBranch);
                } catch (IOException e) {
                    cache.deletePartFile(partFile); // 磁盘异常：降级不缓存，播放照常
                    partFile = null;
                }
            }
            dataSource = new BufferedInputStream(raw, 65536);
        }

        // 2. 解码第一帧，确定采样参数
        Bitstream bitstream = new Bitstream(dataSource);
        JavaSoundOutput output = null;
        boolean success = false;
        boolean httpMode = (httpStream != null);
        try {
            Decoder decoder = new Decoder();
            Header header = bitstream.readFrame();
            if (header == null) {
                firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                        "[Music] 解码失败（非 MP3 数据，可能为付费歌曲的 404 页）songId={}", songId);
                callbacks.onLocalFailure(playbackId, LocalFailure.MP3_DECODE_FAILED);
                return;
            }
            int sampleRate = header.frequency();
            int channels = (header.mode() == Header.SINGLE_CHANNEL) ? 1 : 2;

            output = JavaSoundOutput.tryOpen(sampleRate, channels);
            if (output == null) {
                // 静音降级：无输出设备。不得跑高速解码循环（无 write 背压），
                // 由 Silent 时钟维持 HUD 进度，线程按权威时长等待
                success = waitSilent();
                if (success) {
                    callbacks.onFinished(playbackId, true);
                }
                return;
            }
            clockRef.set(output.clock(basePositionMs)); // 换精确 JavaSound 时钟

            // 3. discard 快进（中途加入）：解码并丢弃 positionMs 之前的 PCM
            boolean seeking = basePositionMs > 0;
            long discardedSamples = 0L;

            while (!cancelled) {
                SampleBuffer pcm = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                short[] samples = pcm.getBuffer();
                int len = pcm.getBufferLength();

                if (seeking) {
                    long elapsedMs = discardedSamples * 1000L / ((long) sampleRate * channels);
                    long frameMs = len * 1000L / ((long) sampleRate * channels);
                    if (elapsedMs + frameMs <= basePositionMs) {
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
                    break; // 流结束（自然播完）
                }
            }
            success = !cancelled;
        } catch (Exception e) {
            success = false;
            if (!cancelled) {
                firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                        "[Music] 解码中断 songId={}: {}", songId, String.valueOf(e));
                callbacks.onLocalFailure(playbackId, LocalFailure.MP3_DECODE_FAILED);
                return; // finally 仍会执行清理
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
            closeQuietly(teeBranch);
            // 缓存只对网络模式有意义且本次完整读完（header==null 正常 break）才落盘
            if (httpMode && success && partFile != null) {
                cache.finalizePartFile(partFile, songId);
            } else {
                cache.deletePartFile(partFile);
            }
        }
        if (!cancelled) {
            callbacks.onFinished(playbackId, success);
        }
    }

    /** 静音降级等待：按权威时长睡到结束（或被 cancel 唤醒）。不下载不解码。 */
    private boolean waitSilent() {
        long waited = 0L;
        try {
            while (!cancelled && waited < durationMs) {
                Thread.sleep(200L);
                waited += 200L;
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

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }
}
