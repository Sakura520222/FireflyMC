package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C 定向发包：服务端搜索失败（无法访问外网等），委托点歌者的客户端代为搜索。
 * 客户端完成搜索后经 {@link MusicSearchResultPayload} 回传——必须原样携带 proxyToken
 * （sessionId 单调可预测，无 token 绑定时改版客户端可伪造回包绕过服务端搜索与预检）。
 */
public record MusicProxySearchRequestPayload(long sessionId, long proxyToken, String keyword)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MusicProxySearchRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "music_proxy_search_request"));

    public static final StreamCodec<ByteBuf, MusicProxySearchRequestPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, MusicProxySearchRequestPayload::sessionId,
            ByteBufCodecs.VAR_LONG, MusicProxySearchRequestPayload::proxyToken,
            ByteBufCodecs.STRING_UTF8, MusicProxySearchRequestPayload::keyword,
            MusicProxySearchRequestPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
