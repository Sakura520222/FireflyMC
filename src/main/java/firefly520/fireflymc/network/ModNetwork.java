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

        // 注册服务端→客户端的密码提示包
        registrar.playToClient(
                PasswordPromptPayload.TYPE,
                PasswordPromptPayload.STREAM_CODEC,
                (payload, context) -> handlePasswordPromptOnClient(payload, context)
        );

        // 注册客户端→服务端的密码提交包
        registrar.playToServer(
                PasswordSubmitPayload.TYPE,
                PasswordSubmitPayload.STREAM_CODEC,
                ModPayloadHandler::handlePasswordSubmit
        );

        // 注册服务端→客户端的称号同步包
        registrar.playToClient(
                TitleSyncPayload.TYPE,
                TitleSyncPayload.STREAM_CODEC,
                (payload, context) -> handleTitleSyncOnClient(payload, context)
        );

        // 注册服务端→客户端的密码限流包
        registrar.playToClient(
                AuthLockoutPayload.TYPE,
                AuthLockoutPayload.STREAM_CODEC,
                (payload, context) -> handleAuthLockoutOnClient(payload, context)
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
     */
    private static void handleShowRulesOnClient(ShowRulesPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            try {
                Class<?> handlerClass = Class.forName("firefly520.fireflymc.client.ClientPayloadHandler");
                java.lang.reflect.Method method = handlerClass.getDeclaredMethod(
                    "handleShowRules",
                    ShowRulesPayload.class,
                    IPayloadContext.class
                );
                method.invoke(null, payload, context);
            } catch (Exception e) {
                // 忽略错误
            }
        }
    }

    /**
     * 使用反射调用客户端密码提示处理器
     */
    private static void handlePasswordPromptOnClient(PasswordPromptPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            try {
                Class<?> handlerClass = Class.forName("firefly520.fireflymc.client.ClientPayloadHandler");
                java.lang.reflect.Method method = handlerClass.getDeclaredMethod(
                    "handlePasswordPrompt",
                    PasswordPromptPayload.class,
                    IPayloadContext.class
                );
                method.invoke(null, payload, context);
            } catch (Exception e) {
                // 忽略错误
            }
        }
    }

    /**
     * 使用反射调用客户端称号同步处理器
     */
    private static void handleTitleSyncOnClient(TitleSyncPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            try {
                Class<?> handlerClass = Class.forName("firefly520.fireflymc.client.ClientPayloadHandler");
                java.lang.reflect.Method method = handlerClass.getDeclaredMethod(
                    "handleTitleSync",
                    TitleSyncPayload.class,
                    IPayloadContext.class
                );
                method.invoke(null, payload, context);
            } catch (Exception e) {
                // 忽略错误
            }
        }
    }

    /**
     * 使用反射调用客户端密码限流处理器
     */
    private static void handleAuthLockoutOnClient(AuthLockoutPayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            try {
                Class<?> handlerClass = Class.forName("firefly520.fireflymc.client.ClientPayloadHandler");
                java.lang.reflect.Method method = handlerClass.getDeclaredMethod(
                    "handleAuthLockout",
                    AuthLockoutPayload.class,
                    IPayloadContext.class
                );
                method.invoke(null, payload, context);
            } catch (Exception e) {
                private static final String NETWORK_VERSION = "1.0.0";
                private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ModNetwork.class);
            }
        }
    }
}
