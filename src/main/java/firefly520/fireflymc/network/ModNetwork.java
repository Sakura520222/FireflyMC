package firefly520.fireflymc.network;

import firefly520.fireflymc.FireflyMCMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络注册类（双端通用）
 * playToClient 的包处理器使用反射延迟加载客户端类，避免服务端加载客户端类
 */
public class ModNetwork {
    public static final String NETWORK_VERSION = "1.0.0";

    public static void registerPayloads(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(FireflyMCMod.MODID)
                .versioned(NETWORK_VERSION);

        // 注册服务端→客户端的握手包
        // 使用反射方式调用客户端处理器，避免服务端加载客户端类
        registrar.playToClient(
                ModHandshakePayload.TYPE,
                ModHandshakePayload.STREAM_CODEC,
                (payload, context) -> handleHandshakeOnClient(payload, context)
        );

        // 注册客户端→服务端的回复包（只有服务端处理）
        registrar.playToServer(
                ModHandshakeReplyPayload.TYPE,
                ModHandshakeReplyPayload.STREAM_CODEC,
                ModPayloadHandler::handleHandshakeReply
        );

        // 注册服务端→客户端的显示准则弹窗包
        // 使用反射方式调用客户端处理器，避免服务端加载客户端类
        registrar.playToClient(
                ShowRulesPayload.TYPE,
                ShowRulesPayload.STREAM_CODEC,
                (payload, context) -> handleShowRulesOnClient(payload, context)
        );

        // 注册客户端→服务端的确认准则包（只有服务端处理）
        registrar.playToServer(
                ConfirmRulesPayload.TYPE,
                ConfirmRulesPayload.STREAM_CODEC,
                ModPayloadHandler::handleConfirmRules
        );
    }

    /**
     * 使用反射调用客户端握手处理器
     * 这样可以避免在类加载时加载 ClientPayloadHandler
     */
    private static void handleHandshakeOnClient(ModHandshakePayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // 使用反射调用 ClientPayloadHandler.handleHandshake
            try {
                Class<?> handlerClass = Class.forName("firefly520.fireflymc.client.ClientPayloadHandler");
                java.lang.reflect.Method method = handlerClass.getDeclaredMethod(
                    "handleHandshake",
                    ModHandshakePayload.class,
                    IPayloadContext.class
                );
                method.invoke(null, payload, context);
            } catch (Exception e) {
                // 忽略错误，理论上不应该发生
            }
        }
    }

    /**
     * 使用反射调用客户端显示准则处理器
     * 这样可以避免在类加载时加载 ClientPayloadHandler
     */
    private static void handleShowRulesOnClient(ShowRulesPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // 使用反射调用 ClientPayloadHandler.handleShowRules
            try {
                Class<?> handlerClass = Class.forName("firefly520.fireflymc.client.ClientPayloadHandler");
                java.lang.reflect.Method method = handlerClass.getDeclaredMethod(
                    "handleShowRules",
                    ShowRulesPayload.class,
                    IPayloadContext.class
                );
                method.invoke(null, payload, context);
            } catch (Exception e) {
                // 忽略错误，理论上不应该发生
            }
        }
    }
}
