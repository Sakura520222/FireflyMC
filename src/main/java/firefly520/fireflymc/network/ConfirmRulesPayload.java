package firefly520.fireflymc.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import firefly520.fireflymc.FireflyMCMod;

/**
 * 客户端发送给服务端的确认准则包
 * 通知服务端玩家已确认准则，取消无敌状态
 */
public record ConfirmRulesPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ConfirmRulesPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "confirm_rules"));

    public static final StreamCodec<ByteBuf, ConfirmRulesPayload> STREAM_CODEC =
            StreamCodec.unit(new ConfirmRulesPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
