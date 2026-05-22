package firefly520.fireflymc.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import firefly520.fireflymc.FireflyMCMod;

/**
 * 服务端发送给客户端的密码验证提示包
 */
public record PasswordPromptPayload(boolean firstTime, String message, int remainingAttempts) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PasswordPromptPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "password_prompt"));

    public static final StreamCodec<ByteBuf, PasswordPromptPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL,
                    PasswordPromptPayload::firstTime,
                    ByteBufCodecs.STRING_UTF8,
                    PasswordPromptPayload::message,
                    ByteBufCodecs.INT,
                    PasswordPromptPayload::remainingAttempts,
                    PasswordPromptPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
