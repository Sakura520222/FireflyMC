package firefly520.fireflymc.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import firefly520.fireflymc.FireflyMCMod;

/**
 * 服务端发送给客户端的握手检测包
 */
public record ModHandshakePayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ModHandshakePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "handshake"));

    public static final StreamCodec<ByteBuf, ModHandshakePayload> STREAM_CODEC =
            StreamCodec.unit(new ModHandshakePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
