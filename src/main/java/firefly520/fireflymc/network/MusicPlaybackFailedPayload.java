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
     *  PERMANENT/DECODE 属音源型失败（音源本身不可播，参与 quorum）。
     *  编码用显式 wireId（非 ordinal）：枚举重排不再改变旧值语义。wireId 特意与 3.0.1
     *  旧 ordinal 交叉对齐——LAN 集成服务器不走版本握手（版本 bump 挡不住），
     *  旧客户端的 0（HTTP_FAILED）/2（STREAM_INTERRUPTED）/3（DECODE）落到新端语义安全侧
     *  （网络型忽略/相同），10 为旧版不存在的全新值：旧→新绝不误读成 PERMANENT。 */
    public enum FailureCode {
        /** 瞬态网络失败（连接超时/IOException/408/429/5xx/403 等重试耗尽） */
        NETWORK_FAILED(0),
        /** 播放中断流（StallGuard 停滞、中途 read 异常） */
        STREAM_INTERRUPTED(2),
        /** 确定性不可播（404/410：付费、下架） */
        HTTP_PERMANENT_FAILED(10),
        /** MP3 数据解码失败（响应非音频内容） */
        MP3_DECODE_FAILED(3);

        /** 显式协议值 */
        public final int wireId;

        FailureCode(int wireId) {
            this.wireId = wireId;
        }
    }

    public static final CustomPacketPayload.Type<MusicPlaybackFailedPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "music_playback_failed"));

    public static final StreamCodec<ByteBuf, MusicPlaybackFailedPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, MusicPlaybackFailedPayload::playbackId,
            ByteBufCodecs.VAR_INT, p -> p.failureCode.wireId,
            (playbackId, wireId) -> new MusicPlaybackFailedPayload(playbackId, fromWireId(wireId))
    );

    /** wireId 校验：未知/恶意值以 DecoderException 拒绝（协议层标准处理），而非静默错配 */
    private static FailureCode fromWireId(int wireId) {
        for (FailureCode code : FailureCode.values()) {
            if (code.wireId == wireId) {
                return code;
            }
        }
        throw new io.netty.handler.codec.DecoderException("failureCode wireId 未知: " + wireId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
