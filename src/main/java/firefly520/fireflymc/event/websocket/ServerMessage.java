package firefly520.fireflymc.event.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

/**
 * 服务端推送消息数据模型
 *
 * JSON格式示例:
 * {
 *   "type": "chat",
 *   "message": "消息内容",
 *   "sender": "Server",
 *   "color": "#FFB7C5"
 * }
 */
public class ServerMessage {
    private static final Gson GSON = new Gson();

    @SerializedName("type")
    private final String type;

    @SerializedName("message")
    private final String message;

    @SerializedName("sender")
    private final String sender;

    @SerializedName("color")
    private final String color;

    private ServerMessage(String type, String message, String sender, String color) {
        this.type = type;
        this.message = message;
        this.sender = sender;
        this.color = color;
    }

    /**
     * 从JSON字符串解析消息
     */
    public static ServerMessage fromJson(String json) {
        try {
            return GSON.fromJson(json, ServerMessage.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    /**
     * 创建聊天消息
     */
    public static ServerMessage chat(String message, String sender, String color) {
        return new ServerMessage("chat", message, sender, color);
    }

    /**
     * 检查是否是有效的聊天消息
     */
    public boolean isValidChatMessage() {
        return "chat".equals(type) && message != null && !message.isEmpty();
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    // Getters
    public String getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public String getSender() {
        return sender;
    }

    public String getColor() {
        return color;
    }

    /**
     * 获取发送者名称（默认"Server"）
     */
    public String getSenderOrDefault() {
        return sender != null && !sender.isEmpty() ? sender : "Server";
    }
}
