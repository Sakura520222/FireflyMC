package firefly520.fireflymc.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import firefly520.fireflymc.FireflyMCMod;

/**
 * 客户端发送给服务端的密码提交包
 */
public record PasswordSubmitPayload(String password) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PasswordSubmitPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "password_submit"));

    public static final StreamCodec<ByteBuf, PasswordSubmitPayload> STREAM_CODEC =
            ByteBufCodecs.STRING_UTF8.map(PasswordSubmitPayload::new, PasswordSubmitPayload::password);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
