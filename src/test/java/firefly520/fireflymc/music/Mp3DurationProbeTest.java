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
        ByteArrayOutputStream xingOut = new ByteArrayOutputStream();
        xingOut.writeBytes("Xing".getBytes(StandardCharsets.US_ASCII));
        xingOut.writeBytes(new byte[]{0, 0, 0, 1});                       // flags: frames
        xingOut.writeBytes(new byte[]{0, 0, (byte) 0x0B, 0x04});          // 2820 帧
        xingOut.writeBytes(new byte[24]);                                 // 补齐到 ≥64B（模拟真实 64KB 探测数据）
        byte[] head = mp3Head(32, xingOut.toByteArray());
        // duration = 2820 * 1152 * 1000 / 44100 = 73665ms（整数除法）
        long duration = Mp3DurationProbe.probeDurationMs(head, 4_738_291L);
        assertEquals(73665L, duration, 2L);
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
