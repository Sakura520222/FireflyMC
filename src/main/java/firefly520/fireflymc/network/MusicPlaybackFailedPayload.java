package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端播放失败上报（信号而非权威，服务端按 quorum 聚合处理）
 */
public record MusicPlaybackFailedPayload(long playbackId, FailureCode failureCode) implements CustomPacketPayload {

    /** 受限枚举：不接受客户端任意字符串。本地降级（无设备/缓存失败）不在此列，不上报 */
    public enum FailureCode {
        HTTP_FAILED, SOURCE_UNAVAILABLE, STREAM_INTERRUPTED, MP3_DECODE_FAILED
    }

    public static final CustomPacketPayload.Type<MusicPlaybackFailedPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "music_playback_failed"));

    public static final StreamCodec<ByteBuf, MusicPlaybackFailedPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, MusicPlaybackFailedPayload::playbackId,
            ByteBufCodecs.VAR_INT, p -> p.failureCode.ordinal(),
            (playbackId, codeOrdinal) -> new MusicPlaybackFailedPayload(playbackId, FailureCode.values()[codeOrdinal])
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
