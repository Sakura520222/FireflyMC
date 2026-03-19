package firefly520.fireflymc.ai;

import firefly520.fireflymc.ServerConfig;

import java.util.UUID;

/**
 * AI聊天配置 - 从服务端配置读取
 */
public class AIConfig {
    // API配置
    public static String getApiUrl() {
        return ServerConfig.SERVER.aiApiUrl.get();
    }

    public static String getApiKey() {
        return ServerConfig.SERVER.aiApiKey.get();
    }

    public static String getModel() {
        return ServerConfig.SERVER.aiModel.get();
    }

    // 显示配置
    public static String getAiName() {
        return ServerConfig.SERVER.aiName.get();
    }

    public static String getAiNamePlain() {
        return ServerConfig.SERVER.aiNamePlain.get();
    }

    public static UUID getAiUuid() {
        try {
            return UUID.fromString(ServerConfig.SERVER.aiUuid.get());
        } catch (IllegalArgumentException e) {
            // 如果配置的UUID格式无效，使用默认值
            return UUID.fromString("00000000-0000-4000-8000-000000000001");
        }
    }

    // 行为配置
    public static int getMaxHistorySize() {
        return ServerConfig.SERVER.aiMaxHistorySize.get();
    }

    public static int getMaxResponseLength() {
        return ServerConfig.SERVER.aiMaxResponseLength.get();
    }

    public static int getCooldownSeconds() {
        return ServerConfig.SERVER.aiCooldownSeconds.get();
    }

    public static boolean getBroadcastToAll() {
        return ServerConfig.SERVER.aiBroadcastToAll.get();
    }

    public static boolean getEnabled() {
        return ServerConfig.SERVER.aiEnabled.get();
    }

    // ========== 兼容性常量（已废弃，请使用getter方法） ==========

    /**
     * @deprecated 使用 {@link #getApiUrl()} 替代
     */
    @Deprecated
    public static final String API_URL = getApiUrl();

    /**
     * @deprecated 使用 {@link #getApiKey()} 替代
     */
    @Deprecated
    public static final String API_KEY = getApiKey();

    /**
     * @deprecated 使用 {@link #getModel()} 替代
     */
    @Deprecated
    public static final String MODEL = getModel();

    /**
     * @deprecated 使用 {@link #getAiName()} 替代
     */
    @Deprecated
    public static final String AI_NAME = getAiName();

    /**
     * @deprecated 使用 {@link #getAiNamePlain()} 替代
     */
    @Deprecated
    public static final String AI_NAME_PLAIN = getAiNamePlain();

    /**
     * @deprecated 使用 {@link #getAiUuid()} 替代
     */
    @Deprecated
    public static final UUID AI_UUID = getAiUuid();

    /**
     * @deprecated 使用 {@link #getMaxHistorySize()} 替代
     */
    @Deprecated
    public static final int MAX_HISTORY_SIZE = getMaxHistorySize();

    /**
     * @deprecated 使用 {@link #getMaxResponseLength()} 替代
     */
    @Deprecated
    public static final int MAX_RESPONSE_LENGTH = getMaxResponseLength();

    /**
     * @deprecated 使用 {@link #getCooldownSeconds()} 替代
     */
    @Deprecated
    public static final int COOLDOWN_SECONDS = getCooldownSeconds();

    /**
     * @deprecated 使用 {@link #getBroadcastToAll()} 替代
     */
    @Deprecated
    public static final boolean BROADCAST_TO_ALL = getBroadcastToAll();

    /**
     * @deprecated 使用 {@link #getEnabled()} 替代
     */
    @Deprecated
    public static final boolean ENABLED = getEnabled();

    private AIConfig() {
        // 防止实例化
    }
}
