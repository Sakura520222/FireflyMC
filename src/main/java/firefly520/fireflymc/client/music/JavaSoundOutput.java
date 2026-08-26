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

    /**
     * 关闭线路。
     * natural=true 自然播完：drain 排空内部缓冲（约 0.2s）再关，尾音完整；
     * natural=false 取消/切歌：flush 丢弃未播 PCM 立即关。
     */
    public void stopAndClose(boolean natural) {
        try {
            if (natural) {
                line.drain();
            } else {
                line.flush();
            }
            line.stop();
            line.close();
        } catch (Exception ignored) {
        }
    }
}
