package firefly520.fireflymc.event.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

/**
 * 玩家验证响应消息
 * <p>
 * WebSocket服务端返回的验证结果
 * <p>
 * JSON格式示例:
 * <pre>
 * {
 *   "type": "verify_response",
 *   "playerId": "Steve",
 *   "verified": true,
 *   "message": "验证成功"
 * }
 * </pre>
 */
public class VerificationResponseMessage {
    private static final Gson GSON = new Gson();

    @SerializedName("type")
    private final String type;

    @SerializedName("playerId")
    private final String playerId;

    @SerializedName("verified")
    private final boolean verified;

    @SerializedName("message")
    private final String message;

    private VerificationResponseMessage(String type, String playerId, boolean verified, String message) {
        this.type = type;
        this.playerId = playerId;
        this.verified = verified;
        this.message = message;
    }

    /**
     * 从JSON字符串解析消息
     *
     * @param json JSON字符串
     * @return 解析后的消息对象，如果解析失败或类型不匹配则返回null
     */
    public static VerificationResponseMessage fromJson(String json) {
        try {
            VerificationResponseMessage msg = GSON.fromJson(json, VerificationResponseMessage.class);
            if (msg != null && "verify_response".equals(msg.type)) {
                return msg;
            }
        } catch (JsonSyntaxException e) {
            // 静默失败，返回null
        }
        return null;
    }

    /**
     * 检查是否是有效的验证响应消息
     */
    public boolean isValid() {
        return "verify_response".equals(type)
                && playerId != null
                && !playerId.isEmpty();
    }

    // Getters
    public String getType() {
        return type;
    }

    public String getPlayerId() {
        return playerId;
    }

    public boolean isVerified() {
        return verified;
    }

    public String getMessage() {
        return message;
    }
}
