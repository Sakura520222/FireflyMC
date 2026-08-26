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

    /** 构造带 ID3v2 头（syncsafe 尺寸）的数据 */
    private byte[] withId3(int tagBodySize, byte[] tagBody, byte[] afterTag) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("ID3".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[]{4, 0});                                 // version 2.4
        out.write(0);                                                     // flags: 无 footer
        // syncsafe size：tagBodySize = b6<<21 | b7<<14 | b8<<7 | b9
        out.write((tagBodySize >> 21) & 0x7F);
        out.write((tagBodySize >> 14) & 0x7F);
        out.write((tagBodySize >> 7) & 0x7F);
        out.write(tagBodySize & 0x7F);
        out.writeBytes(tagBody);
        out.writeBytes(afterTag);
        return out.toByteArray();
    }

    @Test
    void largeId3TagSkipped() {
        // 32KB ID3v2 标签（模拟嵌入封面超过旧 8KB 扫描窗口）后跟正常 CBR 帧
        byte[] head = withId3(32 * 1024, new byte[32 * 1024], mp3Head(64, null));
        long duration = Mp3DurationProbe.probeDurationMs(head, 4_738_291L);
        assertEquals(296143L, duration, 5L, "大 ID3 标签后的帧头必须仍被找到（不得 fallback）");
    }

    @Test
    void id3TagContentNotMistakenForFrameSync() {
        // 标签体内含 0xFFFB 伪帧同步字（封面 JPEG 数据常见）：必须按 ID3 尺寸整体跳过，
        // 不得误匹配伪帧（否则 Xing 解析错位退化为 CBR 估值）
        ByteArrayOutputStream xingOut = new ByteArrayOutputStream();
        xingOut.writeBytes("Xing".getBytes(StandardCharsets.US_ASCII));
        xingOut.writeBytes(new byte[]{0, 0, 0, 1});
        xingOut.writeBytes(new byte[]{0, 0, (byte) 0x0B, 0x04});          // 2820 帧
        xingOut.writeBytes(new byte[24]);
        byte[] tagBody = new byte[256];
        tagBody[0] = (byte) 0xFF;
        tagBody[1] = (byte) 0xFB;
        tagBody[2] = (byte) 0x90;
        tagBody[3] = 0x00;
        byte[] realFrame = mp3Head(32, xingOut.toByteArray());
        byte[] head = withId3(tagBody.length, tagBody, realFrame);
        // 误匹配伪帧会得到 CBR 值（~296143），正确跳过得到 Xing 值（73665）
        long duration = Mp3DurationProbe.probeDurationMs(head, 4_738_291L);
        assertEquals(73665L, duration, 2L);
    }
}
