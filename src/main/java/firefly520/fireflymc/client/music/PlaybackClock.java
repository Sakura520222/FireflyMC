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
