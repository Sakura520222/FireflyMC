package firefly520.fireflymc.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import firefly520.fireflymc.FireflyMCMod;

/**
 * 客户端回复给服务端的握手确认包
 */
public record ModHandshakeReplyPayload(String modVersion) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ModHandshakeReplyPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "handshake_reply"));

    public static final StreamCodec<ByteBuf, ModHandshakeReplyPayload> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.map(ModHandshakeReplyPayload::new, ModHandshakeReplyPayload::modVersion);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
