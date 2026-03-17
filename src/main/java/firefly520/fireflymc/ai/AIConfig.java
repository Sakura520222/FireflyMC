package firefly520.fireflymc.ai;

import java.util.UUID;

/**
 * AI聊天配置 - 硬编码
 */
public class AIConfig {
    // API配置
    public static final String API_URL = "https://api.xiaomimimo.com/v1";
    public static final String API_KEY = "sk-cverxbbl9ru7icl4ogqqu1dvgfd90oq87rxs6gjbzluynme7";
    public static final String MODEL = "mimo-v2-flash";

    // 显示配置
    public static final String AI_NAME = "§d小樱§r";
    public static final String AI_NAME_PLAIN = "小樱";
    public static final UUID AI_UUID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    // 行为配置
    public static final int MAX_HISTORY_SIZE = 30;
    public static final int MAX_RESPONSE_LENGTH = 200;
    public static final int COOLDOWN_SECONDS = 5;
    public static final boolean BROADCAST_TO_ALL = true;
    public static final boolean ENABLED = true;

    private AIConfig() {
        // 防止实例化
    }
}
