package firefly520.fireflymc.event.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

/**
 * 玩家列表查询请求消息
 * <p>
 * WebSocket服务端发起的玩家列表查询请求
 * <p>
 * JSON格式示例:
 * <pre>
 * {
 *   "type": "player_list_query",
 *   "requestId": "req-123",
 *   "timestamp": 1234567890
 * }
 * </pre>
 */
public class PlayerListQueryRequestMessage {
    private static final Gson GSON = new Gson();

    @SerializedName("type")
    private final String type;

    @SerializedName("requestId")
    private final String requestId;

    @SerializedName("timestamp")
    private final long timestamp;

    private PlayerListQueryRequestMessage(String requestId) {
        this.type = "player_list_query";
        this.requestId = requestId;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 从JSON字符串解析消息
     *
     * @param json JSON字符串
     * @return 解析后的消息对象，如果解析失败或类型不匹配则返回null
     */
    public static PlayerListQueryRequestMessage fromJson(String json) {
        try {
            PlayerListQueryRequestMessage msg = GSON.fromJson(json, PlayerListQueryRequestMessage.class);
            if (msg != null && "player_list_query".equals(msg.type)) {
                return msg;
            }
        } catch (JsonSyntaxException e) {
            // 静默失败，返回null
        }
        return null;
    }

    /**
     * 检查是否是有效的玩家列表查询请求消息
     */
    public boolean isValid() {
        return "player_list_query".equals(type);
    }

    public String getRequestId() {
        return requestId;
    }

    public String getType() {
        return type;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
