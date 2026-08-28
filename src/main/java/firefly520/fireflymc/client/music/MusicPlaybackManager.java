package firefly520.fireflymc.client.music;

import firefly520.fireflymc.client.ClientMusicFailReporter;
import firefly520.fireflymc.network.MusicQueueSyncPayload;
import firefly520.fireflymc.network.MusicStartPayload;
import firefly520.fireflymc.network.MusicStopPayload;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 客户端播放生命周期权威（公开方法只在客户端主线程调用——payload handler 已 enqueueWork）。
 * 主线程段必须 O(1)：cancel 不同步做任何 I/O（后台虚拟线程关网络流）、LRC 后台解析、
 * 缓存 stale 清理由首个播放 worker 承担（实测首次点歌/切歌主线程卡顿修复）。
 * stop 完整序列：失效 playbackId → cancel（O(1)）→ interrupt；
 * 旧 worker 的任何写回先验证仍是当前 playbackId。
 */
public final class MusicPlaybackManager {

    /** MASTER×MUSIC 音量（tick 线程写，播放线程读） */
    private static final AtomicReference<Float> VOLUME = new AtomicReference<>(1.0f);

    /** 主线程段耗时观测阈值（微秒）：超过才记 WARN */
    private static final long SLOW_LOG_THRESHOLD_US = 2_000L;

    private static final MusicCache CACHE = MusicCache.createDefault();
    private static volatile MusicPlayer currentWorker;
    private static volatile Thread workerThread;
    private static volatile long currentPlaybackId = 0L; // 0=无实例

    private MusicPlaybackManager() {}

    /** 音量桥写入（ClientTick 每 tick 调用，不碰播放线程） */
    public static void setEffectiveVolume(float volume) {
        VOLUME.set(volume);
    }

    /** 收到 MusicStartPayload：无条件停旧曲再起新曲（服务端权威）。主线程段必须 O(1) */
    public static void start(MusicStartPayload payload) {
        long startNano = System.nanoTime();
        // 切歌竞争修复（Issue #64）：旧 worker 的 SourceDataLine 在旧线程 finally 才释放，
        // 新 worker 必须等旧 worker 完全退出再开音频设备，否则 tryOpen 竞争失败 → 整首静音。
        // 完成信号优先 + join 兜底；等待全部在新线程内完成，绝不阻塞 Minecraft 主线程。
        final MusicPlayer oldWorker = currentWorker;
        final Thread oldThread = workerThread;
        stopInternal(false);
        currentPlaybackId = payload.playbackId();

        // LRC 解析移出主线程（逐行 regex + TreeMap 插入，歌词大时可产生 5~15ms frame-time
        // spike——实测切歌卡顿源之一）：先以空歌词立即起播，后台解析完成后仅在
        // playbackId 仍匹配时原子替换；歌词晚几十毫秒出现不可察觉
        final long myId = payload.playbackId();

        // 初始以 Silent 时钟建立状态；player 成功打开 SourceDataLine 后经 ClockRef 替换为精确时钟
        MusicPlaybackState.ClockRef clockRef =
                new MusicPlaybackState.ClockRef(new PlaybackClock.Silent(payload.positionMs()));
        MusicPlaybackState.setPlaying(new MusicPlaybackState.PlayingInfo(
                payload.playbackId(), payload.songId(), payload.title(), payload.author(),
                payload.requesterName(), payload.durationMs(), new java.util.TreeMap<>(), clockRef));
        parseLrcAsync(myId, payload.lrc());
        MusicPlayer player = new MusicPlayer(
                myId, payload.songId(), payload.positionMs(), payload.durationMs(),
                VOLUME, CACHE, new MusicPlayer.Callbacks() {
                    @Override
                    public void onFinished(long playbackId, boolean success) {
                        Minecraft.getInstance().execute(() -> {
                            if (playbackId != currentPlaybackId) {
                                return; // 旧 worker 写回，忽略
                            }
                            // 自然结束：HUD 钉在终点，等下一个 Start/Stop
                            MusicPlaybackState.PlayingInfo info = MusicPlaybackState.current();
                            if (info != null) {
                                info.clock().set(new PlaybackClock.Silent(info.durationMs()));
                            }
                        });
                    }

                    @Override
                    public void onLocalFailure(long playbackId, MusicPlayer.LocalFailure code) {
                        Minecraft.getInstance().execute(() -> {
                            if (playbackId != currentPlaybackId) {
                                return; // 旧 worker 写回，忽略
                            }
                            // 上报信号（服务端 quorum 决定是否全服切歌）
                            ClientMusicFailReporter.report(playbackId, code);
                            // 本地切 Silent 时钟：从失败瞬间的位置无缝续走——
                            // JavaSound 时钟随 line 关闭会冻结，quorum 未达时 HUD/歌词
                            // 必须继续按服务端权威计时推进（设计 5.5 的静音降级语义）
                            MusicPlaybackState.PlayingInfo info = MusicPlaybackState.current();
                            if (info != null) {
                                info.clock().set(new PlaybackClock.Silent(info.clock().positionMs()));
                            }
                        });
                    }

                    @Override
                    public void onStreamRecovering(long playbackId, long positionMs) {
                        Minecraft.getInstance().execute(() -> {
                            if (playbackId != currentPlaybackId) {
                                return; // 旧 worker 写回，忽略
                            }
                            // 恢复期间仅 action bar 本地提示，不上报服务端（网络型失败不投全局失败票）
                            var p = Minecraft.getInstance().player;
                            if (p != null) {
                                p.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                                        "fireflymc.music.hud.recovering"), true);
                            }
                        });
                    }
                }, clockRef);
        Thread t = new Thread(() -> {
            // 连切场景：等待期间又收到新 Start → playbackId 已变，放弃本 worker。
            // abandon() 立即 countDown 完成信号：否则该 latch 永不释放，
            // 下一首会对着永不运行的 worker 白等 awaitExit(2s)（审查 #6）
            if (currentPlaybackId != myId) {
                player.abandon();
                return;
            }
            if (oldWorker != null) {
                boolean exited = false;
                try {
                    exited = oldWorker.awaitExit(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // 连切 interrupt：放弃等待，下方复查后退出
                }
                if (!exited && oldThread != null) {
                    try {
                        oldThread.join(500); // latch 超时兜底
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            if (currentPlaybackId != myId) {
                player.abandon(); // 再次复查失败：等待期间被切歌，放行完成信号后退出
                return;
            }
            player.run();
        }, "fireflymc-music-playback");
        t.setDaemon(true);
        currentWorker = player;
        workerThread = t;
        t.start();
        logSlow("start", startNano);
    }

    /** 后台解析歌词（virtual thread，不占主线程），完成后在 playbackId 仍匹配时原子替换 */
    private static void parseLrcAsync(long playbackId, String lrcText) {
        Thread.ofVirtual().name("fireflymc-music-lrc").start(() -> {
            long t0 = System.nanoTime();
            java.util.TreeMap<Long, String> parsed = LrcParser.parse(lrcText);
            long costUs = (System.nanoTime() - t0) / 1_000L;
            if (costUs > SLOW_LOG_THRESHOLD_US) {
                firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                        "[Music] LRC 后台解析耗时 {}μs lines={}", costUs, parsed.size());
            }
            Minecraft.getInstance().execute(() -> {
                if (playbackId != currentPlaybackId) {
                    return; // 已切歌：丢弃过期解析结果
                }
                MusicPlaybackState.PlayingInfo info = MusicPlaybackState.current();
                if (info != null && info.playbackId() == playbackId) {
                    // 原子替换歌词快照（clock 引用不变 → HUD 时钟连续）
                    MusicPlaybackState.setPlaying(new MusicPlaybackState.PlayingInfo(
                            info.playbackId(), info.songId(), info.title(), info.author(),
                            info.requesterName(), info.durationMs(), parsed, info.clock()));
                }
            });
        });
    }

    /** 收到 MusicStopPayload：停止并清空 HUD */
    public static void stop(MusicStopPayload payload) {
        stopInternal(true);
    }

    /** 客户端断开连接/退出世界：停止一切本地状态 */
    public static void shutdown() {
        stopInternal(true);
        MusicPlaybackState.clearPlaying();
        MusicPlaybackState.setQueue(List.of());
    }

    /** 队列同步（HUD 排队列表） */
    public static void onQueueSync(MusicQueueSyncPayload payload) {
        MusicPlaybackState.setQueue(payload.queue());
    }

    private static void stopInternal(boolean clearState) {
        long startNano = System.nanoTime();
        currentPlaybackId = 0L; // 失效 playbackId：旧 worker 写回全部失效
        MusicPlayer worker = currentWorker;
        currentWorker = null;
        if (worker != null) {
            // cancel() 为 O(1)：置取消 + 后台虚拟线程关流；文件资源由 worker finally 清理
            //（主线程同步 close 网络/文件句柄在 Windows 上可停顿数 ms~数十 ms——切歌卡顿修复）
            worker.cancel();
        }
        Thread t = workerThread;
        workerThread = null;
        if (t != null) {
            t.interrupt();
        }
        if (clearState) {
            MusicPlaybackState.clearPlaying();
        }
        logSlow("stopInternal", startNano);
    }

    /** 主线程段耗时观测（>2ms 才记 WARN，验证无残留阻塞 I/O；修复确认后可降 DEBUG/移除） */
    private static void logSlow(String op, long startNano) {
        long costUs = (System.nanoTime() - startNano) / 1_000L;
        if (costUs > SLOW_LOG_THRESHOLD_US) {
            firefly520.fireflymc.FireflyMCMod.LOGGER.warn(
                    "[Music] {} 主线程耗时 {}μs（阈值 {}μs，疑似残留阻塞 I/O）",
                    op, costUs, SLOW_LOG_THRESHOLD_US);
        }
    }
}
