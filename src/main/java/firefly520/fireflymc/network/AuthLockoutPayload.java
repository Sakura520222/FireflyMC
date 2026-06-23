package firefly520.fireflymc.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import firefly520.fireflymc.FireflyMCMod;

/**
 * 服务端发送给客户端的密码限流包。
 * <p>
 * 密码错误次数耗尽被踢出时下发，客户端收到后在本机记录限流，
 * 在 lockoutMinutes 分钟内禁止再次进入该服务器。
 */
public record AuthLockoutPayload(int lockoutMinutes) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AuthLockoutPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "auth_lockout"));

    public static final StreamCodec<ByteBuf, AuthLockoutPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.INT,
                    AuthLockoutPayload::lockoutMinutes,
                    AuthLockoutPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
