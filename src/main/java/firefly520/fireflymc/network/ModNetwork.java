package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络注册类
 */
@EventBusSubscriber(modid = FireflyMCMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ModNetwork {
    public static final String NETWORK_VERSION = "1.0.0";

    @SubscribeEvent
    public static void registerPayloads(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(FireflyMCMod.MODID)
                .versioned(NETWORK_VERSION);

        // 注册服务端→客户端的握手包（只有客户端处理）
        registrar.playToClient(
                ModHandshakePayload.TYPE,
                ModHandshakePayload.STREAM_CODEC,
                ModPayloadHandler::handleHandshake
        );

        // 注册客户端→服务端的回复包（只有服务端处理）
        registrar.playToServer(
                ModHandshakeReplyPayload.TYPE,
                ModHandshakeReplyPayload.STREAM_CODEC,
                ModPayloadHandler::handleHandshakeReply
        );
    }
}
