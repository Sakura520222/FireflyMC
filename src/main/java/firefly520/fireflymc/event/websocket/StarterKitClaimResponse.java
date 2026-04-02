package firefly520.fireflymc.event.websocket;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 福利包标记响应消息
 * 服务端返回标记是否成功
 */
public class StarterKitClaimResponse {
    private static final Logger LOGGER = LoggerFactory.getLogger(StarterKitClaimResponse.class);
    private static final Gson GSON = new Gson();

    @SerializedName("type")
    private String type;

    @SerializedName("playerUuid")
    private String playerUuid;

    @SerializedName("success")
    private boolean success;

    @SerializedName("key")
    private String key;

    /**
     * 从JSON解析响应消息
     * @param json JSON字符串
     * @return 解析后的响应对象，解析失败返回null
     */
    public static StarterKitClaimResponse fromJson(String json) {
        try {
            return GSON.fromJson(json, StarterKitClaimResponse.class);
        } catch (Exception e) {
            LOGGER.error("[FireflyMC] 解析福利包标记响应失败: {}", json, e);
            return null;
        }
    }

    /**
     * 验证响应是否有效
     * @return true表示响应有效
     */
    public boolean isValid() {
        return "starter_kit_claim_response".equals(type)
            && playerUuid != null;
    }

    public String getPlayerUuid() {
        return playerUuid;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getKey() {
        return key;
    }
}
