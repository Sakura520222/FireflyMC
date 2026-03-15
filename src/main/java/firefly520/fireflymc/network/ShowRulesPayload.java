package firefly520.fireflymc.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import firefly520.fireflymc.FireflyMCMod;

/**
 * 服务端发送给客户端的显示准则弹窗包
 * 携带是否首次加入的信息，由服务端决定显示模式
 */
public record ShowRulesPayload(boolean isFirstJoin) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ShowRulesPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FireflyMCMod.MODID, "show_rules"));

    public static final StreamCodec<ByteBuf, ShowRulesPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL,
                    ShowRulesPayload::isFirstJoin,
                    ShowRulesPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
