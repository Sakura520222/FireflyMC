package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端→客户端：跨级聊天代发请求。
 * <p>
 * 服务端没有连接云端的 WebSocket（跨级聊天的 WS 在客户端侧），因此由服务端发起或转发的
 * 聊天内容（如 AI 回复、{@code /ai} 命令的玩家消息）无法直接上行到 QQ 群。本 payload 携带
 * 发送者名称与消息文本，交给触发者客户端以 {@code player_chat} 类型代为上行到云端。
 *
 * @param senderName 发送者显示名（AI 回复时为 AI 名；玩家命令消息时为玩家名）
 * @param message    消息正文
 */
public record CrossChatRelayPayload(String senderName, String message) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CrossChatRelayPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "cross_chat_relay"));

    public static final StreamCodec<ByteBuf, CrossChatRelayPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, CrossChatRelayPayload::senderName,
                    ByteBufCodecs.STRING_UTF8, CrossChatRelayPayload::message,
                    CrossChatRelayPayload::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
