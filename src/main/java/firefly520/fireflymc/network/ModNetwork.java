package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络注册类
 */
public class ModNetwork {
    public static final String NETWORK_VERSION = "1.0.0";

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

        // 注册服务端→客户端的显示准则弹窗包（只有客户端处理）
        registrar.playToClient(
                ShowRulesPayload.TYPE,
                ShowRulesPayload.STREAM_CODEC,
                ModPayloadHandler::handleShowRules
        );

        // 注册客户端→服务端的确认准则包（只有服务端处理）
        registrar.playToServer(
                ConfirmRulesPayload.TYPE,
                ConfirmRulesPayload.STREAM_CODEC,
                ModPayloadHandler::handleConfirmRules
        );
    }
}
