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

    /** 受限枚举：不接受客户端任意字符串。本地降级（无设备/缓存失败）不在此列，不上报。
     *  NETWORK/STREAM 属网络型失败（客户端局部网络问题，服务端 quorum 忽略）；
     *  PERMANENT/DECODE 属音源型失败（音源本身不可播，参与 quorum）。 */
    public enum FailureCode {
        /** 瞬态网络失败（连接超时/IOException/408/429/5xx/403 等重试耗尽） */
        NETWORK_FAILED,
        /** 播放中断流（StallGuard 停滞、中途 read 异常） */
        STREAM_INTERRUPTED,
        /** 确定性不可播（404/410：付费、下架） */
        HTTP_PERMANENT_FAILED,
        /** MP3 数据解码失败（响应非音频内容） */
        MP3_DECODE_FAILED
    }

    public static final CustomPacketPayload.Type<MusicPlaybackFailedPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "music_playback_failed"));

    public static final StreamCodec<ByteBuf, MusicPlaybackFailedPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, MusicPlaybackFailedPayload::playbackId,
            ByteBufCodecs.VAR_INT, p -> p.failureCode.ordinal(),
            (playbackId, codeOrdinal) -> new MusicPlaybackFailedPayload(playbackId, codeFromOrdinal(codeOrdinal))
    );

    /** 序号范围校验：恶意/损坏包的越界序号以 DecoderException 拒绝（协议层标准处理），而非裸 AIOOBE */
    private static FailureCode codeFromOrdinal(int ordinal) {
        FailureCode[] values = FailureCode.values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new io.netty.handler.codec.DecoderException("failureCode 序号越界: " + ordinal);
        }
        return values[ordinal];
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
