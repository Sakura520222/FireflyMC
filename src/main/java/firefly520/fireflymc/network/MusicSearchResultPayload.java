package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S 客户端代搜索结果回传（服务端搜索失败的降级链路）。
 * songId 为空表示未找到或客户端搜索同样失败；其余字段为不可信输入，
 * 服务端必须校验 songId 纯数字并截断/clamp 各字段后再入队。
 */
public record MusicSearchResultPayload(
        long sessionId,
        long proxyToken,
        String songId,
        String title,
        String author,
        String lrc,
        long durationMs,
        String keyword
) implements CustomPacketPayload {

    /** 歌词专用大容量 codec（与 MusicStartPayload 一致）：默认 STRING_UTF8 上限 32767 不够 */
    private static final StreamCodec<ByteBuf, String> LRC_CODEC = ByteBufCodecs.stringUtf8(256 * 1024);

    public static final CustomPacketPayload.Type<MusicSearchResultPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "music_search_result"));

    /** 8 字段超出 composite 上限（6）：手写编解码 */
    public static final StreamCodec<ByteBuf, MusicSearchResultPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public MusicSearchResultPayload decode(ByteBuf buf) {
            return new MusicSearchResultPayload(
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    LRC_CODEC.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf)
            );
        }

        @Override
        public void encode(ByteBuf buf, MusicSearchResultPayload v) {
            ByteBufCodecs.VAR_LONG.encode(buf, v.sessionId);
            ByteBufCodecs.VAR_LONG.encode(buf, v.proxyToken);
            ByteBufCodecs.STRING_UTF8.encode(buf, v.songId);
            ByteBufCodecs.STRING_UTF8.encode(buf, v.title);
            ByteBufCodecs.STRING_UTF8.encode(buf, v.author);
            LRC_CODEC.encode(buf, v.lrc);
            ByteBufCodecs.VAR_LONG.encode(buf, v.durationMs);
            ByteBufCodecs.STRING_UTF8.encode(buf, v.keyword);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
