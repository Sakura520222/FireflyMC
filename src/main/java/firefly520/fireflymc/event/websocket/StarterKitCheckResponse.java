package firefly520.fireflymc.event.websocket;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 福利包检查响应消息
 * 服务端返回玩家是否已领取福利包
 */
public class StarterKitCheckResponse {
    private static final Logger LOGGER = LoggerFactory.getLogger(StarterKitCheckResponse.class);
    private static final Gson GSON = new Gson();

    @SerializedName("type")
    private String type;

    @SerializedName("requestId")
    private String requestId;

    @SerializedName("playerUuid")
    private String playerUuid;

    @SerializedName("claimed")
    private boolean claimed;

    /**
     * 从JSON解析响应消息
     * @param json JSON字符串
     * @return 解析后的响应对象，解析失败返回null
     */
    public static StarterKitCheckResponse fromJson(String json) {
        try {
            return GSON.fromJson(json, StarterKitCheckResponse.class);
        } catch (Exception e) {
            LOGGER.error("[FireflyMC] 解析福利包检查响应失败: {}", json, e);
            return null;
        }
    }

    /**
     * 验证响应是否有效
     * @return true表示响应有效
     */
    public boolean isValid() {
        return "starter_kit_check_response".equals(type)
            && requestId != null
            && playerUuid != null;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getPlayerUuid() {
        return playerUuid;
    }

    public boolean isClaimed() {
        return claimed;
    }
}
