package firefly520.fireflymc.event.websocket;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

/**
 * 玩家验证请求消息
 * <p>
 * 发送给WebSocket服务端，请求验证玩家是否在白名单中
 * <p>
 * JSON格式示例:
 * <pre>
 * {
 *   "type": "verify_member",
 *   "playerId": "Steve",
 *   "timestamp": 1234567890
 * }
 * </pre>
 */
public class VerificationRequestMessage {
    private static final Gson GSON = new Gson();

    @SerializedName("type")
    private final String type;

    @SerializedName("playerId")
    private final String playerId;

    @SerializedName("timestamp")
    private final long timestamp;

    /**
     * 创建验证请求消息
     *
     * @param playerId 玩家ID（用户名）
     */
    public VerificationRequestMessage(String playerId) {
        this.type = "verify_member";
        this.playerId = playerId;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 转换为JSON字符串
     */
    public String toJson() {
        return GSON.toJson(this);
    }

    // Getters
    public String getType() {
        return type;
    }

    public String getPlayerId() {
        return playerId;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
