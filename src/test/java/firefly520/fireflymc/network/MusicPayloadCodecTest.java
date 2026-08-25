package firefly520.fireflymc.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
