package firefly520.fireflymc.event.websocket;

import firefly520.fireflymc.ServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket消息认证工具
 * 集中管理密钥验证逻辑，供所有消息处理使用
 */
public class MessageAuthenticator {
    private static final Logger LOGGER = LoggerFactory.getLogger(MessageAuthenticator.class);
    private static final String DEFAULT_KEY = "change-this-key-in-production";

    /**
     * 验证提供的密钥是否与配置的认证密钥匹配
     *
     * @param providedKey 消息中携带的密钥
     * @return true表示验证通过，false表示验证失败
     */
    public static boolean validateKey(String providedKey) {
        String configuredKey = ServerConfig.SERVER.wsAuthKey.get();

        if (configuredKey == null || configuredKey.isEmpty() || configuredKey.equals(DEFAULT_KEY)) {
            LOGGER.error("[FireflyMC] WebSocket认证密钥未配置，拒绝消息");
            return false;
        }

        if (providedKey == null || providedKey.isEmpty()) {
            LOGGER.warn("[FireflyMC] 消息缺少认证密钥");
            return false;
        }

        if (!configuredKey.equals(providedKey)) {
            LOGGER.warn("[FireflyMC] 消息认证失败：密钥不匹配");
            return false;
        }

        return true;
    }

    /**
     * 获取当前配置的认证密钥
     *
     * @return 认证密钥
     */
    public static String getConfiguredKey() {
        return ServerConfig.SERVER.wsAuthKey.get();
    }
}
