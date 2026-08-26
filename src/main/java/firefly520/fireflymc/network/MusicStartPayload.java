package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 开始播放：服务端广播；玩家登录时对当前曲单独发送（中途加入，positionMs 为已播毫秒）。
 * 8 个字段超出 composite 上限（6），手写编解码。
 */
public record MusicStartPayload(
        long playbackId,
        String songId,
        String title,
        String author,
        String lrc,
        String requesterName,
        long durationMs,
        long positionMs
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MusicStartPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "music_start"));

    /**
     * lrc 字段专用 codec：上限与 MusicApiClient 的歌词截断上限（256 KiB 字符）对齐。
     * 默认 STRING_UTF8 上限 32767 字符，超长歌词会在 encode 广播时抛异常导致整包发送失败。
     */
    private static final StreamCodec<ByteBuf, String> LRC_CODEC = ByteBufCodecs.stringUtf8(256 * 1024);

    public static final StreamCodec<ByteBuf, MusicStartPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public MusicStartPayload decode(ByteBuf buf) {
            return new MusicStartPayload(
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    LRC_CODEC.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf)
            );
        }

        @Override
        public void encode(ByteBuf buf, MusicStartPayload v) {
            ByteBufCodecs.VAR_LONG.encode(buf, v.playbackId);
            ByteBufCodecs.STRING_UTF8.encode(buf, v.songId);
            ByteBufCodecs.STRING_UTF8.encode(buf, v.title);
            ByteBufCodecs.STRING_UTF8.encode(buf, v.author);
            LRC_CODEC.encode(buf, v.lrc);
            ByteBufCodecs.STRING_UTF8.encode(buf, v.requesterName);
            ByteBufCodecs.VAR_LONG.encode(buf, v.durationMs);
            ByteBufCodecs.VAR_LONG.encode(buf, v.positionMs);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
