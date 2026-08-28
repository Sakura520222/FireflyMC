package firefly520.fireflymc.client.music;

import firefly520.fireflymc.music.MusicApiClient;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 流式响应闲置看护：ofInputStream 的 request timeout 只保护到响应头到达，
 * 之后解码循环的阻塞 read 无任何超时——CDN 发完头后中途停滞会让播放线程冻结、
 * 音频耗尽缓冲后静音、HUD 时钟冻住直到服务端切歌。
 * 每次成功读取重排"闲置即关闭"定时器：停滞超过阈值由 watchdog 关闭底层流，
 * 解除阻塞 read，让播放线程走既有的 重试→FAILED 上报 链路。
 * tripped 标志供调用方区分"看护中断（STREAM_INTERRUPTED）"与"数据损坏（MP3_DECODE_FAILED）"。
 */
public class StallGuardInputStream extends FilterInputStream {

    /** 闲置阈值：正常 CDN 慢速 trickle 每 8KB 块间隔远小于此；纯停滞才触发 */
    static final long STALL_TIMEOUT_MS = 30_000L;

    private volatile boolean tripped = false;
    private volatile ScheduledFuture<?> pending;
    /** 成功读取的累计字节数（文件完整性判断用：实收 vs Content-Length） */
    private long bytesRead = 0L;
    /** 构造时固定：read() 每次重排沿用同一调度器与超时（注入测试才可确定性驱动） */
    private final ScheduledExecutorService scheduler;
    private final long stallTimeoutMs;

    public StallGuardInputStream(InputStream in) {
        this(in, MusicApiClient.readWatchdog(), STALL_TIMEOUT_MS);
    }

    /** 测试可注入：自定义调度器与（短）超时，确定性驱动到期场景 */
    StallGuardInputStream(InputStream in, ScheduledExecutorService scheduler, long stallTimeoutMs) {
        super(in);
        this.scheduler = scheduler;
        this.stallTimeoutMs = stallTimeoutMs;
        arm();
    }

    private void arm() {
        cancel();
        pending = scheduler.schedule(this::onStall, stallTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private void onStall() {
        tripped = true;
        try {
            in.close(); // 解除阻塞在底层 socket read 上的解码线程
        } catch (IOException ignored) {
        }
    }

    private void cancel() {
        ScheduledFuture<?> f = pending;
        if (f != null) {
            f.cancel(false);
            pending = null;
        }
    }

    /** 看护是否已触发关闭 */
    public boolean isTripped() {
        return tripped;
    }

    /** 成功读取的累计字节数（仅播放线程读写） */
    public long bytesRead() {
        return bytesRead;
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b >= 0) {
            bytesRead++;
            arm();
        } else {
            cancel();
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = in.read(b, off, len);
        if (n > 0) {
            bytesRead += n;
            arm();
        } else if (n < 0) {
            cancel();
        }
        // n == 0（len==0）：不重排也不取消
        return n;
    }

    @Override
    public void close() throws IOException {
        cancel();
        super.close();
    }
}
