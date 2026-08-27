package firefly520.fireflymc.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MusicPayloadCodecTest {

    private <T> T roundTrip(net.minecraft.network.codec.StreamCodec<ByteBuf, T> codec, T value) {
        ByteBuf buf = Unpooled.buffer();
        codec.encode(buf, value);
        T decoded = codec.decode(buf);
        buf.release();
        return decoded;
    }

    @Test
    void startPayloadRoundTrip() {
        MusicStartPayload p = new MusicStartPayload(7L, "1330348068", "起风了",
                "买辣椒也用券", "[00:01.00]test", "Firefly", 243000L, 151000L);
        assertEquals(p, roundTrip(MusicStartPayload.STREAM_CODEC, p));
    }

    @Test
    void startPayloadLargeLrcRoundTrip() {
        // 超过默认 STRING_UTF8 上限（32767 字符）的长歌词：lrc 专用 codec 必须装得下，
        // 否则入队成功但广播 encode 抛异常，整包发送失败
        String lrc = "[00:00.00]x\n" + "a".repeat(40_000);
        MusicStartPayload p = new MusicStartPayload(1L, "1", "t", "a", lrc, "R", 1000L, 0L);
        assertEquals(p, roundTrip(MusicStartPayload.STREAM_CODEC, p));
    }

    @Test
    void queueSyncRoundTrip() {
        MusicQueueSyncPayload p = new MusicQueueSyncPayload(
                new MusicQueueSyncPayload.SongSummary("起风了", "买辣椒也用券", "Firefly"),
                List.of(new MusicQueueSyncPayload.SongSummary("稻香", "周杰伦", "Alice")));
        assertEquals(p, roundTrip(MusicQueueSyncPayload.STREAM_CODEC, p));
    }

    @Test
    void queueSyncEmptyQueueAllowed() {
        MusicQueueSyncPayload p = new MusicQueueSyncPayload(null, List.of());
        assertEquals(p, roundTrip(MusicQueueSyncPayload.STREAM_CODEC, p));
    }

    @Test
    void stopPayloadRoundTrip() {
        MusicStopPayload p = new MusicStopPayload(0L, MusicStopPayload.Reason.QUEUE_CLEARED);
        assertEquals(p, roundTrip(MusicStopPayload.STREAM_CODEC, p));
    }

    @Test
    void failedPayloadRoundTrip() {
        MusicPlaybackFailedPayload p = new MusicPlaybackFailedPayload(7L,
                MusicPlaybackFailedPayload.FailureCode.MP3_DECODE_FAILED);
        assertEquals(p, roundTrip(MusicPlaybackFailedPayload.STREAM_CODEC, p));
    }

    @Test
    void failedPayloadRejectsOutOfRangeOrdinal() {
        // 恶意/损坏包的越界枚举序号：必须以 DecoderException 拒绝（可预期的协议错误），
        // 而非裸 ArrayIndexOutOfBoundsException
        ByteBuf buf = Unpooled.buffer();
        net.minecraft.network.codec.ByteBufCodecs.VAR_LONG.encode(buf, 1L);
        net.minecraft.network.codec.ByteBufCodecs.VAR_INT.encode(buf, 999);
        assertThrows(io.netty.handler.codec.DecoderException.class,
                () -> MusicPlaybackFailedPayload.STREAM_CODEC.decode(buf));
        buf.release();

        ByteBuf neg = Unpooled.buffer();
        net.minecraft.network.codec.ByteBufCodecs.VAR_LONG.encode(neg, 1L);
        net.minecraft.network.codec.ByteBufCodecs.VAR_INT.encode(neg, -1);
        assertThrows(io.netty.handler.codec.DecoderException.class,
                () -> MusicPlaybackFailedPayload.STREAM_CODEC.decode(neg));
        neg.release();
    }

    @Test
    void stopPayloadRejectsOutOfRangeOrdinal() {
        ByteBuf buf = Unpooled.buffer();
        net.minecraft.network.codec.ByteBufCodecs.VAR_LONG.encode(buf, 0L);
        net.minecraft.network.codec.ByteBufCodecs.VAR_INT.encode(buf, 42);
        assertThrows(io.netty.handler.codec.DecoderException.class,
                () -> MusicStopPayload.STREAM_CODEC.decode(buf));
        buf.release();
    }

    @Test
    void proxySearchRequestRoundTrip() {
        MusicProxySearchRequestPayload p = new MusicProxySearchRequestPayload(42L, 987654321L, "起风了");
        assertEquals(p, roundTrip(MusicProxySearchRequestPayload.STREAM_CODEC, p));
    }

    @Test
    void searchResultRoundTrip() {
        // 含超长歌词 + 中文 keyword 的完整回包（手写 codec，8 字段）
        String lrc = "[00:00.00]x\n" + "a".repeat(40_000);
        MusicSearchResultPayload p = new MusicSearchResultPayload(
                7L, 987654321L, "1330348068", "起风了", "买辣椒也用券", lrc, 243_000L, "点歌 关键词");
        assertEquals(p, roundTrip(MusicSearchResultPayload.STREAM_CODEC, p));
    }

    @Test
    void searchResultNotFoundEmptyFields() {
        // 未找到回包：songId 为空、其余字段空
        MusicSearchResultPayload p = new MusicSearchResultPayload(7L, 987654321L, "", "", "", "", 0L, "关键词");
        assertEquals(p, roundTrip(MusicSearchResultPayload.STREAM_CODEC, p));
    }
}
