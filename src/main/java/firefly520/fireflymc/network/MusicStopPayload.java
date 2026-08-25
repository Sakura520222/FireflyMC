package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 停止播放。playbackId = 0 表示"无活动播放实例"（如 /stop 时无 currentSong）
 */
public record MusicStopPayload(long playbackId, Reason reason) implements CustomPacketPayload {

    public enum Reason {
        FINISHED, SKIPPED, FAILED, QUEUE_CLEARED
    }

    public static final CustomPacketPayload.Type<MusicStopPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "music_stop"));

    public static final StreamCodec<ByteBuf, MusicStopPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, MusicStopPayload::playbackId,
            ByteBufCodecs.VAR_INT, p -> p.reason.ordinal(),
            (playbackId, reasonOrdinal) -> new MusicStopPayload(playbackId, Reason.values()[reasonOrdinal])
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
