package firefly520.fireflymc.music;

import java.nio.charset.StandardCharsets;

/**
 * MP3 头部时长解析（服务端时长探测 + 客户端自检共用）
 * 优先级：Xing/Info → VBRI → CBR 估算（首帧 bitrate + 文件总大小）→ fallback
 */
public final class Mp3DurationProbe {

    /** 解析失败时的保守默认时长（探测三连败才使用；时长缓存使重复点播不触发） */
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
